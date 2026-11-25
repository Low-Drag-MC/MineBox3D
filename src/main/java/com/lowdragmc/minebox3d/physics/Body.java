package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * A rigid body in a {@link PhysicsWorld}: a handle to Box3D's body plus two copies of its transform —
 * where it was at the start of the current interpolation frame and where it is now — so a renderer
 * can draw it between the two (see {@link PhysicsWorld#beginInterpolationFrame()}).
 *
 * <p>Its bounds and velocities come back with every step for every awake body, so reading them costs no call into
 * Box3D; a call that changes them reads them again the next time they are asked for.
 *
 * <p>Belongs to its world's thread rule, like the world.
 */
public final class Body {

    /** floats of state a step reports: {@code [minX, minY, minZ, maxX, maxY, maxZ, vx, vy, vz, wx, wy, wz]} */
    static final int STATE = 12;

    private final PhysicsWorld world;
    final long id;
    private final List<Shape> shapes = new ArrayList<>(2);
    private final List<Joint> joints = new ArrayList<>(0);
    /** [px, py, pz, qx, qy, qz, qw] */
    final double[] current = new double[7];

    final double[] previous = new double[7];
    /** whether {@link #current} changed in the current interpolation frame */
    boolean movedThisFrame;

    private final float[] state = new float[STATE];
    private boolean boundsKnown, velocityKnown;
    /** the world's {@link PhysicsWorld#motionEpoch} when the velocities were last known */
    private int velocityEpoch;
    /** kept on this side: asked for every tick by the terrain streamer, and only this class changes it */
    private BodyType type;

    private final Attachments attachments = new Attachments();

    @Nullable
    private final String name;

    private final long userData;
    private int shapesRevision;
    private int massRevision;
    private boolean destroyed;
    /** disabled by its maker; parked ({@link #setParked}): enabled in Box3D only when neither */
    private boolean disabled, parked;
    /** {@link #setTag}, {@link #setIgnoredTag}: kept on this side, as sent */
    private long tag, ignoredTag;

    Body(PhysicsWorld world, long id, BodyDef def) {
        this.world = world;
        this.id = id;
        this.type = def.type;
        this.name = def.name;
        this.userData = def.userData;
        this.disabled = !def.enabled;
        current[0] = def.positionX;
        current[1] = def.positionY;
        current[2] = def.positionZ;
        current[3] = def.rotationX;
        current[4] = def.rotationY;
        current[5] = def.rotationZ;
        current[6] = def.rotationW;
        System.arraycopy(current, 0, previous, 0, 7);
    }

    public PhysicsWorld world() {
        return world;
    }

    public long id() {
        return id;
    }

    public boolean isValid() {
        return !destroyed && world.isOpen();
    }

    /** {@link BodyDef#name}, as it was made. */
    @Nullable
    public String name() {
        return name;
    }

    /** {@link BodyDef#userData}, as it was made. */
    public long userData() {
        return userData;
    }

    /**
     * A number that changes whenever one of the body's shapes is added, removed, given a new geometry or a new filter,
     * or the body's tags change — so whoever copies the shapes elsewhere (the network sync) knows when to copy them
     * again.
     */
    public int shapesRevision() {
        return shapesRevision;
    }

    void shapesChanged() {
        shapesRevision++;
    }

    /**
     * A number that changes whenever the body's type or mass is set ({@link #setType}, {@link #setMassData},
     * {@link #applyMassFromShapes}) — a shape change that moves the mass changes {@link #shapesRevision} instead.
     */
    public int massRevision() {
        return massRevision;
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

    public List<Shape> shapes() {
        return Collections.unmodifiableList(shapes);
    }

    /** The joints this body is in, either end. */
    public List<Joint> joints() {
        return Collections.unmodifiableList(joints);
    }

    void addJoint(Joint joint) {
        joints.add(joint);
    }

    void removeJoint(Joint joint) {
        joints.remove(joint);
    }

    // ---- shapes -------------------------------------------------------------------------------

    /** A new shape of {@code geometry} on this body; a mesh's or height field's data is kept alive until it goes. */
    public Shape createShape(ShapeDef def, Geometry geometry) {
        world.enter();
        try {
            checkValid();
            Checks.shapeDef(def);
            Checks.geometry(geometry);
            geometry = Geometry.asBuilt(geometry);
            long shapeId =
                    switch (geometry) {
                        case Geometry.Sphere s -> B3.createSphereShape(id, def, s.cx(), s.cy(), s.cz(), s.radius());
                        case Geometry.Capsule c ->
                            B3.createCapsuleShape(id, def, c.ax(), c.ay(), c.az(), c.bx(), c.by(), c.bz(), c.radius());
                        case Geometry.Box b ->
                            B3.createBoxShape(
                                    id, def, b.hx(), b.hy(), b.hz(), b.cx(), b.cy(), b.cz(), b.qx(), b.qy(), b.qz(),
                                    b.qw());
                        case Geometry.Hull h -> B3.createHullShape(id, def, h.points(), h.vertexCount());
                        case Geometry.Mesh m -> {
                            m.data().retain();
                            try {
                                yield B3.createMeshShape(id, def, m.data().pointer(), m.sx(), m.sy(), m.sz());
                            } catch (RuntimeException e) {
                                m.data().release();
                                throw e;
                            }
                        }
                        case Geometry.HeightField f -> {
                            f.data().retain();
                            try {
                                yield B3.createHeightFieldShape(
                                        id, def, f.data().pointer());
                            } catch (RuntimeException e) {
                                f.data().release();
                                throw e;
                            }
                        }
                    };
            Shape shape = new Shape(this, shapeId, geometry);
            shapes.add(shape);
            world.registerShape(shape);
            shapesChanged();
            boundsKnown = false;
            // a new centre of mass under a turning body changes its linear velocity (Box3D keeps the motion of the
            // points, v += w x (c' - c))
            forgetMotion();
            return shape;
        } finally {
            world.exit();
        }
    }

    void destroyShape(Shape shape, boolean updateBodyMass) {
        world.enter();
        try {
            if (!shape.isValid() || shape.body() != this) {
                return;
            }
            if (!world.isFaulted()) {
                B3.destroyShape(shape.id, updateBodyMass);
            }
            shapes.remove(shape);
            world.unregisterShape(shape);
            shapesChanged();
            shape.markDestroyed();
            boundsKnown = false;
            velocityKnown = false;
        } finally {
            world.exit();
        }
    }

    /** Called by the world when Box3D has already freed this body (its own destroy, or the world's). */
    void markDestroyed() {
        if (!destroyed) {
            destroyed = true;
            for (Shape shape : shapes) {
                world.unregisterShape(shape);
                shape.markDestroyed();
            }
            shapes.clear();
            joints.clear();
        }
    }

    /** Destroys the body with its shapes and joints. */
    public void destroy() {
        world.destroyBody(this);
    }

    // ---- what a step reports ------------------------------------------------------------------

    /** A step moved the body: its bounds and velocities as it ended, from {@code states} at {@code offset}. */
    void moved(float[] states, int offset, boolean fellAsleep) {
        System.arraycopy(states, offset, state, 0, STATE);
        if (fellAsleep) {
            // Box3D reports a sleeping body as still
            for (int i = 6; i < STATE; i++) {
                state[i] = 0;
            }
        }
        boundsKnown = true;
        velocityKnown = true;
        velocityEpoch = world.motionEpoch;
    }

    void forgetMotion() {
        velocityKnown = false;
    }

    void forgetBounds() {
        boundsKnown = false;
    }

    // ---- transform ----------------------------------------------------------------------------

    /** Where the body is now, as of the last step or the last call that moved it. */
    public Vector3d position(Vector3d out) {
        return out.set(current[0], current[1], current[2]);
    }

    public Quaternionf rotation(Quaternionf out) {
        return out.set((float) current[3], (float) current[4], (float) current[5], (float) current[6]);
    }

    /**
     * Between the start of the interpolation frame ({@code partial = 0}) and now ({@code partial = 1}), and where a
     * correction is still fading ({@link #addRenderError}), that far off.
     */
    public Vector3d renderPosition(float partial, Vector3d out) {
        out.set(
                previous[0] + (current[0] - previous[0]) * partial,
                previous[1] + (current[1] - previous[1]) * partial,
                previous[2] + (current[2] - previous[2]) * partial);
        if (error != null) {
            out.add(
                    error.start[0] + (error.end[0] - error.start[0]) * partial,
                    error.start[1] + (error.end[1] - error.start[1]) * partial,
                    error.start[2] + (error.end[2] - error.start[2]) * partial);
        }
        return out;
    }

    public Quaternionf renderRotation(float partial, Quaternionf out) {
        out.set((float) previous[3], (float) previous[4], (float) previous[5], (float) previous[6]);
        out.slerp(
                new Quaternionf((float) current[3], (float) current[4], (float) current[5], (float) current[6]),
                partial);
        if (error != null) {
            out.premul(new Quaternionf(error.turnStart).slerp(error.turnEnd, partial));
        }
        return out;
    }

    // ---- drawn off where it is, while a correction fades ----

    /** Frames a correction takes to fade: Unreal's {@code p.RenderInterp.ErrorCorrectionDuration}, 0.3 s at 20 Hz. */
    public static final int ERROR_FRAMES = 6;
    /** m: further apart than this the body is drawn where it is at once, as Unreal snaps past 250 cm. */
    public static final double ERROR_SNAP = 2.5;

    @Nullable
    private RenderError error;

    /**
     * Draws the body off where it is, by a gap that fades over {@link #ERROR_FRAMES} frames — Unreal's error
     * correction, for a body a simulation run again moved while it was being drawn. {@code (dx, dy, dz)} is where it
     * was less where it is now, {@code turn} its turn then times the inverse of its turn now. Adds to a gap still
     * fading; past {@link #ERROR_SNAP} the body is drawn where it is instead.
     */
    public void addRenderError(double dx, double dy, double dz, Quaternionfc turn) {
        world.enter();
        try {
            checkValid();
            RenderError e = error != null ? error : new RenderError();
            double x = e.end[0] + dx, y = e.end[1] + dy, z = e.end[2] + dz;
            if (x * x + y * y + z * z > ERROR_SNAP * ERROR_SNAP) {
                error = null;
                System.arraycopy(current, 0, previous, 0, 7);
                return;
            }
            e.end[0] = e.from[0] = x;
            e.end[1] = e.from[1] = y;
            e.end[2] = e.from[2] = z;
            e.turnEnd.mul(turn).normalize();
            e.turnFrom.set(e.turnEnd);
            e.frames = ERROR_FRAMES;
            if (error == null) {
                error = e;
                world.noteRenderError(this);
            }
        } finally {
            world.exit();
        }
    }

    /** How far off it is drawn now, at the end of the frame; zero with no correction fading. */
    public Vector3d renderError(Vector3d out) {
        return error == null ? out.zero() : out.set(error.end[0], error.end[1], error.end[2]);
    }

    /** A new frame: the gap the last ended on is where this one starts, and it ends a step closer to none. */
    boolean fadeRenderError() {
        RenderError e = error;
        if (e == null) {
            return false;
        }
        System.arraycopy(e.end, 0, e.start, 0, 3);
        e.turnStart.set(e.turnEnd);
        if (e.frames == 0) {
            error = null; // the frame that ended on none is drawn: done
            return false;
        }
        e.frames--;
        double left = (double) e.frames / ERROR_FRAMES;
        for (int i = 0; i < 3; i++) {
            e.end[i] = e.from[i] * left;
        }
        e.turnEnd.identity().slerp(e.turnFrom, (float) left);
        return true;
    }

    private static final class RenderError {
        final double[] start = new double[3], end = new double[3], from = new double[3];
        final Quaternionf turnStart = new Quaternionf(), turnEnd = new Quaternionf(), turnFrom = new Quaternionf();
        int frames;
    }

    /**
     * Teleports the body and wakes it unless static: a sleeping body put in mid air falls. Not interpolated: the
     * previous frame jumps along with it.
     */
    public void setTransform(double x, double y, double z, Quaternionfc rotation) {
        setTransform(x, y, z, rotation, true);
    }

    /**
     * {@link #setTransform(double, double, double, Quaternionfc)}, waking the body only if {@code wake} — PhysX's
     * {@code setGlobalPose(pose, autowake)}. For a body moved only by teleport that nothing has to answer, such as a
     * query volume: a query meets a sleeping body as it does an awake one, and an awake body costs every step. ⚠️ A
     * resting body it is teleported into stays resting until something else wakes it.
     */
    public void setTransform(double x, double y, double z, Quaternionfc rotation, boolean wake) {
        place(x, y, z, rotation, wake, true);
    }

    /**
     * {@link #setTransform(double, double, double, Quaternionfc, boolean)} as part of the motion since the last step,
     * not a teleport: the previous frame stays, so {@link #renderPosition} draws it moving there — a correction made
     * after a step, such as a joint's projection.
     */
    public void correctTransform(double x, double y, double z, Quaternionfc rotation, boolean wake) {
        place(x, y, z, rotation, wake, false);
    }

    /**
     * Back to a state {@link RewindHistory} recorded: the place, the turn as it was — not normalised again, so a
     * simulation run on from here takes the very numbers it took the first time — the velocities, awake or not. Part of
     * the frame's motion, not a teleport: the frame it is drawn from stays.
     */
    void restore(
            double x,
            double y,
            double z,
            float qx,
            float qy,
            float qz,
            float qw,
            float vx,
            float vy,
            float vz,
            float wx,
            float wy,
            float wz,
            boolean awake) {
        world.enter();
        try {
            checkValid();
            B3.bodySetTransform(id, x, y, z, qx, qy, qz, qw);
            if (type != BodyType.STATIC) {
                B3.bodySetLinearVelocity(id, vx, vy, vz);
                B3.bodySetAngularVelocity(id, wx, wy, wz);
                B3.bodySetAwake(id, awake);
            }
            current[0] = x;
            current[1] = y;
            current[2] = z;
            current[3] = qx;
            current[4] = qy;
            current[5] = qz;
            current[6] = qw;
            boundsKnown = false;
            forgetMotion();
            world.motionEpoch++;
            world.noteMoved(this);
        } finally {
            world.exit();
        }
    }

    private void place(double x, double y, double z, Quaternionfc rotation, boolean wake, boolean teleport) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "position");
            float qx = rotation.x(), qy = rotation.y(), qz = rotation.z(), qw = rotation.w();
            float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (!(length > 1e-6f) || !Float.isFinite(length)) {
                throw new IllegalArgumentException("rotation has no direction: " + rotation);
            }
            qx /= length;
            qy /= length;
            qz /= length;
            qw /= length;
            B3.bodySetTransform(id, x, y, z, qx, qy, qz, qw);
            if (wake && type != BodyType.STATIC) {
                B3.bodySetAwake(id, true);
            }
            current[0] = x;
            current[1] = y;
            current[2] = z;
            current[3] = qx;
            current[4] = qy;
            current[5] = qz;
            current[6] = qw;
            if (teleport) {
                System.arraycopy(current, 0, previous, 0, 7);
            } else {
                // part of the frame's motion: the next frame starts from here, even if no step moves it
                world.noteMoved(this);
            }
            boundsKnown = false;
            forgetMotion();
        } finally {
            world.exit();
        }
    }

    /**
     * Drives a kinematic body to the target over {@code timeStep} by velocity, so what it meets on the way is pushed,
     * and wakes it. ⚠️ A sleeping body whose move would be slower than its {@linkplain #sleepThreshold() sleep
     * threshold} is left asleep where it is. The transform this reports changes when the world steps, like a dynamic
     * body's.
     */
    public void setTargetTransform(double x, double y, double z, Quaternionfc rotation, float timeStep) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "target position");
            Checks.positive(timeStep, "time step");
            float qx = rotation.x(), qy = rotation.y(), qz = rotation.z(), qw = rotation.w();
            float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (!(length > 1e-6f) || !Float.isFinite(length)) {
                throw new IllegalArgumentException("target rotation has no direction: " + rotation);
            }
            B3.bodySetTargetTransform(id, x, y, z, qx / length, qy / length, qz / length, qw / length, timeStep, true);
            forgetMotion();
        } finally {
            world.exit();
        }
    }

    // ---- motion -------------------------------------------------------------------------------

    private void knowMotion() {
        if (!velocityKnown || velocityEpoch != world.motionEpoch) {
            float[] v = world.vector;
            B3.bodyGetLinearVelocity(id, v);
            System.arraycopy(v, 0, state, 6, 3);
            B3.bodyGetAngularVelocity(id, v);
            System.arraycopy(v, 0, state, 9, 3);
            velocityKnown = true;
            velocityEpoch = world.motionEpoch;
        }
    }

    public Vector3f linearVelocity(Vector3f out) {
        world.enter();
        try {
            checkValid();
            knowMotion();
            return out.set(state[6], state[7], state[8]);
        } finally {
            world.exit();
        }
    }

    public void setLinearVelocity(float x, float y, float z) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "linear velocity");
            B3.bodySetLinearVelocity(id, x, y, z);
            forgetMotion();
        } finally {
            world.exit();
        }
    }

    public Vector3f angularVelocity(Vector3f out) {
        world.enter();
        try {
            checkValid();
            knowMotion();
            return out.set(state[9], state[10], state[11]);
        } finally {
            world.exit();
        }
    }

    public void setAngularVelocity(float x, float y, float z) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "angular velocity");
            B3.bodySetAngularVelocity(id, x, y, z);
            forgetMotion();
        } finally {
            world.exit();
        }
    }

    /** An impulse, N·s, at a world point, waking the body. */
    public void applyLinearImpulse(float ix, float iy, float iz, double px, double py, double pz) {
        world.enter();
        try {
            checkValid();
            Checks.finite(ix, iy, iz, "impulse");
            Checks.finite(px, py, pz, "impulse point");
            B3.bodyApplyLinearImpulse(id, ix, iy, iz, px, py, pz, true);
            forgetMotion();
        } finally {
            world.exit();
        }
    }

    /** An impulse, N·s, at the centre of mass, waking the body. */
    public void applyLinearImpulseToCenter(float ix, float iy, float iz) {
        world.enter();
        try {
            checkValid();
            Checks.finite(ix, iy, iz, "impulse");
            B3.bodyApplyLinearImpulseToCenter(id, ix, iy, iz, true);
            forgetMotion();
        } finally {
            world.exit();
        }
    }

    /** A force for the next step, at the centre of mass, waking the body. */
    public void applyForceToCenter(float fx, float fy, float fz) {
        world.enter();
        try {
            checkValid();
            Checks.finite(fx, fy, fz, "force");
            B3.bodyApplyForceToCenter(id, fx, fy, fz, true);
        } finally {
            world.exit();
        }
    }

    /** A force for the next step at a world point — it turns the body too — waking the body. */
    public void applyForce(float fx, float fy, float fz, double px, double py, double pz) {
        world.enter();
        try {
            checkValid();
            Checks.finite(fx, fy, fz, "force");
            Checks.finite(px, py, pz, "force point");
            B3.bodyApplyForce(id, fx, fy, fz, px, py, pz, true);
        } finally {
            world.exit();
        }
    }

    /** A torque for the next step, N·m, world space, waking the body. */
    public void applyTorque(float tx, float ty, float tz) {
        world.enter();
        try {
            checkValid();
            Checks.finite(tx, ty, tz, "torque");
            B3.bodyApplyTorque(id, tx, ty, tz, true);
        } finally {
            world.exit();
        }
    }

    /** An angular impulse, N·m·s, world space, waking the body. */
    public void applyAngularImpulse(float ix, float iy, float iz) {
        world.enter();
        try {
            checkValid();
            Checks.finite(ix, iy, iz, "angular impulse");
            B3.bodyApplyAngularImpulse(id, ix, iy, iz, true);
            forgetMotion();
        } finally {
            world.exit();
        }
    }

    /** The velocity of the body's material at a world point: its linear velocity plus the turn's, m/s. */
    public Vector3f pointVelocity(double x, double y, double z, Vector3f out) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "point");
            float[] v = world.vector;
            B3.bodyGetWorldPointVelocity(id, x, y, z, v);
            return out.set(v[0], v[1], v[2]);
        } finally {
            world.exit();
        }
    }

    /** A point in the body's space, in the world, where the body is now. */
    public Vector3d toWorld(float x, float y, float z, Vector3d out) {
        Vector3f p = rotation(new Quaternionf()).transform(new Vector3f(x, y, z));
        return out.set(current[0] + p.x, current[1] + p.y, current[2] + p.z);
    }

    /** A world point in the body's space, where the body is now. */
    public Vector3f toLocal(double x, double y, double z, Vector3f out) {
        out.set((float) (x - current[0]), (float) (y - current[1]), (float) (z - current[2]));
        return rotation(new Quaternionf()).transformInverse(out);
    }

    // ---- mass ---------------------------------------------------------------------------------

    /** Mass, centre of mass and inertia, body space, into {@code out}. */
    public MassData massData(MassData out) {
        world.enter();
        try {
            checkValid();
            B3.bodyGetMassData(id, world.massValues);
            out.read(world.massValues);
            return out;
        } finally {
            world.exit();
        }
    }

    /**
     * Overrides what the shapes' densities give — until a shape change that updates the mass, a change of
     * {@linkplain #setType type} or {@link #applyMassFromShapes()}. The inertia must be a real one: symmetric, nothing
     * negative on its diagonal or in its determinants (Box3D would store a bad one before complaining, and a body with
     * one can gain energy as it spins).
     */
    public void setMassData(MassData data) {
        world.enter();
        try {
            checkValid();
            Checks.notNegative(data.mass, "mass");
            Checks.finite(data.centerX, data.centerY, data.centerZ, "centre of mass");
            Checks.inertia(data.inertia);
            float[] v = world.massValues;
            data.write(v);
            B3.bodySetMassData(id, v);
            massRevision++;
            forgetMotion();
        } finally {
            world.exit();
        }
    }

    /** Mass, centre and inertia from the shapes' densities again. */
    public void applyMassFromShapes() {
        world.enter();
        try {
            checkValid();
            B3.bodyApplyMassFromShapes(id);
            massRevision++;
            forgetMotion();
        } finally {
            world.exit();
        }
    }

    /** The centre of mass in the world. */
    public Vector3d worldCenter(Vector3d out) {
        world.enter();
        try {
            checkValid();
            double[] p = world.point;
            B3.bodyGetWorldCenter(id, p);
            return out.set(p[0], p[1], p[2]);
        } finally {
            world.exit();
        }
    }

    // ---- settings -----------------------------------------------------------------------------

    // the bridge's numbers (mb3_body.c)
    private static final int LINEAR_DAMPING = 0,
            ANGULAR_DAMPING = 1,
            GRAVITY_SCALE = 2,
            SLEEP_THRESHOLD = 3,
            SAFETY_FACTOR = 4;
    private static final int BULLET = 0,
            SLEEP_ENABLED = 1,
            FAST_ROTATION = 2,
            CONTACT_RECYCLING = 3,
            FIXED_ROTATION = 4;

    private void setFloat(int param, float value) {
        world.enter();
        try {
            checkValid();
            B3.bodySetFloat(id, param, value);
        } finally {
            world.exit();
        }
    }

    private float getFloat(int param) {
        world.enter();
        try {
            checkValid();
            return B3.bodyGetFloat(id, param);
        } finally {
            world.exit();
        }
    }

    private void setFlag(int flag, boolean value) {
        world.enter();
        try {
            checkValid();
            B3.bodySetFlag(id, flag, value);
        } finally {
            world.exit();
        }
    }

    private boolean getFlag(int flag) {
        world.enter();
        try {
            checkValid();
            return B3.bodyGetFlag(id, flag);
        } finally {
            world.exit();
        }
    }

    public float linearDamping() {
        return getFloat(LINEAR_DAMPING);
    }

    public void setLinearDamping(float damping) {
        Checks.notNegative(damping, "linear damping");
        setFloat(LINEAR_DAMPING, damping);
    }

    public float angularDamping() {
        return getFloat(ANGULAR_DAMPING);
    }

    public void setAngularDamping(float damping) {
        Checks.notNegative(damping, "angular damping");
        setFloat(ANGULAR_DAMPING, damping);
    }

    public float gravityScale() {
        return getFloat(GRAVITY_SCALE);
    }

    public void setGravityScale(float scale) {
        Checks.finite(scale, "gravity scale");
        setFloat(GRAVITY_SCALE, scale);
    }

    /** m/s: slower than this for a while, the body may sleep. */
    public float sleepThreshold() {
        return getFloat(SLEEP_THRESHOLD);
    }

    public void setSleepThreshold(float speed) {
        Checks.notNegative(speed, "sleep threshold");
        setFloat(SLEEP_THRESHOLD, speed);
    }

    /** Continuous collision safety factor, 0.01–0.5. */
    public float safetyFactor() {
        return getFloat(SAFETY_FACTOR);
    }

    public void setSafetyFactor(float factor) {
        if (!(factor >= 0.01f) || !(factor <= 0.5f)) {
            throw new IllegalArgumentException("safety factor must be 0.01 to 0.5: " + factor);
        }
        setFloat(SAFETY_FACTOR, factor);
    }

    /** A bullet is swept against moving bodies too, not only static ones (but not other bullets): small and fast. */
    public boolean isBullet() {
        return getFlag(BULLET);
    }

    public void setBullet(boolean bullet) {
        setFlag(BULLET, bullet);
    }

    public boolean isSleepEnabled() {
        return getFlag(SLEEP_ENABLED);
    }

    /** False keeps the body (and its island) awake. */
    public void enableSleep(boolean enable) {
        setFlag(SLEEP_ENABLED, enable);
    }

    public boolean isFastRotationAllowed() {
        return getFlag(FAST_ROTATION);
    }

    /** Lets a body spin faster than Box3D's per-step cap: wheels. */
    public void allowFastRotation(boolean allow) {
        setFlag(FAST_ROTATION, allow);
    }

    public boolean isContactRecyclingEnabled() {
        return getFlag(CONTACT_RECYCLING);
    }

    /** On by default: faster, but reused contact points may cause ghost collisions — off for characters. */
    public void enableContactRecycling(boolean enable) {
        setFlag(CONTACT_RECYCLING, enable);
    }

    /** Hit events on every shape of the body (each shape keeps its own switch afterwards). */
    public void enableHitEvents(boolean enable) {
        world.enter();
        try {
            checkValid();
            B3.bodyEnableHitEvents(id, enable);
        } finally {
            world.exit();
        }
    }

    public boolean isFixedRotation() {
        return getFlag(FIXED_ROTATION);
    }

    /** See {@link BodyDef#fixedRotation}. */
    public void setFixedRotation(boolean fixed) {
        world.enter();
        try {
            checkValid();
            B3.bodySetFlag(id, FIXED_ROTATION, fixed);
            forgetMotion();
        } finally {
            world.exit();
        }
    }

    // ---- queries against this body only -------------------------------------------------------

    /** The closest of this body's shapes along {@code origin + translation} that {@code filter} lets through. */
    public boolean castRay(
            double ox, double oy, double oz, float tx, float ty, float tz, QueryFilter filter, CastResult out) {
        world.enter();
        try {
            checkValid();
            Checks.finite(ox, oy, oz, "ray origin");
            Checks.finite(tx, ty, tz, "ray translation");
            CastHits hit = out.hit;
            hit.clear(world);
            if (B3.bodyCastRay(
                    id, ox, oy, oz, tx, ty, tz, filter.categoryBits(), filter.maskBits(), hit.doubles, hit.longs)) {
                hit.count = 1;
            }
            return hit.count > 0;
        } finally {
            world.exit();
        }
    }

    /**
     * The closest hit of {@code geometry} at {@code (x, y, z)} turned by {@code rotation}, moved by
     * {@code translation}, on this body's shapes only; touching at the start hits at fraction 0.
     */
    public boolean castShape(
            Geometry geometry,
            double x,
            double y,
            double z,
            Quaternionfc rotation,
            float tx,
            float ty,
            float tz,
            QueryFilter filter,
            CastResult out) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "cast position");
            Checks.finite(tx, ty, tz, "cast translation");
            int count = world.queryProxy(geometry, rotation);
            CastHits hit = out.hit;
            hit.clear(world);
            if (B3.bodyCastShape(
                    id,
                    x,
                    y,
                    z,
                    world.queryPoints,
                    count,
                    world.queryRadius,
                    tx,
                    ty,
                    tz,
                    filter.categoryBits(),
                    filter.maskBits(),
                    hit.doubles,
                    hit.longs)) {
                hit.count = 1;
            }
            return hit.count > 0;
        } finally {
            world.exit();
        }
    }

    /** Whether {@code geometry} at {@code (x, y, z)} turned by {@code rotation} overlaps one of this body's shapes. */
    public boolean overlaps(
            Geometry geometry, double x, double y, double z, Quaternionfc rotation, QueryFilter filter) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "overlap position");
            int count = world.queryProxy(geometry, rotation);
            return B3.bodyOverlapShape(
                    id, x, y, z, world.queryPoints, count, world.queryRadius, filter.categoryBits(), filter.maskBits());
        } finally {
            world.exit();
        }
    }

    /**
     * The closest point of the body's spheres, capsules, boxes and hulls to a world point, into {@code out}; returns
     * the distance, 0 inside, {@link Float#POSITIVE_INFINITY} with none of those shapes. Exact far from the origin.
     */
    public float closestPoint(double x, double y, double z, Vector3d out) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "point");
            double[] p = world.point;
            float distance = B3.bodyGetClosestPoint(id, x, y, z, p);
            if (distance == Float.MAX_VALUE) {
                out.set(x, y, z);
                return Float.POSITIVE_INFINITY;
            }
            out.set(p[0], p[1], p[2]);
            return distance;
        } finally {
            world.exit();
        }
    }

    // ---- state --------------------------------------------------------------------------------

    public BodyType type() {
        return type;
    }

    /** ⚠️ A body with a height field stays static: Box3D quietly refuses, so this refuses out loud. */
    public void setType(BodyType type) {
        world.enter();
        try {
            checkValid();
            if (type != BodyType.STATIC) {
                for (Shape shape : shapes) {
                    if (shape.geometry() instanceof Geometry.HeightField) {
                        throw new IllegalStateException("a body with a height field stays static");
                    }
                }
            }
            B3.bodySetType(id, type.ordinal());
            this.type = type;
            massRevision++;
            boundsKnown = false;
            // its islands are rebuilt: the bodies around it may sleep or wake with it
            world.motionEpoch++;
        } finally {
            world.exit();
        }
    }

    public float mass() {
        world.enter();
        try {
            checkValid();
            return B3.bodyGetMass(id);
        } finally {
            world.exit();
        }
    }

    public boolean isAwake() {
        world.enter();
        try {
            checkValid();
            return B3.bodyIsAwake(id);
        } finally {
            world.exit();
        }
    }

    /** Wakes the body or puts it to sleep — with its whole island either way. */
    public void setAwake(boolean awake) {
        world.enter();
        try {
            checkValid();
            B3.bodySetAwake(id, awake);
            // Box3D puts the body's whole island to sleep, and a sleeping body is still: everyone in it changed
            world.motionEpoch++;
        } finally {
            world.exit();
        }
    }

    /**
     * A disabled body is out of the simulation: it does not move, collide or show up in queries. A
     * {@linkplain #setParked parked} body stays out till it is let back, then is as this says.
     */
    public void setEnabled(boolean enabled) {
        world.enter();
        try {
            checkValid();
            disabled = !enabled;
            B3.bodySetEnabled(id, enabled && !parked);
            world.motionEpoch++;
        } finally {
            world.exit();
        }
    }

    /**
     * Out of the simulation for a while whatever {@link #setEnabled} says, and back as it says: the terrain streamer's,
     * for a body whose chunks are not loaded.
     */
    @ApiStatus.Internal
    public void setParked(boolean parked) {
        world.enter();
        try {
            checkValid();
            if (this.parked != parked) {
                this.parked = parked;
                B3.bodySetEnabled(id, !disabled && !parked);
                world.motionEpoch++;
            }
        } finally {
            world.exit();
        }
    }

    public boolean isParked() {
        return parked;
    }

    /** Whether it is in the simulation now: enabled, and not parked. */
    public boolean isEnabled() {
        world.enter();
        try {
            checkValid();
            return B3.bodyIsEnabled(id);
        } finally {
            world.exit();
        }
    }

    /** What this body is, to the bodies that ignore a tag ({@link #setIgnoredTag}); 0 is nothing. */
    public long tag() {
        return tag;
    }

    /**
     * See {@link #tag()}. MineBox3D tags an entity's proxy with bit 62 set ({@code EntityShapes.tagOf}): a mod's own
     * tags leave it clear.
     */
    public void setTag(long tag) {
        setTags(tag, ignoredTag);
    }

    /** The tag of the bodies this one never meets; 0 for none. */
    public long ignoredTag() {
        return ignoredTag;
    }

    /**
     * The bodies tagged {@code tag} ({@link #setTag}) and this one never meet: no contact, and a box swept as one of
     * them ({@link PhysicsWorld#sweepBox}) passes through this one — a ragdoll and its own entity's proxy. Box3D's
     * custom filter decides, so only for pairs one of whose shapes was made with
     * {@link ShapeDef#enableCustomFiltering}; pairs already touching are let go. 0 for none.
     */
    public void setIgnoredTag(long tag) {
        setTags(this.tag, tag);
    }

    private void setTags(long tag, long ignoredTag) {
        world.enter();
        try {
            checkValid();
            if (tag != this.tag || ignoredTag != this.ignoredTag) {
                B3.bodySetTags(world.id, id, tag, ignoredTag);
                this.tag = tag;
                this.ignoredTag = ignoredTag;
                // what it meets, as a new filter is: whoever copies it elsewhere copies it again
                shapesChanged();
            }
        } finally {
            world.exit();
        }
    }

    /** Where the body touches things as of the last step, into {@code out}; returns how many points. */
    public int contactPoints(ContactPoints out) {
        world.enter();
        try {
            checkValid();
            out.world = world;
            int count = B3.bodyGetContactPoints(id, out.floats, out.shapes, out.cells);
            if (count > out.shapes.length) {
                out.ensureCapacity(count);
                count = B3.bodyGetContactPoints(id, out.floats, out.shapes, out.cells);
            }
            out.count = Math.min(count, out.shapes.length);
            return out.count;
        } finally {
            world.exit();
        }
    }

    /**
     * World-space bounds of all its shapes, {@code [minX, minY, minZ, maxX, maxY, maxZ]}, as Box3D keeps them: grown by
     * its speculative distance (0.02 m) on every side; the body's position for a body without shapes. Float: coarse far
     * out.
     */
    public float[] bounds(float[] out) {
        world.enter();
        try {
            checkValid();
            if (!boundsKnown) {
                B3.bodyComputeAABB(id, world.bounds);
                System.arraycopy(world.bounds, 0, state, 0, 6);
                boundsKnown = true;
            }
            System.arraycopy(state, 0, out, 0, 6);
            return out;
        } finally {
            world.exit();
        }
    }

    private void checkValid() {
        if (!world.isOpen()) {
            throw new IllegalStateException("the body's world is closed");
        }
        if (destroyed) {
            throw new IllegalStateException("body was destroyed");
        }
    }
}
