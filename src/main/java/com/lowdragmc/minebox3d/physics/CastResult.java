package com.lowdragmc.minebox3d.physics;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * <b>The closest hit of a ray or shape cast</b>, if any: a result object the caller keeps and passes in again (design doc
 * {@code 03-threading-and-api.md} §6.2). Valid until it is filled again. The accessors throw when there was no hit.
 */
public final class CastResult {

    final CastHits hit = new CastHits(1);

    public boolean hasHit() {
        return hit.count > 0;
    }

    /** The shape hit, {@code null} on voxel terrain. */
    @Nullable
    public Shape shape() {
        return hit.shape(0);
    }

    public long shapeId() {
        return hit.shapeId(0);
    }

    @Nullable
    public Body body() {
        return hit.body(0);
    }

    public double x() {
        return hit.x(0);
    }

    public double y() {
        return hit.y(0);
    }

    public double z() {
        return hit.z(0);
    }

    public Vector3d point(Vector3d out) {
        return hit.point(0, out);
    }

    public float fraction() {
        return hit.fraction(0);
    }

    public Vector3f normal(Vector3f out) {
        return hit.normal(0, out);
    }

    public long userMaterialId() {
        return hit.userMaterialId(0);
    }

    public boolean isTerrain() {
        return hit.isTerrain(0);
    }

    public Vector3i cell(Vector3i out) {
        return hit.cell(0, out);
    }

    public int cellX() {
        return hit.cellX(0);
    }

    public int cellY() {
        return hit.cellY(0);
    }

    public int cellZ() {
        return hit.cellZ(0);
    }

    /** A copy that stays as it is, or {@code null} without a hit. */
    @Nullable
    public CastHit toHit() {
        return hasHit() ? hit.toHit(0) : null;
    }
}
