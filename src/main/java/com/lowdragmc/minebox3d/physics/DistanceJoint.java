package com.lowdragmc.minebox3d.physics;

/** A {@link JointType#DISTANCE} joint — see {@link DistanceJointDef}. Lengths in m. */
public final class DistanceJoint extends Joint {

    DistanceJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.DISTANCE;
    }

    /** The rest length. */
    public float length() {
        return get(JointParam.DISTANCE_LENGTH);
    }

    /** Box3D keeps it above a small minimum. */
    public void setLength(float length) {
        Checks.positive(length, "length");
        set(JointParam.DISTANCE_LENGTH, length);
    }

    /** The distance between the two points now. */
    public float currentLength() {
        return get(JointParam.DISTANCE_CURRENT_LENGTH);
    }

    public boolean isSpringEnabled() {
        return getFlag(JointParam.DISTANCE_SPRING);
    }

    /** Off: rigid, ignoring the limit and the motor. */
    public void enableSpring(boolean enable) {
        setFlag(JointParam.DISTANCE_SPRING, enable);
    }

    public float lowerSpringForce() {
        return get(JointParam.DISTANCE_SPRING_FORCE_RANGE, 0);
    }

    public float upperSpringForce() {
        return get(JointParam.DISTANCE_SPRING_FORCE_RANGE, 1);
    }

    /** N: how much the spring may pull (negative) and push; a rope takes no push. */
    public void setSpringForceRange(float lower, float upper) {
        Checks.range(lower, upper, "spring force range");
        set(JointParam.DISTANCE_SPRING_FORCE_RANGE, lower, upper, 0, 0);
    }

    public float springHertz() {
        return get(JointParam.DISTANCE_HERTZ);
    }

    public void setSpringHertz(float hertz) {
        Checks.notNegative(hertz, "spring hertz");
        set(JointParam.DISTANCE_HERTZ, hertz);
    }

    public float springDampingRatio() {
        return get(JointParam.DISTANCE_DAMPING);
    }

    public void setSpringDampingRatio(float dampingRatio) {
        Checks.notNegative(dampingRatio, "spring damping ratio");
        set(JointParam.DISTANCE_DAMPING, dampingRatio);
    }

    public boolean isLimitEnabled() {
        return getFlag(JointParam.DISTANCE_LIMIT);
    }

    public void enableLimit(boolean enable) {
        setFlag(JointParam.DISTANCE_LIMIT, enable);
    }

    public float minLength() {
        return get(JointParam.DISTANCE_LENGTH_RANGE, 0);
    }

    public float maxLength() {
        return get(JointParam.DISTANCE_LENGTH_RANGE, 1);
    }

    public void setLengthRange(float min, float max) {
        Checks.notNegative(min, "min length");
        Checks.range(min, max, "length range");
        set(JointParam.DISTANCE_LENGTH_RANGE, min, max, 0, 0);
    }

    public boolean isMotorEnabled() {
        return getFlag(JointParam.DISTANCE_MOTOR);
    }

    public void enableMotor(boolean enable) {
        setFlag(JointParam.DISTANCE_MOTOR, enable);
    }

    /** m/s */
    public float motorSpeed() {
        return get(JointParam.DISTANCE_MOTOR_SPEED);
    }

    public void setMotorSpeed(float speed) {
        Checks.finite(speed, "motor speed");
        set(JointParam.DISTANCE_MOTOR_SPEED, speed);
    }

    public float maxMotorForce() {
        return get(JointParam.DISTANCE_MAX_MOTOR_FORCE);
    }

    public void setMaxMotorForce(float force) {
        Checks.notNegative(force, "max motor force");
        set(JointParam.DISTANCE_MAX_MOTOR_FORCE, force);
    }

    /** The force the motor applied in the last step, N. */
    public float motorForce() {
        return get(JointParam.DISTANCE_MOTOR_FORCE);
    }
}
