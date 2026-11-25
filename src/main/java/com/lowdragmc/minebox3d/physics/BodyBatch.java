package com.lowdragmc.minebox3d.physics;

import com.lowdragmc.minebox3d.internal.B3;

/**
 * Many bodies of one world driven in one call — a physics asset's kinematic bodies following the animation. Reading
 * where they are needs no batch: {@link Body#position} costs no call into Box3D. The bodies are fixed when made; a
 * destroyed one makes {@link #setTargetTransforms} throw.
 */
public final class BodyBatch {

    /** doubles per body: {@code [px, py, pz, qx, qy, qz, qw]} */
    public static final int TRANSFORM = 7;

    private final PhysicsWorld world;
    private final Body[] bodies;
    private final long[] ids;

    public BodyBatch(Body... bodies) {
        if (bodies.length == 0) {
            throw new IllegalArgumentException("a batch needs a body");
        }
        this.world = bodies[0].world();
        this.bodies = bodies.clone();
        this.ids = new long[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            if (bodies[i].world() != world) {
                throw new IllegalArgumentException("the bodies are in different worlds");
            }
            ids[i] = bodies[i].id;
        }
    }

    public int size() {
        return bodies.length;
    }

    public Body body(int index) {
        return bodies[index];
    }

    /**
     * {@link Body#setTargetTransform} for every body, {@link #TRANSFORM} doubles each, checked as it checks them —
     * before any body is driven.
     */
    public void setTargetTransforms(double[] transforms, float timeStep) {
        if (transforms.length < ids.length * TRANSFORM) {
            throw new IllegalArgumentException(
                    transforms.length + " doubles for " + ids.length + " bodies' transforms");
        }
        for (int i = 0; i < ids.length; i++) {
            int o = i * TRANSFORM;
            Checks.finite(transforms[o], transforms[o + 1], transforms[o + 2], "target position");
            double qx = transforms[o + 3], qy = transforms[o + 4], qz = transforms[o + 5], qw = transforms[o + 6];
            double length = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (!(length > 1e-6) || !Double.isFinite(length)) {
                throw new IllegalArgumentException(
                        "target rotation has no direction: (" + qx + ", " + qy + ", " + qz + ", " + qw + ")");
            }
        }
        world.enter();
        try {
            Checks.positive(timeStep, "time step");
            for (Body body : bodies) {
                if (!body.isValid()) {
                    throw new IllegalStateException("a body of the batch was destroyed");
                }
            }
            B3.bodiesSetTargetTransforms(ids, ids.length, transforms, timeStep, true);
            for (Body body : bodies) {
                body.forgetMotion();
            }
        } finally {
            world.exit();
        }
    }
}
