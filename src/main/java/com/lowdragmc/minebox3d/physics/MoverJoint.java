package com.lowdragmc.minebox3d.physics;

import org.joml.Vector3f;

/** A {@link JointType#MOVER} joint — see {@link MoverJointDef}. */
public final class MoverJoint extends Joint {

    MoverJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.MOVER;
    }

    /** m/s of B relative to A, world space. */
    public Vector3f linearVelocity(Vector3f out) {
        return getVector(JointParam.MOVER_LINEAR_VELOCITY, out);
    }

    public void setLinearVelocity(float x, float y, float z) {
        Checks.finite(x, y, z, "linear velocity");
        set(JointParam.MOVER_LINEAR_VELOCITY, x, y, z, 0);
    }

    public float maxForce() {
        return get(JointParam.MOVER_MAX_FORCE);
    }

    public void setMaxForce(float force) {
        Checks.notNegative(force, "max force");
        set(JointParam.MOVER_MAX_FORCE, force);
    }
}
