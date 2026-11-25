// MineBox3D JNI bridge: worlds.

#include "mb3.h"

#include "box3d/voxel.h"

#include <float.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

MB3_JNI( jint, createWorld )( JNIEnv* env, jclass type, jobject definition )
{
	(void)type;
	b3WorldDef def;
	if ( mb3_world_def_from_java( env, definition, &def ) == false )
	{
		return 0;
	}
	// SurfaceMaterial's combine modes; surfaces without one mix as Box3D's own do
	def.frictionCallback = mb3_mix_friction;
	def.restitutionCallback = mb3_mix_restitution;
	mb3_world_lock();
	int live = b3GetWorldCount();
	b3WorldId id = live < B3_MAX_WORLDS ? b3CreateWorld( &def ) : b3_nullWorldId;
	mb3_world_unlock();
	if ( mb3_check( env ) )
	{
		return 0;
	}
	if ( B3_IS_NULL( id ) )
	{
		mb3_throw( env, "no free Box3D world slot (B3_MAX_WORLDS reached)" );
		return 0;
	}
	return mb3_pack_world( id );
}

MB3_JNI( void, destroyWorld )( JNIEnv* env, jclass type, jint world )
{
	(void)type;
	mb3_world_lock();
	mb3_pairs_world_destroyed( mb3_world( world ) );
	b3DestroyWorld( mb3_world( world ) );
	mb3_world_unlock();
	mb3_check( env );
}

MB3_JNI( jboolean, worldIsValid )( JNIEnv* env, jclass type, jint world )
{
	(void)env;
	(void)type;
	return b3World_IsValid( mb3_world( world ) ) ? JNI_TRUE : JNI_FALSE;
}

MB3_JNI( jint, getWorldCount )( JNIEnv* env, jclass type )
{
	(void)env;
	(void)type;
	return b3GetWorldCount();
}

MB3_JNI( void, worldStep )( JNIEnv* env, jclass type, jint world, jfloat timeStep, jint subStepCount )
{
	(void)type;
	b3World_Step( mb3_world( world ), timeStep, subStepCount );
	mb3_check( env );
}

MB3_JNI( void, worldSetGravity )( JNIEnv* env, jclass type, jint world, jfloat x, jfloat y, jfloat z )
{
	(void)type;
	b3World_SetGravity( mb3_world( world ), mb3_vec3( x, y, z ) );
	mb3_check( env );
}

MB3_JNI( void, worldGetGravity )( JNIEnv* env, jclass type, jint world, jfloatArray out )
{
	(void)type;
	b3Vec3 gravity = b3World_GetGravity( mb3_world( world ) );
	if ( mb3_check( env ) == false )
	{
		mb3_put_vec3( env, out, 0, gravity );
	}
}

MB3_JNI( jint, worldGetAwakeBodyCount )( JNIEnv* env, jclass type, jint world )
{
	(void)type;
	int count = b3World_GetAwakeBodyCount( mb3_world( world ) );
	mb3_check( env );
	return count;
}

// ---- settings -------------------------------------------------------------------------------------------------------
// ⚠️ The numbers are PhysicsWorld's in Java.

enum mb3_world_param
{
	MB3_WP_RESTITUTION_THRESHOLD = 0,
	MB3_WP_HIT_EVENT_THRESHOLD = 1,
	MB3_WP_MAXIMUM_LINEAR_SPEED = 2,
};

enum mb3_world_flag
{
	MB3_WF_SLEEPING = 0,
	MB3_WF_CONTINUOUS = 1,
};

MB3_JNI( void, worldSetFloat )( JNIEnv* env, jclass type, jint world, jint param, jfloat value )
{
	(void)type;
	b3WorldId id = mb3_world( world );
	switch ( param )
	{
		case MB3_WP_RESTITUTION_THRESHOLD: b3World_SetRestitutionThreshold( id, value ); break;
		case MB3_WP_HIT_EVENT_THRESHOLD: b3World_SetHitEventThreshold( id, value ); break;
		case MB3_WP_MAXIMUM_LINEAR_SPEED: b3World_SetMaximumLinearSpeed( id, value ); break;
		default: mb3_throw_illegal_argument( env, "not a world setting" ); return;
	}
	mb3_check( env );
}

MB3_JNI( jfloat, worldGetFloat )( JNIEnv* env, jclass type, jint world, jint param )
{
	(void)type;
	b3WorldId id = mb3_world( world );
	float value;
	switch ( param )
	{
		case MB3_WP_RESTITUTION_THRESHOLD: value = b3World_GetRestitutionThreshold( id ); break;
		case MB3_WP_HIT_EVENT_THRESHOLD: value = b3World_GetHitEventThreshold( id ); break;
		case MB3_WP_MAXIMUM_LINEAR_SPEED: value = b3World_GetMaximumLinearSpeed( id ); break;
		default: mb3_throw_illegal_argument( env, "not a world setting" ); return 0;
	}
	mb3_check( env );
	return value;
}

MB3_JNI( void, worldSetFlag )( JNIEnv* env, jclass type, jint world, jint flag, jboolean value )
{
	(void)type;
	b3WorldId id = mb3_world( world );
	switch ( flag )
	{
		case MB3_WF_SLEEPING: b3World_EnableSleeping( id, value ); break;
		case MB3_WF_CONTINUOUS: b3World_EnableContinuous( id, value ); break;
		default: mb3_throw_illegal_argument( env, "not a world switch" ); return;
	}
	mb3_check( env );
}

MB3_JNI( jboolean, worldGetFlag )( JNIEnv* env, jclass type, jint world, jint flag )
{
	(void)type;
	b3WorldId id = mb3_world( world );
	bool value;
	switch ( flag )
	{
		case MB3_WF_SLEEPING: value = b3World_IsSleepingEnabled( id ); break;
		case MB3_WF_CONTINUOUS: value = b3World_IsContinuousEnabled( id ); break;
		default: mb3_throw_illegal_argument( env, "not a world switch" ); return JNI_FALSE;
	}
	mb3_check( env );
	return value ? JNI_TRUE : JNI_FALSE;
}

// Box3D has no getter for these: the definition's values until set.
MB3_JNI( void, worldSetContactTuning )( JNIEnv* env, jclass type, jint world, jfloat hertz, jfloat dampingRatio,
										jfloat contactSpeed )
{
	(void)type;
	b3World_SetContactTuning( mb3_world( world ), hertz, dampingRatio, contactSpeed );
	mb3_check( env );
}

// An impulse outwards from a point, to shapes whose category is in maskBits: per unit of the area facing the point
// within radius, falling off to zero over falloff beyond it. Spheres, capsules and hulls only.
MB3_JNI( void, worldExplode )( JNIEnv* env, jclass type, jint world, jdouble x, jdouble y, jdouble z, jfloat radius,
							   jfloat falloff, jfloat impulsePerArea, jlong maskBits )
{
	(void)type;
	b3ExplosionDef def = b3DefaultExplosionDef();
	def.position = mb3_pos( x, y, z );
	def.radius = radius;
	def.falloff = falloff;
	def.impulsePerArea = impulsePerArea;
	def.maskBits = (uint64_t)maskBits;
	b3World_Explode( mb3_world( world ), &def );
	mb3_check( env );
}

MB3_JNI( void, worldGetProfile )( JNIEnv* env, jclass type, jint world, jfloatArray out )
{
	(void)type;
	b3Profile profile = b3World_GetProfile( mb3_world( world ) );
	if ( mb3_check( env ) == false )
	{
		// b3Profile is nothing but floats
		mb3_put_floats( env, out, 0, (const float*)&profile, (int)( sizeof( profile ) / sizeof( float ) ) );
	}
}

// ---- events ---------------------------------------------------------------------------------------------------------
// All of them stay valid until the next step: a caller whose arrays were too small grows them and asks again.

static bool mb3_lengths_at_least( JNIEnv* env, jarray a, int count, jarray b, int countB, jarray c, int countC,
								  jarray d, int countD )
{
	if ( ( a != NULL && ( *env )->GetArrayLength( env, a ) < count ) ||
		 ( b != NULL && ( *env )->GetArrayLength( env, b ) < countB ) ||
		 ( c != NULL && ( *env )->GetArrayLength( env, c ) < countC ) ||
		 ( d != NULL && ( *env )->GetArrayLength( env, d ) < countD ) )
	{
		mb3_throw_illegal_argument( env, "event arrays are shorter than capacity" );
		return false;
	}
	return true;
}

MB3_JNI( jint, worldGetBodyMoveEvents )( JNIEnv* env, jclass type, jint world, jlongArray ids, jdoubleArray transforms,
										 jfloatArray states, jbooleanArray fellAsleep, jint capacity )
{
	(void)type;
	b3BodyEvents events = b3World_GetBodyEvents( mb3_world( world ) );
	if ( mb3_check( env ) )
	{
		return 0;
	}
	int count = events.moveCount < capacity ? events.moveCount : capacity;
	if ( count <= 0 )
	{
		return events.moveCount;
	}
	if ( ids == NULL || transforms == NULL || states == NULL || fellAsleep == NULL ||
		 mb3_lengths_at_least( env, ids, count, transforms, 7 * count, states, 12 * count, fellAsleep, count ) ==
			 false )
	{
		if ( ( *env )->ExceptionCheck( env ) == false )
			mb3_throw_illegal_argument( env, "event arrays must not be null" );
		return 0;
	}

	// The extra state is read first: no Box3D call may run while the arrays are held critical
	float* state = malloc( 12 * count * sizeof( float ) );
	if ( state == NULL )
	{
		mb3_throw( env, "out of memory" );
		return 0;
	}
	for ( int i = 0; i < count; ++i )
	{
		b3BodyId body = events.moveEvents[i].bodyId;
		b3AABB box = b3Body_ComputeAABB( body );
		b3Vec3 v = b3Body_GetLinearVelocity( body );
		b3Vec3 w = b3Body_GetAngularVelocity( body );
		float* s = state + 12 * i;
		s[0] = box.lowerBound.x, s[1] = box.lowerBound.y, s[2] = box.lowerBound.z;
		s[3] = box.upperBound.x, s[4] = box.upperBound.y, s[5] = box.upperBound.z;
		s[6] = v.x, s[7] = v.y, s[8] = v.z;
		s[9] = w.x, s[10] = w.y, s[11] = w.z;
	}
	if ( mb3_check( env ) )
	{
		free( state );
		return 0;
	}
	( *env )->SetFloatArrayRegion( env, states, 0, 12 * count, state );
	free( state );

	jlong* idOut = ( *env )->GetPrimitiveArrayCritical( env, ids, NULL );
	jdouble* transformOut = idOut != NULL ? ( *env )->GetPrimitiveArrayCritical( env, transforms, NULL ) : NULL;
	jboolean* asleepOut = transformOut != NULL ? ( *env )->GetPrimitiveArrayCritical( env, fellAsleep, NULL ) : NULL;
	if ( asleepOut != NULL )
	{
		for ( int i = 0; i < count; ++i )
		{
			const b3BodyMoveEvent* e = events.moveEvents + i;
			idOut[i] = mb3_pack_body( e->bodyId );
			jdouble* t = transformOut + 7 * i;
			t[0] = e->transform.p.x;
			t[1] = e->transform.p.y;
			t[2] = e->transform.p.z;
			t[3] = e->transform.q.v.x;
			t[4] = e->transform.q.v.y;
			t[5] = e->transform.q.v.z;
			t[6] = e->transform.q.s;
			asleepOut[i] = e->fellAsleep ? JNI_TRUE : JNI_FALSE;
		}
	}
	if ( asleepOut != NULL )
		( *env )->ReleasePrimitiveArrayCritical( env, fellAsleep, asleepOut, 0 );
	if ( transformOut != NULL )
		( *env )->ReleasePrimitiveArrayCritical( env, transforms, transformOut, 0 );
	if ( idOut != NULL )
		( *env )->ReleasePrimitiveArrayCritical( env, ids, idOut, 0 );
	return events.moveCount;
}

// The terrain cell a hit event names, on whichever of its shapes is voxel terrain: the cell of the triangle Box3D took
// the hit point and the materials from (our fork's triangleIndex). Box3D puts a voxel shape first in a contact, so
// that is shape A; were it B, the hit point moved a little into it. side: 1 = shape A, 2 = shape B.
static void mb3_hit_cell( const b3ContactHitEvent* e, int side, int out[4] )
{
	if ( side == 1 )
	{
		mb3_cell( e->shapeIdA, e->triangleIndex, out );
		if ( out[0] != 0 )
		{
			return;
		}
	}

	// The normal points from A to B: into the terrain is -normal when it is A, +normal when it is B
	float into = side == 1 ? -0.05f : 0.05f;
	out[0] = 1;
	out[1] = (int)floor( e->point.x + into * e->normal.x );
	out[2] = (int)floor( e->point.y + into * e->normal.y );
	out[3] = (int)floor( e->point.z + into * e->normal.z );
}

MB3_JNI( void, worldGetContactEvents )( JNIEnv* env, jclass type, jint world, jlongArray begins, jlongArray ends,
										jlongArray hitIds, jdoubleArray hitData, jintArray counts )
{
	(void)type;
	b3ContactEvents events = b3World_GetContactEvents( mb3_world( world ) );
	if ( mb3_check( env ) )
	{
		return;
	}
	if ( begins == NULL || ends == NULL || hitIds == NULL || hitData == NULL || counts == NULL ||
		 ( *env )->GetArrayLength( env, counts ) < 3 )
	{
		mb3_throw_illegal_argument( env, "event arrays must not be null, counts takes 3" );
		return;
	}
	jint totals[3] = { events.beginCount, events.endCount, events.hitCount };
	( *env )->SetIntArrayRegion( env, counts, 0, 3, totals );

	int beginCount = b3MinInt( events.beginCount, ( *env )->GetArrayLength( env, begins ) / 2 );
	for ( int i = 0; i < beginCount; ++i )
	{
		jlong pair[2] = { mb3_pack_shape( events.beginEvents[i].shapeIdA ),
						  mb3_pack_shape( events.beginEvents[i].shapeIdB ) };
		( *env )->SetLongArrayRegion( env, begins, 2 * i, 2, pair );
	}
	int endCount = b3MinInt( events.endCount, ( *env )->GetArrayLength( env, ends ) / 2 );
	for ( int i = 0; i < endCount; ++i )
	{
		jlong pair[2] = { mb3_pack_shape( events.endEvents[i].shapeIdA ),
						  mb3_pack_shape( events.endEvents[i].shapeIdB ) };
		( *env )->SetLongArrayRegion( env, ends, 2 * i, 2, pair );
	}
	int hitCount = b3MinInt( events.hitCount, b3MinInt( ( *env )->GetArrayLength( env, hitIds ) / 4,
														( *env )->GetArrayLength( env, hitData ) / 11 ) );
	for ( int i = 0; i < hitCount; ++i )
	{
		const b3ContactHitEvent* e = events.hitEvents + i;
		int side = 0;
		if ( b3Shape_IsValid( e->shapeIdA ) && b3Shape_GetType( e->shapeIdA ) == b3_voxelShape )
			side = 1;
		else if ( b3Shape_IsValid( e->shapeIdB ) && b3Shape_GetType( e->shapeIdB ) == b3_voxelShape )
			side = 2;
		int cell[4] = { 0, 0, 0, 0 };
		if ( side != 0 )
		{
			mb3_hit_cell( e, side, cell );
		}
		jlong ids4[4] = { mb3_pack_shape( e->shapeIdA ), mb3_pack_shape( e->shapeIdB ),
						  (jlong)( e->userMaterialIdA & MB3_USER_MATERIAL_BITS ),
						  (jlong)( e->userMaterialIdB & MB3_USER_MATERIAL_BITS ) };
		double values[11] = { e->point.x,		e->point.y, e->point.z, e->normal.x, e->normal.y, e->normal.z,
							  e->approachSpeed, side,		cell[1],	cell[2],	 cell[3] };
		( *env )->SetLongArrayRegion( env, hitIds, 4 * i, 4, ids4 );
		( *env )->SetDoubleArrayRegion( env, hitData, 11 * i, 11, values );
	}
	mb3_check( env );
}

MB3_JNI( void, worldGetSensorEvents )( JNIEnv* env, jclass type, jint world, jlongArray begins, jlongArray ends,
									   jintArray counts )
{
	(void)type;
	b3SensorEvents events = b3World_GetSensorEvents( mb3_world( world ) );
	if ( mb3_check( env ) )
	{
		return;
	}
	if ( begins == NULL || ends == NULL || counts == NULL || ( *env )->GetArrayLength( env, counts ) < 2 )
	{
		mb3_throw_illegal_argument( env, "event arrays must not be null, counts takes 2" );
		return;
	}
	jint totals[2] = { events.beginCount, events.endCount };
	( *env )->SetIntArrayRegion( env, counts, 0, 2, totals );
	int beginCount = b3MinInt( events.beginCount, ( *env )->GetArrayLength( env, begins ) / 2 );
	for ( int i = 0; i < beginCount; ++i )
	{
		jlong pair[2] = { mb3_pack_shape( events.beginEvents[i].sensorShapeId ),
						  mb3_pack_shape( events.beginEvents[i].visitorShapeId ) };
		( *env )->SetLongArrayRegion( env, begins, 2 * i, 2, pair );
	}
	int endCount = b3MinInt( events.endCount, ( *env )->GetArrayLength( env, ends ) / 2 );
	for ( int i = 0; i < endCount; ++i )
	{
		jlong pair[2] = { mb3_pack_shape( events.endEvents[i].sensorShapeId ),
						  mb3_pack_shape( events.endEvents[i].visitorShapeId ) };
		( *env )->SetLongArrayRegion( env, ends, 2 * i, 2, pair );
	}
}

// ---- queries --------------------------------------------------------------------------------------------------------

b3QueryFilter mb3_query_filter( jlong categoryBits, jlong maskBits )
{
	b3QueryFilter filter = b3DefaultQueryFilter();
	filter.categoryBits = (uint64_t)categoryBits;
	filter.maskBits = (uint64_t)maskBits;
	return filter;
}

bool mb3_hit_arrays( JNIEnv* env, jdoubleArray doubles, jlongArray longs )
{
	if ( doubles == NULL || longs == NULL || ( *env )->GetArrayLength( env, doubles ) < MB3_HIT_DOUBLES ||
		 ( *env )->GetArrayLength( env, longs ) < MB3_HIT_LONGS )
	{
		mb3_throw_illegal_argument( env, "a hit takes 11 doubles and 2 longs" );
		return false;
	}
	return true;
}

void mb3_put_hit( JNIEnv* env, jdoubleArray doubles, jlongArray longs, int index, const double* d, const jlong* l )
{
	( *env )->SetDoubleArrayRegion( env, doubles, MB3_HIT_DOUBLES * index, MB3_HIT_DOUBLES, d );
	( *env )->SetLongArrayRegion( env, longs, MB3_HIT_LONGS * index, MB3_HIT_LONGS, l );
}

MB3_JNI( jboolean, worldCastRayClosest )( JNIEnv* env, jclass type, jint world, jdouble ox, jdouble oy, jdouble oz,
										  jfloat tx, jfloat ty, jfloat tz, jlong categoryBits, jlong maskBits,
										  jdoubleArray doubles, jlongArray longs )
{
	(void)type;
	if ( mb3_hit_arrays( env, doubles, longs ) == false )
	{
		return JNI_FALSE;
	}
	b3RayResult result = b3World_CastRayClosest( mb3_world( world ), mb3_pos( ox, oy, oz ), mb3_vec3( tx, ty, tz ),
												 mb3_query_filter( categoryBits, maskBits ) );
	if ( mb3_check( env ) || result.hit == false )
	{
		return JNI_FALSE;
	}
	double d[MB3_HIT_DOUBLES];
	jlong l[MB3_HIT_LONGS];
	mb3_hit( result.shapeId, result.point, result.normal, result.fraction, result.userMaterialId, result.triangleIndex,
			 d, l );
	mb3_put_hit( env, doubles, longs, 0, d, l );
	return mb3_check( env ) ? JNI_FALSE : JNI_TRUE;
}

// The hits a cast reports, held until they go to Java: the first MB3_HIT_ROOM on the caller's stack, the heap only past
// them and only as far as the hits go. ⚠️ Not sized by the Java arrays: those only grow, so after one cast through a
// crowd every later cast would allocate and free room for the crowd.
#define MB3_HIT_ROOM 16

typedef struct mb3_hit_room
{
	double doubles[MB3_HIT_DOUBLES * MB3_HIT_ROOM];
	jlong longs[MB3_HIT_LONGS * MB3_HIT_ROOM];
} mb3_hit_room;

typedef struct mb3_hit_list
{
	double* doubles;
	jlong* longs;
	mb3_hit_room* local;
	int room;
	int capacity;
	int count;
	bool closest;
	bool outOfMemory;
} mb3_hit_list;

// Twice the room, never more than the Java arrays take; false when the memory is not there.
static bool mb3_hit_list_grow( mb3_hit_list* list )
{
	int room = list->room < list->capacity / 2 ? 2 * list->room : list->capacity;
	double* d = malloc( MB3_HIT_DOUBLES * room * sizeof( double ) );
	jlong* l = malloc( MB3_HIT_LONGS * room * sizeof( jlong ) );
	if ( d == NULL || l == NULL )
	{
		free( d );
		free( l );
		return false;
	}
	memcpy( d, list->doubles, MB3_HIT_DOUBLES * list->count * sizeof( double ) );
	memcpy( l, list->longs, MB3_HIT_LONGS * list->count * sizeof( jlong ) );
	if ( list->doubles != list->local->doubles )
	{
		free( list->doubles );
		free( list->longs );
	}
	list->doubles = d;
	list->longs = l;
	list->room = room;
	return true;
}

static float mb3_hit_list_fcn( b3ShapeId shapeId, b3Pos point, b3Vec3 normal, float fraction, uint64_t userMaterialId,
							   int triangleIndex, int childIndex, void* context )
{
	(void)childIndex;
	mb3_hit_list* list = context;
	if ( list->closest )
	{
		// Box3D clips the cast at what this returns: every later report is closer
		mb3_hit( shapeId, point, normal, fraction, userMaterialId, triangleIndex, list->doubles, list->longs );
		list->count = 1;
		return fraction;
	}
	if ( list->count < list->capacity )
	{
		if ( list->count == list->room && mb3_hit_list_grow( list ) == false )
		{
			list->outOfMemory = true;
			return 0.0f; // ends the cast
		}
		mb3_hit( shapeId, point, normal, fraction, userMaterialId, triangleIndex,
				 list->doubles + MB3_HIT_DOUBLES * list->count, list->longs + MB3_HIT_LONGS * list->count );
	}
	list->count += 1;
	return 1.0f;
}

// Ready for a cast reporting every hit, up to capacity of them; false (and a pending exception) when the arrays are
// short.
static bool mb3_hit_list_open( JNIEnv* env, jdoubleArray doubles, jlongArray longs, jint capacity, mb3_hit_room* local,
							   mb3_hit_list* list )
{
	if ( capacity < 0 || doubles == NULL || longs == NULL ||
		 ( *env )->GetArrayLength( env, doubles ) < MB3_HIT_DOUBLES * capacity ||
		 ( *env )->GetArrayLength( env, longs ) < MB3_HIT_LONGS * capacity )
	{
		mb3_throw_illegal_argument( env, "hit arrays are shorter than capacity" );
		return false;
	}
	list->doubles = local->doubles;
	list->longs = local->longs;
	list->local = local;
	list->room = capacity < MB3_HIT_ROOM ? capacity : MB3_HIT_ROOM;
	list->capacity = capacity;
	list->count = 0;
	list->closest = false;
	list->outOfMemory = false;
	return true;
}

// The hits closest first into the Java arrays, when they all fitted; frees what the list grew. Returns how many there
// were.
static jint mb3_hit_list_close( JNIEnv* env, mb3_hit_list* list, jdoubleArray doubles, jlongArray longs )
{
	double* d = list->doubles;
	jlong* l = list->longs;
	int written = list->count < list->capacity ? list->count : list->capacity;
	if ( list->outOfMemory )
	{
		mb3_throw( env, "out of memory" );
	}
	if ( mb3_check( env ) == false && list->count <= list->capacity )
	{
		// closest first: insertion sort by fraction, a handful of hits
		for ( int i = 1; i < written; ++i )
		{
			double hd[MB3_HIT_DOUBLES];
			jlong hl[MB3_HIT_LONGS];
			memcpy( hd, d + MB3_HIT_DOUBLES * i, sizeof( hd ) );
			memcpy( hl, l + MB3_HIT_LONGS * i, sizeof( hl ) );
			int j = i - 1;
			while ( j >= 0 && d[MB3_HIT_DOUBLES * j + 3] > hd[3] )
			{
				memcpy( d + MB3_HIT_DOUBLES * ( j + 1 ), d + MB3_HIT_DOUBLES * j, sizeof( hd ) );
				memcpy( l + MB3_HIT_LONGS * ( j + 1 ), l + MB3_HIT_LONGS * j, sizeof( hl ) );
				j -= 1;
			}
			memcpy( d + MB3_HIT_DOUBLES * ( j + 1 ), hd, sizeof( hd ) );
			memcpy( l + MB3_HIT_LONGS * ( j + 1 ), hl, sizeof( hl ) );
		}
		if ( written > 0 )
		{
			( *env )->SetDoubleArrayRegion( env, doubles, 0, MB3_HIT_DOUBLES * written, d );
			( *env )->SetLongArrayRegion( env, longs, 0, MB3_HIT_LONGS * written, l );
		}
	}
	if ( d != list->local->doubles )
	{
		free( d );
		free( l );
	}
	return list->count;
}

// More hits than capacity writes nothing: Box3D reports them in no order, so the closest are not known until all are,
// and the caller asks again with room for all.
MB3_JNI( jint, worldCastRay )( JNIEnv* env, jclass type, jint world, jdouble ox, jdouble oy, jdouble oz, jfloat tx,
							   jfloat ty, jfloat tz, jlong categoryBits, jlong maskBits, jdoubleArray doubles,
							   jlongArray longs, jint capacity )
{
	(void)type;
	mb3_hit_room local;
	mb3_hit_list list;
	if ( mb3_hit_list_open( env, doubles, longs, capacity, &local, &list ) == false )
	{
		return 0;
	}
	b3World_CastRay( mb3_world( world ), mb3_pos( ox, oy, oz ), mb3_vec3( tx, ty, tz ),
					 mb3_query_filter( categoryBits, maskBits ), mb3_hit_list_fcn, &list );
	return mb3_hit_list_close( env, &list, doubles, longs );
}

bool mb3_proxy( JNIEnv* env, jfloatArray points, jint count, jfloat radius, b3Vec3* storage, b3ShapeProxy* proxy )
{
	if ( points == NULL || count < 1 || count > B3_MAX_SHAPE_CAST_POINTS ||
		 ( *env )->GetArrayLength( env, points ) < 3 * count )
	{
		mb3_throw_illegal_argument( env, "a query shape is 1 to B3_MAX_SHAPE_CAST_POINTS points, 3 floats each" );
		return false;
	}
	( *env )->GetFloatArrayRegion( env, points, 0, 3 * count, (jfloat*)storage ); // b3Vec3 is three floats
	*proxy = (b3ShapeProxy){ storage, count, radius };
	return true;
}

MB3_JNI( jboolean, worldCastShapeClosest )( JNIEnv* env, jclass type, jint world, jdouble ox, jdouble oy, jdouble oz,
											jfloatArray points, jint count, jfloat radius, jfloat tx, jfloat ty,
											jfloat tz, jlong categoryBits, jlong maskBits, jdoubleArray doubles,
											jlongArray longs )
{
	(void)type;
	b3Vec3 storage[B3_MAX_SHAPE_CAST_POINTS];
	b3ShapeProxy proxy;
	if ( mb3_proxy( env, points, count, radius, storage, &proxy ) == false ||
		 mb3_hit_arrays( env, doubles, longs ) == false )
	{
		return JNI_FALSE;
	}

	double d[MB3_HIT_DOUBLES];
	jlong l[MB3_HIT_LONGS];
	mb3_hit_list closest = { .doubles = d, .longs = l, .room = 1, .capacity = 1, .closest = true };
	b3World_CastShape( mb3_world( world ), mb3_pos( ox, oy, oz ), &proxy, mb3_vec3( tx, ty, tz ),
					   mb3_query_filter( categoryBits, maskBits ), mb3_hit_list_fcn, &closest );
	if ( mb3_check( env ) || closest.count == 0 )
	{
		return JNI_FALSE;
	}
	mb3_put_hit( env, doubles, longs, 0, d, l );
	return mb3_check( env ) ? JNI_FALSE : JNI_TRUE;
}

// Every shape a swept shape meets, closest first, one hit each; more than capacity writes nothing (as worldCastRay).
MB3_JNI( jint, worldCastShape )( JNIEnv* env, jclass type, jint world, jdouble ox, jdouble oy, jdouble oz,
								 jfloatArray points, jint count, jfloat radius, jfloat tx, jfloat ty, jfloat tz,
								 jlong categoryBits, jlong maskBits, jdoubleArray doubles, jlongArray longs,
								 jint capacity )
{
	(void)type;
	b3Vec3 storage[B3_MAX_SHAPE_CAST_POINTS];
	b3ShapeProxy proxy;
	mb3_hit_room local;
	mb3_hit_list list;
	if ( mb3_proxy( env, points, count, radius, storage, &proxy ) == false ||
		 mb3_hit_list_open( env, doubles, longs, capacity, &local, &list ) == false )
	{
		return 0;
	}
	b3World_CastShape( mb3_world( world ), mb3_pos( ox, oy, oz ), &proxy, mb3_vec3( tx, ty, tz ),
					   mb3_query_filter( categoryBits, maskBits ), mb3_hit_list_fcn, &list );
	return mb3_hit_list_close( env, &list, doubles, longs );
}

// The shapes an overlap reports, held as a cast's hits are (mb3_hit_list): a few on the stack, the heap only for more.
#define MB3_OVERLAP_ROOM 32

typedef struct mb3_overlaps
{
	jlong* ids;
	jlong* local;
	int room;
	int capacity;
	int count;
	bool outOfMemory;
} mb3_overlaps;

static void mb3_overlaps_open( mb3_overlaps* overlaps, jlong* local, int capacity )
{
	overlaps->ids = local;
	overlaps->local = local;
	overlaps->room = capacity < MB3_OVERLAP_ROOM ? capacity : MB3_OVERLAP_ROOM;
	overlaps->capacity = capacity;
	overlaps->count = 0;
	overlaps->outOfMemory = false;
}

static bool mb3_overlap_fcn( b3ShapeId shapeId, void* context )
{
	mb3_overlaps* overlaps = context;
	if ( overlaps->count < overlaps->capacity )
	{
		if ( overlaps->count == overlaps->room )
		{
			int room = overlaps->room < overlaps->capacity / 2 ? 2 * overlaps->room : overlaps->capacity;
			jlong* ids = malloc( room * sizeof( jlong ) );
			if ( ids == NULL )
			{
				overlaps->outOfMemory = true;
				return false;
			}
			memcpy( ids, overlaps->ids, overlaps->count * sizeof( jlong ) );
			if ( overlaps->ids != overlaps->local )
			{
				free( overlaps->ids );
			}
			overlaps->ids = ids;
			overlaps->room = room;
		}
		overlaps->ids[overlaps->count] = mb3_pack_shape( shapeId );
	}
	overlaps->count += 1;
	return true;
}

// As many as the Java array takes; frees what the list grew. Returns how many there were.
static jint mb3_overlaps_close( JNIEnv* env, mb3_overlaps* overlaps, jlongArray shapeIds )
{
	if ( overlaps->outOfMemory )
	{
		mb3_throw( env, "out of memory" );
	}
	int written = overlaps->count < overlaps->capacity ? overlaps->count : overlaps->capacity;
	if ( mb3_check( env ) == false && written > 0 )
	{
		( *env )->SetLongArrayRegion( env, shapeIds, 0, written, overlaps->ids );
	}
	if ( overlaps->ids != overlaps->local )
	{
		free( overlaps->ids );
	}
	return overlaps->count;
}

MB3_JNI( jint, worldOverlapShape )( JNIEnv* env, jclass type, jint world, jdouble ox, jdouble oy, jdouble oz,
									jfloatArray points, jint count, jfloat radius, jlong categoryBits, jlong maskBits,
									jlongArray shapeIds )
{
	(void)type;
	b3Vec3 storage[B3_MAX_SHAPE_CAST_POINTS];
	b3ShapeProxy proxy;
	if ( mb3_proxy( env, points, count, radius, storage, &proxy ) == false )
	{
		return 0;
	}
	if ( shapeIds == NULL )
	{
		mb3_throw_illegal_argument( env, "output long[] is null" );
		return 0;
	}

	jlong local[MB3_OVERLAP_ROOM];
	mb3_overlaps overlaps;
	mb3_overlaps_open( &overlaps, local, ( *env )->GetArrayLength( env, shapeIds ) );
	b3World_OverlapShape( mb3_world( world ), mb3_pos( ox, oy, oz ), &proxy, mb3_query_filter( categoryBits, maskBits ),
						  mb3_overlap_fcn, &overlaps );
	return mb3_overlaps_close( env, &overlaps, shapeIds );
}

// The broad phase keeps float bounds, so the box is rounded outwards.
MB3_JNI( jint, worldOverlapAABB )( JNIEnv* env, jclass type, jint world, jdouble minX, jdouble minY, jdouble minZ,
								   jdouble maxX, jdouble maxY, jdouble maxZ, jlong categoryBits, jlong maskBits,
								   jlongArray shapeIds )
{
	(void)type;
	if ( shapeIds == NULL )
	{
		mb3_throw_illegal_argument( env, "output long[] is null" );
		return 0;
	}
	b3AABB box = {
		{ nextafterf( (float)minX, -INFINITY ), nextafterf( (float)minY, -INFINITY ),
		  nextafterf( (float)minZ, -INFINITY ) },
		{ nextafterf( (float)maxX, INFINITY ), nextafterf( (float)maxY, INFINITY ),
		  nextafterf( (float)maxZ, INFINITY ) },
	};

	// asked once for every Minecraft entity move near a body
	jlong local[MB3_OVERLAP_ROOM];
	mb3_overlaps overlaps;
	mb3_overlaps_open( &overlaps, local, ( *env )->GetArrayLength( env, shapeIds ) );
	b3World_OverlapAABB( mb3_world( world ), box, mb3_query_filter( categoryBits, maskBits ), mb3_overlap_fcn,
						 &overlaps );
	return mb3_overlaps_close( env, &overlaps, shapeIds );
}

// The planes a mover meets, held as a cast's hits are (mb3_hit_list): a few on the stack, the heap only for more.
#define MB3_PLANE_ROOM 16

typedef struct mb3_plane_room
{
	float planes[7 * MB3_PLANE_ROOM];
	jlong shapeIds[MB3_PLANE_ROOM];
	jint cells[4 * MB3_PLANE_ROOM];
} mb3_plane_room;

typedef struct mb3_mover_planes
{
	float* planes;
	jlong* shapeIds;
	jint* cells;
	mb3_plane_room* local;
	int room;
	int capacity;
	int count;
	bool outOfMemory;
} mb3_mover_planes;

static bool mb3_mover_planes_grow( mb3_mover_planes* out )
{
	int room = out->room < out->capacity / 2 ? 2 * out->room : out->capacity;
	float* planes = malloc( 7 * room * sizeof( float ) );
	jlong* shapeIds = malloc( room * sizeof( jlong ) );
	jint* cells = malloc( 4 * room * sizeof( jint ) );
	if ( planes == NULL || shapeIds == NULL || cells == NULL )
	{
		free( planes );
		free( shapeIds );
		free( cells );
		return false;
	}
	memcpy( planes, out->planes, 7 * out->count * sizeof( float ) );
	memcpy( shapeIds, out->shapeIds, out->count * sizeof( jlong ) );
	memcpy( cells, out->cells, 4 * out->count * sizeof( jint ) );
	if ( out->planes != out->local->planes )
	{
		free( out->planes );
		free( out->shapeIds );
		free( out->cells );
	}
	out->planes = planes;
	out->shapeIds = shapeIds;
	out->cells = cells;
	out->room = room;
	return true;
}

static bool mb3_mover_plane_fcn( b3ShapeId shapeId, const b3PlaneResult* planes, int planeCount, void* context )
{
	mb3_mover_planes* out = context;
	for ( int i = 0; i < planeCount && out->count < out->capacity; ++i )
	{
		if ( out->count == out->room && mb3_mover_planes_grow( out ) == false )
		{
			out->outOfMemory = true;
			return false;
		}
		float* p = out->planes + 7 * out->count;
		p[0] = planes[i].plane.normal.x;
		p[1] = planes[i].plane.normal.y;
		p[2] = planes[i].plane.normal.z;
		p[3] = planes[i].plane.offset;
		p[4] = planes[i].point.x;
		p[5] = planes[i].point.y;
		p[6] = planes[i].point.z;
		out->shapeIds[out->count] = mb3_pack_shape( shapeId );
		int cell[4];
		mb3_cell( shapeId, planes[i].triangleIndex, cell );
		for ( int k = 0; k < 4; ++k )
			out->cells[4 * out->count + k] = cell[k];
		out->count += 1;
	}
	return out->count < out->capacity;
}

MB3_JNI( jint, worldCollideMover )( JNIEnv* env, jclass type, jint world, jdouble ox, jdouble oy, jdouble oz, jfloat x1,
									jfloat y1, jfloat z1, jfloat x2, jfloat y2, jfloat z2, jfloat radius,
									jlong categoryBits, jlong maskBits, jfloatArray planes, jlongArray shapeIds,
									jintArray cells )
{
	(void)type;
	if ( planes == NULL || shapeIds == NULL || cells == NULL )
	{
		mb3_throw_illegal_argument( env, "output arrays must not be null" );
		return 0;
	}

	jsize capacity = ( *env )->GetArrayLength( env, shapeIds );
	if ( ( *env )->GetArrayLength( env, cells ) < 4 * capacity ||
		 ( *env )->GetArrayLength( env, planes ) < 7 * capacity )
	{
		mb3_throw_illegal_argument( env, "7 floats and 4 cell ints per plane" );
		return 0;
	}
	if ( capacity == 0 )
	{
		return 0;
	}

	b3Capsule mover = { mb3_vec3( x1, y1, z1 ), mb3_vec3( x2, y2, z2 ), radius };
	mb3_plane_room local;
	mb3_mover_planes out = {
		local.planes,
		local.shapeIds,
		local.cells,
		&local,
		capacity < MB3_PLANE_ROOM ? capacity : MB3_PLANE_ROOM,
		capacity,
		0,
		false,
	};
	b3World_CollideMover( mb3_world( world ), mb3_pos( ox, oy, oz ), &mover, mb3_query_filter( categoryBits, maskBits ),
						  mb3_mover_plane_fcn, &out );
	if ( out.outOfMemory )
	{
		mb3_throw( env, "out of memory" );
	}
	if ( mb3_check( env ) == false && out.count > 0 )
	{
		( *env )->SetFloatArrayRegion( env, planes, 0, 7 * out.count, out.planes );
		( *env )->SetLongArrayRegion( env, shapeIds, 0, out.count, out.shapeIds );
		( *env )->SetIntArrayRegion( env, cells, 0, 4 * out.count, out.cells );
	}
	if ( out.planes != local.planes )
	{
		free( out.planes );
		free( out.shapeIds );
		free( out.cells );
	}
	return out.count;
}

MB3_JNI( jfloat, worldGetCounters )( JNIEnv* env, jclass type, jint world, jintArray out )
{
	(void)type;
	b3Counters counters = b3World_GetCounters( mb3_world( world ) );
	b3Profile profile = b3World_GetProfile( mb3_world( world ) );
	if ( mb3_check( env ) )
	{
		return 0;
	}
	if ( out == NULL || ( *env )->GetArrayLength( env, out ) < 6 )
	{
		mb3_throw_illegal_argument( env, "output int[] is null or too short" );
		return 0;
	}
	jint values[6] = { counters.bodyCount,	counters.shapeCount,  counters.contactCount,
					   counters.jointCount, counters.islandCount, counters.awakeContactCount };
	( *env )->SetIntArrayRegion( env, out, 0, 6, values );
	return profile.step;
}
