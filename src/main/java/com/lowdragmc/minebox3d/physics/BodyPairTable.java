package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Which pairs among a fixed set of bodies never collide — a physics asset's collision groups and disabled pairs. Box3D asks
 * on its own threads and the bridge answers in C, so it costs nothing in Java; only shapes made with
 * {@link ShapeDef#enableCustomFiltering} are asked about. A body is in one table at most.
 *
 * <p>Changes reach Box3D with {@link #apply()}, pairs already touching included: the bodies whose pairs changed lose their
 * contacts and have them found again the next step. Gone with {@link #destroy()} or the world.
 */
public final class BodyPairTable {

    private final PhysicsWorld world;
    private final Body[] bodies;
    private final int[] words;
    private final int handle;
    private boolean dirty, destroyed;

    /** Every pair colliding to begin with. */
    public BodyPairTable(Body... bodies) {
        if (bodies.length == 0) {
            throw new IllegalArgumentException("a pair table needs a body");
        }
        this.world = bodies[0].world();
        this.bodies = bodies.clone();
        this.words = new int[(bodies.length * bodies.length + 31) / 32];
        long[] ids = new long[bodies.length];
        Set<Body> seen = new HashSet<>();
        for (int i = 0; i < bodies.length; i++) {
            if (bodies[i].world() != world) {
                throw new IllegalArgumentException("the bodies are in different worlds");
            }
            if (!seen.add(bodies[i])) {
                throw new IllegalArgumentException("a body is in the table twice");
            }
            ids[i] = bodies[i].id;
        }
        world.enter();
        try {
            world.checkOpen();
            handle = B3.pairTableCreate(world.id, ids, ids.length);
        } finally {
            world.exit();
        }
    }

    public int size() {
        return bodies.length;
    }

    public Body body(int index) {
        return bodies[index];
    }

    public boolean collides(int i, int j) {
        Objects.checkIndex(i, bodies.length);
        Objects.checkIndex(j, bodies.length);
        int bit = i * bodies.length + j;
        return (words[bit >>> 5] & (1 << (bit & 31))) == 0;
    }

    /** Both ways round. */
    public void setCollides(int i, int j, boolean collide) {
        Objects.checkIndex(i, bodies.length);
        Objects.checkIndex(j, bodies.length);
        set(i * bodies.length + j, collide);
        set(j * bodies.length + i, collide);
        dirty = true;
    }

    /** Every pair, a body with itself aside. */
    public void setAllCollide(boolean collide) {
        for (int i = 0; i < bodies.length; i++) {
            for (int j = 0; j < bodies.length; j++) {
                set(i * bodies.length + j, collide || i == j);
            }
        }
        dirty = true;
    }

    private void set(int bit, boolean collide) {
        if (collide) {
            words[bit >>> 5] &= ~(1 << (bit & 31));
        } else {
            words[bit >>> 5] |= 1 << (bit & 31);
        }
    }

    /** Hands the table to Box3D, if it changed. */
    public void apply() {
        world.enter();
        try {
            checkValid();
            if (dirty) {
                B3.pairTableSetBits(world.id, handle, words);
                dirty = false;
            }
        } finally {
            world.exit();
        }
    }

    public boolean isValid() {
        return !destroyed && world.isOpen();
    }

    public void destroy() {
        world.enter();
        try {
            // its disabled pairs would be refreshed into the broadphase: not in a faulted world, whose close frees it
            if (isValid() && !world.isFaulted()) {
                B3.pairTableDestroy(world.id, handle);
            }
            destroyed = true;
        } finally {
            world.exit();
        }
    }

    private void checkValid() {
        if (!world.isOpen()) {
            throw new IllegalStateException("the pair table's world is closed");
        }
        if (destroyed) {
            throw new IllegalStateException("pair table was destroyed");
        }
    }
}
