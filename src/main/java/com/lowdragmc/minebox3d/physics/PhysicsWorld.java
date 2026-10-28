package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.jni.B3;
import com.lowdragmc.minebox3d.jni.Box3DException;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * <b>A Box3D world</b>: bodies, their shapes, stepping, queries.
 *
 * <h2>Threads</h2>
 * <p>A world belongs to one thread, the one that created it, until {@link #transferTo} hands it to another. Every
 * call from any other thread throws {@link WrongThreadException} — always, not only when two threads happen to
 * collide — so a mod using a world from the wrong thread fails at once and in its own code. Box3D's rule is one
 * thread inside a world at a time; different worlds may be used by different threads at once.
 *
 * <p>Reads that only look at the Java side — {@link Body#position}, {@link Body#renderPosition},
 * {@link #movedBodies()}, {@link #bodies()} — are not checked, for speed, but are just as much the owner's.
 *
 * <h2>Faults</h2>
 * <p>Arguments are checked before they reach Box3D. If Box3D still fails an internal check during a step (a
 * {@link Box3DException}), the world is {@linkplain #isFaulted() faulted} and refuses further steps rather than
 * carry on with whatever the failed check let through.
 *
 * <h2>Interpolation</h2>
 * <p>Every body carries where it was at the start of the frame and where it is now. Call
 * {@link #beginInterpolationFrame()} once per frame you draw between — a Minecraft tick — and then
 * step as many times as the frame needs; {@link Body#renderPosition} blends the two.
 */
public final class PhysicsWorld implements AutoCloseable {

    final int id;
    private final Long2ObjectOpenHashMap<Body> bodies = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Shape> shapes = new Long2ObjectOpenHashMap<>();
    private final List<Body> movedThisFrame = new ArrayList<>();
    private final Set<Body> fellAsleep = new LinkedHashSet<>();
    private final List<VoxelGrid> grids = new ArrayList<>();
    private volatile Thread owner;
    private boolean open = true;
    @Nullable
    private Box3DException fault;

    // move-event buffers, grown on demand and reused
    private long[] eventIds = new long[64];
    private double[] eventTransforms = new double[64 * 7];
    private boolean[] eventAsleep = new boolean[64];

    // results of single calls, reused: only the owner thread is ever in here
    private final double[] castPoint = new double[5];
    private final float[] castNormal = new float[3];
    final float[] vector = new float[3];

    private PhysicsWorld(int id) {
        this.id = id;
        this.owner = Thread.currentThread();
    }

    /** A new world, owned by the calling thread. */
    public static PhysicsWorld create(WorldDef def) {
        NativeLibrary.load();
        Checks.finite(def.gravityX, def.gravityY, def.gravityZ, "gravity");
        if (def.workerCount < 0) {
            throw new IllegalArgumentException("workerCount must not be negative: " + def.workerCount);
        }
        return new PhysicsWorld(B3.createWorld(def));
    }

    public int id() {
        return id;
    }

    public boolean isOpen() {
        return open;
    }

    // ---- the owner thread ---------------------------------------------------------------------

    public Thread owner() {
        return owner;
    }

    /** Hands the world to another thread. Only the owner may; from then on only {@code thread} may use it. */
    public void transferTo(Thread thread) {
        enter();
        owner = thread;
    }

    /** Every call into the world starts here: from the owner thread or not at all. */
    void enter() {
        if (Thread.currentThread() != owner) {
            throw new WrongThreadException("PhysicsWorld " + id + " belongs to " + owner.getName() + ", not "
                    + Thread.currentThread().getName());
        }
    }

    /**
     * Every call ends here. Today there is nothing to undo; it marks where a call stops using the native world, which
     * is where a step running on another thread would be waited for.
     */
    void exit() {
    }

    void checkOpen() {
        if (!open) {
            throw new IllegalStateException("PhysicsWorld " + id + " is closed");
        }
    }

    /** Whether a step has failed one of Box3D's own checks; such a world steps no more. */
    public boolean isFaulted() {
        return fault != null;
    }

    // ---- bodies -------------------------------------------------------------------------------

    public Body createBody(BodyDef def) {
        enter();
        try {
            checkOpen();
            Checks.bodyDef(def);
            long bodyId = B3.createBody(id, def);
            Body body = new Body(this, bodyId, def);
            bodies.put(bodyId, body);
            return body;
        } finally {
            exit();
        }
    }

    void destroyBody(Body body) {
        enter();
        try {
            if (!body.isValid() || body.world() != this) {
                return;
            }
            B3.destroyBody(body.id);
            bodies.remove(body.id);
            if (body.movedThisFrame) {
                movedThisFrame.remove(body);
            }
            fellAsleep.remove(body);
            body.markDestroyed();
        } finally {
            exit();
        }
    }

    @Nullable
    public Body body(long bodyId) {
        return bodies.get(bodyId);
    }

    @Nullable
    public Shape shape(long shapeId) {
        return shapes.get(shapeId);
    }

    /** The bodies, as a live view: do not create or destroy bodies while going through it (see {@link #forEachBody}). */
    public Collection<Body> bodies() {
        return Collections.unmodifiableCollection(bodies.values());
    }

    public int bodyCount() {
        return bodies.size();
    }

    /** Runs {@code action} for every body there is now; it may create and destroy bodies. */
    public void forEachBody(Consumer<Body> action) {
        enter();
        try {
            for (Body body : bodies.values().toArray(new Body[0])) {
                if (body.isValid()) {
                    action.accept(body);
                }
            }
        } finally {
            exit();
        }
    }

    void registerShape(Shape shape) {
        shapes.put(shape.id, shape);
    }

    void unregisterShape(Shape shape) {
        shapes.remove(shape.id);
    }

    // ---- voxel terrain ------------------------------------------------------------------------

    /**
     * Block terrain for this world. Every section's shape is made from {@code shapeTemplate}: its filter,
     * events, user data and base material; per-block surface materials come from the grid's own table.
     * Closed with the world if not before.
     */
    public VoxelGrid createVoxelGrid(ShapeDef shapeTemplate) {
        enter();
        try {
            checkOpen();
            Checks.shapeDef(shapeTemplate);
            VoxelGrid grid = new VoxelGrid(this, B3.createVoxelGrid(id, shapeTemplate));
            grids.add(grid);
            return grid;
        } finally {
            exit();
        }
    }

    void forgetGrid(VoxelGrid grid) {
        grids.remove(grid);
    }

    // ---- stepping -----------------------------------------------------------------------------

    /**
     * Starts a frame to interpolate across: every body's "previous" becomes its "now", and the lists of
     * {@link #movedBodies()} and {@link #bodiesThatFellAsleep()} start again. Bodies that do not move during the frame
     * then draw still instead of replaying their last motion.
     */
    public void beginInterpolationFrame() {
        enter();
        try {
            for (Body body : movedThisFrame) {
                System.arraycopy(body.current, 0, body.previous, 0, 7);
                body.movedThisFrame = false;
            }
            movedThisFrame.clear();
            fellAsleep.clear();
        } finally {
            exit();
        }
    }

    /** Advances the world by {@code timeStep} seconds in {@code subStepCount} sub-steps. */
    public void step(float timeStep, int subStepCount) {
        enter();
        try {
            checkOpen();
            if (fault != null) {
                throw new IllegalStateException("PhysicsWorld " + id + " is faulted and steps no more", fault);
            }
            Checks.notNegative(timeStep, "time step");
            if (subStepCount < 1) {
                throw new IllegalArgumentException("sub-step count must be 1 or more: " + subStepCount);
            }
            try {
                B3.worldStep(id, timeStep, subStepCount);
            } catch (Box3DException e) {
                fault = e;
                throw e;
            }
            readMoveEvents();
        } finally {
            exit();
        }
    }

    private void readMoveEvents() {
        int count = B3.worldGetBodyMoveEvents(id, eventIds, eventTransforms, eventAsleep, eventIds.length);
        if (count > eventIds.length) {
            int capacity = Integer.highestOneBit(count - 1) << 1;
            eventIds = new long[capacity];
            eventTransforms = new double[capacity * 7];
            eventAsleep = new boolean[capacity];
            count = B3.worldGetBodyMoveEvents(id, eventIds, eventTransforms, eventAsleep, eventIds.length);
        }
        for (int i = 0; i < count; i++) {
            Body body = bodies.get(eventIds[i]);
            if (body == null) {
                continue;
            }
            System.arraycopy(eventTransforms, 7 * i, body.current, 0, 7);
            if (!body.movedThisFrame) {
                body.movedThisFrame = true;
                movedThisFrame.add(body);
            }
            if (eventAsleep[i]) {
                fellAsleep.add(body);
            } else {
                // woke again later in the same frame
                fellAsleep.remove(body);
            }
        }
    }

    /** The bodies that moved since {@link #beginInterpolationFrame()}. */
    public List<Body> movedBodies() {
        return Collections.unmodifiableList(movedThisFrame);
    }

    /** The bodies that fell asleep since {@link #beginInterpolationFrame()}, in any of its steps, and are still asleep. */
    public Collection<Body> bodiesThatFellAsleep() {
        return Collections.unmodifiableCollection(fellAsleep);
    }

    // ---- settings -----------------------------------------------------------------------------

    public void setGravity(float x, float y, float z) {
        enter();
        try {
            checkOpen();
            Checks.finite(x, y, z, "gravity");
            B3.worldSetGravity(id, x, y, z);
        } finally {
            exit();
        }
    }

    public void enableSleeping(boolean flag) {
        enter();
        try {
            checkOpen();
            B3.worldEnableSleeping(id, flag);
        } finally {
            exit();
        }
    }

    public int awakeBodyCount() {
        enter();
        try {
            checkOpen();
            return B3.worldGetAwakeBodyCount(id);
        } finally {
            exit();
        }
    }

    /** {@code [bodies, shapes, contacts, joints, islands, awake contacts]}; returns the last step in ms. */
    public float counters(int[] out) {
        enter();
        try {
            checkOpen();
            return B3.worldGetCounters(id, out);
        } finally {
            exit();
        }
    }

    // ---- queries ------------------------------------------------------------------------------

    /**
     * The closest shape whose category {@code maskBits} accepts, along {@code origin + translation}. A shape the
     * ray starts inside or on is ignored (Box3D's closest ray). Hits on voxel terrain have no {@link Shape}
     * object; {@link VoxelGrid#featureCell} names the cell.
     */
    @Nullable
    public CastHit castRayClosest(double ox, double oy, double oz, float tx, float ty, float tz,
                                  long categoryBits, long maskBits) {
        enter();
        try {
            checkOpen();
            Checks.finite(ox, oy, oz, "ray origin");
            Checks.finite(tx, ty, tz, "ray translation");
            long shapeId = B3.worldCastRayClosest(id, ox, oy, oz, tx, ty, tz, categoryBits, maskBits, castPoint, castNormal);
            return hit(shapeId);
        } finally {
            exit();
        }
    }

    /**
     * The closest hit of a shape — {@code count} points, {@code [x, y, z]} relative to {@code origin}, swept
     * with {@code radius} — moved by {@code translation}. Unlike the ray, a shape already touching something
     * hits it at fraction 0. Terrain is hit only on the open side of a block face.
     */
    @Nullable
    public CastHit castShapeClosest(double ox, double oy, double oz, float[] points, int count, float radius,
                                    float tx, float ty, float tz, long categoryBits, long maskBits) {
        enter();
        try {
            checkOpen();
            Checks.finite(ox, oy, oz, "cast origin");
            Checks.points(points, count, "cast shape");
            Checks.notNegative(radius, "cast radius");
            Checks.finite(tx, ty, tz, "cast translation");
            long shapeId = B3.worldCastShapeClosest(id, ox, oy, oz, points, count, radius, tx, ty, tz, categoryBits, maskBits,
                    castPoint, castNormal);
            return hit(shapeId);
        } finally {
            exit();
        }
    }

    @Nullable
    private CastHit hit(long shapeId) {
        return shapeId == 0 ? null : new CastHit(shapes.get(shapeId), shapeId, (int) castPoint[4], castPoint[0], castPoint[1],
                castPoint[2], castNormal[0], castNormal[1], castNormal[2], (float) castPoint[3]);
    }

    /**
     * The shapes overlapping a query shape ({@code count} points relative to {@code origin}, with
     * {@code radius}): ids into {@code shapeIds}, returns how many there are, which may be more than fit.
     */
    public int overlapShape(double ox, double oy, double oz, float[] points, int count, float radius,
                            long categoryBits, long maskBits, long[] shapeIds) {
        enter();
        try {
            checkOpen();
            Checks.finite(ox, oy, oz, "overlap origin");
            Checks.points(points, count, "overlap shape");
            Checks.notNegative(radius, "overlap radius");
            return B3.worldOverlapShape(id, ox, oy, oz, points, count, radius, categoryBits, maskBits, shapeIds);
        } finally {
            exit();
        }
    }

    /**
     * The collision planes around a capsule — Box3D's character mover — with ends {@code a} and {@code b}
     * relative to {@code origin}. Per plane: {@code planes} gets {@code [nx, ny, nz, offset, px, py, pz]}
     * (point relative to origin), {@code shapeIds} the shape, {@code triangleIndices} its triangle index (the
     * cell on terrain). Returns the count, at most {@code shapeIds.length}.
     */
    public int collideMover(double ox, double oy, double oz, float ax, float ay, float az, float bx, float by, float bz,
                            float radius, long categoryBits, long maskBits, float[] planes, long[] shapeIds,
                            int[] triangleIndices) {
        enter();
        try {
            checkOpen();
            Checks.finite(ox, oy, oz, "mover origin");
            Checks.finite(ax, ay, az, "mover end");
            Checks.finite(bx, by, bz, "mover end");
            Checks.positive(radius, "mover radius");
            return B3.worldCollideMover(id, ox, oy, oz, ax, ay, az, bx, by, bz, radius, categoryBits, maskBits, planes, shapeIds,
                    triangleIndices);
        } finally {
            exit();
        }
    }

    // ---- lifetime -----------------------------------------------------------------------------

    /** Destroys the world and with it every body, shape and voxel grid in it. */
    @Override
    public void close() {
        enter();
        try {
            if (!open) {
                return;
            }
            open = false;
            try {
                B3.destroyWorld(id);
            } finally {
                // the world took the section bodies with it; this frees the grids' own memory
                for (VoxelGrid grid : grids) {
                    grid.free();
                }
                grids.clear();
                for (Body body : bodies.values()) {
                    body.markDestroyed();
                }
                bodies.clear();
                shapes.clear();
                movedThisFrame.clear();
                fellAsleep.clear();
            }
        } finally {
            exit();
        }
    }
}
