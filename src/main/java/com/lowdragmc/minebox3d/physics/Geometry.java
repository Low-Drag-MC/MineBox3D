package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import java.util.Arrays;

/**
 * <b>What a shape is made of</b>, in its body's local space. Kept on the {@link Shape} so the Java side
 * can draw and reason about a shape without asking Box3D.
 */
public sealed interface Geometry {

    record Sphere(float cx, float cy, float cz, float radius) implements Geometry {}

    /** A capsule: the segment {@code a → b} swept by a sphere of {@code radius}. */
    record Capsule(float ax, float ay, float az, float bx, float by, float bz, float radius) implements Geometry {}

    /** A box of half extents {@code (hx, hy, hz)}, centred at {@code c}, turned by the unit quaternion {@code q}. */
    record Box(float hx, float hy, float hz, float cx, float cy, float cz, float qx, float qy, float qz, float qw)
            implements Geometry {
        public static Box of(float hx, float hy, float hz) {
            return new Box(hx, hy, hz, 0, 0, 0, 0, 0, 0, 1);
        }

        public static Box of(float hx, float hy, float hz, float cx, float cy, float cz) {
            return new Box(hx, hy, hz, cx, cy, cz, 0, 0, 0, 1);
        }
    }

    /**
     * A convex hull. {@code points} are its vertices, {@code [x, y, z]} each: whatever points it is made from, the
     * record keeps Box3D's hull of them, at most {@link #MAX_VERTICES} (Box3D simplifies one with more). ⚠️ Do not
     * change the array.
     */
    record Hull(float[] points) implements Geometry {
        /** Box3D's {@code B3_MAX_HULL_VERTICES}, which is also the most points a query shape may have. */
        public static final int MAX_VERTICES = 128;

        /** The hull around {@code points} ({@code [x, y, z]} each, at least four not in one plane). */
        public Hull {
            if (points.length % 3 != 0) {
                throw new IllegalArgumentException("points come in threes");
            }
            Checks.points(points, points.length / 3, "hull points");
            NativeLibrary.load();
            float[] vertices = new float[3 * B3.getMaxHullVertices()];
            int count = B3.computeHull(points, points.length / 3, vertices);
            points = Arrays.copyOf(vertices, 3 * count);
        }

        public static Hull of(float... points) {
            return new Hull(points);
        }

        public int vertexCount() {
            return points.length / 3;
        }
    }

    /**
     * A triangle mesh, scaled per axis, meant for static bodies. It collides only with spheres, capsules, boxes and
     * hulls, never another mesh, height field or terrain.
     */
    record Mesh(MeshData data, float sx, float sy, float sz) implements Geometry {
        public static Mesh of(MeshData data) {
            return new Mesh(data, 1, 1, 1);
        }
    }

    /** A height field — see {@link HeightFieldData}. Static bodies only. */
    record HeightField(HeightFieldData data) implements Geometry {}

    /** Whether it has a radius (a sphere or capsule): Box3D measures distances to its core, not its surface. */
    default boolean isRounded() {
        return this instanceof Sphere || this instanceof Capsule;
    }

    /** Whether Box3D treats it as one convex piece (a mesh or height field is many triangles). */
    default boolean isConvex() {
        return !(this instanceof Mesh || this instanceof HeightField);
    }

    /**
     * What Box3D makes of it: a capsule whose ends are within its linear slop is a sphere halfway between them (Box3D
     * makes one when the shape is created, but not when an existing shape is given it).
     */
    static Geometry asBuilt(Geometry geometry) {
        if (geometry instanceof Capsule c) {
            float dx = c.bx() - c.ax(), dy = c.by() - c.ay(), dz = c.bz() - c.az();
            if (dx * dx + dy * dy + dz * dz <= LINEAR_SLOP * LINEAR_SLOP) {
                return new Sphere((c.ax() + c.bx()) / 2, (c.ay() + c.by()) / 2, (c.az() + c.bz()) / 2, c.radius());
            }
        }
        return geometry;
    }

    /** Box3D's {@code B3_LINEAR_SLOP}, m. */
    float LINEAR_SLOP = 0.005f;
}
