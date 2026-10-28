package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.jni.B3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A rigid body in a {@link PhysicsWorld}: a handle to Box3D's body plus two copies of its transform —
 * where it was at the start of the current interpolation frame and where it is now — so a renderer
 * can draw it between the two (see {@link PhysicsWorld#beginInterpolationFrame()}).
 *
 * <p>Belongs to its world's owner thread, like the world.
 */
public final class Body {

    private final PhysicsWorld world;
    final long id;
    private final List<Shape> shapes = new ArrayList<>(2);
    /** [px, py, pz, qx, qy, qz, qw] */
    final double[] current = new double[7];
    final double[] previous = new double[7];
    /** whether {@link #current} changed in the current interpolation frame */
    boolean movedThisFrame;
    /** kept on this side: asked for every tick by the terrain streamer, and only this class changes it */
    private BodyType type;
    @Nullable
    private Object userObject;
    private boolean destroyed;

    Body(PhysicsWorld world, long id, BodyDef def) {
        this.world = world;
        this.id = id;
        this.type = def.type;
        current[0] = def.positionX;
        current[1] = def.positionY;
        current[2] = def.positionZ;
        current[3] = def.rotationX;
        current[4] = def.rotationY;
        current[5] = def.rotationZ;
        current[6] = def.rotationW;
        System.arraycopy(current, 0, previous, 0, 7);
    }

    public PhysicsWorld world() {
        return world;
    }

    public long id() {
        return id;
    }

    public boolean isValid() {
        return !destroyed && world.isOpen();
    }

    @Nullable
    public Object userObject() {
        return userObject;
    }

    public void setUserObject(@Nullable Object userObject) {
        this.userObject = userObject;
    }

    public List<Shape> shapes() {
        return Collections.unmodifiableList(shapes);
    }

    // ---- shapes -------------------------------------------------------------------------------

    public Shape createShape(ShapeDef def, Geometry geometry) {
        world.enter();
        try {
            checkValid();
            Checks.shapeDef(def);
            Checks.geometry(geometry);
            long shapeId = switch (geometry) {
                case Geometry.Sphere s -> B3.createSphereShape(id, def, s.cx(), s.cy(), s.cz(), s.radius());
                case Geometry.Capsule c -> B3.createCapsuleShape(id, def, c.ax(), c.ay(), c.az(), c.bx(), c.by(), c.bz(), c.radius());
                case Geometry.Box b -> B3.createBoxShape(id, def, b.hx(), b.hy(), b.hz(), b.cx(), b.cy(), b.cz(),
                        b.qx(), b.qy(), b.qz(), b.qw());
                case Geometry.Mesh m -> {
                    m.data().retain();
                    try {
                        yield B3.createMeshShape(id, def, m.data().pointer(), m.sx(), m.sy(), m.sz());
                    } catch (RuntimeException e) {
                        m.data().release();
                        throw e;
                    }
                }
            };
            Shape shape = new Shape(this, shapeId, geometry);
            shapes.add(shape);
            world.registerShape(shape);
            return shape;
        } finally {
            world.exit();
        }
    }

    void destroyShape(Shape shape, boolean updateBodyMass) {
        world.enter();
        try {
            if (!shape.isValid() || shape.body() != this) {
                return;
            }
            B3.destroyShape(shape.id, updateBodyMass);
            shapes.remove(shape);
            world.unregisterShape(shape);
            shape.markDestroyed();
        } finally {
            world.exit();
        }
    }

    /** Called by the world when Box3D has already freed this body (its own destroy, or the world's). */
    void markDestroyed() {
        if (!destroyed) {
            destroyed = true;
            for (Shape shape : shapes) {
                world.unregisterShape(shape);
                shape.markDestroyed();
            }
            shapes.clear();
        }
    }

    public void destroy() {
        world.destroyBody(this);
    }

    // ---- transform ----------------------------------------------------------------------------

    /** Where the body is now, as of the last step or the last call that moved it. */
    public Vector3d position(Vector3d out) {
        return out.set(current[0], current[1], current[2]);
    }

    public Quaternionf rotation(Quaternionf out) {
        return out.set((float) current[3], (float) current[4], (float) current[5], (float) current[6]);
    }

    /** Between the start of the interpolation frame ({@code partial = 0}) and now ({@code partial = 1}). */
    public Vector3d renderPosition(float partial, Vector3d out) {
        return out.set(previous[0] + (current[0] - previous[0]) * partial,
                previous[1] + (current[1] - previous[1]) * partial,
                previous[2] + (current[2] - previous[2]) * partial);
    }

    public Quaternionf renderRotation(float partial, Quaternionf out) {
        out.set((float) previous[3], (float) previous[4], (float) previous[5], (float) previous[6]);
        return out.slerp(new Quaternionf((float) current[3], (float) current[4], (float) current[5], (float) current[6]), partial);
    }

    /**
     * Teleports the body, and wakes it if it moves: a sleeping body put in mid air falls. Not interpolated: the
     * previous frame jumps along with it.
     */
    public void setTransform(double x, double y, double z, Quaternionfc rotation) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "position");
            float qx = rotation.x(), qy = rotation.y(), qz = rotation.z(), qw = rotation.w();
            float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (!(length > 1e-6f) || !Float.isFinite(length)) {
                throw new IllegalArgumentException("rotation has no direction: " + rotation);
            }
            qx /= length;
            qy /= length;
            qz /= length;
            qw /= length;
            B3.bodySetTransform(id, x, y, z, qx, qy, qz, qw);
            if (type != BodyType.STATIC) {
                B3.bodySetAwake(id, true);
            }
            current[0] = x;
            current[1] = y;
            current[2] = z;
            current[3] = qx;
            current[4] = qy;
            current[5] = qz;
            current[6] = qw;
            System.arraycopy(current, 0, previous, 0, 7);
        } finally {
            world.exit();
        }
    }

    /**
     * Drives a kinematic body to the target over {@code timeStep} by velocity, so what it meets on the
     * way is pushed. The transform this reports changes when the world steps, like a dynamic body's.
     */
    public void setTargetTransform(double x, double y, double z, Quaternionfc rotation, float timeStep) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "target position");
            Checks.positive(timeStep, "time step");
            float qx = rotation.x(), qy = rotation.y(), qz = rotation.z(), qw = rotation.w();
            float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (!(length > 1e-6f) || !Float.isFinite(length)) {
                throw new IllegalArgumentException("target rotation has no direction: " + rotation);
            }
            B3.bodySetTargetTransform(id, x, y, z, qx / length, qy / length, qz / length, qw / length, timeStep, true);
        } finally {
            world.exit();
        }
    }

    // ---- motion -------------------------------------------------------------------------------

    public Vector3f linearVelocity(Vector3f out) {
        world.enter();
        try {
            checkValid();
            float[] v = world.vector;
            B3.bodyGetLinearVelocity(id, v);
            return out.set(v[0], v[1], v[2]);
        } finally {
            world.exit();
        }
    }

    public void setLinearVelocity(float x, float y, float z) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "linear velocity");
            B3.bodySetLinearVelocity(id, x, y, z);
        } finally {
            world.exit();
        }
    }

    public Vector3f angularVelocity(Vector3f out) {
        world.enter();
        try {
            checkValid();
            float[] v = world.vector;
            B3.bodyGetAngularVelocity(id, v);
            return out.set(v[0], v[1], v[2]);
        } finally {
            world.exit();
        }
    }

    public void setAngularVelocity(float x, float y, float z) {
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "angular velocity");
            B3.bodySetAngularVelocity(id, x, y, z);
        } finally {
            world.exit();
        }
    }

    /** An impulse at a world point, waking the body. */
    public void applyLinearImpulse(float ix, float iy, float iz, double px, double py, double pz) {
        world.enter();
        try {
            checkValid();
            Checks.finite(ix, iy, iz, "impulse");
            Checks.finite(px, py, pz, "impulse point");
            B3.bodyApplyLinearImpulse(id, ix, iy, iz, px, py, pz, true);
        } finally {
            world.exit();
        }
    }

    public void applyLinearImpulseToCenter(float ix, float iy, float iz) {
        world.enter();
        try {
            checkValid();
            Checks.finite(ix, iy, iz, "impulse");
            B3.bodyApplyLinearImpulseToCenter(id, ix, iy, iz, true);
        } finally {
            world.exit();
        }
    }

    /** A force for the next step, at the centre of mass, waking the body. */
    public void applyForceToCenter(float fx, float fy, float fz) {
        world.enter();
        try {
            checkValid();
            Checks.finite(fx, fy, fz, "force");
            B3.bodyApplyForceToCenter(id, fx, fy, fz, true);
        } finally {
            world.exit();
        }
    }

    // ---- state --------------------------------------------------------------------------------

    public BodyType type() {
        return type;
    }

    public void setType(BodyType type) {
        world.enter();
        try {
            checkValid();
            B3.bodySetType(id, type.ordinal());
            this.type = type;
        } finally {
            world.exit();
        }
    }

    public float mass() {
        world.enter();
        try {
            checkValid();
            return B3.bodyGetMass(id);
        } finally {
            world.exit();
        }
    }

    public boolean isAwake() {
        world.enter();
        try {
            checkValid();
            return B3.bodyIsAwake(id);
        } finally {
            world.exit();
        }
    }

    public void setAwake(boolean awake) {
        world.enter();
        try {
            checkValid();
            B3.bodySetAwake(id, awake);
        } finally {
            world.exit();
        }
    }

    /** A disabled body is out of the simulation: it does not move, collide or show up in queries. */
    public void setEnabled(boolean enabled) {
        world.enter();
        try {
            checkValid();
            B3.bodySetEnabled(id, enabled);
        } finally {
            world.exit();
        }
    }

    public boolean isEnabled() {
        world.enter();
        try {
            checkValid();
            return B3.bodyIsEnabled(id);
        } finally {
            world.exit();
        }
    }

    public void setGravityScale(float scale) {
        world.enter();
        try {
            checkValid();
            Checks.finite(scale, "gravity scale");
            B3.bodySetGravityScale(id, scale);
        } finally {
            world.exit();
        }
    }

    /**
     * Where the body touches things, as of the last step. Per point: {@code points} gets
     * {@code [nx, ny, nz, separation, normalImpulse, ax, ay, az]} — the normal from the other shape into this body,
     * the anchor on this body relative to its centre of mass in world orientation — {@code otherShapes} the shape
     * touched and {@code triangleIndices} its triangle index (on terrain, {@link VoxelGrid#featureCell} gives the
     * cell). Returns how many points there are, which may be more than the arrays take.
     */
    public int contactPoints(float[] points, long[] otherShapes, int[] triangleIndices) {
        world.enter();
        try {
            checkValid();
            return B3.bodyGetContactPoints(id, points, otherShapes, triangleIndices);
        } finally {
            world.exit();
        }
    }

    /**
     * World-space bounds of all its shapes, {@code [minX, minY, minZ, maxX, maxY, maxZ]}, as Box3D keeps them: grown by
     * its speculative distance (0.02) on every side once the body has moved. Float: coarse far out.
     */
    public float[] bounds(float[] out) {
        world.enter();
        try {
            checkValid();
            B3.bodyComputeAABB(id, out);
            return out;
        } finally {
            world.exit();
        }
    }

    private void checkValid() {
        if (!world.isOpen()) {
            throw new IllegalStateException("the body's world is closed");
        }
        if (destroyed) {
            throw new IllegalStateException("body was destroyed");
        }
    }
}
