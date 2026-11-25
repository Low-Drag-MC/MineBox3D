package com.lowdragmc.minebox3d.physics;

import java.util.Arrays;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * <b>Where a body touches things</b>, as of the last step: a result object the caller keeps and passes in again (design
 * doc {@code 03-threading-and-api.md} §6.2). Read by index, valid until it is filled again. A point on voxel terrain
 * names the block cell.
 */
public final class ContactPoints {

    /** {@code [nx, ny, nz, separation, normalImpulse, totalNormalImpulse, normalVelocity, ax, ay, az]} per point */
    static final int FLOATS = 10;
    /** {@code [terrain, x, y, z]} per point */
    static final int CELL = 4;

    float[] floats;
    long[] shapes;
    int[] cells;
    int count;

    @Nullable
    PhysicsWorld world;

    public ContactPoints() {
        this(16);
    }

    public ContactPoints(int initialCapacity) {
        int capacity = Math.max(1, initialCapacity);
        floats = new float[FLOATS * capacity];
        shapes = new long[capacity];
        cells = new int[CELL * capacity];
    }

    void ensureCapacity(int points) {
        if (points > shapes.length) {
            int capacity = Math.max(points, 2 * shapes.length);
            floats = Arrays.copyOf(floats, FLOATS * capacity);
            shapes = Arrays.copyOf(shapes, capacity);
            cells = Arrays.copyOf(cells, CELL * capacity);
        }
    }

    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    private int at(int i) {
        if (i < 0 || i >= count) {
            throw new IndexOutOfBoundsException("point " + i + " of " + count);
        }
        return i;
    }

    /** The contact normal, from the other shape into this body. */
    public Vector3f normal(int i, Vector3f out) {
        int o = FLOATS * at(i);
        return out.set(floats[o], floats[o + 1], floats[o + 2]);
    }

    /** Negative when overlapping. */
    public float separation(int i) {
        return floats[FLOATS * at(i) + 3];
    }

    /** The impulse the last sub-step of the last step pushed with at this point, N·s. */
    public float normalImpulse(int i) {
        return floats[FLOATS * at(i) + 4];
    }

    /** The impulse the whole last step pushed with at this point (every sub-step and the bounce), N·s. */
    public float totalNormalImpulse(int i) {
        return floats[FLOATS * at(i) + 5];
    }

    /**
     * How fast this body moved along the normal relative to the other at the start of the last step, m/s: negative
     * coming together. ⚠️ Box3D only keeps it for a pair where either shape has {@linkplain ShapeDef#enableHitEvents
     * hit events} on; otherwise 0.
     */
    public float normalVelocity(int i) {
        return floats[FLOATS * at(i) + 6];
    }

    /** The point on this body, relative to its centre of mass, in world orientation. */
    public Vector3f anchor(int i, Vector3f out) {
        int o = FLOATS * at(i) + 7;
        return out.set(floats[o], floats[o + 1], floats[o + 2]);
    }

    public long otherShapeId(int i) {
        return shapes[at(i)];
    }

    /** The shape touched; {@code null} on voxel terrain. */
    @Nullable
    public Shape otherShape(int i) {
        return world == null ? null : world.shape(otherShapeId(i));
    }

    public boolean isTerrain(int i) {
        return cells[CELL * at(i)] != 0;
    }

    /** The block cell touched, on terrain; zeros otherwise. */
    public Vector3i cell(int i, Vector3i out) {
        int o = CELL * at(i);
        return out.set(cells[o + 1], cells[o + 2], cells[o + 3]);
    }
}
