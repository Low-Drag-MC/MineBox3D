package com.lowdragmc.minebox3d.mc.entity;

import com.lowdragmc.minebox3d.physics.Body;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;

/**
 * <b>A body moved into an entity hard enough to push it</b> (design doc {@code 04-entities-and-bodies.md} §2): fired
 * on {@code NeoForge.EVENT_BUS}, on the server's level thread, once per entity per tick, just before the push is added
 * to the entity's motion.
 *
 * <p>Cancel it to leave the entity where it is; change {@link #setVelocityChange the velocity change} to push harder
 * or softer. MineBox3D does no damage on its own — a mod that wants a falling crate to hurt does it here, from
 * {@link #getApproachSpeed()} or {@link #getImpulse()}.
 */
public class EntityPushedByBodyEvent extends EntityEvent implements ICancellableEvent {

    private final Body body;
    private final float approachSpeed;
    private final float impulse;
    private Vec3 velocityChange;

    public EntityPushedByBodyEvent(Entity entity, Body body, Vec3 velocityChange, float approachSpeed, float impulse) {
        super(entity);
        this.body = body;
        this.velocityChange = velocityChange;
        this.approachSpeed = approachSpeed;
        this.impulse = impulse;
    }

    /** The body that pushed hardest this tick (more than one may have). It may have been destroyed since. */
    public Body getBody() {
        return body;
    }

    /** How fast the body came at the entity, m/s (blocks a second), the fastest of the tick. */
    public float getApproachSpeed() {
        return approachSpeed;
    }

    /** The momentum the entity takes, kg·m/s: its mass times the velocity change, before any listener changed it. */
    public float getImpulse() {
        return impulse;
    }

    /** What is added to the entity's velocity, m/s (blocks a second; Minecraft's motion is a twentieth of it). */
    public Vec3 getVelocityChange() {
        return velocityChange;
    }

    public void setVelocityChange(Vec3 velocityChange) {
        this.velocityChange = velocityChange;
    }
}
