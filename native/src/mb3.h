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
#include <math.h>
#include <stdbool.h>
#include <stdint.h>

#include "box3d/box3d.h"
#include "box3d/collision.h"
#include "box3d/math_functions.h"

// MB3_JNI( jint, createWorld )( JNIEnv* env, jclass type, ... ) declares B3.createWorld
#define MB3_JNI( type, name ) JNIEXPORT type JNICALL Java_com_lowdragmc_minebox3d_internal_B3_##name

#if defined( _MSC_VER )
#define MB3_THREAD_LOCAL __declspec( thread )
#else
#define MB3_THREAD_LOCAL _Thread_local
#endif

// ---- errors -------------------------------------------------------------------------------------

// Throws the Box3DException for an assertion that fired during this call, if one did.
// Returns true when an exception is now pending.
bool mb3_check( JNIEnv* env );
// Whether an assertion fired on this thread since the last mb3_check, without reporting it. For undoing what a
// call created before the assertion is reported (Box3D creates the object even when one of its checks fails).
bool mb3_assert_pending( void );
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

// Normalised: Box3D asserts unit length to 20 float epsilons, far tighter than a rotation typed in or composed in Java
// stays (0.7071 for √½ is already out), and stores the value before the assertion is reported. Java refuses what is not
// near unit length; this takes the rest the last bit of the way.
static inline b3Quat mb3_quat( jfloat x, jfloat y, jfloat z, jfloat w )
{
	float lengthSquared = x * x + y * y + z * z + w * w;
	if ( lengthSquared > 0.0f )
	{
		float inverse = 1.0f / sqrtf( lengthSquared );
		x *= inverse;
		y *= inverse;
		z *= inverse;
		w *= inverse;
	}
	return ( b3Quat ){ { x, y, z }, w };
}

// The same for a rotation read straight into a Box3D struct.
static inline b3Quat mb3_normalized( b3Quat q )
{
	return mb3_quat( q.v.x, q.v.y, q.v.z, q.s );
}

// Writes into a caller-owned array. Returns false (with an exception pending) when it is too short.
bool mb3_put_floats( JNIEnv* env, jfloatArray out, int offset, const float* values, int count );
bool mb3_put_doubles( JNIEnv* env, jdoubleArray out, int offset, const double* values, int count );

// [x, y, z] into a double[] at offset, and the same for a float[].
bool mb3_put_pos( JNIEnv* env, jdoubleArray out, int offset, b3Pos p );
bool mb3_put_vec3( JNIEnv* env, jfloatArray out, int offset, b3Vec3 v );
// [x, y, z, w]
bool mb3_put_quat( JNIEnv* env, jfloatArray out, int offset, b3Quat q );

// ---- results ------------------------------------------------------------------------------------

// The terrain cell a query hit, contact point or event on a shape names: [1, x, y, z] on voxel terrain, else zeros.
// Only for shapes of the world the call is about (b3Shape_GetVoxelCell reads that world).
void mb3_cell( b3ShapeId shape, int triangleIndex, int out[4] );

// One hit as the Java result classes read it: 11 doubles [px, py, pz, fraction, nx, ny, nz, terrain, cx, cy, cz] and
// 2 longs [shape, userMaterialId].
#define MB3_HIT_DOUBLES 11
#define MB3_HIT_LONGS 2
void mb3_hit( b3ShapeId shape, b3Pos point, b3Vec3 normal, float fraction, uint64_t userMaterialId, int triangleIndex,
			  double* doubles, jlong* longs );

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

// ---- material combine modes (mb3_materials.c) -----------------------------------------------------------------------
// Box3D's friction and restitution callbacks take no context, so a surface's combine modes ride in the top byte of its
// userMaterialId: bits 56–58 friction, 59–61 restitution, each SurfaceMaterial.Combine's ordinal + 1, 0 for Box3D's own.
// Java never sees the byte: the bridge packs it on the way in and masks it off every userMaterialId on the way out.
#define MB3_USER_MATERIAL_BITS 0x00FFFFFFFFFFFFFFull

uint64_t mb3_material_pack( uint64_t userMaterialId, int frictionCombine, int restitutionCombine );
int mb3_material_friction_combine( uint64_t userMaterialId );
int mb3_material_restitution_combine( uint64_t userMaterialId );

// The callbacks every world gets
float mb3_mix_friction( float frictionA, uint64_t userMaterialIdA, float frictionB, uint64_t userMaterialIdB );
float mb3_mix_restitution( float restitutionA, uint64_t userMaterialIdA, float restitutionB, uint64_t userMaterialIdB );

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
	MB3_CLASS_JOINT_DEF,
	MB3_CLASS_DISTANCE_JOINT_DEF,
	MB3_CLASS_MOTOR_JOINT_DEF,
	MB3_CLASS_PARALLEL_JOINT_DEF,
	MB3_CLASS_PRISMATIC_JOINT_DEF,
	MB3_CLASS_REVOLUTE_JOINT_DEF,
	MB3_CLASS_SPHERICAL_JOINT_DEF,
	MB3_CLASS_WELD_JOINT_DEF,
	MB3_CLASS_WHEEL_JOINT_DEF,
	MB3_CLASS_MOVER_JOINT_DEF,
	MB3_CLASS_POGO_JOINT_DEF,
	MB3_CLASS_CONSTRAINT_JOINT_DEF,
	MB3_CLASS_CONSTRAINT_JOINT_SETTINGS,
	MB3_CLASS_CONSTRAINT_DRIVE,
	MB3_CLASS_CONSTRAINT_LIMIT_RESPONSE,
	MB3_CLASS_CONSTRAINT_MOTION,
	MB3_CLASS_SWING_LIMIT_SHAPE,
	MB3_CLASS_ANGULAR_DRIVE_MODE,
	MB3_CLASS_SURFACE_COMBINE,
	MB3_CLASS_COUNT
};

// The ordinal of a Java enum value, -1 for null
int mb3_enum_ordinal( JNIEnv* env, jobject value );

// Frees the body pair tables of a world about to be destroyed (mb3_pairs.c)
void mb3_pairs_world_destroyed( b3WorldId world );

// Whether the body never meets the bodies tagged tag (mb3_pairs.c); false for 0
bool mb3_pairs_ignores( b3WorldId world, b3BodyId body, uint64_t tag );

// The joint definition classes' fields (mb3_joint.c); called by mb3_defs_init.
bool mb3_joint_defs_init( JNIEnv* env, jobjectArray classes );

// ---- queries --------------------------------------------------------------------------------------------------------

b3QueryFilter mb3_query_filter( jlong categoryBits, jlong maskBits );
// The hit arrays a closest query writes: at least MB3_HIT_DOUBLES doubles and MB3_HIT_LONGS longs, else throws.
bool mb3_hit_arrays( JNIEnv* env, jdoubleArray doubles, jlongArray longs );
void mb3_put_hit( JNIEnv* env, jdoubleArray doubles, jlongArray longs, int index, const double* d, const jlong* l );
// A query shape: 1 to B3_MAX_SHAPE_CAST_POINTS points [x, y, z, ...] relative to the query origin and a radius, copied
// into storage (B3_MAX_SHAPE_CAST_POINTS long).
bool mb3_proxy( JNIEnv* env, jfloatArray points, jint count, jfloat radius, b3Vec3* storage, b3ShapeProxy* proxy );
// The closest point on a convex shape to a world point, measured in the point's own frame so it stays exact far from
// the origin (Box3D's own closest point queries are float in world space). Returns the distance, FLT_MAX for a shape
// that is not convex (mesh, height field, voxel terrain); *closest is the world point.
float mb3_closest_point( b3ShapeId shape, b3WorldTransform bodyTransform, b3Pos target, b3Pos* closest );
