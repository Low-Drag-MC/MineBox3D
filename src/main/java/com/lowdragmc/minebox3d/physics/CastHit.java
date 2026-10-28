package com.lowdragmc.minebox3d.physics;

import org.jetbrains.annotations.Nullable;

/**
 * The closest hit of a ray or a shape cast: the shape, the world point, the surface normal and how far
 * along the cast. On voxel terrain {@code shape} is null and
 * {@link VoxelGrid#featureCell}{@code (shapeId, triangleIndex, …)} names the cell, while the world is open
 * and the section still exists.
 */
public record CastHit(@Nullable Shape shape, long shapeId, int triangleIndex, double x, double y, double z,
                      float normalX, float normalY, float normalZ, float fraction) {
    @Nullable
    public Body body() {
        return shape == null ? null : shape.body();
    }
}
