package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3MotionLockJointDef}: locks body B's movement along any of frame A's axes, and its turning to frame
 * A's z axis only. Box3D's replacement for the per-body motion locks it dropped, which were unstable. Against a static
 * body it locks a body to a line, a plane or a point, or keeps it upright: frame A's z axis up, {@link #lockAngularXY}
 * on.
 */
public final class MotionLockJointDef extends JointDef<MotionLockJoint, MotionLockJointDef> {
    public boolean lockLinearX, lockLinearY, lockLinearZ;
    /** Turning about frame A's z axis only. */
    public boolean lockAngularXY;

    public MotionLockJointDef() {
        super(JointType.MOTION_LOCK);
    }

    @Override
    MotionLockJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new MotionLockJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {}

    public MotionLockJointDef lockLinear(boolean x, boolean y, boolean z) {
        lockLinearX = x;
        lockLinearY = y;
        lockLinearZ = z;
        return this;
    }

    public MotionLockJointDef lockAngularXY(boolean lock) {
        lockAngularXY = lock;
        return this;
    }
}
