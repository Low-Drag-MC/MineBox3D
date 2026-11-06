package com.lowdragmc.minebox3d.mc;

import com.lowdragmc.minebox3d.mc.entity.EntityShapes;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyType;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * <b>What one body wants from a level's physics</b>: terrain streamed in around it, entities standing in it as proxies
 * that push it, a copy on the clients of the players near it, an entity it never meets. Unless set, a dynamic body wants
 * terrain and proxies and other bodies neither, and no body is copied to clients; a mod sets it per body, so mods never
 * overrule each other (design doc {@code 03-threading-and-api.md} §6.5). Kept on the body ({@link Body#attachment},
 * {@link Body#ignoredTag}); read on the thread using the world.
 */
public final class LevelBodies {

    private LevelBodies() {
    }

    /** null = not set: follows the body's type */
    private record Wants(@Nullable Boolean terrain, @Nullable Boolean pushedByEntities, boolean synced) {
    }

    private static final Wants UNSET = new Wants(null, null, false);

    private static Wants wants(Body body) {
        Wants wants = body.attachment(Wants.class);
        return wants != null ? wants : UNSET;
    }

    /** Whether the terrain streamer loads the blocks around this body (and parks it over unloaded chunks). */
    public static boolean needsTerrain(Body body) {
        Boolean set = wants(body).terrain;
        return set != null ? set : body.type() == BodyType.DYNAMIC;
    }

    /** {@code null} goes back to the default. */
    public static void setNeedsTerrain(Body body, @Nullable Boolean needs) {
        Wants wants = wants(body);
        body.attach(Wants.class, new Wants(needs, wants.pushedByEntities, wants.synced));
    }

    /** Whether entities near this body get proxies that push it. */
    public static boolean pushedByEntities(Body body) {
        Boolean set = wants(body).pushedByEntities;
        return set != null ? set : body.type() == BodyType.DYNAMIC;
    }

    /** {@code null} goes back to the default. */
    public static void setPushedByEntities(Body body, @Nullable Boolean pushed) {
        Wants wants = wants(body);
        body.attach(Wants.class, new Wants(wants.terrain, pushed, wants.synced));
    }

    /**
     * Whether the clients of the players near this body get a copy of it that follows it (design doc
     * {@code 04-entities-and-bodies.md} §10). Only for a body of a server level's own physics; off by default.
     */
    public static boolean synced(Body body) {
        return wants(body).synced;
    }

    public static void setSynced(Body body, boolean synced) {
        Wants wants = wants(body);
        body.attach(Wants.class, new Wants(wants.terrain, wants.pushedByEntities, synced));
    }

    /**
     * The entity this body never meets, {@code null} for none (design doc {@code 04-entities-and-bodies.md} §17): the
     * entity's proxy does not touch it, the entity's own moves pass through it, and it is not the entity's base — a ragdoll
     * and the entity it is the body of, whose box would push it out of itself. Every other entity meets it as its filter
     * says. Its copies on clients ({@link #setSynced}) never meet the entity either. One entity per body.
     */
    public static void setIgnoredEntity(Body body, @Nullable Entity entity) {
        body.setIgnoredTag(entity == null ? 0 : EntityShapes.tagOf(entity));
    }

    /** Whether this body never meets {@code entity} ({@link #setIgnoredEntity}). */
    public static boolean ignores(Body body, Entity entity) {
        long ignored = body.ignoredTag();
        return ignored != 0 && ignored == EntityShapes.tagOf(entity);
    }
}
