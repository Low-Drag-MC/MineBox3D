package com.lowdragmc.minebox3d.mc.sync;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.CollisionCategories;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.ContactPoints;
import com.lowdragmc.minebox3d.physics.Filter;
import com.lowdragmc.minebox3d.physics.MassData;
import com.lowdragmc.minebox3d.physics.Overlaps;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.QueryFilter;
import com.lowdragmc.minebox3d.physics.Shape;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.SurfaceMaterial;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Bodies copied from the server to the clients of the players near them</b> (design doc
 * {@code 04-entities-and-bodies.md} §10). One per {@link LevelPhysics}, on both sides.
 *
 * <p>On the server, at the end of each tick ({@link #afterTick()}): every body
 * {@linkplain LevelBodies#setSynced synced} in the level's own physics is offered to the players whose clients have its
 * chunk: from when the chunk has been sent to them until it leaves their view. Each gets one {@link BodySyncPayload} a
 * tick with what changed for it: the bodies it starts seeing (their shapes, name, user data and where they are), the
 * ones it sees that moved or changed speed, the ones it stops seeing. A body whose shapes changed is taken away and
 * sent again. What a client knows is kept per <b>connection</b>, not per player object: a respawn in the same dimension
 * makes a new player but keeps the client's level, and with it the replicas.
 *
 * <p>On the client: each body arrives as a <b>replica</b>, a kinematic body in the client level's physics, driven a
 * tick at a time to where the server said ({@link #beforeSteps}) and drawn between ticks like any other body. It does
 * not simulate — save one this client pushes ({@link #predict}), simulated here until the server has the push. States
 * wait in a short queue, one taken a tick, so a tick with two states and a tick with none (the two sides' ticks are not
 * in step, and the network jitters) do not make it stop and lunge; with none left it carries on at the server's velocity
 * for a tick or two. {@link SyncedBodyEvent} says when one comes and goes.
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
        var registrar = event.registrar("2").optional();
        registrar.playToClient(BodySyncPayload.TYPE, BodySyncPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> receive(payload, context.player().level())));
        registrar.playToClient(RiderPayload.TYPE, RiderPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> SyncedRiders.receive(payload, context.player().level())));
        registrar.playToServer(StandingPayload.TYPE, StandingPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        SyncedRiders.receive(payload, player);
                    }
                }));
        registrar.playToServer(PushPayload.TYPE, PushPayload.CODEC,
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
     * Whether this client is simulating the replica itself for now ({@link #predict}): a dynamic body here, not driven by
     * the server's states.
     */
    public static boolean isPredicting(Body body) {
        Replica r = body.attachment(Replica.class);
        return r != null && r.predicting;
    }

    /**
     * How fast the server's body was moving as of the state the replica was last driven by, m/s, world space (the
     * replica's own velocity is how it is driven, which lags by the network); for one this client predicts, how fast it
     * simulates it moving. Zero for anything that is not a replica.
     */
    public static Vector3f serverVelocity(Body replica, Vector3f out) {
        Replica r = replica.attachment(Replica.class);
        if (r != null && r.predicting) {
            return replica.linearVelocity(out);
        }
        return r == null ? out.zero() : out.set(r.vx, r.vy, r.vz);
    }

    /** The same for the turn, rad/s. */
    public static Vector3f serverAngularVelocity(Body replica, Vector3f out) {
        Replica r = replica.attachment(Replica.class);
        if (r != null && r.predicting) {
            return replica.angularVelocity(out);
        }
        return r == null ? out.zero() : out.set(r.wx, r.wy, r.wz);
    }

    /**
     * How fast the server's body moves at a world point, as the replica last heard — what the server's
     * {@link Body#pointVelocity} answers there: the velocity of the centre of mass plus the turn's about it, m/s. The
     * centre is the server body's for a dynamic one (sent with it, and again when its type or mass is set), the body's
     * origin otherwise — which a kinematic or static body's is. For one this client predicts, its own. Zero for anything
     * that is not a replica.
     */
    public static Vector3f serverPointVelocity(Body replica, double x, double y, double z, Vector3f out) {
        Replica r = replica.attachment(Replica.class);
        if (r == null) {
            return out.zero();
        }
        if (r.predicting) {
            return replica.pointVelocity(x, y, z, out);
        }
        Vector3d center = replica.position(new Vector3d());
        if (r.centre != null) {
            center.add(replica.rotation(new Quaternionf()).transform(new Vector3d(r.centre.x, r.centre.y, r.centre.z)));
        }
        double rx = x - center.x, ry = y - center.y, rz = z - center.z;
        return out.set((float) (r.vx + r.wy * rz - r.wz * ry), (float) (r.vy + r.wz * rx - r.wx * rz),
                (float) (r.vz + r.wx * ry - r.wy * rx));
    }

    /**
     * The server body's mass, into {@code out}, if entities push it (doc 04 §11.2 #1): what an entity walking into the
     * replica pushes against, whether it is driven or simulated here. False for any other body, and a replica of one
     * entities do not push.
     */
    public static boolean serverMass(Body replica, MassData out) {
        Replica r = replica.attachment(Replica.class);
        if (r == null || r.dynamics == null || !r.dynamics.pushedByEntities()) {
            return false;
        }
        r.dynamics.into(out);
        return true;
    }

    /** Whether the body is a replica of one the server simulates: one this client may simulate too ({@link #predict}). */
    public static boolean isDynamicOnServer(Body body) {
        Replica r = body.attachment(Replica.class);
        return r != null && r.dynamics != null;
    }

    /**
     * <b>Simulates the replica on this client</b> (doc 04 §16) from now until the server's states have had time to show
     * what this client did to it: what moves here pushed it — its own player, what that steers, or a mod's interaction —
     * and waiting a round trip for the server to move it out of the way would stop that against where it was. A dynamic
     * body here meanwhile, with the server body's mass, surface, damping and gravity scale, in terrain, pushed by the
     * entity proxies, the bodies it runs into simulated with it; the server's states reconciled with it as they come; then
     * driven to the server's states again ({@link #beforeSteps}). Asked again, the wait starts over. Client only.
     *
     * @return false for a replica of a body the server does not simulate, and while the server has it touch what this
     *         client lacks ({@link BodySyncPayload.State#whole}) — it stays as the server has it
     */
    public boolean predict(Body replica) {
        return predict(replica, physics.ticks() + predictionTicks());
    }

    /**
     * {@link #predict}, the wait lasting till {@code until} at the least: a body run into by one simulated here lasts as
     * long as that one does — not a wait of its own, which two touching would hand each other for ever.
     */
    private boolean predict(Body replica, long until) {
        Replica r = replica.attachment(Replica.class);
        if (r == null || r.dynamics == null || r.last == null || !r.last.whole() || !replica.isValid()) {
            return false;
        }
        r.predictUntil = Math.max(r.predicting ? r.predictUntil : 0, until);
        if (!r.predicting) {
            r.predicting = true;
            r.forgetTrail();
            Vector3f v = replica.linearVelocity(new Vector3f());
            Vector3f w = replica.angularVelocity(new Vector3f());
            replica.setType(BodyType.DYNAMIC);
            replica.setMassData(r.dynamics.into(new MassData()));
            replica.setLinearDamping(r.dynamics.linearDamping());
            replica.setAngularDamping(r.dynamics.angularDamping());
            replica.setGravityScale(r.dynamics.gravityScale());
            replica.setLinearVelocity(v.x, v.y, v.z);
            replica.setAngularVelocity(w.x, w.y, w.z);
            LevelBodies.setNeedsTerrain(replica, true);
            LevelBodies.setPushedByEntities(replica, r.dynamics.pushedByEntities());
        }
        return true;
    }

    /**
     * Ticks a prediction lasts past the last push: the round trip, the replica's queue of states, and the server's wait
     * for the moves that pushed it — after that the states coming in have the push in them. Handed back only once they
     * agree, too ({@link #stepPrediction}): this is the least.
     */
    private static int predictionTicks() {
        return roundTripTicks + MAX_QUEUED + 3;
    }

    /** Ticks past {@link #predictionTicks} a replica the server's states never agree with is handed back at anyway. */
    private static final int HAND_BACK_LATEST = 60;
    /** Metres, m/s: the server's newest state this near where a replica simulated here is, and as fast, agrees with it. */
    private static final double AGREES = 0.05, AGREES_SPEED = 0.1;
    /**
     * Metres a replica simulated here may be from the server's word on it before it is moved to agree — beyond what three
     * ticks at its speed make of the round trip being an estimate.
     */
    private static final double RECONCILE = 0.25;
    /** Ticks the server holds a client's moves before running them (EntityStudio's queue aims at two), at the least. */
    private static final int SERVER_WAIT = 1;
    /** Ticks of where a replica simulated here was, kept to put the server's word beside. */
    private static final int TRAIL = 64;

    /** This client's round trip to the server, in ticks: set each tick by the client. */
    private static volatile int roundTripTicks = 2;

    @ApiStatus.Internal
    public static void setRoundTrip(int milliseconds) {
        roundTripTicks = Math.max(1, Math.min(40, (milliseconds + 49) / 50));
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
        if (payload.spawns().isEmpty() && payload.states().isEmpty() && payload.removals().length == 0) {
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
            }
            for (BodySyncPayload.Spawn spawn : payload.spawns()) {
                remove(level, spawn.id(), events, gone);
                Body body = build(world, spawn);
                if (body != null) {
                    replicas.put(spawn.id(), body);
                    events.add(new SyncedBodyEvent.Added(level, body, spawn.id()));
                }
            }
            for (BodySyncPayload.State state : payload.states()) {
                Body body = replicas.get(state.id());
                Replica replica = body == null ? null : body.attachment(Replica.class);
                if (replica != null && state.isUsable()) {
                    replica.queue.add(state);
                    while (replica.queue.size() > MAX_QUEUED) {
                        replica.queue.poll();
                    }
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
        Body body = world.createBody(new BodyDef().type(BodyType.KINEMATIC).position(s.x(), s.y(), s.z()).rotation(rotation)
                .name(spawn.name()).userData(spawn.userData()).enableSleep(false));
        try {
            // nothing of the client's own runs off a replica: it brings no terrain and no entity proxies
            LevelBodies.setNeedsTerrain(body, false);
            LevelBodies.setPushedByEntities(body, false);
            // what the server's body never meets — an entity's tag is its id, the same on both sides; before the shapes,
            // so none has pairs to find again
            body.setTag(spawn.tag());
            body.setIgnoredTag(spawn.ignoredTag());
            for (BodySyncPayload.SyncedShape shape : spawn.shapes()) {
                ShapeDef def = new ShapeDef().sensor(shape.sensor());
                def.filter.set(new Filter(shape.categoryBits(), shape.maskBits(), shape.groupIndex()));
                shape.surface(def.baseMaterial);
                body.createShape(def, shape.geometry());
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
     * On the client, before the tick's steps, holding the world: each replica driven over the tick to the next state the
     * server sent; with none, on along the server's velocity for a tick or two (the state is late), then held still. One
     * this client predicts ({@link #predict}) simulates instead ({@link #stepPrediction}); handed back, it is driven from
     * where it got to toward the server's newest state.
     */
    @ApiStatus.Internal
    public void beforeSteps(float timeStep) {
        Vector3d position = new Vector3d();
        Quaternionf rotation = new Quaternionf();
        for (Body body : replicas.values()) {
            Replica r = body.isValid() ? body.attachment(Replica.class) : null;
            if (r == null) {
                continue;
            }
            BodySyncPayload.State next;
            if (r.predicting) {
                if (stepPrediction(body, r)) {
                    continue;
                }
                next = r.last; // handed back: a resting server body sends nothing more, so its last word
            } else {
                next = r.queue.poll();
                if (next != null) {
                    r.take(next);
                }
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
                body.setTargetTransform(position.x + r.vx * timeStep, position.y + r.vy * timeStep,
                        position.z + r.vz * timeStep, body.rotation(rotation), timeStep);
            } else {
                body.setTargetTransform(position.x, position.y, position.z, body.rotation(rotation), timeStep);
            }
        }
        // a replica someone else destroyed is let go (made again only if the server sends it again)
        replicas.values().removeIf(body -> !body.isValid());
    }

    /**
     * One tick of a replica simulated here: the server's states reconciled with where it had the body as they come
     * ({@link #reconcile}); the bodies it is about to run into simulated with it, as the server's run into each other; past
     * the wait, handed back once the server's newest word agrees with where it is — a resting body, its push shown — or
     * {@link #HAND_BACK_LATEST} on regardless; at once if the server has it touch what this client lacks.
     *
     * @return whether it is still simulated here
     */
    private boolean stepPrediction(Body body, Replica r) {
        long now = physics.ticks();
        r.remember(now, body);
        for (BodySyncPayload.State s; (s = r.queue.poll()) != null; ) {
            r.take(s);
            reconcile(body, r, s, now);
        }
        if (r.last == null || !r.last.whole()
                || now >= r.predictUntil && (agrees(body, r.last) || now >= r.predictUntil + HAND_BACK_LATEST)) {
            handBack(body, r);
            return false;
        }
        if (now < r.predictUntil) {
            predictWhatItMeets(body, r.predictUntil); // past its wait it only waits to agree: it drags none in
        }
        return true;
    }

    /**
     * The server's word on a body simulated here beside where this client had it as many ticks ago as the word takes to
     * show what this client did. Further apart than {@link #RECONCILE}, and than three ticks at its speed (the round trip is
     * an estimate), the server's body met what this one did not — another player, a push the server refused — and this one
     * is moved by the difference, at the server's speed.
     */
    private static void reconcile(Body body, Replica r, BodySyncPayload.State s, long now) {
        double[] then = r.at(now - roundTripTicks - SERVER_WAIT);
        if (then == null) {
            return;
        }
        double dx = s.x() - then[0], dy = s.y() - then[1], dz = s.z() - then[2];
        double allowed = RECONCILE + 3 * body.linearVelocity(new Vector3f()).length() / 20;
        if (dx * dx + dy * dy + dz * dz <= allowed * allowed) {
            return;
        }
        Vector3d at = body.position(new Vector3d());
        // the turn put right the same way: the server's, and what this one turned since
        Quaternionf turned = new Quaternionf((float) then[3], (float) then[4], (float) then[5], (float) then[6]).conjugate()
                .premul(body.rotation(new Quaternionf()));
        Quaternionf rotation = new Quaternionf(s.qx(), s.qy(), s.qz(), s.qw()).normalize().premul(turned).normalize();
        body.setTransform(at.x + dx, at.y + dy, at.z + dz, rotation);
        body.setLinearVelocity(s.vx(), s.vy(), s.vz());
        body.setAngularVelocity(s.wx(), s.wy(), s.wz());
        r.forgetTrail();
    }

    private static boolean agrees(Body body, BodySyncPayload.State s) {
        Vector3d at = body.position(new Vector3d());
        Vector3f v = body.linearVelocity(new Vector3f());
        return at.distanceSquared(s.x(), s.y(), s.z()) <= AGREES * AGREES
                && v.distanceSquared(s.vx(), s.vy(), s.vz()) <= AGREES_SPEED * AGREES_SPEED;
    }

    /**
     * The replicas of bodies the server simulates within a tick's travel of a body simulated here: simulated too, as long
     * as it is ({@code until}), so it runs into them as the server's run into each other, rather than into one held where
     * the server last had it.
     */
    private void predictWhatItMeets(Body body, long until) {
        float[] bounds = body.bounds(new float[6]);
        float reach = body.linearVelocity(new Vector3f()).length() / 20 + MEETS;
        int count = physics.world().overlapAABB(bounds[0] - reach, bounds[1] - reach, bounds[2] - reach,
                bounds[3] + reach, bounds[4] + reach, bounds[5] + reach, QueryFilter.ALL, overlaps);
        for (int i = 0; i < count; i++) {
            Shape shape = overlaps.shape(i);
            if (shape != null && shape.isValid() && shape.body() != body && isDynamicOnServer(shape.body())) {
                predict(shape.body(), until);
            }
        }
    }

    /** Metres beyond a tick's travel a body simulated here looks for others to simulate with it. */
    private static final float MEETS = 0.05f;
    private final Overlaps overlaps = new Overlaps(8);

    /** A replica simulated here the server's again: kinematic, out of the terrain and the proxies' way, as it was built. */
    private static void handBack(Body body, Replica r) {
        r.predicting = false;
        body.setType(BodyType.KINEMATIC);
        LevelBodies.setNeedsTerrain(body, false);
        LevelBodies.setPushedByEntities(body, false);
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
     * Whether a synced body the player's client is to have, but has not been sent yet, is around the player — a block or
     * two round its bounds, its chunk in the player's view. Server only, as of the last tick.
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
            if (!player.getChunkTrackingView().contains(SectionPos.blockToSectionCoord(position.x),
                    SectionPos.blockToSectionCoord(position.z))) {
                continue; // it is not this client's to have
            }
            body.bounds(bounds);
            // the bounds are floats: a block or two coarse far out, and the player may be just off the edge
            if (player.getX() > bounds[0] - 2 && player.getX() < bounds[3] + 2 && player.getY() > bounds[1] - 3
                    && player.getY() < bounds[4] + 2 && player.getZ() > bounds[2] - 2 && player.getZ() < bounds[5] + 2) {
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
                // ⚠️ not ChunkMap.getPlayers: it counts a chunk in view but not loaded yet as the client's, then not while
                // it waits to be sent, then again — a body there (which, unlike an entity, may be in a chunk not loaded)
                // would come, go and come. So: seen from when its chunk has reached the client, until it leaves the view.
                View view = views.get(player.connection);
                if (view != null && view.known.containsKey(s.id)
                        || loaded && !player.connection.chunkSender.isPending(chunk)) {
                    (view != null ? view : views.computeIfAbsent(player.connection, View::new)).visible.put(s.id, body);
                }
            }
        }
        // a player gone, or now elsewhere, has a client that dropped everything of this level (forget() does it at once,
        // from the player events; this is for a level that missed one)
        views.values().removeIf(view -> view.connection.player.isRemoved() || view.connection.player.serverLevel() != level);
        for (var it = views.values().iterator(); it.hasNext(); ) {
            View view = it.next();
            BodySyncPayload payload = view.update();
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
        /** what it should see this tick */
        final Int2ObjectOpenHashMap<Body> visible = new Int2ObjectOpenHashMap<>();

        View(ServerGamePacketListenerImpl connection) {
            this.connection = connection;
        }

        @Nullable
        BodySyncPayload update() {
            IntArrayList removals = null;
            List<BodySyncPayload.Spawn> spawns = null;
            List<BodySyncPayload.State> states = null;
            for (var it = known.int2IntEntrySet().fastIterator(); it.hasNext(); ) {
                var entry = it.next();
                Body body = visible.get(entry.getIntKey());
                if (body == null || revision(body) != entry.getIntValue()) {
                    (removals == null ? removals = new IntArrayList() : removals).add(entry.getIntKey());
                    it.remove();
                }
            }
            for (var entry : visible.int2ObjectEntrySet()) {
                int id = entry.getIntKey();
                Body body = entry.getValue();
                Synced s = body.attachment(Synced.class);
                if (!known.containsKey(id)) {
                    (spawns == null ? spawns = new ArrayList<>() : spawns).add(spawn(id, body, s));
                    known.put(id, revision(body));
                } else if (s.changed) {
                    (states == null ? states = new ArrayList<>() : states).add(s.state);
                }
            }
            visible.clear();
            if (removals == null && spawns == null && states == null) {
                return null;
            }
            return new BodySyncPayload(spawns == null ? List.of() : spawns, states == null ? List.of() : states,
                    removals == null ? new int[0] : removals.toIntArray());
        }

        private static BodySyncPayload.Spawn spawn(int id, Body body, Synced s) {
            List<BodySyncPayload.SyncedShape> shapes = new ArrayList<>();
            Filter filter = new Filter();
            SurfaceMaterial surface = new SurfaceMaterial();
            for (Shape shape : body.shapes()) {
                shape.filter(filter);
                BodySyncPayload.SyncedShape synced = BodySyncPayload.SyncedShape.of(shape.geometry(), filter.categoryBits,
                        filter.maskBits, filter.groupIndex, shape.isSensor(), shape.surfaceMaterial(surface));
                if (synced != null) {
                    shapes.add(synced);
                } else if (!s.warned) {
                    s.warned = true;
                    MineBox3D.LOGGER.warn("a synced body ({}) has a {} shape, which is not sent to clients", body.name(),
                            shape.geometry().getClass().getSimpleName());
                }
            }
            MassData data = body.type() == BodyType.DYNAMIC ? body.massData(new MassData()) : null;
            BodySyncPayload.Dynamics dynamics = data == null ? null : BodySyncPayload.Dynamics.of(data,
                    body.linearDamping(), body.angularDamping(), body.gravityScale(), EntityCollisions.pushable(body));
            Vector3f centre = data == null ? null : new Vector3f(data.centerX, data.centerY, data.centerZ);
            return new BodySyncPayload.Spawn(id, body.name(), body.userData(), body.tag(), body.ignoredTag(), shapes,
                    s.state, dynamics, centre);
        }

        /**
         * What a replica is made from changes with: its shapes, and its type and mass — the centre of mass a point's speed
         * turns about. A change sends it again.
         */
        private static int revision(Body body) {
            return 31 * body.shapesRevision() + body.massRevision();
        }
    }

    /** On a synced server body: its number and what was last sent of it. */
    private static final class Synced {
        final int id;
        boolean changed, warned;
        @Nullable
        BodySyncPayload.State state;
        /** every shape is one a replica is made with (no mesh, no height field), as of {@link #shapesRevision} */
        boolean complete;
        int shapesRevision = -1;

        Synced(int id) {
            this.id = id;
        }

        /**
         * Reads the body; whether it moved, changed speed or came to touch what a client lacks since the last tick (a body
         * that stops is said to).
         */
        boolean update(Body body, ContactPoints contacts, Filter filter) {
            if (shapesRevision != body.shapesRevision()) {
                shapesRevision = body.shapesRevision();
                complete = body.shapes().stream().allMatch(shape -> BodySyncPayload.SyncedShape.isSent(shape.geometry()));
            }
            Vector3d p = body.position(new Vector3d());
            Quaternionf q = body.rotation(new Quaternionf());
            Vector3f v = body.linearVelocity(new Vector3f());
            Vector3f w = body.angularVelocity(new Vector3f());
            boolean whole = body.type() != BodyType.DYNAMIC || touchesOnlyWhatClientsHave(body, contacts, filter);
            BodySyncPayload.State now = new BodySyncPayload.State(id, p.x, p.y, p.z, q.x, q.y, q.z, q.w, v.x, v.y, v.z,
                    w.x, w.y, w.z, whole);
            boolean moved = !now.equals(state);
            state = now;
            return moved;
        }

        /**
         * Whether a client simulating the body has everything holding it: what it touches is terrain, an entity's proxy or
         * a synced body sent whole — a client has those — and no joint holds it (joints are not sent).
         */
        private static boolean touchesOnlyWhatClientsHave(Body body, ContactPoints contacts, Filter filter) {
            if (!body.joints().isEmpty()) {
                return false;
            }
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

    /** Apart by more than this, metres, a contact is not touching: Box3D keeps contacts a little way out. */
    private static final float TOUCHING = 0.01f;

    /**
     * On a client replica: its number, the states waiting, the last one taken and the server's velocity as of it, how the
     * server body moves if it is dynamic, its centre of mass if so; and while simulated here, till when and where it was.
     */
    private static final class Replica {
        final int id;
        final ArrayDeque<BodySyncPayload.State> queue = new ArrayDeque<>(MAX_QUEUED + 1);
        int coasting;
        float vx, vy, vz, wx, wy, wz;
        @Nullable
        BodySyncPayload.State last;
        @Nullable
        BodySyncPayload.Dynamics dynamics;
        /** simulated here ({@link #predict}), till the level's physics reaches {@link #predictUntil} ticks at the least */
        boolean predicting;
        long predictUntil;
        @Nullable
        Vector3f centre;
        /** where it was simulated to, a tick a row (x, y, z, qx, qy, qz, qw), by the tick's number */
        final double[] trail = new double[TRAIL * 7];
        final long[] trailTicks = new long[TRAIL];

        Replica(int id) {
            this.id = id;
            forgetTrail();
        }

        void take(BodySyncPayload.State s) {
            last = s;
            vx = s.vx();
            vy = s.vy();
            vz = s.vz();
            wx = s.wx();
            wy = s.wy();
            wz = s.wz();
        }

        void remember(long tick, Body body) {
            int row = (int) Math.floorMod(tick, (long) TRAIL);
            Vector3d at = body.position(new Vector3d());
            Quaternionf q = body.rotation(new Quaternionf());
            trailTicks[row] = tick;
            int i = row * 7;
            trail[i] = at.x;
            trail[i + 1] = at.y;
            trail[i + 2] = at.z;
            trail[i + 3] = q.x;
            trail[i + 4] = q.y;
            trail[i + 5] = q.z;
            trail[i + 6] = q.w;
        }

        /** Where it was at that tick, or {@code null} if not remembered. */
        @Nullable
        double[] at(long tick) {
            int row = (int) Math.floorMod(tick, (long) TRAIL);
            if (tick < 0 || trailTicks[row] != tick) {
                return null;
            }
            return java.util.Arrays.copyOfRange(trail, row * 7, row * 7 + 7);
        }

        void forgetTrail() {
            java.util.Arrays.fill(trailTicks, -1);
        }
    }
}
