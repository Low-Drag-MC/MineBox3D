package com.lowdragmc.minebox3d.mc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

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
    /** Stand-ins for Minecraft entities: their proxies. */
    public static final long ENTITY = 1L << 2;

    /** The first bit {@link #register} hands out; the {@code i}th id registered has bit {@code FIRST_FREE_BIT + i}. */
    @ApiStatus.Internal
    public static final int FIRST_FREE_BIT = 8;
    /** Bits 0–7: MineBox3D's own, the same everywhere. */
    @ApiStatus.Internal
    public static final long MINEBOX3D_BITS = (1L << FIRST_FREE_BIT) - 1;

    private static final Map<ResourceLocation, Long> REGISTERED = new LinkedHashMap<>();

    private CollisionCategories() {}

    /**
     * A category bit of a mod's own, the same bit for the same id for the life of the process.
     * Bits 0–7 are MineBox3D's; there are 56 to hand out.
     *
     * <p>Bits go out in the order ids are first asked for, so a client's bit for an id may not be its server's (the two
     * asked in another order, or a mod on one side only asked at all). A synced body's filter crosses the network by id:
     * its replica on a client is in the client's bits for the same categories.
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

    /** The ids registered from the {@code from}th on, in the order they were. */
    @ApiStatus.Internal
    public static synchronized List<ResourceLocation> registered(int from) {
        List<ResourceLocation> ids = List.copyOf(REGISTERED.keySet());
        return from >= ids.size() ? List.of() : ids.subList(from, ids.size());
    }

    /** Every bit {@link #register} has handed out. */
    @ApiStatus.Internal
    public static synchronized long registeredBits() {
        int count = REGISTERED.size();
        return count == 0 ? 0L : -1L >>> (Long.SIZE - count) << FIRST_FREE_BIT;
    }
}
