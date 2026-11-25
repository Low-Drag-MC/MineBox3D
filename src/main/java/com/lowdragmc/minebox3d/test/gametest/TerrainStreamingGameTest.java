package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.terrain.TerrainMaterials;
import com.lowdragmc.minebox3d.mc.terrain.TerrainStreamer;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.DistanceJointDef;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.Joint;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.ThreadMode;
import com.lowdragmc.minebox3d.physics.WorldDef;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>The terrain streamer's own work</b> (design doc {@code 01-voxel-terrain.md} §8): what reaches the grid when a
 * taller block changes or a block changes on another thread, fast and falling bodies finding ground they were not
 * near, sections coming and going, bodies parked over a chunk that goes, private worlds stepped on a pool, the surfaces
 * Minecraft's blocks give, and a physics that stops when its tick throws. Each test runs a world of its own for
 * seconds in one tick ({@link PrivatePhysics}).
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class TerrainStreamingGameTest {

    // ---- block changes that reach past their cell -------------------------------------------------

    @GameTest(template = "empty", batch = "minebox3d_fence_removed", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void aFenceTakenAwayDropsWhatRestedOnItsTop(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.OAK_FENCE);
        try (PrivatePhysics physics = new PrivatePhysics(helper)) {
            Body box = physics.box(1.5, 5, 1.5, 0.1f);
            physics.ticks(60);
            physics.near(2.6, physics.relative(box).y, 0.02, "on the post's top, in the cell above the fence");
            helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
            physics.ticks(60);
            // only the fence's own cell worked out again would leave the post's top half in the cell above
            physics.near(0.1, physics.relative(box).y, 0.02, "fell to the ground");
        }
        helper.succeed();
    }

    /** A block changed on another thread reaches the grid at the next update, on the level's thread. */
    @GameTest(template = "empty", batch = "minebox3d_off_thread_change", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void aBlockChangedOnAnotherThreadIsTakenInAtTheNextUpdate(GameTestHelper helper) throws Exception {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        BlockPos stone = new BlockPos(1, 1, 1);
        helper.setBlock(stone, Blocks.STONE);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (PrivatePhysics physics = new PrivatePhysics(helper)) {
            Body box = physics.box(1.5, 4, 1.5, 0.25f);
            physics.ticks(40);
            physics.near(2.25, physics.relative(box).y, 0.02, "on the stone");
            // taken out of the chunk's section behind the level's back, then told from another thread
            BlockPos at = helper.absolutePos(stone);
            LevelChunk chunk = helper.getLevel().getChunkAt(at);
            chunk.getSection(chunk.getSectionIndex(at.getY()))
                    .setBlockState(at.getX() & 15, at.getY() & 15, at.getZ() & 15, Blocks.AIR.defaultBlockState());
            pool.submit(() -> physics.terrain.onBlockChanged(
                            at, Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState()))
                    .get(10, TimeUnit.SECONDS);
            box.setAwake(true);
            physics.ticks(40);
            physics.near(0.25, physics.relative(box).y, 0.02, "the stone gone from the grid: down on the floor");
        } finally {
            pool.shutdown();
            helper.setBlock(stone, Blocks.AIR);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_stop_on_error", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void aPhysicsWhoseTickThrowsStopsThere(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        try (PrivatePhysics physics = new PrivatePhysics(helper)) {
            physics.box(1.5, 4, 1.5, 0.25f);
            physics.terrain.grid().close(); // the next section it reads into the grid throws
            physics.physics.tick();
            helper.assertTrue(physics.physics.isStopped(), "stopped");
            long ticks = physics.physics.ticks();
            physics.physics.tick();
            helper.assertTrue(physics.physics.ticks() == ticks, "and not ticked again");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_gate_opened", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void anOpenedGateDropsWhatRestedOnIt(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        BlockPos gate = new BlockPos(1, 1, 1);
        helper.setBlock(gate, Blocks.OAK_FENCE_GATE);
        try (PrivatePhysics physics = new PrivatePhysics(helper)) {
            Body box = physics.box(1.5, 5, 1.5, 0.1f);
            physics.ticks(60);
            physics.near(2.6, physics.relative(box).y, 0.02, "on the closed gate's top");
            helper.setBlock(gate, helper.getBlockState(gate).setValue(FenceGateBlock.OPEN, true));
            physics.ticks(60);
            physics.near(0.1, physics.relative(box).y, 0.02, "an open gate holds nothing up");
        }
        helper.succeed();
    }

    // ---- ground a body was not near yet ------------------------------------------------------------

    @GameTest(template = "empty", batch = "minebox3d_fast", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void aFastBodyStopsAtAWallSectionsAhead(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 40, 5, 4);
        for (int y = 0; y <= 3; y++) {
            for (int z = 0; z <= 3; z++) {
                helper.setBlock(new BlockPos(36, y, z), Blocks.STONE);
            }
        }
        try (PrivatePhysics physics = new PrivatePhysics(helper, 0)) {
            // 35 blocks ahead: at least two section borders away, none of it loaded when it sets off
            Body ball = physics.body(new Geometry.Sphere(0, 0, 0, 0.25f), new ShapeDef(), 1.5, 1.5, 1.5);
            ball.setLinearVelocity(40, 0, 0);
            physics.ticks(30);
            double x = physics.relative(ball).x;
            helper.assertTrue(x > 30 && x < 36, "stopped at the wall, x = " + x);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_drop", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void aBodyDroppedFromHighLandsOnASlab(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 6);
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(
                        new BlockPos(x, 0, z),
                        Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
            }
        }
        try (PrivatePhysics physics = new PrivatePhysics(helper)) {
            // about 60 m/s at the bottom: three blocks a tick, through four sections. Landing that hard with friction,
            // Box3D skids it sideways by up to a block (on any ground: doc 01 §12.6.4), hence the 5 × 5 slabs
            Body box = physics.box(2.5, 60, 2.5, 0.2f);
            physics.ticks(80);
            Vector3d at = physics.relative(box);
            helper.assertTrue(at.x > 0 && at.x < 5 && at.z > 0 && at.z < 5, "landed on the slabs: " + at);
            physics.near(0.7, at.y, 0.02, "on a slab");
        }
        helper.succeed();
    }

    // ---- sections coming and going -----------------------------------------------------------------

    @GameTest(template = "empty", batch = "minebox3d_housekeeping", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void sectionsComeWhenWantedAndGoWhenNot(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        BlockPos high = new BlockPos(1, 40, 1);
        try (PrivatePhysics physics = new PrivatePhysics(helper)) {
            physics.terrain.settings().keepTicks(5);
            long under = SectionPos.asLong(helper.absolutePos(new BlockPos(1, 0, 1)));

            // a body that says it needs no terrain gets none
            Body box = physics.box(1.5, 0.5, 1.5, 0.25f);
            LevelBodies.setNeedsTerrain(box, false);
            physics.terrain.update();
            helper.assertFalse(physics.terrain.isLoaded(under), "no terrain wanted, none loaded");
            LevelBodies.setNeedsTerrain(box, null);
            physics.terrain.update();
            helper.assertTrue(
                    physics.terrain.isLoaded(under), "back to the default for a dynamic body: the section under it");

            // gone keepTicks updates after the last body that wanted it
            box.destroy();
            for (int i = 0; i < 5; i++) {
                physics.terrain.update();
            }
            helper.assertTrue(physics.terrain.isLoaded(under), "kept for keepTicks");
            physics.terrain.update();
            helper.assertFalse(physics.terrain.isLoaded(under), "and then dropped");

            // a request holds a section of nothing but air while it lasts; a block placed into it reaches the grid
            BlockPos at = helper.absolutePos(high);
            long airSection = SectionPos.asLong(at);
            physics.terrain.request(new AABB(at), 3);
            physics.terrain.update();
            helper.assertTrue(physics.terrain.isLoaded(airSection), "requested");
            helper.assertTrue(physics.terrain.grid().cell(at.getX(), at.getY(), at.getZ()) == 0, "air");
            helper.setBlock(high, Blocks.STONE);
            physics.terrain.update();
            helper.assertTrue(
                    physics.terrain.grid().cell(at.getX(), at.getY(), at.getZ()) != 0,
                    "the stone placed into the empty section is in the grid");
            for (int i = 0; i < 3 + 5; i++) {
                physics.terrain.update();
            }
            helper.assertFalse(physics.terrain.isLoaded(airSection), "the request ran out, and keepTicks after it");
        } finally {
            helper.setBlock(high, Blocks.AIR);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_parking", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void aBodyOverAChunkThatGoesWaitsForItToComeBack(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        try (PrivatePhysics physics = new PrivatePhysics(helper)) {
            Body box = physics.box(1.5, 4, 1.5, 0.25f);
            physics.ticks(40);
            double resting = physics.relative(box).y;
            physics.near(2.25, resting, 0.02, "on the stone");
            box.setAwake(true);

            BlockPos stone = helper.absolutePos(new BlockPos(1, 1, 1));
            physics.terrain.onChunkUnloaded(new ChunkPos(stone));
            helper.assertTrue(physics.terrain.isParked(box) && !box.isEnabled(), "parked before its ground went");
            helper.assertFalse(
                    physics.terrain.isLoaded(SectionPos.asLong(stone)), "the chunk's sections left the grid");
            for (int i = 0; i < 60; i++) {
                physics.world.step(1 / 60f, 4);
            }
            physics.near(resting, physics.relative(box).y, 1e-9, "a parked body does not fall");

            // the chunk never really went: the next update finds it there, lets the body go and reads its ground again
            physics.ticks(20);
            helper.assertTrue(!physics.terrain.isParked(box) && box.isEnabled(), "back in the simulation");
            physics.near(2.25, physics.relative(box).y, 0.02, "on the stone again");
        }
        helper.succeed();
    }

    /**
     * A body over a chunk that is not there takes what is jointed to it out with it (half a cart left simulating would
     * fall away from the other half); one that stops needing terrain is let back; and one its maker turned off is still
     * off when let back.
     */
    @GameTest(template = "empty", batch = "minebox3d_parking_group", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void whatIsJointedToAParkedBodyWaitsWithItAndAMakersSwitchIsKept(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        try (PrivatePhysics physics = new PrivatePhysics(helper)) {
            Body near = physics.box(1.5, 2.5, 1.5, 0.25f);
            Body far = physics.box(3000.5, 80, 1.5, 0.25f); // over a chunk nobody loaded
            Joint rope = physics.world.createJoint(
                    new DistanceJointDef().bodies(near, far).length(2999));
            physics.ticks(10);
            helper.assertTrue(
                    physics.terrain.isParked(far) && physics.terrain.isParked(near) && !near.isEnabled(),
                    "the pair waits for the far one's chunk");
            physics.near(2.5, physics.relative(near).y, 1e-9, "the near one did not fall");

            rope.destroy(false);
            physics.ticks(20);
            helper.assertTrue(!physics.terrain.isParked(near) && near.isEnabled(), "let go of, the near one goes on");
            physics.near(2.25, physics.relative(near).y, 0.02, "onto the stone");
            helper.assertTrue(physics.terrain.isParked(far), "the far one still waits");

            LevelBodies.setNeedsTerrain(far, false);
            physics.ticks(1);
            helper.assertTrue(!physics.terrain.isParked(far) && far.isEnabled(), "needing no terrain, it is let back");
            far.destroy();

            near.setEnabled(false);
            physics.terrain.onChunkUnloaded(new ChunkPos(helper.absolutePos(new BlockPos(1, 1, 1))));
            helper.assertTrue(physics.terrain.isParked(near), "parked as its chunk goes");
            physics.ticks(2);
            helper.assertTrue(
                    !physics.terrain.isParked(near) && !near.isEnabled(), "let back, and off as its maker left it");
        }
        helper.succeed();
    }

    /**
     * A private world in {@link ThreadMode#EXCLUSIVE} mode, the way a Rigid Body node runs one: terrain prepared on the
     * level's thread, stepped on a pool. A chunk event while the pool holds the world waits for the next prepare.
     */
    @GameTest(template = "empty", batch = "minebox3d_exclusive", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void aPrivateWorldSteppedOnAPoolTakesChunkEventsAtItsNextPrepare(GameTestHelper helper)
            throws Exception {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        BlockPos stone = helper.absolutePos(new BlockPos(1, 1, 1));
        LevelPhysics physics = LevelPhysics.create(
                helper.getLevel(),
                new WorldDef().gravity(0, LevelPhysics.GRAVITY, 0).threadMode(ThreadMode.EXCLUSIVE));
        helper.assertTrue(physics != null, "Box3D is available");
        PhysicsWorld world = physics.world();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Body box;
            try (PhysicsWorld.Use use = world.use()) {
                Vec3 at = helper.absoluteVec(new Vec3(1.5, 4, 1.5));
                box = world.createBody(new BodyDef().type(BodyType.DYNAMIC).position(at.x, at.y, at.z));
                box.createShape(new ShapeDef(), Geometry.Box.of(0.25f, 0.25f, 0.25f));
            }
            for (int frame = 0; frame < 40; frame++) {
                try (PhysicsWorld.Use use = world.use()) {
                    physics.prepare();
                }
                pool.submit(() -> {
                            try (PhysicsWorld.Use use = world.use()) {
                                physics.step();
                            }
                        })
                        .get(10, TimeUnit.SECONDS);
            }
            try (PhysicsWorld.Use use = world.use()) {
                double y = box.position(new Vector3d()).y
                        - helper.absolutePos(BlockPos.ZERO).getY();
                helper.assertTrue(Math.abs(y - 2.25) < 0.02, "stepped on the pool onto the stone: y = " + y);
            }

            // the world is free and the chunk goes: the event does not take it (a worker could be about to), it waits
            long section = SectionPos.asLong(stone);
            physics.terrain().onChunkUnloaded(new ChunkPos(stone));
            helper.assertTrue(
                    physics.terrain().isLoaded(section), "a free exclusive world is not taken by a chunk event");
            helper.assertTrue(world.owner() == null, "and nobody holds it");

            // the terrain is read on the level's thread only, holding the world or not
            Future<?> offThread = pool.submit(() -> {
                try (PhysicsWorld.Use use = world.use()) {
                    physics.terrain().update();
                }
            });
            try {
                offThread.get(10, TimeUnit.SECONDS);
                helper.fail("the terrain updated off the level's thread");
            } catch (ExecutionException e) {
                helper.assertTrue(e.getCause() instanceof WrongThreadException, "refused: " + e.getCause());
            }

            // the pool holds the world while the chunk goes: nothing is touched now, it waits
            CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
            Future<?> holder = pool.submit(() -> {
                try (PhysicsWorld.Use use = world.use()) {
                    holding.countDown();
                    release.await(10, TimeUnit.SECONDS);
                }
                return null;
            });
            holding.await(10, TimeUnit.SECONDS);
            physics.terrain().onChunkUnloaded(new ChunkPos(stone));
            helper.assertTrue(physics.terrain().isLoaded(section), "not taken out while another thread steps");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            // taken in at the next prepare: out, and (the chunk never really went) straight back in
            try (PhysicsWorld.Use use = world.use()) {
                physics.prepare();
            }
            helper.assertTrue(physics.terrain().loadedLastTick() >= 1, "the section was read again");
        } finally {
            pool.shutdown();
            physics.close();
        }
        helper.succeed();
    }

    /**
     * Closing a private physics whose world another thread holds fails — and leaves nothing of it listening to the
     * level: its terrain hears no more chunks. Closed again once the world is free, it closes.
     */
    @GameTest(template = "empty", batch = "minebox3d_close_held", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void aPhysicsClosedWhileAnotherThreadHoldsItStopsListeningAnyway(GameTestHelper helper)
            throws Exception {
        LevelPhysics physics = LevelPhysics.create(
                helper.getLevel(),
                new WorldDef().gravity(0, LevelPhysics.GRAVITY, 0).threadMode(ThreadMode.EXCLUSIVE));
        helper.assertTrue(physics != null, "Box3D is available");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> {
                try (PhysicsWorld.Use use = physics.world().use()) {
                    holding.countDown();
                    release.await(10, TimeUnit.SECONDS);
                }
                return null;
            });
            holding.await(10, TimeUnit.SECONDS);
            try {
                physics.close();
                helper.fail("closed a world another thread holds");
            } catch (WrongThreadException expected) {
                // the world is the other thread's
            }
            boolean[] listening = {false};
            TerrainStreamer.forLevel(helper.getLevel(), streamer -> listening[0] |= streamer == physics.terrain());
            helper.assertFalse(listening[0], "its terrain no longer hears the level");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            physics.close();
            helper.assertFalse(physics.world().isOpen(), "closed the second time");
        } finally {
            release.countDown();
            pool.shutdown();
        }
        helper.succeed();
    }

    // ---- surfaces ------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = "minebox3d_ice", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void aBoxSlidesFurtherOnIceThanOnStone(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 24, 4, 5);
        for (int x = 0; x < 24; x++) {
            helper.setBlock(new BlockPos(x, 0, 1), Blocks.ICE);
            helper.setBlock(new BlockPos(x, 0, 3), Blocks.STONE);
        }
        try (PrivatePhysics physics = new PrivatePhysics(helper)) {
            Body onIce = physics.box(1.5, 1.3, 1.5, 0.25f);
            Body onStone = physics.box(1.5, 1.3, 3.5, 0.25f);
            physics.ticks(20);
            onIce.setLinearVelocity(8, 0, 0);
            onStone.setLinearVelocity(8, 0, 0);
            physics.ticks(60);
            Vector3d ice = physics.relative(onIce), stone = physics.relative(onStone);
            // friction 0.6 against stone's 0.6 and ice's 0.03 (Minecraft's slipperiness 0.98): about 1.7 m and 7 m
            helper.assertTrue(
                    stone.x - 1.5 > 1 && stone.x - 1.5 < 2.5, "slid about 1.7 m on stone: " + (stone.x - 1.5));
            helper.assertTrue(ice.x - 1.5 > 5, "slid much further on ice: " + (ice.x - 1.5));
        }
        helper.succeed();
    }

    static {
        // a conveyor no test but the one below stands on; otherwise the same surface as stone (friction 0.6, no bounce)
        TerrainMaterials.register(Blocks.LODESTONE, (level, pos, state, out) -> out.tangentVelocityX = 3);
    }

    @GameTest(template = "empty", batch = "minebox3d_conveyor", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void aProvidersConveyorIsNotSharedWithStoneThatFeelsTheSame(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 18, 4, 5);
        for (int x = 0; x < 16; x++) {
            helper.setBlock(new BlockPos(x, 0, 1), Blocks.LODESTONE);
            helper.setBlock(new BlockPos(x, 0, 3), Blocks.STONE);
        }
        try (PrivatePhysics physics = new PrivatePhysics(helper)) {
            Body onLodestone = physics.box(2.5, 1.26, 1.5, 0.25f);
            Body onStone = physics.box(2.5, 1.26, 3.5, 0.25f);
            physics.ticks(30);
            // materials that differ only in their tangent velocity are two materials, whichever the section met first
            double carried = physics.relative(onLodestone).x - 2.5;
            helper.assertTrue(carried > 2, "carried along +x by the lodestone: " + carried);
            physics.near(2.5, physics.relative(onStone).x, 0.01, "the stone carries nothing");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_slime", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void aBoxBouncesOnSlimeAndNotOnStone(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.SLIME_BLOCK);
        helper.setBlock(new BlockPos(1, 0, 3), Blocks.STONE);
        try (PrivatePhysics physics = new PrivatePhysics(helper)) {
            Body onSlime = physics.box(1.5, 4, 1.5, 0.25f);
            Body onStone = physics.box(1.5, 4, 3.5, 0.25f);
            // the first time it goes up, and how high it gets before it comes down again; a bounce is over within a
            // tick, so it is told by the velocity, not by a height it was seen at
            double[] peak = {0, 0};
            int[] phase = {0, 0};
            Body[] bodies = {onSlime, onStone};
            Vector3f v = new Vector3f();
            for (int i = 0; i < 40; i++) {
                physics.ticks(1);
                for (int b = 0; b < 2; b++) {
                    float vy = bodies[b].linearVelocity(v).y;
                    if (phase[b] == 0 && vy < -1) {
                        phase[b] = 1;
                    } else if (phase[b] == 1 && vy >= -1) {
                        phase[b] = 2;
                    }
                    if (phase[b] == 2 && vy >= 0) {
                        peak[b] = Math.max(peak[b], physics.relative(bodies[b]).y);
                    } else if (phase[b] == 2) {
                        phase[b] = 3;
                    }
                }
            }
            helper.assertTrue(phase[0] >= 2 && phase[1] >= 2, "both came down");
            // restitution 0.8 from the slime's provider: back up to about two thirds of the 2.75 m it fell
            helper.assertTrue(peak[0] > 2.5, "bounced on slime, up to " + peak[0]);
            helper.assertTrue(peak[1] < 1.4, "stayed down on stone, up to " + peak[1]);
        }
        helper.succeed();
    }
}
