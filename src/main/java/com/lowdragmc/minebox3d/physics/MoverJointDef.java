package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3MoverJointDef}: drives a dynamic character body (B) towards a velocity relative to A by a limited
 * force, in the xz plane of frame B only — gravity and a {@link PogoJointDef pogo joint} hold it up. Box3D's dynamic
 * mover, which Unreal's Chaos mover's ground constraint answers to. ⚠️ Box3D marks it experimental.
 */
public final class MoverJointDef extends JointDef<MoverJoint, MoverJointDef> {
    /** m/s of B relative to A, world space. */
    public float linearVelocityX, linearVelocityY, linearVelocityZ;
    /** N */
    public float maxForce;

    public MoverJointDef() {
        super(JointType.MOVER);
    }

    @Override
    MoverJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new MoverJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {
        Checks.finite(linearVelocityX, linearVelocityY, linearVelocityZ, "linear velocity");
        Checks.notNegative(maxForce, "max force");
    }

    public MoverJointDef linearVelocity(float x, float y, float z) {
        linearVelocityX = x;
        linearVelocityY = y;
        linearVelocityZ = z;
        return this;
    }

    public MoverJointDef maxForce(float force) {
        maxForce = force;
        return this;
    }
}
