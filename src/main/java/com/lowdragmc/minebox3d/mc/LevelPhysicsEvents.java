package com.lowdragmc.minebox3d.mc;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import com.lowdragmc.minebox3d.mc.sync.SyncedBodies;
import com.lowdragmc.minebox3d.mc.sync.SyncedRiders;
import com.lowdragmc.minebox3d.mc.terrain.TerrainStreamer;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

/** Wires {@link LevelPhysics} to the level's life: its tick, its chunks, its unload. */
@EventBusSubscriber(modid = MineBox3D.MOD_ID)
public final class LevelPhysicsEvents {

    private LevelPhysicsEvents() {}

    /**
     * Server levels step at the end of their own tick (which fires even while {@code /tick freeze} holds the level;
     * {@link LevelPhysics#tick()} looks at that). Client levels step from the client tick (client/ClientPhysicsEvents).
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (!level.isClientSide) {
            LevelPhysics physics = LevelPhysics.existing(level);
            if (physics != null) {
                physics.tick();
            }
            drainLog();
        }
    }

    /** A server tick starts: an entity move a thrown error left open on this thread is let go (EntityCollisions). */
    @SubscribeEvent
    public static void onServerTickStart(ServerTickEvent.Pre event) {
        EntityCollisions.newTick();
    }

    /** Every level has sent its bodies: each client waiting for those around its player is told (SyncedRiders). */
    @SubscribeEvent
    public static void onServerTickEnd(ServerTickEvent.Post event) {
        SyncedRiders.afterServerTick(event.getServer().getTickCount());
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            LevelPhysics.unload(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LevelPhysics.unloadAll(false);
    }

    // A client that drops a level drops the synced bodies it had of it: the server forgets it sent them

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SyncedBodies.forget(player, true);
            SyncedRiders.forget(player, true);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SyncedBodies.forget(player, false);
            SyncedRiders.forget(player, false);
        }
    }

    /**
     * A respawn: the new player stands on nothing; in another dimension its client also dropped what it had of the old
     * one. One in the same keeps the client's level, and what it was sent.
     */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SyncedBodies.forget(player, false);
            SyncedRiders.forget(player, false);
        }
    }

    // Chunk and block changes go to every terrain streamer of the level: the level's own and private ones

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof Level level) {
            TerrainStreamer.forLevel(
                    level, streamer -> streamer.onChunkLoaded(event.getChunk().getPos()));
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            TerrainStreamer.forLevel(
                    level, streamer -> streamer.onChunkUnloaded(event.getChunk().getPos()));
        }
    }

    /** From the {@code LevelChunk.setBlockState} hook: every block change, either side, on whatever thread made it. */
    public static void onBlockChanged(Level level, BlockPos pos, @Nullable BlockState previous, BlockState current) {
        TerrainStreamer.forLevel(level, streamer -> streamer.onBlockChanged(pos, previous, current));
    }

    /** Box3D's warnings, from whatever thread they happened on, into the game log. */
    public static void drainLog() {
        if (!NativeLibrary.isLoaded()) {
            return;
        }
        for (String message = B3.pollLog(); message != null; message = B3.pollLog()) {
            MineBox3D.LOGGER.warn("Box3D: {}", message);
        }
    }
}
