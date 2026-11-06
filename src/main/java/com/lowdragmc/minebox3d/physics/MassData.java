package com.lowdragmc.minebox3d.physics;

import org.joml.Matrix3f;

/**
 * Box3D's {@code b3MassData}: a body's mass, centre of mass and rotational inertia, in the body's own space. Read with
 * {@link Body#massData}, written with {@link Body#setMassData} — a ragdoll limb weighed by the model rather than by its
 * shapes' density.
 */
public final class MassData {
    /** kg */
    public float mass;
    /** The centre of mass, body space. */
    public float centerX, centerY, centerZ;
    /** About the centre of mass, body space, kg·m². */
    public final Matrix3f inertia = new Matrix3f();

    public MassData set(float mass, float centerX, float centerY, float centerZ) {
        this.mass = mass;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        return this;
    }

    /** The same inertia about every axis: a sphere's, kg·m². */
    public MassData inertiaOf(float moment) {
        inertia.identity().scale(moment);
        return this;
    }

    /** {@code [mass, cx, cy, cz, inertia column by column]}, the bridge's order. */
    void read(float[] v) {
        mass = v[0];
        centerX = v[1];
        centerY = v[2];
        centerZ = v[3];
        inertia.set(v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12]);
    }

    void write(float[] v) {
        v[0] = mass;
        v[1] = centerX;
        v[2] = centerY;
        v[3] = centerZ;
        v[4] = inertia.m00;
        v[5] = inertia.m01;
        v[6] = inertia.m02;
        v[7] = inertia.m10;
        v[8] = inertia.m11;
        v[9] = inertia.m12;
        v[10] = inertia.m20;
        v[11] = inertia.m21;
        v[12] = inertia.m22;
    }
}
