package com.lowdragmc.minebox3d.physics;

import org.joml.Vector3f;

/** A {@link JointType#POGO} joint — see {@link PogoJointDef}. */
public final class PogoJoint extends Joint {

    PogoJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.POGO;
    }

    public float restLength() {
        return get(JointParam.POGO_REST_LENGTH);
    }

    public void setRestLength(float length) {
        Checks.notNegative(length, "rest length");
        set(JointParam.POGO_REST_LENGTH, length);
    }

    public float springHertz() {
        return get(JointParam.POGO_HERTZ);
    }

    public void setSpringHertz(float hertz) {
        Checks.notNegative(hertz, "hertz");
        set(JointParam.POGO_HERTZ, hertz);
    }

    public float springDampingRatio() {
        return get(JointParam.POGO_DAMPING);
    }

    public void setSpringDampingRatio(float dampingRatio) {
        Checks.notNegative(dampingRatio, "damping ratio");
        set(JointParam.POGO_DAMPING, dampingRatio);
    }

    public float maxTensionForce() {
        return get(JointParam.POGO_MAX_TENSION_FORCE);
    }

    public void setMaxTensionForce(float force) {
        Checks.notNegative(force, "max tension force");
        set(JointParam.POGO_MAX_TENSION_FORCE, force);
    }

    public float maxCompressionForce() {
        return get(JointParam.POGO_MAX_COMPRESSION_FORCE);
    }

    public void setMaxCompressionForce(float force) {
        Checks.notNegative(force, "max compression force");
        set(JointParam.POGO_MAX_COMPRESSION_FORCE, force);
    }

    /** The ground's normal at the contact, world space. */
    public Vector3f normal(Vector3f out) {
        return getVector(JointParam.POGO_NORMAL, out);
    }

    public void setNormal(float x, float y, float z) {
        Checks.unitVector(x, y, z, "normal");
        set(JointParam.POGO_NORMAL, x, y, z, 0);
    }

    /** m: how long the spring is now. */
    public float currentLength() {
        return get(JointParam.POGO_CURRENT_LENGTH);
    }

    /** m/s: how fast the spring is lengthening, as its internal state has it — for {@link PogoJointDef#velocity}. */
    public float currentSpeed() {
        return get(JointParam.POGO_CURRENT_SPEED);
    }

    /** N·s: the spring's impulse over the last substep — what the ground took; for {@link PogoJointDef#impulse}. */
    public float impulse() {
        return get(JointParam.POGO_IMPULSE);
    }
}
