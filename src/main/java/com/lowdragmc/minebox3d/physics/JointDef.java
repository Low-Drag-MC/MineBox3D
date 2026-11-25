package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * Box3D's {@code b3JointDef}: what every joint definition has. A new definition holds Box3D's own defaults.
 *
 * <h2>Frames</h2>
 * <p>A joint is measured between two frames, one on each body, given in that body's own space from its origin (not its
 * centre of mass, which moves when shapes change). Each joint type reads them its own way — a revolute joint turns about
 * their shared z axis, a prismatic one slides along frame A's x — see {@link JointType}. {@link #worldFrame} sets both
 * from one world pose and where the bodies are now, which is how a joint is usually placed.
 *
 * <p>Re-usable: creating a joint reads it and keeps nothing. {@link #bodyA} and {@link #bodyB} are Java's; the rest is
 * Box3D's, field for field.
 *
 * @param <J> the joint this makes
 * @param <D> the definition itself, for the chained setters
 */
public abstract sealed class JointDef<J extends Joint, D extends JointDef<J, D>>
        permits DistanceJointDef, FilterJointDef, MotorJointDef, ParallelJointDef, PrismaticJointDef, RevoluteJointDef,
        SphericalJointDef, WeldJointDef, WheelJointDef, MoverJointDef, PogoJointDef, MotionLockJointDef,
        ConstraintJointDef {

    @Nullable
    public Body bodyA, bodyB;
    public long userData;
    /** Frame A, in body A's space from its origin. */
    public float positionAX, positionAY, positionAZ;
    public float rotationAX, rotationAY, rotationAZ, rotationAW;
    /** Frame B, in body B's space from its origin. */
    public float positionBX, positionBY, positionBZ;
    public float rotationBX, rotationBY, rotationBZ, rotationBW;
    /** Force (N) above which the joint is reported in {@link WorldEvents#jointEvents()}; Box3D never breaks it itself. */
    public float forceThreshold;
    /** Torque (N·m) above which the joint is reported, as {@link #forceThreshold}. */
    public float torqueThreshold;
    /** How stiffly the joint corrects drift (advanced); Hz. */
    public float constraintHertz;
    public float constraintDampingRatio;
    /** Debug draw scale. */
    public float drawScale;
    /** Whether the two bodies still collide with each other. */
    public boolean collideConnected;

    private final JointType type;

    JointDef(JointType type) {
        NativeLibrary.load();
        this.type = type;
        B3.defaultJointDef(type.ordinal(), this);
    }

    public final JointType type() {
        return type;
    }

    /** The Java joint for a joint Box3D made of this. */
    abstract J wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB);

    /** The type's own checks of its fields, before they reach Box3D. */
    abstract void checkFields();

    @SuppressWarnings("unchecked")
    final D self() {
        return (D) this;
    }

    public D bodies(Body a, Body b) {
        bodyA = a;
        bodyB = b;
        return self();
    }

    public D localFrameA(float x, float y, float z, Quaternionfc rotation) {
        positionAX = x;
        positionAY = y;
        positionAZ = z;
        rotationAX = rotation.x();
        rotationAY = rotation.y();
        rotationAZ = rotation.z();
        rotationAW = rotation.w();
        return self();
    }

    public D localFrameB(float x, float y, float z, Quaternionfc rotation) {
        positionBX = x;
        positionBY = y;
        positionBZ = z;
        rotationBX = rotation.x();
        rotationBY = rotation.y();
        rotationBZ = rotation.z();
        rotationBW = rotation.w();
        return self();
    }

    /**
     * Places the joint at a world point with its frame turned by a world rotation, by setting both local frames from where
     * the two bodies are now ({@link #bodies} first). The frames then coincide, so the joint starts at rest.
     */
    public D worldFrame(double x, double y, double z, Quaternionfc rotation) {
        if (bodyA == null || bodyB == null) {
            throw new IllegalStateException("set the bodies before placing the joint in the world");
        }
        Vector3f p = new Vector3f();
        Quaternionf q = new Quaternionf();
        toLocal(bodyA, x, y, z, rotation, p, q);
        localFrameA(p.x, p.y, p.z, q);
        toLocal(bodyB, x, y, z, rotation, p, q);
        localFrameB(p.x, p.y, p.z, q);
        return self();
    }

    /** {@link #worldFrame} with the frame turned as the world's axes. */
    public D worldFrame(double x, double y, double z) {
        return worldFrame(x, y, z, new Quaternionf());
    }

    private static void toLocal(Body body, double x, double y, double z, Quaternionfc rotation, Vector3f position,
                                Quaternionf local) {
        Quaternionf inverse = body.rotation(new Quaternionf()).conjugate();
        // subtract in double: the bodies may be millions of blocks out
        position.set((float) (x - body.current[0]), (float) (y - body.current[1]), (float) (z - body.current[2]));
        inverse.transform(position);
        inverse.mul(rotation, local).normalize();
    }

    public D collideConnected(boolean collideConnected) {
        this.collideConnected = collideConnected;
        return self();
    }

    /** Thresholds for {@link WorldEvents#jointEvents()}; {@link Float#MAX_VALUE} for never. */
    public D thresholds(float force, float torque) {
        forceThreshold = force;
        torqueThreshold = torque;
        return self();
    }

    public D userData(long userData) {
        this.userData = userData;
        return self();
    }

    final void check() {
        if (bodyA == null || bodyB == null) {
            throw new IllegalArgumentException("a joint needs two bodies");
        }
        if (bodyA == bodyB) {
            throw new IllegalArgumentException("a joint joins two different bodies");
        }
        if (bodyA.world() != bodyB.world()) {
            throw new IllegalArgumentException("the two bodies are in different worlds");
        }
        Checks.finite(positionAX, positionAY, positionAZ, "frame A position");
        Checks.rotation(rotationAX, rotationAY, rotationAZ, rotationAW, "frame A rotation");
        Checks.finite(positionBX, positionBY, positionBZ, "frame B position");
        Checks.rotation(rotationBX, rotationBY, rotationBZ, rotationBW, "frame B rotation");
        Checks.notNegative(forceThreshold, "force threshold");
        Checks.notNegative(torqueThreshold, "torque threshold");
        Checks.notNegative(constraintHertz, "constraint hertz");
        Checks.notNegative(constraintDampingRatio, "constraint damping ratio");
        Checks.finite(drawScale, "draw scale");
        checkFields();
    }
}
