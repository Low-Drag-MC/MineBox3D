package com.lowdragmc.minebox3d.mc.entity;

import com.lowdragmc.minebox3d.mc.CollisionCategories;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * <b>Minecraft entities as kinematic bodies</b> near the bodies that can hit them (design doc §5).
 *
 * <p>Every tick: the entities around each dynamic body (its bounds grown by {@link #reach}) get a
 * proxy if they have none, every proxy is driven to its entity's position with
 * {@code SetTargetTransform} — by velocity, so a box the player walks into is pushed rather than
 * overlapped — and proxies whose entity has gone or left the neighbourhood for {@link #keepTicks}
 * are removed. An entity that moved further than {@link #teleportDistance} in a tick is teleported
 * instead, so a /tp does not fling everything along its path.
 *
 * <p>One way only: entities push bodies, bodies do not push entities.
 */
public final class EntityProxies implements AutoCloseable {

    private final Level level;
    private final PhysicsWorld world;
    private final Map<Entity, Proxy> proxies = new IdentityHashMap<>();
    private Predicate<Body> consumers = body -> body.type() == BodyType.DYNAMIC;

    public float reach = 2.0f;
    public int keepTicks = 20;
    public double teleportDistance = 8.0;

    private long tick;

    public EntityProxies(Level level, PhysicsWorld world) {
        this.level = level;
        this.world = world;
    }

    /** Which bodies entities near which get proxies. By default every dynamic body. */
    public void setConsumers(Predicate<Body> consumers) {
        this.consumers = consumers;
    }

    /**
     * @param timeStep the time the coming steps cover, over which each proxy reaches its entity
     */
    public void update(float timeStep) {
        tick++;
        // the neighbourhoods first: creating a proxy adds a body to the collection being walked
        float[] bounds = new float[6];
        List<AABB> neighbourhoods = new ArrayList<>();
        for (Body body : world.bodies()) {
            if (!consumers.test(body) || body.userObject() instanceof Proxy) {
                continue;
            }
            body.bounds(bounds);
            neighbourhoods.add(new AABB(bounds[0] - reach, bounds[1] - reach, bounds[2] - reach,
                    bounds[3] + reach, bounds[4] + reach, bounds[5] + reach));
        }
        for (AABB around : neighbourhoods) {
            for (Entity entity : level.getEntities((Entity) null, around, e -> !EntityShapes.isExcluded(e))) {
                Proxy proxy = proxies.get(entity);
                if (proxy == null) {
                    proxy = create(entity);
                    if (proxy == null) {
                        continue;
                    }
                    proxies.put(entity, proxy);
                }
                proxy.lastSeen = tick;
            }
        }

        Quaternionf rotation = new Quaternionf();
        for (Iterator<Map.Entry<Entity, Proxy>> it = proxies.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, Proxy> entry = it.next();
            Entity entity = entry.getKey();
            Proxy proxy = entry.getValue();
            if (!proxy.body.isValid()) {
                // someone else destroyed it: made again next time the entity is near a body, not driven (which would throw
                // and stop the level's physics)
                it.remove();
                continue;
            }
            if (entity.isRemoved() || entity.level() != level || proxy.lastSeen < tick - keepTicks
                    || EntityShapes.isExcluded(entity)) {
                proxy.body.destroy();
                it.remove();
                continue;
            }
            if (proxy.width != entity.getBbWidth() || proxy.height != entity.getBbHeight()) {
                // it changed size (crouching, a baby growing up): new shapes
                proxy.body.destroy();
                Proxy rebuilt = create(entity);
                if (rebuilt == null) {
                    it.remove();
                    continue;
                }
                rebuilt.lastSeen = proxy.lastSeen;
                entry.setValue(rebuilt);
                proxy = rebuilt;
            }
            rotationOf(entity, proxy, rotation);
            double x = entity.getX(), y = entity.getY(), z = entity.getZ();
            double dx = x - proxy.lastX, dy = y - proxy.lastY, dz = z - proxy.lastZ;
            if (dx * dx + dy * dy + dz * dz > teleportDistance * teleportDistance) {
                proxy.body.setTransform(x, y, z, rotation);
            } else {
                proxy.body.setTargetTransform(x, y, z, rotation, timeStep);
            }
            proxy.lastX = x;
            proxy.lastY = y;
            proxy.lastZ = z;
        }
    }

    @Nullable
    private Proxy create(Entity entity) {
        EntityShapes.Answer answer = EntityShapes.shapesOf(entity);
        if (answer.shapes().isEmpty()) {
            return null;
        }
        EntityShapes.Provider provider = answer.provider();
        List<Geometry> shapes = answer.shapes();
        Proxy proxy = new Proxy(entity, provider);
        Quaternionf rotation = rotationOf(entity, proxy, new Quaternionf());
        proxy.body = world.createBody(new BodyDef().type(BodyType.KINEMATIC)
                .position(entity.getX(), entity.getY(), entity.getZ()).rotation(rotation).name("entity"));
        ShapeDef def = new ShapeDef();
        def.filter.categoryBits = CollisionCategories.ENTITY;
        // a proxy has nothing to say to terrain or to another proxy (kinematic bodies would not
        // collide with them anyway; the mask keeps them out of each other's queries too)
        def.filter.maskBits = ~(CollisionCategories.TERRAIN | CollisionCategories.ENTITY);
        for (Geometry geometry : shapes) {
            proxy.body.createShape(def, geometry);
        }
        proxy.body.setUserObject(proxy);
        return proxy;
    }

    private static Quaternionf rotationOf(Entity entity, Proxy proxy, Quaternionf out) {
        if (proxy.provider != null && proxy.provider.yawFollows()) {
            // Minecraft's yaw turns clockwise seen from above
            return out.rotationY((float) Math.toRadians(-entity.getYRot()));
        }
        return out.identity();
    }

    /** The proxy of an entity, or {@code null} if it has none right now. */
    @Nullable
    public Body proxyOf(Entity entity) {
        Proxy proxy = proxies.get(entity);
        return proxy == null ? null : proxy.body;
    }

    /** The entity a body stands in for, if it is a proxy. */
    @Nullable
    public static Entity entityOf(Body body) {
        return body.userObject() instanceof Proxy proxy ? proxy.entity : null;
    }

    public int proxyCount() {
        return proxies.size();
    }

    @Override
    public void close() {
        for (Proxy proxy : proxies.values()) {
            if (proxy.body.isValid()) {
                proxy.body.destroy();
            }
        }
        proxies.clear();
    }

    static final class Proxy {
        final Entity entity;
        @Nullable
        final EntityShapes.Provider provider;
        final float width, height;
        Body body;
        long lastSeen;
        double lastX, lastY, lastZ;

        Proxy(Entity entity, @Nullable EntityShapes.Provider provider) {
            this.entity = entity;
            this.provider = provider;
            this.width = entity.getBbWidth();
            this.height = entity.getBbHeight();
            this.lastX = entity.getX();
            this.lastY = entity.getY();
            this.lastZ = entity.getZ();
        }
    }
}
