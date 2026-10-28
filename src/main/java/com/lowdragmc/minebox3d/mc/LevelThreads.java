package com.lowdragmc.minebox3d.mc;

import net.minecraft.world.level.Level;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.common.util.LogicalSidedProvider;

/** Which thread a level belongs to: the server thread for server levels, the client thread for the client's. */
public final class LevelThreads {

    private LevelThreads() {
    }

    public static boolean isLevelThread(Level level) {
        return LogicalSidedProvider.WORKQUEUE.get(level.isClientSide ? LogicalSide.CLIENT : LogicalSide.SERVER).isSameThread();
    }
}
