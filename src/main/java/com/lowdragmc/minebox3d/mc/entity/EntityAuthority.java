package com.lowdragmc.minebox3d.mc.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>Whose word an entity's position is</b>: its client's, the server taking it — Minecraft's rule for a player and what a
 * player steers — or the server's own. Everything in MineBox3D that treats the two apart asks here: whether the server
 * sweeps it among the bodies and keeps its base, how its proxy is driven, whether its client says where it stands and
 * reports the pushes it gives.
 *
 * <p>A mod that moves players on the server itself and only lets their clients predict (EntityStudio's movers) says so
 * ({@link #register}): MineBox3D then treats them as the server's — swept, based, pushing from the server — and their
 * clients stop reporting what the server already knows.
 */
public final class EntityAuthority {

    /** A mod's say on whose word an entity's position is. */
    public interface Provider {
        /** True when the entity's client moves it, false when the server does, null for no say. */
        @Nullable
        Boolean clientMoves(Entity entity);

        /**
         * True when a push the server gives the entity shows in where it is only some ticks later, false when on its next
         * move, null for no say ({@link EntityAuthority#pushesShowLate}).
         */
        @Nullable
        default Boolean pushesShowLate(Entity entity) {
            return null;
        }

        /**
         * True while the entity's move is one being run again — a client's prediction replayed after the server corrected
         * it — rather than lived through: it pushes no body (its pushes were given when it first ran).
         */
        default boolean isResimulating(Entity entity) {
            return false;
        }
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    /** Whether any provider says the entity's move is being run again ({@link Provider#isResimulating}). */
    public static boolean isResimulating(Entity entity) {
        for (Provider provider : PROVIDERS) {
            if (provider.isResimulating(entity)) {
                return true;
            }
        }
        return false;
    }

    private EntityAuthority() {
    }

    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    /**
     * Whether the entity's client moves it and the server takes its word — the first provider with a say, else
     * Minecraft's rule: a player, or what a player steers. The same answer on both sides.
     */
    public static boolean clientMoves(Entity entity) {
        for (Provider provider : PROVIDERS) {
            Boolean said = provider.clientMoves(entity);
            if (said != null) {
                return said;
            }
        }
        return entity instanceof Player || entity.getControllingPassenger() instanceof Player;
    }

    /**
     * Whether a push the server gives the entity shows in where it is only some ticks later — the first provider with a
     * say, else whether its client moves it (its move of the push comes a round trip on). A mod that moves an entity on
     * the server on a schedule its client predicts too (EntityStudio's movers, for a player) applies a push that late.
     * While it has not shown, MineBox3D does not take "not seen moving" for "held there": a body that pushed it is not
     * met by a wall.
     */
    public static boolean pushesShowLate(Entity entity) {
        for (Provider provider : PROVIDERS) {
            Boolean said = provider.pushesShowLate(entity);
            if (said != null) {
                return said;
            }
        }
        return clientMoves(entity);
    }
}
