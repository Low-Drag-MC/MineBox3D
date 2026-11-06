package com.lowdragmc.minebox3d.physics;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A turned box swept exactly</b> ({@link PhysicsWorld#sweepBox}, mb3_sweep.c) — what a character mover keeping its
 * box on what it stands on needs: it stops where it touches, touching is no hit unless it moves in, a box already inside
 * is told the least way out, and it is as exact ten million blocks out as at the origin.
 */
class BoxSweepTest {

    private static final QueryFilter ALL = new QueryFilter(1, -1L);
    private static final Quaternionf NO_TURN = new Quaternionf();
    /** Under how close the sweep takes the box: it ends this near the surface, never inside it. */
    private static final double NEAR = 2.0e-5;

    private static PhysicsWorld world() {
        return PhysicsWorld.create(new WorldDef().gravity(0, -10, 0));
    }

    /** A floor whose top is at y = 0.5, its middle at {@code (x, 0, z)}. */
    private static Body floor(PhysicsWorld world, double x, double z) {
        Body floor = world.createBody(new BodyDef().position(x, 0, z));
        floor.createShape(new ShapeDef(), Geometry.Box.of(5, 0.5f, 5));
        return floor;
    }

    private static BoxSweep down(PhysicsWorld world, double x, double y, double z, float by) {
        BoxSweep out = new BoxSweep();
        world.sweepBox(x, y, z, NO_TURN, 0.3f, 0.9f, 0.3f, 0, -by, 0, ALL, false, null, out);
        return out;
    }

    @Test
    void aBodyToIgnoreIsNotMet() {
        try (PhysicsWorld world = world()) {
            Body floor = floor(world, 0, 0);
            BoxSweep hit = new BoxSweep();
            assertFalse(world.sweepBox(0, 1.3, 0, NO_TURN, 0.3f, 0.9f, 0.3f, 0, -2, 0, ALL, false, floor, hit),
                    "the base it is carried with, inside it or not, is passed through");
            Body below = world.createBody(new BodyDef().position(0, -3, 0));
            below.createShape(new ShapeDef(), Geometry.Box.of(5, 0.5f, 5));
            assertTrue(world.sweepBox(0, 1.3, 0, NO_TURN, 0.3f, 0.9f, 0.3f, 0, -4, 0, ALL, false, floor, hit)
                    && hit.shape().body() == below, "and what is past it is met");
        }
    }

    @Test
    void aBoxSweptOntoAnotherStopsWhereItTouches() {
        try (PhysicsWorld world = world()) {
            Body floor = floor(world, 0, 0);
            BoxSweep hit = down(world, 0, 2.0, 0, 2.0f);
            assertTrue(hit.hit(), "it meets the floor");
            assertFalse(hit.startPenetrating());
            // its bottom (middle less 0.9) on the floor's top at 0.5: the middle at 1.4, three tenths of the way
            double at = 2.0 - 2.0 * hit.fraction();
            assertTrue(at >= 1.4 && at - 1.4 < NEAR, "touching, not short of it or in it: the middle at " + at);
            Vector3f normal = hit.normal(new Vector3f());
            assertEquals(1f, normal.y, 1e-5f, "out of the floor, up: " + normal);
            assertEquals(floor, hit.shape().body(), "and says what it met");
        }
    }

    @Test
    void touchingIsNoHitUnlessTheBoxMovesIntoIt() {
        try (PhysicsWorld world = world()) {
            floor(world, 0, 0);
            BoxSweep first = down(world, 0, 2.0, 0, 2.0f);
            double y = 2.0 - 2.0 * first.fraction();   // standing on it, as a mover leaves it
            BoxSweep along = new BoxSweep();
            assertFalse(world.sweepBox(0, y, 0, NO_TURN, 0.3f, 0.9f, 0.3f, 2, 0, 0, ALL, false, null, along),
                    "sliding along the floor meets nothing");
            assertFalse(world.sweepBox(0, y, 0, NO_TURN, 0.3f, 0.9f, 0.3f, 0, 1, 0, ALL, false, null, along),
                    "nor leaving it");
            BoxSweep into = down(world, 0, y, 0, 0.5f);
            assertTrue(into.hit() && !into.startPenetrating(), "pressing into it meets it");
            assertTrue(into.fraction() * 0.5 < NEAR, "at once: " + into.fraction());
        }
    }

    @Test
    void aBoxStartingInsideIsToldTheLeastWayOut() {
        try (PhysicsWorld world = world()) {
            floor(world, 0, 0);
            BoxSweep inside = new BoxSweep();
            assertTrue(world.sweepBox(0, 1.3, 0, NO_TURN, 0.3f, 0.9f, 0.3f, 0, 0, 0, ALL, false, null, inside),
                    "a tenth into the floor is inside it");
            assertTrue(inside.startPenetrating());
            assertEquals(0.1, inside.depth(), 1e-4, "a tenth deep");
            assertEquals(1f, inside.normal(new Vector3f()).y, 1e-4f, "out is up");
            BoxSweep out = new BoxSweep();
            assertFalse(world.sweepBox(0, 1.3, 0, NO_TURN, 0.3f, 0.9f, 0.3f, 0, 1, 0, ALL, true, null, out),
                    "moving out of it, told to let those go: nothing");
            assertTrue(world.sweepBox(0, 1.3, 0, NO_TURN, 0.3f, 0.9f, 0.3f, 0, 1, 0, ALL, false, null, out)
                    && out.startPenetrating(), "not told to: still inside it");
        }
    }

    @Test
    void aBoxComesDownOnASlopeAlongItsNormal() {
        try (PhysicsWorld world = world()) {
            float tilt = (float) Math.toRadians(20);
            Body slope = world.createBody(new BodyDef().rotation(new Quaternionf().rotationAxis(tilt, 0, 0, 1)));
            slope.createShape(new ShapeDef(), Geometry.Box.of(5, 0.5f, 5));
            Quaternionf turned = new Quaternionf().rotationAxis((float) Math.toRadians(35), 0, 1, 0);
            BoxSweep hit = new BoxSweep();
            assertTrue(world.sweepBox(0.5, 3, 0.2, turned, 0.3f, 0.3f, 0.3f, 0, -3, 0, ALL, false, null, hit), "it meets the slope");
            Vector3f normal = hit.normal(new Vector3f());
            assertEquals(-Math.sin(tilt), normal.x, 1e-4, "the slope's own normal, not an axis: " + normal);
            assertEquals(Math.cos(tilt), normal.y, 1e-4, "the slope's own normal, not an axis: " + normal);
            double y = 3 - 3 * hit.fraction();
            BoxSweep again = new BoxSweep();
            assertTrue(world.sweepBox(0.5, y, 0.2, turned, 0.3f, 0.3f, 0.3f, 0, -0.1f, 0, ALL, false, null, again)
                    && !again.startPenetrating() && again.fraction() * 0.1 < NEAR, "left touching it, not in it");
            assertFalse(world.sweepBox(0.5, y, 0.2, turned, 0.3f, 0.3f, 0.3f, normal.x, normal.y, normal.z, ALL, false,
                    null, again), "and off it along its normal, clear");
        }
    }

    @Test
    void everyKindOfShapeIsMetWhereItIs() {
        try (PhysicsWorld world = world()) {
            Body sphere = world.createBody(new BodyDef().position(0, 0, 0));
            sphere.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 1));
            Body capsule = world.createBody(new BodyDef().position(10, 0, 0));
            capsule.createShape(new ShapeDef(), new Geometry.Capsule(0, -1, 0, 0, 1, 0, 0.5f));
            Body hull = world.createBody(new BodyDef().position(20, 0, 0));
            hull.createShape(new ShapeDef(), Geometry.Hull.of(-1, -1, -1, 1, -1, -1, -1, 1, -1, 1, 1, -1,
                    -1, -1, 1, 1, -1, 1, -1, 1, 1, 1, 1, 1));
            Body mesh = world.createBody(new BodyDef().position(30, 0, 0));
            mesh.createShape(new ShapeDef(), Geometry.Mesh.of(MeshData.create(
                    new float[]{-3, 0, -3, 3, 0, -3, 3, 0, 3, -3, 0, 3}, new int[]{0, 2, 1, 0, 3, 2}, true)));
            // a half-block box dropped onto each: its bottom on the sphere's top (1), the capsule's (1.5), the hull's (1),
            // the mesh's (0)
            double[] xs = {0, 10, 20, 30};
            double[] tops = {1.0, 1.5, 1.0, 0.0};
            for (int i = 0; i < 4; i++) {
                BoxSweep hit = new BoxSweep();
                assertTrue(world.sweepBox(xs[i], 4, 0, NO_TURN, 0.25f, 0.25f, 0.25f, 0, -4, 0, ALL, false, null, hit),
                        "shape " + i + " met");
                double bottom = 4 - 4 * hit.fraction() - 0.25;
                assertTrue(bottom >= tops[i] - 1e-6 && bottom - tops[i] < NEAR,
                        "shape " + i + ": touching its top at " + tops[i] + ", not " + bottom);
                assertEquals(1f, hit.normal(new Vector3f()).y, 1e-4f, "shape " + i + ": straight up");
                assertNotNull(hit.shape(), "shape " + i + " named");
            }
        }
    }

    @Test
    void slidingAcrossWhereOneShapeMeetsTheNextMeetsNoSeam() {
        try (PhysicsWorld world = world()) {
            // two boxes side by side, their tops flush at y = 0.5 and their faces meeting at x = 1
            for (double x : new double[]{0, 2}) {
                Body box = world.createBody(new BodyDef().position(x, 0, 0));
                box.createShape(new ShapeDef(), Geometry.Box.of(1, 0.5f, 1));
            }
            // and a mesh of two triangles, their shared edge across the way
            Body mesh = world.createBody(new BodyDef().position(30, 0, 0));
            mesh.createShape(new ShapeDef(), Geometry.Mesh.of(MeshData.create(
                    new float[]{-3, 0, -3, 3, 0, -3, 3, 0, 3, -3, 0, 3}, new int[]{0, 2, 1, 0, 3, 2}, true)));
            double[][] runs = {{0.2, 0.5, 3.0, 0.0}, {28.0, 0.0, 4.0, -2.0}};
            for (double[] run : runs) {
                BoxSweep landed = down(world, run[0], run[1] + 2.0, 0.4, 2.0f);
                assertTrue(landed.hit(), "landed on it at x " + run[0]);
                double y = run[1] + 2.0 - 2.0 * landed.fraction();
                BoxSweep across = new BoxSweep();
                assertFalse(world.sweepBox(run[0], y, 0.4, NO_TURN, 0.3f, 0.9f, 0.3f, (float) run[2], 0, (float) run[3],
                        ALL, false, null, across), "slid across where the next shape starts, from x " + run[0]
                        + ": nothing met, met at " + across.fraction() + " along " + across.normal(new Vector3f()));
            }
        }
    }

    @Test
    void slidingAlongATiltedFaceIsNoHitAndIntoItIs() {
        try (PhysicsWorld world = world()) {
            float tilt = (float) Math.toRadians(20);
            Quaternionf turn = new Quaternionf().rotationAxis(tilt, 0, 0, 1);
            Body slope = world.createBody(new BodyDef().rotation(turn));
            slope.createShape(new ShapeDef(), Geometry.Box.of(5, 0.5f, 5));
            Vector3f normal = turn.transform(new Vector3f(0, 1, 0));
            Vector3f along = turn.transform(new Vector3f(1, 0, 0));
            // a box turned as the slope is, dropped onto it along its normal
            BoxSweep landed = new BoxSweep();
            assertTrue(world.sweepBox(normal.x * 2, normal.y * 2, 0, turn, 0.3f, 0.3f, 0.3f, -normal.x * 2, -normal.y * 2, 0,
                    ALL, false, null, landed), "it lands on the slope");
            double reach = 2 - 2 * landed.fraction();
            double x = normal.x * reach;
            double y = normal.y * reach;
            BoxSweep slide = new BoxSweep();
            assertFalse(world.sweepBox(x, y, 0, turn, 0.3f, 0.3f, 0.3f, along.x * 3, along.y * 3, 0, ALL, false, null, slide),
                    "down the slope along it: nothing met");
            assertFalse(world.sweepBox(x, y, 0, turn, 0.3f, 0.3f, 0.3f, -along.x * 3, -along.y * 3, 0, ALL, false, null,
                    slide), "nor up it");
            assertTrue(world.sweepBox(x, y, 0, turn, 0.3f, 0.3f, 0.3f, along.x * 3 - normal.x * 0.05f,
                    along.y * 3 - normal.y * 0.05f, 0, ALL, false, null, slide) && !slide.startPenetrating(),
                    "into it, however shallowly: met");
            assertTrue(slide.fraction() < 1e-3, "at once: " + slide.fraction());
            assertEquals(normal.y, slide.normal(new Vector3f()).y, 1e-4, "along its normal");
        }
    }

    @Test
    void aBoxStartingInsideARoundShapeOrATriangleIsToldTheWayOut() {
        try (PhysicsWorld world = world()) {
            Body sphere = world.createBody(new BodyDef().position(0, 0, 0));
            sphere.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 1));
            Body capsule = world.createBody(new BodyDef().position(10, 0, 0));
            capsule.createShape(new ShapeDef(), new Geometry.Capsule(0, -1, 0, 0, 1, 0, 0.5f));
            Body mesh = world.createBody(new BodyDef().position(30, 0, 0));
            mesh.createShape(new ShapeDef(), Geometry.Mesh.of(MeshData.create(
                    new float[]{-3, 0, -3, 3, 0, -3, 3, 0, 3, -3, 0, 3}, new int[]{0, 2, 1, 0, 3, 2}, true)));
            // a twentieth into each: on the sphere's top, against the capsule's side, through the mesh's face
            double[][] at = {{0, 1.2, 0}, {10.7, 0, 0}, {30, 0.2, 0}};
            float[][] out = {{0, 1, 0}, {1, 0, 0}, {0, 1, 0}};
            for (int i = 0; i < 3; i++) {
                BoxSweep inside = new BoxSweep();
                assertTrue(world.sweepBox(at[i][0], at[i][1], at[i][2], NO_TURN, 0.25f, 0.25f, 0.25f, 0, 0, 0, ALL, false,
                        null, inside) && inside.startPenetrating(), "shape " + i + ": inside it");
                Vector3f normal = inside.normal(new Vector3f());
                assertEquals(out[i][0], normal.x, 1e-3f, "shape " + i + ": out of it " + normal);
                assertEquals(out[i][1], normal.y, 1e-3f, "shape " + i + ": out of it " + normal);
                assertEquals(0.05, inside.depth(), 1e-3, "shape " + i + ": a twentieth deep");
            }
        }
    }

    @Test
    void aTriangleIsNotMetFromBehind() {
        try (PhysicsWorld world = world()) {
            Body mesh = world.createBody(new BodyDef());
            mesh.createShape(new ShapeDef(), Geometry.Mesh.of(MeshData.create(
                    new float[]{-3, 0, -3, 3, 0, -3, 3, 0, 3, -3, 0, 3}, new int[]{0, 2, 1, 0, 3, 2}, true)));
            BoxSweep hit = new BoxSweep();
            assertFalse(world.sweepBox(0, -1, 0, NO_TURN, 0.25f, 0.25f, 0.25f, 0, 2, 0, ALL, false, null, hit),
                    "up through its back: nothing, as Box3D's own contacts");
            assertTrue(world.sweepBox(0, 1, 0, NO_TURN, 0.25f, 0.25f, 0.25f, 0, -2, 0, ALL, false, null, hit),
                    "down onto its face: met");
        }
    }

    @Test
    void farFromALargeBodysOriginItTouchesAsNearIt() {
        try (PhysicsWorld world = world()) {
            // a mesh floor a kilometre across: the box five hundred metres from its origin, where a float steps by 3e-5
            Body mesh = world.createBody(new BodyDef());
            mesh.createShape(new ShapeDef(), Geometry.Mesh.of(MeshData.create(
                    new float[]{-600, 0, -600, 600, 0, -600, 600, 0, 600, -600, 0, 600}, new int[]{0, 2, 1, 0, 3, 2}, true)));
            BoxSweep landed = down(world, 500.3, 2.0, 400.7, 2.0f);
            double y = 2.0 - 2.0 * landed.fraction();
            assertTrue(landed.hit() && y >= 0.9 - 1e-4 && y - 0.9 < 1e-3, "landed on it: the middle at " + y);
            BoxSweep slide = new BoxSweep();
            assertFalse(world.sweepBox(500.3, y, 400.7, NO_TURN, 0.3f, 0.9f, 0.3f, 3, 0, -2, ALL, false, null, slide),
                    "slides along it");
            assertTrue(down(world, 500.3, y, 400.7, 0.5f).hit(), "and presses into it");
        }
    }

    @Test
    void tenMillionBlocksOutItIsAsExact() {
        try (PhysicsWorld world = world()) {
            double far = 1.0e7;
            floor(world, far, -far);
            BoxSweep hit = down(world, far + 0.37, 2.0, -far + 0.21, 2.0f);
            double at = 2.0 - 2.0 * hit.fraction();
            assertTrue(hit.hit() && at >= 1.4 && at - 1.4 < NEAR, "touching as at the origin: the middle at " + at);
        }
    }
}
