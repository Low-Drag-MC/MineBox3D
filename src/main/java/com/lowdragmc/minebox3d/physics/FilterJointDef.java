package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3FilterJointDef}: no constraint at all — only stops the two bodies colliding with each other, where
 * filter bits would be too coarse (two limbs of one ragdoll that overlap at the joint). Like any joint it also keeps
 * them in one island: they sleep and wake together.
 */
public final class FilterJointDef extends JointDef<FilterJoint, FilterJointDef> {

    public FilterJointDef() {
        super(JointType.FILTER);
    }

    @Override
    FilterJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new FilterJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {}
}
