package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.terrain.TerrainStreamer;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.WorldDef;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * A world and a terrain streamer of a test's own on the test's level, stepped by the test: whole seconds of physics in
 * one game tick, nothing else in the world. The streamer hears the level's block and chunk changes like the level's
 * own one does.
 */
final class PrivatePhysics implements AutoCloseable {

    final GameTestHelper helper;
    final PhysicsWorld world;
    final TerrainStreamer terrain;

    PrivatePhysics(GameTestHelper helper) {
        this(helper, LevelPhysics.GRAVITY);
    }

    PrivatePhysics(GameTestHelper helper, float gravity) {
        this.helper = helper;
        this.world = PhysicsWorld.create(new WorldDef().gravity(0, gravity, 0));
        this.terrain = new TerrainStreamer(helper.getLevel(), world);
    }

    /** {@code n} Minecraft ticks the way the level's physics runs them: the streamer, then three steps. */
    void ticks(int n) {
        for (int i = 0; i < n; i++) {
            terrain.update();
            for (int s = 0; s < 3; s++) {
                world.step(1 / 60f, 4);
            }
        }
    }

    /** A dynamic body at a point relative to the test's origin. */
    Body body(Geometry geometry, ShapeDef def, double x, double y, double z) {
        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        Body body = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(at.x, at.y, at.z));
        body.createShape(def, geometry);
        return body;
    }

    Body box(double x, double y, double z, float half) {
        return body(Geometry.Box.of(half, half, half), new ShapeDef(), x, y, z);
    }

    /** The body's position relative to the test's origin. */
    Vector3d relative(Body body) {
        Vector3d p = body.position(new Vector3d());
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return p.sub(origin.getX(), origin.getY(), origin.getZ());
    }

    void near(double expected, double actual, double tolerance, String what) {
        if (Math.abs(expected - actual) > tolerance) {
            helper.fail(what + ": expected " + expected + " ± " + tolerance + ", got " + actual);
        }
    }

    @Override
    public void close() {
        try {
            terrain.close();
        } finally {
            world.close();
        }
    }
}
