// MineBox3D JNI bridge: shared helpers.
//
// Every exported function follows the same three rules, which are what make the bridge safe to
// call from any number of threads at once:
//  1. No static scratch state. Results are written into arrays or objects the caller passes in,
//     so two threads reading two bodies can never see each other's numbers.
//  2. The JNIEnv is the one the call arrived with. Nothing caches an env or calls back into Java
//     from a thread it was not given.
//  3. A Box3D assertion does not break into a debugger. It is recorded for the calling thread and
//     turned into a Box3DException when the call returns (MB3_RETURN / mb3_check).
#pragma once

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>

#include "box3d/box3d.h"
#include "box3d/collision.h"
#include "box3d/math_functions.h"

// MB3_JNI( jint, createWorld )( JNIEnv* env, jclass type, ... ) declares B3.createWorld
#define MB3_JNI( type, name ) JNIEXPORT type JNICALL Java_com_lowdragmc_minebox3d_jni_B3_##name

#if defined( _MSC_VER )
#define MB3_THREAD_LOCAL __declspec( thread )
#else
#define MB3_THREAD_LOCAL _Thread_local
#endif

// ---- errors -------------------------------------------------------------------------------------

// Throws the Box3DException for an assertion that fired during this call, if one did.
// Returns true when an exception is now pending.
bool mb3_check( JNIEnv* env );
void mb3_throw( JNIEnv* env, const char* message );
void mb3_throw_illegal_argument( JNIEnv* env, const char* message );

// ---- ids ----------------------------------------------------------------------------------------

static inline b3WorldId mb3_world( jint packed )
{
	return b3LoadWorldId( (uint32_t)packed );
}

static inline jint mb3_pack_world( b3WorldId id )
{
	return (jint)b3StoreWorldId( id );
}

static inline b3BodyId mb3_body( jlong packed )
{
	return b3LoadBodyId( (uint64_t)packed );
}

static inline jlong mb3_pack_body( b3BodyId id )
{
	return (jlong)b3StoreBodyId( id );
}

static inline b3ShapeId mb3_shape( jlong packed )
{
	return b3LoadShapeId( (uint64_t)packed );
}

static inline jlong mb3_pack_shape( b3ShapeId id )
{
	return (jlong)b3StoreShapeId( id );
}

static inline b3JointId mb3_joint( jlong packed )
{
	return b3LoadJointId( (uint64_t)packed );
}

static inline jlong mb3_pack_joint( b3JointId id )
{
	return (jlong)b3StoreJointId( id );
}

// ---- math ---------------------------------------------------------------------------------------

static inline b3Vec3 mb3_vec3( jfloat x, jfloat y, jfloat z )
{
	return ( b3Vec3 ){ x, y, z };
}

static inline b3Pos mb3_pos( jdouble x, jdouble y, jdouble z )
{
	return ( b3Pos ){ x, y, z };
}

static inline b3Quat mb3_quat( jfloat x, jfloat y, jfloat z, jfloat w )
{
	return ( b3Quat ){ { x, y, z }, w };
}

// Writes into a caller-owned array. Returns false (with an exception pending) when it is too short.
bool mb3_put_floats( JNIEnv* env, jfloatArray out, int offset, const float* values, int count );
bool mb3_put_doubles( JNIEnv* env, jdoubleArray out, int offset, const double* values, int count );

// [x, y, z] into a double[] at offset, and the same for a float[].
bool mb3_put_pos( JNIEnv* env, jdoubleArray out, int offset, b3Pos p );
bool mb3_put_vec3( JNIEnv* env, jfloatArray out, int offset, b3Vec3 v );
// [x, y, z, w]
bool mb3_put_quat( JNIEnv* env, jfloatArray out, int offset, b3Quat q );

// ---- definitions --------------------------------------------------------------------------------
// The Java definition classes are plain objects with public fields; the bridge reads and writes them
// through field ids looked up once, in B3.init. A field renamed on one side and not the other fails
// that lookup at startup instead of silently reading zero.

bool mb3_defs_init( JNIEnv* env, jobjectArray classes );

void mb3_world_def_to_java( JNIEnv* env, jobject target, const b3WorldDef* def );
bool mb3_world_def_from_java( JNIEnv* env, jobject source, b3WorldDef* def );

void mb3_body_def_to_java( JNIEnv* env, jobject target, const b3BodyDef* def );
// The name, if any, is copied into nameBuffer (Box3D copies it again into its own name table).
bool mb3_body_def_from_java( JNIEnv* env, jobject source, b3BodyDef* def, char* nameBuffer, int nameCapacity );

void mb3_shape_def_to_java( JNIEnv* env, jobject target, const b3ShapeDef* def );
// Per-triangle materials, when the definition has any, are copied into a heap block that
// mb3_shape_def_release frees; call it on every path out once this returned true.
bool mb3_shape_def_from_java( JNIEnv* env, jobject source, b3ShapeDef* def, char* nameBuffer, int nameCapacity );
void mb3_shape_def_release( b3ShapeDef* def );

void mb3_filter_to_java( JNIEnv* env, jobject target, b3Filter filter );
bool mb3_filter_from_java( JNIEnv* env, jobject source, b3Filter* filter );

void mb3_material_to_java( JNIEnv* env, jobject target, const b3SurfaceMaterial* material );
bool mb3_material_from_java( JNIEnv* env, jobject source, b3SurfaceMaterial* material );

#define MB3_NAME_CAPACITY 128

// ---- locking ------------------------------------------------------------------------------------
// Box3D keeps its worlds in one global array and b3CreateWorld scans it for a free slot without a
// lock (physics_world.c): two threads creating worlds at once can be handed the same slot. Creation
// and destruction go through this lock; everything else is per world and needs none.
void mb3_world_lock( void );
void mb3_world_unlock( void );

// The classes B3.init passes, in this order.
enum mb3_class
{
	MB3_CLASS_EXCEPTION,
	MB3_CLASS_WORLD_DEF,
	MB3_CLASS_BODY_DEF,
	MB3_CLASS_SHAPE_DEF,
	MB3_CLASS_FILTER,
	MB3_CLASS_SURFACE_MATERIAL,
	MB3_CLASS_COUNT
};
