package com.lowdragmc.minebox3d.mc.terrain;

import com.lowdragmc.minebox3d.mc.CollisionCategories;
import com.lowdragmc.minebox3d.mc.LevelThreads;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.VoxelGrid;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

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
import java.util.function.Predicate;

/**
 * <b>Streams block collision into a {@link PhysicsWorld}</b>, one chunk section at a time, around the bodies
 * that need it (design docs {@code 00-overview.md} §4.1, {@code 01-voxel-terrain.md} §8). The blocks go into a
 * {@link VoxelGrid} as collider ids ({@link BlockColliders}); nothing is meshed.
 *
 * <ul>
 *   <li>A section a dynamic body overlaps (bounds + {@link #margin}) is loaded <b>at once</b>, before the step
 *       that could drop the body through it.</li>
 *   <li>A section the body is heading into (its bounds pushed {@link #lookaheadSeconds} along its velocity) is
 *       queued and loaded under {@link #loadBudget} per tick.</li>
 *   <li>A section no body has wanted for {@link #keepTicks} is removed.</li>
 *   <li>A block change in a loaded section changes that cell, and the cells around it when a taller shape
 *       (fence, wall) came or went, at the next {@link #update()}. Chunk unloads remove sections.</li>
 *   <li>A body over a chunk that is not loaded is <b>parked</b> — taken out of the simulation, like an entity in a
 *       chunk that is not ticking — until its chunks are back, instead of falling through the missing ground.</li>
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
    /** bodies this streamer took out of the simulation because their chunks are not loaded */
    private final Set<Body> parked = Collections.newSetFromMap(new IdentityHashMap<>());
    private final IntArrayList edits = new IntArrayList();
    private final List<Request> requests = new ArrayList<>();
    private Predicate<Body> consumers = body -> body.type() == BodyType.DYNAMIC;

    /** Blocks added around a body's bounds before deciding which sections it overlaps. */
    public float margin = 1.0f;
    /** How far ahead along its velocity a body's sections are prepared. */
    public float lookaheadSeconds = 0.5f;
    /** Sections loaded per tick beyond the ones a body is already touching. */
    public int loadBudget = 32;
    /** Ticks a section stays after the last body that wanted it has gone. */
    public int keepTicks = 40;
    /** Every this many ticks, loaded sections whose chunk has gone without an unload event are dropped. */
    public int chunkCheckTicks = 20;

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
        this.grid = world.createVoxelGrid(template);
        this.colliders = new BlockColliders(grid);
        wanted.defaultReturnValue(Long.MIN_VALUE);
        synchronized (STREAMERS) {
            STREAMERS.computeIfAbsent(level, l -> new ArrayList<>()).add(this);
        }
    }

    /** Runs {@code action} for every open streamer of {@code level}. */
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

    /** Which bodies need terrain. By default every dynamic body. */
    public void setConsumers(Predicate<Body> consumers) {
        this.consumers = consumers;
    }

    /** Keeps the sections overlapping {@code box} in the world for the next {@code ticks} ticks. */
    public void request(AABB box, int ticks) {
        requests.add(new Request(box, tick + ticks));
    }

    // ---- the tick -----------------------------------------------------------------------------

    public void update() {
        tick++;
        loadedLastTick = 0;
        applyChangedCells();
        if (chunkCheckTicks > 0 && tick % chunkCheckTicks == 0) {
            dropSectionsOfGoneChunks();
        }

        urgent.clear();
        for (Body body : world.bodies()) {
            if (!consumers.test(body)) {
                continue;
            }
            body.bounds(bounds);
            float minX = bounds[0] - margin, minY = bounds[1] - margin, minZ = bounds[2] - margin;
            float maxX = bounds[3] + margin, maxY = bounds[4] + margin, maxZ = bounds[5] + margin;
            if (!chunksLoaded(minX, minZ, maxX, maxZ)) {
                park(body);
                continue;
            }
            unpark(body);
            // what it touches now: needed before this tick's step
            mark(minX, minY, minZ, maxX, maxY, maxZ, urgent);
            // where it is going: needed soon
            body.linearVelocity(velocity).mul(lookaheadSeconds);
            mark(Math.min(bounds[0], bounds[0] + velocity.x) - margin, Math.min(bounds[1], bounds[1] + velocity.y) - margin,
                    Math.min(bounds[2], bounds[2] + velocity.z) - margin, Math.max(bounds[3], bounds[3] + velocity.x) + margin,
                    Math.max(bounds[4], bounds[4] + velocity.y) + margin, Math.max(bounds[5], bounds[5] + velocity.z) + margin,
                    null);
        }
        parked.removeIf(body -> !body.isValid());
        for (Iterator<Request> it = requests.iterator(); it.hasNext(); ) {
            Request request = it.next();
            if (request.untilTick < tick) {
                it.remove();
            } else {
                AABB b = request.box;
                mark((float) b.minX, (float) b.minY, (float) b.minZ, (float) b.maxX, (float) b.maxY, (float) b.maxZ, null);
            }
        }

        // sections a body is touching: loaded now, whatever the budget
        for (LongIterator it = urgent.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            if (!loaded.contains(section)) {
                load(section);
            }
        }
        int budget = loadBudget;
        while (budget > 0 && !queue.isEmpty()) {
            long section = queue.removeFirstLong();
            if (!loaded.contains(section) && wanted.get(section) >= tick - keepTicks && load(section)) {
                budget--;
            }
        }

        // sections nobody wants any more
        for (LongIterator it = loaded.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            if (wanted.get(section) < tick - keepTicks) {
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
            return true; // too large to ask about; mark() turns it away anyway
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
        if (parked.add(body)) {
            body.setEnabled(false);
        }
    }

    private void unpark(Body body) {
        if (parked.remove(body)) {
            body.setEnabled(true);
        }
    }

    /** Whether this streamer has taken the body out of the simulation because its chunks are not loaded. */
    public boolean isParked(Body body) {
        return parked.contains(body);
    }

    private void mark(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, @Nullable LongSet urgent) {
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
        // working out a cell may load a chunk's worth of vanilla work that changes blocks again: those go to the next set
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
        boolean large = previous == null || !previous.isAir() && previous.hasLargeCollisionShape()
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
                    if (loaded.contains(SectionPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y),
                            SectionPos.blockToSectionCoord(z)))) {
                        changedCells.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
    }

    /** A chunk is going: its sections leave the grid, and bodies over it are parked first so they do not fall. */
    public void onChunkUnloaded(ChunkPos chunk) {
        for (Body body : world.bodies()) {
            if (!consumers.test(body) || parked.contains(body)) {
                continue;
            }
            body.bounds(bounds);
            if (Mth.floor(bounds[0] - margin) <= chunk.getMaxBlockX() && Mth.floor(bounds[3] + margin) >= chunk.getMinBlockX()
                    && Mth.floor(bounds[2] - margin) <= chunk.getMaxBlockZ() && Mth.floor(bounds[5] + margin) >= chunk.getMinBlockZ()) {
                park(body);
            }
        }
        for (LongIterator it = loaded.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            if (SectionPos.x(section) == chunk.x && SectionPos.z(section) == chunk.z) {
                grid.setSection(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section), null);
                it.remove();
            }
        }
    }

    /**
     * A chunk arrived, or the client was sent a chunk it had again: its own loaded sections are read anew, and the cells
     * of loaded sections next to it, which had read it as air, look again — a taller shape in it may reach across.
     */
    public void onChunkLoaded(ChunkPos chunk) {
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
            int x0 = Math.max(SectionPos.sectionToBlockCoord(sx), minX), x1 = Math.min(SectionPos.sectionToBlockCoord(sx) + 15, maxX);
            int z0 = Math.max(SectionPos.sectionToBlockCoord(sz), minZ), z1 = Math.min(SectionPos.sectionToBlockCoord(sz) + 15, maxZ);
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

    public boolean isLoaded(long section) {
        return loaded.contains(section);
    }

    /** Every section read into the grid, as {@link SectionPos#asLong}. */
    public void forEachLoaded(LongConsumer action) {
        loaded.forEach(action);
    }

    @Override
    public void close() {
        synchronized (STREAMERS) {
            List<TerrainStreamer> list = STREAMERS.get(level);
            if (list != null) {
                list.remove(this);
                if (list.isEmpty()) {
                    STREAMERS.remove(level);
                }
            }
        }
        if (world.isOpen()) {
            for (Body body : parked) {
                if (body.isValid()) {
                    body.setEnabled(true);
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
    }

    private record Request(AABB box, long untilTick) {
    }

    private record OffThreadChange(BlockPos pos, @Nullable BlockState previous, BlockState current) {
    }
}
