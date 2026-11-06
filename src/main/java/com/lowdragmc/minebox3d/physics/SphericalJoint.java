package com.lowdragmc.minebox3d.physics;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/** A {@link JointType#SPHERICAL} joint — see {@link SphericalJointDef}. Angles in radians. */
public final class SphericalJoint extends Joint {

    SphericalJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.SPHERICAL;
    }

    /** Box3D keeps the cone to a quarter turn: it clamps a definition's and asserts on a setter's. */
    static void checkCone(float angle) {
        if (!(angle >= 0) || !(angle <= 0.5f * (float) Math.PI)) {
            throw new IllegalArgumentException("cone angle must be 0 to π/2 (Box3D's limit): " + angle);
        }
    }

    public boolean isConeLimitEnabled() {
        return getFlag(JointParam.SPHERICAL_CONE_LIMIT);
    }

    public void enableConeLimit(boolean enable) {
        setFlag(JointParam.SPHERICAL_CONE_LIMIT, enable);
    }

    /** The widest swing allowed. */
    public float coneLimit() {
        return get(JointParam.SPHERICAL_CONE_LIMIT_ANGLE);
    }

    public void setConeLimit(float angle) {
        checkCone(angle);
        set(JointParam.SPHERICAL_CONE_LIMIT_ANGLE, angle);
    }

    /** How far frame B's z axis is swung from frame A's now. */
    public float coneAngle() {
        return get(JointParam.SPHERICAL_CONE_ANGLE);
    }

    public boolean isTwistLimitEnabled() {
        return getFlag(JointParam.SPHERICAL_TWIST_LIMIT);
    }

    public void enableTwistLimit(boolean enable) {
        setFlag(JointParam.SPHERICAL_TWIST_LIMIT, enable);
    }

    public float lowerTwistLimit() {
        return get(JointParam.SPHERICAL_TWIST_LIMITS, 0);
    }

    public float upperTwistLimit() {
        return get(JointParam.SPHERICAL_TWIST_LIMITS, 1);
    }

    /** Box3D clamps both to ±0.99π. */
    public void setTwistLimits(float lower, float upper) {
        Checks.range(lower, upper, "twist limits");
        set(JointParam.SPHERICAL_TWIST_LIMITS, lower, upper, 0, 0);
    }

    /** The twist about frame B's z axis now. */
    public float twistAngle() {
        return get(JointParam.SPHERICAL_TWIST_ANGLE);
    }

    public boolean isSpringEnabled() {
        return getFlag(JointParam.SPHERICAL_SPRING);
    }

    public void enableSpring(boolean enable) {
        setFlag(JointParam.SPHERICAL_SPRING, enable);
    }

    public float springHertz() {
        return get(JointParam.SPHERICAL_HERTZ);
    }

    public void setSpringHertz(float hertz) {
        Checks.notNegative(hertz, "spring hertz");
        set(JointParam.SPHERICAL_HERTZ, hertz);
    }

    public float springDampingRatio() {
        return get(JointParam.SPHERICAL_DAMPING);
    }

    public void setSpringDampingRatio(float dampingRatio) {
        Checks.notNegative(dampingRatio, "spring damping ratio");
        set(JointParam.SPHERICAL_DAMPING, dampingRatio);
    }

    /** Frame B relative to frame A that the spring pulls towards. */
    public Quaternionf targetRotation(Quaternionf out) {
        return getRotation(JointParam.SPHERICAL_TARGET_ROTATION, out);
    }

    public void setTargetRotation(Quaternionfc target) {
        Checks.rotation(target.x(), target.y(), target.z(), target.w(), "target rotation");
        set(JointParam.SPHERICAL_TARGET_ROTATION, target.x(), target.y(), target.z(), target.w());
    }

    public boolean isMotorEnabled() {
        return getFlag(JointParam.SPHERICAL_MOTOR);
    }

    public void enableMotor(boolean enable) {
        setFlag(JointParam.SPHERICAL_MOTOR, enable);
    }

    /** The angular velocity of B relative to A the motor drives towards, rad/s, world space. */
    public Vector3f motorVelocity(Vector3f out) {
        return getVector(JointParam.SPHERICAL_MOTOR_VELOCITY, out);
    }

    public void setMotorVelocity(float x, float y, float z) {
        Checks.finite(x, y, z, "motor velocity");
        set(JointParam.SPHERICAL_MOTOR_VELOCITY, x, y, z, 0);
    }

    /** The torque the motor applied in the last step, N·m. */
    public Vector3f motorTorque(Vector3f out) {
        return getVector(JointParam.SPHERICAL_MOTOR_TORQUE, out);
    }

    public float maxMotorTorque() {
        return get(JointParam.SPHERICAL_MAX_MOTOR_TORQUE);
    }

    public void setMaxMotorTorque(float torque) {
        Checks.notNegative(torque, "max motor torque");
        set(JointParam.SPHERICAL_MAX_MOTOR_TORQUE, torque);
    }
}
