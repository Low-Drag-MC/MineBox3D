package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import org.jetbrains.annotations.Nullable;

/**
 * Box3D's {@code b3ShapeDef}. A new one holds Box3D's own defaults.
 *
 * <p>Re-usable: creating a shape reads it and keeps nothing.
 */
public final class ShapeDef {
    @Nullable
    public String name;

    public long userData;
    /**
     * Per-triangle materials for mesh and height-field shapes, indexed by the triangle's material index (an index past
     * the end reads the last). ⚠️ When set, {@link #baseMaterial} is ignored and {@code materials[0]} is the shape's
     * own material.
     */
    @Nullable
    public SurfaceMaterial[] materials;
    /** The shape's material when {@link #materials} is null. */
    public SurfaceMaterial baseMaterial = new SurfaceMaterial();
    /** kg/m³ */
    public float density;
    /** How strongly {@link PhysicsWorld#explode} pushes this shape; 0 not at all. */
    public float explosionScale;

    public Filter filter = new Filter();
    /**
     * Lets the world's custom filter decide this shape's pairs — {@link BodyPairTable} and {@link Body#setIgnoredTag}
     * work only through it. One shape of a pair is enough.
     */
    public boolean enableCustomFiltering;
    /** Overlap events only, never a collision response. */
    public boolean sensor;

    public boolean enableSensorEvents;
    public boolean enableContactEvents;
    public boolean enableHitEvents;
    /** No effect: MineBox3D registers no pre-solve callback. */
    public boolean enablePreSolveEvents;
    /** Static shapes only: look for contacts on the next step. */
    public boolean invokeContactCreation;
    /** ⚠️ If false, set the body's mass yourself before stepping. */
    public boolean updateBodyMass;

    /**
     * Leave on unless fewer ghost collisions matter more than collision under rotation; off affects only hulls against
     * triangles (Box3D's, experimental).
     */
    public boolean enableSpeculativeContact;

    public ShapeDef() {
        NativeLibrary.load();
        B3.defaultShapeDef(this);
    }

    public ShapeDef density(float density) {
        this.density = density;
        return this;
    }

    public ShapeDef friction(float friction) {
        baseMaterial.friction = friction;
        return this;
    }

    public ShapeDef restitution(float restitution) {
        baseMaterial.restitution = restitution;
        return this;
    }

    public ShapeDef filter(long categoryBits, long maskBits, int groupIndex) {
        filter.categoryBits = categoryBits;
        filter.maskBits = maskBits;
        filter.groupIndex = groupIndex;
        return this;
    }

    public ShapeDef sensor(boolean sensor) {
        this.sensor = sensor;
        return this;
    }

    public ShapeDef enableContactEvents(boolean enableContactEvents) {
        this.enableContactEvents = enableContactEvents;
        return this;
    }

    public ShapeDef enableSensorEvents(boolean enableSensorEvents) {
        this.enableSensorEvents = enableSensorEvents;
        return this;
    }

    public ShapeDef enableHitEvents(boolean enableHitEvents) {
        this.enableHitEvents = enableHitEvents;
        return this;
    }

    public ShapeDef name(@Nullable String name) {
        this.name = name;
        return this;
    }
}
