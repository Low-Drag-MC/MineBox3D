package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * <b>A Box3D world</b>: bodies, their shapes, stepping, queries, events.
 *
 * <h2>Threads</h2>
 * <p>Box3D allows one thread inside a world at a time; different worlds may be used by different threads at once. How a
 * world is shared is its {@link ThreadMode}, chosen in {@link WorldDef#threadMode}:
 * <ul>
 *   <li>{@link ThreadMode#OWNER_THREAD} (the default): the world belongs to the thread that created it until
 *       {@link #transferTo} hands it on.</li>
 *   <li>{@link ThreadMode#EXCLUSIVE}: any thread, one at a time, between {@link #use()} and closing what it
 *       returned.</li>
 * </ul>
 * <p>Either way every call from a thread that may not use the world now throws {@link WrongThreadException} — always,
 * not only when two threads happen to collide — so a mod using a world wrongly fails at once and in its own code.
 *
 * <p>Reads that only look at the Java side — {@link Body#position}, {@link Body#renderPosition},
 * {@link #movedBodies()}, {@link #bodies()} — are not checked, for speed, but follow the same rule.
 *
 * <h2>Faults</h2>
 * <p>Arguments are checked before they reach Box3D. If Box3D still fails an internal check during a step (a
 * {@link Box3DException}), the world is {@linkplain #isFaulted() faulted} and refuses further steps rather than
 * carry on with whatever the failed check let through — such as a body flung to NaN, whose bounds the broadphase now
 * holds. Taking anything out walks those, so in a faulted world destroying a body, joint, shape or pair table only lets
 * go of it here; {@link #close()} frees them with the world, and the meshes and height fields their shapes still read.
 * Close a faulted world soon: what was let go of is still in it, and queries still reach it.
 *
 * <h2>Frames</h2>
 * <p>Every body carries where it was at the start of the frame and where it is now. Call
 * {@link #beginInterpolationFrame()} once per frame you draw between — a Minecraft tick — and then step as many times
 * as the frame needs: {@link Body#renderPosition} blends the two, and {@link #movedBodies()},
 * {@link #bodiesThatFellAsleep()} and {@link #events()} gather what the frame's steps did.
 *
 * <h2>Lifetime</h2>
 * <p>{@link #close()} frees the native world. A world dropped without closing is freed once it is garbage collected,
 * with a warning: until then it holds one of Box3D's 128 world slots.
 */
public final class PhysicsWorld implements AutoCloseable {

    private static final Quaternionfc IDENTITY = new Quaternionf();

    final int id;
    private final ThreadMode threadMode;
    /** {@link ThreadMode#OWNER_THREAD}: the owner */
    private volatile Thread owner;
    /** {@link ThreadMode#EXCLUSIVE}: the thread between {@link #use()} and its close, if any */
    private final AtomicReference<Thread> holder = new AtomicReference<>();

    private int useDepth;
    private final Use use = new Use();
    private final NativeState nativeState;
    private final Cleaner.Cleanable cleanable;

    private final Long2ObjectOpenHashMap<Body> bodies = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Shape> shapes = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Joint> joints = new Long2ObjectOpenHashMap<>();
    private final List<Body> movedThisFrame = new ArrayList<>();
    private final Set<Body> fellAsleep = new LinkedHashSet<>();
    /** Bodies drawn off where they are while a correction fades ({@link Body#addRenderError}) */
    private final Set<Body> withRenderError = new LinkedHashSet<>();

    private final WorldEvents events = new WorldEvents();
    /** shapes destroyed since the last step: Box3D reports their end events after the next one */
    private final Long2ObjectOpenHashMap<Shape> goneSinceStep = new Long2ObjectOpenHashMap<>();

    private boolean framesUsed;
    /** bumped by calls that change more bodies than their own (an island put to sleep): cached velocities are stale */
    int motionEpoch;

    private final List<VoxelGrid> grids = new ArrayList<>();
    private boolean open = true;

    @Nullable
    private Box3DException fault;
    /** What shapes let go of in this faulted world were made of: Box3D's shapes still read it until the world goes */
    private final List<Geometry> releaseOnClose = new ArrayList<>();

    // event buffers, grown on demand and reused
    private long[] moveIds = new long[64];
    private double[] moveTransforms = new double[64 * 7];
    private float[] moveStates = new float[64 * Body.STATE];
    private boolean[] moveAsleep = new boolean[64];
    private long[] contactBeginIds = new long[2 * 16];
    private long[] contactEndIds = new long[2 * 16];
    private long[] hitIds = new long[4 * 16];
    private double[] hitData = new double[11 * 16];
    private final int[] contactCounts = new int[3];
    private long[] sensorBeginIds = new long[2 * 16];
    private long[] sensorEndIds = new long[2 * 16];
    private final int[] sensorCounts = new int[2];
    private long[] jointEventIds = new long[16];

    // scratch for single calls: only the thread using the world is ever in here
    final float[] vector = new float[3];
    final float[] bounds = new float[6];
    /** a joint property or frame */
    final float[] jointValues = new float[7];
    /** one constraint joint's targets or state */
    final float[] constraintValues = new float[ConstraintJointBatch.TARGET];

    final long[] oneJoint = new long[1];
    final float[] massValues = new float[13];
    final double[] point = new double[3];
    private final float[] profileValues = new float[Profile.FIELDS];

    @Nullable
    private SurfaceMaterial scratchMaterial;
    /** the query shape {@link #queryProxy} built: up to a hull's vertices */
    final float[] queryPoints = new float[3 * Geometry.Hull.MAX_VERTICES];

    float queryRadius;
    private final Vector3f queryCorner = new Vector3f();
    private final Quaternionf queryRotation = new Quaternionf();
    private final CastResult scratchResult = new CastResult();
    /** every hit of a tagged cast, its closest that meets the tag kept ({@link #castShapeClosest}) */
    private final CastHits scratchHits = new CastHits();

    private PhysicsWorld(int id, WorldDef def) {
        this.id = id;
        this.threadMode = def.threadMode;
        this.owner = Thread.currentThread();
        this.nativeState = new NativeState(id);
        this.cleanable = Cleaners.CLEANER.register(this, nativeState);
    }

    /**
     * A new world; with {@link ThreadMode#OWNER_THREAD}, owned by the calling thread. {@link Box3DException} when all
     * 128 of Box3D's world slots are taken.
     */
    public static PhysicsWorld create(WorldDef def) {
        NativeLibrary.load();
        Checks.worldDef(def);
        return new PhysicsWorld(B3.createWorld(def), def);
    }

    public int id() {
        return id;
    }

    public boolean isOpen() {
        return open;
    }

    public ThreadMode threadMode() {
        return threadMode;
    }

    // ---- threads ------------------------------------------------------------------------------

    /** The thread that may use the world now: the owner, or with {@link ThreadMode#EXCLUSIVE} whoever holds it. */
    @Nullable
    public Thread owner() {
        return threadMode == ThreadMode.OWNER_THREAD ? owner : holder.get();
    }

    /** Hands an {@link ThreadMode#OWNER_THREAD} world to another thread. Only the owner may. */
    public void transferTo(Thread thread) {
        if (threadMode != ThreadMode.OWNER_THREAD) {
            throw new IllegalStateException(
                    "PhysicsWorld " + id + " is " + threadMode + ": use() it instead of handing it on");
        }
        enter();
        owner = thread;
    }

    /**
     * Takes the world for the calling thread until the returned {@link Use} is closed, so: {@code try (var use =
     * world.use()) { … }}. With {@link ThreadMode#EXCLUSIVE}, another thread holding it makes this throw
     * {@link WrongThreadException} rather than wait; the holder may nest uses. With {@link ThreadMode#OWNER_THREAD}
     * only the owner may, and closing does nothing — code written with {@code use()} works with both.
     */
    public Use use() {
        Thread current = Thread.currentThread();
        if (threadMode == ThreadMode.OWNER_THREAD) {
            enter();
            return use;
        }
        if (holder.get() == current) {
            useDepth++;
            return use;
        }
        if (!holder.compareAndSet(null, current)) {
            Thread other = holder.get();
            throw new WrongThreadException("PhysicsWorld " + id + " is in use by "
                    + (other == null ? "another thread" : other.getName()) + ", not free for " + current.getName());
        }
        useDepth = 1;
        return use;
    }

    /**
     * {@link #use()} if the calling thread may use the world now, else {@code null} instead of throwing: the owner of
     * an {@link ThreadMode#OWNER_THREAD} world; any thread while an {@link ThreadMode#EXCLUSIVE} one is free or held by
     * it already.
     */
    @Nullable
    public Use tryUse() {
        Thread current = Thread.currentThread();
        if (threadMode == ThreadMode.OWNER_THREAD) {
            return current == owner ? use : null;
        }
        if (holder.get() == current) {
            useDepth++;
            return use;
        }
        if (!holder.compareAndSet(null, current)) {
            return null;
        }
        useDepth = 1;
        return use;
    }

    /** Holding a world: {@link #close()} lets it go. */
    public final class Use implements AutoCloseable {
        private Use() {}

        @Override
        public void close() {
            if (threadMode == ThreadMode.OWNER_THREAD) {
                return;
            }
            if (holder.get() != Thread.currentThread()) {
                throw new WrongThreadException("PhysicsWorld " + id + " is not held by "
                        + Thread.currentThread().getName());
            }
            if (--useDepth == 0) {
                holder.set(null);
            }
        }
    }

    /** Every call into the world starts here: from the thread that may use it now, or not at all. */
    void enter() {
        Thread current = Thread.currentThread();
        if (threadMode == ThreadMode.OWNER_THREAD) {
            if (current != owner) {
                throw new WrongThreadException(
                        "PhysicsWorld " + id + " belongs to " + owner.getName() + ", not " + current.getName());
            }
        } else if (holder.get() != current) {
            Thread other = holder.get();
            throw new WrongThreadException("PhysicsWorld " + id + " is "
                    + (other == null ? "not held: use() it first" : "held by " + other.getName()) + ", not by "
                    + current.getName());
        }
    }

    /**
     * Every call ends here: keeps the world reachable until its native call has returned, so the cleaner cannot free it
     * during the call (a method's last use of {@code this} may come before its native call returns).
     */
    void exit() {
        Reference.reachabilityFence(this);
    }

    /** A material to read into for one call, by the thread using the world. */
    SurfaceMaterial scratchMaterial() {
        if (scratchMaterial == null) {
            scratchMaterial = new SurfaceMaterial();
        }
        return scratchMaterial;
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

    void releaseOnClose(Geometry geometry) {
        releaseOnClose.add(geometry);
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
            if (fault == null) {
                B3.destroyBody(body.id);
            }
            // Box3D took the body's joints with it
            for (Joint joint : body.joints().toArray(new Joint[0])) {
                forgetJoint(joint);
            }
            bodies.remove(body.id);
            if (body.movedThisFrame) {
                movedThisFrame.remove(body);
            }
            fellAsleep.remove(body);
            withRenderError.remove(body);
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

    /**
     * The bodies, as a live view: do not create or destroy bodies while going through it (see {@link #forEachBody}).
     */
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

    // ---- joints -------------------------------------------------------------------------------

    /** A joint between {@link JointDef#bodyA} and {@link JointDef#bodyB}, both of this world. Box3D may wake them. */
    public <J extends Joint> J createJoint(JointDef<J, ?> def) {
        enter();
        try {
            checkOpen();
            def.check();
            Body a = def.bodyA, b = def.bodyB;
            if (a.world() != this || !a.isValid() || !b.isValid()) {
                throw new IllegalArgumentException("the joint's bodies must be alive and in this world");
            }
            long jointId = B3.createJoint(id, def.type().ordinal(), a.id, b.id, def);
            J joint = def.wrap(this, jointId, a, b);
            joints.put(jointId, joint);
            a.addJoint(joint);
            b.addJoint(joint);
            return joint;
        } finally {
            exit();
        }
    }

    void destroyJoint(Joint joint, boolean wakeBodies) {
        enter();
        try {
            if (!joint.isValid() || joint.world() != this) {
                return;
            }
            if (fault == null) {
                B3.destroyJoint(joint.id, wakeBodies);
            }
            forgetJoint(joint);
        } finally {
            exit();
        }
    }

    /** Box3D has already freed the joint. */
    private void forgetJoint(Joint joint) {
        joints.remove(joint.id);
        joint.bodyA().removeJoint(joint);
        joint.bodyB().removeJoint(joint);
        joint.markDestroyed();
    }

    @Nullable
    public Joint joint(long jointId) {
        return joints.get(jointId);
    }

    /** The joints, as a live view: do not create or destroy joints while going through it. */
    public Collection<Joint> joints() {
        return Collections.unmodifiableCollection(joints.values());
    }

    void registerShape(Shape shape) {
        shapes.put(shape.id, shape);
    }

    void unregisterShape(Shape shape) {
        shapes.remove(shape.id);
        if (goneSinceStep.size() >= MAX_GONE) {
            // a world not stepped for a long while: its end events would name nothing (null) rather than hold all
            // forever
            goneSinceStep.clear();
        }
        goneSinceStep.put(shape.id, shape);
    }

    private static final int MAX_GONE = 4096;

    /** The shape of an event: still there, or destroyed since the last step. */
    @Nullable
    private Shape eventShape(long shapeId) {
        Shape shape = shapes.get(shapeId);
        return shape != null ? shape : goneSinceStep.get(shapeId);
    }

    // ---- voxel terrain ------------------------------------------------------------------------

    /**
     * Block terrain for this world. Every section's shape is made from {@code shapeTemplate} — its filter, events, user
     * data and base material — which may not be a sensor; per-block surface materials come from the grid's own table.
     * Closed with the world if not before.
     */
    public VoxelGrid createVoxelGrid(ShapeDef shapeTemplate) {
        enter();
        try {
            checkOpen();
            Checks.shapeDef(shapeTemplate);
            long pointer = B3.createVoxelGrid(id, shapeTemplate);
            VoxelGrid grid = new VoxelGrid(this, pointer);
            grids.add(grid);
            nativeState.grids.add(pointer);
            return grid;
        } finally {
            exit();
        }
    }

    void forgetGrid(VoxelGrid grid, long pointer) {
        grids.remove(grid);
        nativeState.grids.rem(pointer);
    }

    // ---- stepping -----------------------------------------------------------------------------

    /**
     * Starts a frame: every body's "previous" becomes its "now", and {@link #movedBodies()},
     * {@link #bodiesThatFellAsleep()} and {@link #events()} start again. Bodies that do not move during the frame then
     * draw still instead of replaying their last motion. A world that never begins a frame has its events of the last
     * step only.
     */
    public void beginInterpolationFrame() {
        enter();
        try {
            framesUsed = true;
            for (Body body : movedThisFrame) {
                System.arraycopy(body.current, 0, body.previous, 0, 7);
                body.movedThisFrame = false;
            }
            movedThisFrame.clear();
            fellAsleep.clear();
            events.clear();
            withRenderError.removeIf(body -> !body.fadeRenderError());
        } finally {
            exit();
        }
    }

    /**
     * Drops the frame's {@link #events()} and {@link #bodiesThatFellAsleep()} so far — what a simulation run again set
     * off, which Unreal's resimulation does not raise again either. {@link #movedBodies()} is kept.
     */
    public void discardEvents() {
        enter();
        try {
            events.clear();
            fellAsleep.clear();
        } finally {
            exit();
        }
    }

    void noteRenderError(Body body) {
        withRenderError.add(body);
    }

    /**
     * Advances the world by {@code timeStep} seconds in {@code subStepCount} sub-steps. A {@link Box3DException} from
     * it faults the world; a faulted world throws {@link IllegalStateException} here.
     */
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
            if (!framesUsed) {
                events.clear();
            }
            readMoveEvents();
            readContactEvents();
            readSensorEvents();
            readJointEvents();
            goneSinceStep.clear();
        } finally {
            exit();
        }
    }

    private void readMoveEvents() {
        int count = B3.worldGetBodyMoveEvents(id, moveIds, moveTransforms, moveStates, moveAsleep, moveIds.length);
        if (count > moveIds.length) {
            int capacity = Integer.highestOneBit(count - 1) << 1;
            moveIds = new long[capacity];
            moveTransforms = new double[capacity * 7];
            moveStates = new float[capacity * Body.STATE];
            moveAsleep = new boolean[capacity];
            count = B3.worldGetBodyMoveEvents(id, moveIds, moveTransforms, moveStates, moveAsleep, moveIds.length);
        }
        for (int i = 0; i < count; i++) {
            Body body = bodies.get(moveIds[i]);
            if (body == null) {
                continue;
            }
            System.arraycopy(moveTransforms, 7 * i, body.current, 0, 7);
            body.moved(moveStates, Body.STATE * i, moveAsleep[i]);
            if (!body.movedThisFrame) {
                body.movedThisFrame = true;
                movedThisFrame.add(body);
            }
            if (moveAsleep[i]) {
                fellAsleep.add(body);
            } else {
                // woke again later in the same frame
                fellAsleep.remove(body);
            }
        }
    }

    /**
     * A body put somewhere outside a step: its frame's start follows it at the next {@link #beginInterpolationFrame}.
     */
    void noteMoved(Body body) {
        if (!body.movedThisFrame) {
            body.movedThisFrame = true;
            movedThisFrame.add(body);
        }
    }

    private void readContactEvents() {
        B3.worldGetContactEvents(id, contactBeginIds, contactEndIds, hitIds, hitData, contactCounts);
        int begins = contactCounts[0], ends = contactCounts[1], hits = contactCounts[2];
        if (2 * begins > contactBeginIds.length || 2 * ends > contactEndIds.length || 4 * hits > hitIds.length) {
            contactBeginIds = grown(contactBeginIds, 2 * begins);
            contactEndIds = grown(contactEndIds, 2 * ends);
            hitIds = grown(hitIds, 4 * hits);
            if (11 * hits > hitData.length) {
                hitData = new double[Math.max(11 * hits, 2 * hitData.length)];
            }
            B3.worldGetContactEvents(id, contactBeginIds, contactEndIds, hitIds, hitData, contactCounts);
        }
        for (int i = 0; i < begins; i++) {
            long a = contactBeginIds[2 * i], b = contactBeginIds[2 * i + 1];
            events.contactBegins.add(new WorldEvents.ContactBegin(eventShape(a), eventShape(b), a, b));
        }
        for (int i = 0; i < ends; i++) {
            long a = contactEndIds[2 * i], b = contactEndIds[2 * i + 1];
            events.contactEnds.add(new WorldEvents.ContactEnd(eventShape(a), eventShape(b), a, b));
        }
        for (int i = 0; i < hits; i++) {
            long a = hitIds[4 * i], b = hitIds[4 * i + 1];
            int d = 11 * i;
            events.contactHits.add(new WorldEvents.ContactHit(
                    eventShape(a),
                    eventShape(b),
                    a,
                    b,
                    hitData[d],
                    hitData[d + 1],
                    hitData[d + 2],
                    (float) hitData[d + 3],
                    (float) hitData[d + 4],
                    (float) hitData[d + 5],
                    (float) hitData[d + 6],
                    hitIds[4 * i + 2],
                    hitIds[4 * i + 3],
                    (int) hitData[d + 7],
                    (int) hitData[d + 8],
                    (int) hitData[d + 9],
                    (int) hitData[d + 10]));
        }
    }

    private void readSensorEvents() {
        B3.worldGetSensorEvents(id, sensorBeginIds, sensorEndIds, sensorCounts);
        int begins = sensorCounts[0], ends = sensorCounts[1];
        if (2 * begins > sensorBeginIds.length || 2 * ends > sensorEndIds.length) {
            sensorBeginIds = grown(sensorBeginIds, 2 * begins);
            sensorEndIds = grown(sensorEndIds, 2 * ends);
            B3.worldGetSensorEvents(id, sensorBeginIds, sensorEndIds, sensorCounts);
        }
        for (int i = 0; i < begins; i++) {
            long s = sensorBeginIds[2 * i], v = sensorBeginIds[2 * i + 1];
            events.sensorBegins.add(new WorldEvents.SensorBegin(eventShape(s), eventShape(v), s, v));
        }
        for (int i = 0; i < ends; i++) {
            long s = sensorEndIds[2 * i], v = sensorEndIds[2 * i + 1];
            events.sensorEnds.add(new WorldEvents.SensorEnd(eventShape(s), eventShape(v), s, v));
        }
    }

    private void readJointEvents() {
        int count = B3.worldGetJointEvents(id, jointEventIds);
        if (count > jointEventIds.length) {
            jointEventIds = grown(jointEventIds, count);
            count = B3.worldGetJointEvents(id, jointEventIds);
        }
        for (int i = 0; i < count; i++) {
            long jointId = jointEventIds[i];
            events.jointEvents.add(new WorldEvents.JointEvent(joints.get(jointId), jointId));
        }
    }

    private static long[] grown(long[] array, int needed) {
        return needed > array.length ? new long[Math.max(needed, 2 * array.length)] : array;
    }

    /** The bodies that moved since {@link #beginInterpolationFrame()}. */
    public List<Body> movedBodies() {
        return Collections.unmodifiableList(movedThisFrame);
    }

    /** The bodies that fell asleep in any step since {@link #beginInterpolationFrame()} and are still asleep. */
    public Collection<Body> bodiesThatFellAsleep() {
        return Collections.unmodifiableCollection(fellAsleep);
    }

    /** What happened since {@link #beginInterpolationFrame()}. */
    public WorldEvents events() {
        return events;
    }

    // ---- settings -----------------------------------------------------------------------------

    /** m/s²; Box3D has no up vector of its own. */
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

    // the bridge's numbers (mb3_world.c)
    private static final int RESTITUTION_THRESHOLD = 0, HIT_EVENT_THRESHOLD = 1, MAXIMUM_LINEAR_SPEED = 2;
    private static final int SLEEPING = 0, CONTINUOUS = 1;

    private void setFloat(int param, float value) {
        enter();
        try {
            checkOpen();
            B3.worldSetFloat(id, param, value);
        } finally {
            exit();
        }
    }

    private float getFloat(int param) {
        enter();
        try {
            checkOpen();
            return B3.worldGetFloat(id, param);
        } finally {
            exit();
        }
    }

    private void setFlag(int flag, boolean value) {
        enter();
        try {
            checkOpen();
            B3.worldSetFlag(id, flag, value);
        } finally {
            exit();
        }
    }

    private boolean getFlag(int flag) {
        enter();
        try {
            checkOpen();
            return B3.worldGetFlag(id, flag);
        } finally {
            exit();
        }
    }

    public void enableSleeping(boolean flag) {
        setFlag(SLEEPING, flag);
        // turning it off wakes every body
        motionEpoch++;
    }

    public boolean isSleepingEnabled() {
        return getFlag(SLEEPING);
    }

    /** Continuous collision for fast dynamic bodies against static ones. */
    public void enableContinuous(boolean flag) {
        setFlag(CONTINUOUS, flag);
    }

    public boolean isContinuousEnabled() {
        return getFlag(CONTINUOUS);
    }

    /** m/s: collisions slower than this do not bounce. */
    public float restitutionThreshold() {
        return getFloat(RESTITUTION_THRESHOLD);
    }

    public void setRestitutionThreshold(float speed) {
        Checks.notNegative(speed, "restitution threshold");
        setFloat(RESTITUTION_THRESHOLD, speed);
    }

    /** m/s: collisions slower than this raise no hit events. */
    public float hitEventThreshold() {
        return getFloat(HIT_EVENT_THRESHOLD);
    }

    public void setHitEventThreshold(float speed) {
        Checks.notNegative(speed, "hit event threshold");
        setFloat(HIT_EVENT_THRESHOLD, speed);
    }

    /** m/s: no body moves faster. */
    public float maximumLinearSpeed() {
        return getFloat(MAXIMUM_LINEAR_SPEED);
    }

    public void setMaximumLinearSpeed(float speed) {
        Checks.positive(speed, "maximum linear speed");
        setFloat(MAXIMUM_LINEAR_SPEED, speed);
    }

    /**
     * How contacts push overlapping shapes apart: stiffness (Hz), damping ratio, and the cap on the speed it pushes at
     * (m/s). Box3D has no getter: {@link WorldDef}'s values until set.
     */
    public void setContactTuning(float hertz, float dampingRatio, float contactSpeed) {
        Checks.positive(hertz, "contact hertz");
        Checks.notNegative(dampingRatio, "contact damping ratio");
        Checks.positive(contactSpeed, "contact speed");
        enter();
        try {
            checkOpen();
            B3.worldSetContactTuning(id, hertz, dampingRatio, contactSpeed);
        } finally {
            exit();
        }
    }

    /**
     * <b>An explosion</b> (Box3D's): an outward impulse on every sphere, capsule, box and hull of a dynamic body within
     * {@code radius + falloff} of the point, {@code impulsePerArea} (N·s/m²) times the area it shows the point, falling
     * off to zero across {@code falloff}; negative pulls inwards. Each shape's {@link ShapeDef#explosionScale} scales
     * its share. Wakes what it pushes.
     *
     * @param maskBits the categories it reaches
     */
    public void explode(
            double x, double y, double z, float radius, float falloff, float impulsePerArea, long maskBits) {
        enter();
        try {
            checkOpen();
            Checks.finite(x, y, z, "explosion position");
            Checks.notNegative(radius, "explosion radius");
            Checks.notNegative(falloff, "explosion falloff");
            Checks.finite(impulsePerArea, "impulse per area");
            B3.worldExplode(id, x, y, z, radius, falloff, impulsePerArea, maskBits);
            motionEpoch++;
        } finally {
            exit();
        }
    }

    /** {@link #explode(double, double, double, float, float, float, long)} reaching every category. */
    public void explode(double x, double y, double z, float radius, float falloff, float impulsePerArea) {
        explode(x, y, z, radius, falloff, impulsePerArea, -1L);
    }

    /** How long the last step spent in each phase, into {@code out}. */
    public Profile profile(Profile out) {
        enter();
        try {
            checkOpen();
            B3.worldGetProfile(id, profileValues);
            out.read(profileValues);
            return out;
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
     * The closest shape along {@code origin + translation} that {@code filter} lets through, into {@code out}; false
     * for none. A shape the ray starts inside or on is ignored (Box3D's closest ray).
     */
    public boolean castRayClosest(
            double ox, double oy, double oz, float tx, float ty, float tz, QueryFilter filter, CastResult out) {
        enter();
        try {
            checkOpen();
            Checks.finite(ox, oy, oz, "ray origin");
            Checks.finite(tx, ty, tz, "ray translation");
            CastHits hit = out.hit;
            hit.clear(this);
            if (B3.worldCastRayClosest(
                    id, ox, oy, oz, tx, ty, tz, filter.categoryBits(), filter.maskBits(), hit.doubles, hit.longs)) {
                hit.count = 1;
            }
            return hit.count > 0;
        } finally {
            exit();
        }
    }

    /** {@link #castRayClosest(double, double, double, float, float, float, QueryFilter, CastResult)} as a new value. */
    @Nullable
    public CastHit castRayClosest(double ox, double oy, double oz, float tx, float ty, float tz, QueryFilter filter) {
        return castRayClosest(ox, oy, oz, tx, ty, tz, filter, scratchResult) ? scratchResult.toHit() : null;
    }

    /**
     * Every shape along {@code origin + translation} that {@code filter} lets through, closest first, into {@code out}:
     * one hit per shape, its closest; a section of voxel terrain is one shape.
     */
    public int castRay(
            double ox, double oy, double oz, float tx, float ty, float tz, QueryFilter filter, CastHits out) {
        enter();
        try {
            checkOpen();
            Checks.finite(ox, oy, oz, "ray origin");
            Checks.finite(tx, ty, tz, "ray translation");
            out.clear(this);
            int count = B3.worldCastRay(
                    id,
                    ox,
                    oy,
                    oz,
                    tx,
                    ty,
                    tz,
                    filter.categoryBits(),
                    filter.maskBits(),
                    out.doubles,
                    out.longs,
                    out.capacity());
            if (count > out.capacity()) {
                out.ensureCapacity(count);
                count = B3.worldCastRay(
                        id,
                        ox,
                        oy,
                        oz,
                        tx,
                        ty,
                        tz,
                        filter.categoryBits(),
                        filter.maskBits(),
                        out.doubles,
                        out.longs,
                        out.capacity());
            }
            out.count = Math.min(count, out.capacity());
            return out.count;
        } finally {
            exit();
        }
    }

    /**
     * The closest hit of {@code geometry}, placed at {@code (x, y, z)} turned by {@code rotation}, moved by
     * {@code translation}, into {@code out}; false for none. Unlike the ray, a shape already touching something hits it
     * at fraction 0. Terrain is hit only on the open side of a block face. Convex geometry only: not a mesh or height
     * field.
     */
    public boolean castShapeClosest(
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
        enter();
        try {
            checkOpen();
            Checks.finite(x, y, z, "cast position");
            Checks.finite(tx, ty, tz, "cast translation");
            int count = queryProxy(geometry, rotation);
            CastHits hit = out.hit;
            hit.clear(this);
            if (B3.worldCastShapeClosest(
                    id,
                    x,
                    y,
                    z,
                    queryPoints,
                    count,
                    queryRadius,
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
            exit();
        }
    }

    /**
     * <b>A turned box swept exactly</b> — {@code (hx, hy, hz)} half sizes, its middle at {@code (x, y, z)}, turned by
     * {@code rotation}, moved by {@code translation} — into {@code out}: the first shape it touches, or the one it
     * starts deepest in ({@link BoxSweep}). For a character mover that keeps its box touching what it stands on, which
     * {@link #castShapeClosest} cannot: it stops a linear slop short, and tells nothing of the way out of what it
     * starts in. Each shape is measured in its body's frame, so far from the origin it is as exact as near it.
     *
     * <p>Hulls (boxes), spheres, capsules, meshes and height fields; not voxel terrain, compounds or sensors.
     *
     * @param ignoreExits a shape the box starts inside and is moving out of is not met — Unreal's rule for a move out
     *                    of an overlap
     * @param ignore      a body not met at all, or {@code null} — the base a box is being carried with, which moved
     *                    first (Unreal's {@code MOVECOMP_IgnoreBases})
     */
    public boolean sweepBox(
            double x,
            double y,
            double z,
            Quaternionfc rotation,
            float hx,
            float hy,
            float hz,
            float tx,
            float ty,
            float tz,
            QueryFilter filter,
            boolean ignoreExits,
            @Nullable Body ignore,
            BoxSweep out) {
        return sweepBox(x, y, z, rotation, hx, hy, hz, tx, ty, tz, filter, ignoreExits, ignore, 0L, out);
    }

    /**
     * {@link #sweepBox(double, double, double, Quaternionfc, float, float, float, float, float, float, QueryFilter, boolean, Body, BoxSweep)}
     * for a box that is something ({@link Body#setTag}): the bodies that never meet it ({@link Body#setIgnoredTag}) are
     * not met either — an entity's own ragdoll, to the entity's mover. 0 is nothing.
     */
    public boolean sweepBox(
            double x,
            double y,
            double z,
            Quaternionfc rotation,
            float hx,
            float hy,
            float hz,
            float tx,
            float ty,
            float tz,
            QueryFilter filter,
            boolean ignoreExits,
            @Nullable Body ignore,
            long tag,
            BoxSweep out) {
        enter();
        try {
            checkOpen();
            Checks.finite(x, y, z, "sweep position");
            Checks.finite(tx, ty, tz, "sweep translation");
            out.clear(this);
            long ignored = ignore != null && ignore.world() == this && ignore.isValid() ? ignore.id : 0L;
            out.hit = B3.worldSweepBox(
                    id,
                    x,
                    y,
                    z,
                    rotation.x(),
                    rotation.y(),
                    rotation.z(),
                    rotation.w(),
                    hx,
                    hy,
                    hz,
                    tx,
                    ty,
                    tz,
                    filter.categoryBits(),
                    filter.maskBits(),
                    ignoreExits,
                    ignored,
                    tag,
                    out.doubles,
                    out.longs);
            return out.hit;
        } finally {
            exit();
        }
    }

    /**
     * {@link #castShapeClosest(Geometry, double, double, double, Quaternionfc, float, float, float, QueryFilter, CastResult)}
     * for something ({@link Body#setTag}): the bodies that never meet it ({@link Body#setIgnoredTag}) are passed
     * through — an entity's own ragdoll, to the ground query its mover stands by. 0 is nothing.
     */
    public boolean castShapeClosest(
            Geometry geometry,
            double x,
            double y,
            double z,
            Quaternionfc rotation,
            float tx,
            float ty,
            float tz,
            QueryFilter filter,
            long tag,
            CastResult out) {
        if (tag == 0L) {
            return castShapeClosest(geometry, x, y, z, rotation, tx, ty, tz, filter, out);
        }
        enter();
        try {
            int count = castShape(geometry, x, y, z, rotation, tx, ty, tz, filter, scratchHits);
            CastHits hit = out.hit;
            hit.clear(this);
            for (int i = 0; i < count; i++) {
                Body body = scratchHits.body(i);
                if (body != null && body.ignoredTag() == tag) {
                    continue;
                }
                System.arraycopy(scratchHits.doubles, i * CastHits.DOUBLES, hit.doubles, 0, CastHits.DOUBLES);
                System.arraycopy(scratchHits.longs, i * CastHits.LONGS, hit.longs, 0, CastHits.LONGS);
                hit.count = 1;
                break;
            }
            return hit.count > 0;
        } finally {
            exit();
        }
    }

    /**
     * {@link #castShapeClosest(Geometry, double, double, double, Quaternionfc, float, float, float, QueryFilter, CastResult)}
     * unturned.
     */
    public boolean castShapeClosest(
            Geometry geometry,
            double x,
            double y,
            double z,
            float tx,
            float ty,
            float tz,
            QueryFilter filter,
            CastResult out) {
        return castShapeClosest(geometry, x, y, z, IDENTITY, tx, ty, tz, filter, out);
    }

    /**
     * Every shape {@code geometry} meets on its way — placed and moved as
     * {@link #castShapeClosest(Geometry, double, double, double, Quaternionfc, float, float, float, QueryFilter, CastResult)}
     * — that {@code filter} lets through, closest first, into {@code out}: one hit per shape, its first.
     */
    public int castShape(
            Geometry geometry,
            double x,
            double y,
            double z,
            Quaternionfc rotation,
            float tx,
            float ty,
            float tz,
            QueryFilter filter,
            CastHits out) {
        enter();
        try {
            checkOpen();
            Checks.finite(x, y, z, "cast position");
            Checks.finite(tx, ty, tz, "cast translation");
            int points = queryProxy(geometry, rotation);
            out.clear(this);
            int count = B3.worldCastShape(
                    id,
                    x,
                    y,
                    z,
                    queryPoints,
                    points,
                    queryRadius,
                    tx,
                    ty,
                    tz,
                    filter.categoryBits(),
                    filter.maskBits(),
                    out.doubles,
                    out.longs,
                    out.capacity());
            if (count > out.capacity()) {
                out.ensureCapacity(count);
                count = B3.worldCastShape(
                        id,
                        x,
                        y,
                        z,
                        queryPoints,
                        points,
                        queryRadius,
                        tx,
                        ty,
                        tz,
                        filter.categoryBits(),
                        filter.maskBits(),
                        out.doubles,
                        out.longs,
                        out.capacity());
            }
            out.count = Math.min(count, out.capacity());
            return out.count;
        } finally {
            exit();
        }
    }

    /** The closest hit of a shape cast as a new value. */
    @Nullable
    public CastHit castShapeClosest(
            Geometry geometry,
            double x,
            double y,
            double z,
            Quaternionfc rotation,
            float tx,
            float ty,
            float tz,
            QueryFilter filter) {
        return castShapeClosest(geometry, x, y, z, rotation, tx, ty, tz, filter, scratchResult)
                ? scratchResult.toHit()
                : null;
    }

    /**
     * Every shape overlapping {@code geometry} placed at {@code (x, y, z)} turned by {@code rotation} that
     * {@code filter} lets through, into {@code out}. Voxel terrain counts as its sections. Convex geometry only.
     */
    public int overlapShape(
            Geometry geometry, double x, double y, double z, Quaternionfc rotation, QueryFilter filter, Overlaps out) {
        enter();
        try {
            checkOpen();
            Checks.finite(x, y, z, "overlap position");
            int count = queryProxy(geometry, rotation);
            out.world = this;
            int found = B3.worldOverlapShape(
                    id, x, y, z, queryPoints, count, queryRadius, filter.categoryBits(), filter.maskBits(), out.ids);
            if (found > out.ids.length) {
                out.ensureCapacity(found);
                found = B3.worldOverlapShape(
                        id,
                        x,
                        y,
                        z,
                        queryPoints,
                        count,
                        queryRadius,
                        filter.categoryBits(),
                        filter.maskBits(),
                        out.ids);
            }
            out.count = Math.min(found, out.ids.length);
            return out.count;
        } finally {
            exit();
        }
    }

    /**
     * Every shape whose bounds may overlap the box from {@code min} to {@code max} that {@code filter} lets through,
     * into {@code out}: Box3D's broad phase, so candidates — a shape's bounds are fattened and kept in float, and a
     * shape found may not touch the box at all — for a precise query to look at next. Cheap: no shape is tested.
     */
    public int overlapAABB(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            QueryFilter filter,
            Overlaps out) {
        enter();
        try {
            checkOpen();
            Checks.finite(minX, minY, minZ, "box min");
            Checks.finite(maxX, maxY, maxZ, "box max");
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("box min above its max: (" + minX + ", " + minY + ", " + minZ
                        + ") > (" + maxX + ", " + maxY + ", " + maxZ + ")");
            }
            out.world = this;
            int found = B3.worldOverlapAABB(
                    id, minX, minY, minZ, maxX, maxY, maxZ, filter.categoryBits(), filter.maskBits(), out.ids);
            if (found > out.ids.length) {
                out.ensureCapacity(found);
                found = B3.worldOverlapAABB(
                        id, minX, minY, minZ, maxX, maxY, maxZ, filter.categoryBits(), filter.maskBits(), out.ids);
            }
            out.count = Math.min(found, out.ids.length);
            return out.count;
        } finally {
            exit();
        }
    }

    /** {@link #overlapShape(Geometry, double, double, double, Quaternionfc, QueryFilter, Overlaps)}, unturned. */
    public int overlapShape(Geometry geometry, double x, double y, double z, QueryFilter filter, Overlaps out) {
        return overlapShape(geometry, x, y, z, IDENTITY, filter, out);
    }

    /**
     * The collision planes around a capsule — Box3D's character mover — with ends {@code a} and {@code b} relative to
     * {@code origin}, into {@code out}.
     */
    public int collideMover(
            double ox,
            double oy,
            double oz,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float radius,
            QueryFilter filter,
            MoverPlanes out) {
        enter();
        try {
            checkOpen();
            Checks.finite(ox, oy, oz, "mover origin");
            Checks.finite(ax, ay, az, "mover end");
            Checks.finite(bx, by, bz, "mover end");
            Checks.positive(radius, "mover radius");
            out.world = this;
            while (true) {
                int count = B3.worldCollideMover(
                        id,
                        ox,
                        oy,
                        oz,
                        ax,
                        ay,
                        az,
                        bx,
                        by,
                        bz,
                        radius,
                        filter.categoryBits(),
                        filter.maskBits(),
                        out.floats,
                        out.shapes,
                        out.cells);
                if (count < out.capacity()) {
                    out.count = count;
                    return count;
                }
                // full: there may be more
                out.grow();
            }
        } finally {
            exit();
        }
    }

    /** {@code geometry} turned by {@code rotation} as Box3D's query proxy: points into {@link #queryPoints}, radius. */
    int queryProxy(Geometry geometry, Quaternionfc rotation) {
        Checks.geometry(geometry);
        Checks.rotation(rotation.x(), rotation.y(), rotation.z(), rotation.w(), "query rotation");
        switch (geometry) {
            case Geometry.Sphere s -> {
                queryRadius = s.radius();
                putQueryPoint(0, rotation.transform(queryCorner.set(s.cx(), s.cy(), s.cz())));
                return 1;
            }
            case Geometry.Capsule c -> {
                queryRadius = c.radius();
                putQueryPoint(0, rotation.transform(queryCorner.set(c.ax(), c.ay(), c.az())));
                putQueryPoint(1, rotation.transform(queryCorner.set(c.bx(), c.by(), c.bz())));
                return 2;
            }
            case Geometry.Box b -> {
                queryRadius = 0;
                queryRotation.set(b.qx(), b.qy(), b.qz(), b.qw());
                for (int i = 0; i < 8; i++) {
                    queryCorner.set(
                            (i & 1) == 0 ? -b.hx() : b.hx(),
                            (i & 2) == 0 ? -b.hy() : b.hy(),
                            (i & 4) == 0 ? -b.hz() : b.hz());
                    queryRotation.transform(queryCorner).add(b.cx(), b.cy(), b.cz());
                    putQueryPoint(i, rotation.transform(queryCorner));
                }
                return 8;
            }
            case Geometry.Hull h -> {
                queryRadius = 0;
                float[] points = h.points();
                for (int i = 0; i < h.vertexCount(); i++) {
                    putQueryPoint(
                            i,
                            rotation.transform(queryCorner.set(points[3 * i], points[3 * i + 1], points[3 * i + 2])));
                }
                return h.vertexCount();
            }
            case Geometry.Mesh m -> throw new IllegalArgumentException("queries take convex shapes, not meshes");
            case Geometry.HeightField f ->
                throw new IllegalArgumentException("queries take convex shapes, not height fields");
        }
    }

    private void putQueryPoint(int index, Vector3f point) {
        queryPoints[3 * index] = point.x;
        queryPoints[3 * index + 1] = point.y;
        queryPoints[3 * index + 2] = point.z;
    }

    // ---- lifetime -----------------------------------------------------------------------------

    /** Destroys the world and with it every body, joint, shape, pair table and voxel grid in it. */
    @Override
    public void close() {
        enter();
        try {
            if (!open) {
                return;
            }
            open = false;
            try {
                nativeState.closed = true;
                B3.destroyWorld(id);
            } finally {
                // the world took the section bodies with it; this frees the grids' own memory
                for (VoxelGrid grid : grids) {
                    grid.free();
                }
                grids.clear();
                nativeState.grids.clear();
                for (Joint joint : joints.values()) {
                    joint.markDestroyed();
                }
                joints.clear();
                for (Body body : bodies.values()) {
                    body.markDestroyed();
                }
                bodies.clear();
                for (Geometry geometry : releaseOnClose) {
                    Shape.releaseData(geometry);
                }
                releaseOnClose.clear();
                shapes.clear();
                goneSinceStep.clear();
                movedThisFrame.clear();
                fellAsleep.clear();
                events.clear();
                cleanable.clean();
            }
        } finally {
            exit();
        }
    }

    /** What freeing a world needs, without the world: run by the cleaner when a world was dropped without closing. */
    private static final class NativeState implements Runnable {
        private final int id;
        private final LongArrayList grids = new LongArrayList();
        private volatile boolean closed;

        NativeState(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            if (closed) {
                return;
            }
            Cleaners.LOGGER.warn("PhysicsWorld {} was garbage collected without close(); freeing it now", id);
            // nothing can reach the world any more, so no thread is in it. The world first, as close() does: a grid
            // destroyed before it would destroy its sections in a world that may be faulted
            B3.destroyWorld(id);
            for (int i = 0; i < grids.size(); i++) {
                B3.destroyVoxelGrid(grids.getLong(i));
            }
        }
    }
}
