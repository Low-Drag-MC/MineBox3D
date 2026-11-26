package com.lowdragmc.minebox3d.mc.sync;

import static org.junit.jupiter.api.Assertions.*;

import com.lowdragmc.minebox3d.mc.CollisionCategories;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * A replica's filter in its client's bits: the same as the server's where the two registered alike, and by id where they
 * did not — another order, or a category on one side only.
 */
class RemoteCategoriesTest {

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("minebox3d_test", path);
    }

    /** The server's {@code i}th category's bit. */
    private static long server(int i) {
        return 1L << (CollisionCategories.FIRST_FREE_BIT + i);
    }

    @Test
    void theSameRegistryOnBothSidesChangesNothing() {
        CollisionCategories.register(id("same_a"));
        CollisionCategories.register(id("same_b"));
        RemoteCategories remote = new RemoteCategories();
        remote.learn(0, CollisionCategories.registered(0));
        long[] filters = {
            -1L,
            0L,
            CollisionCategories.TERRAIN,
            ~(CollisionCategories.TERRAIN | CollisionCategories.ENTITY),
            CollisionCategories.register(id("same_b")) | CollisionCategories.ENTITY,
            1L << 60
        };
        for (long bits : filters) {
            assertEquals(bits, remote.translate(bits), Long.toHexString(bits));
        }
    }

    @Test
    void aCategoryIsTheClientsBitForItsId() {
        // this client asked for one of its own first, so its bit for "order_a" is not the server's first
        long clientOnly = CollisionCategories.register(id("order_client_only"));
        long a = CollisionCategories.register(id("order_a"));
        RemoteCategories remote = new RemoteCategories();
        remote.learn(0, List.of(id("order_a"), id("order_server_only")));
        long serverOnly = CollisionCategories.register(id("order_server_only"));
        assertNotEquals(server(0), a, "the two sides' bits differ");

        assertEquals(a, remote.translate(server(0)));
        assertEquals(serverOnly, remote.translate(server(1)), "one the client did not have, registered here");
        assertEquals(
                CollisionCategories.TERRAIN | a,
                remote.translate(CollisionCategories.TERRAIN | server(0)),
                "MineBox3D's own as they are");
        assertEquals(
                0, remote.translate(server(0)) & clientOnly, "a category the server named is not the client's own");

        // every category: Box3D's default, and a mask of all but some, are every category here too
        assertEquals(-1L, remote.translate(-1L));
        assertEquals(~CollisionCategories.ENTITY, remote.translate(~CollisionCategories.ENTITY));
        assertEquals(~a, remote.translate(~server(0)), "all but the server's first: all but its bit here");
        assertEquals(0L, remote.translate(0L));
    }

    @Test
    void theServersIdsComeAPartAtATime() {
        RemoteCategories whole = new RemoteCategories(), parts = new RemoteCategories();
        whole.learn(0, List.of(id("parts_a"), id("parts_b")));
        parts.learn(0, List.of(id("parts_a")));
        parts.learn(1, List.of(id("parts_b")));
        for (long bits : new long[] {server(0), server(1), server(0) | server(1), -1L}) {
            assertEquals(whole.translate(bits), parts.translate(bits), Long.toHexString(bits));
        }
        // sent again from the start (a new view of the same client): the same
        parts.learn(0, List.of(id("parts_a"), id("parts_b")));
        assertEquals(whole.translate(server(1)), parts.translate(server(1)));
        // a part after a gap is not guessed at
        parts.learn(5, List.of(id("parts_gap")));
        assertEquals(whole.translate(server(5)), parts.translate(server(5)));
    }
}
