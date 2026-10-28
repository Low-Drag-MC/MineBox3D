package com.lowdragmc.minebox3d.physics;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Voxel terrain</b> (design doc {@code 01-voxel-terrain.md}, milestone V1): the Box3D fork's voxel
 * shape, without Minecraft. Blocks here are built from the same boxes Minecraft's collision shapes give.
 */
class VoxelTerrainTest {

    static final float GRAVITY = -32;

    static final float[] FULL = {0, 0, 0, 1, 1, 1};
    static final float[] BOTTOM_SLAB = {0, 0, 0, 1, 0.5f, 1};
    static final float[] TOP_SLAB = {0, 0.5f, 0, 1, 1, 1};
    /** A stair rising towards +z: a bottom slab and the back half above it, two boxes like Minecraft's. */
    static final float[] STAIRS = {0, 0, 0, 1, 0.5f, 1, 0, 0.5f, 0.5f, 1, 1, 1};
    /** A glass pane across x: 2/16 thick. */
    static final float[] PANE = {7 / 16f, 0, 0, 9 / 16f, 1, 1};

    /** A world with a grid and the usual colliders; ids in the order added, 1 = full block. Shared with the other voxel tests. */
    static final class Terrain implements AutoCloseable {
        final PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, GRAVITY, 0));
        final VoxelGrid grid = world.createVoxelGrid(new ShapeDef());
        final int material = grid.addMaterial(new SurfaceMaterial().friction(0));
        final int full = collider(FULL);
        final int bottomSlab = collider(BOTTOM_SLAB);
        final int topSlab = collider(TOP_SLAB);
        final int stairs = collider(STAIRS);
        final int pane = collider(PANE);
        final int stairsNorth = collider(STAIRS_NORTH);

        int collider(float[] boxes) {
            return grid.addCollider(boxes, boxes.length / 6, material);
        }

        void set(int x, int y, int z, int collider) {
            grid.setCells(new int[]{x, y, z, collider}, 1);
        }

        /** A floor one cell thick at {@code y}, cell (x, z) taking {@code collider.applyAsInt(x, z)}. */
        void floor(int x0, int x1, int z0, int z1, int y, IntBinaryOperator collider) {
            int count = (x1 - x0) * (z1 - z0);
            int[] edits = new int[4 * count];
            int k = 0;
            for (int x = x0; x < x1; x++) {
                for (int z = z0; z < z1; z++) {
                    edits[k++] = x;
                    edits[k++] = y;
                    edits[k++] = z;
                    edits[k++] = collider.applyAsInt(x, z);
                }
            }
            grid.setCells(edits, count);
        }

        Body body(Geometry geometry, double x, double y, double z) {
            Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(x, y, z));
            body.createShape(new ShapeDef().friction(0), geometry);
            return body;
        }

        void step(int steps) {
            for (int i = 0; i < steps; i++) {
                world.step(1 / 60f, 4);
            }
        }

        @Override
        public void close() {
            world.close();
        }
    }

    static double y(Body body) {
        return body.position(new Vector3d()).y;
    }

    // ---- what contacts see ---------------------------------------------------------------------

    /** One exposed triangle: its normal, area, and per edge (1 to 2, 2 to 3, 3 to 1) midpoint and flatness. */
    record Face(Vector3f normal, float area, Vector3f[] midpoints, boolean[] flat) {
        Vector3f centre() {
            return new Vector3f(midpoints[0]).add(midpoints[1]).add(midpoints[2]).div(3);
        }
    }

    static java.util.List<Face> faces(Terrain t, int sx, int sy, int sz) {
        float[] bounds = {0, 0, 0, 16, 16, 16};
        float[] v = new float[9 * 4096];
        byte[] flags = new byte[4096];
        int count = t.grid.exposedTriangles(sx, sy, sz, bounds, v, flags);
        assertTrue(count <= 4096, "test buffer too small for " + count);
        java.util.List<Face> faces = new java.util.ArrayList<>();
        int[] bits = {VoxelGrid.FLAT_EDGE_1, VoxelGrid.FLAT_EDGE_2, VoxelGrid.FLAT_EDGE_3};
        for (int i = 0; i < count; i++) {
            Vector3f[] p = new Vector3f[3];
            for (int k = 0; k < 3; k++) {
                p[k] = new Vector3f(v[9 * i + 3 * k], v[9 * i + 3 * k + 1], v[9 * i + 3 * k + 2]).add(16 * sx, 16 * sy, 16 * sz);
            }
            Vector3f cross = new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0]));
            Vector3f[] midpoints = new Vector3f[3];
            boolean[] flat = new boolean[3];
            for (int e = 0; e < 3; e++) {
                midpoints[e] = new Vector3f(p[e]).add(p[(e + 1) % 3]).mul(0.5f);
                flat[e] = (flags[i] & bits[e]) == bits[e];
            }
            faces.add(new Face(new Vector3f(cross).normalize(), cross.length() / 2, midpoints, flat));
        }
        return faces;
    }

    static float area(java.util.List<Face> faces, float nx, float ny, float nz) {
        float area = 0;
        for (Face face : faces) {
            if (face.normal.distance(nx, ny, nz) < 1e-4f) {
                area += face.area;
            }
        }
        return area;
    }

    @Test
    void aSolidCubeShowsOnlyItsSurfaceWithFlatEdgesInside() {
        try (Terrain t = new Terrain()) {
            for (int y = 1; y < 4; y++) {
                t.floor(1, 4, 1, 4, y, (x, z) -> t.full);
            }
            java.util.List<Face> faces = faces(t, 0, 0, 0);
            assertEquals(6 * 9 * 2, faces.size(), "two triangles per outer block face, nothing between blocks");
            for (float[] n : new float[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                assertEquals(9, area(faces, n[0], n[1], n[2]), 1e-4, "a 3 x 3 side facing " + java.util.Arrays.toString(n));
            }
            // an edge on the cube's rim is a real edge; every other one has the same plane carrying on past it
            for (Face face : faces) {
                for (int e = 0; e < 3; e++) {
                    Vector3f m = face.midpoints[e];
                    int onRim = 0;
                    for (float c : new float[]{m.x, m.y, m.z}) {
                        if (Math.abs(c - 1) < 1e-4f || Math.abs(c - 4) < 1e-4f) {
                            onRim++;
                        }
                    }
                    assertEquals(onRim < 2, face.flat[e], "edge at " + m);
                }
            }
        }
    }

    @Test
    void aBlockBesideASlabShowsOnlyTheUpperHalfOfThatSide() {
        try (Terrain t = new Terrain()) {
            t.set(5, 5, 5, t.full);
            t.set(6, 5, 5, t.bottomSlab);
            java.util.List<Face> faces = faces(t, 0, 0, 0);
            // the stone's east side: only y 5.5 to 6 is open
            float east = 0;
            for (Face face : faces) {
                if (face.normal.x > 0.999f && Math.abs(face.midpoints[0].x - 6) < 1e-4f) {
                    east += face.area;
                    for (Vector3f m : face.midpoints) {
                        assertTrue(m.y >= 5.5f - 1e-4f, "below the slab's top is covered, at " + m);
                    }
                }
            }
            assertEquals(0.5, east, 1e-4);
            // the slab's west side is wholly against the stone
            for (Face face : faces) {
                assertFalse(face.normal.x < -0.999f && Math.abs(face.midpoints[0].x - 6) < 1e-4f, "slab side against stone");
            }
            // the slab's top meets the stone's side rising above it: that edge is not flat
            for (Face face : faces) {
                if (face.normal.y > 0.999f && Math.abs(face.midpoints[0].y - 5.5f) < 1e-4f) {
                    for (int e = 0; e < 3; e++) {
                        if (Math.abs(face.midpoints[e].x - 6) < 1e-4f) {
                            assertFalse(face.flat[e], "the slab top's edge at the stone");
                        }
                    }
                }
            }
        }
    }

    @Test
    void aFloorAcrossASectionBorderIsFlatAcrossIt() {
        try (Terrain t = new Terrain()) {
            t.floor(14, 18, 4, 6, 0, (x, z) -> (x & 1) == 0 ? t.full : t.topSlab);
            for (int sx = 0; sx <= 1; sx++) {
                for (Face face : faces(t, sx, 0, 0)) {
                    if (face.normal.y < 0.999f) {
                        continue;
                    }
                    for (int e = 0; e < 3; e++) {
                        Vector3f m = face.midpoints[e];
                        boolean rim = Math.abs(m.x - 14) < 1e-4f || Math.abs(m.x - 18) < 1e-4f
                                || Math.abs(m.z - 4) < 1e-4f || Math.abs(m.z - 6) < 1e-4f;
                        assertEquals(!rim, face.flat[e], "top edge at " + m + " in section " + sx);
                    }
                }
            }
            // facing east: the stone at 14 shows its lower half under the slab at 15; the slab at 15 is against the
            // stone at 16 across the section border, which shows its lower half; the slab at 17 faces open air
            assertEquals(1.0, area(faces(t, 0, 0, 0), 1, 0, 0), 1e-4, "section 0");
            assertEquals(2.0, area(faces(t, 1, 0, 0), 1, 0, 0), 1e-4, "section 1");
        }
    }

    // ---- resting -------------------------------------------------------------------------------

    @Test
    void bodiesRestOnTopOfTheFloorAcrossCellAndSectionCorners() {
        Geometry[] shapes = {Geometry.Box.of(0.25f, 0.25f, 0.25f), new Geometry.Sphere(0, 0, 0, 0.25f),
                new Geometry.Capsule(-0.3f, 0, 0, 0.3f, 0, 0, 0.25f)};
        // a cell's middle, a cell corner, a section corner (x = 16, z = 16), a negative section corner
        double[][] spots = {{3.5, 3.5}, {5, 7}, {16, 16}, {0, 0}};
        for (Geometry shape : shapes) {
            for (double[] spot : spots) {
                try (Terrain t = new Terrain()) {
                    t.floor(-8, 24, -8, 24, 0, (x, z) -> t.full);
                    Body body = t.body(shape, spot[0], 3, spot[1]);
                    t.step(180);
                    assertEquals(1.25, y(body), 0.02, shape + " at " + spot[0] + ", " + spot[1]);
                    assertFalse(body.isAwake(), "settled");
                }
            }
        }
    }

    @Test
    void aBoxRestsOnASlabAtHalfABlock() {
        try (Terrain t = new Terrain()) {
            t.floor(0, 4, 0, 4, 0, (x, z) -> t.bottomSlab);
            Body body = t.body(Geometry.Box.of(0.2f, 0.2f, 0.2f), 2, 2, 2);
            t.step(180);
            assertEquals(0.7, y(body), 0.02);
        }
    }

    // ---- ghost collisions ----------------------------------------------------------------------

    private record Kick(float vertical, float angular) {
    }

    /** Slides a body along +x from x = 8 to past x = 24 over the floor, across cell borders and the section border at 16. */
    private static Kick slide(Terrain t, Geometry geometry, double restY, double z, float speed) {
        Body slider = t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(8, restY, z).enableSleep(false));
        slider.createShape(new ShapeDef().friction(0), geometry);
        t.step(30);
        slider.setLinearVelocity(speed, 0, 0);
        float maxVy = 0, maxW = 0;
        Vector3f v = new Vector3f(), w = new Vector3f();
        Vector3d p = new Vector3d();
        for (int i = 0; i < 900; i++) {
            t.step(1);
            slider.position(p);
            if (p.x > 9 && p.x < 23) {
                maxVy = Math.max(maxVy, Math.abs(slider.linearVelocity(v).y));
                maxW = Math.max(maxW, slider.angularVelocity(w).length());
            }
            if (p.x > 24) {
                break;
            }
        }
        assertTrue(p.x > 24, "the slider got across, ended at x = " + p.x);
        // a seam that catches a slider slows it without kicking it up
        assertTrue(slider.linearVelocity(v).x >= 0.95f * speed, "not slowed on the way: " + v.x + " of " + speed);
        slider.destroy();
        return new Kick(maxVy, maxW);
    }

    private enum FloorKind {
        FULL, BOTTOM_SLABS, STONE_AND_TOP_SLABS, STAIR_TREADS, STONE_THEN_TOP_SLABS, STONE_AND_STAIR_TOPS
    }

    /** A stair rising towards −z: its upper half is the front half of the cell, z in [0, 0.5]. */
    static final float[] STAIRS_NORTH = {0, 0, 0, 1, 0.5f, 1, 0, 0.5f, 0, 1, 1, 0.5f};

    @Test
    void slidingAcrossCellsAndSectionsDoesNotKick() {
        // small enough to fit on a stair's tread, z in [8, 8.5]
        Geometry[] bodies = {Geometry.Box.of(0.2f, 0.2f, 0.2f), new Geometry.Capsule(-0.25f, 0, 0, 0.25f, 0, 0, 0.15f),
                new Geometry.Capsule(0, 0, -0.1f, 0, 0, 0.1f, 0.1f), new Geometry.Sphere(0, 0, 0, 0.2f)};
        String[] names = {"box", "capsule along", "capsule across", "sphere"};
        float[] halfHeights = {0.2f, 0.15f, 0.1f, 0.2f};
        float[] speeds = {2, 6, 12};

        StringBuilder report = new StringBuilder("\n=== voxel slide: max |vy| m/s, max |w| rad/s ===\n");
        float worstVy = 0, worstW = 0;
        String worst = "";
        for (FloorKind kind : FloorKind.values()) {
            report.append(kind).append('\n');
            for (int b = 0; b < bodies.length; b++) {
                for (float speed : speeds) {
                    try (Terrain t = new Terrain()) {
                        IntBinaryOperator floor = switch (kind) {
                            case FULL -> (x, z) -> t.full;
                            case BOTTOM_SLABS -> (x, z) -> t.bottomSlab;
                            case STONE_AND_TOP_SLABS -> (x, z) -> (x & 1) == 0 ? t.full : t.topSlab;
                            case STAIR_TREADS -> (x, z) -> t.stairs;
                            case STONE_THEN_TOP_SLABS -> (x, z) -> x < 16 ? t.full : t.topSlab;
                            // the lane runs on the stairs' upper half, z in [8, 8.5], with a drop either side
                            case STONE_AND_STAIR_TOPS -> (x, z) -> (x / 2) % 2 == 0 ? t.full : t.stairsNorth;
                        };
                        t.floor(0, 32, 0, 16, 0, floor);
                        double top = kind == FloorKind.BOTTOM_SLABS || kind == FloorKind.STAIR_TREADS ? 0.5 : 1;
                        Kick kick = slide(t, bodies[b], top + halfHeights[b] + 0.01, 8.25, speed);
                        report.append(String.format(Locale.ROOT, "  %-15s %4.0f m/s  vy %.3f  w %.3f%n", names[b], speed,
                                kick.vertical(), kick.angular()));
                        if (kick.vertical() > worstVy) {
                            worstVy = kick.vertical();
                            worst = kind + " " + names[b] + " " + speed;
                        }
                        worstW = Math.max(worstW, kick.angular());
                    }
                }
            }
        }
        System.out.println(report);
        assertTrue(worstVy < 0.05f, "kicked up at " + worstVy + " m/s (" + worst + ")" + report);
        assertTrue(worstW < 0.1f, "spun up to " + worstW + " rad/s" + report);
    }

    /**
     * A capsule lying across a stair's upper half, its ends over the drops on either side, slid from stone onto the
     * stairs. Its axis passes exactly over the seam between the stone's top and the stair's: the stair's triangle under it
     * reports an edge contact, and the corner of the next triangle, beside the drop, once pushed it sideways and turned it
     * until it fell (found by the GameTest on real stairs).
     *
     * <p>Only that crossing is measured. Back onto stone at x = 4 its ends pass 1 cm over the top corners of the stone's
     * sides, the real step up from the stair's tread, and Box3D's speculative contact lifts it a little there, as it would
     * off a mesh (design doc 01 §12.4).
     */
    @Test
    void aCapsuleAcrossAStairsUpperHalfIsNotTurnedAsideAtTheSeam() {
        try (Terrain t = new Terrain()) {
            t.floor(0, 32, 0, 16, 0, (x, z) -> (x / 2) % 2 == 0 ? t.full : t.stairsNorth);
            Body slider = t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(1.5, 1.16, 8.25).enableSleep(false));
            slider.createShape(new ShapeDef().friction(0).density(500), new Geometry.Capsule(0, 0, -0.2f, 0, 0, 0.2f, 0.15f));
            t.step(30);
            slider.setLinearVelocity(2, 0, 0);
            Vector3f v = new Vector3f(), w = new Vector3f();
            Vector3d p = new Vector3d();
            float maxSideways = 0, maxVy = 0, maxW = 0;
            for (int i = 0; i < 120 && p.x < 3.5; i++) {
                t.step(1);
                slider.position(p);
                maxSideways = Math.max(maxSideways, Math.abs(slider.linearVelocity(v).z));
                maxVy = Math.max(maxVy, Math.abs(v.y));
                maxW = Math.max(maxW, slider.angularVelocity(w).length());
            }
            assertTrue(p.x >= 3.5 && p.y > 1, "crossed onto the stairs' top, at " + p);
            assertTrue(maxVy < 0.05f, "kicked up at " + maxVy);
            assertTrue(maxSideways < 0.01f, "pushed sideways at " + maxSideways);
            assertTrue(maxW < 0.1f, "turned at " + maxW);
        }
    }

    // ---- changing cells ------------------------------------------------------------------------

    @Test
    void takingAwayTheBlockUnderASleepingBodyDropsIt() {
        try (Terrain t = new Terrain()) {
            t.floor(0, 4, 0, 4, 0, (x, z) -> t.full);
            t.set(1, 2, 1, t.full);
            Body body = t.body(Geometry.Box.of(0.25f, 0.25f, 0.25f), 1.5, 5, 1.5);
            t.step(180);
            assertEquals(3.25, y(body), 0.02, "on the upper block");
            assertFalse(body.isAwake());

            t.set(1, 2, 1, 0);
            assertTrue(body.isAwake(), "woken by the change");
            t.step(120);
            assertEquals(1.25, y(body), 0.02, "fell onto the floor");
        }
    }

    @Test
    void aBlockPlacedIntoABodyPushesItOut() {
        try (Terrain t = new Terrain()) {
            t.floor(0, 8, 0, 8, 0, (x, z) -> t.full);
            Body body = t.body(Geometry.Box.of(0.25f, 0.25f, 0.25f), 3.5, 3, 3.5);
            t.step(180);
            assertFalse(body.isAwake());

            // a block right where the box sits; its sides are open
            t.set(3, 1, 3, t.full);
            t.step(120);
            Vector3d p = body.position(new Vector3d());
            boolean inside = p.x > 3 - 0.24 && p.x < 4 + 0.24 && p.z > 3 - 0.24 && p.z < 4 + 0.24 && p.y < 2 + 0.24;
            assertFalse(inside, "pushed out of the block, at " + p);
            assertTrue(p.y > 1, "not through the floor, at " + p);
        }
    }

    @Test
    void aBuriedBodyHeadsForTheNearestOpenCell() {
        try (Terrain t = new Terrain()) {
            // a solid 5 x 5 x 5 lump with the body in its middle cell, open air above the lump
            for (int y = 0; y < 5; y++) {
                t.floor(0, 5, 0, 5, y, (x, z) -> t.full);
            }
            Body body = t.body(new Geometry.Sphere(0, 0, 0, 0.2f), 2.5, 3.5, 2.5);
            t.step(240);
            Vector3d p = body.position(new Vector3d());
            assertTrue(p.y > 5, "out on top of the lump, at " + p);
        }
    }

    @Test
    void aBlockInANewSectionWakesAndPushesTheBodyThere() {
        try (Terrain t = new Terrain()) {
            // the floor's top is the top of section 0, the body rests in section 1, which does not exist yet
            t.floor(0, 4, 0, 4, 15, (x, z) -> t.full);
            Body body = t.body(Geometry.Box.of(0.25f, 0.25f, 0.25f), 1.5, 18, 1.5);
            t.step(180);
            assertEquals(16.25, y(body), 0.02);
            assertFalse(body.isAwake());
            assertFalse(t.grid.hasSection(0, 1, 0));

            t.set(1, 16, 1, t.full);
            assertTrue(t.grid.hasSection(0, 1, 0), "the edit made the section");
            assertTrue(body.isAwake(), "woken");
            t.step(120);
            Vector3d p = body.position(new Vector3d());
            boolean inside = p.x > 1 - 0.24 && p.x < 2 + 0.24 && p.z > 1 - 0.24 && p.z < 2 + 0.24 && p.y < 17 + 0.24;
            assertFalse(inside, "pushed out, at " + p);
        }
    }

    @Test
    void emptyingASectionRemovesIt() {
        try (Terrain t = new Terrain()) {
            t.set(5, 5, 5, t.full);
            assertEquals(1, t.grid.sectionCount());
            t.set(5, 5, 5, 0);
            assertEquals(0, t.grid.sectionCount());
            assertEquals(0, t.grid.cell(5, 5, 5));
        }
    }

    @Test
    void wholeSectionsLoadAndUnload() {
        try (Terrain t = new Terrain()) {
            short[] cells = new short[VoxelGrid.SECTION_CELLS];
            // the bottom layer full: y = 0 is the first 256 cells
            for (int i = 0; i < 256; i++) {
                cells[i] = (short) t.full;
            }
            t.grid.setSection(-1, 0, -1, cells);
            assertEquals(t.full, t.grid.cell(-16, 0, -16));
            assertEquals(t.full, t.grid.cell(-1, 0, -1));
            assertEquals(0, t.grid.cell(-1, 1, -1));

            Body body = t.body(Geometry.Box.of(0.25f, 0.25f, 0.25f), -8.5, 3, -8.5);
            t.step(180);
            assertEquals(1.25, y(body), 0.02);

            t.grid.setSection(-1, 0, -1, null);
            assertEquals(0, t.grid.sectionCount());
            t.step(60);
            assertTrue(y(body) < 0, "falls once the section is gone");
        }
    }

    // ---- continuous collision ------------------------------------------------------------------

    @Test
    void aFastBodyDoesNotGoThroughAThinPane() {
        try (Terrain t = new Terrain()) {
            t.world.setGravity(0, 0, 0);
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    t.set(10, y, z, t.pane);
                }
            }
            Body body = t.body(new Geometry.Sphere(0, 0, 0, 0.1f), 5, 1.5, 1.5);
            body.setLinearVelocity(120, 0, 0);
            t.step(60);
            double x = body.position(new Vector3d()).x;
            assertTrue(x < 10 + 7 / 16.0, "stopped by the pane, at x = " + x);
        }
    }

    // ---- queries -------------------------------------------------------------------------------

    @Test
    void aRayHitsTheFirstBlockAndNamesItsCell() {
        try (Terrain t = new Terrain()) {
            t.floor(14, 18, 14, 18, 0, (x, z) -> t.full);
            t.set(16, 1, 16, t.bottomSlab);
            CastHit hit = t.world.castRayClosest(16.5, 10, 16.5, 0, -20, 0, -1L, -1L);
            assertNotNull(hit);
            assertNull(hit.shape(), "terrain has no Shape object");
            assertEquals(1.5, hit.y(), 1e-4, "the slab's top");
            assertEquals(1, hit.normalY(), 1e-4);
            int[] cell = new int[3];
            assertTrue(t.grid.featureCell(hit.shapeId(), hit.triangleIndex(), cell));
            assertEquals(16, cell[0]);
            assertEquals(1, cell[1]);
            assertEquals(16, cell[2]);

            CastHit side = t.world.castRayClosest(10, 0.5, 15.5, 10, 0, 0, -1L, -1L);
            assertNotNull(side);
            assertEquals(14, side.x(), 1e-4, "the floor's west face");
            assertEquals(-1, side.normalX(), 1e-4);

            assertNull(t.world.castRayClosest(10, 5, 10, 0, 1, 0, -1L, -1L), "nothing up there");
        }
    }

    @Test
    void aRayStartingOnOrInABlockGoesOnToTheNextOne() {
        try (Terrain t = new Terrain()) {
            // two layers with a gap: y = 0 and y = 3, both in section 0
            t.floor(0, 4, 0, 4, 0, (x, z) -> t.full);
            t.floor(0, 4, 0, 4, 3, (x, z) -> t.full);

            // standing exactly on the upper layer's top, looking down: through it, onto the lower layer
            CastHit fromTop = t.world.castRayClosest(1.5, 4, 1.5, 0, -10, 0, -1L, -1L);
            assertNotNull(fromTop, "the rest of the section is still seen");
            assertEquals(1, fromTop.y(), 1e-4);

            // from inside the upper layer
            CastHit fromInside = t.world.castRayClosest(1.5, 3.5, 1.5, 0, -10, 0, -1L, -1L);
            assertNotNull(fromInside);
            assertEquals(1, fromInside.y(), 1e-4);
        }
        try (Terrain t = new Terrain()) {
            // skimming along faces where the walk visits the very cells whose faces it runs along: the top of a floor that is
            // a section border (the walk clamps into the floor's own cells), its underside and its west side
            t.floor(0, 4, 0, 4, 15, (x, z) -> t.full);
            assertNull(t.world.castRayClosest(-1, 16, 1.5, 10, 0, 0, -1L, -1L), "along the top at a section border");
            assertNull(t.world.castRayClosest(-1, 15, 1.5, 10, 0, 0, -1L, -1L), "along the underside");
            assertNull(t.world.castRayClosest(0, 15.5, -1, 0, 0, 10, -1L, -1L), "along the west side");
            CastHit inside = t.world.castRayClosest(-1, 15.5, 1.5, 10, 0, 0, -1L, -1L);
            assertNotNull(inside, "a hair inside the same block is a hit");
            assertEquals(0, inside.x(), 1e-4);
        }
    }

    @Test
    void aHitOnASectionThatIsGoneNamesNoCell() {
        try (Terrain t = new Terrain()) {
            t.set(1, 0, 1, t.full);
            CastHit hit = t.world.castRayClosest(1.5, 5, 1.5, 0, -10, 0, -1L, -1L);
            assertNotNull(hit);
            t.set(1, 0, 1, 0);
            assertEquals(0, t.grid.sectionCount());
            assertFalse(t.grid.featureCell(hit.shapeId(), hit.triangleIndex(), new int[3]));

            VoxelGrid grid = t.grid;
            t.world.close();
            assertThrows(IllegalStateException.class, () -> grid.featureCell(hit.shapeId(), hit.triangleIndex(), new int[3]));
        }
    }

    private static float[] boxPoints(float h) {
        return new float[]{-h, -h, -h, h, -h, -h, -h, h, -h, h, h, -h, -h, -h, h, h, -h, h, -h, h, h, h, h, h};
    }

    @Test
    void aShapeCastSlidesOverTheSeamsAndStopsAtAWall() {
        try (Terrain t = new Terrain()) {
            t.floor(0, 32, 0, 16, 0, (x, z) -> t.full);
            t.set(20, 1, 8, t.full);
            // a box just above the floor (2 cm), swept along x across 15 seams and a section border
            CastHit hit = t.world.castShapeClosest(4, 1.22, 8.5, boxPoints(0.2f), 8, 0, 20, 0, 0, -1L, -1L);
            assertNotNull(hit);
            assertEquals(20, hit.x(), 1e-2, "the wall's west face, not a seam on the way");
            assertEquals(-1, hit.normalX(), 1e-3);
            assertEquals((20 - 0.2 - 4) / 20, hit.fraction(), 1e-3);
        }
    }

    @Test
    void anOverlapFindsTheTerrainUnderASphere() {
        try (Terrain t = new Terrain()) {
            t.floor(0, 8, 0, 8, 0, (x, z) -> t.full);
            long[] ids = new long[4];
            float[] center = {0, 0, 0};
            assertEquals(1, t.world.overlapShape(5.5, 1.2, 5.5, center, 1, 0.3f, -1L, -1L, ids));
            assertEquals(t.grid.sectionShapeId(0, 0, 0), ids[0]);
            assertEquals(0, t.world.overlapShape(5.5, 1.5, 5.5, center, 1, 0.3f, -1L, -1L, ids));
        }
    }

    @Test
    void aCharacterMoverGetsTheFloorAndTheWallButNoSeams() {
        try (Terrain t = new Terrain()) {
            t.floor(0, 8, 0, 8, 0, (x, z) -> t.full);
            float[] planes = new float[7 * 16];
            long[] shapes = new long[16];
            int[] triangles = new int[16];

            // standing on a corner where four blocks meet
            int count = t.world.collideMover(5, 0, 5, 0, 1.28f, 0, 0, 1.8f, 0, 0.29f, -1L, -1L, planes, shapes, triangles);
            assertTrue(count > 0, "the floor");
            for (int i = 0; i < count; i++) {
                assertEquals(1, planes[7 * i + 1], 1e-3, "only the floor's top, plane " + i);
            }

            // next to a block standing on the floor
            t.set(6, 1, 5, t.full);
            count = t.world.collideMover(5.75, 0, 5.5, 0, 1.28f, 0, 0, 1.8f, 0, 0.29f, -1L, -1L, planes, shapes, triangles);
            boolean wall = false;
            for (int i = 0; i < count; i++) {
                if (planes[7 * i] < -0.999f) {
                    wall = true;
                    int[] cell = new int[3];
                    assertTrue(t.grid.featureCell(shapes[i], triangles[i], cell));
                    assertEquals(6, cell[0]);
                    assertEquals(1, cell[1]);
                } else {
                    assertEquals(1, planes[7 * i + 1], 1e-3, "otherwise the floor's top");
                }
            }
            assertTrue(wall, "the block's west face");
        }
    }

    // ---- materials -----------------------------------------------------------------------------

    @Test
    void eachBlockHasItsOwnFriction() {
        try (Terrain t = new Terrain()) {
            int ice = t.grid.addMaterial(new SurfaceMaterial().friction(0));
            int rough = t.grid.addMaterial(new SurfaceMaterial().friction(1));
            int iceBlock = t.grid.addCollider(FULL, 1, ice);
            int roughBlock = t.grid.addCollider(FULL, 1, rough);
            t.floor(0, 32, 0, 4, 0, (x, z) -> z < 2 ? iceBlock : roughBlock);

            Body onIce = t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(2, 1.26, 1));
            onIce.createShape(new ShapeDef().friction(1), Geometry.Box.of(0.25f, 0.25f, 0.25f));
            Body onRough = t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(2, 1.26, 3));
            onRough.createShape(new ShapeDef().friction(1), Geometry.Box.of(0.25f, 0.25f, 0.25f));
            t.step(20);
            onIce.setLinearVelocity(5, 0, 0);
            onRough.setLinearVelocity(5, 0, 0);
            t.step(60);
            Vector3f v = new Vector3f();
            assertTrue(onIce.linearVelocity(v).x > 4, "slides on ice, vx = " + v.x);
            assertTrue(onRough.linearVelocity(v).x < 0.5f, "stops on the rough block, vx = " + v.x);
        }
    }
}
