package com.lowdragmc.minebox3d.test.wiki;

import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.sync.SyncedBodies;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.Shape;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Draws the client level's bodies solid for the wiki's videos: boxes as blocks, spheres and capsules as lit, tinted
 * meshes. MineBox3D itself draws only wireframes ({@code /minebox3d debug}); how a mod shows its bodies is up to it.
 */
final class ShowcaseRenderer {

    private static final BlockState[] BOXES = {
        Blocks.BARREL.defaultBlockState(),
        Blocks.OAK_PLANKS.defaultBlockState(),
        Blocks.BRICKS.defaultBlockState(),
        Blocks.HAY_BLOCK.defaultBlockState(),
        Blocks.BOOKSHELF.defaultBlockState(),
        Blocks.CRAFTING_TABLE.defaultBlockState(),
        Blocks.PUMPKIN.defaultBlockState(),
        Blocks.TNT.defaultBlockState(),
        Blocks.COPPER_BLOCK.defaultBlockState(),
        Blocks.MELON.defaultBlockState(),
    };
    private static final int[] TINTS = {
        0xffe8553f, 0xfff2a33a, 0xfff4d35e, 0xff7cc576, 0xff4fa3d9, 0xff9b7fd8, 0xffe88fb4, 0xfff7f4ea
    };
    private static final ResourceLocation SPHERE =
            ResourceLocation.withDefaultNamespace("textures/block/white_wool.png");
    private static final ResourceLocation CAPSULE =
            ResourceLocation.withDefaultNamespace("textures/block/white_concrete_powder.png");
    private static final int RINGS = 12, SEGMENTS = 24;

    private static boolean registered;
    private static boolean enabled;

    private ShowcaseRenderer() {}

    static void enable(boolean on) {
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(ShowcaseRenderer::onRenderLevel);
            registered = true;
        }
        enabled = on;
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || minecraft.level == null) {
            return;
        }
        LevelPhysics physics = LevelPhysics.existing(minecraft.level);
        if (physics == null) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Vector3d position = new Vector3d();
        Quaternionf rotation = new Quaternionf();
        for (Body body : physics.world().bodies()) {
            boolean replica = SyncedBodies.isReplica(body);
            if (body.type() != BodyType.DYNAMIC && !replica) {
                continue;
            }
            body.renderPosition(partial, position);
            body.renderRotation(partial, rotation);
            int light = LevelRenderer.getLightColor(
                    minecraft.level, BlockPos.containing(position.x, position.y, position.z));
            int look = Math.floorMod(
                    Long.hashCode(replica ? SyncedBodies.networkId(body) : body.id()) * 0x9e3779b1, 1 << 30);
            pose.pushPose();
            pose.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
            pose.mulPose(rotation);
            for (Shape shape : body.shapes()) {
                switch (shape.geometry()) {
                    case Geometry.Box box -> box(pose, buffers, box, BOXES[look % BOXES.length], light);
                    case Geometry.Sphere sphere -> sphere(pose, buffers, sphere, TINTS[look % TINTS.length], light);
                    case Geometry.Capsule capsule ->
                        capsule(pose, buffers, capsule, TINTS[(look / 7) % TINTS.length], light);
                    default -> {}
                }
            }
            pose.popPose();
        }
        buffers.endBatch();
    }

    private static void box(PoseStack pose, MultiBufferSource buffers, Geometry.Box box, BlockState state, int light) {
        pose.pushPose();
        pose.translate(box.cx(), box.cy(), box.cz());
        pose.mulPose(new Quaternionf(box.qx(), box.qy(), box.qz(), box.qw()));
        pose.scale(2 * box.hx(), 2 * box.hy(), 2 * box.hz());
        pose.translate(-0.5, -0.5, -0.5);
        Minecraft.getInstance()
                .getBlockRenderer()
                .renderSingleBlock(state, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    private static void sphere(PoseStack pose, MultiBufferSource buffers, Geometry.Sphere sphere, int tint, int light) {
        VertexConsumer out = buffers.getBuffer(RenderType.entitySolid(SPHERE));
        PoseStack.Pose last = pose.last();
        Vector3f centre = new Vector3f(sphere.cx(), sphere.cy(), sphere.cz());
        for (int ring = 0; ring < RINGS; ring++) {
            for (int segment = 0; segment < SEGMENTS; segment++) {
                quad(out, last, centre, sphere.radius(), ring, segment, 0, 0, tint, light);
            }
        }
    }

    /** Along local y from {@code -half} to {@code +half}, turned onto the segment a → b. */
    private static void capsule(
            PoseStack pose, MultiBufferSource buffers, Geometry.Capsule capsule, int tint, int light) {
        Vector3f a = new Vector3f(capsule.ax(), capsule.ay(), capsule.az());
        Vector3f b = new Vector3f(capsule.bx(), capsule.by(), capsule.bz());
        Vector3f axis = new Vector3f(b).sub(a);
        float half = axis.length() / 2;
        pose.pushPose();
        pose.translate((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2);
        if (half > 1e-5f) {
            pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), axis.normalize()));
        }
        VertexConsumer out = buffers.getBuffer(RenderType.entitySolid(CAPSULE));
        PoseStack.Pose last = pose.last();
        Vector3f origin = new Vector3f();
        for (int ring = 0; ring < RINGS; ring++) {
            // the upper hemisphere's rings sit half up, the lower's half down; the ring at the equator is the side
            float up = ring < RINGS / 2 ? half : -half;
            for (int segment = 0; segment < SEGMENTS; segment++) {
                quad(out, last, origin, capsule.radius(), ring, segment, up, up, tint, light);
            }
        }
        for (int segment = 0; segment < SEGMENTS; segment++) {
            side(out, last, capsule.radius(), half, segment, tint, light);
        }
        pose.popPose();
    }

    /** One quad of a sphere's latitude-longitude grid, its top edge raised by {@code topShift}, its bottom by
     * {@code bottomShift}. */
    private static void quad(
            VertexConsumer out,
            PoseStack.Pose pose,
            Vector3f centre,
            float radius,
            int ring,
            int segment,
            float topShift,
            float bottomShift,
            int tint,
            int light) {
        float t0 = (float) Math.PI * ring / RINGS, t1 = (float) Math.PI * (ring + 1) / RINGS;
        float p0 = 2 * (float) Math.PI * segment / SEGMENTS, p1 = 2 * (float) Math.PI * (segment + 1) / SEGMENTS;
        float[][] corners = {{t0, p0}, {t0, p1}, {t1, p1}, {t1, p0}};
        for (float[] c : corners) {
            float nx = (float) (Math.sin(c[0]) * Math.cos(c[1]));
            float ny = (float) Math.cos(c[0]);
            float nz = (float) (Math.sin(c[0]) * Math.sin(c[1]));
            float shift = c[0] == t0 ? topShift : bottomShift;
            vertex(
                    out,
                    pose,
                    centre.x + nx * radius,
                    centre.y + ny * radius + shift,
                    centre.z + nz * radius,
                    c[1] / (2 * (float) Math.PI),
                    c[0] / (float) Math.PI,
                    nx,
                    ny,
                    nz,
                    tint,
                    light);
        }
    }

    private static void side(
            VertexConsumer out, PoseStack.Pose pose, float radius, float half, int segment, int tint, int light) {
        float p0 = 2 * (float) Math.PI * segment / SEGMENTS, p1 = 2 * (float) Math.PI * (segment + 1) / SEGMENTS;
        float[][] corners = {{p0, half}, {p1, half}, {p1, -half}, {p0, -half}};
        for (float[] c : corners) {
            float nx = (float) Math.cos(c[0]), nz = (float) Math.sin(c[0]);
            vertex(
                    out,
                    pose,
                    nx * radius,
                    c[1],
                    nz * radius,
                    c[0] / (2 * (float) Math.PI),
                    c[1] > 0 ? 0.25f : 0.75f,
                    nx,
                    0,
                    nz,
                    tint,
                    light);
        }
    }

    private static void vertex(
            VertexConsumer out,
            PoseStack.Pose pose,
            float x,
            float y,
            float z,
            float u,
            float v,
            float nx,
            float ny,
            float nz,
            int tint,
            int light) {
        out.addVertex(pose, x, y, z)
                .setColor(tint)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
