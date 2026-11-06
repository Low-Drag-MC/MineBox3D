package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
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
    /**
     * Threads stepping this world, the stepping thread included; 0 and 1 mean the stepping thread alone. Above 1,
     * Box3D starts {@code workerCount - 1} threads of its own <b>for this world</b>, which spin while they wait for
     * work: only worth it for a large world, and never for many small ones. Box3D's internal checks failing on those
     * threads are only logged (MineBox3D's log), not thrown.
     */
    public int workerCount;
    /** Optional initial capacities, to avoid growing at run time. */
    public int staticShapeCapacity, dynamicShapeCapacity, staticBodyCapacity, dynamicBodyCapacity, contactCapacity;
    /** How the world is shared between threads; MineBox3D's own, not Box3D's. */
    public ThreadMode threadMode = ThreadMode.OWNER_THREAD;

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

    public WorldDef threadMode(ThreadMode threadMode) {
        this.threadMode = threadMode;
        return this;
    }
}
