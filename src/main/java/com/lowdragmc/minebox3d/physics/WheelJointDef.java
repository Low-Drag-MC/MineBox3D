package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3WheelJointDef}: body A is the chassis, body B the wheel. The wheel spins about frame B's z axis, rides
 * a suspension along frame A's x axis, and may steer about it.
 */
public final class WheelJointDef extends JointDef<WheelJoint, WheelJointDef> {
    public boolean enableSuspensionSpring;
    public float suspensionHertz;
    public float suspensionDampingRatio;
    public boolean enableSuspensionLimit;
    /** m along frame A's x axis. */
    public float lowerSuspensionLimit, upperSuspensionLimit;
    public boolean enableSpinMotor;
    /** N·m */
    public float maxSpinTorque;
    /** rad/s */
    public float spinSpeed;
    /** False: the wheel points straight ahead. */
    public boolean enableSteering;
    public float steeringHertz;
    public float steeringDampingRatio;
    /** Radians. */
    public float targetSteeringAngle;
    /** N·m */
    public float maxSteeringTorque;
    public boolean enableSteeringLimit;
    /** Radians. */
    public float lowerSteeringLimit, upperSteeringLimit;

    public WheelJointDef() {
        super(JointType.WHEEL);
    }

    @Override
    WheelJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new WheelJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {
        Checks.notNegative(suspensionHertz, "suspension hertz");
        Checks.notNegative(suspensionDampingRatio, "suspension damping ratio");
        Checks.range(lowerSuspensionLimit, upperSuspensionLimit, "suspension limits");
        Checks.notNegative(maxSpinTorque, "max spin torque");
        Checks.finite(spinSpeed, "spin speed");
        Checks.notNegative(steeringHertz, "steering hertz");
        Checks.notNegative(steeringDampingRatio, "steering damping ratio");
        Checks.finite(targetSteeringAngle, "target steering angle");
        Checks.notNegative(maxSteeringTorque, "max steering torque");
        Checks.range(lowerSteeringLimit, upperSteeringLimit, "steering limits");
    }

    public WheelJointDef suspension(float hertz, float dampingRatio) {
        enableSuspensionSpring = true;
        suspensionHertz = hertz;
        suspensionDampingRatio = dampingRatio;
        return this;
    }

    public WheelJointDef spinMotor(float maxTorque, float speed) {
        enableSpinMotor = true;
        maxSpinTorque = maxTorque;
        spinSpeed = speed;
        return this;
    }
}
