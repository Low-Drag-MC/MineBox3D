package com.lowdragmc.minebox3d.test.wiki;

import static com.lowdragmc.minebox3d.test.wiki.WikiStage.Y;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIRecording;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.physics.Geometry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * The terrain page's edits: a pile of crates on a wooden floor that goes away a row at a time, an explosion in the
 * pile, and a wall of blocks placed through it.
 */
@LDLRegisterClient(
        name = "mb3_wiki_edits",
        group = "minebox3d_wiki",
        registry = UIRecording.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class EditsRecording implements UIRecording {

    private static final int FLOOR = Y + 4;
    private static final double[] FROM = {-11, Y + 9, -12, 0, Y + 2.5, 0};
    private static final double[] TO = {-7, Y + 7, -11, 0, Y + 1.5, 0};

    @Override
    public void configure(ScenarioOptions options) {
        WikiScenes.configure(options);
    }

    @Override
    public void define(ScenarioBuilder s) {
        WikiScenes.open(s, WikiView.pan(FROM, FROM), EditsRecording::build);
        s.server("the pile", sc -> pile(sc.level())).ticks(80);
        s.take("edits", o -> WikiScenes.video(o, 3000), t -> {
            t.step("dolly", ctx -> WikiView.play(WikiView.pan(FROM, TO), 16_000))
                    .caption("en", "A block changes, and the bodies on it know at once")
                    .caption("zh", "方块一变，上面的刚体马上就知道")
                    .hold(1500);
            for (int x = -3; x <= 3; x++) {
                int row = x;
                t.server(
                                "floor row " + row,
                                sc -> WikiStage.fill(
                                        sc.level(), row, FLOOR, -3, row, FLOOR, 3, Blocks.AIR.defaultBlockState()))
                        .hold(250);
            }
            t.hold(3000)
                    .server("boom", sc -> boom(sc.level()))
                    .caption("en", "PhysicsWorld.explode pushes every body in reach")
                    .caption("zh", "PhysicsWorld.explode 推开范围内的每个刚体")
                    .hold(3500)
                    .server(
                            "a wall placed through the pile",
                            sc -> WikiStage.fill(
                                    sc.level(), 0, Y, -6, 0, Y + 1, 6, Blocks.POLISHED_ANDESITE.defaultBlockState()))
                    .caption("en", "Blocks placed into bodies push them out")
                    .caption("zh", "放进刚体里的方块会把它们推出去")
                    .hold(4000);
        });
        WikiScenes.close(s);
    }

    static void build(ServerLevel level) {
        WikiStage.fill(level, -9, Y - 1, -9, 9, Y - 1, 9, Blocks.SMOOTH_STONE.defaultBlockState());
        for (int[] corner : new int[][] {{-4, -4}, {4, -4}, {-4, 4}, {4, 4}}) {
            WikiStage.fill(
                    level,
                    corner[0],
                    Y,
                    corner[1],
                    corner[0],
                    FLOOR,
                    corner[1],
                    Blocks.STONE_BRICKS.defaultBlockState());
        }
        WikiStage.fill(level, -3, FLOOR, -3, 3, FLOOR, 3, Blocks.OAK_PLANKS.defaultBlockState());
    }

    /** Crates in layers on the floor: 6 x 6, 5 x 5, 4 x 4 and 3 x 3. */
    private static void pile(ServerLevel level) {
        LevelPhysics physics = WikiStage.physics(level);
        for (int layer = 0; layer < 4; layer++) {
            int side = 6 - layer;
            for (int i = 0; i < side; i++) {
                for (int j = 0; j < side; j++) {
                    double x = 0.5 - (side - 1) / 2.0 + i, z = 0.5 - (side - 1) / 2.0 + j;
                    WikiStage.body(
                            physics,
                            x,
                            FLOOR + 1 + 0.45 + layer * 0.92,
                            z,
                            Geometry.Box.of(0.45f, 0.45f, 0.45f),
                            WikiStage.wood());
                }
            }
        }
    }

    private static void boom(ServerLevel level) {
        double x = 1.5, y = Y + 0.5, z = 0.5;
        WikiStage.physics(level).world().explode(x, y, z, 5, 2, 2500);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0, 0, 0, 0);
    }
}
