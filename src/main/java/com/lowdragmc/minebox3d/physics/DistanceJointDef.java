package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3DistanceJointDef}: keeps the origins of the two frames a length apart — rigid like a rod, or as a
 * spring with a force range (a rope takes no compression), within limits, with a motor along the line.
 */
public final class DistanceJointDef extends JointDef<DistanceJoint, DistanceJointDef> {
    /** Rest length, m; Box3D keeps it above a small minimum. */
    public float length;
    /** False: rigid, ignoring the limit and the motor. */
    public boolean enableSpring;
    /** N: how much the spring may pull (negative) and push. */
    public float lowerSpringForce, upperSpringForce;

    public float hertz;
    public float dampingRatio;
    public boolean enableLimit;
    public float minLength, maxLength;
    public boolean enableMotor;
    /** N */
    public float maxMotorForce;
    /** m/s */
    public float motorSpeed;

    public DistanceJointDef() {
        super(JointType.DISTANCE);
    }

    @Override
    DistanceJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new DistanceJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {
        Checks.positive(length, "length");
        Checks.range(lowerSpringForce, upperSpringForce, "spring force range");
        Checks.notNegative(hertz, "spring hertz");
        Checks.notNegative(dampingRatio, "spring damping ratio");
        Checks.notNegative(minLength, "min length");
        Checks.range(minLength, maxLength, "length range");
        Checks.notNegative(maxMotorForce, "max motor force");
        Checks.finite(motorSpeed, "motor speed");
    }

    public DistanceJointDef length(float length) {
        this.length = length;
        return this;
    }

    public DistanceJointDef spring(float hertz, float dampingRatio) {
        enableSpring = true;
        this.hertz = hertz;
        this.dampingRatio = dampingRatio;
        return this;
    }

    public DistanceJointDef limits(float minLength, float maxLength) {
        enableLimit = true;
        this.minLength = minLength;
        this.maxLength = maxLength;
        return this;
    }
}
