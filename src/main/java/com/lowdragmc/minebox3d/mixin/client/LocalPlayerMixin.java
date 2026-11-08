package com.lowdragmc.minebox3d.mixin.client;

import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityBases;
import com.lowdragmc.minebox3d.mc.sync.StandingPayload;
import com.lowdragmc.minebox3d.mc.sync.SyncedRiders;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * This client's player and the bodies (design doc {@code 04-entities-and-bodies.md} §11.2 #3): right before each
 * move packet — its own, or the vehicle's it steers — where on its body it stands is told to the server, which puts the
 * move there on its own body before checking it; and while the server has yet to send the bodies around where it last
 * put the player, the player is held (not ticked: as Minecraft holds it where the chunk has not arrived). Standing on a
 * body that turns it, its view turns between ticks (§21.2).
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    /**
     * Its view's turn while a body it stands on turns it (EntityBases): drawn between the last tick's turn and this one's,
     * as Minecraft draws a rider's — a boat turning it. Its own player's view is otherwise drawn at the turn it has, for
     * the mouse turns that frame by frame; the mouse turns the last tick's turn as much ({@code Entity.turn}), so it is no
     * later for it. Turned by its base a tick at a time and drawn so, the view stood still for a tick's frames and jumped.
     */
    @Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
    private void minebox3d$turnedByItsBase(float partialTick, CallbackInfoReturnable<Float> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (!self.isPassenger() && EntityBases.turnsWithItsBase(self)) {
            cir.setReturnValue(partialTick == 1.0F ? self.getYRot() : Mth.lerp(partialTick, self.yRotO, self.getYRot()));
        }
    }

    @Shadow
    @Final
    public ClientPacketListener connection;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void minebox3d$hold(CallbackInfo ci) {
        if (SyncedRiders.isHeld()) {
            ci.cancel();
        }
    }

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void minebox3d$standing(CallbackInfo ci) {
        minebox3d$tell((Entity) (Object) this, false);
    }

    @Inject(method = "tick", at = @At(value = "NEW", target = "net/minecraft/network/protocol/game/ServerboundMoveVehiclePacket"))
    private void minebox3d$vehicleStanding(CallbackInfo ci) {
        minebox3d$tell(((Entity) (Object) this).getRootVehicle(), true);
    }

    private void minebox3d$tell(Entity mover, boolean vehicle) {
        LevelPhysics physics = LevelPhysics.existing(mover.level());
        if (physics != null) {
            physics.sync().riders().beforeMovePacket(mover, vehicle, connection.hasChannel(StandingPayload.TYPE));
        }
    }
}
