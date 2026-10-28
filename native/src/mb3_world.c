// MineBox3D JNI bridge: worlds.

#include "mb3.h"

#include <stdlib.h>

MB3_JNI( jint, createWorld )( JNIEnv* env, jclass type, jobject definition )
{
	(void)type;
	b3WorldDef def;
	if ( mb3_world_def_from_java( env, definition, &def ) == false )
	{
		return 0;
	}
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

MB3_JNI( void, worldEnableSleeping )( JNIEnv* env, jclass type, jint world, jboolean flag )
{
	(void)type;
	b3World_EnableSleeping( mb3_world( world ), flag );
	mb3_check( env );
}

MB3_JNI( void, worldEnableContinuous )( JNIEnv* env, jclass type, jint world, jboolean flag )
{
	(void)type;
	b3World_EnableContinuous( mb3_world( world ), flag );
	mb3_check( env );
}

// The bodies that moved during the last step: ids into `ids`, 7 doubles each into `transforms`
// ([px, py, pz, qx, qy, qz, qw]), 1 if it fell asleep this step into `fellAsleep`.
// Returns the total number of events; when that is more than the arrays hold, only the first
// `capacity` are written and the caller asks again with bigger arrays (the events stay valid until
// the next step).
MB3_JNI( jint, worldGetBodyMoveEvents )( JNIEnv* env, jclass type, jint world, jlongArray ids, jdoubleArray transforms,
										 jbooleanArray fellAsleep, jint capacity )
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
	if ( ids == NULL || ( *env )->GetArrayLength( env, ids ) < count || transforms == NULL ||
		 ( *env )->GetArrayLength( env, transforms ) < 7 * count || fellAsleep == NULL ||
		 ( *env )->GetArrayLength( env, fellAsleep ) < count )
	{
		mb3_throw_illegal_argument( env, "event arrays are shorter than capacity" );
		return 0;
	}
	jlong* idOut = ( *env )->GetPrimitiveArrayCritical( env, ids, NULL );
	jdouble* transformOut = ( *env )->GetPrimitiveArrayCritical( env, transforms, NULL );
	jboolean* asleepOut = ( *env )->GetPrimitiveArrayCritical( env, fellAsleep, NULL );
	if ( idOut != NULL && transformOut != NULL && asleepOut != NULL )
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

// Closest hit along origin + translation. Returns the shape hit (0 for none); when there is one,
// pointAndFraction gets [px, py, pz, fraction, triangleIndex] and normal [nx, ny, nz].
MB3_JNI( jlong, worldCastRayClosest )( JNIEnv* env, jclass type, jint world, jdouble ox, jdouble oy, jdouble oz, jfloat tx,
									   jfloat ty, jfloat tz, jlong categoryBits, jlong maskBits, jdoubleArray pointAndFraction,
									   jfloatArray normal )
{
	(void)type;
	b3QueryFilter filter = b3DefaultQueryFilter();
	filter.categoryBits = (uint64_t)categoryBits;
	filter.maskBits = (uint64_t)maskBits;
	b3RayResult result = b3World_CastRayClosest( mb3_world( world ), mb3_pos( ox, oy, oz ), mb3_vec3( tx, ty, tz ), filter );
	if ( mb3_check( env ) || result.hit == false )
	{
		return 0;
	}
	// The triangle index (a voxel feature key on terrain) is exact as a double
	double p[5] = { result.point.x, result.point.y, result.point.z, result.fraction, result.triangleIndex };
	if ( mb3_put_doubles( env, pointAndFraction, 0, p, 5 ) == false || mb3_put_vec3( env, normal, 0, result.normal ) == false )
	{
		return 0;
	}
	return mb3_pack_shape( result.shapeId );
}

// A query shape: up to B3_MAX_SHAPE_CAST_POINTS points [x, y, z, ...] relative to the query origin, and a radius.
// The points are copied into caller stack storage of that size.
static bool mb3_proxy( JNIEnv* env, jfloatArray points, jint count, jfloat radius, b3Vec3* storage, b3ShapeProxy* proxy )
{
	if ( points == NULL || count < 1 || count > B3_MAX_SHAPE_CAST_POINTS || ( *env )->GetArrayLength( env, points ) < 3 * count )
	{
		mb3_throw_illegal_argument( env, "a query shape is 1 to B3_MAX_SHAPE_CAST_POINTS points, 3 floats each" );
		return false;
	}
	( *env )->GetFloatArrayRegion( env, points, 0, 3 * count, (jfloat*)storage ); // b3Vec3 is three floats
	*proxy = (b3ShapeProxy){ storage, count, radius };
	return true;
}

static b3QueryFilter mb3_query_filter( jlong categoryBits, jlong maskBits )
{
	b3QueryFilter filter = b3DefaultQueryFilter();
	filter.categoryBits = (uint64_t)categoryBits;
	filter.maskBits = (uint64_t)maskBits;
	return filter;
}

typedef struct mb3_closest_cast
{
	b3ShapeId shapeId;
	b3Pos point;
	b3Vec3 normal;
	float fraction;
	int triangleIndex;
	bool hit;
} mb3_closest_cast;

static float mb3_closest_cast_fcn( b3ShapeId shapeId, b3Pos point, b3Vec3 normal, float fraction, uint64_t userMaterialId,
								   int triangleIndex, int childIndex, void* context )
{
	(void)userMaterialId;
	(void)childIndex;
	mb3_closest_cast* closest = context;
	closest->shapeId = shapeId;
	closest->point = point;
	closest->normal = normal;
	closest->fraction = fraction;
	closest->triangleIndex = triangleIndex;
	closest->hit = true;
	return fraction;
}

// Closest hit of a shape swept along translation from origin. Unlike the closest ray, a shape already
// touching something at the start is reported, at fraction 0. Outputs as worldCastRayClosest.
MB3_JNI( jlong, worldCastShapeClosest )( JNIEnv* env, jclass type, jint world, jdouble ox, jdouble oy, jdouble oz,
										 jfloatArray points, jint count, jfloat radius, jfloat tx, jfloat ty, jfloat tz,
										 jlong categoryBits, jlong maskBits, jdoubleArray pointAndFraction, jfloatArray normal )
{
	(void)type;
	b3Vec3 storage[B3_MAX_SHAPE_CAST_POINTS];
	b3ShapeProxy proxy;
	if ( mb3_proxy( env, points, count, radius, storage, &proxy ) == false )
	{
		return 0;
	}

	mb3_closest_cast closest = { 0 };
	b3World_CastShape( mb3_world( world ), mb3_pos( ox, oy, oz ), &proxy, mb3_vec3( tx, ty, tz ),
					   mb3_query_filter( categoryBits, maskBits ), mb3_closest_cast_fcn, &closest );
	if ( mb3_check( env ) || closest.hit == false )
	{
		return 0;
	}
	double p[5] = { closest.point.x, closest.point.y, closest.point.z, closest.fraction, closest.triangleIndex };
	if ( mb3_put_doubles( env, pointAndFraction, 0, p, 5 ) == false || mb3_put_vec3( env, normal, 0, closest.normal ) == false )
	{
		return 0;
	}
	return mb3_pack_shape( closest.shapeId );
}

typedef struct mb3_overlaps
{
	jlong* ids;
	int capacity;
	int count;
} mb3_overlaps;

static bool mb3_overlap_fcn( b3ShapeId shapeId, void* context )
{
	mb3_overlaps* overlaps = context;
	if ( overlaps->count < overlaps->capacity )
	{
		overlaps->ids[overlaps->count] = mb3_pack_shape( shapeId );
	}
	overlaps->count += 1;
	return true;
}

// Every shape overlapping the query shape; ids into shapeIds, returns how many there are (which may be more
// than fit).
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

	jsize capacity = ( *env )->GetArrayLength( env, shapeIds );
	jlong* ids = capacity > 0 ? malloc( capacity * sizeof( jlong ) ) : NULL;
	if ( capacity > 0 && ids == NULL )
	{
		mb3_throw( env, "out of memory" );
		return 0;
	}
	mb3_overlaps overlaps = { ids, capacity, 0 };
	b3World_OverlapShape( mb3_world( world ), mb3_pos( ox, oy, oz ), &proxy, mb3_query_filter( categoryBits, maskBits ),
						  mb3_overlap_fcn, &overlaps );
	if ( mb3_check( env ) == false && capacity > 0 )
	{
		( *env )->SetLongArrayRegion( env, shapeIds, 0, overlaps.count < capacity ? overlaps.count : capacity, ids );
	}
	free( ids );
	return overlaps.count;
}

typedef struct mb3_mover_planes
{
	float* planes;
	jlong* shapeIds;
	jint* triangleIndices;
	int capacity;
	int count;
} mb3_mover_planes;

static bool mb3_mover_plane_fcn( b3ShapeId shapeId, const b3PlaneResult* planes, int planeCount, void* context )
{
	mb3_mover_planes* out = context;
	for ( int i = 0; i < planeCount && out->count < out->capacity; ++i )
	{
		float* p = out->planes + 7 * out->count;
		p[0] = planes[i].plane.normal.x;
		p[1] = planes[i].plane.normal.y;
		p[2] = planes[i].plane.normal.z;
		p[3] = planes[i].plane.offset;
		p[4] = planes[i].point.x;
		p[5] = planes[i].point.y;
		p[6] = planes[i].point.z;
		out->shapeIds[out->count] = mb3_pack_shape( shapeId );
		out->triangleIndices[out->count] = planes[i].triangleIndex;
		out->count += 1;
	}
	return out->count < out->capacity;
}

// The collision planes around a capsule mover (Box3D's character mover), relative to origin. Per plane:
// planes gets [nx, ny, nz, offset, px, py, pz], shapeIds the shape and triangleIndices its triangle index.
// Returns the count, at most the capacity of shapeIds.
MB3_JNI( jint, worldCollideMover )( JNIEnv* env, jclass type, jint world, jdouble ox, jdouble oy, jdouble oz, jfloat x1,
									jfloat y1, jfloat z1, jfloat x2, jfloat y2, jfloat z2, jfloat radius, jlong categoryBits,
									jlong maskBits, jfloatArray planes, jlongArray shapeIds, jintArray triangleIndices )
{
	(void)type;
	if ( planes == NULL || shapeIds == NULL || triangleIndices == NULL )
	{
		mb3_throw_illegal_argument( env, "output arrays must not be null" );
		return 0;
	}

	jsize capacity = ( *env )->GetArrayLength( env, shapeIds );
	if ( ( *env )->GetArrayLength( env, triangleIndices ) < capacity || ( *env )->GetArrayLength( env, planes ) < 7 * capacity )
	{
		mb3_throw_illegal_argument( env, "7 floats and 1 triangle index per plane" );
		return 0;
	}
	if ( capacity == 0 )
	{
		return 0;
	}

	float* planeValues = malloc( 7 * capacity * sizeof( float ) );
	jlong* ids = malloc( capacity * sizeof( jlong ) );
	jint* triangles = malloc( capacity * sizeof( jint ) );
	if ( planeValues == NULL || ids == NULL || triangles == NULL )
	{
		free( planeValues );
		free( ids );
		free( triangles );
		mb3_throw( env, "out of memory" );
		return 0;
	}

	b3Capsule mover = { mb3_vec3( x1, y1, z1 ), mb3_vec3( x2, y2, z2 ), radius };
	mb3_mover_planes out = { planeValues, ids, triangles, capacity, 0 };
	b3World_CollideMover( mb3_world( world ), mb3_pos( ox, oy, oz ), &mover, mb3_query_filter( categoryBits, maskBits ),
						  mb3_mover_plane_fcn, &out );
	if ( mb3_check( env ) == false && out.count > 0 )
	{
		( *env )->SetFloatArrayRegion( env, planes, 0, 7 * out.count, planeValues );
		( *env )->SetLongArrayRegion( env, shapeIds, 0, out.count, ids );
		( *env )->SetIntArrayRegion( env, triangleIndices, 0, out.count, triangles );
	}
	free( planeValues );
	free( ids );
	free( triangles );
	return out.count;
}

// [bodyCount, shapeCount, contactCount, jointCount, islandCount, awakeContactCount] and the step
// time in milliseconds.
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
	jint values[6] = { counters.bodyCount,	 counters.shapeCount,  counters.contactCount,
					   counters.jointCount, counters.islandCount, counters.awakeContactCount };
	( *env )->SetIntArrayRegion( env, out, 0, 6, values );
	return profile.step;
}
