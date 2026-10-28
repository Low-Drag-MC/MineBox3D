package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.jni.B3;
import org.jetbrains.annotations.Nullable;

/**
 * <b>Block terrain</b> in a world: our Box3D fork's voxel grid (design doc {@code 01-voxel-terrain.md}).
 *
 * <p>The grid is cut into sections of 16 × 16 × 16 unit cells. A cell holds a collider id, 0 being
 * empty; a collider is up to {@link #MAX_BOXES} boxes inside the unit cell plus a surface material, added
 * once with {@link #addCollider}. Each section with anything in it becomes a static body of its own,
 * made and destroyed by the grid. Collision sees only the parts of box faces no neighbouring box covers,
 * so a floor of blocks is one flat surface, and a body that ends up inside a block is pushed out of the
 * nearest open face. Changing a cell rebuilds nothing.
 *
 * <p>Belongs to its world and follows its one-thread rule. Not during a step.
 */
public final class VoxelGrid implements AutoCloseable {

    public static final int SECTION_SIZE = 16;
    public static final int SECTION_CELLS = 4096;
    public static final int MAX_BOXES = 64;

    private final PhysicsWorld world;
    private long pointer;

    VoxelGrid(PhysicsWorld world, long pointer) {
        this.world = world;
        this.pointer = pointer;
    }

    public PhysicsWorld world() {
        return world;
    }

    public boolean isOpen() {
        return pointer != 0;
    }

    private long pointer() {
        if (pointer == 0) {
            throw new IllegalStateException("VoxelGrid is closed");
        }
        return pointer;
    }

    /** Adds a surface material to the grid's table and returns its index. */
    public int addMaterial(SurfaceMaterial material) {
        world.enter();
        try {
            Checks.material(material, "voxel material");
            return B3.voxelGridAddMaterial(pointer(), material);
        } finally {
            world.exit();
        }
    }

    /**
     * Adds a collider and returns its id, 1 or more.
     *
     * @param boxes    {@code [minX, minY, minZ, maxX, maxY, maxZ]} per box, inside the unit cell; boxes may
     *                 touch but not overlap
     * @param material an index from {@link #addMaterial}
     */
    public int addCollider(float[] boxes, int boxCount, int material) {
        world.enter();
        try {
            if (boxCount < 1 || boxCount > MAX_BOXES || boxes.length < 6 * boxCount) {
                throw new IllegalArgumentException("a collider is 1 to " + MAX_BOXES + " boxes of 6 floats, got " + boxCount
                        + " in " + boxes.length + " floats");
            }
            for (int i = 0; i < 6 * boxCount; i++) {
                Checks.finite(boxes[i], "collider box");
            }
            return B3.voxelGridAddCollider(pointer(), boxes, boxCount, material);
        } finally {
            world.exit();
        }
    }

    /** The number of colliders, counting the empty collider 0. */
    public int colliderCount() {
        world.enter();
        try {
            return B3.voxelGridGetColliderCount(pointer());
        } finally {
            world.exit();
        }
    }

    /**
     * Replaces a section. {@code cells} is {@link #SECTION_CELLS} collider ids indexed
     * {@code x + 16 * z + 256 * y}, Minecraft's own order in a chunk section; null or all zero removes it.
     */
    public void setSection(int sectionX, int sectionY, int sectionZ, short @Nullable [] cells) {
        world.enter();
        try {
            if (cells != null && cells.length != SECTION_CELLS) {
                throw new IllegalArgumentException("a section is " + SECTION_CELLS + " cells, got " + cells.length);
            }
            B3.voxelGridSetSection(pointer(), sectionX, sectionY, sectionZ, cells);
        } finally {
            world.exit();
        }
    }

    /**
     * Changes cells: {@code [x, y, z, collider]} per edit, in grid cell coordinates. A cell in a section
     * that does not exist yet creates it. Bodies near the changes are woken.
     */
    public void setCells(int[] edits, int count) {
        world.enter();
        try {
            if (count < 0 || count > edits.length / 4) {
                throw new IllegalArgumentException(count + " edits do not fit " + edits.length + " ints");
            }
            B3.voxelGridSetCells(pointer(), edits, count);
        } finally {
            world.exit();
        }
    }

    public int cell(int x, int y, int z) {
        world.enter();
        try {
            return B3.voxelGridGetCell(pointer(), x, y, z);
        } finally {
            world.exit();
        }
    }

    public boolean hasSection(int sectionX, int sectionY, int sectionZ) {
        world.enter();
        try {
            return B3.voxelGridHasSection(pointer(), sectionX, sectionY, sectionZ);
        } finally {
            world.exit();
        }
    }

    public int sectionCount() {
        world.enter();
        try {
            return B3.voxelGridGetSectionCount(pointer());
        } finally {
            world.exit();
        }
    }

    /** Edge flag bits of {@link #exposedTriangles}: edge 1 (vertex 1 to 2), 2 (2 to 3), 3 (3 to 1) is flat. */
    public static final int FLAT_EDGE_1 = 0x11, FLAT_EDGE_2 = 0x22, FLAT_EDGE_3 = 0x44;

    /**
     * What contacts see of a section: the exposed parts of its block faces as outward-facing triangles,
     * those overlapping {@code bounds} ({@code [minX, minY, minZ, maxX, maxY, maxZ]} in section coordinates,
     * 0 to 16). Nine floats each into {@code vertices}, section coordinates, and the edge flags into
     * {@code flags}; a flat edge is one the surface carries on past in the same plane. Returns how many
     * there are, which may be more than the arrays take. For debug drawing and tests: worked out on each call.
     */
    public int exposedTriangles(int sectionX, int sectionY, int sectionZ, float[] bounds, float[] vertices, byte[] flags) {
        world.enter();
        try {
            return B3.voxelGridCollectTriangles(pointer(), sectionX, sectionY, sectionZ, bounds, vertices, flags);
        } finally {
            world.exit();
        }
    }

    /** The shape id of a section, 0 if there is none. */
    public long sectionShapeId(int sectionX, int sectionY, int sectionZ) {
        world.enter();
        try {
            return B3.voxelGridGetSectionShape(pointer(), sectionX, sectionY, sectionZ);
        } finally {
            world.exit();
        }
    }

    /**
     * The grid cell a hit on voxel terrain in this grid's world names ({@link CastHit#triangleIndex()}, a
     * contact point's triangle index), into {@code out[0..2]}. False if the shape is not voxel terrain or no
     * longer exists.
     */
    public boolean featureCell(long shapeId, int triangleIndex, int[] out) {
        world.enter();
        try {
            world.checkOpen();
            // the grid is passed so a shape of another world (a server hit asked of the client's grid) is refused
            // without touching that world
            return B3.voxelShapeGetFeatureCell(pointer(), shapeId, triangleIndex, out);
        } finally {
            world.exit();
        }
    }

    /** Destroys the grid and its section bodies. */
    @Override
    public void close() {
        if (pointer == 0) {
            return;
        }
        world.enter();
        try {
            world.forgetGrid(this);
            free();
        } finally {
            world.exit();
        }
    }

    /** Frees the native grid; its sections' bodies too while the world is alive. */
    void free() {
        if (pointer != 0) {
            B3.destroyVoxelGrid(pointer);
            pointer = 0;
        }
    }
}
