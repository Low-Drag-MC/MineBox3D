package com.lowdragmc.minebox3d.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A sneaking player stays on a body's edge as on a block's (design doc {@code 04-entities-and-bodies.md} §11):
 * Minecraft keeps it from walking off where it "can fall" — nothing in the box below — and asks the blocks only, so on
 * a body a sneaking player could not move at all.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {

    @WrapOperation(
            method = "canFallAtLeast",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean minebox3d$noBodyBelow(Level level, Entity entity, AABB box, Operation<Boolean> original) {
        return EntityCollisions.canFallInto(entity, box, original.call(level, entity, box));
    }
}
