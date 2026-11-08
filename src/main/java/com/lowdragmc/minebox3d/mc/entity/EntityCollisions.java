package com.lowdragmc.minebox3d.mc.entity;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.CollisionCategories;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.LevelThreads;
import com.lowdragmc.minebox3d.mc.sync.SyncedBodies;
import com.lowdragmc.minebox3d.mc.sync.SyncedRiders;
import com.lowdragmc.minebox3d.mixin.EntityInvoker;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.BoxSweep;
import com.lowdragmc.minebox3d.physics.CastResult;
import com.lowdragmc.minebox3d.physics.ContactPoints;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.MassData;
import com.lowdragmc.minebox3d.physics.Overlaps;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.QueryFilter;
import com.lowdragmc.minebox3d.physics.Shape;
import it.unimi.dsi.fastutil.floats.FloatArrays;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>Bodies in the way of Minecraft entities</b> (design doc {@code 04-entities-and-bodies.md} §11, batch 4c-1): an
 * entity moving with {@code Entity.move} is stopped by the level's bodies as by blocks, steps up onto them, pushes the
 * ones it can by mass, and is pushed out of one that moved into it.
 *
 * <p>Minecraft moves an entity's box one axis at a time, Y first, each axis cut short by the blocks in the way
 * ({@code Entity.collideWithShapes}). Here each axis is also cut short by the bodies, with Box3D's box-mover sweep
 * against every solid shape nearby ({@link Shape#castShape}, {@code canEncroach}): a box resting on a body or sliding
 * along its side is not stopped by it, one moving in stops a few millimetres short. The tops of the shapes it may step
 * onto ({@link StepUpOn}) join Minecraft's candidate step heights, so Minecraft's own step-up climbs onto bodies.
 *
 * <p><b>Stepping up</b> goes as Unreal's Mover goes ({@code TryMoveToStepUp}): none at all when what stopped the move
 * may not be stepped onto, none that lands on such a shape, and a step that climbs over a body does not push it. A shape
 * that may not be stepped onto is still walked on by an entity standing on its body: Minecraft has no slopes, and
 * walking up one is stepping up it a little at a time — what Unreal's option leaves alone ("not whether they can walk on
 * it after landing on it").
 *
 * <p><b>By mass</b> (§11.2 #1). Every body stops the entity at its surface; one it can push ({@link #pushable}) and did
 * not step up onto is then pushed, when the move ends, as an inelastic collision of the two along the cut axis: both go
 * on at the shared speed {@code u = (M v + m v_b) / (M + m)} (M the entity's mass, m the body's effective mass at the
 * point along the axis, its turning included), the body by an impulse at the point, the entity by keeping {@code u}
 * where Minecraft would zero the axis — and the cut is no collision to Minecraft (sprinting goes on). Next tick the gap
 * the body opened lets the entity on: a 5 kg crate goes along with a player at nearly its pace, two tonnes of iron stay
 * put against the ground's friction. A crate lower than the step is stepped onto rather than pushed, unless its shapes
 * say no ({@link StepUpOn#NO}). Falling onto a body pushes nothing — the weight of what stands on one is pressed into it
 * by its proxy ({@link EntityProxies}).
 *
 * <p>Which bodies: the level's own physics only (a private one changes nothing of the level), shapes that accept
 * entities ({@link #FILTER}: not terrain, which is Minecraft's blocks, nor entity proxies; a shape that masks out
 * {@link CollisionCategories#ENTITY} lets entities through) and not sensors, nor the bodies that never meet the entity
 * moving ({@link LevelBodies#setIgnoredEntity}, §17) — and none at all for an entity whose physics collision is off
 * ({@link EntityShapes#hasPhysicsCollision}). On a client its replicas of the server's
 * bodies, with the server body's mass ({@link SyncedBodies#serverMass}); a push its own player gives one is told to
 * the server when the client moves it ({@link SyncedRiders}). An entity whose moves push no body
 * ({@link EntityAuthority#pushesBodies}) is stopped by them all, as by walls.
 *
 * <p>⚠️ Not what a client moves, on the server — a player, and what a player steers: the server takes the client's
 * word on bodies (§11.2 #2), its move put in the body's frame first (SyncedRiders). A server replaying a client's move
 * against its own bodies, which the client sees one to three ticks late, would pull players back near anything moving;
 * one whose client has no MineBox3D would be pulled back at every body.
 */
public final class EntityCollisions {

    /** An entity's sweep: shapes that are not terrain or proxies and accept entities. */
    public static final QueryFilter FILTER = new QueryFilter(CollisionCategories.ENTITY,
            ~(CollisionCategories.TERRAIN | CollisionCategories.ENTITY));
    /** How far a body that moved into an entity may have gone in and still be pushed out of, beyond the box's size. */
    private static final double PUSH_OUT_REACH = 2.0;
    /** How much thinner across its move a swept box is (sweeping): twice Box3D's linear slop. */
    private static final float SKIN = 0.01f;
    /** How far a body may be into an entity's box and still be only touching it. */
    private static final float OVERLAP = 0.01f;
    /** How much further along an axis than its own sweep a move's step-up went, to have stepped over what cut it. */
    private static final double STEPPED_OVER = 1.0e-4;
    /** The least upward normal a top is stood and stepped on at: Unreal's walkable floor angle, 44.765 degrees. */
    private static final float WALKABLE = 0.7f;
    private static final Quaternionfc NO_TURN = new Quaternionf();
    private static final int MAX_DEPTH = 8;
    private static final Direction.Axis[] AXES = Direction.Axis.values();

    private static final ThreadLocal<Movers> MOVERS = ThreadLocal.withInitial(Movers::new);

    private EntityCollisions() {
    }

    /** Whether entities push a body by mass rather than stop at it: dynamic, and {@linkplain LevelBodies#pushedByEntities pushed}. */
    public static boolean pushable(Body body) {
        return body.type() == BodyType.DYNAMIC && LevelBodies.pushedByEntities(body);
    }

    // ---- stepping up onto shapes ----------------------------------------------------------------

    /** A mod's answer for its bodies' shapes set to {@link StepUpOn#OWNER} — Unreal's {@code AActor::CanBeBaseForCharacter}. */
    public interface StepUpOwner {
        /** Whether {@code mover} may step up onto {@code body}; {@code null} for a body that is not this mod's. */
        @Nullable
        Boolean canStepUpOnto(Body body, Entity mover);
    }

    private static final List<StepUpOwner> STEP_UP_OWNERS = new CopyOnWriteArrayList<>();

    public static void registerStepUpOwner(StepUpOwner owner) {
        STEP_UP_OWNERS.add(owner);
    }

    /**
     * Whether an entity walking into {@code shape} may step up onto it; {@code null} goes back to the default, yes. Copied
     * to clients with the shape (a client moves its player, steps up included).
     */
    public static void setStepUpOn(Shape shape, @Nullable StepUpOn stepUpOn) {
        StepUpOn kept = stepUpOn == StepUpOn.YES ? null : stepUpOn;
        if (shape.attachment(StepUpOn.class) != kept) {
            shape.attach(StepUpOn.class, kept);
            shape.markChanged();
        }
    }

    public static StepUpOn stepUpOn(Shape shape) {
        StepUpOn set = shape.attachment(StepUpOn.class);
        return set != null ? set : StepUpOn.YES;
    }

    /**
     * Whether {@code mover} may step up onto {@code shape} ({@link StepUpOn}) — as Unreal's {@code CanStepUpOnHitSurface}
     * asks it (GroundMovementUtils.cpp:565-598): the shape's own no, and then its owner, the first with a say, else yes.
     * So a shape set to yes on something whose owner says no (a part of a character) is not stepped onto either.
     */
    public static boolean canStepUpOnto(Shape shape, Entity mover) {
        return stepUpOn(shape) != StepUpOn.NO && ownerLets(shape.body(), mover);
    }

    private static boolean ownerLets(Body body, Entity mover) {
        for (StepUpOwner owner : STEP_UP_OWNERS) {
            Boolean said = owner.canStepUpOnto(body, mover);
            if (said != null) {
                return said;
            }
        }
        return true;
    }

    /**
     * Whether something that does not give — terrain, a static or kinematic body (an entity's proxy, unless it is
     * force-capped: EntityProxies.Settings#strength) — holds
     * {@code body} from the far side of a push along {@code (dx, dy, dz)} (unit), as of the last step: pushed on, it would
     * only be squeezed between the two, and Box3D lets a squeezed body out sideways, fast. Such a body is a wall to an
     * entity walking into it.
     */
    public static boolean isPinned(Body body, float dx, float dy, float dz) {
        ContactPoints contacts = CONTACTS.get();
        int count = body.contactPoints(contacts);
        Vector3f normal = new Vector3f();
        for (int i = 0; i < count; i++) {
            // the normal is from the other shape into this body: one holding it from beyond the push points back at it
            if (contacts.separation(i) > 0.01f || contacts.normal(i, normal).dot(dx, dy, dz) > -0.7f) {
                continue;
            }
            if (contacts.isTerrain(i)) {
                return true;
            }
            Shape other = contacts.otherShape(i);
            if (other != null && other.body().type() != BodyType.DYNAMIC) {
                return true;
            }
        }
        return false;
    }

    private static final ThreadLocal<ContactPoints> CONTACTS = ThreadLocal.withInitial(ContactPoints::new);

    /** On the server, whether a client moves this entity and the server takes its word ({@link EntityAuthority}). */
    public static boolean isClientMoved(Entity entity) {
        return !entity.level().isClientSide && EntityAuthority.clientMoves(entity);
    }

    // ---- for mods that sweep their own entities (doc 04 §8.2 #4) ----------------------------------

    /**
     * The shapes in {@code region} that stop entities ({@link #FILTER}, not sensors) in the level's own physics, into
     * {@code out}; nothing off the level's thread. Candidates from Box3D's broad phase: a shape found may only come near
     * (its bounds are floats — two blocks apart ten million blocks out).
     */
    public static List<Shape> shapesNear(Level level, AABB region, List<Shape> out) {
        return shapesNear(level, region, null, out);
    }

    /**
     * {@link #shapesNear(Level, AABB, List)} for {@code mover}'s own sweep, {@code null} for nobody's: not the bodies that
     * never meet it ({@link LevelBodies#setIgnoredEntity}), and none for one whose physics collision is off
     * ({@link EntityShapes#hasPhysicsCollision}).
     */
    public static List<Shape> shapesNear(Level level, AABB region, @Nullable Entity mover, List<Shape> out) {
        LevelPhysics physics = LevelPhysics.existing(level);
        if (physics == null || !physics.world().isOpen() || mover != null && !EntityShapes.hasPhysicsCollision(mover)) {
            return out;
        }
        try (PhysicsWorld.Use use = physics.world().tryUse()) {
            if (use == null) {
                return out;
            }
            Overlaps overlaps = new Overlaps(8);
            double far = Math.max(Math.max(Math.abs(region.minX), Math.abs(region.maxX)),
                    Math.max(Math.abs(region.minZ), Math.abs(region.maxZ)));
            double margin = 2 * Math.ulp((float) far);
            int count = physics.world().overlapAABB(region.minX - margin, region.minY - margin, region.minZ - margin,
                    region.maxX + margin, region.maxY + margin, region.maxZ + margin, FILTER, overlaps);
            for (int i = 0; i < count; i++) {
                Shape shape = overlaps.shape(i);
                if (shape != null && shape.isValid() && !shape.isSensor()
                        && (mover == null || !LevelBodies.ignores(shape.body(), mover))) {
                    out.add(shape);
                }
            }
        }
        return out;
    }

    /**
     * <b>A turned box swept exactly among the bodies that stop entities</b> ({@link #FILTER}: not terrain, not entity
     * proxies, not sensors) — {@link PhysicsWorld#sweepBox}: stopping where it touches, touching no hit unless it moves
     * in, a body it starts inside told the least way out. For a mod that sweeps its own turned entities with a mover that
     * keeps its box on what it stands on (EntityStudio's wall-walkers); {@link #sweep} is Box3D's own cast, a few
     * millimetres short. False for none, and off the level's thread.
     *
     * @param ignore a body not met, or {@code null}: the base the entity is being carried with ({@link PhysicsWorld#sweepBox})
     */
    public static boolean sweepBox(Level level, double x, double y, double z, Quaternionfc rotation, float hx, float hy,
                                   float hz, float tx, float ty, float tz, boolean ignoreExits, @Nullable Body ignore,
                                   BoxSweep out) {
        return sweepBox(level, x, y, z, rotation, hx, hy, hz, tx, ty, tz, ignoreExits, ignore, null, out);
    }

    /**
     * {@link #sweepBox(Level, double, double, double, Quaternionfc, float, float, float, float, float, float, boolean, Body, BoxSweep)}
     * as {@code mover}'s own box, {@code null} for nobody's: not the bodies that never meet it
     * ({@link LevelBodies#setIgnoredEntity}), and nothing for one whose physics collision is off
     * ({@link EntityShapes#hasPhysicsCollision}).
     */
    public static boolean sweepBox(Level level, double x, double y, double z, Quaternionfc rotation, float hx, float hy,
                                   float hz, float tx, float ty, float tz, boolean ignoreExits, @Nullable Body ignore,
                                   @Nullable Entity mover, BoxSweep out) {
        LevelPhysics physics = LevelPhysics.existing(level);
        if (physics == null || !physics.world().isOpen() || !LevelThreads.isLevelThread(level)
                || mover != null && !EntityShapes.hasPhysicsCollision(mover)) {
            return false;
        }
        try (PhysicsWorld.Use use = physics.world().tryUse()) {
            return use != null && physics.world().sweepBox(x, y, z, rotation, hx, hy, hz, tx, ty, tz, FILTER, ignoreExits,
                    ignore, mover == null ? 0L : EntityShapes.tagOf(mover), out);
        }
    }

    /**
     * The first of the shapes that stop entities that {@code geometry} — at {@code (x, y, z)} turned by {@code rotation},
     * moved by {@code translation} — meets, as Box3D moves a box among bodies ({@link Shape#castShape}, {@code
     * canEncroach}: what it rests on or slides along is no hit, it stops a few millimetres short), into {@code out}; false
     * for none, or off the level's thread. Shapes it starts inside are passed through. A mod that sweeps its own entities
     * (turned boxes, as EntityStudio's wall-walkers) meets the bodies with this as Minecraft's moves do.
     *
     * @param geometry a box or hull: a sphere or capsule cannot stop short of a mesh body ({@link Shape#castShape})
     */
    public static boolean sweep(Level level, Geometry geometry, double x, double y, double z, Quaternionfc rotation,
                                float tx, float ty, float tz, CastResult out) {
        return sweep(level, geometry, x, y, z, rotation, tx, ty, tz, null, out);
    }

    /**
     * {@link #sweep(Level, Geometry, double, double, double, Quaternionfc, float, float, float, CastResult)} as
     * {@code mover}'s own shape, {@code null} for nobody's ({@link #shapesNear(Level, AABB, Entity, List)}).
     */
    public static boolean sweep(Level level, Geometry geometry, double x, double y, double z, Quaternionfc rotation,
                                float tx, float ty, float tz, @Nullable Entity mover, CastResult out) {
        // the swept shape's reach, whatever its turn: a sphere round its farthest point
        float reach = reach(geometry);
        AABB region = new AABB(x - reach, y - reach, z - reach, x + reach, y + reach, z + reach).expandTowards(tx, ty, tz);
        Shape best = null;
        float fraction = Float.POSITIVE_INFINITY;
        Vector3f normal = new Vector3f();
        for (Shape shape : shapesNear(level, region, mover, new ArrayList<>())) {
            if (shape.castShape(geometry, x, y, z, rotation, tx, ty, tz, true, out)
                    && out.normal(normal).lengthSquared() >= 0.25f && out.fraction() < fraction) {
                best = shape;
                fraction = out.fraction();
            }
        }
        return best != null && best.castShape(geometry, x, y, z, rotation, tx, ty, tz, true, out);
    }

    private static float reach(Geometry geometry) {
        return switch (geometry) {
            case Geometry.Box b -> (float) (Math.sqrt(b.cx() * b.cx() + b.cy() * b.cy() + b.cz() * b.cz())
                    + Math.sqrt(b.hx() * b.hx() + b.hy() * b.hy() + b.hz() * b.hz()));
            case Geometry.Hull h -> {
                float most = 0;
                float[] p = h.points();
                for (int i = 0; i < p.length; i += 3) {
                    most = Math.max(most, p[i] * p[i] + p[i + 1] * p[i + 1] + p[i + 2] * p[i + 2]);
                }
                yield (float) Math.sqrt(most);
            }
            default -> throw new IllegalArgumentException("not a shape to sweep: " + geometry);
        };
    }

    /** Whether a shape that stops entities touches or overlaps {@code box}: each candidate asked exactly. */
    public static boolean anyBodyIn(Level level, AABB box) {
        return anyBodyIn(level, box, null);
    }

    /**
     * {@link #anyBodyIn(Level, AABB)} for {@code mover}'s own box, {@code null} for nobody's
     * ({@link #shapesNear(Level, AABB, Entity, List)}).
     */
    public static boolean anyBodyIn(Level level, AABB box, @Nullable Entity mover) {
        if (!LevelThreads.isLevelThread(level)) {
            return false;
        }
        Geometry.Box query = Geometry.Box.of((float) (box.getXsize() / 2), (float) (box.getYsize() / 2),
                (float) (box.getZsize() / 2));
        Vec3 center = box.getCenter();
        CastResult hit = new CastResult();
        for (Shape shape : shapesNear(level, box, mover, new ArrayList<>())) {
            // a sweep of nothing hits at once what the box touches or overlaps, and nothing else
            if (shape.castShape(query, center.x, center.y, center.z, NO_TURN, 0, 0, 0, false, hit)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a body that stops entities is under or right around {@code entity} — the server's "no blocks around" before
     * it kicks a player for flying, widened by a block.
     */
    public static boolean isBodyUnder(Entity entity) {
        return anyBodyIn(entity.level(), entity.getBoundingBox().inflate(0.0625).expandTowards(0, -0.55, 0).inflate(1),
                entity);
    }

    // ---- from the mixins -------------------------------------------------------------------------

    /** A tick starts on this thread: a move a thrown error left open is let go. */
    @ApiStatus.Internal
    public static void newTick() {
        MOVERS.get().reset();
    }

    /**
     * {@code Entity.move} starts: the bodies that may be in the way of {@code motion} (and of a step up), and the entity
     * pushed out of one it is stuck in. Always paired with {@link #endMove}.
     */
    @ApiStatus.Internal
    public static void beginMove(Entity entity, Vec3 motion) {
        EntityBases.beforeMove(entity);
        Mover mover = MOVERS.get().push();
        if (mover.gather(entity, motion)) {
            mover.pushOut();
        }
    }

    /**
     * {@code Entity.move} ends: what it pushed, pushed ({@link Mover#settle}); on a body's top, the entity has it as its
     * base ({@link EntityBases}).
     */
    @ApiStatus.Internal
    public static void endMove(Entity entity) {
        Movers movers = MOVERS.get();
        Mover mover = movers.top();
        if (mover != null && mover.entity == entity) {
            mover.settle();
        }
        if (mover != null && !entity.noPhysics && !isClientMoved(entity) && LevelThreads.isLevelThread(entity.level())) {
            EntityBases.afterMove(entity, mover.entity == entity ? mover.ground : null);
        }
        movers.pop();
    }

    /** {@code Entity.collide} starts: the motion the step-up's destination is taken from. */
    @ApiStatus.Internal
    public static void collide(Vec3 motion) {
        Mover mover = MOVERS.get().current();
        if (mover != null) {
            mover.intentX = motion.x;
            mover.intentZ = motion.z;
        }
    }

    /** {@code Entity.collide} ends with {@code result}: further sideways than the move's own sweep, it stepped up. */
    @ApiStatus.Internal
    public static void collided(Vec3 result) {
        Mover mover = MOVERS.get().current();
        if (mover != null) {
            mover.collided(result);
        }
    }

    /** Whether the move going on on this thread has bodies to look at: if not, Minecraft's collision is left alone. */
    @ApiStatus.Internal
    public static boolean isActive() {
        return MOVERS.get().current() != null;
    }

    /**
     * {@code Entity.collideWithShapes}, with the bodies of the move going on ({@link #isActive()}; with none, the same as
     * Minecraft's): Minecraft's axis order, each axis cut by blocks then bodies. Unlike Minecraft's, it does not stop at
     * "no blocks around". {@code main}: the move's own sweep (not one of its step-up's), whose cuts are pushes.
     */
    @ApiStatus.Internal
    public static Vec3 collideWithShapes(Vec3 motion, AABB box, List<VoxelShape> shapes, boolean main) {
        Mover mover = MOVERS.get().current();
        if (mover == null) {
            mover = Mover.NONE;
            main = false;
        }
        AABB from = box;
        double dx = motion.x, dy = motion.y, dz = motion.z;
        if (dy != 0) {
            dy = mover.cut(Direction.Axis.Y, box, shapes, dy, main);
            if (dy != 0) {
                box = box.move(0, dy, 0);
            }
        }
        boolean zFirst = Math.abs(dx) < Math.abs(dz);
        if (zFirst && dz != 0) {
            dz = mover.cut(Direction.Axis.Z, box, shapes, dz, main);
            if (dz != 0) {
                box = box.move(0, 0, dz);
            }
        }
        if (dx != 0) {
            dx = mover.cut(Direction.Axis.X, box, shapes, dx, main);
            if (!zFirst && dx != 0) {
                box = box.move(dx, 0, 0);
            }
        }
        if (!zFirst && dz != 0) {
            dz = mover.cut(Direction.Axis.Z, box, shapes, dz, main);
        }
        if (main) {
            mover.sweptX = dx;
            mover.sweptZ = dz;
        } else if (mover != Mover.NONE && dy > 0 && (dx != 0 || dz != 0)
                && mover.landsWhereItMayNotStep(from.move(dx, dy, dz))) {
            // a step-up's sweep that would end on a shape it may not step onto goes nowhere sideways, so Minecraft does
            // not take it — Unreal's step down onto "!CanStepUpOnHitSurface" while going up
            return new Vec3(0, dy, 0);
        }
        return new Vec3(dx, dy, dz);
    }

    /**
     * {@code Entity.collectCandidateStepUpHeights}, with the tops of the bodies where the entity is going that it may step
     * onto — and none at all when a body it may not step onto is what stopped it ({@link StepUpOn}).
     */
    @ApiStatus.Internal
    public static float[] stepUpHeights(float[] heights, AABB box, float stepHeight) {
        Mover mover = MOVERS.get().current();
        if (mover == null) {
            return heights;
        }
        return mover.stepUpHeights(heights, box, stepHeight);
    }

    /**
     * Whether nothing — no block (Minecraft's own answer, {@code blocksFree}) and no body that stops entities — is in
     * {@code box}: Minecraft's {@code Player.canFallAtLeast}, which keeps a sneaking player from walking off an edge.
     * Without the bodies, a player sneaking on one could not move at all.
     */
    @ApiStatus.Internal
    public static boolean canFallInto(Entity entity, AABB box, boolean blocksFree) {
        // thinner across than the sweep down that finds what it stands on (sweeping) by more than Box3D's "touching" (a slop
        // and a quarter): where this still touches, that still stands
        return blocksFree && !(LevelPhysics.existing(entity.level()) != null
                && anyBodyIn(entity.level(), box.deflate(2 * SKIN, 0, 2 * SKIN), entity));
    }

    /**
     * Where an entity's own next move takes it, bodies included: {@code Entity.collide} as the move does it, its step-up
     * too — and into {@code steppedUp[0]}, whether it steps up. For a proxy's target (EntityProxies), from inside the
     * physics' prepare.
     */
    static Vec3 predict(Entity entity, Vec3 motion, boolean[] steppedUp) {
        Movers movers = MOVERS.get();
        Mover mover = movers.push();
        try {
            mover.gather(entity, motion);
            Vec3 allowed = ((EntityInvoker) entity).minebox3d$collide(motion);
            steppedUp[0] = mover.steppedUp;
            return allowed;
        } finally {
            movers.pop();
        }
    }

    /**
     * The entity moved by {@code carry} as by a move of its own, through blocks and the bodies other than where it is
     * carried to (EntityBases): a platform never carries it into a wall. Not a move — no step sounds, no fall. How far it
     * went.
     */
    static Vec3 carry(Entity entity, Vec3 carry) {
        Movers movers = MOVERS.get();
        Mover mover = movers.push();
        try {
            mover.gather(entity, carry);
            AABB box = entity.getBoundingBox();
            Vec3 allowed = Entity.collideBoundingBox(entity, carry, box, entity.level(),
                    entity.level().getEntityCollisions(entity, box.expandTowards(carry)));
            entity.setPos(entity.getX() + allowed.x, entity.getY() + allowed.y, entity.getZ() + allowed.z);
            return allowed;
        } finally {
            movers.pop();
        }
    }

    // ---- one move ------------------------------------------------------------------------------

    /** The moves going on on one thread: an entity's move may, rarely, move another. */
    private static final class Movers {
        private final Mover[] stack = new Mover[MAX_DEPTH];
        private int depth;
        private boolean warned;

        /** The next mover, cleared. Past the most moves deep (moves that threw never ended), it starts over. */
        Mover push() {
            if (depth == MAX_DEPTH) {
                if (!warned) {
                    warned = true;
                    MineBox3D.LOGGER.warn("entity moves nested {} deep on {}: starting over", MAX_DEPTH,
                            Thread.currentThread().getName());
                }
                reset();
            }
            Mover mover = stack[depth];
            if (mover == null) {
                mover = stack[depth] = new Mover();
            }
            depth++;
            mover.clear();
            return mover;
        }

        void pop() {
            if (depth > 0) {
                stack[--depth].clear();
            }
        }

        void reset() {
            while (depth > 0) {
                pop();
            }
        }

        /** The innermost move, bodies or not. */
        @Nullable
        Mover top() {
            return depth == 0 ? null : stack[depth - 1];
        }

        /** The move going on, if it has bodies to look at. */
        @Nullable
        Mover current() {
            if (depth == 0) {
                return null;
            }
            Mover mover = stack[depth - 1];
            return mover.entity == null ? null : mover;
        }
    }

    /**
     * The box swept along {@code axis}: a centimetre thinner across it. Box3D counts shapes within its linear slop (5 mm)
     * as touching, and a box resting that close above one top meets the edge of the next — a step it has just climbed, a
     * crate beside the one it stands on — as an obstacle when it moves on sideways; Minecraft, sweeping exact boxes, does
     * not. Thinner across the move, what it slides over or along is out of reach; what it moves into is not.
     */
    private static Geometry.Box sweeping(AABB box, Direction.Axis axis) {
        float hx = (float) (box.getXsize() / 2), hy = (float) (box.getYsize() / 2), hz = (float) (box.getZsize() / 2);
        return Geometry.Box.of(axis == Direction.Axis.X ? hx : thinner(hx), axis == Direction.Axis.Y ? hy : thinner(hy),
                axis == Direction.Axis.Z ? hz : thinner(hz));
    }

    private static float thinner(float half) {
        return Math.max(half - SKIN, Math.min(half, SKIN));
    }

    /** {@code query} with each face across {@code axis} {@link #OVERLAP} further in; itself if too thin for that. */
    private static Geometry.Box shorter(Geometry.Box query, Direction.Axis axis) {
        float half = (float) axis.choose(query.hx(), query.hy(), query.hz());
        if (half <= 2 * OVERLAP) {
            return query;
        }
        return Geometry.Box.of(axis == Direction.Axis.X ? half - OVERLAP : query.hx(),
                axis == Direction.Axis.Y ? half - OVERLAP : query.hy(), axis == Direction.Axis.Z ? half - OVERLAP : query.hz());
    }

    /** One entity's move: the solid shapes near it, what the sweeps need, and what the move pushed. */
    private static final class Mover {
        /** No bodies: cuts nothing. */
        static final Mover NONE = new Mover();

        @Nullable
        Entity entity;
        @Nullable
        LevelPhysics physics;
        final List<Shape> shapes = new ArrayList<>();
        /** the entity's mass, NaN until a pushable body asks */
        float mass = Float.NaN;
        /** {@link EntityAuthority#pushesBodies}: no, and every body is a wall to the move */
        boolean pushes;
        double intentX, intentZ;
        /** the body whose top stopped the move's fall, if one did */
        @Nullable
        Body ground;
        /** the body it stood on as the move began: walked on, whatever its shapes say of stepping up onto them */
        @Nullable
        Body base;
        /** per axis, in the move's own sweep: cut by a block or a body it does not push; the body it pushes, where, how fast */
        final boolean[] stopped = new boolean[3];
        final Shape[] pushed = new Shape[3];
        final Vector3d[] pushedAt = {new Vector3d(), new Vector3d(), new Vector3d()};
        final double[] pushedBy = new double[3];
        /** per axis, in the move's own sweep: the shape that cut it short of the blocks, if one did */
        final Shape[] cutBy = new Shape[3];
        /** how far the move's own sweep went along x and z; NaN before it */
        double sweptX = Double.NaN, sweptZ = Double.NaN;
        /** Minecraft took a step-up ({@link #collided}) */
        boolean steppedUp;

        final Overlaps overlaps = new Overlaps(16);
        final CastResult hit = new CastResult();
        final Vector3f normal = new Vector3f();
        final Vector3d point = new Vector3d();
        final Vector3d center = new Vector3d();
        final Vector3f velocity = new Vector3f();
        final Quaternionf rotation = new Quaternionf();
        final MassData massData = new MassData();
        final Matrix3f inverse = new Matrix3f();
        final Vector3f arm = new Vector3f();
        final Vector3f twist = new Vector3f();

        void clear() {
            entity = null;
            physics = null;
            shapes.clear();
            mass = Float.NaN;
            pushes = false;
            intentX = intentZ = 0;
            ground = null;
            base = null;
            sweptX = sweptZ = Double.NaN;
            steppedUp = false;
            for (int i = 0; i < 3; i++) {
                stopped[i] = false;
                pushed[i] = null;
                pushedBy[i] = 0;
                cutBy[i] = null;
            }
        }

        /**
         * The solid shapes near the move; false (and the move goes as Minecraft's) for none — or for an entity the bodies
         * do not stop here: one without physics, one off its level's thread, one a client moves (on the server), one whose
         * physics collision is off. Not the bodies that never meet it.
         */
        boolean gather(Entity entity, Vec3 motion) {
            if (entity.noPhysics || isClientMoved(entity) || !LevelThreads.isLevelThread(entity.level())
                    || !EntityShapes.hasPhysicsCollision(entity)) {
                return false;
            }
            LevelPhysics physics = LevelPhysics.existing(entity.level());
            if (physics == null || physics.isStopped()) {
                return false;
            }
            PhysicsWorld world = physics.world();
            if (!world.isOpen()) {
                return false;
            }
            try (PhysicsWorld.Use use = world.tryUse()) {
                if (use == null) {
                    return false; // not this thread's world: the move goes without bodies
                }
                AABB box = entity.getBoundingBox().expandTowards(motion).expandTowards(0, entity.maxUpStep(), 0);
                // the broad phase keeps float bounds: wider far out, where a float is coarser
                double far = Math.max(Math.max(Math.abs(box.minX), Math.abs(box.maxX)),
                        Math.max(Math.abs(box.minZ), Math.abs(box.maxZ)));
                double margin = 0.05 + 2 * Math.ulp((float) far);
                int count = world.overlapAABB(box.minX - margin, box.minY - margin, box.minZ - margin, box.maxX + margin,
                        box.maxY + margin, box.maxZ + margin, FILTER, overlaps);
                for (int i = 0; i < count; i++) {
                    Shape shape = overlaps.shape(i);
                    if (shape != null && shape.isValid() && !shape.isSensor() && !LevelBodies.ignores(shape.body(), entity)) {
                        shapes.add(shape);
                    }
                }
            }
            if (shapes.isEmpty()) {
                return false;
            }
            this.entity = entity;
            this.physics = physics;
            this.pushes = EntityAuthority.pushesBodies(entity);
            this.base = entity.onGround() ? EntityBases.baseOf(entity) : null;
            return true;
        }

        /** One axis of the box's move: cut by the blocks, then by the bodies; in the move's own sweep, what cut it kept. */
        double cut(Direction.Axis axis, AABB box, List<VoxelShape> blocks, double d, boolean main) {
            double free = Shapes.collide(axis, box, blocks, d);
            if (main && free != d) {
                stopped[axis.ordinal()] = true;
            }
            return clip(axis, box, free, main);
        }

        /**
         * One axis of the box's move, {@code d} as far as the blocks let it: as far as the bodies let it too. An
         * overlapping body is no obstacle here — the entity is let out of it, and {@link #pushOut} deals with it.
         */
        double clip(Direction.Axis axis, AABB box, double d, boolean main) {
            if (d == 0 || entity == null) {
                return d;
            }
            Geometry.Box query = sweeping(box, axis);
            double cx = (box.minX + box.maxX) / 2, cy = (box.minY + box.maxY) / 2, cz = (box.minZ + box.maxZ) / 2;
            float tx = axis == Direction.Axis.X ? (float) d : 0;
            float ty = axis == Direction.Axis.Y ? (float) d : 0;
            float tz = axis == Direction.Axis.Z ? (float) d : 0;
            double allowed = d;
            Shape stopping = null;
            boolean top = false;
            Geometry.Box shorter = null;
            for (Shape shape : shapes) {
                if (!shape.isValid() || !shape.castShape(query, cx, cy, cz, NO_TURN, tx, ty, tz, true, hit)) {
                    continue;
                }
                double reach;
                if (hit.normal(normal).lengthSquared() >= 0.25f) {
                    reach = hit.fraction() * d;
                } else {
                    // inside it. Barely (a body that moved a hair into the box) is touching: the box shorter along the move
                    // by more than that meets it moving in, and not moving out
                    if (shorter == null) {
                        shorter = shorter(query, axis);
                    }
                    if (shorter == query || !shape.castShape(shorter, cx, cy, cz, NO_TURN, tx, ty, tz, true, hit)
                            || hit.normal(normal).lengthSquared() < 0.25f) {
                        continue;
                    }
                    reach = Math.copySign(Math.max(0, Math.abs(hit.fraction() * d) - OVERLAP), d);
                }
                if (Math.abs(reach) < Math.abs(allowed)) {
                    allowed = reach;
                    stopping = shape;
                    top = normal.y > WALKABLE;
                    hit.point(point);
                }
            }
            if (axis == Direction.Axis.Y && d < 0) {
                ground = top ? stopping.body() : null; // a fall ended on a body's top; otherwise on blocks, or not at all
            }
            if (main && stopping != null) {
                int i = axis.ordinal();
                cutBy[i] = stopping;
                // falling onto one pushes nothing (the class notes)
                if (!(axis == Direction.Axis.Y && d < 0) && pushes && massOf(stopping.body())) {
                    pushed[i] = stopping;
                    pushedBy[i] = d;
                    // where on the face it pushes: the sweep's point is any of the touching face's (a crate's edge, as
                    // often as not, which would flip it); the body's centre as near as the entity's face reaches instead —
                    // a crate it covers is pushed straight, a plank by its end is turned
                    Body body = stopping.body();
                    body.worldCenter(center);
                    Vector3d at = pushedAt[i].set(point);
                    if (axis != Direction.Axis.X) {
                        at.x = Math.clamp(center.x, box.minX, box.maxX);
                    }
                    if (axis != Direction.Axis.Y) {
                        at.y = Math.clamp(center.y, box.minY, box.maxY);
                    }
                    if (axis != Direction.Axis.Z) {
                        at.z = Math.clamp(center.z, box.minZ, box.maxZ);
                    }
                } else {
                    stopped[i] = true;
                }
            }
            return allowed;
        }

        /**
         * {@code Entity.collide}'s result: further along x or z than the move's own sweep, Minecraft took a step-up — over
         * what cut that axis, which is then not pushed (Unreal pushes what it walks into only when the step up failed). An
         * axis the step went no further along keeps its push: a step tried at a crate taller than it, a hair higher, comes
         * out an ulp further and is no step over it.
         */
        void collided(Vec3 result) {
            if (Double.isNaN(sweptX)) {
                return;
            }
            boolean overX = Math.abs(result.x) > Math.abs(sweptX) + STEPPED_OVER;
            boolean overZ = Math.abs(result.z) > Math.abs(sweptZ) + STEPPED_OVER;
            if (overX) {
                pushed[Direction.Axis.X.ordinal()] = null;
            }
            if (overZ) {
                pushed[Direction.Axis.Z.ordinal()] = null;
            }
            steppedUp = overX || overZ;
        }

        /** Whether it may step up onto {@code shape} — {@code null} for a block, or none — or stands on its body already. */
        boolean mayStepOnto(@Nullable Shape shape) {
            return shape == null || !shape.isValid() || shape.body() == base || canStepUpOnto(shape, entity);
        }

        /**
         * Whether a box at {@code end} rests on a shape it may not step up onto (its top within a {@link #SKIN} of the
         * box's bottom): where a step-up may not end.
         */
        boolean landsWhereItMayNotStep(AABB end) {
            Geometry.Box query = sweeping(end, Direction.Axis.Y);
            double cx = (end.minX + end.maxX) / 2, cy = (end.minY + end.maxY) / 2, cz = (end.minZ + end.maxZ) / 2;
            for (Shape shape : shapes) {
                if (!mayStepOnto(shape) && shape.castShape(query, cx, cy + SKIN, cz, NO_TURN, 0, -2 * SKIN, 0, true, hit)
                        && hit.normal(normal).y >= WALKABLE) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The move's end: each body it pushed, pushed — the inelastic collision of the two along the axis, the body by the
         * impulse at the point (a replica's, told to the server by its player's client; one the server moves pushes the
         * server's body from there itself), the entity by keeping the shared speed on a sideways axis nothing else cut,
         * which then is no collision to Minecraft.
         */
        void settle() {
            if (entity == null) {
                return;
            }
            Vec3 motion = entity.getDeltaMovement();
            double[] kept = {motion.x, motion.y, motion.z};
            boolean pushedSideways = false;
            for (Direction.Axis axis : AXES) {
                int i = axis.ordinal();
                Shape shape = pushed[i];
                if (shape == null || !shape.isValid() || !massOf(shape.body())) {
                    continue;
                }
                Body body = shape.body();
                double v = pushedBy[i];
                float sign = Math.signum((float) v);
                Vector3d at = pushedAt[i];
                boolean replica = SyncedBodies.isReplica(body);
                // a wall: against something that does not give. The server judges a replica's
                if (!replica && isPinned(body, axis == Direction.Axis.X ? sign : 0, axis == Direction.Axis.Y ? sign : 0,
                        axis == Direction.Axis.Z ? sign : 0)) {
                    stopped[i] = true;
                    continue;
                }
                body.pointVelocity(at.x, at.y, at.z, velocity);
                double bodyV = (axis == Direction.Axis.X ? velocity.x : axis == Direction.Axis.Y ? velocity.y : velocity.z) / 20;
                if ((v - bodyV) * sign <= 0) {
                    continue; // it is getting away as fast
                }
                float m = effectiveMass(body, at, axis, sign);
                float entityMass = entityMass();
                double shared = entityMass == Float.POSITIVE_INFINITY ? v : (entityMass * v + m * bodyV) / (entityMass + m);
                // blocks a tick to metres a second
                float impulse = (float) (m * (shared - bodyV) * 20);
                float ix = axis == Direction.Axis.X ? impulse : 0, iy = axis == Direction.Axis.Y ? impulse : 0,
                        iz = axis == Direction.Axis.Z ? impulse : 0;
                if (!replica) {
                    body.applyLinearImpulse(ix, iy, iz, at.x, at.y, at.z);
                } else if (entity.isControlledByLocalInstance() && EntityAuthority.clientMoves(entity)) {
                    // told to the server, which takes this client's word for where it is; not simulated here — walked on
                    // past what this client lacks (a body not synced), it would stand where the server holds the body,
                    // and nothing would pull it back
                    physics.sync().riders().pushed(body, ix, iy, iz, at);
                }
                if (axis != Direction.Axis.Y && !stopped[i]) {
                    kept[i] = shared;
                    pushedSideways = true;
                }
            }
            if (pushedSideways) {
                entity.setDeltaMovement(kept[0], kept[1], kept[2]);
                entity.horizontalCollision = stopped[Direction.Axis.X.ordinal()] || stopped[Direction.Axis.Z.ordinal()];
                if (!entity.horizontalCollision) {
                    entity.minorHorizontalCollision = false;
                }
            }
        }

        /**
         * The body's mass at {@code at} along the axis, kg: {@code 1 / m = 1 / mass + (r × a) · I⁻¹ (r × a)}, its turning
         * included, from {@link #massData} ({@link #massOf} first).
         */
        float effectiveMass(Body body, Vector3d at, Direction.Axis axis, float sign) {
            body.position(center);
            body.rotation(rotation);
            arm.set(massData.centerX, massData.centerY, massData.centerZ).rotate(rotation);
            arm.set((float) (at.x - center.x - arm.x), (float) (at.y - center.y - arm.y), (float) (at.z - center.z - arm.z));
            arm.cross(axis == Direction.Axis.X ? sign : 0, axis == Direction.Axis.Y ? sign : 0,
                    axis == Direction.Axis.Z ? sign : 0);
            float inverseMass = 1 / massData.mass;
            if (Math.abs(inverse.set(massData.inertia).determinant()) > 0) {
                // (r × a) · R I⁻¹ Rᵀ (r × a), worked in body space: Rᵀ (r × a) there
                rotation.transformInverse(arm);
                inverse.invert().transform(twist.set(arm));
                inverseMass += arm.dot(twist);
            }
            return 1 / inverseMass;
        }

        /** The body's mass into {@link #massData} if entities push it; false for one as heavy as a wall. */
        boolean massOf(Body body) {
            if (SyncedBodies.isReplica(body)) {
                return SyncedBodies.serverMass(body, massData) && massData.mass > 0;
            }
            if (!pushable(body)) {
                return false;
            }
            body.massData(massData);
            return massData.mass > 0 && Float.isFinite(massData.mass);
        }

        float entityMass() {
            if (Float.isNaN(mass)) {
                mass = physics.entities().massOf(entity);
            }
            return mass;
        }

        /** The heights above {@code box}'s bottom the tops of the bodies where the entity is going would lift it to. */
        float[] stepUpHeights(float[] heights, AABB box, float stepHeight) {
            if (stepHeight <= 0) {
                return heights;
            }
            // Unreal asks it of what stopped the move before it tries: a shape it may not step onto, and no step at all —
            // nor onto a block beside it
            if (!mayStepOnto(cutBy[Direction.Axis.X.ordinal()]) || !mayStepOnto(cutBy[Direction.Axis.Z.ordinal()])) {
                return new float[0];
            }
            // the whole box, not one a centimetre thinner: a move into a body by less than that is still cut, and a top it
            // did not find is a push instead of a step — then pushed on at its pace, a crate is never more than grazed again
            // (Minecraft takes every top in the move's way)
            Geometry.Box query = Geometry.Box.of((float) (box.getXsize() / 2), (float) (box.getYsize() / 2),
                    (float) (box.getZsize() / 2));
            // from a step's height above where the move goes, down
            double cx = (box.minX + box.maxX) / 2 + intentX, cz = (box.minZ + box.maxZ) / 2 + intentZ;
            double cy = (box.minY + box.maxY) / 2 + stepHeight;
            float[] more = null;
            int added = 0;
            for (Shape shape : shapes) {
                if (!shape.isValid() || !mayStepOnto(shape)
                        || !shape.castShape(query, cx, cy, cz, NO_TURN, 0, -stepHeight, 0, true, hit)) {
                    continue;
                }
                if (hit.normal(normal).y < WALKABLE) {
                    // inside it at that height, or a side rather than a top — or a face too steep to stand on (a tall
                    // crate tipping over as it is pushed): Unreal's step up onto an unwalkable floor is no step
                    continue;
                }
                float height = stepHeight * (1 - hit.fraction());
                // a top less than the skin up is no step: the move's own sweep slides over it already. One tried anyway —
                // the body it stands on, found a hair off its feet — tries the move again for nothing
                if (height >= SKIN && height <= stepHeight) {
                    if (more == null) {
                        more = new float[shapes.size()];
                    }
                    more[added++] = height;
                }
            }
            if (added == 0) {
                return heights;
            }
            float[] all = new float[heights.length + added];
            System.arraycopy(heights, 0, all, 0, heights.length);
            System.arraycopy(more, 0, all, heights.length, added);
            FloatArrays.unstableSort(all);
            return all;
        }

        /**
         * Out of a body that moved into the entity and stops it (one it pushes is let be: Box3D moves it off the entity's
         * proxy): the least way out along an axis that Minecraft's blocks and the other bodies allow, as a move of its own
         * before the entity's move.
         *
         * <p>⚠️ Not out of the base of an entity a mod carries itself ({@link EntityBases#carriesItself}) while it is
         * carrying it there: a deck that rose into its feet is what that mod's carry is about to lift it by, and pushed
         * out first it was lifted twice. In the air off it the mod does not carry it, and a lift rising into it pushes it
         * out as any body does.
         */
        void pushOut() {
            Entity entity = this.entity;
            AABB box = entity.getBoundingBox();
            Geometry.Box query = Geometry.Box.of((float) (box.getXsize() / 2), (float) (box.getYsize() / 2),
                    (float) (box.getZsize() / 2));
            double cx = (box.minX + box.maxX) / 2, cy = (box.minY + box.maxY) / 2, cz = (box.minZ + box.maxZ) / 2;
            Body ownBase = EntityBases.carriesItself(entity) ? EntityBases.carryingBase(entity) : null;
            for (Shape shape : shapes) {
                if (!shape.isValid() || shape.body() == ownBase || massOf(shape.body())
                        // a nudge, to tell inside (a hit at 0 with no normal) from touching
                        || !shape.castShape(query, cx, cy, cz, NO_TURN, 0, -1e-3f, 0, true, hit)
                        || hit.normal(normal).lengthSquared() >= 0.25f) {
                    continue;
                }
                double bestX = 0, bestY = 0, bestZ = 0, best = Double.POSITIVE_INFINITY;
                for (Direction direction : Direction.values()) {
                    double reach = PUSH_OUT_REACH + box.getSize();
                    double nx = direction.getStepX(), ny = direction.getStepY(), nz = direction.getStepZ();
                    // from outside along the direction back in: where it first meets the shape is where it is out
                    if (!shape.castShape(query, cx + nx * reach, cy + ny * reach, cz + nz * reach, NO_TURN,
                            (float) (-nx * reach), (float) (-ny * reach), (float) (-nz * reach), false, hit)
                            || hit.fraction() == 0) {
                        continue; // through it, or deeper than the reach
                    }
                    double out = reach * (1 - hit.fraction());
                    AABB moved = box.move(nx * out, ny * out, nz * out);
                    // not into blocks, nor into another body (touching the one it leaves is fine)
                    if (out < best && entity.level().noCollision(entity, moved)
                            && !anyBodyIn(entity.level(), moved.deflate(2 * SKIN), entity)) {
                        best = out;
                        bestX = nx * out;
                        bestY = ny * out;
                        bestZ = nz * out;
                    }
                }
                if (best < Double.POSITIVE_INFINITY) {
                    entity.setPos(entity.getX() + bestX, entity.getY() + bestY, entity.getZ() + bestZ);
                    return; // one a move; the next move sees what is left
                }
            }
        }
    }
}
