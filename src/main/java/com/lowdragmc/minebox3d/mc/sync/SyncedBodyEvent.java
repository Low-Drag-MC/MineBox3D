package com.lowdragmc.minebox3d.mc.sync;

import com.lowdragmc.minebox3d.physics.Body;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;

/**
 * <b>A copy of a server body came or went on this client</b> (design doc {@code 04-entities-and-bodies.md} §10):
 * fired on {@code NeoForge.EVENT_BUS}, on the client thread. The copy is a body in the client level's physics that
 * follows the server's ({@link SyncedBodies}); {@link Body#name()} and {@link Body#userData()} are what the server's
 * body was made with, so a mod can tell its own bodies apart.
 */
public abstract class SyncedBodyEvent extends Event {

    private final Level level;
    private final Body replica;
    private final int networkId;

    SyncedBodyEvent(Level level, Body replica, int networkId) {
        this.level = level;
        this.replica = replica;
        this.networkId = networkId;
    }

    public Level getLevel() {
        return level;
    }

    public Body getReplica() {
        return replica;
    }

    /** The body's number on the network, the same for every client in the level. */
    public int getNetworkId() {
        return networkId;
    }

    /** The copy was made: the player started seeing the body, or it is sent anew (after a {@link Removed}). */
    public static final class Added extends SyncedBodyEvent {
        Added(Level level, Body replica, int networkId) {
            super(level, replica, networkId);
        }
    }

    /**
     * The copy is about to go: the body was destroyed or stopped being synced, the player stopped seeing it, or it is
     * sent anew — its shapes, type or mass changed.
     */
    public static final class Removed extends SyncedBodyEvent {
        Removed(Level level, Body replica, int networkId) {
            super(level, replica, networkId);
        }
    }
}
