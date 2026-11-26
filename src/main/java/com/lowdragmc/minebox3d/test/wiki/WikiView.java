package com.lowdragmc.minebox3d.test.wiki;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.Locale;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;

/**
 * The client side of the wiki's videos: where the camera is, frame by frame, on the video clock, and the readout of
 * the server's physics drawn into the frame.
 */
final class WikiView {

    /** A camera move: {@code [x, y, z, yaw, pitch]} at {@code t} from 0 to 1. */
    interface Shot {
        double[] at(double t);
    }

    private static boolean registered;
    private static Shot shot;
    private static long start, duration;
    private static boolean stats;
    private static float shownMillis;

    private WikiView() {}

    private static void register() {
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(WikiView::onFrameStart);
            // ahead of the recorder, which takes the frame in its own RenderFrameEvent.Post
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, WikiView::onFrameEnd);
            registered = true;
        }
    }

    /** From now, the camera follows {@code shot} over {@code millis} of video time, then holds its end. */
    static void play(Shot next, long millis) {
        register();
        shot = next;
        start = Util.getMillis();
        duration = Math.max(1, millis);
    }

    static void stop() {
        shot = null;
        stats = false;
    }

    static void showStats(boolean show) {
        register();
        stats = show;
    }

    /** Standing at (x, y, z), looking at (tx, ty, tz). */
    static Shot still(double x, double y, double z, double tx, double ty, double tz) {
        double[] pose = look(x, y, z, tx, ty, tz);
        return t -> pose;
    }

    /** From one point to another, looking at (tx, ty, tz) all the way. */
    static Shot dolly(
            double x0, double y0, double z0, double x1, double y1, double z1, double tx, double ty, double tz) {
        return t -> {
            double s = smooth(t);
            return look(x0 + (x1 - x0) * s, y0 + (y1 - y0) * s, z0 + (z1 - z0) * s, tx, ty, tz);
        };
    }

    /** {@code [x, y, z, tx, ty, tz]} to another: where the camera is and what it looks at, both moving. */
    static Shot pan(double[] from, double[] to) {
        return t -> {
            double s = smooth(t);
            double[] p = new double[6];
            for (int i = 0; i < 6; i++) {
                p[i] = from[i] + (to[i] - from[i]) * s;
            }
            return look(p[0], p[1], p[2], p[3], p[4], p[5]);
        };
    }

    /** Round (cx, cz) at {@code radius}, {@code height} above {@code cy}, from one angle (degrees) to another. */
    static Shot orbit(double cx, double cy, double cz, double radius, double height, double from, double to) {
        return t -> {
            double angle = Math.toRadians(from + (to - from) * smooth(t));
            return look(cx + Math.sin(angle) * radius, cy + height, cz - Math.cos(angle) * radius, cx, cy, cz);
        };
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double[] look(double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x, dy = ty - y, dz = tz - z;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new double[] {x, y, z, yaw, pitch};
    }

    private static void onFrameStart(RenderFrameEvent.Pre event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (shot == null || player == null) {
            return;
        }
        double t = Math.min(1, (Util.getMillis() - start) / (double) duration);
        double[] p = shot.at(t);
        // the camera reads the player between its last two ticks: both are put where it is to be
        player.setDeltaMovement(0, 0, 0);
        player.moveTo(p[0], p[1] - player.getEyeHeight(), p[2], (float) p[3], (float) p[4]);
        player.xo = player.xOld = player.getX();
        player.yo = player.yOld = player.getY();
        player.zo = player.zOld = player.getZ();
        player.yRotO = player.getYRot();
        player.xRotO = player.getXRot();
        player.yHeadRot = player.yHeadRotO = player.getYRot();
    }

    private static void onFrameEnd(RenderFrameEvent.Post event) {
        if (!stats) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        var target = minecraft.getMainRenderTarget();
        double scale = minecraft.getWindow().getGuiScale();
        float width = (float) (target.width / scale), height = (float) (target.height / scale);
        // eased, so the readout can be read in a video
        shownMillis += (WikiStage.tickMillis - shownMillis) * 0.05f;
        String[] lines = {
            String.format(Locale.ROOT, "%,d bodies", WikiStage.bodies),
            String.format(Locale.ROOT, "server physics %.1f ms per tick", shownMillis),
        };
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0, width, height, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.translation(0, 0, -11000);
        RenderSystem.applyModelViewMatrix();
        GuiGraphics graphics =
                new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
        try {
            float textScale = height / 360f;
            graphics.pose().pushPose();
            graphics.pose().translate(12 * textScale, 12 * textScale, 0);
            graphics.pose().scale(textScale, textScale, 1);
            int boxWidth = 0;
            for (String line : lines) {
                boxWidth = Math.max(boxWidth, minecraft.font.width(line));
            }
            graphics.fill(-6, -5, boxWidth + 6, lines.length * 12 + 3, 0xb0101216);
            for (int i = 0; i < lines.length; i++) {
                graphics.drawString(minecraft.font, lines[i], 0, i * 12, i == 0 ? 0xffffffff : 0xffb8e0ff, false);
            }
            graphics.pose().popPose();
            graphics.flush();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }
}
