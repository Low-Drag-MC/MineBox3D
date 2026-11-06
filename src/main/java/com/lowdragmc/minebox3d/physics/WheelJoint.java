package com.lowdragmc.minebox3d.physics;

/** A {@link JointType#WHEEL} joint — see {@link WheelJointDef}. Body A is the chassis, body B the wheel. */
public final class WheelJoint extends Joint {

    WheelJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.WHEEL;
    }

    // ---- suspension ---------------------------------------------------------------------------

    public boolean isSuspensionEnabled() {
        return getFlag(JointParam.WHEEL_SUSPENSION);
    }

    public void enableSuspension(boolean enable) {
        setFlag(JointParam.WHEEL_SUSPENSION, enable);
    }

    public float suspensionHertz() {
        return get(JointParam.WHEEL_SUSPENSION_HERTZ);
    }

    public void setSuspensionHertz(float hertz) {
        Checks.notNegative(hertz, "suspension hertz");
        set(JointParam.WHEEL_SUSPENSION_HERTZ, hertz);
    }

    public float suspensionDampingRatio() {
        return get(JointParam.WHEEL_SUSPENSION_DAMPING);
    }

    public void setSuspensionDampingRatio(float dampingRatio) {
        Checks.notNegative(dampingRatio, "suspension damping ratio");
        set(JointParam.WHEEL_SUSPENSION_DAMPING, dampingRatio);
    }

    public boolean isSuspensionLimitEnabled() {
        return getFlag(JointParam.WHEEL_SUSPENSION_LIMIT);
    }

    public void enableSuspensionLimit(boolean enable) {
        setFlag(JointParam.WHEEL_SUSPENSION_LIMIT, enable);
    }

    public float lowerSuspensionLimit() {
        return get(JointParam.WHEEL_SUSPENSION_LIMITS, 0);
    }

    public float upperSuspensionLimit() {
        return get(JointParam.WHEEL_SUSPENSION_LIMITS, 1);
    }

    public void setSuspensionLimits(float lower, float upper) {
        Checks.range(lower, upper, "suspension limits");
        set(JointParam.WHEEL_SUSPENSION_LIMITS, lower, upper, 0, 0);
    }

    // ---- spin ---------------------------------------------------------------------------------

    public boolean isSpinMotorEnabled() {
        return getFlag(JointParam.WHEEL_SPIN_MOTOR);
    }

    public void enableSpinMotor(boolean enable) {
        setFlag(JointParam.WHEEL_SPIN_MOTOR, enable);
    }

    /** The spin the motor drives towards, rad/s. */
    public float spinMotorSpeed() {
        return get(JointParam.WHEEL_SPIN_MOTOR_SPEED);
    }

    public void setSpinMotorSpeed(float speed) {
        Checks.finite(speed, "spin speed");
        set(JointParam.WHEEL_SPIN_MOTOR_SPEED, speed);
    }

    public float maxSpinTorque() {
        return get(JointParam.WHEEL_MAX_SPIN_TORQUE);
    }

    public void setMaxSpinTorque(float torque) {
        Checks.notNegative(torque, "max spin torque");
        set(JointParam.WHEEL_MAX_SPIN_TORQUE, torque);
    }

    /** How fast the wheel spins now, rad/s. */
    public float spinSpeed() {
        return get(JointParam.WHEEL_SPIN_SPEED);
    }

    /** The torque the spin motor applied in the last step, N·m. */
    public float spinTorque() {
        return get(JointParam.WHEEL_SPIN_TORQUE);
    }

    // ---- steering -----------------------------------------------------------------------------

    public boolean isSteeringEnabled() {
        return getFlag(JointParam.WHEEL_STEERING);
    }

    public void enableSteering(boolean enable) {
        setFlag(JointParam.WHEEL_STEERING, enable);
    }

    public float steeringHertz() {
        return get(JointParam.WHEEL_STEERING_HERTZ);
    }

    public void setSteeringHertz(float hertz) {
        Checks.notNegative(hertz, "steering hertz");
        set(JointParam.WHEEL_STEERING_HERTZ, hertz);
    }

    public float steeringDampingRatio() {
        return get(JointParam.WHEEL_STEERING_DAMPING);
    }

    public void setSteeringDampingRatio(float dampingRatio) {
        Checks.notNegative(dampingRatio, "steering damping ratio");
        set(JointParam.WHEEL_STEERING_DAMPING, dampingRatio);
    }

    public float maxSteeringTorque() {
        return get(JointParam.WHEEL_MAX_STEERING_TORQUE);
    }

    public void setMaxSteeringTorque(float torque) {
        Checks.notNegative(torque, "max steering torque");
        set(JointParam.WHEEL_MAX_STEERING_TORQUE, torque);
    }

    public boolean isSteeringLimitEnabled() {
        return getFlag(JointParam.WHEEL_STEERING_LIMIT);
    }

    public void enableSteeringLimit(boolean enable) {
        setFlag(JointParam.WHEEL_STEERING_LIMIT, enable);
    }

    public float lowerSteeringLimit() {
        return get(JointParam.WHEEL_STEERING_LIMITS, 0);
    }

    public float upperSteeringLimit() {
        return get(JointParam.WHEEL_STEERING_LIMITS, 1);
    }

    /** Radians. */
    public void setSteeringLimits(float lower, float upper) {
        Checks.range(lower, upper, "steering limits");
        set(JointParam.WHEEL_STEERING_LIMITS, lower, upper, 0, 0);
    }

    public float targetSteeringAngle() {
        return get(JointParam.WHEEL_TARGET_STEERING_ANGLE);
    }

    public void setTargetSteeringAngle(float angle) {
        Checks.finite(angle, "target steering angle");
        set(JointParam.WHEEL_TARGET_STEERING_ANGLE, angle);
    }

    /** The steering angle now, radians. */
    public float steeringAngle() {
        return get(JointParam.WHEEL_STEERING_ANGLE);
    }

    /** The torque the steering applied in the last step, N·m. */
    public float steeringTorque() {
        return get(JointParam.WHEEL_STEERING_TORQUE);
    }
}
