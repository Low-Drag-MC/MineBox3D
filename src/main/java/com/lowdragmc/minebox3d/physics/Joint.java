package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * <b>A joint between two bodies</b>: a handle to Box3D's joint, typed by what it is ({@link JointType}). Made by
 * {@link PhysicsWorld#createJoint}; gone with either body, the world, or {@link #destroy}.
 *
 * <p>Box3D does not wake the bodies when a property changes: a sleeping ragdoll given a new motor target stays asleep
 * until {@link #wakeBodies()} (or anything else) wakes it.
 *
 * <p>Belongs to its world's thread rule, like the world.
 */
public abstract sealed class Joint
        permits DistanceJoint,
                FilterJoint,
                MotorJoint,
                ParallelJoint,
                PrismaticJoint,
                RevoluteJoint,
                SphericalJoint,
                WeldJoint,
                WheelJoint,
                MoverJoint,
                PogoJoint,
                MotionLockJoint,
                ConstraintJoint {

    final PhysicsWorld world;
    final long id;
    private final Body bodyA, bodyB;
    private final Attachments attachments = new Attachments();
    private boolean destroyed;

    Joint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        this.world = world;
        this.id = id;
        this.bodyA = bodyA;
        this.bodyB = bodyB;
    }

    public abstract JointType type();

    public PhysicsWorld world() {
        return world;
    }

    public long id() {
        return id;
    }

    public Body bodyA() {
        return bodyA;
    }

    public Body bodyB() {
        return bodyB;
    }

    /** The body at the other end from {@code body}. */
    public Body other(Body body) {
        return body == bodyA ? bodyB : bodyA;
    }

    public boolean isValid() {
        return !destroyed && world.isOpen();
    }

    /** What is attached under {@code type}, or {@code null}. Any number of mods can attach their own, one per type. */
    @Nullable
    public <T> T attachment(Class<T> type) {
        return attachments.get(type);
    }

    /** Attaches {@code value} under {@code type}, replacing what was there; {@code null} removes it. */
    public <T> void attach(Class<T> type, @Nullable T value) {
        attachments.put(type, value);
    }

    /** Removes the joint, optionally waking the two bodies so they react to being let go. */
    public void destroy(boolean wakeBodies) {
        world.destroyJoint(this, wakeBodies);
    }

    void markDestroyed() {
        destroyed = true;
    }

    // ---- frames -------------------------------------------------------------------------------

    /** Frame A's origin in body A's space. */
    public Vector3f localPositionA(Vector3f out) {
        return frame(false, out, null);
    }

    public Quaternionf localRotationA(Quaternionf out) {
        frame(false, null, out);
        return out;
    }

    public Vector3f localPositionB(Vector3f out) {
        return frame(true, out, null);
    }

    public Quaternionf localRotationB(Quaternionf out) {
        frame(true, null, out);
        return out;
    }

    /** Moves frame A on body A — for a {@link MotorJoint}, its target. Does not wake the bodies. */
    public void setLocalFrameA(float x, float y, float z, Quaternionfc rotation) {
        setFrame(false, x, y, z, rotation);
    }

    public void setLocalFrameB(float x, float y, float z, Quaternionfc rotation) {
        setFrame(true, x, y, z, rotation);
    }

    private Vector3f frame(boolean b, @Nullable Vector3f position, @Nullable Quaternionf rotation) {
        world.enter();
        try {
            checkValid();
            float[] v = world.jointValues;
            B3.jointGetLocalFrame(id, b, v);
            if (rotation != null) {
                rotation.set(v[3], v[4], v[5], v[6]);
            }
            return position == null ? null : position.set(v[0], v[1], v[2]);
        } finally {
            world.exit();
        }
    }

    private void setFrame(boolean b, float x, float y, float z, Quaternionfc rotation) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "frame position");
            Checks.rotation(rotation.x(), rotation.y(), rotation.z(), rotation.w(), "frame rotation");
            B3.jointSetLocalFrame(id, b, x, y, z, rotation.x(), rotation.y(), rotation.z(), rotation.w());
        } finally {
            world.exit();
        }
    }

    // ---- what every joint has -----------------------------------------------------------------

    public boolean collideConnected() {
        return getFlag(JointParam.COLLIDE_CONNECTED);
    }

    public void setCollideConnected(boolean collide) {
        setFlag(JointParam.COLLIDE_CONNECTED, collide);
    }

    /** N; past it the joint is reported in {@link WorldEvents#jointEvents()}. Box3D never breaks a joint itself. */
    public float forceThreshold() {
        return get(JointParam.FORCE_THRESHOLD, 0);
    }

    public void setForceThreshold(float threshold) {
        Checks.notNegative(threshold, "force threshold");
        set(JointParam.FORCE_THRESHOLD, threshold, 0, 0, 0);
    }

    /** N·m, as {@link #forceThreshold()}. */
    public float torqueThreshold() {
        return get(JointParam.TORQUE_THRESHOLD, 0);
    }

    public void setTorqueThreshold(float threshold) {
        Checks.notNegative(threshold, "torque threshold");
        set(JointParam.TORQUE_THRESHOLD, threshold, 0, 0, 0);
    }

    public float constraintHertz() {
        return get(JointParam.CONSTRAINT_TUNING, 0);
    }

    public float constraintDampingRatio() {
        return get(JointParam.CONSTRAINT_TUNING, 1);
    }

    /** How stiffly the joint corrects drift (advanced). */
    public void setConstraintTuning(float hertz, float dampingRatio) {
        Checks.notNegative(hertz, "constraint hertz");
        Checks.notNegative(dampingRatio, "constraint damping ratio");
        set(JointParam.CONSTRAINT_TUNING, hertz, dampingRatio, 0, 0);
    }

    /** How far the joint's points have drifted apart, m. */
    public float linearSeparation() {
        return get(JointParam.LINEAR_SEPARATION, 0);
    }

    /** How far the joint is turned past what it allows, radians. */
    public float angularSeparation() {
        return get(JointParam.ANGULAR_SEPARATION, 0);
    }

    /** The force the joint applied in the last step, N, world space. */
    public Vector3f constraintForce(Vector3f out) {
        return getVector(JointParam.CONSTRAINT_FORCE, out);
    }

    /** The torque the joint applied in the last step, N·m, world space. */
    public Vector3f constraintTorque(Vector3f out) {
        return getVector(JointParam.CONSTRAINT_TORQUE, out);
    }

    public boolean isAwake() {
        return getFlag(JointParam.AWAKE);
    }

    public void wakeBodies() {
        set(JointParam.AWAKE, 1, 0, 0, 0);
    }

    // ---- for the typed subclasses -------------------------------------------------------------

    final void set(int param, float a, float b, float c, float d) {
        world.enter();
        try {
            checkValid();
            B3.jointSet(id, param, a, b, c, d);
        } finally {
            world.exit();
        }
    }

    final void set(int param, float value) {
        set(param, value, 0, 0, 0);
    }

    final void setFlag(int param, boolean value) {
        set(param, value ? 1 : 0, 0, 0, 0);
    }

    final float get(int param, int index) {
        world.enter();
        try {
            checkValid();
            float[] v = world.jointValues;
            B3.jointGet(id, param, v);
            return v[index];
        } finally {
            world.exit();
        }
    }

    final float get(int param) {
        return get(param, 0);
    }

    final boolean getFlag(int param) {
        return get(param, 0) != 0;
    }

    final Vector3f getVector(int param, Vector3f out) {
        world.enter();
        try {
            checkValid();
            float[] v = world.jointValues;
            B3.jointGet(id, param, v);
            return out.set(v[0], v[1], v[2]);
        } finally {
            world.exit();
        }
    }

    final Quaternionf getRotation(int param, Quaternionf out) {
        world.enter();
        try {
            checkValid();
            float[] v = world.jointValues;
            B3.jointGet(id, param, v);
            return out.set(v[0], v[1], v[2], v[3]);
        } finally {
            world.exit();
        }
    }

    final void checkValid() {
        if (!world.isOpen()) {
            throw new IllegalStateException("the joint's world is closed");
        }
        if (destroyed) {
            throw new IllegalStateException("joint was destroyed");
        }
    }
}
