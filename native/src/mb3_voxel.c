// MineBox3D JNI bridge: voxel terrain (our Box3D fork's b3VoxelGrid, docs/design/01-voxel-terrain.md).
//
// A grid belongs to one world and follows its one-thread rule; the Java VoxelGrid enters the world
// around every call. The grid makes and destroys its sections' static bodies itself.

#include "mb3.h"

#include "box3d/voxel.h"

#include <stdlib.h>
#include <string.h>

void mb3_cell( b3ShapeId shape, int triangleIndex, int out[4] )
{
	int x, y, z;
	if ( B3_IS_NON_NULL( shape ) && b3Shape_GetVoxelCell( shape, triangleIndex, &x, &y, &z ) )
	{
		out[0] = 1, out[1] = x, out[2] = y, out[3] = z;
	}
	else
	{
		out[0] = 0, out[1] = 0, out[2] = 0, out[3] = 0;
	}
}

void mb3_hit( b3ShapeId shape, b3Pos point, b3Vec3 normal, float fraction, uint64_t userMaterialId, int triangleIndex,
			  double* doubles, jlong* longs )
{
	int cell[4];
	mb3_cell( shape, triangleIndex, cell );
	doubles[0] = point.x;
	doubles[1] = point.y;
	doubles[2] = point.z;
	doubles[3] = fraction;
	doubles[4] = normal.x;
	doubles[5] = normal.y;
	doubles[6] = normal.z;
	doubles[7] = cell[0];
	doubles[8] = cell[1];
	doubles[9] = cell[2];
	doubles[10] = cell[3];
	longs[0] = mb3_pack_shape( shape );
	longs[1] = (jlong)( userMaterialId & MB3_USER_MATERIAL_BITS );
}

static b3VoxelGrid* mb3_grid( JNIEnv* env, jlong grid )
{
	if ( grid == 0 )
	{
		mb3_throw_illegal_argument( env, "voxel grid is null" );
		return NULL;
	}
	return (b3VoxelGrid*)(intptr_t)grid;
}

MB3_JNI( jlong, createVoxelGrid )( JNIEnv* env, jclass type, jint world, jobject shapeDefinition )
{
	(void)type;
	b3VoxelGridDef def = b3DefaultVoxelGridDef();
	char name[MB3_NAME_CAPACITY];
	if ( mb3_shape_def_from_java( env, shapeDefinition, &def.shapeDef, name, MB3_NAME_CAPACITY ) == false )
	{
		return 0;
	}
	if ( def.shapeDef.isSensor )
	{
		mb3_shape_def_release( &def.shapeDef );
		mb3_throw_illegal_argument( env, "voxel terrain cannot be a sensor" );
		return 0;
	}

	b3VoxelGrid* grid = b3CreateVoxelGrid( mb3_world( world ), &def );
	mb3_shape_def_release( &def.shapeDef );
	if ( mb3_check( env ) )
	{
		if ( grid != NULL )
			b3DestroyVoxelGrid( grid );
		return 0;
	}
	if ( grid == NULL )
	{
		mb3_throw( env, "Box3D could not create the voxel grid" );
		return 0;
	}
	return (jlong)(intptr_t)grid;
}

MB3_JNI( void, destroyVoxelGrid )( JNIEnv* env, jclass type, jlong grid )
{
	(void)type;
	b3VoxelGrid* g = mb3_grid( env, grid );
	if ( g == NULL )
		return;
	// After its world is gone the grid asks the world table whether that world still exists; another thread
	// creating a world in the same slot meanwhile would look alive half way through. Same lock as create.
	mb3_world_lock();
	b3DestroyVoxelGrid( g );
	mb3_world_unlock();
	mb3_check( env );
}

MB3_JNI( jint, voxelGridAddMaterial )( JNIEnv* env, jclass type, jlong grid, jobject material )
{
	(void)type;
	b3VoxelGrid* g = mb3_grid( env, grid );
	b3SurfaceMaterial m = b3DefaultSurfaceMaterial();
	if ( g == NULL || mb3_material_from_java( env, material, &m ) == false )
		return -1;
	int index = b3VoxelGrid_AddMaterial( g, m );
	return mb3_check( env ) ? -1 : index;
}

MB3_JNI( jint, voxelGridAddCollider )( JNIEnv* env, jclass type, jlong grid, jfloatArray boxes, jint boxCount, jint material )
{
	(void)type;
	b3VoxelGrid* g = mb3_grid( env, grid );
	if ( g == NULL )
		return 0;
	if ( boxes == NULL || boxCount < 1 || boxCount > B3_MAX_VOXEL_BOXES || ( *env )->GetArrayLength( env, boxes ) < 6 * boxCount )
	{
		mb3_throw_illegal_argument( env, "a collider is 1 to 64 boxes, 6 floats each" );
		return 0;
	}

	float values[6 * B3_MAX_VOXEL_BOXES];
	( *env )->GetFloatArrayRegion( env, boxes, 0, 6 * boxCount, values );
	b3AABB aabbs[B3_MAX_VOXEL_BOXES];
	for ( int i = 0; i < boxCount; ++i )
	{
		const float* v = values + 6 * i;
		aabbs[i] = (b3AABB){ { v[0], v[1], v[2] }, { v[3], v[4], v[5] } };
	}

	int id = b3VoxelGrid_AddCollider( g, aabbs, boxCount, material );
	if ( mb3_check( env ) )
		return 0;
	if ( id == 0 )
	{
		mb3_throw_illegal_argument( env, "invalid collider: boxes must lie in the unit cell, or the table is full" );
		return 0;
	}
	return id;
}

MB3_JNI( jint, voxelGridGetColliderCount )( JNIEnv* env, jclass type, jlong grid )
{
	(void)type;
	b3VoxelGrid* g = mb3_grid( env, grid );
	return g == NULL ? 0 : b3VoxelGrid_GetColliderCount( g );
}

MB3_JNI( void, voxelGridSetSection )( JNIEnv* env, jclass type, jlong grid, jint sx, jint sy, jint sz, jshortArray cells )
{
	(void)type;
	b3VoxelGrid* g = mb3_grid( env, grid );
	if ( g == NULL )
		return;
	if ( cells == NULL )
	{
		b3VoxelGrid_SetSection( g, sx, sy, sz, NULL );
		mb3_check( env );
		return;
	}
	if ( ( *env )->GetArrayLength( env, cells ) < B3_VOXEL_SECTION_CELLS )
	{
		mb3_throw_illegal_argument( env, "a section is 4096 cells" );
		return;
	}

	uint16_t values[B3_VOXEL_SECTION_CELLS];
	( *env )->GetShortArrayRegion( env, cells, 0, B3_VOXEL_SECTION_CELLS, (jshort*)values );
	b3VoxelGrid_SetSection( g, sx, sy, sz, values );
	mb3_check( env );
}

MB3_JNI( void, voxelGridSetCells )( JNIEnv* env, jclass type, jlong grid, jintArray edits, jint count )
{
	(void)type;
	b3VoxelGrid* g = mb3_grid( env, grid );
	if ( g == NULL || count <= 0 )
		return;
	if ( edits == NULL || ( *env )->GetArrayLength( env, edits ) < 4 * count )
	{
		mb3_throw_illegal_argument( env, "4 ints per edit" );
		return;
	}

	jint* values = ( *env )->GetIntArrayElements( env, edits, NULL );
	if ( values == NULL )
		return;
	b3VoxelCellEdit* cellEdits = malloc( count * sizeof( b3VoxelCellEdit ) );
	if ( cellEdits == NULL )
	{
		( *env )->ReleaseIntArrayElements( env, edits, values, JNI_ABORT );
		mb3_throw( env, "out of memory" );
		return;
	}
	for ( int i = 0; i < count; ++i )
	{
		const jint* v = values + 4 * i;
		cellEdits[i] = (b3VoxelCellEdit){ v[0], v[1], v[2], (uint16_t)v[3] };
	}
	( *env )->ReleaseIntArrayElements( env, edits, values, JNI_ABORT );

	b3VoxelGrid_SetCells( g, cellEdits, count );
	free( cellEdits );
	mb3_check( env );
}

MB3_JNI( jint, voxelGridGetCell )( JNIEnv* env, jclass type, jlong grid, jint x, jint y, jint z )
{
	(void)type;
	b3VoxelGrid* g = mb3_grid( env, grid );
	return g == NULL ? 0 : b3VoxelGrid_GetCell( g, x, y, z );
}

MB3_JNI( jboolean, voxelGridHasSection )( JNIEnv* env, jclass type, jlong grid, jint sx, jint sy, jint sz )
{
	(void)type;
	b3VoxelGrid* g = mb3_grid( env, grid );
	return g != NULL && b3VoxelGrid_HasSection( g, sx, sy, sz );
}

MB3_JNI( jint, voxelGridGetSectionCount )( JNIEnv* env, jclass type, jlong grid )
{
	(void)type;
	b3VoxelGrid* g = mb3_grid( env, grid );
	return g == NULL ? 0 : b3VoxelGrid_GetSectionCount( g );
}

MB3_JNI( jlong, voxelGridGetSectionShape )( JNIEnv* env, jclass type, jlong grid, jint sx, jint sy, jint sz )
{
	(void)type;
	b3VoxelGrid* g = mb3_grid( env, grid );
	if ( g == NULL )
		return 0;
	b3ShapeId id = b3VoxelGrid_GetSectionShape( g, sx, sy, sz );
	return B3_IS_NULL( id ) ? 0 : mb3_pack_shape( id );
}

MB3_JNI( jint, voxelGridCollectTriangles )( JNIEnv* env, jclass type, jlong grid, jint sx, jint sy, jint sz, jfloatArray bounds,
											jfloatArray vertices, jbyteArray flags )
{
	(void)type;
	b3VoxelGrid* g = mb3_grid( env, grid );
	if ( g == NULL )
		return 0;
	if ( bounds == NULL || ( *env )->GetArrayLength( env, bounds ) < 6 || vertices == NULL || flags == NULL )
	{
		mb3_throw_illegal_argument( env, "bounds is 6 floats; vertices and flags must not be null" );
		return 0;
	}

	float b[6];
	( *env )->GetFloatArrayRegion( env, bounds, 0, 6, b );
	jsize capacity = ( *env )->GetArrayLength( env, vertices ) / 9;
	jsize flagCapacity = ( *env )->GetArrayLength( env, flags );
	capacity = capacity < flagCapacity ? capacity : flagCapacity;

	float* v = capacity > 0 ? malloc( 9 * capacity * sizeof( float ) ) : NULL;
	uint8_t* f = capacity > 0 ? malloc( capacity ) : NULL;
	if ( capacity > 0 && ( v == NULL || f == NULL ) )
	{
		free( v );
		free( f );
		mb3_throw( env, "out of memory" );
		return 0;
	}

	b3AABB local = { { b[0], b[1], b[2] }, { b[3], b[4], b[5] } };
	int count = b3VoxelGrid_CollectTriangles( g, sx, sy, sz, local, v, f, capacity );
	int written = count < capacity ? count : capacity;
	if ( mb3_check( env ) == false && written > 0 )
	{
		( *env )->SetFloatArrayRegion( env, vertices, 0, 9 * written, v );
		( *env )->SetByteArrayRegion( env, flags, 0, written, (const jbyte*)f );
	}
	free( v );
	free( f );
	return count;
}
