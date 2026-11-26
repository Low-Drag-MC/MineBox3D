package com.lowdragmc.minebox3d.test.wiki;

import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The server side of the wiki's videos: a clear noon on the flat world, sets built from blocks, and bodies made in the
 * level's own physics and synced to the client — what a mod using MineBox3D does. Everything here runs on the server
 * thread.
 */
final class WikiStage {

    /** The flat world's surface: the first block above the grass. */
    static final int Y = -60;
    /** How far from the site's middle a set may reach, and what {@link #clear} clears. */
    static final int REACH = 24;

    enum Mark {
        MARK
    }

    private static final List<LevelPhysics.TickListener> listeners = new ArrayList<>();
    static volatile float tickMillis;
    static volatile int bodies;

    private WikiStage() {}

    static LevelPhysics physics(ServerLevel level) {
        LevelPhysics physics = LevelPhysics.of(level);
        if (physics == null) {
            throw new IllegalStateException("Box3D is not available on the server");
        }
        return physics;
    }

    /** Noon, no weather, the player a spectator floating at the site, and the site cleared. */
    static void open(ServerLevel level, ServerPlayer player) {
        level.setDayTime(6000);
        level.setWeatherParameters(12000, 0, false, false);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        player.setGameMode(GameType.SPECTATOR);
        player.teleportTo(level, 0.5, Y + 12, -20, 0, 20);
        clear(level);
        LevelPhysics physics = physics(level);
        LevelPhysics.TickListener stats = new LevelPhysics.TickListener() {
            @Override
            public void afterSteps(LevelPhysics p) {
                tickMillis = p.lastTickMillis();
            }
        };
        physics.addTickListener(stats);
        listeners.add(stats);
    }

    /** Bodies, listeners, blocks and entities of the site gone. */
    static void close(ServerLevel level) {
        LevelPhysics physics = LevelPhysics.existing(level);
        if (physics != null) {
            listeners.forEach(physics::removeTickListener);
            List<Body> mine = new ArrayList<>();
            for (Body body : physics.world().bodies()) {
                if (body.attachment(Mark.class) != null) {
                    mine.add(body);
                }
            }
            mine.forEach(Body::destroy);
        }
        listeners.clear();
        bodies = 0;
        clear(level);
    }

    static void clear(ServerLevel level) {
        fill(level, -REACH, Y, -REACH, REACH, Y + 30, REACH, Blocks.AIR.defaultBlockState());
        fill(level, -REACH, Y - 1, -REACH, REACH, Y - 1, REACH, Blocks.GRASS_BLOCK.defaultBlockState());
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof ServerPlayer)
                    && Math.abs(entity.getX()) < REACH + 8
                    && Math.abs(entity.getZ()) < REACH + 8) {
                entity.discard();
            }
        }
    }

    static void fill(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL);
                }
            }
        }
    }

    static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL);
    }

    /** A synced dynamic body with one shape, marked as the site's. */
    static Body body(LevelPhysics physics, double x, double y, double z, Geometry geometry, ShapeDef shape) {
        Body body = physics.createBody(
                new BodyDef().type(BodyType.DYNAMIC).position(x, y, z).name("wiki"));
        body.createShape(shape, geometry);
        LevelBodies.setSynced(body, true);
        body.attach(Mark.class, Mark.MARK);
        bodies++;
        return body;
    }

    static ShapeDef wood() {
        return new ShapeDef().density(400).friction(0.6f);
    }

    /** A box, a ball or a capsule, by {@code i}: the three shapes take turns. */
    static Body any(LevelPhysics physics, double x, double y, double z, int i, Random random) {
        Body body = body(
                physics,
                x,
                y,
                z,
                switch (i % 3) {
                    case 0 -> Geometry.Box.of(0.4f, 0.4f, 0.4f);
                    case 1 -> new Geometry.Sphere(0, 0, 0, 0.35f);
                    default -> new Geometry.Capsule(0, -0.3f, 0, 0, 0.3f, 0, 0.25f);
                },
                wood());
        body.setAngularVelocity(
                (float) random.nextGaussian() * 2,
                (float) random.nextGaussian() * 2,
                (float) random.nextGaussian() * 2);
        return body;
    }

    /**
     * {@code count} bodies dropped over the disc of {@code radius} round (x, z), {@code perTick} a tick from
     * {@code height} above the surface.
     */
    static void rain(ServerLevel level, double x, double z, double radius, double height, int count, int perTick) {
        LevelPhysics physics = physics(level);
        Random random = new Random(1234);
        LevelPhysics.TickListener rain = new LevelPhysics.TickListener() {
            int made;

            @Override
            public void beforeSteps(LevelPhysics p) {
                for (int i = 0; i < perTick && made < count; i++, made++) {
                    double angle = random.nextDouble() * Math.PI * 2, r = Math.sqrt(random.nextDouble()) * radius;
                    any(
                            p,
                            x + Math.cos(angle) * r,
                            Y + height + random.nextDouble() * 3,
                            z + Math.sin(angle) * r,
                            made,
                            random);
                }
            }
        };
        physics.addTickListener(rain);
        listeners.add(rain);
    }
}
