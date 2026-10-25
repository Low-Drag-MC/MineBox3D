package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.jni.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;

/**
 * Box3D's {@code b3WorldDef}. A new one holds Box3D's own defaults.
 *
 * <p>Units are Box3D's, which MineBox3D keeps: one block is one metre, seconds, kilograms.
 */
public final class WorldDef {
    /** Box3D has no up vector of its own. */
    public float gravityX, gravityY, gravityZ;
    /** Collisions faster than this (m/s) bounce. */
    public float restitutionThreshold;
    public int restitutionIterations;
    public boolean enableRestitutionPropagation;
    /** Collisions faster than this (m/s) can raise hit events. */
    public float hitEventThreshold;
    /** Contact stiffness, Hz. */
    public float contactHertz;
    public float contactDampingRatio;
    /** Cap on how fast overlap is pushed apart, m/s. */
    public float contactSpeed;
    /** m/s */
    public float maximumLinearSpeed;
    /** 0–1; a sphere is 0, a 1×1×2 box 0.5. Below it, gyroscopic torque is skipped. */
    public float isotropicInertiaTolerance;
    public boolean enableSleep;
    public boolean enableContinuous;
    /** Above 1, Box3D starts its own worker threads for this world's step. */
    public int workerCount;
    /** Optional initial capacities, to avoid growing at run time. */
    public int staticShapeCapacity, dynamicShapeCapacity, staticBodyCapacity, dynamicBodyCapacity, contactCapacity;

    public WorldDef() {
        NativeLibrary.load();
        B3.defaultWorldDef(this);
    }

    public WorldDef gravity(float x, float y, float z) {
        gravityX = x;
        gravityY = y;
        gravityZ = z;
        return this;
    }

    public WorldDef enableSleep(boolean enableSleep) {
        this.enableSleep = enableSleep;
        return this;
    }

    public WorldDef enableContinuous(boolean enableContinuous) {
        this.enableContinuous = enableContinuous;
        return this;
    }

    public WorldDef workerCount(int workerCount) {
        this.workerCount = workerCount;
        return this;
    }
}
