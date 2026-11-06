package com.lowdragmc.minebox3d.physics;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** <b>How worlds are shared between threads</b> (design doc 03 §4.2): bound to an owner, or used by one thread at a time. */
class ThreadModeTest {

    private static PhysicsWorld exclusive() {
        return PhysicsWorld.create(new WorldDef().gravity(0, -10, 0).threadMode(ThreadMode.EXCLUSIVE));
    }

    private static Throwable onAnotherThread(Runnable action) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        thread.start();
        thread.join();
        return thrown.get();
    }

    @Test
    void anExclusiveWorldIsUsedBetweenUseAndItsClose() throws InterruptedException {
        PhysicsWorld world = exclusive();
        assertThrows(WrongThreadException.class, () -> world.createBody(new BodyDef()), "not held: nobody may call in");
        assertNull(world.owner());
        Body body;
        try (PhysicsWorld.Use use = world.use()) {
            assertSame(Thread.currentThread(), world.owner());
            body = world.createBody(new BodyDef().type(BodyType.DYNAMIC));
            body.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.5f));
            // nested uses by the holder are fine
            try (PhysicsWorld.Use again = world.use()) {
                world.step(1 / 60f, 4);
            }
            world.step(1 / 60f, 4);
            // while held here, another thread is refused at once, not kept waiting
            assertInstanceOf(WrongThreadException.class, onAnotherThread(world::use));
            assertInstanceOf(WrongThreadException.class, onAnotherThread(() -> world.step(1 / 60f, 4)));
            assertNull(onAnotherThreadTryUse(world), "tryUse says no instead");
        }
        assertNull(world.owner(), "let go");
        assertThrows(WrongThreadException.class, () -> body.setLinearVelocity(1, 0, 0));

        // free again: any thread may take it
        Body other = body;
        assertNull(onAnotherThread(() -> {
            try (PhysicsWorld.Use use = world.use()) {
                other.setLinearVelocity(0, 5, 0);
                world.step(1 / 60f, 4);
            }
        }));
        assertThrows(IllegalStateException.class, () -> world.transferTo(Thread.currentThread()), "nothing to hand on");
        try (PhysicsWorld.Use use = world.use()) {
            assertTrue(body.position(new Vector3d()).y > 0, "the other thread's velocity took");
            world.close();
        }
    }

    private static PhysicsWorld.Use onAnotherThreadTryUse(PhysicsWorld world) throws InterruptedException {
        AtomicReference<PhysicsWorld.Use> result = new AtomicReference<>();
        Thread thread = new Thread(() -> result.set(world.tryUse()));
        thread.start();
        thread.join();
        return result.get();
    }

    @Test
    void shapesJointsAndGridsRefuseAThreadTheirWorldDoesNotLetIn() throws InterruptedException {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef())) {
            Body a = world.createBody(new BodyDef().type(BodyType.DYNAMIC));
            Shape shape = a.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.5f));
            Body b = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(1, 0, 0));
            b.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.5f));
            Joint joint = world.createJoint(new DistanceJointDef().bodies(a, b).length(1));
            VoxelGrid grid = world.createVoxelGrid(new ShapeDef());
            List<Runnable> calls = List.of(
                    () -> shape.setFriction(0.5f),
                    () -> shape.bounds(new float[6]),
                    () -> shape.castShape(Geometry.Box.of(0.1f, 0.1f, 0.1f), 0, 2, 0, new Quaternionf(), 0, -2, 0, true,
                            new CastResult()),
                    () -> joint.setCollideConnected(true),
                    () -> joint.destroy(false),
                    () -> grid.setCells(new int[]{0, 0, 0, 0}, 1),
                    grid::close);
            for (int i = 0; i < calls.size(); i++) {
                assertInstanceOf(WrongThreadException.class, onAnotherThread(calls.get(i)), "call " + i);
            }
            assertTrue(joint.isValid() && grid.isOpen(), "and nothing done");
        }
    }

    @Test
    void anOwnedWorldTakesUseFromItsOwnerOnly() throws InterruptedException {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef())) {
            try (PhysicsWorld.Use use = world.use()) {
                world.createBody(new BodyDef());
            }
            // closing the use let nothing go: the owner still owns it
            world.createBody(new BodyDef());
            assertNotNull(world.tryUse());
            assertInstanceOf(WrongThreadException.class, onAnotherThread(world::use));
            assertNull(onAnotherThreadTryUse(world));
        }
    }

    /**
     * The case the mode is for: many small worlds stepped on a pool, each frame on whichever thread is free (EntityStudio's
     * Rigid Body nodes, doc 56 §3.4).
     */
    @Test
    void smallWorldsStepOnAPoolFrameAfterFrame() throws Exception {
        int worlds = 8, frames = 120;
        List<PhysicsWorld> all = new ArrayList<>();
        List<Body> balls = new ArrayList<>();
        for (int i = 0; i < worlds; i++) {
            PhysicsWorld world = exclusive();
            try (PhysicsWorld.Use use = world.use()) {
                world.createBody(new BodyDef()).createShape(new ShapeDef(), Geometry.Box.of(5, 0.5f, 5));
                Body ball = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, 3, 0));
                ball.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.25f));
                balls.add(ball);
            }
            all.add(world);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int frame = 0; frame < frames; frame++) {
                List<Future<?>> steps = new ArrayList<>();
                for (PhysicsWorld world : all) {
                    steps.add(pool.submit(() -> {
                        try (PhysicsWorld.Use use = world.use()) {
                            world.beginInterpolationFrame();
                            world.step(1 / 60f, 4);
                        }
                    }));
                }
                for (Future<?> step : steps) {
                    step.get(10, TimeUnit.SECONDS);
                }
            }
        } finally {
            pool.shutdown();
        }
        for (int i = 0; i < worlds; i++) {
            try (PhysicsWorld.Use use = all.get(i).use()) {
                assertEquals(0.75, balls.get(i).position(new Vector3d()).y, 0.02, "world " + i + " stepped every frame");
                all.get(i).close();
            }
        }
    }

    @Test
    void twoThreadsRacingForAnExclusiveWorldNeverBothGetIn() throws Exception {
        PhysicsWorld world = exclusive();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        int[] inside = {0};
        int[] maxInside = {0};
        int[] refused = new int[threads];
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> racers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int index = t;
            Thread racer = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2000; i++) {
                        PhysicsWorld.Use use = world.tryUse();
                        if (use == null) {
                            refused[index]++;
                            continue;
                        }
                        try (use) {
                            synchronized (inside) {
                                maxInside[0] = Math.max(maxInside[0], ++inside[0]);
                            }
                            world.awakeBodyCount();
                            synchronized (inside) {
                                inside[0]--;
                            }
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            racer.start();
            racers.add(racer);
        }
        start.countDown();
        for (Thread racer : racers) {
            racer.join();
        }
        assertNull(failure.get(), "no racer failed");
        assertEquals(1, maxInside[0], "one thread at a time");
        assertTrue(java.util.Arrays.stream(refused).sum() > 0, "they did meet");
        try (PhysicsWorld.Use use = world.use()) {
            world.close();
        }
    }
}
