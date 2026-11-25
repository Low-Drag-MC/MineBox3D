// MineBox3D JNI bridge: joints.
//
// Each definition class is read through one table per type: each Java field names the C struct member it is,
// by offset, so reading and writing a definition is one loop and a field renamed on one side fails the lookup at
// startup. Runtime properties go through two calls, jointSet / jointGet, with a parameter number (enum below, the Java
// twin is JointParam); the typed API is the Java classes.

#include "mb3.h"

#include <float.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

// ---- definitions ----------------------------------------------------------------------------------------------------

typedef struct mb3_def_field
{
	const char* name;
	// 'F' float, 'Z' bool, 'J' a pointer-sized user data, 'E' a Java enum as a C enum, 'O' a Java object holding a C
	// struct's fields (its own table below)
	char kind;
	size_t offset;
	jfieldID id;
	int classIndex;					  // 'E', 'O': the field's class in B3.boundClasses
	const char* signature;			  // 'E', 'O'
	struct mb3_def_field* fields;	  // 'O'
	int count;
} mb3_def_field;

#define MB3_PHYSICS "Lcom/lowdragmc/minebox3d/physics/"
#define MB3_TABLE( a ) a, (int)( sizeof( a ) / sizeof( ( a )[0] ) )
#define MB3_F( type, member, javaName ) { javaName, 'F', offsetof( type, member ), NULL, 0, NULL, NULL, 0 }
#define MB3_Z( type, member, javaName ) { javaName, 'Z', offsetof( type, member ), NULL, 0, NULL, NULL, 0 }
#define MB3_E( type, member, javaName, classIndex, javaClass )                                                                   \
	{ javaName, 'E', offsetof( type, member ), NULL, classIndex, MB3_PHYSICS javaClass ";", NULL, 0 }
#define MB3_O( type, member, javaName, classIndex, javaClass, table )                                                            \
	{ javaName, 'O', offsetof( type, member ), NULL, classIndex, MB3_PHYSICS javaClass ";", MB3_TABLE( table ) }

// The constants of each Java enum a table names, by class index, for writing a C enum back
static jobjectArray s_enumConstants[MB3_CLASS_COUNT];

// b3JointDef is the first member of every joint definition, so these offsets hold for all of them.
static mb3_def_field s_baseFields[] = {
	{ "userData", 'J', offsetof( b3JointDef, userData ), NULL },
	MB3_F( b3JointDef, localFrameA.p.x, "positionAX" ),
	MB3_F( b3JointDef, localFrameA.p.y, "positionAY" ),
	MB3_F( b3JointDef, localFrameA.p.z, "positionAZ" ),
	MB3_F( b3JointDef, localFrameA.q.v.x, "rotationAX" ),
	MB3_F( b3JointDef, localFrameA.q.v.y, "rotationAY" ),
	MB3_F( b3JointDef, localFrameA.q.v.z, "rotationAZ" ),
	MB3_F( b3JointDef, localFrameA.q.s, "rotationAW" ),
	MB3_F( b3JointDef, localFrameB.p.x, "positionBX" ),
	MB3_F( b3JointDef, localFrameB.p.y, "positionBY" ),
	MB3_F( b3JointDef, localFrameB.p.z, "positionBZ" ),
	MB3_F( b3JointDef, localFrameB.q.v.x, "rotationBX" ),
	MB3_F( b3JointDef, localFrameB.q.v.y, "rotationBY" ),
	MB3_F( b3JointDef, localFrameB.q.v.z, "rotationBZ" ),
	MB3_F( b3JointDef, localFrameB.q.s, "rotationBW" ),
	MB3_F( b3JointDef, forceThreshold, "forceThreshold" ),
	MB3_F( b3JointDef, torqueThreshold, "torqueThreshold" ),
	MB3_F( b3JointDef, constraintHertz, "constraintHertz" ),
	MB3_F( b3JointDef, constraintDampingRatio, "constraintDampingRatio" ),
	MB3_F( b3JointDef, drawScale, "drawScale" ),
	MB3_Z( b3JointDef, collideConnected, "collideConnected" ),
};

static mb3_def_field s_distanceFields[] = {
	MB3_F( b3DistanceJointDef, length, "length" ),
	MB3_Z( b3DistanceJointDef, enableSpring, "enableSpring" ),
	MB3_F( b3DistanceJointDef, lowerSpringForce, "lowerSpringForce" ),
	MB3_F( b3DistanceJointDef, upperSpringForce, "upperSpringForce" ),
	MB3_F( b3DistanceJointDef, hertz, "hertz" ),
	MB3_F( b3DistanceJointDef, dampingRatio, "dampingRatio" ),
	MB3_Z( b3DistanceJointDef, enableLimit, "enableLimit" ),
	MB3_F( b3DistanceJointDef, minLength, "minLength" ),
	MB3_F( b3DistanceJointDef, maxLength, "maxLength" ),
	MB3_Z( b3DistanceJointDef, enableMotor, "enableMotor" ),
	MB3_F( b3DistanceJointDef, maxMotorForce, "maxMotorForce" ),
	MB3_F( b3DistanceJointDef, motorSpeed, "motorSpeed" ),
};

static mb3_def_field s_motorFields[] = {
	MB3_F( b3MotorJointDef, linearVelocity.x, "linearVelocityX" ),
	MB3_F( b3MotorJointDef, linearVelocity.y, "linearVelocityY" ),
	MB3_F( b3MotorJointDef, linearVelocity.z, "linearVelocityZ" ),
	MB3_F( b3MotorJointDef, maxVelocityForce, "maxVelocityForce" ),
	MB3_F( b3MotorJointDef, angularVelocity.x, "angularVelocityX" ),
	MB3_F( b3MotorJointDef, angularVelocity.y, "angularVelocityY" ),
	MB3_F( b3MotorJointDef, angularVelocity.z, "angularVelocityZ" ),
	MB3_F( b3MotorJointDef, maxVelocityTorque, "maxVelocityTorque" ),
	MB3_F( b3MotorJointDef, linearHertz, "linearHertz" ),
	MB3_F( b3MotorJointDef, linearDampingRatio, "linearDampingRatio" ),
	MB3_F( b3MotorJointDef, maxSpringForce, "maxSpringForce" ),
	MB3_F( b3MotorJointDef, angularHertz, "angularHertz" ),
	MB3_F( b3MotorJointDef, angularDampingRatio, "angularDampingRatio" ),
	MB3_F( b3MotorJointDef, maxSpringTorque, "maxSpringTorque" ),
};

static mb3_def_field s_moverFields[] = {
	MB3_F( b3MoverJointDef, linearVelocity.x, "linearVelocityX" ),
	MB3_F( b3MoverJointDef, linearVelocity.y, "linearVelocityY" ),
	MB3_F( b3MoverJointDef, linearVelocity.z, "linearVelocityZ" ),
	MB3_F( b3MoverJointDef, maxForce, "maxForce" ),
};

static mb3_def_field s_pogoFields[] = {
	MB3_F( b3PogoJointDef, normal.x, "normalX" ),
	MB3_F( b3PogoJointDef, normal.y, "normalY" ),
	MB3_F( b3PogoJointDef, normal.z, "normalZ" ),
	MB3_F( b3PogoJointDef, hertz, "hertz" ),
	MB3_F( b3PogoJointDef, dampingRatio, "dampingRatio" ),
	MB3_F( b3PogoJointDef, restLength, "restLength" ),
	MB3_F( b3PogoJointDef, maxTensionForce, "maxTensionForce" ),
	MB3_F( b3PogoJointDef, maxCompressionForce, "maxCompressionForce" ),
	MB3_F( b3PogoJointDef, impulse, "impulse" ),
	MB3_F( b3PogoJointDef, velocity, "velocity" ),
};

static mb3_def_field s_motionLockFields[] = {
	MB3_Z( b3MotionLockJointDef, lockLinearX, "lockLinearX" ),
	MB3_Z( b3MotionLockJointDef, lockLinearY, "lockLinearY" ),
	MB3_Z( b3MotionLockJointDef, lockLinearZ, "lockLinearZ" ),
	MB3_Z( b3MotionLockJointDef, lockAngularXY, "lockAngularXY" ),
};

static mb3_def_field s_parallelFields[] = {
	MB3_F( b3ParallelJointDef, hertz, "hertz" ),
	MB3_F( b3ParallelJointDef, dampingRatio, "dampingRatio" ),
	MB3_F( b3ParallelJointDef, maxTorque, "maxTorque" ),
};

static mb3_def_field s_prismaticFields[] = {
	MB3_Z( b3PrismaticJointDef, enableSpring, "enableSpring" ),
	MB3_F( b3PrismaticJointDef, hertz, "hertz" ),
	MB3_F( b3PrismaticJointDef, dampingRatio, "dampingRatio" ),
	MB3_F( b3PrismaticJointDef, targetTranslation, "targetTranslation" ),
	MB3_Z( b3PrismaticJointDef, enableLimit, "enableLimit" ),
	MB3_F( b3PrismaticJointDef, lowerTranslation, "lowerTranslation" ),
	MB3_F( b3PrismaticJointDef, upperTranslation, "upperTranslation" ),
	MB3_Z( b3PrismaticJointDef, enableMotor, "enableMotor" ),
	MB3_F( b3PrismaticJointDef, maxMotorForce, "maxMotorForce" ),
	MB3_F( b3PrismaticJointDef, motorSpeed, "motorSpeed" ),
};

static mb3_def_field s_revoluteFields[] = {
	MB3_F( b3RevoluteJointDef, targetAngle, "targetAngle" ),
	MB3_Z( b3RevoluteJointDef, enableSpring, "enableSpring" ),
	MB3_F( b3RevoluteJointDef, hertz, "hertz" ),
	MB3_F( b3RevoluteJointDef, dampingRatio, "dampingRatio" ),
	MB3_Z( b3RevoluteJointDef, enableLimit, "enableLimit" ),
	MB3_F( b3RevoluteJointDef, lowerAngle, "lowerAngle" ),
	MB3_F( b3RevoluteJointDef, upperAngle, "upperAngle" ),
	MB3_Z( b3RevoluteJointDef, enableMotor, "enableMotor" ),
	MB3_F( b3RevoluteJointDef, maxMotorTorque, "maxMotorTorque" ),
	MB3_F( b3RevoluteJointDef, motorSpeed, "motorSpeed" ),
};

static mb3_def_field s_sphericalFields[] = {
	MB3_Z( b3SphericalJointDef, enableSpring, "enableSpring" ),
	MB3_F( b3SphericalJointDef, hertz, "hertz" ),
	MB3_F( b3SphericalJointDef, dampingRatio, "dampingRatio" ),
	MB3_F( b3SphericalJointDef, targetRotation.v.x, "targetRotationX" ),
	MB3_F( b3SphericalJointDef, targetRotation.v.y, "targetRotationY" ),
	MB3_F( b3SphericalJointDef, targetRotation.v.z, "targetRotationZ" ),
	MB3_F( b3SphericalJointDef, targetRotation.s, "targetRotationW" ),
	MB3_Z( b3SphericalJointDef, enableConeLimit, "enableConeLimit" ),
	MB3_F( b3SphericalJointDef, coneAngle, "coneAngle" ),
	MB3_Z( b3SphericalJointDef, enableTwistLimit, "enableTwistLimit" ),
	MB3_F( b3SphericalJointDef, lowerTwistAngle, "lowerTwistAngle" ),
	MB3_F( b3SphericalJointDef, upperTwistAngle, "upperTwistAngle" ),
	MB3_Z( b3SphericalJointDef, enableMotor, "enableMotor" ),
	MB3_F( b3SphericalJointDef, maxMotorTorque, "maxMotorTorque" ),
	MB3_F( b3SphericalJointDef, motorVelocity.x, "motorVelocityX" ),
	MB3_F( b3SphericalJointDef, motorVelocity.y, "motorVelocityY" ),
	MB3_F( b3SphericalJointDef, motorVelocity.z, "motorVelocityZ" ),
};

static mb3_def_field s_weldFields[] = {
	MB3_F( b3WeldJointDef, linearHertz, "linearHertz" ),
	MB3_F( b3WeldJointDef, angularHertz, "angularHertz" ),
	MB3_F( b3WeldJointDef, linearDampingRatio, "linearDampingRatio" ),
	MB3_F( b3WeldJointDef, angularDampingRatio, "angularDampingRatio" ),
};

static mb3_def_field s_wheelFields[] = {
	MB3_Z( b3WheelJointDef, enableSuspensionSpring, "enableSuspensionSpring" ),
	MB3_F( b3WheelJointDef, suspensionHertz, "suspensionHertz" ),
	MB3_F( b3WheelJointDef, suspensionDampingRatio, "suspensionDampingRatio" ),
	MB3_Z( b3WheelJointDef, enableSuspensionLimit, "enableSuspensionLimit" ),
	MB3_F( b3WheelJointDef, lowerSuspensionLimit, "lowerSuspensionLimit" ),
	MB3_F( b3WheelJointDef, upperSuspensionLimit, "upperSuspensionLimit" ),
	MB3_Z( b3WheelJointDef, enableSpinMotor, "enableSpinMotor" ),
	MB3_F( b3WheelJointDef, maxSpinTorque, "maxSpinTorque" ),
	MB3_F( b3WheelJointDef, spinSpeed, "spinSpeed" ),
	MB3_Z( b3WheelJointDef, enableSteering, "enableSteering" ),
	MB3_F( b3WheelJointDef, steeringHertz, "steeringHertz" ),
	MB3_F( b3WheelJointDef, steeringDampingRatio, "steeringDampingRatio" ),
	MB3_F( b3WheelJointDef, targetSteeringAngle, "targetSteeringAngle" ),
	MB3_F( b3WheelJointDef, maxSteeringTorque, "maxSteeringTorque" ),
	MB3_Z( b3WheelJointDef, enableSteeringLimit, "enableSteeringLimit" ),
	MB3_F( b3WheelJointDef, lowerSteeringLimit, "lowerSteeringLimit" ),
	MB3_F( b3WheelJointDef, upperSteeringLimit, "upperSteeringLimit" ),
};

// MineBox3D's constraint joint: its settings are their own Java object (ConstraintJointSettings), which a profile swap
// passes alone; its drives and limit responses are objects of their own in turn.
static mb3_def_field s_constraintDriveFields[] = {
	MB3_Z( b3ConstraintDrive, positionDrive, "positionDrive" ),
	MB3_Z( b3ConstraintDrive, velocityDrive, "velocityDrive" ),
	MB3_F( b3ConstraintDrive, spring.stiffness, "stiffness" ),
	MB3_F( b3ConstraintDrive, spring.damping, "damping" ),
	MB3_Z( b3ConstraintDrive, spring.accelerationMode, "accelerationMode" ),
	MB3_F( b3ConstraintDrive, maxForce, "maxForce" ),
};

static mb3_def_field s_constraintResponseFields[] = {
	MB3_Z( b3ConstraintLimitResponse, soft, "soft" ),
	MB3_F( b3ConstraintLimitResponse, spring.stiffness, "stiffness" ),
	MB3_F( b3ConstraintLimitResponse, spring.damping, "damping" ),
	MB3_Z( b3ConstraintLimitResponse, spring.accelerationMode, "accelerationMode" ),
	MB3_F( b3ConstraintLimitResponse, restitution, "restitution" ),
};

#define MB3_MOTION( member ) MB3_E( b3ConstraintJointSettings, member, #member, MB3_CLASS_CONSTRAINT_MOTION, "ConstraintMotion" )
#define MB3_DRIVE( member )                                                                                                     \
	MB3_O( b3ConstraintJointSettings, member, #member, MB3_CLASS_CONSTRAINT_DRIVE, "ConstraintDrive", s_constraintDriveFields )
#define MB3_RESPONSE( member )                                                                                                  \
	MB3_O( b3ConstraintJointSettings, member, #member, MB3_CLASS_CONSTRAINT_LIMIT_RESPONSE, "ConstraintLimitResponse",          \
		   s_constraintResponseFields )

static mb3_def_field s_constraintSettingsFields[] = {
	MB3_MOTION( linearMotionX ),
	MB3_MOTION( linearMotionY ),
	MB3_MOTION( linearMotionZ ),
	MB3_F( b3ConstraintJointSettings, linearLimit, "linearLimit" ),
	MB3_RESPONSE( linearLimitResponse ),
	MB3_MOTION( twistMotion ),
	MB3_MOTION( swing1Motion ),
	MB3_MOTION( swing2Motion ),
	MB3_F( b3ConstraintJointSettings, lowerTwistLimit, "lowerTwistLimit" ),
	MB3_F( b3ConstraintJointSettings, upperTwistLimit, "upperTwistLimit" ),
	MB3_F( b3ConstraintJointSettings, swing1Limit, "swing1Limit" ),
	MB3_F( b3ConstraintJointSettings, swing2Limit, "swing2Limit" ),
	MB3_E( b3ConstraintJointSettings, swingLimitShape, "swingLimitShape", MB3_CLASS_SWING_LIMIT_SHAPE, "SwingLimitShape" ),
	MB3_RESPONSE( twistLimitResponse ),
	MB3_RESPONSE( swingLimitResponse ),
	MB3_DRIVE( linearDriveX ),
	MB3_DRIVE( linearDriveY ),
	MB3_DRIVE( linearDriveZ ),
	MB3_F( b3ConstraintJointSettings, linearPositionTarget.x, "linearPositionTargetX" ),
	MB3_F( b3ConstraintJointSettings, linearPositionTarget.y, "linearPositionTargetY" ),
	MB3_F( b3ConstraintJointSettings, linearPositionTarget.z, "linearPositionTargetZ" ),
	MB3_F( b3ConstraintJointSettings, linearVelocityTarget.x, "linearVelocityTargetX" ),
	MB3_F( b3ConstraintJointSettings, linearVelocityTarget.y, "linearVelocityTargetY" ),
	MB3_F( b3ConstraintJointSettings, linearVelocityTarget.z, "linearVelocityTargetZ" ),
	MB3_E( b3ConstraintJointSettings, angularDriveMode, "angularDriveMode", MB3_CLASS_ANGULAR_DRIVE_MODE, "AngularDriveMode" ),
	MB3_DRIVE( slerpDrive ),
	MB3_DRIVE( twistDrive ),
	MB3_DRIVE( swingDrive ),
	MB3_F( b3ConstraintJointSettings, angularOrientationTarget.v.x, "angularOrientationTargetX" ),
	MB3_F( b3ConstraintJointSettings, angularOrientationTarget.v.y, "angularOrientationTargetY" ),
	MB3_F( b3ConstraintJointSettings, angularOrientationTarget.v.z, "angularOrientationTargetZ" ),
	MB3_F( b3ConstraintJointSettings, angularOrientationTarget.s, "angularOrientationTargetW" ),
	MB3_F( b3ConstraintJointSettings, angularVelocityTarget.x, "angularVelocityTargetX" ),
	MB3_F( b3ConstraintJointSettings, angularVelocityTarget.y, "angularVelocityTargetY" ),
	MB3_F( b3ConstraintJointSettings, angularVelocityTarget.z, "angularVelocityTargetZ" ),
	MB3_F( b3ConstraintJointSettings, parentInverseMassScale, "parentInverseMassScale" ),
	MB3_Z( b3ConstraintJointSettings, massConditioning, "massConditioning" ),
};

static mb3_def_field s_constraintFields[] = {
	MB3_O( b3ConstraintJointDef, settings, "settings", MB3_CLASS_CONSTRAINT_JOINT_SETTINGS, "ConstraintJointSettings",
		   s_constraintSettingsFields ),
};

// Per b3JointType: the class's own fields (the base ones are shared) and the class index B3.boundClasses gives.
typedef struct mb3_joint_kind
{
	mb3_def_field* fields;
	int count;
	int classIndex;
} mb3_joint_kind;

static mb3_joint_kind s_kinds[] = {
	[b3_parallelJoint] = { MB3_TABLE( s_parallelFields ), MB3_CLASS_PARALLEL_JOINT_DEF },
	[b3_distanceJoint] = { MB3_TABLE( s_distanceFields ), MB3_CLASS_DISTANCE_JOINT_DEF },
	[b3_filterJoint] = { NULL, 0, -1 },
	[b3_motorJoint] = { MB3_TABLE( s_motorFields ), MB3_CLASS_MOTOR_JOINT_DEF },
	[b3_prismaticJoint] = { MB3_TABLE( s_prismaticFields ), MB3_CLASS_PRISMATIC_JOINT_DEF },
	[b3_revoluteJoint] = { MB3_TABLE( s_revoluteFields ), MB3_CLASS_REVOLUTE_JOINT_DEF },
	[b3_sphericalJoint] = { MB3_TABLE( s_sphericalFields ), MB3_CLASS_SPHERICAL_JOINT_DEF },
	[b3_weldJoint] = { MB3_TABLE( s_weldFields ), MB3_CLASS_WELD_JOINT_DEF },
	[b3_wheelJoint] = { MB3_TABLE( s_wheelFields ), MB3_CLASS_WHEEL_JOINT_DEF },
	[b3_moverJoint] = { MB3_TABLE( s_moverFields ), MB3_CLASS_MOVER_JOINT_DEF },
	[b3_pogoJoint] = { MB3_TABLE( s_pogoFields ), MB3_CLASS_POGO_JOINT_DEF },
	[b3_motionLockJoint] = { MB3_TABLE( s_motionLockFields ), MB3_CLASS_MOTION_LOCK_JOINT_DEF },
	[b3_constraintJoint] = { MB3_TABLE( s_constraintFields ), MB3_CLASS_CONSTRAINT_JOINT_DEF },
};

#define MB3_JOINT_TYPE_COUNT ( (int)( sizeof( s_kinds ) / sizeof( s_kinds[0] ) ) )

static bool mb3_lookup_fields( JNIEnv* env, jobjectArray classes, jclass type, mb3_def_field* fields, int count )
{
	for ( int i = 0; i < count; ++i )
	{
		mb3_def_field* field = fields + i;
		const char* signature = field->kind == 'F' ? "F" : field->kind == 'Z' ? "Z" : field->kind == 'J' ? "J" : field->signature;
		field->id = ( *env )->GetFieldID( env, type, field->name, signature );
		if ( field->id == NULL )
		{
			return false; // NoSuchFieldError pending, naming the field
		}

		if ( field->kind == 'O' )
		{
			jclass nested = (jclass)( *env )->GetObjectArrayElement( env, classes, field->classIndex );
			if ( mb3_lookup_fields( env, classes, nested, field->fields, field->count ) == false )
			{
				return false;
			}
		}
		else if ( field->kind == 'E' && s_enumConstants[field->classIndex] == NULL )
		{
			jclass enumType = (jclass)( *env )->GetObjectArrayElement( env, classes, field->classIndex );
			jclass classType = ( *env )->GetObjectClass( env, enumType );
			jmethodID constants = ( *env )->GetMethodID( env, classType, "getEnumConstants", "()[Ljava/lang/Object;" );
			jobject values = constants == NULL ? NULL : ( *env )->CallObjectMethod( env, enumType, constants );
			if ( values == NULL )
			{
				return false;
			}
			s_enumConstants[field->classIndex] = (jobjectArray)( *env )->NewGlobalRef( env, values );
		}
	}
	return true;
}

bool mb3_joint_defs_init( JNIEnv* env, jobjectArray classes )
{
	jclass base = (jclass)( *env )->GetObjectArrayElement( env, classes, MB3_CLASS_JOINT_DEF );
	if ( mb3_lookup_fields( env, classes, base, MB3_TABLE( s_baseFields ) ) == false )
	{
		return false;
	}
	for ( int t = 0; t < MB3_JOINT_TYPE_COUNT; ++t )
	{
		if ( s_kinds[t].count == 0 )
		{
			continue;
		}
		jclass type = (jclass)( *env )->GetObjectArrayElement( env, classes, s_kinds[t].classIndex );
		if ( mb3_lookup_fields( env, classes, type, s_kinds[t].fields, s_kinds[t].count ) == false )
		{
			return false;
		}
	}
	return true;
}

static void mb3_throw_null_field( JNIEnv* env, const char* name )
{
	char message[96];
	snprintf( message, sizeof( message ), "%s is null", name );
	mb3_throw_illegal_argument( env, message );
}

// A nested object a constructor has not made yet is left alone: it is made with its own defaults.
static void mb3_fields_to_java( JNIEnv* env, jobject target, const void* def, const mb3_def_field* fields, int count )
{
	const char* base = def;
	for ( int i = 0; i < count; ++i )
	{
		const char* at = base + fields[i].offset;
		switch ( fields[i].kind )
		{
			case 'F':
				( *env )->SetFloatField( env, target, fields[i].id, *(const float*)at );
				break;
			case 'Z':
				( *env )->SetBooleanField( env, target, fields[i].id, *(const bool*)at ? JNI_TRUE : JNI_FALSE );
				break;
			case 'E':
			{
				jobjectArray constants = s_enumConstants[fields[i].classIndex];
				int value = *(const int*)at;
				if ( 0 <= value && value < ( *env )->GetArrayLength( env, constants ) )
				{
					jobject constant = ( *env )->GetObjectArrayElement( env, constants, value );
					( *env )->SetObjectField( env, target, fields[i].id, constant );
					( *env )->DeleteLocalRef( env, constant );
				}
				break;
			}
			case 'O':
			{
				jobject nested = ( *env )->GetObjectField( env, target, fields[i].id );
				if ( nested != NULL )
				{
					mb3_fields_to_java( env, nested, at, fields[i].fields, fields[i].count );
					( *env )->DeleteLocalRef( env, nested );
				}
				break;
			}
			default:
				( *env )->SetLongField( env, target, fields[i].id, (jlong)( *(void* const*)at ) );
				break;
		}
	}
}

// Returns false with an exception pending for a missing enum or nested object.
static bool mb3_fields_from_java( JNIEnv* env, jobject source, void* def, const mb3_def_field* fields, int count )
{
	char* base = def;
	for ( int i = 0; i < count; ++i )
	{
		char* at = base + fields[i].offset;
		switch ( fields[i].kind )
		{
			case 'F':
				*(float*)at = ( *env )->GetFloatField( env, source, fields[i].id );
				break;
			case 'Z':
				*(bool*)at = ( *env )->GetBooleanField( env, source, fields[i].id ) != JNI_FALSE;
				break;
			case 'E':
			{
				jobject value = ( *env )->GetObjectField( env, source, fields[i].id );
				int ordinal = mb3_enum_ordinal( env, value );
				( *env )->DeleteLocalRef( env, value );
				if ( ordinal < 0 )
				{
					mb3_throw_null_field( env, fields[i].name );
					return false;
				}
				*(int*)at = ordinal;
				break;
			}
			case 'O':
			{
				jobject nested = ( *env )->GetObjectField( env, source, fields[i].id );
				if ( nested == NULL )
				{
					mb3_throw_null_field( env, fields[i].name );
					return false;
				}
				bool read = mb3_fields_from_java( env, nested, at, fields[i].fields, fields[i].count );
				( *env )->DeleteLocalRef( env, nested );
				if ( read == false )
				{
					return false;
				}
				break;
			}
			default:
				*(void**)at = (void*)(intptr_t)( *env )->GetLongField( env, source, fields[i].id );
				break;
		}
	}
	return true;
}

typedef union mb3_any_joint_def
{
	b3JointDef base;
	b3ParallelJointDef parallel;
	b3DistanceJointDef distance;
	b3FilterJointDef filter;
	b3MotorJointDef motor;
	b3PrismaticJointDef prismatic;
	b3RevoluteJointDef revolute;
	b3SphericalJointDef spherical;
	b3WeldJointDef weld;
	b3WheelJointDef wheel;
	b3MoverJointDef mover;
	b3PogoJointDef pogo;
	b3MotionLockJointDef motionLock;
	b3ConstraintJointDef constraint;
} mb3_any_joint_def;

static bool mb3_default_joint_def( int jointType, mb3_any_joint_def* def )
{
	switch ( jointType )
	{
		case b3_parallelJoint:
			def->parallel = b3DefaultParallelJointDef();
			return true;
		case b3_distanceJoint:
			def->distance = b3DefaultDistanceJointDef();
			return true;
		case b3_filterJoint:
			def->filter = b3DefaultFilterJointDef();
			return true;
		case b3_motorJoint:
			def->motor = b3DefaultMotorJointDef();
			return true;
		case b3_prismaticJoint:
			def->prismatic = b3DefaultPrismaticJointDef();
			return true;
		case b3_revoluteJoint:
			def->revolute = b3DefaultRevoluteJointDef();
			return true;
		case b3_sphericalJoint:
			def->spherical = b3DefaultSphericalJointDef();
			return true;
		case b3_weldJoint:
			def->weld = b3DefaultWeldJointDef();
			return true;
		case b3_wheelJoint:
			def->wheel = b3DefaultWheelJointDef();
			return true;
		case b3_moverJoint:
			def->mover = b3DefaultMoverJointDef();
			return true;
		case b3_pogoJoint:
			def->pogo = b3DefaultPogoJointDef();
			return true;
		case b3_motionLockJoint:
			def->motionLock = b3DefaultMotionLockJointDef();
			return true;
		case b3_constraintJoint:
			def->constraint = b3DefaultConstraintJointDef();
			return true;
		default:
			return false;
	}
}

MB3_JNI( void, defaultJointDef )( JNIEnv* env, jclass type, jint jointType, jobject target )
{
	(void)type;
	mb3_any_joint_def def;
	if ( target == NULL || mb3_default_joint_def( jointType, &def ) == false )
	{
		mb3_throw_illegal_argument( env, "not a joint type, or no definition" );
		return;
	}
	mb3_fields_to_java( env, target, &def, MB3_TABLE( s_baseFields ) );
	mb3_fields_to_java( env, target, &def, s_kinds[jointType].fields, s_kinds[jointType].count );
}

MB3_JNI( jlong, createJoint )( JNIEnv* env, jclass type, jint world, jint jointType, jlong bodyA, jlong bodyB,
							   jobject definition )
{
	(void)type;
	mb3_any_joint_def def;
	if ( definition == NULL || mb3_default_joint_def( jointType, &def ) == false )
	{
		mb3_throw_illegal_argument( env, "not a joint type, or no definition" );
		return 0;
	}
	if ( mb3_fields_from_java( env, definition, &def, MB3_TABLE( s_baseFields ) ) == false ||
		 mb3_fields_from_java( env, definition, &def, s_kinds[jointType].fields, s_kinds[jointType].count ) == false ||
		 ( *env )->ExceptionCheck( env ) )
	{
		return 0;
	}
	def.base.bodyIdA = mb3_body( bodyA );
	def.base.bodyIdB = mb3_body( bodyB );
	def.base.localFrameA.q = mb3_normalized( def.base.localFrameA.q );
	def.base.localFrameB.q = mb3_normalized( def.base.localFrameB.q );
	if ( jointType == b3_sphericalJoint )
	{
		def.spherical.targetRotation = mb3_normalized( def.spherical.targetRotation );
	}
	else if ( jointType == b3_constraintJoint )
	{
		def.constraint.settings.angularOrientationTarget = mb3_normalized( def.constraint.settings.angularOrientationTarget );
	}

	b3WorldId worldId = mb3_world( world );
	b3JointId id = b3_nullJointId;
	switch ( jointType )
	{
		case b3_parallelJoint:
			id = b3CreateParallelJoint( worldId, &def.parallel );
			break;
		case b3_distanceJoint:
			id = b3CreateDistanceJoint( worldId, &def.distance );
			break;
		case b3_filterJoint:
			id = b3CreateFilterJoint( worldId, &def.filter );
			break;
		case b3_motorJoint:
			id = b3CreateMotorJoint( worldId, &def.motor );
			break;
		case b3_prismaticJoint:
			id = b3CreatePrismaticJoint( worldId, &def.prismatic );
			break;
		case b3_revoluteJoint:
			id = b3CreateRevoluteJoint( worldId, &def.revolute );
			break;
		case b3_sphericalJoint:
			id = b3CreateSphericalJoint( worldId, &def.spherical );
			break;
		case b3_weldJoint:
			id = b3CreateWeldJoint( worldId, &def.weld );
			break;
		case b3_wheelJoint:
			id = b3CreateWheelJoint( worldId, &def.wheel );
			break;
		case b3_moverJoint:
			id = b3CreateMoverJoint( worldId, &def.mover );
			break;
		case b3_pogoJoint:
			id = b3CreatePogoJoint( worldId, &def.pogo );
			break;
		case b3_motionLockJoint:
			id = b3CreateMotionLockJoint( worldId, &def.motionLock );
			break;
		case b3_constraintJoint:
			id = b3CreateConstraintJoint( worldId, &def.constraint );
			break;
		default:
			break;
	}
	// A failed check does not stop Box3D making the joint: Java never gets its id, so it would be an orphan
	if ( mb3_assert_pending() && B3_IS_NON_NULL( id ) )
	{
		b3DestroyJoint( id, false );
	}
	if ( mb3_check( env ) )
	{
		return 0;
	}
	if ( B3_IS_NULL( id ) )
	{
		mb3_throw( env, "Box3D could not create the joint" );
		return 0;
	}
	return mb3_pack_joint( id );
}

MB3_JNI( void, destroyJoint )( JNIEnv* env, jclass type, jlong joint, jboolean wakeAttached )
{
	(void)type;
	b3DestroyJoint( mb3_joint( joint ), wakeAttached );
	mb3_check( env );
}

MB3_JNI( jboolean, jointIsValid )( JNIEnv* env, jclass type, jlong joint )
{
	(void)env;
	(void)type;
	return b3Joint_IsValid( mb3_joint( joint ) ) ? JNI_TRUE : JNI_FALSE;
}

MB3_JNI( jint, jointGetType )( JNIEnv* env, jclass type, jlong joint )
{
	(void)type;
	b3JointType jointType = b3Joint_GetType( mb3_joint( joint ) );
	mb3_check( env );
	return (jint)jointType;
}

// Frame A or B in that body's space, from its origin.
MB3_JNI( void, jointSetLocalFrame )( JNIEnv* env, jclass type, jlong joint, jboolean frameB, jfloat px, jfloat py, jfloat pz,
									 jfloat qx, jfloat qy, jfloat qz, jfloat qw )
{
	(void)type;
	b3Transform frame = { mb3_vec3( px, py, pz ), mb3_quat( qx, qy, qz, qw ) };
	if ( frameB )
	{
		b3Joint_SetLocalFrameB( mb3_joint( joint ), frame );
	}
	else
	{
		b3Joint_SetLocalFrameA( mb3_joint( joint ), frame );
	}
	mb3_check( env );
}

MB3_JNI( void, jointGetLocalFrame )( JNIEnv* env, jclass type, jlong joint, jboolean frameB, jfloatArray out )
{
	(void)type;
	b3Transform frame = frameB ? b3Joint_GetLocalFrameB( mb3_joint( joint ) ) : b3Joint_GetLocalFrameA( mb3_joint( joint ) );
	if ( mb3_check( env ) == false )
	{
		float values[7] = { frame.p.x, frame.p.y, frame.p.z, frame.q.v.x, frame.q.v.y, frame.q.v.z, frame.q.s };
		mb3_put_floats( env, out, 0, values, 7 );
	}
}

// ---- runtime properties ---------------------------------------------------------------------------------------------
// ⚠️ The numbers are JointParam's in Java. A group of ten (or twenty) per joint type; the base ones work on any joint.
// Booleans travel as 0 / 1, ranges as two values, vectors as three, a rotation as four.

enum mb3_joint_param
{
	MB3_JP_FORCE_THRESHOLD = 0,
	MB3_JP_TORQUE_THRESHOLD = 1,
	MB3_JP_CONSTRAINT_TUNING = 2,
	MB3_JP_COLLIDE_CONNECTED = 3,
	MB3_JP_LINEAR_SEPARATION = 4,
	MB3_JP_ANGULAR_SEPARATION = 5,
	MB3_JP_CONSTRAINT_FORCE = 6,
	MB3_JP_CONSTRAINT_TORQUE = 7,
	MB3_JP_AWAKE = 8,

	MB3_JP_DISTANCE_LENGTH = 10,
	MB3_JP_DISTANCE_SPRING = 11,
	MB3_JP_DISTANCE_SPRING_FORCE_RANGE = 12,
	MB3_JP_DISTANCE_HERTZ = 13,
	MB3_JP_DISTANCE_DAMPING = 14,
	MB3_JP_DISTANCE_LIMIT = 15,
	MB3_JP_DISTANCE_LENGTH_RANGE = 16,
	MB3_JP_DISTANCE_CURRENT_LENGTH = 17,
	MB3_JP_DISTANCE_MOTOR = 18,
	MB3_JP_DISTANCE_MOTOR_SPEED = 19,
	MB3_JP_DISTANCE_MAX_MOTOR_FORCE = 20,
	MB3_JP_DISTANCE_MOTOR_FORCE = 21,

	MB3_JP_MOTOR_LINEAR_VELOCITY = 30,
	MB3_JP_MOTOR_ANGULAR_VELOCITY = 31,
	MB3_JP_MOTOR_MAX_VELOCITY_FORCE = 32,
	MB3_JP_MOTOR_MAX_VELOCITY_TORQUE = 33,
	MB3_JP_MOTOR_LINEAR_HERTZ = 34,
	MB3_JP_MOTOR_LINEAR_DAMPING = 35,
	MB3_JP_MOTOR_ANGULAR_HERTZ = 36,
	MB3_JP_MOTOR_ANGULAR_DAMPING = 37,
	MB3_JP_MOTOR_MAX_SPRING_FORCE = 38,
	MB3_JP_MOTOR_MAX_SPRING_TORQUE = 39,

	MB3_JP_PARALLEL_HERTZ = 40,
	MB3_JP_PARALLEL_DAMPING = 41,
	MB3_JP_PARALLEL_MAX_TORQUE = 42,

	MB3_JP_PRISMATIC_SPRING = 50,
	MB3_JP_PRISMATIC_HERTZ = 51,
	MB3_JP_PRISMATIC_DAMPING = 52,
	MB3_JP_PRISMATIC_TARGET_TRANSLATION = 53,
	MB3_JP_PRISMATIC_LIMIT = 54,
	MB3_JP_PRISMATIC_LIMITS = 55,
	MB3_JP_PRISMATIC_MOTOR = 56,
	MB3_JP_PRISMATIC_MOTOR_SPEED = 57,
	MB3_JP_PRISMATIC_MAX_MOTOR_FORCE = 58,
	MB3_JP_PRISMATIC_MOTOR_FORCE = 59,
	MB3_JP_PRISMATIC_TRANSLATION = 60,
	MB3_JP_PRISMATIC_SPEED = 61,

	MB3_JP_REVOLUTE_SPRING = 70,
	MB3_JP_REVOLUTE_HERTZ = 71,
	MB3_JP_REVOLUTE_DAMPING = 72,
	MB3_JP_REVOLUTE_TARGET_ANGLE = 73,
	MB3_JP_REVOLUTE_ANGLE = 74,
	MB3_JP_REVOLUTE_LIMIT = 75,
	MB3_JP_REVOLUTE_LIMITS = 76,
	MB3_JP_REVOLUTE_MOTOR = 77,
	MB3_JP_REVOLUTE_MOTOR_SPEED = 78,
	MB3_JP_REVOLUTE_MOTOR_TORQUE = 79,
	MB3_JP_REVOLUTE_MAX_MOTOR_TORQUE = 80,

	MB3_JP_SPHERICAL_CONE_LIMIT = 90,
	MB3_JP_SPHERICAL_CONE_LIMIT_ANGLE = 91,
	MB3_JP_SPHERICAL_CONE_ANGLE = 92,
	MB3_JP_SPHERICAL_TWIST_LIMIT = 93,
	MB3_JP_SPHERICAL_TWIST_LIMITS = 94,
	MB3_JP_SPHERICAL_TWIST_ANGLE = 95,
	MB3_JP_SPHERICAL_SPRING = 96,
	MB3_JP_SPHERICAL_HERTZ = 97,
	MB3_JP_SPHERICAL_DAMPING = 98,
	MB3_JP_SPHERICAL_TARGET_ROTATION = 99,
	MB3_JP_SPHERICAL_MOTOR = 100,
	MB3_JP_SPHERICAL_MOTOR_VELOCITY = 101,
	MB3_JP_SPHERICAL_MOTOR_TORQUE = 102,
	MB3_JP_SPHERICAL_MAX_MOTOR_TORQUE = 103,

	MB3_JP_WELD_LINEAR_HERTZ = 110,
	MB3_JP_WELD_LINEAR_DAMPING = 111,
	MB3_JP_WELD_ANGULAR_HERTZ = 112,
	MB3_JP_WELD_ANGULAR_DAMPING = 113,

	MB3_JP_WHEEL_SUSPENSION = 120,
	MB3_JP_WHEEL_SUSPENSION_HERTZ = 121,
	MB3_JP_WHEEL_SUSPENSION_DAMPING = 122,
	MB3_JP_WHEEL_SUSPENSION_LIMIT = 123,
	MB3_JP_WHEEL_SUSPENSION_LIMITS = 124,
	MB3_JP_WHEEL_SPIN_MOTOR = 125,
	MB3_JP_WHEEL_SPIN_MOTOR_SPEED = 126,
	MB3_JP_WHEEL_MAX_SPIN_TORQUE = 127,
	MB3_JP_WHEEL_SPIN_SPEED = 128,
	MB3_JP_WHEEL_SPIN_TORQUE = 129,
	MB3_JP_WHEEL_STEERING = 130,
	MB3_JP_WHEEL_STEERING_HERTZ = 131,
	MB3_JP_WHEEL_STEERING_DAMPING = 132,
	MB3_JP_WHEEL_MAX_STEERING_TORQUE = 133,
	MB3_JP_WHEEL_STEERING_LIMIT = 134,
	MB3_JP_WHEEL_STEERING_LIMITS = 135,
	MB3_JP_WHEEL_TARGET_STEERING_ANGLE = 136,
	MB3_JP_WHEEL_STEERING_ANGLE = 137,
	MB3_JP_WHEEL_STEERING_TORQUE = 138,

	MB3_JP_MOVER_LINEAR_VELOCITY = 140,
	MB3_JP_MOVER_MAX_FORCE = 141,

	MB3_JP_POGO_REST_LENGTH = 150,
	MB3_JP_POGO_HERTZ = 151,
	MB3_JP_POGO_DAMPING = 152,
	MB3_JP_POGO_MAX_TENSION_FORCE = 153,
	MB3_JP_POGO_MAX_COMPRESSION_FORCE = 154,
	MB3_JP_POGO_NORMAL = 155,
	MB3_JP_POGO_CURRENT_LENGTH = 156,
	MB3_JP_POGO_CURRENT_SPEED = 157,
	MB3_JP_POGO_IMPULSE = 158,

	MB3_JP_MOTION_LOCK_LINEAR_X = 160,
	MB3_JP_MOTION_LOCK_LINEAR_Y = 161,
	MB3_JP_MOTION_LOCK_LINEAR_Z = 162,
	MB3_JP_MOTION_LOCK_ANGULAR = 163,
};

// The joint type a parameter belongs to, -1 for the base ones.
static int mb3_param_joint_type( int param )
{
	if ( param < 10 )
		return -1;
	if ( param < 30 )
		return b3_distanceJoint;
	if ( param < 40 )
		return b3_motorJoint;
	if ( param < 50 )
		return b3_parallelJoint;
	if ( param < 70 )
		return b3_prismaticJoint;
	if ( param < 90 )
		return b3_revoluteJoint;
	if ( param < 110 )
		return b3_sphericalJoint;
	if ( param < 120 )
		return b3_weldJoint;
	if ( param < 140 )
		return b3_wheelJoint;
	if ( param < 150 )
		return b3_moverJoint;
	if ( param < 160 )
		return b3_pogoJoint;
	return b3_motionLockJoint;
}

// Box3D asserts on a property of another joint type; say which instead.
static bool mb3_param_fits( JNIEnv* env, b3JointId joint, int param )
{
	int wanted = mb3_param_joint_type( param );
	if ( wanted < 0 )
	{
		return true;
	}
	b3JointType actual = b3Joint_GetType( joint );
	if ( mb3_check( env ) )
	{
		return false;
	}
	if ( (int)actual != wanted )
	{
		mb3_throw_illegal_argument( env, "joint property of another joint type" );
		return false;
	}
	return true;
}

MB3_JNI( void, jointSet )( JNIEnv* env, jclass type, jlong joint, jint param, jfloat a, jfloat b, jfloat c, jfloat d )
{
	(void)type;
	b3JointId id = mb3_joint( joint );
	if ( mb3_param_fits( env, id, param ) == false )
	{
		return;
	}
	bool on = a != 0.0f;
	b3Vec3 v = mb3_vec3( a, b, c );
	switch ( param )
	{
		case MB3_JP_FORCE_THRESHOLD: b3Joint_SetForceThreshold( id, a ); break;
		case MB3_JP_TORQUE_THRESHOLD: b3Joint_SetTorqueThreshold( id, a ); break;
		case MB3_JP_CONSTRAINT_TUNING: b3Joint_SetConstraintTuning( id, a, b ); break;
		case MB3_JP_COLLIDE_CONNECTED: b3Joint_SetCollideConnected( id, on ); break;
		case MB3_JP_AWAKE: b3Joint_WakeBodies( id ); break;

		case MB3_JP_DISTANCE_LENGTH: b3DistanceJoint_SetLength( id, a ); break;
		case MB3_JP_DISTANCE_SPRING: b3DistanceJoint_EnableSpring( id, on ); break;
		case MB3_JP_DISTANCE_SPRING_FORCE_RANGE: b3DistanceJoint_SetSpringForceRange( id, a, b ); break;
		case MB3_JP_DISTANCE_HERTZ: b3DistanceJoint_SetSpringHertz( id, a ); break;
		case MB3_JP_DISTANCE_DAMPING: b3DistanceJoint_SetSpringDampingRatio( id, a ); break;
		case MB3_JP_DISTANCE_LIMIT: b3DistanceJoint_EnableLimit( id, on ); break;
		case MB3_JP_DISTANCE_LENGTH_RANGE: b3DistanceJoint_SetLengthRange( id, a, b ); break;
		case MB3_JP_DISTANCE_MOTOR: b3DistanceJoint_EnableMotor( id, on ); break;
		case MB3_JP_DISTANCE_MOTOR_SPEED: b3DistanceJoint_SetMotorSpeed( id, a ); break;
		case MB3_JP_DISTANCE_MAX_MOTOR_FORCE: b3DistanceJoint_SetMaxMotorForce( id, a ); break;

		case MB3_JP_MOTOR_LINEAR_VELOCITY: b3MotorJoint_SetLinearVelocity( id, v ); break;
		case MB3_JP_MOTOR_ANGULAR_VELOCITY: b3MotorJoint_SetAngularVelocity( id, v ); break;
		case MB3_JP_MOTOR_MAX_VELOCITY_FORCE: b3MotorJoint_SetMaxVelocityForce( id, a ); break;
		case MB3_JP_MOTOR_MAX_VELOCITY_TORQUE: b3MotorJoint_SetMaxVelocityTorque( id, a ); break;
		case MB3_JP_MOTOR_LINEAR_HERTZ: b3MotorJoint_SetLinearHertz( id, a ); break;
		case MB3_JP_MOTOR_LINEAR_DAMPING: b3MotorJoint_SetLinearDampingRatio( id, a ); break;
		case MB3_JP_MOTOR_ANGULAR_HERTZ: b3MotorJoint_SetAngularHertz( id, a ); break;
		case MB3_JP_MOTOR_ANGULAR_DAMPING: b3MotorJoint_SetAngularDampingRatio( id, a ); break;
		case MB3_JP_MOTOR_MAX_SPRING_FORCE: b3MotorJoint_SetMaxSpringForce( id, a ); break;
		case MB3_JP_MOTOR_MAX_SPRING_TORQUE: b3MotorJoint_SetMaxSpringTorque( id, a ); break;

		case MB3_JP_PARALLEL_HERTZ: b3ParallelJoint_SetSpringHertz( id, a ); break;
		case MB3_JP_PARALLEL_DAMPING: b3ParallelJoint_SetSpringDampingRatio( id, a ); break;
		case MB3_JP_PARALLEL_MAX_TORQUE: b3ParallelJoint_SetMaxTorque( id, a ); break;

		case MB3_JP_PRISMATIC_SPRING: b3PrismaticJoint_EnableSpring( id, on ); break;
		case MB3_JP_PRISMATIC_HERTZ: b3PrismaticJoint_SetSpringHertz( id, a ); break;
		case MB3_JP_PRISMATIC_DAMPING: b3PrismaticJoint_SetSpringDampingRatio( id, a ); break;
		case MB3_JP_PRISMATIC_TARGET_TRANSLATION: b3PrismaticJoint_SetTargetTranslation( id, a ); break;
		case MB3_JP_PRISMATIC_LIMIT: b3PrismaticJoint_EnableLimit( id, on ); break;
		case MB3_JP_PRISMATIC_LIMITS: b3PrismaticJoint_SetLimits( id, a, b ); break;
		case MB3_JP_PRISMATIC_MOTOR: b3PrismaticJoint_EnableMotor( id, on ); break;
		case MB3_JP_PRISMATIC_MOTOR_SPEED: b3PrismaticJoint_SetMotorSpeed( id, a ); break;
		case MB3_JP_PRISMATIC_MAX_MOTOR_FORCE: b3PrismaticJoint_SetMaxMotorForce( id, a ); break;

		case MB3_JP_REVOLUTE_SPRING: b3RevoluteJoint_EnableSpring( id, on ); break;
		case MB3_JP_REVOLUTE_HERTZ: b3RevoluteJoint_SetSpringHertz( id, a ); break;
		case MB3_JP_REVOLUTE_DAMPING: b3RevoluteJoint_SetSpringDampingRatio( id, a ); break;
		case MB3_JP_REVOLUTE_TARGET_ANGLE: b3RevoluteJoint_SetTargetAngle( id, a ); break;
		case MB3_JP_REVOLUTE_LIMIT: b3RevoluteJoint_EnableLimit( id, on ); break;
		case MB3_JP_REVOLUTE_LIMITS: b3RevoluteJoint_SetLimits( id, a, b ); break;
		case MB3_JP_REVOLUTE_MOTOR: b3RevoluteJoint_EnableMotor( id, on ); break;
		case MB3_JP_REVOLUTE_MOTOR_SPEED: b3RevoluteJoint_SetMotorSpeed( id, a ); break;
		case MB3_JP_REVOLUTE_MAX_MOTOR_TORQUE: b3RevoluteJoint_SetMaxMotorTorque( id, a ); break;

		case MB3_JP_SPHERICAL_CONE_LIMIT: b3SphericalJoint_EnableConeLimit( id, on ); break;
		case MB3_JP_SPHERICAL_CONE_LIMIT_ANGLE: b3SphericalJoint_SetConeLimit( id, a ); break;
		case MB3_JP_SPHERICAL_TWIST_LIMIT: b3SphericalJoint_EnableTwistLimit( id, on ); break;
		case MB3_JP_SPHERICAL_TWIST_LIMITS: b3SphericalJoint_SetTwistLimits( id, a, b ); break;
		case MB3_JP_SPHERICAL_SPRING: b3SphericalJoint_EnableSpring( id, on ); break;
		case MB3_JP_SPHERICAL_HERTZ: b3SphericalJoint_SetSpringHertz( id, a ); break;
		case MB3_JP_SPHERICAL_DAMPING: b3SphericalJoint_SetSpringDampingRatio( id, a ); break;
		case MB3_JP_SPHERICAL_TARGET_ROTATION: b3SphericalJoint_SetTargetRotation( id, mb3_quat( a, b, c, d ) ); break;
		case MB3_JP_SPHERICAL_MOTOR: b3SphericalJoint_EnableMotor( id, on ); break;
		case MB3_JP_SPHERICAL_MOTOR_VELOCITY: b3SphericalJoint_SetMotorVelocity( id, v ); break;
		case MB3_JP_SPHERICAL_MAX_MOTOR_TORQUE: b3SphericalJoint_SetMaxMotorTorque( id, a ); break;

		case MB3_JP_WELD_LINEAR_HERTZ: b3WeldJoint_SetLinearHertz( id, a ); break;
		case MB3_JP_WELD_LINEAR_DAMPING: b3WeldJoint_SetLinearDampingRatio( id, a ); break;
		case MB3_JP_WELD_ANGULAR_HERTZ: b3WeldJoint_SetAngularHertz( id, a ); break;
		case MB3_JP_WELD_ANGULAR_DAMPING: b3WeldJoint_SetAngularDampingRatio( id, a ); break;

		case MB3_JP_WHEEL_SUSPENSION: b3WheelJoint_EnableSuspension( id, on ); break;
		case MB3_JP_WHEEL_SUSPENSION_HERTZ: b3WheelJoint_SetSuspensionHertz( id, a ); break;
		case MB3_JP_WHEEL_SUSPENSION_DAMPING: b3WheelJoint_SetSuspensionDampingRatio( id, a ); break;
		case MB3_JP_WHEEL_SUSPENSION_LIMIT: b3WheelJoint_EnableSuspensionLimit( id, on ); break;
		case MB3_JP_WHEEL_SUSPENSION_LIMITS: b3WheelJoint_SetSuspensionLimits( id, a, b ); break;
		case MB3_JP_WHEEL_SPIN_MOTOR: b3WheelJoint_EnableSpinMotor( id, on ); break;
		case MB3_JP_WHEEL_SPIN_MOTOR_SPEED: b3WheelJoint_SetSpinMotorSpeed( id, a ); break;
		case MB3_JP_WHEEL_MAX_SPIN_TORQUE: b3WheelJoint_SetMaxSpinTorque( id, a ); break;
		case MB3_JP_WHEEL_STEERING: b3WheelJoint_EnableSteering( id, on ); break;
		case MB3_JP_WHEEL_STEERING_HERTZ: b3WheelJoint_SetSteeringHertz( id, a ); break;
		case MB3_JP_WHEEL_STEERING_DAMPING: b3WheelJoint_SetSteeringDampingRatio( id, a ); break;
		case MB3_JP_WHEEL_MAX_STEERING_TORQUE: b3WheelJoint_SetMaxSteeringTorque( id, a ); break;
		case MB3_JP_WHEEL_STEERING_LIMIT: b3WheelJoint_EnableSteeringLimit( id, on ); break;
		case MB3_JP_WHEEL_STEERING_LIMITS: b3WheelJoint_SetSteeringLimits( id, a, b ); break;
		case MB3_JP_WHEEL_TARGET_STEERING_ANGLE: b3WheelJoint_SetTargetSteeringAngle( id, a ); break;

		case MB3_JP_MOVER_LINEAR_VELOCITY: b3MoverJoint_SetLinearVelocity( id, v ); break;
		case MB3_JP_MOVER_MAX_FORCE: b3MoverJoint_SetMaxVelocityForce( id, a ); break;

		case MB3_JP_POGO_REST_LENGTH: b3PogoJoint_SetRestLength( id, a ); break;
		case MB3_JP_POGO_HERTZ: b3PogoJoint_SetSpringHertz( id, a ); break;
		case MB3_JP_POGO_DAMPING: b3PogoJoint_SetSpringDampingRatio( id, a ); break;
		case MB3_JP_POGO_MAX_TENSION_FORCE: b3PogoJoint_SetMaxTensionForce( id, a ); break;
		case MB3_JP_POGO_MAX_COMPRESSION_FORCE: b3PogoJoint_SetMaxCompressionForce( id, a ); break;
		case MB3_JP_POGO_NORMAL: b3PogoJoint_SetNormal( id, v ); break;

		case MB3_JP_MOTION_LOCK_LINEAR_X: b3MotionLockJoint_EnableLinearLock( id, 0, on ); break;
		case MB3_JP_MOTION_LOCK_LINEAR_Y: b3MotionLockJoint_EnableLinearLock( id, 1, on ); break;
		case MB3_JP_MOTION_LOCK_LINEAR_Z: b3MotionLockJoint_EnableLinearLock( id, 2, on ); break;
		case MB3_JP_MOTION_LOCK_ANGULAR: b3MotionLockJoint_EnableAngularLock( id, on ); break;

		default:
			mb3_throw_illegal_argument( env, "not a joint property that can be set" );
			return;
	}
	mb3_check( env );
}

MB3_JNI( jint, jointGet )( JNIEnv* env, jclass type, jlong joint, jint param, jfloatArray out )
{
	(void)type;
	b3JointId id = mb3_joint( joint );
	if ( mb3_param_fits( env, id, param ) == false )
	{
		return 0;
	}
	float v[4] = { 0, 0, 0, 0 };
	int n = 1;
	b3Vec3 vector;
	b3Quat rotation;
	switch ( param )
	{
		case MB3_JP_FORCE_THRESHOLD: v[0] = b3Joint_GetForceThreshold( id ); break;
		case MB3_JP_TORQUE_THRESHOLD: v[0] = b3Joint_GetTorqueThreshold( id ); break;
		case MB3_JP_CONSTRAINT_TUNING: b3Joint_GetConstraintTuning( id, v, v + 1 ); n = 2; break;
		case MB3_JP_COLLIDE_CONNECTED: v[0] = b3Joint_GetCollideConnected( id ); break;
		case MB3_JP_LINEAR_SEPARATION: v[0] = b3Joint_GetLinearSeparation( id ); break;
		case MB3_JP_ANGULAR_SEPARATION: v[0] = b3Joint_GetAngularSeparation( id ); break;
		case MB3_JP_CONSTRAINT_FORCE: vector = b3Joint_GetConstraintForce( id ); goto vec3;
		case MB3_JP_CONSTRAINT_TORQUE: vector = b3Joint_GetConstraintTorque( id ); goto vec3;
		case MB3_JP_AWAKE: v[0] = b3Joint_IsAwake( id ); break;

		case MB3_JP_DISTANCE_LENGTH: v[0] = b3DistanceJoint_GetLength( id ); break;
		case MB3_JP_DISTANCE_SPRING: v[0] = b3DistanceJoint_IsSpringEnabled( id ); break;
		case MB3_JP_DISTANCE_SPRING_FORCE_RANGE: b3DistanceJoint_GetSpringForceRange( id, v, v + 1 ); n = 2; break;
		case MB3_JP_DISTANCE_HERTZ: v[0] = b3DistanceJoint_GetSpringHertz( id ); break;
		case MB3_JP_DISTANCE_DAMPING: v[0] = b3DistanceJoint_GetSpringDampingRatio( id ); break;
		case MB3_JP_DISTANCE_LIMIT: v[0] = b3DistanceJoint_IsLimitEnabled( id ); break;
		case MB3_JP_DISTANCE_LENGTH_RANGE:
			v[0] = b3DistanceJoint_GetMinLength( id );
			v[1] = b3DistanceJoint_GetMaxLength( id );
			n = 2;
			break;
		case MB3_JP_DISTANCE_CURRENT_LENGTH: v[0] = b3DistanceJoint_GetCurrentLength( id ); break;
		case MB3_JP_DISTANCE_MOTOR: v[0] = b3DistanceJoint_IsMotorEnabled( id ); break;
		case MB3_JP_DISTANCE_MOTOR_SPEED: v[0] = b3DistanceJoint_GetMotorSpeed( id ); break;
		case MB3_JP_DISTANCE_MAX_MOTOR_FORCE: v[0] = b3DistanceJoint_GetMaxMotorForce( id ); break;
		case MB3_JP_DISTANCE_MOTOR_FORCE: v[0] = b3DistanceJoint_GetMotorForce( id ); break;

		case MB3_JP_MOTOR_LINEAR_VELOCITY: vector = b3MotorJoint_GetLinearVelocity( id ); goto vec3;
		case MB3_JP_MOTOR_ANGULAR_VELOCITY: vector = b3MotorJoint_GetAngularVelocity( id ); goto vec3;
		case MB3_JP_MOTOR_MAX_VELOCITY_FORCE: v[0] = b3MotorJoint_GetMaxVelocityForce( id ); break;
		case MB3_JP_MOTOR_MAX_VELOCITY_TORQUE: v[0] = b3MotorJoint_GetMaxVelocityTorque( id ); break;
		case MB3_JP_MOTOR_LINEAR_HERTZ: v[0] = b3MotorJoint_GetLinearHertz( id ); break;
		case MB3_JP_MOTOR_LINEAR_DAMPING: v[0] = b3MotorJoint_GetLinearDampingRatio( id ); break;
		case MB3_JP_MOTOR_ANGULAR_HERTZ: v[0] = b3MotorJoint_GetAngularHertz( id ); break;
		case MB3_JP_MOTOR_ANGULAR_DAMPING: v[0] = b3MotorJoint_GetAngularDampingRatio( id ); break;
		case MB3_JP_MOTOR_MAX_SPRING_FORCE: v[0] = b3MotorJoint_GetMaxSpringForce( id ); break;
		case MB3_JP_MOTOR_MAX_SPRING_TORQUE: v[0] = b3MotorJoint_GetMaxSpringTorque( id ); break;

		case MB3_JP_PARALLEL_HERTZ: v[0] = b3ParallelJoint_GetSpringHertz( id ); break;
		case MB3_JP_PARALLEL_DAMPING: v[0] = b3ParallelJoint_GetSpringDampingRatio( id ); break;
		case MB3_JP_PARALLEL_MAX_TORQUE: v[0] = b3ParallelJoint_GetMaxTorque( id ); break;

		case MB3_JP_PRISMATIC_SPRING: v[0] = b3PrismaticJoint_IsSpringEnabled( id ); break;
		case MB3_JP_PRISMATIC_HERTZ: v[0] = b3PrismaticJoint_GetSpringHertz( id ); break;
		case MB3_JP_PRISMATIC_DAMPING: v[0] = b3PrismaticJoint_GetSpringDampingRatio( id ); break;
		case MB3_JP_PRISMATIC_TARGET_TRANSLATION: v[0] = b3PrismaticJoint_GetTargetTranslation( id ); break;
		case MB3_JP_PRISMATIC_LIMIT: v[0] = b3PrismaticJoint_IsLimitEnabled( id ); break;
		case MB3_JP_PRISMATIC_LIMITS:
			v[0] = b3PrismaticJoint_GetLowerLimit( id );
			v[1] = b3PrismaticJoint_GetUpperLimit( id );
			n = 2;
			break;
		case MB3_JP_PRISMATIC_MOTOR: v[0] = b3PrismaticJoint_IsMotorEnabled( id ); break;
		case MB3_JP_PRISMATIC_MOTOR_SPEED: v[0] = b3PrismaticJoint_GetMotorSpeed( id ); break;
		case MB3_JP_PRISMATIC_MAX_MOTOR_FORCE: v[0] = b3PrismaticJoint_GetMaxMotorForce( id ); break;
		case MB3_JP_PRISMATIC_MOTOR_FORCE: v[0] = b3PrismaticJoint_GetMotorForce( id ); break;
		case MB3_JP_PRISMATIC_TRANSLATION: v[0] = b3PrismaticJoint_GetTranslation( id ); break;
		case MB3_JP_PRISMATIC_SPEED: v[0] = b3PrismaticJoint_GetSpeed( id ); break;

		case MB3_JP_REVOLUTE_SPRING: v[0] = b3RevoluteJoint_IsSpringEnabled( id ); break;
		case MB3_JP_REVOLUTE_HERTZ: v[0] = b3RevoluteJoint_GetSpringHertz( id ); break;
		case MB3_JP_REVOLUTE_DAMPING: v[0] = b3RevoluteJoint_GetSpringDampingRatio( id ); break;
		case MB3_JP_REVOLUTE_TARGET_ANGLE: v[0] = b3RevoluteJoint_GetTargetAngle( id ); break;
		case MB3_JP_REVOLUTE_ANGLE: v[0] = b3RevoluteJoint_GetAngle( id ); break;
		case MB3_JP_REVOLUTE_LIMIT: v[0] = b3RevoluteJoint_IsLimitEnabled( id ); break;
		case MB3_JP_REVOLUTE_LIMITS:
			v[0] = b3RevoluteJoint_GetLowerLimit( id );
			v[1] = b3RevoluteJoint_GetUpperLimit( id );
			n = 2;
			break;
		case MB3_JP_REVOLUTE_MOTOR: v[0] = b3RevoluteJoint_IsMotorEnabled( id ); break;
		case MB3_JP_REVOLUTE_MOTOR_SPEED: v[0] = b3RevoluteJoint_GetMotorSpeed( id ); break;
		case MB3_JP_REVOLUTE_MOTOR_TORQUE: v[0] = b3RevoluteJoint_GetMotorTorque( id ); break;
		case MB3_JP_REVOLUTE_MAX_MOTOR_TORQUE: v[0] = b3RevoluteJoint_GetMaxMotorTorque( id ); break;

		case MB3_JP_SPHERICAL_CONE_LIMIT: v[0] = b3SphericalJoint_IsConeLimitEnabled( id ); break;
		case MB3_JP_SPHERICAL_CONE_LIMIT_ANGLE: v[0] = b3SphericalJoint_GetConeLimit( id ); break;
		case MB3_JP_SPHERICAL_CONE_ANGLE: v[0] = b3SphericalJoint_GetConeAngle( id ); break;
		case MB3_JP_SPHERICAL_TWIST_LIMIT: v[0] = b3SphericalJoint_IsTwistLimitEnabled( id ); break;
		case MB3_JP_SPHERICAL_TWIST_LIMITS:
			v[0] = b3SphericalJoint_GetLowerTwistLimit( id );
			v[1] = b3SphericalJoint_GetUpperTwistLimit( id );
			n = 2;
			break;
		case MB3_JP_SPHERICAL_TWIST_ANGLE: v[0] = b3SphericalJoint_GetTwistAngle( id ); break;
		case MB3_JP_SPHERICAL_SPRING: v[0] = b3SphericalJoint_IsSpringEnabled( id ); break;
		case MB3_JP_SPHERICAL_HERTZ: v[0] = b3SphericalJoint_GetSpringHertz( id ); break;
		case MB3_JP_SPHERICAL_DAMPING: v[0] = b3SphericalJoint_GetSpringDampingRatio( id ); break;
		case MB3_JP_SPHERICAL_TARGET_ROTATION:
			rotation = b3SphericalJoint_GetTargetRotation( id );
			v[0] = rotation.v.x;
			v[1] = rotation.v.y;
			v[2] = rotation.v.z;
			v[3] = rotation.s;
			n = 4;
			break;
		case MB3_JP_SPHERICAL_MOTOR: v[0] = b3SphericalJoint_IsMotorEnabled( id ); break;
		case MB3_JP_SPHERICAL_MOTOR_VELOCITY: vector = b3SphericalJoint_GetMotorVelocity( id ); goto vec3;
		case MB3_JP_SPHERICAL_MOTOR_TORQUE: vector = b3SphericalJoint_GetMotorTorque( id ); goto vec3;
		case MB3_JP_SPHERICAL_MAX_MOTOR_TORQUE: v[0] = b3SphericalJoint_GetMaxMotorTorque( id ); break;

		case MB3_JP_WELD_LINEAR_HERTZ: v[0] = b3WeldJoint_GetLinearHertz( id ); break;
		case MB3_JP_WELD_LINEAR_DAMPING: v[0] = b3WeldJoint_GetLinearDampingRatio( id ); break;
		case MB3_JP_WELD_ANGULAR_HERTZ: v[0] = b3WeldJoint_GetAngularHertz( id ); break;
		case MB3_JP_WELD_ANGULAR_DAMPING: v[0] = b3WeldJoint_GetAngularDampingRatio( id ); break;

		case MB3_JP_WHEEL_SUSPENSION: v[0] = b3WheelJoint_IsSuspensionEnabled( id ); break;
		case MB3_JP_WHEEL_SUSPENSION_HERTZ: v[0] = b3WheelJoint_GetSuspensionHertz( id ); break;
		case MB3_JP_WHEEL_SUSPENSION_DAMPING: v[0] = b3WheelJoint_GetSuspensionDampingRatio( id ); break;
		case MB3_JP_WHEEL_SUSPENSION_LIMIT: v[0] = b3WheelJoint_IsSuspensionLimitEnabled( id ); break;
		case MB3_JP_WHEEL_SUSPENSION_LIMITS:
			v[0] = b3WheelJoint_GetLowerSuspensionLimit( id );
			v[1] = b3WheelJoint_GetUpperSuspensionLimit( id );
			n = 2;
			break;
		case MB3_JP_WHEEL_SPIN_MOTOR: v[0] = b3WheelJoint_IsSpinMotorEnabled( id ); break;
		case MB3_JP_WHEEL_SPIN_MOTOR_SPEED: v[0] = b3WheelJoint_GetSpinMotorSpeed( id ); break;
		case MB3_JP_WHEEL_MAX_SPIN_TORQUE: v[0] = b3WheelJoint_GetMaxSpinTorque( id ); break;
		case MB3_JP_WHEEL_SPIN_SPEED: v[0] = b3WheelJoint_GetSpinSpeed( id ); break;
		case MB3_JP_WHEEL_SPIN_TORQUE: v[0] = b3WheelJoint_GetSpinTorque( id ); break;
		case MB3_JP_WHEEL_STEERING: v[0] = b3WheelJoint_IsSteeringEnabled( id ); break;
		case MB3_JP_WHEEL_STEERING_HERTZ: v[0] = b3WheelJoint_GetSteeringHertz( id ); break;
		case MB3_JP_WHEEL_STEERING_DAMPING: v[0] = b3WheelJoint_GetSteeringDampingRatio( id ); break;
		case MB3_JP_WHEEL_MAX_STEERING_TORQUE: v[0] = b3WheelJoint_GetMaxSteeringTorque( id ); break;
		case MB3_JP_WHEEL_STEERING_LIMIT: v[0] = b3WheelJoint_IsSteeringLimitEnabled( id ); break;
		case MB3_JP_WHEEL_STEERING_LIMITS:
			v[0] = b3WheelJoint_GetLowerSteeringLimit( id );
			v[1] = b3WheelJoint_GetUpperSteeringLimit( id );
			n = 2;
			break;
		case MB3_JP_WHEEL_TARGET_STEERING_ANGLE: v[0] = b3WheelJoint_GetTargetSteeringAngle( id ); break;
		case MB3_JP_WHEEL_STEERING_ANGLE: v[0] = b3WheelJoint_GetSteeringAngle( id ); break;
		case MB3_JP_WHEEL_STEERING_TORQUE: v[0] = b3WheelJoint_GetSteeringTorque( id ); break;

		case MB3_JP_MOVER_LINEAR_VELOCITY: vector = b3MoverJoint_GetLinearVelocity( id ); goto vec3;
		case MB3_JP_MOVER_MAX_FORCE: v[0] = b3MoverJoint_GetMaxVelocityForce( id ); break;

		case MB3_JP_POGO_REST_LENGTH: v[0] = b3PogoJoint_GetRestLength( id ); break;
		case MB3_JP_POGO_HERTZ: v[0] = b3PogoJoint_GetSpringHertz( id ); break;
		case MB3_JP_POGO_DAMPING: v[0] = b3PogoJoint_GetSpringDampingRatio( id ); break;
		case MB3_JP_POGO_MAX_TENSION_FORCE: v[0] = b3PogoJoint_GetMaxTensionForce( id ); break;
		case MB3_JP_POGO_MAX_COMPRESSION_FORCE: v[0] = b3PogoJoint_GetMaxCompressionForce( id ); break;
		case MB3_JP_POGO_NORMAL: vector = b3PogoJoint_GetNormal( id ); goto vec3;
		case MB3_JP_POGO_CURRENT_LENGTH: v[0] = b3PogoJoint_GetCurrentLength( id ); break;
		case MB3_JP_POGO_CURRENT_SPEED: v[0] = b3PogoJoint_GetCurrentSpeed( id ); break;
		case MB3_JP_POGO_IMPULSE: v[0] = b3PogoJoint_GetImpulse( id ); break;

		case MB3_JP_MOTION_LOCK_LINEAR_X: v[0] = b3MotionLockJoint_IsLinearLockEnabled( id, 0 ); break;
		case MB3_JP_MOTION_LOCK_LINEAR_Y: v[0] = b3MotionLockJoint_IsLinearLockEnabled( id, 1 ); break;
		case MB3_JP_MOTION_LOCK_LINEAR_Z: v[0] = b3MotionLockJoint_IsLinearLockEnabled( id, 2 ); break;
		case MB3_JP_MOTION_LOCK_ANGULAR: v[0] = b3MotionLockJoint_IsAngularLockEnabled( id ); break;

		default:
			mb3_throw_illegal_argument( env, "not a joint property that can be read" );
			return 0;
	}
	goto put;

vec3:
	v[0] = vector.x;
	v[1] = vector.y;
	v[2] = vector.z;
	n = 3;

put:
	if ( mb3_check( env ) )
	{
		return 0;
	}
	mb3_put_floats( env, out, 0, v, n );
	return n;
}

MB3_JNI( jint, worldGetJointEvents )( JNIEnv* env, jclass type, jint world, jlongArray out )
{
	(void)type;
	b3JointEvents events = b3World_GetJointEvents( mb3_world( world ) );
	if ( mb3_check( env ) )
	{
		return 0;
	}
	if ( out == NULL )
	{
		mb3_throw_illegal_argument( env, "output long[] is null" );
		return 0;
	}
	int count = b3MinInt( events.count, ( *env )->GetArrayLength( env, out ) );
	for ( int i = 0; i < count; ++i )
	{
		jlong id = mb3_pack_joint( events.jointEvents[i].jointId );
		( *env )->SetLongArrayRegion( env, out, i, 1, &id );
	}
	return events.count;
}

// ---- constraint joint (MineBox3D) -----------------------------------------------------------------------------------

#define MB3_CONSTRAINT_TARGET 13
#define MB3_CONSTRAINT_STATE 8

MB3_JNI( void, defaultConstraintJointSettings )( JNIEnv* env, jclass type, jobject target )
{
	(void)type;
	if ( target == NULL )
	{
		mb3_throw_illegal_argument( env, "settings are null" );
		return;
	}
	b3ConstraintJointSettings settings = b3DefaultConstraintJointSettings();
	mb3_fields_to_java( env, target, &settings, MB3_TABLE( s_constraintSettingsFields ) );
}

// Box3D asserts on another joint type and then reads the joint as this one
static bool mb3_is_constraint_joint( JNIEnv* env, b3JointId id )
{
	b3JointType jointType = b3Joint_GetType( id );
	if ( mb3_check( env ) )
	{
		return false;
	}
	if ( jointType != b3_constraintJoint )
	{
		mb3_throw_illegal_argument( env, "not a constraint joint" );
		return false;
	}
	return true;
}

MB3_JNI( void, constraintJointSetSettings )( JNIEnv* env, jclass type, jlong joint, jobject source )
{
	(void)type;
	b3JointId id = mb3_joint( joint );
	if ( source == NULL )
	{
		mb3_throw_illegal_argument( env, "settings are null" );
		return;
	}
	if ( mb3_is_constraint_joint( env, id ) == false )
	{
		return;
	}
	b3ConstraintJointSettings settings = b3DefaultConstraintJointSettings();
	if ( mb3_fields_from_java( env, source, &settings, MB3_TABLE( s_constraintSettingsFields ) ) == false )
	{
		return;
	}
	settings.angularOrientationTarget = mb3_normalized( settings.angularOrientationTarget );
	b3ConstraintJoint_SetSettings( id, &settings );
	mb3_check( env );
}

MB3_JNI( void, constraintJointGetSettings )( JNIEnv* env, jclass type, jlong joint, jobject target )
{
	(void)type;
	b3JointId id = mb3_joint( joint );
	if ( target == NULL )
	{
		mb3_throw_illegal_argument( env, "settings are null" );
		return;
	}
	if ( mb3_is_constraint_joint( env, id ) == false )
	{
		return;
	}
	b3ConstraintJointSettings settings = b3ConstraintJoint_GetSettings( id );
	if ( mb3_check( env ) == false )
	{
		mb3_fields_to_java( env, target, &settings, MB3_TABLE( s_constraintSettingsFields ) );
	}
}

// The ids and a float[] of `stride` values per joint, checked; NULL with an exception pending otherwise.
static jlong* mb3_constraint_batch( JNIEnv* env, jlongArray joints, jint count, jfloatArray values, int stride )
{
	if ( joints == NULL || count < 0 || ( *env )->GetArrayLength( env, joints ) < count )
	{
		mb3_throw_illegal_argument( env, "joint id array is null or shorter than count" );
		return NULL;
	}
	if ( values == NULL || ( *env )->GetArrayLength( env, values ) < stride * count )
	{
		mb3_throw_illegal_argument( env, "float[] is null or too short for count joints" );
		return NULL;
	}
	jlong* ids = ( *env )->GetLongArrayElements( env, joints, NULL );
	if ( ids == NULL )
	{
		return NULL;
	}
	for ( int i = 0; i < count; ++i )
	{
		if ( mb3_is_constraint_joint( env, mb3_joint( ids[i] ) ) == false )
		{
			( *env )->ReleaseLongArrayElements( env, joints, ids, JNI_ABORT );
			return NULL;
		}
	}
	return ids;
}

// Per joint: [linear position xyz, linear velocity xyz, orientation xyzw, angular velocity xyz], as the settings' targets
MB3_JNI( void, constraintJointsSetTargets )( JNIEnv* env, jclass type, jlongArray joints, jint count, jfloatArray values,
											 jboolean wake )
{
	(void)type;
	jlong* ids = mb3_constraint_batch( env, joints, count, values, MB3_CONSTRAINT_TARGET );
	if ( ids == NULL )
	{
		return;
	}
	jfloat* v = ( *env )->GetFloatArrayElements( env, values, NULL );
	if ( v != NULL )
	{
		for ( int i = 0; i < count; ++i )
		{
			const jfloat* t = v + MB3_CONSTRAINT_TARGET * i;
			b3JointId id = mb3_joint( ids[i] );
			b3ConstraintJoint_SetLinearDriveTarget( id, mb3_vec3( t[0], t[1], t[2] ), mb3_vec3( t[3], t[4], t[5] ) );
			b3ConstraintJoint_SetAngularDriveTarget( id, mb3_quat( t[6], t[7], t[8], t[9] ), mb3_vec3( t[10], t[11], t[12] ) );
			if ( wake )
			{
				b3Joint_WakeBodies( id );
			}
		}
		( *env )->ReleaseFloatArrayElements( env, values, v, JNI_ABORT );
	}
	( *env )->ReleaseLongArrayElements( env, joints, ids, JNI_ABORT );
	mb3_check( env );
}

// Per joint: [twist, swing 1, swing 2 as the limits measure them, linear offset xyz in frame A, linear separation,
// angular separation]
MB3_JNI( void, constraintJointsGetState )( JNIEnv* env, jclass type, jlongArray joints, jint count, jfloatArray out )
{
	(void)type;
	jlong* ids = mb3_constraint_batch( env, joints, count, out, MB3_CONSTRAINT_STATE );
	if ( ids == NULL )
	{
		return;
	}
	jfloat* v = ( *env )->GetFloatArrayElements( env, out, NULL );
	if ( v != NULL )
	{
		for ( int i = 0; i < count; ++i )
		{
			jfloat* s = v + MB3_CONSTRAINT_STATE * i;
			b3JointId id = mb3_joint( ids[i] );
			b3Vec3 angles = b3ConstraintJoint_GetAngles( id );
			b3Vec3 offset = b3ConstraintJoint_GetLinearOffset( id );
			s[0] = angles.x;
			s[1] = angles.y;
			s[2] = angles.z;
			s[3] = offset.x;
			s[4] = offset.y;
			s[5] = offset.z;
			s[6] = b3Joint_GetLinearSeparation( id );
			s[7] = b3Joint_GetAngularSeparation( id );
		}
		( *env )->ReleaseFloatArrayElements( env, out, v, 0 );
	}
	( *env )->ReleaseLongArrayElements( env, joints, ids, JNI_ABORT );
	mb3_check( env );
}
