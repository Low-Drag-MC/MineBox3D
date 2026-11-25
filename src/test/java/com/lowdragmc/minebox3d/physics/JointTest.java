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
 * <b>Joints</b> (design doc {@code 03-threading-and-api.md} §6.4): every property of every type reads back what was set
 * (which is also what holds the Java and C parameter numbers together), each type does what it is for in a world, and a
 * joint goes with its bodies.
 */
class JointTest {

    private static final float PI = (float) Math.PI;

    private static PhysicsWorld world(float gravity) {
        return PhysicsWorld.create(new WorldDef().gravity(0, gravity, 0));
    }

    private static Body anchor(PhysicsWorld world, double x, double y, double z) {
        return world.createBody(new BodyDef().position(x, y, z));
    }

    private static Body box(PhysicsWorld world, double x, double y, double z, float hx, float hy, float hz) {
        Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(x, y, z));
        body.createShape(new ShapeDef().density(1000), Geometry.Box.of(hx, hy, hz));
        return body;
    }

    private static void steps(PhysicsWorld world, int count) {
        for (int i = 0; i < count; i++) {
            world.beginInterpolationFrame();
            world.step(1 / 60f, 4);
        }
    }

    // ---- every property, set and read back -------------------------------------------------------

    @Test
    void everyPropertyOfEveryJointReadsBackWhatWasSet() {
        try (PhysicsWorld world = world(0)) {
            Body a = box(world, 0, 0, 0, 0.2f, 0.2f, 0.2f);
            Body b = box(world, 1, 0, 0, 0.2f, 0.2f, 0.2f);
            Vector3f v = new Vector3f();
            Quaternionf q = new Quaternionf();

            RevoluteJoint revolute =
                    world.createJoint(new RevoluteJointDef().bodies(a, b).worldFrame(0.5, 0, 0));
            revolute.setForceThreshold(123);
            assertEquals(123, revolute.forceThreshold());
            revolute.setTorqueThreshold(45);
            assertEquals(45, revolute.torqueThreshold());
            revolute.setConstraintTuning(30, 1.5f);
            assertEquals(30, revolute.constraintHertz());
            assertEquals(1.5f, revolute.constraintDampingRatio());
            revolute.setCollideConnected(true);
            assertTrue(revolute.collideConnected());
            Quaternionf turned = new Quaternionf().rotateY(0.4f);
            revolute.setLocalFrameA(0.1f, 0.2f, 0.3f, turned);
            assertEquals(new Vector3f(0.1f, 0.2f, 0.3f), revolute.localPositionA(v));
            assertTrue(revolute.localRotationA(q).equals(turned, 1e-6f));
            revolute.setLocalFrameB(-0.1f, 0, 0.25f, turned);
            assertEquals(new Vector3f(-0.1f, 0, 0.25f), revolute.localPositionB(v));
            assertTrue(revolute.localRotationB(q).equals(turned, 1e-6f));

            revolute.enableSpring(true);
            assertTrue(revolute.isSpringEnabled());
            revolute.setSpringHertz(3);
            assertEquals(3, revolute.springHertz());
            revolute.setSpringDampingRatio(0.4f);
            assertEquals(0.4f, revolute.springDampingRatio());
            revolute.setTargetAngle(0.7f);
            assertEquals(0.7f, revolute.targetAngle());
            revolute.enableLimit(true);
            assertTrue(revolute.isLimitEnabled());
            revolute.setLimits(-0.5f, 0.6f);
            assertEquals(-0.5f, revolute.lowerLimit());
            assertEquals(0.6f, revolute.upperLimit());
            revolute.enableMotor(true);
            assertTrue(revolute.isMotorEnabled());
            revolute.setMotorSpeed(2.5f);
            assertEquals(2.5f, revolute.motorSpeed());
            revolute.setMaxMotorTorque(17);
            assertEquals(17, revolute.maxMotorTorque());

            SphericalJoint spherical =
                    world.createJoint(new SphericalJointDef().bodies(a, b).worldFrame(0.5, 0, 0));
            spherical.enableConeLimit(true);
            assertTrue(spherical.isConeLimitEnabled());
            spherical.setConeLimit(0.8f);
            assertEquals(0.8f, spherical.coneLimit());
            spherical.enableTwistLimit(true);
            assertTrue(spherical.isTwistLimitEnabled());
            spherical.setTwistLimits(-0.3f, 0.9f);
            assertEquals(-0.3f, spherical.lowerTwistLimit());
            assertEquals(0.9f, spherical.upperTwistLimit());
            spherical.enableSpring(true);
            assertTrue(spherical.isSpringEnabled());
            spherical.setSpringHertz(4);
            assertEquals(4, spherical.springHertz());
            spherical.setSpringDampingRatio(0.6f);
            assertEquals(0.6f, spherical.springDampingRatio());
            spherical.setTargetRotation(turned);
            assertTrue(spherical.targetRotation(q).equals(turned, 1e-6f));
            spherical.enableMotor(true);
            assertTrue(spherical.isMotorEnabled());
            spherical.setMotorVelocity(1, -2, 3);
            assertEquals(new Vector3f(1, -2, 3), spherical.motorVelocity(v));
            spherical.setMaxMotorTorque(9);
            assertEquals(9, spherical.maxMotorTorque());

            WeldJoint weld = world.createJoint(new WeldJointDef().bodies(a, b).worldFrame(0.5, 0, 0));
            weld.setLinearHertz(5);
            assertEquals(5, weld.linearHertz());
            weld.setLinearDampingRatio(0.3f);
            assertEquals(0.3f, weld.linearDampingRatio());
            weld.setAngularHertz(6);
            assertEquals(6, weld.angularHertz());
            weld.setAngularDampingRatio(0.2f);
            assertEquals(0.2f, weld.angularDampingRatio());

            MotorJoint motor = world.createJoint(new MotorJointDef().bodies(a, b));
            motor.setLinearVelocity(1, 2, 3);
            assertEquals(new Vector3f(1, 2, 3), motor.linearVelocity(v));
            motor.setAngularVelocity(-1, 0.5f, 0);
            assertEquals(new Vector3f(-1, 0.5f, 0), motor.angularVelocity(v));
            motor.setMaxVelocityForce(11);
            assertEquals(11, motor.maxVelocityForce());
            motor.setMaxVelocityTorque(12);
            assertEquals(12, motor.maxVelocityTorque());
            motor.setLinearHertz(7);
            assertEquals(7, motor.linearHertz());
            motor.setLinearDampingRatio(0.8f);
            assertEquals(0.8f, motor.linearDampingRatio());
            motor.setAngularHertz(8);
            assertEquals(8, motor.angularHertz());
            motor.setAngularDampingRatio(0.9f);
            assertEquals(0.9f, motor.angularDampingRatio());
            motor.setMaxSpringForce(13);
            assertEquals(13, motor.maxSpringForce());
            motor.setMaxSpringTorque(14);
            assertEquals(14, motor.maxSpringTorque());

            DistanceJoint distance =
                    world.createJoint(new DistanceJointDef().bodies(a, b).length(1));
            distance.setLength(1.5f);
            assertEquals(1.5f, distance.length());
            distance.enableSpring(true);
            assertTrue(distance.isSpringEnabled());
            distance.setSpringForceRange(-20, 5);
            assertEquals(-20, distance.lowerSpringForce());
            assertEquals(5, distance.upperSpringForce());
            distance.setSpringHertz(2);
            assertEquals(2, distance.springHertz());
            distance.setSpringDampingRatio(0.1f);
            assertEquals(0.1f, distance.springDampingRatio());
            distance.enableLimit(true);
            assertTrue(distance.isLimitEnabled());
            distance.setLengthRange(0.5f, 2.5f);
            assertEquals(0.5f, distance.minLength());
            assertEquals(2.5f, distance.maxLength());
            distance.enableMotor(true);
            assertTrue(distance.isMotorEnabled());
            distance.setMotorSpeed(0.25f);
            assertEquals(0.25f, distance.motorSpeed());
            distance.setMaxMotorForce(3);
            assertEquals(3, distance.maxMotorForce());

            PrismaticJoint prismatic =
                    world.createJoint(new PrismaticJointDef().bodies(a, b).worldFrame(0.5, 0, 0));
            prismatic.enableSpring(true);
            assertTrue(prismatic.isSpringEnabled());
            prismatic.setSpringHertz(1.5f);
            assertEquals(1.5f, prismatic.springHertz());
            prismatic.setSpringDampingRatio(0.7f);
            assertEquals(0.7f, prismatic.springDampingRatio());
            prismatic.setTargetTranslation(0.4f);
            assertEquals(0.4f, prismatic.targetTranslation());
            prismatic.enableLimit(true);
            assertTrue(prismatic.isLimitEnabled());
            prismatic.setLimits(-1, 2);
            assertEquals(-1, prismatic.lowerLimit());
            assertEquals(2, prismatic.upperLimit());
            prismatic.enableMotor(true);
            assertTrue(prismatic.isMotorEnabled());
            prismatic.setMotorSpeed(-0.5f);
            assertEquals(-0.5f, prismatic.motorSpeed());
            prismatic.setMaxMotorForce(21);
            assertEquals(21, prismatic.maxMotorForce());

            WheelJoint wheel =
                    world.createJoint(new WheelJointDef().bodies(a, b).worldFrame(0.5, 0, 0));
            wheel.enableSuspension(false);
            assertFalse(wheel.isSuspensionEnabled());
            wheel.setSuspensionHertz(2.5f);
            assertEquals(2.5f, wheel.suspensionHertz());
            wheel.setSuspensionDampingRatio(0.35f);
            assertEquals(0.35f, wheel.suspensionDampingRatio());
            wheel.enableSuspensionLimit(true);
            assertTrue(wheel.isSuspensionLimitEnabled());
            wheel.setSuspensionLimits(-0.2f, 0.3f);
            assertEquals(-0.2f, wheel.lowerSuspensionLimit());
            assertEquals(0.3f, wheel.upperSuspensionLimit());
            wheel.enableSpinMotor(true);
            assertTrue(wheel.isSpinMotorEnabled());
            wheel.setSpinMotorSpeed(6);
            assertEquals(6, wheel.spinMotorSpeed());
            wheel.setMaxSpinTorque(31);
            assertEquals(31, wheel.maxSpinTorque());
            wheel.enableSteering(true);
            assertTrue(wheel.isSteeringEnabled());
            wheel.setSteeringHertz(3.5f);
            assertEquals(3.5f, wheel.steeringHertz());
            wheel.setSteeringDampingRatio(0.45f);
            assertEquals(0.45f, wheel.steeringDampingRatio());
            wheel.setMaxSteeringTorque(41);
            assertEquals(41, wheel.maxSteeringTorque());
            wheel.enableSteeringLimit(true);
            assertTrue(wheel.isSteeringLimitEnabled());
            wheel.setSteeringLimits(-0.6f, 0.4f);
            assertEquals(-0.6f, wheel.lowerSteeringLimit());
            assertEquals(0.4f, wheel.upperSteeringLimit());
            wheel.setTargetSteeringAngle(0.2f);
            assertEquals(0.2f, wheel.targetSteeringAngle());

            ParallelJoint parallel = world.createJoint(new ParallelJointDef().bodies(a, b));
            parallel.setSpringHertz(1.25f);
            assertEquals(1.25f, parallel.springHertz());
            parallel.setSpringDampingRatio(0.55f);
            assertEquals(0.55f, parallel.springDampingRatio());
            parallel.setMaxTorque(61);
            assertEquals(61, parallel.maxTorque());

            FilterJoint filter = world.createJoint(new FilterJointDef().bodies(a, b));
            assertEquals(JointType.FILTER, filter.type());

            // the readings that are measured, not set: they answer, and a step later they are about the step
            steps(world, 2);
            assertTrue(Float.isFinite(revolute.angle()) && Float.isFinite(revolute.motorTorque()));
            assertTrue(Float.isFinite(spherical.coneAngle()) && Float.isFinite(spherical.twistAngle()));
            assertTrue(Float.isFinite(spherical.motorTorque(v).x));
            assertTrue(Float.isFinite(distance.currentLength()) && Float.isFinite(distance.motorForce()));
            assertTrue(Float.isFinite(prismatic.translation())
                    && Float.isFinite(prismatic.speed())
                    && Float.isFinite(prismatic.motorForce()));
            assertTrue(Float.isFinite(wheel.spinSpeed())
                    && Float.isFinite(wheel.spinTorque())
                    && Float.isFinite(wheel.steeringAngle())
                    && Float.isFinite(wheel.steeringTorque()));
            assertTrue(Float.isFinite(revolute.linearSeparation()) && Float.isFinite(revolute.angularSeparation()));
            assertTrue(Float.isFinite(weld.constraintForce(v).x) && Float.isFinite(weld.constraintTorque(v).x));
            assertTrue(revolute.isAwake());
        }
    }

    /**
     * The definition reaches C by field name, the runtime getters by parameter number: a joint made with a distinct
     * value in every field and read back through the getters pins the numbers to the fields (two swapped numbers would
     * read back as each other's value — which setting and reading through the same wrong number would not show).
     */
    @Test
    void whatADefinitionSaysIsWhatTheGettersRead() {
        try (PhysicsWorld world = world(0)) {
            Body a = box(world, 0, 0, 0, 0.2f, 0.2f, 0.2f);
            Body b = box(world, 1, 0, 0, 0.2f, 0.2f, 0.2f);
            Vector3f v = new Vector3f();
            Quaternionf q = new Quaternionf();

            RevoluteJointDef rd = new RevoluteJointDef().bodies(a, b);
            rd.targetAngle = 0.11f;
            rd.enableSpring = true;
            rd.hertz = 0.12f;
            rd.dampingRatio = 0.13f;
            rd.enableLimit = false;
            rd.lowerAngle = -0.14f;
            rd.upperAngle = 0.15f;
            rd.enableMotor = true;
            rd.maxMotorTorque = 0.16f;
            rd.motorSpeed = 0.17f;
            rd.forceThreshold = 0.18f;
            rd.torqueThreshold = 0.19f;
            rd.constraintHertz = 0.21f;
            rd.constraintDampingRatio = 0.22f;
            rd.collideConnected = true;
            RevoluteJoint r = world.createJoint(rd);
            assertEquals(0.11f, r.targetAngle());
            assertTrue(r.isSpringEnabled());
            assertEquals(0.12f, r.springHertz());
            assertEquals(0.13f, r.springDampingRatio());
            assertFalse(r.isLimitEnabled());
            assertEquals(-0.14f, r.lowerLimit());
            assertEquals(0.15f, r.upperLimit());
            assertTrue(r.isMotorEnabled());
            assertEquals(0.16f, r.maxMotorTorque());
            assertEquals(0.17f, r.motorSpeed());
            assertEquals(0.18f, r.forceThreshold());
            assertEquals(0.19f, r.torqueThreshold());
            assertEquals(0.21f, r.constraintHertz());
            assertEquals(0.22f, r.constraintDampingRatio());
            assertTrue(r.collideConnected());

            SphericalJointDef sd = new SphericalJointDef().bodies(a, b);
            sd.enableSpring = true;
            sd.hertz = 0.31f;
            sd.dampingRatio = 0.32f;
            Quaternionf target = new Quaternionf().rotateXYZ(0.1f, 0.2f, 0.3f);
            sd.targetRotationX = target.x;
            sd.targetRotationY = target.y;
            sd.targetRotationZ = target.z;
            sd.targetRotationW = target.w;
            sd.enableConeLimit = true;
            sd.coneAngle = 0.33f;
            sd.enableTwistLimit = false;
            sd.lowerTwistAngle = -0.34f;
            sd.upperTwistAngle = 0.35f;
            sd.enableMotor = true;
            sd.maxMotorTorque = 0.36f;
            sd.motorVelocityX = 0.37f;
            sd.motorVelocityY = 0.38f;
            sd.motorVelocityZ = 0.39f;
            SphericalJoint s = world.createJoint(sd);
            assertTrue(s.isSpringEnabled());
            assertEquals(0.31f, s.springHertz());
            assertEquals(0.32f, s.springDampingRatio());
            assertTrue(s.targetRotation(q).equals(target, 1e-6f));
            assertTrue(s.isConeLimitEnabled());
            assertEquals(0.33f, s.coneLimit());
            assertFalse(s.isTwistLimitEnabled());
            assertEquals(-0.34f, s.lowerTwistLimit());
            assertEquals(0.35f, s.upperTwistLimit());
            assertTrue(s.isMotorEnabled());
            assertEquals(0.36f, s.maxMotorTorque());
            assertEquals(new Vector3f(0.37f, 0.38f, 0.39f), s.motorVelocity(v));

            WeldJointDef wd = new WeldJointDef().bodies(a, b).springs(0.41f, 0.42f, 0.43f, 0.44f);
            WeldJoint w = world.createJoint(wd);
            assertEquals(0.41f, w.linearHertz());
            assertEquals(0.42f, w.linearDampingRatio());
            assertEquals(0.43f, w.angularHertz());
            assertEquals(0.44f, w.angularDampingRatio());

            MotorJointDef md = new MotorJointDef().bodies(a, b);
            md.linearVelocityX = 0.51f;
            md.linearVelocityY = 0.52f;
            md.linearVelocityZ = 0.53f;
            md.maxVelocityForce = 0.54f;
            md.angularVelocityX = 0.55f;
            md.angularVelocityY = 0.56f;
            md.angularVelocityZ = 0.57f;
            md.maxVelocityTorque = 0.58f;
            md.linearHertz = 0.59f;
            md.linearDampingRatio = 0.61f;
            md.maxSpringForce = 0.62f;
            md.angularHertz = 0.63f;
            md.angularDampingRatio = 0.64f;
            md.maxSpringTorque = 0.65f;
            MotorJoint m = world.createJoint(md);
            assertEquals(new Vector3f(0.51f, 0.52f, 0.53f), m.linearVelocity(v));
            assertEquals(0.54f, m.maxVelocityForce());
            assertEquals(new Vector3f(0.55f, 0.56f, 0.57f), m.angularVelocity(v));
            assertEquals(0.58f, m.maxVelocityTorque());
            assertEquals(0.59f, m.linearHertz());
            assertEquals(0.61f, m.linearDampingRatio());
            assertEquals(0.62f, m.maxSpringForce());
            assertEquals(0.63f, m.angularHertz());
            assertEquals(0.64f, m.angularDampingRatio());
            assertEquals(0.65f, m.maxSpringTorque());

            DistanceJointDef dd = new DistanceJointDef().bodies(a, b);
            dd.length = 1.71f;
            dd.enableSpring = true;
            dd.lowerSpringForce = -0.72f;
            dd.upperSpringForce = 0.73f;
            dd.hertz = 0.74f;
            dd.dampingRatio = 0.75f;
            dd.enableLimit = false;
            dd.minLength = 0.76f;
            dd.maxLength = 1.77f;
            dd.enableMotor = true;
            dd.maxMotorForce = 0.78f;
            dd.motorSpeed = 0.79f;
            DistanceJoint d = world.createJoint(dd);
            assertEquals(1.71f, d.length());
            assertTrue(d.isSpringEnabled());
            assertEquals(-0.72f, d.lowerSpringForce());
            assertEquals(0.73f, d.upperSpringForce());
            assertEquals(0.74f, d.springHertz());
            assertEquals(0.75f, d.springDampingRatio());
            assertFalse(d.isLimitEnabled());
            assertEquals(0.76f, d.minLength());
            assertEquals(1.77f, d.maxLength());
            assertTrue(d.isMotorEnabled());
            assertEquals(0.78f, d.maxMotorForce());
            assertEquals(0.79f, d.motorSpeed());

            PrismaticJointDef pd = new PrismaticJointDef().bodies(a, b);
            pd.enableSpring = true;
            pd.hertz = 0.81f;
            pd.dampingRatio = 0.82f;
            pd.targetTranslation = 0.83f;
            pd.enableLimit = false;
            pd.lowerTranslation = -0.84f;
            pd.upperTranslation = 0.85f;
            pd.enableMotor = true;
            pd.maxMotorForce = 0.86f;
            pd.motorSpeed = 0.87f;
            PrismaticJoint p = world.createJoint(pd);
            assertTrue(p.isSpringEnabled());
            assertEquals(0.81f, p.springHertz());
            assertEquals(0.82f, p.springDampingRatio());
            assertEquals(0.83f, p.targetTranslation());
            assertFalse(p.isLimitEnabled());
            assertEquals(-0.84f, p.lowerLimit());
            assertEquals(0.85f, p.upperLimit());
            assertTrue(p.isMotorEnabled());
            assertEquals(0.86f, p.maxMotorForce());
            assertEquals(0.87f, p.motorSpeed());

            WheelJointDef hd = new WheelJointDef().bodies(a, b);
            hd.enableSuspensionSpring = false;
            hd.suspensionHertz = 0.91f;
            hd.suspensionDampingRatio = 0.92f;
            hd.enableSuspensionLimit = true;
            hd.lowerSuspensionLimit = -0.93f;
            hd.upperSuspensionLimit = 0.94f;
            hd.enableSpinMotor = true;
            hd.maxSpinTorque = 0.95f;
            hd.spinSpeed = 0.96f;
            hd.enableSteering = true;
            hd.steeringHertz = 0.97f;
            hd.steeringDampingRatio = 0.98f;
            hd.targetSteeringAngle = 0.99f;
            hd.maxSteeringTorque = 1.01f;
            hd.enableSteeringLimit = false;
            hd.lowerSteeringLimit = -1.02f;
            hd.upperSteeringLimit = 1.03f;
            WheelJoint h = world.createJoint(hd);
            assertFalse(h.isSuspensionEnabled());
            assertEquals(0.91f, h.suspensionHertz());
            assertEquals(0.92f, h.suspensionDampingRatio());
            assertTrue(h.isSuspensionLimitEnabled());
            assertEquals(-0.93f, h.lowerSuspensionLimit());
            assertEquals(0.94f, h.upperSuspensionLimit());
            assertTrue(h.isSpinMotorEnabled());
            assertEquals(0.95f, h.maxSpinTorque());
            assertEquals(0.96f, h.spinMotorSpeed());
            assertTrue(h.isSteeringEnabled());
            assertEquals(0.97f, h.steeringHertz());
            assertEquals(0.98f, h.steeringDampingRatio());
            assertEquals(0.99f, h.targetSteeringAngle());
            assertEquals(1.01f, h.maxSteeringTorque());
            assertFalse(h.isSteeringLimitEnabled());
            assertEquals(-1.02f, h.lowerSteeringLimit());
            assertEquals(1.03f, h.upperSteeringLimit());

            ParallelJoint l =
                    world.createJoint(new ParallelJointDef().bodies(a, b).spring(1.11f, 1.12f, 1.13f));
            assertEquals(1.11f, l.springHertz());
            assertEquals(1.12f, l.springDampingRatio());
            assertEquals(1.13f, l.maxTorque());

            // and the frames, from the base table
            Quaternionf turned = new Quaternionf().rotateZ(0.4f);
            FilterJoint f = world.createJoint(new FilterJointDef()
                    .bodies(a, b)
                    .localFrameA(1.21f, 1.22f, 1.23f, turned)
                    .localFrameB(-1.24f, 1.25f, -1.26f, turned));
            assertEquals(new Vector3f(1.21f, 1.22f, 1.23f), f.localPositionA(v));
            assertEquals(new Vector3f(-1.24f, 1.25f, -1.26f), f.localPositionB(v));
            assertTrue(f.localRotationB(q).equals(turned, 1e-6f));
        }
    }

    /**
     * Box3D asserts unit length to 20 float epsilons; the bridge takes what Java lets through the last bit of the way.
     */
    @Test
    void aNearlyUnitRotationIsTakenAsTheUnitOne() {
        try (PhysicsWorld world = world(0)) {
            Body a = box(world, 0, 0, 0, 0.2f, 0.2f, 0.2f);
            Body b = box(world, 1, 0, 0, 0.2f, 0.2f, 0.2f);
            Quaternionf typed = new Quaternionf(0, 0.7071f, 0, 0.7071f); // |q|² = 0.99998: Box3D's assert says no
            SphericalJoint joint = world.createJoint(new SphericalJointDef()
                    .bodies(a, b)
                    .localFrameA(0, 0, 0, typed)
                    .localFrameB(0, 0, 0, typed)
                    .spring(1, 1, typed));
            joint.setLocalFrameA(0.5f, 0, 0, typed);
            joint.setTargetRotation(typed);
            Quaternionf read = joint.localRotationA(new Quaternionf());
            assertEquals(1, read.lengthSquared(), 1e-6, "stored as the unit rotation");
            assertTrue(read.equals(new Quaternionf(typed).normalize(), 1e-6f));
            steps(world, 2);
        }
    }

    @Test
    void theBridgeRefusesAPropertyOfAnotherJointType() {
        try (PhysicsWorld world = world(0)) {
            Body a = box(world, 0, 0, 0, 0.2f, 0.2f, 0.2f);
            Body b = box(world, 1, 0, 0, 0.2f, 0.2f, 0.2f);
            RevoluteJoint revolute = world.createJoint(new RevoluteJointDef().bodies(a, b));
            float[] out = new float[4];
            assertThrows(
                    IllegalArgumentException.class, () -> B3.jointGet(revolute.id(), JointParam.SPHERICAL_HERTZ, out));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> B3.jointSet(revolute.id(), JointParam.WHEEL_SPIN_MOTOR_SPEED, 1, 0, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> B3.jointGet(revolute.id(), 9999, out));
            // the base ones work on any joint
            assertEquals(1, B3.jointGet(revolute.id(), JointParam.FORCE_THRESHOLD, out));
        }
    }

    @Test
    void definitionsStartFromBox3DsDefaults() {
        DistanceJointDef distance = new DistanceJointDef();
        assertEquals(1, distance.length);
        assertEquals(-Float.MAX_VALUE, distance.lowerSpringForce);
        assertEquals(Float.MAX_VALUE, distance.upperSpringForce);
        assertEquals(Float.MAX_VALUE, distance.forceThreshold);
        assertEquals(Float.MAX_VALUE, distance.torqueThreshold);
        assertEquals(60, distance.constraintHertz);
        assertEquals(2, distance.constraintDampingRatio);
        assertEquals(1, distance.rotationAW);
        assertEquals(1, distance.rotationBW);
        WheelJointDef wheel = new WheelJointDef();
        assertTrue(wheel.enableSuspensionSpring);
        assertEquals(1, wheel.suspensionHertz);
        assertEquals(0.7f, wheel.suspensionDampingRatio);
        assertEquals(1, new SphericalJointDef().targetRotationW);
        assertEquals(JointType.MOTOR, new MotorJointDef().type());
    }

    // ---- what each type is for ----------------------------------------------------------------

    /** A box hanging from a point, frame z pointing down: the cone is about the vertical. */
    private static float widestSwing(boolean limited) {
        try (PhysicsWorld world = world(-10)) {
            Body pivot = anchor(world, 0, 5, 0);
            Body box = box(world, 0, 4.5, 0, 0.1f, 0.5f, 0.1f);
            SphericalJoint joint = world.createJoint(
                    new SphericalJointDef().bodies(pivot, box).worldFrame(0, 5, 0, new Quaternionf().rotateX(PI / 2)));
            if (limited) {
                joint.enableConeLimit(true);
                joint.setConeLimit(0.3f);
            }
            box.setLinearVelocity(6, 0, 0);
            float widest = 0;
            Vector3d top = new Vector3d();
            for (int i = 0; i < 120; i++) {
                steps(world, 1);
                widest = Math.max(widest, joint.coneAngle());
                box.toWorld(0, 0.5f, 0, top);
                assertEquals(0, top.distance(0, 5, 0), 0.03, "the box's top stays at the pivot");
            }
            return widest;
        }
    }

    @Test
    void aSphericalJointHoldsAPendulumAtItsPivotAndWithinItsCone() {
        assertTrue(widestSwing(false) > 0.6f, "free, it swings wide");
        float limited = widestSwing(true);
        assertTrue(limited < 0.35f, "the cone holds it: " + limited);
    }

    @Test
    void aRevoluteMotorTurnsAHingeAtItsSpeedAndALimitStopsIt() {
        try (PhysicsWorld world = world(0)) {
            Body hub = anchor(world, 0, 0, 0);
            Body plank = world.createBody(new BodyDef().type(BodyType.DYNAMIC));
            plank.createShape(new ShapeDef().density(1000), Geometry.Box.of(1, 0.1f, 0.1f, 1, 0, 0));
            RevoluteJoint hinge = world.createJoint(new RevoluteJointDef()
                    .bodies(hub, plank)
                    .worldFrame(0, 0, 0)
                    .motor(1.0e5f, 2));
            steps(world, 30);
            Vector3f w = plank.angularVelocity(new Vector3f());
            assertEquals(2, w.z, 0.05, "spins at the motor's speed about the hinge");
            assertEquals(0, w.x, 1e-3);
            assertEquals(0, w.y, 1e-3);

            // back to the start, then the limit: the motor drives it into the limit from inside, not from past it
            // where the free spin left it
            plank.setTransform(0, 0, 0, new Quaternionf());
            plank.setAngularVelocity(0, 0, 0);
            hinge.setLimits(-0.5f, 0.5f);
            hinge.enableLimit(true);
            float widest = 0;
            for (int i = 0; i < 120; i++) {
                steps(world, 1);
                widest = Math.max(widest, hinge.angle());
            }
            // soft: a motor this strong presses a degree or two in, where a free hinge goes round
            assertTrue(widest < 0.55f, "the limit holds: " + widest);
            assertEquals(0.5f, hinge.angle(), 0.03, "pressed against it by the motor");
        }
    }

    @Test
    void aWeldHoldsABodyWhereItWasAgainstGravity() {
        try (PhysicsWorld world = world(-10)) {
            Body wall = anchor(world, 0, 5, 0);
            Body arm = box(world, 1, 5, 0, 1, 0.1f, 0.1f);
            world.createJoint(new WeldJointDef().bodies(wall, arm).worldFrame(0, 5, 0));
            steps(world, 120);
            Vector3d p = arm.position(new Vector3d());
            assertEquals(1, p.x, 0.02);
            assertEquals(5, p.y, 0.05, "does not sag away");
            Quaternionf q = arm.rotation(new Quaternionf());
            assertTrue(Math.abs(q.w) > 0.999f, "does not droop: " + q);
        }
    }

    @Test
    void aPrismaticJointSlidesOnlyAlongItsAxisAndStopsAtItsLimit() {
        try (PhysicsWorld world = world(-10)) {
            Body rail = anchor(world, 0, 5, 0);
            Body slider = box(world, 0, 5, 0, 0.2f, 0.2f, 0.2f);
            // frame x pointing down: gravity slides it along the axis
            PrismaticJoint joint = world.createJoint(new PrismaticJointDef()
                    .bodies(rail, slider)
                    .worldFrame(0, 5, 0, new Quaternionf().rotateZ(-PI / 2))
                    .limits(0, 1));
            slider.setLinearVelocity(3, 0, 2);
            steps(world, 120);
            assertEquals(1, joint.translation(), 0.02, "slid down to the limit");
            Vector3d p = slider.position(new Vector3d());
            assertEquals(4, p.y, 0.02);
            assertEquals(0, p.x, 0.01, "not sideways");
            assertEquals(0, p.z, 0.01, "not sideways");
            assertTrue(Math.abs(slider.rotation(new Quaternionf()).w) > 0.9999f, "does not turn");
        }
    }

    @Test
    void aDistanceJointIsARodRigidAndARopeWithinItsLimit() {
        try (PhysicsWorld world = world(-10)) {
            Body pivot = anchor(world, 0, 5, 0);
            Body bob = box(world, 2, 5, 0, 0.1f, 0.1f, 0.1f);
            DistanceJoint rod =
                    world.createJoint(new DistanceJointDef().bodies(pivot, bob).length(2));
            for (int i = 0; i < 120; i++) {
                steps(world, 1);
                assertEquals(2, rod.currentLength(), 0.02, "a rod keeps its length while it swings");
            }
        }
        try (PhysicsWorld world = world(-10)) {
            Body pivot = anchor(world, 0, 5, 0);
            Body bob = box(world, 0, 4, 0, 0.1f, 0.1f, 0.1f);
            DistanceJoint rope = world.createJoint(new DistanceJointDef()
                    .bodies(pivot, bob)
                    .length(1)
                    .spring(0, 0)
                    .limits(0.5f, 2.5f));
            float longest = 0;
            for (int i = 0; i < 120; i++) {
                steps(world, 1);
                longest = Math.max(longest, rope.currentLength());
            }
            assertTrue(longest > 2.4f, "a slack spring lets it fall to the limit: " + longest);
            assertTrue(longest < 2.55f, "and no further: " + longest);
        }
    }

    @Test
    void aMotorJointDrivesItsBodyToTheTargetAndFollowsItWhenItMoves() {
        try (PhysicsWorld world = world(0)) {
            Body base = anchor(world, 0, 0, 0);
            Body puppet = box(world, 0, 0, 0, 0.2f, 0.2f, 0.2f);
            MotorJoint motor = world.createJoint(new MotorJointDef()
                    .bodies(base, puppet)
                    .linearSpring(4, 1, 1.0e5f)
                    .angularSpring(4, 1, 1.0e5f)
                    .localFrameA(3, 0, 0, new Quaternionf()));
            steps(world, 180);
            assertEquals(0, puppet.position(new Vector3d()).distance(3, 0, 0), 0.05, "at the target");
            motor.setLocalFrameA(-2, 1, 0, new Quaternionf());
            motor.wakeBodies();
            steps(world, 180);
            assertEquals(0, puppet.position(new Vector3d()).distance(-2, 1, 0), 0.05, "followed the target");
        }
    }

    @Test
    void aWheelSpinsAtItsMotorSpeed() {
        try (PhysicsWorld world = world(0)) {
            Body chassis = anchor(world, 0, 0, 0);
            Body wheel = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(1, 0, 0));
            wheel.createShape(new ShapeDef().density(1000), new Geometry.Sphere(0, 0, 0, 0.3f));
            WheelJoint joint = world.createJoint(new WheelJointDef()
                    .bodies(chassis, wheel)
                    .worldFrame(1, 0, 0)
                    .spinMotor(1.0e4f, 5));
            steps(world, 60);
            assertEquals(5, joint.spinSpeed(), 0.05);
            assertEquals(5, wheel.angularVelocity(new Vector3f()).z, 0.05, "about frame B's z");
        }
    }

    /** Two boxes half inside each other; how far apart they end up. */
    private static double separation(boolean filtered) {
        try (PhysicsWorld world = world(0)) {
            Body a = box(world, 0, 0, 0, 0.5f, 0.5f, 0.5f);
            Body b = box(world, 0.5, 0, 0, 0.5f, 0.5f, 0.5f);
            if (filtered) {
                world.createJoint(new FilterJointDef().bodies(a, b));
            }
            steps(world, 30);
            return a.position(new Vector3d()).distance(b.position(new Vector3d()));
        }
    }

    @Test
    void aFilterJointOnlyStopsItsTwoBodiesColliding() {
        assertTrue(separation(false) > 0.8, "unfiltered, they push apart");
        assertEquals(0.5, separation(true), 1e-3, "filtered, they stay where they are");
    }

    @Test
    void aParallelJointStandsATiltedBodyBackUp() {
        try (PhysicsWorld world = world(0)) {
            Body ground = anchor(world, 0, 0, 0);
            Body post = world.createBody(
                    new BodyDef().type(BodyType.DYNAMIC).position(0, 1, 0).rotation(new Quaternionf().rotateX(0.5f)));
            post.createShape(new ShapeDef().density(1000), Geometry.Box.of(0.1f, 0.1f, 0.5f));
            world.createJoint(new ParallelJointDef().bodies(ground, post).spring(2, 1, 1.0e5f));
            Vector3f z = new Vector3f();
            assertTrue(post.rotation(new Quaternionf()).transform(z.set(0, 0, 1)).z < 0.9f, "starts tilted");
            steps(world, 240);
            assertTrue(post.rotation(new Quaternionf()).transform(z.set(0, 0, 1)).z > 0.995f, "stood back up: " + z);
        }
    }

    // ---- placing, reporting, going --------------------------------------------------------------

    @Test
    void aJointPlacedByAWorldFrameStartsAtRestEvenMillionsOut() {
        try (PhysicsWorld world = world(0)) {
            double x = 12_345_678.25, z = -9_876_543.5;
            Body a = box(world, x, 64, z, 0.3f, 0.3f, 0.3f);
            Body b = world.createBody(new BodyDef()
                    .type(BodyType.DYNAMIC)
                    .position(x + 1.5, 64.25, z)
                    .rotation(new Quaternionf().rotateY(0.7f)));
            b.createShape(new ShapeDef().density(1000), Geometry.Box.of(0.3f, 0.3f, 0.3f));
            Quaternionf frame = new Quaternionf().rotateX(0.3f).rotateZ(1.1f);
            WeldJoint weld =
                    world.createJoint(new WeldJointDef().bodies(a, b).worldFrame(x + 0.75, 64.1, z + 0.2, frame));
            steps(world, 1);
            assertEquals(0, weld.linearSeparation(), 1e-4);
            assertEquals(0, weld.angularSeparation(), 1e-4);
            assertEquals(0, a.linearVelocity(new Vector3f()).length(), 1e-3, "nothing to correct");
        }
    }

    /**
     * Our fork (02-box3d-fork.md §3.1): Box3D measured both against the bodies, and across the axis along one
     * direction.
     */
    @Test
    void separationsAreMeasuredInTheJointsFrames() {
        try (PhysicsWorld world = world(0)) {
            Body a = anchor(world, 0, 0, 0);
            Body b = box(world, 0, 0, 0, 0.2f, 0.2f, 0.2f);
            // the axis along the world's z
            PrismaticJoint slide = world.createJoint(
                    new PrismaticJointDef().bodies(a, b).worldFrame(0, 0, 0, new Quaternionf().rotateY(-PI / 2)));
            b.setTransform(0.3, 0.4, 7, new Quaternionf());
            assertEquals(7, slide.translation(), 1e-4, "along the frame's axis");
            assertEquals(0.5, slide.linearSeparation(), 1e-4, "off it, both ways across");
            assertEquals(0, slide.angularSeparation(), 1e-4);

            Body c = world.createBody(new BodyDef().type(BodyType.DYNAMIC).rotation(new Quaternionf().rotateX(1)));
            c.createShape(new ShapeDef(), Geometry.Box.of(0.2f, 0.2f, 0.2f));
            RevoluteJoint hinge = world.createJoint(
                    new RevoluteJointDef().bodies(a, c).worldFrame(0, 0, 0, new Quaternionf().rotateY(0.6f)));
            assertEquals(0, hinge.angularSeparation(), 1e-4, "frames coincide though the bodies are turned");
            WheelJoint wheel = world.createJoint(new WheelJointDef().bodies(a, c));
            assertEquals(0, wheel.angularSeparation(), "Box3D has no measure for a wheel: 0, not a failed check");

            // turned 0.3 about the hinge against limits of ±0.1: off by 0.2, not by the whole turn
            hinge.setLimits(-0.1f, 0.1f);
            hinge.enableLimit(true);
            Quaternionf frame = new Quaternionf().rotateY(0.6f);
            Quaternionf about = new Quaternionf(frame).rotateZ(0.3f).mul(new Quaternionf(frame).conjugate());
            c.setTransform(0, 0, 0, about.mul(new Quaternionf().rotateX(1)).normalize());
            assertEquals(0.3f, hinge.angle(), 1e-4);
            assertEquals(0.2f, hinge.angularSeparation(), 1e-4);
        }
    }

    /** A hinge spinning freely is a perfect hinge the whole way round, not 2π off for half of every turn. */
    @Test
    void aSpinningHingeIsNeverOffItsAxis() {
        try (PhysicsWorld world = world(0)) {
            Body hub = anchor(world, 0, 0, 0);
            Body wheel = box(world, 0, 0, 0, 0.5f, 0.5f, 0.1f);
            RevoluteJoint hinge = world.createJoint(new RevoluteJointDef()
                    .bodies(hub, wheel)
                    .worldFrame(0, 0, 0)
                    .motor(1.0e5f, 10));
            float worst = 0;
            for (int i = 0; i < 120; i++) { // over three turns
                steps(world, 1);
                worst = Math.max(worst, hinge.angularSeparation());
            }
            assertTrue(worst < 0.01f, "off its axis by " + worst);
            ParallelJoint upright =
                    world.createJoint(new ParallelJointDef().bodies(hub, wheel).spring(0, 0, 0));
            assertTrue(upright.angularSeparation() < 0.01f, "spun about the shared z: still parallel");
        }
    }

    @Test
    void aJointPastItsThresholdIsReportedOncePerStepAndCanBeBrokenThere() {
        try (PhysicsWorld world = world(-10)) {
            Body pivot = anchor(world, 0, 5, 0);
            Body box = box(world, 0, 4.5, 0, 0.1f, 0.5f, 0.1f); // 40 kg: 400 N on the joint
            SphericalJoint joint = world.createJoint(new SphericalJointDef()
                    .bodies(pivot, box)
                    .worldFrame(0, 5, 0)
                    .thresholds(1000, Float.MAX_VALUE));
            steps(world, 10);
            assertTrue(world.events().jointEvents().isEmpty(), "under its threshold");

            joint.setForceThreshold(100);
            world.beginInterpolationFrame();
            world.step(1 / 60f, 4);
            world.step(1 / 60f, 4);
            List<WorldEvents.JointEvent> events = world.events().jointEvents();
            assertEquals(2, events.size(), "once per step");
            assertSame(joint, events.getFirst().joint());
            assertEquals(joint.id(), events.getFirst().jointId());

            joint.destroy(true);
            steps(world, 30);
            assertTrue(box.position(new Vector3d()).y < 4, "let go, it falls");
            assertTrue(world.events().jointEvents().isEmpty());
        }
    }

    @Test
    void aJointGoesWithEitherBodyAndWithTheWorld() {
        try (PhysicsWorld world = world(0)) {
            Body a = box(world, 0, 0, 0, 0.2f, 0.2f, 0.2f);
            Body b = box(world, 1, 0, 0, 0.2f, 0.2f, 0.2f);
            Body c = box(world, 2, 0, 0, 0.2f, 0.2f, 0.2f);
            RevoluteJoint ab = world.createJoint(new RevoluteJointDef().bodies(a, b));
            WeldJoint bc = world.createJoint(new WeldJointDef().bodies(b, c));
            assertEquals(List.of(ab, bc), b.joints());
            assertEquals(2, world.joints().size());
            assertSame(ab, world.joint(ab.id()));
            assertSame(c, bc.other(b));

            a.destroy();
            assertFalse(ab.isValid());
            assertFalse(B3.jointIsValid(ab.id()), "Box3D took it with the body");
            assertEquals(List.of(bc), b.joints());
            assertNull(world.joint(ab.id()));
            assertThrows(IllegalStateException.class, ab::angle);
            ab.destroy(false); // a no-op now

            bc.destroy(false);
            assertFalse(bc.isValid());
            assertTrue(b.joints().isEmpty()
                    && c.joints().isEmpty()
                    && world.joints().isEmpty());

            WeldJoint again = world.createJoint(new WeldJointDef().bodies(b, c));
            world.close();
            assertFalse(again.isValid());
        }
    }

    @Test
    void whatBox3DWouldStoreAndAssertOnIsRefusedFirst() {
        try (PhysicsWorld world = world(0);
                PhysicsWorld other = world(0)) {
            Body a = box(world, 0, 0, 0, 0.2f, 0.2f, 0.2f);
            Body b = box(world, 1, 0, 0, 0.2f, 0.2f, 0.2f);
            Body elsewhere = box(other, 0, 0, 0, 0.2f, 0.2f, 0.2f);
            SphericalJoint spherical = world.createJoint(new SphericalJointDef().bodies(a, b));
            RevoluteJoint revolute = world.createJoint(new RevoluteJointDef().bodies(a, b));
            List<Executable> refused = List.of(
                    () -> world.createJoint(new WeldJointDef()),
                    () -> world.createJoint(new WeldJointDef().bodies(a, a)),
                    () -> world.createJoint(new WeldJointDef().bodies(a, elsewhere)),
                    () -> other.createJoint(new WeldJointDef().bodies(a, b)),
                    () -> world.createJoint(
                            new WeldJointDef().bodies(a, b).localFrameA(0, 0, 0, new Quaternionf(0, 0, 0, 2))),
                    () -> world.createJoint(new SphericalJointDef().bodies(a, b).coneLimit(2)),
                    () -> world.createJoint(new RevoluteJointDef().bodies(a, b).limits(1, -1)),
                    () -> world.createJoint(new DistanceJointDef().bodies(a, b).length(0)),
                    () -> world.createJoint(new WeldJointDef().bodies(a, b).thresholds(-1, 0)),
                    () -> spherical.setConeLimit(PI),
                    () -> spherical.setTwistLimits(0.5f, -0.5f),
                    () -> spherical.setSpringHertz(-1),
                    () -> spherical.setMotorVelocity(Float.NaN, 0, 0),
                    () -> revolute.setTargetAngle(4),
                    () -> revolute.setMaxMotorTorque(-1),
                    () -> revolute.setLocalFrameA(Float.NaN, 0, 0, new Quaternionf()));
            for (Executable call : refused) {
                assertThrows(IllegalArgumentException.class, call);
            }
            assertEquals(2, world.joints().size(), "none of them made a joint");
        }
    }

    // ---- Box3D bugs our fork fixed, several fixed upstream since (02-box3d-fork.md §3.1) ----------

    /** Box3D kept warm starting a motor part its solve no longer ran: a puppet let go stayed held. */
    @Test
    void aMotorPartTurnedOffLetsGo() {
        for (boolean byHertz : new boolean[] {false, true}) {
            try (PhysicsWorld world = world(-10)) {
                Body base = anchor(world, 0, 0, 0);
                Body puppet = box(world, 0, 0, 0, 0.2f, 0.2f, 0.2f);
                MotorJoint motor = world.createJoint(
                        new MotorJointDef().bodies(base, puppet).linearSpring(8, 1, 1.0e5f));
                steps(world, 60);
                assertEquals(0, puppet.position(new Vector3d()).y, 0.05, "held at the target");
                if (byHertz) {
                    motor.setLinearHertz(0);
                } else {
                    motor.setMaxSpringForce(0);
                }
                motor.wakeBodies();
                steps(world, 30);
                assertTrue(
                        puppet.position(new Vector3d()).y < -1,
                        "falls once let go: " + puppet.position(new Vector3d()));
                assertEquals(0, motor.constraintForce(new Vector3f()).length(), 1e-3);
            }
        }
    }

    /** The same in every spring: one set to zero hertz kept warm starting its last impulse, holding what it held. */
    @Test
    void aSpringSetToZeroHertzLetsGo() {
        try (PhysicsWorld world = world(-10)) {
            Body anchor = anchor(world, 0, 0, 0);
            Body hinged = box(world, 0.5, 0, 0, 0.5f, 0.05f, 0.05f);
            RevoluteJoint hinge = world.createJoint(new RevoluteJointDef()
                    .bodies(anchor, hinged)
                    .worldFrame(0, 0, 0)
                    .spring(8, 1, 0));
            Body ball = box(world, 0.5, 0, 10, 0.5f, 0.05f, 0.05f);
            Body ballAnchor = anchor(world, 0, 0, 10);
            SphericalJoint socket = world.createJoint(new SphericalJointDef()
                    .bodies(ballAnchor, ball)
                    .worldFrame(0, 0, 10, new Quaternionf().rotateY(PI / 2))
                    .spring(8, 1, new Quaternionf()));
            Body slider = box(world, 0, 0, 20, 0.2f, 0.2f, 0.2f);
            PrismaticJoint slide = world.createJoint(new PrismaticJointDef()
                    .bodies(anchor(world, 0, 0, 20), slider)
                    .worldFrame(0, 0, 20, new Quaternionf().rotateZ(-PI / 2))
                    .spring(8, 1, 0));
            Body hanging = box(world, 0, -1, 30, 0.2f, 0.2f, 0.2f);
            DistanceJoint rope = world.createJoint(new DistanceJointDef()
                    .bodies(anchor(world, 0, 0, 30), hanging)
                    .length(1)
                    .spring(8, 1));
            Body wheel = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, -1, 40));
            wheel.createShape(new ShapeDef().density(1000), new Geometry.Sphere(0, 0, 0, 0.3f));
            WheelJoint axle = world.createJoint(new WheelJointDef()
                    .bodies(anchor(world, 0, 0, 40), wheel)
                    .worldFrame(0, -1, 40, new Quaternionf().rotateZ(-PI / 2))
                    .suspension(8, 1));
            steps(world, 60);
            assertEquals(0, hinge.angle(), 0.05, "each held where its spring wants it");
            assertEquals(0, socket.coneAngle(), 0.05);
            assertEquals(0, slide.translation(), 0.05);
            assertEquals(1, rope.currentLength(), 0.05);
            assertEquals(-1, wheel.position(new Vector3d()).y, 0.05);

            hinge.setSpringHertz(0);
            socket.setSpringHertz(0);
            slide.setSpringHertz(0);
            rope.setSpringHertz(0);
            axle.setSuspensionHertz(0);
            for (Joint joint : List.of(hinge, socket, slide, rope, axle)) {
                joint.wakeBodies();
            }
            steps(world, 30);
            assertTrue(Math.abs(hinge.angle()) > 0.5f, "the hinge swings down: " + hinge.angle());
            assertTrue(socket.coneAngle() > 0.5f, "the ball joint swings down: " + socket.coneAngle());
            assertTrue(slide.translation() > 1, "the slider falls: " + slide.translation());
            assertTrue(rope.currentLength() > 2, "the spring rope lets it fall: " + rope.currentLength());
            assertTrue(wheel.position(new Vector3d()).y < -2, "the wheel drops: " + wheel.position(new Vector3d()));
        }
    }

    /** The parallel joint had both: no max torque, or a spring of zero hertz, kept its last impulse. */
    @Test
    void aParallelJointTurnedOffLetsGo() {
        for (boolean byHertz : new boolean[] {false, true}) {
            try (PhysicsWorld world = world(-10)) {
                Body anchor = anchor(world, 0, 0, 0);
                Body bar = box(world, 0.5, 0, 0, 0.5f, 0.05f, 0.05f);
                world.createJoint(new SphericalJointDef().bodies(anchor, bar).worldFrame(0, 0, 0));
                // free to turn about frame z, here the bar's own axis, so it holds the bar level
                ParallelJoint level = world.createJoint(new ParallelJointDef()
                        .bodies(anchor, bar)
                        .worldFrame(0, 0, 0, new Quaternionf().rotateY(PI / 2))
                        .spring(8, 1, 1.0e5f));
                steps(world, 60);
                assertEquals(0, tilt(bar), 0.05, "held level");
                if (byHertz) {
                    level.setSpringHertz(0);
                } else {
                    level.setMaxTorque(0);
                }
                level.wakeBodies();
                steps(world, 30);
                assertTrue(tilt(bar) < -0.5, "swings down once let go: " + tilt(bar));
            }
        }
    }

    /** How far down the bar's own axis points. */
    private static double tilt(Body bar) {
        return bar.rotation(new Quaternionf()).transform(new Vector3f(1, 0, 0)).y;
    }

    /**
     * The distance joint's limit kept its last impulse when turned off, holding a taut spring rope at its old length.
     */
    @Test
    void aDistanceLimitTurnedOffLetsGo() {
        try (PhysicsWorld world = world(-10)) {
            Body hanging = box(world, 0, -1, 0, 0.2f, 0.2f, 0.2f);
            DistanceJoint rope = world.createJoint(new DistanceJointDef()
                    .bodies(anchor(world, 0, 0, 0), hanging)
                    .length(1)
                    .spring(0.5f, 1)
                    .limits(0.5f, 1.2f));
            steps(world, 120);
            assertEquals(1.2, rope.currentLength(), 0.02, "the weak spring stretches to the limit");
            rope.enableLimit(false);
            rope.wakeBodies();
            steps(world, 60);
            assertTrue(rope.currentLength() > 1.5, "and on past it once the limit is off: " + rope.currentLength());
        }
    }

    /**
     * Box3D added the cone limit's impulse where its solver subtracts it, and put the twist limit's along frame B's z
     * rather than its Jacobian: the torque a spherical joint reported, and broke at, was not the one it applied.
     */
    @Test
    void aSphericalJointReportsTheTorqueItApplies() {
        try (PhysicsWorld world = world(0)) {
            Body pivot = anchor(world, 0, 0, 0);
            Body bar = box(world, 0, 0, 0.5, 0.05f, 0.05f, 0.5f);
            bar.setAngularDamping(5);
            SphericalJoint joint = world.createJoint(new SphericalJointDef()
                    .bodies(pivot, bar)
                    .worldFrame(0, 0, 0)
                    .coneLimit(0.3f)
                    .twistLimits(-0.2f, 0.2f));
            for (int i = 0; i < 240; i++) {
                bar.applyTorque(20, 0, 2);
                steps(world, 1);
            }
            assertEquals(0.3f, joint.coneAngle(), 0.01f, "pressed into the cone");
            assertEquals(0.2f, joint.twistAngle(), 0.01f, "and the twist limit");
            Vector3f torque = joint.constraintTorque(new Vector3f());
            assertEquals(-20, torque.x, 0.5f, "holding back the push: " + torque);
            assertEquals(0, torque.y, 0.5f, "holding back the push: " + torque);
            assertEquals(-2, torque.z, 0.2f, "holding back the push: " + torque);
        }
    }

    /**
     * Box3D checked only the joints in the constraint graph's colors against their thresholds: a body's joints past the
     * twenty colors go to the overflow, and those were never reported, so never broken.
     */
    @Test
    void aJointInTheSolversOverflowIsReportedToo() {
        try (PhysicsWorld world = world(0)) {
            Body hub = box(world, 0, 0, 0, 0.3f, 0.3f, 0.3f);
            int count = 40;
            Body[] satellites = new Body[count];
            for (int i = 0; i < count; i++) {
                double angle = 2 * Math.PI * i / count;
                satellites[i] = box(world, 2 * Math.cos(angle), 0, 2 * Math.sin(angle), 0.05f, 0.05f, 0.05f);
                world.createJoint(new DistanceJointDef()
                        .bodies(hub, satellites[i])
                        .length(2)
                        .thresholds(1, Float.MAX_VALUE));
            }
            for (int i = 0; i < count; i++) {
                Vector3d p = satellites[i].position(new Vector3d());
                satellites[i].applyForceToCenter((float) p.x * 25, 0, (float) p.z * 25);
            }
            world.beginInterpolationFrame();
            world.step(1 / 60f, 4);
            assertEquals(
                    count,
                    world.events().jointEvents().stream()
                            .map(WorldEvents.JointEvent::joint)
                            .distinct()
                            .count(),
                    "every joint pulled past its threshold is reported");
        }
    }
}
