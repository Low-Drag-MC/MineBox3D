// MineBox3D JNI bridge: a turned box swept exactly through a world's shapes (EntityStudio doc 58 §16).
//
// What a character mover sweeping a turned box needs and Box3D's own cast does not give: the box stops where it touches
// (Box3D's cast stops a linear slop short); touching is no hit unless the box moves into the surface; and a box that
// starts inside a shape is told how far out along which way (Box3D's cast passes through it). Each shape is worked in its
// body's frame, the box put there from double positions, so the answer is as exact far from the origin as near it.

#include "mb3.h"

#include <float.h>
#include <stdlib.h>
#include <string.h>

// Closer than this, metres, a box touches a shape, and inside it by no more than this it is touching it too: no hit unless
// moving on takes it in deeper. The advancement stops half of it short, so the next sweep from there starts touching.
// Near a body's origin; further out it is what a float there can still tell apart (mb3_sweep_tolerance).
#define MB3_SWEEP_TOUCH 1.0e-5f
#define MB3_SWEEP_ITERATIONS 40
#define MB3_SWEEP_POINTS 16

// [fraction, nx, ny, nz, px, py, pz, depth, startPenetrating] and [shape]
#define MB3_SWEEP_DOUBLES 9
#define MB3_SWEEP_LONGS 1

typedef struct mb3_sweep_box
{
	b3Vec3 corners[8]; // in the box's own frame
	b3BoxHull hull;
	float radius;
} mb3_sweep_box;

// The box against one shape's body: the box at the start and its move, in that body's frame, and how close is touching
// there.
typedef struct mb3_sweep_pair
{
	const mb3_sweep_box* box;
	b3Transform boxInBody;
	b3Vec3 translation;
	bool ignoreExits;
	float touch;
} mb3_sweep_pair;

typedef struct mb3_sweep_hit
{
	float fraction;
	b3Vec3 normal; // in the body's frame: out of the shape, toward the box
	b3Vec3 point;  // in the body's frame
	float depth;
	bool startPenetrating;
} mb3_sweep_hit;

typedef enum mb3_obstacle_kind
{
	MB3_OBSTACLE_HULL,
	MB3_OBSTACLE_SPHERE,
	MB3_OBSTACLE_CAPSULE,
	MB3_OBSTACLE_TRIANGLE,
} mb3_obstacle_kind;

// One convex piece of a shape, in its body's frame: the shape itself, or one triangle of a mesh or height field.
typedef struct mb3_obstacle
{
	mb3_obstacle_kind kind;
	b3ShapeProxy proxy;
	const b3HullData* hull;
	b3Sphere sphere;
	b3Capsule capsule;
	b3Vec3 triangle[3];
} mb3_obstacle;

// How close is touching for one pair: MB3_SWEEP_TOUCH, or a few floats' worth of how far the box gets from the body's
// origin — a large mesh or height field puts the box many metres from it, where a float steps by more than a touch.
static float mb3_sweep_tolerance( const mb3_sweep_pair* pair )
{
	float scale = b3Length( pair->boxInBody.p ) + b3Length( pair->translation ) + pair->box->radius;
	return fmaxf( MB3_SWEEP_TOUCH, 8.0f * FLT_EPSILON * scale );
}

// Starting inside before not, the deepest first; then the soonest — EntityStudio's BoxList, so either source may win.
static bool mb3_sweep_earlier( const mb3_sweep_hit* a, const mb3_sweep_hit* b )
{
	if ( a->startPenetrating != b->startPenetrating )
	{
		return a->startPenetrating;
	}
	if ( a->startPenetrating )
	{
		return a->depth > b->depth;
	}
	return a->fraction < b->fraction;
}

// How far the box placed at xf is into the obstacle and which way is out, from Box3D's own contact manifolds (separating
// axes, so the least way out). False when they make no contact at all.
static bool mb3_sweep_contact( const mb3_obstacle* o, const mb3_sweep_box* box, b3Transform xf, float* depth, b3Vec3* normal,
							   b3Vec3* point )
{
	b3LocalManifoldPoint points[MB3_SWEEP_POINTS];
	b3LocalManifold manifold = { 0 };
	manifold.points = points;
	const b3HullData* boxHull = &box->hull.base;
	// the frame the manifold is in, as a transform into the body's; and whether its normal already points at the box
	b3Transform frame = b3Transform_identity;
	bool towardBox = true;
	switch ( o->kind )
	{
		case MB3_OBSTACLE_HULL:
		{
			b3SATCache cache = { 0 };
			b3CollideHulls( &manifold, MB3_SWEEP_POINTS, o->hull, boxHull, xf, &cache );
			break;
		}
		case MB3_OBSTACLE_SPHERE:
		{
			b3SimplexCache cache = { 0 };
			b3CollideHullAndSphere( &manifold, MB3_SWEEP_POINTS, boxHull, &o->sphere, b3InvMulTransforms( xf, b3Transform_identity ),
									&cache );
			frame = xf;
			towardBox = false;
			break;
		}
		case MB3_OBSTACLE_CAPSULE:
		{
			b3SimplexCache cache = { 0 };
			b3CollideHullAndCapsule( &manifold, MB3_SWEEP_POINTS, boxHull, &o->capsule, b3InvMulTransforms( xf, b3Transform_identity ),
									 &cache );
			frame = xf;
			towardBox = false;
			break;
		}
		case MB3_OBSTACLE_TRIANGLE:
		{
			// the triangle into the box's frame: the hull is the box's own
			b3Vec3 v1 = b3InvTransformPoint( xf, o->triangle[0] );
			b3Vec3 v2 = b3InvTransformPoint( xf, o->triangle[1] );
			b3Vec3 v3 = b3InvTransformPoint( xf, o->triangle[2] );
			b3SATCache cache = { 0 };
			b3CollideTriangleAndHull( &manifold, MB3_SWEEP_POINTS, v1, v2, v3, boxHull, &cache, false );
			frame = xf;
			break;
		}
	}
	if ( manifold.pointCount == 0 )
	{
		return false;
	}
	int deepest = 0;
	for ( int i = 1; i < manifold.pointCount; ++i )
	{
		if ( manifold.points[i].separation < manifold.points[deepest].separation )
		{
			deepest = i;
		}
	}
	b3Vec3 n = b3RotateVector( frame.q, manifold.normal );
	*normal = towardBox ? n : b3Neg( n );
	*point = b3TransformPoint( frame, manifold.points[deepest].point );
	*depth = -manifold.points[deepest].separation;
	return true;
}

// How far in the box placed at xf is along n, the way out of the obstacle: how far it would have to move along n to be
// clear of it. Not the least way out, but a true one.
static float mb3_sweep_overlap_along( const mb3_obstacle* o, const mb3_sweep_box* box, b3Transform xf, b3Vec3 n )
{
	float far = -FLT_MAX;
	for ( int i = 0; i < o->proxy.count; ++i )
	{
		far = fmaxf( far, b3Dot( o->proxy.points[i], n ) );
	}
	far += o->proxy.radius;
	float near = FLT_MAX;
	for ( int i = 0; i < 8; ++i )
	{
		near = fminf( near, b3Dot( b3TransformPoint( xf, box->corners[i] ), n ) );
	}
	return far - near;
}

// Where the box is at time t against the obstacle: apart by a gap (s > 0) or in by a depth (s < 0), which way is out
// toward the box, and the obstacle's point there.
static void mb3_sweep_separation( const mb3_obstacle* o, const mb3_sweep_pair* pair, b3DistanceInput* input, b3SimplexCache* cache,
								  float t, float* s, b3Vec3* normal, b3Vec3* point )
{
	input->transform.p = b3MulAdd( pair->boxInBody.p, t, pair->translation );
	b3DistanceOutput out = b3ShapeDistance( input, cache, NULL, 0 );
	if ( out.distance > 0.0f )
	{
		*s = out.distance;
		*normal = out.normal;
		*point = out.pointA;
		return;
	}
	float depth;
	if ( mb3_sweep_contact( o, pair->box, input->transform, &depth, normal, point ) )
	{
		*s = -depth;
		return;
	}
	// overlapping with no manifold to say how: out from the obstacle's middle through the box's, or back the way it came
	b3Vec3 middle = b3Vec3_zero;
	for ( int i = 0; i < o->proxy.count; ++i )
	{
		middle = b3Add( middle, o->proxy.points[i] );
	}
	middle = b3MulSV( 1.0f / (float)o->proxy.count, middle );
	b3Vec3 away = b3Sub( input->transform.p, middle );
	if ( b3LengthSquared( away ) < FLT_EPSILON )
	{
		away = b3LengthSquared( pair->translation ) > 0.0f ? b3Neg( pair->translation ) : b3Vec3_axisY;
	}
	*normal = b3Normalize( away );
	*point = middle;
	*s = -mb3_sweep_overlap_along( o, pair->box, input->transform, *normal );
}

// Whether the box, touching the obstacle at t, goes on into it deeper than touching before its move ends: what makes a
// touch a hit. Not, along a surface it rests on, across a seam where the next shape starts flush with the last, or past a
// corner it brushes. The separation along a straight move is convex in t, so a step that takes the box at most 2·touch
// past where its last reading put the surface cannot go deeper than that within it, and a reading no longer closing has
// seen the least of it.
static bool mb3_sweep_goes_in( const mb3_obstacle* o, const mb3_sweep_pair* pair, b3DistanceInput* input, b3SimplexCache* cache,
							   float t, float s, b3Vec3 normal )
{
	for ( int i = 0; i < MB3_SWEEP_ITERATIONS; ++i )
	{
		if ( s < -pair->touch )
		{
			return true;
		}
		float approach = -b3Dot( pair->translation, normal );
		if ( approach <= 0.0f || t >= 1.0f )
		{
			return false;
		}
		t = fminf( 1.0f, t + ( s + 2.0f * pair->touch ) / approach );
		b3Vec3 point;
		mb3_sweep_separation( o, pair, input, cache, t, &s, &normal, &point );
	}
	return s < -pair->touch;
}

// The box swept against one convex obstacle. Starting inside it deeper than touching: how deep and which way out.
// Apart: conservative advancement — each step as far as the gap over the speed it closes at, which never passes the
// surface for a box that only translates — to half a touch short of it. Touching, there or from the start: a hit only if
// moving on takes it in.
static bool mb3_sweep_obstacle( const mb3_obstacle* o, const mb3_sweep_pair* pair, mb3_sweep_hit* hit )
{
	b3DistanceInput input;
	input.proxyA = o->proxy;
	input.proxyB = ( b3ShapeProxy ){ pair->box->corners, 8, 0.0f };
	input.transform = pair->boxInBody;
	input.useRadii = true;
	b3SimplexCache cache = { 0 };
	float s;
	b3Vec3 normal;
	b3Vec3 point;
	mb3_sweep_separation( o, pair, &input, &cache, 0.0f, &s, &normal, &point );

	if ( s < -pair->touch )
	{
		if ( pair->ignoreExits && b3Dot( normal, pair->translation ) > 0.0f )
		{
			return false; // moving out of it
		}
		*hit = ( mb3_sweep_hit ){ 0.0f, normal, point, -s, true };
		return true;
	}

	float t = 0.0f;
	float target = 0.5f * pair->touch;
	if ( s > pair->touch )
	{
		for ( int i = 0; i < MB3_SWEEP_ITERATIONS; ++i )
		{
			float approach = -b3Dot( pair->translation, normal );
			if ( approach <= 0.0f )
			{
				return false; // closing on it no more: it is passed by
			}
			t += ( s - 0.5f * target ) / approach;
			if ( t > 1.0f )
			{
				return false;
			}
			mb3_sweep_separation( o, pair, &input, &cache, t, &s, &normal, &point );
			if ( s <= target )
			{
				break;
			}
		}
	}
	if ( mb3_sweep_goes_in( o, pair, &input, &cache, t, s, normal ) == false )
	{
		return false;
	}
	*hit = ( mb3_sweep_hit ){ t, normal, point, 0.0f, false };
	return true;
}

// Every triangle of a mesh or height field the swept box's bounds take in, one obstacle each.
typedef struct mb3_sweep_triangles
{
	const mb3_sweep_pair* pair;
	mb3_sweep_hit* best;
	bool found;
} mb3_sweep_triangles;

static bool mb3_sweep_triangle_fcn( b3Vec3 a, b3Vec3 b, b3Vec3 c, int triangleIndex, void* context )
{
	(void)triangleIndex;
	mb3_sweep_triangles* triangles = context;
	// one-sided, as Box3D's own triangle contact: a box whose middle is behind the face is not met by it
	b3Vec3 face = b3Cross( b3Sub( b, a ), b3Sub( c, a ) );
	if ( b3Dot( face, b3Sub( triangles->pair->boxInBody.p, a ) ) < -B3_LINEAR_SLOP * b3Length( face ) )
	{
		return true;
	}
	mb3_obstacle o = { 0 };
	o.kind = MB3_OBSTACLE_TRIANGLE;
	o.triangle[0] = a;
	o.triangle[1] = b;
	o.triangle[2] = c;
	o.proxy = ( b3ShapeProxy ){ o.triangle, 3, 0.0f };
	mb3_sweep_hit hit;
	if ( mb3_sweep_obstacle( &o, triangles->pair, &hit ) && ( triangles->found == false || mb3_sweep_earlier( &hit, triangles->best ) ) )
	{
		*triangles->best = hit;
		triangles->found = true;
	}
	return true;
}

// The bounds the box sweeps through, in the body's frame, a touch wider.
static b3AABB mb3_sweep_bounds( const mb3_sweep_pair* pair )
{
	b3AABB bounds = { { FLT_MAX, FLT_MAX, FLT_MAX }, { -FLT_MAX, -FLT_MAX, -FLT_MAX } };
	for ( int i = 0; i < 8; ++i )
	{
		b3Vec3 p = b3TransformPoint( pair->boxInBody, pair->box->corners[i] );
		b3Vec3 q = b3Add( p, pair->translation );
		bounds.lowerBound = b3Min( bounds.lowerBound, b3Min( p, q ) );
		bounds.upperBound = b3Max( bounds.upperBound, b3Max( p, q ) );
	}
	b3Vec3 margin = { 2.0f * pair->touch, 2.0f * pair->touch, 2.0f * pair->touch };
	bounds.lowerBound = b3Sub( bounds.lowerBound, margin );
	bounds.upperBound = b3Add( bounds.upperBound, margin );
	return bounds;
}

// The box against one shape, in its body's frame. False for one it does not touch, and for kinds it does not sweep
// (compounds, voxel terrain — a mod's own blocks are its own to sweep).
static bool mb3_sweep_shape( b3ShapeId shape, const mb3_sweep_pair* pair, mb3_sweep_hit* hit )
{
	mb3_obstacle o = { 0 };
	switch ( b3Shape_GetType( shape ) )
	{
		case b3_hullShape:
			o.kind = MB3_OBSTACLE_HULL;
			o.hull = b3Shape_GetHull( shape );
			o.proxy = ( b3ShapeProxy ){ b3GetHullPoints( o.hull ), o.hull->vertexCount, 0.0f };
			return mb3_sweep_obstacle( &o, pair, hit );
		case b3_sphereShape:
			o.kind = MB3_OBSTACLE_SPHERE;
			o.sphere = b3Shape_GetSphere( shape );
			o.proxy = ( b3ShapeProxy ){ &o.sphere.center, 1, o.sphere.radius };
			return mb3_sweep_obstacle( &o, pair, hit );
		case b3_capsuleShape:
			// center1 and center2 sit next to each other: the two points of the proxy
			o.kind = MB3_OBSTACLE_CAPSULE;
			o.capsule = b3Shape_GetCapsule( shape );
			o.proxy = ( b3ShapeProxy ){ &o.capsule.center1, 2, o.capsule.radius };
			return mb3_sweep_obstacle( &o, pair, hit );
		case b3_meshShape:
		{
			b3Mesh mesh = b3Shape_GetMesh( shape );
			mb3_sweep_triangles triangles = { pair, hit, false };
			b3QueryMesh( &mesh, mb3_sweep_bounds( pair ), mb3_sweep_triangle_fcn, &triangles );
			return triangles.found;
		}
		case b3_heightShape:
		{
			mb3_sweep_triangles triangles = { pair, hit, false };
			b3QueryHeightField( b3Shape_GetHeightField( shape ), mb3_sweep_bounds( pair ), mb3_sweep_triangle_fcn, &triangles );
			return triangles.found;
		}
		default:
			return false;
	}
}

typedef struct mb3_sweep_shapes
{
	b3ShapeId* ids;
	int capacity;
	int count;
	bool full;
} mb3_sweep_shapes;

static bool mb3_sweep_gather_fcn( b3ShapeId shapeId, void* context )
{
	mb3_sweep_shapes* shapes = context;
	if ( shapes->count == shapes->capacity )
	{
		int capacity = shapes->capacity * 2;
		b3ShapeId* grown = realloc( shapes->ids, capacity * sizeof( b3ShapeId ) );
		if ( grown == NULL )
		{
			shapes->full = true;
			return false;
		}
		shapes->ids = grown;
		shapes->capacity = capacity;
	}
	shapes->ids[shapes->count++] = shapeId;
	return true;
}

// ignoreBody: a body whose shapes are not swept (0 for none) — what the box stands on and is being carried with, which
// moved first and would otherwise be met as the box catches up with it. tag: what the box is (0 for nothing) — the bodies
// that never meet it (mb3_pairs.c) are not swept either
MB3_JNI( jboolean, worldSweepBox )( JNIEnv* env, jclass type, jint world, jdouble cx, jdouble cy, jdouble cz, jfloat qx, jfloat qy,
									jfloat qz, jfloat qw, jfloat hx, jfloat hy, jfloat hz, jfloat tx, jfloat ty, jfloat tz,
									jlong categoryBits, jlong maskBits, jboolean ignoreExits, jlong ignoreBody, jlong tag,
									jdoubleArray doubles, jlongArray longs )
{
	(void)type;
	if ( doubles == NULL || longs == NULL || ( *env )->GetArrayLength( env, doubles ) < MB3_SWEEP_DOUBLES ||
		 ( *env )->GetArrayLength( env, longs ) < MB3_SWEEP_LONGS )
	{
		mb3_throw_illegal_argument( env, "sweep arrays are too short" );
		return JNI_FALSE;
	}
	if ( !( hx > 0.0f && hy > 0.0f && hz > 0.0f ) || !isfinite( tx + ty + tz ) || !isfinite( cx + cy + cz ) )
	{
		mb3_throw_illegal_argument( env, "a box sweep needs positive half sizes and finite numbers" );
		return JNI_FALSE;
	}

	b3Pos origin = mb3_pos( cx, cy, cz );
	b3Quat rotation = mb3_quat( qx, qy, qz, qw );
	b3Vec3 translation = mb3_vec3( tx, ty, tz );
	mb3_sweep_box box;
	box.hull = b3MakeBoxHull( hx, hy, hz );
	for ( int i = 0; i < 8; ++i )
	{
		box.corners[i] = ( b3Vec3 ){ ( i & 1 ) ? hx : -hx, ( i & 2 ) ? hy : -hy, ( i & 4 ) ? hz : -hz };
	}
	box.radius = sqrtf( hx * hx + hy * hy + hz * hz );

	// the broad phase's bounds are floats: the box's world bounds rounded outwards, start and end, a slop wider than
	// any shape's touch
	float reach = box.radius + B3_LINEAR_SLOP;
	double lowX = fmin( cx, cx + tx ) - reach, lowY = fmin( cy, cy + ty ) - reach, lowZ = fmin( cz, cz + tz ) - reach;
	double highX = fmax( cx, cx + tx ) + reach, highY = fmax( cy, cy + ty ) + reach, highZ = fmax( cz, cz + tz ) + reach;
	b3AABB bounds = {
		{ nextafterf( (float)lowX, -INFINITY ), nextafterf( (float)lowY, -INFINITY ), nextafterf( (float)lowZ, -INFINITY ) },
		{ nextafterf( (float)highX, INFINITY ), nextafterf( (float)highY, INFINITY ), nextafterf( (float)highZ, INFINITY ) },
	};
	mb3_sweep_shapes shapes = { malloc( 16 * sizeof( b3ShapeId ) ), 16, 0, false };
	if ( shapes.ids == NULL )
	{
		mb3_throw( env, "out of memory" );
		return JNI_FALSE;
	}
	b3World_OverlapAABB( mb3_world( world ), bounds, mb3_query_filter( categoryBits, maskBits ), mb3_sweep_gather_fcn, &shapes );
	if ( mb3_check( env ) || shapes.full )
	{
		if ( shapes.full )
		{
			mb3_throw( env, "out of memory" );
		}
		free( shapes.ids );
		return JNI_FALSE;
	}

	mb3_sweep_hit best = { 0 };
	b3Transform bestBody = b3Transform_identity;
	b3ShapeId bestShape = b3_nullShapeId;
	bool found = false;
	b3Transform boxInOrigin = { b3Vec3_zero, rotation };
	for ( int i = 0; i < shapes.count; ++i )
	{
		b3ShapeId shape = shapes.ids[i];
		b3BodyId bodyId = b3Shape_GetBody( shape );
		if ( b3Shape_IsSensor( shape ) || ( ignoreBody != 0 && mb3_pack_body( bodyId ) == ignoreBody ) ||
			 mb3_pairs_ignores( mb3_world( world ), bodyId, (uint64_t)tag ) )
		{
			continue;
		}
		b3Transform body = b3ToRelativeTransform( b3Body_GetTransform( bodyId ), origin );
		mb3_sweep_pair pair = { &box, b3InvMulTransforms( body, boxInOrigin ), b3InvRotateVector( body.q, translation ),
								ignoreExits == JNI_TRUE, 0.0f };
		pair.touch = mb3_sweep_tolerance( &pair );
		mb3_sweep_hit hit;
		if ( mb3_sweep_shape( shape, &pair, &hit ) && ( found == false || mb3_sweep_earlier( &hit, &best ) ) )
		{
			best = hit;
			bestBody = body;
			bestShape = shape;
			found = true;
		}
	}
	free( shapes.ids );
	if ( mb3_check( env ) || found == false )
	{
		return JNI_FALSE;
	}

	b3Vec3 normal = b3RotateVector( bestBody.q, best.normal );
	b3Vec3 point = b3TransformPoint( bestBody, best.point );
	double d[MB3_SWEEP_DOUBLES] = {
		best.fraction,	   normal.x,	 normal.y, normal.z, origin.x + point.x, origin.y + point.y, origin.z + point.z,
		best.depth, best.startPenetrating ? 1.0 : 0.0,
	};
	jlong l[MB3_SWEEP_LONGS] = { mb3_pack_shape( bestShape ) };
	( *env )->SetDoubleArrayRegion( env, doubles, 0, MB3_SWEEP_DOUBLES, d );
	( *env )->SetLongArrayRegion( env, longs, 0, MB3_SWEEP_LONGS, l );
	return JNI_TRUE;
}
