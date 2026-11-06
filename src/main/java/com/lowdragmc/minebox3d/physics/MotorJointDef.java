package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3MotorJointDef}: drives body B towards frame B coinciding with frame A, by springs of limited force,
 * and towards a velocity relative to A, by a limited force — a body following an animated target. Move the target by
 * changing frame A ({@link Joint#setLocalFrameA}) on a body that stands still or moves with the animation.
 */
public final class MotorJointDef extends JointDef<MotorJoint, MotorJointDef> {
    /** m/s of B relative to A, world space. */
    public float linearVelocityX, linearVelocityY, linearVelocityZ;
    /** N */
    public float maxVelocityForce;
    /** rad/s of B relative to A, world space. */
    public float angularVelocityX, angularVelocityY, angularVelocityZ;
    /** N·m */
    public float maxVelocityTorque;
    /** Position spring, Hz. */
    public float linearHertz;
    public float linearDampingRatio;
    /** N */
    public float maxSpringForce;
    /** Rotation spring, Hz. */
    public float angularHertz;
    public float angularDampingRatio;
    /** N·m */
    public float maxSpringTorque;

    public MotorJointDef() {
        super(JointType.MOTOR);
    }

    @Override
    MotorJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new MotorJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {
        Checks.finite(linearVelocityX, linearVelocityY, linearVelocityZ, "linear velocity");
        Checks.notNegative(maxVelocityForce, "max velocity force");
        Checks.finite(angularVelocityX, angularVelocityY, angularVelocityZ, "angular velocity");
        Checks.notNegative(maxVelocityTorque, "max velocity torque");
        Checks.notNegative(linearHertz, "linear hertz");
        Checks.notNegative(linearDampingRatio, "linear damping ratio");
        Checks.notNegative(maxSpringForce, "max spring force");
        Checks.notNegative(angularHertz, "angular hertz");
        Checks.notNegative(angularDampingRatio, "angular damping ratio");
        Checks.notNegative(maxSpringTorque, "max spring torque");
    }

    public MotorJointDef linearSpring(float hertz, float dampingRatio, float maxForce) {
        linearHertz = hertz;
        linearDampingRatio = dampingRatio;
        maxSpringForce = maxForce;
        return this;
    }

    public MotorJointDef angularSpring(float hertz, float dampingRatio, float maxTorque) {
        angularHertz = hertz;
        angularDampingRatio = dampingRatio;
        maxSpringTorque = maxTorque;
        return this;
    }
}
