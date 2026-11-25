package com.lowdragmc.minebox3d.physics;

import static org.junit.jupiter.api.Assertions.*;

import com.lowdragmc.minebox3d.internal.B3;
import java.util.List;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * <b>A world's second round of API</b> (design doc {@code 03-threading-and-api.md} §6.4 item 4): explosions, the
 * settings that can change after creation, and where a step's time went.
 */
class WorldSettingsTest {

    private static Body cube(PhysicsWorld world, double x, double y, double z, long category) {
        Body cube = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(x, y, z));
        cube.createShape(new ShapeDef().filter(category, -1L, 0), Geometry.Box.of(0.25f, 0.25f, 0.25f));
        return cube;
    }

    @Test
    void anExplosionPushesWhatIsInReachOutwardAndLeavesTheRest() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, 0, 0))) {
            Body east = cube(world, 2, 0, 0, 1);
            Body above = cube(world, 0, 2, 0, 1);
            Body edge = cube(world, -3.5, 0, 0, 1);
            Body masked = cube(world, 0, 0, 2, 2);
            Body beyond = cube(world, -10, 0, 0, 1);
            // a 125 kg cube showing a quarter square metre: 500 N·s/m² is 1 m/s
            world.explode(0, 0, 0, 3, 1, 500, 1);
            Vector3f v = new Vector3f();

            east.linearVelocity(v);
            assertEquals(1, v.x, 0.05, "pushed away from the blast: " + v);
            assertEquals(0, v.y, 1e-4);
            assertEquals(0, v.z, 1e-4);
            assertTrue(above.linearVelocity(v).y > 0.1f, "and upwards above it: " + v);
            float full = east.linearVelocity(v).x;
            float faded = -edge.linearVelocity(v).x;
            assertTrue(faded > 0 && faded < full, "in the falloff, less: " + faded + " < " + full);
            assertEquals(0, masked.linearVelocity(v).length(), 1e-6, "not in the mask");
            assertEquals(0, beyond.linearVelocity(v).length(), 1e-6, "out of reach");

            // the velocity each body reports is the one Box3D has now, though no step ran
            float[] direct = new float[3];
            B3.bodyGetLinearVelocity(above.id(), direct);
            assertEquals(direct[1], above.linearVelocity(v).y, 1e-6);
        }
    }

    @Test
    void anImplosionPullsInwards() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, 0, 0))) {
            Body east = cube(world, 2, 0, 0, 1);
            world.explode(0, 0, 0, 3, 0, -500);
            assertTrue(east.linearVelocity(new Vector3f()).x < -0.1f);
        }
    }

    @Test
    void settingsReadBackWhatWasSet() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef())) {
            world.setRestitutionThreshold(2.5f);
            assertEquals(2.5f, world.restitutionThreshold());
            world.setHitEventThreshold(0.75f);
            assertEquals(0.75f, world.hitEventThreshold());
            world.setMaximumLinearSpeed(120);
            assertEquals(120, world.maximumLinearSpeed());
            world.enableSleeping(false);
            assertFalse(world.isSleepingEnabled());
            world.enableContinuous(false);
            assertFalse(world.isContinuousEnabled());
            world.setContactTuning(20, 5, 2);

            List<Executable> refused = List.of(
                    () -> world.setRestitutionThreshold(-1),
                    () -> world.setHitEventThreshold(Float.NaN),
                    () -> world.setMaximumLinearSpeed(0),
                    () -> world.setContactTuning(0, 1, 1),
                    () -> world.explode(0, Double.NaN, 0, 1, 1, 1),
                    () -> world.explode(0, 0, 0, -1, 1, 1));
            for (Executable call : refused) {
                assertThrows(IllegalArgumentException.class, call);
            }
        }
    }

    @Test
    void aSpeedCapHoldsBodiesUnderIt() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, -100, 0))) {
            world.setMaximumLinearSpeed(5);
            Body falling = cube(world, 0, 100, 0, 1);
            for (int i = 0; i < 60; i++) {
                world.step(1 / 60f, 4);
            }
            assertEquals(-5, falling.linearVelocity(new Vector3f()).y, 0.01);
        }
    }

    @Test
    void theProfileSaysWhereTheStepWent() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, -10, 0))) {
            for (int i = 0; i < 50; i++) {
                cube(world, i % 10, 1 + i / 10, 0, 1);
            }
            world.step(1 / 60f, 4);
            Profile profile = world.profile(new Profile());
            assertTrue(profile.step > 0, "a step took time");
            assertTrue(profile.solve > 0 && profile.solve <= profile.step, "solving is part of it");
            assertEquals(profile.step, world.counters(new int[6]), 1e-6, "the same step the counters time");
        }
    }
}
