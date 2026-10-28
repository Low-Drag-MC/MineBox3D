package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.jni.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>Box3D mesh data</b>: the triangles of a mesh shape, with Box3D's BVH and edge flags built once.
 *
 * <p>⚠️ Box3D keeps only a pointer to this from every shape made of it, so the native data must
 * outlive those shapes. This object counts them: {@link #close()} with shapes still alive only marks
 * it, and the last shape to go frees it. Not tied to a world — one mesh can back shapes in many.
 *
 * <p>Keeps its own copy of the vertices and indices for the Java side (debug drawing, tests).
 */
public final class MeshData implements AutoCloseable {

    private final float[] vertices;
    private final int[] indices;
    private final long pointer;
    /** shapes alive + 1 for the owner until {@link #close()} */
    private final AtomicInteger references = new AtomicInteger(1);

    private MeshData(float[] vertices, int[] indices, long pointer) {
        this.vertices = vertices;
        this.indices = indices;
        this.pointer = pointer;
    }

    /**
     * @param vertices        x, y, z per vertex
     * @param indices         three per triangle, counter-clockwise seen from outside
     * @param identifyEdges   let Box3D find which triangle edges are shared, so bodies sliding over
     *                        them do not catch on edges inside a flat surface
     */
    public static MeshData create(float[] vertices, int[] indices, boolean identifyEdges) {
        return create(vertices, indices, null, identifyEdges);
    }

    /** @param materialIndices one per triangle, into the shape definition's {@code materials}, or null */
    public static MeshData create(float[] vertices, int[] indices, byte[] materialIndices, boolean identifyEdges) {
        NativeLibrary.load();
        if (vertices.length % 3 != 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("vertices and indices must come in threes");
        }
        long pointer = B3.createMesh(vertices, vertices.length / 3, indices, indices.length / 3, materialIndices,
                1.0e-4f, true, false, identifyEdges, false);
        return new MeshData(vertices.clone(), indices.clone(), pointer);
    }

    public float[] vertices() {
        return vertices;
    }

    public int[] indices() {
        return indices;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    long pointer() {
        if (references.get() <= 0) {
            throw new IllegalStateException("mesh data already freed");
        }
        return pointer;
    }

    void retain() {
        // never bring a freed mesh back: count up only from a live count
        int count;
        do {
            count = references.get();
            if (count <= 0) {
                throw new IllegalStateException("mesh data already freed");
            }
        } while (!references.compareAndSet(count, count + 1));
    }

    void release() {
        if (references.decrementAndGet() == 0) {
            B3.destroyMesh(pointer);
        }
    }

    private volatile boolean closed;

    /** Frees the native data now, or as soon as the last shape using it is destroyed. */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            release();
        }
    }
}
