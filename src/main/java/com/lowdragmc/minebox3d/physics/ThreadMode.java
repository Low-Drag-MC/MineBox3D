package com.lowdragmc.minebox3d.physics;

/**
 * <b>How a {@link PhysicsWorld} is shared between threads</b> (design doc {@code 03-threading-and-api.md} §4.2). Box3D
 * allows one thread inside a world at a time; either way, using a world against the rule throws
 * {@link WrongThreadException} at once, never sometimes.
 */
public enum ThreadMode {
    /**
     * Bound to the thread that created it until {@link PhysicsWorld#transferTo} hands it on. For worlds that live on
     * one thread: a level's physics, an editor preview.
     */
    OWNER_THREAD,
    /**
     * Any thread, one at a time, between {@link PhysicsWorld#use()} and closing what it returned. For worlds stepped on
     * a thread pool, where the thread changes from one frame to the next.
     */
    EXCLUSIVE
}
