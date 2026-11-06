package com.lowdragmc.minebox3d.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lowdragmc.minebox3d.mc.sync.SyncedRiders;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An entity a client keeps on a body where the server says (design doc {@code 04-entities-and-bodies.md} §11.2 #3)
 * walks by its steps on the body, not by the body's travel: Minecraft's walk animation goes by how far the entity moved
 * since the last tick, which on a moving deck is mostly the deck.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @WrapOperation(method = "calculateEntityAnimation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;length(DDD)D"))
    private double minebox3d$stepOnBody(double x, double y, double z, Operation<Double> original) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide && SyncedRiders.placedHere(self)) {
            return SyncedRiders.stepHere(self);
        }
        return original.call(x, y, z);
    }
}
