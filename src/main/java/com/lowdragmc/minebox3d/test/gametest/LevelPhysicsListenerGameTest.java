package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>A mod's own part of each physics tick</b>: a tick listener is told before the steps and after them, once a tick — a
 * falling body has not moved in the first and has in the second — and one that throws is taken off while the physics, and
 * the other listeners, go on.
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class LevelPhysicsListenerGameTest {

    @GameTest(template = "empty", batch = "minebox3d_tick_listeners", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aListenerIsToldOnEitherSideOfTheStepsAndOneThatThrowsIsTakenOff(GameTestHelper helper) {
        LevelPhysics physics = LevelPhysics.of(helper.getLevel());
        if (physics == null) {
            helper.fail("Box3D is not available: physics could not start");
            return;
        }
        // high over the test, so nothing is under it while the test runs
        Vec3 at = helper.absoluteVec(new Vec3(0.5, 80, 0.5));
        Body ball = physics.createBody(new BodyDef().type(BodyType.DYNAMIC).position(at.x, at.y, at.z));
        ball.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 0.25f));
        LevelBodies.setNeedsTerrain(ball, false);
        List<String> heard = new ArrayList<>();
        double[] before = new double[1];
        boolean[] movedBetween = {true};
        int[] brokenCalls = new int[1];
        LevelPhysics.TickListener told = new LevelPhysics.TickListener() {
            @Override
            public void beforeSteps(LevelPhysics p) {
                heard.add("before");
                before[0] = ball.position(new Vector3d()).y;
            }

            @Override
            public void afterSteps(LevelPhysics p) {
                heard.add("after");
                movedBetween[0] &= ball.position(new Vector3d()).y < before[0];
            }
        };
        LevelPhysics.TickListener broken = new LevelPhysics.TickListener() {
            @Override
            public void beforeSteps(LevelPhysics p) {
                brokenCalls[0]++;
                throw new IllegalStateException("thrown on purpose by a test");
            }
        };
        physics.addTickListener(broken);
        physics.addTickListener(told);
        helper.runAfterDelay(10, () -> {
            try {
                helper.assertTrue(heard.size() >= 10, "told every tick: " + heard);
                for (int i = 0; i < heard.size(); i++) {
                    helper.assertTrue(heard.get(i).equals(i % 2 == 0 ? "before" : "after"), "before, then after: " + heard);
                }
                helper.assertTrue(movedBetween[0], "the falling body moved between the two, not before the first");
                helper.assertTrue(brokenCalls[0] == 1, "the one that threw was taken off after it threw: " + brokenCalls[0]);
                helper.assertFalse(physics.isStopped(), "and the physics went on");
            } finally {
                physics.removeTickListener(told);
                physics.removeTickListener(broken);
                ball.destroy();
            }
            helper.succeed();
        });
    }
}
