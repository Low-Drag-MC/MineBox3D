package com.lowdragmc.minebox3d.mc.sync;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.CollisionCategories;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.LevelRewind;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.ContactPoints;
import com.lowdragmc.minebox3d.physics.Filter;
import com.lowdragmc.minebox3d.physics.MassData;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.Shape;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.SurfaceMaterial;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>Bodies copied from the server to the clients of the players near them</b> (design doc
 * {@code 04-entities-and-bodies.md} §10). One per {@link LevelPhysics}, on both sides.
 *
 * <p>On the server, at the end of each tick ({@link #afterTick()}): every body
 * {@linkplain LevelBodies#setSynced synced} in the level's own physics is offered to the players whose clients have its
 * chunk: from when the chunk has been sent to them until it leaves their view. Each gets one {@link BodySyncPayload} a
 * tick with what changed for it; a body whose shapes, type or mass changed is taken away and sent again. What a client
 * knows is kept per <b>connection</b>, not per player object: a respawn in the same dimension makes a new player but
 * keeps the client's level, and with it the replicas.
 *
 * <p>On the client: each body arrives as a <b>replica</b>, a kinematic body in the client level's physics, driven a
 * tick at a time to where the server said ({@link #beforeSteps}) and drawn between ticks like any other body — Unreal's
 * default physics replication, where a client only shows the server's bodies; round a {@link Predictor} it is simulated
 * here instead. States wait in a short queue, one taken a tick, so a tick with two states and a tick with none (the two
 * sides' ticks are not in step, and the network jitters) do not make it stop and lunge; with none left it carries on at
 * the server's velocity for a tick or two. {@link SyncedBodyEvent} says when one comes and goes.
 *
 * <p>Private physics ({@link LevelPhysics#create}) is not synced. A client without MineBox3D, or without Box3D, is sent
 * nothing.
 */
public final class SyncedBodies {

    /** A replica further than this from where the server says jumps instead of being driven there. */
    private static final double SNAP_DISTANCE = 8;
    /** States queued past this many are dropped, oldest first: a replica lags the server by at most that many ticks. */
    private static final int MAX_QUEUED = 3;
    /** Ticks a replica with no new state carries on at the server's velocity before it holds still. */
    private static final int MAX_COAST = 2;

    /**
     * Round a predicting player's body, bodies are sent every tick, moved or not: its client holds each against where
     * it ran it to (EntityStudio doc 61 §3.9). A little past the bubble its client runs again.
     */
    private static final double PREDICTED_RADIUS = LevelRewind.BUBBLE_RADIUS + 2;

    /**
     * What a mod whose players' clients predict their bodies says of each player, on the server (EntityStudio's physics
     * characters, doc 61 §3.9). One mod at a time; set from common setup.
     */
    public interface Prediction {
        /**
         * The player's own frame the server ran this tick — its client's number for it — or
         * {@link BodySyncPayload#NO_FRAME}.
         */
        int frameOf(ServerPlayer player);

        /** Where round its client predicts bodies now; {@code null} for nowhere. */
        @Nullable
        Vec3 centreOf(ServerPlayer player);

        /**
         * The body its client predicts as its own, or {@code null}: the joints it makes with what it stands on are its
         * client's own as well, and are not counted among what holds those bodies
         * ({@link BodySyncPayload.State#joints}) — its client loses them the moment it steps off, the server's word a
         * round trip later.
         */
        @Nullable
        default Body ownBodyOf(ServerPlayer player) {
            return null;
        }
    }

    /**
     * What predicts this client's own body and the bodies round it, on a client (EntityStudio doc 61 §3.9): set while
     * it does. Round it the replicas of bodies the server simulates are simulated here, their server states held
     * against where they were run to ({@link LevelRewind#know}); its own body arrives synced as any, and is that body
     * here.
     */
    public interface Predictor {
        /** Where round it bodies are simulated here; {@code null} for nowhere now. */
        @Nullable
        Vec3 centre();

        /** The tick of this client's physics its frame {@code frame} ran in; {@link Long#MIN_VALUE} if not known. */
        long localTickOf(int frame);

        /** Whether the synced body so tagged and named is its own: no replica is made of it. */
        boolean isOwn(long tag, @Nullable String name);

        /**
         * Whether a replica so tagged and named is simulated here round it; false for one it only follows — another
         * client's predicted body, which is not in this one's run again (Unreal's remote characters).
         */
        default boolean simulates(long tag, @Nullable String name) {
            return true;
        }

        /** Its own body here, to hold the server's word of its own against; {@code null} for none now. */
        @Nullable
        Body ownBody();
    }

    @Nullable
    private static volatile Prediction prediction;

    /** Sets what says which of its players' frames each tick runs — see {@link Prediction}. */
    public static void setPrediction(@Nullable Prediction prediction) {
        SyncedBodies.prediction = prediction;
    }

    private final LevelPhysics physics;
    private final SyncedRiders riders;

    // ---- the server ----
    private int nextId = 1;
    private final Map<ServerGamePacketListenerImpl, View> views = new IdentityHashMap<>();
    private final List<Body> synced = new ArrayList<>();
    private final Int2ObjectOpenHashMap<Body> byId = new Int2ObjectOpenHashMap<>();
    private final List<ServerPlayer> players = new ArrayList<>();

    // ---- the client ----
    private final Int2ObjectOpenHashMap<Body> replicas = new Int2ObjectOpenHashMap<>();
    /** What each body the server sent here is, by its number — replicas and a predictor's own alike */
    private final Int2ObjectOpenHashMap<Sent> sent = new Int2ObjectOpenHashMap<>();

    @Nullable
    private Predictor predictor;
    /** The network number of the predictor's own body, which has no replica; -1 for none */
    private int ownId = -1;

    /** How a body the server sent is told apart: what a predictor's {@link Predictor#isOwn} is asked of. */
    private record Sent(long tag, @Nullable String name) {}

    /**
     * Sets what predicts this client's body — see {@link Predictor}; {@code null} when nothing does. Client only. Its
     * own body is looked for among all the server sent, so one that comes after another (its tree made again) finds it
     * too.
     */
    public void setPredictor(@Nullable Predictor predictor) {
        if (this.predictor == predictor) {
            return;
        }
        if (this.predictor != null) {
            physics.rewind().unwatch(this.predictor);
        }
        this.predictor = predictor;
        ownId = -1;
        if (predictor != null) {
            physics.rewind().watch(predictor, predictor::centre);
            for (var entry : sent.int2ObjectEntrySet()) {
                if (predictor.isOwn(entry.getValue().tag(), entry.getValue().name())) {
                    ownId = entry.getIntKey();
                    break;
                }
            }
            if (ownId >= 0) {
                dropOwnReplica(ownId);
            }
        }
    }

    /** Its own body, come as a replica before the predictor did: gone, the body here is it (two would push apart). */
    private void dropOwnReplica(int own) {
        if (!replicas.containsKey(own)) {
            return;
        }
        List<Event> events = new ArrayList<>();
        List<Body> gone = new ArrayList<>();
        try (PhysicsWorld.Use use = physics.world().use()) {
            remove(physics.level(), own, events, gone);
            try {
                for (Event event : events) {
                    NeoForge.EVENT_BUS.post(event);
                }
            } finally {
                for (Body body : gone) {
                    if (body.isValid()) {
                        body.destroy();
                    }
                }
            }
        }
    }

    @Nullable
    public Predictor predictor() {
        return predictor;
    }

    @ApiStatus.Internal
    public SyncedBodies(LevelPhysics physics) {
        this.physics = physics;
        this.riders = new SyncedRiders(physics);
    }

    /**
     * The network sync's payloads; registered by the mod. Optional, so a client without MineBox3D may still join; and
     * only where Box3D loaded, so a client that cannot build a replica does not say it wants them.
     */
    @ApiStatus.Internal
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        if (!NativeLibrary.isLoaded()) {
            return;
        }
        var registrar = event.registrar("6").optional();
        registrar.playToClient(
                BodySyncPayload.TYPE,
                BodySyncPayload.CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> receive(payload, context.player().level())));
        registrar.playToClient(
                RiderPayload.TYPE,
                RiderPayload.CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> SyncedRiders.receive(payload, context.player().level())));
        registrar.playToServer(
                StandingPayload.TYPE,
                StandingPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        SyncedRiders.receive(payload, player);
                    }
                }));
        registrar.playToServer(
                PushPayload.TYPE,
                PushPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        SyncedRiders.receive(payload, player);
                    }
                }));
    }

    /** What stands on the synced bodies, over the network (doc 04 §11.2 #3). */
    public SyncedRiders riders() {
        return riders;
    }

    // ---- either side -------------------------------------------------------------------------

    /** A body's number on the network: a synced body's on the server, a replica's on the client; -1 for any other. */
    public static int networkId(Body body) {
        Synced synced = body.attachment(Synced.class);
        if (synced != null) {
            return synced.id;
        }
        Replica replica = body.attachment(Replica.class);
        return replica != null ? replica.id : -1;
    }

    public static boolean isReplica(Body body) {
        return body.attachment(Replica.class) != null;
    }

    /**
     * How fast the server's body was moving as of the state the replica was last driven by, m/s, world space (the
     * replica's own velocity is how it is driven, which lags by the network). Zero for anything that is not a replica.
     */
    public static Vector3f serverVelocity(Body replica, Vector3f out) {
        Replica r = replica.attachment(Replica.class);
        return r == null ? out.zero() : out.set(r.vx, r.vy, r.vz);
    }

    /** The same for the turn, rad/s. */
    public static Vector3f serverAngularVelocity(Body replica, Vector3f out) {
        Replica r = replica.attachment(Replica.class);
        return r == null ? out.zero() : out.set(r.wx, r.wy, r.wz);
    }

    /**
     * How fast the server's body moves at a world point, as the replica last heard — what the server's
     * {@link Body#pointVelocity} answers there: the velocity of the centre of mass plus the turn's about it, m/s. The
     * centre is the server body's for a dynamic one (sent with it, and again when its type or mass is set), the body's
     * origin otherwise — which a kinematic or static body's is. Zero for anything that is not a replica.
     */
    public static Vector3f serverPointVelocity(Body replica, double x, double y, double z, Vector3f out) {
        Replica r = replica.attachment(Replica.class);
        if (r == null) {
            return out.zero();
        }
        Vector3d center = replica.position(new Vector3d());
        if (r.centre != null) {
            center.add(replica.rotation(new Quaternionf()).transform(new Vector3d(r.centre.x, r.centre.y, r.centre.z)));
        }
        double rx = x - center.x, ry = y - center.y, rz = z - center.z;
        return out.set((float) (r.vx + r.wy * rz - r.wz * ry), (float) (r.vy + r.wz * rx - r.wx * rz), (float)
                (r.vz + r.wx * ry - r.wy * rx));
    }

    /**
     * The server body's mass, into {@code out}, if entities push it (doc 04 §11.2 #1): what an entity walking into the
     * replica pushes against. False for any other body, and a replica of one entities do not push.
     */
    public static boolean serverMass(Body replica, MassData out) {
        Replica r = replica.attachment(Replica.class);
        if (r == null || r.dynamics == null || !r.dynamics.pushedByEntities()) {
            return false;
        }
        r.dynamics.into(out);
        return true;
    }

    /** Whether the body is a replica of one the server simulates. */
    public static boolean isDynamicOnServer(Body body) {
        Replica r = body.attachment(Replica.class);
        return r != null && r.dynamics != null;
    }

    // ---- the client --------------------------------------------------------------------------

    /** The replica with that network number, or {@code null}. Client only. */
    @Nullable
    public Body replica(int networkId) {
        return replicas.get(networkId);
    }

    /** Every replica now. Client only. */
    public Collection<Body> replicas() {
        return Collections.unmodifiableCollection(replicas.values());
    }

    private static void receive(BodySyncPayload payload, Level level) {
        if (payload.settled()) {
            SyncedRiders.settled(); // the mark comes alone, after the bodies it is about
        }
        if (payload.spawns().isEmpty()
                && payload.states().isEmpty()
                && payload.removals().length == 0
                && payload.tunings().isEmpty()) {
            return;
        }
        LevelPhysics physics = LevelPhysics.of(level);
        if (physics != null) {
            physics.sync().apply(payload);
        }
    }

    /**
     * One tick of the server's word: on the client thread, between ticks. The whole payload is applied before anyone is
     * told, so a listener that throws cannot cost the rest of it; the replicas that go are destroyed after, so a
     * {@link SyncedBodyEvent.Removed} listener still has them.
     */
    @ApiStatus.Internal
    public void apply(BodySyncPayload payload) {
        Level level = physics.level();
        PhysicsWorld world = physics.world();
        List<Event> events = new ArrayList<>();
        List<Body> gone = new ArrayList<>();
        try (PhysicsWorld.Use use = world.use()) {
            for (int id : payload.removals()) {
                remove(level, id, events, gone);
                sent.remove(id);
                if (id == ownId) {
                    ownId = -1;
                }
            }
            for (BodySyncPayload.Spawn spawn : payload.spawns()) {
                remove(level, spawn.id(), events, gone);
                sent.put(spawn.id(), new Sent(spawn.tag(), spawn.name()));
                if (predictor != null && predictor.isOwn(spawn.tag(), spawn.name())) {
                    ownId = spawn.id(); // its own: the body here is it
                    continue;
                }
                Body body = build(world, spawn);
                if (body != null) {
                    replicas.put(spawn.id(), body);
                    events.add(new SyncedBodyEvent.Added(level, body, spawn.id()));
                }
            }
            // how a body moves besides its mass, changed: on the replica as it is, simulated here from now on by it
            for (BodySyncPayload.Tuning tuning : payload.tunings()) {
                Body body = replicas.get(tuning.id());
                Replica replica = body == null ? null : body.attachment(Replica.class);
                if (replica != null && tuning.dynamics().isUsable()) {
                    replica.dynamics = tuning.dynamics();
                    if (replica.simulated) {
                        tuning.dynamics().moveAs(body);
                    }
                }
            }
            // the predictor's tick for what the server's tick ran of its frames: what the states are held against
            long local = predictor == null || payload.frame() == BodySyncPayload.NO_FRAME
                    ? Long.MIN_VALUE
                    : predictor.localTickOf(payload.frame());
            for (BodySyncPayload.State state : payload.states()) {
                if (!state.isUsable()) {
                    continue;
                }
                if (state.id() == ownId) {
                    Body own = predictor == null ? null : predictor.ownBody();
                    if (own != null && own.isValid() && local != Long.MIN_VALUE) {
                        know(local, own, state);
                    }
                    continue;
                }
                Body body = replicas.get(state.id());
                Replica replica = body == null ? null : body.attachment(Replica.class);
                if (replica == null) {
                    continue;
                }
                if (replica.simulated && local != Long.MIN_VALUE) {
                    replica.take(state);
                    know(local, body, state);
                    continue;
                }
                replica.queue.add(state);
                while (replica.queue.size() > MAX_QUEUED) {
                    replica.queue.poll();
                }
            }
            try {
                for (Event event : events) {
                    NeoForge.EVENT_BUS.post(event);
                }
            } finally {
                for (Body body : gone) {
                    if (body.isValid()) {
                        body.destroy();
                    }
                }
            }
        }
    }

    private void know(long tick, Body body, BodySyncPayload.State s) {
        Quaternionf q = new Quaternionf(s.qx(), s.qy(), s.qz(), s.qw()).normalize();
        physics.rewind()
                .know(
                        tick, body, s.x(), s.y(), s.z(), q.x, q.y, q.z, q.w, s.vx(), s.vy(), s.vz(), s.wx(), s.wy(),
                        s.wz());
    }

    /** A replica, whole, or {@code null} (and nothing left behind) if it could not be built. */
    @Nullable
    private static Body build(PhysicsWorld world, BodySyncPayload.Spawn spawn) {
        BodySyncPayload.State s = spawn.state();
        if (!s.isUsable()) {
            MineBox3D.LOGGER.warn("synced body {} arrived at an unusable place: {}", spawn.id(), s);
            return null;
        }
        Quaternionf rotation = new Quaternionf(s.qx(), s.qy(), s.qz(), s.qw()).normalize();
        // never asleep: Box3D ignores a sleeping kinematic body's targets below its sleep speed, and it would rest
        // millimetres off the server's
        Body body = world.createBody(new BodyDef()
                .type(BodyType.KINEMATIC)
                .position(s.x(), s.y(), s.z())
                .rotation(rotation)
                .name(spawn.name())
                .userData(spawn.userData())
                .enableSleep(false));
        try {
            // nothing of the client's own runs off a replica: it brings no terrain and no entity proxies
            LevelBodies.setNeedsTerrain(body, false);
            LevelBodies.setPushedByEntities(body, false);
            // what the server's body never meets — an entity's tag is its id, the same on both sides; set before the
            // shapes, so none has pairs to find again
            body.setTag(spawn.tag());
            body.setIgnoredTag(spawn.ignoredTag());
            for (BodySyncPayload.SyncedShape shape : spawn.shapes()) {
                ShapeDef def = new ShapeDef().sensor(shape.sensor());
                def.filter.set(new Filter(shape.categoryBits(), shape.maskBits(), shape.groupIndex()));
                shape.surface(def.baseMaterial);
                EntityCollisions.setStepUpOn(body.createShape(def, shape.geometry()), shape.stepUpOn());
            }
        } catch (RuntimeException e) {
            MineBox3D.LOGGER.error("could not build the replica of synced body {}", spawn.id(), e);
            body.destroy();
            return null;
        }
        Replica replica = new Replica(spawn.id());
        replica.take(s);
        replica.dynamics = spawn.dynamics() != null && spawn.dynamics().isUsable() ? spawn.dynamics() : null;
        Vector3f centre = spawn.centre();
        replica.centre = centre != null && Float.isFinite(centre.x + centre.y + centre.z) ? centre : null;
        body.attach(Replica.class, replica);
        return body;
    }

    private void remove(Level level, int id, List<Event> events, List<Body> gone) {
        Body body = replicas.remove(id);
        if (body != null) {
            events.add(new SyncedBodyEvent.Removed(level, body, id));
            gone.add(body);
        }
    }

    /**
     * On the client, before the tick's steps, holding the world: each replica driven over the tick to the next state
     * the server sent; with none, on along the server's velocity for a tick or two (the state is late), then held
     * still. Round a predictor, one it can simulate as the server does is simulated here instead.
     */
    @ApiStatus.Internal
    public void beforeSteps(float timeStep) {
        Vector3d position = new Vector3d();
        Quaternionf rotation = new Quaternionf();
        Vec3 centre = predictor == null ? null : predictor.centre();
        Body own = predictor == null ? null : predictor.ownBody();
        for (Body body : replicas.values()) {
            Replica r = body.isValid() ? body.attachment(Replica.class) : null;
            if (r == null) {
                continue;
            }
            body.position(position);
            // round what predicts here, a body the server simulates is simulated here too (EntityStudio doc 61 §3.9)
            boolean simulate = centre != null
                    && r.reproducible(body, own)
                    && position.distanceSquared(centre.x, centre.y, centre.z)
                            <= LevelRewind.BUBBLE_RADIUS * LevelRewind.BUBBLE_RADIUS
                    && predictor.simulates(body.tag(), body.name());
            if (simulate != r.simulated) {
                simulate(body, r, simulate);
            }
            if (r.simulated) {
                r.queue.clear();
                continue;
            }
            BodySyncPayload.State next = r.queue.poll();
            if (next != null) {
                r.take(next);
            }
            body.position(position);
            if (next != null) {
                r.coasting = 0;
                rotation.set(next.qx(), next.qy(), next.qz(), next.qw()).normalize();
                if (position.distanceSquared(next.x(), next.y(), next.z()) > SNAP_DISTANCE * SNAP_DISTANCE) {
                    body.setTransform(next.x(), next.y(), next.z(), rotation);
                } else {
                    body.setTargetTransform(next.x(), next.y(), next.z(), rotation, timeStep);
                }
            } else if (r.coasting < MAX_COAST && (r.vx != 0 || r.vy != 0 || r.vz != 0)) {
                // on along the line only; the turn is held (a guessed turn that is wrong shows more than a held one)
                r.coasting++;
                body.setTargetTransform(
                        position.x + r.vx * timeStep,
                        position.y + r.vy * timeStep,
                        position.z + r.vz * timeStep,
                        body.rotation(rotation),
                        timeStep);
            } else {
                body.setTargetTransform(position.x, position.y, position.z, body.rotation(rotation), timeStep);
            }
        }
        // a replica someone else destroyed is let go (made again only if the server sends it again)
        replicas.values().removeIf(body -> !body.isValid());
    }

    /**
     * A replica simulated here — dynamic, as heavy as the server's body, going at the server's last velocity — or
     * driven to the server's states again.
     */
    private static void simulate(Body body, Replica r, boolean simulated) {
        r.simulated = simulated;
        if (simulated) {
            body.setType(BodyType.DYNAMIC);
            body.setMassData(r.dynamics.into(new MassData()));
            r.dynamics.moveAs(body);
            body.setLinearVelocity(r.vx, r.vy, r.vz);
            body.setAngularVelocity(r.wx, r.wy, r.wz);
        } else {
            body.setType(BodyType.KINEMATIC);
            r.coasting = 0;
        }
    }

    /** Whether a replica is simulated here, round what predicts (EntityStudio doc 61 §3.9). Client only. */
    public static boolean isSimulated(Body replica) {
        Replica r = replica.attachment(Replica.class);
        return r != null && r.simulated;
    }

    // ---- the server --------------------------------------------------------------------------

    /** The synced body with that network number as of the last tick, or {@code null}. Server only. */
    @Nullable
    public Body serverBody(int networkId) {
        Body body = byId.get(networkId);
        return body != null && body.isValid() && LevelBodies.synced(body) ? body : null;
    }

    /** Whether the player's client has (been sent) the synced body with that number. Server only. */
    public boolean knows(ServerPlayer player, int networkId) {
        View view = views.get(player.connection);
        return view != null && view.known.containsKey(networkId);
    }

    /**
     * Whether a synced body the player's client is to have, but has not been sent yet, is around the player — a block
     * or two round its bounds, its chunk in the player's view. Server only, as of the last tick.
     */
    public boolean anyUnsentNear(ServerPlayer player) {
        if (physics.isStopped()) {
            return false; // none will be
        }
        float[] bounds = new float[6];
        Vector3d position = new Vector3d();
        for (Body body : synced) {
            if (!body.isValid() || knows(player, networkId(body))) {
                continue;
            }
            body.position(position);
            if (!player.getChunkTrackingView()
                    .contains(SectionPos.blockToSectionCoord(position.x), SectionPos.blockToSectionCoord(position.z))) {
                continue; // it is not this client's to have
            }
            body.bounds(bounds);
            // the bounds are floats: a block or two coarse far out, and the player may be just off the edge
            if (player.getX() > bounds[0] - 2
                    && player.getX() < bounds[3] + 2
                    && player.getY() > bounds[1] - 3
                    && player.getY() < bounds[4] + 2
                    && player.getZ() > bounds[2] - 2
                    && player.getZ() < bounds[5] + 2) {
                return true;
            }
        }
        return false;
    }

    /** At the end of the level's tick, holding the world: what each player's client should know now, sent. */
    @ApiStatus.Internal
    public void afterTick() {
        if (!(physics.level() instanceof ServerLevel level) || LevelPhysics.existing(level) != physics) {
            return;
        }
        synced.clear();
        byId.clear();
        for (Body body : physics.world().bodies()) {
            if (body.isValid() && LevelBodies.synced(body)) {
                synced.add(body);
            }
        }
        if (synced.isEmpty() && views.isEmpty()) {
            return;
        }
        players.clear();
        for (ServerPlayer player : level.players()) {
            if (player.connection.hasChannel(BodySyncPayload.TYPE)) {
                players.add(player);
            }
        }
        ChunkMap chunks = level.getChunkSource().chunkMap;
        Vector3d position = new Vector3d();
        ContactPoints contacts = new ContactPoints();
        Filter filter = new Filter();
        for (Body body : synced) {
            Synced s = body.attachment(Synced.class);
            if (s == null) {
                s = new Synced(nextId++);
                body.attach(Synced.class, s);
            }
            s.changed = s.update(body, contacts, filter);
            byId.put(s.id, body);
            body.position(position);
            int cx = SectionPos.blockToSectionCoord(position.x), cz = SectionPos.blockToSectionCoord(position.z);
            long chunk = ChunkPos.asLong(cx, cz);
            boolean loaded = chunks.getChunkToSend(chunk) != null;
            for (ServerPlayer player : players) {
                if (!player.getChunkTrackingView().contains(cx, cz)) {
                    continue;
                }
                // ⚠️ not ChunkMap.getPlayers: it counts a chunk in view but not loaded yet as the client's, then not
                // while it waits to be sent, then again — a body there (unlike an entity, a body may be in a chunk not
                // loaded) would come, go and come. So: seen from when its chunk has reached the client until it leaves
                // the view.
                View view = views.get(player.connection);
                if (view != null && view.known.containsKey(s.id)
                        || loaded && !player.connection.chunkSender.isPending(chunk)) {
                    (view != null ? view : views.computeIfAbsent(player.connection, View::new)).visible.put(s.id, body);
                }
            }
        }
        // a player gone, or now elsewhere, has a client that dropped everything of this level (forget() does it at
        // once, from the player events; this is for a level that missed one)
        views.values()
                .removeIf(view -> view.connection.player.isRemoved() || view.connection.player.serverLevel() != level);
        Prediction predicting = prediction;
        for (var it = views.values().iterator(); it.hasNext(); ) {
            View view = it.next();
            ServerPlayer player = view.connection.player;
            int frame = predicting == null ? BodySyncPayload.NO_FRAME : predicting.frameOf(player);
            Vec3 centre = frame == BodySyncPayload.NO_FRAME ? null : predicting.centreOf(player);
            Body own = predicting == null ? null : predicting.ownBodyOf(player);
            BodySyncPayload payload = view.update(physics.ticks(), frame, centre, own);
            if (payload != null) {
                PacketDistributor.sendToPlayer(view.connection.player, payload);
            }
            if (view.known.isEmpty()) {
                it.remove(); // made again when it next sees a body
            }
        }
    }

    /**
     * Forgets what a player's client knew of every level's synced bodies — of every level but its current one with
     * {@code everywhere} false. Called on logging out (everywhere), changing dimension and respawning: a respawn in the
     * same dimension keeps the client's level, and the replicas in it.
     */
    @ApiStatus.Internal
    public static void forget(ServerPlayer player, boolean everywhere) {
        for (LevelPhysics physics : LevelPhysics.all()) {
            if (physics.level() instanceof ServerLevel level && (everywhere || level != player.serverLevel())) {
                physics.sync().views.remove(player.connection);
            }
        }
    }

    /** What one client knows of this level's synced bodies. */
    private static final class View {
        final ServerGamePacketListenerImpl connection;
        /** network id → the shapes revision it was sent */
        final Int2IntOpenHashMap known = new Int2IntOpenHashMap();
        /** network id → the damping, gravity scale and fixed rotation it was sent ({@link Synced#motion}) */
        final Int2IntOpenHashMap knownMotion = new Int2IntOpenHashMap();
        /** what it should see this tick */
        final Int2ObjectOpenHashMap<Body> visible = new Int2ObjectOpenHashMap<>();

        View(ServerGamePacketListenerImpl connection) {
            this.connection = connection;
        }

        /**
         * What changed for this client this tick; round {@code centre} (its predicted body), every body, moved or not,
         * its client holding each against where it ran it to; {@code own}, the body its client predicts as its own.
         */
        @Nullable
        BodySyncPayload update(long tick, int frame, @Nullable Vec3 centre, @Nullable Body own) {
            IntArrayList removals = null;
            List<BodySyncPayload.Spawn> spawns = null;
            List<BodySyncPayload.State> states = null;
            List<BodySyncPayload.Tuning> tunings = null;
            Vector3d at = new Vector3d();
            for (var it = known.int2IntEntrySet().fastIterator(); it.hasNext(); ) {
                var entry = it.next();
                Body body = visible.get(entry.getIntKey());
                if (body == null || revision(body) != entry.getIntValue()) {
                    (removals == null ? removals = new IntArrayList() : removals).add(entry.getIntKey());
                    knownMotion.remove(entry.getIntKey());
                    it.remove();
                }
            }
            for (var entry : visible.int2ObjectEntrySet()) {
                int id = entry.getIntKey();
                Body body = entry.getValue();
                Synced s = body.attachment(Synced.class);
                if (!known.containsKey(id)) {
                    (spawns == null ? spawns = new ArrayList<>() : spawns).add(spawn(id, body, s, own));
                    known.put(id, revision(body));
                    knownMotion.put(id, s.motion);
                    continue;
                }
                if (s.motion != knownMotion.get(id) && body.type() == BodyType.DYNAMIC) {
                    knownMotion.put(id, s.motion);
                    (tunings == null ? tunings = new ArrayList<>() : tunings)
                            .add(new BodySyncPayload.Tuning(
                                    id,
                                    BodySyncPayload.Dynamics.of(
                                            body.massData(new MassData()), body, EntityCollisions.pushable(body))));
                }
                if (s.changed
                        || centre != null
                                && body.position(at).distanceSquared(centre.x, centre.y, centre.z)
                                        <= PREDICTED_RADIUS * PREDICTED_RADIUS) {
                    (states == null ? states = new ArrayList<>() : states).add(seenBy(s.state, body, own));
                }
            }
            visible.clear();
            if (removals == null && spawns == null && states == null && tunings == null) {
                return null;
            }
            return new BodySyncPayload(
                    spawns == null ? List.of() : spawns,
                    states == null ? List.of() : states,
                    removals == null ? new int[0] : removals.toIntArray(),
                    false,
                    tick,
                    frame,
                    tunings == null ? List.of() : tunings);
        }

        /** {@code state} for the client predicting {@code own}: less the joints {@code own} holds this body by. */
        private static BodySyncPayload.State seenBy(BodySyncPayload.State state, Body body, @Nullable Body own) {
            int theirs = own == null || own == body || state.joints() == 0 ? 0 : jointsBetween(body, own);
            return theirs == 0
                    ? state
                    : new BodySyncPayload.State(
                            state.id(),
                            state.x(),
                            state.y(),
                            state.z(),
                            state.qx(),
                            state.qy(),
                            state.qz(),
                            state.qw(),
                            state.vx(),
                            state.vy(),
                            state.vz(),
                            state.wx(),
                            state.wy(),
                            state.wz(),
                            state.whole(),
                            Math.max(0, state.joints() - theirs));
        }

        private static BodySyncPayload.Spawn spawn(int id, Body body, Synced s, @Nullable Body own) {
            List<BodySyncPayload.SyncedShape> shapes = new ArrayList<>();
            Filter filter = new Filter();
            SurfaceMaterial surface = new SurfaceMaterial();
            for (Shape shape : body.shapes()) {
                shape.filter(filter);
                BodySyncPayload.SyncedShape synced = BodySyncPayload.SyncedShape.of(
                        shape.geometry(),
                        filter.categoryBits,
                        filter.maskBits,
                        filter.groupIndex,
                        shape.isSensor(),
                        shape.surfaceMaterial(surface),
                        EntityCollisions.stepUpOn(shape));
                if (synced != null) {
                    shapes.add(synced);
                } else if (!s.warned) {
                    s.warned = true;
                    MineBox3D.LOGGER.warn(
                            "a synced body ({}) has a {} shape, which is not sent to clients",
                            body.name(),
                            shape.geometry().getClass().getSimpleName());
                }
            }
            MassData data = body.type() == BodyType.DYNAMIC ? body.massData(new MassData()) : null;
            BodySyncPayload.Dynamics dynamics =
                    data == null ? null : BodySyncPayload.Dynamics.of(data, body, EntityCollisions.pushable(body));
            Vector3f centre = data == null ? null : new Vector3f(data.centerX, data.centerY, data.centerZ);
            return new BodySyncPayload.Spawn(
                    id,
                    body.name(),
                    body.userData(),
                    body.tag(),
                    body.ignoredTag(),
                    shapes,
                    seenBy(s.state, body, own),
                    dynamics,
                    centre);
        }

        /**
         * What a replica is made from changes with: its shapes, and its type and mass — the centre of mass a point's
         * speed turns about. A change sends it again; how it moves besides goes as a {@link BodySyncPayload.Tuning}.
         */
        private static int revision(Body body) {
            return 31 * body.shapesRevision() + body.massRevision();
        }
    }

    /** How many of {@code body}'s joints hold it to {@code other}. */
    private static int jointsBetween(Body body, Body other) {
        int count = 0;
        for (var joint : body.joints()) {
            if (joint.bodyA() == other || joint.bodyB() == other) {
                count++;
            }
        }
        return count;
    }

    /** Apart by more than this, metres, a contact is not touching: Box3D keeps contacts a little way out. */
    private static final float TOUCHING = 0.01f;

    /** On a synced server body: its number and what was last sent of it. */
    private static final class Synced {
        final int id;
        boolean changed, warned;

        @Nullable
        BodySyncPayload.State state;
        /** Whether all its shapes are sent, as of {@link #shapesRevision} */
        boolean complete;

        int shapesRevision = Integer.MIN_VALUE;
        /** Damping, gravity scale and fixed rotation, hashed; a change is sent as a {@link BodySyncPayload.Tuning} */
        int motion;

        Synced(int id) {
            this.id = id;
        }

        /**
         * Reads the body; whether it moved, changed speed or what a client needs to simulate it (a body that stops is
         * said to).
         */
        boolean update(Body body, ContactPoints contacts, Filter filter) {
            if (shapesRevision != body.shapesRevision()) {
                shapesRevision = body.shapesRevision();
                complete =
                        body.shapes().stream().allMatch(shape -> BodySyncPayload.SyncedShape.isSent(shape.geometry()));
            }
            boolean dynamic = body.type() == BodyType.DYNAMIC;
            motion = !dynamic
                    ? 0
                    : 31
                                    * (31
                                                    * (31 * Float.floatToIntBits(body.linearDamping())
                                                            + Float.floatToIntBits(body.angularDamping()))
                                            + Float.floatToIntBits(body.gravityScale()))
                            + (body.isFixedRotation() ? 1 : 0);
            Vector3d p = body.position(new Vector3d());
            Quaternionf q = body.rotation(new Quaternionf());
            Vector3f v = body.linearVelocity(new Vector3f());
            Vector3f w = body.angularVelocity(new Vector3f());
            boolean whole = !dynamic || touchesOnlyWhatClientsHave(body, contacts, filter);
            BodySyncPayload.State now = new BodySyncPayload.State(
                    id,
                    p.x,
                    p.y,
                    p.z,
                    q.x,
                    q.y,
                    q.z,
                    q.w,
                    v.x,
                    v.y,
                    v.z,
                    w.x,
                    w.y,
                    w.z,
                    whole,
                    dynamic ? body.joints().size() : 0);
            boolean moved = !now.equals(state);
            state = now;
            return moved;
        }

        /**
         * Whether a client simulating the body has all it touches: terrain, an entity's proxy or a synced body sent
         * whole. A body resting on one the server keeps to itself would fall through on the client.
         */
        private static boolean touchesOnlyWhatClientsHave(Body body, ContactPoints contacts, Filter filter) {
            int count = body.contactPoints(contacts);
            for (int i = 0; i < count; i++) {
                if (contacts.separation(i) > TOUCHING || contacts.isTerrain(i)) {
                    continue;
                }
                Shape other = contacts.otherShape(i);
                if (other == null) {
                    return false;
                }
                if ((other.filter(filter).categoryBits & CollisionCategories.ENTITY) != 0) {
                    continue; // an entity's proxy: a client has the entity, and its own proxy of it
                }
                Synced synced = other.body().attachment(Synced.class);
                if (synced == null || !synced.complete) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * On a client replica: its number, the states waiting, the server's velocity as of the last one taken, and its
     * mass and centre of mass if the server body is dynamic.
     */
    private static final class Replica {
        final int id;
        final ArrayDeque<BodySyncPayload.State> queue = new ArrayDeque<>(MAX_QUEUED + 1);
        int coasting;
        /** Simulated here, round what predicts, rather than driven to the server's states */
        boolean simulated;

        float vx, vy, vz, wx, wy, wz;
        /** As of the last state taken: whether all it touches is here too */
        boolean whole = true;

        /** As of the last state taken: how many joints hold it on the server */
        int joints;

        @Nullable
        BodySyncPayload.Dynamics dynamics;

        @Nullable
        Vector3f centre;

        Replica(int id) {
            this.id = id;
        }

        void take(BodySyncPayload.State s) {
            vx = s.vx();
            vy = s.vy();
            vz = s.vz();
            wx = s.wx();
            wy = s.wy();
            wz = s.wz();
            whole = s.whole();
            joints = s.joints();
        }

        /**
         * Whether a client can simulate it as the server does: what it touches is here, and it is held here by as many
         * joints as there — the client's own copies of them (an EntityStudio constraint is made on both ends) — not
         * counting those its own body {@code own} holds it by, which the server's count leaves out too.
         */
        boolean reproducible(Body body, @Nullable Body own) {
            if (dynamics == null || !whole) {
                return false;
            }
            int here = body.joints().size() - (own == null ? 0 : jointsBetween(body, own));
            return here >= joints;
        }
    }
}
