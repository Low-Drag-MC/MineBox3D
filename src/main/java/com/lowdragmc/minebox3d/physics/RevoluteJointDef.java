package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3RevoluteJointDef}: body B turns about the z axis the two frames share — a hinge, a door, an elbow.
 * The angle is zero where the frames coincide.
 */
public final class RevoluteJointDef extends JointDef<RevoluteJoint, RevoluteJointDef> {
    /** Radians, -π to π: the angle the spring pulls towards. */
    public float targetAngle;

    public boolean enableSpring;
    public float hertz;
    public float dampingRatio;
    public boolean enableLimit;
    /** Radians, Box3D clamps both to ±0.99π. */
    public float lowerAngle, upperAngle;

    public boolean enableMotor;
    /** N·m */
    public float maxMotorTorque;
    /** rad/s */
    public float motorSpeed;

    public RevoluteJointDef() {
        super(JointType.REVOLUTE);
    }

    @Override
    RevoluteJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new RevoluteJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {
        RevoluteJoint.checkTargetAngle(targetAngle);
        Checks.notNegative(hertz, "spring hertz");
        Checks.notNegative(dampingRatio, "spring damping ratio");
        Checks.range(lowerAngle, upperAngle, "angle limits");
        Checks.notNegative(maxMotorTorque, "max motor torque");
        Checks.finite(motorSpeed, "motor speed");
    }

    public RevoluteJointDef limits(float lower, float upper) {
        enableLimit = true;
        lowerAngle = lower;
        upperAngle = upper;
        return this;
    }

    public RevoluteJointDef spring(float hertz, float dampingRatio, float targetAngle) {
        enableSpring = true;
        this.hertz = hertz;
        this.dampingRatio = dampingRatio;
        this.targetAngle = targetAngle;
        return this;
    }

    public RevoluteJointDef motor(float maxTorque, float speed) {
        enableMotor = true;
        maxMotorTorque = maxTorque;
        motorSpeed = speed;
        return this;
    }
}
