package com.lowdragmc.minebox3d.physics;

/** How a {@link ConstraintJoint}'s limit gives when reached — UE's {@code FConstraintBaseParams}. Hard by default. */
public final class ConstraintLimitResponse {

    /** Pushed back by a spring once passed, rather than stopped there. */
    public boolean soft;
    /** The soft limit's spring: 1/s² in {@link #accelerationMode}, otherwise N/m or N·m/rad. */
    public float stiffness;
    /** 1/s in {@link #accelerationMode}, otherwise N·s/m or N·m·s/rad. */
    public float damping;

    /** The spring's numbers per unit of mass, as {@link ConstraintDrive#accelerationMode}. */
    public boolean accelerationMode = true;
    /** How much of its speed a hard limit gives back when hit: 0 stops it, 1 bounces it back as fast. */
    public float restitution;

    public ConstraintLimitResponse set(ConstraintLimitResponse other) {
        soft = other.soft;
        stiffness = other.stiffness;
        damping = other.damping;
        accelerationMode = other.accelerationMode;
        restitution = other.restitution;
        return this;
    }

    /** A soft limit with this spring. */
    public ConstraintLimitResponse soft(float stiffness, float damping) {
        soft = true;
        this.stiffness = stiffness;
        this.damping = damping;
        return this;
    }

    void check(String what) {
        Checks.notNegative(stiffness, what + " stiffness");
        Checks.notNegative(damping, what + " damping");
        Checks.notNegative(restitution, what + " restitution");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ConstraintLimitResponse r
                && soft == r.soft
                && Float.compare(stiffness, r.stiffness) == 0
                && Float.compare(damping, r.damping) == 0
                && accelerationMode == r.accelerationMode
                && Float.compare(restitution, r.restitution) == 0;
    }

    @Override
    public int hashCode() {
        int h = Boolean.hashCode(soft);
        h = 31 * h + Float.hashCode(stiffness);
        h = 31 * h + Float.hashCode(damping);
        h = 31 * h + Boolean.hashCode(accelerationMode);
        return 31 * h + Float.hashCode(restitution);
    }

    @Override
    public String toString() {
        return "ConstraintLimitResponse[soft=" + soft + ", stiffness=" + stiffness + ", damping=" + damping
                + ", acceleration=" + accelerationMode + ", restitution=" + restitution + "]";
    }
}
