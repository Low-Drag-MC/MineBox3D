package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;

/**
 * Box3D's {@code b3Filter}: which shapes collide with which, and which queries see them.
 *
 * <p>Two shapes collide when each one's category is in the other's mask — unless they share a
 * non-zero group index, which wins: negative never collide, positive always do.
 */
public final class Filter {
    /** What this shape is. Normally one bit. */
    public long categoryBits;
    /** What this shape accepts collision with. */
    public long maskBits;
    /** Negative: never collides with the same group. Positive: always does. Zero: no effect. */
    public int groupIndex;

    /** Box3D's defaults. */
    public Filter() {
        NativeLibrary.load();
        B3.defaultFilter(this);
    }

    public Filter(long categoryBits, long maskBits, int groupIndex) {
        this.categoryBits = categoryBits;
        this.maskBits = maskBits;
        this.groupIndex = groupIndex;
    }

    public Filter copy() {
        return new Filter(categoryBits, maskBits, groupIndex);
    }

    public void set(Filter other) {
        categoryBits = other.categoryBits;
        maskBits = other.maskBits;
        groupIndex = other.groupIndex;
    }
}
