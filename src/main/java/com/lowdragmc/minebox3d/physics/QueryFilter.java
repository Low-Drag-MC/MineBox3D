package com.lowdragmc.minebox3d.physics;

/**
 * What a query sees: shapes whose category {@code maskBits} accepts and whose mask accepts {@code categoryBits}
 * (Box3D's {@code b3QueryFilter}). See {@link Filter} for the shapes' side.
 */
public record QueryFilter(long categoryBits, long maskBits) {

    /** Every shape, except one whose category or mask is zero. */
    public static final QueryFilter ALL = new QueryFilter(-1L, -1L);

    /** Only shapes in one of {@code maskBits}' categories, as a query in every category. */
    public static QueryFilter only(long maskBits) {
        return new QueryFilter(-1L, maskBits);
    }
}
