package com.lowdragmc.minebox3d.physics;

import static com.lowdragmc.minebox3d.physics.VoxelTerrainTest.FULL;
import static org.junit.jupiter.api.Assertions.*;

import com.lowdragmc.minebox3d.physics.VoxelTerrainTest.Terrain;
import java.util.List;
import org.junit.jupiter.api.Test;

/** <b>What a frame's steps report</b> (design doc 03 §6.3): touches, hits, sensors, gathered per frame. */
class WorldEventsTest {

    private static final long TERRAIN = 1L << 1;

    /** A terrain the way the streamer makes it: asking for no events of its own. */
    private static Terrain quietTerrain() {
        return new Terrain() {
            @Override
            ShapeDef template() {
                ShapeDef template = new ShapeDef();
                template.filter.categoryBits = TERRAIN;
                template.enableContactEvents = false;
                template.enableHitEvents = false;
                return template;
            }
        };
    }

    private static Body ball(Terrain t, ShapeDef def, double x, double y, double z) {
        Body ball = t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(x, y, z));
        ball.createShape(def, new Geometry.Sphere(0, 0, 0, 0.25f));
        return ball;
    }

    @Test
    void aBallLandingOnTerrainBeginsTouchingHitsAndEndsWhenTheBlockGoes() {
        try (Terrain t = quietTerrain()) {
            t.floor(0, 8, 0, 8, 0, (x, z) -> t.full);
            ShapeDef asks = new ShapeDef().enableHitEvents(true);
            asks.enableContactEvents = true;
            Body ball = ball(t, asks, 3.5, 4, 3.5);
            Shape shape = ball.shapes().getFirst();

            List<WorldEvents.ContactBegin> begins = List.of();
            List<WorldEvents.ContactHit> hits = List.of();
            for (int frame = 0; frame < 40 && begins.isEmpty(); frame++) {
                t.world.beginInterpolationFrame();
                t.step(3);
                begins = t.world.events().contactBegins();
                hits = t.world.events().contactHits();
            }
            assertEquals(1, begins.size(), "one touch began");
            WorldEvents.ContactBegin begin = begins.getFirst();
            assertTrue(begin.shapeA() == shape || begin.shapeB() == shape, "the ball's");
            long section = t.grid.sectionShapeId(0, 0, 0);
            assertTrue(begin.shapeIdA() == section || begin.shapeIdB() == section, "with the terrain");

            assertEquals(1, hits.size(), "landing at about 8 m/s is a hit");
            WorldEvents.ContactHit hit = hits.getFirst();
            assertTrue(hit.onTerrain());
            assertTrue(hit.approachSpeed() > 5, "speed " + hit.approachSpeed());
            assertEquals(3, hit.cellX());
            assertEquals(0, hit.cellY());
            assertEquals(3, hit.cellZ());
            assertEquals(1, hit.y(), 0.3, "where it hit");

            // the next frame starts empty
            t.world.beginInterpolationFrame();
            t.step(3);
            assertTrue(t.world.events().contactBegins().isEmpty());
            assertTrue(t.world.events().contactHits().isEmpty());

            // the block under it goes: the touch ends
            t.set(3, 0, 3, 0);
            t.world.beginInterpolationFrame();
            t.step(3);
            assertFalse(t.world.events().contactEnds().isEmpty(), "the touch ended");
        }
    }

    @Test
    void aShapeDestroyedBetweenStepsIsNamedByItsEndEvent() {
        try (Terrain t = quietTerrain()) {
            t.floor(0, 8, 0, 8, 0, (x, z) -> t.full);
            ShapeDef asks = new ShapeDef();
            asks.enableContactEvents = true;
            Body ball = ball(t, asks, 3.5, 2, 3.5);
            Shape shape = ball.shapes().getFirst();
            t.world.beginInterpolationFrame();
            t.step(60);
            assertEquals(1, t.world.events().contactBegins().size());

            ball.destroy();
            t.world.beginInterpolationFrame();
            t.step(1);
            List<WorldEvents.ContactEnd> ends = t.world.events().contactEnds();
            assertEquals(1, ends.size(), "Box3D reports it after the step");
            WorldEvents.ContactEnd end = ends.getFirst();
            assertTrue(end.shapeA() == shape || end.shapeB() == shape, "and it still says whose: " + end);
            assertFalse(shape.isValid());
        }
    }

    @Test
    void aWorldThatNeverBeginsAFrameKeepsOnlyTheLastStepsEvents() {
        try (Terrain t = quietTerrain()) {
            t.floor(0, 8, 0, 8, 0, (x, z) -> t.full);
            ShapeDef asks = new ShapeDef();
            asks.enableContactEvents = true;
            ball(t, asks, 3.5, 2, 3.5);
            int steps = 0;
            while (t.world.events().contactBegins().isEmpty() && steps++ < 120) {
                t.step(1);
            }
            assertEquals(1, t.world.events().contactBegins().size(), "the step it landed in");
            t.step(1);
            assertTrue(t.world.events().contactBegins().isEmpty(), "not kept on for ever");
        }
    }

    /** Box3D takes a hit's point and materials from one contact point: the cell must be that point's too. */
    @Test
    void aHitAcrossTwoBlocksNamesTheBlockItsMaterialIsFrom() {
        for (double x : new double[] {3.9, 4.0, 4.1}) {
            try (Terrain t = quietTerrain()) {
                SurfaceMaterial stone = new SurfaceMaterial();
                stone.userMaterialId = 1;
                SurfaceMaterial dirt = new SurfaceMaterial();
                dirt.userMaterialId = 2;
                int stoneId = t.grid.addCollider(FULL, 1, t.grid.addMaterial(stone));
                int dirtId = t.grid.addCollider(FULL, 1, t.grid.addMaterial(dirt));
                t.floor(0, 8, 0, 8, 0, (cx, cz) -> cx < 4 ? stoneId : dirtId);
                Body box =
                        t.world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(x, 3, 3.5));
                box.createShape(new ShapeDef().enableHitEvents(true), Geometry.Box.of(0.5f, 0.25f, 0.5f));
                List<WorldEvents.ContactHit> hits = List.of();
                for (int i = 0; i < 60 && hits.isEmpty(); i++) {
                    t.world.beginInterpolationFrame();
                    t.step(1);
                    hits = t.world.events().contactHits();
                }
                assertFalse(hits.isEmpty(), "landed at x = " + x);
                WorldEvents.ContactHit hit = hits.getFirst();
                long material = hit.terrainSide() == 1 ? hit.materialA() : hit.materialB();
                assertEquals(
                        hit.cellX() < 4 ? 1 : 2, material, "cell " + hit.cellX() + " and material agree at x = " + x);
            }
        }
    }

    @Test
    void nothingIsReportedWhenNeitherShapeAsks() {
        try (Terrain t = quietTerrain()) {
            t.floor(0, 8, 0, 8, 0, (x, z) -> t.full);
            ShapeDef quiet = new ShapeDef();
            quiet.enableContactEvents = false;
            ball(t, quiet, 3.5, 4, 3.5);
            for (int frame = 0; frame < 40; frame++) {
                t.world.beginInterpolationFrame();
                t.step(3);
                assertTrue(t.world.events().contactBegins().isEmpty(), "terrain asks for nothing, the ball neither");
            }
        }
    }

    @Test
    void aSensorHearsWhatEntersAndLeaves() {
        try (Terrain t = quietTerrain()) {
            t.world.setGravity(0, 0, 0);
            Body sensorBody = t.world.createBody(new BodyDef().position(5, 5, 5));
            Shape sensor = sensorBody.createShape(
                    new ShapeDef().sensor(true).enableSensorEvents(true), Geometry.Box.of(1, 1, 1));
            Body visitor = ball(t, new ShapeDef().enableSensorEvents(true), 2, 5, 5);
            visitor.setLinearVelocity(4, 0, 0);

            boolean entered = false, left = false;
            for (int frame = 0; frame < 60 && !left; frame++) {
                t.world.beginInterpolationFrame();
                t.step(3);
                for (WorldEvents.SensorBegin begin : t.world.events().sensorBegins()) {
                    assertSame(sensor, begin.sensor());
                    assertSame(visitor.shapes().getFirst(), begin.visitor());
                    entered = true;
                }
                for (WorldEvents.SensorEnd end : t.world.events().sensorEnds()) {
                    assertTrue(entered, "left after it entered");
                    assertSame(visitor.shapes().getFirst(), end.visitor());
                    left = true;
                }
            }
            assertTrue(entered && left, "through the sensor and out");
        }
    }

    @Test
    void eventsOfManyBodiesInOneStepAllArrive() {
        try (Terrain t = quietTerrain()) {
            t.floor(0, 32, 0, 32, 0, (x, z) -> t.full);
            ShapeDef asks = new ShapeDef();
            asks.enableContactEvents = true;
            // 100 balls at one height land in the same step: more begins than the buffers start with
            for (int i = 0; i < 100; i++) {
                ball(t, asks, 1.5 + 3 * (i % 10), 3, 1.5 + 3 * (i / 10));
            }
            int begins = 0;
            for (int frame = 0; frame < 40; frame++) {
                t.world.beginInterpolationFrame();
                t.step(3);
                begins += t.world.events().contactBegins().size();
            }
            assertEquals(100, begins);
        }
    }
}
