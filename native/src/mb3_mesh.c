// MineBox3D JNI bridge: triangle meshes.
//
// ⚠️ Box3D keeps only a pointer to a mesh (shape.c, b3CreateShape): the b3MeshData must outlive every
// shape made from it. The Java MeshData owns the pointer and counts the shapes using it.

#include "mb3.h"

MB3_JNI( jlong, createMesh )( JNIEnv* env, jclass type, jfloatArray vertices, jint vertexCount, jintArray indices,
							   jint triangleCount, jbyteArray materialIndices, jfloat weldTolerance, jboolean weldVertices,
							   jboolean useMedianSplit, jboolean identifyEdges, jboolean clockWiseWinding )
{
	(void)type;
	if ( vertices == NULL || vertexCount < 3 || ( *env )->GetArrayLength( env, vertices ) < 3 * vertexCount )
	{
		mb3_throw_illegal_argument( env, "a mesh needs at least 3 vertices, 3 floats each" );
		return 0;
	}
	if ( indices == NULL || triangleCount < 1 || ( *env )->GetArrayLength( env, indices ) < 3 * triangleCount )
	{
		mb3_throw_illegal_argument( env, "a mesh needs at least 1 triangle, 3 indices each" );
		return 0;
	}
	if ( materialIndices != NULL && ( *env )->GetArrayLength( env, materialIndices ) < triangleCount )
	{
		mb3_throw_illegal_argument( env, "one material index per triangle" );
		return 0;
	}

	// Box3D reads everything during b3CreateMesh and keeps none of it ("No pointers are held to this
	// data", types.h b3MeshDef), so the arrays are only pinned for the call.
	jfloat* v = ( *env )->GetFloatArrayElements( env, vertices, NULL );
	jint* i = ( *env )->GetIntArrayElements( env, indices, NULL );
	jbyte* m = materialIndices != NULL ? ( *env )->GetByteArrayElements( env, materialIndices, NULL ) : NULL;
	if ( v == NULL || i == NULL || ( materialIndices != NULL && m == NULL ) )
	{
		if ( v != NULL )
			( *env )->ReleaseFloatArrayElements( env, vertices, v, JNI_ABORT );
		if ( i != NULL )
			( *env )->ReleaseIntArrayElements( env, indices, i, JNI_ABORT );
		if ( m != NULL )
			( *env )->ReleaseByteArrayElements( env, materialIndices, m, JNI_ABORT );
		return 0;
	}

	for ( int k = 0; k < 3 * triangleCount; ++k )
	{
		if ( i[k] < 0 || i[k] >= vertexCount )
		{
			( *env )->ReleaseFloatArrayElements( env, vertices, v, JNI_ABORT );
			( *env )->ReleaseIntArrayElements( env, indices, i, JNI_ABORT );
			if ( m != NULL )
				( *env )->ReleaseByteArrayElements( env, materialIndices, m, JNI_ABORT );
			mb3_throw_illegal_argument( env, "a triangle index is outside the vertex array" );
			return 0;
		}
	}

	b3MeshDef def = { 0 };
	def.vertices = (b3Vec3*)v; // b3Vec3 is three floats, the layout of the Java array
	def.stride = 0;
	def.indices = (int32_t*)i;
	def.materialIndices = (uint8_t*)m;
	def.weldTolerance = weldTolerance;
	def.vertexCount = vertexCount;
	def.triangleCount = triangleCount;
	def.weldVertices = weldVertices;
	def.useMedianSplit = useMedianSplit;
	def.identifyEdges = identifyEdges;
	def.clockWiseWinding = clockWiseWinding;
	b3MeshData* mesh = b3CreateMesh( &def, NULL, 0 );

	( *env )->ReleaseFloatArrayElements( env, vertices, v, JNI_ABORT );
	( *env )->ReleaseIntArrayElements( env, indices, i, JNI_ABORT );
	if ( m != NULL )
		( *env )->ReleaseByteArrayElements( env, materialIndices, m, JNI_ABORT );

	if ( mb3_check( env ) )
	{
		if ( mesh != NULL )
			b3DestroyMesh( mesh );
		return 0;
	}
	if ( mesh == NULL )
	{
		mb3_throw( env, "Box3D could not build the mesh (every triangle degenerate?)" );
		return 0;
	}
	return (jlong)(intptr_t)mesh;
}

MB3_JNI( void, destroyMesh )( JNIEnv* env, jclass type, jlong mesh )
{
	(void)type;
	if ( mesh != 0 )
	{
		b3DestroyMesh( (b3MeshData*)(intptr_t)mesh );
		mb3_check( env );
	}
}

MB3_JNI( jlong, createMeshShape )( JNIEnv* env, jclass type, jlong body, jobject definition, jlong mesh, jfloat sx,
									jfloat sy, jfloat sz )
{
	(void)type;
	if ( mesh == 0 )
	{
		mb3_throw_illegal_argument( env, "mesh is null" );
		return 0;
	}
	b3ShapeDef def;
	char name[MB3_NAME_CAPACITY];
	if ( mb3_shape_def_from_java( env, definition, &def, name, MB3_NAME_CAPACITY ) == false )
	{
		return 0;
	}
	b3ShapeId id = b3CreateMeshShape( mb3_body( body ), &def, (const b3MeshData*)(intptr_t)mesh, mb3_vec3( sx, sy, sz ) );
	mb3_shape_def_release( &def );
	// A failed check does not stop Box3D making the shape: Java never gets its id, and the shape would keep pointing
	// at a mesh Java frees
	if ( mb3_assert_pending() && B3_IS_NON_NULL( id ) )
	{
		b3DestroyShape( id, true );
	}
	if ( mb3_check( env ) )
	{
		return 0;
	}
	if ( B3_IS_NULL( id ) )
	{
		mb3_throw( env, "Box3D could not create the mesh shape" );
		return 0;
	}
	return mb3_pack_shape( id );
}
