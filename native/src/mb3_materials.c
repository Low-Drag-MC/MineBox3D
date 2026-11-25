// MineBox3D JNI bridge: friction and restitution combine modes — UE's physical material's FrictionCombineMode and
// RestitutionCombineMode on Box3D's mixing callbacks. Two surfaces without a mode mix as Box3D always did.

#include "mb3.h"

#include <math.h>

#define MB3_FRICTION_SHIFT 56
#define MB3_RESTITUTION_SHIFT 59
#define MB3_MODE_MASK 7ull

// SurfaceMaterial.Combine's order, which is UE's: of two surfaces' modes the later wins
enum
{
	MB3_COMBINE_AVERAGE,
	MB3_COMBINE_MIN,
	MB3_COMBINE_MULTIPLY,
	MB3_COMBINE_MAX
};

uint64_t mb3_material_pack( uint64_t userMaterialId, int frictionCombine, int restitutionCombine )
{
	uint64_t packed = userMaterialId & MB3_USER_MATERIAL_BITS;
	packed |= (uint64_t)( frictionCombine + 1 ) << MB3_FRICTION_SHIFT;
	packed |= (uint64_t)( restitutionCombine + 1 ) << MB3_RESTITUTION_SHIFT;
	return packed;
}

int mb3_material_friction_combine( uint64_t userMaterialId )
{
	return (int)( ( userMaterialId >> MB3_FRICTION_SHIFT ) & MB3_MODE_MASK ) - 1;
}

int mb3_material_restitution_combine( uint64_t userMaterialId )
{
	return (int)( ( userMaterialId >> MB3_RESTITUTION_SHIFT ) & MB3_MODE_MASK ) - 1;
}

static float mb3_combine( int mode, float a, float b )
{
	switch ( mode )
	{
		case MB3_COMBINE_MIN: return a < b ? a : b;
		case MB3_COMBINE_MULTIPLY: return a * b;
		case MB3_COMBINE_MAX: return a > b ? a : b;
		default: return 0.5f * ( a + b );
	}
}

// A surface without a mode counts as UE's default, Average, the earliest, against one with
static int mb3_mode( int a, int b )
{
	int mode = a > b ? a : b;
	return mode < MB3_COMBINE_AVERAGE ? MB3_COMBINE_AVERAGE : mode;
}

float mb3_mix_friction( float frictionA, uint64_t userMaterialIdA, float frictionB, uint64_t userMaterialIdB )
{
	int a = mb3_material_friction_combine( userMaterialIdA );
	int b = mb3_material_friction_combine( userMaterialIdB );
	if ( a < 0 && b < 0 )
	{
		// Box3D's b3MixFriction
		return sqrtf( frictionA * frictionB );
	}
	return mb3_combine( mb3_mode( a, b ), frictionA, frictionB );
}

float mb3_mix_restitution( float restitutionA, uint64_t userMaterialIdA, float restitutionB, uint64_t userMaterialIdB )
{
	int a = mb3_material_restitution_combine( userMaterialIdA );
	int b = mb3_material_restitution_combine( userMaterialIdB );
	if ( a < 0 && b < 0 )
	{
		// Box3D's b3MixRestitution
		return restitutionA > restitutionB ? restitutionA : restitutionB;
	}
	return mb3_combine( mb3_mode( a, b ), restitutionA, restitutionB );
}
