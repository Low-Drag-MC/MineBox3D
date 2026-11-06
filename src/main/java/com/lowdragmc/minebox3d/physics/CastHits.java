package com.lowdragmc.minebox3d.physics;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.Arrays;

/**
 * <b>Hits of a ray or shape cast</b>, closest first: a result object the caller keeps and passes in again, so a query
 * allocates nothing once it has grown to fit (design doc {@code 03-threading-and-api.md} §6.2). Read by index, valid
 * until it is filled again. A hit on voxel terrain has no {@link Shape}; it names the block cell instead.
 */
public final class CastHits {

    /** {@code [px, py, pz, fraction, nx, ny, nz, terrain, cx, cy, cz]} per hit */
    static final int DOUBLES = 11;
    /** {@code [shape, userMaterialId]} per hit */
    static final int LONGS = 2;

    double[] doubles;
    long[] longs;
    int count;
    @Nullable
    PhysicsWorld world;

    public CastHits() {
        this(8);
    }

    public CastHits(int initialCapacity) {
        int capacity = Math.max(1, initialCapacity);
        doubles = new double[DOUBLES * capacity];
        longs = new long[LONGS * capacity];
    }

    int capacity() {
        return longs.length / LONGS;
    }

    void ensureCapacity(int hits) {
        if (hits > capacity()) {
            int capacity = Math.max(hits, 2 * capacity());
            doubles = Arrays.copyOf(doubles, DOUBLES * capacity);
            longs = Arrays.copyOf(longs, LONGS * capacity);
        }
    }

    void clear(PhysicsWorld world) {
        this.world = world;
        count = 0;
    }

    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    private int at(int i) {
        if (i < 0 || i >= count) {
            throw new IndexOutOfBoundsException("hit " + i + " of " + count);
        }
        return i;
    }

    /** The shape hit, {@code null} on voxel terrain (and for a shape destroyed since). */
    @Nullable
    public Shape shape(int i) {
        return world == null ? null : world.shape(shapeId(i));
    }

    public long shapeId(int i) {
        return longs[LONGS * at(i)];
    }

    @Nullable
    public Body body(int i) {
        Shape shape = shape(i);
        return shape == null ? null : shape.body();
    }

    public double x(int i) {
        return doubles[DOUBLES * at(i)];
    }

    public double y(int i) {
        return doubles[DOUBLES * at(i) + 1];
    }

    public double z(int i) {
        return doubles[DOUBLES * at(i) + 2];
    }

    public Vector3d point(int i, Vector3d out) {
        int o = DOUBLES * at(i);
        return out.set(doubles[o], doubles[o + 1], doubles[o + 2]);
    }

    /** How far along the cast, 0 to 1. */
    public float fraction(int i) {
        return (float) doubles[DOUBLES * at(i) + 3];
    }

    /** The surface normal at the hit, pointing out of what was hit. */
    public Vector3f normal(int i, Vector3f out) {
        int o = DOUBLES * at(i) + 4;
        return out.set((float) doubles[o], (float) doubles[o + 1], (float) doubles[o + 2]);
    }

    /** The surface material's id: on terrain the block's, from the grid's table. */
    public long userMaterialId(int i) {
        return longs[LONGS * at(i) + 1];
    }

    /** Whether the hit is on voxel terrain, which names a block cell. */
    public boolean isTerrain(int i) {
        return doubles[DOUBLES * at(i) + 7] != 0;
    }

    /** The block cell hit, on terrain; zeros otherwise. */
    public Vector3i cell(int i, Vector3i out) {
        int o = DOUBLES * at(i) + 8;
        return out.set((int) doubles[o], (int) doubles[o + 1], (int) doubles[o + 2]);
    }

    public int cellX(int i) {
        return (int) doubles[DOUBLES * at(i) + 8];
    }

    public int cellY(int i) {
        return (int) doubles[DOUBLES * at(i) + 9];
    }

    public int cellZ(int i) {
        return (int) doubles[DOUBLES * at(i) + 10];
    }

    /** A copy of hit {@code i} that stays as it is. */
    public CastHit toHit(int i) {
        int d = DOUBLES * at(i);
        return new CastHit(shape(i), shapeId(i), doubles[d], doubles[d + 1], doubles[d + 2], (float) doubles[d + 4],
                (float) doubles[d + 5], (float) doubles[d + 6], (float) doubles[d + 3], userMaterialId(i), isTerrain(i), cellX(i),
                cellY(i), cellZ(i));
    }
}
