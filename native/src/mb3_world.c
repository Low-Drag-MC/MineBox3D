// MineBox3D JNI bridge: worlds.

#include "mb3.h"

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
