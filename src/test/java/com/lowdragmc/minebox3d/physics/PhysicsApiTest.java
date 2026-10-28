package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.jni.B3;
import com.lowdragmc.minebox3d.jni.Box3DException;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>The physics API's contract</b>: what each method does that can be seen, what it refuses, what is left after
 * closing, and that nothing leaks. Without Minecraft.
 */
class PhysicsApiTest {

    private static PhysicsWorld world() {
        return PhysicsWorld.create(new WorldDef().gravity(0, -10, 0));
    }

    private static Body ground(PhysicsWorld world) {
        Body ground = world.createBody(new BodyDef());
        ground.createShape(new ShapeDef(), Geometry.Box.of(20, 0.5f, 20));
        return ground;
    }

    private static Body ball(PhysicsWorld world, double y) {
        Body ball = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, y, 0));
        ball.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.25f));
        return ball;
    }

    private static void steps(PhysicsWorld world, int count) {
        for (int i = 0; i < count; i++) {
            world.beginInterpolationFrame();
            world.step(1 / 60f, 4);
        }
    }

    // ---- refusing what would poison a world ------------------------------------------------------

    @Test
    void numbersBox3DWouldStoreAndSpreadAreRefusedBeforeTheyReachIt() {
        try (PhysicsWorld world = world()) {
            ground(world);
            Body ball = ball(world, 2);
            float nan = Float.NaN;
            List<Executable> refused = List.of(
                    () -> ball.setLinearVelocity(nan, 0, 0),
                    () -> ball.setAngularVelocity(0, Float.POSITIVE_INFINITY, 0),
                    () -> ball.applyLinearImpulse(0, nan, 0, 0, 0, 0),
                    () -> ball.applyLinearImpulse(0, 1, 0, Double.NaN, 0, 0),
                    () -> ball.applyLinearImpulseToCenter(nan, 0, 0),
                    () -> ball.applyForceToCenter(0, 0, nan),
                    () -> ball.setGravityScale(nan),
                    () -> ball.setTransform(Double.NaN, 0, 0, new Quaternionf()),
                    () -> ball.setTransform(0, 0, 0, new Quaternionf(0, 0, 0, 0)),
                    () -> ball.setTargetTransform(0, 0, 0, new Quaternionf(), 0),
                    () -> world.createBody(new BodyDef().position(Double.NaN, 0, 0)),
                    () -> world.createBody(new BodyDef().rotation(0, 0, 0, 2)),
                    () -> world.createBody(new BodyDef().linearVelocity(0, nan, 0)),
                    () -> ball.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0)),
                    () -> ball.createShape(new ShapeDef(), new Geometry.Sphere(nan, 0, 0, 1)),
                    () -> ball.createShape(new ShapeDef(), new Geometry.Capsule(0, 0, 0, 0, 1, 0, -1)),
                    () -> ball.createShape(new ShapeDef(), Geometry.Box.of(0, 1, 1)),
                    () -> ball.createShape(new ShapeDef().density(-1), new Geometry.Sphere(0, 0, 0, 1)),
                    () -> ball.createShape(new ShapeDef().friction(-0.1f), new Geometry.Sphere(0, 0, 0, 1)),
                    () -> ball.shapes().getFirst().setFriction(nan),
                    () -> world.setGravity(0, nan, 0),
                    () -> world.step(-1, 4),
                    () -> world.step(1 / 60f, 0),
                    () -> world.castRayClosest(Double.NaN, 0, 0, 0, -1, 0, -1L, -1L),
                    () -> world.castShapeClosest(0, 0, 0, new float[]{0, nan, 0}, 1, 0.1f, 0, -1, 0, -1L, -1L),
                    () -> world.overlapShape(0, 0, 0, new float[3], 2, 0.1f, -1L, -1L, new long[4]),
                    () -> world.collideMover(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, -1L, -1L, new float[7], new long[1], new int[1]));
            for (int i = 0; i < refused.size(); i++) {
                assertThrows(IllegalArgumentException.class, refused.get(i), "call " + i);
            }
            // nothing got in: the world still steps, and every body is somewhere real
            steps(world, 120);
            Vector3d p = ball.position(new Vector3d());
            assertTrue(Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z), "at " + p);
            assertEquals(0.75, p.y, 0.02, "and it landed");
            assertEquals(1, ball.shapes().size(), "no refused shape was made");
            assertFalse(world.isFaulted());
        }
    }

    @Test
    void aBodyOrShapeBox3DMakesDespiteAFailedCheckIsNotLeftBehind() {
        // straight through the binding, past the Java checks: Box3D asserts, and then creates the object anyway
        try (PhysicsWorld world = world()) {
            int[] counters = new int[6];
            Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC));
            world.counters(counters);
            int bodies = counters[0], shapes = counters[1];

            ShapeDef negativeFriction = new ShapeDef();
            negativeFriction.baseMaterial.friction = -1;
            assertThrows(Box3DException.class, () -> B3.createSphereShape(body.id(), negativeFriction, 0, 0, 0, 1));
            BodyDef negativeDamping = new BodyDef().type(BodyType.DYNAMIC);
            negativeDamping.linearDamping = -1;
            assertThrows(Box3DException.class, () -> B3.createBody(world.id(), negativeDamping));

            world.counters(counters);
            assertEquals(bodies, counters[0], "no orphan body");
            assertEquals(shapes, counters[1], "no orphan shape");
        }
    }

    // ---- after closing --------------------------------------------------------------------------

    @Test
    void aClosedWorldAndEverythingInItRefuseToBeUsed() {
        PhysicsWorld world = world();
        Body body = ball(world, 2);
        Shape shape = body.shapes().getFirst();
        VoxelGrid grid = world.createVoxelGrid(new ShapeDef());
        world.close();
        world.close(); // a second close is nothing

        List<Executable> refused = List.of(
                () -> world.createBody(new BodyDef()),
                () -> world.step(1 / 60f, 4),
                () -> world.setGravity(0, 0, 0),
                () -> world.castRayClosest(0, 0, 0, 0, -1, 0, -1L, -1L),
                () -> world.createVoxelGrid(new ShapeDef()),
                () -> body.setLinearVelocity(1, 0, 0),
                () -> body.isAwake(),
                () -> body.bounds(new float[6]),
                () -> shape.setFriction(1),
                () -> shape.bounds(new float[6]),
                () -> grid.setCells(new int[]{0, 0, 0, 0}, 1),
                () -> grid.featureCell(0, 0, new int[3]));
        for (int i = 0; i < refused.size(); i++) {
            assertThrows(IllegalStateException.class, refused.get(i), "call " + i);
        }
        body.destroy(); // destroying what is already gone is nothing
        shape.destroy(false);
        grid.close();
    }

    // ---- leaks ----------------------------------------------------------------------------------

    private record Full(PhysicsWorld world, VoxelGrid grid) {
    }

    /** A world with something of every kind in it, stepped a little. */
    private static Full fullWorld(MeshData mesh) {
        PhysicsWorld world = world();
        VoxelGrid grid = world.createVoxelGrid(new ShapeDef());
        int id = grid.addCollider(new float[]{0, 0, 0, 1, 1, 1}, 1, grid.addMaterial(new SurfaceMaterial()));
        for (int s = 0; s < 10; s++) {
            grid.setCells(new int[]{16 * s, 0, 0, id, 16 * s + 1, 0, 0, id}, 2);
        }
        Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0.5, 3, 0.5));
        body.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.25f));
        body.createShape(new ShapeDef(), new Geometry.Capsule(0, 0, 0, 0, 0.5f, 0, 0.2f));
        body.createShape(new ShapeDef(), Geometry.Box.of(0.2f, 0.2f, 0.2f));
        world.createBody(new BodyDef().position(40, 0, 0)).createShape(new ShapeDef(), Geometry.Mesh.of(mesh));
        steps(world, 60);
        return new Full(world, grid);
    }

    @Test
    void everythingIsFreedHoweverTheWorldIsTakenApart() {
        float[] vertices = {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1};
        int[] indices = {0, 3, 2, 0, 2, 1};
        // one cycle first: Box3D's once-per-process allocations are not leaks
        MeshData warm = MeshData.create(vertices, indices, true);
        fullWorld(warm).world().close();
        warm.close();
        long bytes = B3.getByteCount();
        int worlds = B3.getWorldCount();

        for (int order = 0; order < 3; order++) {
            for (boolean meshFirst : new boolean[]{true, false}) {
                MeshData mesh = MeshData.create(vertices, indices, true);
                Full full = fullWorld(mesh);
                PhysicsWorld world = full.world();
                if (meshFirst) {
                    mesh.close();
                }
                switch (order) {
                    case 0 -> world.close();
                    case 1 -> {
                        // the grid alone first, then the world
                        full.grid().close();
                        world.close();
                    }
                    default -> {
                        world.forEachBody(Body::destroy);
                        world.close();
                    }
                }
                if (!meshFirst) {
                    mesh.close();
                }
                assertEquals(worlds, B3.getWorldCount(), "world slot back, order " + order);
                assertEquals(bytes, B3.getByteCount(), "every byte back, order " + order + ", mesh closed first " + meshFirst);
            }
        }
    }

    @Test
    void aGridClosedBeforeItsWorldFreesItsSections() {
        try (PhysicsWorld world = world()) {
            int[] counters = new int[6];
            world.counters(counters);
            int bodies = counters[0];
            VoxelGrid grid = world.createVoxelGrid(new ShapeDef());
            int id = grid.addCollider(new float[]{0, 0, 0, 1, 1, 1}, 1, grid.addMaterial(new SurfaceMaterial()));
            grid.setCells(new int[]{0, 0, 0, id, 40, 0, 0, id}, 2);
            world.counters(counters);
            assertEquals(bodies + 2, counters[0], "a static body per section");
            grid.close();
            world.counters(counters);
            assertEquals(bodies, counters[0], "gone with the grid");
            assertThrows(IllegalStateException.class, () -> grid.cell(0, 0, 0));
        }
    }

    @Test
    void worldsComeAndGoOnManyThreadsAtOnce() throws Exception {
        int threads = 8, rounds = 100;
        int worlds = B3.getWorldCount();
        CyclicBarrier start = new CyclicBarrier(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                try {
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        try (PhysicsWorld world = world()) {
                            VoxelGrid grid = world.createVoxelGrid(new ShapeDef());
                            int id = grid.addCollider(new float[]{0, 0, 0, 1, 1, 1}, 1, grid.addMaterial(new SurfaceMaterial()));
                            grid.setCells(new int[]{0, 0, 0, id}, 1);
                            ball(world, 2);
                            world.step(1 / 60f, 4);
                            if (r % 2 == 0) {
                                grid.close(); // the grid before the world half the time, under the same lock
                            }
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        assertNull(failure.get(), () -> "failed: " + failure.get());
        assertEquals(worlds, B3.getWorldCount(), "every slot came back");
    }

    // ---- events ---------------------------------------------------------------------------------

    @Test
    void moreBodiesMoveThanTheEventBufferFirstHolds() {
        try (PhysicsWorld world = world()) {
            Body[] bodies = new Body[200];
            for (int i = 0; i < bodies.length; i++) {
                bodies[i] = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(i * 2, 10, 0));
                bodies[i].createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.25f));
            }
            steps(world, 2);
            double[] transform = new double[7];
            for (Body body : bodies) {
                B3.bodyGetTransform(body.id(), transform);
                assertEquals(transform[1], body.position(new Vector3d()).y, 1e-9, "the 65th body and on are not frozen");
            }
            assertEquals(bodies.length, world.movedBodies().size());
        }
    }

    @Test
    void aBodyThatFallsAsleepInAnyStepOfAFrameIsReported() {
        try (PhysicsWorld world = world()) {
            ground(world);
            Set<Body> all = new HashSet<>();
            for (int i = 0; i < 30; i++) {
                // dropped from different heights, so they fall asleep in different steps
                Body ball = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(i * 1.2 - 18, 1 + 0.1 * i, 0));
                ball.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.25f));
                all.add(ball);
            }
            Set<Body> reported = new HashSet<>();
            for (int frame = 0; frame < 200; frame++) {
                world.beginInterpolationFrame();
                for (int s = 0; s < 3; s++) {
                    world.step(1 / 60f, 4);
                }
                reported.addAll(world.bodiesThatFellAsleep());
            }
            for (Body ball : all) {
                assertFalse(ball.isAwake());
            }
            assertEquals(all, reported, "every body that went to sleep, whichever of the frame's three steps it was in");
        }
    }

    // ---- what each call does --------------------------------------------------------------------

    @Test
    void teleportingASleepingBodyWakesIt() {
        try (PhysicsWorld world = world()) {
            ground(world);
            Body ball = ball(world, 1);
            steps(world, 200);
            assertFalse(ball.isAwake());
            ball.setTransform(3, 5, 0, new Quaternionf());
            assertTrue(ball.isAwake(), "teleported into the air");
            assertEquals(5, ball.renderPosition(0, new Vector3d()).y, 1e-9, "the frame's start jumped along");
            steps(world, 120);
            Vector3d p = ball.position(new Vector3d());
            assertEquals(3, p.x, 1e-3);
            assertEquals(0.75, p.y, 0.02, "fell back down");
        }
    }

    @Test
    void impulsesForcesAndGravityScaleMoveTheBodyAsTheySay() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, -10, 0))) {
            Body box = world.createBody(new BodyDef().type(BodyType.DYNAMIC));
            box.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            float mass = box.mass();
            assertEquals(1000, mass, 1, "a 1 m cube of water density");
            box.setGravityScale(0);
            // an impulse along +x above the centre turns it about -z
            box.applyLinearImpulse(mass, 0, 0, 0, 0.5, 0);
            Vector3f v = box.linearVelocity(new Vector3f());
            Vector3f w = box.angularVelocity(new Vector3f());
            assertEquals(1, v.x, 1e-4);
            assertTrue(w.z < -0.1f, "r × J points down z: " + w);
            box.setLinearVelocity(0, 0, 0);
            box.setAngularVelocity(0, 0, 0);
            box.applyLinearImpulseToCenter(0, 0, 2 * mass);
            assertEquals(2, box.linearVelocity(v).z, 1e-4);
            assertEquals(0, box.angularVelocity(w).length(), 1e-5);
            box.setLinearVelocity(0, 0, 0);
            box.applyForceToCenter(mass * 60, 0, 0); // one second's worth over one 1/60 step: 1 m/s
            world.step(1 / 60f, 4);
            assertEquals(1, box.linearVelocity(v).x, 1e-3);
            assertEquals(0, v.y, 1e-6, "gravity scale 0: no fall");
            box.setGravityScale(1);
            world.step(1 / 60f, 4);
            assertEquals(-10 / 60f, box.linearVelocity(v).y, 1e-3);
        }
    }

    @Test
    void typeEnabledAndAwakeAreWhatTheySay() {
        try (PhysicsWorld world = world()) {
            ground(world);
            Body ball = ball(world, 3);
            ball.setType(BodyType.STATIC);
            assertEquals(BodyType.STATIC, ball.type());
            steps(world, 30);
            assertEquals(3, ball.position(new Vector3d()).y, 1e-9, "static: does not fall");
            ball.setType(BodyType.DYNAMIC);
            ball.setAwake(true);
            steps(world, 10);
            assertTrue(ball.position(new Vector3d()).y < 3, "dynamic again: falls");

            ball.setEnabled(false);
            assertFalse(ball.isEnabled());
            double y = ball.position(new Vector3d()).y;
            steps(world, 30);
            assertEquals(y, ball.position(new Vector3d()).y, 1e-9, "disabled: out of the simulation");
            CastHit hit = world.castRayClosest(0, 10, 0, 0, -20, 0, -1L, -1L);
            assertTrue(hit != null && hit.body() != ball, "and of queries: the ray goes through to the ground");
            ball.setEnabled(true);
            steps(world, 120);
            assertEquals(0.75, ball.position(new Vector3d()).y, 0.02, "enabled: lands");

            ball.setAwake(false);
            assertFalse(ball.isAwake());
            ball.setAwake(true);
            assertTrue(ball.isAwake());
            assertEquals(1, world.awakeBodyCount());
            world.enableSleeping(false);
            steps(world, 200);
            assertTrue(ball.isAwake(), "sleeping off: stays awake");
        }
    }

    @Test
    void boundsFiltersAndFrictionOfShapes() {
        try (PhysicsWorld world = world()) {
            Body ground = ground(world);
            Body box = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, 0.75, 0));
            Shape shape = box.createShape(new ShapeDef().friction(0), Geometry.Box.of(0.25f, 0.25f, 0.25f));
            float[] b = shape.bounds(new float[6]);
            assertEquals(-0.25f, b[0], 0.03f);
            assertEquals(1.0f, b[4], 0.03f);
            assertArrayEquals(b, box.bounds(new float[6]), 1e-6f, "one shape: the body's bounds are the shape's");

            // a category the ray's mask leaves out is not hit
            shape.setFilter(1L << 9, -1L, 0, false);
            CastHit hit = world.castRayClosest(0, 5, 0, 0, -10, 0, -1L, ~(1L << 9));
            assertNotNull(hit);
            assertSame(ground, hit.body(), "the ray passes the box to the ground");

            // friction 0 on one side mixes to 0: slides on; then friction 1 on both stops it
            ground.shapes().getFirst().setFriction(1);
            steps(world, 20);
            box.setLinearVelocity(3, 0, 0);
            steps(world, 30);
            assertTrue(box.linearVelocity(new Vector3f()).x > 2.9f, "frictionless");
            shape.setFriction(1);
            steps(world, 60);
            assertEquals(0, box.linearVelocity(new Vector3f()).x, 0.05f, "stopped");
        }
    }

    @Test
    void renderRotationTurnsHalfWayAtHalfAFrame() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, 0, 0))) {
            Body spinner = world.createBody(new BodyDef().type(BodyType.DYNAMIC));
            spinner.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            spinner.setAngularVelocity(0, (float) Math.PI, 0); // half a turn a second
            world.beginInterpolationFrame();
            for (int i = 0; i < 20; i++) {
                world.step(1 / 60f, 4); // a third of a second: 60 degrees
            }
            Quaternionf half = spinner.renderRotation(0.5f, new Quaternionf());
            Vector3f x = half.transform(new Vector3f(1, 0, 0));
            assertEquals(Math.toRadians(30), Math.atan2(-x.z, x.x), 1e-2, "30 degrees at half the frame");
        }
    }
}
