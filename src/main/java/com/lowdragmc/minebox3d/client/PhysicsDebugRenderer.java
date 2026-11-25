package com.lowdragmc.minebox3d.client;

import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityProxies;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.Shape;
import com.lowdragmc.minebox3d.physics.VoxelGrid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>Wireframes of the client level's physics</b>, toggled with {@code /minebox3d debug}.
 *
 * <p>Colours: dynamic awake green, dynamic asleep grey, kinematic (entity proxies) cyan, terrain
 * yellow-brown. Terrain is what contacts see — the outlines of the exposed parts of block faces — drawn only
 * within {@link #TERRAIN_RADIUS} blocks, refreshed every {@link #TERRAIN_REFRESH_FRAMES} frames; its lines are
 * pushed a hair off the surface so the block faces do not swallow them.
 */
public final class PhysicsDebugRenderer {

    private static final int AWAKE = 0xff40e040;
    private static final int ASLEEP = 0xff808080;
    private static final int KINEMATIC = 0xff40d0e0;
    private static final int TERRAIN = 0xffb09040;
    private static final double TERRAIN_RADIUS = 24;
    private static final int TERRAIN_REFRESH_FRAMES = 10;
    private static final int CIRCLE_SEGMENTS = 16;
    private static final float[] SECTION_BOUNDS = {0, 0, 0, 16, 16, 16};

    private static boolean enabled;
    private static long frame;
    private static final Long2ObjectOpenHashMap<SectionLines> terrainLines = new Long2ObjectOpenHashMap<>();
    private static float[] triangleBuffer = new float[9 * 4096];
    private static byte[] flagBuffer = new byte[4096];

    private PhysicsDebugRenderer() {}

    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    static void render(PoseStack poseStack, MultiBufferSource.BufferSource buffers, Vec3 camera, float partial) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled || minecraft.level == null) {
            return;
        }
        LevelPhysics physics = LevelPhysics.existing(minecraft.level);
        if (physics == null) {
            return;
        }
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        Matrix4f pose = poseStack.last().pose();
        Vector3d position = new Vector3d();
        Quaternionf rotation = new Quaternionf();
        for (Body body : physics.world().bodies()) {
            if (body.type() == BodyType.STATIC) {
                continue;
            }
            int colour = body.type() == BodyType.KINEMATIC || EntityProxies.entityOf(body) != null
                    ? KINEMATIC
                    : body.isAwake() ? AWAKE : ASLEEP;
            body.renderPosition(partial, position);
            body.renderRotation(partial, rotation);
            Vector3f origin = new Vector3f(
                    (float) (position.x - camera.x), (float) (position.y - camera.y), (float) (position.z - camera.z));
            for (Shape shape : body.shapes()) {
                drawShape(lines, pose, origin, rotation, shape.geometry(), colour);
            }
        }
        frame++;
        VoxelGrid grid = physics.terrain().grid();
        physics.terrain().forEachLoaded(section -> {
            double ox = SectionPos.sectionToBlockCoord(SectionPos.x(section));
            double oy = SectionPos.sectionToBlockCoord(SectionPos.y(section));
            double oz = SectionPos.sectionToBlockCoord(SectionPos.z(section));
            double cx = ox + 8 - camera.x, cy = oy + 8 - camera.y, cz = oz + 8 - camera.z;
            if (cx * cx + cy * cy + cz * cz > (TERRAIN_RADIUS + 14) * (TERRAIN_RADIUS + 14)) {
                return;
            }
            SectionLines cached = terrainLines.get(section);
            if (cached == null || frame - cached.frame >= TERRAIN_REFRESH_FRAMES) {
                cached = sectionLines(grid, section);
                terrainLines.put(section, cached);
            }
            drawSegments(
                    lines,
                    pose,
                    (float) (ox - camera.x),
                    (float) (oy - camera.y),
                    (float) (oz - camera.z),
                    cached.points);
        });
        // forget sections not drawn for a while
        terrainLines.values().removeIf(cached -> frame - cached.frame > 4 * TERRAIN_REFRESH_FRAMES);
        buffers.endBatch(RenderType.lines());
    }

    private record SectionLines(long frame, float[] points) {}

    /**
     * What contacts see of a section: the edges of its exposed block faces, less the flat ones where the surface
     * carries on in the same plane.
     */
    private static SectionLines sectionLines(VoxelGrid grid, long section) {
        int sx = SectionPos.x(section), sy = SectionPos.y(section), sz = SectionPos.z(section);
        int count = grid.exposedTriangles(sx, sy, sz, SECTION_BOUNDS, triangleBuffer, flagBuffer);
        if (count > flagBuffer.length) {
            triangleBuffer = new float[9 * count];
            flagBuffer = new byte[count];
            count = grid.exposedTriangles(sx, sy, sz, SECTION_BOUNDS, triangleBuffer, flagBuffer);
        }
        FloatArrayList points = new FloatArrayList();
        int[] bits = {VoxelGrid.FLAT_EDGE_1, VoxelGrid.FLAT_EDGE_2, VoxelGrid.FLAT_EDGE_3};
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), normal = new Vector3f();
        for (int t = 0; t < count; t++) {
            float[] v = triangleBuffer;
            a.set(v[9 * t], v[9 * t + 1], v[9 * t + 2]);
            b.set(v[9 * t + 3], v[9 * t + 4], v[9 * t + 5]);
            c.set(v[9 * t + 6], v[9 * t + 7], v[9 * t + 8]);
            new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a), normal).normalize(0.004f);
            Vector3f[] corners = {new Vector3f(a).add(normal), new Vector3f(b).add(normal), new Vector3f(c).add(normal)
            };
            for (int e = 0; e < 3; e++) {
                if ((flagBuffer[t] & bits[e]) == bits[e]) {
                    continue;
                }
                Vector3f p = corners[e], q = corners[(e + 1) % 3];
                points.addElements(points.size(), new float[] {p.x, p.y, p.z, q.x, q.y, q.z});
            }
        }
        return new SectionLines(frame, points.toFloatArray());
    }

    private static void drawSegments(
            VertexConsumer lines, Matrix4f pose, float ox, float oy, float oz, float[] points) {
        Vector3f p = new Vector3f(), q = new Vector3f();
        for (int i = 0; i < points.length; i += 6) {
            p.set(points[i] + ox, points[i + 1] + oy, points[i + 2] + oz);
            q.set(points[i + 3] + ox, points[i + 4] + oy, points[i + 5] + oz);
            segment(lines, pose, p, q, TERRAIN);
        }
    }

    private static void drawShape(
            VertexConsumer lines, Matrix4f pose, Vector3f origin, Quaternionf rotation, Geometry geometry, int colour) {
        switch (geometry) {
            case Geometry.Sphere s -> {
                Vector3f centre =
                        rotation.transform(new Vector3f(s.cx(), s.cy(), s.cz())).add(origin);
                circle(lines, pose, centre, new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), s.radius(), colour);
                circle(lines, pose, centre, new Vector3f(0, 1, 0), new Vector3f(0, 0, 1), s.radius(), colour);
                circle(lines, pose, centre, new Vector3f(1, 0, 0), new Vector3f(0, 0, 1), s.radius(), colour);
            }
            case Geometry.Capsule c -> {
                Vector3f a =
                        rotation.transform(new Vector3f(c.ax(), c.ay(), c.az())).add(origin);
                Vector3f b =
                        rotation.transform(new Vector3f(c.bx(), c.by(), c.bz())).add(origin);
                Vector3f axis = new Vector3f(b).sub(a);
                Vector3f u = axis.lengthSquared() > 1e-8f ? new Vector3f(axis).normalize() : new Vector3f(0, 1, 0);
                Vector3f v = Math.abs(u.y) < 0.9f
                        ? new Vector3f(u).cross(0, 1, 0).normalize()
                        : new Vector3f(u).cross(1, 0, 0).normalize();
                Vector3f w = new Vector3f(u).cross(v).normalize();
                circle(lines, pose, a, v, w, c.radius(), colour);
                circle(lines, pose, b, v, w, c.radius(), colour);
                circle(lines, pose, a, u, v, c.radius(), colour);
                circle(lines, pose, b, u, v, c.radius(), colour);
                for (Vector3f side : new Vector3f[] {v, w, new Vector3f(v).negate(), new Vector3f(w).negate()}) {
                    Vector3f offset = new Vector3f(side).mul(c.radius());
                    segment(lines, pose, new Vector3f(a).add(offset), new Vector3f(b).add(offset), colour);
                }
            }
            case Geometry.Box box -> {
                Quaternionf turn =
                        new Quaternionf(rotation).mul(new Quaternionf(box.qx(), box.qy(), box.qz(), box.qw()));
                Vector3f centre = rotation.transform(new Vector3f(box.cx(), box.cy(), box.cz()))
                        .add(origin);
                Vector3f[] corners = new Vector3f[8];
                for (int i = 0; i < 8; i++) {
                    corners[i] = turn.transform(new Vector3f(
                                    (i & 1) == 0 ? -box.hx() : box.hx(),
                                    (i & 2) == 0 ? -box.hy() : box.hy(),
                                    (i & 4) == 0 ? -box.hz() : box.hz()))
                            .add(centre);
                }
                for (int i = 0; i < 8; i++) {
                    for (int bit = 1; bit < 8; bit <<= 1) {
                        if ((i & bit) == 0) {
                            segment(lines, pose, corners[i], corners[i | bit], colour);
                        }
                    }
                }
            }
            case Geometry.Hull hull -> {
                // the vertices only: Java does not keep the hull's edges
                float[] points = hull.points();
                for (int i = 0; i < points.length; i += 3) {
                    Vector3f p = rotation.transform(new Vector3f(points[i], points[i + 1], points[i + 2]))
                            .add(origin);
                    for (int axis = 0; axis < 3; axis++) {
                        Vector3f d = new Vector3f(axis == 0 ? 0.05f : 0, axis == 1 ? 0.05f : 0, axis == 2 ? 0.05f : 0);
                        segment(lines, pose, new Vector3f(p).sub(d), new Vector3f(p).add(d), colour);
                    }
                }
            }
            case Geometry.Mesh mesh -> {
                // meshes on moving bodies are rare
            }
            case Geometry.HeightField field -> {
                // static ground, drawn by whatever made it
            }
        }
    }

    private static void circle(
            VertexConsumer lines, Matrix4f pose, Vector3f centre, Vector3f u, Vector3f v, float radius, int colour) {
        Vector3f previous = null;
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            Vector3f point = new Vector3f(u)
                    .mul((float) Math.cos(angle) * radius)
                    .add(new Vector3f(v).mul((float) Math.sin(angle) * radius))
                    .add(centre);
            if (previous != null) {
                segment(lines, pose, previous, point, colour);
            }
            previous = point;
        }
    }

    private static void segment(VertexConsumer lines, Matrix4f pose, Vector3f a, Vector3f b, int colour) {
        Vector3f direction = new Vector3f(b).sub(a);
        if (direction.lengthSquared() > 1.0E-9f) {
            direction.normalize();
        } else {
            direction.set(0, 1, 0);
        }
        lines.addVertex(pose, a.x, a.y, a.z).setColor(colour).setNormal(direction.x, direction.y, direction.z);
        lines.addVertex(pose, b.x, b.y, b.z).setColor(colour).setNormal(direction.x, direction.y, direction.z);
    }
}
