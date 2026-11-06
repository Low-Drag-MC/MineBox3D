// MineBox3D JNI bridge: bodies that never collide — tables of body pairs (a physics asset's collision groups and disabled
// pairs) and tags (a body never meets the bodies tagged with what it ignores: a ragdoll and its own entity's proxy).
//
// Box3D's custom filter asks about a new pair on whichever thread finds it, so the answer is read here, never from Java,
// and only for pairs one of whose shapes was made with enableCustomFiltering. Tables and tags change between steps only
// (the world's thread rule), and a change reaches pairs already touching: the bodies whose pairs changed have them found
// again.

#include "mb3.h"

#include <stdlib.h>
#include <string.h>

#define MB3_PAIRS_MAX_BODIES 0xFFFF

typedef struct mb3_pair_table
{
	int count; // bodies; 0 for a free table
	b3BodyId* bodies;
	uint32_t* bits; // count × count, row by row: set = the pair does not collide
} mb3_pair_table;

// A body's tags: what it is, and what it never meets — 0 for none of either
typedef struct mb3_body_tags
{
	b3BodyId body; // whose: a body destroyed and its index reused reads as untagged
	uint64_t tag;
	uint64_t ignored;
} mb3_body_tags;

typedef struct mb3_world_pairs
{
	// by Box3D body index: (table + 1) << 16 | the body's place in it; 0 for none. A body destroyed and its index
	// reused reads as none, its id no longer the table's.
	uint32_t* slots;
	int slotCount;
	mb3_pair_table* tables;
	int tableCount;
	mb3_body_tags* tags; // by Box3D body index
	int tagCount;
} mb3_world_pairs;

static mb3_world_pairs* s_worlds[B3_MAX_WORLDS];

static const mb3_body_tags* mb3_tags_of( const mb3_world_pairs* pairs, b3BodyId body )
{
	int index = body.index1 - 1;
	if ( index < 0 || index >= pairs->tagCount || B3_ID_EQUALS( pairs->tags[index].body, body ) == false )
	{
		return NULL;
	}
	return pairs->tags + index;
}

// Whether one of the two ignores what the other is
static bool mb3_tags_apart( const mb3_world_pairs* pairs, b3BodyId bodyA, b3BodyId bodyB )
{
	const mb3_body_tags* a = mb3_tags_of( pairs, bodyA );
	const mb3_body_tags* b = mb3_tags_of( pairs, bodyB );
	return a != NULL && b != NULL &&
		   ( ( a->ignored != 0 && a->ignored == b->tag ) || ( b->ignored != 0 && b->ignored == a->tag ) );
}

static bool mb3_pairs_filter( b3ShapeId shapeA, b3ShapeId shapeB, void* context )
{
	const mb3_world_pairs* pairs = context;
	b3BodyId bodyA = b3Shape_GetBody( shapeA );
	b3BodyId bodyB = b3Shape_GetBody( shapeB );
	if ( mb3_tags_apart( pairs, bodyA, bodyB ) )
	{
		return false;
	}
	int indexA = bodyA.index1 - 1;
	int indexB = bodyB.index1 - 1;
	if ( indexA >= pairs->slotCount || indexB >= pairs->slotCount )
	{
		return true;
	}

	uint32_t slotA = pairs->slots[indexA];
	uint32_t slotB = pairs->slots[indexB];
	if ( slotA == 0 || ( slotA >> 16 ) != ( slotB >> 16 ) )
	{
		return true;
	}

	const mb3_pair_table* table = pairs->tables + ( slotA >> 16 ) - 1;
	int i = (int)( slotA & 0xFFFF );
	int j = (int)( slotB & 0xFFFF );
	if ( B3_ID_EQUALS( table->bodies[i], bodyA ) == false || B3_ID_EQUALS( table->bodies[j], bodyB ) == false )
	{
		return true;
	}

	int bit = i * table->count + j;
	return ( table->bits[bit >> 5] & ( 1u << ( bit & 31 ) ) ) == 0;
}

static mb3_world_pairs* mb3_world_pairs_of( b3WorldId world, bool create )
{
	int index = world.index1 - 1;
	if ( index < 0 || index >= B3_MAX_WORLDS )
	{
		return NULL;
	}

	if ( s_worlds[index] == NULL && create )
	{
		s_worlds[index] = calloc( 1, sizeof( mb3_world_pairs ) );
		if ( s_worlds[index] != NULL )
		{
			b3World_SetCustomFilterCallback( world, mb3_pairs_filter, s_worlds[index] );
		}
	}
	return s_worlds[index];
}

// Box3D asks the filter only about pairs it has not got, so a body whose pairs changed has its shapes' contacts dropped and
// its pairs found again
static void mb3_refresh_body( b3BodyId body )
{
	if ( b3Body_IsValid( body ) == false )
	{
		return;
	}
	int count = b3Body_GetShapeCount( body );
	b3ShapeId* shapes = count > 0 ? malloc( (size_t)count * sizeof( b3ShapeId ) ) : NULL;
	if ( shapes == NULL )
	{
		return;
	}
	count = b3Body_GetShapes( body, shapes, count );
	for ( int i = 0; i < count; ++i )
	{
		b3Shape_RefreshFilter( shapes[i] );
	}
	free( shapes );
}

static void mb3_free_table( mb3_world_pairs* pairs, int table )
{
	mb3_pair_table* t = pairs->tables + table;
	for ( int i = 0; i < t->count; ++i )
	{
		int index = t->bodies[i].index1 - 1;
		if ( index < pairs->slotCount && ( pairs->slots[index] >> 16 ) == (uint32_t)( table + 1 ) )
		{
			pairs->slots[index] = 0;
		}
	}
	// its disabled pairs collide from now on, those already overlapping too
	for ( int i = 0; i < t->count; ++i )
	{
		for ( int j = 0; j < t->count; ++j )
		{
			int bit = i * t->count + j;
			if ( ( t->bits[bit >> 5] & ( 1u << ( bit & 31 ) ) ) != 0 )
			{
				mb3_refresh_body( t->bodies[i] );
				break;
			}
		}
	}
	free( t->bodies );
	free( t->bits );
	*t = ( mb3_pair_table ){ 0 };
}

void mb3_pairs_world_destroyed( b3WorldId world )
{
	int index = world.index1 - 1;
	if ( index < 0 || index >= B3_MAX_WORLDS || s_worlds[index] == NULL )
	{
		return;
	}

	mb3_world_pairs* pairs = s_worlds[index];
	for ( int t = 0; t < pairs->tableCount; ++t )
	{
		free( pairs->tables[t].bodies );
		free( pairs->tables[t].bits );
	}
	free( pairs->tables );
	free( pairs->slots );
	free( pairs->tags );
	free( pairs );
	s_worlds[index] = NULL;
}

bool mb3_pairs_ignores( b3WorldId world, b3BodyId body, uint64_t tag )
{
	int index = world.index1 - 1;
	if ( tag == 0 || index < 0 || index >= B3_MAX_WORLDS || s_worlds[index] == NULL )
	{
		return false;
	}
	const mb3_body_tags* tags = mb3_tags_of( s_worlds[index], body );
	return tags != NULL && tags->ignored == tag;
}

// A body's tags: what it is, and the tag of the bodies it never meets; 0 for none of either
MB3_JNI( void, bodySetTags )( JNIEnv* env, jclass type, jint world, jlong body, jlong tag, jlong ignored )
{
	(void)type;
	b3WorldId worldId = mb3_world( world );
	b3BodyId bodyId = mb3_body( body );
	if ( b3World_IsValid( worldId ) == false || b3Body_IsValid( bodyId ) == false || bodyId.world0 != worldId.index1 - 1 )
	{
		mb3_throw_illegal_argument( env, "not a live body of this world" );
		return;
	}

	bool tagged = tag != 0 || ignored != 0;
	mb3_world_pairs* pairs = mb3_world_pairs_of( worldId, tagged );
	if ( pairs == NULL )
	{
		if ( tagged )
		{
			mb3_throw( env, "out of memory for body tags" );
		}
		return; // untagged in a world never tagged: nothing to undo
	}

	int index = bodyId.index1 - 1;
	if ( index >= pairs->tagCount )
	{
		if ( tagged == false )
		{
			return;
		}
		int count = b3MaxInt( index + 1, 2 * pairs->tagCount );
		mb3_body_tags* tags = realloc( pairs->tags, (size_t)count * sizeof( mb3_body_tags ) );
		if ( tags == NULL )
		{
			mb3_throw( env, "out of memory for body tags" );
			return;
		}
		memset( tags + pairs->tagCount, 0, (size_t)( count - pairs->tagCount ) * sizeof( mb3_body_tags ) );
		pairs->tags = tags;
		pairs->tagCount = count;
	}

	mb3_body_tags* entry = pairs->tags + index;
	bool had = B3_ID_EQUALS( entry->body, bodyId );
	uint64_t wasTag = had ? entry->tag : 0;
	uint64_t wasIgnored = had ? entry->ignored : 0;
	*entry = tagged ? ( mb3_body_tags ){ bodyId, (uint64_t)tag, (uint64_t)ignored } : ( mb3_body_tags ){ 0 };
	if ( wasTag != (uint64_t)tag || wasIgnored != (uint64_t)ignored )
	{
		mb3_refresh_body( bodyId );
	}
}

// A table over count bodies of the world, every pair colliding; its handle (> 0). A body is in one table at most.
MB3_JNI( jint, pairTableCreate )( JNIEnv* env, jclass type, jint world, jlongArray bodies, jint count )
{
	(void)type;
	b3WorldId worldId = mb3_world( world );
	if ( b3World_IsValid( worldId ) == false )
	{
		mb3_throw_illegal_argument( env, "not a live world" );
		return 0;
	}
	if ( bodies == NULL || count <= 0 || count > MB3_PAIRS_MAX_BODIES || ( *env )->GetArrayLength( env, bodies ) < count )
	{
		mb3_throw_illegal_argument( env, "body id array is null, empty, too long or shorter than count" );
		return 0;
	}

	mb3_world_pairs* pairs = mb3_world_pairs_of( worldId, true );
	jlong* ids = ( *env )->GetLongArrayElements( env, bodies, NULL );
	if ( pairs == NULL || ids == NULL )
	{
		if ( ids != NULL )
		{
			( *env )->ReleaseLongArrayElements( env, bodies, ids, JNI_ABORT );
		}
		mb3_throw( env, "out of memory for a pair table" );
		return 0;
	}

	int largest = 0;
	for ( int i = 0; i < count; ++i )
	{
		b3BodyId body = mb3_body( ids[i] );
		int index = body.index1 - 1;
		bool taken = index < pairs->slotCount && pairs->slots[index] != 0 &&
					 B3_ID_EQUALS( pairs->tables[( pairs->slots[index] >> 16 ) - 1].bodies[pairs->slots[index] & 0xFFFF], body );
		if ( b3Body_IsValid( body ) == false || body.world0 != worldId.index1 - 1 || taken )
		{
			( *env )->ReleaseLongArrayElements( env, bodies, ids, JNI_ABORT );
			mb3_throw_illegal_argument( env, "a body is not alive in this world, or already in a pair table" );
			return 0;
		}
		largest = b3MaxInt( largest, index + 1 );
	}

	int table = 0;
	while ( table < pairs->tableCount && pairs->tables[table].count != 0 )
	{
		++table;
	}

	int words = ( count * count + 31 ) / 32;
	b3BodyId* tableBodies = malloc( (size_t)count * sizeof( b3BodyId ) );
	uint32_t* bits = calloc( (size_t)words, sizeof( uint32_t ) );
	bool grown = true;
	if ( table == pairs->tableCount && table < MB3_PAIRS_MAX_BODIES )
	{
		mb3_pair_table* tables = realloc( pairs->tables, (size_t)( table + 1 ) * sizeof( mb3_pair_table ) );
		grown = tables != NULL;
		if ( grown )
		{
			pairs->tables = tables;
			pairs->tables[table] = ( mb3_pair_table ){ 0 };
			pairs->tableCount = table + 1;
		}
	}
	if ( grown && largest > pairs->slotCount )
	{
		uint32_t* slots = realloc( pairs->slots, (size_t)largest * sizeof( uint32_t ) );
		grown = slots != NULL;
		if ( grown )
		{
			memset( slots + pairs->slotCount, 0, (size_t)( largest - pairs->slotCount ) * sizeof( uint32_t ) );
			pairs->slots = slots;
			pairs->slotCount = largest;
		}
	}
	if ( grown == false || table >= pairs->tableCount || tableBodies == NULL || bits == NULL )
	{
		free( tableBodies );
		free( bits );
		( *env )->ReleaseLongArrayElements( env, bodies, ids, JNI_ABORT );
		mb3_throw( env, "out of memory for a pair table" );
		return 0;
	}

	mb3_pair_table* t = pairs->tables + table;
	t->count = count;
	t->bodies = tableBodies;
	t->bits = bits;
	for ( int i = 0; i < count; ++i )
	{
		b3BodyId body = mb3_body( ids[i] );
		t->bodies[i] = body;
		pairs->slots[body.index1 - 1] = ( (uint32_t)( table + 1 ) << 16 ) | (uint32_t)i;
	}
	( *env )->ReleaseLongArrayElements( env, bodies, ids, JNI_ABORT );
	return table + 1;
}

static mb3_pair_table* mb3_table( JNIEnv* env, jint world, jint table )
{
	mb3_world_pairs* pairs = mb3_world_pairs_of( mb3_world( world ), false );
	if ( pairs == NULL || table <= 0 || table > pairs->tableCount || pairs->tables[table - 1].count == 0 )
	{
		mb3_throw_illegal_argument( env, "not a pair table of this world" );
		return NULL;
	}
	return pairs->tables + table - 1;
}

// The whole table at once: bit i × count + j of words set = bodies i and j do not collide (Java keeps it symmetric)
MB3_JNI( void, pairTableSetBits )( JNIEnv* env, jclass type, jint world, jint table, jintArray words )
{
	(void)type;
	mb3_pair_table* t = mb3_table( env, world, table );
	if ( t == NULL )
	{
		return;
	}
	int count = ( t->count * t->count + 31 ) / 32;
	if ( words == NULL || ( *env )->GetArrayLength( env, words ) < count )
	{
		mb3_throw_illegal_argument( env, "int[] is null or shorter than the table" );
		return;
	}
	uint32_t* fresh = malloc( (size_t)count * sizeof( uint32_t ) );
	if ( fresh == NULL )
	{
		mb3_throw( env, "out of memory for a pair table" );
		return;
	}
	( *env )->GetIntArrayRegion( env, words, 0, count, (jint*)fresh );

	uint32_t* old = t->bits;
	t->bits = fresh;
	for ( int i = 0; i < t->count; ++i )
	{
		for ( int j = 0; j < t->count; ++j )
		{
			int bit = i * t->count + j;
			if ( ( ( old[bit >> 5] ^ fresh[bit >> 5] ) & ( 1u << ( bit & 31 ) ) ) != 0 )
			{
				mb3_refresh_body( t->bodies[i] );
				break;
			}
		}
	}
	free( old );
}

MB3_JNI( void, pairTableDestroy )( JNIEnv* env, jclass type, jint world, jint table )
{
	(void)type;
	if ( mb3_table( env, world, table ) != NULL )
	{
		mb3_free_table( mb3_world_pairs_of( mb3_world( world ), false ), table - 1 );
	}
}
