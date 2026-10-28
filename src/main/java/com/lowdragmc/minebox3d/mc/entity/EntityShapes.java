package com.lowdragmc.minebox3d.mc.entity;

import com.lowdragmc.minebox3d.physics.Geometry;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * <b>What shape an entity has in Box3D</b>, in the entity's own frame: origin at its position (the
 * feet), axes the world's — or turned by {@link Provider#yawFollows()}.
 *
 * <p>Providers are asked newest first; the first that answers wins. With none answering, an entity is
 * its collision box. A mod with its own idea of an entity's volume (EntityStudio's per-bone hit
 * volumes, a capsule for players) registers one here; MineBox3D never needs to know the entity.
 */
public final class EntityShapes {

    /** One mod's say on some entities. */
    public interface Provider {
        /** The shapes, or {@code null} to leave the entity to the next provider. An empty list = no proxy. */
        @Nullable
        List<Geometry> shapes(Entity entity);

        /** Whether the shapes turn with the entity's yaw. A box does not; anything asymmetric should. */
        default boolean yawFollows() {
            return false;
        }
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final List<Predicate<Entity>> EXCLUSIONS = new CopyOnWriteArrayList<>();

    private EntityShapes() {
    }

    public static void register(Provider provider) {
        PROVIDERS.addFirst(provider);
    }

    /** Entities matching this never get a proxy (spectators never do). */
    public static void exclude(Predicate<Entity> exclusion) {
        EXCLUSIONS.add(exclusion);
    }

    public static boolean isExcluded(Entity entity) {
        if (entity.isSpectator() || entity.isRemoved()) {
            return true;
        }
        for (Predicate<Entity> exclusion : EXCLUSIONS) {
            if (exclusion.test(entity)) {
                return true;
            }
        }
        return false;
    }

    /** Who answered for an entity ({@code null} = the default box) and what they said. */
    record Answer(@Nullable Provider provider, List<Geometry> shapes) {
    }

    static Answer shapesOf(Entity entity) {
        for (Provider provider : PROVIDERS) {
            List<Geometry> shapes = provider.shapes(entity);
            if (shapes != null) {
                return new Answer(provider, shapes);
            }
        }
        return new Answer(null, collisionBox(entity));
    }

    /** The default: the entity's collision box, as a box standing on the entity's position. */
    static List<Geometry> collisionBox(Entity entity) {
        float hw = entity.getBbWidth() / 2f, h = entity.getBbHeight();
        if (hw <= 0 || h <= 0) {
            return List.of();
        }
        return List.of(Geometry.Box.of(hw, h / 2f, hw, 0, h / 2f, 0));
    }
}
