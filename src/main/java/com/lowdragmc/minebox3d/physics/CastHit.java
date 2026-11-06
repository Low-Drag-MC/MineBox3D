package com.lowdragmc.minebox3d.physics;

import org.jetbrains.annotations.Nullable;

/**
 * One hit of a ray or a shape cast, as a value that stays: the shape, the world point, the surface normal and how far
 * along the cast. On voxel terrain {@code shape} is null and {@code terrain} is set with the block cell. The
 * allocating convenience; {@link CastResult} and {@link CastHits} are the reusable forms.
 */
public record CastHit(@Nullable Shape shape, long shapeId, double x, double y, double z, float normalX, float normalY,
                      float normalZ, float fraction, long userMaterialId, boolean terrain, int cellX, int cellY, int cellZ) {
    @Nullable
    public Body body() {
        return shape == null ? null : shape.body();
    }
}
