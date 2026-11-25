package com.lowdragmc.minebox3d.physics;

import static org.junit.jupiter.api.Assertions.*;

import com.lowdragmc.minebox3d.internal.B3;
import java.util.List;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * <b>A body's second round of API</b> (design doc {@code 03-threading-and-api.md} §6.4 item 2): mass, damping, forces
 * that turn, the switches, the velocity at a point, and queries against one body.
 */
class BodyApiTest {

    private static PhysicsWorld world(float gravity) {
        return PhysicsWorld.create(new WorldDef().gravity(0, gravity, 0));
    }

    private static Body cube(PhysicsWorld world, double x, double y, double z, float half) {
        Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(x, y, z));
        body.createShape(new ShapeDef().density(1000), Geometry.Box.of(half, half, half));
        return body;
    }

    private static void steps(PhysicsWorld world, int count) {
        for (int i = 0; i < count; i++) {
            world.beginInterpolationFrame();
            world.step(1 / 60f, 4);
        }
    }

    @Test
    void massComesFromTheShapesAndCanBeOverriddenAndRestored() {
        try (PhysicsWorld world = world(0)) {
            Body body = cube(world, 0, 0, 0, 0.5f);
            MassData mass = body.massData(new MassData());
            assertEquals(1000, mass.mass, 1e-2, "1 m³ at 1000 kg/m³");
            assertEquals(1000, body.mass(), 1e-2);
            // a solid cube: m (2h)² / 6 about each axis
            assertEquals(1000 / 6f, mass.inertia.m00, 0.5);
            assertEquals(1000 / 6f, mass.inertia.m22, 0.5);
            assertEquals(0, mass.inertia.m01, 1e-3);

            MassData heavy = new MassData().set(50, 0.1f, 0, 0);
            heavy.inertia.identity().scale(2);
            body.setMassData(heavy);
            MassData read = body.massData(new MassData());
            assertEquals(50, read.mass, 1e-4);
            assertEquals(0.1f, read.centerX, 1e-5);
            assertEquals(2, read.inertia.m11, 1e-4);
            assertEquals(0.1, body.worldCenter(new Vector3d()).x, 1e-5, "the centre moved with it");

            body.applyMassFromShapes();
            assertEquals(1000, body.mass(), 1e-2, "back to the shapes'");
            assertEquals(0, body.worldCenter(new Vector3d()).x, 1e-5);
        }
    }

    @Test
    void settingsReadBackAndDoWhatTheySay() {
        try (PhysicsWorld world = world(0)) {
            Body body = cube(world, 0, 0, 0, 0.25f);
            body.setLinearDamping(2);
            assertEquals(2, body.linearDamping());
            body.setAngularDamping(3);
            assertEquals(3, body.angularDamping());
            body.setGravityScale(-0.5f);
            assertEquals(-0.5f, body.gravityScale());
            body.setSleepThreshold(0.2f);
            assertEquals(0.2f, body.sleepThreshold());
            body.setSafetyFactor(0.3f);
            assertEquals(0.3f, body.safetyFactor());
            body.setBullet(true);
            assertTrue(body.isBullet());
            body.enableSleep(false);
            assertFalse(body.isSleepEnabled());
            body.allowFastRotation(true);
            assertTrue(body.isFastRotationAllowed());
            body.enableContactRecycling(false);
            assertFalse(body.isContactRecyclingEnabled());
            body.enableHitEvents(true);
            assertTrue(body.shapes().getFirst().areHitEventsEnabled(), "onto every shape");
            body.setFixedRotation(true);
            assertTrue(body.isFixedRotation());

            body.setFixedRotation(false);
            body.setLinearVelocity(5, 0, 0);
            steps(world, 30);
            assertTrue(body.linearVelocity(new Vector3f()).x < 2.5f, "damped");
        }
    }

    /**
     * Parking (the terrain streamer's) and its maker's switch are two: it is in the simulation only when neither says
     * no.
     */
    @Test
    void aParkedBodyStaysOutTillLetBackAndIsThenAsItsMakerSaid() {
        try (PhysicsWorld world = world(-10)) {
            Body body = cube(world, 0, 0, 0, 0.25f);
            body.setParked(true);
            assertTrue(body.isParked());
            assertFalse(body.isEnabled());
            body.setEnabled(true);
            assertFalse(body.isEnabled(), "still parked");
            steps(world, 10);
            assertEquals(0, body.position(new Vector3d()).y, 1e-9, "out of the simulation");
            body.setParked(false);
            assertTrue(body.isEnabled());

            body.setEnabled(false);
            body.setParked(true);
            body.setParked(false);
            assertFalse(body.isEnabled(), "its maker turned it off, and off it stays");

            Body off = world.createBody(new BodyDef().type(BodyType.DYNAMIC).enabled(false));
            off.setParked(true);
            off.setParked(false);
            assertFalse(off.isEnabled(), "made off");
        }
    }

    @Test
    void aBodyThatMayNotSleepStaysAwakeAtRest() {
        try (PhysicsWorld world = world(0)) {
            Body sleepy = cube(world, 0, 0, 0, 0.25f);
            Body awake = cube(world, 5, 0, 0, 0.25f);
            awake.enableSleep(false);
            steps(world, 200);
            assertFalse(sleepy.isAwake(), "the control falls asleep");
            assertTrue(awake.isAwake());
        }
    }

    @Test
    void aFixedRotationBodyFallsWithoutTurning() {
        try (PhysicsWorld world = world(-10)) {
            Body fixed = world.createBody(
                    new BodyDef().type(BodyType.DYNAMIC).position(0, 5, 0).fixedRotation(true));
            fixed.createShape(new ShapeDef(), Geometry.Box.of(0.25f, 0.25f, 0.25f));
            assertTrue(fixed.isFixedRotation(), "from the definition");
            fixed.applyAngularImpulse(5, 5, 5);
            steps(world, 30);
            assertTrue(fixed.position(new Vector3d()).y < 4, "falls");
            assertEquals(0, fixed.angularVelocity(new Vector3f()).length(), 1e-6, "never turns");
        }
    }

    @Test
    void aMotionLockJointStopsTheAxesItNames() {
        try (PhysicsWorld world = world(-10)) {
            Body anchor = world.createBody(new BodyDef());
            Body locked = cube(world, 0, 5, 0, 0.25f);
            // the frame where the cube is, its z axis up: kept on that height, turning about the vertical only
            Quaternionf zUp = new Quaternionf().rotationX((float) (-Math.PI / 2));
            MotionLockJoint joint = world.createJoint(new MotionLockJointDef()
                    .bodies(anchor, locked)
                    .worldFrame(0, 5, 0, zUp)
                    .lockLinear(false, false, true)
                    .lockAngularXY(true));
            assertTrue(joint.isLinearLocked(2) && !joint.isLinearLocked(0) && joint.isAngularLocked());
            locked.applyAngularImpulse(5, 5, 5);
            locked.setLinearVelocity(1, 0, 0);
            steps(world, 30);
            Vector3d at = locked.position(new Vector3d());
            assertEquals(5, at.y, 1e-3, "does not fall");
            assertTrue(at.x > 0.4, "slides along x: " + at);
            Vector3f w = locked.angularVelocity(new Vector3f());
            assertEquals(0, w.x, 1e-3);
            assertEquals(0, w.z, 1e-3);
            assertTrue(w.y > 0.1f, "turns about y only: " + w);

            joint.lockLinear(2, false);
            assertFalse(joint.isLinearLocked(2));
            steps(world, 10);
            assertTrue(locked.position(new Vector3d()).y < 4.9, "let go, it falls");
        }
    }

    @Test
    void forcesOffTheCentreTurnTheBodyAndTorquesTurnIt() {
        try (PhysicsWorld world = world(0)) {
            Body pushed = cube(world, 0, 0, 0, 0.5f);
            // pushed along +x at its top: moves +x, turns about -z
            pushed.applyForce(1000, 0, 0, 0, 0.5, 0);
            steps(world, 1);
            assertTrue(pushed.linearVelocity(new Vector3f()).x > 0);
            assertTrue(pushed.angularVelocity(new Vector3f()).z < 0, "turned by the lever");

            Body twisted = cube(world, 5, 0, 0, 0.5f);
            twisted.applyTorque(0, 100, 0);
            steps(world, 1);
            assertTrue(twisted.angularVelocity(new Vector3f()).y > 0);
            assertEquals(0, twisted.linearVelocity(new Vector3f()).length(), 1e-6, "a torque does not move it");

            Body spun = cube(world, 10, 0, 0, 0.5f);
            spun.applyAngularImpulse(0, 0, 1000 / 6f);
            assertEquals(1, spun.angularVelocity(new Vector3f()).z, 1e-3, "impulse / inertia, at once");
        }
    }

    @Test
    void theVelocityAtAPointIsTheBodysPlusItsTurn() {
        try (PhysicsWorld world = world(0)) {
            double x = 7_654_321.5;
            Body body = cube(world, x, 64, 0, 0.5f);
            body.setLinearVelocity(1, 0, 0);
            body.setAngularVelocity(0, 0, 2);
            // 1.5 above the centre: v + w x r = (1, 0, 0) + (0, 0, 2) x (0, 1.5, 0) = (1 - 3, 0, 0)
            Vector3f v = body.pointVelocity(x, 65.5, 0, new Vector3f());
            assertEquals(-2, v.x, 1e-4);
            assertEquals(0, v.y, 1e-4);
            assertEquals(0, v.z, 1e-4);
        }
    }

    @Test
    void pointsGoBetweenTheBodysSpaceAndTheWorldExactlyFarOut() {
        try (PhysicsWorld world = world(0)) {
            double x = 29_000_000.125, z = -29_000_000.375;
            Body body = world.createBody(new BodyDef()
                    .type(BodyType.DYNAMIC)
                    .position(x, 70, z)
                    .rotation(new Quaternionf().rotateY((float) Math.PI / 2)));
            Vector3d world3 = body.toWorld(1, 2, 0, new Vector3d());
            // a quarter turn about y takes local +x to world -z
            assertEquals(x, world3.x, 1e-6);
            assertEquals(72, world3.y, 1e-6);
            assertEquals(z - 1, world3.z, 1e-6);
            Vector3f back = body.toLocal(world3.x, world3.y, world3.z, new Vector3f());
            assertEquals(1, back.x, 1e-5);
            assertEquals(2, back.y, 1e-5);
            assertEquals(0, back.z, 1e-5);
        }
    }

    @Test
    void queriesAgainstOneBodySeeOnlyThatBody() {
        try (PhysicsWorld world = world(0)) {
            Body near = cube(world, 2, 0, 0, 0.5f);
            Body far = cube(world, 5, 0, 0, 0.5f);
            CastResult hit = new CastResult();

            assertTrue(world.castRayClosest(0, 0, 0, 10, 0, 0, QueryFilter.ALL, hit));
            assertSame(near, hit.body(), "the world sees the near one first");
            assertTrue(far.castRay(0, 0, 0, 10, 0, 0, QueryFilter.ALL, hit), "through the near one");
            assertSame(far, hit.body());
            assertEquals(4.5, hit.x(), 1e-4);
            assertEquals(-1, hit.normal(new Vector3f()).x, 1e-4);
            assertFalse(far.castRay(0, 0, 0, 0, 10, 0, QueryFilter.ALL, hit), "a miss is a miss");
            assertFalse(far.castRay(0, 0, 0, 10, 0, 0, QueryFilter.only(0), hit), "the filter applies");

            Geometry ball = new Geometry.Sphere(0, 0, 0, 0.25f);
            assertTrue(far.castShape(ball, 0, 0, 0, new Quaternionf(), 10, 0, 0, QueryFilter.ALL, hit));
            assertSame(far, hit.body());
            assertEquals((4.5 - 0.25) / 10, hit.fraction(), 1e-3);

            assertTrue(far.overlaps(ball, 5.6, 0, 0, new Quaternionf(), QueryFilter.ALL));
            assertFalse(far.overlaps(ball, 2, 0, 0, new Quaternionf(), QueryFilter.ALL), "the near one is not asked");
        }
    }

    @Test
    void theClosestPointIsExactFarOutAndInfiniteWithoutAConvexShape() {
        try (PhysicsWorld world = world(0)) {
            double x = 29_999_000.317, z = -29_999_000.719;
            Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(x, 64, z));
            body.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.5f));
            body.createShape(new ShapeDef(), Geometry.Box.of(0.25f, 0.25f, 0.25f, 0, 2, 0));
            Vector3d closest = new Vector3d();
            float distance = body.closestPoint(x + 5, 64, z, closest);
            assertEquals(4.5f, distance, 1e-4);
            assertEquals(x + 0.5, closest.x, 1e-4, "on the sphere, to a ten-thousandth millions out");
            assertEquals(z, closest.z, 1e-4);
            assertEquals(0.25f, body.closestPoint(x, 64 + 2.5, z, closest), 1e-4, "the box is closer up there");
            assertEquals(64 + 2.25, closest.y, 1e-4);
            assertEquals(0, body.closestPoint(x + 0.1, 64, z, closest), 1e-6, "inside");

            Body bare = world.createBody(new BodyDef().position(x, 0, z));
            assertEquals(Float.POSITIVE_INFINITY, bare.closestPoint(x, 1, z, closest));
        }
    }

    /** The step's whole impulse and the approach speed Box3D samples — the latter only for a pair reporting hits. */
    @Test
    void aContactPointSaysHowHardTheStepPushedAndHowFastThePairCame() {
        for (boolean hits : new boolean[] {true, false}) {
            try (PhysicsWorld world = world(-10)) {
                Body ground = world.createBody(new BodyDef());
                ground.createShape(new ShapeDef(), Geometry.Box.of(5, 0.5f, 5));
                Body ball = world.createBody(new BodyDef()
                        .type(BodyType.DYNAMIC)
                        .position(0, 0.5 + 0.25 + 0.05, 0)
                        .linearVelocity(0, -4, 0));
                ball.createShape(new ShapeDef().enableHitEvents(hits), new Geometry.Sphere(0, 0, 0, 0.25f));
                ContactPoints points = new ContactPoints();
                float approach = 0, total = 0, last = 0;
                for (int i = 0; i < 10 && approach == 0; i++) {
                    world.step(1 / 60f, 4);
                    if (ball.contactPoints(points) > 0) {
                        approach = -points.normalVelocity(0);
                        total = points.totalNormalImpulse(0);
                        last = points.normalImpulse(0);
                    }
                }
                if (hits) {
                    assertEquals(4, approach, 0.5, "came together at about its fall speed");
                    assertTrue(
                            total > last && total > 0.5f * ball.mass() * 4, "the step's impulse stopped it: " + total);
                } else {
                    assertTrue(total > 0, "touching");
                    assertEquals(0, approach, 0.0, "not measured without hit events");
                }
            }
        }
    }

    // ---- the cached motion after each new way of changing it --------------------------------------

    private static void sameAsBox3D(Body body, String when) {
        float[] v = new float[3];
        B3.bodyGetLinearVelocity(body.id(), v);
        assertEquals(new Vector3f(v[0], v[1], v[2]), body.linearVelocity(new Vector3f()), "linear velocity " + when);
        B3.bodyGetAngularVelocity(body.id(), v);
        assertEquals(new Vector3f(v[0], v[1], v[2]), body.angularVelocity(new Vector3f()), "angular velocity " + when);
    }

    /** Box3D keeps a spinning body's points moving as they were when its centre of mass moves: v += w x (c' - c). */
    @Test
    void movingTheCentreOfMassOfASpinningBodyIsSeenInItsCachedVelocity() {
        try (PhysicsWorld world = world(0)) {
            Body body = cube(world, 0, 0, 0, 0.5f);
            body.setAngularVelocity(0, 3, 0);
            steps(world, 1); // the step's move event fills the cache
            Vector3f before = body.linearVelocity(new Vector3f());

            Shape side = body.createShape(new ShapeDef().density(1000), Geometry.Box.of(0.5f, 0.5f, 0.5f, 2, 0, 0));
            sameAsBox3D(body, "after a shape off the centre");
            assertNotEquals(
                    before, body.linearVelocity(new Vector3f()), "and it did change (else this proves nothing)");

            steps(world, 1);
            before = body.linearVelocity(new Vector3f());
            side.setDensity(4000, true);
            sameAsBox3D(body, "after a heavier side");
            assertNotEquals(before, body.linearVelocity(new Vector3f()));

            steps(world, 1);
            before = body.linearVelocity(new Vector3f());
            side.setGeometry(Geometry.Box.of(0.5f, 0.5f, 0.5f, -2, 0, 0), true);
            sameAsBox3D(body, "after the side moved over");
            assertNotEquals(before, body.linearVelocity(new Vector3f()));

            steps(world, 1);
            before = body.linearVelocity(new Vector3f());
            body.setMassData(new MassData().set(10, 0.4f, 0, 0).inertiaOf(1));
            sameAsBox3D(body, "after mass data");
            assertNotEquals(before, body.linearVelocity(new Vector3f()));

            steps(world, 1);
            before = body.linearVelocity(new Vector3f());
            body.applyMassFromShapes();
            sameAsBox3D(body, "after the shapes' mass again");
            assertNotEquals(before, body.linearVelocity(new Vector3f()));

            steps(world, 1);
            before = body.linearVelocity(new Vector3f());
            side.destroy(true);
            sameAsBox3D(body, "after the side went");
            assertNotEquals(before, body.linearVelocity(new Vector3f()));

            steps(world, 1);
            body.setFixedRotation(true);
            sameAsBox3D(body, "after fixing its rotation");
        }
    }

    @Test
    void anExplosionIsSeenInTheCachedVelocityOfWhatItPushed() {
        try (PhysicsWorld world = world(0)) {
            Body body = cube(world, 2, 0, 0, 0.25f);
            body.setLinearVelocity(0, 0, 1);
            steps(world, 1);
            assertEquals(1, body.linearVelocity(new Vector3f()).z, 1e-4, "cached by the step");
            world.explode(0, 0, 0, 3, 0, 500);
            sameAsBox3D(body, "after the blast");
            assertTrue(body.linearVelocity(new Vector3f()).x > 0.5f);
        }
    }

    @Test
    void anInertiaThatIsNotOneIsRefused() {
        try (PhysicsWorld world = world(0)) {
            Body body = cube(world, 0, 0, 0, 0.5f);
            MassData twoNegative = new MassData().set(1, 0, 0, 0);
            twoNegative.inertia.set(-1, 0, 0, 0, -1, 0, 0, 0, 1); // determinant 1: Box3D would take it
            assertThrows(IllegalArgumentException.class, () -> body.setMassData(twoNegative));
            MassData lopsided = new MassData().set(1, 0, 0, 0);
            lopsided.inertia.set(1, 0.5f, 0, 0, 1, 0, 0, 0, 1);
            assertThrows(IllegalArgumentException.class, () -> body.setMassData(lopsided), "not symmetric");
            MassData negativeDeterminant = new MassData().set(1, 0, 0, 0);
            negativeDeterminant.inertia.set(1, 2, 0, 2, 1, 0, 0, 0, 1);
            assertThrows(IllegalArgumentException.class, () -> body.setMassData(negativeDeterminant));
            assertEquals(1000, body.mass(), 1e-2, "nothing reached Box3D");
            body.setMassData(new MassData().set(5, 0, 0, 0).inertiaOf(0.1f));
            assertEquals(5, body.mass(), 1e-5);
        }
    }

    @Test
    void aBodyWithAHeightFieldStaysStatic() {
        try (PhysicsWorld world = world(0);
                HeightFieldData data = HeightFieldData.create(new float[] {0, 0, 0, 0}, 2, 2)) {
            Body ground = world.createBody(new BodyDef());
            ground.createShape(new ShapeDef(), new Geometry.HeightField(data));
            assertThrows(IllegalStateException.class, () -> ground.setType(BodyType.DYNAMIC));
            assertEquals(BodyType.STATIC, ground.type());
            assertEquals(BodyType.STATIC.ordinal(), B3.bodyGetType(ground.id()));
        }
    }

    /**
     * Box3D asserts unit length to 20 float epsilons; the bridge takes what Java lets through the last bit of the way.
     */
    @Test
    void aNearlyUnitRotationIsTakenAsTheUnitOne() {
        try (PhysicsWorld world = world(0)) {
            Quaternionf typed = new Quaternionf(0, 0.7071f, 0, 0.7071f); // |q|² = 0.99998
            Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC).rotation(typed));
            Geometry.Box turned = new Geometry.Box(0.5f, 0.5f, 0.5f, 0, 0, 0, typed.x, typed.y, typed.z, typed.w);
            Shape shape = body.createShape(new ShapeDef(), turned);
            Geometry.Box wider = new Geometry.Box(1, 0.5f, 0.5f, 0, 0, 0, typed.x, typed.y, typed.z, typed.w);
            shape.setGeometry(wider, true);
            assertSame(wider, shape.geometry());
            body.setTargetTransform(0, 1, 0, typed, 1 / 20f);
            body.setTransform(0, 0, 0, typed);
            steps(world, 1);
        }
    }

    @Test
    void aTeleportThatDoesNotWakeLeavesTheBodyAsleepWhereQueriesStillMeetIt() {
        try (PhysicsWorld world = world(-10)) {
            Body volume =
                    world.createBody(new BodyDef().type(BodyType.KINEMATIC).awake(false));
            volume.createShape(new ShapeDef().sensor(true), new Geometry.Sphere(0, 0, 0, 0.25f));
            steps(world, 1);
            assertFalse(volume.isAwake(), "made asleep");

            volume.setTransform(5, 0, 0, new Quaternionf(), false);
            steps(world, 3);
            assertFalse(volume.isAwake(), "the teleport left it asleep, and so did the steps");
            assertEquals(0, world.awakeBodyCount());
            CastHits hits = new CastHits();
            assertEquals(1, world.castRay(0, 0, 0, 10, 0, 0, QueryFilter.ALL, hits), "met where it was put");
            assertEquals(4.75, hits.x(0), 1e-4);
            Overlaps found = new Overlaps();
            assertEquals(
                    1,
                    world.overlapShape(
                            new Geometry.Sphere(0, 0, 0, 0.1f), 5, 0, 0, new Quaternionf(), QueryFilter.ALL, found));

            volume.setTransform(6, 0, 0, new Quaternionf());
            assertTrue(volume.isAwake(), "a plain teleport still wakes it");
        }
    }

    /**
     * Switching a sleeping body off left its sleeping set behind, empty, never woken and never freed before the world
     * went (fork, doc 02 §3.1): a query volume switched off and on every swing grew Box3D's memory for good.
     */
    @Test
    void aSleepingBodySwitchedOffAndOnKeepsNothingBehind() {
        try (PhysicsWorld world = world(0)) {
            Body volume =
                    world.createBody(new BodyDef().type(BodyType.KINEMATIC).awake(false));
            volume.createShape(new ShapeDef().sensor(true), new Geometry.Sphere(0, 0, 0, 0.25f));
            for (int i = 0; i < 20; i++) {
                switchOffAndOnAsleep(volume);
            }
            long before = B3.getByteCount();
            for (int i = 0; i < 1000; i++) {
                switchOffAndOnAsleep(volume);
            }
            assertEquals(before, B3.getByteCount(), "bytes Box3D holds");
            assertFalse(volume.isAwake());
            assertTrue(volume.isEnabled());
        }
    }

    private static void switchOffAndOnAsleep(Body body) {
        body.setEnabled(false);
        body.setEnabled(true);
        body.setAwake(false);
    }

    @Test
    void whatBox3DWouldStoreIsRefusedFirst() {
        try (PhysicsWorld world = world(0)) {
            Body body = cube(world, 0, 0, 0, 0.5f);
            float nan = Float.NaN;
            List<Executable> refused = List.of(
                    () -> body.setLinearDamping(-1),
                    () -> body.setAngularDamping(nan),
                    () -> body.setSleepThreshold(-0.1f),
                    () -> body.setSafetyFactor(0.9f),
                    () -> body.setMassData(new MassData().set(-1, 0, 0, 0)),
                    () -> body.setMassData(new MassData().set(1, nan, 0, 0)),
                    () -> body.applyForce(nan, 0, 0, 0, 0, 0),
                    () -> body.applyTorque(0, Float.POSITIVE_INFINITY, 0),
                    () -> body.applyAngularImpulse(0, 0, nan),
                    () -> body.pointVelocity(Double.NaN, 0, 0, new Vector3f()),
                    () -> body.closestPoint(0, Double.NaN, 0, new Vector3d()),
                    () -> body.overlaps(Geometry.Mesh.of(null), 0, 0, 0, new Quaternionf(), QueryFilter.ALL));
            for (Executable call : refused) {
                assertThrows(IllegalArgumentException.class, call);
            }
            MassData invalidInertia = new MassData().set(1, 0, 0, 0);
            invalidInertia.inertia.m11 = nan;
            assertThrows(IllegalArgumentException.class, () -> body.setMassData(invalidInertia));
            assertEquals(1000, body.mass(), 1e-2, "nothing reached Box3D");
        }
    }
}
