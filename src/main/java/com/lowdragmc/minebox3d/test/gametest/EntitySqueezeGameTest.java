package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityBases;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.MassData;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>A body held fast is not squeezed out by an entity's proxy</b> (design doc {@code 04-entities-and-bodies.md} §18): an
 * armor stand put on into a crate a quarter block a tick by its position alone — as a client moves its player, and as a
 * server takes its word — against a wall, or down onto it on the ground. Run with the cap off first, to show the squeeze
 * happens here at all. And the force-capped proxy's life: capped only by bodies it meets, kinematic again once off them,
 * resized and re-made in place, put where its entity is when held off it, and leaving nothing behind.
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class EntitySqueezeGameTest {

    @GameTest(template = "empty", batch = "minebox3d_squeeze", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aCrateHeldAgainstAWallIsNotSqueezedOutByAnEntityPutIntoIt(GameTestHelper helper) {
        squeeze(helper, false);
    }

    @GameTest(template = "empty", batch = "minebox3d_squeeze_down", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aCrateOnTheGroundIsNotSqueezedOutByAnEntityPutDownOntoIt(GameTestHelper helper) {
        squeeze(helper, true);
    }

    @AfterBatch(batch = "minebox3d_squeeze")
    public static void strengthBackAfterTheWall(ServerLevel level) {
        strengthBack(level);
    }

    @AfterBatch(batch = "minebox3d_squeeze_down")
    public static void strengthBackAfterTheGround(ServerLevel level) {
        strengthBack(level);
    }

    /** The squeeze tests turn the level's cap off for a round: on again for the batches after, failed or not. */
    private static void strengthBack(ServerLevel level) {
        LevelPhysics physics = LevelPhysics.of(level);
        if (physics != null) {
            physics.entities().settings().strength(LevelPhysics.ENTITY_STRENGTH);
        }
    }

    private static void squeeze(GameTestHelper helper, boolean down) {
        LevelPhysics physics = physics(helper);
        if (physics == null) {
            return;
        }
        TestArea.clear(helper, -1, 0, -1, 8, 4, 4);
        TestArea.forceChunks(helper, -1, -1, 8, 4, true);
        if (!down) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 4; z++) {
                    helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
                }
            }
        }
        // a crate against the wall's face at x = 6, a stand half a block short of it; or a stand just over a crate
        double crateX = down ? 3.5 : 5.6;
        Vec3 standAt = down ? new Vec3(3.5, 0.9, 1.5) : new Vec3(4.4, 0, 1.5);
        Vec3 step = down ? new Vec3(0, -0.25, 0) : new Vec3(0.25, 0, 0);
        int steps = down ? 4 : 8;
        float[] fastest = new float[2];
        Body[] crate = new Body[1];
        ArmorStand[] stand = new ArmorStand[1];
        GameTestSequence sequence = helper.startSequence();
        for (int round = 0; round < 2; round++) {
            int capped = round;
            sequence.thenExecute(() -> {
                physics.entities().settings().strength(capped == 1 ? LevelPhysics.ENTITY_STRENGTH : 0);
                if (crate[0] != null) {
                    crate[0].destroy();
                    stand[0].discard();
                }
                crate[0] = crate(helper, crateX, 1.5);
                stand[0] = stand(helper, standAt.x, standAt.y, standAt.z);
            });
            sequence.thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, stand[0]), "the stand ticks"));
            sequence.thenExecuteAfter(
                    5,
                    () -> helper.assertTrue(
                            physics.entities().proxyOf(stand[0]) != null, "the stand by the crate has a proxy"));
            for (int i = 0; i < steps; i++) {
                sequence.thenExecuteAfter(1, () -> {
                    stand[0].setPos(stand[0].position().add(step));
                    fastest[capped] = Math.max(
                            fastest[capped],
                            crate[0].linearVelocity(new Vector3f()).length());
                });
            }
            for (int i = 0; i < 10; i++) {
                sequence.thenExecuteAfter(
                        1,
                        () -> fastest[capped] = Math.max(
                                fastest[capped],
                                crate[0].linearVelocity(new Vector3f()).length()));
            }
        }
        sequence.thenExecute(() -> {
            Vector3d at = relative(helper, crate[0]);
            crate[0].destroy();
            stand[0].discard();
            TestArea.forceChunks(helper, -1, -1, 8, 4, false);
            MineBox3D.LOGGER.info(
                    "squeezed crate ({}), fastest: {} m/s uncapped, {} m/s capped",
                    down ? "down" : "wall",
                    fastest[0],
                    fastest[1]);
            helper.assertTrue(
                    fastest[0] > 8,
                    "uncapped, the stand's proxy squeezes the crate out (so this test sees a "
                            + "squeeze at all): fastest " + fastest[0] + " m/s");
            helper.assertTrue(fastest[1] < 3, "capped, the crate stays: fastest " + fastest[1] + " m/s");
            helper.assertTrue(
                    at.y < 1.5 && Math.abs(at.x - crateX) < 0.5 && Math.abs(at.z - 1.5) < 0.5,
                    "and on the ground where it was: " + at);
        });
        sequence.thenSucceed();
    }

    private static LevelPhysics physics(GameTestHelper helper) {
        LevelPhysics physics = LevelPhysics.of(helper.getLevel());
        if (physics == null) {
            helper.fail("Box3D is not available: physics could not start");
        }
        return physics;
    }

    /** A free 41 kg crate on the ground. */
    private static Body crate(GameTestHelper helper, double x, double z) {
        Vec3 at = helper.absoluteVec(new Vec3(x, 0.4, z));
        Body crate =
                physics(helper).createBody(new BodyDef().type(BodyType.DYNAMIC).position(at.x, at.y, at.z));
        crate.createShape(new ShapeDef().density(80).friction(0.6f), Geometry.Box.of(0.4f, 0.4f, 0.4f));
        return crate;
    }

    private static ArmorStand stand(GameTestHelper helper, double x, double y, double z) {
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new Vec3(x, y, z));
        stand.setNoGravity(true);
        return stand;
    }

    /** An armor stand's size as its saved data says it ({@code setSmall} is private), the rest left as it is spawned. */
    private static CompoundTag size(boolean small) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Small", small);
        return tag;
    }

    private static Vector3d relative(GameTestHelper helper, Body body) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return body.position(new Vector3d()).sub(origin.getX(), origin.getY(), origin.getZ());
    }

    private static boolean capped(GameTestHelper helper, Entity entity) {
        Body proxy = physics(helper).entities().proxyOf(entity);
        return proxy != null && proxy.type() == BodyType.DYNAMIC;
    }

    /**
     * The bodies proxies are pulled after while force-capped, but those of {@code before}: a test that failed before its
     * clean-up leaves its own behind.
     */
    private static List<Body> targets(GameTestHelper helper, Set<Body> before) {
        return physics(helper).world().bodies().stream()
                .filter(b -> b.isValid() && "entity target".equals(b.name()) && !before.contains(b))
                .toList();
    }

    private static Set<Body> targetsBefore(GameTestHelper helper) {
        return new HashSet<>(targets(helper, Set.of()));
    }

    private static void step(GameTestSequence sequence, Entity[] entity, double dx, double dz, int ticks) {
        for (int i = 0; i < ticks; i++) {
            sequence.thenExecuteAfter(
                    1, () -> entity[0].setPos(entity[0].getX() + dx, entity[0].getY(), entity[0].getZ() + dz));
        }
    }

    @GameTest(template = "empty", batch = "minebox3d_squeeze_life", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aProxyCappedOnACrateIsKinematicOnceOffItAndLeavesNothingWhenItsEntityGoes(
            GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 7, 4, 4);
        TestArea.forceChunks(helper, -1, -1, 7, 4, true);
        Set<Body> before = targetsBefore(helper);
        Body crate = crate(helper, 4, 1.5);
        Entity[] stand = {stand(helper, 2.2, 0, 1.5)};
        boolean[] capped = new boolean[1];
        GameTestSequence sequence = helper.startSequence();
        sequence.thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, stand[0]), "the stand ticks"));
        sequence.thenExecuteAfter(
                3,
                () -> helper.assertTrue(
                        physics(helper).entities().proxyOf(stand[0]) != null && !capped(helper, stand[0]),
                        "kinematic, off the crate"));
        // onto the crate at 3.6, pushing it along half a block
        for (int i = 0; i < 18; i++) {
            sequence.thenExecuteAfter(1, () -> {
                stand[0].setPos(stand[0].getX() + 0.1, stand[0].getY(), stand[0].getZ());
                capped[0] |= capped(helper, stand[0]);
            });
        }
        step(sequence, stand, -0.25, 0, 6);
        sequence.thenExecuteAfter(20, () -> {
            helper.assertTrue(capped[0], "force-capped while it came onto the crate");
            helper.assertFalse(capped(helper, stand[0]), "kinematic again, off it and caught up");
            stand[0].discard();
        });
        sequence.thenExecuteAfter(3, () -> {
            helper.assertTrue(physics(helper).entities().proxyOf(stand[0]) == null, "the stand's proxy went with it");
            helper.assertTrue(
                    targets(helper, before).isEmpty(), "and its target: " + targets(helper, before) + " left");
            crate.destroy();
            TestArea.forceChunks(helper, -1, -1, 7, 4, false);
        });
        sequence.thenSucceed();
    }

    /** Through a crate that ignores it, and along the dynamic slab it stands on (filtered from it): never capped. */
    @GameTest(template = "empty", batch = "minebox3d_squeeze_never", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aProxyIsNotCappedByBodiesItNeverMeets(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 7, 4, 7);
        TestArea.forceChunks(helper, -1, -1, 7, 7, true);
        Body crate = crate(helper, 4, 1.5);
        Entity[] passing = {stand(helper, 2.2, 0, 1.5)};
        LevelBodies.setIgnoredEntity(crate, passing[0]);
        Vec3 slabAt = helper.absoluteVec(new Vec3(3.5, 0.25, 5));
        Body slab =
                physics(helper).createBody(new BodyDef().type(BodyType.DYNAMIC).position(slabAt.x, slabAt.y, slabAt.z));
        slab.createShape(new ShapeDef().density(500).friction(0.6f), Geometry.Box.of(2, 0.25f, 1));
        Entity[] standing = {helper.spawn(EntityType.ARMOR_STAND, new Vec3(2.2, 1, 5))};
        boolean[] capped = new boolean[2];
        Vector3d[] crateWas = new Vector3d[1];
        GameTestSequence sequence = helper.startSequence();
        // landing on the slab caps it, as it should: from once it is kinematic again
        sequence.thenWaitUntil(() -> helper.assertTrue(
                TestArea.ticks(helper, passing[0])
                        && standing[0].onGround()
                        && EntityBases.baseOf(standing[0]) == slab
                        && physics(helper).entities().proxyOf(standing[0]) != null
                        && !capped(helper, standing[0]),
                "the stands tick, one on the slab, kinematic"));
        sequence.thenExecute(() -> crateWas[0] = crate.position(new Vector3d()));
        for (int i = 0; i < 25; i++) {
            sequence.thenExecuteAfter(1, () -> {
                passing[0].setPos(passing[0].getX() + 0.1, passing[0].getY(), passing[0].getZ());
                standing[0].setPos(standing[0].getX() + 0.1, standing[0].getY(), standing[0].getZ());
                capped[0] |= capped(helper, passing[0]);
                capped[1] |= capped(helper, standing[0]);
            });
        }
        sequence.thenExecute(() -> {
            helper.assertTrue(
                    physics(helper).entities().proxyOf(passing[0]) != null
                            && physics(helper).entities().proxyOf(standing[0]) != null,
                    "both have proxies");
            helper.assertFalse(capped[0], "not capped going through the crate that ignores it");
            helper.assertTrue(crate.position(new Vector3d()).distance(crateWas[0]) < 0.01, "which stayed");
            helper.assertTrue(EntityBases.baseOf(standing[0]) == slab, "the other still on the slab");
            helper.assertFalse(capped[1], "not capped going along the slab it stands on");
            passing[0].discard();
            standing[0].discard();
            crate.destroy();
            slab.destroy();
            TestArea.forceChunks(helper, -1, -1, 7, 7, false);
        });
        sequence.thenSucceed();
    }

    /** A small stand made full size while pushing a crate: capped still, of its new mass, one target. */
    @GameTest(template = "empty", batch = "minebox3d_squeeze_resized", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aProxyCappedWhileItsEntityGrowsStaysCappedOfItsNewMass(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 7, 4, 4);
        TestArea.forceChunks(helper, -1, -1, 7, 4, true);
        Set<Body> before = targetsBefore(helper);
        Body crate = crate(helper, 4, 1.5);
        ArmorStand small = stand(helper, 3, 0, 1.5);
        small.readAdditionalSaveData(size(true));
        Entity[] stand = {small};
        float[] smallMass = new float[1];
        GameTestSequence sequence = helper.startSequence();
        sequence.thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, stand[0]), "the stand ticks"));
        step(sequence, stand, 0.1, 0, 6); // into the crate at 3.6
        sequence.thenExecute(() -> {
            helper.assertTrue(capped(helper, stand[0]), "force-capped pushing the crate");
            smallMass[0] = physics(helper).entities().massOf(stand[0]);
            small.readAdditionalSaveData(size(false));
        });
        step(sequence, stand, 0.05, 0, 2);
        sequence.thenExecute(() -> {
            Body proxy = physics(helper).entities().proxyOf(stand[0]);
            float mass = physics(helper).entities().massOf(stand[0]);
            float bodyMass = proxy.massData(new MassData()).mass;
            helper.assertTrue(proxy.type() == BodyType.DYNAMIC, "still capped, full size");
            helper.assertTrue(
                    mass > 2 * smallMass[0] && Math.abs(bodyMass - mass) < 0.01 * mass,
                    "of the stand's mass: " + bodyMass + " kg, the stand " + mass + " kg, small " + smallMass[0]
                            + " kg");
            helper.assertTrue(
                    targets(helper, before).size() == 1, "pulled after one target: " + targets(helper, before));
            stand[0].discard();
            crate.destroy();
            TestArea.forceChunks(helper, -1, -1, 7, 4, false);
        });
        sequence.thenSucceed();
    }

    /** Its target destroyed by another mod, then the proxy itself: the physics carries on, and nothing is left. */
    @GameTest(template = "empty", batch = "minebox3d_squeeze_destroyed", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aForceCappedProxyAnotherModDestroysLeavesNoTarget(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 7, 4, 4);
        TestArea.forceChunks(helper, -1, -1, 7, 4, true);
        Set<Body> before = targetsBefore(helper);
        Body crate = crate(helper, 4, 1.5);
        Entity[] stand = {stand(helper, 3, 0, 1.5)};
        GameTestSequence sequence = helper.startSequence();
        sequence.thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, stand[0]), "the stand ticks"));
        step(sequence, stand, 0.1, 0, 6); // into the crate at 3.6
        sequence.thenExecute(() -> {
            List<Body> targets = targets(helper, before);
            helper.assertTrue(capped(helper, stand[0]) && targets.size() == 1, "force-capped, pulled after its target");
            targets.getFirst().destroy();
        });
        step(sequence, stand, 0.1, 0, 2);
        sequence.thenExecute(() -> {
            helper.assertFalse(physics(helper).isStopped(), "the level's physics carried on");
            helper.assertTrue(
                    capped(helper, stand[0]) && targets(helper, before).size() == 1,
                    "capped still, after a new target");
            physics(helper).entities().proxyOf(stand[0]).destroy();
        });
        sequence.thenExecuteAfter(3, () -> {
            helper.assertFalse(physics(helper).isStopped(), "the level's physics carried on");
            helper.assertTrue(
                    targets(helper, before).isEmpty(),
                    "the destroyed proxy's target went too: " + targets(helper, before) + " left");
            stand[0].discard();
            crate.destroy();
            TestArea.forceChunks(helper, -1, -1, 7, 4, false);
        });
        sequence.thenSucceed();
    }

    /**
     * Capped on a crate, then put back through a static wall it does not push: put where its entity is at once, rather
     * than held behind the wall for good — while a stand put on into the squeezed crate stays capped (the squeeze tests).
     */
    @GameTest(template = "empty", batch = "minebox3d_squeeze_held", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aCappedProxyLeftBehindIsPutWhereItsEntityIs(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 7, 4, 4);
        TestArea.forceChunks(helper, -1, -1, 7, 4, true);
        Set<Body> before = targetsBefore(helper);
        Body crate = crate(helper, 4, 1.5);
        Vec3 wallAt = helper.absoluteVec(new Vec3(2.5, 1, 1.5));
        Body wall = physics(helper).createBody(new BodyDef().position(wallAt.x, wallAt.y, wallAt.z));
        wall.createShape(new ShapeDef(), Geometry.Box.of(0.05f, 1, 1.5f)); // x from 2.45 to 2.55
        Entity[] stand = {stand(helper, 3.0, 0, 1.5)};
        GameTestSequence sequence = helper.startSequence();
        sequence.thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, stand[0]), "the stand ticks"));
        step(sequence, stand, 0.1, 0, 4);
        sequence.thenExecute(() -> {
            helper.assertTrue(capped(helper, stand[0]), "force-capped on the crate");
            // to 1.5, through the wall: short of a teleport
            stand[0].setPos(stand[0].getX() - 1.9, stand[0].getY(), stand[0].getZ());
        });
        sequence.thenExecuteAfter(3, () -> {
            double off = physics(helper).entities().proxyOf(stand[0]).position(new Vector3d()).x - stand[0].getX();
            helper.assertFalse(capped(helper, stand[0]), "kinematic again");
            helper.assertTrue(Math.abs(off) < 0.01, "where its stand is, not behind the wall: " + off + " off");
            helper.assertTrue(
                    targets(helper, before).size() == 1, "its target kept for next time: " + targets(helper, before));
            stand[0].discard();
            crate.destroy();
            wall.destroy();
            TestArea.forceChunks(helper, -1, -1, 7, 4, false);
        });
        sequence.thenSucceed();
    }

    /** Put past a crate in one tick (a command, a mod): not dragged through it as a walk into it would be. */
    @GameTest(template = "empty", batch = "minebox3d_squeeze_placed", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void anEntityPutFarPastACrateDoesNotDragIt(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 9, 4, 4);
        TestArea.forceChunks(helper, -1, -1, 9, 4, true);
        Body crate = crate(helper, 4, 1.5);
        Entity[] stand = {stand(helper, 2.2, 0, 1.5)};
        Vector3d[] before = new Vector3d[1];
        boolean[] capped = new boolean[1];
        GameTestSequence sequence = helper.startSequence();
        sequence.thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, stand[0]), "the stand ticks"));
        sequence.thenExecuteAfter(5, () -> {
            before[0] = crate.position(new Vector3d());
            Vec3 past = helper.absoluteVec(new Vec3(7.5, 0, 1.5));
            stand[0].setPos(past.x, past.y, past.z);
        });
        for (int i = 0; i < 15; i++) {
            sequence.thenExecuteAfter(1, () -> capped[0] |= capped(helper, stand[0]));
        }
        sequence.thenExecute(() -> {
            double moved = crate.position(new Vector3d()).distance(before[0]);
            helper.assertTrue(moved < 0.1, "the crate stayed where it was: moved " + moved);
            helper.assertFalse(capped[0], "and the stand's proxy was never capped for it");
            stand[0].discard();
            crate.destroy();
            TestArea.forceChunks(helper, -1, -1, 9, 4, false);
        });
        sequence.thenSucceed();
    }
}
