package com.lowdragmc.minebox3d.physics;

/** A {@link JointType#PRISMATIC} joint — see {@link PrismaticJointDef}. Translations in m along frame A's x axis. */
public final class PrismaticJoint extends Joint {

    PrismaticJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.PRISMATIC;
    }

    /** How far B has slid from where the frames coincide. */
    public float translation() {
        return get(JointParam.PRISMATIC_TRANSLATION);
    }

    /** m/s along the axis. */
    public float speed() {
        return get(JointParam.PRISMATIC_SPEED);
    }

    public boolean isSpringEnabled() {
        return getFlag(JointParam.PRISMATIC_SPRING);
    }

    public void enableSpring(boolean enable) {
        setFlag(JointParam.PRISMATIC_SPRING, enable);
    }

    public float springHertz() {
        return get(JointParam.PRISMATIC_HERTZ);
    }

    public void setSpringHertz(float hertz) {
        Checks.notNegative(hertz, "spring hertz");
        set(JointParam.PRISMATIC_HERTZ, hertz);
    }

    public float springDampingRatio() {
        return get(JointParam.PRISMATIC_DAMPING);
    }

    public void setSpringDampingRatio(float dampingRatio) {
        Checks.notNegative(dampingRatio, "spring damping ratio");
        set(JointParam.PRISMATIC_DAMPING, dampingRatio);
    }

    public float targetTranslation() {
        return get(JointParam.PRISMATIC_TARGET_TRANSLATION);
    }

    public void setTargetTranslation(float translation) {
        Checks.finite(translation, "target translation");
        set(JointParam.PRISMATIC_TARGET_TRANSLATION, translation);
    }

    public boolean isLimitEnabled() {
        return getFlag(JointParam.PRISMATIC_LIMIT);
    }

    public void enableLimit(boolean enable) {
        setFlag(JointParam.PRISMATIC_LIMIT, enable);
    }

    public float lowerLimit() {
        return get(JointParam.PRISMATIC_LIMITS, 0);
    }

    public float upperLimit() {
        return get(JointParam.PRISMATIC_LIMITS, 1);
    }

    public void setLimits(float lower, float upper) {
        Checks.range(lower, upper, "translation limits");
        set(JointParam.PRISMATIC_LIMITS, lower, upper, 0, 0);
    }

    public boolean isMotorEnabled() {
        return getFlag(JointParam.PRISMATIC_MOTOR);
    }

    public void enableMotor(boolean enable) {
        setFlag(JointParam.PRISMATIC_MOTOR, enable);
    }

    public float motorSpeed() {
        return get(JointParam.PRISMATIC_MOTOR_SPEED);
    }

    public void setMotorSpeed(float speed) {
        Checks.finite(speed, "motor speed");
        set(JointParam.PRISMATIC_MOTOR_SPEED, speed);
    }

    public float maxMotorForce() {
        return get(JointParam.PRISMATIC_MAX_MOTOR_FORCE);
    }

    public void setMaxMotorForce(float force) {
        Checks.notNegative(force, "max motor force");
        set(JointParam.PRISMATIC_MAX_MOTOR_FORCE, force);
    }

    /** The force the motor applied in the last step, N. */
    public float motorForce() {
        return get(JointParam.PRISMATIC_MOTOR_FORCE);
    }
}
