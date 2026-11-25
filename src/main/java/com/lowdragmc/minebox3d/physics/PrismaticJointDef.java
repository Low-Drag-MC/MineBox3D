package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3PrismaticJointDef}: body B slides along frame A's x axis and does not turn relative to A — a piston,
 * a sliding door. The translation is zero where the frames' origins coincide.
 */
public final class PrismaticJointDef extends JointDef<PrismaticJoint, PrismaticJointDef> {
    public boolean enableSpring;
    public float hertz;
    public float dampingRatio;
    /** m: where the spring pulls to. */
    public float targetTranslation;

    public boolean enableLimit;
    public float lowerTranslation, upperTranslation;
    public boolean enableMotor;
    /** N */
    public float maxMotorForce;
    /** m/s */
    public float motorSpeed;

    public PrismaticJointDef() {
        super(JointType.PRISMATIC);
    }

    @Override
    PrismaticJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new PrismaticJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {
        Checks.notNegative(hertz, "spring hertz");
        Checks.notNegative(dampingRatio, "spring damping ratio");
        Checks.finite(targetTranslation, "target translation");
        Checks.range(lowerTranslation, upperTranslation, "translation limits");
        Checks.notNegative(maxMotorForce, "max motor force");
        Checks.finite(motorSpeed, "motor speed");
    }

    public PrismaticJointDef limits(float lower, float upper) {
        enableLimit = true;
        lowerTranslation = lower;
        upperTranslation = upper;
        return this;
    }

    public PrismaticJointDef motor(float maxForce, float speed) {
        enableMotor = true;
        maxMotorForce = maxForce;
        motorSpeed = speed;
        return this;
    }

    public PrismaticJointDef spring(float hertz, float dampingRatio, float targetTranslation) {
        enableSpring = true;
        this.hertz = hertz;
        this.dampingRatio = dampingRatio;
        this.targetTranslation = targetTranslation;
        return this;
    }
}
