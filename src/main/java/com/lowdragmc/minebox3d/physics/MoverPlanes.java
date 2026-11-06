package com.lowdragmc.minebox3d.physics;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.Arrays;

/**
 * <b>The collision planes around a character mover</b> ({@link PhysicsWorld#collideMover}): a result object the caller
 * keeps and passes in again (design doc {@code 03-threading-and-api.md} §6.2). Read by index, valid until it is filled
 * again. A plane on voxel terrain names the block cell.
 */
public final class MoverPlanes {

    /** {@code [nx, ny, nz, offset, px, py, pz]} per plane */
    static final int FLOATS = 7;
    static final int CELL = 4;

    float[] floats;
    long[] shapes;
    int[] cells;
    int count;
    @Nullable
    PhysicsWorld world;

    public MoverPlanes() {
        this(16);
    }

    public MoverPlanes(int initialCapacity) {
        int capacity = Math.max(1, initialCapacity);
        floats = new float[FLOATS * capacity];
        shapes = new long[capacity];
        cells = new int[CELL * capacity];
    }

    int capacity() {
        return shapes.length;
    }

    void grow() {
        int capacity = 2 * shapes.length;
        floats = Arrays.copyOf(floats, FLOATS * capacity);
        shapes = Arrays.copyOf(shapes, capacity);
        cells = Arrays.copyOf(cells, CELL * capacity);
    }

    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    private int at(int i) {
        if (i < 0 || i >= count) {
            throw new IndexOutOfBoundsException("plane " + i + " of " + count);
        }
        return i;
    }

    public Vector3f normal(int i, Vector3f out) {
        int o = FLOATS * at(i);
        return out.set(floats[o], floats[o + 1], floats[o + 2]);
    }

    /** The plane's offset along its normal, relative to the query origin. */
    public float offset(int i) {
        return floats[FLOATS * at(i) + 3];
    }

    /** The point on the plane, relative to the query origin. */
    public Vector3f point(int i, Vector3f out) {
        int o = FLOATS * at(i) + 4;
        return out.set(floats[o], floats[o + 1], floats[o + 2]);
    }

    public long shapeId(int i) {
        return shapes[at(i)];
    }

    @Nullable
    public Shape shape(int i) {
        return world == null ? null : world.shape(shapeId(i));
    }

    public boolean isTerrain(int i) {
        return cells[CELL * at(i)] != 0;
    }

    public Vector3i cell(int i, Vector3i out) {
        int o = CELL * at(i);
        return out.set(cells[o + 1], cells[o + 2], cells[o + 3]);
    }
}
