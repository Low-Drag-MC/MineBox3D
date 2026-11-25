package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;

/**
 * Many {@link ConstraintJoint}s of one world driven and read in one call each — a physics asset's joints every frame.
 * The joints are fixed when made; a destroyed one makes every call throw.
 */
public final class ConstraintJointBatch {

    /** floats per joint: {@code [linear position xyz, linear velocity xyz, orientation xyzw, angular velocity xyz]} */
    public static final int TARGET = 13;
    /** floats per joint: {@code [twist, swing 1, swing 2, linear offset xyz, linear separation, angular separation]} */
    public static final int STATE = 8;

    private final PhysicsWorld world;
    private final ConstraintJoint[] joints;
    private final long[] ids;

    public ConstraintJointBatch(ConstraintJoint... joints) {
        if (joints.length == 0) {
            throw new IllegalArgumentException("a batch needs a joint");
        }
        this.world = joints[0].world();
        this.joints = joints.clone();
        this.ids = new long[joints.length];
        for (int i = 0; i < joints.length; i++) {
            if (joints[i].world() != world) {
                throw new IllegalArgumentException("the joints are in different worlds");
            }
            ids[i] = joints[i].id;
        }
    }

    public int size() {
        return joints.length;
    }

    public ConstraintJoint joint(int index) {
        return joints[index];
    }

    /**
     * Every joint's drive targets, {@link #TARGET} floats each, as {@link ConstraintJoint#setDriveTargets} takes and
     * checks them. {@code wake} wakes every joint's bodies, which a sleeping one needs to follow a new target.
     */
    public void setTargets(float[] values, boolean wake) {
        if (values.length < ids.length * TARGET) {
            throw new IllegalArgumentException(values.length + " floats for " + ids.length + " joints' targets");
        }
        for (int i = 0; i < ids.length; i++) {
            int o = i * TARGET;
            Checks.finite(values[o], values[o + 1], values[o + 2], "linear position target");
            Checks.finite(values[o + 3], values[o + 4], values[o + 5], "linear velocity target");
            Checks.rotation(values[o + 6], values[o + 7], values[o + 8], values[o + 9], "angular orientation target");
            Checks.finite(values[o + 10], values[o + 11], values[o + 12], "angular velocity target");
        }
        world.enter();
        try {
            checkJoints();
            B3.constraintJointsSetTargets(ids, ids.length, values, wake);
        } finally {
            world.exit();
        }
    }

    /**
     * Every joint now, {@link #STATE} floats each, as {@link ConstraintJoint#angles},
     * {@link ConstraintJoint#linearOffset}, {@link Joint#linearSeparation} and {@link Joint#angularSeparation} read it.
     */
    public void readState(float[] out) {
        world.enter();
        try {
            checkJoints();
            B3.constraintJointsGetState(ids, ids.length, out);
        } finally {
            world.exit();
        }
    }

    private void checkJoints() {
        for (ConstraintJoint joint : joints) {
            joint.checkValid();
        }
    }
}
