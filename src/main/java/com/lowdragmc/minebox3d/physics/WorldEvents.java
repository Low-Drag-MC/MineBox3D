package com.lowdragmc.minebox3d.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * <b>What happened in a world's steps</b> since the frame began ({@link PhysicsWorld#beginInterpolationFrame()}):
 * touches beginning and ending, hits, sensor overlaps, joints past their thresholds (design doc
 * {@code 03-threading-and-api.md} §6.3). Copied out of Box3D once per step and gathered over the frame's steps (a world
 * that never begins a frame keeps the last step's), then read on the thread using the world; nothing is called back
 * during a step. The lists are live: the next frame empties them.
 *
 * <p>Shapes are resolved when copied. A shape destroyed between steps is still named by the end events Box3D reports
 * for it after the next step; {@code null} is voxel terrain, which has no {@link Shape}, with its id.
 *
 * <p>Box3D reports a pair when either shape asks: contacts with {@link ShapeDef#enableContactEvents}, hits with
 * {@link ShapeDef#enableHitEvents} above the world's hit threshold (all off by default). Sensor overlaps need
 * {@link ShapeDef#enableSensorEvents} on both the {@link ShapeDef#sensor} and the shape entering it, which must be a
 * sphere, capsule, box or hull.
 */
public final class WorldEvents {

    /** Two shapes began touching. */
    public record ContactBegin(@Nullable Shape shapeA, @Nullable Shape shapeB, long shapeIdA, long shapeIdB) {}

    /** Two shapes stopped touching, or one of them went. */
    public record ContactEnd(@Nullable Shape shapeA, @Nullable Shape shapeB, long shapeIdA, long shapeIdB) {}

    /**
     * Two shapes met faster than the world's hit threshold. {@code x, y, z} is where, the normal points from A to B,
     * {@code approachSpeed} is in m/s, {@code materialA} and {@code materialB} are the surfaces'
     * {@linkplain SurfaceMaterial#userMaterialId user material ids}. When one of them is voxel terrain,
     * {@code terrainSide} says which (1 = A, 2 = B, 0 = neither) and {@code cellX, cellY, cellZ} the block cell.
     */
    public record ContactHit(
            @Nullable Shape shapeA,
            @Nullable Shape shapeB,
            long shapeIdA,
            long shapeIdB,
            double x,
            double y,
            double z,
            float normalX,
            float normalY,
            float normalZ,
            float approachSpeed,
            long materialA,
            long materialB,
            int terrainSide,
            int cellX,
            int cellY,
            int cellZ) {
        public boolean onTerrain() {
            return terrainSide != 0;
        }
    }

    /** A shape began overlapping a sensor. */
    public record SensorBegin(@Nullable Shape sensor, @Nullable Shape visitor, long sensorId, long visitorId) {}

    /** A shape stopped overlapping a sensor, or one of them went. */
    public record SensorEnd(@Nullable Shape sensor, @Nullable Shape visitor, long sensorId, long visitorId) {}

    /**
     * A joint whose force or torque passed its threshold ({@link JointDef#forceThreshold}) in a step, while awake.
     * Box3D only reports it: breaking the joint is the reader's call ({@link Joint#destroy}). {@code null} for a joint
     * made outside the API.
     */
    public record JointEvent(@Nullable Joint joint, long jointId) {}

    final List<ContactBegin> contactBegins = new ArrayList<>();
    final List<ContactEnd> contactEnds = new ArrayList<>();
    final List<ContactHit> contactHits = new ArrayList<>();
    final List<SensorBegin> sensorBegins = new ArrayList<>();
    final List<SensorEnd> sensorEnds = new ArrayList<>();
    final List<JointEvent> jointEvents = new ArrayList<>();

    WorldEvents() {}

    /** Joints past their thresholds, once per step they were. */
    public List<JointEvent> jointEvents() {
        return Collections.unmodifiableList(jointEvents);
    }

    public List<ContactBegin> contactBegins() {
        return Collections.unmodifiableList(contactBegins);
    }

    public List<ContactEnd> contactEnds() {
        return Collections.unmodifiableList(contactEnds);
    }

    public List<ContactHit> contactHits() {
        return Collections.unmodifiableList(contactHits);
    }

    public List<SensorBegin> sensorBegins() {
        return Collections.unmodifiableList(sensorBegins);
    }

    public List<SensorEnd> sensorEnds() {
        return Collections.unmodifiableList(sensorEnds);
    }

    void clear() {
        contactBegins.clear();
        contactEnds.clear();
        contactHits.clear();
        sensorBegins.clear();
        sensorEnds.clear();
        jointEvents.clear();
    }
}
