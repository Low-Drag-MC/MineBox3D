package com.lowdragmc.minebox3d.internal;

import com.lowdragmc.minebox3d.physics.AngularDriveMode;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.Box3DException;
import com.lowdragmc.minebox3d.physics.ConstraintDrive;
import com.lowdragmc.minebox3d.physics.ConstraintJointDef;
import com.lowdragmc.minebox3d.physics.ConstraintJointSettings;
import com.lowdragmc.minebox3d.physics.ConstraintLimitResponse;
import com.lowdragmc.minebox3d.physics.ConstraintMotion;
import com.lowdragmc.minebox3d.physics.DistanceJointDef;
import com.lowdragmc.minebox3d.physics.Filter;
import com.lowdragmc.minebox3d.physics.JointDef;
import com.lowdragmc.minebox3d.physics.MotionLockJointDef;
import com.lowdragmc.minebox3d.physics.MotorJointDef;
import com.lowdragmc.minebox3d.physics.MoverJointDef;
import com.lowdragmc.minebox3d.physics.ParallelJointDef;
import com.lowdragmc.minebox3d.physics.PogoJointDef;
import com.lowdragmc.minebox3d.physics.PrismaticJointDef;
import com.lowdragmc.minebox3d.physics.RevoluteJointDef;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.SphericalJointDef;
import com.lowdragmc.minebox3d.physics.SurfaceMaterial;
import com.lowdragmc.minebox3d.physics.SwingLimitShape;
import com.lowdragmc.minebox3d.physics.WeldJointDef;
import com.lowdragmc.minebox3d.physics.WheelJointDef;
import com.lowdragmc.minebox3d.physics.WorldDef;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>The raw Box3D binding</b>: one static native per bridge function, ids as packed integers.
 *
 * <p>Ids are Box3D's own, packed the way Box3D packs them ({@code b3StoreWorldId} is an {@code int},
 * {@code b3StoreBodyId / ShapeId / JointId} a {@code long}); zero is the null id. Results come back
 * through arrays the caller owns, so any number of threads may call in at once as long as each world
 * is used by one thread at a time — Box3D's own rule.
 *
 * <p>⚠️ Not API. Nothing here checks threads, closed worlds or stale ids: an id whose world slot has been reused
 * reaches into whichever world has it now, possibly one another thread is stepping. The {@code physics} package is
 * the API; this class is what it is built on.
 *
 * <p>⚠️ Call {@link com.lowdragmc.minebox3d.natives.NativeLibrary#load()} first: until it succeeds
 * every method here throws {@link UnsatisfiedLinkError}.
 */
@ApiStatus.Internal
public final class B3 {

    private B3() {}

    /** The classes the bridge reads fields of, in the order of {@code enum mb3_class} in mb3.h. */
    public static Class<?>[] boundClasses() {
        return new Class<?>[] {
            Box3DException.class,
            WorldDef.class,
            BodyDef.class,
            ShapeDef.class,
            Filter.class,
            SurfaceMaterial.class,
            JointDef.class,
            DistanceJointDef.class,
            MotorJointDef.class,
            ParallelJointDef.class,
            PrismaticJointDef.class,
            RevoluteJointDef.class,
            SphericalJointDef.class,
            WeldJointDef.class,
            WheelJointDef.class,
            MoverJointDef.class,
            PogoJointDef.class,
            MotionLockJointDef.class,
            ConstraintJointDef.class,
            ConstraintJointSettings.class,
            ConstraintDrive.class,
            ConstraintLimitResponse.class,
            ConstraintMotion.class,
            SwingLimitShape.class,
            AngularDriveMode.class,
            SurfaceMaterial.Combine.class,
        };
    }

    // ---- library ------------------------------------------------------------------------------

    /** Looks up every field the bridge touches and installs the assertion and log handlers. */
    public static native boolean init(Class<?>[] classes);

    /** {@code [major, minor, revision]} of the Box3D that was compiled in. */
    public static native void getVersion(int[] out);

    public static native boolean isDoublePrecision();

    /** {@code B3_MAX_WORLDS} as this library was compiled with. */
    public static native int getMaxWorlds();

    /** Bytes Box3D currently has allocated, across every world. */
    public static native long getByteCount();

    /** The next Box3D log message, or {@code null}. Messages from any thread queue up here. */
    public static native String pollLog();

    // ---- defaults -----------------------------------------------------------------------------

    public static native void defaultWorldDef(WorldDef target);

    public static native void defaultBodyDef(BodyDef target);

    public static native void defaultShapeDef(ShapeDef target);

    public static native void defaultFilter(Filter target);

    public static native void defaultSurfaceMaterial(SurfaceMaterial target);

    // ---- world --------------------------------------------------------------------------------

    public static native int createWorld(WorldDef definition);

    public static native void destroyWorld(int world);

    public static native boolean worldIsValid(int world);

    public static native int getWorldCount();

    public static native void worldStep(int world, float timeStep, int subStepCount);

    public static native void worldSetGravity(int world, float x, float y, float z);

    public static native void worldGetGravity(int world, float[] out);

    public static native int worldGetAwakeBodyCount(int world);

    /** {@code param}: 0 restitution threshold, 1 hit event threshold, 2 maximum linear speed. */
    public static native void worldSetFloat(int world, int param, float value);

    public static native float worldGetFloat(int world, int param);

    /** {@code flag}: 0 sleeping, 1 continuous collision. */
    public static native void worldSetFlag(int world, int flag, boolean value);

    public static native boolean worldGetFlag(int world, int flag);

    public static native void worldSetContactTuning(int world, float hertz, float dampingRatio, float contactSpeed);

    /** Box3D's explosion: an outward impulse per area facing {@code (x, y, z)}, to shapes in {@code maskBits}. */
    public static native void worldExplode(
            int world, double x, double y, double z, float radius, float falloff, float impulsePerArea, long maskBits);

    /** The last step's time per phase in milliseconds, the 23 fields of {@code b3Profile} in order. */
    public static native void worldGetProfile(int world, float[] out);

    /** Joints past their force or torque threshold in the last step; returns the total, which may exceed the array. */
    public static native int worldGetJointEvents(int world, long[] out);

    /**
     * The bodies that moved in the last step, which is every awake body. Writes up to {@code capacity}: the id, seven
     * doubles of transform ({@code [px, py, pz, qx, qy, qz, qw]}), twelve floats of state ({@code [minX, minY, minZ,
     * maxX, maxY, maxZ, vx, vy, vz, wx, wy, wz]}) and whether it fell asleep this step. Returns the total, which may be
     * more than was written; the events stay valid until the next step.
     */
    public static native int worldGetBodyMoveEvents(
            int world, long[] ids, double[] transforms, float[] states, boolean[] fellAsleep, int capacity);

    /**
     * Contact events of the last step: 2 shape ids per begin and end; per hit 4 longs {@code [shapeA, shapeB,
     * materialA, materialB]} and 11 doubles {@code [px, py, pz, nx, ny, nz, approachSpeed, terrainSide, cx, cy, cz]}
     * (terrain side 0 = neither, 1 = A, 2 = B is voxel terrain, with the cell). {@code counts} gets the totals
     * {@code [begins, ends, hits]}; only what fits is written.
     */
    public static native void worldGetContactEvents(
            int world, long[] begins, long[] ends, long[] hitIds, double[] hitData, int[] counts);

    /** Sensor events of the last step: {@code [sensor, visitor]} each; {@code counts} gets {@code [begins, ends]}. */
    public static native void worldGetSensorEvents(int world, long[] begins, long[] ends, int[] counts);

    /**
     * Closest hit along {@code origin + translation}: 11 doubles {@code [px, py, pz, fraction, nx, ny, nz, terrain,
     * cx, cy, cz]} and 2 longs {@code [shape, userMaterialId]}. False for none.
     */
    public static native boolean worldCastRayClosest(
            int world,
            double ox,
            double oy,
            double oz,
            float tx,
            float ty,
            float tz,
            long categoryBits,
            long maskBits,
            double[] doubles,
            long[] longs);

    /**
     * Every hit along {@code origin + translation}, closest first, 11 doubles and 2 longs each as
     * {@link #worldCastRayClosest}. Returns how many there are; more than {@code capacity} writes nothing.
     */
    public static native int worldCastRay(
            int world,
            double ox,
            double oy,
            double oz,
            float tx,
            float ty,
            float tz,
            long categoryBits,
            long maskBits,
            double[] doubles,
            long[] longs,
            int capacity);

    /**
     * Closest hit of a shape ({@code count} points {@code [x, y, z]} relative to the origin, swept with
     * {@code radius}) moved by {@code translation}; outputs as {@link #worldCastRayClosest}. A shape touching
     * something at the start hits it at fraction 0.
     */
    public static native boolean worldCastShapeClosest(
            int world,
            double ox,
            double oy,
            double oz,
            float[] points,
            int count,
            float radius,
            float tx,
            float ty,
            float tz,
            long categoryBits,
            long maskBits,
            double[] doubles,
            long[] longs);

    /**
     * Every hit of a swept shape (as {@link #worldCastShapeClosest}), closest first, one per shape, 11 doubles and 2
     * longs each as {@link #worldCastRayClosest}. Returns how many there are; more than {@code capacity} writes
     * nothing.
     */
    public static native int worldCastShape(
            int world,
            double ox,
            double oy,
            double oz,
            float[] points,
            int count,
            float radius,
            float tx,
            float ty,
            float tz,
            long categoryBits,
            long maskBits,
            double[] doubles,
            long[] longs,
            int capacity);

    /**
     * A box of half sizes {@code (hx, hy, hz)} at {@code (cx, cy, cz)} turned by {@code q}, swept exactly by
     * {@code translation} (mb3_sweep.c): {@code doubles} {@code [fraction, nx, ny, nz, px, py, pz, depth,
     * startPenetrating]}, {@code longs} {@code [shape]}. Not terrain, compounds or sensors, nor the shapes of
     * {@code ignoreBody} (0 for none), nor those of the bodies that never meet {@code tag} (0 for none).
     */
    public static native boolean worldSweepBox(
            int world,
            double cx,
            double cy,
            double cz,
            float qx,
            float qy,
            float qz,
            float qw,
            float hx,
            float hy,
            float hz,
            float tx,
            float ty,
            float tz,
            long categoryBits,
            long maskBits,
            boolean ignoreExits,
            long ignoreBody,
            long tag,
            double[] doubles,
            long[] longs);

    /**
     * Shapes whose broad-phase bounds may overlap the box (candidates, not exact): ids into {@code shapeIds}; returns
     * how many there are (may exceed the array).
     */
    public static native int worldOverlapAABB(
            int world,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            long categoryBits,
            long maskBits,
            long[] shapeIds);

    /** Shapes overlapping the query shape: ids into {@code shapeIds}; returns how many (may exceed the array). */
    public static native int worldOverlapShape(
            int world,
            double ox,
            double oy,
            double oz,
            float[] points,
            int count,
            float radius,
            long categoryBits,
            long maskBits,
            long[] shapeIds);

    /**
     * Collision planes around a capsule mover relative to the origin: {@code [nx, ny, nz, offset, px, py, pz]} per
     * plane, its shape and {@code [terrain, x, y, z]}; returns the count, at most {@code shapeIds.length}.
     */
    public static native int worldCollideMover(
            int world,
            double ox,
            double oy,
            double oz,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float radius,
            long categoryBits,
            long maskBits,
            float[] planes,
            long[] shapeIds,
            int[] cells);

    /**
     * {@code [bodies, shapes, contacts, joints, islands, awake contacts]} into {@code out}; returns the
     * last step's time in milliseconds.
     */
    public static native float worldGetCounters(int world, int[] out);

    // ---- body ---------------------------------------------------------------------------------

    public static native long createBody(int world, BodyDef definition);

    public static native void destroyBody(long body);

    public static native boolean bodyIsValid(long body);

    /** {@code [px, py, pz, qx, qy, qz, qw]} */
    public static native void bodyGetTransform(long body, double[] out);

    /** Seven doubles per body into {@code out}, in the order of {@code bodies}, each as {@link #bodyGetTransform}. */
    public static native void bodiesGetTransforms(long[] bodies, int count, double[] out);

    public static native void bodySetTransform(
            long body, double px, double py, double pz, float qx, float qy, float qz, float qw);

    public static native void bodyGetLinearVelocity(long body, float[] out);

    public static native void bodySetLinearVelocity(long body, float x, float y, float z);

    public static native void bodyGetAngularVelocity(long body, float[] out);

    public static native void bodySetAngularVelocity(long body, float x, float y, float z);

    public static native boolean bodyIsAwake(long body);

    public static native float bodyGetMass(long body);

    /** {@link com.lowdragmc.minebox3d.physics.BodyType#ordinal()} */
    public static native int bodyGetType(long body);

    public static native void bodySetType(long body, int type);

    /** Moves a kinematic body to the target over {@code timeStep} by velocity, pushing what it meets. */
    public static native void bodySetTargetTransform(
            long body,
            double px,
            double py,
            double pz,
            float qx,
            float qy,
            float qz,
            float qw,
            float timeStep,
            boolean wake);

    /** {@link #bodySetTargetTransform} for {@code count} bodies, 7 doubles each as {@link #bodiesGetTransforms}. */
    public static native void bodiesSetTargetTransforms(
            long[] bodies, int count, double[] transforms, float timeStep, boolean wake);

    public static native void bodySetEnabled(long body, boolean enabled);

    public static native boolean bodyIsEnabled(long body);

    public static native void bodySetAwake(long body, boolean awake);

    /** {@code param}: 0 linear damping, 1 angular damping, 2 gravity scale, 3 sleep threshold, 4 safety factor. */
    public static native void bodySetFloat(long body, int param, float value);

    public static native float bodyGetFloat(long body, int param);

    /** {@code flag}: 0 bullet, 1 sleep enabled, 2 fast rotation allowed, 3 contact recycling, 4 fixed rotation. */
    public static native void bodySetFlag(long body, int flag, boolean value);

    public static native boolean bodyGetFlag(long body, int flag);

    /** Hit events on every shape of the body. */
    public static native void bodyEnableHitEvents(long body, boolean enable);

    /** {@code [mass, cx, cy, cz, inertia about the centre of mass column by column (9)]}, body space. */
    public static native void bodyGetMassData(long body, float[] out);

    public static native void bodySetMassData(long body, float[] values);

    public static native void bodyApplyMassFromShapes(long body);

    public static native void bodyGetWorldCenter(long body, double[] out);

    public static native void bodyGetWorldPointVelocity(long body, double x, double y, double z, float[] out);

    /** The closest hit on this body only; outputs as {@link #worldCastRayClosest}. */
    public static native boolean bodyCastRay(
            long body,
            double ox,
            double oy,
            double oz,
            float tx,
            float ty,
            float tz,
            long categoryBits,
            long maskBits,
            double[] doubles,
            long[] longs);

    /** The closest hit of a query shape on this body only; outputs as {@link #worldCastRayClosest}. */
    public static native boolean bodyCastShape(
            long body,
            double ox,
            double oy,
            double oz,
            float[] points,
            int count,
            float radius,
            float tx,
            float ty,
            float tz,
            long categoryBits,
            long maskBits,
            double[] doubles,
            long[] longs);

    public static native boolean bodyOverlapShape(
            long body,
            double ox,
            double oy,
            double oz,
            float[] points,
            int count,
            float radius,
            long categoryBits,
            long maskBits);

    /**
     * The closest point on the body's convex shapes into {@code out}; the distance (0 inside), {@code Float.MAX_VALUE}
     * for none.
     */
    public static native float bodyGetClosestPoint(long body, double x, double y, double z, double[] out);

    /**
     * The points of the body's touching contacts: per point {@code [nx, ny, nz, separation, normalImpulse,
     * totalNormalImpulse, normalVelocity, ax, ay, az]} (normal from the other shape into this body; impulse of the last
     * sub-step and of the step; relative normal velocity before the solve, negative approaching, 0 unless the pair has
     * hit events; anchor from this body's centre of mass, world orientation), the other shape and
     * {@code [terrain, x, y, z]}. Returns how many there are, which may be more than the arrays take.
     */
    public static native int bodyGetContactPoints(long body, float[] points, long[] otherShapes, int[] cells);

    /** {@code [minX, minY, minZ, maxX, maxY, maxZ]}, world space, float (the broad phase's own box). */
    public static native void bodyComputeAABB(long body, float[] out);

    public static native void bodyApplyLinearImpulse(
            long body, float ix, float iy, float iz, double px, double py, double pz, boolean wake);

    public static native void bodyApplyLinearImpulseToCenter(long body, float ix, float iy, float iz, boolean wake);

    public static native void bodyApplyAngularImpulse(long body, float ix, float iy, float iz, boolean wake);

    public static native void bodyApplyForce(
            long body, float fx, float fy, float fz, double px, double py, double pz, boolean wake);

    public static native void bodyApplyForceToCenter(long body, float fx, float fy, float fz, boolean wake);

    public static native void bodyApplyTorque(long body, float tx, float ty, float tz, boolean wake);

    // ---- shape --------------------------------------------------------------------------------

    public static native long createSphereShape(
            long body, ShapeDef definition, float cx, float cy, float cz, float radius);

    public static native long createCapsuleShape(
            long body, ShapeDef definition, float x1, float y1, float z1, float x2, float y2, float z2, float radius);

    /** A box of half extents {@code (hx, hy, hz)} centred at {@code c}, turned by {@code q}, in body space. */
    public static native long createBoxShape(
            long body,
            ShapeDef definition,
            float hx,
            float hy,
            float hz,
            float cx,
            float cy,
            float cz,
            float qx,
            float qy,
            float qz,
            float qw);

    public static native void destroyShape(long shape, boolean updateBodyMass);

    public static native boolean shapeIsValid(long shape);

    public static native long shapeGetBody(long shape);

    public static native void shapeSetFilter(
            long shape, long categoryBits, long maskBits, int groupIndex, boolean invokeContacts);

    public static native void shapeSetFriction(long shape, float friction);

    public static native void shapeSetRestitution(long shape, float restitution);

    /** {@code [minX, minY, minZ, maxX, maxY, maxZ]}, world space. */
    public static native void shapeGetAABB(long shape, float[] out);

    /** A convex hull around {@code count} points ({@code [x, y, z]} each), body space. */
    public static native long createHullShape(long body, ShapeDef definition, float[] points, int count);

    /**
     * The vertices of the hull Box3D makes of the points into {@code out} (room for {@link #getMaxHullVertices()});
     * returns how many.
     */
    public static native int computeHull(float[] points, int count, float[] out);

    public static native int getMaxHullVertices();

    // in place: Box3D keeps the shape and rebuilds its contacts; the body's mass is not recomputed

    public static native void shapeSetSphere(long shape, float cx, float cy, float cz, float radius);

    public static native void shapeSetCapsule(
            long shape, float x1, float y1, float z1, float x2, float y2, float z2, float radius);

    public static native void shapeSetBox(
            long shape,
            float hx,
            float hy,
            float hz,
            float cx,
            float cy,
            float cz,
            float qx,
            float qy,
            float qz,
            float qw);

    public static native void shapeSetHull(long shape, float[] points, int count);

    public static native void shapeSetMesh(long shape, long mesh, float sx, float sy, float sz);

    public static native void shapeSetDensity(long shape, float density, boolean updateBodyMass);

    public static native float shapeGetDensity(long shape);

    public static native void shapeSetSurfaceMaterial(long shape, SurfaceMaterial material);

    public static native void shapeGetSurfaceMaterial(long shape, SurfaceMaterial target);

    public static native int shapeGetMeshMaterialCount(long shape);

    public static native void shapeSetMeshMaterial(long shape, SurfaceMaterial material, int index);

    public static native void shapeGetMeshMaterial(long shape, int index, SurfaceMaterial target);

    public static native void shapeGetFilter(long shape, Filter target);

    /** {@code flag}: 0 sensor events, 1 contact events, 2 hit events; 3 is a sensor (read only). */
    public static native void shapeSetFlag(long shape, int flag, boolean value);

    public static native boolean shapeGetFlag(long shape, int flag);

    /** The shapes inside this sensor as of the last step into {@code out}; returns the total, which may exceed it. */
    public static native int shapeGetSensorOverlaps(long shape, long[] out);

    /** The closest point on this convex shape into {@code out}; the distance, {@code Float.MAX_VALUE} if not convex. */
    public static native float shapeGetClosestPoint(long shape, double x, double y, double z, double[] out);

    /**
     * A query shape swept against this shape only (our fork's {@code b3Shape_CastShape}); outputs as
     * {@link #worldCastRayClosest}. With {@code canEncroach}, one touching it without overlapping hits only moving in
     * (Box3D's box mover).
     */
    public static native boolean shapeCastShape(
            long shape,
            double ox,
            double oy,
            double oz,
            float[] points,
            int count,
            float radius,
            float tx,
            float ty,
            float tz,
            boolean canEncroach,
            double[] doubles,
            long[] longs);

    // ---- height fields ------------------------------------------------------------------------

    /**
     * Builds Box3D height-field data and returns the pointer. Nothing passed in is kept. ⚠️ Box3D keeps only the
     * pointer: it must outlive every shape made from it. Heights outside {@code [minHeight, maxHeight]} are clamped;
     * both are unscaled.
     *
     * @param heights   {@code countX * countZ}, index {@code z * countX + x}
     * @param materials one per cell ({@code (countX - 1) * (countZ - 1)}), {@code 0xFF} a hole, or null
     */
    public static native long createHeightField(
            float[] heights,
            int countX,
            int countZ,
            byte[] materials,
            float sx,
            float sy,
            float sz,
            float minHeight,
            float maxHeight,
            boolean clockwise);

    public static native void destroyHeightField(long heightField);

    public static native long createHeightFieldShape(long body, ShapeDef definition, long heightField);

    // ---- joints -------------------------------------------------------------------------------

    /** Fills a joint definition with Box3D's defaults; {@code type} is {@code b3JointType}. */
    public static native void defaultJointDef(int type, JointDef target);

    public static native long createJoint(int world, int type, long bodyA, long bodyB, JointDef definition);

    public static native void destroyJoint(long joint, boolean wakeAttached);

    public static native boolean jointIsValid(long joint);

    public static native int jointGetType(long joint);

    public static native void jointSetLocalFrame(
            long joint, boolean frameB, float px, float py, float pz, float qx, float qy, float qz, float qw);

    /** {@code [px, py, pz, qx, qy, qz, qw]} */
    public static native void jointGetLocalFrame(long joint, boolean frameB, float[] out);

    /** A runtime property by number ({@code JointParam}): one to four values, booleans as 0 / 1. */
    public static native void jointSet(long joint, int param, float a, float b, float c, float d);

    /** Writes the property into {@code out} and returns how many values it is. */
    public static native int jointGet(long joint, int param, float[] out);

    // ---- constraint joints (MineBox3D) --------------------------------------------------------

    public static native void defaultConstraintJointSettings(ConstraintJointSettings target);

    public static native void constraintJointSetSettings(long joint, ConstraintJointSettings settings);

    public static native void constraintJointGetSettings(long joint, ConstraintJointSettings out);

    /**
     * 13 floats per joint: {@code [linear position xyz, linear velocity xyz, orientation xyzw, angular velocity xyz]},
     * as the settings' targets.
     */
    public static native void constraintJointsSetTargets(long[] joints, int count, float[] values, boolean wake);

    /**
     * 8 floats per joint: {@code [twist, swing1, swing2, linear offset xyz, linear separation, angular separation]},
     * the offset in frame A.
     */
    public static native void constraintJointsGetState(long[] joints, int count, float[] out);

    // ---- body pair tables ---------------------------------------------------------------------

    /** A table over the bodies, every pair colliding; its handle. Shapes ask it only with custom filtering enabled. */
    public static native int pairTableCreate(int world, long[] bodies, int count);

    /** Bit {@code i * count + j} set = bodies i and j do not collide. */
    public static native void pairTableSetBits(int world, int table, int[] words);

    /** A body's tags: what it is, and the tag of the bodies it never meets; 0 for none of either. */
    public static native void bodySetTags(int world, long body, long tag, long ignored);

    public static native void pairTableDestroy(int world, int table);

    // ---- mesh ---------------------------------------------------------------------------------

    /**
     * Builds Box3D mesh data (its BVH, and edge flags when {@code identifyEdges}) and returns the
     * pointer. Nothing passed in is kept. ⚠️ The data must outlive every shape made from it.
     *
     * @param materialIndices one per triangle, indexing the shape definition's materials, or null
     */
    public static native long createMesh(
            float[] vertices,
            int vertexCount,
            int[] indices,
            int triangleCount,
            byte[] materialIndices,
            float weldTolerance,
            boolean weldVertices,
            boolean useMedianSplit,
            boolean identifyEdges,
            boolean clockWiseWinding);

    public static native void destroyMesh(long mesh);

    public static native long createMeshShape(long body, ShapeDef definition, long mesh, float sx, float sy, float sz);

    // ---- voxel terrain (our Box3D fork) -------------------------------------------------------

    /** A voxel grid for the world; every section's shape copies {@code shapeDefinition}. Returns the pointer. */
    public static native long createVoxelGrid(int world, ShapeDef shapeDefinition);

    /** Destroys the grid and, if the world still exists, every section body it made. */
    public static native void destroyVoxelGrid(long grid);

    /** Adds a surface material to the grid's table; returns its index. */
    public static native int voxelGridAddMaterial(long grid, SurfaceMaterial material);

    /**
     * Adds a collider: {@code [minX, minY, minZ, maxX, maxY, maxZ]} per box in the unit cell, boxes not
     * overlapping. Returns its id, 1 or more.
     */
    public static native int voxelGridAddCollider(long grid, float[] boxes, int boxCount, int material);

    public static native int voxelGridGetColliderCount(long grid);

    /** 4096 collider ids indexed {@code x + 16 * z + 256 * y}; null or all zero removes the section. */
    public static native void voxelGridSetSection(long grid, int sx, int sy, int sz, short[] cells);

    /** {@code [x, y, z, collider]} per edit, grid cell coordinates. */
    public static native void voxelGridSetCells(long grid, int[] edits, int count);

    public static native int voxelGridGetCell(long grid, int x, int y, int z);

    public static native boolean voxelGridHasSection(long grid, int sx, int sy, int sz);

    public static native int voxelGridGetSectionCount(long grid);

    /** The section's shape id, 0 if there is no such section. */
    public static native long voxelGridGetSectionShape(long grid, int sx, int sy, int sz);

    /**
     * What contacts see of a section: its exposed triangles overlapping {@code bounds} ({@code [minX, minY,
     * minZ, maxX, maxY, maxZ]}, section coordinates), 9 floats each into {@code vertices} and Box3D edge flags
     * into {@code flags}. Returns how many there are, which may be more than fit.
     */
    public static native int voxelGridCollectTriangles(
            long grid, int sx, int sy, int sz, float[] bounds, float[] vertices, byte[] flags);
}
