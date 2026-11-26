package com.lowdragmc.minebox3d.test.wiki;

import static com.lowdragmc.minebox3d.test.wiki.WikiStage.Y;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIRecording;
import com.lowdragmc.minebox3d.client.PhysicsDebugRenderer;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.Geometry;
import java.util.Random;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The terrain page: three lanes of stone, ice and slime with the same crate and ball on each, then bodies tumbling down
 * a staircase, then the debug view of what the physics sees.
 */
@LDLRegisterClient(
        name = "mb3_wiki_terrain",
        group = "minebox3d_wiki",
        registry = UIRecording.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class TerrainRecording implements UIRecording {

    /** The lanes run along +x at these z. */
    private static final int[] LANES = {-4, 0, 4};
    /** The staircase steps down along +x between these z. */
    private static final int STAIRS_Z0 = 11, STAIRS_Z1 = 15;

    private static final double[] LANES_VIEW = {0.5, Y + 9, -10, 0.5, Y, 0.5};
    private static final double[] STAIRS_VIEW = {-1, Y + 8, 3, -1, Y + 2, 13};

    @Override
    public void configure(ScenarioOptions options) {
        WikiScenes.configure(options);
    }

    @Override
    public void define(ScenarioBuilder s) {
        WikiScenes.open(s, WikiView.pan(LANES_VIEW, LANES_VIEW), TerrainRecording::build);
        s.take("terrain", o -> WikiScenes.video(o, 2500), t -> t.server("launch", sc -> launch(sc.level()))
                .caption("en", "Each block's surface: stone holds, ice lets slide, slime bounces")
                .caption("zh", "每种方块有自己的表面：石头摩擦大，冰面滑，粘液块会弹")
                .hold(6000)
                .step("to the stairs", ctx -> WikiView.play(WikiView.pan(LANES_VIEW, STAIRS_VIEW), 2500))
                .hold(1500)
                .server("tumble", sc -> tumble(sc.level()))
                .caption("en", "Stairs are stairs: two boxes, as Minecraft gives them, not a ramp")
                .caption("zh", "楼梯就是楼梯：和原版一样是两个盒子，不是斜坡")
                .hold(5500)
                .step("debug view", ctx -> {
                    LevelPhysics physics = LevelPhysics.of(ctx.mc().level);
                    if (physics != null) {
                        physics.terrain().request(new AABB(-12, Y - 2, -10, 12, Y + 10, 20), 2000);
                    }
                    if (!PhysicsDebugRenderer.isEnabled()) {
                        PhysicsDebugRenderer.toggle();
                    }
                })
                .caption("en", "/minebox3d debug: the exposed block faces the bodies touch")
                .caption("zh", "/minebox3d debug：刚体碰到的方块外露面")
                .hold(5000));
        s.teardown("debug view off", ctx -> {
            if (PhysicsDebugRenderer.isEnabled()) {
                PhysicsDebugRenderer.toggle();
            }
        });
        WikiScenes.close(s);
    }

    static void build(ServerLevel level) {
        BlockState[] floors = {
            Blocks.STONE.defaultBlockState(),
            Blocks.BLUE_ICE.defaultBlockState(),
            Blocks.SLIME_BLOCK.defaultBlockState()
        };
        for (int lane = 0; lane < 3; lane++) {
            int z = LANES[lane];
            WikiStage.fill(level, -9, Y - 1, z - 1, 9, Y - 1, z + 1, floors[lane]);
        }
        for (int z : new int[] {-6, -2, 2, 6}) {
            WikiStage.fill(level, -9, Y, z, 9, Y, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
        }
        WikiStage.fill(level, 10, Y, -6, 10, Y + 1, 6, Blocks.STONE_BRICKS.defaultBlockState());

        // a staircase down along +x, with a landing at the top to start from
        for (int i = 0; i < 7; i++) {
            int x = -7 + i, top = 6 - i;
            for (int z = STAIRS_Z0; z <= STAIRS_Z1; z++) {
                for (int y = 0; y < top; y++) {
                    WikiStage.set(level, x, Y + y, z, Blocks.STONE_BRICKS.defaultBlockState());
                }
                WikiStage.set(
                        level,
                        x,
                        Y + top,
                        z,
                        Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
            }
        }
        WikiStage.fill(level, -10, Y, STAIRS_Z0, -8, Y + 6, STAIRS_Z1, Blocks.STONE_BRICKS.defaultBlockState());
        WikiStage.fill(level, -10, Y, STAIRS_Z1 + 1, 1, Y + 7, STAIRS_Z1 + 1, Blocks.STONE_BRICKS.defaultBlockState());
    }

    /** On each lane, a crate pushed along it and a ball dropped onto it. */
    private static void launch(ServerLevel level) {
        LevelPhysics physics = WikiStage.physics(level);
        for (int z : LANES) {
            Body crate = WikiStage.body(
                    physics, -8, Y + 0.45, z + 0.5, Geometry.Box.of(0.45f, 0.45f, 0.45f), WikiStage.wood());
            crate.setLinearVelocity(12, 0, 0);
            WikiStage.body(physics, 3.5, Y + 7, z + 0.5, new Geometry.Sphere(0, 0, 0, 0.4f), WikiStage.wood());
        }
    }

    /** Bodies pushed off the landing at the top of the stairs, a few at a time. */
    private static void tumble(ServerLevel level) {
        LevelPhysics physics = WikiStage.physics(level);
        Random random = new Random(5);
        for (int i = 0; i < 24; i++) {
            double z = STAIRS_Z0 + 0.5 + random.nextDouble() * (STAIRS_Z1 - STAIRS_Z0);
            Body body = WikiStage.any(physics, -9 + (i % 3) * 0.9, Y + 7.6 + (i / 3) * 0.9, z, i, random);
            body.setLinearVelocity(3 + (float) random.nextDouble(), 0, 0);
        }
    }
}
