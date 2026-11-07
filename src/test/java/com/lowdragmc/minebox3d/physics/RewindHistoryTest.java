package com.lowdragmc.minebox3d.physics;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Putting bodies back and simulating again</b> ({@link RewindHistory}): where nothing touches, the run again is the
 * first run to the bit; where contacts and joints play a part — they keep no history, as Unreal's do not — it stays within
 * a centimetre and a degree. Ticks of three steps of four sub-steps, as a level's physics takes them.
 */
class RewindHistoryTest {

    private static final float GRAVITY = -32, STEP = 1 / 60f;
    private static final int STEPS_PER_TICK = 3, SUB_STEPS = 4;

    private static PhysicsWorld world() {
        return PhysicsWorld.create(new WorldDef().gravity(0, GRAVITY, 0));
    }

    private static void tick(PhysicsWorld world) {
        world.beginInterpolationFrame();
        for (int i = 0; i < STEPS_PER_TICK; i++) {
            world.step(STEP, SUB_STEPS);
        }
    }

    /** Ticks {@code from} to {@code to} − 1, each body recorded at the end of each. */
    private static void run(PhysicsWorld world, RewindHistory history, List<Body> bodies, long from, long to) {
        for (long t = from; t < to; t++) {
            tick(world);
            for (Body body : bodies) {
                history.record(t, body);
            }
        }
    }

    private static Body box(PhysicsWorld world, double x, double y, double z, float half) {
        Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(x, y, z));
        body.createShape(new ShapeDef().density(100), Geometry.Box.of(half, half, half));
        return body;
    }

    /** Each body's place, turn and velocities now. */
    private static List<float[]> statesOf(List<Body> bodies, List<Vector3d> places) {
        places.clear();
        List<float[]> states = new ArrayList<>();
        for (Body body : bodies) {
            places.add(body.position(new Vector3d()));
            Quaternionf q = body.rotation(new Quaternionf());
            Vector3f v = body.linearVelocity(new Vector3f());
            Vector3f w = body.angularVelocity(new Vector3f());
            states.add(new float[]{q.x, q.y, q.z, q.w, v.x, v.y, v.z, w.x, w.y, w.z});
        }
        return states;
    }

    private static float degreesBetween(Quaternionf a, Quaternionf b) {
        float dot = Math.min(1, Math.abs(a.dot(b)));
        return (float) Math.toDegrees(2 * Math.acos(dot));
    }

    @Test
    void freeBodiesRunAgainToTheBit() {
        try (PhysicsWorld world = world()) {
            List<Body> bodies = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Body body = box(world, i * 3, 10, 0, 0.3f + 0.1f * i);
                body.setLinearVelocity(2 - i, 8 + i, 1.5f * i);
                body.setAngularVelocity(1 + i, -2, 0.5f * i);
                bodies.add(body);
            }
            RewindHistory history = new RewindHistory(40);
            run(world, history, bodies, 0, 30);
            List<Vector3d> first = new ArrayList<>();
            List<float[]> firstStates = statesOf(bodies, first);

            for (Body body : bodies) {
                assertTrue(history.restore(9, body), "tick 9 is held");
            }
            run(world, history, bodies, 10, 30);
            List<Vector3d> again = new ArrayList<>();
            List<float[]> againStates = statesOf(bodies, again);
            for (int i = 0; i < bodies.size(); i++) {
                assertEquals(first.get(i), again.get(i), "body " + i + " where it was the first time, to the bit");
                for (int k = 0; k < firstStates.get(i).length; k++) {
                    assertEquals(firstStates.get(i)[k], againStates.get(i)[k], 0f,
                            "body " + i + " turned and going as the first time, to the bit (" + k + ")");
                }
            }
        }
    }

    @Test
    void bodiesTouchingRunAgainCloseBy() {
        try (PhysicsWorld world = world()) {
            Body ground = world.createBody(new BodyDef().position(0, -0.5, 0));
            ground.createShape(new ShapeDef(), Geometry.Box.of(30, 0.5f, 30));
            Body slope = world.createBody(new BodyDef().position(8, 1, 0).rotation(new Quaternionf().rotationZ(0.4f)));
            slope.createShape(new ShapeDef(), Geometry.Box.of(3, 0.2f, 1.5f));
            List<Body> bodies = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                bodies.add(box(world, 0, 0.45 + 0.9 * i, 0, 0.4f));
            }
            Body slider = box(world, 9, 3, 0, 0.3f);
            bodies.add(slider);
            Body ball = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(-4, 0.5, 0));
            ball.createShape(new ShapeDef().density(100), new Geometry.Sphere(0, 0, 0, 0.5f));
            ball.setLinearVelocity(3, 0, 1);
            bodies.add(ball);
            RewindHistory history = new RewindHistory(40);
            run(world, history, bodies, 0, 40);
            List<Vector3d> first = new ArrayList<>();
            statesOf(bodies, first);
            List<Quaternionf> firstTurns = bodies.stream().map(b -> b.rotation(new Quaternionf())).toList();

            for (Body body : bodies) {
                assertTrue(history.restore(19, body));
            }
            run(world, history, bodies, 20, 40);
            for (int i = 0; i < bodies.size(); i++) {
                Body body = bodies.get(i);
                double off = body.position(new Vector3d()).distance(first.get(i));
                float turned = degreesBetween(body.rotation(new Quaternionf()), firstTurns.get(i));
                assertTrue(off < 0.01, "body " + i + " within a centimetre of the first run: " + off);
                assertTrue(turned < 1, "and a degree: " + turned);
            }
        }
    }

    @Test
    void aJointChainRunsAgainCloseBy() {
        try (PhysicsWorld world = world()) {
            Body anchor = world.createBody(new BodyDef().position(0, 10, 0));
            List<Body> links = new ArrayList<>();
            Body previous = anchor;
            for (int i = 0; i < 4; i++) {
                Body link = box(world, 0.6 + 1.2 * i, 10, 0, 0.3f);
                world.createJoint(new SphericalJointDef().bodies(previous, link).worldFrame(1.2 * i, 10, 0));
                links.add(link);
                previous = link;
            }
            RewindHistory history = new RewindHistory(40);
            run(world, history, links, 0, 40);
            List<Vector3d> first = new ArrayList<>();
            statesOf(links, first);
            List<Quaternionf> firstTurns = links.stream().map(b -> b.rotation(new Quaternionf())).toList();

            // back a round trip's worth (six ticks): a swinging chain is chaotic, and the joints' warm starts that are not
            // held part the two runs by 2 cm over a second and a quarter
            for (Body link : links) {
                assertTrue(history.restore(33, link));
            }
            run(world, history, links, 34, 40);
            for (int i = 0; i < links.size(); i++) {
                Body link = links.get(i);
                double off = link.position(new Vector3d()).distance(first.get(i));
                float turned = degreesBetween(link.rotation(new Quaternionf()), firstTurns.get(i));
                assertTrue(off < 0.01, "link " + i + " within a centimetre of the first run: " + off);
                assertTrue(turned < 1, "and a degree: " + turned);
            }
        }
    }

    @Test
    void whatIsNotHeldIsNotRestored() {
        try (PhysicsWorld world = world()) {
            Body body = box(world, 0, 10, 0, 0.5f);
            RewindHistory history = new RewindHistory(5);
            run(world, history, List.of(body), 0, 10);
            assertTrue(history.has(7, body) && history.has(9, body), "the last five ticks are held");
            assertFalse(history.has(3, body), "older ones are let go by the ring");
            assertFalse(history.has(10, body), "and none is held before it is run");
            Vector3d before = body.position(new Vector3d());
            assertFalse(history.restore(3, body), "nothing to restore from");
            assertEquals(before, body.position(new Vector3d()), "and the body untouched");
            assertNull(history.position(3, body, new Vector3d()));
            assertNotNull(history.position(8, body, new Vector3d()));

            Body other = box(world, 5, 10, 0, 0.5f);
            assertFalse(history.has(9, other), "a body never recorded");
            history.record(9, other);
            other.destroy();
            history.prune();
            assertFalse(history.bodies().contains(other), "a destroyed body let go");
            assertTrue(history.bodies().contains(body));
        }
    }

    @Test
    void aRestoreIsPartOfTheFramesMotion() {
        try (PhysicsWorld world = world()) {
            Body body = box(world, 0, 10, 0, 0.5f);
            RewindHistory history = new RewindHistory(5);
            run(world, history, List.of(body), 0, 3);
            Vector3d held = body.position(new Vector3d());
            tick(world);
            Vector3d frameStart = body.renderPosition(0, new Vector3d());
            assertTrue(history.restore(2, body));
            assertEquals(frameStart, body.renderPosition(0, new Vector3d()), "drawn from where the frame started");
            assertEquals(held, body.renderPosition(1, new Vector3d()), "to where it was put back");
            world.beginInterpolationFrame();
            assertEquals(held, body.renderPosition(0, new Vector3d()), "and the next frame starts there");
        }
    }

    @Test
    void aSleepingBodyIsPutBackAsleep() {
        try (PhysicsWorld world = world()) {
            Body ground = world.createBody(new BodyDef().position(0, -0.5, 0));
            ground.createShape(new ShapeDef(), Geometry.Box.of(10, 0.5f, 10));
            Body body = box(world, 0, 0.5, 0, 0.5f);
            RewindHistory history = new RewindHistory(200);
            long t = 0;
            while (body.isAwake() && t < 200) {
                run(world, history, List.of(body), t, t + 1);
                t++;
            }
            assertFalse(body.isAwake(), "it came to rest");
            long asleep = t - 1;
            body.setLinearVelocity(4, 6, 0);
            run(world, history, List.of(body), t, t + 5);
            assertTrue(body.isAwake());
            assertTrue(history.restore(asleep, body));
            assertFalse(body.isAwake(), "put back asleep");
            assertEquals(0, body.linearVelocity(new Vector3f()).length(), 1e-6, "and still");
        }
    }
}
