package com.lowdragmc.minebox3d.physics;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * <b>The shapes an overlap query found</b>: a result object the caller keeps and passes in again (design doc
 * {@code 03-threading-and-api.md} §6.2). Read by index, valid until it is filled again. Voxel terrain shows up as its
 * section's shape id, with no {@link Shape}.
 */
public final class Overlaps {

    long[] ids;
    int count;
    @Nullable
    PhysicsWorld world;

    public Overlaps() {
        this(16);
    }

    public Overlaps(int initialCapacity) {
        ids = new long[Math.max(1, initialCapacity)];
    }

    void ensureCapacity(int shapes) {
        if (shapes > ids.length) {
            ids = Arrays.copyOf(ids, Math.max(shapes, 2 * ids.length));
        }
    }

    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public long shapeId(int i) {
        if (i < 0 || i >= count) {
            throw new IndexOutOfBoundsException("shape " + i + " of " + count);
        }
        return ids[i];
    }

    /** {@code null} for voxel terrain (and for a shape destroyed since). */
    @Nullable
    public Shape shape(int i) {
        return world == null ? null : world.shape(shapeId(i));
    }

    @Nullable
    public Body body(int i) {
        Shape shape = shape(i);
        return shape == null ? null : shape.body();
    }
}
