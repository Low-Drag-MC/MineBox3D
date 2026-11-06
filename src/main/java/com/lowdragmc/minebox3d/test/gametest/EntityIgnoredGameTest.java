package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityBases;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import com.lowdragmc.minebox3d.mc.entity.EntityShapes;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.BoxSweep;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * <b>Entities that meet no body, and bodies that never meet one entity</b> (design doc {@code 04-entities-and-bodies.md}
 * §17): an entity whose physics collision is off has no proxy and moves through every body; a body ignoring an entity is
 * neither touched by its proxy nor in the way of its moves and sweeps, and meets every other entity as before; a moving
 * deck an entity stops meeting lets it go as it is.
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class EntityIgnoredGameTest {

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

    /** A body of one box at a point relative to the test, its shapes asking Box3D's custom filter. */
    private static Body box(GameTestHelper helper, BodyType type, double x, double y, double z, float hx, float hy,
                            float hz) {
        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        Body body = physics(helper).createBody(new BodyDef().type(type).position(at.x, at.y, at.z));
        ShapeDef def = new ShapeDef().density(500);
        def.enableCustomFiltering = true;
        body.createShape(def, Geometry.Box.of(hx, hy, hz));
        return body;
    }

    private static ArmorStand stand(GameTestHelper helper, double x, double y, double z) {
        return helper.spawn(EntityType.ARMOR_STAND, new Vec3(x, y, z));
    }

    private static Vec3 relative(GameTestHelper helper, ArmorStand entity) {
        return entity.position().subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)));
    }

    private static Vector3d relative(GameTestHelper helper, Body body) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return body.position(new Vector3d()).sub(origin.getX(), origin.getY(), origin.getZ());
    }

    @GameTest(template = "empty", batch = "minebox3d_ignored_off", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandWithItsPhysicsCollisionOffMeetsNoBody(GameTestHelper helper) {
        area(helper, 8, 4);
        Body slab = box(helper, BodyType.STATIC, 1.5, 2.25, 1.5, 1, 0.25f, 1); // its top at 2.5
        ArmorStand falling = stand(helper, 1.5, 4.5, 1.5);
        ArmorStand standing = stand(helper, 5.5, 0, 1.5);
        EntityShapes.setPhysicsCollision(falling, false);
        EntityShapes.setPhysicsCollision(standing, false);
        Body[] crate = new Body[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, falling, standing), "the stands tick"))
                .thenExecute(() -> crate[0] = box(helper, BodyType.DYNAMIC, 5.5, 3.5, 1.5, 0.25f, 0.25f, 0.25f))
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(relative(helper, falling).y < 0.01, "through the slab to the floor: "
                            + relative(helper, falling).y);
                    helper.assertTrue(physics(helper).entities().proxyOf(standing) == null, "no proxy for the other");
                    double y = relative(helper, crate[0]).y;
                    helper.assertTrue(y < 0.3, "a crate dropped on it fell through it to the floor: y = " + y);
                    helper.assertFalse(EntityCollisions.isBodyUnder(falling), "and no body is under it, to it");
                    // back on: a proxy again (a moving body near it), and what falls on it lands on it
                    crate[0].destroy();
                    EntityShapes.setPhysicsCollision(standing, true);
                    crate[1] = box(helper, BodyType.DYNAMIC, 5.5, 3.5, 1.5, 0.25f, 0.25f, 0.25f);
                })
                .thenExecuteAfter(3, () -> helper.assertTrue(physics(helper).entities().proxyOf(standing) != null,
                        "a proxy once it is back on"))
                .thenExecuteAfter(40, () -> {
                    double y = relative(helper, crate[1]).y;
                    helper.assertTrue(y > 2.1 && y < 2.3, "the next crate lands on the stand's top: y = " + y);
                    slab.destroy();
                    crate[1].destroy();
                    falling.discard();
                    standing.discard();
                    done(helper, 8, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_ignored_proxy", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aCrateIgnoringAStandFallsThroughItAndLandsOnAnother(GameTestHelper helper) {
        area(helper, 8, 4);
        ArmorStand ignored = stand(helper, 1.5, 0, 1.5);
        ArmorStand other = stand(helper, 5.5, 0, 1.5);
        Body[] crates = new Body[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, ignored, other), "the stands tick"))
                .thenExecute(() -> {
                    // both ignore the first stand
                    crates[0] = box(helper, BodyType.DYNAMIC, 1.5, 3.5, 1.5, 0.25f, 0.25f, 0.25f);
                    crates[1] = box(helper, BodyType.DYNAMIC, 5.5, 3.5, 1.5, 0.25f, 0.25f, 0.25f);
                    for (Body crate : crates) {
                        LevelBodies.setIgnoredEntity(crate, ignored);
                        helper.assertTrue(LevelBodies.ignores(crate, ignored) && !LevelBodies.ignores(crate, other),
                                "it ignores the first stand only");
                    }
                })
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(physics(helper).entities().proxyOf(ignored) != null, "the first stand has its proxy");
                    double through = relative(helper, crates[0]).y;
                    helper.assertTrue(through < 0.3, "through the stand it ignores to the floor: y = " + through);
                    double on = relative(helper, crates[1]).y;
                    helper.assertTrue(on > 2.1 && on < 2.3, "onto the other stand's top: y = " + on);
                    // the one resting on the other stand now ignores that one: let go where it rests
                    LevelBodies.setIgnoredEntity(crates[1], other);
                    // and one ignoring nobody again lands on the first
                    crates[0].destroy();
                    crates[0] = box(helper, BodyType.DYNAMIC, 1.5, 3.5, 1.5, 0.25f, 0.25f, 0.25f);
                    LevelBodies.setIgnoredEntity(crates[0], ignored);
                    LevelBodies.setIgnoredEntity(crates[0], null);
                })
                .thenExecuteAfter(40, () -> {
                    double through = relative(helper, crates[1]).y;
                    helper.assertTrue(through < 0.3, "let go, it fell through the stand it rested on: y = " + through);
                    double y = relative(helper, crates[0]).y;
                    helper.assertTrue(y > 2.1 && y < 2.3, "ignoring nobody again, it lands on the first stand: y = " + y);
                    for (Body crate : crates) {
                        crate.destroy();
                    }
                    ignored.discard();
                    other.discard();
                    done(helper, 8, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_ignored_base", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandOnAMovingDeckItStopsMeetingIsLetGoAsItIs(GameTestHelper helper) {
        area(helper, 16, 6);
        // their tops at 1.5, from x = 1 to 7
        Body ignoring = box(helper, BodyType.KINEMATIC, 4, 1.25, 1.5, 3, 0.25f, 1);
        Body deck = box(helper, BodyType.KINEMATIC, 4, 1.25, 4.5, 3, 0.25f, 1);
        ArmorStand ignored = stand(helper, 2.5, 2, 1.5);
        ArmorStand off = stand(helper, 2.5, 2, 4.5);
        double[] x = new double[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, ignored, off) && ignored.onGround()
                        && off.onGround() && EntityBases.baseOf(ignored) == ignoring && EntityBases.baseOf(off) == deck,
                        "each stands on its deck"))
                .thenExecute(() -> {
                    ignoring.setLinearVelocity(3, 0, 0);
                    deck.setLinearVelocity(3, 0, 0);
                })
                .thenExecuteAfter(5, () -> {
                    x[0] = relative(helper, ignored).x;
                    x[1] = relative(helper, off).x;
                    helper.assertTrue(x[0] > 2.75 && x[1] > 2.75, "carried along at first: " + x[0] + ", " + x[1]);
                    LevelBodies.setIgnoredEntity(ignoring, ignored);
                    EntityShapes.setPhysicsCollision(off, false);
                    helper.assertTrue(EntityBases.baseOf(ignored) == null && EntityBases.baseOf(off) == null,
                            "neither has a base from now on");
                })
                .thenExecuteAfter(30, () -> {
                    Vec3 a = relative(helper, ignored), b = relative(helper, off);
                    helper.assertTrue(a.y < 0.01 && b.y < 0.01, "both fell through to the floor: " + a.y + ", " + b.y);
                    // carried on for a jump's while, or given the deck's speed, either would be well over a block on
                    helper.assertTrue(Math.abs(a.x - x[0]) < 0.3, "the one its deck ignores went neither on with it nor "
                            + "flung: from " + x[0] + " to " + a.x);
                    helper.assertTrue(Math.abs(b.x - x[1]) < 0.3, "nor the one whose collision is off: from " + x[1]
                            + " to " + b.x);
                    ignoring.destroy();
                    deck.destroy();
                    ignored.discard();
                    off.discard();
                    done(helper, 16, 6);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_ignored_moves", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aStandMovesThroughABodyIgnoringItWhereAnotherStops(GameTestHelper helper) {
        area(helper, 9, 6);
        // across both lanes, its face at x = 5
        Body wall = box(helper, BodyType.STATIC, 5.25, 1, 3, 0.25f, 1, 2.5f);
        ArmorStand ignored = stand(helper, 3, 0, 1.5);
        ArmorStand other = stand(helper, 3, 0, 4.5);
        LevelBodies.setIgnoredEntity(wall, ignored);
        Vec3 at = helper.absoluteVec(new Vec3(4.5, 1, 3));
        AABB inWall = new AABB(at.x + 0.5, at.y - 0.5, at.z - 0.5, at.x + 1, at.y + 0.5, at.z + 0.5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TestArea.ticks(helper, ignored, other), "the stands tick"))
                .thenExecute(() -> {
                    // the queries for other mods, as each stand's own
                    helper.assertTrue(EntityCollisions.anyBodyIn(helper.getLevel(), inWall), "the wall is there to nobody");
                    helper.assertTrue(EntityCollisions.anyBodyIn(helper.getLevel(), inWall, other), "and to the other");
                    helper.assertFalse(EntityCollisions.anyBodyIn(helper.getLevel(), inWall, ignored), "not to the one");
                    BoxSweep sweep = new BoxSweep();
                    helper.assertTrue(EntityCollisions.sweepBox(helper.getLevel(), at.x, at.y, at.z, new Quaternionf(),
                            0.25f, 0.25f, 0.25f, 2, 0, 0, false, null, other, sweep), "the other's sweep meets it");
                    helper.assertFalse(EntityCollisions.sweepBox(helper.getLevel(), at.x, at.y, at.z, new Quaternionf(),
                            0.25f, 0.25f, 0.25f, 2, 0, 0, false, null, ignored, sweep), "the one's goes through");
                    ignored.setDeltaMovement(1.5, 0, 0);
                    other.setDeltaMovement(1.5, 0, 0);
                })
                .thenExecuteAfter(30, () -> {
                    double through = relative(helper, ignored).x;
                    helper.assertTrue(through > 5.75, "the one walked through the wall: x = " + through);
                    double front = relative(helper, other).x + 0.25;
                    helper.assertTrue(front <= 5.0 && front > 4.9, "the other stopped at its face: " + front);
                    // its physics collision off, the wall is not there to the other either
                    EntityShapes.setPhysicsCollision(other, false);
                    helper.assertFalse(EntityCollisions.anyBodyIn(helper.getLevel(), inWall, other),
                            "nothing is in the way of one whose physics collision is off");
                    other.setDeltaMovement(1.5, 0, 0);
                })
                .thenExecuteAfter(30, () -> {
                    double through = relative(helper, other).x;
                    helper.assertTrue(through > 5.75, "and it walks through too: x = " + through);
                    wall.destroy();
                    ignored.discard();
                    other.discard();
                    done(helper, 9, 6);
                });
    }
}
