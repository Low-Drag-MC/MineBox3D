// MineBox3D JNI bridge: library setup, errors, logging, version.

#include "mb3.h"

#include <stdio.h>
#include <string.h>

#if defined( _WIN32 )
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <pthread.h>
#endif

// ---- a plain mutex ------------------------------------------------------------------------------

typedef struct mb3_mutex
{
#if defined( _WIN32 )
	SRWLOCK lock;
#else
	pthread_mutex_t lock;
#endif
} mb3_mutex;

#if defined( _WIN32 )
#define MB3_MUTEX_INIT { SRWLOCK_INIT }
static void mb3_mutex_lock( mb3_mutex* m )
{
	AcquireSRWLockExclusive( &m->lock );
}
static void mb3_mutex_unlock( mb3_mutex* m )
{
	ReleaseSRWLockExclusive( &m->lock );
}
#else
#define MB3_MUTEX_INIT { PTHREAD_MUTEX_INITIALIZER }
static void mb3_mutex_lock( mb3_mutex* m )
{
	pthread_mutex_lock( &m->lock );
}
static void mb3_mutex_unlock( mb3_mutex* m )
{
	pthread_mutex_unlock( &m->lock );
}
#endif

static mb3_mutex s_worldMutex = MB3_MUTEX_INIT;

void mb3_world_lock( void )
{
	mb3_mutex_lock( &s_worldMutex );
}

void mb3_world_unlock( void )
{
	mb3_mutex_unlock( &s_worldMutex );
}

// ---- assertions ---------------------------------------------------------------------------------

#define MB3_ERROR_CAPACITY 512

static MB3_THREAD_LOCAL bool t_errorPending;
static MB3_THREAD_LOCAL char t_error[MB3_ERROR_CAPACITY];

static jclass s_exceptionClass;

static void mb3_log( const char* message );

static int mb3_assert( const char* condition, const char* fileName, int lineNumber )
{
	const char* file = fileName;
	for ( const char* c = fileName; *c != '\0'; ++c )
	{
		if ( *c == '/' || *c == '\\' )
		{
			file = c + 1;
		}
	}
	char message[MB3_ERROR_CAPACITY];
	snprintf( message, MB3_ERROR_CAPACITY, "Box3D assertion failed: %s (%s:%d)", condition, file, lineNumber );

	// Into the log as well: on Box3D's own worker threads (a world with workerCount > 1) nothing ever asks for the
	// thread's pending error, and the log is the only trace
	mb3_log( message );

	// Keep the first one: what follows a failed assertion is usually its consequence.
	if ( t_errorPending == false )
	{
		snprintf( t_error, MB3_ERROR_CAPACITY, "%s", message );
		t_errorPending = true;
	}
	// 0 = no debugger break: Box3D carries on and the call throws on its way out. Java validates arguments so that
	// this stays the exception.
	return 0;
}

bool mb3_assert_pending( void )
{
	return t_errorPending;
}

bool mb3_check( JNIEnv* env )
{
	if ( t_errorPending == false )
	{
		return ( *env )->ExceptionCheck( env );
	}
	t_errorPending = false;
	if ( ( *env )->ExceptionCheck( env ) == JNI_FALSE )
	{
		( *env )->ThrowNew( env, s_exceptionClass, t_error );
	}
	return true;
}

void mb3_throw( JNIEnv* env, const char* message )
{
	if ( ( *env )->ExceptionCheck( env ) == JNI_FALSE )
	{
		( *env )->ThrowNew( env, s_exceptionClass, message );
	}
}

void mb3_throw_illegal_argument( JNIEnv* env, const char* message )
{
	if ( ( *env )->ExceptionCheck( env ) == JNI_FALSE )
	{
		jclass type = ( *env )->FindClass( env, "java/lang/IllegalArgumentException" );
		if ( type != NULL )
		{
			( *env )->ThrowNew( env, type, message );
		}
	}
}

// ---- log ----------------------------------------------------------------------------------------
// Box3D logs warnings from whatever thread it is on, including its own worker threads, which have
// no JNIEnv. Messages wait in a small ring that Java drains (B3.pollLog).

#define MB3_LOG_SLOTS 64
#define MB3_LOG_LENGTH 256

static mb3_mutex s_logMutex = MB3_MUTEX_INIT;
static char s_log[MB3_LOG_SLOTS][MB3_LOG_LENGTH];
static int s_logHead;
static int s_logCount;
static int s_logDropped;

static void mb3_log( const char* message )
{
	mb3_mutex_lock( &s_logMutex );
	if ( s_logCount == MB3_LOG_SLOTS )
	{
		s_logDropped += 1;
	}
	else
	{
		int slot = ( s_logHead + s_logCount ) % MB3_LOG_SLOTS;
		snprintf( s_log[slot], MB3_LOG_LENGTH, "%s", message );
		s_logCount += 1;
	}
	mb3_mutex_unlock( &s_logMutex );
}

MB3_JNI( jstring, pollLog )( JNIEnv* env, jclass type )
{
	(void)type;
	char message[MB3_LOG_LENGTH + 64];
	bool found = false;
	mb3_mutex_lock( &s_logMutex );
	if ( s_logDropped > 0 )
	{
		snprintf( message, sizeof( message ), "(%d Box3D log messages were dropped)", s_logDropped );
		s_logDropped = 0;
		found = true;
	}
	else if ( s_logCount > 0 )
	{
		snprintf( message, sizeof( message ), "%s", s_log[s_logHead] );
		s_logHead = ( s_logHead + 1 ) % MB3_LOG_SLOTS;
		s_logCount -= 1;
		found = true;
	}
	mb3_mutex_unlock( &s_logMutex );
	return found ? ( *env )->NewStringUTF( env, message ) : NULL;
}

// ---- array helpers ------------------------------------------------------------------------------

bool mb3_put_floats( JNIEnv* env, jfloatArray out, int offset, const float* values, int count )
{
	if ( out == NULL || offset < 0 || ( *env )->GetArrayLength( env, out ) < offset + count )
	{
		mb3_throw_illegal_argument( env, "output float[] is null or too short" );
		return false;
	}
	( *env )->SetFloatArrayRegion( env, out, offset, count, values );
	return true;
}

bool mb3_put_doubles( JNIEnv* env, jdoubleArray out, int offset, const double* values, int count )
{
	if ( out == NULL || offset < 0 || ( *env )->GetArrayLength( env, out ) < offset + count )
	{
		mb3_throw_illegal_argument( env, "output double[] is null or too short" );
		return false;
	}
	( *env )->SetDoubleArrayRegion( env, out, offset, count, values );
	return true;
}

bool mb3_put_pos( JNIEnv* env, jdoubleArray out, int offset, b3Pos p )
{
	double values[3] = { p.x, p.y, p.z };
	return mb3_put_doubles( env, out, offset, values, 3 );
}

bool mb3_put_vec3( JNIEnv* env, jfloatArray out, int offset, b3Vec3 v )
{
	float values[3] = { v.x, v.y, v.z };
	return mb3_put_floats( env, out, offset, values, 3 );
}

bool mb3_put_quat( JNIEnv* env, jfloatArray out, int offset, b3Quat q )
{
	float values[4] = { q.v.x, q.v.y, q.v.z, q.s };
	return mb3_put_floats( env, out, offset, values, 4 );
}

// ---- setup --------------------------------------------------------------------------------------

MB3_JNI( jboolean, init )( JNIEnv* env, jclass type, jobjectArray classes )
{
	(void)type;
	if ( classes == NULL || ( *env )->GetArrayLength( env, classes ) != MB3_CLASS_COUNT )
	{
		mb3_throw_illegal_argument( env, "B3.init: wrong class table" );
		return JNI_FALSE;
	}
	jobject exception = ( *env )->GetObjectArrayElement( env, classes, MB3_CLASS_EXCEPTION );
	if ( s_exceptionClass == NULL )
	{
		s_exceptionClass = (jclass)( *env )->NewGlobalRef( env, exception );
	}
	if ( mb3_defs_init( env, classes ) == false )
	{
		return JNI_FALSE;
	}
	b3SetAssertFcn( mb3_assert );
	b3SetLogFcn( mb3_log );
	return JNI_TRUE;
}

MB3_JNI( void, getVersion )( JNIEnv* env, jclass type, jintArray out )
{
	(void)type;
	b3Version version = b3GetVersion();
	if ( out == NULL || ( *env )->GetArrayLength( env, out ) < 3 )
	{
		mb3_throw_illegal_argument( env, "output int[] is null or too short" );
		return;
	}
	jint values[3] = { version.major, version.minor, version.revision };
	( *env )->SetIntArrayRegion( env, out, 0, 3, values );
}

MB3_JNI( jboolean, isDoublePrecision )( JNIEnv* env, jclass type )
{
	(void)env;
	(void)type;
	return b3IsDoublePrecision() ? JNI_TRUE : JNI_FALSE;
}

MB3_JNI( jint, getMaxWorlds )( JNIEnv* env, jclass type )
{
	(void)env;
	(void)type;
	return B3_MAX_WORLDS;
}

MB3_JNI( jlong, getByteCount )( JNIEnv* env, jclass type )
{
	(void)env;
	(void)type;
	return (jlong)b3GetByteCount();
}
