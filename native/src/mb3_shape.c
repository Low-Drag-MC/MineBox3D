// MineBox3D JNI bridge: shapes.

#include "mb3.h"

// Reads the definition, creates the shape with `create`, and releases what the definition copied.
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
		/* a failed check does not stop Box3D making the shape: Java never gets its id, so it would be an orphan */            \
		if ( mb3_assert_pending() && B3_IS_NON_NULL( id ) )                                                                    \
		{                                                                                                                      \
			b3DestroyShape( id, true );                                                                                        \
		}                                                                                                                      \
		if ( mb3_check( env ) )                                                                                                \
		{                                                                                                                      \
			return 0;                                                                                                          \
		}                                                                                                                      \
		if ( B3_IS_NULL( id ) )                                                                                                \
		{                                                                                                                      \
			mb3_throw( env, "Box3D could not create the shape" );                                                              \
			return 0;                                                                                                          \
		}                                                                                                                      \
		return mb3_pack_shape( id );                                                                                           \
	}                                                                                                                          \
	while ( 0 )

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
