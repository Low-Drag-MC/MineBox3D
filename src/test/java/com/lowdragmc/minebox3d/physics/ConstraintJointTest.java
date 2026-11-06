package com.lowdragmc.minebox3d.physics;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>The constraint joint</b> (design doc {@code 05-constraint-joint.md}): the settings round trip, every motion
 * combination holds its limit — the overshoot measured, not assumed — the drives reach their targets and let go when turned
 * off, soft limits give and hard ones bounce by their restitution, parent dominates and mass conditioning act, and the
 * batches and the body pair table do what they say.
 *
 * <p>The test rig: a static anchor at the origin and an 8 kg bar hanging 0.8 m below it, the joint at the anchor with
 * frame x pointing down along the bar — so the twist turns about world -y, swing 1 about world z, swing 2 about world x.
 */
class ConstraintJointTest {

    private static final float PI = (float) Math.PI;
    /** frame x pointing down: x → -y, y → +x, z stays */
    private static final Quaternionf DOWN = new Quaternionf().rotateZ(-PI / 2);
    /** the most a hard limit may be passed while a torque drives the bar into it, radians */
    private static final float ANGLE_TOLERANCE = 0.02f;

    private static PhysicsWorld world(float gravity) {
        return PhysicsWorld.create(new WorldDef().gravity(0, gravity, 0));
    }

    private static Body bar(PhysicsWorld world) {
        Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, -0.4, 0).enableSleep(false));
        body.createShape(new ShapeDef().density(1000), Geometry.Box.of(0.05f, 0.4f, 0.05f));
        return body;
    }

    private static ConstraintJoint hang(PhysicsWorld world, Body bar, ConstraintJointSettings settings) {
        Body anchor = world.createBody(new BodyDef());
        return world.createJoint(new ConstraintJointDef().bodies(anchor, bar).worldFrame(0, 0, 0, DOWN).settings(settings));
    }

    private static void step(PhysicsWorld world) {
        world.beginInterpolationFrame();
        world.step(1 / 60f, 4);
    }

    /** Pushes the bar with a constant torque (world) for the frames; returns the largest |twist|, |swing 1|, |swing 2| seen. */
    private static Vector3f push(PhysicsWorld world, Body bar, ConstraintJoint joint, float tx, float ty, float tz, int frames,
                                 Vector3f last) {
        Vector3f most = new Vector3f();
        for (int i = 0; i < frames; i++) {
            bar.applyTorque(tx, ty, tz);
            step(world);
            joint.angles(last);
            most.set(Math.max(most.x, Math.abs(last.x)), Math.max(most.y, Math.abs(last.y)), Math.max(most.z, Math.abs(last.z)));
        }
        return most;
    }

    // ---- settings ------------------------------------------------------------------------------

    /** The bridge passes {@link JointType}'s ordinal for Box3D's enum: an upstream sync that adds a type shifts ours. */
    @Test
    void javasJointTypesAreBox3DsNumbers() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJoint constraint = hang(world, bar, new ConstraintJointSettings());
            assertEquals(JointType.CONSTRAINT.ordinal(), com.lowdragmc.minebox3d.internal.B3.jointGetType(constraint.id()));
            MotorJoint motor = world.createJoint(new MotorJointDef().bodies(world.createBody(new BodyDef()), bar));
            assertEquals(JointType.MOTOR.ordinal(), com.lowdragmc.minebox3d.internal.B3.jointGetType(motor.id()));
            assertEquals(JointType.WHEEL.ordinal() + 3, JointType.CONSTRAINT.ordinal(), "Box3D's mover and pogo joints sit between");
        }
    }

    @Test
    void newSettingsAreABallAndSocketWithUesDefaults() {
        ConstraintJointSettings s = new ConstraintJointSettings();
        assertEquals(ConstraintMotion.LOCKED, s.linearMotionX);
        assertEquals(ConstraintMotion.LOCKED, s.linearMotionY);
        assertEquals(ConstraintMotion.LOCKED, s.linearMotionZ);
        assertEquals(ConstraintMotion.FREE, s.twistMotion);
        assertEquals(ConstraintMotion.FREE, s.swing1Motion);
        assertEquals(ConstraintMotion.FREE, s.swing2Motion);
        assertEquals(-PI / 4, s.lowerTwistLimit, 1e-6f);
        assertEquals(PI / 4, s.upperTwistLimit, 1e-6f);
        assertEquals(PI / 4, s.swing1Limit, 1e-6f);
        assertEquals(PI / 4, s.swing2Limit, 1e-6f);
        assertEquals(SwingLimitShape.ELLIPSE, s.swingLimitShape);
        assertEquals(AngularDriveMode.SLERP, s.angularDriveMode);
        assertTrue(s.slerpDrive.accelerationMode);
        assertFalse(s.slerpDrive.positionDrive);
        assertEquals(1, s.angularOrientationTargetW);
        assertEquals(1, s.parentInverseMassScale);
        assertTrue(s.massConditioning);
        assertEquals(s, new ConstraintJointDef().settings, "a definition starts from the same defaults");
    }

    @Test
    void everySettingReadsBackFromTheJointAndAProfileSwapReplacesThemAll() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJointSettings s = every(new ConstraintJointSettings());
            ConstraintJoint joint = hang(world, bar, s);
            assertEquals(s, joint.settings(new ConstraintJointSettings()), "what the definition set");

            ConstraintJointSettings other = new ConstraintJointSettings();
            other.twistMotion = ConstraintMotion.LOCKED;
            other.swingDrive.drive(5, 1, 0);
            joint.setSettings(other);
            assertEquals(other, joint.settings(new ConstraintJointSettings()), "a swap replaces every field");
            joint.setSettings(s);
            assertEquals(s, joint.settings(new ConstraintJointSettings()));
        }
    }

    /** Every field away from its default and from every other, so a field read into another's place shows. */
    private static ConstraintJointSettings every(ConstraintJointSettings s) {
        s.linearMotionX = ConstraintMotion.FREE;
        s.linearMotionY = ConstraintMotion.LIMITED;
        s.linearMotionZ = ConstraintMotion.LOCKED;
        s.linearLimit = 0.31f;
        response(s.linearLimitResponse, 1);
        s.twistMotion = ConstraintMotion.LIMITED;
        s.swing1Motion = ConstraintMotion.LOCKED;
        s.swing2Motion = ConstraintMotion.LIMITED;
        s.lowerTwistLimit = -0.42f;
        s.upperTwistLimit = 0.53f;
        s.swing1Limit = 0.64f;
        s.swing2Limit = 0.75f;
        s.swingLimitShape = SwingLimitShape.PYRAMID;
        response(s.twistLimitResponse, 2);
        response(s.swingLimitResponse, 3);
        drive(s.linearDriveX, 4);
        drive(s.linearDriveY, 5);
        drive(s.linearDriveZ, 6);
        s.linearPositionTarget(0.11f, 0.12f, 0.13f).linearVelocityTarget(0.14f, 0.15f, 0.16f);
        s.angularDriveMode = AngularDriveMode.TWIST_SWING;
        drive(s.slerpDrive, 7);
        drive(s.twistDrive, 8);
        drive(s.swingDrive, 9);
        s.angularOrientationTarget(new Quaternionf(0, 0, 0.6f, 0.8f));
        s.angularVelocityTarget(0.17f, 0.18f, 0.19f);
        s.parentInverseMassScale = 0.25f;
        s.massConditioning = false;
        return s;
    }

    private static void response(ConstraintLimitResponse r, int n) {
        r.soft = n % 2 == 1;
        r.stiffness = 10 * n + 1;
        r.damping = 10 * n + 2;
        r.accelerationMode = n % 2 == 0;
        r.restitution = 0.1f * n;
    }

    private static void drive(ConstraintDrive d, int n) {
        d.positionDrive = n % 2 == 0;
        d.velocityDrive = n % 3 == 0;
        d.stiffness = 100 * n + 1;
        d.damping = 100 * n + 2;
        d.accelerationMode = n % 2 == 1;
        d.maxForce = 100 * n + 3;
    }

    // ---- limits --------------------------------------------------------------------------------

    @Test
    void aBallAndSocketKeepsItsAnchorTogether() {
        try (PhysicsWorld world = world(-10)) {
            Body bar = bar(world);
            bar.setLinearVelocity(3, 0, 1);
            ConstraintJoint joint = hang(world, bar, new ConstraintJointSettings());
            float most = 0;
            for (int i = 0; i < 180; i++) {
                step(world);
                most = Math.max(most, joint.linearSeparation());
            }
            assertTrue(most < 0.005f, "the anchor drifted " + most + " m while swinging");
            assertTrue(joint.angles(new Vector3f()).length() > 0.1f, "it swung");
        }
    }

    @Test
    void aTwistLimitHoldsBothWays() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJointSettings s = new ConstraintJointSettings().angularMotion(ConstraintMotion.LOCKED);
            s.twistMotion = ConstraintMotion.LIMITED;
            s.twistLimits(-0.2f, 0.5f);
            ConstraintJoint joint = hang(world, bar, s);
            Vector3f last = new Vector3f();

            push(world, bar, joint, 0, -30, 0, 90, last);
            assertEquals(0.5f, last.x, ANGLE_TOLERANCE, "against the upper limit (twist about -y)");
            push(world, bar, joint, 0, 30, 0, 90, last);
            assertEquals(-0.2f, last.x, ANGLE_TOLERANCE, "against the lower limit");
            assertEquals(0, joint.angularSeparation(), ANGLE_TOLERANCE);
            assertTrue(Math.abs(last.y) < 0.01f && Math.abs(last.z) < 0.01f, "the swings stay locked: " + last);
        }
    }

    @Test
    void anEllipticalConeStopsADiagonalSwingOnTheEllipse() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJointSettings s = new ConstraintJointSettings().swingLimits(0.6f, 0.3f);
            s.twistMotion = ConstraintMotion.LOCKED;
            s.swing1Motion = ConstraintMotion.LIMITED;
            s.swing2Motion = ConstraintMotion.LIMITED;
            // conditioning lifts the bar's thin twist inertia for the joint, which moves where a push comes to rest
            s.massConditioning = false;
            ConstraintJoint joint = hang(world, bar, s);
            // nothing rubs along a limit: undamped, the bar slides to and fro round the ellipse for seconds
            bar.setAngularDamping(3);
            Vector3f last = new Vector3f();

            push(world, bar, joint, 25, 0, 25, 900, last);
            float reach = (float) Math.hypot(last.y / 0.6f, last.z / 0.3f);
            assertEquals(1, reach, 0.01f, "on the ellipse: swing 1 " + last.y + ", swing 2 " + last.z);
            // pushed back along the ellipse's normal, it rests where the normal lies along the push, which with the twist
            // locked is 25 : 24.3 there — swing 1 about 4.1 times swing 2 (a radial push would rest at 1 : 1)
            assertEquals(4.1f, Math.abs(last.y / last.z), 0.2f, "where the normal points along the push: " + last);
            assertEquals(0, joint.angularSeparation(), ANGLE_TOLERANCE);
        }
    }

    @Test
    void anEllipsePressedAlongItsWideAxisStopsThereWithTheOtherSwingAtNoise() {
        // the rig turned about the vertical so the swing nobody pushes reads float noise rather than exactly nothing: the
        // nearest point on the ellipse is then a root next to its pole, which must still come out at the wide limit
        Quaternionf turn = new Quaternionf().rotateY(0.7f);
        try (PhysicsWorld world = world(0)) {
            Body bar = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, -0.4, 0).rotation(turn).enableSleep(false));
            bar.createShape(new ShapeDef().density(1000), Geometry.Box.of(0.05f, 0.4f, 0.05f));
            ConstraintJointSettings s = new ConstraintJointSettings().swingLimits(0.6f, 0.3f);
            s.twistMotion = ConstraintMotion.LOCKED;
            s.swing1Motion = ConstraintMotion.LIMITED;
            s.swing2Motion = ConstraintMotion.LIMITED;
            s.massConditioning = false;
            Body anchor = world.createBody(new BodyDef());
            ConstraintJoint joint = world.createJoint(new ConstraintJointDef().bodies(anchor, bar)
                    .worldFrame(0, 0, 0, new Quaternionf(turn).mul(DOWN)).settings(s));
            bar.setAngularDamping(3);
            // swing 1 turns about frame A's z, the rig's z turned with it
            Vector3f axis = turn.transform(new Vector3f(0, 0, 1));
            Vector3f last = new Vector3f();
            float sideways = 0;
            for (int i = 0; i < 240; i++) {
                bar.applyTorque(25 * axis.x, 25 * axis.y, 25 * axis.z);
                step(world);
                joint.angles(last);
                sideways = Math.max(sideways, Math.abs(last.z));
            }
            assertEquals(0.6f, Math.abs(last.y), ANGLE_TOLERANCE, "at the wide limit: " + last);
            assertTrue(sideways < 0.01f, "never kicked out to the side: " + sideways);
            assertEquals(0, joint.angularSeparation(), ANGLE_TOLERANCE);
        }
    }

    @Test
    void aBarSpinningOnItsOwnAxisBesideTheEllipseKeepsItsSpin() {
        // swung near the wide limit with the twist free, spun about its own length and nothing else: the cone's row has
        // nothing to do and must not touch the spin
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJointSettings s = new ConstraintJointSettings().swingLimits(0.6f, 0.3f);
            s.swing1Motion = ConstraintMotion.LIMITED;
            s.swing2Motion = ConstraintMotion.LIMITED;
            ConstraintJoint joint = hang(world, bar, s);
            Quaternionf swung = new Quaternionf().rotateZ(0.57f);
            Vector3f down = swung.transform(new Vector3f(0, -0.4f, 0));
            bar.setTransform(down.x, down.y, down.z, swung);
            Vector3f along = swung.transform(new Vector3f(0, -1, 0));
            bar.setAngularVelocity(5 * along.x, 5 * along.y, 5 * along.z);
            for (int i = 0; i < 60; i++) {
                step(world);
            }
            Vector3f w = bar.angularVelocity(new Vector3f());
            assertEquals(5, w.dot(along), 0.05f, "the spin kept: " + w);
            assertEquals(0.57f, Math.abs(joint.angles(new Vector3f()).y), 0.01f, "and the swing where it was");
        }
    }

    @Test
    void aPyramidLetsADiagonalSwingReachBothLimits() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJointSettings s = new ConstraintJointSettings().swingLimits(0.6f, 0.3f);
            s.twistMotion = ConstraintMotion.LOCKED;
            s.swing1Motion = ConstraintMotion.LIMITED;
            s.swing2Motion = ConstraintMotion.LIMITED;
            s.swingLimitShape = SwingLimitShape.PYRAMID;
            ConstraintJoint joint = hang(world, bar, s);
            Vector3f last = new Vector3f();

            Vector3f most = push(world, bar, joint, 25, 0, 25, 120, last);
            assertEquals(0.6f, Math.abs(last.y), 0.03f, "swing 1 at its limit: " + last);
            assertEquals(0.3f, Math.abs(last.z), 0.03f, "swing 2 at its limit: " + last);
            assertTrue(most.y < 0.6f + ANGLE_TOLERANCE && most.z < 0.3f + ANGLE_TOLERANCE, "never past either: " + most);
        }
    }

    @Test
    void aHingeTurnsOnlyAboutSwingOneWithinItsLimit() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJointSettings s = new ConstraintJointSettings().swingLimits(0.4f, 0);
            s.twistMotion = ConstraintMotion.LOCKED;
            s.swing1Motion = ConstraintMotion.LIMITED;
            s.swing2Motion = ConstraintMotion.LOCKED;
            ConstraintJoint joint = hang(world, bar, s);
            Vector3f last = new Vector3f();

            // the twist is about the bar's own thin axis: 2 N·m there is as much as 20 across
            Vector3f most = push(world, bar, joint, 20, 2, 20, 120, last);
            assertEquals(0.4f, Math.abs(last.y), ANGLE_TOLERANCE, "swing 1 at its limit");
            assertTrue(most.y < 0.4f + ANGLE_TOLERANCE, "never past it: " + most.y);
            assertTrue(most.x < 0.01f && most.z < 0.01f, "twist and swing 2 locked: " + most);
        }
    }

    @Test
    void aDualConeLimitsTheLeanOutOfTheFreeSwingsPlane() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJointSettings s = new ConstraintJointSettings().swingLimits(0.4f, 0);
            s.twistMotion = ConstraintMotion.LOCKED;
            s.swing1Motion = ConstraintMotion.LIMITED;
            ConstraintJoint joint = hang(world, bar, s);
            Vector3f last = new Vector3f();

            Vector3f leaning = push(world, bar, joint, 0, 0, 20, 60, last);
            assertEquals(0.4f, Math.abs(last.y), ANGLE_TOLERANCE, "the lean at its limit");
            Vector3f most = push(world, bar, joint, 20, 0, 20, 120, last);
            assertTrue(Math.max(leaning.y, most.y) < 0.4f + ANGLE_TOLERANCE, "never past it: " + most.y);
            assertTrue(most.z > 0.8f, "swing 2 turns freely the while: " + most.z);
        }
    }

    @Test
    void oneSwingLockedWithTheTwistFreeKeepsTheBarInThePlane() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJointSettings s = new ConstraintJointSettings();
            s.swing2Motion = ConstraintMotion.LOCKED;
            ConstraintJoint joint = hang(world, bar, s);
            Vector3f last = new Vector3f();

            Vector3f most = push(world, bar, joint, 15, -15, 15, 150, last);
            assertTrue(most.x > 0.5f && most.y > 0.5f, "twist and swing 1 turn freely: " + most);
            assertTrue(joint.angularSeparation() < ANGLE_TOLERANCE, "swing 2 held: " + joint.angularSeparation());
        }
    }

    @Test
    void aLinearLimitKeepsTheAnchorWithinItsSphere() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJointSettings s = new ConstraintJointSettings().linearMotion(ConstraintMotion.LIMITED);
            s.linearLimit = 0.2f;
            ConstraintJoint joint = hang(world, bar, s);
            Vector3f offset = new Vector3f();
            float most = 0;
            for (int i = 0; i < 120; i++) {
                bar.applyForceToCenter(400, -400, 0);
                step(world);
                most = Math.max(most, joint.linearOffset(offset).length());
            }
            assertEquals(0.2f, offset.length(), 0.01f, "against the sphere");
            assertTrue(most < 0.2f + 0.01f, "never past it: " + most);
        }
    }

    @Test
    void aSoftLimitGivesByItsSpringAndAHardOneBouncesByItsRestitution() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJointSettings s = new ConstraintJointSettings().angularMotion(ConstraintMotion.LOCKED);
            s.twistMotion = ConstraintMotion.LIMITED;
            s.twistLimit(0.2f);
            s.twistLimitResponse.soft(50, 10);
            s.twistLimitResponse.accelerationMode = false;
            // conditioning would lift the bar's thin twist inertia for the joint, making the spring answer slower
            s.massConditioning = false;
            ConstraintJoint joint = hang(world, bar, s);
            Vector3f last = new Vector3f();

            push(world, bar, joint, 0, -5, 0, 240, last);
            // a 50 N·m/rad spring against 5 N·m settles a tenth of a radian past
            assertEquals(0.3f, last.x, 0.02f, "soft: past the limit by torque / stiffness");
        }

        for (float restitution : new float[]{0, 0.8f}) {
            try (PhysicsWorld world = world(0)) {
                Body bar = bar(world);
                ConstraintJointSettings s = new ConstraintJointSettings().angularMotion(ConstraintMotion.LOCKED);
                s.twistMotion = ConstraintMotion.LIMITED;
                s.twistLimit(0.2f);
                s.twistLimitResponse.restitution = restitution;
                ConstraintJoint joint = hang(world, bar, s);
                bar.setAngularVelocity(0, -3, 0);
                Vector3f w = new Vector3f();
                float back = 0;
                for (int i = 0; i < 30; i++) {
                    step(world);
                    back = Math.max(back, bar.angularVelocity(w).y);
                }
                if (restitution == 0) {
                    assertTrue(back < 0.1f, "no restitution stops it: " + back);
                } else {
                    assertEquals(0.8f * 3, back, 0.4f, "bounces back at 0.8 of 3 rad/s: " + back);
                }
                assertTrue(joint.angles(new Vector3f()).x < 0.2f + ANGLE_TOLERANCE);
            }
        }
    }

    // ---- drives --------------------------------------------------------------------------------

    @Test
    void aSlerpDriveTurnsTheBarToItsTargetUnlessAnAngleIsLocked() {
        Quaternionf target = new Quaternionf().rotateZ(0.5f);
        for (boolean lockTwist : new boolean[]{false, true}) {
            try (PhysicsWorld world = world(0)) {
                Body bar = bar(world);
                ConstraintJointSettings s = new ConstraintJointSettings().angularOrientationTarget(target);
                s.slerpDrive.drive(200, 30, 0);
                if (lockTwist) {
                    s.twistMotion = ConstraintMotion.LOCKED;
                }
                ConstraintJoint joint = hang(world, bar, s);
                for (int i = 0; i < 180; i++) {
                    step(world);
                }
                Vector3f angles = joint.angles(new Vector3f());
                if (lockTwist) {
                    assertEquals(0, angles.y, 1e-3f, "UE: no slerp drive while an angle is locked");
                } else {
                    assertEquals(0.5f, angles.y, 0.01f, "swing 1 at the target: " + angles);
                }
            }
        }
    }

    @Test
    void twistAndSwingDrivesTurnEachAngleToItsTarget() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            Quaternionf target = new Quaternionf().rotateZ(-0.3f).rotateX(0.4f);
            ConstraintJointSettings s = new ConstraintJointSettings().angularOrientationTarget(target);
            s.angularDriveMode = AngularDriveMode.TWIST_SWING;
            s.twistDrive.drive(200, 30, 0);
            s.swingDrive.drive(200, 30, 0);
            ConstraintJoint joint = hang(world, bar, s);
            for (int i = 0; i < 240; i++) {
                step(world);
            }
            Vector3f angles = joint.angles(new Vector3f());
            assertEquals(0.4f, angles.x, 0.01f, "twist: " + angles);
            assertEquals(-0.3f, angles.y, 0.01f, "swing 1: " + angles);
        }
    }

    @Test
    void aVelocityDriveSpinsTheTwistAtItsTargetRate() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJointSettings s = new ConstraintJointSettings().angularVelocityTarget(2, 0, 0);
            s.angularDriveMode = AngularDriveMode.TWIST_SWING;
            s.twistDrive.velocityDrive = true;
            s.twistDrive.damping = 50;
            hang(world, bar, s);
            for (int i = 0; i < 60; i++) {
                step(world);
            }
            // frame B's x is world -y
            assertEquals(-2, bar.angularVelocity(new Vector3f()).y, 0.05f);
        }
    }

    @Test
    void aDriveCappedBelowTheLoadCannotHoldIt() {
        for (float maxForce : new float[]{0, 20}) {
            try (PhysicsWorld world = world(0)) {
                Body bar = bar(world);
                ConstraintJointSettings s = new ConstraintJointSettings();
                s.slerpDrive.drive(2000, 90, maxForce);
                ConstraintJoint joint = hang(world, bar, s);
                Vector3f last = new Vector3f();
                Vector3f most = push(world, bar, joint, 0, 0, 40, 120, last);
                if (maxForce == 0) {
                    assertTrue(most.y < 0.15f, "uncapped, it holds near the target: " + most);
                } else {
                    assertTrue(most.y > 1, "20 rad/s², 9 N·m on this bar, against 40 N·m gives way: " + most);
                }
            }
        }
    }

    @Test
    void anAccelerationModeCapIsPerUnitOfTheAxissMass() {
        // UE's default solver: the cap times the row's mass, the bar's 0.43 kg·m² about its centre — 60 rad/s² is 26 N·m and
        // gives way under 40 N·m, where a physical cap of 60 N·m on the same spring holds
        for (boolean acceleration : new boolean[]{true, false}) {
            try (PhysicsWorld world = world(0)) {
                Body bar = bar(world);
                ConstraintJointSettings s = new ConstraintJointSettings();
                if (acceleration) {
                    s.slerpDrive.drive(2000, 90, 60);
                } else {
                    s.slerpDrive.drive(866, 39, 60);
                    s.slerpDrive.accelerationMode = false;
                }
                ConstraintJoint joint = hang(world, bar, s);
                Vector3f most = push(world, bar, joint, 0, 0, 40, 120, new Vector3f());
                if (acceleration) {
                    assertTrue(most.y > 1, "26 N·m against 40 N·m gives way: " + most);
                } else {
                    assertTrue(most.y < 0.15f, "60 N·m against 40 N·m holds: " + most);
                }
            }
        }
    }

    @Test
    void aSliderOnASpinningParentStaysOnItsRail() {
        // the child 1 m out along the parent's x, free to slide out to there and held off its y and z, the pair spun about z
        // with no gravity: the locks hold the child's anchor on a rail turning with the parent, so they push the parent at
        // the child's anchor too — off the rail by nothing, the pair's angular momentum kept
        try (PhysicsWorld world = world(0)) {
            Body parent = world.createBody(new BodyDef().type(BodyType.DYNAMIC).enableSleep(false));
            parent.createShape(new ShapeDef().density(1250), Geometry.Box.of(0.1f, 0.1f, 0.1f));
            Body child = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(1, 0, 0).enableSleep(false));
            child.createShape(new ShapeDef().density(1000), Geometry.Box.of(0.05f, 0.05f, 0.05f));
            ConstraintJointSettings s = new ConstraintJointSettings();
            s.linearMotionX = ConstraintMotion.LIMITED;
            s.linearLimit = 1;
            s.twistMotion = ConstraintMotion.LOCKED;
            s.swing1Motion = ConstraintMotion.LOCKED;
            s.swing2Motion = ConstraintMotion.LOCKED;
            s.massConditioning = false;
            ConstraintJoint joint = world.createJoint(new ConstraintJointDef().bodies(parent, child)
                    .localFrameA(0, 0, 0, new Quaternionf()).localFrameB(0, 0, 0, new Quaternionf()).settings(s));
            parent.setAngularVelocity(0, 0, 2);
            child.setAngularVelocity(0, 0, 2);
            child.setLinearVelocity(0, 2, 0);
            double before = spin(parent, child);
            float off = 0;
            for (int i = 0; i < 120; i++) {
                step(world);
                Vector3f offset = joint.linearOffset(new Vector3f());
                off = Math.max(off, Math.max(Math.abs(offset.y), Math.abs(offset.z)));
            }
            assertTrue(off < 2e-3f, "off the rail by " + off + " m");
            assertEquals(before, spin(parent, child), 0.01 * before, "the angular momentum about z kept");
        }
    }

    /** The pair's angular momentum about z through their centre of mass. */
    private static double spin(Body... bodies) {
        double mass = 0, cx = 0, cy = 0, vx = 0, vy = 0;
        Vector3d p = new Vector3d();
        Vector3f v = new Vector3f();
        for (Body body : bodies) {
            float m = body.mass();
            body.worldCenter(p);
            body.linearVelocity(v);
            mass += m;
            cx += m * p.x;
            cy += m * p.y;
            vx += m * v.x;
            vy += m * v.y;
        }
        cx /= mass;
        cy /= mass;
        vx /= mass;
        vy /= mass;
        double spin = 0;
        MassData data = new MassData();
        for (Body body : bodies) {
            float m = body.mass();
            body.worldCenter(p);
            body.linearVelocity(v);
            // cubes: the same inertia about every axis
            spin += body.massData(data).inertia.m22() * body.angularVelocity(new Vector3f()).z
                    + m * ((p.x - cx) * (v.y - vy) - (p.y - cy) * (v.x - vx));
        }
        return spin;
    }

    @Test
    void aLinearDrivePullsTheAnchorToItsTarget() {
        try (PhysicsWorld world = world(0)) {
            Body bar = bar(world);
            ConstraintJointSettings s = new ConstraintJointSettings().linearPositionTarget(0.3f, 0, 0);
            s.linearMotionX = ConstraintMotion.FREE;
            s.linearDriveX.drive(100, 20, 0);
            ConstraintJoint joint = hang(world, bar, s);
            for (int i = 0; i < 180; i++) {
                step(world);
            }
            Vector3f offset = joint.linearOffset(new Vector3f());
            assertEquals(0.3f, offset.x, 0.005f, "along frame A's x: " + offset);
            assertEquals(0, offset.y, 1e-3f);
            assertEquals(-0.3f, bar.position(new Vector3d()).y + 0.4f, 0.005f, "frame A's x is world -y");
        }
    }

    @Test
    void turningADriveOffLetsGoOfItsImpulse() {
        try (PhysicsWorld world = world(-10)) {
            Body bar = bar(world);
            ConstraintJointSettings s = new ConstraintJointSettings().angularOrientationTarget(new Quaternionf().rotateZ(PI / 2));
            // acceleration mode scales by the row's mass, the bar's 0.43 kg·m² about its centre: 860 N·m/rad against 32 N·m
            s.slerpDrive.drive(2000, 90, 0);
            ConstraintJoint joint = hang(world, bar, s);
            Vector3f torque = new Vector3f();
            for (int i = 0; i < 120; i++) {
                step(world);
            }
            assertEquals(PI / 2, joint.angles(new Vector3f()).y, 0.05f, "held out level against gravity");
            assertTrue(joint.constraintTorque(torque).length() > 20, "by the drive's torque: " + torque);

            s.slerpDrive.positionDrive = false;
            s.slerpDrive.velocityDrive = false;
            joint.setSettings(s);
            for (int i = 0; i < 30; i++) {
                step(world);
            }
            assertTrue(joint.angles(new Vector3f()).y < PI / 2 - 0.3f, "falls once let go: " + joint.angles(new Vector3f()));
            assertEquals(0, joint.constraintTorque(torque).length(), 1e-3f, "and pushes with nothing");

            s.slerpDrive.drive(2000, 90, 0);
            joint.setSettings(s);
            step(world);
            s.slerpDrive.positionDrive = true;
            s.slerpDrive.velocityDrive = false;
            s.slerpDrive.stiffness = 0;
            joint.setSettings(s);
            step(world);
            assertEquals(0, joint.constraintTorque(torque).length(), 1e-3f, "a position drive with no stiffness holds nothing");
        }
    }

    // ---- masses ------------------------------------------------------------------------------

    @Test
    void aDominatingParentIsNotMovedByItsChild() {
        for (float scale : new float[]{1, 0}) {
            try (PhysicsWorld world = world(0)) {
                Body parent = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, 0, 0).enableSleep(false));
                parent.createShape(new ShapeDef().density(1000), Geometry.Box.of(0.1f, 0.1f, 0.1f));
                Body child = bar(world);
                ConstraintJointSettings s = new ConstraintJointSettings();
                s.parentInverseMassScale = scale;
                world.createJoint(new ConstraintJointDef().bodies(parent, child).worldFrame(0, 0, 0, DOWN).settings(s));
                for (int i = 0; i < 30; i++) {
                    child.applyForceToCenter(100, 0, 0);
                    step(world);
                }
                float moved = (float) parent.position(new Vector3d()).length();
                if (scale == 0) {
                    assertTrue(moved < 1e-3f, "the child's pull does not move a dominating parent: " + moved);
                } else {
                    assertTrue(moved > 0.05f, "it does move an ordinary one: " + moved);
                }
            }
        }
    }

    @Test
    void massConditioningStretchesALightParentUnderAHeavyChildLess() {
        float[] stretch = new float[2];
        for (int run = 0; run < 2; run++) {
            try (PhysicsWorld world = world(-10)) {
                Body anchor = world.createBody(new BodyDef());
                Body link = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, -0.2, 0).enableSleep(false));
                link.createShape(new ShapeDef().density(5), Geometry.Box.of(0.05f, 0.2f, 0.05f));
                Body weight = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, -0.6, 0).enableSleep(false));
                weight.createShape(new ShapeDef().density(5000), Geometry.Box.of(0.2f, 0.2f, 0.2f));
                ConstraintJointSettings s = new ConstraintJointSettings();
                s.massConditioning = run == 1;
                world.createJoint(new ConstraintJointDef().bodies(anchor, link).worldFrame(0, 0, 0, DOWN).settings(s));
                ConstraintJoint lower = world.createJoint(
                        new ConstraintJointDef().bodies(link, weight).worldFrame(0, -0.4, 0, DOWN).settings(s));
                weight.setLinearVelocity(4, 0, 0);
                for (int i = 0; i < 120; i++) {
                    step(world);
                    stretch[run] = Math.max(stretch[run], lower.linearSeparation());
                }
            }
        }
        assertTrue(stretch[1] < stretch[0], "conditioned " + stretch[1] + " m against " + stretch[0] + " m");
    }

    // ---- batches and the pair table ----------------------------------------------------------

    @Test
    void theBatchesSetAndReadWhatTheSingleCallsDo() {
        try (PhysicsWorld world = world(0)) {
            // two bars, each its own place in the arrays, both asleep so the targets have to wake them
            Body[] bars = new Body[2];
            ConstraintJoint[] joints = new ConstraintJoint[2];
            for (int i = 0; i < 2; i++) {
                bars[i] = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(3 * i, -0.4, 0));
                bars[i].createShape(new ShapeDef().density(1000), Geometry.Box.of(0.05f, 0.4f, 0.05f));
                ConstraintJointSettings s = new ConstraintJointSettings();
                s.slerpDrive.drive(200, 30, 0);
                joints[i] = world.createJoint(new ConstraintJointDef().bodies(world.createBody(new BodyDef()), bars[i])
                        .worldFrame(3 * i, 0, 0, DOWN).settings(s));
            }
            for (int i = 0; i < 120; i++) {
                step(world);
            }
            assertFalse(bars[0].isAwake() || bars[1].isAwake(), "asleep at their targets");

            ConstraintJointBatch batch = new ConstraintJointBatch(joints);
            float[] targets = new float[2 * ConstraintJointBatch.TARGET];
            float[] turns = {0.4f, -0.25f};
            for (int i = 0; i < 2; i++) {
                Quaternionf target = new Quaternionf().rotateZ(turns[i]);
                int o = i * ConstraintJointBatch.TARGET;
                targets[o + 6] = target.x;
                targets[o + 7] = target.y;
                targets[o + 8] = target.z;
                targets[o + 9] = target.w;
            }
            batch.setTargets(targets, true);
            assertEquals(targets[ConstraintJointBatch.TARGET + 8],
                    joints[1].settings(new ConstraintJointSettings()).angularOrientationTargetZ, 1e-6f);
            for (int i = 0; i < 120; i++) {
                step(world);
            }
            float[] state = new float[2 * ConstraintJointBatch.STATE];
            batch.readState(state);
            for (int i = 0; i < 2; i++) {
                int o = i * ConstraintJointBatch.STATE;
                assertEquals(joints[i].angles(new Vector3f()).y, state[o + 1], 0);
                assertEquals(turns[i], state[o + 1], 0.01f, "woken and turned to its own target");
                assertEquals(joints[i].linearSeparation(), state[o + 6], 0);
            }

            targets[ConstraintJointBatch.TARGET + 1] = Float.NaN;
            assertThrows(IllegalArgumentException.class, () -> batch.setTargets(targets, false));
            assertEquals(0, joints[1].settings(new ConstraintJointSettings()).linearPositionTargetY, "nothing stored");

            Body kinematic = world.createBody(new BodyDef().type(BodyType.KINEMATIC).position(5, 0, 0));
            BodyBatch bodies = new BodyBatch(kinematic);
            bodies.setTargetTransforms(new double[]{5, 1, 0, 0, 0, 0, 2}, 1 / 60f);
            step(world);
            assertEquals(1, kinematic.position(new Vector3d()).y, 1e-4);
            assertEquals(1, kinematic.rotation(new Quaternionf()).w, 1e-6, "made unit, as the single call does");
            assertThrows(IllegalArgumentException.class,
                    () -> bodies.setTargetTransforms(new double[]{5, Double.NaN, 0, 0, 0, 0, 1}, 1 / 60f));
            assertThrows(IllegalArgumentException.class,
                    () -> bodies.setTargetTransforms(new double[]{5, 1, 0, 0, 0, 0, 0}, 1 / 60f));
        }
    }

    @Test
    void aPairTableStopsJustItsDisabledPairsColliding() {
        try (PhysicsWorld world = world(0)) {
            // two overlapping pairs far apart, and a body outside the table overlapping a third
            double[] xs = {0, 0.1, 10, 10.1, 20, 20.1};
            Body[] boxes = new Body[xs.length];
            for (int i = 0; i < xs.length; i++) {
                boxes[i] = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(xs[i], 0, 0).enableSleep(false));
                ShapeDef shape = new ShapeDef().density(1000);
                shape.enableCustomFiltering = true;
                boxes[i].createShape(shape, Geometry.Box.of(0.2f, 0.2f, 0.2f));
            }
            BodyPairTable table = new BodyPairTable(boxes[0], boxes[1], boxes[2], boxes[3], boxes[4]);
            table.setCollides(0, 1, false);
            table.apply();
            for (int i = 0; i < 60; i++) {
                step(world);
            }
            assertEquals(0.1, gap(boxes, 0, 1), 1e-4, "the disabled pair passes through each other");
            assertTrue(gap(boxes, 2, 3) > 0.35, "a pair the table lets collide pushes apart: " + gap(boxes, 2, 3));
            assertTrue(gap(boxes, 4, 5) > 0.35, "and a body outside the table collides with one in it: " + gap(boxes, 4, 5));
            assertFalse(table.collides(1, 0));

            // changes reach pairs already overlapping, both ways: the pair let collide again pushes apart where it lies,
            // and one disabled while touching stops colliding
            table.setCollides(0, 1, true);
            table.setCollides(2, 3, false);
            table.apply();
            boxes[3].setTransform(10.1, 0, 0, new Quaternionf());
            boxes[3].setLinearVelocity(0, 0, 0);
            boxes[2].setTransform(10, 0, 0, new Quaternionf());
            boxes[2].setLinearVelocity(0, 0, 0);
            for (int i = 0; i < 60; i++) {
                step(world);
            }
            assertTrue(gap(boxes, 0, 1) > 0.35, "re-enabled while overlapping, it pushes apart: " + gap(boxes, 0, 1));
            assertEquals(0.1, gap(boxes, 2, 3), 1e-4, "disabled, it passes through");

            // gone, its disabled pairs collide again, those overlapping at rest included
            table.destroy();
            assertFalse(table.isValid());
            for (int i = 0; i < 60; i++) {
                step(world);
            }
            assertTrue(gap(boxes, 2, 3) > 0.35, "a destroyed table filters nothing: " + gap(boxes, 2, 3));
            assertThrows(IndexOutOfBoundsException.class, () -> table.collides(0, 5));
            assertThrows(IllegalArgumentException.class, () -> new BodyPairTable(boxes[5], boxes[5]));
        }
    }

    private static double gap(Body[] boxes, int i, int j) {
        return boxes[j].position(new Vector3d()).x - boxes[i].position(new Vector3d()).x;
    }
}
