package com.lowdragmc.minebox3d.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft's private {@code Entity.collide}: a move's collision, its step-up included — for a proxy's prediction. */
@Mixin(Entity.class)
public interface EntityInvoker {

    @Invoker("collide")
    Vec3 minebox3d$collide(Vec3 motion);
}
