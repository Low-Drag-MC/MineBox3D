package com.lowdragmc.minebox3d.mc.entity;

import com.lowdragmc.minebox3d.mc.CollisionCategories;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelThreads;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.ContactPoints;
import com.lowdragmc.minebox3d.physics.FilterJointDef;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.Joint;
import com.lowdragmc.minebox3d.physics.MassData;
import com.lowdragmc.minebox3d.physics.MotionLockJointDef;
import com.lowdragmc.minebox3d.physics.MotorJointDef;
import com.lowdragmc.minebox3d.physics.Overlaps;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.Shape;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>Minecraft entities as kinematic bodies</b> near the bodies that can hit them (design doc
 * {@code 00-overview.md} §5).
 *
 * <p>Every tick: the entities around each body {@linkplain LevelBodies#pushedByEntities pushed by entities}
 * (its bounds grown by {@link Settings#reach()}) get a proxy if they have none; every proxy is driven with
 * {@code SetTargetTransform} — by velocity, so a box the player walks into is pushed rather than overlapped — to where
 * its entity will be when the tick's steps end (its position plus the motion it moves by next; a player, whose client
 * moves it, to where it is); proxies whose entity has gone or left the neighbourhood for {@link Settings#keepTicks()}
 * are removed. An entity that moved further than {@link Settings#teleportDistance()} in a tick is teleported instead,
 * so a /tp does not fling everything along its path.
 *
 * <h2>Bodies push entities back (design doc {@code 04-entities-and-bodies.md} §2)</h2>
 * <p>After every step ({@link #afterStep()}) each proxy's contacts with dynamic bodies are read; after the tick's steps
 * ({@link #afterSteps()}) each body that ran into an entity is settled as the inelastic collision of the two. Box3D,
 * which sees a proxy as kinematic (immovable), stopped the body against it with some impulse; the body gets back the
 * part the entity's lightness lets it keep, at the points it was taken, and the entity's velocity changes by the rest
 * before its next move (server only, through {@link EntityPushedByBodyEvent}). Momentum is kept and no energy is made:
 * a light crate slid into a player nudges it; a block of iron hardly notices it and pushes it ahead.
 *
 * <p>Only a body that is itself moving at the entity pushes: an entity walking into a resting body is not pushed back
 * (that is its own collision — doc 04 §3), and one running into a light body coming at it takes only what that body
 * has to give. An entity that cannot give is met as Box3D meets it, as a wall: one standing on something is not pushed
 * down into it, and one that was pushed and hardly moved (a wall, a fence behind it, a cancelled push) is held that way
 * for a tick. The entity's mass is {@link EntityShapes#massOf}.
 *
 * <h2>Force-capped while it comes onto a body ({@link Settings#strength}; doc 04 §18)</h2>
 * <p>A kinematic proxy is immovable: driven into a body that cannot get away — a crate against a wall, a crate tipped
 * under a landing player — it squeezes it, and Box3D lets it out at tens of metres a second. So the tick a proxy is
 * about to sweep near a dynamic body it is made dynamic, of its entity's mass, pulled after a kinematic target by a
 * motor joint of at most the strength and kept upright by a motion-lock joint to it: a body that cannot give stops it.
 * Back to kinematic a few ticks after it last touched one, once caught up with its entity — or put where its entity is
 * if no body it pushes is there, at once if it lags a quarter block behind. Touching nothing it stays kinematic, at no
 * extra cost. Never for an entity whose moves push no body ({@link EntityAuthority#pushesBodies}): its proxy is put
 * where it is each update, not driven.
 *
 * <p>⚠️ Proxy shapes have hit events on: Box3D measures how fast a pair came together only for pairs that report hits
 * ({@code ContactPoints.normalVelocity}). So {@code events().contactHits()} also lists bodies hitting entities above
 * the world's hit threshold.
 */
public final class EntityProxies implements AutoCloseable {

    /** m/s: slower than this, a body resting against an entity, not pushing it. */
    private static final float PUSH_SPEED = 0.1f;
    /** Ticks a push to an entity whose pushes show late counts as going away before it shows: a generous round trip. */
    private static final int UNECHOED_TICKS = 6;
    /** Ticks a proxy stays force-capped after it last came onto a body: not made and unmade at every graze. */
    private static final int CAPPED_TICKS = 5;
    /** How stiffly a force-capped proxy is pulled after its entity: Hz, critically damped. */
    private static final float PULL_HERTZ = 20;
    /** Metres from where its entity was driven to that a force-capped proxy counts as having caught up. */
    private static final double CAUGHT_UP = 0.05;
    /** Metres round a proxy's sweep a body caps it from: Box3D's speculative reach and some. */
    private static final double LOOK_MARGIN = 0.1;
    /** Metres a force-capped proxy may lag before it is put where its entity is, if no body it pushes is there. */
    private static final double LAGS = 0.25;
    /** Metres in from an entity's faces it is asked what it is in: touching is not being in. */
    private static final float SKIN = 0.02f;
    /** Metres an entity on the ground rose since the last update that is a step up (or a lift). */
    private static final double STEPPED = 1.0e-4;

    private static final Quaternionfc UPRIGHT = new Quaternionf();
    /** A joint frame whose z axis is its body's y axis. */
    private static final Quaternionfc Z_TO_Y = new Quaternionf().rotationX((float) (-Math.PI / 2));

    private final Level level;
    private final PhysicsWorld world;
    private final Map<Entity, Proxy> proxies = new IdentityHashMap<>();
    private final Settings settings = new Settings();
    private final List<AABB> neighbourhoods = new ArrayList<>();
    private final float[] bounds = new float[6];
    private final Quaternionf rotation = new Quaternionf();
    private final ContactPoints contacts = new ContactPoints();
    private final Overlaps overlaps = new Overlaps(4);
    private final Vector3f normal = new Vector3f();
    private final Vector3f anchor = new Vector3f();
    private final Vector3d center = new Vector3d();
    private final Vector3f baseVelocity = new Vector3f();
    /** whether the proxy's entity steps up in its predicted move (EntityCollisions.predict) */
    private final boolean[] stepping = new boolean[1];
    /** this update's dynamic bodies entities push, and their bounds, six floats each */
    private Body[] movableBodies = new Body[16];

    private float[] movableBounds = new float[6 * 16];
    private int movable;

    private long tick;

    /** How the proxies behave; one per world. */
    public static final class Settings {
        private float reach = 2.0f;
        private int keepTicks = 20;
        private double teleportDistance = 2.0;
        private boolean pushEntities = true;
        private float entityDensity = 110;
        private float strength;

        private Settings() {}

        /**
         * N per kg of the entity: the most its proxy pushes a dynamic body with (the class notes), as Box3D's dynamic
         * mover and Unreal's Chaos mover cap a character's force. 0, the default: a kinematic proxy always, which
         * squeezes a body held fast. A level's own physics sets {@code LevelPhysics.ENTITY_STRENGTH}.
         */
        public float strength() {
            return strength;
        }

        public Settings strength(float newtonsPerKilogram) {
            if (!(newtonsPerKilogram >= 0) || !Float.isFinite(newtonsPerKilogram)) {
                throw new IllegalArgumentException("strength must be zero or more: " + newtonsPerKilogram);
            }
            this.strength = newtonsPerKilogram;
            return this;
        }

        /**
         * Whether bodies push the entities they run into (doc 04 §2); the server's to say. On in a level's own
         * physics, off in a private one ({@code LevelPhysics.create}): what happens in a mod's own simulation stays in
         * it unless the mod turns this on.
         */
        public boolean pushEntities() {
            return pushEntities;
        }

        public Settings pushEntities(boolean push) {
            this.pushEntities = push;
            return this;
        }

        /**
         * kg per m³ of collision box, for an entity no provider gives a mass ({@link EntityShapes#massOf}): the default
         * 110 makes a player about 70 kg. Used when a proxy is made or resized.
         */
        public float entityDensity() {
            return entityDensity;
        }

        public Settings entityDensity(float density) {
            if (!(density > 0) || !Float.isFinite(density)) {
                throw new IllegalArgumentException("density must be positive: " + density);
            }
            this.entityDensity = density;
            return this;
        }

        /** How far around a body entities get proxies, in blocks. */
        public float reach() {
            return reach;
        }

        public Settings reach(float reach) {
            if (!(reach >= 0) || !Float.isFinite(reach)) {
                throw new IllegalArgumentException("reach must be zero or more: " + reach);
            }
            this.reach = reach;
            return this;
        }

        /** Updates a proxy stays after its entity left every neighbourhood. */
        public int keepTicks() {
            return keepTicks;
        }

        public Settings keepTicks(int ticks) {
            if (ticks < 0) {
                throw new IllegalArgumentException("keep ticks must be zero or more: " + ticks);
            }
            this.keepTicks = ticks;
            return this;
        }

        /**
         * An entity moving further than this in one update is teleported, not swept; 2 blocks by default, 40 m/s.
         * Swept further — a /tp, a chorus fruit — a kinematic proxy flings what lies on the way, a force-capped one
         * drags it.
         */
        public double teleportDistance() {
            return teleportDistance;
        }

        public Settings teleportDistance(double distance) {
            if (!(distance > 0) || !Double.isFinite(distance)) {
                throw new IllegalArgumentException("teleport distance must be positive: " + distance);
            }
            this.teleportDistance = distance;
            return this;
        }
    }

    public EntityProxies(Level level, PhysicsWorld world) {
        this.level = level;
        this.world = world;
    }

    public Settings settings() {
        return settings;
    }

    /**
     * The proxies' tick: on the level's thread (it reads entities), holding the world.
     *
     * @param timeStep the time the coming steps cover, over which each proxy reaches its entity
     */
    public void update(float timeStep) {
        if (!LevelThreads.isLevelThread(level)) {
            throw new WrongThreadException(
                    "the entities of " + level.dimension().location() + " are read on its level's thread, not "
                            + Thread.currentThread().getName());
        }
        tick++;
        applyPushes(); // of steps taken off the level's thread
        float reach = settings.reach;
        // the neighbourhoods first: creating a proxy adds a body to the collection being walked
        neighbourhoods.clear();
        movable = 0;
        for (Body body : world.bodies()) {
            if (!LevelBodies.pushedByEntities(body) || body.attachment(Proxy.class) != null) {
                continue;
            }
            body.bounds(bounds);
            neighbourhoods.add(new AABB(
                    bounds[0] - reach,
                    bounds[1] - reach,
                    bounds[2] - reach,
                    bounds[3] + reach,
                    bounds[4] + reach,
                    bounds[5] + reach));
            if (settings.strength > 0 && body.type() == BodyType.DYNAMIC) {
                if (movable == movableBodies.length) {
                    movableBodies = Arrays.copyOf(movableBodies, 2 * movable);
                    movableBounds = Arrays.copyOf(movableBounds, 12 * movable);
                }
                movableBodies[movable] = body;
                System.arraycopy(bounds, 0, movableBounds, 6 * movable++, 6);
            }
        }
        for (AABB around : neighbourhoods) {
            for (Entity entity : level.getEntities((Entity) null, around, e -> !EntityShapes.isExcluded(e))) {
                Proxy proxy = proxies.get(entity);
                if (proxy == null) {
                    proxy = create(entity);
                    if (proxy == null) {
                        continue;
                    }
                    proxies.put(entity, proxy);
                }
                proxy.lastSeen = tick;
            }
        }

        double teleport = settings.teleportDistance;
        for (Iterator<Map.Entry<Entity, Proxy>> it = proxies.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, Proxy> entry = it.next();
            Entity entity = entry.getKey();
            Proxy proxy = entry.getValue();
            if (!proxy.body.isValid()) {
                // someone else destroyed it: made again next time the entity is near a body, not driven (which would
                // throw and stop the level's physics)
                discard(proxy);
                it.remove();
                continue;
            }
            if (entity.isRemoved()
                    || entity.level() != level
                    || proxy.lastSeen < tick - settings.keepTicks
                    || EntityShapes.isExcluded(entity)) {
                discard(proxy);
                it.remove();
                continue;
            }
            if ((proxy.width != entity.getBbWidth() || proxy.height != entity.getBbHeight())
                    && !reshape(entity, proxy)) {
                discard(proxy);
                Proxy rebuilt = create(entity);
                if (rebuilt == null) {
                    it.remove();
                    continue;
                }
                rebuilt.lastSeen = proxy.lastSeen;
                entry.setValue(rebuilt);
                proxy = rebuilt;
            }
            rotationOf(entity, proxy, rotation);
            double x = entity.getX(), y = entity.getY(), z = entity.getZ();
            double dx = x - proxy.lastX, dy = y - proxy.lastY, dz = z - proxy.lastZ;
            proxy.clientMoved = isClientMoved(entity);
            proxy.pushesShowLate = EntityAuthority.pushesShowLate(entity);
            readFreedom(proxy, entity, dx, dy, dz);
            double tx = x, ty = y, tz = z;
            stepping[0] = false;
            if (!level.isClientSide && !proxy.clientMoved && proxy.mass != Float.POSITIVE_INFINITY) {
                // where the entity will be when the coming steps end, not where it is: a body that has just pushed it
                // would otherwise meet the proxy standing still for a tick and be stopped dead. Its motion cut as
                // Minecraft's collision will cut it (the ground, a wall): a proxy driven past either springs back the
                // tick after, flinging whatever rests on it. Not for a client-moved entity (its motion is not on the
                // server), a client's entities (a remote mob's is stale) or an immovable one (a no-AI mob's piles up)
                Vec3 motion = entity.getDeltaMovement();
                if (motion.lengthSqr() > 1.0e-10) {
                    // and as the bodies will: into a crate it pushes by the share its mass gives (EntityCollisions), so
                    // the proxy pushes the crate by as much — up to it, for one whose moves push no body
                    Vec3 allowed = EntityCollisions.predict(entity, motion, stepping);
                    tx += allowed.x;
                    ty += allowed.y;
                    tz += allowed.z;
                }
            }
            // and where the body it stands on carries it (EntityBases) — its client's move does too — so the two go on
            // together rather than the body being held back by its rider
            Body base = EntityBases.carryingBase(entity);
            if (base != null && base.world() == world) {
                base.pointVelocity(x, y, z, baseVelocity);
                tx += baseVelocity.x * timeStep;
                ty += baseVelocity.y * timeStep;
                tz += baseVelocity.z * timeStep;
            }
            boolean pushes = EntityAuthority.pushesBodies(entity);
            weigh(proxy, entity, pushes);
            ignoreBase(proxy, base != null && base.world() == world && base.type() == BodyType.DYNAMIC ? base : null);
            // one whose moves push no body is put where its entity is, not driven there: driven, its straight path cuts
            // the corner its entity steps up around (onto a crate) and flings what it cuts; force-capped, it carries
            // its speed into a crate it walks up to. Put, it only stands in the way, and Box3D lets out what it
            // overlaps no faster than its contact speed. So is one stepping up — in its next move as predicted, or in
            // its last (a client moves a player) — and once more after, should the predicted step not come
            boolean stepped = stepping[0] || proxy.wasOnGround && entity.onGround() && dy > STEPPED;
            boolean teleported =
                    !pushes || stepped || proxy.stepped || dx * dx + dy * dy + dz * dz > teleport * teleport;
            proxy.stepped = stepped;
            proxy.wasOnGround = entity.onGround();
            boolean capping = settings.strength > 0 && proxy.mass < Float.POSITIVE_INFINITY && !teleported;
            if (capping && tick - proxy.pressedTick > CAPPED_TICKS && comesOntoABody(proxy, entity, tx, ty, tz)) {
                proxy.pressedTick = tick;
            }
            if (proxy.capped && !(proxy.target.isValid() && proxy.drive.isValid() && proxy.upright.isValid())) {
                // another mod destroyed its target or a joint: driving it would throw and stop the level's physics
                uncap(proxy);
                cap(proxy);
            }
            if (proxy.capped && !teleported && lagsBehind(proxy) && !intoABody(proxy, entity, tx, ty, tz)) {
                // its entity went where it could not follow — off the edge it is perched on (its own collision a hair
                // apart from Box3D's), through what it does not push (a static or kinematic body) — and nothing it
                // pushes is there: held, it would press on what holds it for good
                proxy.pressedTick = Long.MIN_VALUE / 2;
                uncap(proxy);
                teleported = true;
            }
            boolean capped = capping && tick - proxy.pressedTick <= CAPPED_TICKS;
            if (capped && !proxy.capped) {
                cap(proxy);
            } else if (!capped && proxy.capped) {
                if (!capping || caughtUp(proxy)) {
                    uncap(proxy);
                } else if (!intoABody(proxy, entity, tx, ty, tz)) {
                    // touching no dynamic body for a while and still off its entity: held off by a static one
                    uncap(proxy);
                    teleported = true;
                }
            }
            if (teleported) {
                proxy.body.setTransform(tx, ty, tz, rotation);
                // setTransform keeps the velocity it was last driven with
                proxy.body.setLinearVelocity(0, 0, 0);
                proxy.pressedTick = Long.MIN_VALUE / 2;
                proxy.kinematicX = proxy.kinematicY = proxy.kinematicZ = 0;
            } else {
                proxy.kinematicX = (float) ((tx - proxy.targetX) / timeStep);
                proxy.kinematicY = (float) ((ty - proxy.targetY) / timeStep);
                proxy.kinematicZ = (float) ((tz - proxy.targetZ) / timeStep);
                if (proxy.capped) {
                    drive(proxy, tx, ty, tz, rotation, timeStep);
                } else {
                    proxy.body.setTargetTransform(tx, ty, tz, rotation, timeStep);
                }
            }
            proxy.lastX = x;
            proxy.lastY = y;
            proxy.lastZ = z;
            proxy.targetX = tx;
            proxy.targetY = ty;
            proxy.targetZ = tz;
        }
        Arrays.fill(movableBodies, 0, movable, null);
    }

    /**
     * An entity standing on a body it can push presses on it with its weight over the coming steps
     * ({@link #beforeStep}): a plank on a pivot tips under whoever stands on its end. (Box3D meets the proxy as a wall
     * it can lean on, not as a weight.) Where its feet meet the body: the body's centre as near as the feet reach. Not
     * one whose moves push no body ({@link EntityAuthority#pushesBodies}).
     */
    private void weigh(Proxy proxy, Entity entity, boolean pushes) {
        proxy.weighs = null;
        Body base = pushes && entity.onGround() ? EntityBases.baseOf(entity) : null;
        if (base == null
                || base.world() != world
                || !EntityCollisions.pushable(base)
                || !(proxy.mass < Float.POSITIVE_INFINITY)) {
            return;
        }
        // Minecraft's gravity is blocks a tick a tick; a block is a metre
        float weight = (float) (proxy.mass * entity.getGravity() * 400);
        if (weight > 0) {
            AABB feet = entity.getBoundingBox();
            base.worldCenter(center);
            proxy.weighs = base;
            proxy.weight = weight;
            proxy.weighX = Math.clamp(center.x, feet.minX, feet.maxX);
            proxy.weighY = feet.minY;
            proxy.weighZ = Math.clamp(center.z, feet.minZ, feet.maxZ);
        }
    }

    /**
     * A proxy and the dynamic body that carries its entity do not collide (a filter joint), as Unreal's movement base:
     * the body carries the entity and bears its weight ({@link #weigh}). ⚠️ Touching, the proxy — kinematic, so of no
     * finite mass — would drag the body by friction, and whenever the entity's position jumps (a client-moved one put
     * on the body's frame) slam it away at the jump's speed.
     */
    private void ignoreBase(Proxy proxy, @Nullable Body base) {
        if (proxy.ignoredBase == base) {
            return;
        }
        if (proxy.ignoring != null && proxy.ignoring.isValid()) {
            proxy.ignoring.destroy(false);
        }
        proxy.ignoring = base == null ? null : world.createJoint(new FilterJointDef().bodies(proxy.body, base));
        proxy.ignoredBase = base;
    }

    /**
     * Before every step, holding the world (any thread): the weights of the entities standing on bodies they can push,
     * as forces. ⚠️ Not one impulse a tick: on a light body near its edge that would be a kick it spins away from.
     */
    public void beforeStep() {
        for (Proxy proxy : proxies.values()) {
            Body base = proxy.weighs;
            if (base != null && base.isValid()) {
                base.applyForce(0, -proxy.weight, 0, proxy.weighX, proxy.weighY, proxy.weighZ);
            }
        }
    }

    // ---- bodies pushing entities ----------------------------------------------------------------

    /**
     * After every step, holding the world (any thread): which bodies ran into which entities this step — how fast,
     * along which normal, and the impulse Box3D stopped them with, point by point (on the server, with
     * {@link Settings#pushEntities}). A pair's points are taken together along the point that came together fastest;
     * a pair met in several steps of a tick keeps its fastest. Settled once a tick by {@link #afterSteps()}. On either
     * side, whether a force-capped proxy still touches one.
     */
    public void afterStep() {
        boolean pushes = settings.pushEntities && !level.isClientSide;
        for (Proxy proxy : proxies.values()) {
            if (!(pushes || proxy.capped) || !proxy.body.isValid() || proxy.mass == Float.POSITIVE_INFINITY) {
                continue;
            }
            int count = proxy.body.contactPoints(contacts);
            for (int i = 0; i < count; i++) {
                Body other = otherBody(i);
                if (other == null) {
                    continue;
                }
                if (proxy.capped) {
                    proxy.pressedTick = tick; // still on it: capped on, whichever way it goes
                }
                if (pushes && !seenBefore(i, other)) {
                    observe(proxy, other, i, count);
                }
            }
        }
    }

    /**
     * Once a tick, after its steps, holding the world: every body that ran into an entity, settled as an inelastic
     * collision (the class notes) by giving back part of the impulse Box3D stopped it with, and the entity given its
     * share — on the level's thread, so before its next move; stepped on another, at the next {@link #update}.
     */
    public void afterSteps() {
        for (Proxy proxy : proxies.values()) {
            for (Hit hit : proxy.hits) {
                settle(proxy, hit);
            }
            proxy.hits.clear();
        }
        if (LevelThreads.isLevelThread(level)) {
            applyPushes();
        }
    }

    /** The dynamic body point {@code i} touches; {@code null} for terrain, a static or kinematic body, a proxy. */
    @Nullable
    private Body otherBody(int i) {
        Shape shape = contacts.otherShape(i);
        if (shape == null) {
            return null;
        }
        Body body = shape.body();
        return body.type() == BodyType.DYNAMIC && body.attachment(Proxy.class) == null ? body : null;
    }

    private boolean seenBefore(int i, Body other) {
        for (int j = 0; j < i; j++) {
            if (otherBody(j) == other) {
                return true;
            }
        }
        return false;
    }

    private void observe(Proxy proxy, Body other, int first, int count) {
        // the pair's points: the fastest one's normal and approach, the step's whole impulse
        int fastest = first;
        float impulse = 0;
        for (int j = first; j < count; j++) {
            if (otherBody(j) == other) {
                impulse += contacts.totalNormalImpulse(j);
                if (contacts.normalVelocity(j) < contacts.normalVelocity(fastest)) {
                    fastest = j;
                }
            }
        }
        if (!(impulse > 0)) {
            return; // close enough to count as touching, not touching: Box3D did not push
        }
        if (other == EntityBases.baseOf(proxy.entity)) {
            return; // the body it stands on carries it (EntityBases), and its proxy goes along
        }
        // the normal points from the body into the entity: the way the entity is pushed
        contacts.normal(fastest, normal);
        if (proxy.supported && normal.y < -0.5f) {
            return; // pressing it into what it stands on: it cannot give
        }
        if (normal.dot(proxy.heldX, proxy.heldY, proxy.heldZ) > 0.7f) {
            return; // the way it was pushed and did not go: something holds it
        }
        // the pair closed at -normalVelocity against the proxy as it was driven (the entity's motion); the body's own
        // speed is that plus the proxy's
        float approach = -contacts.normalVelocity(fastest);
        float bodySpeed = approach + normal.dot(proxy.kinematicX, proxy.kinematicY, proxy.kinematicZ);
        if (bodySpeed <= PUSH_SPEED || approach <= PUSH_SPEED) {
            return; // it rests there, or the entity walked into it
        }
        float scale = 1;
        if (proxy.unechoedX != 0 || proxy.unechoedY != 0 || proxy.unechoedZ != 0) {
            // pushed, the push not shown yet (pushesShowLate): it is going away at that push, whatever its proxy does
            float away = normal.dot(proxy.unechoedX, proxy.unechoedY, proxy.unechoedZ);
            if (away > 0) {
                if (approach - away <= PUSH_SPEED) {
                    return;
                }
                scale = (approach - away) / approach;
                approach -= away;
            }
        }
        Hit hit = null;
        for (Hit known : proxy.hits) {
            if (known.body == other) {
                hit = known;
                break;
            }
        }
        if (hit == null) {
            hit = new Hit(other);
            proxy.hits.add(hit);
        } else if (approach <= hit.approach) {
            return;
        }
        hit.approach = approach;
        hit.impulse = impulse * scale;
        hit.normal.set(normal);
        // where Box3D pushed the body, and how hard, to give its share back at the same points: no spin it did not have
        proxy.body.worldCenter(center);
        hit.points = 0;
        for (int j = first; j < count; j++) {
            if (otherBody(j) == other) {
                contacts.anchor(j, anchor);
                hit.add(
                        center.x + anchor.x,
                        center.y + anchor.y,
                        center.z + anchor.z,
                        contacts.totalNormalImpulse(j) * scale);
            }
        }
    }

    /**
     * Box3D stopped the body against the proxy with impulse {@code P}, as against a wall, at a relative speed
     * {@code v}: the body's mass there, rotation included, is {@code m = P / v}. Against an entity of mass {@code M}
     * the collision would have moved both on together: the entity by {@code P / (m + M)}, and the body would have kept
     * {@code m / (m + M)} of {@code P} — given back at the points Box3D took it at. Nothing is given back that Box3D
     * did not take.
     */
    private void settle(Proxy proxy, Hit hit) {
        Body other = hit.body;
        if (!other.isValid() || !proxy.body.isValid() || other.type() != BodyType.DYNAMIC) {
            return;
        }
        float bodyMass = hit.impulse / hit.approach;
        float entityShare = 1 / (bodyMass + proxy.mass);
        float change = hit.impulse * entityShare;
        Vector3f n = hit.normal;
        // pushes along one way do not add up (two bodies side by side): each asks for its own, only what is missing
        // is added
        float missing = change - n.dot(proxy.pushX, proxy.pushY, proxy.pushZ);
        if (missing > 0) {
            proxy.pushX += missing * n.x;
            proxy.pushY += missing * n.y;
            proxy.pushZ += missing * n.z;
        }
        if (change > proxy.strongest) {
            proxy.strongest = change;
            proxy.pusher = other;
        }
        proxy.approach = Math.max(proxy.approach, hit.approach);
        if (proxy.capped) {
            return; // a force-capped proxy has a mass: Box3D shared the collision between the two itself
        }
        float bodyShare = bodyMass * entityShare;
        for (int i = 0; i < hit.points; i++) {
            float back = hit.pointImpulses[i] * bodyShare;
            other.applyLinearImpulse(
                    back * n.x, back * n.y, back * n.z, hit.pointXs[i], hit.pointYs[i], hit.pointZs[i]);
        }
    }

    /** Whether the entity's movement is its client's, not the server's ({@link EntityAuthority}). */
    private static boolean isClientMoved(Entity entity) {
        return EntityAuthority.clientMoves(entity);
    }

    /** A body that ran into an entity in this tick's steps, at its fastest: its normal, speed, impulse per point. */
    private static final class Hit {
        final Body body;
        final Vector3f normal = new Vector3f();
        float approach, impulse;
        int points;
        double[] pointXs = new double[4], pointYs = new double[4], pointZs = new double[4];
        float[] pointImpulses = new float[4];

        Hit(Body body) {
            this.body = body;
        }

        void add(double x, double y, double z, float impulse) {
            if (points == pointXs.length) {
                pointXs = Arrays.copyOf(pointXs, 2 * points);
                pointYs = Arrays.copyOf(pointYs, 2 * points);
                pointZs = Arrays.copyOf(pointZs, 2 * points);
                pointImpulses = Arrays.copyOf(pointImpulses, 2 * points);
            }
            pointXs[points] = x;
            pointYs[points] = y;
            pointZs[points] = z;
            pointImpulses[points] = impulse;
            points++;
        }
    }

    /** The pushes the tick's steps gave, onto the entities: on the level's thread. */
    private void applyPushes() {
        for (Proxy proxy : proxies.values()) {
            if (proxy.pusher == null) {
                continue;
            }
            Body pusher = proxy.pusher;
            Vec3 change = new Vec3(proxy.pushX, proxy.pushY, proxy.pushZ);
            float approach = proxy.approach;
            proxy.clearPush();
            if (!settings.pushEntities || proxy.entity.isRemoved() || proxy.entity.level() != level) {
                continue;
            }
            EntityPushedByBodyEvent event = NeoForge.EVENT_BUS.post(new EntityPushedByBodyEvent(
                    proxy.entity, pusher, change, approach, (float) change.length() * proxy.mass));
            proxy.pushTick = proxy.entity.tickCount;
            if (event.isCanceled()) {
                // it did not go that way: next tick it holds there like a wall (readFreedom), rather than the body
                // being handed back a velocity every tick for a push that is never taken
                proxy.pushGiven = change;
                continue;
            }
            Vec3 velocity = event.getVelocityChange();
            // Minecraft's motion is blocks a tick; the way vanilla knocks back: hasImpulse for the motion packet, and a
            // player's own client is told through hurtMarked
            proxy.entity.push(velocity.x / 20, velocity.y / 20, velocity.z / 20);
            if (proxy.entity instanceof ServerPlayer) {
                proxy.entity.hurtMarked = true;
            }
            proxy.pushGiven = velocity;
            if (EntityAuthority.pushesShowLate(proxy.entity)) {
                // it moves by it some ticks from now (its client a round trip on; a mod's own schedule): until then it
                // is going away at this push whatever its proxy does (observe), rather than pushed again by the same
                // body for the same hit
                proxy.unechoedX += (float) velocity.x;
                proxy.unechoedY += (float) velocity.y;
                proxy.unechoedZ += (float) velocity.z;
                proxy.unechoedTicks = 0;
            }
        }
    }

    /**
     * What the entity can give, read on the level's thread before the steps: standing on something it cannot be
     * pushed down into it; pushed last tick and hardly moved that way, something holds it there (a wall, a fence,
     * another mob) — either way a body running into it that way meets it as Box3D does, as a wall, rather than being
     * handed back a velocity it would only drive into the entity again.
     */
    private void readFreedom(Proxy proxy, Entity entity, double dx, double dy, double dz) {
        proxy.supported = entity.onGround() && EntityBases.baseOf(entity) != null
                || !level.noCollision(entity, entity.getBoundingBox().move(0, -0.05, 0));
        proxy.heldX = proxy.heldY = proxy.heldZ = 0;
        if (proxy.pushesShowLate) {
            // its move of a push comes late: not seen moving is not held. The push it has not shown yet is let go once
            // it shows, or after UNECHOED_TICKS
            float unechoed = (float) Math.sqrt(proxy.unechoedX * proxy.unechoedX
                    + proxy.unechoedY * proxy.unechoedY
                    + proxy.unechoedZ * proxy.unechoedZ);
            if (unechoed > 0
                    && (++proxy.unechoedTicks > UNECHOED_TICKS
                            || (dx * proxy.unechoedX + dy * proxy.unechoedY + dz * proxy.unechoedZ) / unechoed
                                    >= 0.5 * unechoed / 20)) {
                proxy.unechoedX = proxy.unechoedY = proxy.unechoedZ = 0;
            }
            proxy.pushGiven = null;
            return;
        }
        // the push given last, against the move the entity made since — once it has ticked: one that has not ticked has
        // not moved at all, held or not (an entity in a chunk that does not tick entities)
        Vec3 push = null;
        if (proxy.pushGiven != null && entity.tickCount != proxy.pushTick) {
            push = proxy.pushGiven;
            proxy.pushGiven = null;
        }
        if (push != null) {
            double length = push.length();
            double went = (dx * push.x + dy * push.y + dz * push.z) / length;
            // its first tick of the push moves it the push's whole twentieth
            if (length > 0 && went < 0.25 * length / 20) {
                proxy.heldX = (float) (push.x / length);
                proxy.heldY = (float) (push.y / length);
                proxy.heldZ = (float) (push.z / length);
            }
        }
    }

    @Nullable
    private Proxy create(Entity entity) {
        EntityShapes.Answer answer = EntityShapes.shapesOf(entity);
        if (answer.shapes().isEmpty()) {
            return null;
        }
        EntityShapes.Provider provider = answer.provider();
        List<Geometry> shapes = answer.shapes();
        Proxy proxy = new Proxy(entity, provider, EntityShapes.massOf(entity, settings.entityDensity));
        Quaternionf rotation = rotationOf(entity, proxy, new Quaternionf());
        // not falling when force-capped (cap): the entity's own movement does that
        BodyDef def0 = new BodyDef()
                .type(BodyType.KINEMATIC)
                .position(entity.getX(), entity.getY(), entity.getZ())
                .rotation(rotation)
                .name("entity")
                .gravityScale(0);
        proxy.body = world.createBody(def0);
        // said for when it is dynamic (cap): it meets no terrain, and the default would stream terrain in round it
        LevelBodies.setNeedsTerrain(proxy.body, false);
        // the bodies that never meet this entity (LevelBodies.setIgnoredEntity) never meet its proxy; before the
        // shapes, so none has pairs to find again
        proxy.body.setTag(EntityShapes.tagOf(entity));
        ShapeDef def = new ShapeDef();
        def.filter.categoryBits = CollisionCategories.ENTITY;
        // a proxy meets neither terrain nor another proxy, even force-capped (dynamic), and a query made as either
        // (EntityCollisions.FILTER) misses it
        def.filter.maskBits = ~(CollisionCategories.TERRAIN | CollisionCategories.ENTITY);
        // the tags are asked of a pair only where one of its shapes asks the custom filter: the proxy always does, so a
        // body ignoring this entity is never met whatever its own shapes say (a new pair asks once)
        def.enableCustomFiltering = true;
        // Box3D's default, said out loud: it reports a touch when either shape asks, and a body that wants to hear
        // about entities asks itself
        def.enableContactEvents = false;
        // not for the events: pushing the entity back needs normalVelocity (the class notes)
        def.enableHitEvents = true;
        for (Geometry geometry : shapes) {
            proxy.body.createShape(def, geometry);
        }
        proxy.body.attach(Proxy.class, proxy);
        return proxy;
    }

    /**
     * An entity that changed size (crouching, a baby growing up) keeps its proxy: the same body, each shape given its
     * new geometry in place, and the mass asked again. So a body or joint another mod holds stays good, and so does
     * what the proxy remembers of the pushes it was given. {@code false}, and nothing changed, where it cannot be:
     * another provider answers now, or with another number of shapes, with none, or with a height field.
     */
    private boolean reshape(Entity entity, Proxy proxy) {
        EntityShapes.Answer answer = EntityShapes.shapesOf(entity);
        List<Geometry> next = answer.shapes();
        List<Shape> shapes = proxy.body.shapes();
        if (answer.provider() != proxy.provider || next.isEmpty() || next.size() != shapes.size()) {
            return false;
        }
        for (Geometry geometry : next) {
            if (geometry instanceof Geometry.HeightField) {
                return false; // Box3D cannot turn a shape into one
            }
        }
        // capped again where it is, of the new mass: made kinematic, it would be driven the way it lags behind at once
        boolean capped = proxy.capped;
        if (capped) {
            uncap(proxy);
        }
        for (int i = 0; i < next.size(); i++) {
            shapes.get(i).setGeometry(next.get(i), false);
        }
        proxy.width = entity.getBbWidth();
        proxy.height = entity.getBbHeight();
        proxy.mass = EntityShapes.massOf(entity, settings.entityDensity);
        if (capped && proxy.mass < Float.POSITIVE_INFINITY) {
            cap(proxy);
        }
        return true;
    }

    /**
     * The proxy made dynamic, of its entity's mass, pulled after a kinematic target by a motor joint of at most
     * {@link Settings#strength} per kg, and kept upright by a motion-lock joint to the target. A spring, not only a
     * velocity drive: a velocity drive alone still lets crates out (doc 04 §18).
     */
    private void cap(Proxy proxy) {
        Vector3d at = proxy.body.position(new Vector3d());
        Quaternionf turn = proxy.body.rotation(new Quaternionf());
        Vector3f moving = proxy.body.linearVelocity(new Vector3f());
        if (proxy.target == null || !proxy.target.isValid()) {
            proxy.target = world.createBody(new BodyDef()
                    .type(BodyType.KINEMATIC)
                    .position(at.x, at.y, at.z)
                    .rotation(turn)
                    .name("entity target"));
        } else {
            proxy.target.setTransform(at.x, at.y, at.z, turn);
        }
        proxy.body.setType(BodyType.DYNAMIC);
        MassData mass = proxy.body.massData(new MassData());
        if (mass.mass > 0) {
            mass.inertia.scale(proxy.mass / mass.mass);
        }
        mass.mass = proxy.mass;
        proxy.body.setMassData(mass);
        proxy.body.setLinearVelocity(moving.x, moving.y, moving.z);
        float force = settings.strength * proxy.mass;
        MotorJointDef pull = new MotorJointDef()
                .bodies(proxy.target, proxy.body)
                .linearSpring(PULL_HERTZ, 1, force)
                .angularSpring(PULL_HERTZ, 1, force);
        pull.maxVelocityForce = force;
        pull.maxVelocityTorque = force;
        Joint drive = null;
        try {
            drive = world.createJoint(pull);
            // upright: both frames' z along the bodies' y, so it turns only about y, by the drive
            proxy.upright = world.createJoint(new MotionLockJointDef()
                    .bodies(proxy.target, proxy.body)
                    .localFrameA(0, 0, 0, Z_TO_Y)
                    .localFrameB(0, 0, 0, Z_TO_Y)
                    .lockAngularXY(true));
        } catch (RuntimeException e) {
            if (drive != null) {
                drive.destroy(false);
            }
            proxy.body.setType(BodyType.KINEMATIC);
            throw e;
        }
        proxy.drive = drive;
        proxy.capped = true;
    }

    /** The target a force-capped proxy is pulled after, driven to {@code (tx, ty, tz)} over the tick. */
    private void drive(Proxy proxy, double tx, double ty, double tz, Quaternionf rotation, float timeStep) {
        proxy.target.setTargetTransform(tx, ty, tz, rotation, timeStep);
        if (tx != proxy.targetX || ty != proxy.targetY || tz != proxy.targetZ) {
            // asleep with what it leans on, its target moving off does not wake it
            proxy.body.setAwake(true);
        }
    }

    /**
     * Whether a force-capped proxy is near enough where it was driven to last to be made kinematic again: one left
     * behind would otherwise be driven the whole way back at once, through whatever held it.
     */
    private boolean caughtUp(Proxy proxy) {
        proxy.body.position(center);
        return center.distanceSquared(proxy.targetX, proxy.targetY, proxy.targetZ) <= CAUGHT_UP * CAUGHT_UP;
    }

    /** Whether a force-capped proxy is further than {@link #LAGS} from where it was driven to last. */
    private boolean lagsBehind(Proxy proxy) {
        proxy.body.position(center);
        return center.distanceSquared(proxy.targetX, proxy.targetY, proxy.targetZ) > LAGS * LAGS;
    }

    /**
     * Whether the entity's box at {@code (tx, ty, tz)}, a {@link #SKIN} in from its faces, is in a dynamic body its
     * proxy meets — the squeeze capping is for. An exact query, so only asked of a proxy that lags behind.
     */
    private boolean intoABody(Proxy proxy, Entity entity, double tx, double ty, double tz) {
        AABB box = entity.getBoundingBox();
        float hx = (float) box.getXsize() / 2 - SKIN,
                hy = (float) box.getYsize() / 2 - SKIN,
                hz = (float) box.getZsize() / 2 - SKIN;
        if (!(hx > 0 && hy > 0 && hz > 0)) {
            return false;
        }
        int count = world.overlapShape(
                Geometry.Box.of(hx, hy, hz),
                (box.minX + box.maxX) / 2 + tx - entity.getX(),
                (box.minY + box.maxY) / 2 + ty - entity.getY(),
                (box.minZ + box.maxZ) / 2 + tz - entity.getZ(),
                UPRIGHT,
                EntityCollisions.FILTER,
                overlaps);
        long tag = proxy.body.tag();
        for (int i = 0; i < count; i++) {
            Shape shape = overlaps.shape(i);
            Body body = shape == null || shape.isSensor() ? null : shape.body();
            if (body != null
                    && body.type() == BodyType.DYNAMIC
                    && body.attachment(Proxy.class) == null
                    && body != proxy.ignoredBase
                    && body.ignoredTag() != tag) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the proxy, going from where it was driven last to {@code (tx, ty, tz)}, sweeps near a dynamic body it
     * meets. Any such body, not only one held fast or one it heads at: a tipping crate hooked on its edge cannot get
     * away, and a player jumping off a crate's middle clips its raised corner. The proxy's travel, not the entity's
     * motion: a client-moved player has none. Off the bounds {@link #update} took: a query per proxy costs more than
     * the capping saves.
     */
    private boolean comesOntoABody(Proxy proxy, Entity entity, double tx, double ty, double tz) {
        double mx = tx - proxy.targetX, my = ty - proxy.targetY, mz = tz - proxy.targetZ;
        if (mx * mx + my * my + mz * mz < 1.0e-8) {
            return false;
        }
        return nearABody(
                proxy,
                entity.getBoundingBox(),
                proxy.targetX - entity.getX(),
                proxy.targetY - entity.getY(),
                proxy.targetZ - entity.getZ(),
                mx,
                my,
                mz);
    }

    /**
     * Whether {@code box} moved by {@code o} and swept by {@code m} comes within {@link #LOOK_MARGIN} of a dynamic body
     * the proxy meets: not one ignoring its entity, nor the base it is filtered from ({@link #ignoreBase}).
     */
    private boolean nearABody(Proxy proxy, AABB box, double ox, double oy, double oz, double mx, double my, double mz) {
        // the bounds are floats, a metre apart fifteen million blocks out: as much further
        double margin = LOOK_MARGIN + Math.ulp((float) Math.max(Math.abs(box.minX + ox), Math.abs(box.minZ + oz)));
        double minX = box.minX + ox + Math.min(mx, 0) - margin, maxX = box.maxX + ox + Math.max(mx, 0) + margin;
        double minY = box.minY + oy + Math.min(my, 0) - margin, maxY = box.maxY + oy + Math.max(my, 0) + margin;
        double minZ = box.minZ + oz + Math.min(mz, 0) - margin, maxZ = box.maxZ + oz + Math.max(mz, 0) + margin;
        long tag = proxy.body.tag();
        for (int i = 0; i < movable; i++) {
            Body body = movableBodies[i];
            int o = 6 * i;
            if (movableBounds[o] <= maxX
                    && movableBounds[o + 3] >= minX
                    && movableBounds[o + 1] <= maxY
                    && movableBounds[o + 4] >= minY
                    && movableBounds[o + 2] <= maxZ
                    && movableBounds[o + 5] >= minZ
                    && body != proxy.ignoredBase
                    && body.ignoredTag() != tag) {
                return true;
            }
        }
        return false;
    }

    /** Back to a kinematic proxy, driven to its entity; the target is kept, still, for the next time. */
    private void uncap(Proxy proxy) {
        if (proxy.drive != null && proxy.drive.isValid()) {
            proxy.drive.destroy(false);
        }
        if (proxy.upright != null && proxy.upright.isValid()) {
            proxy.upright.destroy(false);
        }
        proxy.drive = null;
        proxy.upright = null;
        if (proxy.body.isValid()) {
            proxy.body.setType(BodyType.KINEMATIC);
        }
        if (proxy.target != null && proxy.target.isValid()) {
            proxy.target.setLinearVelocity(0, 0, 0);
        }
        proxy.capped = false;
    }

    /** The proxy's bodies gone, its joints with them. */
    private static void discard(Proxy proxy) {
        if (proxy.body.isValid()) {
            proxy.body.destroy();
        }
        if (proxy.target != null && proxy.target.isValid()) {
            proxy.target.destroy();
        }
    }

    private static Quaternionf rotationOf(Entity entity, Proxy proxy, Quaternionf out) {
        if (proxy.provider != null && proxy.provider.yawFollows()) {
            // Minecraft's yaw turns clockwise seen from above
            return out.rotationY((float) Math.toRadians(-entity.getYRot()));
        }
        return out.identity();
    }

    @Nullable
    public Body proxyOf(Entity entity) {
        Proxy proxy = proxies.get(entity);
        return proxy == null ? null : proxy.body;
    }

    /** An entity's mass, kg: its proxy's, or what it would be given ({@link EntityShapes#massOf}). */
    public float massOf(Entity entity) {
        Proxy proxy = proxies.get(entity);
        return proxy != null ? proxy.mass : EntityShapes.massOf(entity, settings.entityDensity);
    }

    /** The entity a body stands in for, if it is a proxy. */
    @Nullable
    public static Entity entityOf(Body body) {
        Proxy proxy = body.attachment(Proxy.class);
        return proxy == null ? null : proxy.entity;
    }

    public int proxyCount() {
        return proxies.size();
    }

    @Override
    public void close() {
        for (Proxy proxy : proxies.values()) {
            discard(proxy);
        }
        proxies.clear();
    }

    static final class Proxy {
        final Entity entity;

        @Nullable
        final EntityShapes.Provider provider;
        /** the entity's size its shapes were made for */
        float width, height;
        /** kg; infinite for an entity no body moves */
        float mass;

        Body body;
        /**
         * force-capped ({@link #cap}): dynamic, pulled by {@code drive} after {@code target} and kept upright by
         * {@code upright}; {@code pressedTick} the last tick it came onto a body
         */
        boolean capped;

        @Nullable
        Joint drive;

        @Nullable
        Joint upright;

        @Nullable
        Body target;

        long pressedTick = Long.MIN_VALUE / 2;
        long lastSeen;
        /** where the entity was at the last update, and where its proxy was driven to */
        double lastX, lastY, lastZ, targetX, targetY, targetZ;
        /** at the last update: the entity on the ground; stepping up, so put rather than driven */
        boolean wasOnGround, stepped;
        /** what this tick's steps pushed the entity by, m/s, and the body that pushed hardest */
        float pushX, pushY, pushZ, strongest, approach;

        @Nullable
        Body pusher;
        /** the bodies that ran into it in this tick's steps */
        final List<Hit> hits = new ArrayList<>(0);
        /** m/s: how the proxy is driven this tick */
        float kinematicX, kinematicY, kinematicZ;
        /** the body the entity weighs on this tick, with its weight (N) and where */
        @Nullable
        Body weighs;

        float weight;
        double weighX, weighY, weighZ;
        /** the base the proxy does not collide with, and the joint that says so */
        @Nullable
        Body ignoredBase;

        @Nullable
        Joint ignoring;

        boolean clientMoved;
        /** {@link EntityAuthority#pushesShowLate}: this tick's answer */
        boolean pushesShowLate;
        /** m/s: pushes given to an entity whose pushes show late that it has not shown yet, and for how many ticks */
        float unechoedX, unechoedY, unechoedZ;

        int unechoedTicks;
        /** what it can give this tick: standing on something; the way it was held last tick (unit, or zero) */
        boolean supported;

        float heldX, heldY, heldZ;
        /** the push it was last given (m/s), and its tick then: whether it went is seen once it has ticked since */
        @Nullable
        Vec3 pushGiven;

        int pushTick;

        Proxy(Entity entity, @Nullable EntityShapes.Provider provider, float mass) {
            this.entity = entity;
            this.provider = provider;
            this.mass = mass;
            this.width = entity.getBbWidth();
            this.height = entity.getBbHeight();
            this.lastX = this.targetX = entity.getX();
            this.lastY = this.targetY = entity.getY();
            this.lastZ = this.targetZ = entity.getZ();
        }

        void clearPush() {
            pushX = pushY = pushZ = strongest = approach = 0;
            pusher = null;
        }
    }
}
