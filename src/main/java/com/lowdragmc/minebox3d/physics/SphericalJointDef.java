package com.lowdragmc.minebox3d.physics;

import org.joml.Quaternionfc;

/**
 * Box3D's {@code b3SphericalJointDef}: body B turns freely about a point shared with body A — the ragdoll's shoulders,
 * hips and neck. The swing is limited to a cone about frame A's z axis, the twist about frame B's z axis to a range.
 */
public final class SphericalJointDef extends JointDef<SphericalJoint, SphericalJointDef> {
    public boolean enableSpring;
    /** Spring towards {@link #targetRotationX the target rotation}, Hz (Box3D may clamp it for stability). */
    public float hertz;
    public float dampingRatio;
    /** Frame B relative to frame A that the spring pulls towards. */
    public float targetRotationX, targetRotationY, targetRotationZ, targetRotationW;
    public boolean enableConeLimit;
    /** Radians, 0 to π/2: the widest swing of frame B's z axis away from frame A's. */
    public float coneAngle;
    public boolean enableTwistLimit;
    /** Radians, Box3D clamps both to ±0.99π. */
    public float lowerTwistAngle, upperTwistAngle;
    public boolean enableMotor;
    /** N·m */
    public float maxMotorTorque;
    /** The angular velocity of B relative to A the motor drives towards, rad/s, world space. */
    public float motorVelocityX, motorVelocityY, motorVelocityZ;

    public SphericalJointDef() {
        super(JointType.SPHERICAL);
    }

    @Override
    SphericalJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new SphericalJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {
        Checks.notNegative(hertz, "spring hertz");
        Checks.notNegative(dampingRatio, "spring damping ratio");
        Checks.rotation(targetRotationX, targetRotationY, targetRotationZ, targetRotationW, "target rotation");
        SphericalJoint.checkCone(coneAngle);
        Checks.range(lowerTwistAngle, upperTwistAngle, "twist limits");
        Checks.notNegative(maxMotorTorque, "max motor torque");
        Checks.finite(motorVelocityX, motorVelocityY, motorVelocityZ, "motor velocity");
    }

    public SphericalJointDef coneLimit(float angle) {
        enableConeLimit = true;
        coneAngle = angle;
        return this;
    }

    public SphericalJointDef twistLimits(float lower, float upper) {
        enableTwistLimit = true;
        lowerTwistAngle = lower;
        upperTwistAngle = upper;
        return this;
    }

    public SphericalJointDef spring(float hertz, float dampingRatio, Quaternionfc target) {
        enableSpring = true;
        this.hertz = hertz;
        this.dampingRatio = dampingRatio;
        targetRotationX = target.x();
        targetRotationY = target.y();
        targetRotationZ = target.z();
        targetRotationW = target.w();
        return this;
    }

    public SphericalJointDef motor(float maxTorque, float vx, float vy, float vz) {
        enableMotor = true;
        maxMotorTorque = maxTorque;
        motorVelocityX = vx;
        motorVelocityY = vy;
        motorVelocityZ = vz;
        return this;
    }
}
