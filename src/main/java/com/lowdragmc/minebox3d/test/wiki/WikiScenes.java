package com.lowdragmc.minebox3d.test.wiki;

import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.record.TakeOptions;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;

/** What every wiki video does before and after its take. */
final class WikiScenes {

    private WikiScenes() {}

    static void configure(ScenarioOptions options) {
        options.tags("wiki").scenarioTimeoutMs(900_000).defaultTimeoutMs(120_000);
    }

    /** 720p at 30 fps, without the pointer, clicks and keys: nothing here is clicked. */
    static void video(TakeOptions options, long posterMs) {
        options.size(1280, 720)
                .fps(30)
                .poster(posterMs)
                .cursor(false)
                .clicks(false)
                .keys(false);
    }

    /** The stage cleared and built, the camera at the take's first frame, and the world drawn. */
    static void open(ScenarioBuilder s, WikiView.Shot first, Consumer<ServerLevel> build) {
        s.server("a clear noon on the flat world, the set built", sc -> {
                    WikiStage.open(sc.level(), sc.player());
                    build.accept(sc.level());
                })
                .step("bodies drawn solid, the camera in place", ctx -> {
                    ShowcaseRenderer.enable(true);
                    WikiView.play(first, 1);
                })
                .ticks(40)
                .waitUntil("the world is drawn", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .frames(10);
    }

    static void close(ScenarioBuilder s) {
        s.teardown("the camera and the drawing back", ctx -> {
                    WikiView.stop();
                    ShowcaseRenderer.enable(false);
                })
                .teardownServer("the site cleared", sc -> WikiStage.close(sc.level()));
    }
}
