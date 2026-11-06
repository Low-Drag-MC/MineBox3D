package com.lowdragmc.minebox3d.physics;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>Where a turned box swept through a world first met a shape</b> ({@link PhysicsWorld#sweepBox}) — or, when it started
 * inside some, the one it was deepest in and the least way out. A result object the caller keeps and passes in again;
 * valid until it is filled again.
 *
 * <p>Exact, as a character mover's sweep needs it: the box stops where it touches (not a linear slop short), touching is
 * no hit unless the box moves into the surface, and the normal is the surface's own, out of the shape toward the box.
 */
public final class BoxSweep {

    /** {@code [fraction, nx, ny, nz, px, py, pz, depth, startPenetrating]} */
    final double[] doubles = new double[9];
    /** {@code [shape]} */
    final long[] longs = new long[1];
    boolean hit;
    @Nullable
    PhysicsWorld world;

    public boolean hit() {
        return hit;
    }

    /** How far along the sweep the box got before it touched, 0 to 1; 0 when it {@linkplain #startPenetrating started inside}. */
    public double fraction() {
        return doubles[0];
    }

    /** The surface's normal, out of the shape toward the box. */
    public Vector3f normal(Vector3f out) {
        return out.set((float) doubles[1], (float) doubles[2], (float) doubles[3]);
    }

    /** Where they touch (or overlap deepest), world space. */
    public Vector3d point(Vector3d out) {
        return out.set(doubles[4], doubles[5], doubles[6]);
    }

    /** Whether the box was inside the shape before it moved at all. */
    public boolean startPenetrating() {
        return doubles[8] != 0;
    }

    /** How far the box must move along {@link #normal} to be out, when it {@linkplain #startPenetrating started inside}. */
    public double depth() {
        return doubles[7];
    }

    /** The shape met, {@code null} for one destroyed since. */
    @Nullable
    public Shape shape() {
        return hit && world != null ? world.shape(longs[0]) : null;
    }

    void clear(PhysicsWorld world) {
        this.world = world;
        hit = false;
    }
}
