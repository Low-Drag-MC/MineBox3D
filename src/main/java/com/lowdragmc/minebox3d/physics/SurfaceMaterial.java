package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import org.jetbrains.annotations.Nullable;

/** Box3D's {@code b3SurfaceMaterial}: how a surface behaves in contact. */
public final class SurfaceMaterial {

    /**
     * How two surfaces' friction or restitution make one — UE's {@code EFrictionCombineMode}. Of two surfaces' modes
     * the later in this order wins (UE).
     */
    public enum Combine {
        AVERAGE,
        MIN,
        MULTIPLY,
        MAX
    }

    /** The bits of {@link #userMaterialId} that are its user's: MineBox3D keeps the top byte for the combine modes. */
    public static final long USER_MATERIAL_BITS = 0x00FFFFFFFFFFFFFFL;

    /** Coulomb friction, usually 0–1. */
    public float friction;
    /** Restitution (bounce), usually 0–1. */
    public float restitution;
    /** Rolling resistance, 0–1. Spheres and capsules only. */
    public float rollingResistance;
    /** Conveyor-belt velocity, local to the shape, projected onto the contact surface. */
    public float tangentVelocityX, tangentVelocityY, tangentVelocityZ;
    /**
     * Passed through to query results and events; Box3D does not read it. Its top byte is MineBox3D's
     * ({@link #USER_MATERIAL_BITS}).
     */
    public long userMaterialId;
    /** Debug draw colour, 0 = none. The low 24 bits are RGB. */
    public int customColor;
    /**
     * How this surface's friction meets another's; null for Box3D's own, √(a·b) — which is what two surfaces without a
     * mode get. With one, UE's: the later mode of the two, a surface without one counting as {@link Combine#AVERAGE}.
     */
    @Nullable
    public Combine frictionCombine;
    /** The same for restitution; Box3D's own is the larger. */
    @Nullable
    public Combine restitutionCombine;

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

    public SurfaceMaterial combine(@Nullable Combine friction, @Nullable Combine restitution) {
        this.frictionCombine = friction;
        this.restitutionCombine = restitution;
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
        frictionCombine = other.frictionCombine;
        restitutionCombine = other.restitutionCombine;
    }
}
