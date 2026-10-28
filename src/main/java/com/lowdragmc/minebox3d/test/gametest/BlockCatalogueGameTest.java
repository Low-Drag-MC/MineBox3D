package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.CollisionCategories;
import com.lowdragmc.minebox3d.physics.CastHit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>Every kind of block shape, as Minecraft has it</b> (design doc {@code 01-voxel-terrain.md} §3.1): blocks whose
 * collision is several boxes, thinner than a block, taller than one, or offset by their position are streamed in, and
 * rays from every side and corner are cast at each against the grid and against Minecraft's own
 * {@link VoxelShape#clip}. They must hit the same point, or both miss.
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class BlockCatalogueGameTest {

    private static final int COLUMNS = 10, SPACING = 3, Y = 2;
    /** ray offsets across a face, never on a sixteenth of a block, out past the cell for shapes that reach out */
    private static final double[] SAMPLES = {-0.289, -0.078, 0.133, 0.344, 0.555, 0.766, 0.977, 1.188, 1.399};
    private static final double NEAR = -1.2, FAR = 2.2;
    private static final int OFFSET_COPIES = 3;

    private static List<BlockState> catalogue() {
        List<BlockState> states = new ArrayList<>();
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (Half half : Half.values()) {
                states.add(Blocks.STONE_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                        .setValue(BlockStateProperties.HALF, half));
            }
        }
        states.add(Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));
        states.add(Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
        // posts standing alone; fences and walls 1.5 high
        for (Block block : new Block[]{Blocks.OAK_FENCE, Blocks.COBBLESTONE_WALL, Blocks.IRON_BARS, Blocks.GLASS_PANE,
                Blocks.OAK_FENCE_GATE, Blocks.CHORUS_PLANT}) {
            states.add(block.defaultBlockState());
        }
        for (Direction.Axis axis : Direction.Axis.values()) {
            states.add(Blocks.CHAIN.defaultBlockState().setValue(BlockStateProperties.AXIS, axis));
        }
        // several boxes
        for (Block block : new Block[]{Blocks.CAULDRON, Blocks.HOPPER, Blocks.LECTERN, Blocks.ANVIL, Blocks.BREWING_STAND,
                Blocks.ENCHANTING_TABLE, Blocks.CAMPFIRE, Blocks.BELL, Blocks.STONECUTTER, Blocks.CHEST, Blocks.CONDUIT,
                Blocks.FLOWER_POT, Blocks.DECORATED_POT, Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER, Blocks.HEAVY_CORE,
                Blocks.LIGHTNING_ROD, Blocks.AMETHYST_CLUSTER, Blocks.AZALEA, Blocks.SNIFFER_EGG, Blocks.SKELETON_SKULL,
                Blocks.DAYLIGHT_DETECTOR, Blocks.REPEATER, Blocks.BEEHIVE, Blocks.RED_BED}) {
            states.add(block.defaultBlockState());
        }
        states.add(Blocks.END_PORTAL_FRAME.defaultBlockState());
        states.add(Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(BlockStateProperties.EYE, true));
        states.add(Blocks.COMPOSTER.defaultBlockState());
        states.add(Blocks.COMPOSTER.defaultBlockState().setValue(BlockStateProperties.LEVEL_COMPOSTER, 8));
        states.add(Blocks.LANTERN.defaultBlockState());
        states.add(Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true));
        states.add(Blocks.GRINDSTONE.defaultBlockState());
        states.add(Blocks.GRINDSTONE.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR));
        states.add(Blocks.CAKE.defaultBlockState());
        states.add(Blocks.CAKE.defaultBlockState().setValue(BlockStateProperties.BITES, 6));
        for (int layers = 1; layers <= 8; layers++) {
            states.add(Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, layers));
        }
        // lower than a block
        for (Block block : new Block[]{Blocks.SOUL_SAND, Blocks.FARMLAND, Blocks.DIRT_PATH, Blocks.HONEY_BLOCK, Blocks.MUD,
                Blocks.WHITE_CARPET, Blocks.LILY_PAD}) {
            states.add(block.defaultBlockState());
        }
        states.add(Blocks.SEA_PICKLE.defaultBlockState().setValue(BlockStateProperties.PICKLES, 4));
        states.add(Blocks.TURTLE_EGG.defaultBlockState().setValue(BlockStateProperties.EGGS, 4));
        states.add(Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 4));
        // thin, and turned
        states.add(Blocks.OAK_TRAPDOOR.defaultBlockState());
        states.add(Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP));
        states.add(Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true));
        states.add(Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        states.add(Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true));
        states.add(Blocks.LADDER.defaultBlockState());
        states.add(Blocks.END_ROD.defaultBlockState());
        states.add(Blocks.END_ROD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
        states.add(Blocks.BIG_DRIPLEAF.defaultBlockState());
        // offset by their position: read at the block, not per state. Three of each, at three places, so a collider cached
        // for the first would put the others' in the wrong place
        for (int i = 0; i < OFFSET_COPIES; i++) {
            states.add(Blocks.BAMBOO.defaultBlockState());
            states.add(Blocks.POINTED_DRIPSTONE.defaultBlockState());
        }
        // no collision for an entity with no context
        states.add(Blocks.SCAFFOLDING.defaultBlockState());
        states.add(Blocks.POWDER_SNOW.defaultBlockState());
        return states;
    }

    @GameTest(template = "empty", batch = "minebox3d_catalogue", timeoutTicks = 20)
    @PrefixGameTestTemplate(false)
    public static void everyBlockShapeIsHitWhereMinecraftHitsIt(GameTestHelper helper) {
        List<BlockState> states = catalogue();
        int rows = (states.size() + COLUMNS - 1) / COLUMNS;
        TestArea.clear(helper, -2, 0, -2, COLUMNS * SPACING, Y + 4, rows * SPACING);
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) {
            BlockPos pos = new BlockPos(SPACING * (i % COLUMNS), Y, SPACING * (i / COLUMNS));
            helper.setBlock(pos, states.get(i));
            positions.add(helper.absolutePos(pos));
        }
        for (Block offset : new Block[]{Blocks.BAMBOO, Blocks.POINTED_DRIPSTONE}) {
            List<Vec3> offsets = new ArrayList<>();
            for (BlockPos pos : positions) {
                BlockState state = helper.getLevel().getBlockState(pos);
                if (state.is(offset)) {
                    offsets.add(state.getOffset(helper.getLevel(), pos));
                }
            }
            helper.assertTrue(offsets.size() == OFFSET_COPIES && offsets.stream().distinct().count() > 1,
                    offset + " sits at the same offset everywhere: nothing would tell a per-state cache apart, " + offsets);
        }

        StringBuilder report = new StringBuilder("\n=== block catalogue: rays that disagree with VoxelShape.clip ===\n");
        int rays = 0;
        List<String> wrong = new ArrayList<>();
        try (PrivatePhysics physics = new PrivatePhysics(helper, 0)) {
            BlockPos min = helper.absolutePos(new BlockPos(-2, 0, -2));
            BlockPos max = helper.absolutePos(new BlockPos(COLUMNS * SPACING, Y + 4, rows * SPACING));
            physics.terrain.request(new AABB(Vec3.atLowerCornerOf(min), Vec3.atLowerCornerOf(max)), 5);
            physics.terrain.update();
            physics.terrain.update();
            for (BlockPos pos : positions) {
                BlockState state = helper.getLevel().getBlockState(pos);
                VoxelShape shape = state.getCollisionShape(helper.getLevel(), pos);
                int disagreements = 0;
                String first = null;
                for (double[] ray : rays()) {
                    rays++;
                    Vec3 from = new Vec3(pos.getX() + ray[0], pos.getY() + ray[1], pos.getZ() + ray[2]);
                    Vec3 to = from.add(ray[3], ray[4], ray[5]);
                    String problem = compare(physics, shape, pos, from, to);
                    if (problem != null) {
                        disagreements++;
                        if (first == null) {
                            first = problem;
                        }
                    }
                }
                if (disagreements > 0) {
                    String line = String.format(Locale.ROOT, "  %s: %d rays, first %s", state, disagreements, first);
                    report.append(line).append('\n');
                    wrong.add(line);
                }
            }
        } finally {
            TestArea.clear(helper, -2, 0, -2, COLUMNS * SPACING, Y + 4, rows * SPACING);
        }
        report.append(String.format(Locale.ROOT, "  %d states, %d rays, %d states disagree%n", states.size(), rays, wrong.size()));
        MineBox3D.LOGGER.info(report.toString());
        helper.assertTrue(wrong.isEmpty(), wrong.size() + " block shapes differ from Minecraft's: " + wrong);
        helper.succeed();
    }

    /** {@code [ox, oy, oz, tx, ty, tz]} relative to the block's corner: straight in from all six sides, and from the corners. */
    private static List<double[]> rays() {
        List<double[]> rays = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                for (double u : SAMPLES) {
                    for (double v : SAMPLES) {
                        double[] ray = new double[6];
                        ray[axis] = sign > 0 ? NEAR : FAR;
                        ray[(axis + 1) % 3] = u;
                        ray[(axis + 2) % 3] = v;
                        ray[3 + axis] = sign * (FAR - NEAR);
                        rays.add(ray);
                    }
                }
            }
        }
        double[] aims = {-0.17, 0.13};
        for (int corner = 0; corner < 8; corner++) {
            double cx = (corner & 1) == 0 ? NEAR : FAR, cy = (corner & 2) == 0 ? NEAR : FAR, cz = (corner & 4) == 0 ? NEAR : FAR;
            for (double ax : aims) {
                for (double ay : aims) {
                    for (double az : aims) {
                        rays.add(new double[]{cx, cy, cz, 2 * (0.5 + ax - cx), 2 * (0.6 + ay - cy), 2 * (0.5 + az - cz)});
                    }
                }
            }
        }
        return rays;
    }

    @Nullable
    private static String compare(PrivatePhysics physics, VoxelShape shape, BlockPos pos, Vec3 from, Vec3 to) {
        BlockHitResult expected = shape.isEmpty() ? null : shape.clip(from, to, pos);
        Vec3 d = to.subtract(from);
        CastHit actual = physics.world.castRayClosest(from.x, from.y, from.z, (float) d.x, (float) d.y, (float) d.z, -1L,
                CollisionCategories.TERRAIN);
        if (expected == null && actual == null) {
            return null;
        }
        Vec3 relative = from.subtract(Vec3.atLowerCornerOf(pos));
        String ray = String.format(Locale.ROOT, "from (%.3f, %.3f, %.3f) along (%.2f, %.2f, %.2f)", relative.x, relative.y,
                relative.z, d.x, d.y, d.z);
        if (expected == null) {
            return ray + ": Minecraft misses, the grid hits at " + point(actual.x(), actual.y(), actual.z(), pos);
        }
        Vec3 e = expected.getLocation();
        if (actual == null) {
            return ray + ": the grid misses, Minecraft hits at " + point(e.x, e.y, e.z, pos);
        }
        double error = Math.sqrt((e.x - actual.x()) * (e.x - actual.x()) + (e.y - actual.y()) * (e.y - actual.y())
                + (e.z - actual.z()) * (e.z - actual.z()));
        return error < 2e-3 ? null : ray + ": Minecraft hits at " + point(e.x, e.y, e.z, pos) + ", the grid at "
                + point(actual.x(), actual.y(), actual.z(), pos);
    }

    private static String point(double x, double y, double z, BlockPos pos) {
        return String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)", x - pos.getX(), y - pos.getY(), z - pos.getZ());
    }
}
