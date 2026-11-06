package com.lowdragmc.minebox3d.physics;

import org.joml.Vector3f;

/**
 * A {@link JointType#MOTOR} joint — see {@link MotorJointDef}. The pose it drives towards is frame B coinciding with
 * frame A: move frame A ({@link #setLocalFrameA}) to move the target.
 */
public final class MotorJoint extends Joint {

    MotorJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.MOTOR;
    }

    /** m/s of B relative to A, world space. */
    public Vector3f linearVelocity(Vector3f out) {
        return getVector(JointParam.MOTOR_LINEAR_VELOCITY, out);
    }

    public void setLinearVelocity(float x, float y, float z) {
        Checks.finite(x, y, z, "linear velocity");
        set(JointParam.MOTOR_LINEAR_VELOCITY, x, y, z, 0);
    }

    /** rad/s of B relative to A, world space. */
    public Vector3f angularVelocity(Vector3f out) {
        return getVector(JointParam.MOTOR_ANGULAR_VELOCITY, out);
    }

    public void setAngularVelocity(float x, float y, float z) {
        Checks.finite(x, y, z, "angular velocity");
        set(JointParam.MOTOR_ANGULAR_VELOCITY, x, y, z, 0);
    }

    public float maxVelocityForce() {
        return get(JointParam.MOTOR_MAX_VELOCITY_FORCE);
    }

    public void setMaxVelocityForce(float force) {
        Checks.notNegative(force, "max velocity force");
        set(JointParam.MOTOR_MAX_VELOCITY_FORCE, force);
    }

    public float maxVelocityTorque() {
        return get(JointParam.MOTOR_MAX_VELOCITY_TORQUE);
    }

    public void setMaxVelocityTorque(float torque) {
        Checks.notNegative(torque, "max velocity torque");
        set(JointParam.MOTOR_MAX_VELOCITY_TORQUE, torque);
    }

    public float linearHertz() {
        return get(JointParam.MOTOR_LINEAR_HERTZ);
    }

    public void setLinearHertz(float hertz) {
        Checks.notNegative(hertz, "linear hertz");
        set(JointParam.MOTOR_LINEAR_HERTZ, hertz);
    }

    public float linearDampingRatio() {
        return get(JointParam.MOTOR_LINEAR_DAMPING);
    }

    public void setLinearDampingRatio(float dampingRatio) {
        Checks.notNegative(dampingRatio, "linear damping ratio");
        set(JointParam.MOTOR_LINEAR_DAMPING, dampingRatio);
    }

    public float angularHertz() {
        return get(JointParam.MOTOR_ANGULAR_HERTZ);
    }

    public void setAngularHertz(float hertz) {
        Checks.notNegative(hertz, "angular hertz");
        set(JointParam.MOTOR_ANGULAR_HERTZ, hertz);
    }

    public float angularDampingRatio() {
        return get(JointParam.MOTOR_ANGULAR_DAMPING);
    }

    public void setAngularDampingRatio(float dampingRatio) {
        Checks.notNegative(dampingRatio, "angular damping ratio");
        set(JointParam.MOTOR_ANGULAR_DAMPING, dampingRatio);
    }

    public float maxSpringForce() {
        return get(JointParam.MOTOR_MAX_SPRING_FORCE);
    }

    public void setMaxSpringForce(float force) {
        Checks.notNegative(force, "max spring force");
        set(JointParam.MOTOR_MAX_SPRING_FORCE, force);
    }

    public float maxSpringTorque() {
        return get(JointParam.MOTOR_MAX_SPRING_TORQUE);
    }

    public void setMaxSpringTorque(float torque) {
        Checks.notNegative(torque, "max spring torque");
        set(JointParam.MOTOR_MAX_SPRING_TORQUE, torque);
    }
}
