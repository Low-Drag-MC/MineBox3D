package com.lowdragmc.minebox3d.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import com.lowdragmc.minebox3d.mc.sync.SyncedRiders;
import java.util.Set;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server and a player standing on a body (design doc {@code 04-entities-and-bodies.md} §11.2 #2 / #3):
 * <ul>
 * <li>its move — or its vehicle's — is put in the server's frame before it is checked: the position the move handler
 * reads is where on the server's body the client said, right before, that it stands (SyncedRiders.convert);</li>
 * <li>a body under it is something around, for the flying kick;</li>
 * <li>wherever the server puts it, its client is told when the bodies around are sent (SyncedRiders.putSomewhere).</li>
 * </ul>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    /** The move's position in the server's frame, between reading its x and its z. */
    @Unique
    @Nullable
    private Vec3 minebox3d$converted;

    @ModifyReturnValue(method = "noBlocksAround", at = @At("RETURN"))
    private boolean minebox3d$noBodiesAround(boolean noBlocks, Entity entity) {
        return noBlocks && !EntityCollisions.isBodyUnder(entity);
    }

    // the move's own read of its position (the first getX(0) only checks it is a number)

    @WrapOperation(
            method = "handleMovePlayer",
            at =
                    @At(
                            value = "INVOKE",
                            ordinal = 1,
                            target = "Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;getX(D)D"))
    private double minebox3d$moveX(ServerboundMovePlayerPacket packet, double fallback, Operation<Double> original) {
        double x = original.call(packet, fallback);
        minebox3d$converted = packet.hasPosition()
                ? SyncedRiders.convert(player, false, x, packet.getY(player.getY()), packet.getZ(player.getZ()))
                : null;
        return minebox3d$converted == null ? x : minebox3d$converted.x;
    }

    @WrapOperation(
            method = "handleMovePlayer",
            at =
                    @At(
                            value = "INVOKE",
                            ordinal = 1,
                            target = "Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;getY(D)D"))
    private double minebox3d$moveY(ServerboundMovePlayerPacket packet, double fallback, Operation<Double> original) {
        return minebox3d$converted == null ? original.call(packet, fallback) : minebox3d$converted.y;
    }

    @WrapOperation(
            method = "handleMovePlayer",
            at =
                    @At(
                            value = "INVOKE",
                            ordinal = 1,
                            target = "Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;getZ(D)D"))
    private double minebox3d$moveZ(ServerboundMovePlayerPacket packet, double fallback, Operation<Double> original) {
        Vec3 converted = minebox3d$converted;
        minebox3d$converted = null;
        return converted == null ? original.call(packet, fallback) : converted.z;
    }

    @WrapOperation(
            method = "handleMoveVehicle",
            at =
                    @At(
                            value = "INVOKE",
                            ordinal = 1,
                            target = "Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;getX()D"))
    private double minebox3d$vehicleX(ServerboundMoveVehiclePacket packet, Operation<Double> original) {
        double x = original.call(packet);
        minebox3d$converted = SyncedRiders.convert(player, true, x, packet.getY(), packet.getZ());
        return minebox3d$converted == null ? x : minebox3d$converted.x;
    }

    @WrapOperation(
            method = "handleMoveVehicle",
            at =
                    @At(
                            value = "INVOKE",
                            ordinal = 1,
                            target = "Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;getY()D"))
    private double minebox3d$vehicleY(ServerboundMoveVehiclePacket packet, Operation<Double> original) {
        return minebox3d$converted == null ? original.call(packet) : minebox3d$converted.y;
    }

    @WrapOperation(
            method = "handleMoveVehicle",
            at =
                    @At(
                            value = "INVOKE",
                            ordinal = 1,
                            target = "Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;getZ()D"))
    private double minebox3d$vehicleZ(ServerboundMoveVehiclePacket packet, Operation<Double> original) {
        Vec3 converted = minebox3d$converted;
        minebox3d$converted = null;
        return converted == null ? original.call(packet) : converted.z;
    }

    @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("RETURN"))
    private void minebox3d$putSomewhere(
            double x, double y, double z, float yaw, float pitch, Set<RelativeMovement> relative, CallbackInfo ci) {
        SyncedRiders.putSomewhere(player);
    }
}
