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

/**
 * The performance page: two thousand bodies poured into an arena, the server's physics time read out on screen. The
 * time is the tick's wall time measured by the server, so it is what the run cost, not video time.
 */
@LDLRegisterClient(
        name = "mb3_wiki_scale",
        group = "minebox3d_wiki",
        registry = UIRecording.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ScaleRecording implements UIRecording {

    private static final int HALF = 9;

    @Override
    public void configure(ScenarioOptions options) {
        WikiScenes.configure(options);
    }

    @Override
    public void define(ScenarioBuilder s) {
        WikiScenes.open(s, WikiView.orbit(0.5, Y, 0.5, 21, 15, -20, -20), ScaleRecording::build);
        s.take("scale", o -> WikiScenes.video(o, 11_000), t -> t.step("the readout and the orbit", ctx -> {
                    WikiView.showStats(true);
                    WikiView.play(WikiView.orbit(0.5, Y, 0.5, 21, 15, -20, 30), 16_000);
                })
                .server("pour", sc -> WikiStage.rain(sc.level(), 0.5, 0.5, HALF - 1.5, 16, 2000, 12))
                .caption("en", "2,000 bodies on one server thread, the level's default")
                .caption("zh", "两千个刚体，跑在服务端的一个线程上（关卡的默认设置）")
                .hold(16_000));
        WikiScenes.close(s);
    }

    /** A walled arena with rows of stairs across its floor. */
    static void build(ServerLevel level) {
        WikiStage.fill(
                level, -HALF - 1, Y - 1, -HALF - 1, HALF + 1, Y - 1, HALF + 1, Blocks.SMOOTH_STONE.defaultBlockState());
        for (int i = -HALF - 1; i <= HALF + 1; i++) {
            for (int y = Y; y < Y + 3; y++) {
                WikiStage.set(level, i, y, -HALF - 1, Blocks.STONE_BRICKS.defaultBlockState());
                WikiStage.set(level, i, y, HALF + 1, Blocks.STONE_BRICKS.defaultBlockState());
                WikiStage.set(level, -HALF - 1, y, i, Blocks.STONE_BRICKS.defaultBlockState());
                WikiStage.set(level, HALF + 1, y, i, Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        for (int z = -HALF + 2; z <= HALF - 2; z += 4) {
            for (int x = -HALF; x <= HALF; x++) {
                WikiStage.set(
                        level,
                        x,
                        Y,
                        z,
                        Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
            }
        }
    }
}
