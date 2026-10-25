package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.jni.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;

/** Box3D's {@code b3SurfaceMaterial}: how a surface behaves in contact. */
public final class SurfaceMaterial {
    /** Coulomb friction, usually 0–1. */
    public float friction;
    /** Restitution (bounce), usually 0–1. */
    public float restitution;
    /** Rolling resistance, 0–1. Spheres and capsules only. */
    public float rollingResistance;
    /** Conveyor-belt velocity, local to the shape, projected onto the contact surface. */
    public float tangentVelocityX, tangentVelocityY, tangentVelocityZ;
    /** Passed through to query results and material mixing; Box3D does not read it. */
    public long userMaterialId;
    /** Debug draw colour, 0 = none. The low 24 bits are RGB. */
    public int customColor;

    /** Box3D's defaults. */
    public SurfaceMaterial() {
        NativeLibrary.load();
        B3.defaultSurfaceMaterial(this);
    }

    public SurfaceMaterial friction(float friction) {
        this.friction = friction;
        return this;
    }

    public SurfaceMaterial restitution(float restitution) {
        this.restitution = restitution;
        return this;
    }

    public SurfaceMaterial rollingResistance(float rollingResistance) {
        this.rollingResistance = rollingResistance;
        return this;
    }

    public SurfaceMaterial copy() {
        SurfaceMaterial copy = new SurfaceMaterial();
        copy.set(this);
        return copy;
    }

    public void set(SurfaceMaterial other) {
        friction = other.friction;
        restitution = other.restitution;
        rollingResistance = other.rollingResistance;
        tangentVelocityX = other.tangentVelocityX;
        tangentVelocityY = other.tangentVelocityY;
        tangentVelocityZ = other.tangentVelocityZ;
        userMaterialId = other.userMaterialId;
        customColor = other.customColor;
    }
}
