package com.lowdragmc.minebox3d.mixin;

import com.lowdragmc.minebox3d.mc.LevelPhysicsEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every block change goes through here on both sides — players, pistons, explosions, commands, the
 * client applying the server's updates — so the terrain in Box3D hears of all of them. NeoForge's
 * block events only see the player-made ones.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void minebox3d$onSetBlockState(BlockPos pos, BlockState state, boolean isMoving,
                                           CallbackInfoReturnable<BlockState> cir) {
        BlockState previous = cir.getReturnValue();
        BlockState current = ((LevelChunk) (Object) this).getBlockState(pos);
        // null is "nothing changed" (the state was already there), or "the old block's onRemove changed it again",
        // which is a change whose previous state is unknown
        if (previous == null && current == state) {
            return;
        }
        LevelPhysicsEvents.onBlockChanged(level, pos, previous, current);
    }
}
