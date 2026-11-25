package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityBases;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.MeshData;
import com.lowdragmc.minebox3d.physics.RevoluteJointDef;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>Standing on a body</b> (design doc {@code 04-entities-and-bodies.md} §11, batch 4c-2): a stand on a moving platform
 * goes along with it, turns with a turning one, rides a lift up, and is held back by a wall the deck carries it into;
 * a jump on a moving deck comes down where it went up; off the edge, or too long in the air, it goes on with the
 * platform's speed; dropped onto a moving deck it settles on it; it takes the next deck it slides onto, a mesh too;
 * put elsewhere on its deck it is not pulled back, and it is let go as it is when teleported off, seated, or when its
 * deck is destroyed or put far away; one a mod carries itself is left to it; and its weight tips a plank on a pivot.
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class EntityBaseGameTest {

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

    /** A kinematic platform, still until it is given a velocity, its top at {@code y + hy}. */
    private static Body platform(GameTestHelper helper, double x, double y, double z, float hx, float hy, float hz) {
        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        Body body = physics(helper)
                .createBody(new BodyDef().type(BodyType.KINEMATIC).position(at.x, at.y, at.z));
        body.createShape(new ShapeDef(), Geometry.Box.of(hx, hy, hz));
        return body;
    }

    private static ArmorStand stand(GameTestHelper helper, double x, double y, double z) {
        return helper.spawn(EntityType.ARMOR_STAND, new Vec3(x, y, z));
    }

    /** Ticking, on the ground, and on {@code base}. */
    private static boolean standsOn(GameTestHelper helper, Entity entity, Body base) {
        return TestArea.ticks(helper, entity) && entity.onGround() && EntityBases.baseOf(entity) == base;
    }

    private static Vec3 relative(GameTestHelper helper, Entity entity) {
        return entity.position().subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)));
    }

    private static Vector3d relative(GameTestHelper helper, Body body) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return body.position(new Vector3d()).sub(origin.getX(), origin.getY(), origin.getZ());
    }

    /** The entity's feet less the body's position. */
    private static Vec3 offset(Entity entity, Body body) {
        Vector3d p = body.position(new Vector3d());
        return new Vec3(entity.getX() - p.x, entity.getY() - p.y, entity.getZ() - p.z);
    }

    @GameTest(template = "empty", batch = "minebox3d_base_along", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandGoesAlongWithAMovingPlatform(GameTestHelper helper) {
        area(helper, 12, 4);
        Body deck = platform(helper, 2.5, 1.25, 1.5, 1.5f, 0.25f, 1); // its top at 1.5
        ArmorStand stand = stand(helper, 3, 2, 1.5);
        Vec3[] start = new Vec3[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, deck), "the stand stands on the deck"))
                .thenExecute(() -> {
                    start[0] = offset(stand, deck);
                    deck.setLinearVelocity(3, 0, 0);
                })
                .thenExecuteAfter(30, () -> {
                    double went = relative(helper, deck).x - 2.5;
                    helper.assertTrue(went > 4, "the deck went along: " + went);
                    Vec3 now = offset(stand, deck);
                    helper.assertTrue(
                            now.distanceTo(start[0]) < 0.05,
                            "the stand stayed where it stood on it: " + start[0] + " then, " + now + " now");
                    helper.assertTrue(stand.onGround() && EntityBases.baseOf(stand) == deck, "on it all along");
                    Vector3f v = EntityBases.baseVelocity(stand, new Vector3f());
                    helper.assertTrue(
                            Math.abs(v.x - 3) < 0.01 && Math.abs(v.y) < 0.01, "the base's speed under it: " + v);
                    deck.destroy();
                    stand.discard();
                    done(helper, 12, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_base_turn", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandTurnsWithATurningPlatform(GameTestHelper helper) {
        area(helper, 6, 6);
        Body deck = platform(helper, 3, 1.25, 3, 1.5f, 0.25f, 1.5f);
        ArmorStand stand = stand(helper, 4, 2, 3); // a block east of the middle
        float[] yaw = new float[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, deck), "the stand stands on the deck"))
                .thenExecute(() -> {
                    yaw[0] = stand.getYRot();
                    deck.setAngularVelocity(
                            0, (float) (Math.PI / 2), 0); // a quarter turn a second, counter-clockwise from above
                })
                .thenExecuteAfter(20, () -> {
                    deck.setAngularVelocity(0, 0, 0);
                    // a quarter turn about +Y takes east (+X) to north (-Z)
                    Vec3 now = offset(stand, deck);
                    helper.assertTrue(
                            Math.abs(now.x) < 0.1 && Math.abs(now.z + 1) < 0.1, "carried round a quarter: " + now);
                    float turned = stand.getYRot() - yaw[0];
                    helper.assertTrue(
                            Math.abs(turned + 90) < 5, "and turned with it (yaw clockwise from above): " + turned);
                    deck.destroy();
                    stand.discard();
                    done(helper, 6, 6);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_base_lift", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandRidesALiftUp(GameTestHelper helper) {
        area(helper, 4, 4);
        Body lift = platform(helper, 1.5, 0.75, 1.5, 1, 0.25f, 1); // its top at 1
        ArmorStand stand = stand(helper, 1.5, 1.5, 1.5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, lift), "the stand stands on the lift"))
                .thenExecute(() -> lift.setLinearVelocity(0, 2, 0))
                .thenExecuteAfter(20, () -> {
                    lift.setLinearVelocity(0, 0, 0);
                    double top = relative(helper, lift).y + 0.25, feet = relative(helper, stand).y;
                    helper.assertTrue(top > 2.8, "the lift rose two blocks: its top at " + top);
                    helper.assertTrue(
                            feet - top > 0 && feet - top < 0.02,
                            "the stand on it still: its feet " + (feet - top) + " above the top");
                    lift.destroy();
                    stand.discard();
                    done(helper, 4, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_base_jump", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aJumpOnAMovingDeckComesDownWhereItWentUp(GameTestHelper helper) {
        area(helper, 16, 4);
        Body deck = platform(helper, 4, 1.25, 1.5, 3, 0.25f, 1); // from 1 to 7 along x
        ArmorStand stand = stand(helper, 2, 2, 1.5);
        Vec3[] start = new Vec3[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, deck), "the stand stands on the deck"))
                .thenExecute(() -> deck.setLinearVelocity(3, 0, 0))
                .thenExecuteAfter(5, () -> {
                    start[0] = offset(stand, deck);
                    stand.setDeltaMovement(0, 0.42, 0); // a jump
                })
                .thenWaitUntil(
                        () -> helper.assertTrue(stand.getDeltaMovement().y < 0 && stand.onGround(), "down again"))
                .thenExecute(() -> {
                    Vec3 now = offset(stand, deck);
                    helper.assertTrue(
                            Math.abs(now.x - start[0].x) < 0.1,
                            "where it went up, on the moving deck: " + start[0] + " then, " + now + " now");
                    helper.assertTrue(EntityBases.baseOf(stand) == deck, "on it again");
                    deck.destroy();
                    stand.discard();
                    done(helper, 16, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_base_off", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void offTheEdgeOfAMovingDeckGoesOnWithIt(GameTestHelper helper) {
        area(helper, 16, 6);
        Body deck = platform(helper, 4, 1.25, 1.5, 3, 0.25f, 1); // its edge at z = 2.5
        ArmorStand stand = stand(helper, 3, 2, 2);
        double[] left = new double[1], landed = new double[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, deck), "the stand stands on the deck"))
                .thenExecute(() -> deck.setLinearVelocity(3, 0, 0))
                .thenExecuteAfter(5, () -> {
                    left[0] = relative(helper, stand).x;
                    stand.setDeltaMovement(0, 0, 0.6); // off the side
                })
                .thenWaitUntil(
                        () -> helper.assertTrue(relative(helper, stand).y < 0.01 && stand.onGround(), "on the floor"))
                .thenExecute(() -> {
                    landed[0] = relative(helper, stand).x;
                    helper.assertTrue(
                            landed[0] - left[0] > 0.5,
                            "carried along the deck's way as it fell: " + (landed[0] - left[0]));
                    helper.assertTrue(EntityBases.baseOf(stand) == null, "and has no base now");
                })
                .thenExecuteAfter(5, () -> {
                    // the deck's speed handed over on landing: it slides on, the floor's friction slowing it
                    double slid = relative(helper, stand).x - landed[0];
                    helper.assertTrue(slid > 0.1, "on the floor, it slid on along the deck's way: " + slid);
                    deck.destroy();
                    stand.discard();
                    done(helper, 16, 6);
                });
    }

    /**
     * Dropped straight down onto a moving deck, a stand meets it a deck's speed behind, and slides back a little till the
     * deck's friction has it — as a box dropped on a belt — then rides along; it is not flung on by the deck's speed twice.
     */
    @GameTest(template = "empty", batch = "minebox3d_base_landing", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandDroppedOntoAMovingDeckSettlesOnIt(GameTestHelper helper) {
        area(helper, 16, 4);
        Body deck = platform(helper, 4, 1.25, 1.5, 3, 0.25f, 1);
        Vec3[] landed = new Vec3[2];
        ArmorStand[] stand = new ArmorStand[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper), "the area ticks"))
                .thenExecute(() -> deck.setLinearVelocity(2, 0, 0))
                .thenExecuteAfter(2, () -> stand[0] = stand(helper, 4.5, 3, 1.5))
                .thenWaitUntil(() -> helper.assertTrue(
                        stand[0].onGround() && EntityBases.baseOf(stand[0]) == deck, "it landed on the deck"))
                .thenExecute(() -> landed[0] = offset(stand[0], deck))
                .thenExecuteAfter(15, () -> landed[1] = offset(stand[0], deck))
                .thenExecuteAfter(15, () -> {
                    Vec3 now = offset(stand[0], deck);
                    double slid = landed[1].x - landed[0].x;
                    helper.assertTrue(slid < 0 && slid > -0.3, "slid back a little on landing: " + slid);
                    helper.assertTrue(
                            Math.abs(now.x - landed[1].x) < 0.01,
                            "then rode along where it stopped: " + landed[1] + " then, " + now + " now");
                    deck.destroy();
                    stand[0].discard();
                    done(helper, 16, 4);
                });
    }

    /** A deck carrying a stand into a wall: the wall holds the stand back and the deck slides on under it. */
    @GameTest(template = "empty", batch = "minebox3d_base_wall", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aDeckCarryingAStandIntoAWallLeavesItAtTheWall(GameTestHelper helper) {
        area(helper, 14, 4);
        Body deck = platform(helper, 3, 1.25, 1.5, 2.5f, 0.25f, 1); // from 0.5 to 5.5
        Vec3 at = helper.absoluteVec(new Vec3(6.75, 2.75, 1.5));
        Body wall = physics(helper)
                .createBody(new BodyDef().position(at.x, at.y, at.z)); // its face at x = 6.5, above the deck
        wall.createShape(new ShapeDef(), Geometry.Box.of(0.25f, 1.25f, 1));
        ArmorStand stand = stand(helper, 4, 2, 1.5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, deck), "the stand stands on the deck"))
                .thenExecute(() -> deck.setLinearVelocity(2, 0, 0))
                .thenExecuteAfter(30, () -> {
                    double front = relative(helper, stand).x + 0.25;
                    helper.assertTrue(front <= 6.5 && front > 6.4, "held at the wall's face: " + front);
                    deck.destroy();
                    wall.destroy();
                    stand.discard();
                    done(helper, 14, 4);
                });
    }

    /**
     * One put elsewhere on its deck is not pulled back (let go, it stands on the deck again where it was put); and a deck
     * destroyed under it lets it go.
     */
    @GameTest(template = "empty", batch = "minebox3d_base_moved", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandPutElsewhereIsNotPulledBackAndALostDeckLetsGo(GameTestHelper helper) {
        area(helper, 16, 6);
        Body deck = platform(helper, 4, 1.25, 2.5, 3, 0.25f, 2);
        ArmorStand stand = stand(helper, 4, 2, 2);
        double[] put = new double[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, deck), "the stand stands on the deck"))
                .thenExecute(() -> deck.setLinearVelocity(1, 0, 0))
                .thenExecuteAfter(3, () -> {
                    // a short teleport, along the deck, between its moves
                    stand.setPos(stand.getX(), stand.getY(), stand.getZ() + 1.5);
                    put[0] = relative(helper, stand).z;
                })
                .thenExecuteAfter(3, () -> {
                    helper.assertTrue(
                            Math.abs(relative(helper, stand).z - put[0]) < 0.01,
                            "not pulled back: z = " + relative(helper, stand).z + ", put at " + put[0]);
                    deck.destroy();
                })
                .thenExecuteAfter(10, () -> {
                    helper.assertTrue(EntityBases.baseOf(stand) == null, "no base once the deck is gone");
                    helper.assertTrue(relative(helper, stand).y < 0.01, "and it fell to the floor");
                    stand.discard();
                    done(helper, 16, 6);
                });
    }

    /**
     * Teleported off a moving deck into the air, a stand is let go as it is: not carried along by the deck while it
     * falls, nor given the deck's speed — it comes down straight under where it was put.
     */
    @GameTest(template = "empty", batch = "minebox3d_base_teleport", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandTeleportedOffAMovingDeckIsLetGoAsItIs(GameTestHelper helper) {
        area(helper, 16, 8);
        Body deck = platform(helper, 4, 1.25, 1.5, 3, 0.25f, 1);
        ArmorStand stand = stand(helper, 3, 2, 1.5);
        Vec3 put = new Vec3(12.5, 4, 6.5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, deck), "the stand stands on the deck"))
                .thenExecute(() -> deck.setLinearVelocity(3, 0, 0))
                .thenExecuteAfter(5, () -> {
                    Vec3 at = helper.absoluteVec(put);
                    stand.teleportTo(at.x, at.y, at.z);
                })
                .thenWaitUntil(
                        () -> helper.assertTrue(stand.onGround() && relative(helper, stand).y < 0.01, "on the floor"))
                .thenExecuteAfter(5, () -> {
                    Vec3 now = relative(helper, stand);
                    helper.assertTrue(
                            Math.abs(now.x - put.x) < 0.01 && Math.abs(now.z - put.z) < 0.01,
                            "straight down under where it was put: " + now);
                    helper.assertTrue(EntityBases.baseOf(stand) == null, "with no base");
                    deck.destroy();
                    stand.discard();
                    done(helper, 16, 8);
                });
    }

    /** A stand on a deck that sits in a boat is the boat's to move: the deck lets it go. */
    @GameTest(template = "empty", batch = "minebox3d_base_seated", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandThatTakesASeatIsLetGo(GameTestHelper helper) {
        area(helper, 16, 8);
        Body deck = platform(helper, 4, 1.25, 1.5, 3, 0.25f, 1);
        ArmorStand stand = stand(helper, 3, 2, 1.5);
        Entity boat = helper.spawn(EntityType.BOAT, new Vec3(4, 0, 4.5));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        standsOn(helper, stand, deck) && TestArea.ticks(helper, boat),
                        "the stand stands on the deck, the boat ticks"))
                .thenExecute(() -> {
                    deck.setLinearVelocity(3, 0, 0);
                    helper.assertTrue(stand.startRiding(boat, true), "seated");
                })
                .thenExecuteAfter(10, () -> {
                    helper.assertTrue(EntityBases.baseOf(stand) == null, "no base while seated");
                    helper.assertTrue(
                            stand.getVehicle() == boat && stand.distanceTo(boat) < 1.5,
                            "where the boat has it: " + relative(helper, stand));
                    deck.destroy();
                    stand.discard();
                    boat.discard();
                    done(helper, 16, 8);
                });
    }

    /** A deck put somewhere far in one step lets its stand go, where it was. */
    @GameTest(template = "empty", batch = "minebox3d_base_lost", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aDeckPutFarAwayLetsItsStandGo(GameTestHelper helper) {
        area(helper, 6, 4);
        Body deck = platform(helper, 2.5, 1.25, 1.5, 1.5f, 0.25f, 1);
        ArmorStand stand = stand(helper, 2.5, 2, 1.5);
        double[] x = new double[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, deck), "the stand stands on the deck"))
                .thenExecute(() -> {
                    x[0] = relative(helper, stand).x;
                    Vec3 far = helper.absoluteVec(new Vec3(2.5, 30, 1.5));
                    deck.setTransform(far.x, far.y, far.z, new Quaternionf());
                })
                .thenWaitUntil(
                        () -> helper.assertTrue(stand.onGround() && relative(helper, stand).y < 0.01, "on the floor"))
                .thenExecute(() -> {
                    helper.assertTrue(EntityBases.baseOf(stand) == null, "let go");
                    helper.assertTrue(Math.abs(relative(helper, stand).x - x[0]) < 0.01, "straight down");
                    deck.destroy();
                    stand.discard();
                    done(helper, 6, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_base_air", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandTooLongInTheAirIsLetGoWithTheDecksSpeed(GameTestHelper helper) {
        area(helper, 16, 4);
        Body deck = platform(helper, 4, 1.25, 1.5, 3, 0.25f, 1);
        ArmorStand stand = stand(helper, 2, 2, 1.5);
        int[] since = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, deck), "the stand stands on the deck"))
                .thenExecute(() -> {
                    deck.setLinearVelocity(3, 0, 0);
                    stand.setDeltaMovement(0, 1, 0); // a jump of some 25 ticks
                    since[0] = stand.tickCount;
                })
                .thenWaitUntil(() -> helper.assertTrue(EntityBases.baseOf(stand) == null, "let go"))
                .thenExecute(() -> {
                    int air = stand.tickCount - since[0];
                    helper.assertTrue(air > 17 && air <= 22, "after its 20 ticks in the air: " + air);
                    helper.assertTrue(
                            !stand.onGround() && stand.getDeltaMovement().x > 0.1,
                            "going on along the deck's way: " + stand.getDeltaMovement());
                    deck.destroy();
                    stand.discard();
                    done(helper, 16, 4);
                });
    }

    /** A stand slid from one deck onto the next has the next one as its base, and stands on a mesh as on a box. */
    @GameTest(template = "empty", batch = "minebox3d_base_next", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandSlidOntoTheNextDeckTakesIt(GameTestHelper helper) {
        area(helper, 8, 4);
        Body first = platform(helper, 2, 1.25, 1.5, 1.5f, 0.25f, 1); // x 0.5 to 3.5
        Vec3 at = helper.absoluteVec(new Vec3(3.5, 1.5, 0.5));
        Body next = physics(helper).createBody(new BodyDef().position(at.x, at.y, at.z));
        MeshData quad =
                MeshData.create(new float[] {0, 0, 0, 3, 0, 0, 0, 0, 2, 3, 0, 2}, new int[] {0, 2, 1, 1, 2, 3}, true);
        next.createShape(new ShapeDef(), Geometry.Mesh.of(quad)); // x 3.5 to 6.5, at the first one's top
        quad.close();
        ArmorStand stand = stand(helper, 2, 2, 1.5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, first), "the stand stands on the first"))
                .thenExecute(() -> stand.setDeltaMovement(0.9, 0, 0)) // about two blocks
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, next), "on the next, a mesh"))
                .thenExecuteAfter(5, () -> {
                    double feet = relative(helper, stand).y - 1.5;
                    helper.assertTrue(feet >= 0 && feet < 0.01, "on its surface: " + feet);
                    first.destroy();
                    next.destroy();
                    stand.discard();
                    done(helper, 8, 4);
                });
    }

    private static final String CARRIES_ITSELF = "minebox3d_test_carries_itself";

    static {
        EntityBases.registerCarrier(entity -> entity.getTags().contains(CARRIES_ITSELF));
    }

    /** A stand a mod carries itself is left to it: its base is kept, but MineBox3D does not move it. */
    @GameTest(template = "empty", batch = "minebox3d_base_carrier", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandAModCarriesItselfIsLeftToIt(GameTestHelper helper) {
        area(helper, 12, 4);
        Body deck = platform(helper, 4, 1.25, 1.5, 3, 0.25f, 1);
        ArmorStand stand = stand(helper, 2, 2, 1.5);
        stand.addTag(CARRIES_ITSELF);
        double[] x = new double[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsOn(helper, stand, deck), "the stand stands on the deck"))
                .thenExecute(() -> {
                    x[0] = relative(helper, stand).x;
                    deck.setLinearVelocity(2, 0, 0);
                })
                .thenExecuteAfter(10, () -> {
                    helper.assertTrue(
                            Math.abs(relative(helper, stand).x - x[0]) < 0.05,
                            "not carried: " + (relative(helper, stand).x - x[0]));
                    helper.assertTrue(EntityBases.baseOf(stand) == deck, "its base kept, for its mod");
                    deck.destroy();
                    stand.discard();
                    done(helper, 12, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_base_weight", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandsWeightTipsAPlankOnAPivot(GameTestHelper helper) {
        area(helper, 8, 4);
        Vec3 pivot = helper.absoluteVec(new Vec3(4, 2.5, 1.5));
        Body anchor = physics(helper).createBody(new BodyDef().position(pivot.x, pivot.y, pivot.z));
        Body plank =
                physics(helper).createBody(new BodyDef().type(BodyType.DYNAMIC).position(pivot.x, pivot.y, pivot.z));
        plank.createShape(new ShapeDef().density(100), Geometry.Box.of(2.5f, 0.1f, 0.5f)); // 100 kg, x from 1.5 to 6.5
        var hinge = physics(helper)
                .world()
                .createJoint(
                        new RevoluteJointDef().bodies(anchor, plank).worldFrame(pivot.x, pivot.y, pivot.z)); // about z
        ArmorStand[] stand = new ArmorStand[1];
        helper.startSequence()
                // once its chunks tick: on it a moment, it tips the plank and slides off
                .thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper), "the area ticks"))
                .thenExecute(() -> stand[0] = stand(helper, 6, 3, 1.5)) // over the east end
                .thenWaitUntil(
                        () -> helper.assertTrue(standsOn(helper, stand[0], plank), "the stand stands on the plank"))
                .thenExecuteAfter(10, () -> {
                    Vector3f east = plank.rotation(new Quaternionf()).transform(new Vector3f(1, 0, 0));
                    helper.assertTrue(east.y < -0.05, "the east end went down under it: " + east);
                    hinge.destroy(false);
                    plank.destroy();
                    anchor.destroy();
                    stand[0].discard();
                    done(helper, 8, 4);
                });
    }

    /** The same plank under a stand whose moves a mod says push no body (Unreal's Mover): it stays level. */
    @GameTest(template = "empty", batch = "minebox3d_base_no_weight", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandWhoseMovesPushNothingLeavesThePlankLevel(GameTestHelper helper) {
        area(helper, 8, 4);
        Vec3 pivot = helper.absoluteVec(new Vec3(4, 2.5, 1.5));
        Body anchor = physics(helper).createBody(new BodyDef().position(pivot.x, pivot.y, pivot.z));
        Body plank =
                physics(helper).createBody(new BodyDef().type(BodyType.DYNAMIC).position(pivot.x, pivot.y, pivot.z));
        plank.createShape(new ShapeDef().density(100), Geometry.Box.of(2.5f, 0.1f, 0.5f));
        var hinge = physics(helper)
                .world()
                .createJoint(new RevoluteJointDef().bodies(anchor, plank).worldFrame(pivot.x, pivot.y, pivot.z));
        ArmorStand[] stand = new ArmorStand[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper), "the area ticks"))
                .thenExecute(() -> stand[0] = EntityAuthorityGameTest.pushingNothing(stand(helper, 6, 3, 1.5)))
                .thenWaitUntil(
                        () -> helper.assertTrue(standsOn(helper, stand[0], plank), "the stand stands on the plank"))
                .thenExecute(() -> {
                    // level and still again after the landing, which is no weight: from here only a weight would tip it
                    plank.setTransform(pivot.x, pivot.y, pivot.z, new Quaternionf());
                    plank.setLinearVelocity(0, 0, 0);
                    plank.setAngularVelocity(0, 0, 0);
                })
                .thenExecuteAfter(10, () -> {
                    Vector3f east = plank.rotation(new Quaternionf()).transform(new Vector3f(1, 0, 0));
                    helper.assertTrue(Math.abs(east.y) < 0.01, "level under it: " + east);
                    hinge.destroy(false);
                    plank.destroy();
                    anchor.destroy();
                    stand[0].discard();
                    done(helper, 8, 4);
                });
    }
}
