package com.lowdragmc.minebox3d.client;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.LevelPhysicsEvents;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import com.lowdragmc.minebox3d.mc.sync.StandingPayload;
import com.lowdragmc.minebox3d.mc.sync.SyncedBodies;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** The client half: stepping the client level's physics, drawing it, and the client commands. */
@EventBusSubscriber(modid = MineBox3D.MOD_ID, value = Dist.CLIENT)
public final class ClientPhysicsEvents {

    private ClientPhysicsEvents() {
    }

    /** A client tick starts: an entity move a thrown error left open on this thread is let go (EntityCollisions). */
    @SubscribeEvent
    public static void onClientTickStart(ClientTickEvent.Pre event) {
        EntityCollisions.newTick();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && !minecraft.isPaused()) {
            LevelPhysics physics = LevelPhysics.existing(minecraft.level);
            if (physics != null) {
                ClientPacketListener connection = minecraft.getConnection();
                // how long the replicas this client pushes are simulated here (SyncedBodies.predict)
                PlayerInfo self = connection == null || minecraft.player == null ? null
                        : connection.getPlayerInfo(minecraft.player.getUUID());
                if (self != null) {
                    SyncedBodies.setRoundTrip(self.getLatency());
                }
                physics.tick();
                // after the step: our player's pushes on replicas told to the server, and the others the server says
                // stand on bodies put there (design doc 04 §11.2 #3, §11.5)
                physics.sync().riders().afterClientTick(minecraft.player,
                        connection != null && connection.hasChannel(StandingPayload.TYPE));
            }
        }
        LevelPhysicsEvents.drainLog();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientPhysics.unloadAll();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || !PhysicsDebugRenderer.isEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        PhysicsDebugRenderer.render(event.getPoseStack(), minecraft.renderBuffers().bufferSource(),
                event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("minebox3d");
        root.then(Commands.literal("debug").executes(context -> {
            boolean on = PhysicsDebugRenderer.toggle();
            say(context, "MineBox3D debug view " + (on ? "on" : "off"));
            return 1;
        }));
        for (Playground.Kind kind : Playground.Kind.values()) {
            String name = kind.name().toLowerCase(java.util.Locale.ROOT);
            root.then(Commands.literal("spawn").then(Commands.literal(name)
                    .executes(context -> spawned(context, Playground.spawn(kind, 1)))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                            .executes(context -> spawned(context,
                                    Playground.spawn(kind, IntegerArgumentType.getInteger(context, "count")))))));
        }
        root.then(Commands.literal("clear").executes(context -> {
            say(context, "removed " + Playground.clear() + " bodies");
            return 1;
        }));
        root.then(Commands.literal("stats").executes(context -> {
            say(context, ClientPhysics.stats());
            return 1;
        }));
        event.getDispatcher().register(root);
    }

    private static int spawned(CommandContext<CommandSourceStack> context, int count) {
        say(context, count == 0 ? "Box3D is not available" : "spawned " + count + " (client only)");
        return count;
    }

    private static void say(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSystemMessage(Component.literal(message));
    }
}
