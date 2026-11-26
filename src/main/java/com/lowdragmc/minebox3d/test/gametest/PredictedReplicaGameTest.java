package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.LevelRewind;
import com.lowdragmc.minebox3d.mc.entity.StepUpOn;
import com.lowdragmc.minebox3d.mc.sync.BodySyncPayload;
import com.lowdragmc.minebox3d.mc.sync.SyncedBodies;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.RevoluteJointDef;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.WorldDef;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>A client predicting round its own body</b> (EntityStudio doc 61 §3.9), the client's half played on a private
 * physics of the test's own, the server's payloads handed to it by hand: round what predicts, a replica of a body the
 * server simulates is simulated here and one further off still follows the server's states; a server state off from
 * where it was run to by more than Unreal's 10 cm runs the ticks since again from it, one within does not; the
 * predictor's own body has no replica, and the server's word of it is held against the body here.
 */
@GameTestHolder(MineBox3D.MOD_ID)
public final class PredictedReplicaGameTest {

    private PredictedReplicaGameTest() {}

    private static final byte BOX = 2;
    private static final long OWN_TAG = 1L << 62 | 7;
    private static final long OTHER_TAG = 1L << 62 | 8;

    /**
     * A predictor at a fixed place, whose frames are the physics' own ticks; the body tagged {@link #OTHER_TAG}
     * another's.
     */
    private static final class Predicting implements SyncedBodies.Predictor {
        final Vec3 centre;

        @Nullable
        Body own;

        @Override
        public boolean simulates(long tag, @Nullable String name) {
            return tag != OTHER_TAG;
        }

        Predicting(Vec3 centre) {
            this.centre = centre;
        }

        @Override
        public Vec3 centre() {
            return centre;
        }

        @Override
        public long localTickOf(int frame) {
            return frame;
        }

        @Override
        public boolean isOwn(long tag, @Nullable String name) {
            return tag == OWN_TAG;
        }

        @Override
        public Body ownBody() {
            return own;
        }
    }

    private static final class Stage implements AutoCloseable {
        final LevelPhysics physics;
        final Vec3 centre;
        final Predicting predictor;

        Stage(GameTestHelper helper) {
            this(helper, new Vec3(4, 80, 4), true);
        }

        /** What predicts at {@code centre} in the test's frame; a floor of a body under it, or only the blocks. */
        Stage(GameTestHelper helper, Vec3 centre, boolean floor) {
            LevelPhysics created =
                    LevelPhysics.create(helper.getLevel(), new WorldDef().gravity(0, LevelPhysics.GRAVITY, 0));
            if (created == null) {
                throw new IllegalStateException("Box3D is not available");
            }
            physics = created;
            this.centre = helper.absoluteVec(centre);
            if (floor) {
                Body body =
                        physics.createBody(new BodyDef().position(this.centre.x, this.centre.y - 0.5, this.centre.z));
                body.createShape(new ShapeDef(), Geometry.Box.of(30, 0.5f, 30));
            }
            predictor = new Predicting(this.centre);
            physics.sync().setPredictor(predictor);
        }

        /**
         * One tick of the physics. On the server's level its prepare gives the replicas no part: a test that needs
         * that calls {@code sync().beforeSteps} itself.
         */
        void tick() {
            physics.tick();
        }

        void ticks(GameTestHelper helper, int n) {
            for (int i = 0; i < n; i++) {
                tick();
            }
            if (physics.isStopped()) {
                helper.fail("the physics stopped: see the log");
            }
        }

        /** The server's word: a crate of 40 kg at {@code (x, y, z)}, tagged {@code tag}. */
        void spawn(int id, double x, double y, double z, long tag) {
            spawn(
                    id,
                    state(id, x, y, z),
                    tag,
                    new BodySyncPayload.Dynamics(40, 0, 0, 0, new float[] {4, 0, 0, 0, 4, 0, 0, 0, 4}, true));
        }

        void spawn(int id, BodySyncPayload.State state, long tag, BodySyncPayload.Dynamics dynamics) {
            var shape = new BodySyncPayload.SyncedShape(
                    BOX,
                    new float[] {0.4f, 0.4f, 0.4f, 0, 0, 0, 0, 0, 0, 1},
                    -1L,
                    -1L,
                    0,
                    false,
                    0.6f,
                    0,
                    0,
                    StepUpOn.YES);
            physics.sync()
                    .apply(new BodySyncPayload(
                            List.of(new BodySyncPayload.Spawn(
                                    id, "crate", 0, tag, 0, List.of(shape), state, dynamics, new Vector3f())),
                            List.of(),
                            new int[0],
                            false,
                            physics.ticks(),
                            BodySyncPayload.NO_FRAME));
        }

        void state(int id, double x, double y, double z, long tick) {
            physics.sync()
                    .apply(new BodySyncPayload(
                            List.of(), List.of(state(id, x, y, z)), new int[0], false, tick, (int) tick));
        }

        static BodySyncPayload.State state(int id, double x, double y, double z) {
            return new BodySyncPayload.State(id, x, y, z, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0);
        }

        @Override
        public void close() {
            physics.sync().setPredictor(null);
            physics.close();
        }
    }

    @GameTest(template = "empty", batch = "minebox3d_predicted", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void roundWhatPredictsAReplicaIsSimulatedHere(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Vec3 c = stage.centre;
            stage.spawn(1, c.x, c.y + 3, c.z, 0);
            stage.spawn(2, c.x + 20, c.y + 3, c.z, 0);
            Body near = stage.physics.sync().replica(1),
                    far = stage.physics.sync().replica(2);
            helper.assertTrue(near != null && far != null, "both made");
            // a client's tick: the replicas given their part before the steps (prepare does it only on a client)
            stage.physics.sync().beforeSteps(1 / 20f);
            stage.ticks(helper, 10);
            helper.assertTrue(
                    near.type() == BodyType.DYNAMIC && SyncedBodies.isSimulated(near), "the near one simulated");
            helper.assertTrue(
                    near.position(new Vector3d()).y < c.y + 1,
                    "falling here on its own: " + (near.position(new Vector3d()).y - c.y));
            helper.assertTrue(
                    far.type() == BodyType.KINEMATIC && !SyncedBodies.isSimulated(far), "the far one following");
            helper.assertTrue(far.position(new Vector3d()).y > c.y + 2.9, "where the server last said");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_predicted", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aReplicaSimulatedHereStandsOnTheBlocks(GameTestHelper helper) {
        TestArea.clear(helper, 0, 0, 0, 8, 6, 8);
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        // nothing else here needs terrain: only the replica, once it is simulated, brings the stone in
        try (Stage stage = new Stage(helper, new Vec3(4, 3, 4), false)) {
            Vec3 at = helper.absoluteVec(new Vec3(6.5, 2, 4.5));
            stage.spawn(1, at.x, at.y, at.z, 0);
            Body replica = stage.physics.sync().replica(1);
            helper.assertTrue(replica != null, "made");
            helper.assertFalse(LevelBodies.needsTerrain(replica), "a followed replica brings no terrain");
            stage.physics.sync().beforeSteps(1 / 20f);
            helper.assertTrue(SyncedBodies.isSimulated(replica), "simulated round what predicts");
            helper.assertTrue(LevelBodies.needsTerrain(replica), "and brings the blocks round it");
            stage.ticks(helper, 20);
            // a 0.8 m box on a block's top at y = 1: its middle at 1.4, not through the stone
            double y = replica.position(new Vector3d()).y - helper.absoluteVec(Vec3.ZERO).y;
            helper.assertTrue(Math.abs(y - 1.4) < 0.05, "standing on the stone: " + y);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_predicted", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aServerStateOffFromTheRecordRunsTheTicksAgain(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Vec3 c = stage.centre;
            stage.spawn(1, c.x, c.y + 0.4, c.z, 0);
            Body crate = stage.physics.sync().replica(1);
            stage.physics.sync().beforeSteps(1 / 20f);
            stage.ticks(helper, 10);
            long then = stage.physics.ticks() - 3;
            Vector3d recorded = stage.physics.rewind().history().position(then, crate, new Vector3d());
            helper.assertTrue(recorded != null, "the crate recorded round what predicts");

            stage.state(1, recorded.x + 0.05, recorded.y, recorded.z, then);
            stage.tick();
            helper.assertTrue(
                    stage.physics.rewind().resimulations() == 0, "5 cm off: within Unreal's 10, not run again");

            stage.state(1, recorded.x + 0.5, recorded.y, recorded.z, then);
            Vector3d before = crate.position(new Vector3d());
            stage.tick();
            helper.assertTrue(stage.physics.rewind().resimulations() == 1, "half a block off: run again");
            double moved = crate.position(new Vector3d()).x - before.x;
            helper.assertTrue(Math.abs(moved - 0.5) < 0.05, "from where the server said: moved " + moved);
            helper.assertTrue(crate.renderError(new Vector3d()).length() > 0.4, "drawn from where it was, fading");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_predicted", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void itsOwnBodyHasNoReplicaAndIsHeldAgainstTheServers(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Vec3 c = stage.centre;
            Body own = stage.physics.createBody(
                    new BodyDef().type(BodyType.DYNAMIC).position(c.x + 1, c.y + 0.4, c.z));
            own.createShape(new ShapeDef().density(100), Geometry.Box.of(0.4f, 0.4f, 0.4f));
            LevelBodies.setNeedsTerrain(own, false);
            stage.predictor.own = own;
            stage.spawn(5, c.x + 1, c.y + 0.4, c.z, OWN_TAG);
            helper.assertTrue(stage.physics.sync().replica(5) == null, "no replica of its own");
            stage.ticks(helper, 10);
            long then = stage.physics.ticks() - 2;
            Vector3d recorded = stage.physics.rewind().history().position(then, own, new Vector3d());
            helper.assertTrue(recorded != null, "its own recorded");
            stage.state(5, recorded.x, recorded.y, recorded.z + 0.3, then);
            stage.tick();
            helper.assertTrue(stage.physics.rewind().resimulations() == 1, "the server's word of it off: run again");
            double moved = own.position(new Vector3d()).z - recorded.z;
            helper.assertTrue(Math.abs(moved - 0.3) < 0.05, "from where the server said: " + moved);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_predicted", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void anotherClientsBodyRoundItIsOnlyFollowed(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Vec3 c = stage.centre;
            stage.spawn(1, c.x, c.y + 3, c.z, OTHER_TAG);
            Body other = stage.physics.sync().replica(1);
            stage.physics.sync().beforeSteps(1 / 20f);
            stage.ticks(helper, 5);
            helper.assertTrue(other.type() == BodyType.KINEMATIC && !SyncedBodies.isSimulated(other), "followed");
            helper.assertTrue(other.position(new Vector3d()).y > c.y + 2.9, "where the server said");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_predicted", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void itsOwnBodyComeBeforeItsPredictorIsDroppedForItsOwn(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Vec3 c = stage.centre;
            stage.physics.sync().setPredictor(null);
            stage.spawn(5, c.x + 1, c.y + 0.4, c.z, OWN_TAG);
            helper.assertTrue(stage.physics.sync().replica(5) != null, "a replica while nothing predicts");
            Body own = stage.physics.createBody(
                    new BodyDef().type(BodyType.DYNAMIC).position(c.x + 1, c.y + 0.4, c.z));
            own.createShape(new ShapeDef().density(100), Geometry.Box.of(0.4f, 0.4f, 0.4f));
            LevelBodies.setNeedsTerrain(own, false);
            stage.predictor.own = own;
            stage.physics.sync().setPredictor(stage.predictor);
            helper.assertTrue(stage.physics.sync().replica(5) == null, "gone once its predictor comes");
            stage.ticks(helper, 10);
            long then = stage.physics.ticks() - 2;
            Vector3d recorded = stage.physics.rewind().history().position(then, own, new Vector3d());
            helper.assertTrue(recorded != null, "its own recorded");
            stage.state(5, recorded.x, recorded.y, recorded.z + 0.3, then);
            stage.tick();
            helper.assertTrue(
                    stage.physics.rewind().resimulations() == 1, "the server's word of it held against its own");
        }
        helper.succeed();
    }

    /**
     * A replica simulated here moves as the server's body does — weightless, damped, its rotation fixed — and one whose
     * state says it touches what this client lacks, or is held by joints it is not held by here, is only followed.
     */
    @GameTest(template = "empty", batch = "minebox3d_predicted", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aReplicaIsSimulatedHereOnlyAsTheServersMoves(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Vec3 c = stage.centre;
            float[] inertia = {4, 0, 0, 0, 4, 0, 0, 0, 4};
            stage.spawn(
                    1,
                    Stage.state(1, c.x, c.y + 3, c.z),
                    0,
                    new BodySyncPayload.Dynamics(40, 0, 0, 0, inertia, true, 0.5f, 0.25f, 0, true));
            stage.spawn(
                    2,
                    new BodySyncPayload.State(2, c.x + 2, c.y + 3, c.z, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, false, 0),
                    0,
                    new BodySyncPayload.Dynamics(40, 0, 0, 0, inertia, true));
            stage.spawn(
                    3,
                    new BodySyncPayload.State(3, c.x - 2, c.y + 3, c.z, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, true, 1),
                    0,
                    new BodySyncPayload.Dynamics(40, 0, 0, 0, inertia, true));
            Body weightless = stage.physics.sync().replica(1),
                    lacking = stage.physics.sync().replica(2),
                    held = stage.physics.sync().replica(3);
            stage.physics.sync().beforeSteps(1 / 20f);
            stage.ticks(helper, 10);
            helper.assertTrue(SyncedBodies.isSimulated(weightless), "simulated here");
            helper.assertTrue(
                    weightless.gravityScale() == 0
                            && weightless.linearDamping() == 0.5f
                            && weightless.angularDamping() == 0.25f
                            && weightless.isFixedRotation(),
                    "moving as the server's body: " + weightless.gravityScale() + " " + weightless.isFixedRotation());
            helper.assertTrue(weightless.position(new Vector3d()).y > c.y + 2.9, "weightless, it floats as there");
            helper.assertTrue(
                    !SyncedBodies.isSimulated(lacking) && lacking.type() == BodyType.KINEMATIC,
                    "touching what is not here: followed");
            helper.assertTrue(
                    !SyncedBodies.isSimulated(held) && held.type() == BodyType.KINEMATIC,
                    "held by a joint it is not held by here: followed");
        }
        helper.succeed();
    }

    /** A replica simulated with a fixed rotation turns as the server's body again once it is only followed. */
    @GameTest(template = "empty", batch = "minebox3d_predicted", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aFixedRotationReplicaFollowedAgainTurnsAsTheServers(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Vec3 c = stage.centre;
            float[] inertia = {4, 0, 0, 0, 4, 0, 0, 0, 4};
            stage.spawn(
                    1,
                    Stage.state(1, c.x, c.y + 3, c.z),
                    0,
                    new BodySyncPayload.Dynamics(40, 0, 0, 0, inertia, true, 0, 0, 0, true));
            Body replica = stage.physics.sync().replica(1);
            stage.physics.sync().beforeSteps(1 / 20f);
            helper.assertTrue(SyncedBodies.isSimulated(replica) && replica.isFixedRotation(), "simulated, not turning");

            // touching what this client has not got: followed from the next state on, turned a quarter about y
            Quaternionf quarter = new Quaternionf().rotationY((float) (Math.PI / 2));
            BodySyncPayload.State turned = new BodySyncPayload.State(
                    1, c.x, c.y + 3, c.z, quarter.x, quarter.y, quarter.z, quarter.w, 0, 0, 0, 0, 0, 0, false, 0);
            for (int i = 0; i < 5; i++) {
                stage.physics
                        .sync()
                        .apply(new BodySyncPayload(
                                List.of(), List.of(turned), new int[0], false, stage.physics.ticks(), (int)
                                        stage.physics.ticks()));
                stage.physics.sync().beforeSteps(1 / 20f);
                stage.ticks(helper, 1);
            }
            helper.assertTrue(!SyncedBodies.isSimulated(replica), "followed");
            float angle = replica.rotation(new Quaternionf()).angle();
            helper.assertTrue(Math.abs(angle - Math.PI / 2) < 0.05, "turned as the server's: " + angle);
        }
        helper.succeed();
    }

    /**
     * How a body moves changed on the server — its gravity, its damping — is put on its replica where it is, not the
     * replica made again; and the joints the predictor's own body holds a replica by (standing on it) are not what
     * keeps it from being simulated here — the server's count leaves them out too.
     */
    @GameTest(template = "empty", batch = "minebox3d_predicted", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aTuningIsPutOnTheReplicaAndItsOwnJointsDoNotCount(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Vec3 c = stage.centre;
            float[] inertia = {4, 0, 0, 0, 4, 0, 0, 0, 4};
            stage.spawn(
                    1, Stage.state(1, c.x, c.y + 3, c.z), 0, new BodySyncPayload.Dynamics(40, 0, 0, 0, inertia, true));
            Body crate = stage.physics.sync().replica(1);
            stage.physics.sync().beforeSteps(1 / 20f);
            helper.assertTrue(SyncedBodies.isSimulated(crate) && crate.gravityScale() == 1, "simulated, falling");
            stage.physics
                    .sync()
                    .apply(new BodySyncPayload(
                            List.of(),
                            List.of(),
                            new int[0],
                            false,
                            stage.physics.ticks(),
                            BodySyncPayload.NO_FRAME,
                            List.of(new BodySyncPayload.Tuning(
                                    1, new BodySyncPayload.Dynamics(40, 0, 0, 0, inertia, true, 0.5f, 0, 0, false)))));
            helper.assertTrue(stage.physics.sync().replica(1) == crate, "the same replica");
            helper.assertTrue(crate.gravityScale() == 0 && crate.linearDamping() == 0.5f, "moving as the server's now");

            // held on the server by a joint this client has not got, and here by its own body standing on it: that
            // joint is its own body's, not the one the server counts — followed
            stage.spawn(
                    2,
                    new BodySyncPayload.State(2, c.x + 3, c.y + 3, c.z, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, true, 1),
                    0,
                    new BodySyncPayload.Dynamics(40, 0, 0, 0, inertia, true));
            Body held = stage.physics.sync().replica(2);
            Body own = stage.physics.createBody(
                    new BodyDef().type(BodyType.DYNAMIC).position(c.x + 3, c.y + 4, c.z));
            own.createShape(new ShapeDef().density(100), Geometry.Box.of(0.3f, 0.3f, 0.3f));
            LevelBodies.setNeedsTerrain(own, false);
            stage.predictor.own = own;
            stage.physics.world().createJoint(new RevoluteJointDef().bodies(held, own));
            stage.physics.sync().beforeSteps(1 / 20f);
            helper.assertTrue(
                    !SyncedBodies.isSimulated(held), "its own body's joint is not the one the server holds it by");
        }
        helper.succeed();
    }

    /**
     * A predictor that comes after another — its tree made again — finds its own body, sent while the first one was.
     */
    @GameTest(template = "empty", batch = "minebox3d_predicted", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void aPredictorAfterAnotherFindsItsOwnBody(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Vec3 c = stage.centre;
            Body own = stage.physics.createBody(
                    new BodyDef().type(BodyType.DYNAMIC).position(c.x + 1, c.y + 0.4, c.z));
            own.createShape(new ShapeDef().density(100), Geometry.Box.of(0.4f, 0.4f, 0.4f));
            LevelBodies.setNeedsTerrain(own, false);
            stage.predictor.own = own;
            stage.spawn(5, c.x + 1, c.y + 0.4, c.z, OWN_TAG);
            Predicting again = new Predicting(c);
            again.own = own;
            stage.physics.sync().setPredictor(again);
            helper.assertTrue(stage.physics.sync().replica(5) == null, "still no replica of its own");
            stage.ticks(helper, 10);
            long then = stage.physics.ticks() - 2;
            Vector3d recorded = stage.physics.rewind().history().position(then, own, new Vector3d());
            helper.assertTrue(recorded != null, "its own recorded");
            stage.state(5, recorded.x, recorded.y, recorded.z + 0.3, then);
            stage.tick();
            helper.assertTrue(
                    stage.physics.rewind().resimulations() == 1, "the server's word of it held against its own");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "minebox3d_predicted", timeoutTicks = 100)
    @PrefixGameTestTemplate(false)
    public static void withNothingPredictingTheReplicasFollowAsEver(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Vec3 c = stage.centre;
            stage.spawn(1, c.x, c.y + 3, c.z, 0);
            Body crate = stage.physics.sync().replica(1);
            stage.physics.sync().setPredictor(null);
            stage.physics.sync().beforeSteps(1 / 20f);
            stage.ticks(helper, 5);
            helper.assertTrue(crate.type() == BodyType.KINEMATIC, "following");
            helper.assertTrue(crate.position(new Vector3d()).y > c.y + 2.9, "where the server said");
            helper.assertTrue(!stage.physics.rewind().isWatching(), "and nothing held");
            helper.assertTrue(LevelRewind.BUBBLE_RADIUS > 0, "the bubble's");
        }
        helper.succeed();
    }
}
