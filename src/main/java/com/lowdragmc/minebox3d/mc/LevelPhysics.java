package com.lowdragmc.minebox3d.mc;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.entity.EntityBases;
import com.lowdragmc.minebox3d.mc.entity.EntityProxies;
import com.lowdragmc.minebox3d.mc.sync.SyncedBodies;
import com.lowdragmc.minebox3d.mc.terrain.TerrainStreamer;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.ThreadMode;
import com.lowdragmc.minebox3d.physics.WorldDef;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>Physics in a {@link Level}</b> (design doc §3): a {@link PhysicsWorld}, the terrain streamed into it and the
 * entities standing in it.
 *
 * <p>Every level has its own on each side, made on first use by {@link #of}, stepped once per tick of that level and
 * closed when the level unloads; a body made on the server does not appear on the client. It belongs to the level's
 * thread.
 *
 * <p>{@link #create} makes a <b>private</b> one on the same level — a ragdoll's own simulation, an editor preview —
 * which nothing steps or closes but its maker: {@link #tick()} for both halves at once on the level's thread, or with
 * {@link ThreadMode#EXCLUSIVE} {@link #prepare()} on the level's thread (terrain and entities are read there) and
 * {@link #step()} on any thread, each inside {@link PhysicsWorld#use()}.
 */
public final class LevelPhysics implements AutoCloseable {

    /** Minecraft's own gravity for an entity: 0.08 blocks per tick per tick. */
    public static final float GRAVITY = -32f;
    /**
     * N per kg an entity's proxy in a level's own physics pushes with at most ({@link EntityProxies.Settings#strength}):
     * about 21 kN for a player, which keeps up with it within centimetres and gives way to a crate held against a wall.
     */
    public static final float ENTITY_STRENGTH = 300;

    private static final Map<Level, LevelPhysics> LEVELS = new ConcurrentHashMap<>();
    /** private physics not closed yet, closed with their level if their makers did not */
    private static final Map<Level, Set<LevelPhysics>> PRIVATE = new ConcurrentHashMap<>();

    private final Level level;
    private final PhysicsWorld world;
    private final TerrainStreamer terrain;
    private final EntityProxies entities;
    private final SyncedBodies sync = new SyncedBodies(this);
    private final EntityBases.Riders riders = new EntityBases.Riders();
    private final Settings settings = new Settings();

    private long ticks;
    private float lastTickMillis;
    private boolean stopped;
    private final List<TickListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Something that runs with this physics' {@link #tick()}, on the level's thread: told once the tick has read the level
     * and before its steps, and after them — what a mod's own bodies need on either side of a step, as Unreal's components
     * tick before and after physics. Not told on a tick that does not step ({@code /tick freeze}).
     */
    public interface TickListener {

        /** The level read (terrain, entity proxies, replicas), the steps not yet taken. */
        default void beforeSteps(LevelPhysics physics) {
        }

        /** The steps taken; what stands on the bodies not yet carried, nothing sent yet. */
        default void afterSteps(LevelPhysics physics) {
        }
    }

    /** How a level's physics is stepped. */
    public static final class Settings {
        private int stepsPerTick = 3;
        private int subSteps = 4;

        private Settings() {
        }

        /** Box3D steps per Minecraft tick; Box3D is tuned for 60 Hz, a tick is 1/20 s. */
        public int stepsPerTick() {
            return stepsPerTick;
        }

        public Settings stepsPerTick(int steps) {
            if (steps < 1) {
                throw new IllegalArgumentException("steps per tick must be 1 or more: " + steps);
            }
            this.stepsPerTick = steps;
            return this;
        }

        public int subSteps() {
            return subSteps;
        }

        public Settings subSteps(int subSteps) {
            if (subSteps < 1) {
                throw new IllegalArgumentException("sub-steps must be 1 or more: " + subSteps);
            }
            this.subSteps = subSteps;
            return this;
        }
    }

    private LevelPhysics(Level level, WorldDef def) {
        this.level = level;
        this.world = PhysicsWorld.create(def);
        try (PhysicsWorld.Use use = world.use()) {
            this.terrain = new TerrainStreamer(level, world);
            this.entities = new EntityProxies(level, world);
        }
    }

    /**
     * The level's own physics, made if it has none yet; {@code null} when Box3D is not available on this platform, in
     * which case the caller carries on without physics. Only on the level's own thread (the server thread, or the client
     * thread for the client's level): the world belongs to it.
     */
    @Nullable
    public static LevelPhysics of(Level level) {
        checkLevelThread(level);
        if (!NativeLibrary.isLoaded() && !NativeLibrary.tryLoad()) {
            return null;
        }
        return LEVELS.computeIfAbsent(level, l -> {
            LevelPhysics physics = new LevelPhysics(l, new WorldDef().gravity(0, GRAVITY, 0));
            physics.entities.settings().strength(ENTITY_STRENGTH);
            return physics;
        });
    }

    /**
     * A private physics on {@code level}, made on the level's thread: terrain and entities of the level come into it like
     * into the level's own, but nothing else steps it and the caller closes it — at the latest when the level unloads,
     * where one still open is closed with a warning. {@code null} when Box3D is not available.
     *
     * <p>Its bodies do not push the level's entities (they meet them as walls) unless the maker turns it on:
     * {@code entities().settings().pushEntities(true)}. It reads the level; changing it is the maker's choice.
     */
    @Nullable
    public static LevelPhysics create(Level level, WorldDef def) {
        checkLevelThread(level);
        if (!NativeLibrary.isLoaded() && !NativeLibrary.tryLoad()) {
            return null;
        }
        LevelPhysics physics = new LevelPhysics(level, def);
        physics.entities.settings().pushEntities(false);
        PRIVATE.computeIfAbsent(level, l -> ConcurrentHashMap.newKeySet()).add(physics);
        return physics;
    }

    private static void checkLevelThread(Level level) {
        if (!LevelThreads.isLevelThread(level)) {
            throw new WrongThreadException("LevelPhysics of " + level.dimension().location() + " asked for on "
                    + Thread.currentThread().getName() + ", not the level's thread");
        }
    }

    /** The level's own physics if something has already made it. */
    @Nullable
    public static LevelPhysics existing(Level level) {
        return LEVELS.get(level);
    }

    public static List<LevelPhysics> all() {
        return new ArrayList<>(LEVELS.values());
    }

    static void unload(Level level) {
        LevelPhysics physics = LEVELS.remove(level);
        if (physics != null) {
            try {
                physics.close();
            } catch (RuntimeException e) {
                MineBox3D.LOGGER.error("could not close the physics of {}", level.dimension().location(), e);
            }
        }
        Set<LevelPhysics> left = PRIVATE.remove(level);
        if (left != null) {
            for (LevelPhysics forgotten : left) {
                MineBox3D.LOGGER.warn("a private physics of {} was still open when the level unloaded; closing it",
                        level.dimension().location());
                try {
                    forgotten.close();
                } catch (RuntimeException e) {
                    MineBox3D.LOGGER.error("could not close it", e);
                }
            }
        }
    }

    /** For the client's own logout handling, which lives where client classes may be named. */
    public static void unloadClient(Level level) {
        if (level.isClientSide) {
            unload(level);
        }
    }

    static void unloadAll(boolean clientSide) {
        Set<Level> levels = new HashSet<>(LEVELS.keySet());
        levels.addAll(PRIVATE.keySet());
        for (Level level : levels) {
            if (level.isClientSide == clientSide) {
                unload(level);
            }
        }
    }

    public Level level() {
        return level;
    }

    public PhysicsWorld world() {
        return world;
    }

    public TerrainStreamer terrain() {
        return terrain;
    }

    /** The bodies copied to clients: on the server what is sent, on the client the replicas (design doc 04 §10). */
    public SyncedBodies sync() {
        return sync;
    }

    /** The entities standing on this physics' bodies (design doc 04 §11, EntityBases). */
    @ApiStatus.Internal
    public EntityBases.Riders riders() {
        return riders;
    }

    public EntityProxies entities() {
        return entities;
    }

    public Settings settings() {
        return settings;
    }

    public Body createBody(BodyDef def) {
        return world.createBody(def);
    }

    public long ticks() {
        return ticks;
    }

    /** Wall time of the last {@link #tick()}, terrain and proxies included. */
    public float lastTickMillis() {
        return lastTickMillis;
    }

    /** Told with every {@link #tick()} from the next one on; on the level's thread. */
    public void addTickListener(TickListener listener) {
        checkLevelThread(level);
        listeners.add(listener);
    }

    public void removeTickListener(TickListener listener) {
        listeners.remove(listener);
    }

    /**
     * Each listener told, in the order they came. ⚠️ One that throws is taken off, with one log entry, and the rest go on:
     * a mod's mistake stops its own part, not the level's physics.
     */
    private void tell(boolean before) {
        for (TickListener listener : listeners) {
            try {
                if (before) {
                    listener.beforeSteps(this);
                } else {
                    listener.afterSteps(this);
                }
            } catch (RuntimeException e) {
                listeners.remove(listener);
                MineBox3D.LOGGER.error("a tick listener of the physics of {} failed and was taken off: {}",
                        level.dimension().location(), listener, e);
            }
        }
    }

    /**
     * One Minecraft tick of physics: {@link #prepare()} then {@link #step()}. Called at the end of the level's tick
     * (after entities moved) for the level's own. No steps while the level's tick is frozen ({@code /tick freeze};
     * {@code /tick step} runs them) — the bodies are still sent to the clients that come to see them — and nothing more
     * once a tick has failed: an error stops this physics, with one log entry, instead of failing the level's tick every
     * tick.
     */
    public void tick() {
        if (stopped) {
            return;
        }
        boolean runs = level.tickRateManager().runsNormally();
        long start = System.nanoTime();
        try (PhysicsWorld.Use use = world.use()) {
            if (runs) {
                prepare();
                tell(true);
                step();
                tell(false);
                if (existing(level) == this) {
                    // what stands on the bodies, to where the steps took them (EntityBases; only the level's own bodies bear)
                    riders.carry(level);
                }
            }
            // what the server's steps did, out to the clients that see it (nothing on a client, or for private physics);
            // then what stands on them, which the clients place on the bodies they have just been sent
            sync.afterTick();
            sync.riders().afterTick();
        } catch (RuntimeException e) {
            stopped = true;
            MineBox3D.LOGGER.error("the physics of {} failed and stopped", level.dimension().location(), e);
        }
        if (runs) {
            lastTickMillis = (System.nanoTime() - start) / 1.0e6f;
        }
    }

    /**
     * The half of a tick that reads the level, on the level's thread: starts the interpolation frame, brings terrain in
     * around the bodies, moves the entity proxies to where their entities are now, and on a client drives the server's
     * bodies' replicas to where the server last said.
     */
    public void prepare() {
        checkLevelThread(level);
        ticks++;
        world.beginInterpolationFrame();
        terrain.update();
        entities.update(1f / 20f);
        if (level.isClientSide) {
            sync.beforeSteps(1f / 20f);
        }
    }

    /**
     * The half of a tick that only steps: {@link Settings#stepsPerTick()} steps of a tick's time. Before each, the
     * weights of entities standing on bodies ({@link EntityProxies#beforeStep()}); after each, which bodies ran into
     * entities ({@link EntityProxies#afterStep()}); after them all, those collisions settled
     * ({@link EntityProxies#afterSteps()}) — the entities pushed there on the level's thread, at the next
     * {@link #prepare()} if stepped on another.
     */
    public void step() {
        float timeStep = 1f / (20f * settings.stepsPerTick);
        for (int i = 0; i < settings.stepsPerTick; i++) {
            entities.beforeStep();
            world.step(timeStep, settings.subSteps);
            entities.afterStep();
        }
        entities.afterSteps();
    }

    /** Whether an error stopped this physics (see {@link #tick()}). */
    public boolean isStopped() {
        return stopped;
    }

    /**
     * Closes it, on the thread that may use its world. However that goes, it is no longer the level's, nor listed among
     * the level's private ones, and its terrain no longer hears the level's chunks.
     */
    @Override
    public void close() {
        Set<LevelPhysics> privates = PRIVATE.get(level);
        if (privates != null) {
            privates.remove(this);
        }
        LEVELS.remove(level, this);
        listeners.clear();
        try (PhysicsWorld.Use use = world.use()) {
            try {
                entities.close();
            } finally {
                try {
                    terrain.close();
                } finally {
                    world.close();
                    MineBox3D.LOGGER.debug("closed the physics of {}", level.dimension().location());
                }
            }
        } finally {
            terrain.stopListening();
        }
    }
}
