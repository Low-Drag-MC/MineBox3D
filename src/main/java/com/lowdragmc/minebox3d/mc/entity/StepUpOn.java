package com.lowdragmc.minebox3d.mc.entity;

/**
 * <b>Whether an entity that walks into a shape may step up onto it</b> — Unreal's {@code CanCharacterStepUpOn}
 * ({@code ECanBeCharacterBase}), per shape ({@link EntityCollisions#setStepUpOn}). Only the step: an entity that has
 * landed on a shape it may not step onto stands and walks on it as on any other. As in Unreal, a shape that does not
 * say no still asks its owner ({@link EntityCollisions.StepUpOwner}, Unreal's {@code AActor::CanBeBaseForCharacter}),
 * yes where no owner has a say.
 */
public enum StepUpOn {
    /** It may not: walked into, the shape stops the entity — or, one it pushes, is pushed. */
    NO,
    /** It may, when the shape's top is within the entity's step height and its owner lets it. The default. */
    YES,
    /**
     * As its owner says. The owner is asked for {@link #YES} too, so here it steps as that does; it is there for a
     * mod's owner answer to read, as Unreal's {@code APawn} reads its root component's.
     */
    OWNER
}
