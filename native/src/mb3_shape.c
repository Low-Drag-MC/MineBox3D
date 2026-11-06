// MineBox3D JNI bridge: shapes.

#include "mb3.h"

#include <float.h>
#include <stdlib.h>

// What a shape creation hands back to Java: its id, or 0 with an exception pending.
static jlong mb3_shape_created( JNIEnv* env, b3ShapeId id )
{
	// a failed check does not stop Box3D making the shape: Java never gets its id, so it would be an orphan
	if ( mb3_assert_pending() && B3_IS_NON_NULL( id ) )
	{
		b3DestroyShape( id, true );
	}
	if ( mb3_check( env ) )
	{
		return 0;
	}
	if ( B3_IS_NULL( id ) )
	{
		mb3_throw( env, "Box3D could not create the shape" );
		return 0;
	}
	return mb3_pack_shape( id );
}

// Reads the definition, creates the shape with `call`, and releases what the definition copied.
#define MB3_CREATE_SHAPE( env, definition, call )                                                                              \
	do                                                                                                                         \
	{                                                                                                                          \
		b3ShapeDef def;                                                                                                        \
		char name[MB3_NAME_CAPACITY];                                                                                          \
		if ( mb3_shape_def_from_java( env, definition, &def, name, MB3_NAME_CAPACITY ) == false )                              \
		{                                                                                                                      \
			return 0;                                                                                                          \
		}                                                                                                                      \
		b3ShapeId id = call;                                                                                                   \
		mb3_shape_def_release( &def );                                                                                         \
		return mb3_shape_created( env, id );                                                                                   \
	}                                                                                                                          \
	while ( 0 )

// A hull around `count` points [x, y, z, ...], at most B3_MAX_HULL_VERTICES vertices; NULL with an exception pending
// when the points make none. The caller destroys it: Box3D copies a hull into the world it is used in.
static b3HullData* mb3_hull_from_java( JNIEnv* env, jfloatArray points, jint count )
{
	if ( points == NULL || count < 4 || ( *env )->GetArrayLength( env, points ) < 3 * count )
	{
		mb3_throw_illegal_argument( env, "a hull needs at least 4 points, 3 floats each" );
		return NULL;
	}
	float* p = malloc( 3 * (size_t)count * sizeof( float ) );
	if ( p == NULL )
	{
		mb3_throw( env, "out of memory" );
		return NULL;
	}
	( *env )->GetFloatArrayRegion( env, points, 0, 3 * count, p );
	b3HullData* hull = b3CreateHull( (const b3Vec3*)p, count, B3_MAX_HULL_VERTICES ); // b3Vec3 is three floats
	free( p );
	if ( mb3_check( env ) )
	{
		if ( hull != NULL )
			b3DestroyHull( hull );
		return NULL;
	}
	if ( hull == NULL )
	{
		mb3_throw_illegal_argument( env, "the points make no hull (all in one plane, or too close together)" );
	}
	return hull;
}

MB3_JNI( jint, computeHull )( JNIEnv* env, jclass type, jfloatArray points, jint count, jfloatArray out )
{
	(void)type;
	if ( out == NULL || ( *env )->GetArrayLength( env, out ) < 3 * B3_MAX_HULL_VERTICES )
	{
		mb3_throw_illegal_argument( env, "the output takes 3 * B3_MAX_HULL_VERTICES floats" );
		return 0;
	}
	b3HullData* hull = mb3_hull_from_java( env, points, count );
	if ( hull == NULL )
	{
		return 0;
	}
	int n = hull->vertexCount;
	( *env )->SetFloatArrayRegion( env, out, 0, 3 * n, (const float*)b3GetHullPoints( hull ) );
	b3DestroyHull( hull );
	return n;
}

MB3_JNI( jint, getMaxHullVertices )( JNIEnv* env, jclass type )
{
	(void)env;
	(void)type;
	return B3_MAX_HULL_VERTICES;
}

MB3_JNI( jlong, createHullShape )( JNIEnv* env, jclass type, jlong body, jobject definition, jfloatArray points, jint count )
{
	(void)type;
	b3HullData* hull = mb3_hull_from_java( env, points, count );
	if ( hull == NULL )
	{
		return 0;
	}
	b3ShapeDef def;
	char name[MB3_NAME_CAPACITY];
	if ( mb3_shape_def_from_java( env, definition, &def, name, MB3_NAME_CAPACITY ) == false )
	{
		b3DestroyHull( hull );
		return 0;
	}
	b3ShapeId id = b3CreateHullShape( mb3_body( body ), &def, hull );
	mb3_shape_def_release( &def );
	b3DestroyHull( hull );
	return mb3_shape_created( env, id );
}

// ---- height fields --------------------------------------------------------------------------------------------------
// ⚠️ Like a mesh, Box3D keeps only a pointer to a height field: the Java HeightFieldData owns it and counts its shapes.

MB3_JNI( jlong, createHeightField )( JNIEnv* env, jclass type, jfloatArray heights, jint countX, jint countZ,
									 jbyteArray materials, jfloat sx, jfloat sy, jfloat sz, jfloat minHeight, jfloat maxHeight,
									 jboolean clockwise )
{
	(void)type;
	// as 64-bit: the int product wraps, and Box3D sizes its cells and triangles from it
	int64_t points = (int64_t)countX * countZ;
	if ( countX < 2 || countZ < 2 || points > ( 1 << 24 ) || heights == NULL || ( *env )->GetArrayLength( env, heights ) < points )
	{
		mb3_throw_illegal_argument( env, "a height field is 2 x 2 to 4096 x 4096 points, countX * countZ heights" );
		return 0;
	}
	if ( materials != NULL && ( *env )->GetArrayLength( env, materials ) < ( countX - 1 ) * ( countZ - 1 ) )
	{
		mb3_throw_illegal_argument( env, "one material index per cell" );
		return 0;
	}
	// Box3D reads everything during the call and keeps none of it ("No pointers are held to this data")
	// the second pinned only once the first was: a failed pin leaves an exception pending, and no JNI call may follow
	jfloat* h = ( *env )->GetFloatArrayElements( env, heights, NULL );
	jbyte* m = h != NULL && materials != NULL ? ( *env )->GetByteArrayElements( env, materials, NULL ) : NULL;
	if ( h == NULL || ( materials != NULL && m == NULL ) )
	{
		if ( h != NULL )
			( *env )->ReleaseFloatArrayElements( env, heights, h, JNI_ABORT );
		return 0;
	}
	b3HeightFieldDef def = { 0 };
	def.heights = h;
	def.materialIndices = (uint8_t*)m;
	def.scale = mb3_vec3( sx, sy, sz );
	def.countX = countX;
	def.countZ = countZ;
	def.globalMinimumHeight = minHeight;
	def.globalMaximumHeight = maxHeight;
	def.clockwiseWinding = clockwise;
	b3HeightFieldData* field = b3CreateHeightField( &def );
	( *env )->ReleaseFloatArrayElements( env, heights, h, JNI_ABORT );
	if ( m != NULL )
		( *env )->ReleaseByteArrayElements( env, materials, m, JNI_ABORT );
	if ( mb3_check( env ) )
	{
		if ( field != NULL )
			b3DestroyHeightField( field );
		return 0;
	}
	if ( field == NULL )
	{
		mb3_throw( env, "Box3D could not build the height field" );
		return 0;
	}
	return (jlong)(intptr_t)field;
}

MB3_JNI( void, destroyHeightField )( JNIEnv* env, jclass type, jlong field )
{
	(void)type;
	if ( field != 0 )
	{
		b3DestroyHeightField( (b3HeightFieldData*)(intptr_t)field );
		mb3_check( env );
	}
}

MB3_JNI( jlong, createHeightFieldShape )( JNIEnv* env, jclass type, jlong body, jobject definition, jlong field )
{
	(void)type;
	if ( field == 0 )
	{
		mb3_throw_illegal_argument( env, "height field is null" );
		return 0;
	}
	MB3_CREATE_SHAPE( env, definition, b3CreateHeightFieldShape( mb3_body( body ), &def, (const b3HeightFieldData*)(intptr_t)field ) );
}

// ---- changing the geometry in place ---------------------------------------------------------------------------------
// Box3D keeps the shape, its id and its filter, rebuilds its contacts and wakes the body. The body's mass is not
// recomputed (bodyApplyMassFromShapes does that).

MB3_JNI( void, shapeSetSphere )( JNIEnv* env, jclass type, jlong shape, jfloat cx, jfloat cy, jfloat cz, jfloat radius )
{
	(void)type;
	b3Sphere sphere = { mb3_vec3( cx, cy, cz ), radius };
	b3Shape_SetSphere( mb3_shape( shape ), &sphere );
	mb3_check( env );
}

MB3_JNI( void, shapeSetCapsule )( JNIEnv* env, jclass type, jlong shape, jfloat x1, jfloat y1, jfloat z1, jfloat x2, jfloat y2,
								  jfloat z2, jfloat radius )
{
	(void)type;
	b3Capsule capsule = { mb3_vec3( x1, y1, z1 ), mb3_vec3( x2, y2, z2 ), radius };
	b3Shape_SetCapsule( mb3_shape( shape ), &capsule );
	mb3_check( env );
}

MB3_JNI( void, shapeSetBox )( JNIEnv* env, jclass type, jlong shape, jfloat hx, jfloat hy, jfloat hz, jfloat cx, jfloat cy,
							  jfloat cz, jfloat qx, jfloat qy, jfloat qz, jfloat qw )
{
	(void)type;
	b3Transform transform = { mb3_vec3( cx, cy, cz ), mb3_quat( qx, qy, qz, qw ) };
	b3BoxHull box = b3MakeTransformedBoxHull( hx, hy, hz, transform );
	b3Shape_SetHull( mb3_shape( shape ), &box.base );
	mb3_check( env );
}

MB3_JNI( void, shapeSetHull )( JNIEnv* env, jclass type, jlong shape, jfloatArray points, jint count )
{
	(void)type;
	b3HullData* hull = mb3_hull_from_java( env, points, count );
	if ( hull == NULL )
	{
		return;
	}
	b3Shape_SetHull( mb3_shape( shape ), hull );
	b3DestroyHull( hull );
	mb3_check( env );
}

MB3_JNI( void, shapeSetMesh )( JNIEnv* env, jclass type, jlong shape, jlong mesh, jfloat sx, jfloat sy, jfloat sz )
{
	(void)type;
	if ( mesh == 0 )
	{
		mb3_throw_illegal_argument( env, "mesh is null" );
		return;
	}
	b3Shape_SetMesh( mb3_shape( shape ), (const b3MeshData*)(intptr_t)mesh, mb3_vec3( sx, sy, sz ) );
	mb3_check( env );
}

// ---- material, density, filter, events ------------------------------------------------------------------------------

MB3_JNI( void, shapeSetDensity )( JNIEnv* env, jclass type, jlong shape, jfloat density, jboolean updateBodyMass )
{
	(void)type;
	b3Shape_SetDensity( mb3_shape( shape ), density, updateBodyMass );
	mb3_check( env );
}

MB3_JNI( jfloat, shapeGetDensity )( JNIEnv* env, jclass type, jlong shape )
{
	(void)type;
	float density = b3Shape_GetDensity( mb3_shape( shape ) );
	mb3_check( env );
	return density;
}

MB3_JNI( void, shapeSetSurfaceMaterial )( JNIEnv* env, jclass type, jlong shape, jobject material )
{
	(void)type;
	b3SurfaceMaterial m;
	if ( mb3_material_from_java( env, material, &m ) )
	{
		b3Shape_SetSurfaceMaterial( mb3_shape( shape ), m );
		mb3_check( env );
	}
}

MB3_JNI( void, shapeGetSurfaceMaterial )( JNIEnv* env, jclass type, jlong shape, jobject target )
{
	(void)type;
	b3SurfaceMaterial m = b3Shape_GetSurfaceMaterial( mb3_shape( shape ) );
	if ( mb3_check( env ) == false )
	{
		mb3_material_to_java( env, target, &m );
	}
}

// The per-triangle materials of a mesh or height field shape (the definition's `materials`).
MB3_JNI( jint, shapeGetMeshMaterialCount )( JNIEnv* env, jclass type, jlong shape )
{
	(void)type;
	int count = b3Shape_GetMeshMaterialCount( mb3_shape( shape ) );
	mb3_check( env );
	return count;
}

MB3_JNI( void, shapeSetMeshMaterial )( JNIEnv* env, jclass type, jlong shape, jobject material, jint index )
{
	(void)type;
	b3SurfaceMaterial m;
	if ( mb3_material_from_java( env, material, &m ) )
	{
		b3Shape_SetMeshMaterial( mb3_shape( shape ), m, index );
		mb3_check( env );
	}
}

MB3_JNI( void, shapeGetMeshMaterial )( JNIEnv* env, jclass type, jlong shape, jint index, jobject target )
{
	(void)type;
	b3SurfaceMaterial m = b3Shape_GetMeshSurfaceMaterial( mb3_shape( shape ), index );
	if ( mb3_check( env ) == false )
	{
		mb3_material_to_java( env, target, &m );
	}
}

MB3_JNI( void, shapeGetFilter )( JNIEnv* env, jclass type, jlong shape, jobject target )
{
	(void)type;
	b3Filter filter = b3Shape_GetFilter( mb3_shape( shape ) );
	if ( mb3_check( env ) == false )
	{
		mb3_filter_to_java( env, target, filter );
	}
}

// ⚠️ The numbers are Shape's in Java. Whether a shape is a sensor is fixed when it is made (read only).
enum mb3_shape_flag
{
	MB3_SF_SENSOR_EVENTS = 0,
	MB3_SF_CONTACT_EVENTS = 1,
	MB3_SF_HIT_EVENTS = 2,
	MB3_SF_SENSOR = 3,
};

MB3_JNI( void, shapeSetFlag )( JNIEnv* env, jclass type, jlong shape, jint flag, jboolean value )
{
	(void)type;
	b3ShapeId id = mb3_shape( shape );
	switch ( flag )
	{
		case MB3_SF_SENSOR_EVENTS: b3Shape_EnableSensorEvents( id, value ); break;
		case MB3_SF_CONTACT_EVENTS: b3Shape_EnableContactEvents( id, value ); break;
		case MB3_SF_HIT_EVENTS: b3Shape_EnableHitEvents( id, value ); break;
		default: mb3_throw_illegal_argument( env, "not a shape switch that can be set" ); return;
	}
	mb3_check( env );
}

MB3_JNI( jboolean, shapeGetFlag )( JNIEnv* env, jclass type, jlong shape, jint flag )
{
	(void)type;
	b3ShapeId id = mb3_shape( shape );
	bool value;
	switch ( flag )
	{
		case MB3_SF_SENSOR_EVENTS: value = b3Shape_AreSensorEventsEnabled( id ); break;
		case MB3_SF_CONTACT_EVENTS: value = b3Shape_AreContactEventsEnabled( id ); break;
		case MB3_SF_HIT_EVENTS: value = b3Shape_AreHitEventsEnabled( id ); break;
		case MB3_SF_SENSOR: value = b3Shape_IsSensor( id ); break;
		default: mb3_throw_illegal_argument( env, "not a shape switch" ); return JNI_FALSE;
	}
	mb3_check( env );
	return value ? JNI_TRUE : JNI_FALSE;
}

MB3_JNI( jint, shapeGetSensorOverlaps )( JNIEnv* env, jclass type, jlong shape, jlongArray out )
{
	(void)type;
	if ( out == NULL )
	{
		mb3_throw_illegal_argument( env, "output long[] is null" );
		return 0;
	}
	b3ShapeId id = mb3_shape( shape );
	int capacity = b3Shape_GetSensorCapacity( id );
	if ( mb3_check( env ) || capacity <= 0 )
	{
		return 0;
	}
	b3ShapeId* visitors = malloc( (size_t)capacity * sizeof( b3ShapeId ) );
	if ( visitors == NULL )
	{
		mb3_throw( env, "out of memory" );
		return 0;
	}
	int count = b3Shape_GetSensorData( id, visitors, capacity );
	int written = b3MinInt( count, ( *env )->GetArrayLength( env, out ) );
	for ( int i = 0; i < written; ++i )
	{
		jlong visitor = mb3_pack_shape( visitors[i] );
		( *env )->SetLongArrayRegion( env, out, i, 1, &visitor );
	}
	free( visitors );
	mb3_check( env );
	return count;
}

// ---- closest point --------------------------------------------------------------------------------------------------

float mb3_closest_point( b3ShapeId shape, b3WorldTransform bodyTransform, b3Pos target, b3Pos* closest )
{
	*closest = target;
	b3ShapeProxy proxy;
	b3Sphere sphere;
	b3Capsule capsule;
	switch ( b3Shape_GetType( shape ) )
	{
		case b3_sphereShape:
			sphere = b3Shape_GetSphere( shape );
			proxy = ( b3ShapeProxy ){ &sphere.center, 1, sphere.radius };
			break;
		case b3_capsuleShape:
			// center1 and center2 sit next to each other: the two points of the proxy
			capsule = b3Shape_GetCapsule( shape );
			proxy = ( b3ShapeProxy ){ &capsule.center1, 2, capsule.radius };
			break;
		case b3_hullShape:
		{
			const b3HullData* hull = b3Shape_GetHull( shape );
			proxy = ( b3ShapeProxy ){ b3GetHullPoints( hull ), hull->vertexCount, 0.0f };
			break;
		}
		default:
			return FLT_MAX;
	}

	// Frame A is the body's, placed relative to the target; B is the target, at that origin
	b3Vec3 origin = b3Vec3_zero;
	b3Transform body = b3ToRelativeTransform( bodyTransform, target );
	b3DistanceInput input;
	input.proxyA = proxy;
	input.proxyB = ( b3ShapeProxy ){ &origin, 1, 0.0f };
	input.transform = b3InvMulTransforms( body, b3Transform_identity );
	input.useRadii = true;
	b3SimplexCache cache = { 0 };
	b3DistanceOutput output = b3ShapeDistance( &input, &cache, NULL, 0 );
	b3Vec3 offset = b3TransformPoint( body, output.pointA );
	*closest = ( b3Pos ){ target.x + offset.x, target.y + offset.y, target.z + offset.z };
	return output.distance;
}

MB3_JNI( jfloat, shapeGetClosestPoint )( JNIEnv* env, jclass type, jlong shape, jdouble x, jdouble y, jdouble z, jdoubleArray out )
{
	(void)type;
	b3ShapeId id = mb3_shape( shape );
	b3WorldTransform transform = b3Body_GetTransform( b3Shape_GetBody( id ) );
	if ( mb3_check( env ) )
	{
		return FLT_MAX;
	}
	b3Pos closest;
	float distance = mb3_closest_point( id, transform, mb3_pos( x, y, z ), &closest );
	if ( mb3_check( env ) == false )
	{
		mb3_put_pos( env, out, 0, closest );
	}
	return distance;
}

MB3_JNI( jboolean, shapeCastShape )( JNIEnv* env, jclass type, jlong shape, jdouble ox, jdouble oy, jdouble oz, jfloatArray points,
									 jint count, jfloat radius, jfloat tx, jfloat ty, jfloat tz, jboolean canEncroach,
									 jdoubleArray doubles, jlongArray longs )
{
	(void)type;
	b3Vec3 storage[B3_MAX_SHAPE_CAST_POINTS];
	b3ShapeProxy proxy;
	if ( mb3_proxy( env, points, count, radius, storage, &proxy ) == false || mb3_hit_arrays( env, doubles, longs ) == false )
	{
		return JNI_FALSE;
	}
	b3ShapeId id = mb3_shape( shape );
	b3WorldCastOutput output = b3Shape_CastShape( id, mb3_pos( ox, oy, oz ), &proxy, mb3_vec3( tx, ty, tz ), canEncroach );
	if ( mb3_check( env ) || output.hit == false )
	{
		return JNI_FALSE;
	}
	uint64_t material = b3Shape_GetUserMaterialId( id, output.childIndex, output.triangleIndex );
	double d[MB3_HIT_DOUBLES];
	jlong l[MB3_HIT_LONGS];
	mb3_hit( id, output.point, output.normal, output.fraction, material, output.triangleIndex, d, l );
	mb3_put_hit( env, doubles, longs, 0, d, l );
	return mb3_check( env ) ? JNI_FALSE : JNI_TRUE;
}

MB3_JNI( jlong, createSphereShape )( JNIEnv* env, jclass type, jlong body, jobject definition, jfloat cx, jfloat cy, jfloat cz,
									jfloat radius )
{
	(void)type;
	b3Sphere sphere = { mb3_vec3( cx, cy, cz ), radius };
	MB3_CREATE_SHAPE( env, definition, b3CreateSphereShape( mb3_body( body ), &def, &sphere ) );
}

MB3_JNI( jlong, createCapsuleShape )( JNIEnv* env, jclass type, jlong body, jobject definition, jfloat x1, jfloat y1,
									 jfloat z1, jfloat x2, jfloat y2, jfloat z2, jfloat radius )
{
	(void)type;
	b3Capsule capsule = { mb3_vec3( x1, y1, z1 ), mb3_vec3( x2, y2, z2 ), radius };
	MB3_CREATE_SHAPE( env, definition, b3CreateCapsuleShape( mb3_body( body ), &def, &capsule ) );
}

// A box is a hull. Box3D copies hulls into its own per-world database, so the stack copy is enough.
MB3_JNI( jlong, createBoxShape )( JNIEnv* env, jclass type, jlong body, jobject definition, jfloat hx, jfloat hy, jfloat hz,
								 jfloat cx, jfloat cy, jfloat cz, jfloat qx, jfloat qy, jfloat qz, jfloat qw )
{
	(void)type;
	b3Transform transform = { mb3_vec3( cx, cy, cz ), mb3_quat( qx, qy, qz, qw ) };
	b3BoxHull box = b3MakeTransformedBoxHull( hx, hy, hz, transform );
	MB3_CREATE_SHAPE( env, definition, b3CreateHullShape( mb3_body( body ), &def, &box.base ) );
}

MB3_JNI( void, destroyShape )( JNIEnv* env, jclass type, jlong shape, jboolean updateBodyMass )
{
	(void)type;
	b3DestroyShape( mb3_shape( shape ), updateBodyMass );
	mb3_check( env );
}

MB3_JNI( jboolean, shapeIsValid )( JNIEnv* env, jclass type, jlong shape )
{
	(void)env;
	(void)type;
	return b3Shape_IsValid( mb3_shape( shape ) ) ? JNI_TRUE : JNI_FALSE;
}

MB3_JNI( jlong, shapeGetBody )( JNIEnv* env, jclass type, jlong shape )
{
	(void)type;
	b3BodyId body = b3Shape_GetBody( mb3_shape( shape ) );
	if ( mb3_check( env ) )
	{
		return 0;
	}
	return mb3_pack_body( body );
}

MB3_JNI( void, shapeSetFilter )( JNIEnv* env, jclass type, jlong shape, jlong categoryBits, jlong maskBits, jint groupIndex,
								 jboolean invokeContacts )
{
	(void)type;
	b3Filter filter = { (uint64_t)categoryBits, (uint64_t)maskBits, groupIndex };
	b3Shape_SetFilter( mb3_shape( shape ), filter, invokeContacts );
	mb3_check( env );
}

MB3_JNI( void, shapeSetFriction )( JNIEnv* env, jclass type, jlong shape, jfloat friction )
{
	(void)type;
	b3Shape_SetFriction( mb3_shape( shape ), friction );
	mb3_check( env );
}

MB3_JNI( void, shapeSetRestitution )( JNIEnv* env, jclass type, jlong shape, jfloat restitution )
{
	(void)type;
	b3Shape_SetRestitution( mb3_shape( shape ), restitution );
	mb3_check( env );
}

MB3_JNI( void, shapeGetAABB )( JNIEnv* env, jclass type, jlong shape, jfloatArray out )
{
	(void)type;
	b3AABB box = b3Shape_GetAABB( mb3_shape( shape ) );
	if ( mb3_check( env ) == false )
	{
		float values[6] = { box.lowerBound.x, box.lowerBound.y, box.lowerBound.z,
							box.upperBound.x, box.upperBound.y, box.upperBound.z };
		mb3_put_floats( env, out, 0, values, 6 );
	}
}
