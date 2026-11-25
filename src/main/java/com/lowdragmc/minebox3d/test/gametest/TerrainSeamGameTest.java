package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.terrain.TerrainStreamer;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.WorldDef;
import java.util.Locale;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>Real blocks, no seams</b> (design doc {@code 01-voxel-terrain.md} §9 V3): bodies slid across the blocks a
 * world is made of — full and partial, across cell and section borders — are not kicked up by the joins.
 *
 * <p>The six floors §9 V3 names, and a farmland floor (15/16 high). Every run streams the real blocks
 * through {@link TerrainStreamer} into a private world and steps it at 60 Hz × 4 sub-steps, sampling every step
 * (the level's own physics only steps per tick, which would hide a kick that lasts one step). Logs a table.
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class TerrainSeamGameTest {

    private static final int LENGTH = 26;
    /** The vertical speed that counts as being kicked up, m/s. */
    private static final float KICK = 0.05f;

    private record Scene(String name, float topY, float laneZ, IntFunction<BlockState> blockAtX) {}

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState TOP_SLAB =
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
    private static final BlockState BOTTOM_SLAB =
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
    /** upper half on the north (−z) side: its top is 1 × 0.5 at y = 1, beside a stone's 1 × 1 */
    private static final BlockState STAIRS = Blocks.STONE_STAIRS
            .defaultBlockState()
            .setValue(StairBlock.FACING, Direction.NORTH)
            .setValue(StairBlock.HALF, Half.BOTTOM);
    /** upside down: top is a full square at y = 1, made of two boxes */
    private static final BlockState UPSIDE_DOWN_STAIRS = Blocks.STONE_STAIRS
            .defaultBlockState()
            .setValue(StairBlock.FACING, Direction.EAST)
            .setValue(StairBlock.HALF, Half.TOP);

    private static final BlockState FARMLAND = Blocks.FARMLAND.defaultBlockState();

    private static final Scene[] SCENES = {
        new Scene("stone", 1, 2.5f, x -> STONE),
        new Scene("stone|top slab", 1, 2.5f, x -> x % 2 == 0 ? STONE : TOP_SLAB),
        new Scene("stone|stairs tread", 1, 2.25f, x -> (x / 2) % 2 == 0 ? STONE : STAIRS),
        new Scene("stone|upside-down stairs", 1, 2.5f, x -> x % 3 == 0 ? UPSIDE_DOWN_STAIRS : STONE),
        new Scene("bottom slabs", 0.5f, 2.5f, x -> BOTTOM_SLAB),
        new Scene("slab|stairs low step", 0.5f, 2.75f, x -> (x / 2) % 2 == 0 ? BOTTOM_SLAB : STAIRS),
        new Scene("farmland", 15 / 16f, 2.5f, x -> FARMLAND),
    };

    // every one fits on a stair's upper half (0.5 wide) with room to spare: a body hanging over the drop beside it
    // passes within a centimetre of real corners, where Box3D's speculative contact lifts it a little (doc 01 §12.4)
    private static final Geometry[] BODIES = {
        Geometry.Box.of(0.2f, 0.2f, 0.2f),
        new Geometry.Capsule(-0.3f, 0, 0, 0.3f, 0, 0, 0.15f),
        new Geometry.Capsule(0, 0, -0.1f, 0, 0, 0.1f, 0.1f),
        new Geometry.Sphere(0, 0, 0, 0.2f)
    };
    private static final String[] BODY_NAMES = {"box", "capsule along", "capsule across", "sphere"};
    private static final float[] SPEEDS = {2, 6, 12};

    /**
     * The angular speed that counts as being turned aside, rad/s; the runs stay under 0.01. Where section borders fall
     * depends on where the server places the test, so a turn at one (a box once turned 40°, doc 01 §12.6.2) shows only
     * in some runs: {@code VoxelDetailTest} tries every alignment.
     */
    private static final float TURN = 0.1f;

    /** {@code exitVx} is the speed along x at the end of the run, 0 if it never got there. */
    private record Result(float maxVy, double atX, float maxW, boolean fell, float exitVx) {}

    @GameTest(template = "empty", batch = "minebox3d_seams", timeoutTicks = 40)
    @PrefixGameTestTemplate(false)
    public static void slidingOverBlocksDoesNotKick(GameTestHelper helper) {
        StringBuilder report = new StringBuilder("\n=== terrain seams: max |vy| m/s, max |w| rad/s ===\n");
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        report.append(String.format(
                Locale.ROOT,
                "  run x from %d to %d (section borders where x %% 16 == 0)%n",
                origin.getX(),
                origin.getX() + LENGTH - 1));
        float worstVy = 0, worstW = 0;
        String worst = "", fell = "", slowed = "";
        for (Scene scene : SCENES) {
            TestArea.clear(helper, -1, 0, -1, LENGTH + 1, 4, 6);
            for (int x = 0; x < LENGTH; x++) {
                for (int z = 0; z < 5; z++) {
                    helper.setBlock(new BlockPos(x, 1, z), scene.blockAtX.apply(x));
                }
            }
            report.append("  ").append(scene.name).append('\n');
            for (int b = 0; b < BODIES.length; b++) {
                for (float speed : SPEEDS) {
                    Result result = slide(helper, scene, BODIES[b], speed);
                    report.append(String.format(
                            Locale.ROOT,
                            "    %-15s %4.0f m/s  vy %.3f (at x %.2f)  w %.3f  out at %.2f%s%n",
                            BODY_NAMES[b],
                            speed,
                            result.maxVy,
                            result.atX,
                            result.maxW,
                            result.exitVx,
                            result.fell ? "  FELL" : ""));
                    if (result.maxVy > worstVy) {
                        worstVy = result.maxVy;
                        worst = scene.name + ", " + BODY_NAMES[b] + " at " + speed + " m/s";
                    }
                    worstW = Math.max(worstW, result.maxW);
                    if (result.fell) {
                        fell = scene.name + ", " + BODY_NAMES[b] + " at " + speed + " m/s";
                    } else if (result.exitVx < 0.95f * speed) {
                        // frictionless: a seam that catches it without lifting it still takes speed off
                        slowed = scene.name + ", " + BODY_NAMES[b] + " at " + speed + " m/s, out at " + result.exitVx;
                    }
                }
            }
        }
        report.append(String.format(Locale.ROOT, "  worst: vy %.3f (%s), w %.3f%n", worstVy, worst, worstW));
        MineBox3D.LOGGER.info(report.toString());
        helper.assertTrue(fell.isEmpty(), "fell off the floor: " + fell);
        helper.assertTrue(slowed.isEmpty(), "did not get across at speed: " + slowed);
        helper.assertTrue(worstVy < KICK, "kicked up at " + worstVy + " m/s: " + worst);
        helper.assertTrue(worstW < TURN, "turned at " + worstW + " rad/s");
        helper.succeed();
    }

    private static Result slide(GameTestHelper helper, Scene scene, Geometry geometry, float speed) {
        float rest =
                switch (geometry) {
                    case Geometry.Box box -> box.hy();
                    case Geometry.Sphere sphere -> sphere.radius();
                    case Geometry.Capsule capsule -> capsule.radius();
                    default -> 0.5f;
                };
        Vec3 start = helper.absoluteVec(new Vec3(1.5, 1 + scene.topY + rest + 0.01, scene.laneZ));
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, -32, 0));
                TerrainStreamer terrain = new TerrainStreamer(helper.getLevel(), world)) {
            Body slider = world.createBody(new BodyDef()
                    .type(BodyType.DYNAMIC)
                    .position(start.x, start.y, start.z)
                    .enableSleep(false));
            // frictionless on its side, so the geometric mean with the block's friction is 0 too
            slider.createShape(new ShapeDef().friction(0).density(500), geometry);
            for (int i = 0; i < 30; i++) {
                if (i % 3 == 0) {
                    terrain.update();
                }
                world.step(1 / 60f, 4);
            }
            slider.setLinearVelocity(speed, 0, 0);
            float maxVy = 0, maxW = 0, exitVx = 0;
            double atX = 0;
            boolean fell = false;
            Vector3d p = new Vector3d();
            Vector3f v = new Vector3f(), w = new Vector3f();
            for (int i = 0; i < 900; i++) {
                if (i % 3 == 0) {
                    terrain.update();
                }
                world.step(1 / 60f, 4);
                slider.position(p);
                double x = p.x - origin.getX();
                if (p.y - origin.getY() < 1 + scene.topY - 0.3) {
                    fell = true;
                    break;
                }
                if (x > 2.5 && x < LENGTH - 2.5) {
                    float vy = Math.abs(slider.linearVelocity(v).y);
                    if (vy > maxVy) {
                        maxVy = vy;
                        atX = x;
                    }
                    maxW = Math.max(maxW, slider.angularVelocity(w).length());
                }
                if (x > LENGTH - 2.5) {
                    exitVx = slider.linearVelocity(v).x;
                    break;
                }
            }
            return new Result(maxVy, atX, maxW, fell, exitVx);
        }
    }
}
