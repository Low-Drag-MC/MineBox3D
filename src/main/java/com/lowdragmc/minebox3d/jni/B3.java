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

    // ---- shape --------------------------------------------------------------------------------

    public static native long createSphereShape(long body, ShapeDef definition, float cx, float cy, float cz, float radius);

    public static native long createCapsuleShape(long body, ShapeDef definition, float x1, float y1, float z1,
                                                 float x2, float y2, float z2, float radius);

    /** A box of half extents {@code (hx, hy, hz)} centred at {@code c}, turned by {@code q}, in body space. */
    public static native long createBoxShape(long body, ShapeDef definition, float hx, float hy, float hz,
                                             float cx, float cy, float cz, float qx, float qy, float qz, float qw);

    public static native void destroyShape(long shape, boolean updateBodyMass);

    public static native boolean shapeIsValid(long shape);
}
