package com.lowdragmc.minebox3d.physics;

/** A {@link JointType#FILTER} joint: the two bodies do not collide with each other, and nothing else. */
public final class FilterJoint extends Joint {

    FilterJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.FILTER;
    }
}
