package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionfc;
import org.joml.Vector3d;

/** A shape on a {@link Body}: a handle to Box3D's shape, with the {@link Geometry} it is made of now. */
public final class Shape {

    private final Body body;
    final long id;
    private Geometry geometry;
    private final Attachments attachments = new Attachments();
    private boolean destroyed;

    Shape(Body body, long id, Geometry geometry) {
        this.body = body;
        this.id = id;
        this.geometry = geometry;
    }

    public Body body() {
        return body;
    }

    public long id() {
        return id;
    }

    public Geometry geometry() {
        return geometry;
    }

    /** What is attached under {@code type}, or {@code null}. Any number of mods can attach their own, one per type. */
    @Nullable
    public <T> T attachment(Class<T> type) {
        return attachments.get(type);
    }

    /** Attaches {@code value} under {@code type}, replacing what was there; {@code null} removes it. */
    public <T> void attach(Class<T> type, @Nullable T value) {
        attachments.put(type, value);
    }

    /**
     * Says that what is copied of this shape elsewhere changed while the shape did not — an attachment the network sync
     * sends with it: the body's {@link Body#shapesRevision} moves on, as for a new filter.
     */
    public void markChanged() {
        body.shapesChanged();
    }

    public boolean isValid() {
        return !destroyed && body.isValid();
    }

    // ---- geometry -----------------------------------------------------------------------------

    /**
     * <b>Changes what the shape is made of, in place</b>: same shape, id, filter and material, its contacts rebuilt and
     * its body woken — an entity's proxy growing, a limb swapping a sphere for a capsule. Not into a height field.
     *
     * @param updateBodyMass recompute the body's mass from its shapes (Box3D does not on its own)
     */
    public void setGeometry(Geometry next, boolean updateBodyMass) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            Checks.geometry(next);
            next = Geometry.asBuilt(next);
            if (next instanceof Geometry.Mesh mesh) {
                mesh.data().retain();
            }
            try {
                switch (next) {
                    case Geometry.Sphere s -> B3.shapeSetSphere(id, s.cx(), s.cy(), s.cz(), s.radius());
                    case Geometry.Capsule c ->
                        B3.shapeSetCapsule(id, c.ax(), c.ay(), c.az(), c.bx(), c.by(), c.bz(), c.radius());
                    case Geometry.Box b ->
                        B3.shapeSetBox(
                                id, b.hx(), b.hy(), b.hz(), b.cx(), b.cy(), b.cz(), b.qx(), b.qy(), b.qz(), b.qw());
                    case Geometry.Hull h -> B3.shapeSetHull(id, h.points(), h.vertexCount());
                    case Geometry.Mesh m -> B3.shapeSetMesh(id, m.data().pointer(), m.sx(), m.sy(), m.sz());
                    case Geometry.HeightField f ->
                        throw new IllegalArgumentException(
                                "Box3D cannot turn a shape into a height field; make a new shape");
                }
            } catch (RuntimeException e) {
                if (next instanceof Geometry.Mesh mesh) {
                    mesh.data().release();
                }
                throw e;
            }
            releaseData(geometry);
            geometry = next;
            if (updateBodyMass) {
                B3.bodyApplyMassFromShapes(body.id);
            }
            body.shapesChanged();
            body.forgetBounds();
            body.forgetMotion();
        } finally {
            world.exit();
        }
    }

    // ---- material -----------------------------------------------------------------------------

    /** kg/m³ */
    public float density() {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            return B3.shapeGetDensity(id);
        } finally {
            world.exit();
        }
    }

    /** @param updateBodyMass recompute the body's mass from its shapes now */
    public void setDensity(float density, boolean updateBodyMass) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            Checks.notNegative(density, "density");
            B3.shapeSetDensity(id, density, updateBodyMass);
            if (updateBodyMass) {
                body.forgetMotion();
            }
        } finally {
            world.exit();
        }
    }

    public float friction() {
        return surfaceMaterial(body.world().scratchMaterial()).friction;
    }

    public void setFriction(float friction) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            Checks.notNegative(friction, "friction");
            B3.shapeSetFriction(id, friction);
        } finally {
            world.exit();
        }
    }

    public float restitution() {
        return surfaceMaterial(body.world().scratchMaterial()).restitution;
    }

    public void setRestitution(float restitution) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            Checks.notNegative(restitution, "restitution");
            B3.shapeSetRestitution(id, restitution);
        } finally {
            world.exit();
        }
    }

    /** The shape's material ({@code materials[0]} for one made with {@link ShapeDef#materials}), into {@code out}. */
    public SurfaceMaterial surfaceMaterial(SurfaceMaterial out) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            B3.shapeGetSurfaceMaterial(id, out);
            return out;
        } finally {
            world.exit();
        }
    }

    public void setSurfaceMaterial(SurfaceMaterial material) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            Checks.material(material, "surface material");
            B3.shapeSetSurfaceMaterial(id, material);
        } finally {
            world.exit();
        }
    }

    /**
     * How many materials the shape has: as many as the {@link ShapeDef#materials} it was made with (a mesh's or height
     * field's per-triangle ones), else one, its own.
     */
    public int meshMaterialCount() {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            return B3.shapeGetMeshMaterialCount(id);
        } finally {
            world.exit();
        }
    }

    public SurfaceMaterial meshMaterial(int index, SurfaceMaterial out) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            checkMeshMaterial(index);
            B3.shapeGetMeshMaterial(id, index, out);
            return out;
        } finally {
            world.exit();
        }
    }

    public void setMeshMaterial(int index, SurfaceMaterial material) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            checkMeshMaterial(index);
            Checks.material(material, "mesh material");
            B3.shapeSetMeshMaterial(id, material, index);
        } finally {
            world.exit();
        }
    }

    private void checkMeshMaterial(int index) {
        int count = B3.shapeGetMeshMaterialCount(id);
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("mesh material " + index + " of " + count);
        }
    }

    // ---- filter and events --------------------------------------------------------------------

    public Filter filter(Filter out) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            B3.shapeGetFilter(id, out);
            return out;
        } finally {
            world.exit();
        }
    }

    /**
     * Almost as costly as making the shape again.
     *
     * @param invokeContacts find all the shape's contacts again at the next step (costly)
     */
    public void setFilter(Filter filter, boolean invokeContacts) {
        setFilter(filter.categoryBits, filter.maskBits, filter.groupIndex, invokeContacts);
    }

    public void setFilter(long categoryBits, long maskBits, int groupIndex, boolean invokeContacts) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            B3.shapeSetFilter(id, categoryBits, maskBits, groupIndex, invokeContacts);
            body.shapesChanged();
        } finally {
            world.exit();
        }
    }

    // the bridge's numbers (mb3_shape.c)
    private static final int SENSOR_EVENTS = 0, CONTACT_EVENTS = 1, HIT_EVENTS = 2, SENSOR = 3;

    private boolean getFlag(int flag) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            return B3.shapeGetFlag(id, flag);
        } finally {
            world.exit();
        }
    }

    private void setFlag(int flag, boolean value) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            B3.shapeSetFlag(id, flag, value);
        } finally {
            world.exit();
        }
    }

    /** Fixed when the shape is made ({@link ShapeDef#sensor}). */
    public boolean isSensor() {
        return getFlag(SENSOR);
    }

    public boolean areSensorEventsEnabled() {
        return getFlag(SENSOR_EVENTS);
    }

    /** On a sensor: whether it reports; on any other shape: whether sensors notice it. */
    public void enableSensorEvents(boolean enable) {
        setFlag(SENSOR_EVENTS, enable);
    }

    public boolean areContactEventsEnabled() {
        return getFlag(CONTACT_EVENTS);
    }

    public void enableContactEvents(boolean enable) {
        setFlag(CONTACT_EVENTS, enable);
    }

    public boolean areHitEventsEnabled() {
        return getFlag(HIT_EVENTS);
    }

    public void enableHitEvents(boolean enable) {
        setFlag(HIT_EVENTS, enable);
    }

    /** The shapes inside this sensor as of the last step, into {@code out}; returns how many. */
    public int sensorOverlaps(Overlaps out) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            out.world = world;
            int count = B3.shapeGetSensorOverlaps(id, out.ids);
            if (count > out.ids.length) {
                out.ensureCapacity(count);
                count = B3.shapeGetSensorOverlaps(id, out.ids);
            }
            out.count = Math.min(count, out.ids.length);
            return out.count;
        } finally {
            world.exit();
        }
    }

    // ---- where it is --------------------------------------------------------------------------

    /** World-space bounds, {@code [minX, minY, minZ, maxX, maxY, maxZ]}, grown as {@link Body#bounds} says. */
    public float[] bounds(float[] out) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            B3.shapeGetAABB(id, out);
            return out;
        } finally {
            world.exit();
        }
    }

    /**
     * The closest point of this sphere, capsule, box or hull to a world point, into {@code out}; returns the distance,
     * 0 inside, {@link Float#POSITIVE_INFINITY} for a mesh or height field. Exact far from the origin.
     */
    public float closestPoint(double x, double y, double z, Vector3d out) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "point");
            double[] p = world.point;
            float distance = B3.shapeGetClosestPoint(id, x, y, z, p);
            if (distance == Float.MAX_VALUE) {
                out.set(x, y, z);
                return Float.POSITIVE_INFINITY;
            }
            out.set(p[0], p[1], p[2]);
            return distance;
        } finally {
            world.exit();
        }
    }

    /**
     * {@code geometry} at {@code (x, y, z)} turned by {@code rotation}, moved by {@code translation}, against this
     * shape only, into {@code out}; false for no hit. A sensor is hit like any other shape. The moving geometry must
     * be convex.
     *
     * <p>Without {@code canEncroach}, touching at the start hits at fraction 0. With it — the way Box3D moves a box
     * among bodies — one touching this shape (within a linear slop or two, 5–10 mm) without overlapping it hits only if
     * it moves in, stopping half a slop short, and misses moving along or away; from further off it stops about a slop
     * short; one overlapping it still hits at fraction 0, with no normal. ⚠️ Refused for a sphere or capsule against a
     * mesh or height field: there Box3D lets a rounded shape creep in a little at every cast (our fork stops that
     * against a convex shape only).
     */
    public boolean castShape(
            Geometry geometry,
            double x,
            double y,
            double z,
            Quaternionfc rotation,
            float tx,
            float ty,
            float tz,
            boolean canEncroach,
            CastResult out) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            Checks.finite(x, y, z, "cast position");
            Checks.finite(tx, ty, tz, "cast translation");
            if (canEncroach && geometry.isRounded() && (this.geometry == null || !this.geometry.isConvex())) {
                throw new IllegalArgumentException("a " + geometry.getClass().getSimpleName()
                        + " cannot encroach on a mesh, height field or terrain: sweep a box or hull");
            }
            int count = world.queryProxy(geometry, rotation);
            CastHits hit = out.hit;
            hit.clear(world);
            if (B3.shapeCastShape(
                    id,
                    x,
                    y,
                    z,
                    world.queryPoints,
                    count,
                    world.queryRadius,
                    tx,
                    ty,
                    tz,
                    canEncroach,
                    hit.doubles,
                    hit.longs)) {
                hit.count = 1;
            }
            return hit.count > 0;
        } finally {
            world.exit();
        }
    }

    /** @param updateBodyMass recompute the body's mass from the shapes left */
    public void destroy(boolean updateBodyMass) {
        body.destroyShape(this, updateBodyMass);
    }

    void markDestroyed() {
        if (!destroyed) {
            destroyed = true;
            PhysicsWorld world = body.world();
            // let go in a faulted world, Box3D's shape is still there and still reads its mesh: freed with the world
            if (world.isFaulted() && world.isOpen()) {
                world.releaseOnClose(geometry);
            } else {
                releaseData(geometry);
            }
        }
    }

    static void releaseData(Geometry geometry) {
        if (geometry instanceof Geometry.Mesh mesh) {
            mesh.data().release();
        } else if (geometry instanceof Geometry.HeightField field) {
            field.data().release();
        }
    }

    private void checkValid() {
        if (!body.world().isOpen()) {
            throw new IllegalStateException("the shape's world is closed");
        }
        if (!isValid()) {
            throw new IllegalStateException("shape was destroyed");
        }
    }
}
