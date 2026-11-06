package com.lowdragmc.minebox3d.client;

import com.lowdragmc.minebox3d.internal.B3;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/** Client-only helpers around {@link LevelPhysics}. */
final class ClientPhysics {

    private ClientPhysics() {
    }

    static void unloadAll() {
        for (LevelPhysics physics : LevelPhysics.all()) {
            if (physics.level().isClientSide) {
                LevelPhysics.unloadClient(physics.level());
            }
        }
    }

    static String stats() {
        if (!NativeLibrary.isLoaded()) {
            return "Box3D is not available: " + NativeLibrary.failure();
        }
        Minecraft minecraft = Minecraft.getInstance();
        LevelPhysics physics = minecraft.level == null ? null : LevelPhysics.existing(minecraft.level);
        if (physics == null) {
            return "no physics in this level yet (spawn something)";
        }
        int[] counters = new int[6];
        float stepMillis = physics.world().counters(counters);
        return String.format(Locale.ROOT,
                "bodies %d (awake %d), shapes %d, contacts %d | terrain sections %d (%d with collision), colliders %d | "
                        + "entity proxies %d | tick %.2f ms (last step %.2f ms) | Box3D memory %d KB, worlds %d",
                counters[0], physics.world().awakeBodyCount(), counters[1], counters[2],
                physics.terrain().sectionCount(), physics.terrain().grid().sectionCount(),
                physics.terrain().colliders().colliderCount(), physics.entities().proxyCount(),
                physics.lastTickMillis(), stepMillis, B3.getByteCount() / 1024, B3.getWorldCount());
    }
}
