package com.lowdragmc.minebox3d.mc.sync;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.entity.StepUpOn;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.MassData;
import com.lowdragmc.minebox3d.physics.MotionLocks;
import com.lowdragmc.minebox3d.physics.SurfaceMaterial;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>One tick of synced bodies for one player</b> (design doc {@code 04-entities-and-bodies.md} §10): the bodies it
 * starts seeing, where the ones it sees moved to, and the ones it stops seeing; the server's physics tick they are as of,
 * and the player's own frame that tick ran ({@link SyncedBodies.Prediction}) — what its client holds them against, or
 * {@link #NO_FRAME}; and the bodies it sees whose damping, gravity or locks changed ({@link Tuning}). Server to client.
 *
 * <p>Shapes travel as their numbers and become {@link Geometry} only when a replica is built ({@link SyncedShape#geometry()},
 * on the client thread): a hull is Box3D's to build, and decoding must not need Box3D — the payload is read on the
 * network thread, and a decoding error drops the connection.
 */
public record BodySyncPayload(List<Spawn> spawns, List<State> states, int[] removals, boolean settled, long tick,
                              int frame, List<Tuning> tunings) implements CustomPacketPayload {

    /** {@link #frame} for a player that predicts nothing. */
    public static final int NO_FRAME = Integer.MIN_VALUE;

    /**
     * Nothing changed but this: the client now has the bodies around where its player was last put (a teleport, a respawn,
     * joining), so its player, held there till then (SyncedRiders), may move — and not fall through one still on its way.
     */
    public static final BodySyncPayload SETTLED = new BodySyncPayload(List.of(), List.of(), new int[0], true, 0, NO_FRAME);

    public BodySyncPayload(List<Spawn> spawns, List<State> states, int[] removals) {
        this(spawns, states, removals, false, 0, NO_FRAME);
    }

    public BodySyncPayload(List<Spawn> spawns, List<State> states, int[] removals, boolean settled, long tick, int frame) {
        this(spawns, states, removals, settled, tick, frame, List.of());
    }

    /**
     * A body's {@link Dynamics} anew, its damping, gravity scale or locks changed: put on its replica where it is, rather
     * than the replica made again — a physics character turns its gravity off and on at every jump.
     */
    public record Tuning(int id, Dynamics dynamics) {
    }

    public static final Type<BodySyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MineBox3D.MOD_ID, "bodies"));
    public static final StreamCodec<FriendlyByteBuf, BodySyncPayload> CODEC =
            StreamCodec.of((buffer, payload) -> payload.write(buffer), BodySyncPayload::read);

    /**
     * Where a body is and how it moves, as of the server's tick; and what a client simulating it would need to have too
     * (EntityStudio doc 61 §3.9): {@code whole}, all it touches is what a client has — the blocks, the entities, synced
     * bodies sent whole — and {@code joints}, how many joints hold it, which a client must hold it by as well.
     */
    public record State(int id, double x, double y, double z, float qx, float qy, float qz, float qw, float vx, float vy,
                        float vz, float wx, float wy, float wz, boolean whole, int joints) {

        /** A body touching only what a client has, and held by no joint. */
        public State(int id, double x, double y, double z, float qx, float qy, float qz, float qw, float vx, float vy,
                     float vz, float wx, float wy, float wz) {
            this(id, x, y, z, qx, qy, qz, qw, vx, vy, vz, wx, wy, wz, true, 0);
        }

        /** Finite, and a rotation that can be made a unit one: what a replica may be driven to. */
        boolean isUsable() {
            float q = qx * qx + qy * qy + qz * qz + qw * qw;
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && q > 0.5f && q < 1.5f
                    && Float.isFinite(vx + vy + vz + wx + wy + wz);
        }
    }

    /**
     * A shape to copy: its kind and numbers ({@link #of}), the filter the client's collisions with it go by, whether it
     * is a sensor (which on the client stays one, rather than becoming a solid box), its surface — what the client's
     * own bodies slide and bounce on it by — and whether entities step up onto it (the client moves its player).
     */
    public record SyncedShape(byte kind, float[] values, long categoryBits, long maskBits, int groupIndex, boolean sensor,
                              float friction, float restitution, float rollingResistance, StepUpOn stepUpOn) {

        static final byte SPHERE = 0, CAPSULE = 1, BOX = 2, HULL = 3;
        private static final StepUpOn[] STEP_UP_ON = StepUpOn.values();

        /** Whether a shape of this geometry is sent: not a mesh or height field, whose whole data would be. */
        static boolean isSent(Geometry geometry) {
            return geometry instanceof Geometry.Sphere || geometry instanceof Geometry.Capsule
                    || geometry instanceof Geometry.Box || geometry instanceof Geometry.Hull;
        }

        /** {@code null} for what is not sent in this version ({@link #isSent}). */
        @Nullable
        static SyncedShape of(Geometry geometry, long categoryBits, long maskBits, int groupIndex, boolean sensor,
                              SurfaceMaterial surface, StepUpOn stepUpOn) {
            return switch (geometry) {
                case Geometry.Sphere s -> new SyncedShape(SPHERE, new float[]{s.cx(), s.cy(), s.cz(), s.radius()},
                        categoryBits, maskBits, groupIndex, sensor, surface.friction, surface.restitution,
                        surface.rollingResistance, stepUpOn);
                case Geometry.Capsule c -> new SyncedShape(CAPSULE, new float[]{c.ax(), c.ay(), c.az(), c.bx(), c.by(),
                        c.bz(), c.radius()}, categoryBits, maskBits, groupIndex, sensor, surface.friction,
                        surface.restitution, surface.rollingResistance, stepUpOn);
                case Geometry.Box b -> new SyncedShape(BOX, new float[]{b.hx(), b.hy(), b.hz(), b.cx(), b.cy(), b.cz(),
                        b.qx(), b.qy(), b.qz(), b.qw()}, categoryBits, maskBits, groupIndex, sensor, surface.friction,
                        surface.restitution, surface.rollingResistance, stepUpOn);
                case Geometry.Hull h -> new SyncedShape(HULL, h.points().clone(), categoryBits, maskBits, groupIndex, sensor,
                        surface.friction, surface.restitution, surface.rollingResistance, stepUpOn);
                default -> null;
            };
        }

        /** The step-up value written as {@code ordinal}; one this version does not know is the default. */
        static StepUpOn stepUpOn(int ordinal) {
            return ordinal >= 0 && ordinal < STEP_UP_ON.length ? STEP_UP_ON[ordinal] : StepUpOn.YES;
        }

        /** The surface into {@code out}; a number that is not a usable one is left as {@code out} had it. */
        SurfaceMaterial surface(SurfaceMaterial out) {
            if (friction >= 0 && Float.isFinite(friction)) {
                out.friction = friction;
            }
            if (restitution >= 0 && Float.isFinite(restitution)) {
                out.restitution = restitution;
            }
            if (rollingResistance >= 0 && Float.isFinite(rollingResistance)) {
                out.rollingResistance = rollingResistance;
            }
            return out;
        }

        /** The geometry these numbers stand for. A hull is built again by Box3D: needs the native library. */
        Geometry geometry() {
            float[] v = values;
            return switch (kind) {
                case SPHERE -> new Geometry.Sphere(v[0], v[1], v[2], v[3]);
                case CAPSULE -> new Geometry.Capsule(v[0], v[1], v[2], v[3], v[4], v[5], v[6]);
                case BOX -> new Geometry.Box(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]);
                case HULL -> new Geometry.Hull(v);
                default -> throw new IllegalStateException("unknown shape kind " + kind);
            };
        }

        /** How many numbers a kind carries; -1 for any count a hull may have. */
        private static int length(byte kind) {
            return switch (kind) {
                case SPHERE -> 4;
                case CAPSULE -> 7;
                case BOX -> 10;
                case HULL -> -1;
                default -> throw new IllegalArgumentException("unknown shape kind " + kind);
            };
        }
    }

    /**
     * A dynamic body's mass, centre of mass and inertia (body space, column by column) — what an entity walking into its
     * replica pushes against ({@link SyncedBodies#serverMass}) — and whether entities push it (doc 04 §11.2 #1): one they
     * do not is as heavy as a wall to them; and its damping, gravity scale and motion locks ({@link MotionLocks#bits}),
     * which a client simulating it moves it by. Sent only for a dynamic body.
     */
    public record Dynamics(float mass, float cx, float cy, float cz, float[] inertia, boolean pushedByEntities,
                           float linearDamping, float angularDamping, float gravityScale, int locks) {

        /** Undamped, falling as the world does, locked in nothing. */
        public Dynamics(float mass, float cx, float cy, float cz, float[] inertia, boolean pushedByEntities) {
            this(mass, cx, cy, cz, inertia, pushedByEntities, 0, 0, 1, 0);
        }

        static Dynamics of(MassData data, Body body, boolean pushedByEntities) {
            float[] inertia = new float[9];
            data.inertia.get(inertia);
            return new Dynamics(data.mass, data.centerX, data.centerY, data.centerZ, inertia, pushedByEntities,
                    body.linearDamping(), body.angularDamping(), body.gravityScale(), body.motionLocks().bits());
        }

        MassData into(MassData out) {
            out.set(mass, cx, cy, cz);
            out.inertia.set(inertia);
            return out;
        }

        /** How it moves besides its mass, onto {@code body}: what makes a replica simulated here move as the server's. */
        void moveAs(Body body) {
            body.setLinearDamping(linearDamping);
            body.setAngularDamping(angularDamping);
            body.setGravityScale(gravityScale);
            body.setMotionLocks(MotionLocks.of(locks));
        }

        boolean isUsable() {
            if (!(mass > 0) || !Float.isFinite(mass + cx + cy + cz + gravityScale) || !(linearDamping >= 0)
                    || !(angularDamping >= 0) || !Float.isFinite(linearDamping + angularDamping)
                    || (locks & ~MotionLocks.ALL_BITS) != 0) {
                return false;
            }
            for (float value : inertia) {
                if (!Float.isFinite(value)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * A body a player starts seeing; {@code tag} and {@code ignoredTag} its {@link Body#tag} and {@link Body#ignoredTag}
     * (an entity it never meets, doc 04 §17); {@code dynamics} for a dynamic one, else {@code null}; {@code centre}, its
     * centre of mass in its own frame, for a dynamic one (a point's speed is its turn about there), else {@code null}.
     */
    public record Spawn(int id, @Nullable String name, long userData, long tag, long ignoredTag, List<SyncedShape> shapes,
                        State state, @Nullable Dynamics dynamics, @Nullable Vector3f centre) {
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public boolean isEmpty() {
        return spawns.isEmpty() && states.isEmpty() && removals.length == 0 && !settled && tunings.isEmpty();
    }

    // ---- the wire ---------------------------------------------------------------------------------

    /** Lists are made no bigger than this up front, whatever count the other end claims. */
    private static final int PREALLOCATE = 256;

    void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(spawns.size());
        for (Spawn spawn : spawns) {
            buffer.writeVarInt(spawn.id);
            buffer.writeBoolean(spawn.name != null);
            if (spawn.name != null) {
                buffer.writeUtf(spawn.name);
            }
            buffer.writeLong(spawn.userData);
            buffer.writeLong(spawn.tag);
            buffer.writeLong(spawn.ignoredTag);
            buffer.writeVarInt(spawn.shapes.size());
            for (SyncedShape shape : spawn.shapes) {
                buffer.writeByte(shape.kind);
                buffer.writeVarInt(shape.values.length);
                for (float value : shape.values) {
                    buffer.writeFloat(value);
                }
                buffer.writeLong(shape.categoryBits);
                buffer.writeLong(shape.maskBits);
                buffer.writeInt(shape.groupIndex);
                buffer.writeBoolean(shape.sensor);
                buffer.writeFloat(shape.friction);
                buffer.writeFloat(shape.restitution);
                buffer.writeFloat(shape.rollingResistance);
                buffer.writeByte(shape.stepUpOn.ordinal());
            }
            writeState(buffer, spawn.state);
            buffer.writeBoolean(spawn.dynamics != null);
            if (spawn.dynamics != null) {
                writeDynamics(buffer, spawn.dynamics);
            }
            buffer.writeBoolean(spawn.centre != null);
            if (spawn.centre != null) {
                buffer.writeFloat(spawn.centre.x);
                buffer.writeFloat(spawn.centre.y);
                buffer.writeFloat(spawn.centre.z);
            }
        }
        buffer.writeVarInt(states.size());
        for (State state : states) {
            buffer.writeVarInt(state.id);
            writeState(buffer, state);
        }
        buffer.writeVarIntArray(removals);
        buffer.writeBoolean(settled);
        buffer.writeVarLong(tick);
        buffer.writeInt(frame);
        buffer.writeVarInt(tunings.size());
        for (Tuning tuning : tunings) {
            buffer.writeVarInt(tuning.id);
            writeDynamics(buffer, tuning.dynamics);
        }
    }

    private static void writeDynamics(FriendlyByteBuf buffer, Dynamics d) {
        buffer.writeFloat(d.mass);
        buffer.writeFloat(d.cx);
        buffer.writeFloat(d.cy);
        buffer.writeFloat(d.cz);
        for (float value : d.inertia) {
            buffer.writeFloat(value);
        }
        buffer.writeBoolean(d.pushedByEntities);
        buffer.writeFloat(d.linearDamping);
        buffer.writeFloat(d.angularDamping);
        buffer.writeFloat(d.gravityScale);
        buffer.writeByte(d.locks);
    }

    private static Dynamics readDynamics(FriendlyByteBuf buffer) {
        float m = buffer.readFloat(), cx = buffer.readFloat(), cy = buffer.readFloat(), cz = buffer.readFloat();
        float[] inertia = new float[9];
        for (int v = 0; v < 9; v++) {
            inertia[v] = buffer.readFloat();
        }
        return new Dynamics(m, cx, cy, cz, inertia, buffer.readBoolean(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat(), buffer.readUnsignedByte());
    }

    static BodySyncPayload read(FriendlyByteBuf buffer) {
        int spawnCount = buffer.readVarInt();
        List<Spawn> spawns = new ArrayList<>(Math.min(spawnCount, PREALLOCATE));
        for (int i = 0; i < spawnCount; i++) {
            int id = buffer.readVarInt();
            String name = buffer.readBoolean() ? buffer.readUtf() : null;
            long userData = buffer.readLong();
            long tag = buffer.readLong();
            long ignoredTag = buffer.readLong();
            int shapeCount = buffer.readVarInt();
            List<SyncedShape> shapes = new ArrayList<>(Math.min(shapeCount, PREALLOCATE));
            for (int s = 0; s < shapeCount; s++) {
                byte kind = buffer.readByte();
                int length = buffer.readVarInt();
                int expected = SyncedShape.length(kind);
                if (expected >= 0 ? length != expected
                        : length < 12 || length % 3 != 0 || length > 3 * Geometry.Hull.MAX_VERTICES) {
                    throw new IllegalArgumentException("a shape of kind " + kind + " with " + length + " numbers");
                }
                float[] values = new float[length];
                for (int v = 0; v < length; v++) {
                    values[v] = buffer.readFloat();
                }
                shapes.add(new SyncedShape(kind, values, buffer.readLong(), buffer.readLong(), buffer.readInt(),
                        buffer.readBoolean(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                        SyncedShape.stepUpOn(buffer.readUnsignedByte())));
            }
            State state = readState(buffer, id);
            Dynamics dynamics = buffer.readBoolean() ? readDynamics(buffer) : null;
            Vector3f centre = buffer.readBoolean()
                    ? new Vector3f(buffer.readFloat(), buffer.readFloat(), buffer.readFloat()) : null;
            spawns.add(new Spawn(id, name, userData, tag, ignoredTag, shapes, state, dynamics, centre));
        }
        int stateCount = buffer.readVarInt();
        List<State> states = new ArrayList<>(Math.min(stateCount, PREALLOCATE));
        for (int i = 0; i < stateCount; i++) {
            states.add(readState(buffer, buffer.readVarInt()));
        }
        int[] removals = buffer.readVarIntArray();
        boolean settled = buffer.readBoolean();
        long tick = buffer.readVarLong();
        int frame = buffer.readInt();
        int tuningCount = buffer.readVarInt();
        List<Tuning> tunings = new ArrayList<>(Math.min(tuningCount, PREALLOCATE));
        for (int i = 0; i < tuningCount; i++) {
            tunings.add(new Tuning(buffer.readVarInt(), readDynamics(buffer)));
        }
        return new BodySyncPayload(spawns, states, removals, settled, tick, frame, tunings);
    }

    private static void writeState(FriendlyByteBuf buffer, State state) {
        buffer.writeDouble(state.x);
        buffer.writeDouble(state.y);
        buffer.writeDouble(state.z);
        buffer.writeFloat(state.qx);
        buffer.writeFloat(state.qy);
        buffer.writeFloat(state.qz);
        buffer.writeFloat(state.qw);
        buffer.writeFloat(state.vx);
        buffer.writeFloat(state.vy);
        buffer.writeFloat(state.vz);
        buffer.writeFloat(state.wx);
        buffer.writeFloat(state.wy);
        buffer.writeFloat(state.wz);
        buffer.writeBoolean(state.whole);
        buffer.writeByte(Math.clamp(state.joints, 0, 255));
    }

    private static State readState(FriendlyByteBuf buffer, int id) {
        return new State(id, buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readBoolean(),
                buffer.readUnsignedByte());
    }
}
