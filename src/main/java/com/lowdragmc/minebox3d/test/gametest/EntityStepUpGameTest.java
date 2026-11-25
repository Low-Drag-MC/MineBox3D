package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityBases;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import com.lowdragmc.minebox3d.mc.entity.StepUpOn;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.RevoluteJointDef;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * <b>Stepping up onto bodies, as Unreal's Mover steps</b> (design doc {@code 04-entities-and-bodies.md} §21,
 * {@link StepUpOn}): a stand with a step climbs a low light crate rather than pushing it, and pushes one whose shape
 * says no; a low box that says no stops it, and one whose owner says no; a step up a body that would end on one that
 * says no is not taken; on a deck, a crate taller than the step is pushed along; a board on a hinge is walked up; a
 * slope that says no is walked up by a stand on it, and not stepped onto from the ground.
 *
 * <p>Armor stands as in EntityBlockedGameTest — given a step (theirs is 0), walked by their motion set each tick.
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class EntityStepUpGameTest {

    /** The body this test's owner says no for ({@link StepUpOn#OWNER}); none outside the test. */
    private static final AtomicReference<Body> REFUSED_BY_OWNER = new AtomicReference<>();

    static {
        EntityCollisions.registerStepUpOwner((body, mover) -> body == REFUSED_BY_OWNER.get() ? Boolean.FALSE : null);
    }

    private static final double WALK = 0.15;
    private static final float STEP = 0.6f;

    private static LevelPhysics physics(GameTestHelper helper) {
        LevelPhysics physics = LevelPhysics.of(helper.getLevel());
        if (physics == null) {
            helper.fail("Box3D is not available: physics could not start");
        }
        return physics;
    }

    private static void area(GameTestHelper helper, int x1, int z1) {
        TestArea.clear(helper, -1, 0, -1, x1, 7, z1);
        TestArea.forceChunks(helper, -1, -1, x1, z1, true);
    }

    /**
     * The area let go, before the asserts: a failed assert skips what follows it, and bodies left in the level poison
     * the tests after (doc 04 §21.1).
     */
    private static void release(GameTestHelper helper, int x1, int z1, Body[] bodies, ArmorStand... stands) {
        for (Body body : bodies) {
            body.destroy();
        }
        for (ArmorStand stand : stands) {
            stand.discard();
        }
        TestArea.forceChunks(helper, -1, -1, x1, z1, false);
    }

    /** A body of one box at a point relative to the test, its shape saying {@code stepUpOn}. */
    private static Body box(
            GameTestHelper helper,
            BodyType type,
            double x,
            double y,
            double z,
            Quaternionf rotation,
            float hx,
            float hy,
            float hz,
            float density,
            StepUpOn stepUpOn) {
        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        Body body = physics(helper)
                .createBody(new BodyDef().type(type).position(at.x, at.y, at.z).rotation(rotation));
        EntityCollisions.setStepUpOn(
                body.createShape(new ShapeDef().density(density), Geometry.Box.of(hx, hy, hz)), stepUpOn);
        return body;
    }

    private static Body box(
            GameTestHelper helper,
            BodyType type,
            double x,
            double y,
            double z,
            float hx,
            float hy,
            float hz,
            StepUpOn stepUpOn) {
        return box(helper, type, x, y, z, new Quaternionf(), hx, hy, hz, 23, stepUpOn);
    }

    /** A stand with a player's step. */
    private static ArmorStand stand(GameTestHelper helper, double x, double y, double z) {
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new Vec3(x, y, z));
        stand.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(STEP);
        return stand;
    }

    private static Vec3 relative(GameTestHelper helper, ArmorStand entity) {
        return entity.position().subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)));
    }

    private static Vector3d relative(GameTestHelper helper, Body body) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return body.position(new Vector3d()).sub(origin.getX(), origin.getY(), origin.getZ());
    }

    /** Walks the stands +x while {@code walking[0]}, keeping each one's highest feet. */
    private static void walk(GameTestHelper helper, boolean[] walking, double[] highest, ArmorStand... stands) {
        helper.onEachTick(() -> {
            for (int i = 0; i < stands.length; i++) {
                ArmorStand stand = stands[i];
                if (!stand.isAlive() || !TestArea.ticks(helper, stand)) {
                    continue;
                }
                if (walking[0]) {
                    stand.setDeltaMovement(WALK, stand.getDeltaMovement().y, 0);
                }
                highest[i] = Math.max(highest[i], relative(helper, stand).y);
            }
        });
    }

    /**
     * A low light crate stepped onto, not pushed — also by a stand whose first step only grazes it (5 mm in: the top is
     * still found, or that step is a push and the crate, pushed on at its pace, is never more than grazed again) — and
     * one whose shape says no pushed along.
     */
    @GameTest(template = "empty", batch = "minebox3d_step_crate", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aLowLightCrateIsSteppedOntoAndOneThatSaysNoIsPushed(GameTestHelper helper) {
        area(helper, 8, 9);
        // 0.4 high, 3 kg: x from 3.7 to 4.3
        Body onto = box(helper, BodyType.DYNAMIC, 4, 0.2, 1.5, 0.3f, 0.2f, 0.3f, StepUpOn.YES);
        Body pushed = box(helper, BodyType.DYNAMIC, 4, 0.2, 4.5, 0.3f, 0.2f, 0.3f, StepUpOn.NO);
        Body grazed = box(helper, BodyType.DYNAMIC, 4, 0.2, 7.5, 0.3f, 0.2f, 0.3f, StepUpOn.YES);
        ArmorStand stepper = stand(helper, 2, 0, 1.5);
        ArmorStand pusher = stand(helper, 2, 0, 4.5);
        // its front 0.145 short of the crate: its first step of 0.15 goes 5 mm in
        ArmorStand grazer = stand(helper, 3.7 - 0.25 - 0.145, 0, 7.5);
        boolean[] walking = {false};
        double[] highest = new double[3];
        double[] crateWhenOn = {Double.NaN, Double.NaN};
        walk(helper, walking, highest, stepper, pusher, grazer);
        helper.onEachTick(() -> {
            if (Double.isNaN(crateWhenOn[0]) && highest[0] > 0.35) {
                crateWhenOn[0] = relative(helper, onto).x;
            }
            if (Double.isNaN(crateWhenOn[1]) && highest[2] > 0.35) {
                crateWhenOn[1] = relative(helper, grazed).x;
            }
        });
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertTrue(TestArea.ticks(helper, stepper, pusher, grazer), "the stands tick"))
                .thenExecute(() -> walking[0] = true)
                .thenExecuteAfter(30, () -> walking[0] = false)
                .thenExecuteAfter(20, () -> {
                    double pushedAlong = relative(helper, pushed).x - 4;
                    release(helper, 8, 9, new Body[] {onto, pushed, grazed}, stepper, pusher, grazer);
                    helper.assertTrue(
                            highest[0] > 0.35, "the stand stepped up onto the low crate: highest feet " + highest[0]);
                    helper.assertTrue(
                            Math.abs(crateWhenOn[0] - 4) < 0.1,
                            "and did not push it first: the crate at " + crateWhenOn[0] + " when it was on");
                    helper.assertTrue(
                            highest[2] > 0.35,
                            "the one whose first step grazed it stepped onto it too: highest feet " + highest[2]);
                    helper.assertTrue(
                            Math.abs(crateWhenOn[1] - 4) < 0.1,
                            "and did not push it first: the crate at " + crateWhenOn[1] + " when it was on");
                    helper.assertTrue(
                            highest[1] < 0.05,
                            "the stand did not step onto the crate that says no: highest feet " + highest[1]);
                    helper.assertTrue(pushedAlong > 0.3, "it pushed that one along: " + pushedAlong);
                    helper.succeed();
                });
    }

    /**
     * On a deck (a body), pushing a crate taller than its step: pushed along as far as by a stand that never tries a
     * step. The deck under it is no step (a top a hair off its feet), and a step tried at the crate that goes no
     * further is not one — a push it does not lose.
     */
    @GameTest(template = "empty", batch = "minebox3d_step_deck_push", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void onADeckACrateTallerThanTheStepIsPushedAlong(GameTestHelper helper) {
        area(helper, 9, 9);
        // the deck's top at 0.5, x from 0 to 9, z from 0.5 to 4.5; a crate a block high on it, x from 3.7 to 4.3,
        // pushed by a stand with a step and by one with none, which never tries a step; the same crate on the ground
        Body deck = box(helper, BodyType.STATIC, 4.5, 0.25, 2.5, 4.5f, 0.25f, 2, StepUpOn.YES);
        Body crate = box(helper, BodyType.DYNAMIC, 4, 1, 1.5, 0.3f, 0.5f, 0.3f, StepUpOn.YES);
        Body unstepped = box(helper, BodyType.DYNAMIC, 4, 1, 3.5, 0.3f, 0.5f, 0.3f, StepUpOn.YES);
        Body grounded = box(helper, BodyType.DYNAMIC, 4, 0.5, 7.5, 0.3f, 0.5f, 0.3f, StepUpOn.YES);
        ArmorStand pusher = stand(helper, 2, 0.5, 1.5);
        ArmorStand stepless = stand(helper, 2, 0.5, 3.5);
        stepless.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0);
        ArmorStand onTheGround = stand(helper, 2, 0, 7.5);
        boolean[] walking = {false};
        double[] highest = new double[3];
        double[] start = new double[3];
        walk(helper, walking, highest, pusher, stepless, onTheGround);
        Body[] crates = {crate, unstepped, grounded};
        // kept upright: a tall light crate pushed at its middle tips over, and its slanting back is no step only
        // past 45°
        for (Body body : crates) {
            body.setFixedRotation(true);
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        TestArea.ticks(helper, pusher, stepless, onTheGround)
                                && pusher.onGround()
                                && EntityBases.baseOf(pusher) == deck,
                        "the stand on the deck"))
                .thenExecute(() -> {
                    for (int i = 0; i < 3; i++) {
                        start[i] = relative(helper, crates[i]).x;
                    }
                    walking[0] = true;
                })
                .thenExecuteAfter(30, () -> {
                    walking[0] = false;
                    double[] went = new double[3];
                    for (int i = 0; i < 3; i++) {
                        went[i] = relative(helper, crates[i]).x - start[i];
                    }
                    MineBox3D.LOGGER.info(
                            "[mb3] pushed on the deck {}, on the deck with no step {}, on the ground {}",
                            went[0],
                            went[1],
                            went[2]);
                    release(helper, 9, 9, new Body[] {deck, crate, unstepped, grounded}, pusher, stepless, onTheGround);
                    helper.assertTrue(
                            went[0] > 0.9 * went[1],
                            "the crate pushed along the deck as by a stand that never " + "tries a step: " + went[0]
                                    + " against " + went[1]);
                    helper.succeed();
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_step_no", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aLowBoxThatSaysNoStopsAStepAndOneWhoseOwnerSaysNo(GameTestHelper helper) {
        area(helper, 8, 9);
        // 0.4 high, x from 3.7 to 4.3, nothing pushes them
        Body no = box(helper, BodyType.KINEMATIC, 4, 0.2, 1.5, 0.3f, 0.2f, 0.3f, StepUpOn.NO);
        Body ownerNo = box(helper, BodyType.KINEMATIC, 4, 0.2, 4.5, 0.3f, 0.2f, 0.3f, StepUpOn.OWNER);
        Body ownerSilent = box(helper, BodyType.KINEMATIC, 4, 0.2, 7.5, 0.3f, 0.2f, 0.3f, StepUpOn.OWNER);
        REFUSED_BY_OWNER.set(ownerNo);
        ArmorStand[] stands = {stand(helper, 2, 0, 1.5), stand(helper, 2, 0, 4.5), stand(helper, 2, 0, 7.5)};
        boolean[] walking = {false};
        double[] highest = new double[3];
        walk(helper, walking, highest, stands);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, stands), "the stands tick"))
                .thenExecute(() -> walking[0] = true)
                .thenExecuteAfter(30, () -> walking[0] = false)
                .thenExecuteAfter(10, () -> {
                    double[] front = {relative(helper, stands[0]).x + 0.25, relative(helper, stands[1]).x + 0.25};
                    REFUSED_BY_OWNER.set(null);
                    release(helper, 8, 9, new Body[] {no, ownerNo, ownerSilent}, stands);
                    for (int i = 0; i < 2; i++) {
                        String which = i == 0 ? "the box that says no" : "the box whose owner says no";
                        helper.assertTrue(
                                highest[i] < 0.05, "not stepped onto " + which + ": highest feet " + highest[i]);
                        helper.assertTrue(front[i] < 3.71, "stopped at " + which + ": its front at " + front[i]);
                    }
                    helper.assertTrue(
                            highest[2] > 0.35,
                            "the box whose owner has no say is stepped onto: highest feet " + highest[2]);
                    helper.succeed();
                });
    }

    /**
     * A step up a thin box that would end with the stand over a box that says no, level with it: not taken (Unreal's
     * step down onto a surface it may not step up onto). The stand starts at the thin box's face, so every try ends
     * over both.
     */
    @GameTest(template = "empty", batch = "minebox3d_step_landing", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStepThatWouldEndOnABoxThatSaysNoIsNotTaken(GameTestHelper helper) {
        area(helper, 8, 6);
        // a thin box, x from 3.0 to 3.1, and right behind it one level with it, x from 3.1 to 4.1: both 0.4 high
        Body thin = box(helper, BodyType.KINEMATIC, 3.05, 0.2, 1.5, 0.05f, 0.2f, 0.5f, StepUpOn.YES);
        Body behindNo = box(helper, BodyType.KINEMATIC, 3.6, 0.2, 1.5, 0.5f, 0.2f, 0.5f, StepUpOn.NO);
        Body thinToo = box(helper, BodyType.KINEMATIC, 3.05, 0.2, 4.5, 0.05f, 0.2f, 0.5f, StepUpOn.YES);
        Body behindYes = box(helper, BodyType.KINEMATIC, 3.6, 0.2, 4.5, 0.5f, 0.2f, 0.5f, StepUpOn.YES);
        // a centimetre short of the thin box's face
        ArmorStand refused = stand(helper, 2.74, 0, 1.5);
        ArmorStand taken = stand(helper, 2.74, 0, 4.5);
        boolean[] walking = {false};
        double[] highest = new double[2];
        walk(helper, walking, highest, refused, taken);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, refused, taken), "the stands tick"))
                .thenExecute(() -> walking[0] = true)
                .thenExecuteAfter(20, () -> walking[0] = false)
                .thenExecuteAfter(10, () -> {
                    release(helper, 8, 6, new Body[] {thin, behindNo, thinToo, behindYes}, refused, taken);
                    helper.assertTrue(
                            highest[0] < 0.05, "no step that ends on the box that says no: highest feet " + highest[0]);
                    helper.assertTrue(
                            highest[1] > 0.35,
                            "the same step onto boxes that both say yes: highest feet " + highest[1]);
                    helper.succeed();
                });
    }

    /**
     * EntityStudio's seesaw (doc 04 §21): a 40 kg board on a hinge at its middle, 0.6 up, one end on the ground —
     * walked up from that end by a stand with a step, which Minecraft does as many small steps up its slope.
     */
    @GameTest(template = "empty", batch = "minebox3d_step_seesaw", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aBoardOnAHingeIsWalkedUp(GameTestHelper helper) {
        area(helper, 8, 3);
        Vec3 pivot = helper.absoluteVec(new Vec3(4, 0.6, 1.5));
        // tipped about z till its west end is a few millimetres off the ground (2.5 sin a + 0.1 cos a = 0.6 at 0.2022)
        Quaternionf tipped = new Quaternionf().rotationAxis(0.2f, 0, 0, 1);
        Body anchor = physics(helper).createBody(new BodyDef().position(pivot.x, pivot.y, pivot.z));
        Body board = box(helper, BodyType.DYNAMIC, 4, 0.6, 1.5, tipped, 2.5f, 0.1f, 0.5f, 40, StepUpOn.YES);
        var hinge = physics(helper)
                .world()
                .createJoint(new RevoluteJointDef().bodies(anchor, board).worldFrame(pivot.x, pivot.y, pivot.z));
        ArmorStand stand = stand(helper, 0.6, 0, 1.5);
        boolean[] walking = {false};
        double[] highest = new double[1];
        walk(helper, walking, highest, stand);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, stand), "the stand ticks"))
                .thenExecute(() -> walking[0] = true)
                .thenExecuteAfter(25, () -> walking[0] = false)
                .thenExecuteAfter(5, () -> {
                    double along = relative(helper, stand).x;
                    hinge.destroy(false);
                    release(helper, 8, 3, new Body[] {board, anchor}, stand);
                    helper.assertTrue(highest[0] > 0.45, "up the board: highest feet " + highest[0]);
                    helper.assertTrue(along > 2.5, "along it: at x " + along);
                    helper.succeed();
                });
    }

    /**
     * A slope that says no: a stand standing on it walks up it (Unreal's option is about the step onto it, not walking
     * on it after landing — and walking up a slope in Minecraft is stepping up it); one on the ground walking into its
     * foot does not step onto it.
     */
    @GameTest(template = "empty", batch = "minebox3d_step_slope", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aSlopeThatSaysNoIsWalkedUpByAStandOnItOnly(GameTestHelper helper) {
        area(helper, 8, 6);
        // 15 degrees, its west end on the ground at x 2.07: 2 sin 15 + 0.1 cos 15 = 0.614
        Quaternionf tipped = new Quaternionf().rotationAxis((float) Math.toRadians(15), 0, 0, 1);
        Body under = box(helper, BodyType.KINEMATIC, 4, 0.614, 1.5, tipped, 2, 0.1f, 0.5f, 1, StepUpOn.NO);
        Body ahead = box(helper, BodyType.KINEMATIC, 4, 0.614, 4.5, tipped, 2, 0.1f, 0.5f, 1, StepUpOn.NO);
        ArmorStand onIt = stand(helper, 3, 2, 1.5);
        ArmorStand fromTheGround = stand(helper, 1.2, 0, 4.5);
        boolean[] walking = {false};
        double[] highest = new double[2];
        double[] from = new double[1];
        walk(helper, walking, highest, onIt, fromTheGround);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        TestArea.ticks(helper, onIt, fromTheGround)
                                && onIt.onGround()
                                && EntityBases.baseOf(onIt) == under,
                        "the stand landed on the slope"))
                .thenExecute(() -> {
                    // from where it stands, not from where it fell from
                    from[0] = highest[0] = relative(helper, onIt).y;
                    highest[1] = relative(helper, fromTheGround).y;
                    walking[0] = true;
                })
                .thenExecuteAfter(12, () -> walking[0] = false)
                .thenExecuteAfter(5, () -> {
                    release(helper, 8, 6, new Body[] {under, ahead}, onIt, fromTheGround);
                    helper.assertTrue(
                            highest[0] - from[0] > 0.2,
                            "the stand on the slope walked up it: from " + from[0] + " to " + highest[0]);
                    helper.assertTrue(
                            highest[1] < 0.05,
                            "the one on the ground did not step onto it: highest feet " + highest[1]);
                    helper.succeed();
                });
    }
}
