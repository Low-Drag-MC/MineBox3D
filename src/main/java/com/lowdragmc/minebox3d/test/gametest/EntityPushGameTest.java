package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityPushedByBodyEvent;
import com.lowdragmc.minebox3d.mc.entity.EntityShapes;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.WorldDef;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Bodies push the entities they run into</b> (design doc {@code 04-entities-and-bodies.md} §2, batch 4a): as a
 * collision of the two masses, two bodies together no harder than their speed, only by the body's own motion,
 * through a cancellable event; never for an entity a provider makes immovable or one hanging on a wall, nor when
 * pushing is off (a server's choice, and a private physics' default). A body riding a falling entity lands with it.
 *
 * <p>⚠️ The targets are armor stands, not no-AI mobs: a no-AI mob does not move at all, pushed or not
 * ({@code LivingEntity.travel} moves only what {@code isEffectiveAi}, which no-AI turns off). And the bodies are only
 * launched once the stands tick: a freshly spawned entity may not for a while (TestArea.forceChunks).
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class EntityPushGameTest {

    private static final String CANCEL = "minebox3d_test_push_cancelled";
    private static final String IMMOVABLE = "minebox3d_test_immovable";
    /** the last event of each of the tests' entities, and the first */
    private static final Map<Entity, EntityPushedByBodyEvent> EVENTS = new ConcurrentHashMap<>();
    private static final Map<Entity, EntityPushedByBodyEvent> FIRST = new ConcurrentHashMap<>();

    static {
        // live for the whole test server; only entities a test tags are touched
        NeoForge.EVENT_BUS.addListener((EntityPushedByBodyEvent event) -> {
            EVENTS.put(event.getEntity(), event);
            FIRST.putIfAbsent(event.getEntity(), event);
            if (event.getEntity().getTags().contains(CANCEL)) {
                event.setCanceled(true);
            }
        });
        EntityShapes.register(new EntityShapes.Provider() {
            @Nullable
            @Override
            public List<Geometry> shapes(Entity entity) {
                return null;
            }

            @Override
            public float mass(Entity entity) {
                return entity.getTags().contains(IMMOVABLE) ? Float.POSITIVE_INFINITY : Float.NaN;
            }
        });
    }

    private static LevelPhysics physics(GameTestHelper helper) {
        LevelPhysics physics = LevelPhysics.of(helper.getLevel());
        if (physics == null) {
            helper.fail("Box3D is not available: physics could not start");
        }
        return physics;
    }

    /** The area cleared, and its chunks ticking entities (TestArea.forceChunks). */
    private static void area(GameTestHelper helper, int x1, int z1) {
        TestArea.clear(helper, -1, 0, -1, x1, 4, z1);
        TestArea.forceChunks(helper, -1, -1, x1, z1, true);
    }

    private static void done(GameTestHelper helper, int x1, int z1) {
        TestArea.forceChunks(helper, -1, -1, x1, z1, false);
        helper.succeed();
    }

    /** A frictionless box sliding along +x at {@code speed} on the ground. */
    private static Body slider(GameTestHelper helper, double x, double z, float density, float speed) {
        return slider(physics(helper), helper, x, z, density, speed);
    }

    private static Body slider(LevelPhysics physics, GameTestHelper helper, double x, double z, float density, float speed) {
        Vec3 at = helper.absoluteVec(new Vec3(x, 0.4, z));
        Body body = physics.createBody(new BodyDef().type(BodyType.DYNAMIC).position(at.x, at.y, at.z)
                .linearVelocity(speed, 0, 0));
        body.createShape(new ShapeDef().density(density).friction(0), Geometry.Box.of(0.4f, 0.4f, 0.4f));
        return body;
    }

    /** Something to push: an armor stand, 54 kg by its box. */
    private static ArmorStand target(GameTestHelper helper, double x, double z) {
        return helper.spawn(EntityType.ARMOR_STAND, new Vec3(x, 0, z));
    }

    private static boolean ticking(GameTestHelper helper, Entity... entities) {
        return TestArea.ticks(helper, entities);
    }

    private static double relativeX(GameTestHelper helper, Body body) {
        return body.position(new Vector3d()).x - helper.absolutePos(BlockPos.ZERO).getX();
    }

    @GameTest(template = "empty", batch = "minebox3d_push_mass", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aSlidingBoxPushesAStandAndAHeavierOnePushesItFurther(GameTestHelper helper) {
        area(helper, 14, 6);
        ArmorStand light = target(helper, 4, 1.5);
        ArmorStand heavy = target(helper, 4, 4.5);
        double lightStart = light.getX(), heavyStart = heavy.getX();
        Body[] boxes = new Body[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, light, heavy), "the stands tick"))
                .thenExecute(() -> {
                    boxes[0] = slider(helper, 0.5, 1.5, 60, 6);   // a crate, 31 kg against the stand's 54
                    boxes[1] = slider(helper, 0.5, 4.5, 4000, 6); // iron, 2 t
                })
                .thenExecuteAfter(30, () -> {
                    Body crate = boxes[0], iron = boxes[1];
                    // the first hit: an inelastic collision of 31 kg and 54 kg at 6 m/s moves the stand at 2.2 m/s; of
                    // 2 t and 54 kg at 5.8
                    EntityPushedByBodyEvent first = FIRST.get(light);
                    helper.assertTrue(first != null && Math.abs(first.getVelocityChange().x - 2.19) < 0.5,
                            "the crate's first push, as the masses say: " + (first == null ? "none" : first.getVelocityChange()));
                    first = FIRST.get(heavy);
                    helper.assertTrue(first != null && Math.abs(first.getVelocityChange().x - 5.84) < 0.6,
                            "the iron's first push: " + (first == null ? "none" : first.getVelocityChange()));
                    double lightMoved = light.getX() - lightStart, heavyMoved = heavy.getX() - heavyStart;
                    helper.assertTrue(lightMoved > 0.1, "the crate pushed its stand along +x: " + lightMoved);
                    helper.assertTrue(heavyMoved > 2 * lightMoved, "the iron pushed its stand much further: " + heavyMoved
                            + " vs " + lightMoved);
                    // never faster than what pushed it: 6 m/s for a second and a half
                    helper.assertTrue(heavyMoved < 9, "and no further than the iron could take it: " + heavyMoved);
                    helper.assertTrue(Math.abs(light.getZ() - helper.absoluteVec(new Vec3(4, 0, 1.5)).z) < 0.1,
                            "along the push, not sideways");
                    helper.assertTrue(relativeX(helper, iron) > 4.5, "the iron ploughed on past where the stand stood: "
                            + relativeX(helper, iron));
                    helper.assertTrue(iron.linearVelocity(new Vector3f()).length() < 6.05f, "and never sped up");
                    helper.assertTrue(relativeX(helper, crate) < relativeX(helper, iron), "the crate was stopped more");
                    EntityPushedByBodyEvent event = EVENTS.get(heavy);
                    helper.assertTrue(event != null && event.getBody() == iron, "the event names the body that pushed");
                    crate.destroy();
                    iron.destroy();
                    light.discard();
                    heavy.discard();
                    done(helper, 14, 6);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_push_one_way", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void anEntityMovingIntoARestingBoxIsNotPushedBack(GameTestHelper helper) {
        area(helper, 8, 4);
        Vec3 at = helper.absoluteVec(new Vec3(4, 0.4, 1.5));
        Body box = physics(helper).createBody(new BodyDef().type(BodyType.DYNAMIC).position(at.x, at.y, at.z));
        box.createShape(new ShapeDef().density(500), Geometry.Box.of(0.4f, 0.4f, 0.4f));
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new Vec3(1.5, 0, 1.5));
        boolean[] walking = {false};
        // walked into the box, a fifth of a block a tick
        helper.onEachTick(() -> {
            if (walking[0] && stand.isAlive()) {
                stand.setPos(stand.getX() + 0.2, stand.getY(), stand.getZ());
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, stand), "the stand ticks"))
                .thenExecute(() -> walking[0] = true)
                .thenExecuteAfter(25, () -> {
                    Vector3d p = box.position(new Vector3d());
                    helper.assertTrue(p.x > at.x + 0.5, "the stand shoved the box");
                    helper.assertTrue(stand.getDeltaMovement().x >= 0 && !EVENTS.containsKey(stand), "and was not pushed back");
                    box.destroy();
                    stand.discard();
                    done(helper, 8, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_push_cancel", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aCancelledPushOrAnImmovableEntityLeavesItWhereItIs(GameTestHelper helper) {
        area(helper, 10, 8);
        ArmorStand cancelled = target(helper, 4, 1.5);
        cancelled.addTag(CANCEL);
        ArmorStand immovable = target(helper, 4, 4.5);
        immovable.addTag(IMMOVABLE);
        double cancelledStart = cancelled.getX(), immovableStart = immovable.getX();
        Body[] boxes = new Body[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, cancelled, immovable), "the stands tick"))
                .thenExecute(() -> {
                    boxes[0] = slider(helper, 0.5, 1.5, 4000, 6);
                    boxes[1] = slider(helper, 0.5, 4.5, 4000, 6);
                })
                .thenExecuteAfter(20, () -> {
                    helper.assertTrue(EVENTS.containsKey(cancelled), "the push was offered");
                    helper.assertTrue(Math.abs(cancelled.getX() - cancelledStart) < 0.05, "and, cancelled, not taken");
                    helper.assertTrue(Math.abs(immovable.getX() - immovableStart) < 0.05 && !EVENTS.containsKey(immovable),
                            "an infinite mass is never pushed");
                    helper.assertTrue(boxes[1].linearVelocity(new Vector3f()).x < 1, "and stops what hits it");
                    helper.assertTrue(boxes[0].linearVelocity(new Vector3f()).x < 1, "a cancelled push holds it like a wall");
                    boxes[0].destroy();
                    boxes[1].destroy();
                    cancelled.discard();
                    immovable.discard();
                    done(helper, 10, 8);
                });
    }

    /** A stand running at 6 m/s into a crumb crawling at 0.3 m/s barely notices it — not stopped dead by it. */
    @GameTest(template = "empty", batch = "minebox3d_push_head_on", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void anEntityRunningIntoALightBodyComingAtItKeepsGoing(GameTestHelper helper) {
        // room ahead: knocked at the stand's pace, the crumb must not reach the barriers by the check (it stops there)
        area(helper, 16, 4);
        ArmorStand stand = target(helper, 1.5, 1.5);
        boolean[] running = {false};
        helper.onEachTick(() -> {
            if (running[0] && stand.isAlive()) {
                stand.setDeltaMovement(0.3, stand.getDeltaMovement().y, 0);
            }
        });
        Body[] crumb = new Body[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, stand), "the stand ticks"))
                .thenExecute(() -> {
                    running[0] = true;
                    // 0.6 kg
                    Vec3 at = helper.absoluteVec(new Vec3(7, 0.2, 1.5));
                    crumb[0] = physics(helper).createBody(new BodyDef().type(BodyType.DYNAMIC).position(at.x, at.y, at.z)
                            .linearVelocity(-0.3f, 0, 0));
                    crumb[0].createShape(new ShapeDef().density(10).friction(0), Geometry.Box.of(0.2f, 0.2f, 0.2f));
                })
                .thenExecuteAfter(25, () -> {
                    running[0] = false;
                    EntityPushedByBodyEvent event = FIRST.get(stand);
                    helper.assertTrue(event == null || event.getVelocityChange().length() < 0.5,
                            "pushed by no more than the crumb has to give: " + (event == null ? "none" : event.getVelocityChange()));
                    // else this proves nothing: they did meet, and the crumb was knocked ahead at the stand's pace
                    helper.assertTrue(crumb[0].linearVelocity(new Vector3f()).x > 3,
                            "the crumb was knocked ahead: " + crumb[0].linearVelocity(new Vector3f()));
                    crumb[0].destroy();
                    stand.discard();
                    done(helper, 16, 4);
                });
    }

    @GameTest(template = "empty", batch = "minebox3d_push_side_by_side", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void twoBodiesTogetherPushNoHarderThanTheirSpeed(GameTestHelper helper) {
        area(helper, 14, 4);
        ArmorStand stand = target(helper, 4, 1.5);
        Body[] irons = new Body[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, stand), "the stand ticks"))
                .thenExecute(() -> {
                    irons[0] = slider(helper, 0.5, 1.05, 4000, 6);
                    irons[1] = slider(helper, 0.5, 1.95, 4000, 6);
                })
                .thenExecuteAfter(20, () -> {
                    EntityPushedByBodyEvent event = FIRST.get(stand);
                    helper.assertTrue(event != null && event.getVelocityChange().x > 4 && event.getVelocityChange().x < 6.5,
                            "about the irons' 6 m/s, not 12: " + (event == null ? "none" : event.getVelocityChange()));
                    irons[0].destroy();
                    irons[1].destroy();
                    stand.discard();
                    done(helper, 14, 4);
                });
    }

    /** A crate riding on a falling stand lands with it: the stand's proxy is not driven into the ground and flung back. */
    @GameTest(template = "empty", batch = "minebox3d_push_landing", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aBodyRidingAFallingEntityLandsWithIt(GameTestHelper helper) {
        area(helper, 4, 4);
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new Vec3(1.5, 3, 1.5));
        stand.setNoGravity(true);
        Body[] crate = new Body[1];
        double[] worst = {0};
        helper.onEachTick(() -> {
            if (crate[0] != null && crate[0].isValid()) {
                double above = crate[0].position(new Vector3d()).y - (stand.getY() + stand.getBbHeight() + 0.2);
                worst[0] = Math.max(worst[0], above);
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, stand), "the stand ticks"))
                .thenExecute(() -> {
                    Vec3 at = helper.absoluteVec(new Vec3(1.5, 3 + stand.getBbHeight() + 0.2, 1.5));
                    crate[0] = physics(helper).createBody(new BodyDef().type(BodyType.DYNAMIC).position(at.x, at.y, at.z));
                    crate[0].createShape(new ShapeDef().density(300), Geometry.Box.of(0.2f, 0.2f, 0.2f));
                })
                .thenExecuteAfter(10, () -> stand.setNoGravity(false))
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(stand.onGround(), "the stand landed");
                    helper.assertTrue(worst[0] < 0.3, "the crate never left its head by more than a little: " + worst[0]);
                    double resting = crate[0].position(new Vector3d()).y - (stand.getY() + stand.getBbHeight() + 0.2);
                    helper.assertTrue(Math.abs(resting) < 0.1, "and rests on it: " + resting);
                    crate[0].destroy();
                    stand.discard();
                    done(helper, 4, 4);
                });
    }

    /** An item frame hanging in a body's way stays on its wall: a push would break it off ({@code BlockAttachedEntity.push}). */
    @GameTest(template = "empty", batch = "minebox3d_push_frame", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aHangingEntityIsNeverPushedOffItsWall(GameTestHelper helper) {
        area(helper, 8, 4);
        helper.setBlock(new BlockPos(6, 0, 1), net.minecraft.world.level.block.Blocks.STONE);
        net.minecraft.world.entity.decoration.ItemFrame frame = new net.minecraft.world.entity.decoration.ItemFrame(
                helper.getLevel(), helper.absolutePos(new BlockPos(5, 0, 1)), net.minecraft.core.Direction.WEST);
        helper.getLevel().addFreshEntity(frame);
        Body iron = slider(helper, 1.5, 1.5, 4000, 6);
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(frame.isAlive(), "the frame is still on its wall");
            helper.assertTrue(physics(helper).entities().proxyOf(frame) != null, "and it did meet the iron (it has a proxy)");
            frame.discard();
            iron.destroy();
            done(helper, 8, 4);
        });
    }

    @GameTest(template = "empty", batch = "minebox3d_push_off", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aServerThatTurnsPushingOffGetsNone(GameTestHelper helper) {
        area(helper, 10, 4);
        ArmorStand stand = target(helper, 4, 1.5);
        double start = stand.getX();
        Body[] iron = new Body[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, stand), "the stand ticks"))
                .thenExecute(() -> {
                    physics(helper).entities().settings().pushEntities(false);
                    iron[0] = slider(helper, 0.5, 1.5, 4000, 6);
                })
                .thenExecuteAfter(20, () -> {
                    physics(helper).entities().settings().pushEntities(true);
                    helper.assertTrue(Math.abs(stand.getX() - start) < 0.05 && !EVENTS.containsKey(stand), "not pushed");
                    helper.assertTrue(relativeX(helper, iron[0]) < 4, "the iron stopped at the stand as before (kinematic)");
                    iron[0].destroy();
                    stand.discard();
                    done(helper, 10, 4);
                });
    }

    /**
     * A private physics (a mod's own simulation) reads the level's entities but does not push them unless its maker turns
     * it on (user's ruling, 2026-10-29): two of them side by side, as made and turned on, each with iron sliding at a stand.
     */
    @GameTest(template = "empty", batch = "minebox3d_push_private", timeoutTicks = 1200)
    @PrefixGameTestTemplate(false)
    public static void aPrivatePhysicsPushesNoEntityUnlessItsMakerSaysSo(GameTestHelper helper) {
        area(helper, 10, 7);
        ArmorStand left = target(helper, 4, 1.5);
        ArmorStand pushed = target(helper, 4, 4.5);
        double leftStart = left.getX(), pushedStart = pushed.getX();
        LevelPhysics quiet = LevelPhysics.create(helper.getLevel(), new WorldDef().gravity(0, LevelPhysics.GRAVITY, 0));
        LevelPhysics loud = LevelPhysics.create(helper.getLevel(), new WorldDef().gravity(0, LevelPhysics.GRAVITY, 0));
        if (quiet == null || loud == null) {
            helper.fail("Box3D is not available");
            return;
        }
        helper.assertFalse(quiet.entities().settings().pushEntities(), "off in a private physics as made");
        helper.assertTrue(physics(helper).entities().settings().pushEntities(), "on in the level's own");
        loud.entities().settings().pushEntities(true);
        // stepped by their maker, once a game tick
        helper.onEachTick(() -> {
            if (quiet.world().isOpen() && loud.world().isOpen()) {
                quiet.tick();
                loud.tick();
            }
        });
        Body[] iron = new Body[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(helper, left, pushed), "the stands tick"))
                .thenExecute(() -> {
                    iron[0] = slider(quiet, helper, 0.5, 1.5, 4000, 6);
                    iron[1] = slider(loud, helper, 0.5, 4.5, 4000, 6);
                })
                .thenExecuteAfter(20, () -> {
                    helper.assertFalse(quiet.isStopped() || loud.isStopped(), "both ran");
                    helper.assertTrue(quiet.entities().proxyOf(left) != null, "the quiet one did meet its stand (a proxy)");
                    helper.assertTrue(Math.abs(left.getX() - leftStart) < 0.05 && !EVENTS.containsKey(left),
                            "and did not push it: moved " + (left.getX() - leftStart));
                    helper.assertTrue(relativeX(helper, iron[0]) < 4, "its iron stopped at the stand, as at a wall: "
                            + relativeX(helper, iron[0]));
                    helper.assertTrue(pushed.getX() - pushedStart > 0.5 && EVENTS.containsKey(pushed),
                            "the one turned on pushed its stand: moved " + (pushed.getX() - pushedStart));
                    quiet.close();
                    loud.close();
                    left.discard();
                    pushed.discard();
                    done(helper, 10, 7);
                });
    }
}
