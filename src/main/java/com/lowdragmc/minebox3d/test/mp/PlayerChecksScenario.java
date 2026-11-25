package com.lowdragmc.minebox3d.test.mp;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegister;
import com.lowdragmc.lowdraglib2.uitest.ServerContext;
import com.lowdragmc.lowdraglib2.uitest.mp.MPScenario;
import com.lowdragmc.lowdraglib2.uitest.mp.MPScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.mp.MPScenarioOptions;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.entity.EntityBases;
import com.lowdragmc.minebox3d.mc.sync.PushPayload;
import com.lowdragmc.minebox3d.mc.sync.StandingPayload;
import com.lowdragmc.minebox3d.mc.sync.SyncedBodies;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>Players on bodies, the harder cases</b> (design doc {@code 04-entities-and-bodies.md} §11, batch 4c-3, review),
 * over a real dedicated server and two real clients:
 * <ul>
 * <li>A sneaks on a synced deck: it moves (Minecraft's edge check sees the deck), and stops at the deck's edge;</li>
 * <li>A's client tells the server lies — a body it does not have, a point far from where it is, a push far harder than
 * a player gives: the server does not put A there, and the crate is pushed no harder than a player could;</li>
 * <li>A is sent onto a wide deck far off whose middle is in another chunk: held till the deck arrives, it lands on
 * it;</li>
 * <li>A, in a boat on a deck, rides the moving deck: the boat stays where it was on it, on the server too.</li>
 * </ul>
 *
 * <p>{@code ./gradlew runMpTest -PldMpTest=group:minebox3d -PldTestHeadless}
 */
@LDLRegister(
        name = "mb3_mp_player_checks",
        group = "minebox3d",
        registry = MPScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PlayerChecksScenario implements MPScenario {

    private static final String DECK = "mb3_mp_check_deck";
    private static final String CRATE = "mb3_mp_check_crate";
    private static final String WIDE = "mb3_mp_wide_deck";
    private static final String RAFT = "mb3_mp_raft";
    private static final String BOAT = "mb3_mp_boat";
    private static final double GROUND = -60;

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

    private static Body make(
            ServerContext sc,
            String name,
            BodyType type,
            double x,
            double y,
            double z,
            float hx,
            float hy,
            float hz,
            float density) {
        Body body = physics(sc)
                .createBody(new BodyDef().type(type).position(x, y, z).name(name));
        body.createShape(new ShapeDef().density(density), Geometry.Box.of(hx, hy, hz));
        LevelBodies.setSynced(body, true);
        return body;
    }

    private static boolean standsOn(Entity entity, String name) {
        Body replica = MpClient.replica(name);
        return entity != null && replica != null && entity.onGround() && EntityBases.baseOf(entity) == replica;
    }

    private static Vec3 offset(Entity entity, Body body) {
        Vector3d p = body.position(new Vector3d());
        return new Vec3(entity.getX() - p.x, entity.getY() - p.y, entity.getZ() - p.z);
    }

    @Nullable
    private static Entity boat(ServerContext sc) {
        for (Entity entity : sc.server().overworld().getAllEntities()) {
            if (entity.hasCustomName() && BOAT.equals(entity.getCustomName().getString())) {
                return entity;
            }
        }
        return null;
    }

    @Override
    public void define(MPScenarioBuilder s) {
        s.server("A, playing survival, on a synced deck; a light synced crate beside it", sc -> {
                    sc.player("A").setGameMode(GameType.SURVIVAL);
                    make(
                            sc,
                            DECK,
                            BodyType.STATIC,
                            2.5,
                            GROUND + 2.25,
                            0.5,
                            2,
                            0.25f,
                            2,
                            1); // x 0.5..4.5, its top at 2.5 up
                    // 5 kg, below the deck's +x edge: within a player's reach of where A ends up
                    make(sc, CRATE, BodyType.DYNAMIC, 5.5, GROUND + 0.3, 0.5, 0.3f, 0.3f, 0.3f, 23);
                    sc.player("A").teleportTo(sc.server().overworld(), 2.5, GROUND + 3, 0.5, 0, 0);
                    sc.player("B").teleportTo(sc.server().overworld(), 2.5, GROUND, 8.5, 0, 0);
                })
                .client("A", "A sneaks towards the deck's edge: it moves, and stops at the edge", b -> b.waitUntil(
                                "standing on the deck", ctx -> standsOn(MpClient.self(), DECK))
                        .step("sneak, and walk on", ctx -> {
                            ctx.put("sneak.from", MpClient.self().getX());
                            MpClient.sneak(true);
                        })
                        .waitUntil("sneaking", ctx -> MpClient.self().isShiftKeyDown())
                        .repeat(
                                30,
                                r -> r.step("a step", ctx -> MpClient.self()
                                        .setDeltaMovement(0.1, MpClient.self().getDeltaMovement().y, 0)))
                        .step("where it got to", ctx -> {
                            // stopped first: let go of sneaking while still walking, it would step off the edge
                            // onto the crate
                            MpClient.self().setDeltaMovement(0, MpClient.self().getDeltaMovement().y, 0);
                            MpClient.sneak(false);
                            double went = MpClient.self().getX() - (double) ctx.get("sneak.from");
                            ctx.check("A moved, sneaking on the deck", went > 0.5, "> 0.5", went);
                            ctx.check(
                                    "and is still on it, at its edge",
                                    standsOn(MpClient.self(), DECK)
                                            && MpClient.self().getX() < 4.5 + 0.3,
                                    "on the deck",
                                    MpClient.self().position());
                        }))
                .server("where A is on the server, before the lies", sc -> {
                    sc.put("a.x", sc.player("A").getX());
                    sc.put("a.z", sc.player("A").getZ());
                    Vector3d crate = body(sc, CRATE).position(new Vector3d());
                    sc.put("crate.at", new Vec3(crate.x - 0.3, crate.y, crate.z));
                    sc.put("crate.fastest", 0f);
                })
                .fetch("deck.id", "the deck's network number", Double.class, sc ->
                        (double) SyncedBodies.networkId(body(sc, DECK)))
                .fetch("crate.id", "the crate's network number", Double.class, sc ->
                        (double) SyncedBodies.networkId(body(sc, CRATE)))
                .client(
                        "A",
                        "A's client tells lies, then moves a little",
                        b -> b.step("lie", ctx -> {
                            int deck = (int) (double) ctx.get("deck.id"), crate = (int) (double) ctx.get("crate.id");
                            // a body it does not have; a point of its deck six blocks from it; numbers that are
                            // not numbers
                            PacketDistributor.sendToServer(new StandingPayload(99_999, 0, 0.3f, 0, false));
                            PacketDistributor.sendToServer(new StandingPayload(deck, 1.9f, 0.3f, -6f, false));
                            PacketDistributor.sendToServer(new StandingPayload(deck, Float.NaN, 0, 0, false));
                            // a push a thousand times harder than a player gives, many times
                            for (int i = 0; i < 20; i++) {
                                PacketDistributor.sendToServer(new PushPayload(crate, 1.0e6f, 0, 0, -0.3f, 0, 0));
                            }
                            MpClient.self().setDeltaMovement(-0.05, 0, 0);
                        }))
                .serverWaitUntil("ten ticks, the crate's top speed taken", sc -> {
                    // along the pushes (+x): it bounces as it goes, which is not the push
                    float fastest = Math.max(
                            (float) sc.get("crate.fastest"),
                            Math.abs(body(sc, CRATE).linearVelocity(new Vector3f()).x));
                    sc.put("crate.fastest", fastest);
                    int ticks = sc.get("lies.ticks") == null ? 1 : (int) sc.get("lies.ticks") + 1;
                    sc.put("lies.ticks", ticks);
                    return ticks >= 10;
                })
                .server("the server did not put A where it lied, nor shoot the crate", sc -> {
                    double moved = Math.hypot(
                            sc.player("A").getX() - (double) sc.get("a.x"),
                            sc.player("A").getZ() - (double) sc.get("a.z"));
                    // the lie is six blocks off
                    sc.check("A where its own move put it, not six blocks off", moved < 3, "< 3", moved);
                    sc.check(
                            "the lies' pushes were within reach (else this proves nothing)",
                            sc.player("A").getBoundingBox().inflate(3).contains(sc.<Vec3>get("crate.at")),
                            "within 3",
                            sc.<Vec3>get("crate.at"));
                    float speed = sc.get("crate.fastest");
                    sc.check(
                            "the crate pushed, but no harder than a player can push",
                            speed > 5 && speed < 21,
                            "(5, 21) m/s",
                            speed);
                })
                .server("A sent onto a wide synced deck far off, its middle in another chunk", sc -> {
                    // x 980..1020: its middle at 1000 (chunk 62), A at 1016 (chunk 63)
                    make(sc, WIDE, BodyType.STATIC, 1000.5, GROUND + 10, 0.5, 20, 0.25f, 4, 1); // its top at 10.25 up
                    sc.player("A").teleportTo(sc.server().overworld(), 1016.5, GROUND + 10.26, 0.5, 0, 0);
                })
                .client("A", "A lands on the wide deck, not through it", b -> b.waitUntil(
                                "standing on it", ctx -> standsOn(MpClient.self(), WIDE))
                        .step("on its top", ctx -> {
                            double above = MpClient.self().getY() - (GROUND + 10.25);
                            ctx.check("A's feet on the wide deck's top", above > 0 && above < 0.02, "(0, 0.02)", above);
                        }))
                .server("A in a boat on a synced deck", sc -> {
                    make(sc, RAFT, BodyType.KINEMATIC, 2.5, GROUND + 2.25, 20.5, 3, 0.25f, 2, 1); // its top at 2.5 up
                    Boat boat = EntityType.BOAT.create(sc.server().overworld());
                    boat.moveTo(2.5, GROUND + 2.6, 20.5);
                    boat.setCustomName(net.minecraft.network.chat.Component.literal(BOAT));
                    sc.server().overworld().addFreshEntity(boat);
                    sc.player("A").teleportTo(sc.server().overworld(), 2.5, GROUND + 3, 20.5, 0, 0);
                    sc.player("A").startRiding(boat, true);
                })
                .client(
                        "A",
                        "A's client has the boat standing on the deck",
                        b -> b.waitUntil(
                                "the boat on the raft",
                                ctx -> MpClient.self().getVehicle() != null
                                        && standsOn(MpClient.self().getVehicle(), RAFT)))
                .serverWaitUntil(
                        "the server has the boat standing on its raft, where A's client says",
                        sc -> boat(sc) != null && EntityBases.baseOf(boat(sc)) == named(sc, RAFT))
                .server("the raft sets off along x at 3 m/s", sc -> {
                    Vec3 start = offset(boat(sc), body(sc, RAFT));
                    sc.put("boat.x", start.x);
                    sc.put("boat.z", start.z);
                    body(sc, RAFT).setLinearVelocity(3, 0, 0);
                })
                .serverSettle(40)
                .server("two seconds on: the boat where it was on the raft, on the server", sc -> {
                    body(sc, RAFT).setLinearVelocity(0, 0, 0);
                    Vec3 now = offset(boat(sc), body(sc, RAFT));
                    double off = Math.hypot(now.x - (double) sc.get("boat.x"), now.z - (double) sc.get("boat.z"));
                    sc.check(
                            "the boat rode the raft on the server (not set back, not left behind)",
                            off < 0.2,
                            "< 0.2",
                            off);
                })
                .teardownServer("clean up", sc -> {
                    sc.player("A").stopRiding();
                    Entity boat = boat(sc);
                    if (boat != null) {
                        boat.discard();
                    }
                    for (String name : new String[] {DECK, CRATE, WIDE, RAFT}) {
                        Body body = named(sc, name);
                        if (body != null) {
                            body.destroy();
                        }
                    }
                    sc.player("A").setGameMode(GameType.CREATIVE);
                })
                .teardownClient("A", "let the sneak key go", b -> b.step("release", ctx -> MpClient.sneak(false)));
    }
}
