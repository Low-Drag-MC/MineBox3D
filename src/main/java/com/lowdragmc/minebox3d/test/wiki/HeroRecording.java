package com.lowdragmc.minebox3d.test.wiki;

import static com.lowdragmc.minebox3d.test.wiki.WikiStage.Y;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIRecording;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bodies raining onto a set of stairs, slabs, fences and walls: the wiki's index and the README.
 *
 * <p>{@code ./gradlew runClient -PldRecord=group:minebox3d_wiki -PldTestHeadless -PldTestWindow=1920x1080}
 */
@LDLRegisterClient(
        name = "mb3_wiki_hero",
        group = "minebox3d_wiki",
        registry = UIRecording.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class HeroRecording implements UIRecording {

    @Override
    public void configure(ScenarioOptions options) {
        WikiScenes.configure(options);
    }

    @Override
    public void define(ScenarioBuilder s) {
        WikiScenes.open(s, WikiView.orbit(0.5, Y + 1.5, 0.5, 15, 6, -40, -40), HeroRecording::build);
        s.take("hero", o -> WikiScenes.video(o, 9000), t -> t.step(
                        "orbit", ctx -> WikiView.play(WikiView.orbit(0.5, Y + 1.5, 0.5, 15, 6, -40, 20), 14_000))
                .server("rain", sc -> WikiStage.rain(sc.level(), 0.5, 0.5, 9, 18, 360, 2))
                .caption("en", "Boxes, balls and capsules land on each block's real collision shape")
                .caption("zh", "方块、球和胶囊落在每个方块真实的碰撞形状上")
                .hold(7000)
                .caption("en", "Simulated on the server, sent to every client nearby")
                .caption("zh", "在服务端模拟，同步给附近的每个客户端")
                .hold(7000));
        WikiScenes.close(s);
    }

    /** A stepped pyramid of stairs with a cauldron on top, a ring of fences, walls, panes and slabs. */
    static void build(ServerLevel level) {
        WikiStage.fill(level, -11, Y - 1, -11, 11, Y - 1, 11, Blocks.SMOOTH_STONE.defaultBlockState());
        for (int step = 0; step < 4; step++) {
            int r = 4 - step;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    BlockState state = Blocks.STONE_BRICKS.defaultBlockState();
                    if (Math.max(Math.abs(x), Math.abs(z)) == r) {
                        Direction facing = Math.abs(x) >= Math.abs(z)
                                ? (x > 0 ? Direction.WEST : Direction.EAST)
                                : (z > 0 ? Direction.NORTH : Direction.SOUTH);
                        state = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
                    }
                    WikiStage.set(level, x, Y + step, z, state);
                }
            }
        }
        WikiStage.set(level, 0, Y + 4, 0, Blocks.CAULDRON.defaultBlockState());
        for (int i = -8; i <= 8; i++) {
            if (Math.floorMod(i, 4) != 2) {
                WikiStage.set(level, i, Y, -8, Blocks.OAK_FENCE.defaultBlockState());
                WikiStage.set(level, i, Y, 8, Blocks.COBBLESTONE_WALL.defaultBlockState());
                WikiStage.set(level, -8, Y, i, Blocks.GLASS_PANE.defaultBlockState());
                WikiStage.set(level, 8, Y, i, Blocks.SPRUCE_FENCE.defaultBlockState());
            }
        }
        for (int[] corner : new int[][] {{-8, -8}, {8, -8}, {-8, 8}, {8, 8}}) {
            WikiStage.set(level, corner[0], Y + 1, corner[1], Blocks.LANTERN.defaultBlockState());
        }
        int[][] slabs = {{-6, -3}, {-5, 5}, {6, 2}, {3, -6}, {-2, 6}, {6, -5}, {-6, 1}};
        for (int[] slab : slabs) {
            WikiStage.set(level, slab[0], Y, slab[1], Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        }
        WikiStage.set(level, 5, Y, 5, Blocks.ANVIL.defaultBlockState());
        WikiStage.set(level, -5, Y, -5, Blocks.HOPPER.defaultBlockState());
    }
}
