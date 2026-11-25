package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import java.util.Objects;
import org.joml.Quaternionfc;

/**
 * Everything a {@link ConstraintJoint} does besides its bodies and frames — UE's {@code FConstraintProfileProperties},
 * what a profile swap changes ({@link ConstraintJoint#setSettings}). A new one holds Box3D's defaults: a ball and
 * socket, every angle free with π/4 limits ready to turn on, no drives, mass conditioning on.
 *
 * <p>Frame A is the parent's and frame B the child's. Frame B's x axis is the twist axis; swing 1 turns about frame
 * A's z axis, swing 2 about its y axis — UE's axes, so a physics asset's frames go in as they are. Radians and metres;
 * linear motion is frame B's anchor along frame A's axes. Box3D keeps the twist within ±0.99π and each swing within
 * 0.97π.
 */
public final class ConstraintJointSettings {

    public ConstraintMotion linearMotionX = ConstraintMotion.LOCKED;
    public ConstraintMotion linearMotionY = ConstraintMotion.LOCKED;
    public ConstraintMotion linearMotionZ = ConstraintMotion.LOCKED;
    /** How far the anchor may move along the limited axes together: within a sphere, a circle or a band. */
    public float linearLimit;

    public final ConstraintLimitResponse linearLimitResponse = new ConstraintLimitResponse();

    /** About frame B's x axis. */
    public ConstraintMotion twistMotion = ConstraintMotion.FREE;
    /** About frame A's z axis. */
    public ConstraintMotion swing1Motion = ConstraintMotion.FREE;
    /** About frame A's y axis. */
    public ConstraintMotion swing2Motion = ConstraintMotion.FREE;
    /** The twist range, lower to upper (UE's is ±one value). */
    public float lowerTwistLimit, upperTwistLimit;
    /** Each swing's half angle either way. */
    public float swing1Limit, swing2Limit;

    public SwingLimitShape swingLimitShape = SwingLimitShape.ELLIPSE;
    public final ConstraintLimitResponse twistLimitResponse = new ConstraintLimitResponse();
    public final ConstraintLimitResponse swingLimitResponse = new ConstraintLimitResponse();

    /** Along frame A's axes; a drive on a locked axis does nothing. */
    public final ConstraintDrive linearDriveX = new ConstraintDrive();

    public final ConstraintDrive linearDriveY = new ConstraintDrive();
    public final ConstraintDrive linearDriveZ = new ConstraintDrive();
    /** Where the linear drives pull frame B's anchor, in frame A. */
    public float linearPositionTargetX, linearPositionTargetY, linearPositionTargetZ;
    /** How fast they move it, in frame A. */
    public float linearVelocityTargetX, linearVelocityTargetY, linearVelocityTargetZ;

    public AngularDriveMode angularDriveMode = AngularDriveMode.SLERP;
    public final ConstraintDrive slerpDrive = new ConstraintDrive();
    public final ConstraintDrive twistDrive = new ConstraintDrive();
    /** Both swings. */
    public final ConstraintDrive swingDrive = new ConstraintDrive();
    /** Frame B relative to frame A that the angular drive pulls toward. */
    public float angularOrientationTargetX,
            angularOrientationTargetY,
            angularOrientationTargetZ,
            angularOrientationTargetW = 1;
    /** rad/s. Slerp: a vector in frame A; twist and swing: x the twist's rate, y swing 2's, z swing 1's. */
    public float angularVelocityTargetX, angularVelocityTargetY, angularVelocityTargetZ;

    /**
     * Scales the parent's inverse mass for this joint alone, 0 to 1: 1 as normal, 0 for the parent unmoved by the
     * child (UE's parent dominates).
     */
    public float parentInverseMassScale = 1;
    /**
     * UE's mass conditioning: for this joint each body's inertia evened out to within 1:5 between its axes, and the
     * parent counted no lighter than a fifth of the child. Steadier chains of very different bodies.
     */
    public boolean massConditioning = true;

    public ConstraintJointSettings() {
        NativeLibrary.load();
        B3.defaultConstraintJointSettings(this);
    }

    public ConstraintJointSettings set(ConstraintJointSettings other) {
        linearMotionX = other.linearMotionX;
        linearMotionY = other.linearMotionY;
        linearMotionZ = other.linearMotionZ;
        linearLimit = other.linearLimit;
        linearLimitResponse.set(other.linearLimitResponse);
        twistMotion = other.twistMotion;
        swing1Motion = other.swing1Motion;
        swing2Motion = other.swing2Motion;
        lowerTwistLimit = other.lowerTwistLimit;
        upperTwistLimit = other.upperTwistLimit;
        swing1Limit = other.swing1Limit;
        swing2Limit = other.swing2Limit;
        swingLimitShape = other.swingLimitShape;
        twistLimitResponse.set(other.twistLimitResponse);
        swingLimitResponse.set(other.swingLimitResponse);
        linearDriveX.set(other.linearDriveX);
        linearDriveY.set(other.linearDriveY);
        linearDriveZ.set(other.linearDriveZ);
        linearPositionTargetX = other.linearPositionTargetX;
        linearPositionTargetY = other.linearPositionTargetY;
        linearPositionTargetZ = other.linearPositionTargetZ;
        linearVelocityTargetX = other.linearVelocityTargetX;
        linearVelocityTargetY = other.linearVelocityTargetY;
        linearVelocityTargetZ = other.linearVelocityTargetZ;
        angularDriveMode = other.angularDriveMode;
        slerpDrive.set(other.slerpDrive);
        twistDrive.set(other.twistDrive);
        swingDrive.set(other.swingDrive);
        angularOrientationTargetX = other.angularOrientationTargetX;
        angularOrientationTargetY = other.angularOrientationTargetY;
        angularOrientationTargetZ = other.angularOrientationTargetZ;
        angularOrientationTargetW = other.angularOrientationTargetW;
        angularVelocityTargetX = other.angularVelocityTargetX;
        angularVelocityTargetY = other.angularVelocityTargetY;
        angularVelocityTargetZ = other.angularVelocityTargetZ;
        parentInverseMassScale = other.parentInverseMassScale;
        massConditioning = other.massConditioning;
        return this;
    }

    /** All three linear axes. */
    public ConstraintJointSettings linearMotion(ConstraintMotion motion) {
        linearMotionX = motion;
        linearMotionY = motion;
        linearMotionZ = motion;
        return this;
    }

    /** All three angles. */
    public ConstraintJointSettings angularMotion(ConstraintMotion motion) {
        twistMotion = motion;
        swing1Motion = motion;
        swing2Motion = motion;
        return this;
    }

    /** UE's symmetric twist limit, ±{@code angle}. */
    public ConstraintJointSettings twistLimit(float angle) {
        return twistLimits(-angle, angle);
    }

    public ConstraintJointSettings twistLimits(float lower, float upper) {
        lowerTwistLimit = lower;
        upperTwistLimit = upper;
        return this;
    }

    public ConstraintJointSettings swingLimits(float swing1, float swing2) {
        swing1Limit = swing1;
        swing2Limit = swing2;
        return this;
    }

    public ConstraintJointSettings linearPositionTarget(float x, float y, float z) {
        linearPositionTargetX = x;
        linearPositionTargetY = y;
        linearPositionTargetZ = z;
        return this;
    }

    public ConstraintJointSettings linearVelocityTarget(float x, float y, float z) {
        linearVelocityTargetX = x;
        linearVelocityTargetY = y;
        linearVelocityTargetZ = z;
        return this;
    }

    public ConstraintJointSettings angularOrientationTarget(Quaternionfc target) {
        angularOrientationTargetX = target.x();
        angularOrientationTargetY = target.y();
        angularOrientationTargetZ = target.z();
        angularOrientationTargetW = target.w();
        return this;
    }

    public ConstraintJointSettings angularVelocityTarget(float x, float y, float z) {
        angularVelocityTargetX = x;
        angularVelocityTargetY = y;
        angularVelocityTargetZ = z;
        return this;
    }

    void check() {
        Objects.requireNonNull(linearMotionX, "linear motion x");
        Objects.requireNonNull(linearMotionY, "linear motion y");
        Objects.requireNonNull(linearMotionZ, "linear motion z");
        Objects.requireNonNull(twistMotion, "twist motion");
        Objects.requireNonNull(swing1Motion, "swing 1 motion");
        Objects.requireNonNull(swing2Motion, "swing 2 motion");
        Objects.requireNonNull(swingLimitShape, "swing limit shape");
        Objects.requireNonNull(angularDriveMode, "angular drive mode");
        Checks.notNegative(linearLimit, "linear limit");
        linearLimitResponse.check("linear limit");
        Checks.finite(lowerTwistLimit, "lower twist limit");
        Checks.finite(upperTwistLimit, "upper twist limit");
        Checks.notNegative(swing1Limit, "swing 1 limit");
        Checks.notNegative(swing2Limit, "swing 2 limit");
        twistLimitResponse.check("twist limit");
        swingLimitResponse.check("swing limit");
        linearDriveX.check("linear drive x");
        linearDriveY.check("linear drive y");
        linearDriveZ.check("linear drive z");
        Checks.finite(linearPositionTargetX, linearPositionTargetY, linearPositionTargetZ, "linear position target");
        Checks.finite(linearVelocityTargetX, linearVelocityTargetY, linearVelocityTargetZ, "linear velocity target");
        slerpDrive.check("slerp drive");
        twistDrive.check("twist drive");
        swingDrive.check("swing drive");
        Checks.rotation(
                angularOrientationTargetX,
                angularOrientationTargetY,
                angularOrientationTargetZ,
                angularOrientationTargetW,
                "angular orientation target");
        Checks.finite(
                angularVelocityTargetX, angularVelocityTargetY, angularVelocityTargetZ, "angular velocity target");
        if (!(parentInverseMassScale >= 0 && parentInverseMassScale <= 1)) {
            throw new IllegalArgumentException("parent inverse mass scale must be 0 to 1: " + parentInverseMassScale);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ConstraintJointSettings s
                && linearMotionX == s.linearMotionX
                && linearMotionY == s.linearMotionY
                && linearMotionZ == s.linearMotionZ
                && Float.compare(linearLimit, s.linearLimit) == 0
                && linearLimitResponse.equals(s.linearLimitResponse)
                && twistMotion == s.twistMotion
                && swing1Motion == s.swing1Motion
                && swing2Motion == s.swing2Motion
                && Float.compare(lowerTwistLimit, s.lowerTwistLimit) == 0
                && Float.compare(upperTwistLimit, s.upperTwistLimit) == 0
                && Float.compare(swing1Limit, s.swing1Limit) == 0
                && Float.compare(swing2Limit, s.swing2Limit) == 0
                && swingLimitShape == s.swingLimitShape
                && twistLimitResponse.equals(s.twistLimitResponse)
                && swingLimitResponse.equals(s.swingLimitResponse)
                && linearDriveX.equals(s.linearDriveX)
                && linearDriveY.equals(s.linearDriveY)
                && linearDriveZ.equals(s.linearDriveZ)
                && Float.compare(linearPositionTargetX, s.linearPositionTargetX) == 0
                && Float.compare(linearPositionTargetY, s.linearPositionTargetY) == 0
                && Float.compare(linearPositionTargetZ, s.linearPositionTargetZ) == 0
                && Float.compare(linearVelocityTargetX, s.linearVelocityTargetX) == 0
                && Float.compare(linearVelocityTargetY, s.linearVelocityTargetY) == 0
                && Float.compare(linearVelocityTargetZ, s.linearVelocityTargetZ) == 0
                && angularDriveMode == s.angularDriveMode
                && slerpDrive.equals(s.slerpDrive)
                && twistDrive.equals(s.twistDrive)
                && swingDrive.equals(s.swingDrive)
                && Float.compare(angularOrientationTargetX, s.angularOrientationTargetX) == 0
                && Float.compare(angularOrientationTargetY, s.angularOrientationTargetY) == 0
                && Float.compare(angularOrientationTargetZ, s.angularOrientationTargetZ) == 0
                && Float.compare(angularOrientationTargetW, s.angularOrientationTargetW) == 0
                && Float.compare(angularVelocityTargetX, s.angularVelocityTargetX) == 0
                && Float.compare(angularVelocityTargetY, s.angularVelocityTargetY) == 0
                && Float.compare(angularVelocityTargetZ, s.angularVelocityTargetZ) == 0
                && Float.compare(parentInverseMassScale, s.parentInverseMassScale) == 0
                && massConditioning == s.massConditioning;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                linearMotionX,
                linearMotionY,
                linearMotionZ,
                linearLimit,
                linearLimitResponse,
                twistMotion,
                swing1Motion,
                swing2Motion,
                lowerTwistLimit,
                upperTwistLimit,
                swing1Limit,
                swing2Limit,
                swingLimitShape,
                twistLimitResponse,
                swingLimitResponse,
                linearDriveX,
                linearDriveY,
                linearDriveZ,
                angularDriveMode,
                slerpDrive,
                twistDrive,
                swingDrive,
                parentInverseMassScale,
                massConditioning);
    }
}
