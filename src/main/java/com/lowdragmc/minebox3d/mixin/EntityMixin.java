package com.lowdragmc.minebox3d.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lowdragmc.minebox3d.mc.entity.EntityBases;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Minecraft's entity movement sees the level's bodies (design doc {@code 04-entities-and-bodies.md} §11, 4c-1 / 4c-2):
 * every {@code Entity.move} is first carried by the body the entity stands on and gathers the bodies near it, the
 * axis-by-axis collision — the move's and its step-up's — cuts each axis by them after the blocks, and the move's
 * end finds the body it stands on now. All the work is in {@link EntityCollisions} and {@link EntityBases}; the entity
 * keeps its base here.
 */
@Mixin(Entity.class)
public abstract class EntityMixin implements EntityBases.Holder {

    @Unique
    @Nullable
    private EntityBases.Base minebox3d$base;

    @Override
    public EntityBases.@Nullable Base minebox3d$base() {
        return minebox3d$base;
    }

    @Override
    public void minebox3d$setBase(EntityBases.@Nullable Base base) {
        minebox3d$base = base;
    }

    @Inject(method = "move", at = @At("HEAD"))
    private void minebox3d$beginMove(MoverType type, Vec3 motion, CallbackInfo ci) {
        EntityCollisions.beginMove((Entity) (Object) this, motion);
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void minebox3d$endMove(MoverType type, Vec3 motion, CallbackInfo ci) {
        EntityCollisions.endMove((Entity) (Object) this);
    }

    @Inject(method = "collide", at = @At("HEAD"))
    private void minebox3d$collide(Vec3 motion, CallbackInfoReturnable<Vec3> cir) {
        EntityCollisions.collide(motion);
    }

    /** The step-up's own sweeps. */
    @WrapOperation(method = "collide", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collideWithShapes(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 minebox3d$stepUpWithBodies(Vec3 motion, AABB box, List<VoxelShape> shapes, Operation<Vec3> original) {
        return EntityCollisions.isActive() ? EntityCollisions.collideWithShapes(motion, box, shapes, false)
                : original.call(motion, box, shapes);
    }

    @WrapOperation(method = "collide", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collectCandidateStepUpHeights(Lnet/minecraft/world/phys/AABB;Ljava/util/List;FF)[F"))
    private float[] minebox3d$stepOntoBodies(AABB box, List<VoxelShape> shapes, float stepHeight, float clippedY,
                                             Operation<float[]> original) {
        return EntityCollisions.stepUpHeights(original.call(box, shapes, stepHeight, clippedY), box, stepHeight);
    }

    /** The move's sweep (and anyone else's {@code collideBoundingBox} during a move, or a proxy's prediction). */
    @WrapOperation(method = "collideBoundingBox", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collideWithShapes(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 minebox3d$collideWithBodies(Vec3 motion, AABB box, List<VoxelShape> shapes, Operation<Vec3> original) {
        return EntityCollisions.isActive() ? EntityCollisions.collideWithShapes(motion, box, shapes, true)
                : original.call(motion, box, shapes);
    }
}
