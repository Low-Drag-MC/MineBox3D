package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.LevelRewind;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.WorldDef;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>A level's physics rolled back and simulated again</b> ({@link LevelRewind}): the ticks run again end where the first
 * run ended; a kick given again for a past tick ends where a run kicked then ends; what is outside the bubble stands still
 * meanwhile and is as it was after; what the run again sets off is dropped; what was drawn fades into where it is in six
 * frames; nothing is held while nothing is watched, nor for longer than a second. Each on a private physics of its own, high
 * over the test, ticked by hand.
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class RewindGameTest {

    private RewindGameTest() {}

    /** A private physics with a floor and a stack of crates high over the test, watched round them. */
    private static final class Stage implements AutoCloseable {
        final LevelPhysics physics;
        final Vec3 centre;
        final List<Body> crates = new ArrayList<>();

        Stage(GameTestHelper helper) {
            LevelPhysics created =
                    LevelPhysics.create(helper.getLevel(), new WorldDef().gravity(0, LevelPhysics.GRAVITY, 0));
            if (created == null) {
                throw new IllegalStateException("Box3D is not available");
            }
            physics = created;
            centre = helper.absoluteVec(new Vec3(4, 80, 4));
            Body floor = physics.createBody(new BodyDef().position(centre.x, centre.y - 0.5, centre.z));
            floor.createShape(new ShapeDef(), Geometry.Box.of(10, 0.5f, 10));
            for (int i = 0; i < 3; i++) {
                crates.add(crate(centre.x, centre.y + 0.45 + 0.95 * i, centre.z));
            }
            Body slider = crate(centre.x + 3, centre.y + 0.45, centre.z);
            slider.setLinearVelocity(4, 0, 1);
            crates.add(slider);
        }

        Body crate(double x, double y, double z) {
            Body body = physics.createBody(new BodyDef().type(BodyType.DYNAMIC).position(x, y, z));
            body.createShape(new ShapeDef().density(100), Geometry.Box.of(0.45f, 0.45f, 0.45f));
            LevelBodies.setNeedsTerrain(body, false);
            return body;
        }

        void ticks(GameTestHelper helper, int n) {
            for (int i = 0; i < n; i++) {
                physics.tick();
            }
            if (physics.isStopped()) {
                helper.fail("the physics stopped: see the log");
            }
        }

        List<Vector3d> places() {
            List<Vector3d> places = new ArrayList<>();
            for (Body crate : crates) {
                places.add(crate.position(new Vector3d()));
            }
            return places;
        }

        @Override
        public void close() {
            physics.close();
        }
    }

    @GameTest(template = "empty", batch = "minebox3d_rewind", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void ticksRunAgainEndWhereTheFirstRunEnded(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            stage.physics.rewind().watch("test", () -> stage.centre);
            stage.ticks(helper, 30);
            List<Vector3d> first = stage.places();
            long now = stage.physics.ticks();
            helper.assertTrue(stage.physics.rewind().resimulate(now - 9), "the last ten ticks are held");
            List<Vector3d> again = stage.places();
            for (int i = 0; i < first.size(); i++) {
                double off = first.get(i).distance(again.get(i));
                helper.assertTrue(off < 0.01, "crate " + i + " where the first run left it: " + off + " off");
                double drawn = stage.crates.get(i).renderError(new Vector3d()).length();
                helper.assertTrue(drawn < 0.01, "and drawn there: " + drawn);
            }
            helper.assertTrue(stage.physics.rewind().resimulations() == 1, "once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_rewind", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aKickGivenAgainForAPastTickEndsWhereAKickThenEnds(GameTestHelper helper) {
        int kickTick = 20;
        try (Stage kickedThen = new Stage(helper);
                Stage kickedAgain = new Stage(helper)) {
            Body thenCrate = kickedThen.crates.get(3), againCrate = kickedAgain.crates.get(3);
            // the first: kicked on tick 20 as it happens
            kickedThen.physics.addTickListener(new LevelPhysics.TickListener() {
                @Override
                public void beforeSteps(LevelPhysics physics) {
                    if (physics.ticks() == kickTick) {
                        thenCrate.applyLinearImpulseToCenter(0, 200, -150);
                    }
                }
            });
            kickedThen.ticks(helper, 30);
            // the second: not kicked, and told of the kick afterwards — given again for tick 20 as it is run again
            kickedAgain.physics.rewind().watch("test", () -> kickedAgain.centre);
            kickedAgain.ticks(helper, 30);
            Vector3d unkicked = againCrate.position(new Vector3d());
            kickedAgain.physics.addTickListener(new LevelPhysics.TickListener() {
                @Override
                public void beforeResimTick(LevelPhysics physics, long tick) {
                    if (tick == kickTick) {
                        againCrate.applyLinearImpulseToCenter(0, 200, -150);
                    }
                }
            });
            helper.assertTrue(kickedAgain.physics.rewind().resimulate(kickTick), "run again from the kick");
            Vector3d then = thenCrate.position(new Vector3d()), again = againCrate.position(new Vector3d());
            double off = then.distance(again);
            double moved = unkicked.distance(then);
            helper.assertTrue(
                    moved > 0.3 && moved < Body.ERROR_SNAP, "the kick took it somewhere, short of a snap: " + moved);
            helper.assertTrue(off < 0.01, "where the run kicked then left it: " + off + " off");
            // drawn where it was, the gap fading over six frames
            Vector3d gap = againCrate.renderError(new Vector3d());
            helper.assertTrue(gap.distance(new Vector3d(unkicked).sub(again)) < 1e-6, "drawn where it was: " + gap);
            for (int frame = 0; frame < 6; frame++) {
                kickedAgain.ticks(helper, 1);
            }
            helper.assertTrue(
                    againCrate.renderError(new Vector3d()).length() < 1e-9, "and drawn where it is six frames on");
        }
        helper.succeed();
    }

    /** Run again to further off than Unreal smooths (250 cm), a body is drawn where it is at once. */
    @GameTest(template = "empty", batch = "minebox3d_rewind", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aBodyFarOffAfterARunAgainIsDrawnThereAtOnce(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Body slider = stage.crates.get(3);
            stage.physics.rewind().watch("test", () -> stage.centre);
            stage.ticks(helper, 10);
            long last = stage.physics.ticks();
            stage.physics.addTickListener(new LevelPhysics.TickListener() {
                @Override
                public void afterResimTick(LevelPhysics physics, long tick) {
                    if (tick == last) {
                        // where the server says it is: four blocks off
                        Vector3d at = slider.position(new Vector3d());
                        slider.setTransform(at.x - 4, at.y, at.z, slider.rotation(new org.joml.Quaternionf()));
                    }
                }
            });
            helper.assertTrue(stage.physics.rewind().resimulate(last - 3), "run again");
            helper.assertTrue(slider.renderError(new Vector3d()).length() == 0, "not smoothed");
            helper.assertTrue(
                    slider.renderPosition(0, new Vector3d()).equals(slider.position(new Vector3d())),
                    "drawn where it is from the frame's start");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_rewind", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void whatIsOutsideStandsStillMeanwhile(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            // twenty blocks off, falling
            Body far = stage.crate(stage.centre.x + 20, stage.centre.y + 10, stage.centre.z);
            stage.physics.rewind().watch("test", () -> stage.centre);
            stage.ticks(helper, 10);
            Vector3d where = far.position(new Vector3d());
            Vector3f going = far.linearVelocity(new Vector3f());
            int contacts = stage.physics.world().events().contactBegins().size();
            helper.assertTrue(stage.physics.rewind().resimulate(stage.physics.ticks() - 5), "run again");
            helper.assertTrue(
                    far.position(new Vector3d()).equals(where),
                    "outside, where it was: " + far.position(new Vector3d()));
            helper.assertTrue(far.linearVelocity(new Vector3f()).equals(going), "and going as it was");
            helper.assertTrue(
                    stage.physics.world().events().contactBegins().size() <= contacts,
                    "what the run again set off dropped");
            stage.ticks(helper, 1);
            helper.assertTrue(far.position(new Vector3d()).y < where.y, "and falling on after");
        }
        helper.succeed();
    }

    /** A long deck reaching under what is watched is held, though where it is — its middle — is far off. */
    @GameTest(template = "empty", batch = "minebox3d_rewind", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aLongDeckReachingInIsHeldByWhereItReaches(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Body deck = stage.physics.createBody(new BodyDef()
                    .type(BodyType.KINEMATIC)
                    .position(stage.centre.x + 14, stage.centre.y + 5, stage.centre.z));
            deck.createShape(new ShapeDef(), Geometry.Box.of(15, 0.25f, 2));
            stage.physics.rewind().watch("test", () -> stage.centre);
            stage.ticks(helper, 3);
            long now = stage.physics.ticks();
            helper.assertTrue(
                    stage.physics.rewind().history().position(now, deck, new Vector3d()) != null,
                    "held: it reaches to within the bubble, its middle fourteen blocks off");
        }
        helper.succeed();
    }

    /** The server's word for a tick older than the history is not held against anything, nor put anywhere. */
    @GameTest(template = "empty", batch = "minebox3d_rewind", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aWordOlderThanTheHistoryMovesNothing(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Body slider = stage.crates.get(3);
            stage.physics.rewind().watch("test", () -> stage.centre);
            stage.ticks(helper, 30);
            long old = stage.physics.ticks() - LevelRewind.HISTORY_TICKS - 2;
            Vector3d now = slider.position(new Vector3d());
            helper.assertTrue(
                    !stage.physics.rewind().know(old, slider, now.x - 3, now.y, now.z, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0),
                    "not held");
            helper.assertTrue(
                    slider.position(new Vector3d()).equals(now), "nor put there: " + slider.position(new Vector3d()));
            helper.assertTrue(!stage.physics.rewind().canResimulateFrom(old + 1), "and not to be run again from");
            helper.assertTrue(stage.physics.rewind().canResimulateFrom(stage.physics.ticks() - 5), "a recent one is");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_rewind", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void nothingIsHeldUnwatchedNorForLongerThanASecond(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            stage.ticks(helper, 5);
            helper.assertTrue(stage.physics.rewind().recordedTick() == Long.MIN_VALUE, "nothing held unwatched");
            helper.assertTrue(!stage.physics.rewind().resimulate(stage.physics.ticks() - 2), "nothing to run again");
            stage.physics.rewind().watch("test", () -> stage.centre);
            stage.ticks(helper, 30);
            long now = stage.physics.ticks();
            helper.assertTrue(
                    !stage.physics.rewind().resimulate(now - LevelRewind.HISTORY_TICKS), "a second back is too far");
            helper.assertTrue(stage.physics.rewind().resimulate(now - LevelRewind.HISTORY_TICKS + 2), "less is held");
            stage.physics.rewind().unwatch("test");
            stage.ticks(helper, 1);
            helper.assertTrue(stage.physics.rewind().recordedTick() == Long.MIN_VALUE, "let go once unwatched");
        }
        helper.succeed();
    }
}
