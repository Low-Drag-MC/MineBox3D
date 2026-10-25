// MineBox3D JNI bridge: bodies.

#include "mb3.h"

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
