package com.lowdragmc.minebox3d.physics;

/**
 * <b>What a shape is made of</b>, in its body's local space. Kept on the {@link Shape} so the Java side
 * can draw and reason about a shape without asking Box3D.
 */
public sealed interface Geometry {

    /** A sphere of {@code radius} centred at {@code (cx, cy, cz)}. */
    record Sphere(float cx, float cy, float cz, float radius) implements Geometry {
    }

    /** A capsule: the segment {@code a → b} swept by a sphere of {@code radius}. */
    record Capsule(float ax, float ay, float az, float bx, float by, float bz, float radius) implements Geometry {
    }

    /** A box of half extents {@code (hx, hy, hz)}, centred at {@code c}, turned by the unit quaternion {@code q}. */
    record Box(float hx, float hy, float hz, float cx, float cy, float cz,
               float qx, float qy, float qz, float qw) implements Geometry {
        public static Box of(float hx, float hy, float hz) {
            return new Box(hx, hy, hz, 0, 0, 0, 0, 0, 0, 1);
        }

        public static Box of(float hx, float hy, float hz, float cx, float cy, float cz) {
            return new Box(hx, hy, hz, cx, cy, cz, 0, 0, 0, 1);
        }
    }

    /** A triangle mesh, scaled per axis. Static bodies only collide with dynamic ones through it. */
    record Mesh(MeshData data, float sx, float sy, float sz) implements Geometry {
        public static Mesh of(MeshData data) {
            return new Mesh(data, 1, 1, 1);
        }
    }
}
