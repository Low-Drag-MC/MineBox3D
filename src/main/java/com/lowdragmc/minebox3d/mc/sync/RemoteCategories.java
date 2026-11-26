package com.lowdragmc.minebox3d.mc.sync;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.CollisionCategories;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * <b>The server's collision categories in this client's bits</b>. {@link CollisionCategories#register} hands bits out
 * in the order ids are first asked for, which a dedicated server and its clients need not share, nor the ids (a mod on
 * one side only). The server sends its ids in its order ({@link BodySyncPayload#categories}), and a replica's filter is
 * put in the bits this client has for the same ids.
 */
final class RemoteCategories {

    private final List<ResourceLocation> ids = new ArrayList<>();
    /** this client's bit for each of the server's ids; 0 for one it had no bit left for */
    private final LongArrayList local = new LongArrayList();
    /** this client's bits for the server's ids, all together */
    private long mapped;

    /** The server's ids from its {@code from}th on: what it said before from there on is replaced. */
    void learn(int from, List<ResourceLocation> learned) {
        if (learned.isEmpty()) {
            return;
        }
        if (from > ids.size()) {
            MineBox3D.LOGGER.warn(
                    "the server's collision categories from {} came without those from {}", from, ids.size());
            return;
        }
        ids.subList(from, ids.size()).clear();
        local.removeElements(from, local.size());
        for (ResourceLocation id : learned) {
            ids.add(id);
            local.add(localBit(id));
        }
        mapped = 0;
        for (int i = 0; i < local.size(); i++) {
            mapped |= local.getLong(i);
        }
    }

    private static long localBit(ResourceLocation id) {
        try {
            return CollisionCategories.register(id);
        } catch (IllegalStateException full) {
            MineBox3D.LOGGER.warn("no collision category left here for the server's {}: its replicas are in none", id);
            return 0;
        }
    }

    /**
     * The server's {@code bits} in this client's: MineBox3D's own as they are; each of the server's categories as this
     * client's bit for its id; a bit neither side gave an id as it is. And when every bit the server gave no id is set
     * (Box3D's default filter, or a mask of all but some), this client's own categories too: a body of every category
     * on the server is in every category here.
     */
    long translate(long bits) {
        int n = ids.size();
        long named = n == 0 ? 0L : -1L >>> (Long.SIZE - n) << CollisionCategories.FIRST_FREE_BIT;
        long unnamed = ~(CollisionCategories.MINEBOX3D_BITS | named);
        long out = bits & CollisionCategories.MINEBOX3D_BITS;
        for (int i = 0; i < n; i++) {
            if ((bits & 1L << (CollisionCategories.FIRST_FREE_BIT + i)) != 0) {
                out |= local.getLong(i);
            }
        }
        long registered = CollisionCategories.registeredBits();
        out |= bits & unnamed & ~registered;
        if (unnamed != 0 && (bits & unnamed) == unnamed) {
            out |= registered & ~mapped;
        }
        return out;
    }
}
