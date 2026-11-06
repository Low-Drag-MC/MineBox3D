package com.lowdragmc.minebox3d.physics;

/** How a {@link ConstraintJoint}'s angular drive turns frame B toward its target. ⚠️ Box3D's enum order. */
public enum AngularDriveMode {
    /**
     * {@link ConstraintJointSettings#slerpDrive} along the shortest turn, about each of frame B's axes alike. Drives
     * nothing while any angle is locked (UE).
     */
    SLERP,
    /** {@link ConstraintJointSettings#twistDrive} about frame B's x, {@link ConstraintJointSettings#swingDrive} about its z and y. */
    TWIST_SWING
}
