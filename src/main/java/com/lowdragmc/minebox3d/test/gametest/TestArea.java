package com.lowdragmc.minebox3d.test.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/**
 * ⚠️ A GameTest structure is walled in barriers (StructureUtils.encaseStructure: the walls always, the
 * ceiling unless skyAccess), one block outside its bounds. Barriers have collision, so terrain streaming
 * picks them up like any block: a test that builds beyond the 1×1×1 "empty" structure clears its area
 * first. Every physics test runs in a batch of its own, so the walls it removes guard nothing else.
 */
final class TestArea {

    private TestArea() {
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
