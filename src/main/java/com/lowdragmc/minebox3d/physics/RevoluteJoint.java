package com.lowdragmc.minebox3d.physics;

/** A {@link JointType#REVOLUTE} joint — see {@link RevoluteJointDef}. Angles in radians. */
public final class RevoluteJoint extends Joint {

    RevoluteJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.REVOLUTE;
    }

    static void checkTargetAngle(float angle) {
        if (!(angle >= -(float) Math.PI) || !(angle <= (float) Math.PI)) {
            throw new IllegalArgumentException("target angle must be -π to π: " + angle);
        }
    }

    /** The angle now, B relative to A about the shared z axis. */
    public float angle() {
        return get(JointParam.REVOLUTE_ANGLE);
    }

    public boolean isSpringEnabled() {
        return getFlag(JointParam.REVOLUTE_SPRING);
    }

    public void enableSpring(boolean enable) {
        setFlag(JointParam.REVOLUTE_SPRING, enable);
    }

    public float springHertz() {
        return get(JointParam.REVOLUTE_HERTZ);
    }

    public void setSpringHertz(float hertz) {
        Checks.notNegative(hertz, "spring hertz");
        set(JointParam.REVOLUTE_HERTZ, hertz);
    }

    public float springDampingRatio() {
        return get(JointParam.REVOLUTE_DAMPING);
    }

    public void setSpringDampingRatio(float dampingRatio) {
        Checks.notNegative(dampingRatio, "spring damping ratio");
        set(JointParam.REVOLUTE_DAMPING, dampingRatio);
    }

    public float targetAngle() {
        return get(JointParam.REVOLUTE_TARGET_ANGLE);
    }

    public void setTargetAngle(float angle) {
        checkTargetAngle(angle);
        set(JointParam.REVOLUTE_TARGET_ANGLE, angle);
    }

    public boolean isLimitEnabled() {
        return getFlag(JointParam.REVOLUTE_LIMIT);
    }

    public void enableLimit(boolean enable) {
        setFlag(JointParam.REVOLUTE_LIMIT, enable);
    }

    public float lowerLimit() {
        return get(JointParam.REVOLUTE_LIMITS, 0);
    }

    public float upperLimit() {
        return get(JointParam.REVOLUTE_LIMITS, 1);
    }

    /** Box3D clamps both to ±0.99π. */
    public void setLimits(float lower, float upper) {
        Checks.range(lower, upper, "angle limits");
        set(JointParam.REVOLUTE_LIMITS, lower, upper, 0, 0);
    }

    public boolean isMotorEnabled() {
        return getFlag(JointParam.REVOLUTE_MOTOR);
    }

    public void enableMotor(boolean enable) {
        setFlag(JointParam.REVOLUTE_MOTOR, enable);
    }

    /** rad/s */
    public float motorSpeed() {
        return get(JointParam.REVOLUTE_MOTOR_SPEED);
    }

    public void setMotorSpeed(float speed) {
        Checks.finite(speed, "motor speed");
        set(JointParam.REVOLUTE_MOTOR_SPEED, speed);
    }

    /** The torque the motor applied in the last step, N·m. */
    public float motorTorque() {
        return get(JointParam.REVOLUTE_MOTOR_TORQUE);
    }

    public float maxMotorTorque() {
        return get(JointParam.REVOLUTE_MAX_MOTOR_TORQUE);
    }

    public void setMaxMotorTorque(float torque) {
        Checks.notNegative(torque, "max motor torque");
        set(JointParam.REVOLUTE_MAX_MOTOR_TORQUE, torque);
    }
}
