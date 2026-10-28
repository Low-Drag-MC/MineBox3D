package com.lowdragmc.minebox3d.physics;

/**
 * Argument checks before a value reaches Box3D. Box3D's own checks are assertions: in MineBox3D they turn into an
 * exception when the call returns, but the bad value has already been stored by then — a NaN velocity is in the
 * solver and spreads through every contact of the world. So everything that can be wrong is refused here first.
 */
final class Checks {

    private Checks() {
    }

    static void finite(float value, String what) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(what + " is not a finite number: " + value);
        }
    }

    static void finite(double value, String what) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(what + " is not a finite number: " + value);
        }
    }

    static void finite(float x, float y, float z, String what) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException(what + " is not finite: (" + x + ", " + y + ", " + z + ")");
        }
    }

    static void finite(double x, double y, double z, String what) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException(what + " is not finite: (" + x + ", " + y + ", " + z + ")");
        }
    }

    static void positive(float value, String what) {
        if (!(value > 0) || !Float.isFinite(value)) {
            throw new IllegalArgumentException(what + " must be a positive number: " + value);
        }
    }

    static void notNegative(float value, String what) {
        if (!(value >= 0) || !Float.isFinite(value)) {
            throw new IllegalArgumentException(what + " must be zero or more: " + value);
        }
    }

    /** A rotation: finite, and not so far from unit length that normalising it is guesswork. */
    static void rotation(float x, float y, float z, float w, String what) {
        float lengthSquared = x * x + y * y + z * z + w * w;
        if (!Float.isFinite(lengthSquared) || Math.abs(lengthSquared - 1) > 1e-3f) {
            throw new IllegalArgumentException(what + " is not a unit quaternion: (" + x + ", " + y + ", " + z + ", " + w + ")");
        }
    }

    static void material(SurfaceMaterial material, String what) {
        notNegative(material.friction, what + " friction");
        notNegative(material.restitution, what + " restitution");
        notNegative(material.rollingResistance, what + " rolling resistance");
        finite(material.tangentVelocityX, material.tangentVelocityY, material.tangentVelocityZ, what + " tangent velocity");
    }

    static void shapeDef(ShapeDef def) {
        notNegative(def.density, "density");
        finite(def.explosionScale, "explosion scale");
        material(def.baseMaterial, "base material");
        if (def.materials != null) {
            for (SurfaceMaterial material : def.materials) {
                material(material, "material");
            }
        }
    }

    static void bodyDef(BodyDef def) {
        finite(def.positionX, def.positionY, def.positionZ, "body position");
        rotation(def.rotationX, def.rotationY, def.rotationZ, def.rotationW, "body rotation");
        finite(def.linearVelocityX, def.linearVelocityY, def.linearVelocityZ, "linear velocity");
        finite(def.angularVelocityX, def.angularVelocityY, def.angularVelocityZ, "angular velocity");
        notNegative(def.linearDamping, "linear damping");
        notNegative(def.angularDamping, "angular damping");
        finite(def.gravityScale, "gravity scale");
        notNegative(def.sleepThreshold, "sleep threshold");
        notNegative(def.safetyFactor, "safety factor");
    }

    static void geometry(Geometry geometry) {
        switch (geometry) {
            case Geometry.Sphere s -> {
                finite(s.cx(), s.cy(), s.cz(), "sphere centre");
                positive(s.radius(), "sphere radius");
            }
            case Geometry.Capsule c -> {
                finite(c.ax(), c.ay(), c.az(), "capsule end");
                finite(c.bx(), c.by(), c.bz(), "capsule end");
                positive(c.radius(), "capsule radius");
            }
            case Geometry.Box b -> {
                positive(b.hx(), "box half extent");
                positive(b.hy(), "box half extent");
                positive(b.hz(), "box half extent");
                finite(b.cx(), b.cy(), b.cz(), "box centre");
                rotation(b.qx(), b.qy(), b.qz(), b.qw(), "box rotation");
            }
            case Geometry.Mesh m -> {
                finite(m.sx(), m.sy(), m.sz(), "mesh scale");
                if (m.sx() == 0 || m.sy() == 0 || m.sz() == 0) {
                    throw new IllegalArgumentException("mesh scale has a zero axis");
                }
            }
        }
    }

    /** {@code count} points of three floats, all finite. */
    static void points(float[] points, int count, String what) {
        if (count < 1 || points.length < 3 * count) {
            throw new IllegalArgumentException(what + ": " + count + " points do not fit " + points.length + " floats");
        }
        for (int i = 0; i < 3 * count; i++) {
            finite(points[i], what);
        }
    }
}
