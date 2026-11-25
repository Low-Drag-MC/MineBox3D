// MineBox3D JNI bridge: the Java definition classes <-> Box3D definition structs.

#include "mb3.h"

#include <stdlib.h>
#include <string.h>

typedef struct mb3_field
{
	const char* name;
	const char* signature;
	jfieldID* id;
} mb3_field;

static bool mb3_lookup( JNIEnv* env, jclass type, const mb3_field* fields, int count )
{
	for ( int i = 0; i < count; ++i )
	{
		*fields[i].id = ( *env )->GetFieldID( env, type, fields[i].name, fields[i].signature );
		if ( *fields[i].id == NULL )
		{
			// GetFieldID left a NoSuchFieldError pending, naming the field.
			return false;
		}
	}
	return true;
}

#define MB3_COUNT( a ) ( (int)( sizeof( a ) / sizeof( ( a )[0] ) ) )

static jmethodID s_enumOrdinal;

static int mb3_ordinal( JNIEnv* env, jobject value )
{
	return value == NULL ? -1 : ( *env )->CallIntMethod( env, value, s_enumOrdinal );
}

int mb3_enum_ordinal( JNIEnv* env, jobject value )
{
	return mb3_ordinal( env, value );
}

// Copies a Java string into buffer, cut to fit; NULL for a null or empty string.
static const char* mb3_copy_name( JNIEnv* env, jstring value, char* buffer, int capacity )
{
	if ( value == NULL )
	{
		return NULL;
	}
	const char* chars = ( *env )->GetStringUTFChars( env, value, NULL );
	if ( chars == NULL )
	{
		return NULL;
	}
	size_t length = strlen( chars );
	if ( length >= (size_t)capacity )
	{
		length = (size_t)capacity - 1;
	}
	memcpy( buffer, chars, length );
	buffer[length] = '\0';
	( *env )->ReleaseStringUTFChars( env, value, chars );
	return length == 0 ? NULL : buffer;
}

// ---- Filter -------------------------------------------------------------------------------------

static jfieldID f_filterCategoryBits, f_filterMaskBits, f_filterGroupIndex;

void mb3_filter_to_java( JNIEnv* env, jobject target, b3Filter filter )
{
	( *env )->SetLongField( env, target, f_filterCategoryBits, (jlong)filter.categoryBits );
	( *env )->SetLongField( env, target, f_filterMaskBits, (jlong)filter.maskBits );
	( *env )->SetIntField( env, target, f_filterGroupIndex, filter.groupIndex );
}

bool mb3_filter_from_java( JNIEnv* env, jobject source, b3Filter* filter )
{
	if ( source == NULL )
	{
		mb3_throw_illegal_argument( env, "filter is null" );
		return false;
	}
	filter->categoryBits = (uint64_t)( *env )->GetLongField( env, source, f_filterCategoryBits );
	filter->maskBits = (uint64_t)( *env )->GetLongField( env, source, f_filterMaskBits );
	filter->groupIndex = ( *env )->GetIntField( env, source, f_filterGroupIndex );
	return true;
}

// ---- SurfaceMaterial ----------------------------------------------------------------------------

static jfieldID f_matFriction, f_matRestitution, f_matRollingResistance, f_matTangentX, f_matTangentY, f_matTangentZ,
	f_matUserMaterialId, f_matCustomColor, f_matFrictionCombine, f_matRestitutionCombine;

// SurfaceMaterial.Combine's values, to write a mode back
static jobjectArray s_combineModes;

static jobject mb3_combine_mode( JNIEnv* env, int ordinal )
{
	return ordinal < 0 || ordinal >= ( *env )->GetArrayLength( env, s_combineModes )
			   ? NULL
			   : ( *env )->GetObjectArrayElement( env, s_combineModes, ordinal );
}

void mb3_material_to_java( JNIEnv* env, jobject target, const b3SurfaceMaterial* m )
{
	( *env )->SetFloatField( env, target, f_matFriction, m->friction );
	( *env )->SetFloatField( env, target, f_matRestitution, m->restitution );
	( *env )->SetFloatField( env, target, f_matRollingResistance, m->rollingResistance );
	( *env )->SetFloatField( env, target, f_matTangentX, m->tangentVelocity.x );
	( *env )->SetFloatField( env, target, f_matTangentY, m->tangentVelocity.y );
	( *env )->SetFloatField( env, target, f_matTangentZ, m->tangentVelocity.z );
	( *env )->SetLongField( env, target, f_matUserMaterialId, (jlong)( m->userMaterialId & MB3_USER_MATERIAL_BITS ) );
	( *env )->SetIntField( env, target, f_matCustomColor, (jint)m->customColor );
	jobject friction = mb3_combine_mode( env, mb3_material_friction_combine( m->userMaterialId ) );
	jobject restitution = mb3_combine_mode( env, mb3_material_restitution_combine( m->userMaterialId ) );
	( *env )->SetObjectField( env, target, f_matFrictionCombine, friction );
	( *env )->SetObjectField( env, target, f_matRestitutionCombine, restitution );
	if ( friction != NULL )
	{
		( *env )->DeleteLocalRef( env, friction );
	}
	if ( restitution != NULL )
	{
		( *env )->DeleteLocalRef( env, restitution );
	}
}

bool mb3_material_from_java( JNIEnv* env, jobject source, b3SurfaceMaterial* m )
{
	if ( source == NULL )
	{
		mb3_throw_illegal_argument( env, "surface material is null" );
		return false;
	}
	*m = b3DefaultSurfaceMaterial();
	m->friction = ( *env )->GetFloatField( env, source, f_matFriction );
	m->restitution = ( *env )->GetFloatField( env, source, f_matRestitution );
	m->rollingResistance = ( *env )->GetFloatField( env, source, f_matRollingResistance );
	m->tangentVelocity.x = ( *env )->GetFloatField( env, source, f_matTangentX );
	m->tangentVelocity.y = ( *env )->GetFloatField( env, source, f_matTangentY );
	m->tangentVelocity.z = ( *env )->GetFloatField( env, source, f_matTangentZ );
	m->customColor = (uint32_t)( *env )->GetIntField( env, source, f_matCustomColor );
	jobject friction = ( *env )->GetObjectField( env, source, f_matFrictionCombine );
	jobject restitution = ( *env )->GetObjectField( env, source, f_matRestitutionCombine );
	m->userMaterialId = mb3_material_pack( (uint64_t)( *env )->GetLongField( env, source, f_matUserMaterialId ),
										   mb3_enum_ordinal( env, friction ), mb3_enum_ordinal( env, restitution ) );
	if ( friction != NULL )
	{
		( *env )->DeleteLocalRef( env, friction );
	}
	if ( restitution != NULL )
	{
		( *env )->DeleteLocalRef( env, restitution );
	}
	return true;
}

// ---- WorldDef -----------------------------------------------------------------------------------

static jfieldID f_wGravityX, f_wGravityY, f_wGravityZ, f_wRestitutionThreshold, f_wRestitutionIterations,
	f_wEnableRestitutionPropagation, f_wHitEventThreshold, f_wContactHertz, f_wContactDampingRatio, f_wContactSpeed,
	f_wMaximumLinearSpeed, f_wIsotropicInertiaTolerance, f_wEnableSleep, f_wEnableContinuous, f_wWorkerCount,
	f_wStaticShapeCapacity, f_wDynamicShapeCapacity, f_wStaticBodyCapacity, f_wDynamicBodyCapacity, f_wContactCapacity;

void mb3_world_def_to_java( JNIEnv* env, jobject t, const b3WorldDef* d )
{
	( *env )->SetFloatField( env, t, f_wGravityX, d->gravity.x );
	( *env )->SetFloatField( env, t, f_wGravityY, d->gravity.y );
	( *env )->SetFloatField( env, t, f_wGravityZ, d->gravity.z );
	( *env )->SetFloatField( env, t, f_wRestitutionThreshold, d->restitutionThreshold );
	( *env )->SetIntField( env, t, f_wRestitutionIterations, d->restitutionIterations );
	( *env )->SetBooleanField( env, t, f_wEnableRestitutionPropagation, d->enableRestitutionPropagation );
	( *env )->SetFloatField( env, t, f_wHitEventThreshold, d->hitEventThreshold );
	( *env )->SetFloatField( env, t, f_wContactHertz, d->contactHertz );
	( *env )->SetFloatField( env, t, f_wContactDampingRatio, d->contactDampingRatio );
	( *env )->SetFloatField( env, t, f_wContactSpeed, d->contactSpeed );
	( *env )->SetFloatField( env, t, f_wMaximumLinearSpeed, d->maximumLinearSpeed );
	( *env )->SetFloatField( env, t, f_wIsotropicInertiaTolerance, d->isotropicInertiaTolerance );
	( *env )->SetBooleanField( env, t, f_wEnableSleep, d->enableSleep );
	( *env )->SetBooleanField( env, t, f_wEnableContinuous, d->enableContinuous );
	( *env )->SetIntField( env, t, f_wWorkerCount, (jint)d->workerCount );
	( *env )->SetIntField( env, t, f_wStaticShapeCapacity, d->capacity.staticShapeCount );
	( *env )->SetIntField( env, t, f_wDynamicShapeCapacity, d->capacity.dynamicShapeCount );
	( *env )->SetIntField( env, t, f_wStaticBodyCapacity, d->capacity.staticBodyCount );
	( *env )->SetIntField( env, t, f_wDynamicBodyCapacity, d->capacity.dynamicBodyCount );
	( *env )->SetIntField( env, t, f_wContactCapacity, d->capacity.contactCount );
}

bool mb3_world_def_from_java( JNIEnv* env, jobject s, b3WorldDef* d )
{
	if ( s == NULL )
	{
		mb3_throw_illegal_argument( env, "world definition is null" );
		return false;
	}
	*d = b3DefaultWorldDef();
	d->gravity.x = ( *env )->GetFloatField( env, s, f_wGravityX );
	d->gravity.y = ( *env )->GetFloatField( env, s, f_wGravityY );
	d->gravity.z = ( *env )->GetFloatField( env, s, f_wGravityZ );
	d->restitutionThreshold = ( *env )->GetFloatField( env, s, f_wRestitutionThreshold );
	d->restitutionIterations = ( *env )->GetIntField( env, s, f_wRestitutionIterations );
	d->enableRestitutionPropagation = ( *env )->GetBooleanField( env, s, f_wEnableRestitutionPropagation );
	d->hitEventThreshold = ( *env )->GetFloatField( env, s, f_wHitEventThreshold );
	d->contactHertz = ( *env )->GetFloatField( env, s, f_wContactHertz );
	d->contactDampingRatio = ( *env )->GetFloatField( env, s, f_wContactDampingRatio );
	d->contactSpeed = ( *env )->GetFloatField( env, s, f_wContactSpeed );
	d->maximumLinearSpeed = ( *env )->GetFloatField( env, s, f_wMaximumLinearSpeed );
	d->isotropicInertiaTolerance = ( *env )->GetFloatField( env, s, f_wIsotropicInertiaTolerance );
	d->enableSleep = ( *env )->GetBooleanField( env, s, f_wEnableSleep );
	d->enableContinuous = ( *env )->GetBooleanField( env, s, f_wEnableContinuous );
	jint workers = ( *env )->GetIntField( env, s, f_wWorkerCount );
	d->workerCount = workers < 1 ? 1u : (uint32_t)workers;
	d->capacity.staticShapeCount = ( *env )->GetIntField( env, s, f_wStaticShapeCapacity );
	d->capacity.dynamicShapeCount = ( *env )->GetIntField( env, s, f_wDynamicShapeCapacity );
	d->capacity.staticBodyCount = ( *env )->GetIntField( env, s, f_wStaticBodyCapacity );
	d->capacity.dynamicBodyCount = ( *env )->GetIntField( env, s, f_wDynamicBodyCapacity );
	d->capacity.contactCount = ( *env )->GetIntField( env, s, f_wContactCapacity );
	return true;
}

// ---- BodyDef ------------------------------------------------------------------------------------

static jfieldID f_bType, f_bPositionX, f_bPositionY, f_bPositionZ, f_bRotationX, f_bRotationY, f_bRotationZ,
	f_bRotationW, f_bLinearVelocityX, f_bLinearVelocityY, f_bLinearVelocityZ, f_bAngularVelocityX, f_bAngularVelocityY,
	f_bAngularVelocityZ, f_bLinearDamping, f_bAngularDamping, f_bGravityScale, f_bSleepThreshold, f_bSafetyFactor,
	f_bName, f_bUserData, f_bFixedRotation, f_bEnableSleep, f_bAwake, f_bBullet, f_bEnabled, f_bAllowFastRotation,
	f_bEnableContactRecycling;

void mb3_body_def_to_java( JNIEnv* env, jobject t, const b3BodyDef* d )
{
	// the type is an enum and stays the Java side's (BodyDef initialises it to STATIC = b3_staticBody)
	( *env )->SetDoubleField( env, t, f_bPositionX, d->position.x );
	( *env )->SetDoubleField( env, t, f_bPositionY, d->position.y );
	( *env )->SetDoubleField( env, t, f_bPositionZ, d->position.z );
	( *env )->SetFloatField( env, t, f_bRotationX, d->rotation.v.x );
	( *env )->SetFloatField( env, t, f_bRotationY, d->rotation.v.y );
	( *env )->SetFloatField( env, t, f_bRotationZ, d->rotation.v.z );
	( *env )->SetFloatField( env, t, f_bRotationW, d->rotation.s );
	( *env )->SetFloatField( env, t, f_bLinearVelocityX, d->linearVelocity.x );
	( *env )->SetFloatField( env, t, f_bLinearVelocityY, d->linearVelocity.y );
	( *env )->SetFloatField( env, t, f_bLinearVelocityZ, d->linearVelocity.z );
	( *env )->SetFloatField( env, t, f_bAngularVelocityX, d->angularVelocity.x );
	( *env )->SetFloatField( env, t, f_bAngularVelocityY, d->angularVelocity.y );
	( *env )->SetFloatField( env, t, f_bAngularVelocityZ, d->angularVelocity.z );
	( *env )->SetFloatField( env, t, f_bLinearDamping, d->linearDamping );
	( *env )->SetFloatField( env, t, f_bAngularDamping, d->angularDamping );
	( *env )->SetFloatField( env, t, f_bGravityScale, d->gravityScale );
	( *env )->SetFloatField( env, t, f_bSleepThreshold, d->sleepThreshold );
	( *env )->SetFloatField( env, t, f_bSafetyFactor, d->safetyFactor );
	( *env )->SetLongField( env, t, f_bUserData, (jlong)(intptr_t)d->userData );
	( *env )->SetBooleanField( env, t, f_bFixedRotation, d->fixedRotation );
	( *env )->SetBooleanField( env, t, f_bEnableSleep, d->enableSleep );
	( *env )->SetBooleanField( env, t, f_bAwake, d->isAwake );
	( *env )->SetBooleanField( env, t, f_bBullet, d->isBullet );
	( *env )->SetBooleanField( env, t, f_bEnabled, d->isEnabled );
	( *env )->SetBooleanField( env, t, f_bAllowFastRotation, d->allowFastRotation );
	( *env )->SetBooleanField( env, t, f_bEnableContactRecycling, d->enableContactRecycling );
}

bool mb3_body_def_from_java( JNIEnv* env, jobject s, b3BodyDef* d, char* nameBuffer, int nameCapacity )
{
	if ( s == NULL )
	{
		mb3_throw_illegal_argument( env, "body definition is null" );
		return false;
	}
	*d = b3DefaultBodyDef();
	int type = mb3_ordinal( env, ( *env )->GetObjectField( env, s, f_bType ) );
	if ( type < 0 || type >= b3_bodyTypeCount )
	{
		mb3_throw_illegal_argument( env, "body definition has no type" );
		return false;
	}
	d->type = (b3BodyType)type;
	d->position =
		mb3_pos( ( *env )->GetDoubleField( env, s, f_bPositionX ), ( *env )->GetDoubleField( env, s, f_bPositionY ),
				 ( *env )->GetDoubleField( env, s, f_bPositionZ ) );
	d->rotation =
		mb3_quat( ( *env )->GetFloatField( env, s, f_bRotationX ), ( *env )->GetFloatField( env, s, f_bRotationY ),
				  ( *env )->GetFloatField( env, s, f_bRotationZ ), ( *env )->GetFloatField( env, s, f_bRotationW ) );
	d->linearVelocity = mb3_vec3( ( *env )->GetFloatField( env, s, f_bLinearVelocityX ),
								  ( *env )->GetFloatField( env, s, f_bLinearVelocityY ),
								  ( *env )->GetFloatField( env, s, f_bLinearVelocityZ ) );
	d->angularVelocity = mb3_vec3( ( *env )->GetFloatField( env, s, f_bAngularVelocityX ),
								   ( *env )->GetFloatField( env, s, f_bAngularVelocityY ),
								   ( *env )->GetFloatField( env, s, f_bAngularVelocityZ ) );
	d->linearDamping = ( *env )->GetFloatField( env, s, f_bLinearDamping );
	d->angularDamping = ( *env )->GetFloatField( env, s, f_bAngularDamping );
	d->gravityScale = ( *env )->GetFloatField( env, s, f_bGravityScale );
	d->sleepThreshold = ( *env )->GetFloatField( env, s, f_bSleepThreshold );
	d->safetyFactor = ( *env )->GetFloatField( env, s, f_bSafetyFactor );
	d->name = mb3_copy_name( env, (jstring)( *env )->GetObjectField( env, s, f_bName ), nameBuffer, nameCapacity );
	d->userData = (void*)(intptr_t)( *env )->GetLongField( env, s, f_bUserData );
	d->fixedRotation = ( *env )->GetBooleanField( env, s, f_bFixedRotation );
	d->enableSleep = ( *env )->GetBooleanField( env, s, f_bEnableSleep );
	d->isAwake = ( *env )->GetBooleanField( env, s, f_bAwake );
	d->isBullet = ( *env )->GetBooleanField( env, s, f_bBullet );
	d->isEnabled = ( *env )->GetBooleanField( env, s, f_bEnabled );
	d->allowFastRotation = ( *env )->GetBooleanField( env, s, f_bAllowFastRotation );
	d->enableContactRecycling = ( *env )->GetBooleanField( env, s, f_bEnableContactRecycling );
	return ( *env )->ExceptionCheck( env ) == JNI_FALSE;
}

// ---- ShapeDef -----------------------------------------------------------------------------------

static jfieldID f_sName, f_sUserData, f_sMaterials, f_sBaseMaterial, f_sDensity, f_sExplosionScale, f_sFilter,
	f_sEnableCustomFiltering, f_sSensor, f_sEnableSensorEvents, f_sEnableContactEvents, f_sEnableHitEvents,
	f_sEnablePreSolveEvents, f_sInvokeContactCreation, f_sUpdateBodyMass, f_sEnableSpeculativeContact;

void mb3_shape_def_to_java( JNIEnv* env, jobject t, const b3ShapeDef* d )
{
	// the nested base material and filter objects are created by the Java constructor
	mb3_material_to_java( env, ( *env )->GetObjectField( env, t, f_sBaseMaterial ), &d->baseMaterial );
	mb3_filter_to_java( env, ( *env )->GetObjectField( env, t, f_sFilter ), d->filter );
	( *env )->SetLongField( env, t, f_sUserData, (jlong)(intptr_t)d->userData );
	( *env )->SetFloatField( env, t, f_sDensity, d->density );
	( *env )->SetFloatField( env, t, f_sExplosionScale, d->explosionScale );
	( *env )->SetBooleanField( env, t, f_sEnableCustomFiltering, d->enableCustomFiltering );
	( *env )->SetBooleanField( env, t, f_sSensor, d->isSensor );
	( *env )->SetBooleanField( env, t, f_sEnableSensorEvents, d->enableSensorEvents );
	( *env )->SetBooleanField( env, t, f_sEnableContactEvents, d->enableContactEvents );
	( *env )->SetBooleanField( env, t, f_sEnableHitEvents, d->enableHitEvents );
	( *env )->SetBooleanField( env, t, f_sEnablePreSolveEvents, d->enablePreSolveEvents );
	( *env )->SetBooleanField( env, t, f_sInvokeContactCreation, d->invokeContactCreation );
	( *env )->SetBooleanField( env, t, f_sUpdateBodyMass, d->updateBodyMass );
	( *env )->SetBooleanField( env, t, f_sEnableSpeculativeContact, d->enableSpeculativeContact );
}

bool mb3_shape_def_from_java( JNIEnv* env, jobject s, b3ShapeDef* d, char* nameBuffer, int nameCapacity )
{
	if ( s == NULL )
	{
		mb3_throw_illegal_argument( env, "shape definition is null" );
		return false;
	}
	*d = b3DefaultShapeDef();
	d->name = mb3_copy_name( env, (jstring)( *env )->GetObjectField( env, s, f_sName ), nameBuffer, nameCapacity );
	d->userData = (void*)(intptr_t)( *env )->GetLongField( env, s, f_sUserData );
	if ( mb3_material_from_java( env, ( *env )->GetObjectField( env, s, f_sBaseMaterial ), &d->baseMaterial ) == false )
	{
		return false;
	}
	if ( mb3_filter_from_java( env, ( *env )->GetObjectField( env, s, f_sFilter ), &d->filter ) == false )
	{
		return false;
	}
	d->density = ( *env )->GetFloatField( env, s, f_sDensity );
	d->explosionScale = ( *env )->GetFloatField( env, s, f_sExplosionScale );
	d->enableCustomFiltering = ( *env )->GetBooleanField( env, s, f_sEnableCustomFiltering );
	d->isSensor = ( *env )->GetBooleanField( env, s, f_sSensor );
	d->enableSensorEvents = ( *env )->GetBooleanField( env, s, f_sEnableSensorEvents );
	d->enableContactEvents = ( *env )->GetBooleanField( env, s, f_sEnableContactEvents );
	d->enableHitEvents = ( *env )->GetBooleanField( env, s, f_sEnableHitEvents );
	d->enablePreSolveEvents = ( *env )->GetBooleanField( env, s, f_sEnablePreSolveEvents );
	d->invokeContactCreation = ( *env )->GetBooleanField( env, s, f_sInvokeContactCreation );
	d->updateBodyMass = ( *env )->GetBooleanField( env, s, f_sUpdateBodyMass );
	d->enableSpeculativeContact = ( *env )->GetBooleanField( env, s, f_sEnableSpeculativeContact );

	d->materials = NULL;
	d->materialCount = 0;
	jobjectArray materials = (jobjectArray)( *env )->GetObjectField( env, s, f_sMaterials );
	if ( materials != NULL )
	{
		jsize count = ( *env )->GetArrayLength( env, materials );
		if ( count > 0 )
		{
			d->materials = malloc( (size_t)count * sizeof( b3SurfaceMaterial ) );
			if ( d->materials == NULL )
			{
				mb3_throw( env, "out of memory copying surface materials" );
				return false;
			}
			d->materialCount = count;
			for ( jsize i = 0; i < count; ++i )
			{
				jobject material = ( *env )->GetObjectArrayElement( env, materials, i );
				bool ok = mb3_material_from_java( env, material, d->materials + i );
				( *env )->DeleteLocalRef( env, material );
				if ( ok == false )
				{
					mb3_shape_def_release( d );
					return false;
				}
			}
		}
	}
	return true;
}

void mb3_shape_def_release( b3ShapeDef* d )
{
	free( d->materials );
	d->materials = NULL;
	d->materialCount = 0;
}

// ---- lookup -------------------------------------------------------------------------------------

#define MB3_FIELD( id, name, sig ) { name, sig, &id }

bool mb3_defs_init( JNIEnv* env, jobjectArray classes )
{
	jclass enumClass = ( *env )->FindClass( env, "java/lang/Enum" );
	if ( enumClass == NULL )
	{
		return false;
	}
	s_enumOrdinal = ( *env )->GetMethodID( env, enumClass, "ordinal", "()I" );
	jclass combineClass = (jclass)( *env )->GetObjectArrayElement( env, classes, MB3_CLASS_SURFACE_COMBINE );
	jclass classClass = ( *env )->FindClass( env, "java/lang/Class" );
	jmethodID constants = classClass == NULL
							  ? NULL
							  : ( *env )->GetMethodID( env, classClass, "getEnumConstants", "()[Ljava/lang/Object;" );
	jobject modes =
		combineClass == NULL || constants == NULL ? NULL : ( *env )->CallObjectMethod( env, combineClass, constants );
	if ( modes == NULL || ( *env )->ExceptionCheck( env ) )
	{
		return false;
	}
	s_combineModes = (jobjectArray)( *env )->NewGlobalRef( env, modes );
	if ( s_enumOrdinal == NULL )
	{
		return false;
	}

	const mb3_field filter[] = {
		MB3_FIELD( f_filterCategoryBits, "categoryBits", "J" ),
		MB3_FIELD( f_filterMaskBits, "maskBits", "J" ),
		MB3_FIELD( f_filterGroupIndex, "groupIndex", "I" ),
	};
	const mb3_field material[] = {
		MB3_FIELD( f_matFriction, "friction", "F" ),
		MB3_FIELD( f_matRestitution, "restitution", "F" ),
		MB3_FIELD( f_matRollingResistance, "rollingResistance", "F" ),
		MB3_FIELD( f_matTangentX, "tangentVelocityX", "F" ),
		MB3_FIELD( f_matTangentY, "tangentVelocityY", "F" ),
		MB3_FIELD( f_matTangentZ, "tangentVelocityZ", "F" ),
		MB3_FIELD( f_matUserMaterialId, "userMaterialId", "J" ),
		MB3_FIELD( f_matCustomColor, "customColor", "I" ),
		MB3_FIELD( f_matFrictionCombine, "frictionCombine",
				   "Lcom/lowdragmc/minebox3d/physics/SurfaceMaterial$Combine;" ),
		MB3_FIELD( f_matRestitutionCombine, "restitutionCombine",
				   "Lcom/lowdragmc/minebox3d/physics/SurfaceMaterial$Combine;" ),
	};
	const mb3_field world[] = {
		MB3_FIELD( f_wGravityX, "gravityX", "F" ),
		MB3_FIELD( f_wGravityY, "gravityY", "F" ),
		MB3_FIELD( f_wGravityZ, "gravityZ", "F" ),
		MB3_FIELD( f_wRestitutionThreshold, "restitutionThreshold", "F" ),
		MB3_FIELD( f_wRestitutionIterations, "restitutionIterations", "I" ),
		MB3_FIELD( f_wEnableRestitutionPropagation, "enableRestitutionPropagation", "Z" ),
		MB3_FIELD( f_wHitEventThreshold, "hitEventThreshold", "F" ),
		MB3_FIELD( f_wContactHertz, "contactHertz", "F" ),
		MB3_FIELD( f_wContactDampingRatio, "contactDampingRatio", "F" ),
		MB3_FIELD( f_wContactSpeed, "contactSpeed", "F" ),
		MB3_FIELD( f_wMaximumLinearSpeed, "maximumLinearSpeed", "F" ),
		MB3_FIELD( f_wIsotropicInertiaTolerance, "isotropicInertiaTolerance", "F" ),
		MB3_FIELD( f_wEnableSleep, "enableSleep", "Z" ),
		MB3_FIELD( f_wEnableContinuous, "enableContinuous", "Z" ),
		MB3_FIELD( f_wWorkerCount, "workerCount", "I" ),
		MB3_FIELD( f_wStaticShapeCapacity, "staticShapeCapacity", "I" ),
		MB3_FIELD( f_wDynamicShapeCapacity, "dynamicShapeCapacity", "I" ),
		MB3_FIELD( f_wStaticBodyCapacity, "staticBodyCapacity", "I" ),
		MB3_FIELD( f_wDynamicBodyCapacity, "dynamicBodyCapacity", "I" ),
		MB3_FIELD( f_wContactCapacity, "contactCapacity", "I" ),
	};
	const mb3_field body[] = {
		MB3_FIELD( f_bType, "type", "Lcom/lowdragmc/minebox3d/physics/BodyType;" ),
		MB3_FIELD( f_bPositionX, "positionX", "D" ),
		MB3_FIELD( f_bPositionY, "positionY", "D" ),
		MB3_FIELD( f_bPositionZ, "positionZ", "D" ),
		MB3_FIELD( f_bRotationX, "rotationX", "F" ),
		MB3_FIELD( f_bRotationY, "rotationY", "F" ),
		MB3_FIELD( f_bRotationZ, "rotationZ", "F" ),
		MB3_FIELD( f_bRotationW, "rotationW", "F" ),
		MB3_FIELD( f_bLinearVelocityX, "linearVelocityX", "F" ),
		MB3_FIELD( f_bLinearVelocityY, "linearVelocityY", "F" ),
		MB3_FIELD( f_bLinearVelocityZ, "linearVelocityZ", "F" ),
		MB3_FIELD( f_bAngularVelocityX, "angularVelocityX", "F" ),
		MB3_FIELD( f_bAngularVelocityY, "angularVelocityY", "F" ),
		MB3_FIELD( f_bAngularVelocityZ, "angularVelocityZ", "F" ),
		MB3_FIELD( f_bLinearDamping, "linearDamping", "F" ),
		MB3_FIELD( f_bAngularDamping, "angularDamping", "F" ),
		MB3_FIELD( f_bGravityScale, "gravityScale", "F" ),
		MB3_FIELD( f_bSleepThreshold, "sleepThreshold", "F" ),
		MB3_FIELD( f_bSafetyFactor, "safetyFactor", "F" ),
		MB3_FIELD( f_bName, "name", "Ljava/lang/String;" ),
		MB3_FIELD( f_bUserData, "userData", "J" ),
		MB3_FIELD( f_bFixedRotation, "fixedRotation", "Z" ),
		MB3_FIELD( f_bEnableSleep, "enableSleep", "Z" ),
		MB3_FIELD( f_bAwake, "awake", "Z" ),
		MB3_FIELD( f_bBullet, "bullet", "Z" ),
		MB3_FIELD( f_bEnabled, "enabled", "Z" ),
		MB3_FIELD( f_bAllowFastRotation, "allowFastRotation", "Z" ),
		MB3_FIELD( f_bEnableContactRecycling, "enableContactRecycling", "Z" ),
	};
	const mb3_field shape[] = {
		MB3_FIELD( f_sName, "name", "Ljava/lang/String;" ),
		MB3_FIELD( f_sUserData, "userData", "J" ),
		MB3_FIELD( f_sMaterials, "materials", "[Lcom/lowdragmc/minebox3d/physics/SurfaceMaterial;" ),
		MB3_FIELD( f_sBaseMaterial, "baseMaterial", "Lcom/lowdragmc/minebox3d/physics/SurfaceMaterial;" ),
		MB3_FIELD( f_sDensity, "density", "F" ),
		MB3_FIELD( f_sExplosionScale, "explosionScale", "F" ),
		MB3_FIELD( f_sFilter, "filter", "Lcom/lowdragmc/minebox3d/physics/Filter;" ),
		MB3_FIELD( f_sEnableCustomFiltering, "enableCustomFiltering", "Z" ),
		MB3_FIELD( f_sSensor, "sensor", "Z" ),
		MB3_FIELD( f_sEnableSensorEvents, "enableSensorEvents", "Z" ),
		MB3_FIELD( f_sEnableContactEvents, "enableContactEvents", "Z" ),
		MB3_FIELD( f_sEnableHitEvents, "enableHitEvents", "Z" ),
		MB3_FIELD( f_sEnablePreSolveEvents, "enablePreSolveEvents", "Z" ),
		MB3_FIELD( f_sInvokeContactCreation, "invokeContactCreation", "Z" ),
		MB3_FIELD( f_sUpdateBodyMass, "updateBodyMass", "Z" ),
		MB3_FIELD( f_sEnableSpeculativeContact, "enableSpeculativeContact", "Z" ),
	};

	jclass worldClass = (jclass)( *env )->GetObjectArrayElement( env, classes, MB3_CLASS_WORLD_DEF );
	jclass bodyClass = (jclass)( *env )->GetObjectArrayElement( env, classes, MB3_CLASS_BODY_DEF );
	jclass shapeClass = (jclass)( *env )->GetObjectArrayElement( env, classes, MB3_CLASS_SHAPE_DEF );
	jclass filterClass = (jclass)( *env )->GetObjectArrayElement( env, classes, MB3_CLASS_FILTER );
	jclass materialClass = (jclass)( *env )->GetObjectArrayElement( env, classes, MB3_CLASS_SURFACE_MATERIAL );

	return mb3_lookup( env, filterClass, filter, MB3_COUNT( filter ) ) &&
		   mb3_lookup( env, materialClass, material, MB3_COUNT( material ) ) &&
		   mb3_lookup( env, worldClass, world, MB3_COUNT( world ) ) &&
		   mb3_lookup( env, bodyClass, body, MB3_COUNT( body ) ) &&
		   mb3_lookup( env, shapeClass, shape, MB3_COUNT( shape ) ) && mb3_joint_defs_init( env, classes );
}

// ---- defaults -----------------------------------------------------------------------------------
// Each Java definition fills itself from Box3D's own defaults, so the two can never disagree.

MB3_JNI( void, defaultWorldDef )( JNIEnv* env, jclass type, jobject target )
{
	(void)type;
	b3WorldDef def = b3DefaultWorldDef();
	mb3_world_def_to_java( env, target, &def );
}

MB3_JNI( void, defaultBodyDef )( JNIEnv* env, jclass type, jobject target )
{
	(void)type;
	b3BodyDef def = b3DefaultBodyDef();
	mb3_body_def_to_java( env, target, &def );
}

MB3_JNI( void, defaultShapeDef )( JNIEnv* env, jclass type, jobject target )
{
	(void)type;
	b3ShapeDef def = b3DefaultShapeDef();
	mb3_shape_def_to_java( env, target, &def );
}

MB3_JNI( void, defaultFilter )( JNIEnv* env, jclass type, jobject target )
{
	(void)type;
	mb3_filter_to_java( env, target, b3DefaultFilter() );
}

MB3_JNI( void, defaultSurfaceMaterial )( JNIEnv* env, jclass type, jobject target )
{
	(void)type;
	b3SurfaceMaterial material = b3DefaultSurfaceMaterial();
	mb3_material_to_java( env, target, &material );
}
