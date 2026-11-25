package com.lowdragmc.minebox3d.mc.terrain;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.physics.SurfaceMaterial;
import com.lowdragmc.minebox3d.physics.VoxelGrid;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * <b>Minecraft's block collision as the collider ids of one {@link VoxelGrid}</b> (design doc
 * {@code 01-voxel-terrain.md} §3.1, §8).
 *
 * <p>A cell's collider is its block's collision shape cut to the cell, plus what a neighbour's taller shape
 * reaches into it — a fence or a wall is 1.5 blocks high, and its top half belongs to the cell above — with
 * the block's surface ({@link TerrainMaterials}). Colliders are shared by content: every stone block is one id.
 *
 * <p>Shapes Minecraft caches per block state (every block not marked {@code dynamicShape}) are looked up per
 * state; dynamic ones (bamboo's random offset, an opening shulker box, a moving piston) are read at the block.
 * Shapes come with an empty collision context, the way Minecraft caches them: scaffolding and powder snow
 * collide the way they do for an entity with no context.
 *
 * <p>Runs on the thread that owns the grid's world.
 */
public final class BlockColliders {

    private static final int MISSING = -1;

    private final VoxelGrid grid;
    /** a state's own collider, cut to its cell, for states whose shape Minecraft caches */
    private final Reference2IntOpenHashMap<BlockState> byState = new Reference2IntOpenHashMap<>();

    private final Object2IntOpenHashMap<ColliderKey> byContent = new Object2IntOpenHashMap<>();
    private final Object2IntOpenHashMap<MaterialKey> materials = new Object2IntOpenHashMap<>();
    private final SurfaceMaterial scratch = new SurfaceMaterial();
    private final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
    private boolean warnedTooManyBoxes;

    public BlockColliders(VoxelGrid grid) {
        this.grid = grid;
        byState.defaultReturnValue(MISSING);
        byContent.defaultReturnValue(MISSING);
        materials.defaultReturnValue(MISSING);
    }

    public VoxelGrid grid() {
        return grid;
    }

    /** Distinct colliders so far, the empty one included. */
    public int colliderCount() {
        return grid.colliderCount();
    }

    // ---- one cell -----------------------------------------------------------------------------

    /** The collider of the cell at {@code pos}: its own block, and what taller neighbours reach into it. */
    public int collider(Level level, BlockPos pos) {
        BlockState state = stateAt(level, pos);
        VoxelShape reach = Shapes.empty();
        BlockState reachFrom = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    neighbour.setWithOffset(pos, dx, dy, dz);
                    BlockState other = stateAt(level, neighbour);
                    if (other.isAir() || !other.hasLargeCollisionShape()) {
                        continue;
                    }
                    VoxelShape part = partIn(other.getCollisionShape(level, neighbour), dx, dy, dz);
                    if (!part.isEmpty()) {
                        reach = Shapes.joinUnoptimized(reach, part, BooleanOp.OR);
                        reachFrom = other;
                    }
                }
            }
        }
        if (reachFrom == null) {
            return own(level, pos, state);
        }
        VoxelShape shape = state.isAir() ? Shapes.empty() : cut(state.getCollisionShape(level, pos));
        VoxelShape all = Shapes.join(shape, reach, BooleanOp.OR);
        // the cell's own block gives the surface; only what reaches in from below when it has none
        return idOf(all, shape.isEmpty() ? material(level, pos, reachFrom) : material(level, pos, state));
    }

    /** The part of a neighbour's shape, at offset {@code (dx, dy, dz)} from a cell, that lies in that cell. */
    private static VoxelShape partIn(VoxelShape shape, int dx, int dy, int dz) {
        if (shape.isEmpty()) {
            return shape;
        }
        AABB b = shape.bounds();
        // in the cell's frame the neighbour's unit cube sits at (dx, dy, dz)
        if (b.maxX + dx <= 0
                || b.minX + dx >= 1
                || b.maxY + dy <= 0
                || b.minY + dy >= 1
                || b.maxZ + dz <= 0
                || b.minZ + dz >= 1) {
            return Shapes.empty();
        }
        return Shapes.join(shape.move(dx, dy, dz), Shapes.block(), BooleanOp.AND);
    }

    /** A block's own collider, cut to its cell, without what neighbours reach into it. */
    int own(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        boolean perState = !state.getBlock().hasDynamicShape();
        if (perState) {
            int id = byState.getInt(state);
            if (id != MISSING) {
                return id;
            }
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        int id = shape.isEmpty() ? 0 : idOf(cut(shape), material(level, pos, state));
        if (perState) {
            byState.put(state, id);
        }
        return id;
    }

    private static VoxelShape cut(VoxelShape shape) {
        if (shape.isEmpty()) {
            return shape;
        }
        AABB b = shape.bounds();
        if (b.minX >= 0 && b.minY >= 0 && b.minZ >= 0 && b.maxX <= 1 && b.maxY <= 1 && b.maxZ <= 1) {
            return shape;
        }
        return Shapes.join(shape, Shapes.block(), BooleanOp.AND);
    }

    private int idOf(VoxelShape shape, int material) {
        if (shape.isEmpty()) {
            return 0;
        }
        List<AABB> boxes = shape.toAabbs();
        if (boxes.size() > VoxelGrid.MAX_BOXES) {
            if (!warnedTooManyBoxes) {
                MineBox3D.LOGGER.warn(
                        "a block collision shape has {} boxes, more than {}: using its bounds",
                        boxes.size(),
                        VoxelGrid.MAX_BOXES);
                warnedTooManyBoxes = true;
            }
            boxes = List.of(shape.bounds());
        }
        float[] values = new float[6 * boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            AABB box = boxes.get(i);
            values[6 * i] = (float) box.minX;
            values[6 * i + 1] = (float) box.minY;
            values[6 * i + 2] = (float) box.minZ;
            values[6 * i + 3] = (float) box.maxX;
            values[6 * i + 4] = (float) box.maxY;
            values[6 * i + 5] = (float) box.maxZ;
        }
        ColliderKey key = new ColliderKey(values, material);
        int id = byContent.getInt(key);
        if (id == MISSING) {
            id = addCollider(shape, values, boxes.size(), material);
            byContent.put(key, id);
        }
        return id;
    }

    /**
     * Into the grid's table. A shape the table will not take — more than 65535 colliders in one world, or boxes too
     * thin to survive the float conversion — falls back to its bounds, then to nothing, with a warning: a block missing
     * from physics beats a server crashing in its tick.
     */
    private int addCollider(VoxelShape shape, float[] values, int boxCount, int material) {
        try {
            return grid.addCollider(values, boxCount, material);
        } catch (RuntimeException rejected) {
            AABB b = shape.bounds();
            float[] bounds = {
                (float) b.minX, (float) b.minY, (float) b.minZ, (float) b.maxX, (float) b.maxY, (float) b.maxZ
            };
            try {
                int id = grid.addCollider(bounds, 1, material);
                MineBox3D.LOGGER.warn(
                        "a block collision shape was refused ({}); using its bounds", rejected.getMessage());
                return id;
            } catch (RuntimeException alsoRejected) {
                MineBox3D.LOGGER.warn(
                        "a block collision shape was refused ({}); the block has no physics", rejected.getMessage());
                return 0;
            }
        }
    }

    private int material(Level level, BlockPos pos, BlockState state) {
        TerrainMaterials.fill(level, pos, state, scratch);
        MaterialKey key = new MaterialKey(
                scratch.friction,
                scratch.restitution,
                scratch.rollingResistance,
                scratch.tangentVelocityX,
                scratch.tangentVelocityY,
                scratch.tangentVelocityZ,
                scratch.userMaterialId,
                scratch.customColor);
        int index = materials.getInt(key);
        if (index == MISSING) {
            index = grid.addMaterial(scratch);
            materials.put(key, index);
        }
        return index;
    }

    // ---- a whole section ----------------------------------------------------------------------

    /**
     * The colliders of one chunk section, {@link VoxelGrid#SECTION_CELLS} indexed {@code x + 16 * z + 256 * y}, or
     * {@code null} when nothing in it collides.
     */
    @Nullable
    public short[] section(Level level, LevelChunk chunk, int sectionY) {
        int index = level.getSectionIndexFromSectionY(sectionY);
        if (index < 0 || index >= chunk.getSectionsCount()) {
            return null;
        }
        LevelChunkSection blocks = chunk.getSection(index);
        int ox = chunk.getPos().getMinBlockX(),
                oy = SectionPos.sectionToBlockCoord(sectionY),
                oz = chunk.getPos().getMinBlockZ();
        short[] cells = new short[VoxelGrid.SECTION_CELLS];
        boolean any = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        if (!blocks.hasOnlyAir()) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = blocks.getBlockState(x, y, z);
                        if (state.isAir()) {
                            continue;
                        }
                        int id = own(level, pos.set(ox + x, oy + y, oz + z), state);
                        cells[x + 16 * z + 256 * y] = (short) id;
                        any |= id != 0;
                    }
                }
            }
        }

        // taller shapes, here or just outside, reaching into cells of this section
        if (mayHaveLargeShapes(level, chunk.getPos().x, sectionY, chunk.getPos().z)) {
            for (int y = -1; y <= 16; y++) {
                for (int z = -1; z <= 16; z++) {
                    for (int x = -1; x <= 16; x++) {
                        BlockState state = stateAt(level, pos.set(ox + x, oy + y, oz + z));
                        if (state.isAir() || !state.hasLargeCollisionShape()) {
                            continue;
                        }
                        VoxelShape shape = state.getCollisionShape(level, pos);
                        if (shape.isEmpty()) {
                            continue;
                        }
                        AABB b = shape.bounds();
                        // the cells of this section it reaches into work their colliders out again
                        for (int ty = Math.max(0, y - 1); ty <= Math.min(15, y + 1); ty++) {
                            for (int tz = Math.max(0, z - 1); tz <= Math.min(15, z + 1); tz++) {
                                for (int tx = Math.max(0, x - 1); tx <= Math.min(15, x + 1); tx++) {
                                    int rx = tx - x, ry = ty - y, rz = tz - z;
                                    if (rx == 0 && ry == 0 && rz == 0
                                            || b.maxX <= rx
                                            || b.minX >= rx + 1
                                            || b.maxY <= ry
                                            || b.minY >= ry + 1
                                            || b.maxZ <= rz
                                            || b.minZ >= rz + 1) {
                                        continue;
                                    }
                                    int id = collider(level, new BlockPos(ox + tx, oy + ty, oz + tz));
                                    cells[tx + 16 * tz + 256 * ty] = (short) id;
                                    any |= id != 0;
                                }
                            }
                        }
                    }
                }
            }
        }
        return any ? cells : null;
    }

    private static boolean mayHaveLargeShapes(Level level, int sx, int sy, int sz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(sx + dx, sz + dz);
                if (chunk == null) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    int index = level.getSectionIndexFromSectionY(sy + dy);
                    if (index >= 0
                            && index < chunk.getSectionsCount()
                            && chunk.getSection(index)
                                    .maybeHas(state -> !state.isAir() && state.hasLargeCollisionShape())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The block at {@code pos}, air where the chunk is not loaded or outside the build height. Never waits for a chunk:
     * {@code getChunk(.., false)} on the server blocks the main thread on a chunk that is still loading.
     */
    static BlockState stateAt(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        LevelChunk chunk = level.getChunkSource()
                .getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
    }

    private record ColliderKey(float[] boxes, int material) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ColliderKey other && material == other.material && Arrays.equals(boxes, other.boxes);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(boxes) + material;
        }
    }

    /** Every field of the material: two blocks share a material only if they are the same surface in every respect. */
    private record MaterialKey(
            float friction,
            float restitution,
            float rollingResistance,
            float tangentVelocityX,
            float tangentVelocityY,
            float tangentVelocityZ,
            long userMaterialId,
            int customColor) {}
}
