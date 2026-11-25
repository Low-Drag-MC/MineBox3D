package com.lowdragmc.minebox3d.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * <b>Box3D's dynamic mover joints</b>: the mover joint drives a character body at a velocity by a limited force, the pogo
 * joint holds it up off what it stands on and weighs that down — every property reads back what was set, and each does
 * what it is for: walks, pushes a light crate, is stopped by a heavy one, stands at its height, tips a plank.
 */
class MoverJointTest {

    private static final float GRAVITY = -10;
    private static final float HALF_HEIGHT = 0.9f, RADIUS = 0.3f, REST = 0.5f;

    private static PhysicsWorld world() {
        return PhysicsWorld.create(new WorldDef().gravity(0, GRAVITY, 0));
    }

    private static Body ground(PhysicsWorld world) {
        Body ground = world.createBody(new BodyDef().position(0, -0.5, 0));
        ground.createShape(new ShapeDef(), Geometry.Box.of(50, 0.5f, 50));
        return ground;
    }

    /** A capsule standing upright with its rotation locked, no friction: what Box3D's sample walks. */
    private static Body character(PhysicsWorld world, double x, double y, double z) {
        BodyDef def = new BodyDef()
                .type(BodyType.DYNAMIC)
                .position(x, y, z)
                .enableSleep(false)
                .fixedRotation(true);
        Body body = world.createBody(def);
        ShapeDef shape = new ShapeDef().density(500);
        shape.baseMaterial.friction = 0;
        body.createShape(shape, new Geometry.Capsule(0, RADIUS, 0, 0, HALF_HEIGHT * 2 - RADIUS, 0, RADIUS));
        return body;
    }

    /** The pogo between the character and the ground right under it, its frame B at the capsule's bottom. */
    private static PogoJoint pogo(PhysicsWorld world, Body ground, Body character) {
        PogoJointDef def = new PogoJointDef()
                .bodies(ground, character)
                .normal(0, 1, 0)
                .spring(5, 1, REST)
                .maxForces(character.mass() * 20, character.mass() * 40);
        Vector3d at = character.position(new Vector3d());
        // the ground's top, in the ground body's own frame (it is centred half a block down)
        def.localFrameA((float) at.x, 0.5f, (float) at.z, new Quaternionf());
        def.localFrameB(0, 0, 0, new Quaternionf());
        return world.createJoint(def);
    }

    private static void steps(PhysicsWorld world, int count) {
        for (int i = 0; i < count; i++) {
            world.beginInterpolationFrame();
            world.step(1 / 60f, 4);
        }
    }

    @Test
    void everyPropertyReadsBackWhatWasSet() {
        try (PhysicsWorld world = world()) {
            Body ground = ground(world);
            Body body = character(world, 0, 1, 0);
            Vector3f v = new Vector3f();

            MoverJoint mover = world.createJoint(new MoverJointDef()
                    .bodies(ground, body)
                    .linearVelocity(1, 0, 2)
                    .maxForce(300));
            assertEquals(new Vector3f(1, 0, 2), mover.linearVelocity(v));
            assertEquals(300, mover.maxForce());
            mover.setLinearVelocity(-3, 0, 0.5f);
            assertEquals(new Vector3f(-3, 0, 0.5f), mover.linearVelocity(v));
            mover.setMaxForce(50);
            assertEquals(50, mover.maxForce());
            assertEquals(JointType.MOVER, mover.type());

            PogoJoint pogo = world.createJoint(new PogoJointDef()
                    .bodies(ground, body)
                    .normal(0, 1, 0)
                    .spring(4, 0.7f, 0.6f)
                    .maxForces(100, 200));
            assertEquals(0.6f, pogo.restLength());
            assertEquals(4, pogo.springHertz());
            assertEquals(0.7f, pogo.springDampingRatio());
            assertEquals(100, pogo.maxTensionForce());
            assertEquals(200, pogo.maxCompressionForce());
            pogo.setRestLength(0.4f);
            assertEquals(0.4f, pogo.restLength());
            pogo.setSpringHertz(6);
            assertEquals(6, pogo.springHertz());
            pogo.setSpringDampingRatio(0.5f);
            assertEquals(0.5f, pogo.springDampingRatio());
            pogo.setMaxTensionForce(7);
            assertEquals(7, pogo.maxTensionForce());
            pogo.setMaxCompressionForce(8);
            assertEquals(8, pogo.maxCompressionForce());
            pogo.setNormal(0, 0, 1);
            assertEquals(new Vector3f(0, 0, 1), pogo.normal(v));
            assertEquals(JointType.POGO, pogo.type());
            assertTrue(
                    Float.isFinite(pogo.currentLength())
                            && Float.isFinite(pogo.currentSpeed())
                            && Float.isFinite(pogo.impulse()),
                    "the spring's state reads");

            assertThrows(IllegalArgumentException.class, () -> mover.setMaxForce(-1));
            assertThrows(IllegalArgumentException.class, () -> pogo.setNormal(0, 2, 0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> world.createJoint(
                            new PogoJointDef().bodies(ground, body).normal(1, 1, 0)));
        }
    }

    @Test
    void aMoverStandsAtItsPogosHeightAndWalksAtItsVelocity() {
        try (PhysicsWorld world = world()) {
            Body ground = ground(world);
            Body body = character(world, 0, REST, 0);
            MoverJoint mover =
                    world.createJoint(new MoverJointDef().bodies(ground, body).maxForce(body.mass() * 30));
            PogoJoint pogo = pogo(world, ground, body);
            steps(world, 60);
            assertEquals(REST, body.position(new Vector3d()).y, 0.05, "held its rest length above the ground");

            pogo.destroy(false);
            mover.setLinearVelocity(3, 0, 0);
            for (int i = 0; i < 60; i++) {
                PogoJoint step = pogo(world, ground, body);
                steps(world, 1);
                step.destroy(false);
            }
            Vector3f v = body.linearVelocity(new Vector3f());
            assertEquals(3, v.x, 0.1, "walks at the mover's velocity");
            assertEquals(REST, body.position(new Vector3d()).y, 0.1, "at its height");
        }
    }

    @Test
    void aMoverPushesALightCrateAndIsStoppedByAHeavyOne() {
        try (PhysicsWorld world = world()) {
            Body ground = ground(world);
            Body body = character(world, 0, REST, 0);
            float force = body.mass() * 30;
            MoverJoint mover = world.createJoint(new MoverJointDef()
                    .bodies(ground, body)
                    .linearVelocity(2, 0, 0)
                    .maxForce(force));
            Body light = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(1.5, 0.4, 0));
            light.createShape(new ShapeDef().density(50), Geometry.Box.of(0.4f, 0.4f, 0.4f));
            for (int i = 0; i < 120; i++) {
                PogoJoint step = pogo(world, ground, body);
                steps(world, 1);
                step.destroy(false);
            }
            assertTrue(
                    light.position(new Vector3d()).x > 2.5,
                    "pushed the light crate along: " + light.position(new Vector3d()));

            light.destroy();
            Body heavy = world.createBody(
                    new BodyDef().type(BodyType.DYNAMIC).position(body.position(new Vector3d()).x + 1.5, 0.4, 0));
            ShapeDef stuck = new ShapeDef().density(20000);
            stuck.baseMaterial.friction = 1;
            heavy.createShape(stuck, Geometry.Box.of(0.4f, 0.4f, 0.4f));
            for (int i = 0; i < 120; i++) {
                PogoJoint step = pogo(world, ground, body);
                steps(world, 1);
                step.destroy(false);
            }
            assertTrue(
                    body.linearVelocity(new Vector3f()).x < 0.3f,
                    "held at the heavy one by its limited force: " + body.linearVelocity(new Vector3f()));
            mover.setLinearVelocity(0, 0, 0);
        }
    }

    @Test
    void aPogoWeighsDownWhatItStandsOn() {
        try (PhysicsWorld world = world()) {
            ground(world);
            Body pivot = world.createBody(new BodyDef().position(0, 1, 0));
            Body plank = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(0, 1, 0));
            plank.createShape(new ShapeDef().density(100), Geometry.Box.of(2.5f, 0.1f, 0.5f));
            RevoluteJoint hinge = world.createJoint(
                    new RevoluteJointDef().bodies(pivot, plank).worldFrame(0, 1, 0));
            Body body = character(world, 2, 1.1 + REST, 0);
            for (int i = 0; i < 30; i++) {
                PogoJointDef def = new PogoJointDef()
                        .bodies(plank, body)
                        .normal(0, 1, 0)
                        .spring(5, 1, REST)
                        .maxForces(body.mass() * 20, body.mass() * 40);
                Vector3d at = body.position(new Vector3d());
                def.localFrameA((float) at.x, 0.1f, 0, new Quaternionf());
                PogoJoint step = world.createJoint(def);
                steps(world, 1);
                step.destroy(false);
            }
            Vector3f east = plank.rotation(new Quaternionf()).transform(new Vector3f(1, 0, 0));
            assertTrue(east.y < -0.05f, "the end it stands on went down: " + east);
            hinge.destroy(false);
        }
    }
}
