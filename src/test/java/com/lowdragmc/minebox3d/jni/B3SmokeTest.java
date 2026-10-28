package com.lowdragmc.minebox3d.jni;

import com.lowdragmc.minebox3d.natives.NativeLibrary;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.WorldDef;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The bridge end to end: the library loads, a world steps, a body falls and lands. */
class B3SmokeTest {

    @BeforeAll
    static void load() {
        NativeLibrary.load();
    }

    /** The bindings no Java class calls yet: each once, with an effect that can be seen. */
    @Test
    void everyOtherBindingDoesWhatItSays() {
        int world = B3.createWorld(new WorldDef().gravity(0, -9, 0));
        try {
            float[] v = new float[3];
            B3.worldGetGravity(world, v);
            assertArrayEquals(new float[]{0, -9, 0}, v);
            B3.worldEnableContinuous(world, false); // no visible effect without a tunnelling setup; must not fail

            long body = B3.createBody(world, new BodyDef().type(BodyType.DYNAMIC));
            long shape = B3.createSphereShape(body, new ShapeDef(), 0, 0, 0, 0.5f);
            assertTrue(B3.bodyIsValid(body));
            assertTrue(B3.shapeIsValid(shape));
            assertEquals(body, B3.shapeGetBody(shape));
            assertEquals(BodyType.DYNAMIC.ordinal(), B3.bodyGetType(body));
            B3.shapeSetRestitution(shape, 0.5f);
            B3.bodySetBullet(body, true);

            B3.worldSetGravity(world, 0, 0, 0);
            B3.bodyApplyAngularImpulse(body, 0, 1, 0, true);
            B3.bodyGetAngularVelocity(body, v);
            assertTrue(v[1] > 0, "spins about +y");
            B3.bodySetAngularVelocity(body, 0, 0, 0);
            B3.bodyApplyTorque(body, 0, 0, 5, true);
            B3.worldStep(world, 1 / 60f, 4);
            B3.bodyGetAngularVelocity(body, v);
            assertTrue(v[2] > 0, "torque about +z");
            B3.bodySetAngularVelocity(body, 0, 0, 0);
            B3.bodyApplyForce(body, 10, 0, 0, 0, 0, 0, true);
            B3.worldStep(world, 1 / 60f, 4);
            B3.bodyGetLinearVelocity(body, v);
            assertTrue(v[0] > 0, "pushed along +x");

            // damping slows it
            B3.bodySetLinearVelocity(body, 5, 0, 0);
            B3.bodySetDamping(body, 5, 0);
            for (int i = 0; i < 30; i++) {
                B3.worldStep(world, 1 / 60f, 4);
            }
            B3.bodyGetLinearVelocity(body, v);
            assertTrue(v[0] < 2.5f, "damped: " + v[0]);

            // a body that may not sleep stays awake at rest
            B3.bodySetLinearVelocity(body, 0, 0, 0);
            B3.bodySetAngularVelocity(body, 0, 0, 0);
            B3.bodyEnableSleep(body, false);
            for (int i = 0; i < 200; i++) {
                B3.worldStep(world, 1 / 60f, 4);
            }
            assertTrue(B3.bodyIsAwake(body));

            B3.destroyBody(body);
            assertFalse(B3.bodyIsValid(body));
            assertFalse(B3.shapeIsValid(shape));
        } finally {
            B3.destroyWorld(world);
        }
    }

    @Test
    void theLibraryIsTheOneWeBuilt() {
        assertTrue(B3.isDoublePrecision(), "positions must be double for Minecraft coordinates");
        assertEquals(1024, B3.getMaxWorlds());
        int[] version = new int[3];
        B3.getVersion(version);
        assertTrue(version[0] > 0 || version[1] > 0, "a Box3D version");
    }

    @Test
    void aSphereFallsOntoABoxAndStops() {
        int world = B3.createWorld(new WorldDef().gravity(0, -10, 0));
        try {
            long ground = B3.createBody(world, new BodyDef());
            B3.createBoxShape(ground, new ShapeDef(), 10, 0.5f, 10, 0, 0, 0, 0, 0, 0, 1);

            long ball = B3.createBody(world, new BodyDef().type(BodyType.DYNAMIC).position(0, 5, 0));
            B3.createSphereShape(ball, new ShapeDef().density(1000), 0, 0, 0, 0.25f);
            assertTrue(B3.bodyGetMass(ball) > 0, "the shape gave the body mass");

            for (int i = 0; i < 240; i++) {
                B3.worldStep(world, 1 / 60f, 4);
            }
            double[] transform = new double[7];
            B3.bodyGetTransform(ball, transform);
            // ground top is y = 0.5, the ball's radius 0.25
            assertEquals(0.75, transform[1], 0.02, "the ball rests on the ground");
            assertEquals(0, transform[0], 1e-3);
            float[] velocity = new float[3];
            B3.bodyGetLinearVelocity(ball, velocity);
            assertEquals(0, velocity[1], 0.05);
        } finally {
            B3.destroyWorld(world);
        }
        assertFalse(B3.worldIsValid(world));
    }

    @Test
    void farFromTheOriginPositionsKeepTheirFraction() {
        int world = B3.createWorld(new WorldDef().gravity(0, 0, 0));
        try {
            double x = 29_999_000.317, z = -29_999_000.719;
            long body = B3.createBody(world, new BodyDef().type(BodyType.DYNAMIC).position(x, 64.5, z));
            B3.createSphereShape(body, new ShapeDef(), 0, 0, 0, 0.5f);
            B3.worldStep(world, 1 / 60f, 4);
            double[] transform = new double[7];
            B3.bodyGetTransform(body, transform);
            assertEquals(x, transform[0], 1e-6);
            assertEquals(z, transform[2], 1e-6);
        } finally {
            B3.destroyWorld(world);
        }
    }

    @Test
    void aFailedAssertionIsAnExceptionNotACrash() {
        int world = B3.createWorld(new WorldDef());
        try {
            long body = B3.createBody(world, new BodyDef().type(BodyType.DYNAMIC));
            // not a unit quaternion: Box3D asserts b3IsValidQuat
            Box3DException e = assertThrows(Box3DException.class,
                    () -> B3.bodySetTransform(body, 0, 0, 0, 0, 0, 0, 2));
            assertTrue(e.getMessage().contains("b3IsValidQuat"), e.getMessage());
            // and the next call on this thread is clean again
            B3.bodySetTransform(body, 0, 1, 0, 0, 0, 0, 1);
        } finally {
            B3.destroyWorld(world);
        }
    }

    @Test
    void manyBodiesReadInOneCall() {
        int world = B3.createWorld(new WorldDef().gravity(0, 0, 0));
        try {
            int n = 16;
            long[] ids = new long[n];
            for (int i = 0; i < n; i++) {
                ids[i] = B3.createBody(world, new BodyDef().type(BodyType.DYNAMIC).position(i, 2 * i, -i));
            }
            double[] out = new double[7 * n];
            B3.bodiesGetTransforms(ids, n, out);
            for (int i = 0; i < n; i++) {
                assertEquals(i, out[7 * i], 1e-9);
                assertEquals(2 * i, out[7 * i + 1], 1e-9);
                assertEquals(-i, out[7 * i + 2], 1e-9);
                assertEquals(1, out[7 * i + 6], 1e-6, "identity rotation");
            }
        } finally {
            B3.destroyWorld(world);
        }
    }
}
