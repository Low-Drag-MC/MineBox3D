package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.terrain.TerrainStreamer;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

/**
 * <b>Terrain and entities streaming into the server level's physics</b> (design doc §4, §5).
 *
 * <p>The GameTest level is a superflat world: the grass under a test's origin has its top at the
 * origin's y. Every test is in a batch of its own so no two tests' bodies share a neighbourhood.
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class TerrainGameTest {

    private static LevelPhysics physics(GameTestHelper helper) {
        LevelPhysics physics = LevelPhysics.of(helper.getLevel());
        if (physics == null) {
            helper.fail("Box3D is not available: physics could not start");
        }
        return physics;
    }

    private static Body box(GameTestHelper helper, double x, double y, double z, float half) {
        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        Body body = physics(helper).createBody(new BodyDef().type(BodyType.DYNAMIC).position(at.x, at.y, at.z));
        body.createShape(new ShapeDef().density(500), Geometry.Box.of(half, half, half));
        return body;
    }

    /** The body's position relative to the test's origin. */
    private static Vector3d relative(GameTestHelper helper, Body body) {
        Vector3d p = body.position(new Vector3d());
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return p.sub(origin.getX(), origin.getY(), origin.getZ());
    }

    private static void near(GameTestHelper helper, double expected, double actual, double tolerance, String what) {
        if (Math.abs(expected - actual) > tolerance) {
            helper.fail(what + ": expected " + expected + " ± " + tolerance + ", got " + actual);
        }
    }

    @GameTest(template = "empty", batch = "minebox3d_land", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aBoxLandsOnABlockStreamedIn(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        Body body = box(helper, 1.5, 5, 1.5, 0.25f);
        helper.succeedWhen(() -> {
            // top of the stone is y = 2; the box's half extent 0.25
            near(helper, 2.25, relative(helper, body).y, 0.02, "resting height");
            helper.assertTrue(!body.isAwake(), "settled");
            long section = SectionPos.asLong(helper.absolutePos(new BlockPos(1, 1, 1)));
            helper.assertTrue(physics(helper).terrain().isLoaded(section), "the section under the box is in Box3D");
            body.destroy();
        });
    }

    @GameTest(template = "empty", batch = "minebox3d_remove", timeoutTicks = 200)
    @PrefixGameTestTemplate(false)
    public static void takingAwayTheBlockLetsTheBoxFall(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 3, 1), Blocks.STONE);
        Body body = box(helper, 1.5, 6, 1.5, 0.25f);
        boolean[] removed = {false};
        helper.onEachTick(() -> {
            if (!removed[0] && !body.isAwake()) {
                near(helper, 4.25, relative(helper, body).y, 0.02, "on the upper block first");
                helper.setBlock(new BlockPos(1, 3, 1), Blocks.AIR);
                removed[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(removed[0], "the upper block was taken away");
            // the box only falls if the block change reached the section's mesh
            near(helper, 2.25, relative(helper, body).y, 0.02, "fell onto the lower block");
            helper.assertTrue(!body.isAwake(), "settled again");
            body.destroy();
        });
    }

    @GameTest(template = "empty", batch = "minebox3d_slab", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aSlabHoldsTheBoxAtHalfABlock(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        Body body = box(helper, 1.5, 4, 1.5, 0.2f);
        helper.succeedWhen(() -> {
            near(helper, 1.7, relative(helper, body).y, 0.02, "resting on the slab's top at y = 1.5");
            helper.assertTrue(!body.isAwake(), "settled");
            body.destroy();
        });
    }

    @GameTest(template = "empty", batch = "minebox3d_cube", timeoutTicks = 40)
    @PrefixGameTestTemplate(false)
    public static void aSolidCubeShowsOnlyItsSurface(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        for (int x = 1; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                for (int z = 1; z <= 3; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        BlockPos min = helper.absolutePos(new BlockPos(1, 1, 1));
        TerrainStreamer terrain = physics(helper).terrain();
        terrain.request(new AABB(min.getX(), min.getY(), min.getZ(), min.getX() + 3, min.getY() + 3, min.getZ() + 3), 40);
        helper.succeedWhen(() -> {
            // the cube may straddle section borders: count over every section it touches
            int triangles = 0;
            float[] bounds = {0, 0, 0, 16, 16, 16};
            float[] v = new float[9 * 4096];
            byte[] flags = new byte[4096];
            for (int sx = SectionPos.blockToSectionCoord(min.getX()); sx <= SectionPos.blockToSectionCoord(min.getX() + 2); sx++) {
                for (int sy = SectionPos.blockToSectionCoord(min.getY()); sy <= SectionPos.blockToSectionCoord(min.getY() + 2); sy++) {
                    for (int sz = SectionPos.blockToSectionCoord(min.getZ()); sz <= SectionPos.blockToSectionCoord(min.getZ() + 2); sz++) {
                        helper.assertTrue(terrain.isLoaded(SectionPos.asLong(sx, sy, sz)), "the requested sections are in");
                        int count = terrain.grid().exposedTriangles(sx, sy, sz, bounds, v, flags);
                        helper.assertTrue(count <= 4096, "more triangles than the buffer: " + count);
                        float ox = SectionPos.sectionToBlockCoord(sx) - min.getX();
                        float oy = SectionPos.sectionToBlockCoord(sy) - min.getY();
                        float oz = SectionPos.sectionToBlockCoord(sz) - min.getZ();
                        for (int t = 0; t < Math.min(count, 4096); t++) {
                            float cx = (v[9 * t] + v[9 * t + 3] + v[9 * t + 6]) / 3 + ox;
                            float cy = (v[9 * t + 1] + v[9 * t + 4] + v[9 * t + 7]) / 3 + oy;
                            float cz = (v[9 * t + 2] + v[9 * t + 5] + v[9 * t + 8]) / 3 + oz;
                            if (cx >= -0.01f && cx <= 3.01f && cy >= -0.01f && cy <= 3.01f && cz >= -0.01f && cz <= 3.01f) {
                                triangles++;
                            }
                        }
                    }
                }
            }
            // six sides of 3×3 squares, two triangles each (the cube floats a block above the grass), and
            // nothing between the 27 blocks inside it
            helper.assertTrue(triangles == 6 * 9 * 2, "expected 108 surface triangles, got " + triangles);
        });
    }

    @GameTest(template = "empty", batch = "minebox3d_fence", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aFenceIsOneAndAHalfBlocksTall(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.OAK_FENCE);
        // the fence post is 1.5 high: its top half is in the cell above, which is air
        Body body = box(helper, 1.5, 5, 1.5, 0.1f);
        helper.succeedWhen(() -> {
            near(helper, 2.6, relative(helper, body).y, 0.02, "resting on the post's top at y = 2.5");
            helper.assertTrue(!body.isAwake(), "settled");
            body.destroy();
        });
    }

    @GameTest(template = "empty", batch = "minebox3d_push", timeoutTicks = 120)
    @PrefixGameTestTemplate(false)
    public static void aMovingEntityPushesABody(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        Body body = box(helper, 2.5, 0.25, 1.5, 0.25f);
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new Vec3(0.5, 0, 1.5));
        stand.setNoGravity(true);
        int[] ticks = {0};
        helper.onEachTick(() -> {
            if (ticks[0] < 25) {
                Vec3 at = helper.absoluteVec(new Vec3(0.5 + 0.1 * ++ticks[0], 0.02, 1.5));
                stand.setPos(at);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(ticks[0] >= 25, "the stand has finished moving");
            helper.assertTrue(physics(helper).entities().proxyOf(stand) != null, "the stand has a proxy");
            double x = relative(helper, body).x;
            // the stand's front ends at 3.0 + 0.25; the box's back must be past it, and it slides only a little further
            // once the stand stops (2 m/s against friction 0.6: about 0.1 m)
            helper.assertTrue(x > 3.45 && x < 3.9, "the box was pushed ahead of the stand, x = " + x);
            body.destroy();
            stand.discard();
        });
    }
}
