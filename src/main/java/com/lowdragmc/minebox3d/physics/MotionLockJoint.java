package com.lowdragmc.minebox3d.physics;

/** A {@link JointType#MOTION_LOCK} joint — see {@link MotionLockJointDef}. */
public final class MotionLockJoint extends Joint {

    MotionLockJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.MOTION_LOCK;
    }

    /** Whether movement along frame A's axis (0 x, 1 y, 2 z) is locked. */
    public boolean isLinearLocked(int axis) {
        return getFlag(linearParam(axis));
    }

    public void lockLinear(int axis, boolean lock) {
        setFlag(linearParam(axis), lock);
    }

    /** Whether body B turns about frame A's z axis only. */
    public boolean isAngularLocked() {
        return getFlag(JointParam.MOTION_LOCK_ANGULAR);
    }

    public void lockAngular(boolean lock) {
        setFlag(JointParam.MOTION_LOCK_ANGULAR, lock);
    }

    private static int linearParam(int axis) {
        return switch (axis) {
            case 0 -> JointParam.MOTION_LOCK_LINEAR_X;
            case 1 -> JointParam.MOTION_LOCK_LINEAR_Y;
            case 2 -> JointParam.MOTION_LOCK_LINEAR_Z;
            default -> throw new IllegalArgumentException("axis is 0, 1 or 2: " + axis);
        };
    }
}
