package com.lowdragmc.minebox3d.physics;

/** A drive of a {@link ConstraintJoint} — UE's {@code FConstraintDrive}: toward a target position, a target velocity or both. */
public final class ConstraintDrive {

    /** Pull toward the target position, by {@link #stiffness}. */
    public boolean positionDrive;
    /** Pull toward the target velocity, by {@link #damping}. */
    public boolean velocityDrive;
    /** 1/s² in {@link #accelerationMode}, otherwise N/m or N·m/rad. */
    public float stiffness;
    /** 1/s in {@link #accelerationMode}, otherwise N·s/m or N·m·s/rad. */
    public float damping;
    /** The numbers per unit of mass, so any body follows alike; otherwise a physical spring heavier bodies follow slower. */
    public boolean accelerationMode = true;
    /**
     * The most force (N) or torque (N·m) along each axis; 0 for no limit (UE, unlike Box3D's motors). In
     * {@link #accelerationMode} per unit of the axis's mass, like the spring (UE's default solver).
     */
    public float maxForce;

    public ConstraintDrive set(ConstraintDrive other) {
        positionDrive = other.positionDrive;
        velocityDrive = other.velocityDrive;
        stiffness = other.stiffness;
        damping = other.damping;
        accelerationMode = other.accelerationMode;
        maxForce = other.maxForce;
        return this;
    }

    /** A drive toward both targets. */
    public ConstraintDrive drive(float stiffness, float damping, float maxForce) {
        positionDrive = stiffness > 0;
        velocityDrive = damping > 0;
        this.stiffness = stiffness;
        this.damping = damping;
        this.maxForce = maxForce;
        return this;
    }

    void check(String what) {
        Checks.notNegative(stiffness, what + " stiffness");
        Checks.notNegative(damping, what + " damping");
        Checks.notNegative(maxForce, what + " max force");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ConstraintDrive d && positionDrive == d.positionDrive && velocityDrive == d.velocityDrive
                && Float.compare(stiffness, d.stiffness) == 0 && Float.compare(damping, d.damping) == 0
                && accelerationMode == d.accelerationMode && Float.compare(maxForce, d.maxForce) == 0;
    }

    @Override
    public int hashCode() {
        int h = Boolean.hashCode(positionDrive);
        h = 31 * h + Boolean.hashCode(velocityDrive);
        h = 31 * h + Float.hashCode(stiffness);
        h = 31 * h + Float.hashCode(damping);
        h = 31 * h + Boolean.hashCode(accelerationMode);
        return 31 * h + Float.hashCode(maxForce);
    }

    @Override
    public String toString() {
        return "ConstraintDrive[position=" + positionDrive + ", velocity=" + velocityDrive + ", stiffness=" + stiffness
                + ", damping=" + damping + ", acceleration=" + accelerationMode + ", maxForce=" + maxForce + "]";
    }
}
