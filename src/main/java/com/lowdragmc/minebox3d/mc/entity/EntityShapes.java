package com.lowdragmc.minebox3d.mc.entity;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.Geometry;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * <b>What shape an entity has in Box3D</b>, in the entity's own frame: origin at its position (the
 * feet), axes the world's — or turned by {@link Provider#yawFollows()}.
 *
 * <p>Providers are asked newest first; the first that answers wins. With none answering, an entity is
 * its collision box — and a part entity is nothing. A mod with its own idea of an entity's body (a
 * capsule for players) registers one here; MineBox3D never needs to know the entity.
 *
 * <p>⚠️ A part entity has no proxy unless a provider gives it one. NeoForge's {@code getEntities} hands
 * parts out with everything else, and what a part stands for is the owner's business: a dragon's are
 * its body, but EntityStudio's are hit volumes that must never move anything (docs/design/04 §8.2).
 * Vanilla agrees by default — a part is neither pushable nor collidable.
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

        /**
         * The entity's mass in kg, for how far a body pushes it; {@link Float#NaN} to leave it to the next provider,
         * {@link Float#POSITIVE_INFINITY} for an entity no body moves. Asked when its proxy is made, and again when the entity
         * changes size.
         */
        default float mass(Entity entity) {
            return Float.NaN;
        }
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final List<Predicate<Entity>> EXCLUSIONS = new CopyOnWriteArrayList<>();
    /** Bit 62 of an entity's tag: a mod's own body tags leave it clear */
    private static final long ENTITY_TAG = 1L << 62;

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MineBox3D.MOD_ID);
    /** On an entity whose physics collision is off ({@link #setPhysicsCollision}); never saved */
    private static final Supplier<AttachmentType<Boolean>> NO_PHYSICS_COLLISION = ATTACHMENTS.register(
            "no_physics_collision", () -> AttachmentType.builder(() -> Boolean.TRUE).build());

    private EntityShapes() {
    }

    @ApiStatus.Internal
    public static void registerAttachments(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }

    public static void register(Provider provider) {
        PROVIDERS.addFirst(provider);
    }

    /** Entities matching this never get a proxy (spectators never do). */
    public static void exclude(Predicate<Entity> exclusion) {
        EXCLUSIONS.add(exclusion);
    }

    /**
     * What an entity's proxy is tagged ({@link Body#setTag}): a body that ignores it ({@code LevelBodies.setIgnoredEntity})
     * and the entity never meet. The entity's id, which its level's client and server share, with bit 62 set.
     */
    public static long tagOf(Entity entity) {
        return ENTITY_TAG | (entity.getId() & 0xFFFFFFFFL);
    }

    /**
     * Whether the entity meets the level's bodies at all: a proxy near them that pushes them and that they stop against,
     * its own moves stopped, stood on and carried by them. On unless turned off — Unreal's capsule with its physics
     * collision on; off is what a game does to a character whose ragdoll takes over. Read on the level's thread.
     */
    public static boolean hasPhysicsCollision(Entity entity) {
        return !entity.hasData(NO_PHYSICS_COLLISION);
    }

    /**
     * Turns the entity's meeting the level's bodies on or off ({@link #hasPhysicsCollision}), on this side: its proxy goes
     * at the next update. Not saved; the last call wins. Set on the level's thread.
     */
    public static void setPhysicsCollision(Entity entity, boolean on) {
        if (on) {
            entity.removeData(NO_PHYSICS_COLLISION);
        } else {
            entity.setData(NO_PHYSICS_COLLISION, Boolean.TRUE);
        }
    }

    public static boolean isExcluded(Entity entity) {
        if (entity.isSpectator() || entity.isRemoved() || !hasPhysicsCollision(entity)) {
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

    /**
     * An entity's mass, kg: the first provider's answer, else its collision box's volume times {@code density}. Minecraft
     * has no masses; a box is far roomier than the body in it, so the density that gives a player about 70 kg is about a
     * tenth of water's (EntityProxies' default).
     *
     * <p>Unless a provider says otherwise, infinite — never pushed — for what a push would wreck or never move: a painting,
     * an item frame or a leash knot ({@code BlockAttachedEntity.push} breaks it off its block), a no-AI mob (it does not
     * travel, so its motion only piles up), and the bodiless markers ({@code Marker}, {@code Interaction}, displays).
     */
    public static float massOf(Entity entity, float density) {
        for (Provider provider : PROVIDERS) {
            float mass = provider.mass(entity);
            if (!Float.isNaN(mass)) {
                if (!(mass > 0)) {
                    throw new IllegalStateException("a provider gave " + entity + " a mass of " + mass);
                }
                return mass;
            }
        }
        if (entity instanceof BlockAttachedEntity || entity instanceof Mob mob && mob.isNoAi() || entity instanceof Marker
                || entity instanceof Interaction || entity instanceof Display) {
            return Float.POSITIVE_INFINITY;
        }
        float width = entity.getBbWidth(), height = entity.getBbHeight();
        return Math.max(width * width * height * density, 1.0e-3f);
    }

    /** The default: the entity's collision box, as a box standing on the entity's position; none for a part. */
    static List<Geometry> collisionBox(Entity entity) {
        if (entity instanceof PartEntity<?>) {
            return List.of();
        }
        float hw = entity.getBbWidth() / 2f, h = entity.getBbHeight();
        if (hw <= 0 || h <= 0) {
            return List.of();
        }
        return List.of(Geometry.Box.of(hw, h / 2f, hw, 0, h / 2f, 0));
    }
}
