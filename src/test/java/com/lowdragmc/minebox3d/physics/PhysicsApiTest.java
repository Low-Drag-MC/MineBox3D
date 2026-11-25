package com.lowdragmc.minebox3d.physics;

import static org.junit.jupiter.api.Assertions.*;

import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * <b>The physics API's contract</b>: what each method does that can be seen, what it refuses, what is left after
 * closing, and that nothing leaks. Without Minecraft.
 */
class PhysicsApiTest {

    // a test reading B3 before any world is made, run first, finds nothing bound
    @BeforeAll
    static void load() {
        NativeLibrary.load();
    }

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
                    () -> world.castRayClosest(Double.NaN, 0, 0, 0, -1, 0, QueryFilter.ALL),
                    () -> world.castShapeClosest(
                            new Geometry.Sphere(0, nan, 0, 0.1f), 0, 0, 0, 0, -1, 0, QueryFilter.ALL, new CastResult()),
                    () -> world.castShapeClosest(
                            new Geometry.Sphere(0, 0, 0, 0.1f),
                            0,
                            0,
                            0,
                            new Quaternionf(0, 0, 0, 2),
                            0,
                            -1,
                            0,
                            QueryFilter.ALL,
                            new CastResult()),
                    () -> world.overlapShape(
                            Geometry.Mesh.of(MeshData.create(
                                    new float[] {0, 0, 0, 1, 0, 0, 0, 0, 1}, new int[] {0, 2, 1}, false)),
                            0,
                            0,
                            0,
                            QueryFilter.ALL,
                            new Overlaps()),
                    () -> world.collideMover(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, QueryFilter.ALL, new MoverPlanes()));
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

    @Test
    void aFaultedWorldLetsGoOfWhatIsInItWithoutAskingBox3D() {
        try (PhysicsWorld world = world()) {
            Body[] balls = new Body[3];
            for (int i = 0; i < balls.length; i++) {
                balls[i] = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0.1 * i, 2, 0));
                ShapeDef shape = new ShapeDef();
                shape.enableCustomFiltering = true;
                balls[i].createShape(shape, new Geometry.Sphere(0, 0, 0, 0.25f));
            }
            BodyPairTable table = new BodyPairTable(balls);
            table.setAllCollide(false);
            table.apply();
            Joint joint = world.createJoint(
                    new ConstraintJointDef().bodies(balls[0], balls[1]).worldFrame(0, 2, 0));
            steps(world, 2);

            // as the solver flinging a body would: two of the largest forces add up to an infinite one, which the
            // step's
            // speed clamp makes NaN, and the step fails Box3D's own check
            B3.bodyApplyForceToCenter(balls[0].id(), Float.MAX_VALUE, 0, 0, true);
            B3.bodyApplyForceToCenter(balls[0].id(), Float.MAX_VALUE, 0, 0, true);
            assertThrows(Box3DException.class, () -> steps(world, 1));
            assertTrue(world.isFaulted());
            int[] before = new int[6];
            world.counters(before);

            // asked of Box3D, each of these walks a broadphase now holding NaN bounds
            table.destroy();
            joint.destroy(true);
            balls[1].shapes().getFirst().destroy(true);
            balls[2].destroy();
            assertFalse(table.isValid());
            assertFalse(joint.isValid());
            assertTrue(balls[1].shapes().isEmpty());
            assertFalse(balls[2].isValid());
            int[] after = new int[6];
            world.counters(after);
            assertArrayEquals(before, after, "nothing reached Box3D: [bodies, shapes, contacts, joints, ...]");
        }
    }

    @Test
    void aMeshLetGoOfInAFaultedWorldIsFreedWithTheWorldNotBefore() {
        MeshData data = MeshData.create(new float[] {-2, 0, -2, 2, 0, -2, 0, 0, 2}, new int[] {0, 2, 1}, false);
        try (PhysicsWorld other = world()) {
            PhysicsWorld world = world();
            Body ground = world.createBody(new BodyDef());
            ground.createShape(new ShapeDef(), Geometry.Mesh.of(data));
            Body ball = ball(world, 1);
            steps(world, 2);
            // the owner is done with it: the ground's shape holds the last reference
            data.close();
            B3.bodyApplyForceToCenter(ball.id(), Float.MAX_VALUE, 0, 0, true);
            B3.bodyApplyForceToCenter(ball.id(), Float.MAX_VALUE, 0, 0, true);
            assertThrows(Box3DException.class, () -> steps(world, 1));
            ground.destroy();

            // let go of here, Box3D's shape is still in the world and still reads the mesh
            Body probe = other.createBody(new BodyDef());
            assertDoesNotThrow(
                    () -> probe.createShape(new ShapeDef(), Geometry.Mesh.of(data)), "not freed under Box3D");
            world.close();
            probe.destroy();
            Body late = other.createBody(new BodyDef());
            assertThrows(
                    IllegalStateException.class,
                    () -> late.createShape(new ShapeDef(), Geometry.Mesh.of(data)),
                    "freed once the world and the last other shape are gone");
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
                () -> world.castRayClosest(0, 0, 0, 0, -1, 0, QueryFilter.ALL),
                () -> world.castRay(0, 0, 0, 0, -1, 0, QueryFilter.ALL, new CastHits()),
                () -> world.overlapShape(new Geometry.Sphere(0, 0, 0, 1), 0, 0, 0, QueryFilter.ALL, new Overlaps()),
                () -> world.createVoxelGrid(new ShapeDef()),
                () -> body.setLinearVelocity(1, 0, 0),
                () -> body.isAwake(),
                () -> body.bounds(new float[6]),
                () -> body.contactPoints(new ContactPoints()),
                () -> shape.setFriction(1),
                () -> shape.bounds(new float[6]),
                () -> grid.setCells(new int[] {0, 0, 0, 0}, 1));
        for (int i = 0; i < refused.size(); i++) {
            assertThrows(IllegalStateException.class, refused.get(i), "call " + i);
        }
        body.destroy(); // destroying what is already gone is nothing
        shape.destroy(false);
        grid.close();
    }

    // ---- leaks ----------------------------------------------------------------------------------

    private record Full(PhysicsWorld world, VoxelGrid grid) {}

    /** A world with something of every kind in it, stepped a little. */
    private static Full fullWorld(MeshData mesh) {
        PhysicsWorld world = world();
        VoxelGrid grid = world.createVoxelGrid(new ShapeDef());
        int id = grid.addCollider(new float[] {0, 0, 0, 1, 1, 1}, 1, grid.addMaterial(new SurfaceMaterial()));
        for (int s = 0; s < 10; s++) {
            grid.setCells(new int[] {16 * s, 0, 0, id, 16 * s + 1, 0, 0, id}, 2);
        }
        Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0.5, 3, 0.5));
        body.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.25f));
        body.createShape(new ShapeDef(), new Geometry.Capsule(0, 0, 0, 0, 0.5f, 0, 0.2f));
        body.createShape(new ShapeDef(), Geometry.Box.of(0.2f, 0.2f, 0.2f));
        body.createShape(new ShapeDef(), Geometry.Hull.of(-0.2f, 0, -0.2f, 0.2f, 0, -0.2f, 0, 0, 0.2f, 0, 0.3f, 0));
        ShapeDef materials = new ShapeDef();
        materials.materials = new SurfaceMaterial[] {new SurfaceMaterial(), new SurfaceMaterial().friction(0.1f)};
        world.createBody(new BodyDef().position(40, 0, 0)).createShape(materials, Geometry.Mesh.of(mesh));
        try (HeightFieldData field = HeightFieldData.create(new float[9], 3, 3)) {
            world.createBody(new BodyDef().position(-40, 0, 0))
                    .createShape(new ShapeDef(), new Geometry.HeightField(field));
        }
        Body other = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(1.5, 3, 0.5));
        other.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.25f));
        world.createJoint(new RevoluteJointDef().bodies(body, other).worldFrame(1, 3, 0.5));
        world.createJoint(new DistanceJointDef().bodies(body, other).length(1));
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
            for (boolean meshFirst : new boolean[] {true, false}) {
                MeshData mesh = MeshData.create(vertices, indices, true);
                Full full = fullWorld(mesh);
                PhysicsWorld world = full.world();
                if (meshFirst) {
                    mesh.close();
                }
                switch (order) {
                    case 0 -> world.close();
                    case 1 -> {
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
                assertEquals(
                        bytes,
                        B3.getByteCount(),
                        "every byte back, order " + order + ", mesh closed first " + meshFirst);
            }
        }
    }

    /** What Box3D would assert on, or size wrong, is refused before it gets there. */
    @Test
    void definitionsAndDataBox3DCannotTakeAreRefused() {
        int worlds = B3.getWorldCount();
        List<java.util.function.Consumer<WorldDef>> bad = List.of(
                def -> def.gravityY = Float.NaN,
                def -> def.contactHertz = 0,
                def -> def.contactSpeed = -1,
                def -> def.maximumLinearSpeed = Float.NaN,
                def -> def.isotropicInertiaTolerance = 2,
                def -> def.restitutionIterations = -1,
                def -> def.dynamicBodyCapacity = 50_000_000,
                def -> def.threadMode = null);
        for (int i = 0; i < bad.size(); i++) {
            WorldDef def = new WorldDef();
            bad.get(i).accept(def);
            assertThrows(IllegalArgumentException.class, () -> PhysicsWorld.create(def), "world def " + i);
        }
        assertEquals(worlds, B3.getWorldCount(), "none made");

        // countX * countZ wraps to the array's length in an int: the long product does not
        assertThrows(IllegalArgumentException.class, () -> HeightFieldData.create(new float[65536], 65536, 65537));
        assertThrows(
                IllegalArgumentException.class,
                () -> MeshData.create(new float[] {0, 0, 0, 1, Float.NaN, 0, 0, 0, 1}, new int[] {0, 2, 1}, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> MeshData.create(
                        new float[] {0, 0, 0, 1, 0, 0, 0, 0, Float.POSITIVE_INFINITY}, new int[] {0, 2, 1}, false));
    }

    @Test
    void aGridClosedBeforeItsWorldFreesItsSections() {
        try (PhysicsWorld world = world()) {
            int[] counters = new int[6];
            world.counters(counters);
            int bodies = counters[0];
            VoxelGrid grid = world.createVoxelGrid(new ShapeDef());
            int id = grid.addCollider(new float[] {0, 0, 0, 1, 1, 1}, 1, grid.addMaterial(new SurfaceMaterial()));
            grid.setCells(new int[] {0, 0, 0, id, 40, 0, 0, id}, 2);
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
                            int id = grid.addCollider(
                                    new float[] {0, 0, 0, 1, 1, 1}, 1, grid.addMaterial(new SurfaceMaterial()));
                            grid.setCells(new int[] {0, 0, 0, id}, 1);
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
                bodies[i] =
                        world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(i * 2, 10, 0));
                bodies[i].createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.25f));
            }
            steps(world, 2);
            double[] transform = new double[7];
            for (Body body : bodies) {
                B3.bodyGetTransform(body.id(), transform);
                assertEquals(
                        transform[1], body.position(new Vector3d()).y, 1e-9, "the 65th body and on are not frozen");
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
                Body ball =
                        world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(i * 1.2 - 18, 1 + 0.1 * i, 0));
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
            assertEquals(
                    all, reported, "every body that went to sleep, whichever of the frame's three steps it was in");
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
            CastHit hit = world.castRayClosest(0, 10, 0, 0, -20, 0, QueryFilter.ALL);
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
            CastHit hit = world.castRayClosest(0, 5, 0, 0, -10, 0, QueryFilter.only(~(1L << 9)));
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

    // ---- what a step brings back with it ----------------------------------------------------------

    /** The bounds and velocities the step reports, against Box3D's own answer asked for directly. */
    private static void sameAsBox3D(Body body, String when) {
        float[] v = new float[3];
        Vector3f cached = new Vector3f();
        B3.bodyGetLinearVelocity(body.id(), v);
        assertEquals(new Vector3f(v[0], v[1], v[2]), body.linearVelocity(cached), "linear velocity " + when);
        B3.bodyGetAngularVelocity(body.id(), v);
        assertEquals(new Vector3f(v[0], v[1], v[2]), body.angularVelocity(cached), "angular velocity " + when);
        float[] box = new float[6];
        B3.bodyComputeAABB(body.id(), box);
        assertArrayEquals(box, body.bounds(new float[6]), "bounds " + when);
    }

    @Test
    void boundsAndVelocitiesComeBackWithTheStepAndStayRight() {
        try (PhysicsWorld world = world()) {
            ground(world);
            Body ball = ball(world, 3);
            ball.setAngularVelocity(0, 2, 0);
            sameAsBox3D(ball, "before any step");
            steps(world, 5);
            sameAsBox3D(ball, "falling");
            ball.setLinearVelocity(4, 0, 0);
            sameAsBox3D(ball, "after setting it");
            ball.applyLinearImpulseToCenter(0, 50, 0);
            sameAsBox3D(ball, "after an impulse");
            ball.setTransform(10, 5, 0, new Quaternionf());
            sameAsBox3D(ball, "after a teleport");
            ball.createShape(new ShapeDef(), Geometry.Box.of(1, 1, 1));
            sameAsBox3D(ball, "after a bigger shape");
            steps(world, 400);
            assertFalse(ball.isAwake());
            sameAsBox3D(ball, "asleep");
            ball.setAwake(true);
            sameAsBox3D(ball, "woken");
        }
    }

    @Test
    void puttingOneBodyToSleepStopsItsWholeIslandInTheCacheToo() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, 0, 0))) {
            // two boxes pressed together, sliding along: one island
            Body a = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, 0, 0));
            a.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            Body b = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0.99, 0, 0));
            b.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            a.setLinearVelocity(1, 0, 1);
            b.setLinearVelocity(1, 0, 1);
            steps(world, 3);
            assertTrue(b.linearVelocity(new Vector3f()).length() > 0.5f, "moving, as of the step");
            a.setAwake(false);
            assertFalse(b.isAwake(), "the island went to sleep");
            sameAsBox3D(b, "after its neighbour put the island to sleep");
        }
    }

    // ---- attachments ------------------------------------------------------------------------------

    private record ModA(String value) {}

    private record ModB(int value) {}

    @Test
    void eachModAttachesItsOwnValueByType() {
        try (PhysicsWorld world = world()) {
            Body body = ball(world, 3);
            Shape shape = body.shapes().getFirst();
            assertNull(body.attachment(ModA.class));
            body.attach(ModA.class, new ModA("a"));
            body.attach(ModB.class, new ModB(2));
            shape.attach(ModA.class, new ModA("shape"));
            assertEquals("a", body.attachment(ModA.class).value());
            assertEquals(2, body.attachment(ModB.class).value());
            assertEquals("shape", shape.attachment(ModA.class).value());
            body.attach(ModA.class, new ModA("again"));
            assertEquals("again", body.attachment(ModA.class).value(), "replaced");
            body.attach(ModA.class, null);
            assertNull(body.attachment(ModA.class), "removed");
            assertEquals(2, body.attachment(ModB.class).value(), "the other one stays");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Object> wrong = (Class) ModA.class;
            assertThrows(ClassCastException.class, () -> body.attach(wrong, "not a ModA"));
        }
    }

    // ---- queries with a turned shape --------------------------------------------------------------

    @Test
    void aTurnedQueryShapeReachesFurther() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, 0, 0))) {
            Body wall = world.createBody(new BodyDef().position(10, 0, 0));
            wall.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 5, 5));
            Geometry box = Geometry.Box.of(1, 1, 1);
            CastHit straight = world.castShapeClosest(box, 0, 0, 0, new Quaternionf(), 20, 0, 0, QueryFilter.ALL);
            CastHit turned = world.castShapeClosest(
                    box, 0, 0, 0, new Quaternionf().rotationY((float) Math.PI / 4), 20, 0, 0, QueryFilter.ALL);
            assertNotNull(straight);
            assertNotNull(turned);
            // a face 1 ahead against a corner √2 ahead
            assertEquals((9.5 - 1) / 20, straight.fraction(), 1e-3);
            assertEquals((9.5 - Math.sqrt(2)) / 20, turned.fraction(), 1e-3);

            // a box's own turn counts the same
            Geometry ownTurn = new Geometry.Box(
                    1, 1, 1, 0, 0, 0, 0, (float) Math.sin(Math.PI / 8), 0, (float) Math.cos(Math.PI / 8));
            CastHit own = world.castShapeClosest(ownTurn, 0, 0, 0, new Quaternionf(), 20, 0, 0, QueryFilter.ALL);
            assertNotNull(own);
            assertEquals(turned.fraction(), own.fraction(), 1e-4);
        }
    }

    // ---- dropped without closing ------------------------------------------------------------------

    @Test
    void aWorldDroppedWithoutClosingIsFreedByTheCleaner() throws InterruptedException {
        int before = B3.getWorldCount();
        dropAWorld();
        assertEquals(before + 1, B3.getWorldCount());
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (B3.getWorldCount() > before && System.nanoTime() < deadline) {
            System.gc();
            byte[][] garbage = new byte[64][];
            for (int i = 0; i < garbage.length; i++) {
                garbage[i] = new byte[1 << 16];
            }
            Thread.sleep(20);
        }
        assertEquals(before, B3.getWorldCount(), "the cleaner freed it");
    }

    private static void dropAWorld() {
        PhysicsWorld world = world();
        world.createVoxelGrid(new ShapeDef()).setCells(new int[] {0, 0, 0, 0}, 1);
        world.createBody(new BodyDef().type(BodyType.DYNAMIC))
                .createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 1));
    }
}
