package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>A shape's second round of API</b> (design doc {@code 03-threading-and-api.md} §6.4 item 3): hulls, height fields,
 * changing the geometry in place, what can be read and changed on a live shape, and one shape's sweep and the broad
 * phase's candidates that entity collision uses (doc 04 §11).
 */
class ShapeApiTest {

    private static PhysicsWorld world() {
        return PhysicsWorld.create(new WorldDef().gravity(0, -10, 0));
    }

    private static Body ground(PhysicsWorld world) {
        Body ground = world.createBody(new BodyDef());
        ground.createShape(new ShapeDef(), Geometry.Box.of(20, 0.5f, 20));
        return ground;
    }

    private static void steps(PhysicsWorld world, int count) {
        for (int i = 0; i < count; i++) {
            world.beginInterpolationFrame();
            world.step(1 / 60f, 4);
        }
    }

    private static final float[] CUBE = {
            -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f,
            -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f,
            0, 0, 0, 0.1f, 0.2f, -0.1f, 0.4f, 0.4f, 0.4f};

    // ---- hulls --------------------------------------------------------------------------------

    @Test
    void aHullKeepsBox3DsHullOfItsPointsAndRestsOnAFace() {
        Geometry.Hull hull = Geometry.Hull.of(CUBE);
        assertEquals(8, hull.vertexCount(), "the corners; the points inside are not vertices");
        for (int i = 0; i < hull.points().length; i++) {
            assertEquals(0.5f, Math.abs(hull.points()[i]), 1e-5);
        }
        assertThrows(IllegalArgumentException.class, () -> Geometry.Hull.of(0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 1),
                "flat: no hull");
        assertThrows(IllegalArgumentException.class, () -> Geometry.Hull.of(0, 0, 0, 1));

        try (PhysicsWorld world = world()) {
            ground(world);
            Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, 3, 0));
            Shape shape = body.createShape(new ShapeDef().density(1000), hull);
            assertSame(hull, shape.geometry());
            assertEquals(1000, body.mass(), 1, "a unit cube's mass");
            steps(world, 120);
            assertEquals(1, body.position(new Vector3d()).y, 0.02, "on the ground, half a block up");

            // queries take hulls too
            CastResult hit = new CastResult();
            assertTrue(world.castShapeClosest(hull, 0, 5, 0, new Quaternionf(), 0, -5, 0, QueryFilter.ALL, hit));
            assertSame(body, hit.body());
            assertEquals((5 - 0.5 - 1.5) / 5, hit.fraction(), 1e-3, "met the body's top at 1.5");
        }
    }

    // ---- height fields ------------------------------------------------------------------------

    @Test
    void aBallRestsOnAHeightFieldAndFallsThroughItsHole() {
        try (PhysicsWorld world = world()) {
            int n = 5;
            float[] heights = new float[n * n];
            java.util.Arrays.fill(heights, 1);
            byte[] materials = new byte[(n - 1) * (n - 1)];
            materials[2 * (n - 1) + 2] = HeightFieldData.HOLE; // the cell from (2, 2) to (3, 3)
            try (HeightFieldData data = HeightFieldData.create(heights, n, n, materials, 1, 2, 1, 0, 4)) {
                Body field = world.createBody(new BodyDef().position(-2, 0, -2));
                ShapeDef def = new ShapeDef();
                def.materials = new SurfaceMaterial[]{new SurfaceMaterial()};
                field.createShape(def, new Geometry.HeightField(data));

                Body resting = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(-1.5, 4, -1.5));
                resting.createShape(new ShapeDef().density(1000), new Geometry.Sphere(0, 0, 0, 0.25f));
                Body falling = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0.5, 4, 0.5));
                falling.createShape(new ShapeDef().density(1000), new Geometry.Sphere(0, 0, 0, 0.25f));
                steps(world, 120);
                assertEquals(2.25, resting.position(new Vector3d()).y, 0.02, "height 1 scaled by 2, plus the radius");
                assertTrue(falling.position(new Vector3d()).y < 0, "through the hole");
            }
            // closed with a shape alive: freed when the shape goes, with the world
        }
    }

    @Test
    void aHeightFieldGoesOnStaticBodiesOnly() {
        try (PhysicsWorld world = world();
             HeightFieldData data = HeightFieldData.create(new float[]{0, 0, 0, 0}, 2, 2)) {
            Body dynamic = world.createBody(new BodyDef().type(BodyType.DYNAMIC));
            assertThrows(Box3DException.class, () -> dynamic.createShape(new ShapeDef(), new Geometry.HeightField(data)));
            assertTrue(dynamic.shapes().isEmpty());
            List<Executable> refused = List.of(
                    () -> HeightFieldData.create(new float[3], 2, 2),
                    () -> HeightFieldData.create(new float[]{0, Float.NaN, 0, 0}, 2, 2),
                    () -> HeightFieldData.create(new float[4], 2, 2, new byte[2], 1, 1, 1, 0, 1),
                    () -> HeightFieldData.create(new float[4], 2, 2, null, 0, 1, 1, 0, 1),
                    () -> HeightFieldData.create(new float[4], 2, 2, null, 1, 1, 1, 1, 0));
            for (Executable call : refused) {
                assertThrows(IllegalArgumentException.class, call);
            }
        }
    }

    // ---- changing what a shape is -------------------------------------------------------------

    @Test
    void aShapeChangesItsGeometryInPlaceAndTheBodyRestsOnTheNewOne() {
        try (PhysicsWorld world = world()) {
            ground(world);
            Body ball = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, 2, 0));
            Shape shape = ball.createShape(new ShapeDef().density(1000), new Geometry.Sphere(0, 0, 0, 0.25f));
            long id = shape.id();
            steps(world, 90);
            assertEquals(0.75, ball.position(new Vector3d()).y, 0.02);
            float small = ball.mass();

            Geometry.Sphere big = new Geometry.Sphere(0, 0, 0, 0.5f);
            shape.setGeometry(big, false);
            assertSame(big, shape.geometry());
            assertEquals(id, shape.id(), "the same shape");
            assertEquals(small, ball.mass(), 1e-3, "the mass stays unless asked");
            float[] bounds = shape.bounds(new float[6]);
            assertEquals(1.0, bounds[4] - bounds[1], 0.05, "bounds at once");
            steps(world, 90);
            assertEquals(1.0, ball.position(new Vector3d()).y, 0.02, "pushed up, resting on the bigger sphere");

            shape.setGeometry(new Geometry.Sphere(0, 0, 0, 0.5f), true);
            assertEquals(8 * small, ball.mass(), small * 0.01, "eight times the volume");

            shape.setGeometry(Geometry.Hull.of(CUBE), true);
            assertEquals(1000, ball.mass(), 1, "a unit cube's");
            shape.setGeometry(new Geometry.Capsule(0, -0.3f, 0, 0, 0.3f, 0, 0.2f), true);
            shape.setGeometry(Geometry.Box.of(0.5f, 0.25f, 0.5f), true);
            ball.setTransform(0, 2, 0, new Quaternionf());
            steps(world, 120);
            assertEquals(0.5 + 0.25, ball.position(new Vector3d()).y, 0.02, "lying on the slab it became");
            assertThrows(IllegalArgumentException.class,
                    () -> shape.setGeometry(new Geometry.HeightField(HeightFieldData.create(new float[4], 2, 2)), false));
        }
    }

    @Test
    void aMeshSwappedOutIsLetGoAndOneSwappedInIsHeld() {
        float[] vertices = {-5, 0, -5, 5, 0, -5, 5, 0, 5, -5, 0, 5};
        int[] indices = {0, 2, 1, 0, 3, 2};
        try (PhysicsWorld world = world()) {
            Body floor = world.createBody(new BodyDef());
            Shape shape = floor.createShape(new ShapeDef(), Geometry.Box.of(1, 1, 1));
            MeshData mesh = MeshData.create(vertices, indices, true);
            shape.setGeometry(Geometry.Mesh.of(mesh), false);
            mesh.close();
            mesh.pointer(); // still held by the shape

            Body ball = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(1, 2, 1));
            ball.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.25f));
            steps(world, 90);
            assertEquals(0.25, ball.position(new Vector3d()).y, 0.02, "on the mesh");

            shape.setGeometry(Geometry.Box.of(1, 1, 1), false);
            assertThrows(IllegalStateException.class, mesh::pointer, "let go: the last reference freed it");
        }
    }

    // ---- reading and changing a live shape ----------------------------------------------------

    @Test
    void materialDensityFilterAndSwitchesReadBack() {
        try (PhysicsWorld world = world()) {
            Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC));
            Shape shape = body.createShape(new ShapeDef().density(1000), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            assertEquals(1000, shape.density());
            shape.setDensity(500, true);
            assertEquals(500, shape.density());
            assertEquals(500, body.mass(), 0.1, "the mass followed");
            shape.setDensity(2000, false);
            assertEquals(500, body.mass(), 0.1, "not asked to");

            shape.setFriction(0.3f);
            assertEquals(0.3f, shape.friction());
            shape.setRestitution(0.4f);
            assertEquals(0.4f, shape.restitution());
            SurfaceMaterial material = new SurfaceMaterial().friction(0.9f).restitution(0.1f).rollingResistance(0.2f);
            material.tangentVelocityX = 1.5f;
            material.userMaterialId = 77;
            shape.setSurfaceMaterial(material);
            SurfaceMaterial read = shape.surfaceMaterial(new SurfaceMaterial());
            assertEquals(0.9f, read.friction);
            assertEquals(0.1f, read.restitution);
            assertEquals(0.2f, read.rollingResistance);
            assertEquals(1.5f, read.tangentVelocityX);
            assertEquals(77, read.userMaterialId);

            shape.setFilter(new Filter(4, 9, -2), false);
            Filter filter = shape.filter(new Filter());
            assertEquals(4, filter.categoryBits);
            assertEquals(9, filter.maskBits);
            assertEquals(-2, filter.groupIndex);

            assertFalse(shape.isSensor());
            assertFalse(shape.areContactEventsEnabled(), "Box3D's default: off");
            shape.enableContactEvents(true);
            assertTrue(shape.areContactEventsEnabled());
            shape.enableHitEvents(true);
            assertTrue(shape.areHitEventsEnabled());
            shape.enableSensorEvents(true);
            assertTrue(shape.areSensorEventsEnabled());
            assertEquals(1, shape.meshMaterialCount(), "a box's one material");
            assertEquals(0.9f, shape.meshMaterial(0, new SurfaceMaterial()).friction);
            assertThrows(IndexOutOfBoundsException.class, () -> shape.meshMaterial(1, new SurfaceMaterial()));
            assertThrows(IllegalArgumentException.class, () -> shape.setSurfaceMaterial(new SurfaceMaterial().friction(-1)));
            assertThrows(IllegalArgumentException.class, () -> shape.setDensity(Float.NaN, true));
        }
    }

    @Test
    void aMeshShapesPerTriangleMaterialsReadBackAndChange() {
        try (PhysicsWorld world = world();
             MeshData mesh = MeshData.create(new float[]{0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 1}, new int[]{0, 2, 1, 1, 2, 3},
                     new byte[]{0, 1}, true)) {
            ShapeDef def = new ShapeDef();
            def.materials = new SurfaceMaterial[]{new SurfaceMaterial().friction(0.1f), new SurfaceMaterial().friction(0.2f)};
            Shape shape = world.createBody(new BodyDef()).createShape(def, Geometry.Mesh.of(mesh));
            assertEquals(2, shape.meshMaterialCount());
            assertEquals(0.2f, shape.meshMaterial(1, new SurfaceMaterial()).friction);
            shape.setMeshMaterial(1, new SurfaceMaterial().friction(0.7f));
            assertEquals(0.7f, shape.meshMaterial(1, new SurfaceMaterial()).friction);
            assertThrows(IndexOutOfBoundsException.class, () -> shape.setMeshMaterial(2, new SurfaceMaterial()));
        }
    }

    /** With per-triangle materials the base material is not used: the first of them is the shape's own. */
    @Test
    void aMeshWithMaterialsHasTheFirstAsItsOwnAndASweepReportsTheTrianglesOwn() {
        // two triangles side by side, x 0..1 and 1..2, the second with material 1
        float[] vertices = {0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 1, 2, 0, 0, 2, 0, 1};
        try (PhysicsWorld world = world();
             MeshData mesh = MeshData.create(vertices, new int[]{0, 2, 1, 1, 3, 4, 4, 3, 5, 1, 2, 3}, new byte[]{0, 1, 1, 0},
                     true)) {
            ShapeDef def = new ShapeDef();
            def.baseMaterial.userMaterialId = 99;
            def.materials = new SurfaceMaterial[]{new SurfaceMaterial(), new SurfaceMaterial()};
            def.materials[0].userMaterialId = 10;
            def.materials[1].userMaterialId = 20;
            double x = 20_000_000;
            Shape shape = world.createBody(new BodyDef().position(x, 0, 0)).createShape(def, Geometry.Mesh.of(mesh));
            assertEquals(10, shape.surfaceMaterial(new SurfaceMaterial()).userMaterialId, "materials[0], not the base");

            Geometry.Box feet = Geometry.Box.of(0.1f, 0.1f, 0.1f);
            Quaternionf none = new Quaternionf();
            CastResult own = new CastResult(), all = new CastResult();
            for (double[] at : new double[][]{{0.3, 0.3, 10}, {1.7, 0.4, 20}}) {
                assertTrue(shape.castShape(feet, x + at[0], 1, at[1], none, 0, -2, 0, true, own));
                assertTrue(world.castShapeClosest(feet, x + at[0], 1, at[1], none, 0, -2, 0, QueryFilter.ALL, all));
                assertEquals((long) at[2], own.userMaterialId(), "the triangle's material at x " + at[0]);
                assertEquals(all.userMaterialId(), own.userMaterialId(), "as the world's sweep says");
            }
        }
    }

    @Test
    void aRoundSweepThatMayEncroachIsRefusedAgainstAMesh() {
        try (PhysicsWorld world = world();
             MeshData mesh = MeshData.create(new float[]{0, 0, 0, 1, 0, 0, 0, 0, 1}, new int[]{0, 2, 1}, false)) {
            Shape triangle = world.createBody(new BodyDef()).createShape(new ShapeDef(), Geometry.Mesh.of(mesh));
            Quaternionf none = new Quaternionf();
            CastResult hit = new CastResult();
            Geometry.Sphere ball = new Geometry.Sphere(0, 0, 0, 0.1f);
            assertThrows(IllegalArgumentException.class,
                    () -> triangle.castShape(ball, 0.3, 1, 0.3, none, 0, -2, 0, true, hit), "it would creep in");
            assertTrue(triangle.castShape(ball, 0.3, 1, 0.3, none, 0, -2, 0, false, hit), "not encroaching, it is fine");
            assertTrue(triangle.castShape(Geometry.Box.of(0.1f, 0.1f, 0.1f), 0.3, 1, 0.3, none, 0, -2, 0, true, hit));
        }
    }

    @Test
    void aCapsuleWithItsEndsWithinTheSlopIsTheSphereBox3DMakesOfIt() {
        try (PhysicsWorld world = world()) {
            Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC));
            Shape shape = body.createShape(new ShapeDef(), new Geometry.Capsule(0, 1, 0, 0, 1.002f, 0, 0.25f));
            Geometry.Sphere sphere = assertInstanceOf(Geometry.Sphere.class, shape.geometry());
            assertEquals(1.001f, sphere.cy(), 1e-6);
            assertEquals(0.25f, sphere.radius());
            shape.setGeometry(new Geometry.Capsule(1, 0, 0, 1, 0, 0, 0.5f), true);
            assertEquals(new Geometry.Sphere(1, 0, 0, 0.5f), shape.geometry(), "given to a live shape too");
            shape.setGeometry(new Geometry.Capsule(0, 0, 0, 0, 1, 0, 0.5f), true);
            assertInstanceOf(Geometry.Capsule.class, shape.geometry());
        }
    }

    @Test
    void aSensorListsWhatIsInsideIt() {
        try (PhysicsWorld world = world()) {
            Body zone = world.createBody(new BodyDef());
            Shape sensor = zone.createShape(new ShapeDef().sensor(true).enableSensorEvents(true), Geometry.Box.of(2, 2, 2));
            assertTrue(sensor.isSensor());
            Body inside = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, 0, 0).gravityScale(0));
            Shape ball = inside.createShape(new ShapeDef().enableSensorEvents(true), new Geometry.Sphere(0, 0, 0, 0.25f));
            Body outside = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(10, 0, 0).gravityScale(0));
            outside.createShape(new ShapeDef().enableSensorEvents(true), new Geometry.Sphere(0, 0, 0, 0.25f));
            steps(world, 2);
            Overlaps overlaps = new Overlaps(1);
            assertEquals(1, sensor.sensorOverlaps(overlaps));
            assertSame(ball, overlaps.shape(0));
            assertEquals(0, ball.sensorOverlaps(overlaps), "not a sensor: nothing in it");
        }
    }

    @Test
    void aShapesClosestPointIsItsOwnAndExact() {
        try (PhysicsWorld world = world()) {
            double x = 20_000_000.5;
            Body body = world.createBody(new BodyDef().position(x, 0, 0));
            Shape box = body.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            Shape far = body.createShape(new ShapeDef(), new Geometry.Sphere(3, 0, 0, 0.5f));
            Vector3d closest = new Vector3d();
            assertEquals(1.5f, box.closestPoint(x - 2, 0, 0, closest), 1e-4);
            assertEquals(x - 0.5, closest.x, 1e-4);
            assertEquals(4.5f, far.closestPoint(x - 2, 0, 0, closest), 1e-4, "that shape, not the body's closest");

            try (MeshData mesh = MeshData.create(new float[]{0, 0, 0, 1, 0, 0, 0, 0, 1}, new int[]{0, 2, 1}, false)) {
                Shape triangle = body.createShape(new ShapeDef(), Geometry.Mesh.of(mesh));
                assertEquals(Float.POSITIVE_INFINITY, triangle.closestPoint(x, 1, 0, closest));
            }
            assertTrue(B3.shapeIsValid(box.id()));
        }
    }

    @Test
    void aShapeCastReportsEveryShapeItMeetsClosestFirst() {
        try (PhysicsWorld world = world()) {
            double x = 20_000_000.5;
            Shape[] boxes = new Shape[3];
            for (int i = 0; i < 3; i++) {
                Body body = world.createBody(new BodyDef().position(x + 8 - 3 * i, 0, 0)); // farthest made first
                boxes[2 - i] = body.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            }
            Body zone = world.createBody(new BodyDef().position(x + 11, 0, 0));
            Shape sensor = zone.createShape(new ShapeDef().sensor(true), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            ShapeDef other = new ShapeDef();
            other.filter.categoryBits = 1L << 9;
            world.createBody(new BodyDef().position(x + 4, 0, 0)).createShape(other, Geometry.Box.of(0.5f, 0.5f, 0.5f));
            Geometry.Sphere ball = new Geometry.Sphere(0, 0, 0, 0.25f);

            CastHits hits = new CastHits(1);
            assertEquals(5, world.castShape(ball, x, 0, 0, new Quaternionf(), 20, 0, 0, QueryFilter.ALL, hits),
                    "the three boxes, the other category's and the sensor: room is made for them all");
            assertEquals(4, world.castShape(ball, x, 0, 0, new Quaternionf(), 20, 0, 0, QueryFilter.only(~(1L << 9)), hits),
                    "a filter leaves a category out");
            assertSame(boxes[0], hits.shape(0));
            assertSame(boxes[1], hits.shape(1));
            assertSame(boxes[2], hits.shape(2));
            assertSame(sensor, hits.shape(3), "a sensor is met as any shape");
            for (int i = 0; i < 3; i++) {
                assertEquals(x + 1.5 + 3 * i, hits.x(i), 0.01, "each box's near face, exact this far out");
                assertEquals((1.5 + 3 * i - 0.25) / 20, hits.fraction(i), 6e-4, "a linear slop short at most");
            }

            // starting inside one: met at the start
            assertEquals(3, world.castShape(ball, x + 2, 0, 0, new Quaternionf(), 6, 0, 0, QueryFilter.only(~(1L << 9)), hits));
            assertSame(boxes[0], hits.shape(0));
            assertEquals(0, hits.fraction(0));
            assertEquals(0, world.castShape(ball, x, 5, 0, new Quaternionf(), 20, 0, 0, QueryFilter.ALL, hits), "a miss");
        }
    }

    /**
     * Through a node reaching back to the origin, ten and twenty-five million blocks out: a ball there and the one cast at
     * make one node, its middle millions of blocks off the cast; a third far the other way makes it a child of the root.
     * ⚠️ Real shapes' bounds are fattened and rounded outwards, and with that slack even a walk that centres such a node
     * unclipped met every ball here — what the clip is for was shown by an exact emulation (Entity Studio doc 56 §19.10).
     */
    @Test
    void castsFarOutThroughANodeReachingBackToTheOrigin() {
        Geometry.Sphere grain = new Geometry.Sphere(0, 0, 0, 0.05f);
        CastHits hits = new CastHits(1);
        List<String> missed = new java.util.ArrayList<>();
        for (double further : new double[]{0, 15_000_000}) {
            for (int p = 0; p < 200; p++) {
                double x = x(p) + further, z = z(p) - further;
                try (PhysicsWorld world = world()) {
                    ball(world, 0, 0, 0);
                    Shape target = ball(world, x, -55, z);
                    ball(world, -29_000_000, 0, 29_000_000);
                    if (world.castRay(x, -50, z, 0, -10, 0, QueryFilter.ALL, hits) != 1 || hits.shape(0) != target) {
                        missed.add("ray down at " + x + ", " + z);
                    }
                    if (world.castRay(x - 0.5, -50, z - 0.5, 1, -10, 1, QueryFilter.ALL, hits) != 1 || hits.shape(0) != target) {
                        missed.add("ray slanting down at " + x + ", " + z);
                    }
                    if (world.castShape(grain, x + 0.5, -50, z, new Quaternionf(), -1, -10, 0, QueryFilter.ALL, hits) != 1
                            || hits.shape(0) != target) {
                        missed.add("grain slanting down at " + x + ", " + z);
                    }
                }
            }
        }
        assertEquals(List.of(), missed);
    }

    private static Shape ball(PhysicsWorld world, double x, double y, double z) {
        return world.createBody(new BodyDef().position(x, y, z)).createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.4f));
    }

    /**
     * Ten million blocks out a float holds whole blocks: the broad phase's walk, in float, dropped small shapes a cast ran
     * straight through — two balls of 0.4 under a ray down a half-block line, found or not by where they stood
     * (fork, doc 02). Many places, on half and whole blocks, so that the rounding goes every way it can.
     */
    @Test
    void castsTenMillionBlocksOutMeetTheSmallShapesTheyRunThrough() {
        try (PhysicsWorld world = world()) {
            int places = 200;
            Shape[][] balls = new Shape[places][2];
            for (int p = 0; p < places; p++) {
                for (int i = 0; i < 2; i++) {
                    Body body = world.createBody(new BodyDef().type(BodyType.KINEMATIC));
                    balls[p][i] = body.createShape(new ShapeDef().sensor(true), new Geometry.Sphere(0, 0, 0, 0.4f));
                    body.setTransform(x(p), -55 - 2 * i, z(p), new Quaternionf());
                }
            }
            CastHits hits = new CastHits(1);
            Geometry.Sphere grain = new Geometry.Sphere(0, 0, 0, 0.05f);
            List<String> missed = new java.util.ArrayList<>();
            for (int p = 0; p < places; p++) {
                int count = world.castRay(x(p), -55, z(p), 0, -2.8f, 0, QueryFilter.ALL, hits);
                if (count != 2 || hits.shape(0) != balls[p][0] || hits.shape(1) != balls[p][1]) {
                    missed.add("ray from inside at " + p + ": " + count);
                }
                CastHit closest = world.castRayClosest(x(p), -50, z(p), 0, -10, 0, QueryFilter.ALL);
                if (closest == null || closest.shape() != balls[p][0]) {
                    missed.add("closest from above at " + p);
                }
                count = world.castShape(grain, x(p), -50, z(p), new Quaternionf(), 0, -10, 0, QueryFilter.ALL, hits);
                if (count != 2 || hits.shape(0) != balls[p][0] || hits.shape(1) != balls[p][1]) {
                    missed.add("grain swept down at " + p + ": " + count);
                }
                // slanting, a tenth of a block off each ball's middle: straight down, the walk's edge tests had nothing to do
                count = world.castRay(x(p) - 0.6, -50, z(p) - 0.6, 1.2f, -12, 1.2f, QueryFilter.ALL, hits);
                if (count != 2 || hits.shape(0) != balls[p][0] || hits.shape(1) != balls[p][1]) {
                    missed.add("ray slanting down at " + p + ": " + count);
                }
                count = world.castShape(grain, x(p) + 0.6, -50, z(p) - 0.6, new Quaternionf(), -1.2f, -12, 1.2f,
                        QueryFilter.ALL, hits);
                if (count != 2 || hits.shape(0) != balls[p][0] || hits.shape(1) != balls[p][1]) {
                    missed.add("grain slanting down at " + p + ": " + count);
                }
            }
            assertEquals(List.of(), missed);
            assertEquals(-56.6, world.castRay(x(0), -55, z(0), 0, -2.8f, 0, QueryFilter.ALL, hits) == 2 ? hits.y(1) : 0,
                    1e-3, "exact where it is met");
        }
    }

    private static double x(int place) {
        return 10_383_108.5 + 7 * place + (place % 2) * 0.5;
    }

    private static double z(int place) {
        return -11_403_396.5 - 13 * place - (place % 4 / 2) * 0.5;
    }

    /**
     * More than the natives hold on the stack — sixteen hits a cast or planes a mover, thirty-two shapes an overlap — all
     * come back, closest first where that is the order, whether the arrays had room from the start or grew for them.
     */
    @Test
    void morePastWhatTheNativesHoldOnTheStackAllComeBack() {
        try (PhysicsWorld world = world()) {
            int balls = 50;
            java.util.Set<Shape> made = new java.util.HashSet<>();
            for (int i = 0; i < balls; i++) {
                Body body = world.createBody(new BodyDef().type(BodyType.KINEMATIC).position(i, 0, 0));
                made.add(body.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.3f)));
            }
            int[] planes = new int[2];
            for (int round = 0; round < 2; round++) {
                int room = round == 0 ? 1 : 1000;
                CastHits hits = new CastHits(room);
                assertEquals(balls, world.castRay(-1, 0, 0, balls + 1, 0, 0, QueryFilter.ALL, hits));
                for (int i = 0; i < balls; i++) {
                    assertEquals(i - 0.3, hits.x(i), 1e-4, "closest first");
                }
                assertEquals(balls, world.castShape(new Geometry.Sphere(0, 0, 0, 0.1f), -1, 0, 0, new Quaternionf(), balls + 1,
                        0, 0, QueryFilter.ALL, hits));
                for (int i = 1; i < balls; i++) {
                    assertTrue(hits.fraction(i - 1) < hits.fraction(i), "closest first");
                }

                Overlaps found = new Overlaps(room);
                assertEquals(balls, world.overlapShape(Geometry.Box.of(balls, 1, 1), 0, 0, 0, QueryFilter.ALL, found));
                assertEquals(made, shapesOf(found));
                assertEquals(balls, world.overlapAABB(-1, -1, -1, balls, 1, 1, QueryFilter.ALL, found));
                assertEquals(made, shapesOf(found));

                planes[round] = world.collideMover(0, 0.6, 0, 0, 0, 0, balls - 1, 0, 0, 0.4f, QueryFilter.ALL,
                        new MoverPlanes(room));
            }
            assertTrue(planes[0] >= balls, "a plane for every ball the mover lies on: " + planes[0]);
            assertEquals(planes[0], planes[1]);
        }
    }

    /**
     * A cast as long as a float goes, in a tree of one shape: the walk's margin went infinite with it, the empty node's
     * bounds went not-a-number, and it was taken for a leaf — a proxy far past the end of the array.
     */
    @Test
    void aCastAsLongAsAFloatGoesDoesNotTakeTheEmptyNodeForAShape() {
        try (PhysicsWorld world = world()) {
            Body ground = world.createBody(new BodyDef());
            Shape top = ground.createShape(new ShapeDef(), Geometry.Box.of(5, 0.5f, 5));
            CastHits hits = new CastHits();
            assertEquals(1, world.castRay(0, 10, 0, 0, -1e20f, 0, QueryFilter.ALL, hits));
            assertSame(top, hits.shape(0));
            CastHit closest = world.castRayClosest(0, 10, 0, 0, -Float.MAX_VALUE, 0, QueryFilter.ALL);
            assertTrue(closest == null || closest.shape() == top);
            world.castShape(new Geometry.Sphere(0, 0, 0, 0.1f), 0, 10, 0, new Quaternionf(), 1e20f, -1e20f, 0, QueryFilter.ALL,
                    hits);
        }
    }

    private static java.util.Set<Shape> shapesOf(Overlaps found) {
        java.util.Set<Shape> shapes = new java.util.HashSet<>();
        for (int i = 0; i < found.count(); i++) {
            shapes.add(found.shape(i));
        }
        return shapes;
    }

    // ---- one shape's sweep, and the broad phase's candidates (doc 04 §11, 4c-1) ----------------

    /**
     * A box resting on a ball or a capsule, pressed down tick after tick as Minecraft's gravity does, stays a few
     * millimetres above it: it does not creep in (Box3D's own rule for a rounded pair lets it in a slop at every cast).
     */
    @Test
    void aBoxPressedOntoARoundShapeTickAfterTickDoesNotSinkIn() {
        try (PhysicsWorld world = world()) {
            double x = 20_000_000.5;
            Body body = world.createBody(new BodyDef().position(x, 0, 0));
            Shape ball = body.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 1)); // its top at y = 1
            Shape log = body.createShape(new ShapeDef(), new Geometry.Capsule(-1, 0, 4, 1, 0, 4, 0.5f)); // top at 0.5
            Geometry.Box feet = Geometry.Box.of(0.3f, 0.3f, 0.3f);
            Quaternionf none = new Quaternionf();
            CastResult hit = new CastResult();
            for (Object[] round : new Object[][]{{ball, 0.0, 1.0}, {log, 4.0, 0.5}}) {
                Shape shape = (Shape) round[0];
                double z = (double) round[1], top = (double) round[2];
                // from afar: a slop short of the surface, not inside it
                double bottom = top + 2;
                assertTrue(shape.castShape(feet, x, bottom + 0.3, z, none, 0, -3, 0, true, hit));
                bottom -= 3 * hit.fraction();
                assertTrue(bottom > top && bottom < top + 0.01, "landed above the top: " + (bottom - top));
                // gravity, every tick for a while
                for (int tick = 0; tick < 100; tick++) {
                    if (shape.castShape(feet, x, bottom + 0.3, z, none, 0, -0.08f, 0, true, hit)) {
                        bottom -= 0.08 * hit.fraction();
                    } else {
                        bottom -= 0.08;
                    }
                }
                assertTrue(bottom > top + 0.001 && bottom < top + 0.01, "still above it after 100 ticks: " + (bottom - top));
                // and along it is no hit
                assertFalse(shape.castShape(feet, x, bottom + 0.3, z, none, 0.05f, 0, 0, true, hit), "sliding across its top");
            }
        }
    }

    @Test
    void aSweepAgainstOneShapeEncroachesOnlyMovingIn() {
        try (PhysicsWorld world = world()) {
            double x = 20_000_000.5;
            Body body = world.createBody(new BodyDef().position(x, 0.5, 0));
            Shape top = body.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f)); // its top at y = 1
            Shape trigger = body.createShape(new ShapeDef().sensor(true), new Geometry.Sphere(0, 4, 0, 0.5f));
            Geometry.Box feet = Geometry.Box.of(0.3f, 0.3f, 0.3f);
            Quaternionf none = new Quaternionf();
            CastResult hit = new CastResult();
            Vector3f normal = new Vector3f();

            // from afar: stops Box3D's linear slop short of the top
            assertTrue(top.castShape(feet, x, 3, 0, none, 0, -3, 0, true, hit));
            double landed = 3 - 3 * hit.fraction() - 0.3;
            assertEquals(1.005, landed, 0.002, "a few millimetres above the top: " + landed);
            assertEquals(1, hit.normal(normal).y, 1e-3, "the top's normal");

            // standing there: along and away are not hits, in is, and closer
            assertFalse(top.castShape(feet, x, landed + 0.3, 0, none, 1, 0, 0, true, hit), "walking along the top");
            assertFalse(top.castShape(feet, x, landed + 0.3, 0, none, 0, 0.5f, 0, true, hit), "jumping off it");
            assertTrue(top.castShape(feet, x, landed + 0.3, 0, none, 0, -0.5f, 0, true, hit), "pressing down");
            double pressed = landed - 0.5 * hit.fraction();
            assertTrue(pressed > 1 && pressed < landed, "a little closer, never in: " + pressed);
            // without encroaching, touching is a hit at the start whichever way
            assertTrue(top.castShape(feet, x, landed + 0.3, 0, none, 1, 0, 0, false, hit));
            assertEquals(0, hit.fraction());

            // overlapping: a hit at fraction 0 with no normal
            assertTrue(top.castShape(feet, x, 0.5, 0, none, 1, 0, 0, true, hit));
            assertEquals(0, hit.fraction());
            assertEquals(0, hit.normal(normal).length(), 1e-6, "no normal from inside");

            // only that shape: the sensor is its own, and is hit when asked for itself
            assertFalse(top.castShape(feet, x, 4, -3, none, 0, 0, 6, true, hit), "the box's sweep does not see the sensor");
            assertTrue(trigger.castShape(feet, x, 4, -3, none, 0, 0, 6, true, hit));
            assertSame(trigger, hit.shape());
        }
    }

    @Test
    void theBroadPhaseHandsOutTheShapesNearABoxFarOut() {
        try (PhysicsWorld world = world()) {
            double x = 20_000_000.5;
            Body near = world.createBody(new BodyDef().position(x, 0, 0));
            Shape mine = near.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            Body away = world.createBody(new BodyDef().position(x + 10, 0, 0));
            away.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            ShapeDef other = new ShapeDef();
            other.filter.categoryBits = 1L << 9;
            Shape hidden = near.createShape(other, new Geometry.Sphere(0, 2, 0, 0.5f));

            Overlaps found = new Overlaps(1);
            assertEquals(2, world.overlapAABB(x + 0.4, -0.1, -0.1, x + 0.6, 2.1, 0.1, QueryFilter.ALL, found),
                    "both of the near body's shapes; not the one ten blocks off");
            assertTrue(found.shape(0) == mine || found.shape(1) == mine);
            assertEquals(1, world.overlapAABB(x + 0.4, -0.1, -0.1, x + 0.6, 2.1, 0.1, QueryFilter.only(~(1L << 9)), found),
                    "a filter leaves a category out");
            assertSame(mine, found.shape(0));
            assertNotSame(hidden, found.shape(0));
            // the broad phase keeps float bounds: two blocks apart this far out, so "nothing between" is asked nearer in
            Body close = world.createBody(new BodyDef().position(1000.5, 0, 0));
            close.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
            assertEquals(1, world.overlapAABB(1000.9, 0, 0, 1001.2, 1, 1, QueryFilter.ALL, found));
            assertEquals(0, world.overlapAABB(1002, 0, 0, 1003, 1, 1, QueryFilter.ALL, found), "nothing a block off");
            assertThrows(IllegalArgumentException.class,
                    () -> world.overlapAABB(1, 0, 0, 0, 1, 1, QueryFilter.ALL, new Overlaps()), "min above max");
        }
    }
}
