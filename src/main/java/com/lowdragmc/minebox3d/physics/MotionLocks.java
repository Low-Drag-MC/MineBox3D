package com.lowdragmc.minebox3d.physics;

/** Box3D's {@code b3MotionLocks}: which world axes a body may not move along or turn about. */
public record MotionLocks(boolean linearX, boolean linearY, boolean linearZ, boolean angularX, boolean angularY,
                          boolean angularZ) {

    public static final MotionLocks NONE = new MotionLocks(false, false, false, false, false, false);
    /** Turns only about the world's up axis: a body that must not tip over. */
    public static final MotionLocks UPRIGHT = new MotionLocks(false, false, false, true, false, true);

    int bits() {
        return (linearX ? 1 : 0) | (linearY ? 2 : 0) | (linearZ ? 4 : 0) | (angularX ? 8 : 0) | (angularY ? 16 : 0)
                | (angularZ ? 32 : 0);
    }

    static MotionLocks of(int bits) {
        return new MotionLocks((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0, (bits & 16) != 0,
                (bits & 32) != 0);
    }
}
