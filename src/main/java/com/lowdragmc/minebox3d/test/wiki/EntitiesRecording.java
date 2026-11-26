package com.lowdragmc.minebox3d.test.wiki;

import static com.lowdragmc.minebox3d.test.wiki.WikiStage.Y;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIRecording;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityShapes;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;

/** The entities page: a minecart on powered rails ploughing through three stacks of crates. */
@LDLRegisterClient(
        name = "mb3_wiki_entities",
        group = "minebox3d_wiki",
        registry = UIRecording.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class EntitiesRecording implements UIRecording {

    private static final int[] STACKS = {-6, 1, 8};
    private static final String TAG = "mb3_wiki";
    private static boolean registered;
    private static final double CART_X = -14.5;
    private static final double[] FROM = {CART_X - 5, Y + 4, -8, CART_X + 3, Y + 0.5, 0.5};

    @Override
    public void configure(ScenarioOptions options) {
        WikiScenes.configure(options);
    }

    @Override
    public void define(ScenarioBuilder s) {
        WikiScenes.open(s, WikiView.pan(FROM, FROM), EntitiesRecording::build);
        s.server("the stacks", sc -> stacks(sc.level())).ticks(60);
        s.take("entities", o -> WikiScenes.video(o, 4500), t -> t.step(
                        "follow the minecart", ctx -> WikiView.play(EntitiesRecording::follow, 1))
                .server("the minecart", sc -> minecart(sc.level()))
                .caption("en", "Entities take part as kinematic bodies: a minecart shoves the crates aside")
                .caption("zh", "实体以运动学刚体参与模拟：矿车把木箱撞开")
                .hold(10_000));
        WikiScenes.close(s);
    }

    static void build(ServerLevel level) {
        WikiStage.fill(level, -16, Y - 1, -3, 16, Y - 1, 4, Blocks.SMOOTH_STONE.defaultBlockState());
        WikiStage.fill(level, -16, Y - 1, 0, 16, Y - 1, 0, Blocks.REDSTONE_BLOCK.defaultBlockState());
        WikiStage.fill(
                level,
                -16,
                Y,
                0,
                16,
                Y,
                0,
                Blocks.POWERED_RAIL
                        .defaultBlockState()
                        .setValue(PoweredRailBlock.SHAPE, RailShape.EAST_WEST)
                        .setValue(PoweredRailBlock.POWERED, true));
        WikiStage.fill(level, 17, Y, -1, 17, Y + 1, 1, Blocks.STONE_BRICKS.defaultBlockState());
    }

    /** Three crates across the track, two high, at each stack. */
    private static void stacks(ServerLevel level) {
        LevelPhysics physics = WikiStage.physics(level);
        for (int x : STACKS) {
            for (int layer = 0; layer < 2; layer++) {
                for (int z = -1; z <= 1; z++) {
                    WikiStage.body(
                            physics,
                            x + 0.5,
                            Y + 0.42 + layer * 0.86,
                            z + 0.5,
                            Geometry.Box.of(0.42f, 0.42f, 0.42f),
                            new ShapeDef().density(150).friction(0.5f));
                }
            }
        }
    }

    /** Beside the minecart and a little behind it, looking ahead of it. */
    private static double[] follow(double t) {
        Minecraft minecraft = Minecraft.getInstance();
        double x = CART_X;
        if (minecraft.level != null) {
            float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof Minecart) {
                    x = Math.min(entity.getPosition(partial).x, 9);
                }
            }
        }
        return WikiView.still(x - 5, Y + 4, -8, x + 3, Y + 0.5, 0.5).at(t);
    }

    /** Minecarts tagged for the video weigh what no crate can stop: an {@link EntityShapes} provider's mass. */
    private static void unstoppable() {
        if (registered) {
            return;
        }
        registered = true;
        EntityShapes.register(new EntityShapes.Provider() {
            @Override
            public List<Geometry> shapes(Entity entity) {
                return null;
            }

            @Override
            public float mass(Entity entity) {
                return entity instanceof Minecart && entity.getTags().contains(TAG)
                        ? Float.POSITIVE_INFINITY
                        : Float.NaN;
            }
        });
    }

    private static void minecart(ServerLevel level) {
        unstoppable();
        Minecart cart = EntityType.MINECART.create(level);
        if (cart == null) {
            throw new IllegalStateException("no minecart");
        }
        cart.moveTo(CART_X, Y + 0.1, 0.5, 0, 0);
        cart.setDeltaMovement(0.6, 0, 0);
        cart.addTag(TAG);
        level.addFreshEntity(cart);
    }
}
