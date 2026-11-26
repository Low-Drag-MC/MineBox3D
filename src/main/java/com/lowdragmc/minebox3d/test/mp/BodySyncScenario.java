package com.lowdragmc.minebox3d.test.mp;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegister;
import com.lowdragmc.lowdraglib2.uitest.ServerContext;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.mp.MPScenario;
import com.lowdragmc.lowdraglib2.uitest.mp.MPScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.mp.MPScenarioOptions;
import com.lowdragmc.minebox3d.mc.CollisionCategories;
import com.lowdragmc.minebox3d.mc.LevelBodies;
import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.mc.sync.SyncedBodies;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Filter;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>Bodies copied to the clients</b> (design doc {@code 04-entities-and-bodies.md} §10, batch 4b), over a real
 * dedicated server and two real clients: a synced box shows up on both and follows the server's — falling, resting,
 * shoved — while one that is not synced never shows up; a box that never meets one player never meets it on either
 * client; a box in a collision category is in the client's bit for it, which is not the server's; the box is sent
 * again with its new shape; a client whose player walks away loses it while the other keeps
 * it; a body seen before dying is let go after respawning in the same dimension; coming back brings the box again; a
 * body made while the tick is frozen is still sent; destroying them takes them from everyone; and each coming and going
 * was one event.
 *
 * <p>{@code ./gradlew runMpTest -PldMpTest=group:minebox3d -PldTestHeadless}
 */
@LDLRegister(
        name = "mb3_mp_body_sync",
        group = "minebox3d",
        registry = MPScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class BodySyncScenario implements MPScenario {

    private static final String SYNCED = "mb3_mp_box";
    private static final String UNSYNCED = "mb3_mp_not_synced";
    private static final String FAR = "mb3_mp_far";
    private static final String FROZEN = "mb3_mp_frozen";
    private static final String TAGGED = "mb3_mp_tagged";
    private static final String CATEGORISED = "mb3_mp_categorised";
    /** registered by the clients only, before {@link #SHARED}: their bit for that is not the server's */
    private static final ResourceLocation CLIENT_ONLY = ResourceLocation.fromNamespaceAndPath("minebox3d", "mp_client");

    private static final ResourceLocation SHARED = ResourceLocation.fromNamespaceAndPath("minebox3d", "mp_shared");
    /** past any view distance from the box */
    private static final double FAR_X = 1000;
    /** the flat world's surface */
    private static final double GROUND = -60;

    @Override
    public void configure(MPScenarioOptions options) {
        options.clients("A", "B").tags("mp", "sync");
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

    private static Body box(ServerContext sc) {
        Body body = named(sc, SYNCED);
        if (body == null) {
            throw new IllegalStateException("the synced box is gone");
        }
        return body;
    }

    private static Body make(ServerContext sc, String name, double x, double z) {
        Body body = physics(sc)
                .createBody(new BodyDef()
                        .type(BodyType.DYNAMIC)
                        .position(x, GROUND + 5, z)
                        .name(name)
                        .userData(42));
        body.createShape(new ShapeDef().density(500), Geometry.Box.of(0.5f, 0.5f, 0.5f));
        return body;
    }

    /** How far this client's replica is from where the server's body was fetched. */
    private static double offBy(TestContext ctx) {
        Body replica = MpClient.replica(SYNCED);
        if (replica == null) {
            return Double.POSITIVE_INFINITY;
        }
        Vector3d p = replica.position(new Vector3d());
        double x = ctx.get("server.x"), y = ctx.get("server.y"), z = ctx.get("server.z");
        return p.distance(x, y, z);
    }

    private static void checkEvents(TestContext ctx, String name, int added, int removed) {
        int[] events = MpClient.events(name);
        ctx.check(name + ": Added", events[0] == added, added, events[0]);
        ctx.check(name + ": Removed", events[1] == removed, removed, events[1]);
        ctx.check(name + ": Removed after the replica was gone", events[2] == 0, 0, events[2]);
    }

    @Override
    public void define(MPScenarioBuilder s) {
        s.allClients("count the replicas' events from here", b -> b.step("listen", ctx -> MpClient.countEvents()))
                .server("both players near the drop; a synced box and one that is not", sc -> {
                    for (var player : sc.players()) {
                        player.teleportTo(
                                sc.server().overworld(), player == sc.player("A") ? 0.5 : 6.5, GROUND, 0.5, 0, 0);
                    }
                    LevelBodies.setSynced(make(sc, SYNCED, 3.5, 6.5), true);
                    make(sc, UNSYNCED, 9.5, 6.5);
                })
                .allClients("the synced box arrives, as it was made", b -> b.waitUntil(
                                "a replica of the synced box", ctx -> MpClient.replica(SYNCED) != null)
                        .step("what it carries", ctx -> {
                            Body replica = MpClient.replica(SYNCED);
                            ctx.check(
                                    "one replica",
                                    MpClient.replicas(SYNCED).size() == 1,
                                    1,
                                    MpClient.replicas(SYNCED).size());
                            ctx.check(
                                    "the user data the server made it with",
                                    replica.userData() == 42,
                                    42,
                                    replica.userData());
                            ctx.check(
                                    "a kinematic copy",
                                    replica.type() == BodyType.KINEMATIC,
                                    BodyType.KINEMATIC,
                                    replica.type());
                            ctx.check(
                                    "its box",
                                    replica.shapes().size() == 1
                                            && replica.shapes()
                                                    .getFirst()
                                                    .geometry()
                                                    .equals(Geometry.Box.of(0.5f, 0.5f, 0.5f)),
                                    Geometry.Box.of(0.5f, 0.5f, 0.5f),
                                    replica.shapes());
                            ctx.check(
                                    "a network number",
                                    SyncedBodies.networkId(replica) > 0,
                                    "> 0",
                                    SyncedBodies.networkId(replica));
                            ctx.check(
                                    "the body nobody asked to sync never shows",
                                    MpClient.replicas(UNSYNCED).isEmpty(),
                                    0,
                                    MpClient.replicas(UNSYNCED).size());
                        }))
                .server("a synced box that never meets A", sc -> {
                    Body tagged = make(sc, TAGGED, 12.5, 6.5);
                    LevelBodies.setSynced(tagged, true);
                    LevelBodies.setIgnoredEntity(tagged, sc.player("A"));
                })
                .allClients("its replica never meets A either, as each client sees A", b -> b.waitUntil(
                                "a replica of it", ctx -> MpClient.replica(TAGGED) != null)
                        .step("what it ignores", ctx -> {
                            Body replica = MpClient.replica(TAGGED);
                            ctx.check(
                                    "A",
                                    LevelBodies.ignores(replica, MpClient.player("A")),
                                    true,
                                    replica.ignoredTag());
                            ctx.check(
                                    "not B",
                                    !LevelBodies.ignores(replica, MpClient.player("B")),
                                    false,
                                    replica.ignoredTag());
                        }))
                .server("now it never meets B", sc -> LevelBodies.setIgnoredEntity(named(sc, TAGGED), sc.player("B")))
                .allClients(
                        "it is sent again, never meeting B",
                        b -> b.waitUntil("its replica ignores B, not A", ctx -> {
                            Body replica = MpClient.replica(TAGGED);
                            return replica != null
                                    && LevelBodies.ignores(replica, MpClient.player("B"))
                                    && !LevelBodies.ignores(replica, MpClient.player("A"));
                        }))
                .server("the box that ignores goes", sc -> named(sc, TAGGED).destroy())
                .allClients(
                        "and its replica",
                        b -> b.waitUntil("no replica of it", ctx -> MpClient.replicas(TAGGED)
                                .isEmpty()))
                .allClients(
                        "a collision category of the client's own, asked for before the one it shares with the server",
                        b -> b.step("register", ctx -> {
                            CollisionCategories.register(CLIENT_ONLY);
                            CollisionCategories.register(SHARED);
                        }))
                .server("a synced box in the shared category, meeting only it and the blocks", sc -> {
                    long shared = CollisionCategories.register(SHARED);
                    Body body = physics(sc)
                            .createBody(new BodyDef()
                                    .type(BodyType.DYNAMIC)
                                    .position(15.5, GROUND + 5, 6.5)
                                    .name(CATEGORISED));
                    ShapeDef def = new ShapeDef().density(500);
                    def.filter.categoryBits = shared;
                    def.filter.maskBits = shared | CollisionCategories.TERRAIN;
                    body.createShape(def, Geometry.Box.of(0.5f, 0.5f, 0.5f));
                    LevelBodies.setSynced(body, true);
                })
                .fetch(
                        "server.shared",
                        "the server's bit for the shared category",
                        Long.class,
                        sc -> CollisionCategories.register(SHARED))
                .allClients("its replica is in the client's bit for that category, not the server's", b -> b.waitUntil(
                                "a replica of it", ctx -> MpClient.replica(CATEGORISED) != null)
                        .step("its filter", ctx -> {
                            long shared = CollisionCategories.register(SHARED);
                            long own = CollisionCategories.register(CLIENT_ONLY);
                            long servers = ctx.get("server.shared");
                            ctx.check(
                                    "the two sides' bits for it differ",
                                    servers != shared,
                                    "not " + Long.toHexString(servers),
                                    Long.toHexString(shared));
                            Filter filter = MpClient.replica(CATEGORISED)
                                    .shapes()
                                    .getFirst()
                                    .filter(new Filter());
                            ctx.check(
                                    "in the shared category",
                                    filter.categoryBits == shared,
                                    Long.toHexString(shared),
                                    Long.toHexString(filter.categoryBits));
                            ctx.check(
                                    "meeting it and the blocks",
                                    filter.maskBits == (shared | CollisionCategories.TERRAIN),
                                    Long.toHexString(shared | CollisionCategories.TERRAIN),
                                    Long.toHexString(filter.maskBits));
                            ctx.check(
                                    "not in the client's own",
                                    (filter.categoryBits & own) == 0,
                                    0,
                                    Long.toHexString(filter.categoryBits & own));
                        }))
                .server("the categorised box goes", sc -> named(sc, CATEGORISED).destroy())
                .allClients(
                        "and its replica",
                        b -> b.waitUntil("no replica of it", ctx -> MpClient.replicas(CATEGORISED)
                                .isEmpty()))
                .serverWaitUntil(
                        "the box lands",
                        sc -> box(sc).position(new Vector3d()).y < GROUND + 0.6
                                && box(sc).linearVelocity(new Vector3f()).length() < 0.05f)
                .fetch("server.x", "where the box rests", Double.class, sc -> box(sc).position(new Vector3d()).x)
                .fetch("server.y", "where the box rests", Double.class, sc -> box(sc).position(new Vector3d()).y)
                .fetch("server.z", "where the box rests", Double.class, sc -> box(sc).position(new Vector3d()).z)
                .allClients("the replica followed it down and rests there too", b -> b.waitUntil(
                                "within a hundredth of a block", ctx -> offBy(ctx) < 0.01)
                        .step(
                                "it fell",
                                ctx -> ctx.check(
                                        "below where it was made",
                                        MpClient.replica(SYNCED).position(new Vector3d()).y < GROUND + 1,
                                        "< " + (GROUND + 1),
                                        MpClient.replica(SYNCED).position(new Vector3d()).y)))
                .server("shove it along x", sc -> box(sc).setLinearVelocity(6, 0, 0))
                .serverWaitUntil(
                        "it slid and stopped",
                        sc -> box(sc).linearVelocity(new Vector3f()).length() < 0.05f
                                && box(sc).position(new Vector3d()).x > 4.0)
                .fetch("server.x", "where it stopped", Double.class, sc -> box(sc).position(new Vector3d()).x)
                .fetch("server.y", "where it stopped", Double.class, sc -> box(sc).position(new Vector3d()).y)
                .fetch("server.z", "where it stopped", Double.class, sc -> box(sc).position(new Vector3d()).z)
                .allClients("the replica slid with it", b -> b.waitUntil(
                                "within a hundredth of a block", ctx -> offBy(ctx) < 0.01)
                        // the replica's own velocity is its being driven; what the server's body does is said apart
                        .waitUntil(
                                "the server's velocity, as told, is the stop",
                                ctx -> MpClient.replica(SYNCED) != null
                                        && SyncedBodies.serverVelocity(MpClient.replica(SYNCED), new Vector3f())
                                                        .length()
                                                < 0.05f))
                .server(
                        "make it a ball",
                        sc -> box(sc).shapes().getFirst().setGeometry(new Geometry.Sphere(0, 0, 0, 0.5f), true))
                .allClients(
                        "the replica is sent again as a ball",
                        b -> b.waitUntil("its shape is a sphere", ctx -> {
                            Body replica = MpClient.replica(SYNCED);
                            return replica != null
                                    && replica.shapes().size() == 1
                                    && replica.shapes().getFirst().geometry() instanceof Geometry.Sphere;
                        }))
                .server("A walks far away, where a second synced body waits", sc -> {
                    sc.player("A").teleportTo(sc.server().overworld(), FAR_X + 0.5, GROUND, 0.5, 0, 0);
                    // kinematic: it holds where it is made, whether or not its chunk has loaded yet
                    Body far = physics(sc)
                            .createBody(new BodyDef()
                                    .type(BodyType.KINEMATIC)
                                    .position(FAR_X + 3.5, GROUND + 2, 0.5)
                                    .name(FAR));
                    far.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
                    LevelBodies.setSynced(far, true);
                })
                .client("A", "A's client lets the box go and gets the far one", b -> b.waitUntil(
                                "no box", ctx -> MpClient.replicas(SYNCED).isEmpty())
                        .waitUntil("the far one", ctx -> MpClient.replica(FAR) != null))
                .client("B", "B's client keeps the box and never sees the far one", b -> b.check(
                                "still there", ctx -> MpClient.replica(SYNCED) != null)
                        .check("not the far one", ctx -> MpClient.replicas(FAR).isEmpty()))

                // the server makes a new player and the client keeps its level, and with it what it was sent: the far
                // one must go as if A had walked away, not stay behind for good
                .server("A dies and respawns, in the same dimension", sc -> {
                    // as the respawn button does (ServerGamePacketListenerImpl.handleClientCommand): the connection is
                    // handed the new player by whoever asked for it, not by PlayerList.respawn
                    ServerPlayer dead = sc.player("A");
                    dead.connection.player =
                            sc.server().getPlayerList().respawn(dead, false, Entity.RemovalReason.KILLED);
                })
                .client(
                        "A",
                        "A's client lets the far one go",
                        b -> b.waitUntil(
                                "no far one", ctx -> MpClient.replicas(FAR).isEmpty()))
                .server("A comes back to the box", sc -> sc.player("A")
                        .teleportTo(sc.server().overworld(), 0.5, GROUND, 0.5, 0, 0))
                .client(
                        "A",
                        "A's client is sent the box again, as it is now",
                        b -> b.waitUntil("the box, a ball", ctx -> {
                            Body replica = MpClient.replica(SYNCED);
                            return replica != null
                                    && replica.shapes().size() == 1
                                    && replica.shapes().getFirst().geometry() instanceof Geometry.Sphere;
                        }))
                .server("the tick frozen, as /tick freeze does; a new synced body by B", sc -> {
                    sc.server().tickRateManager().setFrozen(true);
                    Body frozen = physics(sc)
                            .createBody(new BodyDef()
                                    .type(BodyType.KINEMATIC)
                                    .position(6.5, GROUND + 3, 4.5)
                                    .name(FROZEN));
                    frozen.createShape(new ShapeDef(), Geometry.Box.of(0.5f, 0.5f, 0.5f));
                    LevelBodies.setSynced(frozen, true);
                })
                .client(
                        "B",
                        "B is sent it, frozen as the tick is",
                        b -> b.waitUntil("its replica", ctx -> MpClient.replica(FROZEN) != null))
                .server("the tick runs again", sc -> {
                    sc.server().tickRateManager().setFrozen(false);
                    Body frozen = named(sc, FROZEN);
                    if (frozen != null) {
                        frozen.destroy();
                    }
                })
                .server("both are destroyed", sc -> {
                    box(sc).destroy();
                    Body far = named(sc, FAR);
                    if (far != null) {
                        far.destroy();
                    }
                })
                .allClients(
                        "gone from every client",
                        b -> b.waitUntil(
                                "no replica",
                                ctx -> MpClient.replicas(SYNCED).isEmpty()
                                        && MpClient.replicas(FAR).isEmpty()))
                .client(
                        "A",
                        "A was told of each coming and going, while the replica was still there",
                        b -> b
                                // the box: came, sent again as a ball, left behind, sent again on coming
                                // back, destroyed
                                .step("its events", ctx -> {
                            checkEvents(ctx, SYNCED, 3, 3);
                            checkEvents(ctx, FAR, 1, 1);
                        }))
                .client(
                        "B",
                        "B was told of each coming and going, while the replica was still there",
                        b -> b.step("its events", ctx -> {
                            checkEvents(ctx, SYNCED, 2, 2);
                            checkEvents(ctx, FAR, 0, 0);
                        }))
                .teardownServer("clean up", sc -> {
                    sc.server().tickRateManager().setFrozen(false);
                    for (String name : new String[] {SYNCED, UNSYNCED, FAR, FROZEN}) {
                        Body body = named(sc, name);
                        if (body != null) {
                            body.destroy();
                        }
                    }
                });
    }
}
