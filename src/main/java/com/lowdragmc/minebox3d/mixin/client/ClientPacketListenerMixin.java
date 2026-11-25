package com.lowdragmc.minebox3d.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lowdragmc.minebox3d.mc.sync.BodySyncPayload;
import com.lowdragmc.minebox3d.mc.sync.SyncedRiders;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client and entities standing on bodies (design doc {@code 04-entities-and-bodies.md} §11.2 #3): one the server
 * says stands on a body here is kept there on the replica (SyncedRiders), so Minecraft's own movement packets for it —
 * eased in over three ticks, a few ticks apart — are let go meanwhile (the last kept for when it leaves the body); and
 * where the server puts this client's player, it is held till the bodies around are sent.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @WrapOperation(
            method = {"handleTeleportEntity", "handleMoveEntity"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;lerpTo(DDDFFI)V"))
    private void minebox3d$keepPlaced(
            Entity entity, double x, double y, double z, float yRot, float xRot, int steps, Operation<Void> original) {
        if (!SyncedRiders.keepsPlaced(entity, x, y, z, yRot, xRot)) {
            original.call(entity, x, y, z, yRot, xRot, steps);
        }
    }

    @WrapOperation(
            method = "handleRotateMob",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;lerpHeadTo(FI)V"))
    private void minebox3d$keepPlacedHead(Entity entity, float yaw, int steps, Operation<Void> original) {
        if (!SyncedRiders.keepsPlacedHead(entity, yaw)) {
            original.call(entity, yaw, steps);
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void minebox3d$putHere(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        SyncedRiders.putHere(((ClientPacketListener) (Object) this).hasChannel(BodySyncPayload.TYPE));
    }
}
