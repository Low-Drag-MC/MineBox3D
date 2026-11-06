package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>Box3D height-field data</b>: a grid of heights, compressed and with its triangles' edge flags, built once.
 *
 * <p>The grid has {@code countX} points along x and {@code countZ} along z, {@code scaleX} and {@code scaleZ} apart;
 * the shape's origin is the first point. Heights are clamped to {@code [minHeight, maxHeight]} and quantised within
 * it: two height fields meant to line up should share the range.
 *
 * <p>⚠️ Box3D keeps only a pointer to this from every shape made of it, so it counts them like {@link MeshData}:
 * {@link #close()} with shapes alive only marks it, and the last shape to go frees it. Not tied to a world. Height-field
 * shapes go on static bodies only.
 */
public final class HeightFieldData implements AutoCloseable {

    /** The most points a height field has: 4096 × 4096. */
    public static final int MAX_POINTS = 1 << 24;

    private final float[] heights;
    private final int countX, countZ;
    private final float scaleX, scaleY, scaleZ;
    private final long pointer;
    /** shapes alive + 1 for the owner until {@link #close()} */
    private final AtomicInteger references = new AtomicInteger(1);
    private final Owner owner;
    private final Cleaner.Cleanable cleanable;

    private HeightFieldData(float[] heights, int countX, int countZ, float scaleX, float scaleY, float scaleZ, long pointer) {
        this.heights = heights;
        this.countX = countX;
        this.countZ = countZ;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.scaleZ = scaleZ;
        this.pointer = pointer;
        this.owner = new Owner(pointer, references);
        this.cleanable = Cleaners.CLEANER.register(this, owner);
    }

    /**
     * @param heights   {@code countX * countZ}, index {@code z * countX + x}, unscaled
     * @param materials one per cell ({@code (countX - 1) * (countZ - 1)}, index {@code z * (countX - 1) + x}) into the
     *                  shape definition's {@code materials}; {@link #HOLE} leaves the cell out. Null for none.
     */
    public static HeightFieldData create(float[] heights, int countX, int countZ, @Nullable byte[] materials, float scaleX,
                                         float scaleY, float scaleZ, float minHeight, float maxHeight) {
        NativeLibrary.load();
        // as a long: the int product wraps, and Box3D sizes its cells and triangles from it
        if (countX < 2 || countZ < 2 || (long) countX * countZ != heights.length || heights.length > MAX_POINTS) {
            throw new IllegalArgumentException("a height field is 2 x 2 to " + MAX_POINTS + " points, countX * countZ heights: "
                    + countX + " x " + countZ + ", " + heights.length + " heights");
        }
        if (materials != null && materials.length != (countX - 1) * (countZ - 1)) {
            throw new IllegalArgumentException("one material per cell: " + (countX - 1) * (countZ - 1));
        }
        for (float height : heights) {
            Checks.finite(height, "height");
        }
        Checks.positive(scaleX, "scale");
        Checks.positive(scaleY, "scale");
        Checks.positive(scaleZ, "scale");
        Checks.range(minHeight, maxHeight, "height range");
        long pointer = B3.createHeightField(heights, countX, countZ, materials, scaleX, scaleY, scaleZ, minHeight, maxHeight,
                false);
        return new HeightFieldData(heights.clone(), countX, countZ, scaleX, scaleY, scaleZ, pointer);
    }

    /** {@link #create} with no materials, scaled by 1, over the heights' own range. */
    public static HeightFieldData create(float[] heights, int countX, int countZ) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float height : heights) {
            min = Math.min(min, height);
            max = Math.max(max, height);
        }
        return create(heights, countX, countZ, null, 1, 1, 1, min, max);
    }

    /** The material index of a cell left out of the field. */
    public static final byte HOLE = (byte) 0xFF;

    public float[] heights() {
        return heights;
    }

    public int countX() {
        return countX;
    }

    public int countZ() {
        return countZ;
    }

    public float scaleX() {
        return scaleX;
    }

    public float scaleY() {
        return scaleY;
    }

    public float scaleZ() {
        return scaleZ;
    }

    long pointer() {
        if (references.get() <= 0) {
            throw new IllegalStateException("height field data already freed");
        }
        return pointer;
    }

    void retain() {
        int count;
        do {
            count = references.get();
            if (count <= 0) {
                throw new IllegalStateException("height field data already freed");
            }
        } while (!references.compareAndSet(count, count + 1));
    }

    void release() {
        if (references.decrementAndGet() == 0) {
            B3.destroyHeightField(pointer);
        }
    }

    /** Frees the native data now, or as soon as the last shape using it is destroyed. */
    @Override
    public synchronized void close() {
        if (!owner.closed) {
            owner.closed = true;
            release();
            cleanable.clean();
        }
    }

    private static final class Owner implements Runnable {
        private final long pointer;
        private final AtomicInteger references;
        private volatile boolean closed;

        Owner(long pointer, AtomicInteger references) {
            this.pointer = pointer;
            this.references = references;
        }

        @Override
        public void run() {
            if (closed) {
                return;
            }
            Cleaners.LOGGER.warn("HeightFieldData was garbage collected without close(); freeing it now");
            if (references.decrementAndGet() == 0) {
                B3.destroyHeightField(pointer);
            }
        }
    }
}
