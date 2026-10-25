package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3BodyType}. ⚠️ The order is Box3D's enum order; the bridge passes the ordinal.
 */
public enum BodyType {
    /** Zero mass, zero velocity, moved only by hand. */
    STATIC,
    /** Zero mass, velocity set by the user, moved by the solver. */
    KINEMATIC,
    /** Positive mass, velocity from forces, moved by the solver. */
    DYNAMIC
}
