package com.lowdragmc.minebox3d.physics;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * <b>Friction and restitution combine modes</b> (UE's physical material): two surfaces without one mix as Box3D always did,
 * with one the later mode of the two wins, the user's material id comes back as it went in.
 */
class SurfaceCombineTest {

    /** A box sliding at 5 m/s on a floor of friction 0.9, itself 0.1: the friction they slide by, read off how fast it slows. */
    private static float slidingFriction(SurfaceMaterial.Combine mode) {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, -10, 0))) {
            Body floor = world.createBody(new BodyDef().position(0, -0.5, 0));
            ShapeDef ground = new ShapeDef();
            ground.baseMaterial.friction = 0.9f;
            floor.createShape(ground, Geometry.Box.of(50, 0.5f, 50));
            Body box = world.createBody(
                    new BodyDef().type(BodyType.DYNAMIC).position(0, 0.25, 0).enableSleep(false));
            ShapeDef shape = new ShapeDef().density(1000);
            shape.baseMaterial.friction = 0.1f;
            shape.baseMaterial.frictionCombine = mode;
            box.createShape(shape, Geometry.Box.of(0.25f, 0.25f, 0.25f));
            for (int i = 0; i < 10; i++) {
                world.step(1 / 60f, 4);
            }
            box.setLinearVelocity(5, 0, 0);
            for (int i = 0; i < 18; i++) {
                world.step(1 / 60f, 4);
            }
            return (5 - box.linearVelocity(new Vector3f()).x) / (10 * 18 / 60f);
        }
    }

    @Test
    void frictionMixesByTheLaterModeOrAsBox3DWithout() {
        assertEquals(0.3f, slidingFriction(null), 0.03f, "Box3D's own: √(0.9 · 0.1)");
        assertEquals(0.5f, slidingFriction(SurfaceMaterial.Combine.AVERAGE), 0.03f);
        assertEquals(0.1f, slidingFriction(SurfaceMaterial.Combine.MIN), 0.03f);
        assertEquals(0.09f, slidingFriction(SurfaceMaterial.Combine.MULTIPLY), 0.03f);
        assertEquals(0.9f, slidingFriction(SurfaceMaterial.Combine.MAX), 0.03f);
    }

    /** A ball dropped 2 m onto a floor of restitution 0.9, itself 0.1: the share of its speed it bounces back with. */
    private static float bounce(SurfaceMaterial.Combine mode) {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, -10, 0))) {
            Body floor = world.createBody(new BodyDef().position(0, -0.5, 0));
            ShapeDef ground = new ShapeDef();
            ground.baseMaterial.restitution = 0.9f;
            floor.createShape(ground, Geometry.Box.of(50, 0.5f, 50));
            Body ball = world.createBody(
                    new BodyDef().type(BodyType.DYNAMIC).position(0, 2.2, 0).enableSleep(false));
            ShapeDef shape = new ShapeDef().density(1000);
            shape.baseMaterial.restitution = 0.1f;
            shape.baseMaterial.restitutionCombine = mode;
            ball.createShape(shape, new Geometry.Sphere(0, 0, 0, 0.2f));
            float fastestDown = 0, fastestUp = 0;
            for (int i = 0; i < 60; i++) {
                world.step(1 / 60f, 4);
                float vy = ball.linearVelocity(new Vector3f()).y;
                fastestDown = Math.max(fastestDown, -vy);
                fastestUp = Math.max(fastestUp, vy);
            }
            return fastestUp / fastestDown;
        }
    }

    @Test
    void restitutionMixesByTheLaterModeOrAsBox3DWithout() {
        assertEquals(0.9f, bounce(null), 0.05f, "Box3D's own: the larger");
        assertEquals(0.1f, bounce(SurfaceMaterial.Combine.MIN), 0.05f);
        assertEquals(0.5f, bounce(SurfaceMaterial.Combine.AVERAGE), 0.05f);
    }

    @Test
    void theModesAndTheUsersIdComeBackAsTheyWentIn() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef())) {
            Body body = world.createBody(new BodyDef());
            ShapeDef def = new ShapeDef();
            def.baseMaterial.userMaterialId = 0x00AB_CDEF_1234_5678L;
            def.baseMaterial.combine(SurfaceMaterial.Combine.MIN, SurfaceMaterial.Combine.MAX);
            Shape shape = body.createShape(def, Geometry.Box.of(1, 1, 1));
            SurfaceMaterial read = shape.surfaceMaterial(new SurfaceMaterial());
            assertEquals(0x00AB_CDEF_1234_5678L, read.userMaterialId);
            assertEquals(SurfaceMaterial.Combine.MIN, read.frictionCombine);
            assertEquals(SurfaceMaterial.Combine.MAX, read.restitutionCombine);

            CastResult hit = new CastResult();
            assertTrue(world.castRayClosest(0, 5, 0, 0, -10, 0, QueryFilter.ALL, hit));
            assertEquals(
                    0x00AB_CDEF_1234_5678L, hit.userMaterialId(), "a query sees the user's id, not MineBox3D's byte");

            read.combine(null, null);
            shape.setSurfaceMaterial(read);
            SurfaceMaterial plain = shape.surfaceMaterial(new SurfaceMaterial());
            assertNull(plain.frictionCombine);
            assertNull(plain.restitutionCombine);

            def.baseMaterial.userMaterialId = 1L << 60;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> body.createShape(def, Geometry.Box.of(1, 1, 1)),
                    "the top byte is MineBox3D's");
        }
    }
}
