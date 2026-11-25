package com.lowdragmc.minebox3d.mc.entity;

import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.physics.Body;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>The body an entity stands on</b> (design doc {@code 04-entities-and-bodies.md} §11, batch 4c-2) — Unreal's
 * movement base. An entity whose move ends on a body's top ({@link EntityCollisions}) has it as its base: where its
 * feet are in the body's frame is kept, and right after the steps that moved the body ({@link Riders}) it is carried to
 * where that point went — through Minecraft's collision and the other bodies, so a platform never carries it into a
 * wall — and turned by as much as the body turned about the vertical. A jump is carried too, for a while
 * ({@link #afterMove}). It leaves the base by landing elsewhere or staying off it, taking along the velocity of the
 * point it stood on (off a moving cart it goes on with the cart); landing on one, it gives that velocity back, so it is
 * not carried twice. Put elsewhere (a teleport), it is let go as it is ({@link #beforeMove}). Its weight presses on a
 * body it can push ({@link EntityProxies}), and the body it stands on carries it rather than pushes it.
 *
 * <p>Carried where it moves: on the server every entity but players, on a client what the client moves itself (its
 * own player). A mod that carries an entity on its own base itself — EntityStudio's mover has Unreal's movement base —
 * {@linkplain #registerCarrier says so}: MineBox3D then only keeps the base, for {@link #baseOf} /
 * {@link #baseVelocity}.
 */
public final class EntityBases {

    /** A base whose point moved further than this in one tick is let go (it was put somewhere), blocks. */
    private static final double LOST = 8;
    /** An entity further than this from where its base last had it was put somewhere (a teleport), blocks. */
    private static final double PUT_ELSEWHERE = 1;
    /** Ticks an entity off the ground is still carried by its base: a jump, and then some. */
    static final int AIR_TICKS = 20;

    /** A mod's say on who carries an entity standing on a body. */
    public interface Carrier {
        /** True for an entity the mod moves with its base itself. */
        boolean carriesItself(Entity entity);

        /**
         * For an entity it {@linkplain #carriesItself carries itself}: whether it is carrying it on {@code body} now —
         * standing on it, not in the air off it. Only then is {@code body} let be as the entity's base (not pushed out
         * of, not collided with by its proxy); otherwise it is any other body to it. By default, whenever it carries the
         * entity at all.
         */
        default boolean isCarrying(Entity entity, Body body) {
            return true;
        }
    }

    /** On every entity (EntityMixin): its base. */
    @ApiStatus.Internal
    public interface Holder {
        @Nullable
        Base minebox3d$base();

        void minebox3d$setBase(@Nullable Base base);
    }

    /**
     * Where an entity stands on a body: its feet in the body's frame, the body's turn about the vertical then, and how
     * long it has been off the ground.
     */
    @ApiStatus.Internal
    public static final class Base {
        final Body body;
        /** the feet in the body's frame, and in the world, as last kept */
        double localX, localY, localZ, feetX, feetY, feetZ;

        float yaw;
        int airTicks;

        Base(Body body) {
            this.body = body;
        }
    }

    /**
     * The entities on a level's bodies, carried by them right after the steps that moved them ({@link #carry()}): a
     * level's entities move before its physics steps, so carried at their own next move they would ride a tick behind —
     * a tenth of a block into a rising lift, a step behind on a moving deck. One per {@code LevelPhysics}.
     */
    @ApiStatus.Internal
    public static final class Riders {
        private final Set<Entity> riders = Collections.newSetFromMap(new IdentityHashMap<>());

        void add(Entity entity) {
            riders.add(entity);
        }

        /** Each entity standing on a body now. */
        public void forEach(Consumer<Entity> action) {
            for (Entity entity : riders) {
                if (!entity.isRemoved() && baseOf(entity) != null) {
                    action.accept(entity);
                }
            }
        }

        /**
         * After the steps, on {@code level}'s thread, holding the world: each rider moved by as much as its base moved the
         * point its feet were kept at. A server's player too, till its client says next where it stands (SyncedRiders) —
         * or it would stand a tick behind its deck on the server. One gone, now without physics (a spectator), riding
         * something, or in another level is let go.
         */
        public void carry(Level level) {
            for (Iterator<Entity> it = riders.iterator(); it.hasNext(); ) {
                Entity entity = it.next();
                Holder holder = (Holder) entity;
                Base base = holder.minebox3d$base();
                if (base == null
                        || entity.isRemoved()
                        || !base.body.isValid()
                        || entity.noPhysics
                        || entity.isPassenger()
                        || entity.level() != level
                        || !meets(entity, base.body)) {
                    if (base != null && !entity.isRemoved()) {
                        holder.minebox3d$setBase(null);
                    }
                    it.remove();
                    continue;
                }
                Vec3 carried = EntityBases.carry(entity);
                if (carried.lengthSqr() > 1.0e-12) {
                    EntityCollisions.carry(entity, carried);
                    keep(base, entity); // a wall it was carried against holds it back: it slides on the base
                    if (entity instanceof ServerPlayer player) {
                        player.serverLevel().getChunkSource().move(player);
                    }
                }
            }
        }
    }

    private static final List<Carrier> CARRIERS = new CopyOnWriteArrayList<>();

    private EntityBases() {}

    public static void registerCarrier(Carrier carrier) {
        CARRIERS.add(carrier);
    }

    /**
     * The body the entity stands on — or jumped off and is still carried by ({@link #afterMove}; {@code onGround()} tells
     * the two apart) — or {@code null}. On a client a replica's {@code SyncedBodies.networkId} names it.
     */
    @Nullable
    public static Body baseOf(Entity entity) {
        Base base = ((Holder) entity).minebox3d$base();
        return base != null && base.body.isValid() && meets(entity, base.body) ? base.body : null;
    }

    /**
     * Whether the entity still meets the body: its physics collision on, and not ignored by it (doc 04 §17). A base it no
     * longer meets is let go as it is — not carried on, nor its velocity handed over: the entity is past it already.
     */
    private static boolean meets(Entity entity, Body body) {
        return EntityShapes.hasPhysicsCollision(entity) && !LevelBodies.ignores(body, entity);
    }

    /** How fast the point of its base the entity stands on is moving, m/s, world space; zero with no base. */
    public static Vector3f baseVelocity(Entity entity, Vector3f out) {
        Body body = baseOf(entity);
        return body == null ? out.zero() : body.pointVelocity(entity.getX(), entity.getY(), entity.getZ(), out);
    }

    // ---- from EntityCollisions --------------------------------------------------------------------

    /**
     * How far its base carried the point an entity's feet were last kept at, blocks, its turn applied already; zero with
     * no base, or one it carries itself. One put elsewhere since (a teleport) is let go, as it is.
     */
    static Vec3 carry(Entity entity) {
        Holder holder = (Holder) entity;
        Base base = holder.minebox3d$base();
        if (base == null) {
            return Vec3.ZERO;
        }
        boolean itself = carriesItself(entity);
        if (!base.body.isValid() || !itself && isPutElsewhere(base, entity)) {
            holder.minebox3d$setBase(null);
            return Vec3.ZERO;
        }
        if (itself) {
            return Vec3.ZERO;
        }
        Vector3d p = base.body.position(new Vector3d());
        Quaternionf q = base.body.rotation(new Quaternionf());
        Vector3d feet =
                q.transform(new Vector3d(base.localX, base.localY, base.localZ)).add(p);
        double dx = feet.x - base.feetX, dy = feet.y - base.feetY, dz = feet.z - base.feetZ;
        if (dx * dx + dy * dy + dz * dz > LOST * LOST) {
            holder.minebox3d$setBase(null);
            return Vec3.ZERO;
        }
        // Minecraft's yaw turns clockwise seen from above, a turn about +Y the other way
        float turned = yawOf(q) - base.yaw;
        if (turned > Math.PI) {
            turned -= (float) (2 * Math.PI);
        } else if (turned < -Math.PI) {
            turned += (float) (2 * Math.PI);
        }
        if (turned != 0) {
            float degrees = (float) Math.toDegrees(turned);
            entity.setYRot(entity.getYRot() - degrees);
            entity.setYHeadRot(entity.getYHeadRot() - degrees);
            if (entity instanceof LivingEntity living) {
                living.yBodyRot -= degrees;
            }
            base.yaw += turned;
        }
        return new Vec3(dx, dy, dz);
    }

    /**
     * After an entity's move: on the body its move ended on ({@code ground}), on blocks, or in the air. Not a server's
     * player, whose client says where it stands.
     *
     * <p>⚠️ In the air it is still carried, for up to {@link #AIR_TICKS} (VS2's way, not Unreal's): Minecraft slows what
     * flies by a tenth a tick, so the base's velocity handed over at the jump would fade and the jumper land behind —
     * three quarters of a block at a walking cart's 3 m/s. Carried, a jump on a moving deck comes down where it went up.
     * Landing elsewhere, or still in the air after that, the velocity of the point it stood on is handed over (and
     * landing on another body, the difference between the two).
     */
    static void afterMove(Entity entity, @Nullable Body ground) {
        if (EntityCollisions.isClientMoved(entity)) {
            return;
        }
        Holder holder = (Holder) entity;
        Base base = holder.minebox3d$base();
        if (base != null && (!base.body.isValid() || entity.isPassenger() || !meets(entity, base.body))) {
            holder.minebox3d$setBase(base = null);
        }
        if (entity.isPassenger()) {
            return;
        }
        boolean itself = carriesItself(entity);
        if (entity.onGround() && ground != null && ground.isValid()) {
            if (base == null || base.body != ground) {
                if (!itself) {
                    // what it brought along of the motion of where it was is the new base's now
                    Vector3f from = base == null ? new Vector3f() : pointVelocity(base.body, entity);
                    Vector3f to = pointVelocity(ground, entity);
                    Vec3 motion = entity.getDeltaMovement();
                    entity.setDeltaMovement(motion.x + (from.x - to.x) / 20, motion.y, motion.z + (from.z - to.z) / 20);
                }
                base = new Base(ground);
                holder.minebox3d$setBase(base);
                LevelPhysics physics = LevelPhysics.existing(entity.level());
                if (physics != null) {
                    physics.riders().add(entity);
                }
            }
            base.airTicks = 0;
            keep(base, entity);
            return;
        }
        if (base == null) {
            return;
        }
        if (!entity.onGround() && ++base.airTicks <= AIR_TICKS) {
            keep(base, entity); // where it is in the air, in the base's frame
            return;
        }
        holder.minebox3d$setBase(null);
        if (!itself) {
            // off it, onto blocks or still in the air: on with the point it stood on, as from a moving cart
            Vector3f v = pointVelocity(base.body, entity);
            entity.setDeltaMovement(entity.getDeltaMovement().add(v.x / 20, v.y / 20, v.z / 20));
        }
    }

    /**
     * Before an entity's move: one put elsewhere since its base last had it (a teleport) is let go, as it is — not
     * carried from there, nor given the base's velocity. Not a server's player, whose base moves ahead of its move
     * ({@link #stand}). Nor is a base the entity no longer meets kept, whoever's it is.
     */
    static void beforeMove(Entity entity) {
        Holder holder = (Holder) entity;
        Base base = holder.minebox3d$base();
        if (base != null
                && (!meets(entity, base.body)
                        || !EntityCollisions.isClientMoved(entity)
                                && !carriesItself(entity)
                                && isPutElsewhere(base, entity))) {
            holder.minebox3d$setBase(null);
        }
    }

    private static boolean isPutElsewhere(Base base, Entity entity) {
        return entity.distanceToSqr(base.feetX, base.feetY, base.feetZ) > PUT_ELSEWHERE * PUT_ELSEWHERE;
    }

    /** Where the entity is now, in the base's frame (and in the world). */
    private static void keep(Base base, Entity entity) {
        keep(base, entity.getX(), entity.getY(), entity.getZ());
    }

    private static void keep(Base base, double x, double y, double z) {
        Vector3d p = base.body.position(new Vector3d());
        Quaternionf q = base.body.rotation(new Quaternionf());
        Vector3d local = q.transformInverse(new Vector3d(x - p.x, y - p.y, z - p.z));
        base.localX = local.x;
        base.localY = local.y;
        base.localZ = local.z;
        base.feetX = x;
        base.feetY = y;
        base.feetZ = z;
        base.yaw = yawOf(q);
    }

    private static Vector3f pointVelocity(Body body, Entity entity) {
        return body.pointVelocity(entity.getX(), entity.getY(), entity.getZ(), new Vector3f());
    }

    /** Whether a mod carries this entity on its base itself ({@link #registerCarrier}). */
    public static boolean carriesItself(Entity entity) {
        for (Carrier carrier : CARRIERS) {
            if (carrier.carriesItself(entity)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the entity has a base MineBox3D carries and turns it with, a tick at a time ({@link Riders#carry}). */
    public static boolean turnsWithItsBase(Entity entity) {
        return baseOf(entity) != null && !carriesItself(entity);
    }

    /**
     * The base that carries the entity now, or {@code null}: {@link #baseOf} for one MineBox3D carries (in the air off it
     * too, for a while); for one a mod carries itself, its base only while that mod is carrying it there
     * ({@link Carrier#isCarrying}) — in the air off it, the body is any other body to the entity.
     */
    @Nullable
    public static Body carryingBase(Entity entity) {
        Body base = baseOf(entity);
        if (base == null) {
            return null;
        }
        for (Carrier carrier : CARRIERS) {
            if (carrier.carriesItself(entity)) {
                return carrier.isCarrying(entity, base) ? base : null;
            }
        }
        return base;
    }

    /**
     * What a client moves — a server's player, or the vehicle it steers — standing at {@code at} on {@code body}, where
     * the client says (SyncedRiders): its base, as an entity the server moves gets one by moving onto it. Server only.
     */
    @ApiStatus.Internal
    public static void stand(Entity mover, Body body, Vec3 at) {
        Holder holder = (Holder) mover;
        if (!meets(mover, body)) {
            holder.minebox3d$setBase(null);
            return;
        }
        Base base = holder.minebox3d$base();
        if (base == null || base.body != body) {
            base = new Base(body);
            holder.minebox3d$setBase(base);
            LevelPhysics physics = LevelPhysics.existing(mover.level());
            if (physics != null) {
                physics.riders().add(mover);
            }
        }
        keep(base, at.x, at.y, at.z);
    }

    /** What a client moves, off its base: on no body, put somewhere, or gone from the level (SyncedRiders). */
    @ApiStatus.Internal
    public static void release(Entity mover) {
        ((Holder) mover).minebox3d$setBase(null);
    }

    /**
     * A rotation's turn about the vertical, radians, counter-clockwise seen from above: where it takes +X — or, when +X is
     * tipped near the vertical (a crate rolled onto its side), where it takes +Z, a quarter on.
     */
    @ApiStatus.Internal
    public static float yawOf(Quaternionf q) {
        float x = 1 - 2 * (q.y * q.y + q.z * q.z);
        float z = 2 * (q.x * q.z - q.w * q.y);
        if (x * x + z * z >= 0.25f) {
            return (float) Math.atan2(-z, x);
        }
        // +Z turned: (2 (xz + wy), 2 (yz - wx), 1 - 2 (x² + y²)); a turn θ about +Y takes it to (sin θ, 0, cos θ)
        float zx = 2 * (q.x * q.z + q.w * q.y);
        float zz = 1 - 2 * (q.x * q.x + q.y * q.y);
        return (float) Math.atan2(zx, zz);
    }
}
