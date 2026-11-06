package com.lowdragmc.minebox3d.mixin.client;

import com.lowdragmc.minebox3d.mc.LevelPhysics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client's level dropped for the configuration phase goes with its physics: unlike a dimension change or a
 * disconnect, that posts no {@code LevelEvent.Unload}.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow
    @Nullable
    public ClientLevel level;

    @Inject(method = "clearClientLevel", at = @At("HEAD"))
    private void minebox3d$unloadPhysics(Screen screen, CallbackInfo ci) {
        if (level != null) {
            LevelPhysics.unloadClient(level);
        }
    }
}
