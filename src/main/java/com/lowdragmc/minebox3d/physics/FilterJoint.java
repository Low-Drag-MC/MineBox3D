package com.lowdragmc.minebox3d.physics;

/** A {@link JointType#FILTER} joint — see {@link FilterJointDef}: the two bodies do not collide with each other. */
public final class FilterJoint extends Joint {

    FilterJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.FILTER;
    }
}
