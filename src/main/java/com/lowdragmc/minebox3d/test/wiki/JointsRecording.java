package com.lowdragmc.minebox3d.test.wiki;

import static com.lowdragmc.minebox3d.test.wiki.WikiStage.Y;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIRecording;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.RevoluteJointDef;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.SphericalJointDef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.joml.Quaternionf;

/**
 * The joints page: a wrecking ball on a chain of capsules joined by spherical joints, swung into a wall of crates, and
 * a plank bridge on revolute joints taking a load.
 */
@LDLRegisterClient(
        name = "mb3_wiki_joints",
        group = "minebox3d_wiki",
        registry = UIRecording.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class JointsRecording implements UIRecording {

    /** The chain hangs from here: the middle of the beam's underside. */
    private static final double PIVOT_X = 0.5, PIVOT_Y = Y + 10, PIVOT_Z = 0.5;

    private static final int LINKS = 7;
    private static final float LINK = 1.0f;
    /** The bridge spans x from -BRIDGE to +BRIDGE at z = BRIDGE_Z, its deck at BRIDGE_Y. */
    private static final int BRIDGE = 6, BRIDGE_Z = 14;

    private static final double BRIDGE_Y = Y + 4.5;
    private static final double[] BALL_VIEW = {-9, Y + 6, -12, 1, Y + 4, 0.5};
    private static final double[] BRIDGE_VIEW = {0.5, Y + 8, 4, 0.5, Y + 3, BRIDGE_Z + 0.5};

    @Override
    public void configure(ScenarioOptions options) {
        WikiScenes.configure(options);
    }

    @Override
    public void define(ScenarioBuilder s) {
        WikiScenes.open(s, WikiView.pan(BALL_VIEW, BALL_VIEW), JointsRecording::build);
        s.server("the wall and the bridge", sc -> {
                    wall(sc.level());
                    bridge(sc.level());
                })
                .ticks(60);
        s.take("joints", o -> WikiScenes.video(o, 3600), t -> t.server("let go", sc -> wreckingBall(sc.level()))
                .caption("en", "Seven capsules on spherical joints, and a heavy ball")
                .caption("zh", "七节胶囊用球关节连成链，挂着一个重球")
                .hold(7000)
                .step("cut to the bridge", ctx -> WikiView.play(WikiView.pan(BRIDGE_VIEW, BRIDGE_VIEW), 1))
                .hold(1000)
                .server("load the bridge", sc -> load(sc.level()))
                .caption("en", "Planks on revolute joints sag under the load")
                .caption("zh", "铰链关节连起来的木板在重压下下沉")
                .hold(6000));
        WikiScenes.close(s);
    }

    static void build(ServerLevel level) {
        WikiStage.fill(level, -10, Y - 1, -6, 10, Y - 1, 6, Blocks.SMOOTH_STONE.defaultBlockState());
        WikiStage.fill(level, 0, Y, -3, 0, Y + 9, -3, Blocks.STONE_BRICKS.defaultBlockState());
        WikiStage.fill(level, 0, Y, 3, 0, Y + 9, 3, Blocks.STONE_BRICKS.defaultBlockState());
        WikiStage.fill(level, 0, Y + 10, -3, 0, Y + 10, 3, Blocks.STONE_BRICKS.defaultBlockState());
        for (int side : new int[] {-1, 1}) {
            int x = side * (BRIDGE + 1);
            WikiStage.fill(
                    level,
                    x,
                    Y,
                    BRIDGE_Z - 1,
                    x,
                    (int) BRIDGE_Y - 1,
                    BRIDGE_Z + 1,
                    Blocks.STONE_BRICKS.defaultBlockState());
        }
    }

    /** A wall of crates 4 wide and 5 high, just past where the ball is lowest. */
    private static void wall(ServerLevel level) {
        LevelPhysics physics = WikiStage.physics(level);
        for (int row = 0; row < 5; row++) {
            for (int z = -2; z <= 1; z++) {
                WikiStage.body(
                        physics,
                        2.5,
                        Y + 0.45 + row * 0.9,
                        z + 0.5 + (row % 2) * 0.2,
                        Geometry.Box.of(0.45f, 0.45f, 0.45f),
                        WikiStage.wood());
            }
        }
    }

    /** The chain laid out level towards -x from the pivot, so it falls and swings through the wall. */
    private static void wreckingBall(ServerLevel level) {
        LevelPhysics physics = WikiStage.physics(level);
        Body anchor = anchor(physics, PIVOT_X, PIVOT_Y, PIVOT_Z);
        ShapeDef steel = new ShapeDef().density(2000).friction(0.4f);
        Body previous = anchor;
        double x = PIVOT_X;
        for (int i = 0; i < LINKS; i++) {
            Body link = WikiStage.body(
                    physics,
                    x - LINK / 2,
                    PIVOT_Y,
                    PIVOT_Z,
                    new Geometry.Capsule(-0.35f, 0, 0, 0.35f, 0, 0, 0.12f),
                    steel);
            physics.world()
                    .createJoint(new SphericalJointDef().bodies(previous, link).worldFrame(x, PIVOT_Y, PIVOT_Z));
            previous = link;
            x -= LINK;
        }
        Body ball = WikiStage.body(
                physics, x - 0.9, PIVOT_Y, PIVOT_Z, new Geometry.Sphere(0, 0, 0, 0.9f), new ShapeDef().density(4000));
        physics.world()
                .createJoint(new SphericalJointDef().bodies(previous, ball).worldFrame(x, PIVOT_Y, PIVOT_Z));
    }

    /** Planks hinged end to end about z, the two ends hinged to the piers. */
    private static void bridge(ServerLevel level) {
        LevelPhysics physics = WikiStage.physics(level);
        int planks = 2 * BRIDGE + 1;
        float length = (2 * BRIDGE + 1f) / planks;
        Quaternionf alongZ = new Quaternionf();
        Body previous = anchor(physics, -BRIDGE - 0.5, BRIDGE_Y, BRIDGE_Z + 0.5);
        for (int i = 0; i < planks; i++) {
            double x0 = -BRIDGE - 0.5 + i * length;
            Body plank = WikiStage.body(
                    physics,
                    x0 + length / 2,
                    BRIDGE_Y,
                    BRIDGE_Z + 0.5,
                    Geometry.Box.of(length / 2 - 0.02f, 0.08f, 0.9f),
                    WikiStage.wood());
            physics.world()
                    .createJoint(new RevoluteJointDef()
                            .bodies(previous, plank)
                            .worldFrame(x0, BRIDGE_Y, BRIDGE_Z + 0.5, alongZ));
            previous = plank;
        }
        Body end = anchor(physics, BRIDGE + 0.5, BRIDGE_Y, BRIDGE_Z + 0.5);
        physics.world()
                .createJoint(new RevoluteJointDef()
                        .bodies(previous, end)
                        .worldFrame(BRIDGE + 0.5, BRIDGE_Y, BRIDGE_Z + 0.5, alongZ));
    }

    /** Crates dropped onto the middle of the bridge. */
    private static void load(ServerLevel level) {
        LevelPhysics physics = WikiStage.physics(level);
        for (int i = 0; i < 9; i++) {
            WikiStage.body(
                    physics,
                    -2 + (i % 3) * 2 + 0.5,
                    BRIDGE_Y + 3 + (i / 3) * 1.2,
                    BRIDGE_Z + 0.5,
                    Geometry.Box.of(0.4f, 0.4f, 0.4f),
                    new ShapeDef().density(900).friction(0.6f));
        }
    }

    /** A static body to hang things from; static bodies are not drawn, the blocks around it are. */
    private static Body anchor(LevelPhysics physics, double x, double y, double z) {
        Body anchor = physics.createBody(new BodyDef().position(x, y, z).name("wiki anchor"));
        anchor.attach(WikiStage.Mark.class, WikiStage.Mark.MARK);
        return anchor;
    }
}
