package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityProxies;
import com.lowdragmc.minebox3d.mc.entity.EntityShapes;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>Entities as kinematic proxies in the level's own physics</b> (design doc {@code 00-overview.md} §5): a proxy comes
 * and goes with its entity, keeps its body when the entity changes size (made again only when another provider answers,
 * or after another mod destroys it), jumps instead of sweeping when the entity is teleported, and holds up what rests
 * on it; a body that asks not to be pushed brings no proxies; exclusions and shape providers decide who gets which, a
 * part entity getting one only from a provider.
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class EntityProxyGameTest {

    private static final String EXCLUDED = "minebox3d_test_excluded";
    private static final String SHAPELESS = "minebox3d_test_shapeless";
    private static final String BALL = "minebox3d_test_ball";

    static {
        // live for the whole test server; only entities a test tags are touched
        EntityShapes.exclude(entity -> entity.getTags().contains(EXCLUDED));
        EntityShapes.register(new EntityShapes.Provider() {
            @Nullable
            @Override
            public List<Geometry> shapes(Entity entity) {
                if (entity.getTags().contains(SHAPELESS)) {
                    return List.of();
                }
                if (entity.getTags().contains(BALL)) {
                    return List.of(new Geometry.Sphere(0, 0.5f, 0, 0.5f));
                }
                return null;
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

    private static Body box(GameTestHelper helper, double x, double y, double z, float half) {
        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        Body body =
                physics(helper).createBody(new BodyDef().type(BodyType.DYNAMIC).position(at.x, at.y, at.z));
        body.createShape(new ShapeDef().density(500), Geometry.Box.of(half, half, half));
        return body;
    }

    private static Vector3d relative(GameTestHelper helper, Body body) {
        Vector3d p = body.position(new Vector3d());
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return p.sub(origin.getX(), origin.getY(), origin.getZ());
    }

    /** Within the 0.04 Box3D grows a moving body's bounds by. */
    private static boolean asTallAs(Body body, Entity entity) {
        float[] bounds = body.bounds(new float[6]);
        return Math.abs(bounds[4] - bounds[1] - entity.getBbHeight()) < 0.05f;
    }

    @GameTest(template = "empty", batch = "minebox3d_proxy_life", timeoutTicks = 60)
    @PrefixGameTestTemplate(false)
    public static void aProxyFollowsItsEntitysSizeAndGoesWithIt(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        Body box = box(helper, 1.5, 0.25, 1.5, 0.25f);
        Pig pig = helper.spawn(EntityType.PIG, new Vec3(3, 0, 1.5));
        pig.setNoAi(true);
        pig.setBaby(true);
        Body[] proxy = new Body[4];
        helper.startSequence()
                .thenExecuteAfter(3, () -> {
                    proxy[0] = physics(helper).entities().proxyOf(pig);
                    helper.assertTrue(proxy[0] != null, "the piglet next to the box has a proxy");
                    helper.assertTrue(asTallAs(proxy[0], pig), "as tall as the piglet");
                    pig.setBaby(false);
                })
                .thenExecuteAfter(3, () -> {
                    proxy[1] = physics(helper).entities().proxyOf(pig);
                    // the same body, its shape made over in place: whoever holds it (a joint, a mod) still has it
                    helper.assertTrue(proxy[1] == proxy[0] && proxy[1].isValid(), "the grown pig keeps its proxy");
                    helper.assertTrue(
                            proxy[1].shapes().size() == 1,
                            "one shape still: " + proxy[1].shapes().size());
                    helper.assertTrue(asTallAs(proxy[1], pig), "as tall as the pig now");
                    helper.assertTrue(EntityProxies.entityOf(proxy[1]) == pig, "and it says whose it is");
                    // another provider answers for it from now on: its shapes cannot be made over in place
                    pig.addTag(BALL);
                    pig.setBaby(true);
                })
                .thenExecuteAfter(3, () -> {
                    proxy[2] = physics(helper).entities().proxyOf(pig);
                    helper.assertTrue(
                            proxy[2] != null && proxy[2] != proxy[1] && !proxy[1].isValid(),
                            "a new proxy once another provider answers");
                    helper.assertTrue(
                            proxy[2].shapes().size() == 1
                                    && proxy[2].shapes().getFirst().geometry() instanceof Geometry.Sphere,
                            "the provider's ball");
                    // another mod destroying a proxy it does not own
                    proxy[2].destroy();
                })
                .thenExecuteAfter(3, () -> {
                    helper.assertFalse(physics(helper).isStopped(), "the level's physics carried on");
                    proxy[3] = physics(helper).entities().proxyOf(pig);
                    helper.assertTrue(proxy[3] != null && proxy[3].isValid(), "the pig got a proxy again");
                    pig.discard();
                })
                .thenExecuteAfter(3, () -> {
                    helper.assertTrue(
                            physics(helper).entities().proxyOf(pig) == null && !proxy[3].isValid(),
                            "the discarded pig's proxy is gone");
                    box.destroy();
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "minebox3d_proxy_teleport", timeoutTicks = 60)
    @PrefixGameTestTemplate(false)
    public static void aTeleportedEntityDoesNotSweepThroughWhatItLeftBehind(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        Body box = box(helper, 2, 0.25, 1.5, 0.25f);
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new Vec3(0.5, 0, 1.5));
        stand.setNoGravity(true);
        helper.startSequence()
                .thenExecuteAfter(3, () -> {
                    helper.assertTrue(physics(helper).entities().proxyOf(stand) != null, "the stand has a proxy");
                    // straight through the box, 20 blocks in one tick
                    Vec3 far = helper.absoluteVec(new Vec3(20.5, 0, 1.5));
                    stand.setPos(far.x, far.y, far.z);
                })
                .thenExecuteAfter(3, () -> {
                    Vector3d at = relative(helper, box);
                    helper.assertTrue(
                            Math.abs(at.x - 2) < 0.02 && Math.abs(at.y - 0.25) < 0.02,
                            "the box stayed where it was: " + at);
                    helper.assertTrue(box.linearVelocity(new Vector3f()).length() < 0.05f, "and still");
                    box.destroy();
                    stand.discard();
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "minebox3d_proxy_support", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aBoxRestsOnAnArmorStand(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new Vec3(1.5, 0, 1.5));
        stand.setNoGravity(true);
        Body box = box(helper, 1.5, 4, 1.5, 0.2f);
        helper.succeedWhen(() -> {
            double y = relative(helper, box).y;
            // the stand is 1.975 tall
            helper.assertTrue(Math.abs(y - 2.175) < 0.03, "resting on the stand's top: y = " + y);
            helper.assertTrue(box.linearVelocity(new Vector3f()).length() < 0.05f, "still");
            box.destroy();
            stand.discard();
        });
    }

    @GameTest(template = "empty", batch = "minebox3d_proxy_flag", timeoutTicks = 60)
    @PrefixGameTestTemplate(false)
    public static void aBodyThatAsksNotToBePushedBringsNoProxies(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        Body box = box(helper, 2.5, 0.25, 2.5, 0.25f);
        LevelBodies.setPushedByEntities(box, false);
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new Vec3(1, 0, 2.5));
        helper.startSequence()
                .thenExecuteAfter(3, () -> {
                    helper.assertTrue(
                            physics(helper).entities().proxyOf(stand) == null,
                            "no proxy for a body that does not want them");
                    LevelBodies.setPushedByEntities(box, null);
                })
                .thenExecuteAfter(3, () -> {
                    helper.assertTrue(
                            physics(helper).entities().proxyOf(stand) != null, "back to the default: a proxy");
                    box.destroy();
                    stand.discard();
                })
                .thenSucceed();
    }

    /** A part of a multipart entity: EntityStudio's hit volumes are these (docs/design/04 §8.2 #1). */
    private static final class Part extends PartEntity<Entity> {
        Part(Entity parent) {
            super(parent);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {}

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {}
    }

    @GameTest(template = "empty", batch = "minebox3d_proxy_parts", timeoutTicks = 60)
    @PrefixGameTestTemplate(false)
    public static void aPartEntityPushesBodiesOnlyWhenAProviderGivesItAShape(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 6);
        Body box = box(helper, 2.5, 0.25, 2.5, 0.25f);
        Part[] parts = new Part[2];
        ArmorStand owner = new ArmorStand(EntityType.ARMOR_STAND, helper.getLevel()) {
            @Override
            public boolean isMultipartEntity() {
                return true;
            }

            @Override
            public PartEntity<?>[] getParts() {
                return parts;
            }
        };
        parts[0] = new Part(owner);
        parts[1] = new Part(owner);
        Vec3 at = helper.absoluteVec(new Vec3(2.5, 0, 4));
        owner.setPos(at.x, at.y, at.z);
        owner.setNoGravity(true);
        Vec3 plain = helper.absoluteVec(new Vec3(1.5, 0, 2.5));
        parts[0].setPos(plain.x, plain.y, plain.z);
        Vec3 ball = helper.absoluteVec(new Vec3(3.5, 0, 2.5));
        parts[1].setPos(ball.x, ball.y, ball.z);
        parts[1].addTag(BALL);
        // the level lists the parts once the owner is in it, and hands them out with every other entity
        helper.getLevel().addFreshEntity(owner);
        helper.startSequence()
                .thenExecuteAfter(3, () -> {
                    EntityProxies entities = physics(helper).entities();
                    helper.assertTrue(
                            helper.getLevel()
                                    .getEntities((Entity) null, parts[0].getBoundingBox())
                                    .contains(parts[0]),
                            "the level hands the part out (else this proves nothing)");
                    helper.assertTrue(entities.proxyOf(owner) != null, "the owner has a proxy");
                    helper.assertTrue(entities.proxyOf(parts[0]) == null, "a part: none by default");
                    Body given = entities.proxyOf(parts[1]);
                    helper.assertTrue(
                            given != null && given.shapes().getFirst().geometry() instanceof Geometry.Sphere,
                            "unless a provider gives it one");
                    box.destroy();
                    owner.discard();
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "minebox3d_proxy_providers", timeoutTicks = 60)
    @PrefixGameTestTemplate(false)
    public static void exclusionsAndProvidersDecideWhoGetsWhichProxy(GameTestHelper helper) {
        TestArea.clear(helper, -1, 0, -1, 6, 8, 5);
        Body box = box(helper, 2.5, 0.25, 2.5, 0.25f);
        ArmorStand excluded = helper.spawn(EntityType.ARMOR_STAND, new Vec3(1, 0, 1));
        ArmorStand shapeless = helper.spawn(EntityType.ARMOR_STAND, new Vec3(4, 0, 1));
        ArmorStand ball = helper.spawn(EntityType.ARMOR_STAND, new Vec3(2.5, 0, 4));
        ArmorStand plain = helper.spawn(EntityType.ARMOR_STAND, new Vec3(1, 0, 4));
        excluded.addTag(EXCLUDED);
        shapeless.addTag(SHAPELESS);
        ball.addTag(BALL);
        helper.startSequence()
                .thenExecuteAfter(3, () -> {
                    EntityProxies entities = physics(helper).entities();
                    helper.assertTrue(entities.proxyOf(excluded) == null, "excluded: no proxy");
                    helper.assertTrue(entities.proxyOf(shapeless) == null, "a provider with no shapes: no proxy");
                    Body ballProxy = entities.proxyOf(ball);
                    helper.assertTrue(
                            ballProxy != null
                                    && ballProxy.shapes().size() == 1
                                    && ballProxy.shapes().getFirst().geometry() instanceof Geometry.Sphere,
                            "the provider's sphere");
                    Body plainProxy = entities.proxyOf(plain);
                    helper.assertTrue(
                            plainProxy != null && plainProxy.shapes().getFirst().geometry() instanceof Geometry.Box,
                            "nobody's answer: the collision box");
                    // exclusions are asked every tick, both ways
                    excluded.removeTag(EXCLUDED);
                    plain.addTag(EXCLUDED);
                })
                .thenExecuteAfter(3, () -> {
                    EntityProxies entities = physics(helper).entities();
                    helper.assertTrue(entities.proxyOf(excluded) != null, "no longer excluded: a proxy");
                    helper.assertTrue(entities.proxyOf(plain) == null, "excluded now: its proxy went");
                    box.destroy();
                    for (ArmorStand stand : new ArmorStand[] {excluded, shapeless, ball, plain}) {
                        stand.discard();
                    }
                })
                .thenSucceed();
    }
}
