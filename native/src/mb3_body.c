// MineBox3D JNI bridge: bodies.

#include "mb3.h"

#include <stdlib.h>

// The touching contact points of a body, seen from the body: per point, points gets
// [nx, ny, nz, separation, normalImpulse, ax, ay, az] with the normal pointing from the other shape into this
// body and the anchor on this body relative to its centre of mass (world orientation); otherShapes the other
// shape and triangleIndices the other shape's triangle index (a voxel feature key on terrain). Returns the
// number of points, which may be more than the arrays take.
MB3_JNI( jint, bodyGetContactPoints )( JNIEnv* env, jclass type, jlong body, jfloatArray points, jlongArray otherShapes,
									   jintArray triangleIndices )
{
	(void)type;
	if ( points == NULL || otherShapes == NULL || triangleIndices == NULL )
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
	if ( ( *env )->GetArrayLength( env, triangleIndices ) < capacity )
		capacity = ( *env )->GetArrayLength( env, triangleIndices );
	if ( ( *env )->GetArrayLength( env, points ) / 8 < capacity )
		capacity = ( *env )->GetArrayLength( env, points ) / 8;

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
				float values[8] = { sign * manifold->normal.x, sign * manifold->normal.y, sign * manifold->normal.z,
									mp->separation, mp->normalImpulse, anchor.x, anchor.y, anchor.z };
				jlong otherId = mb3_pack_shape( other );
				jint triangle = mp->triangleIndex;
				( *env )->SetFloatArrayRegion( env, points, 8 * count, 8, values );
				( *env )->SetLongArrayRegion( env, otherShapes, count, 1, &otherId );
				( *env )->SetIntArrayRegion( env, triangleIndices, count, 1, &triangle );
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

// Reads `count` bodies in one crossing: out receives 7 doubles per body, in the order of `bodies`.
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

// Moves a kinematic body to `target` over `timeStep` by giving it the velocity that gets it there,
// so what it hits on the way is pushed rather than overlapped.
MB3_JNI( void, bodySetTargetTransform )( JNIEnv* env, jclass type, jlong body, jdouble px, jdouble py, jdouble pz,
										 jfloat qx, jfloat qy, jfloat qz, jfloat qw, jfloat timeStep, jboolean wake )
{
	(void)type;
	b3WorldTransform target = { mb3_pos( px, py, pz ), mb3_quat( qx, qy, qz, qw ) };
	b3Body_SetTargetTransform( mb3_body( body ), target, timeStep, wake );
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

MB3_JNI( void, bodyEnableSleep )( JNIEnv* env, jclass type, jlong body, jboolean enableSleep )
{
	(void)type;
	b3Body_EnableSleep( mb3_body( body ), enableSleep );
	mb3_check( env );
}

// [minX, minY, minZ, maxX, maxY, maxZ] in world space (float: this is the broad phase's own box).
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

MB3_JNI( void, bodySetGravityScale )( JNIEnv* env, jclass type, jlong body, jfloat scale )
{
	(void)type;
	b3Body_SetGravityScale( mb3_body( body ), scale );
	mb3_check( env );
}

MB3_JNI( void, bodySetDamping )( JNIEnv* env, jclass type, jlong body, jfloat linear, jfloat angular )
{
	(void)type;
	b3Body_SetLinearDamping( mb3_body( body ), linear );
	b3Body_SetAngularDamping( mb3_body( body ), angular );
	mb3_check( env );
}

MB3_JNI( void, bodySetBullet )( JNIEnv* env, jclass type, jlong body, jboolean bullet )
{
	(void)type;
	b3Body_SetBullet( mb3_body( body ), bullet );
	mb3_check( env );
}
