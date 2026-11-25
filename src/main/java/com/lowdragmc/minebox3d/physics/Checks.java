package com.lowdragmc.minebox3d.physics;

/**
 * Argument checks before a value reaches Box3D. Box3D's own checks are assertions: in MineBox3D they turn into an
 * exception when the call returns, but the bad value has already been stored by then — a NaN velocity is in the
 * solver and spreads through every contact of the world. So everything that can be wrong is refused here first.
 */
final class Checks {

    private Checks() {}

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

    /** A direction: finite and of unit length, give or take what normalising would not notice. */
    static void unitVector(float x, float y, float z, String what) {
        float lengthSquared = x * x + y * y + z * z;
        if (!Float.isFinite(lengthSquared) || Math.abs(lengthSquared - 1) > 1e-3f) {
            throw new IllegalArgumentException(what + " is not a unit vector: (" + x + ", " + y + ", " + z + ")");
        }
    }

    /** A rotation: finite, and not so far from unit length that normalising it is guesswork. */
    static void rotation(float x, float y, float z, float w, String what) {
        float lengthSquared = x * x + y * y + z * z + w * w;
        if (!Float.isFinite(lengthSquared) || Math.abs(lengthSquared - 1) > 1e-3f) {
            throw new IllegalArgumentException(
                    what + " is not a unit quaternion: (" + x + ", " + y + ", " + z + ", " + w + ")");
        }
    }

    /** An inertia tensor: finite, symmetric, positive semi-definite (non-negative diagonal and principal minors). */
    static void inertia(org.joml.Matrix3fc m) {
        float[] values = {m.m00(), m.m01(), m.m02(), m.m10(), m.m11(), m.m12(), m.m20(), m.m21(), m.m22()};
        for (float value : values) {
            finite(value, "inertia");
        }
        // rounding, relative to the size of the numbers (minors go with its square, the determinant its cube)
        double scale = Math.max(1, Math.max(Math.abs(m.m00()), Math.max(Math.abs(m.m11()), Math.abs(m.m22()))));
        double tolerance = 1e-5 * scale;
        if (Math.abs(m.m01() - m.m10()) > tolerance
                || Math.abs(m.m02() - m.m20()) > tolerance
                || Math.abs(m.m12() - m.m21()) > tolerance) {
            throw new IllegalArgumentException("inertia is not symmetric: " + m);
        }
        double xy = (double) m.m00() * m.m11() - (double) m.m01() * m.m10();
        double xz = (double) m.m00() * m.m22() - (double) m.m02() * m.m20();
        double yz = (double) m.m11() * m.m22() - (double) m.m12() * m.m21();
        double minor = -tolerance * scale;
        if (m.m00() < 0
                || m.m11() < 0
                || m.m22() < 0
                || xy < minor
                || xz < minor
                || yz < minor
                || m.determinant() < minor * scale) {
            throw new IllegalArgumentException("inertia is not positive semi-definite: " + m);
        }
    }

    static void range(float lower, float upper, String what) {
        if (!Float.isFinite(lower) || !Float.isFinite(upper) || lower > upper) {
            throw new IllegalArgumentException(
                    what + " must be two finite numbers, lower first: " + lower + ", " + upper);
        }
    }

    static void material(SurfaceMaterial material, String what) {
        notNegative(material.friction, what + " friction");
        notNegative(material.restitution, what + " restitution");
        notNegative(material.rollingResistance, what + " rolling resistance");
        finite(
                material.tangentVelocityX,
                material.tangentVelocityY,
                material.tangentVelocityZ,
                what + " tangent velocity");
        if ((material.userMaterialId & ~SurfaceMaterial.USER_MATERIAL_BITS) != 0) {
            throw new IllegalArgumentException(what + " user material id uses the top byte, which is MineBox3D's: "
                    + Long.toHexString(material.userMaterialId));
        }
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

    /** Box3D takes a world definition as it comes: the values its runtime setters refuse, refused here too. */
    static void worldDef(WorldDef def) {
        finite(def.gravityX, def.gravityY, def.gravityZ, "gravity");
        notNegative(def.restitutionThreshold, "restitution threshold");
        notNegative(def.hitEventThreshold, "hit event threshold");
        positive(def.contactHertz, "contact hertz");
        notNegative(def.contactDampingRatio, "contact damping ratio");
        positive(def.contactSpeed, "contact speed");
        positive(def.maximumLinearSpeed, "maximum linear speed");
        if (!(def.isotropicInertiaTolerance >= 0 && def.isotropicInertiaTolerance <= 1)) {
            throw new IllegalArgumentException(
                    "isotropic inertia tolerance is 0 to 1: " + def.isotropicInertiaTolerance);
        }
        if (def.restitutionIterations < 0 || def.workerCount < 0) {
            throw new IllegalArgumentException("restitution iterations and worker count must not be negative");
        }
        // Box3D sizes its arrays as int byte counts: a capacity in the tens of millions wraps
        for (int capacity : new int[] {
            def.staticShapeCapacity,
            def.dynamicShapeCapacity,
            def.staticBodyCapacity,
            def.dynamicBodyCapacity,
            def.contactCapacity
        }) {
            if (capacity < 0 || capacity > MAX_CAPACITY) {
                throw new IllegalArgumentException("a capacity is 0 to " + MAX_CAPACITY + ": " + capacity);
            }
        }
        if (def.threadMode == null) {
            throw new IllegalArgumentException("threadMode must not be null");
        }
    }

    static final int MAX_CAPACITY = 1 << 20;

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
            case Geometry.Hull h -> {
                // the record only ever holds Box3D's own hull
            }
            case Geometry.Mesh m -> {
                finite(m.sx(), m.sy(), m.sz(), "mesh scale");
                if (m.sx() == 0 || m.sy() == 0 || m.sz() == 0) {
                    throw new IllegalArgumentException("mesh scale has a zero axis");
                }
            }
            case Geometry.HeightField f -> {}
        }
    }

    static void points(float[] points, int count, String what) {
        if (count < 1 || points.length < 3 * count) {
            throw new IllegalArgumentException(what + ": " + count + " points do not fit " + points.length + " floats");
        }
        for (int i = 0; i < 3 * count; i++) {
            finite(points[i], what);
        }
    }
}
