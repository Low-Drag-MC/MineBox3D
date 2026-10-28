package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.jni.B3;
import org.jetbrains.annotations.Nullable;

/** A shape on a {@link Body}: a handle to Box3D's shape, with the {@link Geometry} it was made from. */
public final class Shape {

    private final Body body;
    final long id;
    private final Geometry geometry;
    @Nullable
    private Object userObject;
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

    @Nullable
    public Object userObject() {
        return userObject;
    }

    public void setUserObject(@Nullable Object userObject) {
        this.userObject = userObject;
    }

    public boolean isValid() {
        return !destroyed && body.isValid();
    }

    public void setFilter(Filter filter, boolean invokeContacts) {
        setFilter(filter.categoryBits, filter.maskBits, filter.groupIndex, invokeContacts);
    }

    public void setFilter(long categoryBits, long maskBits, int groupIndex, boolean invokeContacts) {
        PhysicsWorld world = body.world();
        world.enter();
        try {
            checkValid();
            B3.shapeSetFilter(id, categoryBits, maskBits, groupIndex, invokeContacts);
        } finally {
            world.exit();
        }
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

    /** World-space bounds, {@code [minX, minY, minZ, maxX, maxY, maxZ]}. */
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

    /** Removes the shape from its body. */
    public void destroy(boolean updateBodyMass) {
        body.destroyShape(this, updateBodyMass);
    }

    void markDestroyed() {
        if (!destroyed) {
            destroyed = true;
            if (geometry instanceof Geometry.Mesh mesh) {
                mesh.data().release();
            }
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
