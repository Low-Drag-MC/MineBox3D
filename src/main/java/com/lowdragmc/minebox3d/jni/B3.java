package com.lowdragmc.minebox3d.jni;

import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.Filter;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.SurfaceMaterial;
import com.lowdragmc.minebox3d.physics.WorldDef;

/**
 * <b>The raw Box3D binding</b>: one static native per bridge function, ids as packed integers.
 *
 * <p>Ids are Box3D's own, packed the way Box3D packs them ({@code b3StoreWorldId} is an {@code int},
 * {@code b3StoreBodyId / ShapeId / JointId} a {@code long}); zero is the null id. Results come back
 * through arrays the caller owns, so any number of threads may call in at once as long as each world
 * is used by one thread at a time — Box3D's own rule.
 *
 * <p>Nothing here checks arguments beyond what keeps the process alive. The {@code physics} package
 * is the API meant to be used; this class is what it is built on.
 *
 * <p>⚠️ Call {@link com.lowdragmc.minebox3d.natives.NativeLibrary#load()} first: until it succeeds
 * every method here throws {@link UnsatisfiedLinkError}.
 */
public final class B3 {

    private B3() {
    }

    /** The classes the bridge reads fields of, in the order of {@code enum mb3_class} in mb3.h. */
    public static Class<?>[] boundClasses() {
        return new Class<?>[]{
                Box3DException.class,
                WorldDef.class,
                BodyDef.class,
                ShapeDef.class,
                Filter.class,
                SurfaceMaterial.class,
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

    public static native void worldEnableSleeping(int world, boolean flag);

    public static native void worldEnableContinuous(int world, boolean flag);

    /**
     * The bodies that moved in the last step. Writes up to {@code capacity}: the id, seven doubles of
     * transform ({@code [px, py, pz, qx, qy, qz, qw]}) and whether it fell asleep this step. Returns the
     * total, which may be more than was written; the events stay valid until the next step.
     */
    public static native int worldGetBodyMoveEvents(int world, long[] ids, double[] transforms, boolean[] fellAsleep, int capacity);

    /**
     * Closest hit along {@code origin + translation}, or 0. On a hit, {@code pointAndFraction} gets
     * {@code [px, py, pz, fraction, triangleIndex]} and {@code normal} the surface normal.
     */
    public static native long worldCastRayClosest(int world, double ox, double oy, double oz, float tx, float ty, float tz,
                                                  long categoryBits, long maskBits, double[] pointAndFraction, float[] normal);

    /**
     * Closest hit of a shape ({@code count} points {@code [x, y, z]} relative to the origin, swept with
     * {@code radius}) moved by {@code translation}; outputs as {@link #worldCastRayClosest}. A shape touching
     * something at the start hits it at fraction 0.
     */
    public static native long worldCastShapeClosest(int world, double ox, double oy, double oz, float[] points, int count,
                                                    float radius, float tx, float ty, float tz, long categoryBits,
                                                    long maskBits, double[] pointAndFraction, float[] normal);

    /** Shapes overlapping the query shape: ids into {@code shapeIds}; returns how many there are (may exceed the array). */
    public static native int worldOverlapShape(int world, double ox, double oy, double oz, float[] points, int count,
                                               float radius, long categoryBits, long maskBits, long[] shapeIds);

    /**
     * Collision planes around a capsule mover relative to the origin: {@code [nx, ny, nz, offset, px, py, pz]} per
     * plane, its shape and triangle index; returns the count, at most {@code shapeIds.length}.
     */
    public static native int worldCollideMover(int world, double ox, double oy, double oz, float x1, float y1, float z1,
                                               float x2, float y2, float z2, float radius, long categoryBits, long maskBits,
                                               float[] planes, long[] shapeIds, int[] triangleIndices);

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

    /** Seven doubles per body into {@code out}, in the order of {@code bodies}. */
    public static native void bodiesGetTransforms(long[] bodies, int count, double[] out);

    public static native void bodySetTransform(long body, double px, double py, double pz,
                                               float qx, float qy, float qz, float qw);

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
    public static native void bodySetTargetTransform(long body, double px, double py, double pz,
                                                     float qx, float qy, float qz, float qw, float timeStep, boolean wake);

    public static native void bodySetEnabled(long body, boolean enabled);

    public static native boolean bodyIsEnabled(long body);

    public static native void bodySetAwake(long body, boolean awake);

    public static native void bodyEnableSleep(long body, boolean enableSleep);

    /**
     * The body's touching contact points: per point {@code [nx, ny, nz, separation, normalImpulse, ax, ay, az]} (normal
     * from the other shape into this body, anchor on this body relative to its centre of mass), the other shape and
     * its triangle index. Returns how many there are, which may be more than the arrays take.
     */
    public static native int bodyGetContactPoints(long body, float[] points, long[] otherShapes, int[] triangleIndices);

    /** {@code [minX, minY, minZ, maxX, maxY, maxZ]}, world space, float (the broad phase's own box). */
    public static native void bodyComputeAABB(long body, float[] out);

    public static native void bodyApplyLinearImpulse(long body, float ix, float iy, float iz,
                                                     double px, double py, double pz, boolean wake);

    public static native void bodyApplyLinearImpulseToCenter(long body, float ix, float iy, float iz, boolean wake);

    public static native void bodyApplyAngularImpulse(long body, float ix, float iy, float iz, boolean wake);

    public static native void bodyApplyForce(long body, float fx, float fy, float fz, double px, double py, double pz, boolean wake);

    public static native void bodyApplyForceToCenter(long body, float fx, float fy, float fz, boolean wake);

    public static native void bodyApplyTorque(long body, float tx, float ty, float tz, boolean wake);

    public static native void bodySetGravityScale(long body, float scale);

    public static native void bodySetDamping(long body, float linear, float angular);

    public static native void bodySetBullet(long body, boolean bullet);

    // ---- shape --------------------------------------------------------------------------------

    public static native long createSphereShape(long body, ShapeDef definition, float cx, float cy, float cz, float radius);

    public static native long createCapsuleShape(long body, ShapeDef definition, float x1, float y1, float z1,
                                                 float x2, float y2, float z2, float radius);

    /** A box of half extents {@code (hx, hy, hz)} centred at {@code c}, turned by {@code q}, in body space. */
    public static native long createBoxShape(long body, ShapeDef definition, float hx, float hy, float hz,
                                             float cx, float cy, float cz, float qx, float qy, float qz, float qw);

    public static native void destroyShape(long shape, boolean updateBodyMass);

    public static native boolean shapeIsValid(long shape);

    public static native long shapeGetBody(long shape);

    public static native void shapeSetFilter(long shape, long categoryBits, long maskBits, int groupIndex, boolean invokeContacts);

    public static native void shapeSetFriction(long shape, float friction);

    public static native void shapeSetRestitution(long shape, float restitution);

    /** {@code [minX, minY, minZ, maxX, maxY, maxZ]}, world space. */
    public static native void shapeGetAABB(long shape, float[] out);

    // ---- mesh ---------------------------------------------------------------------------------

    /**
     * Builds Box3D mesh data (its BVH, and edge flags when {@code identifyEdges}) and returns the
     * pointer. Nothing passed in is kept. ⚠️ The data must outlive every shape made from it.
     *
     * @param materialIndices one per triangle, indexing the shape definition's materials, or null
     */
    public static native long createMesh(float[] vertices, int vertexCount, int[] indices, int triangleCount,
                                         byte[] materialIndices, float weldTolerance, boolean weldVertices,
                                         boolean useMedianSplit, boolean identifyEdges, boolean clockWiseWinding);

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
    public static native int voxelGridCollectTriangles(long grid, int sx, int sy, int sz, float[] bounds, float[] vertices,
                                                        byte[] flags);

    /**
     * The grid cell a contact point or query hit on one of this grid's voxel shapes names, into {@code out[0..2]}; false
     * if the shape is not one of the grid's (another world's included) or no longer exists.
     */
    public static native boolean voxelShapeGetFeatureCell(long grid, long shape, int triangleIndex, int[] out);
}
