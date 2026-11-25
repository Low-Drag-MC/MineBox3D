package com.lowdragmc.minebox3d.mc.sync;

import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityAuthority;
import com.lowdragmc.minebox3d.mc.entity.EntityBases;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import com.lowdragmc.minebox3d.physics.Body;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>Entities on synced bodies, over the network</b> (design doc {@code 04-entities-and-bodies.md} §11.2 #1 / #3):
 * where they stand is told in the body's frame, both ways, so that what stands on a moving body moves with it on every
 * side however late the body's replica runs. One per {@link LevelPhysics}, through {@link SyncedBodies#riders()}; the
 * server's "where each client says it stands" and its holds are the server's, across levels.
 *
 * <p><b>Client to server, where it stands</b> ({@link StandingPayload}): a client carries its own player (and the
 * vehicle it steers) on its replica, and right before each move packet says where on it it stands. The server puts the
 * move that follows at that point of its own body <i>before</i> Minecraft checks it ({@link #convert}, from
 * ServerGamePacketListenerImplMixin) — the client's world position is its replica's, a few ticks behind — so the whole
 * check (too quick, moved wrongly, blocks in the way, sneaking at an edge, floating) runs in the server's frame, the
 * way the client saw it. A point too far from where the client's own move says it is is not taken. The body is then
 * the player's base (its weight, its proxy going along).
 *
 * <p><b>Client to server, pushes</b> ({@link PushPayload}): the server never meets a body in a player's move (it takes
 * the client's word), so the push its client worked out against the replica (EntityCollisions) is told, and given,
 * within what a player can give.
 *
 * <p><b>Server to clients</b> ({@link RiderPayload}): each entity on a synced body is told, when it changes, to the
 * clients that track it and have the body; the client keeps it there on its replica after each tick's physics, and
 * Minecraft's own movement packets for it are let go meanwhile (ClientPacketListenerMixin) — its walk animation
 * follows its steps on the body, not the body's travel (LivingEntityMixin).
 *
 * <p><b>The hold</b>: after the server puts a player somewhere (a teleport, a respawn, joining) its client holds the
 * player still until the server says it has sent the bodies around there ({@link BodySyncPayload#SETTLED}), at most
 * {@link #HOLD_TICKS} — a deck whose chunk reaches the client a tick before the deck would let the player fall through.
 */
public final class SyncedRiders {

    /** A point told further than this from its body's origin is not on it: blocks. */
    private static final double MAX_LOCAL = 256;
    /** How far a move put on its body may be from where the client's own move has it: this, plus the body's travel. */
    private static final double SLACK = 2;
    /** The most the client's replica may lag, for the body's travel above: seconds. */
    private static final double LAG = 0.5;
    /** Pushes a client may tell a tick. */
    private static final int MAX_PUSHES = 8;

    /** The speed along a push it may bring the body up to, at most: m/s. */
    private static final float MAX_PUSH_SPEED = 20;
    /** How far from the player a push may be: blocks. */
    private static final double PUSH_REACH = 3;
    /** The longest a client holds its player for the bodies around it: ticks. */
    public static final int HOLD_TICKS = 100;
    /** How long a client keeps the server's word on an entity it has not been sent yet: ticks. */
    private static final int WAIT_TICKS = 40;

    private final LevelPhysics physics;

    // ---- the server ----
    /** what each client was last told of each rider */
    private final Map<ServerGamePacketListenerImpl, Int2ObjectOpenHashMap<RiderPayload.Rider>> told =
            new IdentityHashMap<>();
    /** per connection: what it said it stands on before its next move packet, [player, vehicle] */
    private static final Map<ServerGamePacketListenerImpl, StandingPayload[]> STANDING = new IdentityHashMap<>();

    /** per connection: [server tick, pushes told in it] */
    private static final Map<ServerGamePacketListenerImpl, int[]> PUSHES = new IdentityHashMap<>();
    /** the players put somewhere whose clients wait to hear that the bodies around are sent, and since when */
    private static final Map<ServerGamePacketListenerImpl, Integer> SETTLING = new IdentityHashMap<>();

    // ---- the client ----
    private final Int2ObjectOpenHashMap<Placed> placed = new Int2ObjectOpenHashMap<>();
    private final List<PushPayload> pushes = new ArrayList<>();
    private final int[] reported = {-1, -1};
    private static boolean held;
    private static int heldTicks;

    SyncedRiders(LevelPhysics physics) {
        this.physics = physics;
    }

    // ---- the server: where its clients stand ------------------------------------------------------

    /** A client's word on where its player or vehicle stands, for the move packet that follows: server thread. */
    static void receive(StandingPayload payload, ServerPlayer player) {
        if (player.hasDisconnected()) {
            return; // forget() has been
        }
        STANDING.computeIfAbsent(player.connection, c -> new StandingPayload[2])[payload.vehicle() ? 1 : 0] = payload;
        if (payload.body() < 0) {
            Entity mover = payload.vehicle() ? player.getRootVehicle() : player;
            EntityBases.release(mover);
        }
    }

    /**
     * Where the move packet's position goes in the server's frame: on the server's body, where the client said right
     * before it that the player (or its vehicle) stands; {@code null} to take the packet as it is — no word, on no
     * body, a body it does not have, or a point too far from where the client's own move has it ({@code x, y, z}).
     * Called by the move handlers before they check the move.
     */
    @ApiStatus.Internal
    @Nullable
    public static Vec3 convert(ServerPlayer player, boolean vehicle, double x, double y, double z) {
        StandingPayload[] said = STANDING.get(player.connection);
        StandingPayload standing = said == null ? null : said[vehicle ? 1 : 0];
        if (said != null) {
            said[vehicle ? 1 : 0] = null; // for this packet only
        }
        if (standing == null || standing.body() < 0) {
            return null;
        }
        Entity mover = vehicle ? player.getRootVehicle() : player;
        if (vehicle
                ? mover == player || mover.getControllingPassenger() != player
                : player.isPassenger() || player.isSpectator() || player.isSleeping() || player.isDeadOrDying()) {
            return null;
        }
        if (!EntityAuthority.clientMoves(mover)) {
            return null; // the server moves it: where its client says it stands is not its say
        }
        LevelPhysics physics = LevelPhysics.existing(player.serverLevel());
        Body body = physics == null ? null : physics.sync().serverBody(standing.body());
        double lx = standing.x(), ly = standing.y(), lz = standing.z();
        if (body == null
                || !physics.sync().knows(player, standing.body())
                || !Double.isFinite(lx + ly + lz)
                || lx * lx + ly * ly + lz * lz > MAX_LOCAL * MAX_LOCAL) {
            EntityBases.release(mover);
            return null;
        }
        Vector3d at = body.rotation(new Quaternionf())
                .transform(new Vector3d(lx, ly, lz))
                .add(body.position(new Vector3d()));
        // the replica is behind by what the body travels in the lag, its turn swinging the point round too
        Vector3f v = body.linearVelocity(new Vector3f()), w = body.angularVelocity(new Vector3f());
        double allowed = SLACK + LAG * (v.length() + w.length() * Math.sqrt(lx * lx + ly * ly + lz * lz));
        if (at.distanceSquared(x, y, z) > allowed * allowed) {
            EntityBases.release(mover);
            return null;
        }
        Vec3 there = new Vec3(at.x, at.y, at.z);
        EntityBases.stand(mover, body, there);
        return there;
    }

    /** A push a player's client says it gave a body: given, if it could have. Server thread. */
    static void receive(PushPayload payload, ServerPlayer player) {
        if (player.hasDisconnected()) {
            return;
        }
        int[] count = PUSHES.computeIfAbsent(player.connection, c -> new int[2]);
        int tick = player.server.getTickCount();
        if (count[0] != tick) {
            count[0] = tick;
            count[1] = 0;
        }
        if (++count[1] > MAX_PUSHES || player.isSpectator() || player.isDeadOrDying()) {
            return;
        }
        Entity mover = player.getControlledVehicle() != null ? player.getControlledVehicle() : player;
        if (!EntityAuthority.clientMoves(mover) || !EntityAuthority.pushesBodies(mover)) {
            return; // a client that should not have pushed: the server moves it, or its moves push nothing
        }
        LevelPhysics physics = LevelPhysics.existing(player.serverLevel());
        if (physics == null
                || physics.isStopped()
                || !player.serverLevel().tickRateManager().runsNormally()) {
            return; // frozen pushes would pile up into one launch on the next step
        }
        Body body = physics.sync().serverBody(payload.body());
        if (body == null || !physics.sync().knows(player, payload.body()) || !EntityCollisions.pushable(body)) {
            return;
        }
        float ix = payload.ix(), iy = payload.iy(), iz = payload.iz();
        double lx = payload.x(), ly = payload.y(), lz = payload.z();
        float impulse = (float) Math.sqrt(ix * ix + iy * iy + iz * iz);
        if (!Float.isFinite(impulse)
                || impulse == 0
                || !Double.isFinite(lx + ly + lz)
                || lx * lx + ly * ly + lz * lz > MAX_LOCAL * MAX_LOCAL) {
            return;
        }
        Vector3d at = body.rotation(new Quaternionf())
                .transform(new Vector3d(lx, ly, lz))
                .add(body.position(new Vector3d()));
        if (!player.getBoundingBox().inflate(PUSH_REACH).contains(at.x, at.y, at.z)
                || EntityCollisions.isPinned(body, ix / impulse, iy / impulse, iz / impulse)) {
            return; // out of reach, or held from beyond: the client's replica cannot tell, the server can
        }
        // no harder than a player's pace can: the body's speed that way up to MAX_PUSH_SPEED at most, and no more than
        // the player's mass at that speed; that speed the faster of the point's and the whole's, as a tumbling body's
        // point may lag its centre
        Vector3f there = body.pointVelocity(at.x, at.y, at.z, new Vector3f()),
                whole = body.linearVelocity(new Vector3f());
        float along = Math.max(there.x * ix + there.y * iy + there.z * iz, whole.x * ix + whole.y * iy + whole.z * iz)
                / impulse;
        float playerMass = physics.entities().massOf(player);
        float most = Math.min(body.mass(), Float.isFinite(playerMass) ? playerMass : 0)
                * Math.max(0, MAX_PUSH_SPEED - along);
        if (impulse > most) {
            float scale = most / impulse;
            ix *= scale;
            iy *= scale;
            iz *= scale;
        }
        if (ix != 0 || iy != 0 || iz != 0) {
            body.applyLinearImpulse(ix, iy, iz, at.x, at.y, at.z);
        }
    }

    /**
     * A player put somewhere — a teleport, a respawn, joining: off whatever it stood on (what its client said before is
     * of where it was), and its client holds it till the bodies around are sent.
     */
    @ApiStatus.Internal
    public static void putSomewhere(ServerPlayer player) {
        EntityBases.release(player);
        if (player.connection == null) {
            return;
        }
        STANDING.remove(player.connection);
        if (player.connection.hasChannel(BodySyncPayload.TYPE)) {
            SETTLING.put(player.connection, player.server.getTickCount());
        }
    }

    /**
     * At the end of the server's tick, after every level sent its bodies: each client waiting whose bodies around its
     * player are all sent — or that has waited long enough — is told so.
     */
    @ApiStatus.Internal
    public static void afterServerTick(int tick) {
        STANDING.keySet().removeIf(connection -> connection.player.hasDisconnected());
        PUSHES.keySet().removeIf(connection -> connection.player.hasDisconnected());
        for (var it = SETTLING.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ServerPlayer player = entry.getKey().player;
            if (player.hasDisconnected()) {
                it.remove();
                continue;
            }
            LevelPhysics physics = LevelPhysics.existing(player.serverLevel());
            if (physics == null || !physics.sync().anyUnsentNear(player) || tick - entry.getValue() > HOLD_TICKS) {
                PacketDistributor.sendToPlayer(player, BodySyncPayload.SETTLED);
                it.remove();
            }
        }
    }

    /**
     * A client gone, into another level or respawned: what it said goes, its base too, and what it was told of every
     * level but its current one with {@code everywhere} false.
     */
    @ApiStatus.Internal
    public static void forget(ServerPlayer player, boolean everywhere) {
        STANDING.remove(player.connection);
        PUSHES.remove(player.connection);
        if (everywhere) {
            SETTLING.remove(player.connection);
        }
        EntityBases.release(player);
        for (LevelPhysics physics : LevelPhysics.all()) {
            if (physics.level() instanceof ServerLevel level && (everywhere || level != player.serverLevel())) {
                physics.sync().riders().told.remove(player.connection);
            }
        }
    }

    // ---- the server: telling the clients where the riders are --------------------------------------

    /**
     * At the end of the level's tick, after the bodies' payloads: to each client that has a body, what changed of the
     * entities it tracks standing on it — and which have left.
     */
    @ApiStatus.Internal
    public void afterTick() {
        if (!(physics.level() instanceof ServerLevel level)) {
            return;
        }
        List<Rider> riders = new ArrayList<>();
        Quaternionf q = new Quaternionf();
        Vector3d p = new Vector3d();
        physics.riders().forEach(entity -> {
            Body body = EntityBases.baseOf(entity);
            int id = body == null ? -1 : SyncedBodies.networkId(body);
            if (id < 0 || EntityBases.carriesItself(entity)) {
                return; // not on a body clients have, or moved by its own mod: Minecraft's packets tell it all
            }
            body.position(p);
            body.rotation(q);
            Vector3d local =
                    q.transformInverse(new Vector3d(entity.getX() - p.x, entity.getY() - p.y, entity.getZ() - p.z));
            float turn = (float) Math.toDegrees(EntityBases.yawOf(q));
            float bodyYaw = entity instanceof LivingEntity living ? living.yBodyRot : entity.getYRot();
            riders.add(new Rider(
                    entity,
                    new RiderPayload.Rider(
                            entity.getId(),
                            id,
                            (float) local.x,
                            (float) local.y,
                            (float) local.z,
                            entity.getYRot() + turn,
                            entity.getYHeadRot() + turn,
                            bodyYaw + turn,
                            entity.getXRot())));
        });
        told.keySet().removeIf(connection -> connection.player.isRemoved() || connection.player.serverLevel() != level);
        if (riders.isEmpty() && told.isEmpty()) {
            return;
        }
        IntOpenHashSet seen = new IntOpenHashSet();
        for (ServerPlayer player : level.players()) {
            if (!player.connection.hasChannel(RiderPayload.TYPE)) {
                continue;
            }
            Int2ObjectOpenHashMap<RiderPayload.Rider> sent = told.get(player.connection);
            List<RiderPayload.Rider> changes = null;
            seen.clear();
            for (Rider rider : riders) {
                Entity entity = rider.entity;
                if (entity == player
                        || entity.getControllingPassenger() == player
                        || !physics.sync().knows(player, rider.told.body())
                        || !tracks(player, entity)) {
                    continue;
                }
                seen.add(entity.getId());
                if (sent == null) {
                    told.put(player.connection, sent = new Int2ObjectOpenHashMap<>());
                }
                if (!rider.told.sameAs(sent.get(entity.getId()))) {
                    sent.put(entity.getId(), rider.told);
                    (changes == null ? changes = new ArrayList<>() : changes).add(rider.told);
                }
            }
            if (sent != null) {
                for (var it = sent.int2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
                    int id = it.next().getIntKey();
                    if (!seen.contains(id)) {
                        (changes == null ? changes = new ArrayList<>() : changes).add(RiderPayload.Rider.off(id));
                        it.remove();
                    }
                }
            }
            if (changes != null) {
                PacketDistributor.sendToPlayer(player, new RiderPayload(changes));
            }
        }
    }

    private record Rider(Entity entity, RiderPayload.Rider told) {}

    /**
     * Whether the player's client tracks the entity, as near as the server's tracking goes
     * ({@code ChunkMap.TrackedEntity}): its type's range, its chunk sent. A client told of one it has not been sent yet
     * keeps the word a while.
     */
    private static boolean tracks(ServerPlayer player, Entity entity) {
        double range = player.server.getScaledTrackingDistance(entity.getType().clientTrackingRange() * 16);
        double dx = entity.getX() - player.getX(), dz = entity.getZ() - player.getZ();
        ChunkPos chunk = entity.chunkPosition();
        return dx * dx + dz * dz <= range * range
                && entity.broadcastToPlayer(player)
                && player.getChunkTrackingView().contains(chunk.x, chunk.z)
                && !player.connection.chunkSender.isPending(chunk.toLong());
    }

    // ---- the client --------------------------------------------------------------------------------

    /** Where the server says entities stand: on the client thread. */
    static void receive(RiderPayload payload, Level level) {
        LevelPhysics physics = LevelPhysics.existing(level);
        if (physics == null) {
            return;
        }
        SyncedRiders riders = physics.sync().riders();
        for (RiderPayload.Rider rider : payload.riders()) {
            Placed place = riders.placed.get(rider.entity());
            if (rider.body() < 0) {
                if (place != null) {
                    riders.placed.remove(rider.entity());
                    place.letGo(level.getEntity(rider.entity()));
                }
            } else if (place == null) {
                riders.placed.put(rider.entity(), new Placed(rider));
            } else {
                place.step = place.rider.body() == rider.body()
                        ? (float) Math.sqrt(sq(rider.x() - place.rider.x()) + sq(rider.z() - place.rider.z()))
                        : 0;
                place.rider = rider;
            }
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    /** The bodies around where the server last put this client's player are sent: it may move. */
    static void settled() {
        held = false;
    }

    /**
     * The server put this client's player somewhere: held till the bodies around are sent, if the server will say so
     * ({@code canTell}).
     */
    @ApiStatus.Internal
    public static void putHere(boolean canTell) {
        held = canTell;
        heldTicks = 0;
    }

    /** Whether this client's player is held this tick: waiting for the bodies around it, not for ever. */
    @ApiStatus.Internal
    public static boolean isHeld() {
        if (held && ++heldTicks > HOLD_TICKS) {
            held = false;
        }
        return held;
    }

    /**
     * Whether this client's player is being held — {@link #isHeld} as its tick last left it, without counting a tick:
     * for a mod that moves the player itself, which must not move it either while the bodies around it have not
     * arrived (Minecraft's tick of the player is what is held, not a mod's).
     */
    public static boolean isHolding() {
        return held;
    }

    /**
     * Right before this client's move packet for {@code mover} — its player, or the vehicle it steers: where on its
     * body it stands, told to the server ({@code canTell}: the server has MineBox3D); on none, once.
     */
    @ApiStatus.Internal
    public void beforeMovePacket(Entity mover, boolean vehicle, boolean canTell) {
        if (!canTell) {
            return;
        }
        // one the server moves: where it stands is the server's to know, not this client's to say
        Body base = EntityAuthority.clientMoves(mover) ? EntityBases.baseOf(mover) : null;
        int id = base == null ? -1 : SyncedBodies.networkId(base);
        int which = vehicle ? 1 : 0;
        if (id >= 0) {
            Vector3d local = base.rotation(new Quaternionf())
                    .transformInverse(
                            base.position(new Vector3d()).negate().add(mover.getX(), mover.getY(), mover.getZ()));
            PacketDistributor.sendToServer(
                    new StandingPayload(id, (float) local.x, (float) local.y, (float) local.z, vehicle));
        } else if (reported[which] >= 0) {
            PacketDistributor.sendToServer(StandingPayload.off(vehicle));
        }
        reported[which] = id;
    }

    /** A push this client's player gave a replica (EntityCollisions): told to the server after the tick. */
    @ApiStatus.Internal
    public void pushed(Body replica, float ix, float iy, float iz, Vector3d at) {
        int id = SyncedBodies.networkId(replica);
        if (id < 0) {
            return;
        }
        Vector3d local = replica.rotation(new Quaternionf())
                .transformInverse(replica.position(new Vector3d()).negate().add(at.x, at.y, at.z));
        pushes.add(new PushPayload(id, ix, iy, iz, (float) local.x, (float) local.y, (float) local.z));
    }

    /**
     * After the client's physics tick, which carried what it moves itself: its player's pushes told to the server
     * ({@code canTell}), and the other entities the server says stand on bodies put there on the replicas.
     */
    @ApiStatus.Internal
    public void afterClientTick(@Nullable Entity player, boolean canTell) {
        if (canTell) {
            for (PushPayload push : pushes) {
                PacketDistributor.sendToServer(push);
            }
        }
        pushes.clear();
        Level level = physics.level();
        Quaternionf q = new Quaternionf();
        Vector3d p = new Vector3d();
        for (var it = placed.int2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            Placed place = entry.getValue();
            Entity entity = level.getEntity(entry.getIntKey());
            if (entity == null) {
                // the word may come before the entity does
                if (++place.waited > WAIT_TICKS) {
                    it.remove();
                }
                continue;
            }
            if (entity.isRemoved() || entity == player || entity.isControlledByLocalInstance()) {
                it.remove();
                continue;
            }
            Body replica = physics.sync().replica(place.rider.body());
            if (replica == null || EntityBases.carriesItself(entity)) {
                place.letGo(entity); // its replica not here yet, or gone: Minecraft's packets meanwhile
                continue;
            }
            RiderPayload.Rider rider = place.rider;
            replica.position(p);
            replica.rotation(q);
            Vector3d at =
                    q.transform(new Vector3d(rider.x(), rider.y(), rider.z())).add(p);
            entity.setPos(at.x, at.y, at.z);
            float turn = (float) Math.toDegrees(EntityBases.yawOf(q));
            entity.setYRot(rider.yaw() - turn);
            entity.setYHeadRot(rider.headYaw() - turn);
            entity.setXRot(rider.pitch());
            if (entity instanceof LivingEntity living) {
                living.yBodyRot = rider.bodyYaw() - turn;
            }
            for (Entity passenger : entity.getPassengers()) {
                entity.positionRider(passenger);
            }
        }
    }

    /** Whether the server says the entity stands on a body here (client): kept there, its movement packets let go. */
    public boolean isPlaced(Entity entity) {
        Placed place = placed.get(entity.getId());
        return place != null && physics.sync().replica(place.rider.body()) != null;
    }

    /**
     * How far a placed entity stepped on its body since this was last asked (client): its walk, not its body's travel —
     * nothing while the server tells no change.
     */
    @ApiStatus.Internal
    public float stepOf(Entity entity) {
        Placed place = placed.get(entity.getId());
        if (place == null) {
            return 0;
        }
        float step = place.step;
        place.step = 0;
        return step;
    }

    /** Whether the server says the entity stands on a body, in whatever level it is in (client). */
    @ApiStatus.Internal
    public static boolean placedHere(Entity entity) {
        LevelPhysics physics = LevelPhysics.existing(entity.level());
        return physics != null && physics.sync().riders().isPlaced(entity);
    }

    /**
     * Minecraft's movement packet for an entity, if it is placed (client): let go, the last kept for when it is no
     * longer placed — Minecraft sends none while it stands still, and it would stay where its body left it.
     */
    @ApiStatus.Internal
    public static boolean keepsPlaced(Entity entity, double x, double y, double z, float yaw, float pitch) {
        Placed place = placedOf(entity);
        if (place == null) {
            return false;
        }
        place.missed = new double[] {x, y, z, yaw, pitch};
        return true;
    }

    /** The same for the head's turn. */
    @ApiStatus.Internal
    public static boolean keepsPlacedHead(Entity entity, float headYaw) {
        Placed place = placedOf(entity);
        if (place == null) {
            return false;
        }
        place.missedHead = headYaw;
        return true;
    }

    @Nullable
    private static Placed placedOf(Entity entity) {
        LevelPhysics physics = LevelPhysics.existing(entity.level());
        if (physics == null || !physics.sync().riders().isPlaced(entity)) {
            return null;
        }
        return physics.sync().riders().placed.get(entity.getId());
    }

    /** {@link #stepOf} in whatever level the entity is in (client). */
    @ApiStatus.Internal
    public static float stepHere(Entity entity) {
        LevelPhysics physics = LevelPhysics.existing(entity.level());
        return physics == null ? 0 : physics.sync().riders().stepOf(entity);
    }

    private static final class Placed {
        RiderPayload.Rider rider;
        float step;
        int waited;
        /** Minecraft's last movement for it while placed: x, y, z, yaw, pitch; and its head's turn (NaN for none) */
        @Nullable
        double[] missed;

        float missedHead = Float.NaN;

        Placed(RiderPayload.Rider rider) {
            this.rider = rider;
        }

        /** No longer placed: to where Minecraft last said, if it said anything meanwhile. */
        void letGo(@Nullable Entity entity) {
            if (entity != null && missed != null) {
                entity.lerpTo(missed[0], missed[1], missed[2], (float) missed[3], (float) missed[4], 3);
            }
            if (entity != null && !Float.isNaN(missedHead)) {
                entity.lerpHeadTo(missedHead, 3);
            }
            missed = null;
            missedHead = Float.NaN;
        }
    }
}
