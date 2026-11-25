package com.lowdragmc.minebox3d.test.mp;

import com.lowdragmc.lowdraglib2.uitest.mp.MPRoles;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.sync.SyncedBodyEvent;
import com.lowdragmc.minebox3d.physics.Body;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * What a multi-process scenario reads on a client. ⚠️ The only class of the scenarios that names client types: a
 * scenario is defined on the dedicated server too, so it calls these only inside step bodies and they hand back common
 * types.
 */
final class MpClient {

    /** name → {added, removed, removed with the replica already destroyed} */
    private static final Map<String, int[]> EVENTS = new ConcurrentHashMap<>();

    private static boolean listening;

    private MpClient() {}

    @Nullable
    private static LevelPhysics physics() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : LevelPhysics.existing(level);
    }

    static List<Body> replicas(String name) {
        LevelPhysics physics = physics();
        if (physics == null) {
            return List.of();
        }
        return physics.sync().replicas().stream()
                .filter(body -> body.isValid() && name.equals(body.name()))
                .toList();
    }

    /** The one replica named {@code name}, or {@code null}. */
    @Nullable
    static Body replica(String name) {
        List<Body> found = replicas(name);
        return found.size() == 1 ? found.getFirst() : null;
    }

    /** This client's level's game time: ticks, for a wait that is polled every frame. */
    static long gameTime() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getGameTime();
    }

    @Nullable
    static Entity self() {
        return Minecraft.getInstance().player;
    }

    /** The player of a role as this client sees it (its own, or another's), or {@code null}. */
    @Nullable
    static Entity player(String role) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        String name = MPRoles.usernameFor(role);
        for (Player player : level.players()) {
            if (player.getGameProfile().getName().equals(name)) {
                return player;
            }
        }
        return null;
    }

    /** The entity of that custom name as this client sees it, or {@code null}. */
    @Nullable
    static Entity named(String name) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.hasCustomName() && name.equals(entity.getCustomName().getString())) {
                return entity;
            }
        }
        return null;
    }

    static void sneak(boolean down) {
        Minecraft.getInstance().options.keyShift.setDown(down);
    }

    /** This client's own player walking forward, as W held, or the keyboard handed back. */
    static void walk(boolean forward) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        player.input = forward
                ? new Input() {
                    @Override
                    public void tick(boolean isSneaking, float sneakingSpeedMultiplier) {
                        up = true;
                        forwardImpulse = 1;
                    }
                }
                : new KeyboardInput(Minecraft.getInstance().options);
    }

    /** Per tick since {@link #track}: this client's own player's x and the replica's, at the tick's end. */
    private static final List<double[]> TRACK = new CopyOnWriteArrayList<>();

    @Nullable
    private static volatile String tracked;

    private static boolean ticking;

    /** Starts recording this player and the replica {@code name} every tick, from nothing; null stops. */
    static void track(@Nullable String name) {
        if (!ticking) {
            ticking = true;
            NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
                String body = tracked;
                Entity me = self();
                Body replica = body == null ? null : replica(body);
                if (me != null && replica != null) {
                    TRACK.add(new double[] {me.getX(), replica.position(new Vector3d()).x});
                }
            });
        }
        if (name != null) {
            TRACK.clear();
        }
        tracked = name;
    }

    /** What {@link #track} recorded: {player x, replica x} a tick. */
    static List<double[]> tracked() {
        return List.copyOf(TRACK);
    }

    /** Whether the server says this entity stands on a body, and this client keeps it there (SyncedRiders). */
    static boolean isPlaced(Entity entity) {
        LevelPhysics physics = physics();
        return physics != null && physics.sync().riders().isPlaced(entity);
    }

    /** Counts {@link SyncedBodyEvent}s from now on, from zero. */
    static void countEvents() {
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener(SyncedBodyEvent.Added.class, event -> count(event, 0));
            NeoForge.EVENT_BUS.addListener(
                    SyncedBodyEvent.Removed.class,
                    event -> count(event, event.getReplica().isValid() ? 1 : 2));
        }
        EVENTS.clear();
    }

    private static void count(SyncedBodyEvent event, int which) {
        String name = event.getReplica().name();
        EVENTS.computeIfAbsent(name == null ? "" : name, n -> new int[3])[which]++;
    }

    /** {added, removed, removed already destroyed} for the replicas named {@code name} since {@link #countEvents()}. */
    static int[] events(String name) {
        return EVENTS.getOrDefault(name, new int[3]).clone();
    }
}
