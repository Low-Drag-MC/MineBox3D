package com.lowdragmc.minebox3d.physics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * <b>Sliding over a mesh's inner edges</b>: a floor of unit squares as one mesh with Box3D's
 * {@code identifyEdges}, which links each triangle to its neighbours so a sliding body does not catch on the edges
 * inside a flat surface.
 *
 * <p>Terrain no longer uses meshes (design doc {@code 01-voxel-terrain.md}); this keeps the general mesh shape
 * honest. Box3D's ghost collision rewrite of 2026-11 (upstream {@code 38f2ae1}) leaves capsules bumpier here than
 * before: 0.057 m/s at worst, was 0.014 ({@code 02-box3d-fork.md} §4.2), hence the bound.
 */
class MeshSeamTest {

    private static MeshData floor(int width, int depth) {
        float[] vertices = new float[(width + 1) * (depth + 1) * 3];
        int k = 0;
        for (int z = 0; z <= depth; z++) {
            for (int x = 0; x <= width; x++) {
                vertices[k++] = x;
                vertices[k++] = 0;
                vertices[k++] = z;
            }
        }
        int[] indices = new int[width * depth * 6];
        k = 0;
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int a = z * (width + 1) + x, b = a + 1, c = a + width + 2, d = a + width + 1;
                // counter-clockwise seen from above (+Y)
                indices[k++] = a;
                indices[k++] = d;
                indices[k++] = c;
                indices[k++] = a;
                indices[k++] = c;
                indices[k++] = b;
            }
        }
        return MeshData.create(vertices, indices, true);
    }

    private record Disturbance(float maxVerticalSpeed, float maxAngularSpeed) {}

    private static Disturbance slide(Geometry body, float speed) {
        try (MeshData mesh = floor(32, 16);
                PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, -32, 0))) {
            world.createBody(new BodyDef()).createShape(new ShapeDef().friction(0), Geometry.Mesh.of(mesh));
            float rest =
                    switch (body) {
                        case Geometry.Box b -> b.hy();
                        case Geometry.Sphere s -> s.radius();
                        case Geometry.Capsule c -> c.radius();
                        default -> 0.5f;
                    };
            Body slider = world.createBody(
                    new BodyDef().type(BodyType.DYNAMIC).position(8, rest, 8.3).enableSleep(false));
            // frictionless, both sides (Box3D mixes the two as a geometric mean)
            slider.createShape(new ShapeDef().friction(0), body);
            for (int i = 0; i < 30; i++) {
                world.step(1 / 60f, 4);
            }
            slider.setLinearVelocity(speed, 0, 0);
            float maxVy = 0, maxW = 0;
            Vector3f v = new Vector3f(), w = new Vector3f();
            Vector3d p = new Vector3d();
            for (int i = 0; i < 600; i++) {
                world.step(1 / 60f, 4);
                slider.position(p);
                if (p.x > 10 && p.x < 22) {
                    maxVy = Math.max(maxVy, Math.abs(slider.linearVelocity(v).y));
                    maxW = Math.max(maxW, slider.angularVelocity(w).length());
                }
                if (p.x > 24) {
                    break;
                }
            }
            assertTrue(p.x > 24, "the slider got across, ended at x = " + p.x);
            return new Disturbance(maxVy, maxW);
        }
    }

    @Test
    void slidingOverAFlatMeshDoesNotKick() {
        Geometry[] bodies = {
            Geometry.Box.of(0.25f, 0.25f, 0.25f),
            new Geometry.Capsule(-0.3f, 0, 0, 0.3f, 0, 0, 0.2f),
            new Geometry.Capsule(0, 0, -0.3f, 0, 0, 0.3f, 0.2f),
            new Geometry.Sphere(0, 0, 0, 0.25f)
        };
        String[] names = {"box 0.5", "capsule along", "capsule across", "sphere"};
        float[] speeds = {2, 6, 12, 24};
        StringBuilder report = new StringBuilder("mesh slide: max |vy| m/s / max |w| rad/s\n");
        float worst = 0;
        for (int b = 0; b < bodies.length; b++) {
            for (float speed : speeds) {
                Disturbance d = slide(bodies[b], speed);
                report.append(String.format(
                        Locale.ROOT,
                        "  %-14s %4.0f m/s:  %.4f/%.4f%n",
                        names[b],
                        speed,
                        d.maxVerticalSpeed,
                        d.maxAngularSpeed));
                worst = Math.max(worst, d.maxVerticalSpeed);
            }
        }
        System.out.print(report);
        assertTrue(worst < 0.08f, "a flat mesh is smooth to slide over: " + worst + "\n" + report);
    }
}
