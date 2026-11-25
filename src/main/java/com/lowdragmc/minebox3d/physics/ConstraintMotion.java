package com.lowdragmc.minebox3d.physics;

/**
 * How one degree of freedom of a {@link ConstraintJoint} moves — UE's linear / angular constraint motion. ⚠️ Box3D's
 * enum order; the bridge passes the ordinal.
 */
public enum ConstraintMotion {
    /** Moves freely. */
    FREE,
    /** Moves within its limit. */
    LIMITED,
    /** Held where the frames put it. */
    LOCKED
}
