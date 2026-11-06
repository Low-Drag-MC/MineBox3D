package com.lowdragmc.minebox3d.physics;

/** The shape a {@link ConstraintJoint}'s two swing limits make when both are limited. ⚠️ Box3D's enum order. */
public enum SwingLimitShape {
    /** An elliptical cone: swinging diagonally reaches no further than the ellipse through both limits. */
    ELLIPSE,
    /** Each swing limited on its own: swinging diagonally reaches both limits at once (UE's default solver). */
    PYRAMID
}
