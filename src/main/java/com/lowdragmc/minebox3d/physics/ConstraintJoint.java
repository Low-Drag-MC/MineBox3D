package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * A {@link JointType#CONSTRAINT} joint — see {@link ConstraintJointDef}. Changing its settings or targets does not wake the
 * bodies ({@link #wakeBodies()}). Many at once: {@link ConstraintJointBatch}.
 */
public final class ConstraintJoint extends Joint {

    ConstraintJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.CONSTRAINT;
    }

    public ConstraintJointSettings settings(ConstraintJointSettings out) {
        world.enter();
        try {
            checkValid();
            B3.constraintJointGetSettings(id, out);
            return out;
        } finally {
            world.exit();
        }
    }

    /**
     * Swaps everything the joint does at once — a profile. The impulses of rows that still hold the same thing are kept, so
     * a profile that only retunes a drive does not jolt the bodies.
     */
    public void setSettings(ConstraintJointSettings settings) {
        world.enter();
        try {
            checkValid();
            settings.check();
            B3.constraintJointSetSettings(id, settings);
        } finally {
            world.exit();
        }
    }

    /** The drive targets alone: linear in frame A, angular frame B relative to frame A (see {@link ConstraintJointSettings}). */
    public void setDriveTargets(float px, float py, float pz, float vx, float vy, float vz, Quaternionfc orientation,
                                float wx, float wy, float wz) {
        world.enter();
        try {
            checkValid();
            Checks.finite(px, py, pz, "linear position target");
            Checks.finite(vx, vy, vz, "linear velocity target");
            Checks.rotation(orientation.x(), orientation.y(), orientation.z(), orientation.w(), "angular orientation target");
            Checks.finite(wx, wy, wz, "angular velocity target");
            float[] v = world.constraintValues;
            v[0] = px;
            v[1] = py;
            v[2] = pz;
            v[3] = vx;
            v[4] = vy;
            v[5] = vz;
            v[6] = orientation.x();
            v[7] = orientation.y();
            v[8] = orientation.z();
            v[9] = orientation.w();
            v[10] = wx;
            v[11] = wy;
            v[12] = wz;
            world.oneJoint[0] = id;
            B3.constraintJointsSetTargets(world.oneJoint, 1, v, false);
        } finally {
            world.exit();
        }
    }

    /** Twist, swing 1 and swing 2 now, as the limits measure them (a swing limited with the other free: its dual cone angle). */
    public Vector3f angles(Vector3f out) {
        float[] v = state();
        return out.set(v[0], v[1], v[2]);
    }

    /** Frame B's anchor from frame A's, in frame A. */
    public Vector3f linearOffset(Vector3f out) {
        float[] v = state();
        return out.set(v[3], v[4], v[5]);
    }

    private float[] state() {
        world.enter();
        try {
            checkValid();
            float[] v = world.constraintValues;
            world.oneJoint[0] = id;
            B3.constraintJointsGetState(world.oneJoint, 1, v);
            return v;
        } finally {
            world.exit();
        }
    }
}
