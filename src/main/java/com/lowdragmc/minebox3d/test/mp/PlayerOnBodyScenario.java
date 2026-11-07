package com.lowdragmc.minebox3d.test.mp;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegister;
import com.lowdragmc.lowdraglib2.uitest.ServerContext;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.mp.MPScenario;
import com.lowdragmc.lowdraglib2.uitest.mp.MPScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.mp.MPScenarioOptions;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityBases;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;

/**
 * <b>Players and the bodies</b> (design doc {@code 04-entities-and-bodies.md} §11, batch 4c-3), over a real dedicated
 * server and two real clients: A lands on a synced deck on its own client, and the server — which takes the client's word
 * on bodies — has it standing on the server's deck; the deck sets off and A goes along on both sides, A's moves not taken
 * for wrong ones (A plays survival, where the server checks them); the deck turns a quarter and carries A round,
 * turning it too; B sees A stay put on the moving deck (placed on its replica, not eased in from Minecraft's packets),
 * looking down and turned with the deck; B, back from far off, sees the stand on the deck again, and put off it, at
 * once where it was put; B walks into a synced wall and stops at it, on both sides; B pushes a light synced crate
 * along; and A, sent far off its moving deck onto a deck its client has not got yet, lands on it rather than through
 * it, and is not dragged along by the old one on the server.
 *
 * <p>⚠️ The harness's server allows flight, so the flying kick a body under a player keeps away is not reached here
 * (GameTest: EntityBlockedGameTest's body-under check).
 *
 * <p>{@code ./gradlew runMpTest -PldMpTest=group:minebox3d -PldTestHeadless}
 */
@LDLRegister(name = "mb3_mp_player_on_body", group = "minebox3d", registry = MPScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PlayerOnBodyScenario implements MPScenario {

    private static final String DECK = "mb3_mp_deck";
    private static final String WALL = "mb3_mp_wall";
    private static final String CRATE = "mb3_mp_crate";
    private static final String CRATE_A = "mb3_mp_crate_a";
    private static final String CRATE_B = "mb3_mp_crate_b";
    private static final String CRATE_C = "mb3_mp_crate_c";
    /** A body that is not synced: B's client never has it. */
    private static final String HIDDEN = "mb3_mp_hidden";
    private static final String CRATE_X = "mb3.crate.x", CRATE_A_X = "mb3.crate_a.x", CRATE_B_X = "mb3.crate_b.x",
            CRATE_C_X = "mb3.crate_c.x";
    private static final String FAR_DECK = "mb3_mp_far_deck";
    private static final String RIDER = "mb3_mp_rider";
    private static final double GROUND = -60;
    private static final double FAR_X = 300.5;

    @Override
    public void configure(MPScenarioOptions options) {
        options.clients("A", "B").tags("mp", "sync", "players");
    }

    private static LevelPhysics physics(ServerContext sc) {
        LevelPhysics physics = LevelPhysics.of(sc.server().overworld());
        if (physics == null) {
            throw new IllegalStateException("Box3D is not available on the server");
        }
        return physics;
    }

    @Nullable
    private static Body named(ServerContext sc, String name) {
        for (Body body : physics(sc).world().bodies()) {
            if (body.isValid() && name.equals(body.name())) {
                return body;
            }
        }
        return null;
    }

    private static Body body(ServerContext sc, String name) {
        Body body = named(sc, name);
        if (body == null) {
            throw new IllegalStateException("no body " + name);
        }
        return body;
    }

    /** A synced body of one box. */
    private static Body make(ServerContext sc, String name, BodyType type, double x, double y, double z, float hx, float hy,
                             float hz, float density) {
        Body body = physics(sc).createBody(new BodyDef().type(type).position(x, y, z).name(name));
        body.createShape(new ShapeDef().density(density), Geometry.Box.of(hx, hy, hz));
        LevelBodies.setSynced(body, true);
        return body;
    }

    /** Ticks B went less than 0.02 along while W was down, past getting going: held up by a crate that did not give. */
    private static int heldUp(List<double[]> ticks) {
        int held = 0;
        for (int i = 11; i < Math.min(30, ticks.size()); i++) {
            if (ticks.get(i)[0] - ticks.get(i - 1)[0] < 0.02) {
                held++;
            }
        }
        return held;
    }

    /** That this client's replica named {@code name} is at the server's x for it, as fetched. */
    private static void sameAsServer(TestContext ctx, String name, Double serverX) {
        double off = Math.abs(MpClient.replica(name).position(new Vector3d()).x - serverX);
        ctx.check(name + "'s replica where the server has it", off < 0.01, "< 0.01", off);
    }

    /** The armor stand riding the deck (named {@link #RIDER}). */
    private static Entity rider(ServerContext sc) {
        Entity stand = riderOrNull(sc);
        if (stand == null) {
            throw new IllegalStateException("the stand is gone");
        }
        return stand;
    }

    @Nullable
    private static Entity riderOrNull(ServerContext sc) {
        for (Entity entity : sc.server().overworld().getAllEntities()) {
            if (entity.hasCustomName() && RIDER.equals(entity.getCustomName().getString())) {
                return entity;
            }
        }
        return null;
    }

    /** A private field of vanilla's, read for a check (LDLib2's reflection helper). */
    @Nullable
    private static Object getField(ServerContext sc, Object target, String name) {
        return sc.getField(target, name);
    }

    /** An entity's feet less a body's position. */
    private static Vec3 offset(Entity entity, Body body) {
        Vector3d p = body.position(new Vector3d());
        return new Vec3(entity.getX() - p.x, entity.getY() - p.y, entity.getZ() - p.z);
    }

    /** On this client: whether its own player stands on the replica named {@code name}. */
    private static boolean standsOn(String name) {
        Entity self = MpClient.self();
        Body replica = MpClient.replica(name);
        return self != null && replica != null && self.onGround() && EntityBases.baseOf(self) == replica;
    }

    @Override
    public void define(MPScenarioBuilder s) {
        s.server("A, playing survival, above a synced deck, an armor stand on it too; B on the ground beside it", sc -> {
            sc.player("A").setGameMode(GameType.SURVIVAL);
            sc.player("A").teleportTo(sc.server().overworld(), 2.5, GROUND + 4, 0.5, 0, 0);
            sc.player("B").teleportTo(sc.server().overworld(), 2.5, GROUND, 8.5, 0, 0);
            make(sc, DECK, BodyType.KINEMATIC, 2.5, GROUND + 2.25, 0.5, 3, 0.25f, 2, 1); // its top at 2.5 up
            ArmorStand stand = EntityType.ARMOR_STAND.create(sc.server().overworld());
            stand.moveTo(4.5, GROUND + 3, 1.5);
            stand.setCustomName(net.minecraft.network.chat.Component.literal(RIDER));
            sc.server().overworld().addFreshEntity(stand);
        })
        .client("A", "A lands on the deck's replica", b -> b
                .waitUntil("standing on it", ctx -> standsOn(DECK)))
        .serverWaitUntil("the server has A standing on its deck, where A's client says; and the stand",
                sc -> EntityBases.baseOf(sc.player("A")) == named(sc, DECK) && EntityBases.baseOf(rider(sc)) == named(sc, DECK))
        .fetch("a.x", "where A stands on the server's deck", Double.class, sc -> offset(sc.player("A"), body(sc, DECK)).x)
        .fetch("a.z", "where A stands on the server's deck", Double.class, sc -> offset(sc.player("A"), body(sc, DECK)).z)
        .fetch("stand.x", "where the stand stands on it", Double.class, sc -> offset(rider(sc), body(sc, DECK)).x)
        .fetch("stand.z", "where the stand stands on it", Double.class, sc -> offset(rider(sc), body(sc, DECK)).z)
        .server("the deck sets off along x at 4 m/s", sc -> {
            Vec3 start = offset(sc.player("A"), body(sc, DECK));
            sc.put("start.x", start.x);
            sc.put("start.z", start.z);
            sc.put("ride.ticks", 0);
            sc.put("ride.corrections", 0);
            body(sc, DECK).setLinearVelocity(4, 0, 0);
        })
        .client("B", "B sees A and the stand on the moving deck where the server has them", b -> b
                .waitUntil("both placed on B's replica", ctx -> MpClient.player("A") != null
                        && MpClient.isPlaced(MpClient.player("A")) && MpClient.replica(DECK) != null
                        && MpClient.named(RIDER) != null && MpClient.isPlaced(MpClient.named(RIDER)))
                .waitUntil("a little way along", ctx -> MpClient.replica(DECK).linearVelocity(new org.joml.Vector3f()).x > 3.9)
                .step("where they are on it", ctx -> {
                    Vec3 a = offset(MpClient.player("A"), MpClient.replica(DECK));
                    double off = Math.hypot(a.x - (double) ctx.get("a.x"), a.z - (double) ctx.get("a.z"));
                    ctx.check("A where the server has it on the deck, as B sees it", off < 0.1, "< 0.1", off);
                    Vec3 stand = offset(MpClient.named(RIDER), MpClient.replica(DECK));
                    off = Math.hypot(stand.x - (double) ctx.get("stand.x"), stand.z - (double) ctx.get("stand.z"));
                    ctx.check("the stand where the server has it on the deck, as B sees it", off < 0.1, "< 0.1", off);
                }))
        .client("A", "A looks down", b -> b
                .step("pitch 30", ctx -> MpClient.self().setXRot(30)))
        .client("B", "B sees A look down, placed as A is", b -> b
                .waitUntil("A's pitch", ctx -> MpClient.player("A") != null && MpClient.isPlaced(MpClient.player("A"))
                        && Math.abs(MpClient.player("A").getXRot() - 30) < 1))
        .serverWaitUntil("two seconds of riding, every tick the server not pulling A back", sc -> {
            if (getField(sc, sc.player("A").connection, "awaitingPositionFromClient") != null) {
                sc.put("ride.corrections", (int) sc.get("ride.corrections") + 1);
            }
            int ticks = (int) sc.get("ride.ticks") + 1;
            sc.put("ride.ticks", ticks);
            return ticks >= 40;
        })
        .server("A went along on the server, and stands where it stood", sc -> {
            Body deck = body(sc, DECK);
            deck.setLinearVelocity(0, 0, 0);
            int corrections = sc.get("ride.corrections");
            sc.check("no tick the server was setting A back", corrections == 0, 0, corrections);
            Vec3 now = offset(sc.player("A"), deck);
            double off = Math.hypot(now.x - (double) sc.get("start.x"), now.z - (double) sc.get("start.z"));
            sc.check("A on the server's deck where it stood (not pulled back, not left behind)", off < 0.15, "< 0.15", off);
            sc.check("the deck went along", deck.position(new Vector3d()).x > 2.5 + 6, "> 8.5", deck.position(new Vector3d()).x);
            sc.put("yaw", sc.player("A").getYRot());
            deck.setAngularVelocity(0, (float) (Math.PI / 2), 0); // a quarter turn a second, counter-clockwise from above
        })
        .serverSettle(20)
        .server("a quarter turn on: A carried round with the deck and turned with it, on the server", sc -> {
            Body deck = body(sc, DECK);
            deck.setAngularVelocity(0, 0, 0);
            // in the deck's frame A has not moved: where it stood, turned a quarter about +Y, (x, z) → (z, -x)
            Vec3 now = offset(sc.player("A"), deck);
            double sx = sc.get("start.x"), sz = sc.get("start.z");
            double off = Math.hypot(now.x - sz, now.z + sx);
            sc.check("A carried round a quarter on the server's deck", off < 0.2, "< 0.2", off);
            float turned = net.minecraft.util.Mth.wrapDegrees(sc.player("A").getYRot() - (float) sc.get("yaw"));
            sc.check("A turned with it (yaw clockwise from above)", Math.abs(turned + 90) < 10, -90, turned);
        })
        .fetch("a.yaw", "A's yaw on the server", Double.class, sc -> (double) sc.player("A").getYRot())
        .client("B", "B sees A turned with the deck", b -> b
                .waitUntil("A's yaw as the server has it", ctx -> MpClient.player("A") != null && Math.abs(
                        net.minecraft.util.Mth.wrapDegrees(MpClient.player("A").getYRot() - (float) (double) ctx.get("a.yaw"))) < 10))
        // told of the stand before its entity reaches B (B coming back), B keeps the word till it does
        .server("B far off, out of sight of the deck", sc -> sc.player("B").teleportTo(sc.server().overworld(), 600.5, GROUND,
                8.5, 0, 0))
        .client("B", "the stand gone from B's world", b -> b
                .waitUntil("no stand", ctx -> MpClient.named(RIDER) == null))
        .server("B back beside the deck", sc -> sc.player("B").teleportTo(sc.server().overworld(), 2.5, GROUND, 8.5, 0, 0))
        .client("B", "B sees the stand placed on the deck again", b -> b
                .waitUntil("placed", ctx -> MpClient.named(RIDER) != null && MpClient.isPlaced(MpClient.named(RIDER))))

        .server("the stand put off the deck, on the ground", sc -> rider(sc).teleportTo(6.5, GROUND, 4.5))
        .client("B", "B is told the stand is off the deck, and has it where the server put it at once", b -> {
            long[] since = {-1};
            b.waitUntil("the stand no longer placed", ctx -> MpClient.named(RIDER) != null
                            && !MpClient.isPlaced(MpClient.named(RIDER)))
                    // Minecraft's own word, let go while the stand was placed, not a resend seconds later
                    .waitUntil("on the ground where put, or 10 ticks", ctx -> {
                        Vec3 at = MpClient.named(RIDER).position();
                        if (since[0] < 0) {
                            since[0] = MpClient.gameTime();
                        }
                        return Math.hypot(at.x - 6.5, at.z - 4.5) < 0.05 || MpClient.gameTime() - since[0] > 10;
                    })
                    .step("where put", ctx -> {
                        Vec3 at = MpClient.named(RIDER).position();
                        double off = Math.hypot(at.x - 6.5, at.z - 4.5);
                        ctx.check("the stand where the server put it, as B sees it", off < 0.05, "< 0.05", off);
                    });
        })
        .client("A", "A's own client has it where it stood on the deck", b -> b
                .waitUntil("still standing on it", ctx -> standsOn(DECK)))

        .server("a synced wall in B's way, B a block and a half off", sc -> {
            sc.player("B").teleportTo(sc.server().overworld(), 3.5, GROUND, 8.5, 0, 0);
            make(sc, WALL, BodyType.STATIC, 5.25, GROUND + 1, 8.5, 0.25f, 1, 1, 1); // its face at x = 5
        })
        .client("B", "B walks into the wall's replica and stops at it", b -> b
                .waitUntil("the wall arrived, B where sent", ctx -> MpClient.replica(WALL) != null
                        && Math.abs(MpClient.self().getX() - 3.5) < 0.01)
                .step("walk", ctx -> MpClient.self().setDeltaMovement(1.2, 0, 0))
                .waitUntil("stopped at the face", ctx -> Math.abs(MpClient.self().getDeltaMovement().x) < 1e-3
                        && MpClient.self().getX() > 4.5)
                .step("at the face, not through it", ctx -> {
                    double face = MpClient.self().getX() + 0.3;
                    ctx.check("B's front at the wall's face", face <= 5.0 && face > 4.95, "(4.95, 5]", face);
                }))
        .serverSettle(5)
        .server("the server has B there too", sc -> {
            double face = sc.player("B").getX() + 0.3;
            sc.check("B's front at the wall's face on the server", face <= 5.0 && face > 4.95, "(4.95, 5]", face);
        })

        .server("a light synced crate in front of B, B facing it", sc -> {
            sc.player("B").teleportTo(sc.server().overworld(), 2.5, GROUND, 12.5, -90, 0);
            make(sc, CRATE, BodyType.DYNAMIC, 3.5, GROUND + 0.3, 12.5, 0.3f, 0.3f, 0.3f, 23); // 5 kg
        })
        .client("B", "B walks the crate along, W held for a second and a half", b -> {
            long[] since = {-1};
            b.waitUntil("the crate arrived", ctx -> MpClient.replica(CRATE) != null
                            && Math.abs(MpClient.self().getZ() - 12.5) < 0.01)
                    .step("walk", ctx -> {
                        MpClient.track(CRATE);
                        MpClient.walk(true);
                        since[0] = MpClient.gameTime();
                    })
                    .waitUntil("walked", ctx -> MpClient.gameTime() - since[0] >= 30)
                    .step("stop", ctx -> MpClient.walk(false))
                    // the last push on the server, and its result back here
                    .waitUntil("the crate come to rest", ctx -> MpClient.gameTime() - since[0] >= 70)
                    .step("how the walk went", ctx -> {
                        MpClient.track(null);
                        List<double[]> ticks = MpClient.tracked();
                        double back = 0;
                        for (int i = 1; i < ticks.size(); i++) {
                            back = Math.max(back, ticks.get(i - 1)[1] - ticks.get(i)[1]);
                        }
                        // the client's word on where B is, the push told to the server: the replica gives as the
                        // server's body moves, a round trip on
                        ctx.log("pushing the crate: B held up " + heldUp(ticks) + " tick(s), its replica back by at most "
                                + back);
                        ctx.check("the replica never went back", back < 0.02, "< 0.02", back);
                        ctx.check("the replica walked along", ticks.getLast()[1] - 3.5 > 0.3, "> 0.3",
                                ticks.getLast()[1] - 3.5);
                    });
        })
        .server("the crate went along", sc -> {
            double went = body(sc, CRATE).position(new Vector3d()).x - 3.5;
            sc.log("the crate went " + went + " on the server");
            sc.check("B pushed the crate on the server", went > 0.3, "> 0.3", went);
        })
        .fetch(CRATE_X, "where the server has the crate, at rest", Double.class,
                sc -> body(sc, CRATE).position(new Vector3d()).x)
        .client("B", "B's replica of it where the server has it", b -> b
                .step("beside the server's", ctx -> sameAsServer(ctx, CRATE, ctx.get(CRATE_X))))

        // one crate pushed into another: both go along, on the server and as B's client has them
        .server("two crates in a row in front of B, B facing them", sc -> {
            sc.player("B").teleportTo(sc.server().overworld(), 2.5, GROUND, 16.5, -90, 0);
            make(sc, CRATE_A, BodyType.DYNAMIC, 3.5, GROUND + 0.3, 16.5, 0.3f, 0.3f, 0.3f, 23);
            make(sc, CRATE_B, BodyType.DYNAMIC, 4.4, GROUND + 0.3, 16.5, 0.3f, 0.3f, 0.3f, 23);
        })
        .client("B", "B walks the first crate into the second", b -> {
            long[] since = {-1};
            b.waitUntil("the crates arrived", ctx -> MpClient.replica(CRATE_A) != null && MpClient.replica(CRATE_B) != null
                            && Math.abs(MpClient.self().getZ() - 16.5) < 0.01)
                    .step("walk", ctx -> {
                        MpClient.walk(true);
                        since[0] = MpClient.gameTime();
                    })
                    .waitUntil("walked", ctx -> MpClient.gameTime() - since[0] >= 30)
                    .step("stop", ctx -> MpClient.walk(false))
                    .waitUntil("the crates at rest", ctx -> MpClient.gameTime() - since[0] >= 70);
        })
        .fetch(CRATE_A_X, "where the server has the first crate", Double.class,
                sc -> body(sc, CRATE_A).position(new Vector3d()).x)
        .fetch(CRATE_B_X, "and the second", Double.class, sc -> body(sc, CRATE_B).position(new Vector3d()).x)
        .server("both went along on the server", sc -> {
            double went = body(sc, CRATE_B).position(new Vector3d()).x - 4.4;
            sc.check("the second crate pushed along by the first", went > 0.3, "> 0.3", went);
        })
        .client("B", "B's replicas of them where the server has them", b -> b
                .step("beside the server's", ctx -> {
                    sameAsServer(ctx, CRATE_A, ctx.get(CRATE_A_X));
                    sameAsServer(ctx, CRATE_B, ctx.get(CRATE_B_X));
                }))

        // a crate walked into what B's client does not have — a body that is not synced: it stops there on the server, and
        // as B's client has it
        .server("a crate in front of B, a body its client does not have just past it", sc -> {
            sc.player("B").teleportTo(sc.server().overworld(), 2.5, GROUND, 20.5, -90, 0);
            make(sc, CRATE_C, BodyType.DYNAMIC, 3.5, GROUND + 0.3, 20.5, 0.3f, 0.3f, 0.3f, 23);
            Body hidden = physics(sc).createBody(new BodyDef().type(BodyType.STATIC).position(4.65, GROUND + 0.5, 20.5)
                    .name(HIDDEN));
            hidden.createShape(new ShapeDef(), Geometry.Box.of(0.25f, 0.5f, 1)); // its face at x = 4.4
        })
        .client("B", "B walks the crate into it", b -> {
            long[] since = {-1};
            b.waitUntil("the crate arrived", ctx -> MpClient.replica(CRATE_C) != null
                            && Math.abs(MpClient.self().getZ() - 20.5) < 0.01)
                    .step("walk", ctx -> {
                        MpClient.walk(true);
                        since[0] = MpClient.gameTime();
                    })
                    .waitUntil("walked", ctx -> MpClient.gameTime() - since[0] >= 30)
                    .step("stop", ctx -> MpClient.walk(false))
                    .waitUntil("the crate at rest", ctx -> MpClient.gameTime() - since[0] >= 70);
        })
        .fetch(CRATE_C_X, "where the server has the crate, against the body", Double.class,
                sc -> body(sc, CRATE_C).position(new Vector3d()).x)
        .server("stopped against it on the server", sc -> {
            double front = body(sc, CRATE_C).position(new Vector3d()).x + 0.3;
            sc.check("the crate's front at the hidden body's face", Math.abs(front - 4.4) < 0.05, "4.4 ± 0.05", front);
        })
        .client("B", "B's replica of it where the server has it", b -> b
                .step("beside the server's", ctx -> sameAsServer(ctx, CRATE_C, ctx.get(CRATE_C_X))))

        .server("A sent far off from the deck as it sets off again, onto a synced deck its client has not got", sc -> {
            body(sc, DECK).setLinearVelocity(4, 0, 0);
            make(sc, FAR_DECK, BodyType.STATIC, FAR_X, GROUND + 10, 0.5, 2, 0.25f, 2, 1); // its top at 10.25 up
            sc.player("A").teleportTo(sc.server().overworld(), FAR_X, GROUND + 10.26, 0.5, 0, 0);
            sc.check("the server let A off the old deck at once", EntityBases.baseOf(sc.player("A")) == null, null,
                    EntityBases.baseOf(sc.player("A")));
        })
        .client("A", "A lands on the far deck, not through it", b -> b
                .waitUntil("standing on it", ctx -> standsOn(FAR_DECK))
                .step("on its top", ctx -> {
                    double above = MpClient.self().getY() - (GROUND + 10.25);
                    ctx.check("A's feet on the far deck's top", above > 0 && above < 0.02, "(0, 0.02)", above);
                }))
        .serverWaitUntil("the server has A on the far deck", sc -> EntityBases.baseOf(sc.player("A")) == named(sc, FAR_DECK))
        .server("where it was put, not dragged along by the old deck", sc -> {
            double off = Math.abs(sc.player("A").getX() - FAR_X);
            sc.check("A on the server where it was sent", off < 0.05, "< 0.05", off);
        })

        .teardownServer("clean up", sc -> {
            for (String name : new String[]{DECK, WALL, CRATE, CRATE_A, CRATE_B, CRATE_C, HIDDEN, FAR_DECK}) {
                Body body = named(sc, name);
                if (body != null) {
                    body.destroy();
                }
            }
            Entity stand = riderOrNull(sc);
            if (stand != null) {
                stand.discard();
            }
            sc.player("A").setGameMode(GameType.CREATIVE);
        });
    }
}
