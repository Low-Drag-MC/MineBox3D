package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3JointType}. ⚠️ The order is Box3D's enum order; the bridge passes the ordinal.
 */
public enum JointType {
    /** Keeps frame B's z axis parallel to frame A's with a spring: keeps a body upright. */
    PARALLEL,
    /** Keeps two points a length apart, rigidly or as a spring, with limits and a motor: ropes, springs. */
    DISTANCE,
    /** Only stops the two bodies colliding with each other. */
    FILTER,
    /** Drives body B towards a pose and velocity relative to body A: a body following an animation. */
    MOTOR,
    /** Body B slides along frame A's x axis without turning: pistons, sliding doors. */
    PRISMATIC,
    /** Body B turns about the frames' shared z axis: hinges, doors, elbows. */
    REVOLUTE,
    /** Body B turns freely about a shared point, within a cone and a twist range: shoulders, hips, necks. */
    SPHERICAL,
    /** Holds the two bodies together, rigidly or as a spring. */
    WELD,
    /** A wheel (body B) on a chassis (body A): spins about frame B's z, suspension along frame A's x, steering. */
    WHEEL,
    /** Box3D's dynamic character mover drive: a velocity in frame B's xz plane, up to a force. */
    MOVER,
    /** Box3D's dynamic character mover spring, holding it off the ground along frame B's y. */
    POGO,
    /** Locks body B's movement along frame A's axes, and its turning to frame A's z axis: a body kept upright. */
    MOTION_LOCK,
    /** MineBox3D's: one UE physics asset constraint, each degree of freedom free, limited or locked, with drives. */
    CONSTRAINT
}
