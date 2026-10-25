package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.jni.B3;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.WorldDef;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>The library inside a running server</b> — the one place the loader's two Minecraft-specific
 * decisions are tested (see {@link NativeLibrary}): the resource read through Minecraft's module
 * layers, and the native methods binding to a library loaded from the mod's own class.
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class NativeLoadGameTest {

    @GameTest(template = "empty")
    @PrefixGameTestTemplate(false)
    public static void theLibraryLoadsFromTheModsOwnResources(GameTestHelper helper) {
        if (!NativeLibrary.tryLoad()) {
            helper.fail("the native library did not load: " + NativeLibrary.failure());
            return;
        }
        // the GameTest run deliberately sets no minebox3d.native.path (build.gradle)
        helper.assertTrue(NativeLibrary.loadedFromJar(),
                "loaded from " + NativeLibrary.loadedFrom() + " rather than the mod's resources");
        helper.assertTrue(B3.isDoublePrecision(), "double precision");
        helper.succeed();
    }

    @GameTest(template = "empty")
    @PrefixGameTestTemplate(false)
    public static void aWorldStepsOnTheServerThread(GameTestHelper helper) {
        NativeLibrary.load();
        int world = B3.createWorld(new WorldDef().gravity(0, -10, 0));
        try {
            long ground = B3.createBody(world, new BodyDef());
            B3.createBoxShape(ground, new ShapeDef(), 5, 0.5f, 5, 0, 0, 0, 0, 0, 0, 1);
            long ball = B3.createBody(world, new BodyDef().type(BodyType.DYNAMIC).position(0, 3, 0));
            B3.createSphereShape(ball, new ShapeDef(), 0, 0, 0, 0.25f);
            for (int i = 0; i < 180; i++) {
                B3.worldStep(world, 1 / 60f, 4);
            }
            double[] transform = new double[7];
            B3.bodyGetTransform(ball, transform);
            helper.assertTrue(Math.abs(transform[1] - 0.75) < 0.02, "rests on the ground, y = " + transform[1]);
        } finally {
            B3.destroyWorld(world);
        }
        helper.succeed();
    }
}
