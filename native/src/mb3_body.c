// MineBox3D JNI bridge: bodies.

#include "mb3.h"

#include <float.h>
#include <stdlib.h>

#define MB3_CONTACT_FLOATS 10

// Seen from the body (layout in B3.bodyGetContactPoints). normalVelocity is negative approaching, the same either way
// round (so no flip), and sampled by Box3D only for a pair with hit events or restitution, else 0. The anchor is in
// world orientation.
MB3_JNI( jint, bodyGetContactPoints )( JNIEnv* env, jclass type, jlong body, jfloatArray points, jlongArray otherShapes,
									   jintArray cells )
{
	(void)type;
	if ( points == NULL || otherShapes == NULL || cells == NULL )
	{
		mb3_throw_illegal_argument( env, "output arrays must not be null" );
		return 0;
	}

	b3BodyId id = mb3_body( body );
	int contactCapacity = b3Body_GetContactCapacity( id );
	if ( mb3_check( env ) || contactCapacity == 0 )
	{
		return 0;
	}
	b3ContactData* contacts = malloc( contactCapacity * sizeof( b3ContactData ) );
	if ( contacts == NULL )
	{
		mb3_throw( env, "out of memory" );
		return 0;
	}
	int contactCount = b3Body_GetContactData( id, contacts, contactCapacity );

	jsize capacity = ( *env )->GetArrayLength( env, otherShapes );
	if ( ( *env )->GetArrayLength( env, cells ) / 4 < capacity )
		capacity = ( *env )->GetArrayLength( env, cells ) / 4;
	if ( ( *env )->GetArrayLength( env, points ) / MB3_CONTACT_FLOATS < capacity )
		capacity = ( *env )->GetArrayLength( env, points ) / MB3_CONTACT_FLOATS;

	int count = 0;
	for ( int i = 0; i < contactCount; ++i )
	{
		b3BodyId bodyA = b3Shape_GetBody( contacts[i].shapeIdA );
		bool isA = B3_ID_EQUALS( bodyA, id );
		b3ShapeId other = isA ? contacts[i].shapeIdB : contacts[i].shapeIdA;
		for ( int m = 0; m < contacts[i].manifoldCount; ++m )
		{
			const b3Manifold* manifold = contacts[i].manifolds + m;
			// The manifold normal points from A to B
			float sign = isA ? -1.0f : 1.0f;
			for ( int p = 0; p < manifold->pointCount; ++p, ++count )
			{
				if ( count >= capacity )
				{
					continue;
				}
				const b3ManifoldPoint* mp = manifold->points + p;
				b3Vec3 anchor = isA ? mp->anchorA : mp->anchorB;
				float values[MB3_CONTACT_FLOATS] = { sign * manifold->normal.x,
													 sign * manifold->normal.y,
													 sign * manifold->normal.z,
													 mp->separation,
													 mp->normalImpulse,
													 mp->totalNormalImpulse,
													 mp->normalVelocity,
													 anchor.x,
													 anchor.y,
													 anchor.z };
				jlong otherId = mb3_pack_shape( other );
				int cell[4];
				mb3_cell( other, mp->triangleIndex, cell );
				jint cellOut[4] = { cell[0], cell[1], cell[2], cell[3] };
				( *env )->SetFloatArrayRegion( env, points, MB3_CONTACT_FLOATS * count, MB3_CONTACT_FLOATS, values );
				( *env )->SetLongArrayRegion( env, otherShapes, count, 1, &otherId );
				( *env )->SetIntArrayRegion( env, cells, 4 * count, 4, cellOut );
			}
		}
	}
	free( contacts );
	mb3_check( env );
	return count;
}

MB3_JNI( jlong, createBody )( JNIEnv* env, jclass type, jint world, jobject definition )
{
	(void)type;
	b3BodyDef def;
	char name[MB3_NAME_CAPACITY];
	if ( mb3_body_def_from_java( env, definition, &def, name, MB3_NAME_CAPACITY ) == false )
	{
		return 0;
	}
	b3BodyId id = b3CreateBody( mb3_world( world ), &def );
	// A failed check does not stop Box3D making the body: Java never gets its id, so it would be an orphan
	if ( mb3_assert_pending() && B3_IS_NON_NULL( id ) )
	{
		b3DestroyBody( id );
	}
	if ( mb3_check( env ) )
	{
		return 0;
	}
	return mb3_pack_body( id );
}

MB3_JNI( void, destroyBody )( JNIEnv* env, jclass type, jlong body )
{
	(void)type;
	b3DestroyBody( mb3_body( body ) );
	mb3_check( env );
}

MB3_JNI( jboolean, bodyIsValid )( JNIEnv* env, jclass type, jlong body )
{
	(void)env;
	(void)type;
	return b3Body_IsValid( mb3_body( body ) ) ? JNI_TRUE : JNI_FALSE;
}

// [px, py, pz, qx, qy, qz, qw] at out[offset]
static bool mb3_put_transform( JNIEnv* env, jdoubleArray out, int offset, b3WorldTransform t )
{
	double values[7] = { t.p.x, t.p.y, t.p.z, t.q.v.x, t.q.v.y, t.q.v.z, t.q.s };
	return mb3_put_doubles( env, out, offset, values, 7 );
}

MB3_JNI( void, bodyGetTransform )( JNIEnv* env, jclass type, jlong body, jdoubleArray out )
{
	(void)type;
	b3WorldTransform transform = b3Body_GetTransform( mb3_body( body ) );
	if ( mb3_check( env ) == false )
	{
		mb3_put_transform( env, out, 0, transform );
	}
}

MB3_JNI( void, bodiesGetTransforms )( JNIEnv* env, jclass type, jlongArray bodies, jint count, jdoubleArray out )
{
	(void)type;
	if ( bodies == NULL || count < 0 || ( *env )->GetArrayLength( env, bodies ) < count )
	{
		mb3_throw_illegal_argument( env, "body id array is null or shorter than count" );
		return;
	}
	if ( out == NULL || ( *env )->GetArrayLength( env, out ) < 7 * count )
	{
		mb3_throw_illegal_argument( env, "output double[] is null or shorter than 7 * count" );
		return;
	}
	jlong* ids = ( *env )->GetPrimitiveArrayCritical( env, bodies, NULL );
	jdouble* values = ( *env )->GetPrimitiveArrayCritical( env, out, NULL );
	if ( ids == NULL || values == NULL )
	{
		if ( values != NULL )
		{
			( *env )->ReleasePrimitiveArrayCritical( env, out, values, JNI_ABORT );
		}
		if ( ids != NULL )
		{
			( *env )->ReleasePrimitiveArrayCritical( env, bodies, ids, JNI_ABORT );
		}
		return;
	}
	for ( int i = 0; i < count; ++i )
	{
		b3WorldTransform t = b3Body_GetTransform( mb3_body( ids[i] ) );
		jdouble* v = values + 7 * i;
		v[0] = t.p.x;
		v[1] = t.p.y;
		v[2] = t.p.z;
		v[3] = t.q.v.x;
		v[4] = t.q.v.y;
		v[5] = t.q.v.z;
		v[6] = t.q.s;
	}
	( *env )->ReleasePrimitiveArrayCritical( env, out, values, 0 );
	( *env )->ReleasePrimitiveArrayCritical( env, bodies, ids, JNI_ABORT );
	mb3_check( env );
}

MB3_JNI( void, bodySetTransform )( JNIEnv* env, jclass type, jlong body, jdouble px, jdouble py, jdouble pz, jfloat qx,
								  jfloat qy, jfloat qz, jfloat qw )
{
	(void)type;
	b3Body_SetTransform( mb3_body( body ), mb3_pos( px, py, pz ), mb3_quat( qx, qy, qz, qw ) );
	mb3_check( env );
}

MB3_JNI( void, bodyGetLinearVelocity )( JNIEnv* env, jclass type, jlong body, jfloatArray out )
{
	(void)type;
	b3Vec3 v = b3Body_GetLinearVelocity( mb3_body( body ) );
	if ( mb3_check( env ) == false )
	{
		mb3_put_vec3( env, out, 0, v );
	}
}

MB3_JNI( void, bodySetLinearVelocity )( JNIEnv* env, jclass type, jlong body, jfloat x, jfloat y, jfloat z )
{
	(void)type;
	b3Body_SetLinearVelocity( mb3_body( body ), mb3_vec3( x, y, z ) );
	mb3_check( env );
}

MB3_JNI( void, bodyGetAngularVelocity )( JNIEnv* env, jclass type, jlong body, jfloatArray out )
{
	(void)type;
	b3Vec3 v = b3Body_GetAngularVelocity( mb3_body( body ) );
	if ( mb3_check( env ) == false )
	{
		mb3_put_vec3( env, out, 0, v );
	}
}

MB3_JNI( void, bodySetAngularVelocity )( JNIEnv* env, jclass type, jlong body, jfloat x, jfloat y, jfloat z )
{
	(void)type;
	b3Body_SetAngularVelocity( mb3_body( body ), mb3_vec3( x, y, z ) );
	mb3_check( env );
}

MB3_JNI( jboolean, bodyIsAwake )( JNIEnv* env, jclass type, jlong body )
{
	(void)type;
	bool awake = b3Body_IsAwake( mb3_body( body ) );
	mb3_check( env );
	return awake ? JNI_TRUE : JNI_FALSE;
}

MB3_JNI( jfloat, bodyGetMass )( JNIEnv* env, jclass type, jlong body )
{
	(void)type;
	float mass = b3Body_GetMass( mb3_body( body ) );
	mb3_check( env );
	return mass;
}

MB3_JNI( jint, bodyGetType )( JNIEnv* env, jclass type, jlong body )
{
	(void)type;
	b3BodyType bodyType = b3Body_GetType( mb3_body( body ) );
	mb3_check( env );
	return (jint)bodyType;
}

MB3_JNI( void, bodySetType )( JNIEnv* env, jclass type, jlong body, jint bodyType )
{
	(void)type;
	if ( bodyType < 0 || bodyType >= b3_bodyTypeCount )
	{
		mb3_throw_illegal_argument( env, "not a body type" );
		return;
	}
	b3Body_SetType( mb3_body( body ), (b3BodyType)bodyType );
	mb3_check( env );
}

MB3_JNI( void, bodySetTargetTransform )( JNIEnv* env, jclass type, jlong body, jdouble px, jdouble py, jdouble pz,
										 jfloat qx, jfloat qy, jfloat qz, jfloat qw, jfloat timeStep, jboolean wake )
{
	(void)type;
	b3WorldTransform target = { mb3_pos( px, py, pz ), mb3_quat( qx, qy, qz, qw ) };
	b3Body_SetTargetTransform( mb3_body( body ), target, timeStep, wake );
	mb3_check( env );
}

// bodySetTargetTransform for count bodies, 7 doubles each as bodiesGetTransforms
MB3_JNI( void, bodiesSetTargetTransforms )( JNIEnv* env, jclass type, jlongArray bodies, jint count, jdoubleArray transforms,
										   jfloat timeStep, jboolean wake )
{
	(void)type;
	if ( bodies == NULL || count < 0 || ( *env )->GetArrayLength( env, bodies ) < count )
	{
		mb3_throw_illegal_argument( env, "body id array is null or shorter than count" );
		return;
	}
	if ( transforms == NULL || ( *env )->GetArrayLength( env, transforms ) < 7 * count )
	{
		mb3_throw_illegal_argument( env, "transform double[] is null or shorter than 7 * count" );
		return;
	}
	jlong* ids = ( *env )->GetLongArrayElements( env, bodies, NULL );
	jdouble* values = ids == NULL ? NULL : ( *env )->GetDoubleArrayElements( env, transforms, NULL );
	if ( values != NULL )
	{
		for ( int i = 0; i < count; ++i )
		{
			const jdouble* v = values + 7 * i;
			// Java checked it has a direction; unit as Body.setTargetTransform makes it
			b3WorldTransform target = {
				mb3_pos( v[0], v[1], v[2] ),
				b3NormalizeQuat( mb3_quat( (float)v[3], (float)v[4], (float)v[5], (float)v[6] ) ),
			};
			b3Body_SetTargetTransform( mb3_body( ids[i] ), target, timeStep, wake );
		}
		( *env )->ReleaseDoubleArrayElements( env, transforms, values, JNI_ABORT );
	}
	if ( ids != NULL )
	{
		( *env )->ReleaseLongArrayElements( env, bodies, ids, JNI_ABORT );
	}
	mb3_check( env );
}

MB3_JNI( void, bodySetEnabled )( JNIEnv* env, jclass type, jlong body, jboolean enabled )
{
	(void)type;
	if ( enabled )
	{
		b3Body_Enable( mb3_body( body ) );
	}
	else
	{
		b3Body_Disable( mb3_body( body ) );
	}
	mb3_check( env );
}

MB3_JNI( jboolean, bodyIsEnabled )( JNIEnv* env, jclass type, jlong body )
{
	(void)type;
	bool enabled = b3Body_IsEnabled( mb3_body( body ) );
	mb3_check( env );
	return enabled ? JNI_TRUE : JNI_FALSE;
}

MB3_JNI( void, bodySetAwake )( JNIEnv* env, jclass type, jlong body, jboolean awake )
{
	(void)type;
	b3Body_SetAwake( mb3_body( body ), awake );
	mb3_check( env );
}

MB3_JNI( void, bodyComputeAABB )( JNIEnv* env, jclass type, jlong body, jfloatArray out )
{
	(void)type;
	b3AABB box = b3Body_ComputeAABB( mb3_body( body ) );
	if ( mb3_check( env ) == false )
	{
		float values[6] = { box.lowerBound.x, box.lowerBound.y, box.lowerBound.z,
							box.upperBound.x, box.upperBound.y, box.upperBound.z };
		mb3_put_floats( env, out, 0, values, 6 );
	}
}

MB3_JNI( void, bodyApplyLinearImpulse )( JNIEnv* env, jclass type, jlong body, jfloat ix, jfloat iy, jfloat iz, jdouble px,
										 jdouble py, jdouble pz, jboolean wake )
{
	(void)type;
	b3Body_ApplyLinearImpulse( mb3_body( body ), mb3_vec3( ix, iy, iz ), mb3_pos( px, py, pz ), wake );
	mb3_check( env );
}

MB3_JNI( void, bodyApplyLinearImpulseToCenter )( JNIEnv* env, jclass type, jlong body, jfloat ix, jfloat iy, jfloat iz,
												 jboolean wake )
{
	(void)type;
	b3Body_ApplyLinearImpulseToCenter( mb3_body( body ), mb3_vec3( ix, iy, iz ), wake );
	mb3_check( env );
}

MB3_JNI( void, bodyApplyAngularImpulse )( JNIEnv* env, jclass type, jlong body, jfloat ix, jfloat iy, jfloat iz, jboolean wake )
{
	(void)type;
	b3Body_ApplyAngularImpulse( mb3_body( body ), mb3_vec3( ix, iy, iz ), wake );
	mb3_check( env );
}

MB3_JNI( void, bodyApplyForce )( JNIEnv* env, jclass type, jlong body, jfloat fx, jfloat fy, jfloat fz, jdouble px,
								 jdouble py, jdouble pz, jboolean wake )
{
	(void)type;
	b3Body_ApplyForce( mb3_body( body ), mb3_vec3( fx, fy, fz ), mb3_pos( px, py, pz ), wake );
	mb3_check( env );
}

MB3_JNI( void, bodyApplyForceToCenter )( JNIEnv* env, jclass type, jlong body, jfloat fx, jfloat fy, jfloat fz, jboolean wake )
{
	(void)type;
	b3Body_ApplyForceToCenter( mb3_body( body ), mb3_vec3( fx, fy, fz ), wake );
	mb3_check( env );
}

MB3_JNI( void, bodyApplyTorque )( JNIEnv* env, jclass type, jlong body, jfloat tx, jfloat ty, jfloat tz, jboolean wake )
{
	(void)type;
	b3Body_ApplyTorque( mb3_body( body ), mb3_vec3( tx, ty, tz ), wake );
	mb3_check( env );
}

// ---- settings -------------------------------------------------------------------------------------------------------
// ⚠️ The numbers are Body's in Java.

enum mb3_body_param
{
	MB3_BP_LINEAR_DAMPING = 0,
	MB3_BP_ANGULAR_DAMPING = 1,
	MB3_BP_GRAVITY_SCALE = 2,
	MB3_BP_SLEEP_THRESHOLD = 3,
	MB3_BP_SAFETY_FACTOR = 4,
};

enum mb3_body_flag
{
	MB3_BF_BULLET = 0,
	MB3_BF_SLEEP_ENABLED = 1,
	MB3_BF_FAST_ROTATION = 2,
	MB3_BF_CONTACT_RECYCLING = 3,
	MB3_BF_FIXED_ROTATION = 4,
};

MB3_JNI( void, bodySetFloat )( JNIEnv* env, jclass type, jlong body, jint param, jfloat value )
{
	(void)type;
	b3BodyId id = mb3_body( body );
	switch ( param )
	{
		case MB3_BP_LINEAR_DAMPING: b3Body_SetLinearDamping( id, value ); break;
		case MB3_BP_ANGULAR_DAMPING: b3Body_SetAngularDamping( id, value ); break;
		case MB3_BP_GRAVITY_SCALE: b3Body_SetGravityScale( id, value ); break;
		case MB3_BP_SLEEP_THRESHOLD: b3Body_SetSleepThreshold( id, value ); break;
		case MB3_BP_SAFETY_FACTOR: b3Body_SetSafetyFactor( id, value ); break;
		default: mb3_throw_illegal_argument( env, "not a body setting" ); return;
	}
	mb3_check( env );
}

MB3_JNI( jfloat, bodyGetFloat )( JNIEnv* env, jclass type, jlong body, jint param )
{
	(void)type;
	b3BodyId id = mb3_body( body );
	float value;
	switch ( param )
	{
		case MB3_BP_LINEAR_DAMPING: value = b3Body_GetLinearDamping( id ); break;
		case MB3_BP_ANGULAR_DAMPING: value = b3Body_GetAngularDamping( id ); break;
		case MB3_BP_GRAVITY_SCALE: value = b3Body_GetGravityScale( id ); break;
		case MB3_BP_SLEEP_THRESHOLD: value = b3Body_GetSleepThreshold( id ); break;
		case MB3_BP_SAFETY_FACTOR: value = b3Body_GetSafetyFactor( id ); break;
		default: mb3_throw_illegal_argument( env, "not a body setting" ); return 0;
	}
	mb3_check( env );
	return value;
}

MB3_JNI( void, bodySetFlag )( JNIEnv* env, jclass type, jlong body, jint flag, jboolean value )
{
	(void)type;
	b3BodyId id = mb3_body( body );
	switch ( flag )
	{
		case MB3_BF_BULLET: b3Body_SetBullet( id, value ); break;
		case MB3_BF_SLEEP_ENABLED: b3Body_EnableSleep( id, value ); break;
		case MB3_BF_FAST_ROTATION: b3Body_AllowFastRotation( id, value ); break;
		case MB3_BF_CONTACT_RECYCLING: b3Body_EnableContactRecycling( id, value ); break;
		case MB3_BF_FIXED_ROTATION: b3Body_SetFixedRotation( id, value ); break;
		default: mb3_throw_illegal_argument( env, "not a body switch" ); return;
	}
	mb3_check( env );
}

MB3_JNI( jboolean, bodyGetFlag )( JNIEnv* env, jclass type, jlong body, jint flag )
{
	(void)type;
	b3BodyId id = mb3_body( body );
	bool value;
	switch ( flag )
	{
		case MB3_BF_BULLET: value = b3Body_IsBullet( id ); break;
		case MB3_BF_SLEEP_ENABLED: value = b3Body_IsSleepEnabled( id ); break;
		case MB3_BF_FAST_ROTATION: value = b3Body_IsFastRotationAllowed( id ); break;
		case MB3_BF_CONTACT_RECYCLING: value = b3Body_IsContactRecyclingEnabled( id ); break;
		case MB3_BF_FIXED_ROTATION: value = b3Body_IsFixedRotation( id ); break;
		default: mb3_throw_illegal_argument( env, "not a body switch" ); return JNI_FALSE;
	}
	mb3_check( env );
	return value ? JNI_TRUE : JNI_FALSE;
}

// Hit events for every shape of the body; Box3D keeps no body-wide switch to read back.
MB3_JNI( void, bodyEnableHitEvents )( JNIEnv* env, jclass type, jlong body, jboolean enable )
{
	(void)type;
	b3Body_EnableHitEvents( mb3_body( body ), enable );
	mb3_check( env );
}

// ---- mass -----------------------------------------------------------------------------------------------------------

MB3_JNI( void, bodyGetMassData )( JNIEnv* env, jclass type, jlong body, jfloatArray out )
{
	(void)type;
	b3MassData data = b3Body_GetMassData( mb3_body( body ) );
	if ( mb3_check( env ) == false )
	{
		const b3Matrix3* m = &data.inertia;
		float values[13] = { data.mass,	 data.center.x, data.center.y, data.center.z, m->cx.x, m->cx.y, m->cx.z,
							 m->cy.x,	 m->cy.y,		m->cy.z,	   m->cz.x,		  m->cz.y, m->cz.z };
		mb3_put_floats( env, out, 0, values, 13 );
	}
}

MB3_JNI( void, bodySetMassData )( JNIEnv* env, jclass type, jlong body, jfloatArray values )
{
	(void)type;
	if ( values == NULL || ( *env )->GetArrayLength( env, values ) < 13 )
	{
		mb3_throw_illegal_argument( env, "mass data takes 13 floats" );
		return;
	}
	float v[13];
	( *env )->GetFloatArrayRegion( env, values, 0, 13, v );
	b3MassData data;
	data.mass = v[0];
	data.center = mb3_vec3( v[1], v[2], v[3] );
	data.inertia.cx = mb3_vec3( v[4], v[5], v[6] );
	data.inertia.cy = mb3_vec3( v[7], v[8], v[9] );
	data.inertia.cz = mb3_vec3( v[10], v[11], v[12] );
	b3Body_SetMassData( mb3_body( body ), data );
	mb3_check( env );
}

MB3_JNI( void, bodyApplyMassFromShapes )( JNIEnv* env, jclass type, jlong body )
{
	(void)type;
	b3Body_ApplyMassFromShapes( mb3_body( body ) );
	mb3_check( env );
}

MB3_JNI( void, bodyGetWorldCenter )( JNIEnv* env, jclass type, jlong body, jdoubleArray out )
{
	(void)type;
	b3Pos center = b3Body_GetWorldCenter( mb3_body( body ) );
	if ( mb3_check( env ) == false )
	{
		mb3_put_pos( env, out, 0, center );
	}
}

MB3_JNI( void, bodyGetWorldPointVelocity )( JNIEnv* env, jclass type, jlong body, jdouble x, jdouble y, jdouble z,
											jfloatArray out )
{
	(void)type;
	b3Vec3 v = b3Body_GetWorldPointVelocity( mb3_body( body ), mb3_pos( x, y, z ) );
	if ( mb3_check( env ) == false )
	{
		mb3_put_vec3( env, out, 0, v );
	}
}

// ---- queries against this one body ----------------------------------------------------------------------------------

static bool mb3_body_hit( JNIEnv* env, b3BodyCastResult result, jdoubleArray doubles, jlongArray longs )
{
	if ( mb3_check( env ) || result.hit == false )
	{
		return false;
	}
	double d[MB3_HIT_DOUBLES];
	jlong l[MB3_HIT_LONGS];
	mb3_hit( result.shapeId, result.point, result.normal, result.fraction, result.userMaterialId, result.triangleIndex, d, l );
	mb3_put_hit( env, doubles, longs, 0, d, l );
	return mb3_check( env ) == false;
}

MB3_JNI( jboolean, bodyCastRay )( JNIEnv* env, jclass type, jlong body, jdouble ox, jdouble oy, jdouble oz, jfloat tx, jfloat ty,
								  jfloat tz, jlong categoryBits, jlong maskBits, jdoubleArray doubles, jlongArray longs )
{
	(void)type;
	if ( mb3_hit_arrays( env, doubles, longs ) == false )
	{
		return JNI_FALSE;
	}
	b3BodyId id = mb3_body( body );
	b3WorldTransform transform = b3Body_GetTransform( id );
	if ( mb3_check( env ) )
	{
		return JNI_FALSE;
	}
	b3BodyCastResult result = b3Body_CastRay( id, mb3_pos( ox, oy, oz ), mb3_vec3( tx, ty, tz ),
											  mb3_query_filter( categoryBits, maskBits ), 1.0f, transform );
	return mb3_body_hit( env, result, doubles, longs ) ? JNI_TRUE : JNI_FALSE;
}

// The closest hit of a query shape swept against this body; touching at the start hits at fraction 0, as the world's.
MB3_JNI( jboolean, bodyCastShape )( JNIEnv* env, jclass type, jlong body, jdouble ox, jdouble oy, jdouble oz, jfloatArray points,
									jint count, jfloat radius, jfloat tx, jfloat ty, jfloat tz, jlong categoryBits, jlong maskBits,
									jdoubleArray doubles, jlongArray longs )
{
	(void)type;
	b3Vec3 storage[B3_MAX_SHAPE_CAST_POINTS];
	b3ShapeProxy proxy;
	if ( mb3_proxy( env, points, count, radius, storage, &proxy ) == false || mb3_hit_arrays( env, doubles, longs ) == false )
	{
		return JNI_FALSE;
	}
	b3BodyId id = mb3_body( body );
	b3WorldTransform transform = b3Body_GetTransform( id );
	if ( mb3_check( env ) )
	{
		return JNI_FALSE;
	}
	b3BodyCastResult result = b3Body_CastShape( id, mb3_pos( ox, oy, oz ), &proxy, mb3_vec3( tx, ty, tz ),
												mb3_query_filter( categoryBits, maskBits ), 1.0f, false, transform );
	return mb3_body_hit( env, result, doubles, longs ) ? JNI_TRUE : JNI_FALSE;
}

MB3_JNI( jboolean, bodyOverlapShape )( JNIEnv* env, jclass type, jlong body, jdouble ox, jdouble oy, jdouble oz,
									   jfloatArray points, jint count, jfloat radius, jlong categoryBits, jlong maskBits )
{
	(void)type;
	b3Vec3 storage[B3_MAX_SHAPE_CAST_POINTS];
	b3ShapeProxy proxy;
	if ( mb3_proxy( env, points, count, radius, storage, &proxy ) == false )
	{
		return JNI_FALSE;
	}
	b3BodyId id = mb3_body( body );
	b3WorldTransform transform = b3Body_GetTransform( id );
	if ( mb3_check( env ) )
	{
		return JNI_FALSE;
	}
	bool overlaps = b3Body_OverlapShape( id, mb3_pos( ox, oy, oz ), &proxy, mb3_query_filter( categoryBits, maskBits ), transform );
	return mb3_check( env ) == false && overlaps ? JNI_TRUE : JNI_FALSE;
}

MB3_JNI( jfloat, bodyGetClosestPoint )( JNIEnv* env, jclass type, jlong body, jdouble x, jdouble y, jdouble z, jdoubleArray out )
{
	(void)type;
	b3BodyId id = mb3_body( body );
	int shapeCount = b3Body_GetShapeCount( id );
	b3WorldTransform transform = b3Body_GetTransform( id );
	if ( mb3_check( env ) )
	{
		return FLT_MAX;
	}
	b3ShapeId small[16];
	b3ShapeId* shapes = shapeCount <= 16 ? small : malloc( shapeCount * sizeof( b3ShapeId ) );
	if ( shapes == NULL )
	{
		mb3_throw( env, "out of memory" );
		return FLT_MAX;
	}
	shapeCount = b3Body_GetShapes( id, shapes, shapeCount );
	b3Pos target = mb3_pos( x, y, z );
	b3Pos best = target;
	float bestDistance = FLT_MAX;
	for ( int i = 0; i < shapeCount; ++i )
	{
		b3Pos closest;
		float distance = mb3_closest_point( shapes[i], transform, target, &closest );
		if ( distance < bestDistance )
		{
			bestDistance = distance;
			best = closest;
		}
	}
	if ( shapes != small )
	{
		free( shapes );
	}
	if ( mb3_check( env ) == false )
	{
		mb3_put_pos( env, out, 0, best );
	}
	return bestDistance;
}
