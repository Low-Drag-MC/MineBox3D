package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.physics.VoxelTerrainTest.Face;
import com.lowdragmc.minebox3d.physics.VoxelTerrainTest.Terrain;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.lowdragmc.minebox3d.physics.VoxelTerrainTest.FULL;
import static com.lowdragmc.minebox3d.physics.VoxelTerrainTest.area;
import static com.lowdragmc.minebox3d.physics.VoxelTerrainTest.faces;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Voxel terrain in the corners</b>: every neighbour direction, far coordinates, rays in every direction, query
 * filters, contact points, continuous collision with pre-solve events, section borders at every alignment, push-out
 * directions, exposure details, the candidate cap, per-cell materials.
 */
class VoxelDetailTest {

    private static final long TERRAIN = 1L << 1, ENTITY = 1L << 2;

    // ---- sections and their 26 neighbours -------------------------------------------------------

    @Test
    void aCubeOverEightSectionsShowsOnlyItsSurfaceAndFollowsANeighbourComingAndGoing() {
        try (Terrain t = new Terrain()) {
            for (int y = -1; y <= 1; y++) {
                t.floor(-1, 2, -1, 2, y, (x, z) -> t.full);
            }
            List<Face> all = new ArrayList<>();
            for (int sx = -1; sx <= 0; sx++) {
                for (int sy = -1; sy <= 0; sy++) {
                    for (int sz = -1; sz <= 0; sz++) {
                        all.addAll(faces(t, sx, sy, sz));
                    }
                }
            }
            assertEquals(6 * 9 * 2, all.size(), "nothing between the 27 blocks, across all 8 sections");
            for (Face face : all) {
                for (int e = 0; e < 3; e++) {
                    Vector3f m = face.midpoints()[e];
                    int onRim = 0;
                    for (float c : new float[]{m.x, m.y, m.z}) {
                        if (Math.abs(c + 1) < 1e-4f || Math.abs(c - 2) < 1e-4f) {
                            onRim++;
                        }
                    }
                    assertEquals(onRim < 2, face.flat()[e], "edge at " + m);
                }
            }

            // section (-1, 0, 0) holds the cube's cells at x = -1, y and z in {0, 1}: without it, the four faces behind
            // it show
            assertEquals(0, westAt0(t), 1e-4);
            t.grid.setSection(-1, 0, 0, null);
            assertEquals(4, westAt0(t), 1e-4, "the neighbour gone, its side shows");
            short[] cells = new short[VoxelGrid.SECTION_CELLS];
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    cells[15 + 16 * z + 256 * y] = (short) t.full;
                }
            }
            t.grid.setSection(-1, 0, 0, cells);
            assertEquals(0, westAt0(t), 1e-4, "back, and covering it again");
        }
    }

    private static float westAt0(Terrain t) {
        float area = 0;
        for (Face face : faces(t, 0, 0, 0)) {
            if (face.normal().x < -0.999f && Math.abs(face.centre().x) < 1e-4f) {
                area += face.area();
            }
        }
        return area;
    }

    // ---- far from the origin --------------------------------------------------------------------

    @Test
    void bodiesRestAndSlideOnTerrainFarFromTheOrigin() {
        // ten million blocks out, and next to Minecraft's border (30 million), where a float steps by 2
        for (int x0 : new int[]{10_000_000, 29_999_984}) {
            Geometry[] shapes = {Geometry.Box.of(0.25f, 0.25f, 0.25f), new Geometry.Sphere(0, 0, 0, 0.25f),
                    new Geometry.Capsule(-0.3f, 0, 0, 0.3f, 0, 0, 0.25f)};
            for (Geometry shape : shapes) {
                try (Terrain t = new Terrain()) {
                    t.floor(x0 - 16, x0 + 32, 0, 16, 0, (x, z) -> t.full);
                    Body body = t.body(shape, x0 + 8.5, 3, 8.5);
                    t.step(180);
                    assertEquals(1.25, VoxelTerrainTest.y(body), 0.02, shape + " at x = " + x0);
                    assertFalse(body.isAwake(), "settled at x = " + x0);

                    // across cells and the section border at x0 + 16
                    body.setLinearVelocity(4, 0, 0);
                    float maxVy = 0;
                    Vector3d p = new Vector3d();
                    Vector3f v = new Vector3f();
                    for (int i = 0; i < 300 && p.x < x0 + 24; i++) {
                        t.step(1);
                        body.position(p);
                        maxVy = Math.max(maxVy, Math.abs(body.linearVelocity(v).y));
                    }
                    assertTrue(p.x >= x0 + 24, shape + " got across at x = " + x0 + ", ended at " + p);
                    assertTrue(maxVy < 0.05f, shape + " kicked up at " + maxVy + " at x = " + x0);
                }
            }
        }
    }

    // ---- rays -----------------------------------------------------------------------------------

    @Test
    void raysFromEveryDirectionHitTheRightFaceAndCell() {
        try (Terrain t = new Terrain()) {
            t.floor(-8, 24, -8, 24, 0, (x, z) -> t.full);
            for (int y = 1; y <= 3; y++) {
                t.set(3, y, 2, t.full);
                t.set(2, y, 6, t.full);
            }
            // from another section along +x
            CastHit east = t.world.castRayClosest(-20, 2.5, 2.5, 40, 0, 0, QueryFilter.ALL);
            assertNotNull(east);
            assertEquals(3, east.x(), 1e-4);
            assertEquals(-1, east.normalX(), 1e-4);
            assertArrayEquals(new int[]{3, 2, 2}, cell(east));

            CastHit west = t.world.castRayClosest(20, 1.5, 2.5, -40, 0, 0, QueryFilter.ALL);
            assertNotNull(west);
            assertEquals(4, west.x(), 1e-4);
            assertEquals(1, west.normalX(), 1e-4);

            CastHit south = t.world.castRayClosest(2.5, 3.5, -20, 0, 0, 40, QueryFilter.ALL);
            assertNotNull(south);
            assertEquals(6, south.z(), 1e-4);
            assertEquals(-1, south.normalZ(), 1e-4);
            assertArrayEquals(new int[]{2, 3, 6}, cell(south));

            CastHit north = t.world.castRayClosest(2.5, 1.5, 30, 0, 0, -40, QueryFilter.ALL);
            assertNotNull(north);
            assertEquals(7, north.z(), 1e-4);
            assertEquals(1, north.normalZ(), 1e-4);

            // diagonal, starting in the section at -x, -z, down onto the floor
            CastHit diagonal = t.world.castRayClosest(-3.3, 6, -2.7, 10, -10, 8, QueryFilter.ALL);
            assertNotNull(diagonal);
            assertEquals(0.5, diagonal.fraction(), 1e-4);
            assertEquals(1.7, diagonal.x(), 1e-4);
            assertEquals(1.3, diagonal.z(), 1e-4);
            assertEquals(1, diagonal.normalY(), 1e-4);
            assertArrayEquals(new int[]{1, 0, 1}, cell(diagonal));

            // every shape along the way, closest first (one hit per shape: a section is one shape); a box above the
            // column at (3, 1..3, 2), the room for one hit to start with
            Body box = t.world.createBody(new BodyDef().position(3.5, 8, 2.5));
            Shape boxShape = box.createShape(new ShapeDef(), Geometry.Box.of(0.25f, 0.25f, 0.25f));
            CastHits all = new CastHits(1);
            assertEquals(2, t.world.castRay(3.5, 10, 2.5, 0, -20, 0, QueryFilter.ALL, all));
            assertSame(boxShape, all.shape(0), "the box first");
            assertEquals(8.25, all.y(0), 1e-4);
            assertFalse(all.isTerrain(0));
            assertTrue(all.isTerrain(1), "then the terrain");
            assertEquals(4, all.y(1), 1e-4, "the column's top");
            assertEquals(3, all.cellY(1));
            assertTrue(all.fraction(0) < all.fraction(1));
        }
    }

    private static int[] cell(CastHit hit) {
        assertTrue(hit.terrain(), "a hit on terrain");
        return new int[]{hit.cellX(), hit.cellY(), hit.cellZ()};
    }

    // ---- query filters --------------------------------------------------------------------------

    @Test
    void queriesHonourCategoriesAndMasks() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, 0, 0))) {
            ShapeDef terrainTemplate = new ShapeDef();
            terrainTemplate.filter.categoryBits = TERRAIN;
            VoxelGrid grid = world.createVoxelGrid(terrainTemplate);
            int full = grid.addCollider(FULL, 1, grid.addMaterial(new SurfaceMaterial()));
            int[] edits = new int[4 * 64];
            for (int i = 0; i < 64; i++) {
                edits[4 * i] = i % 8;
                edits[4 * i + 2] = i / 8;
                edits[4 * i + 3] = full;
            }
            grid.setCells(edits, 64);
            long section = grid.sectionShapeId(0, 0, 0);
            // a box like an entity proxy hovering over the ground
            Body proxy = world.createBody(new BodyDef().type(BodyType.KINEMATIC).position(4.5, 2, 4.5));
            ShapeDef proxyDef = new ShapeDef();
            proxyDef.filter.categoryBits = ENTITY;
            proxyDef.filter.maskBits = ~(TERRAIN | ENTITY);
            Shape box = proxy.createShape(proxyDef, Geometry.Box.of(0.5f, 0.5f, 0.5f));

            CastHit hit = world.castRayClosest(4.5, 10, 4.5, 0, -20, 0, QueryFilter.only(TERRAIN));
            assertEquals(section, hit.shapeId(), "terrain only: through the box");
            hit = world.castRayClosest(4.5, 10, 4.5, 0, -20, 0, QueryFilter.only(ENTITY));
            assertEquals(box.id(), hit.shapeId(), "entities only: the box");
            hit = world.castRayClosest(4.5, 10, 4.5, 0, -20, 0, new QueryFilter(TERRAIN, -1L));
            assertEquals(section, hit.shapeId(), "a query that is terrain is not something the box collides with");

            Geometry ball = new Geometry.Sphere(0, 0, 0, 0.2f);
            hit = world.castShapeClosest(ball, 4.5, 10, 4.5, new Quaternionf(), 0, -20, 0, QueryFilter.only(TERRAIN));
            assertEquals(section, hit.shapeId());
            hit = world.castShapeClosest(ball, 4.5, 10, 4.5, new Quaternionf(), 0, -20, 0, QueryFilter.only(ENTITY));
            assertEquals(box.id(), hit.shapeId());

            // a sphere touching both the ground and the box's underside
            Overlaps overlaps = new Overlaps();
            Geometry touching = new Geometry.Sphere(0, 0, 0, 0.3f);
            assertEquals(1, world.overlapShape(touching, 4.5, 1.25, 4.5, QueryFilter.only(TERRAIN), overlaps));
            assertEquals(section, overlaps.shapeId(0));
            assertEquals(1, world.overlapShape(touching, 4.5, 1.25, 4.5, QueryFilter.only(ENTITY), overlaps));
            assertSame(box, overlaps.shape(0));
            assertEquals(2, world.overlapShape(touching, 4.5, 1.25, 4.5, QueryFilter.ALL, new Overlaps(1)), "grown to fit both");

            // a capsule from the ground up into the box
            MoverPlanes planes = new MoverPlanes(1);
            int count = world.collideMover(4.5, 0, 4.5, 0, 1.2f, 0, 0, 1.4f, 0, 0.25f, QueryFilter.ALL, planes);
            boolean sawSection = false, sawBox = false;
            for (int i = 0; i < count; i++) {
                sawSection |= planes.shapeId(i) == section;
                sawBox |= planes.shapeId(i) == box.id();
            }
            assertTrue(sawSection && sawBox, "both, past the room for one plane it started with");
            count = world.collideMover(4.5, 0, 4.5, 0, 1.2f, 0, 0, 1.4f, 0, 0.25f, QueryFilter.only(TERRAIN), planes);
            assertTrue(count > 0);
            for (int i = 0; i < count; i++) {
                assertEquals(section, planes.shapeId(i), "terrain planes only");
            }
        }
    }

    // ---- contact points -------------------------------------------------------------------------

    @Test
    void contactPointsSayWhereABodyTouchesAndWhat() {
        try (Terrain t = new Terrain()) {
            // with friction: a frictionless stack drifts apart by itself in Box3D, on any ground
            int stone = t.grid.addCollider(FULL, 1, t.grid.addMaterial(new SurfaceMaterial().friction(0.6f)));
            t.floor(0, 8, 0, 8, 0, (x, z) -> stone);
            Body bottom = t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(3.5, 2, 3.5));
            bottom.createShape(new ShapeDef().friction(0.6f), Geometry.Box.of(0.4f, 0.4f, 0.4f));
            Body top = t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(3.5, 3, 3.5));
            top.createShape(new ShapeDef().friction(0.6f), Geometry.Box.of(0.2f, 0.2f, 0.2f));
            t.step(240);
            assertEquals(2.0, VoxelTerrainTest.y(top), 0.02, "the stack stands");
            ContactPoints points = new ContactPoints(1);
            Vector3f normal = new Vector3f();

            int n = top.contactPoints(points);
            assertTrue(n >= 1);
            for (int i = 0; i < n; i++) {
                assertEquals(1, points.normal(i, normal).y, 1e-3, "the top box is held up: normal +y");
                assertSame(bottom.shapes().getFirst(), points.otherShape(i));
                assertTrue(points.normalImpulse(i) > 0, "and pressed");
                assertFalse(points.isTerrain(i));
            }

            n = bottom.contactPoints(points);
            int fromTop = 0, fromGround = 0;
            Vector3i cell = new Vector3i();
            for (int i = 0; i < n; i++) {
                if (points.otherShapeId(i) == top.shapes().getFirst().id()) {
                    fromTop++;
                    assertEquals(-1, points.normal(i, normal).y, 1e-3, "the top box presses it down: normal -y");
                } else {
                    fromGround++;
                    assertEquals(1, points.normal(i, normal).y, 1e-3);
                    assertTrue(Math.abs(points.separation(i)) < 0.01f, "touching: " + points.separation(i));
                    assertTrue(points.normalImpulse(i) > 0);
                    assertTrue(points.isTerrain(i));
                    assertEquals(new Vector3i(3, 0, 3), points.cell(i, cell), "the floor cell under the box");
                }
            }
            assertTrue(fromTop >= 1 && fromGround >= 1, "more points than the room for one it started with");
        }
    }

    // ---- continuous collision with pre-solve events (fork fix 54ff131) --------------------------

    @Test
    void aFastBodyWithPreSolveEventsStopsWithNoCallbackSet() {
        try (Terrain t = new Terrain()) {
            t.world.setGravity(0, 0, 0);
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    t.set(10, y, z, t.pane);
                }
            }
            Body wall = t.world.createBody(new BodyDef().position(1.5, 1.5, 20));
            wall.createShape(new ShapeDef(), Geometry.Box.of(1, 1, 0.05f));

            ShapeDef preSolve = new ShapeDef();
            preSolve.enablePreSolveEvents = true;
            Body toPane = t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(5, 1.5, 1.5));
            toPane.createShape(preSolve, new Geometry.Sphere(0, 0, 0, 0.1f));
            toPane.setLinearVelocity(120, 0, 0);
            Body toWall = t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(1.5, 1.5, 10));
            toWall.createShape(preSolve, new Geometry.Sphere(0, 0, 0, 0.1f));
            toWall.setLinearVelocity(0, 0, 120);
            t.step(60);
            assertTrue(toPane.position(new Vector3d()).x < 10 + 7 / 16.0);
            assertTrue(toWall.position(new Vector3d()).z < 20);
        }
    }

    // ---- a seam between sections, wherever it falls -----------------------------------------------

    /**
     * The GameTest server puts tests anywhere, so the section borders fall anywhere in a scene. A box's corner just past
     * the border between two sections finds the edge there with a normal a degree off the face; the other section's
     * face contact that would have hidden it is in another contact. Kept tilted, it turned a box 40 degrees on stair
     * tops and threw it off (5 m/s up) when the border fell between two stairs. Every alignment, at the GameTest's y.
     */
    @Test
    void aBoxSlidingOverStairTopsIsNotTurnedWhereverTheSectionBorderFalls() {
        for (int scene = 0; scene < 2; scene++) {
            for (int ox = 0; ox < 16; ox++) {
                try (Terrain t = new Terrain()) {
                    int oy = -60;
                    for (int x = 0; x < 26; x++) {
                        for (int z = 0; z < 5; z++) {
                            int other = scene == 0 ? t.full : t.bottomSlab;
                            t.set(ox + x, oy + 1, z, (x / 2) % 2 == 0 ? other : t.stairsNorth);
                        }
                    }
                    // on the stairs' upper halves between stone, or on their treads between slabs
                    double top = scene == 0 ? 1 : 0.5, lane = scene == 0 ? 2.25 : 2.75;
                    Body box = t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(ox + 1.5, oy + 1 + top + 0.21, lane)
                            .enableSleep(false));
                    box.createShape(new ShapeDef().friction(0).density(500), Geometry.Box.of(0.2f, 0.2f, 0.2f));
                    t.step(30);
                    box.setLinearVelocity(2, 0, 0);
                    float maxVy = 0, maxW = 0;
                    Vector3d p = new Vector3d();
                    Vector3f v = new Vector3f();
                    for (int i = 0; i < 700 && box.position(p).x < ox + 23.5; i++) {
                        t.step(1);
                        maxVy = Math.max(maxVy, Math.abs(box.linearVelocity(v).y));
                        maxW = Math.max(maxW, box.angularVelocity(v).length());
                    }
                    String where = (scene == 0 ? "stone|stairs" : "slab|stairs") + ", border at x = " + (16 - ox) % 16;
                    assertTrue(p.x >= ox + 23.5, "got across, " + where + ": " + (p.x - ox));
                    assertTrue(maxVy < 0.05f, "kicked up at " + maxVy + ", " + where);
                    assertTrue(maxW < 0.1f, "turned at " + maxW + ", " + where);
                }
            }
        }
    }

    // ---- pushed out of blocks -------------------------------------------------------------------

    private static boolean inside(Vector3d p, double x0, double y0, double z0, double x1, double y1, double z1) {
        return p.x > x0 && p.x < x1 && p.y > y0 && p.y < y1 && p.z > z0 && p.z < z1;
    }

    @Test
    void aBodyInsideALumpLeavesByTheNearestOpenSide() {
        try (Terrain t = new Terrain()) {
            t.world.setGravity(0, 0, 0);
            for (int y = 0; y < 8; y++) {
                t.floor(0, 5, 0, 5, y, (x, z) -> t.full);
            }
            // in cell x = 1: the open side at x = 0 is 1.5 away, every other one 2.5 or more
            Body sphere = t.body(new Geometry.Sphere(0, 0, 0, 0.2f), 1.5, 4.5, 2.5);
            Vector3d p = new Vector3d();
            for (int i = 0; i < 600 && inside(sphere.position(p), 0, 0, 0, 5, 8, 5); i++) {
                t.step(1);
            }
            assertTrue(p.x < 0.01, "out through the west side: " + p);
            assertEquals(4.5, p.y, 0.1);
            assertEquals(2.5, p.z, 0.1);

            Body capsule = t.body(new Geometry.Capsule(0, -0.3f, 0, 0, 0.3f, 0, 0.15f), 2.5, 4.5, 2.5);
            for (int i = 0; i < 900 && inside(capsule.position(p), -0.15, -0.15, -0.15, 5.15, 8.15, 5.15); i++) {
                t.step(1);
            }
            assertFalse(inside(p, -0.15, -0.15, -0.15, 5.15, 8.15, 5.15), "a buried capsule gets out too: " + p);
        }
    }

    @Test
    void aBodyInsideASlabAmongSlabsIsPushedOntoIt() {
        try (Terrain t = new Terrain()) {
            t.floor(0, 5, 0, 5, -1, (x, z) -> t.full);
            t.floor(0, 5, 0, 5, 0, (x, z) -> t.bottomSlab);
            // nearer the slab's underside, but that is on stone
            Body sphere = t.body(new Geometry.Sphere(0, 0, 0, 0.1f), 2.5, 0.2, 2.5);
            t.step(120);
            assertEquals(0.6, VoxelTerrainTest.y(sphere), 0.02, "on top of the slab, the only open side");
        }
    }

    @Test
    void aBodyBuriedDeeperThanTheSearchStillComesOut() {
        try (Terrain t = new Terrain()) {
            t.world.setGravity(0, 0, 0);
            for (int y = 0; y < 11; y++) {
                t.floor(0, 11, 0, 11, y, (x, z) -> t.full);
            }
            // pushed at 3 m/s from x.5, it stops exactly on a cell boundary after 10 steps: it must go on from there
            Body sphere = t.body(new Geometry.Sphere(0, 0, 0, 0.2f), 5.5, 5.5, 5.5);
            Vector3d p = new Vector3d();
            for (int i = 0; i < 1200 && inside(sphere.position(p), 0, 0, 0, 11, 11, 11); i++) {
                t.step(1);
            }
            assertTrue(p.y > 11, "out of the top: " + p);
        }
    }

    // ---- exposure details -----------------------------------------------------------------------

    @Test
    void aStairShowsEachOfItsFacesOnce() {
        try (Terrain t = new Terrain()) {
            t.set(4, 4, 4, t.stairs);
            List<Face> faces = faces(t, 0, 0, 0);
            assertEquals(1.0, area(faces, 0, 1, 0), 1e-4, "tread and top");
            assertEquals(1.0, area(faces, 0, -1, 0), 1e-4);
            assertEquals(0.75, area(faces, 1, 0, 0), 1e-4, "an L-shaped side");
            assertEquals(0.75, area(faces, -1, 0, 0), 1e-4);
            assertEquals(1.0, area(faces, 0, 0, 1), 1e-4, "the back");
            assertEquals(1.0, area(faces, 0, 0, -1), 1e-4, "front and riser");
            float total = 0;
            for (Face face : faces) {
                total += face.area();
            }
            assertEquals(5.5, total, 1e-4, "nothing else: no face between the two boxes, none facing a slant");
        }
    }

    @Test
    void aStoneTopIsFlatBesideAStairTopAndAnEdgeBesideItsTread() {
        try (Terrain t = new Terrain()) {
            t.set(4, 4, 4, t.full);
            t.set(5, 4, 4, t.stairsNorth);
            int checked = 0;
            for (Face face : faces(t, 0, 0, 0)) {
                if (face.normal().y < 0.999f || Math.abs(face.centre().y - 5) > 1e-4f || face.centre().x > 5) {
                    continue;
                }
                for (int e = 0; e < 3; e++) {
                    Vector3f m = face.midpoints()[e];
                    if (Math.abs(m.x - 5) > 1e-4f) {
                        continue;
                    }
                    // the stone's top meets the stair's upper half (z 4 to 4.5) flat, and its tread (z 4.5 to 5) as an
                    // edge
                    assertEquals(m.z < 4.5f, face.flat()[e], "the stone top's east edge at " + m);
                    checked++;
                }
            }
            assertTrue(checked >= 2, "both halves of that edge were seen");
        }
    }

    @Test
    void trianglesAreCollectedOnlyWhereAskedAndOnlyAsManyAsFit() {
        try (Terrain t = new Terrain()) {
            t.floor(0, 4, 0, 4, 0, (x, z) -> t.full);
            float[] v = new float[9 * 64];
            byte[] flags = new byte[64];
            int count = t.grid.exposedTriangles(0, 0, 0, new float[]{0.2f, 0.5f, 0.2f, 0.8f, 1.5f, 0.8f}, v, flags);
            assertEquals(2, count, "the top of cell (0, 0, 0) and nothing else");
            for (int i = 0; i < count; i++) {
                float cx = (v[9 * i] + v[9 * i + 3] + v[9 * i + 6]) / 3, cz = (v[9 * i + 2] + v[9 * i + 5] + v[9 * i + 8]) / 3;
                assertTrue(cx < 1 && cz < 1);
            }

            // room for four triangles' vertices but three flags: three are written
            float[] room = new float[9 * 4];
            Arrays.fill(room, 27, 36, 42);
            int all = t.grid.exposedTriangles(0, 0, 0, new float[]{0, 0, 0, 16, 16, 16}, room, new byte[3]);
            assertTrue(all > 3, "the whole count: " + all);
            for (int i = 27; i < 36; i++) {
                assertEquals(42, room[i], "only three triangles' worth written");
            }
        }
    }

    // ---- the candidate cap ----------------------------------------------------------------------

    /**
     * Box3D gives one convex shape at most {@code B3_MAX_MESH_CONTACT_TRIANGLES} triangles of one section; cells are
     * taken y, z, x, so past the cap the far rows in z are dropped. A stair is six triangles under a body. At Box3D's
     * 512, 10 × 10 blocks (726) lost its far half and 12 × 12 sank in (doc 01 §12.6.3); MineBox3D builds with 2048, a
     * whole section of stairs.
     */
    @Test
    void aBoxOnStairsIsHeldUpUnderAllFourCornersUpToAWholeSection() {
        for (float half : new float[]{3, 5, 6, 7}) {
            try (Terrain t = new Terrain()) {
                t.floor(0, 16, 0, 16, 0, (x, z) -> t.stairs);
                Body slab = t.body(Geometry.Box.of(half, 0.5f, half), 8, 3, 8);
                t.step(240);
                assertEquals(1.5, VoxelTerrainTest.y(slab), 0.02, "on the stairs' upper halves, " + 2 * half + " wide");
                assertTrue(slab.angularVelocity(new Vector3f()).length() < 0.05f);

                ContactPoints points = new ContactPoints();
                int n = slab.contactPoints(points);
                boolean[] quadrants = new boolean[4];
                Vector3f anchor = new Vector3f();
                for (int i = 0; i < n; i++) {
                    float ax = points.anchor(i, anchor).x, az = anchor.z;
                    if (Math.abs(ax) > half / 2 && Math.abs(az) > half / 2) {
                        quadrants[(ax > 0 ? 1 : 0) + (az > 0 ? 2 : 0)] = true;
                    }
                }
                assertArrayEquals(new boolean[]{true, true, true, true}, quadrants,
                        "touching near every corner, far rows included, " + 2 * half + " wide");
            }
        }
    }

    // ---- per-cell materials ---------------------------------------------------------------------

    @Test
    void aBlockWithATangentVelocityCarriesWhatRestsOnIt() {
        try (Terrain t = new Terrain()) {
            SurfaceMaterial conveyor = new SurfaceMaterial().friction(1);
            conveyor.tangentVelocityX = 2;
            int belt = t.grid.addCollider(FULL, 1, t.grid.addMaterial(conveyor));
            int stone = t.grid.addCollider(FULL, 1, t.grid.addMaterial(new SurfaceMaterial().friction(1)));
            t.floor(0, 16, 0, 4, 0, (x, z) -> z < 2 ? belt : stone);
            Body onBelt = t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(4, 1.26, 1));
            onBelt.createShape(new ShapeDef().friction(1), Geometry.Box.of(0.25f, 0.25f, 0.25f));
            Body onStone = t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(4, 1.26, 3));
            onStone.createShape(new ShapeDef().friction(1), Geometry.Box.of(0.25f, 0.25f, 0.25f));
            t.step(60);
            assertTrue(onBelt.position(new Vector3d()).x > 5, "carried along +x: " + onBelt.position(new Vector3d()));
            assertEquals(4, onStone.position(new Vector3d()).x, 0.01, "stone carries nothing");
        }
    }
}
