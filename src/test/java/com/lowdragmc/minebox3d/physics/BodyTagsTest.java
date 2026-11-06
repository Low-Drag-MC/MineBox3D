package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.natives.NativeLibrary;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Body tags</b> ({@link Body#setTag}, {@link Body#setIgnoredTag}): a body never meets the bodies tagged with what it
 * ignores — no contact, and a box swept as one of them passes through it — and meets everything else as before.
 * Without Minecraft.
 */
class BodyTagsTest {

    private static final long FLOOR = 7;
    private static final Quaternionf NO_TURN = new Quaternionf();

    @BeforeAll
    static void load() {
        NativeLibrary.load();
    }

    private static PhysicsWorld world() {
        return PhysicsWorld.create(new WorldDef().gravity(0, -10, 0));
    }

    /** A static floor tagged {@link #FLOOR}, its top at y = 0. */
    private static Body floor(PhysicsWorld world) {
        Body floor = world.createBody(new BodyDef().position(0, -0.5, 0));
        floor.createShape(new ShapeDef(), Geometry.Box.of(5, 0.5f, 5));
        floor.setTag(FLOOR);
        return floor;
    }

    /** A dynamic crate whose shape asks the custom filter, its middle at y. */
    private static Body crate(PhysicsWorld world, double x, double y) {
        Body crate = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(x, y, 0));
        ShapeDef def = new ShapeDef();
        def.enableCustomFiltering = true;
        crate.createShape(def, Geometry.Box.of(0.25f, 0.25f, 0.25f));
        return crate;
    }

    private static void steps(PhysicsWorld world, int count) {
        for (int i = 0; i < count; i++) {
            world.beginInterpolationFrame();
            world.step(1 / 60f, 4);
        }
    }

    private static double y(Body body) {
        return body.position(new Vector3d()).y;
    }

    @Test
    void aCrateFallsThroughTheTagItIgnoresAndLandsOnEverythingElse() {
        try (PhysicsWorld world = world()) {
            Body floor = floor(world);
            Body ignoring = crate(world, -1, 1);
            ignoring.setIgnoredTag(FLOOR);
            Body other = crate(world, 1, 1);
            other.setIgnoredTag(FLOOR + 1);
            steps(world, 90);
            assertTrue(y(ignoring) < -1, "through the floor it ignores: y = " + y(ignoring));
            assertEquals(0.25, y(other), 0.02, "one ignoring another tag lands");
            assertEquals(FLOOR, floor.tag());
            assertEquals(FLOOR, ignoring.ignoredTag());
        }
    }

    @Test
    void ignoringWhatItRestsOnLetsGoAndIgnoringNothingCatchesAgain() {
        try (PhysicsWorld world = world()) {
            floor(world);
            Body crate = crate(world, 0, 1);
            steps(world, 60);
            assertEquals(0.25, y(crate), 0.02, "resting on the floor");
            crate.setIgnoredTag(FLOOR);
            steps(world, 15);
            double sinking = y(crate);
            assertTrue(sinking < 0.1, "let go where it rests: y = " + sinking);
            // above the floor again before it is caught: a body caught deep inside it is pushed out wherever
            crate.setTransform(0, 1, 0, NO_TURN);
            crate.setLinearVelocity(0, 0, 0);
            crate.setIgnoredTag(0);
            steps(world, 60);
            assertEquals(0.25, y(crate), 0.02, "ignoring nothing, it lands again");
        }
    }

    @Test
    void eitherOfThePairMayBeTheOneIgnoring() {
        try (PhysicsWorld world = world()) {
            Body floor = floor(world);
            // the floor's shape does not ask the filter; the crate's does, and the floor ignores the crate
            Body crate = crate(world, 0, 1);
            crate.setTag(3);
            floor.setIgnoredTag(3);
            steps(world, 90);
            assertTrue(y(crate) < -1, "the floor ignores it: through it, y = " + y(crate));
        }
    }

    @Test
    void aBoxSweptAsTheTagPassesThroughWhatIgnoresIt() {
        try (PhysicsWorld world = world()) {
            Body wall = world.createBody(new BodyDef().position(2.25, 1, 0));
            wall.createShape(new ShapeDef(), Geometry.Box.of(0.25f, 1, 1)); // its face at x = 2
            wall.setIgnoredTag(FLOOR);
            BoxSweep sweep = new BoxSweep();
            assertTrue(world.sweepBox(0, 1, 0, NO_TURN, 0.25f, 0.25f, 0.25f, 4, 0, 0, QueryFilter.ALL, false, null, 0L,
                    sweep), "a box that is nothing meets it");
            assertTrue(world.sweepBox(0, 1, 0, NO_TURN, 0.25f, 0.25f, 0.25f, 4, 0, 0, QueryFilter.ALL, false, null,
                    FLOOR + 1, sweep), "and one tagged otherwise");
            assertFalse(world.sweepBox(0, 1, 0, NO_TURN, 0.25f, 0.25f, 0.25f, 4, 0, 0, QueryFilter.ALL, false, null, FLOOR,
                    sweep), "one tagged what it ignores goes through");
            wall.setIgnoredTag(0);
            assertTrue(world.sweepBox(0, 1, 0, NO_TURN, 0.25f, 0.25f, 0.25f, 4, 0, 0, QueryFilter.ALL, false, null, FLOOR,
                    sweep), "until it ignores nothing");
        }
    }

    @Test
    void aDestroyedBodysTagsDoNotStayWithItsPlace() {
        try (PhysicsWorld world = world()) {
            floor(world);
            Body ignoring = crate(world, 0, 1);
            ignoring.setIgnoredTag(FLOOR);
            ignoring.destroy();
            // most likely in the place the destroyed one left: none of its tags
            Body next = crate(world, 0, 1);
            assertEquals(0, next.ignoredTag());
            steps(world, 60);
            assertEquals(0.25, y(next), 0.02, "the next body lands on the floor");
        }
    }
}
