package com.lowdragmc.minebox3d.mc.terrain;

import com.lowdragmc.minebox3d.mc.CollisionCategories;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelThreads;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Joint;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.ThreadMode;
import com.lowdragmc.minebox3d.physics.VoxelGrid;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * <b>Streams block collision into a {@link PhysicsWorld}</b>, one chunk section at a time, around the bodies
 * that need it (design docs {@code 00-overview.md} §4.1, {@code 01-voxel-terrain.md} §8). The blocks go into a
 * {@link VoxelGrid} as collider ids ({@link BlockColliders}); nothing is meshed.
 *
 * <ul>
 *   <li>A section a body that {@linkplain LevelBodies#needsTerrain needs terrain} overlaps (bounds +
 *       {@link Settings#margin()}) is loaded <b>at once</b>, before the step that could drop the body through it.</li>
 *   <li>A section the body is heading into (its bounds pushed {@link Settings#lookaheadSeconds()} along its velocity)
 *       is queued and loaded under {@link Settings#loadBudget()} per tick.</li>
 *   <li>A section no body has wanted for {@link Settings#keepTicks()} is removed.</li>
 *   <li>A block change in a loaded section changes that cell, and the cells around it when a taller shape
 *       (fence, wall) came or went, at the next {@link #update()}. Chunk unloads remove sections.</li>
 *   <li>A body over a chunk that is not loaded is <b>parked</b> ({@link Body#setParked}) with the bodies jointed to
 *       it — taken out of the simulation, like an entity in a chunk that is not ticking — until their chunks are
 *       back, instead of falling through the missing ground.</li>
 * </ul>
 *
 * <p>Every streamer of a level hears its block and chunk changes, the level's own one and private ones alike. Runs on
 * the level's thread; block changes from other threads are queued and taken in at the next update.
 */
public final class TerrainStreamer implements AutoCloseable {

    private static final Map<Level, List<TerrainStreamer>> STREAMERS = new WeakHashMap<>();

    private final Level level;
    private final PhysicsWorld world;
    private final VoxelGrid grid;
    private final BlockColliders colliders;
    /** sections read into the grid, with collision or without */
    private final LongOpenHashSet loaded = new LongOpenHashSet();
    /** section → the last tick something wanted it */
    private final Long2LongOpenHashMap wanted = new Long2LongOpenHashMap();

    private final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
    /** cells to work out again at the next update, as {@link BlockPos#asLong}; swapped out while being worked */
    private LongLinkedOpenHashSet changedCells = new LongLinkedOpenHashSet();
    /** block changes made on other threads, taken in on the level's */
    private final ConcurrentLinkedQueue<OffThreadChange> offThreadChanges = new ConcurrentLinkedQueue<>();
    /** bodies this streamer took out of the simulation because their chunks (or a jointed body's) are not loaded */
    private final Set<Body> parked = Collections.newSetFromMap(new IdentityHashMap<>());
    /** this update's: the bodies that need terrain, and the ones to be parked */
    private final List<Body> needing = new ArrayList<>();

    private final Set<Body> out = Collections.newSetFromMap(new IdentityHashMap<>());
    private final IntArrayList edits = new IntArrayList();
    private final List<Request> requests = new ArrayList<>();
    private final Settings settings = new Settings();

    /** How the streamer behaves; one per streamer, so per world. */
    public static final class Settings {
        private float margin = 1.0f;
        private float lookaheadSeconds = 0.5f;
        private int loadBudget = 32;
        private int keepTicks = 40;
        private int chunkCheckTicks = 20;

        private Settings() {}

        /** Blocks added around a body's bounds before deciding which sections it overlaps. */
        public float margin() {
            return margin;
        }

        public Settings margin(float margin) {
            if (!(margin >= 0) || !Float.isFinite(margin)) {
                throw new IllegalArgumentException("margin must be zero or more: " + margin);
            }
            this.margin = margin;
            return this;
        }

        /** How far ahead along its velocity a body's sections are prepared. */
        public float lookaheadSeconds() {
            return lookaheadSeconds;
        }

        public Settings lookaheadSeconds(float seconds) {
            if (!(seconds >= 0) || !Float.isFinite(seconds)) {
                throw new IllegalArgumentException("lookahead must be zero or more seconds: " + seconds);
            }
            this.lookaheadSeconds = seconds;
            return this;
        }

        /** Sections loaded per tick beyond the ones a body is already touching. */
        public int loadBudget() {
            return loadBudget;
        }

        public Settings loadBudget(int sections) {
            if (sections < 0) {
                throw new IllegalArgumentException("load budget must be zero or more: " + sections);
            }
            this.loadBudget = sections;
            return this;
        }

        /** Updates a section stays after the last body that wanted it has gone. */
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

        /** Every this many updates, loaded sections whose chunk went without an unload event are dropped; 0 never. */
        public int chunkCheckTicks() {
            return chunkCheckTicks;
        }

        public Settings chunkCheckTicks(int ticks) {
            if (ticks < 0) {
                throw new IllegalArgumentException("chunk check interval must be zero or more: " + ticks);
            }
            this.chunkCheckTicks = ticks;
            return this;
        }
    }

    /** chunk events not taken in at once (see {@link #onChunk}), at the next update; any thread may add */
    private final ConcurrentLinkedQueue<PendingChunk> pendingChunks = new ConcurrentLinkedQueue<>();

    private long tick;
    private int loadedLastTick;
    private int cellsChangedLastTick;
    private final float[] bounds = new float[6];
    private final Vector3f velocity = new Vector3f();
    private final LongOpenHashSet urgent = new LongOpenHashSet();

    public TerrainStreamer(Level level, PhysicsWorld world) {
        this.level = level;
        this.world = world;
        ShapeDef template = new ShapeDef();
        template.filter.categoryBits = CollisionCategories.TERRAIN;
        // Box3D's default, said out loud: it reports a touch when either shape asks, and terrain asking would report
        // every body landing anywhere
        template.enableContactEvents = false;
        template.enableHitEvents = false;
        this.grid = world.createVoxelGrid(template);
        this.colliders = new BlockColliders(grid);
        wanted.defaultReturnValue(Long.MIN_VALUE);
        synchronized (STREAMERS) {
            STREAMERS.computeIfAbsent(level, l -> new ArrayList<>()).add(this);
        }
    }

    public static void forLevel(Level level, Consumer<TerrainStreamer> action) {
        TerrainStreamer[] streamers;
        synchronized (STREAMERS) {
            List<TerrainStreamer> list = STREAMERS.get(level);
            if (list == null) {
                return;
            }
            streamers = list.toArray(new TerrainStreamer[0]);
        }
        for (TerrainStreamer streamer : streamers) {
            action.accept(streamer);
        }
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Keeps the sections overlapping {@code box} in the world for the next {@code ticks} updates; ones not in yet are
     * queued under {@link Settings#loadBudget()}.
     */
    public void request(AABB box, int ticks) {
        requests.add(new Request(box, tick + ticks));
    }

    // ---- the tick -----------------------------------------------------------------------------

    /** The streamer's tick: on the level's thread (it reads chunks), holding the world. */
    public void update() {
        checkLevelThread();
        tick++;
        loadedLastTick = 0;
        for (PendingChunk pending; (pending = pendingChunks.poll()) != null; ) {
            if (pending.loaded) {
                loadChunk(pending.chunk);
            } else {
                unloadChunk(pending.chunk);
            }
        }
        applyChangedCells();
        if (settings.chunkCheckTicks > 0 && tick % settings.chunkCheckTicks == 0) {
            dropSectionsOfGoneChunks();
        }

        float margin = settings.margin;
        urgent.clear();
        needing.clear();
        out.clear();
        for (Body body : world.bodies()) {
            if (!LevelBodies.needsTerrain(body)) {
                continue;
            }
            needing.add(body);
            body.bounds(bounds);
            if (!out.contains(body)
                    && !chunksLoaded(bounds[0] - margin, bounds[2] - margin, bounds[3] + margin, bounds[5] + margin)) {
                withJointed(body, out);
            }
        }
        for (Iterator<Body> it = parked.iterator(); it.hasNext(); ) {
            Body body = it.next();
            if (!out.contains(body)) {
                if (body.isValid()) {
                    body.setParked(false);
                }
                it.remove();
            }
        }
        for (Body body : out) {
            park(body);
        }
        for (Body body : needing) {
            if (out.contains(body)) {
                continue;
            }
            body.bounds(bounds);
            float minX = bounds[0] - margin, minY = bounds[1] - margin, minZ = bounds[2] - margin;
            float maxX = bounds[3] + margin, maxY = bounds[4] + margin, maxZ = bounds[5] + margin;
            // what it touches now: needed before this tick's step
            mark(minX, minY, minZ, maxX, maxY, maxZ, urgent);
            // where it is going: needed soon
            body.linearVelocity(velocity).mul(settings.lookaheadSeconds);
            mark(
                    Math.min(bounds[0], bounds[0] + velocity.x) - margin,
                    Math.min(bounds[1], bounds[1] + velocity.y) - margin,
                    Math.min(bounds[2], bounds[2] + velocity.z) - margin,
                    Math.max(bounds[3], bounds[3] + velocity.x) + margin,
                    Math.max(bounds[4], bounds[4] + velocity.y) + margin,
                    Math.max(bounds[5], bounds[5] + velocity.z) + margin,
                    null);
        }
        needing.clear();
        for (Iterator<Request> it = requests.iterator(); it.hasNext(); ) {
            Request request = it.next();
            if (request.untilTick < tick) {
                it.remove();
            } else {
                AABB b = request.box;
                mark(
                        (float) b.minX,
                        (float) b.minY,
                        (float) b.minZ,
                        (float) b.maxX,
                        (float) b.maxY,
                        (float) b.maxZ,
                        null);
            }
        }

        for (LongIterator it = urgent.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            if (!loaded.contains(section)) {
                load(section);
            }
        }
        int budget = settings.loadBudget;
        while (budget > 0 && !queue.isEmpty()) {
            long section = queue.removeFirstLong();
            if (!loaded.contains(section) && wanted.get(section) >= tick - settings.keepTicks && load(section)) {
                budget--;
            }
        }

        for (LongIterator it = loaded.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            if (wanted.get(section) < tick - settings.keepTicks) {
                grid.setSection(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section), null);
                wanted.remove(section);
                it.remove();
            }
        }
    }

    private boolean chunksLoaded(float minX, float minZ, float maxX, float maxZ) {
        int x0 = SectionPos.blockToSectionCoord(Mth.floor(minX)), x1 = SectionPos.blockToSectionCoord(Mth.floor(maxX));
        int z0 = SectionPos.blockToSectionCoord(Mth.floor(minZ)), z1 = SectionPos.blockToSectionCoord(Mth.floor(maxZ));
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > 256) {
            return true; // too large to ask about: taken as loaded
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private void park(Body body) {
        if (body.isValid() && parked.add(body)) {
            body.setParked(true);
        }
    }

    /**
     * {@code body} and every dynamic body joined to it, joint by joint, into {@code into}: a group is parked whole —
     * one half of a cart left simulating would fall away from the other, and spring back at it when let go.
     */
    private void withJointed(Body body, Set<Body> into) {
        if (!into.add(body)) {
            return;
        }
        ArrayDeque<Body> walk = new ArrayDeque<>();
        walk.add(body);
        for (Body next; (next = walk.poll()) != null; ) {
            for (Joint joint : next.joints()) {
                Body other = joint.bodyA() == next ? joint.bodyB() : joint.bodyA();
                if (other.isValid() && other.type() == BodyType.DYNAMIC && into.add(other)) {
                    walk.add(other);
                }
            }
        }
    }

    /**
     * The sections {@code body} overlaps (bounds + {@link Settings#margin()}) loaded now rather than at the next
     * {@link #update()}: for a body that came to {@linkplain LevelBodies#needsTerrain need terrain} after this tick's
     * update — a replica a client starts simulating — and would otherwise step once over nothing.
     */
    @ApiStatus.Internal
    public void loadAround(Body body) {
        checkLevelThread();
        float margin = settings.margin;
        body.bounds(bounds);
        urgent.clear();
        mark(
                bounds[0] - margin,
                bounds[1] - margin,
                bounds[2] - margin,
                bounds[3] + margin,
                bounds[4] + margin,
                bounds[5] + margin,
                urgent);
        for (LongIterator it = urgent.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            if (!loaded.contains(section)) {
                load(section);
            }
        }
    }

    /** Whether this streamer has taken the body out of the simulation because its chunks are not loaded. */
    public boolean isParked(Body body) {
        return parked.contains(body);
    }

    private void mark(
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ, @Nullable LongSet urgent) {
        int x0 = SectionPos.blockToSectionCoord(Mth.floor(minX)), x1 = SectionPos.blockToSectionCoord(Mth.floor(maxX));
        int y0 = SectionPos.blockToSectionCoord(Mth.floor(minY)), y1 = SectionPos.blockToSectionCoord(Mth.floor(maxY));
        int z0 = SectionPos.blockToSectionCoord(Mth.floor(minZ)), z1 = SectionPos.blockToSectionCoord(Mth.floor(maxZ));
        int minSection = level.getMinSection(), maxSection = level.getMaxSection() - 1;
        y0 = Math.max(y0, minSection);
        y1 = Math.min(y1, maxSection);
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 4096) {
            return; // a body so large or fast it would ask for a city; it gets no terrain this tick
        }
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    long section = SectionPos.asLong(x, y, z);
                    wanted.put(section, tick);
                    if (urgent != null) {
                        urgent.add(section);
                    } else if (!loaded.contains(section)) {
                        queue.add(section);
                    }
                }
            }
        }
    }

    /** Reads a section into the grid; false if its chunk is not there to read. */
    private boolean load(long section) {
        // never getChunk(.., false) on the server: for a chunk still loading it waits on the main thread
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.x(section), SectionPos.z(section));
        if (chunk == null) {
            return false; // nothing to collide with until the chunk arrives; while wanted it is asked for again
        }
        short[] cells = colliders.section(level, chunk, SectionPos.y(section));
        grid.setSection(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section), cells);
        loaded.add(section);
        loadedLastTick++;
        return true;
    }

    private void applyChangedCells() {
        cellsChangedLastTick = 0;
        for (OffThreadChange change; (change = offThreadChanges.poll()) != null; ) {
            queueChange(change.pos, change.previous, change.current);
        }
        if (changedCells.isEmpty()) {
            return;
        }
        // working out a cell can set off vanilla work that changes blocks again: those go to the next set
        LongLinkedOpenHashSet cells = changedCells;
        changedCells = new LongLinkedOpenHashSet();
        edits.clear();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (LongIterator it = cells.iterator(); it.hasNext(); ) {
            pos.set(it.nextLong());
            if (!loaded.contains(SectionPos.asLong(pos))) {
                continue;
            }
            edits.add(pos.getX());
            edits.add(pos.getY());
            edits.add(pos.getZ());
            edits.add(colliders.collider(level, pos));
        }
        cellsChangedLastTick = edits.size() / 4;
        if (!edits.isEmpty()) {
            grid.setCells(edits.elements(), edits.size() / 4);
        }
    }

    private void dropSectionsOfGoneChunks() {
        for (LongIterator it = loaded.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            if (level.getChunkSource().getChunkNow(SectionPos.x(section), SectionPos.z(section)) == null) {
                grid.setSection(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section), null);
                it.remove();
            }
        }
    }

    // ---- world changes ------------------------------------------------------------------------

    /**
     * A block changed from {@code previous} (null if unknown) to {@code current}. The cell is worked out again at the
     * next update, and the cells around it too when a shape taller than a block was involved. Any thread.
     */
    public void onBlockChanged(BlockPos pos, @Nullable BlockState previous, BlockState current) {
        if (!LevelThreads.isLevelThread(level)) {
            offThreadChanges.add(new OffThreadChange(pos.immutable(), previous, current));
            return;
        }
        queueChange(pos, previous, current);
    }

    private void queueChange(BlockPos pos, @Nullable BlockState previous, BlockState current) {
        boolean large = previous == null
                || !previous.isAir() && previous.hasLargeCollisionShape()
                || !current.isAir() && current.hasLargeCollisionShape();
        if (!large) {
            // a section that is not in is read whole, fresh, when it comes in
            if (loaded.contains(SectionPos.asLong(pos))) {
                changedCells.add(pos.asLong());
            }
            return;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = pos.getX() + dx, y = pos.getY() + dy, z = pos.getZ() + dz;
                    if (loaded.contains(SectionPos.asLong(
                            SectionPos.blockToSectionCoord(x),
                            SectionPos.blockToSectionCoord(y),
                            SectionPos.blockToSectionCoord(z)))) {
                        changedCells.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
    }

    /**
     * A chunk is going: its sections leave the grid, and bodies over it are parked first so they do not fall. At once
     * when the calling thread may use the world now, else at the next update (see {@link #onChunk}).
     */
    public void onChunkUnloaded(ChunkPos chunk) {
        onChunk(chunk, false);
    }

    /**
     * A chunk arrived, or the client was sent a chunk it had again: its own loaded sections are read anew, and the
     * cells of loaded sections next to it, which had read it as air, look again — a taller shape in it may reach
     * across. At once or at the next update, like {@link #onChunkUnloaded}.
     */
    public void onChunkLoaded(ChunkPos chunk) {
        onChunk(chunk, true);
    }

    /**
     * At once only where that cannot get in anyone's way: the owner of an {@link ThreadMode#OWNER_THREAD} world, or the
     * thread already holding an {@link ThreadMode#EXCLUSIVE} one. Never by taking a free exclusive world: its users
     * schedule their own uses, and a chunk event taking it would make one of them throw at a moment no one chose.
     */
    private void onChunk(ChunkPos chunk, boolean loaded) {
        if (!world.isOpen()) {
            return;
        }
        if (world.owner() != Thread.currentThread()) {
            pendingChunks.add(new PendingChunk(chunk, loaded));
            return;
        }
        if (loaded) {
            loadChunk(chunk);
        } else {
            unloadChunk(chunk);
        }
    }

    private void checkLevelThread() {
        if (!LevelThreads.isLevelThread(level)) {
            throw new WrongThreadException(
                    "the terrain of " + level.dimension().location() + " is read on its level's thread, not "
                            + Thread.currentThread().getName());
        }
    }

    private void unloadChunk(ChunkPos chunk) {
        float margin = settings.margin;
        Set<Body> going = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Body body : world.bodies()) {
            if (!LevelBodies.needsTerrain(body) || parked.contains(body)) {
                continue;
            }
            body.bounds(bounds);
            if (Mth.floor(bounds[0] - margin) <= chunk.getMaxBlockX()
                    && Mth.floor(bounds[3] + margin) >= chunk.getMinBlockX()
                    && Mth.floor(bounds[2] - margin) <= chunk.getMaxBlockZ()
                    && Mth.floor(bounds[5] + margin) >= chunk.getMinBlockZ()) {
                withJointed(body, going);
            }
        }
        for (Body body : going) {
            park(body);
        }
        for (LongIterator it = loaded.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            if (SectionPos.x(section) == chunk.x && SectionPos.z(section) == chunk.z) {
                grid.setSection(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section), null);
                it.remove();
            }
        }
    }

    private void loadChunk(ChunkPos chunk) {
        int minX = chunk.getMinBlockX() - 1, maxX = chunk.getMaxBlockX() + 1;
        int minZ = chunk.getMinBlockZ() - 1, maxZ = chunk.getMaxBlockZ() + 1;
        LongOpenHashSet reload = new LongOpenHashSet();
        for (LongIterator it = loaded.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            int sx = SectionPos.x(section), sz = SectionPos.z(section);
            if (sx == chunk.x && sz == chunk.z) {
                reload.add(section);
                continue;
            }
            if (Math.abs(sx - chunk.x) > 1 || Math.abs(sz - chunk.z) > 1) {
                continue;
            }
            int x0 = Math.max(SectionPos.sectionToBlockCoord(sx), minX),
                    x1 = Math.min(SectionPos.sectionToBlockCoord(sx) + 15, maxX);
            int z0 = Math.max(SectionPos.sectionToBlockCoord(sz), minZ),
                    z1 = Math.min(SectionPos.sectionToBlockCoord(sz) + 15, maxZ);
            int y0 = SectionPos.sectionToBlockCoord(SectionPos.y(section));
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int y = y0; y < y0 + 16; y++) {
                        changedCells.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
        for (LongIterator it = reload.iterator(); it.hasNext(); ) {
            load(it.nextLong());
        }
    }

    // ---- reading ------------------------------------------------------------------------------

    public VoxelGrid grid() {
        return grid;
    }

    public BlockColliders colliders() {
        return colliders;
    }

    /** Sections read into the grid, including those with nothing that collides. */
    public int sectionCount() {
        return loaded.size();
    }

    public int loadedLastTick() {
        return loadedLastTick;
    }

    public int cellsChangedLastTick() {
        return cellsChangedLastTick;
    }

    /** Whether the section, as {@link SectionPos#asLong}, is read into the grid. */
    public boolean isLoaded(long section) {
        return loaded.contains(section);
    }

    /** Every section read into the grid, as {@link SectionPos#asLong}. */
    public void forEachLoaded(LongConsumer action) {
        loaded.forEach(action);
    }

    /** No longer hears the level's block and chunk changes ({@link #forLevel}); closing does it first. */
    public void stopListening() {
        synchronized (STREAMERS) {
            List<TerrainStreamer> list = STREAMERS.get(level);
            if (list != null) {
                list.remove(this);
                if (list.isEmpty()) {
                    STREAMERS.remove(level);
                }
            }
        }
    }

    @Override
    public void close() {
        stopListening();
        // a faulted world is only let go of: its close frees the grid and its sections
        if (world.isOpen() && !world.isFaulted()) {
            for (Body body : parked) {
                if (body.isValid()) {
                    body.setParked(false);
                }
            }
            grid.close();
        }
        parked.clear();
        loaded.clear();
        wanted.clear();
        queue.clear();
        changedCells.clear();
        offThreadChanges.clear();
        pendingChunks.clear();
    }

    private record Request(AABB box, long untilTick) {}

    private record OffThreadChange(BlockPos pos, @Nullable BlockState previous, BlockState current) {}

    private record PendingChunk(ChunkPos chunk, boolean loaded) {}
}
