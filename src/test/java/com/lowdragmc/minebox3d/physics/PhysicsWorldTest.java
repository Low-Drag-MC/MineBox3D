package com.lowdragmc.minebox3d.physics;

import static org.junit.jupiter.api.Assertions.*;

import com.lowdragmc.minebox3d.internal.B3;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class PhysicsWorldTest {

    private static PhysicsWorld world() {
        return PhysicsWorld.create(new WorldDef().gravity(0, -10, 0));
    }

    private static Body ground(PhysicsWorld world) {
        Body ground = world.createBody(new BodyDef());
        ground.createShape(new ShapeDef(), Geometry.Box.of(20, 0.5f, 20));
        return ground;
    }

    @Test
    void aBodyReportsWhereItLandedAndInterpolatesAcrossAFrame() {
        try (PhysicsWorld world = world()) {
            ground(world);
            Body ball = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, 3, 0));
            ball.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.25f));
            Vector3d before = ball.position(new Vector3d());

            world.beginInterpolationFrame();
            world.step(1 / 60f, 4);
            world.step(1 / 60f, 4);
            Vector3d after = ball.position(new Vector3d());
            assertTrue(after.y < before.y, "it fell");
            // the Java copy (from the move events) against Box3D's own
            double[] native7 = new double[7];
            B3.bodyGetTransform(ball.id(), native7);
            assertEquals(native7[1], after.y, 1e-9, "position() is where Box3D has the body");
            assertEquals(before.y, ball.renderPosition(0, new Vector3d()).y, 1e-9, "partial 0 = start of frame");
            assertEquals(after.y, ball.renderPosition(1, new Vector3d()).y, 1e-9, "partial 1 = now");
            assertEquals((before.y + after.y) / 2, ball.renderPosition(0.5f, new Vector3d()).y, 1e-9);
            assertTrue(world.movedBodies().contains(ball));

            for (int i = 0; i < 300; i++) {
                world.beginInterpolationFrame();
                world.step(1 / 60f, 4);
            }
            assertEquals(0.75, ball.position(new Vector3d()).y, 0.02, "rests on the ground");
            assertFalse(ball.isAwake(), "and went to sleep");
            world.beginInterpolationFrame();
            world.step(1 / 60f, 4);
            assertFalse(world.movedBodies().contains(ball), "a sleeping body does not move");
            assertEquals(
                    ball.renderPosition(0, new Vector3d()).y,
                    ball.renderPosition(1, new Vector3d()).y,
                    1e-12,
                    "a body that did not move this frame draws still");
        }
    }

    @Test
    void aCorrectionAfterAStepIsDrawnAsMotionATeleportIsNot() {
        try (PhysicsWorld world = world()) {
            Body box = world.createBody(
                    new BodyDef().type(BodyType.DYNAMIC).position(0, 3, 0).gravityScale(0));
            box.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            world.beginInterpolationFrame();
            world.step(1 / 60f, 4);
            box.correctTransform(1, 3, 0, new Quaternionf(), false);
            assertEquals(0, box.renderPosition(0, new Vector3d()).x, 1e-9, "drawn from where the frame began");
            assertEquals(1, box.renderPosition(1, new Vector3d()).x, 1e-9, "to where it was put");
            assertEquals(1, box.position(new Vector3d()).x, 1e-9, "and it is there");
            box.setTransform(2, 3, 0, new Quaternionf(), false);
            assertEquals(2, box.renderPosition(0, new Vector3d()).x, 1e-9, "a teleport is not drawn on the way");
        }
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
    void aThreadThatDoesNotOwnTheWorldIsAlwaysRefused() throws InterruptedException {
        try (PhysicsWorld world = world()) {
            Body box = world.createBody(new BodyDef().type(BodyType.DYNAMIC));
            box.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.5f));
            // the world is idle: refused all the same, not only when two threads happen to meet
            assertInstanceOf(WrongThreadException.class, onAnotherThread(() -> box.setLinearVelocity(1, 0, 0)));
            assertInstanceOf(WrongThreadException.class, onAnotherThread(() -> world.step(1 / 60f, 4)));
            assertInstanceOf(
                    WrongThreadException.class,
                    onAnotherThread(() -> world.castRayClosest(0, 5, 0, 0, -10, 0, QueryFilter.ALL)));
            assertInstanceOf(WrongThreadException.class, onAnotherThread(world::use));
            assertInstanceOf(WrongThreadException.class, onAnotherThread(world::close));
            assertTrue(world.isOpen(), "a refused close closed nothing");
            box.setLinearVelocity(1, 0, 0);
        }
    }

    @Test
    void theOwnerCanHandTheWorldToAnotherThread() throws InterruptedException {
        PhysicsWorld world = world();
        Body box = world.createBody(new BodyDef().type(BodyType.DYNAMIC));
        CountDownLatch handed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread next = new Thread(() -> {
            try {
                handed.await();
                box.setLinearVelocity(0, 3, 0);
                world.step(1 / 60f, 4);
                world.close();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        next.start();
        world.transferTo(next);
        assertThrows(WrongThreadException.class, () -> box.setLinearVelocity(1, 0, 0), "the old owner is out");
        handed.countDown();
        next.join();
        assertNull(failure.get());
        assertFalse(world.isOpen());
    }

    @Test
    void callsInsideForEachBodyMayCreateAndDestroyBodies() {
        try (PhysicsWorld world = world()) {
            for (int i = 0; i < 10; i++) {
                world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(i, 0, 0));
            }
            int[] visited = {0};
            world.forEachBody(body -> {
                visited[0]++;
                body.setLinearVelocity(1, 0, 0); // back into the world from inside it
                body.destroy();
                world.createBody(new BodyDef().position(0, -5, 0));
            });
            assertEquals(10, visited[0], "every body there was when it started, once");
            assertEquals(10, world.bodyCount(), "ten destroyed, ten made");
        }
    }

    @Test
    void differentWorldsStepOnDifferentThreadsAtOnce() throws InterruptedException {
        int n = 4;
        Thread[] threads = new Thread[n];
        AtomicReference<Throwable> failure = new AtomicReference<>();
        double[] rested = new double[n];
        for (int t = 0; t < n; t++) {
            int index = t;
            threads[t] = new Thread(() -> {
                try (PhysicsWorld world = world()) {
                    ground(world);
                    Body[] boxes = new Body[20];
                    for (int i = 0; i < boxes.length; i++) {
                        boxes[i] = world.createBody(
                                new BodyDef().type(BodyType.DYNAMIC).position(0, 1 + i * 0.6, 0));
                        boxes[i].createShape(new ShapeDef(), Geometry.Box.of(0.25f, 0.25f, 0.25f));
                    }
                    for (int s = 0; s < 400; s++) {
                        world.beginInterpolationFrame();
                        world.step(1 / 60f, 4);
                    }
                    rested[index] = boxes[0].position(new Vector3d()).y;
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertNull(failure.get());
        for (double y : rested) {
            assertEquals(0.75, y, 0.05, "the bottom box of each stack rests on its own ground");
        }
    }

    @Test
    void meshDataLivesUntilItsLastShapeIsGone() {
        float[] vertices = {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1};
        int[] indices = {0, 3, 2, 0, 2, 1};
        try (PhysicsWorld world = world()) {
            Body floor = world.createBody(new BodyDef());
            // grow the world's own arrays first, so what is measured below is the mesh alone
            floor.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 1)).destroy(false);
            long baseline = B3.getByteCount();
            MeshData mesh = MeshData.create(vertices, indices, true);
            Shape shape = floor.createShape(new ShapeDef(), Geometry.Mesh.of(mesh));
            long withMesh = B3.getByteCount();
            assertTrue(withMesh > baseline);
            mesh.close();
            assertEquals(withMesh, B3.getByteCount(), "the shape still uses it: closing only marks it");
            shape.destroy(false);
            assertEquals(baseline, B3.getByteCount(), "the last user gone, the mesh is freed, all of it");
        }
    }

    @Test
    void aClosedMeshCannotComeBackForANewShape() {
        float[] vertices = {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1};
        int[] indices = {0, 3, 2, 0, 2, 1};
        MeshData mesh = MeshData.create(vertices, indices, true);
        mesh.close(); // no shapes: freed now
        try (PhysicsWorld world = world()) {
            Body floor = world.createBody(new BodyDef());
            assertThrows(IllegalStateException.class, () -> floor.createShape(new ShapeDef(), Geometry.Mesh.of(mesh)));
            // the first refusal must not have counted it back up: a second try reaching Box3D would hand it freed
            // memory
            assertThrows(IllegalStateException.class, () -> floor.createShape(new ShapeDef(), Geometry.Mesh.of(mesh)));
        }
    }

    @Test
    void closingTheWorldInvalidatesItsBodies() {
        PhysicsWorld world = world();
        Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC));
        Shape shape = body.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 1));
        world.close();
        assertFalse(body.isValid());
        assertFalse(shape.isValid());
        assertThrows(IllegalStateException.class, () -> body.setLinearVelocity(0, 1, 0));
    }

    @Test
    void aRayFindsTheClosestShape() {
        try (PhysicsWorld world = world()) {
            ground(world);
            Body box = world.createBody(new BodyDef().position(0, 3, 0));
            Shape top = box.createShape(new ShapeDef(), Geometry.Box.of(1, 1, 1));
            CastHit hit = world.castRayClosest(0, 10, 0, 0, -20, 0, QueryFilter.ALL);
            assertNotNull(hit);
            assertSame(top, hit.shape(), "the box above the ground, not the ground");
            assertEquals(4, hit.y(), 1e-4);
            assertEquals(1, hit.normalY(), 1e-4);
        }
    }

    @Test
    void aKinematicBodyDrivenToATargetPushesWhatIsInTheWay() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, 0, 0))) {
            Body pusher =
                    world.createBody(new BodyDef().type(BodyType.KINEMATIC).position(-2, 0, 0));
            pusher.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            Body crate = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, 0, 0));
            crate.createShape(new ShapeDef(), Geometry.Box.of(0.25f, 0.25f, 0.25f));
            for (int tick = 0; tick < 20; tick++) {
                world.beginInterpolationFrame();
                pusher.setTargetTransform(-2 + 0.2 * (tick + 1), 0, 0, new Quaternionf(), 1 / 20f);
                for (int s = 0; s < 3; s++) {
                    world.step(1 / 60f, 4);
                }
            }
            assertEquals(2.0, pusher.position(new Vector3d()).x, 0.02, "the kinematic body reached its target");
            assertTrue(
                    crate.position(new Vector3d()).x > 2.5,
                    "and shoved the crate ahead of it: " + crate.position(new Vector3d()));
        }
    }
}
