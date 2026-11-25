package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionfc;
import org.joml.Vector3dc;
import org.joml.Vector3fc;

/**
 * Box3D's {@code b3BodyDef}. A new one holds Box3D's own defaults: a static body at the origin.
 *
 * <p>Re-usable: creating a body reads it and keeps nothing.
 */
public final class BodyDef {
    public BodyType type = BodyType.STATIC;
    /** World position. Double: Minecraft coordinates reach 30 million. */
    public double positionX, positionY, positionZ;
    /** Unit quaternion. */
    public float rotationX, rotationY, rotationZ, rotationW;
    public float linearVelocityX, linearVelocityY, linearVelocityZ;
    /** rad/s */
    public float angularVelocityX, angularVelocityY, angularVelocityZ;
    public float linearDamping;
    public float angularDamping;
    public float gravityScale;
    /** Sleep speed threshold, m/s. */
    public float sleepThreshold;
    /** Continuous-collision safety factor, 0.01–0.5. */
    public float safetyFactor;
    @Nullable
    public String name;
    public long userData;
    /**
     * No turning: the inverse inertia is zero and the angular velocity kept at zero. To keep a body upright yet free to
     * turn about one axis, or to lock its movement along axes, give it a {@link MotionLockJointDef motion lock joint}.
     */
    public boolean fixedRotation;
    public boolean enableSleep;
    public boolean awake;
    public boolean bullet;
    public boolean enabled;
    public boolean allowFastRotation;
    public boolean enableContactRecycling;

    public BodyDef() {
        NativeLibrary.load();
        B3.defaultBodyDef(this);
    }

    public BodyDef type(BodyType type) {
        this.type = type;
        return this;
    }

    public BodyDef position(double x, double y, double z) {
        positionX = x;
        positionY = y;
        positionZ = z;
        return this;
    }

    public BodyDef position(Vector3dc position) {
        return position(position.x(), position.y(), position.z());
    }

    public BodyDef rotation(float x, float y, float z, float w) {
        rotationX = x;
        rotationY = y;
        rotationZ = z;
        rotationW = w;
        return this;
    }

    public BodyDef rotation(Quaternionfc rotation) {
        return rotation(rotation.x(), rotation.y(), rotation.z(), rotation.w());
    }

    public BodyDef linearVelocity(float x, float y, float z) {
        linearVelocityX = x;
        linearVelocityY = y;
        linearVelocityZ = z;
        return this;
    }

    public BodyDef linearVelocity(Vector3fc velocity) {
        return linearVelocity(velocity.x(), velocity.y(), velocity.z());
    }

    public BodyDef angularVelocity(float x, float y, float z) {
        angularVelocityX = x;
        angularVelocityY = y;
        angularVelocityZ = z;
        return this;
    }

    public BodyDef damping(float linear, float angular) {
        linearDamping = linear;
        angularDamping = angular;
        return this;
    }

    public BodyDef gravityScale(float gravityScale) {
        this.gravityScale = gravityScale;
        return this;
    }

    public BodyDef name(@Nullable String name) {
        this.name = name;
        return this;
    }

    public BodyDef userData(long userData) {
        this.userData = userData;
        return this;
    }

    public BodyDef fixedRotation(boolean fixedRotation) {
        this.fixedRotation = fixedRotation;
        return this;
    }

    public BodyDef bullet(boolean bullet) {
        this.bullet = bullet;
        return this;
    }

    public BodyDef enableSleep(boolean enableSleep) {
        this.enableSleep = enableSleep;
        return this;
    }

    public BodyDef awake(boolean awake) {
        this.awake = awake;
        return this;
    }

    public BodyDef enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }
}
