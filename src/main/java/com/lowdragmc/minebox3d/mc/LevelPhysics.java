package com.lowdragmc.minebox3d.mc;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.entity.EntityProxies;
import com.lowdragmc.minebox3d.mc.terrain.TerrainStreamer;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.WorldDef;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>The physics of one {@link Level}</b> on one side (design doc §3): a {@link PhysicsWorld}, the
 * terrain streamed into it and the entities standing in it, stepped once per tick of that level.
 *
 * <p>Each side has its own: a body made on the server does not appear on the client. Belongs to the
 * level's thread; using it from any other throws (see {@link PhysicsWorld}).
 *
 * <p>Created on first use by {@link #of}, closed when the level unloads.
 */
public final class LevelPhysics implements AutoCloseable {

    /** Minecraft's own gravity for an entity: 0.08 blocks per tick per tick. */
    public static final float GRAVITY = -32f;

    private static final Map<Level, LevelPhysics> LEVELS = new ConcurrentHashMap<>();

    private final Level level;
    private final PhysicsWorld world;
    private final TerrainStreamer terrain;
    private final EntityProxies entities;

    /** Box3D steps per Minecraft tick; Box3D is tuned for 60 Hz, a tick is 1/20 s. */
    public int stepsPerTick = 3;
    public int subSteps = 4;

    private long ticks;
    private float lastTickMillis;
    private boolean stopped;

    private LevelPhysics(Level level) {
        this.level = level;
        this.world = PhysicsWorld.create(new WorldDef().gravity(0, GRAVITY, 0));
        this.terrain = new TerrainStreamer(level, world);
        this.entities = new EntityProxies(level, world);
    }

    /**
     * The level's physics, made if it has none yet; {@code null} when Box3D is not available on this
     * platform, in which case the caller carries on without physics. Only on the level's own thread (the server
     * thread, or the client thread for the client's level): the world belongs to it.
     */
    @Nullable
    public static LevelPhysics of(Level level) {
        if (!LevelThreads.isLevelThread(level)) {
            throw new WrongThreadException("LevelPhysics of " + level.dimension().location() + " asked for on "
                    + Thread.currentThread().getName() + ", not the level's thread");
        }
        if (!NativeLibrary.isLoaded() && !NativeLibrary.tryLoad()) {
            return null;
        }
        return LEVELS.computeIfAbsent(level, LevelPhysics::new);
    }

    /** The level's physics if something has already made it. */
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
            physics.close();
        }
    }

    /** For the client's own logout handling, which lives where client classes may be named. */
    public static void unloadClient(Level level) {
        if (level.isClientSide) {
            unload(level);
        }
    }

    static void unloadAll(boolean clientSide) {
        for (LevelPhysics physics : all()) {
            if (physics.level.isClientSide == clientSide) {
                unload(physics.level);
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

    public EntityProxies entities() {
        return entities;
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

    /**
     * One Minecraft tick of physics: terrain around the bodies, entities to where they now are, then
     * {@link #stepsPerTick} steps. Called at the end of the level's tick (after entities moved). Nothing while the
     * level's tick is frozen ({@code /tick freeze}; {@code /tick step} runs it), and nothing more once a step has
     * failed: an error stops this level's physics, with one log entry, instead of failing the level's tick every tick.
     */
    public void tick() {
        if (stopped || !level.tickRateManager().runsNormally()) {
            return;
        }
        long start = System.nanoTime();
        ticks++;
        try {
            world.beginInterpolationFrame();
            terrain.update();
            float timeStep = 1f / (20f * stepsPerTick);
            entities.update(timeStep * stepsPerTick);
            for (int i = 0; i < stepsPerTick; i++) {
                world.step(timeStep, subSteps);
            }
        } catch (RuntimeException e) {
            stopped = true;
            MineBox3D.LOGGER.error("the physics of {} failed and stopped", level.dimension().location(), e);
        }
        lastTickMillis = (System.nanoTime() - start) / 1.0e6f;
    }

    /** Whether an error stopped this level's physics (see {@link #tick()}). */
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public void close() {
        try {
            entities.close();
            terrain.close();
        } finally {
            world.close();
            MineBox3D.LOGGER.debug("closed the physics of {}", level.dimension().location());
        }
    }
}
