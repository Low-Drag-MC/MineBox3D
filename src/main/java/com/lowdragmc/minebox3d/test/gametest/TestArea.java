package com.lowdragmc.minebox3d.test.gametest;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;

/**
 * ⚠️ A GameTest structure is walled in barriers (StructureUtils.encaseStructure: the walls always, the
 * ceiling unless skyAccess), one block outside its bounds. Barriers have collision, so terrain streaming
 * picks them up like any block: a test that builds beyond the 1×1×1 "empty" structure clears its area
 * first. Each test that clears runs in a batch of its own, so the walls it removes guard nothing else.
 */
final class TestArea {

    /** each running test's forced area, relative: {x0, z0, x1, z1} */
    private static final Map<GameTestHelper, int[]> FORCED = Collections.synchronizedMap(new WeakHashMap<>());

    private TestArea() {}

    /**
     * ⚠️ Entities beyond the structure's own chunk may not tick at all: the runner keeps only that chunk going, and
     * where the area crosses into the next chunk depends on where the run places the test. A test whose entities must
     * move forces its area's chunks ({@code forced = true}), waits for them ({@link #ticks}) and lets them go after.
     */
    static void forceChunks(GameTestHelper helper, int x0, int z0, int x1, int z1, boolean forced) {
        BlockPos from = helper.absolutePos(new BlockPos(x0, 0, z0)), to = helper.absolutePos(new BlockPos(x1, 0, z1));
        for (int cx = Math.min(from.getX(), to.getX()) >> 4; cx <= Math.max(from.getX(), to.getX()) >> 4; cx++) {
            for (int cz = Math.min(from.getZ(), to.getZ()) >> 4; cz <= Math.max(from.getZ(), to.getZ()) >> 4; cz++) {
                helper.getLevel().setChunkForced(cx, cz, forced);
            }
        }
        if (forced) {
            FORCED.put(helper, new int[] {x0, z0, x1, z1});
        } else {
            FORCED.remove(helper);
        }
    }

    /**
     * Whether the entities have ticked, and every chunk the test forced ticks entities: a forced chunk takes a while to
     * come up (far out, with the runner ticking flat out, hundreds of ticks), and an entity that walks into one still
     * coming up stops there.
     */
    static boolean ticks(GameTestHelper helper, Entity... entities) {
        for (Entity entity : entities) {
            if (entity.tickCount < 2) {
                return false;
            }
        }
        int[] area = FORCED.get(helper);
        if (area == null) {
            return true;
        }
        BlockPos from = helper.absolutePos(new BlockPos(area[0], 0, area[1]));
        BlockPos to = helper.absolutePos(new BlockPos(area[2], 0, area[3]));
        for (int cx = Math.min(from.getX(), to.getX()) >> 4; cx <= Math.max(from.getX(), to.getX()) >> 4; cx++) {
            for (int cz = Math.min(from.getZ(), to.getZ()) >> 4; cz <= Math.max(from.getZ(), to.getZ()) >> 4; cz++) {
                if (!helper.getLevel().isPositionEntityTicking(new BlockPos(cx << 4, 0, cz << 4))) {
                    return false;
                }
            }
        }
        return true;
    }

    static void clear(GameTestHelper helper, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }
}
