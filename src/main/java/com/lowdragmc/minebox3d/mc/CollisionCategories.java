package com.lowdragmc.minebox3d.mc;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>Box3D collision category bits</b> as MineBox3D uses them, and a registry for other mods' own.
 *
 * <p>Box3D's default shape is in every category and accepts every category ({@code B3_DEFAULT_*_BITS}
 * are all ones), so a body made with a plain {@code ShapeDef} collides with terrain and entity proxies
 * without anyone setting a filter. The categories here are what terrain and proxies <i>are</i>, so a
 * mod can make its own shapes ignore them (a ragdoll that should pass through its owner, a sensor
 * that should only see entities).
 */
public final class CollisionCategories {

    /** Block collision streamed in by the terrain streamer. */
    public static final long TERRAIN = 1L << 1;
    /** Kinematic stand-ins for Minecraft entities. */
    public static final long ENTITY = 1L << 2;

    private static final int FIRST_FREE_BIT = 8;
    private static final Map<ResourceLocation, Long> REGISTERED = new LinkedHashMap<>();

    private CollisionCategories() {
    }

    /**
     * A category bit of a mod's own, the same bit for the same id for the life of the process.
     * Bits 0–7 are MineBox3D's; there are 56 to hand out.
     */
    public static synchronized long register(ResourceLocation id) {
        Long existing = REGISTERED.get(id);
        if (existing != null) {
            return existing;
        }
        int bit = FIRST_FREE_BIT + REGISTERED.size();
        if (bit >= Long.SIZE) {
            throw new IllegalStateException("all 64 Box3D collision categories are taken");
        }
        long value = 1L << bit;
        REGISTERED.put(id, value);
        return value;
    }
}
