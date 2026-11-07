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
 * ({@link #register}): MineBox3D then treats them as the server's — swept and based on the server — and their clients
 * stop reporting what the server already knows. Such a mover usually says its moves push no body either
 * ({@link #pushesBodies}): pushed on the server, a body would go where its client cannot foresee.
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
         * True when the entity's own moves push bodies, false when they do not, null for no say
         * ({@link EntityAuthority#pushesBodies}).
         */
        @Nullable
        default Boolean pushesBodies(Entity entity) {
            return null;
        }
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

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
     * Whether the entity's own moves push the bodies it walks into, and its weight presses on the one it stands on — the
     * first provider with a say, else yes. No: bodies are walls and floors to it, as to Unreal's Mover, which puts no force
     * into physics bodies. Bodies still push it ({@link EntityPushedByBodyEvent}). The same answer on both sides.
     */
    public static boolean pushesBodies(Entity entity) {
        for (Provider provider : PROVIDERS) {
            Boolean said = provider.pushesBodies(entity);
            if (said != null) {
                return said;
            }
        }
        return true;
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
