package com.lowdragmc.minebox3d;

import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.mc.entity.EntityShapes;
import com.lowdragmc.minebox3d.mc.sync.SyncedBodies;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MineBox3D.MOD_ID)
public final class MineBox3D {
    public static final String MOD_ID = "minebox3d";
    public static final Logger LOGGER = LoggerFactory.getLogger("MineBox3D");

    public MineBox3D(IEventBus modBus) {
        if (NativeLibrary.tryLoad()) {
            int[] version = new int[3];
            B3.getVersion(version);
            LOGGER.info("Box3D {}.{}.{} loaded for {} (double precision, up to {} worlds)",
                    version[0], version[1], version[2], NativeLibrary.platform(), B3.getMaxWorlds());
        } else {
            LOGGER.error("Box3D is unavailable on {}: physics is disabled", NativeLibrary.platform(), NativeLibrary.failure());
        }
        modBus.addListener(SyncedBodies::registerPayloads);
        EntityShapes.registerAttachments(modBus);
    }
}
