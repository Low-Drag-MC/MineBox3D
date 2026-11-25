package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.CollisionCategories;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityBases;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.CastResult;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>Bodies stop entities as blocks do</b> (design doc {@code 04-entities-and-bodies.md} §11, batch 4c-1): an armor
 * stand lands on a body (a ball and a log too), is stopped by one, steps up onto a low one, slides over the seam
 * between two, pushes a light crate along but not two tonnes of iron (and not even the crate when a mod says its moves
 * push nothing), never walks through or sinks into one it can push, turns a plank it pushes by its end, and is pushed
 * out of a body moving into it, into no other — and a sensor, or a shape that lets entities through, stops nothing.
 * The queries other mods sweep entities with see the same bodies.
 *
 * <p>Armor stands for the same reasons as EntityPushGameTest: they move under their own motion and friction, and do
 * not walk off. Each test waits for its stands to tick (TestArea.forceChunks).
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class EntityBlockedGameTest {

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

    private static void done(GameTestHelper helper, int x1, int z1) {
        TestArea.forceChunks(helper, -1, -1, x1, z1, false);
        helper.succeed();
    }

    /** A body of one box at a point relative to the test. */
    private static Body box(
            GameTestHelper helper,
            BodyType type,
            double x,
            double y,
            double z,
            float hx,
            float hy,
            float hz,
            ShapeDef def) {
        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        Body body = physics(helper).createBody(new BodyDef().type(type).position(at.x, at.y, at.z));
        body.createShape(def, Geometry.Box.of(hx, hy, hz));
        return body;
    }

    private static ArmorStand stand(GameTestHelper helper, double x, double y, double z) {
        return helper.spawn(EntityType.ARMOR_STAND, new Vec3(x, y, z));
    }

    private static boolean ticking(GameTestHelper helper, Entity... entities) {
        return TestArea.ticks(helper, entities);
    }

    private static Vec3 relative(GameTestHelper helper, Entity entity) {
        return entity.position().subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)));
    }

    private static Vector3d relative(GameTestHelper helper, Body body) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return body.position(new Vector3d()).sub(origin.getX(), origin.getY(), origin.getZ());
    }

    @GameTest(template = "empty", batch = "minebox3d_blocked_land", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandFallsOntoABodyAndStandsOnIt(GameTestHelper helper) {
        area(helper, 6, 6);
        Body slab = box(helper, BodyType.STATIC, 2.5, 2.25, 2.5, 1, 0.25f, 1, new ShapeDef()); // its top at 2.5
        ArmorStand stand = stand(helper, 2.5, 4.5, 2.5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, stand), "the stand ticks"))
                .thenExecuteAfter(30, () -> {
                    double y = relative(helper, stand).y;
                    // Box3D's box mover stops a few millimetres short of the surface
                    helper.assertTrue(y > 2.5 && y < 2.51, "on the slab's top: y = " + y);
                    helper.assertTrue(stand.onGround(), "and on the ground there, as on a block");
                    slab.destroy();
                })
                .thenExecuteAfter(20, () -> {
                    double y = relative(helper, stand).y;
                    helper.assertTrue(y < 0.01, "the slab gone, it fell to the floor: y = " + y);
                    stand.discard();
                    done(helper, 6, 6);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_blocked_wall", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandSlidIntoABodyStopsAtIt(GameTestHelper helper) {
        area(helper, 8, 4);
        Body wall = box(helper, BodyType.STATIC, 5.25, 1, 1.5, 0.25f, 1, 1, new ShapeDef()); // its face at x = 5
        ArmorStand stand = stand(helper, 3, 0, 1.5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, stand), "the stand ticks"))
                .thenExecute(() -> stand.setDeltaMovement(1.5, 0, 0))
                .thenExecuteAfter(30, () -> {
                    double face = relative(helper, stand).x + 0.25;
                    helper.assertTrue(
                            face <= 5.0 && face > 4.9, "slid up to the wall's face and stopped there: " + face);
                    helper.assertTrue(Math.abs(relative(helper, stand).z - 1.5) < 0.01, "along its line");
                    wall.destroy();
                    stand.discard();
                    done(helper, 8, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_blocked_step", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandStepsUpOntoALowBody(GameTestHelper helper) {
        area(helper, 8, 4);
        // 0.4 high, under a mob's 0.6 step; its face at x = 4
        Body step = box(helper, BodyType.STATIC, 5, 0.2, 1.5, 1, 0.2f, 1, new ShapeDef());
        ArmorStand stand = stand(helper, 2.5, 0, 1.5);
        // ⚠️ an armor stand's own step is 0 (ArmorStand.createAttributes): a mob's here
        stand.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, stand), "the stand ticks"))
                .thenExecute(() -> stand.setDeltaMovement(1.0, 0, 0))
                .thenExecuteAfter(30, () -> {
                    Vec3 at = relative(helper, stand);
                    helper.assertTrue(at.x > 4.25, "went on past the step's face: x = " + at.x);
                    helper.assertTrue(at.y > 0.4 && at.y < 0.41, "on top of it: y = " + at.y);
                    step.destroy();
                    stand.discard();
                    done(helper, 8, 4);
                });
    }

    /**
     * Two slabs side by side, their tops level: a stand sliding from one onto the other does not catch on the seam
     * (Box3D counts the next slab's edge, millimetres below the stand's feet, as touching: EntityCollisions.sweeping).
     */
    @GameTest(template = "empty", batch = "minebox3d_blocked_seam", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandSlidesOverTheSeamBetweenTwoBodies(GameTestHelper helper) {
        area(helper, 10, 4);
        // tops at 0.5, the seam at x = 4
        Body first = box(helper, BodyType.STATIC, 2.5, 0.25, 1.5, 1.5f, 0.25f, 1, new ShapeDef());
        Body second = box(helper, BodyType.STATIC, 6, 0.25, 1.5, 2, 0.25f, 1, new ShapeDef());
        ArmorStand stand = stand(helper, 2, 0.6, 1.5);
        helper.startSequence()
                .thenWaitUntil(() ->
                        helper.assertTrue(ticking(helper, stand) && stand.onGround(), "the stand ticks, on the first"))
                .thenExecute(() -> stand.setDeltaMovement(1.2, 0, 0))
                .thenExecuteAfter(30, () -> {
                    Vec3 at = relative(helper, stand);
                    helper.assertTrue(at.x > 4.5, "slid on over the seam: x = " + at.x);
                    helper.assertTrue(at.y > 0.5 && at.y < 0.51, "on the second's top: y = " + at.y);
                    first.destroy();
                    second.destroy();
                    stand.discard();
                    done(helper, 10, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_blocked_mass", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandPushesALightCrateButNotTwoTonnesOfIron(GameTestHelper helper) {
        area(helper, 10, 6);
        // 0.6 cubes: 5 kg of crate, 1.9 t of iron, against a 54 kg stand; their faces at x = 3.7
        Body crate = box(helper, BodyType.DYNAMIC, 4, 0.3, 1.5, 0.3f, 0.3f, 0.3f, new ShapeDef().density(23));
        Body iron = box(helper, BodyType.DYNAMIC, 4, 0.3, 4.5, 0.3f, 0.3f, 0.3f, new ShapeDef().density(9000));
        ArmorStand light = stand(helper, 3.2, 0, 1.5);
        ArmorStand heavy = stand(helper, 3.2, 0, 4.5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, light, heavy), "the stands tick"))
                .thenExecute(() -> {
                    light.setDeltaMovement(0.6, 0, 0);
                    heavy.setDeltaMovement(0.6, 0, 0);
                })
                .thenExecuteAfter(30, () -> {
                    double crateMoved = relative(helper, crate).x - 4, ironMoved = relative(helper, iron).x - 4;
                    helper.assertTrue(crateMoved > 0.4, "the stand pushed the light crate along: " + crateMoved);
                    helper.assertTrue(
                            relative(helper, light).x > 3.7,
                            "going on past where the crate was: " + relative(helper, light).x);
                    helper.assertTrue(Math.abs(ironMoved) < 0.05, "the iron hardly moved: " + ironMoved);
                    // a body stops the stand at its face; it keeps 54 / (54 + 1944) of its speed, so it goes on only
                    // as far as the iron gives
                    double in = relative(helper, heavy).x + 0.25 - (relative(helper, iron).x - 0.3);
                    helper.assertTrue(in < 0.03, "and stopped the stand at its face: " + in + " in");
                    crate.destroy();
                    iron.destroy();
                    light.discard();
                    heavy.discard();
                    done(helper, 10, 6);
                });
    }

    /**
     * The same light crate in the way of a stand whose moves a mod says push no body
     * ({@code EntityAuthority.pushesBodies}, Unreal's Mover): stopped at its face as at a wall, and the crate stays —
     * while the same stand without the say pushes its own along.
     */
    @GameTest(template = "empty", batch = "minebox3d_blocked_no_push", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandWhoseMovesPushNothingStopsAtALightCrate(GameTestHelper helper) {
        area(helper, 10, 6);
        Body pushed = box(helper, BodyType.DYNAMIC, 4, 0.3, 1.5, 0.3f, 0.3f, 0.3f, new ShapeDef().density(23));
        Body left = box(helper, BodyType.DYNAMIC, 4, 0.3, 4.5, 0.3f, 0.3f, 0.3f, new ShapeDef().density(23));
        ArmorStand pusher = stand(helper, 3.2, 0, 1.5);
        ArmorStand walled = EntityAuthorityGameTest.pushingNothing(stand(helper, 3.2, 0, 4.5));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, pusher, walled), "the stands tick"))
                .thenExecute(() -> {
                    pusher.setDeltaMovement(0.6, 0, 0);
                    walled.setDeltaMovement(0.6, 0, 0);
                })
                .thenExecuteAfter(30, () -> {
                    double pushedMoved = relative(helper, pushed).x - 4, leftMoved = relative(helper, left).x - 4;
                    helper.assertTrue(pushedMoved > 0.4, "the stand without the say pushed its crate: " + pushedMoved);
                    helper.assertTrue(Math.abs(leftMoved) < 0.01, "the other's crate stayed: " + leftMoved);
                    double in = relative(helper, walled).x + 0.25 - (relative(helper, left).x - 0.3);
                    helper.assertTrue(in < 0.01 && in > -0.05, "stopped at its face as at a wall: " + in + " in");
                    pushed.destroy();
                    left.destroy();
                    pusher.discard();
                    walled.discard();
                    done(helper, 10, 6);
                });
    }

    /**
     * A stand whose moves push nothing stepping up onto a light crate: it goes up onto it and the crate stays. (Its
     * proxy driven along the straight line from before the step to after it would cut the crate's top corner and fling
     * it.)
     */
    @GameTest(template = "empty", batch = "minebox3d_blocked_no_push_step", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandWhoseMovesPushNothingStepsOntoALightCrateAndLeavesItBe(GameTestHelper helper) {
        area(helper, 8, 4);
        // 0.4 high, 10 kg; its face at x = 4
        Body crate = box(helper, BodyType.DYNAMIC, 4.5, 0.2, 1.5, 0.5f, 0.2f, 0.5f, new ShapeDef().density(25));
        ArmorStand stand = EntityAuthorityGameTest.pushingNothing(stand(helper, 3.2, 0, 1.5));
        stand.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6);
        float[] fastest = new float[1];
        helper.onEachTick(() -> fastest[0] =
                Math.max(fastest[0], crate.linearVelocity(new Vector3f()).length()));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, stand), "the stand ticks"))
                .thenExecute(() -> {
                    fastest[0] = 0;
                    stand.setDeltaMovement(0.5, 0, 0);
                })
                .thenExecuteAfter(20, () -> {
                    Vec3 at = relative(helper, stand);
                    helper.assertTrue(at.y > 0.39 && at.y < 0.42, "up on the crate: y = " + at.y);
                    helper.assertTrue(fastest[0] < 1, "the crate stayed: fastest " + fastest[0] + " m/s");
                    helper.assertTrue(
                            relative(helper, crate).distance(4.5, 0.2, 1.5) < 0.05,
                            "where it was: " + relative(helper, crate));
                    crate.destroy();
                    stand.discard();
                    done(helper, 8, 4);
                });
    }

    /** A stand landing on a crate ten times lighter, in the middle or near an edge, does not shoot it away. */
    @GameTest(template = "empty", batch = "minebox3d_blocked_light_landing", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandLandingOnALightCrateDoesNotShootItAway(GameTestHelper helper) {
        area(helper, 8, 8);
        double[] offsets = {0, 0.2, 0.4};
        Body[] crates = new Body[offsets.length];
        ArmorStand[] stands = new ArmorStand[offsets.length];
        float[] fastest = new float[offsets.length];
        helper.onEachTick(() -> {
            for (int i = 0; i < crates.length; i++) {
                if (crates[i] != null && crates[i].isValid()) {
                    fastest[i] = Math.max(
                            fastest[i],
                            crates[i].linearVelocity(new org.joml.Vector3f()).length());
                }
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper), "the area ticks"))
                .thenExecute(() -> {
                    for (int i = 0; i < offsets.length; i++) {
                        // 5 kg against the stand's 54
                        crates[i] = box(
                                helper,
                                BodyType.DYNAMIC,
                                3,
                                0.3,
                                1.5 + 2.5 * i,
                                0.3f,
                                0.3f,
                                0.3f,
                                new ShapeDef().density(23));
                        stands[i] = stand(helper, 3 - offsets[i], 1.5, 1.5 + 2.5 * i);
                    }
                })
                .thenExecuteAfter(60, () -> {
                    for (int i = 0; i < offsets.length; i++) {
                        helper.assertTrue(
                                fastest[i] < 3,
                                "the crate under a stand landing " + offsets[i]
                                        + " off its middle never went faster than 3 m/s: " + fastest[i]);
                    }
                    for (int i = 0; i < offsets.length; i++) {
                        crates[i].destroy();
                        stands[i].discard();
                    }
                    done(helper, 8, 8);
                });
    }

    /**
     * What the mass rule must not do: a stand on a crate it could push does not sink into it (its weight presses
     * through its proxy, not by going in); one pushing a crate pinned against a wall does not walk through it. (Whether
     * one with a step climbs a low crate or pushes it: EntityStepUpGameTest.)
     */
    @GameTest(template = "empty", batch = "minebox3d_blocked_pinned", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aPushableCrateIsNeverWalkedThroughOrSunkInto(GameTestHelper helper) {
        area(helper, 10, 8);
        // a 30 kg crate on the floor, a stand on it
        Body under = box(helper, BodyType.DYNAMIC, 1.5, 0.3, 1.5, 0.5f, 0.3f, 0.5f, new ShapeDef().density(50));
        ArmorStand onTop = stand(helper, 1.5, 1, 1.5);
        // a crate against a wall, a stand shoved at it
        Body pinned = box(helper, BodyType.DYNAMIC, 6, 0.3, 4.5, 0.3f, 0.3f, 0.3f, new ShapeDef().density(23));
        Body wall = box(helper, BodyType.STATIC, 6.55, 1, 4.5, 0.25f, 1, 1, new ShapeDef()); // its face at 6.3
        ArmorStand shover = stand(helper, 4.5, 0, 4.5);
        helper.onEachTick(() -> {
            if (shover.tickCount > 2 && shover.isAlive()) {
                shover.setDeltaMovement(0.3, shover.getDeltaMovement().y, 0); // keeps walking into it
            }
        });
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertTrue(ticking(helper, onTop, shover) && onTop.onGround(), "the stands tick"))
                .thenExecuteAfter(40, () -> {
                    double top = relative(helper, under).y + 0.3, feet = relative(helper, onTop).y;
                    helper.assertTrue(feet - top > 0 && feet - top < 0.01, "on the crate, not in it: " + (feet - top));
                    double into = relative(helper, shover).x + 0.25 - (relative(helper, pinned).x - 0.3);
                    helper.assertTrue(into < 0.01, "against the pinned crate, not through it: " + into + " in");
                    helper.assertTrue(
                            relative(helper, pinned).x < 6.05,
                            "the crate pinned at the wall: " + relative(helper, pinned).x);
                    for (Body body : new Body[] {under, pinned, wall}) {
                        body.destroy();
                    }
                    onTop.discard();
                    shover.discard();
                    done(helper, 10, 8);
                });
    }

    /**
     * Pushed where the stand's face meets it nearest its centre: a crate it covers goes straight, a plank by its end
     * turns.
     */
    @GameTest(template = "empty", batch = "minebox3d_blocked_turn", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandPushesACrateStraightAndAPlankByItsEndRound(GameTestHelper helper) {
        area(helper, 10, 8);
        Body crate = box(helper, BodyType.DYNAMIC, 4, 0.3, 1.5, 0.3f, 0.3f, 0.3f, new ShapeDef().density(23));
        // along z from 4.25 to 7.25: the stand at 4.5 meets its end
        Body plank = box(helper, BodyType.DYNAMIC, 4, 0.15, 5.75, 0.15f, 0.15f, 1.5f, new ShapeDef().density(23));
        ArmorStand straight = stand(helper, 3.2, 0, 1.5);
        ArmorStand round = stand(helper, 3.2, 0, 4.5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, straight, round), "the stands tick"))
                .thenExecute(() -> {
                    straight.setDeltaMovement(0.6, 0, 0);
                    round.setDeltaMovement(0.6, 0, 0);
                })
                .thenExecuteAfter(20, () -> {
                    float crateTurn = Math.abs(EntityBases.yawOf(crate.rotation(new Quaternionf())));
                    float plankTurn = Math.abs(EntityBases.yawOf(plank.rotation(new Quaternionf())));
                    helper.assertTrue(relative(helper, crate).x - 4 > 0.3, "the crate went along");
                    helper.assertTrue(crateTurn < Math.toRadians(2), "straight: " + Math.toDegrees(crateTurn) + "°");
                    helper.assertTrue(
                            plankTurn > Math.toRadians(5), "the plank turned: " + Math.toDegrees(plankTurn) + "°");
                    crate.destroy();
                    plank.destroy();
                    straight.discard();
                    round.discard();
                    done(helper, 10, 8);
                });
    }

    /**
     * A crate a hair into a stand's box (Box3D leaves a body resting against an entity's proxy up to its slop in) is
     * still in its way: the stand shoved at it, pinned against a wall, is stopped by it rather than let through as from
     * inside.
     */
    @GameTest(template = "empty", batch = "minebox3d_blocked_hair", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aCrateAHairIntoAStandStillStopsIt(GameTestHelper helper) {
        area(helper, 10, 4);
        ArmorStand shover = stand(helper, 4.5, 0, 1.5); // its front at 4.75
        Body[] bodies = new Body[2];
        helper.onEachTick(() -> {
            if (bodies[0] != null && shover.isAlive()) {
                shover.setDeltaMovement(0.3, shover.getDeltaMovement().y, 0);
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, shover), "the stand ticks"))
                .thenExecute(() -> {
                    // the crate's face 4 mm inside the stand's front, the wall right behind it
                    bodies[0] = box(
                            helper, BodyType.DYNAMIC, 5.046, 0.3, 1.5, 0.3f, 0.3f, 0.3f, new ShapeDef().density(23));
                    bodies[1] = box(helper, BodyType.STATIC, 5.596, 1, 1.5, 0.25f, 1, 1, new ShapeDef());
                })
                .thenExecuteAfter(20, () -> {
                    double into = relative(helper, shover).x + 0.25 - (relative(helper, bodies[0]).x - 0.3);
                    helper.assertTrue(into < 0.01, "held at the crate, not through it: " + into + " in");
                    for (Body body : bodies) {
                        body.destroy();
                    }
                    shover.discard();
                    done(helper, 10, 4);
                });
    }

    /** Out of a body that moved into a stand, by the least way that leads into no other body. */
    @GameTest(template = "empty", batch = "minebox3d_blocked_pushout_other", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandIsPushedOutOfOneBodyNotIntoAnother(GameTestHelper helper) {
        area(helper, 6, 6);
        ArmorStand stand = stand(helper, 2, 0, 3); // x 1.75 to 2.25
        Body[] bodies = new Body[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, stand), "the stand ticks"))
                .thenExecute(() -> {
                    // into its east half; out west is the least way, but another body is there
                    bodies[0] = box(helper, BodyType.STATIC, 2.5, 1, 3, 0.5f, 1, 0.5f, new ShapeDef());
                    bodies[1] = box(helper, BodyType.STATIC, 1.35, 1, 3, 0.35f, 1, 0.5f, new ShapeDef());
                })
                .thenExecuteAfter(3, () -> {
                    helper.assertFalse(
                            EntityCollisions.anyBodyIn(
                                    helper.getLevel(), stand.getBoundingBox().deflate(0.02)),
                            "in neither body: " + relative(helper, stand));
                    for (Body body : bodies) {
                        body.destroy();
                    }
                    stand.discard();
                    done(helper, 6, 6);
                });
    }

    /**
     * A stand on a ball, and one on a lying capsule, stays on top, a few millimetres above: Box3D's sweep would let it
     * in.
     */
    @GameTest(template = "empty", batch = "minebox3d_blocked_round", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandOnABallOrALogStaysOnTop(GameTestHelper helper) {
        area(helper, 8, 4);
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        Body ball = physics(helper).createBody(new BodyDef().position(at.x, at.y, at.z));
        ball.createShape(new ShapeDef(), new Geometry.Sphere(0, 0, 0, 1)); // its top at 2
        at = helper.absoluteVec(new Vec3(5.5, 0.5, 1.5));
        Body log = physics(helper).createBody(new BodyDef().position(at.x, at.y, at.z));
        log.createShape(new ShapeDef(), new Geometry.Capsule(0, 0, -1, 0, 0, 1, 0.5f)); // its top at 1
        ArmorStand onBall = stand(helper, 1.5, 3, 1.5);
        ArmorStand onLog = stand(helper, 5.5, 2, 1.5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        ticking(helper, onBall, onLog) && onBall.onGround() && onLog.onGround(), "the stands landed"))
                .thenExecuteAfter(60, () -> {
                    double ballFeet = relative(helper, onBall).y - 2, logFeet = relative(helper, onLog).y - 1;
                    helper.assertTrue(
                            ballFeet > 0 && ballFeet < 0.01, "on the ball's top three seconds on: " + ballFeet);
                    helper.assertTrue(logFeet > 0 && logFeet < 0.01, "on the log's top three seconds on: " + logFeet);
                    ball.destroy();
                    log.destroy();
                    onBall.discard();
                    onLog.discard();
                    done(helper, 8, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_blocked_pushout", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aBodyMovingIntoAStandPushesItAhead(GameTestHelper helper) {
        area(helper, 12, 4);
        ArmorStand stand = stand(helper, 3, 0, 1.5);
        Body[] pusher = new Body[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, stand), "the stand ticks"))
                .thenExecute(() -> {
                    // kinematic: nothing in Box3D stops it, and entity proxies do not touch it
                    Vec3 at = helper.absoluteVec(new Vec3(1, 1.05, 1.5));
                    pusher[0] = physics(helper)
                            .createBody(new BodyDef()
                                    .type(BodyType.KINEMATIC)
                                    .position(at.x, at.y, at.z)
                                    .linearVelocity(3, 0, 0));
                    pusher[0].createShape(new ShapeDef(), Geometry.Box.of(0.5f, 1, 1));
                })
                .thenExecuteAfter(30, () -> {
                    double front = relative(helper, pusher[0]).x + 0.5;
                    double back = relative(helper, stand).x - 0.25;
                    helper.assertTrue(front > 5, "the body went on: its front at " + front);
                    helper.assertTrue(
                            back > front - 0.2,
                            "the stand kept ahead of it, not inside: its back at " + back + ", the body's front at "
                                    + front);
                    helper.assertTrue(Math.abs(relative(helper, stand).z - 1.5) < 0.01, "pushed straight on");
                    pusher[0].destroy();
                    stand.discard();
                    done(helper, 12, 4);
                });
    }

    /**
     * What a mod that sweeps its own entities, and the server's flying check, ask: a body under an entity standing on
     * it high up is "something around" (and none is, without it); a turned box swept at a wall meets its face, a sensor
     * in the way stops nothing, what the box starts on is no hit, and a round shape is refused.
     */
    @GameTest(template = "empty", batch = "minebox3d_blocked_queries", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void theQueriesForOtherModsSeeWhatStopsEntities(GameTestHelper helper) {
        area(helper, 8, 4);
        Body perch = box(helper, BodyType.STATIC, 1.5, 4.75, 1.5, 0.5f, 0.25f, 0.5f, new ShapeDef()); // its top at 5
        Body wall = box(helper, BodyType.STATIC, 6.25, 1, 1.5, 0.25f, 1, 1, new ShapeDef()); // its face at x = 6
        Body sensor = box(helper, BodyType.STATIC, 4, 1, 1.5, 0.25f, 1, 1, new ShapeDef().sensor(true));
        ArmorStand perched = stand(helper, 1.5, 5.5, 1.5);
        ArmorStand floating = stand(helper, 4, 5, 3.5);
        floating.setNoGravity(true);
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertTrue(ticking(helper, perched) && perched.onGround(), "the stand perched"))
                .thenExecute(() -> {
                    helper.assertTrue(EntityCollisions.isBodyUnder(perched), "a body under the perched stand");
                    // ⚠️ the broad phase hands out the perch and the wall too, millions out (floats a block apart)
                    helper.assertFalse(EntityCollisions.isBodyUnder(floating), "nothing under the floating one");

                    // a box turned a quarter about y, swept along +x at the wall through the sensor
                    Vec3 from = helper.absoluteVec(new Vec3(2, 1, 1.5));
                    Geometry.Box turned = new Geometry.Box(0.5f, 0.5f, 0.25f, 0, 0, 0, 0, 0.7071068f, 0, 0.7071068f);
                    CastResult hit = new CastResult();
                    helper.assertTrue(
                            EntityCollisions.sweep(
                                    helper.getLevel(), turned, from.x, from.y, from.z, new Quaternionf(), 6, 0, 0, hit),
                            "the sweep meets something");
                    helper.assertTrue(hit.body() == wall, "the wall, not the sensor before it");
                    // turned a quarter, the box's half-depth along x is 0.25: its front at the face, a few mm short
                    double front = from.x + 6 * hit.fraction() + 0.25 - helper.absoluteVec(Vec3.ZERO).x;
                    helper.assertTrue(front < 6 && front > 5.98, "stopped at the face: its front at " + front);

                    // standing on the perch, a sweep along its top meets nothing
                    Vec3 feet = perched.position();
                    helper.assertFalse(
                            EntityCollisions.sweep(
                                    helper.getLevel(),
                                    Geometry.Box.of(0.25f, 0.5f, 0.25f),
                                    feet.x,
                                    feet.y + 0.5,
                                    feet.z,
                                    new Quaternionf(),
                                    0,
                                    0,
                                    0.2f,
                                    hit),
                            "sliding along what it stands on");
                    // a round one could not stop short of a mesh body: refused whatever is near
                    try {
                        EntityCollisions.sweep(
                                helper.getLevel(),
                                new Geometry.Sphere(0, 0, 0, 0.3f),
                                from.x,
                                from.y,
                                from.z,
                                new Quaternionf(),
                                1,
                                0,
                                0,
                                hit);
                        helper.fail("a sphere was swept");
                    } catch (IllegalArgumentException expected) {
                        // refused
                    }
                    perch.destroy();
                    wall.destroy();
                    sensor.destroy();
                    perched.discard();
                    floating.discard();
                    done(helper, 8, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_blocked_through", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aSensorOrAShapeThatLetsEntitiesThroughStopsNothing(GameTestHelper helper) {
        area(helper, 8, 4);
        Body sensor = box(helper, BodyType.STATIC, 1.5, 2.25, 1.5, 1, 0.25f, 1, new ShapeDef().sensor(true));
        ShapeDef through = new ShapeDef();
        through.filter.maskBits = ~CollisionCategories.ENTITY;
        Body ghost = box(helper, BodyType.STATIC, 5.5, 2.25, 1.5, 1, 0.25f, 1, through);
        ArmorStand onSensor = stand(helper, 1.5, 4.5, 1.5);
        ArmorStand onGhost = stand(helper, 5.5, 4.5, 1.5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, onSensor, onGhost), "the stands tick"))
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(
                            relative(helper, onSensor).y < 0.01,
                            "through the sensor to the floor: " + relative(helper, onSensor).y);
                    helper.assertTrue(
                            relative(helper, onGhost).y < 0.01,
                            "through the shape that masks out entities: " + relative(helper, onGhost).y);
                    sensor.destroy();
                    ghost.destroy();
                    onSensor.discard();
                    onGhost.discard();
                    done(helper, 8, 4);
                });
    }
}
