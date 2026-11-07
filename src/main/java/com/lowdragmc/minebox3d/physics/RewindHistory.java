package com.lowdragmc.minebox3d.physics;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * <b>Where bodies were over the last ticks</b>, to put them back and simulate again — Unreal's rewind data, the bodies'
 * part of it ({@code RewindData.h}): each body's place, turn, velocities and whether it was awake at the end of each tick
 * it was recorded, in a ring of {@link #capacity()} ticks. Contacts and joints keep nothing (Unreal's neither): a
 * simulation run again from here meets them afresh, so it is the same to the bit only where they play no part. A tick is
 * whatever its owner counts.
 *
 * <p>On its world's thread, as the world.
 */
public final class RewindHistory {

    private final int capacity;
    private final Map<Body, Track> tracks = new IdentityHashMap<>();
    private final Vector3f velocity = new Vector3f();

    public RewindHistory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("a history holds a tick at least: " + capacity);
        }
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    /** The body as it is now, held as it was at the end of {@code tick}. */
    public void record(long tick, Body body) {
        Track track = tracks.computeIfAbsent(body, b -> new Track(capacity));
        int slot = slot(tick);
        track.ticks[slot] = tick;
        double[] at = body.current;
        System.arraycopy(at, 0, track.positions, 3 * slot, 3);
        for (int i = 0; i < 4; i++) {
            track.rotations[4 * slot + i] = (float) at[3 + i];
        }
        body.linearVelocity(velocity);
        track.velocities[6 * slot] = velocity.x;
        track.velocities[6 * slot + 1] = velocity.y;
        track.velocities[6 * slot + 2] = velocity.z;
        body.angularVelocity(velocity);
        track.velocities[6 * slot + 3] = velocity.x;
        track.velocities[6 * slot + 4] = velocity.y;
        track.velocities[6 * slot + 5] = velocity.z;
        track.awake[slot] = body.type() != BodyType.STATIC && body.isAwake();
    }

    /**
     * {@code body}'s state at the end of {@code tick} as it is known to have been — a server's word — held in place of what
     * was recorded, so a {@link #restore} puts it there. The rotation is taken as given: a unit one.
     */
    public void put(long tick, Body body, double x, double y, double z, float qx, float qy, float qz, float qw, float vx,
                    float vy, float vz, float wx, float wy, float wz, boolean awake) {
        Track track = tracks.computeIfAbsent(body, b -> new Track(capacity));
        int slot = slot(tick);
        track.ticks[slot] = tick;
        track.positions[3 * slot] = x;
        track.positions[3 * slot + 1] = y;
        track.positions[3 * slot + 2] = z;
        float[] r = track.rotations, w = track.velocities;
        r[4 * slot] = qx;
        r[4 * slot + 1] = qy;
        r[4 * slot + 2] = qz;
        r[4 * slot + 3] = qw;
        w[6 * slot] = vx;
        w[6 * slot + 1] = vy;
        w[6 * slot + 2] = vz;
        w[6 * slot + 3] = wx;
        w[6 * slot + 4] = wy;
        w[6 * slot + 5] = wz;
        track.awake[slot] = awake;
    }

    /** Whether the body's state at the end of {@code tick} is held. */
    public boolean has(long tick, Body body) {
        Track track = tracks.get(body);
        return track != null && track.ticks[slot(tick)] == tick;
    }

    /**
     * The body put back as it was at the end of {@code tick}, to the bit; false, and untouched, where that is not held —
     * never recorded then, or longer ago than the ring.
     */
    public boolean restore(long tick, Body body) {
        Track track = tracks.get(body);
        int slot = slot(tick);
        if (track == null || track.ticks[slot] != tick || !body.isValid()) {
            return false;
        }
        int p = 3 * slot, q = 4 * slot, v = 6 * slot;
        float[] r = track.rotations, w = track.velocities;
        body.restore(track.positions[p], track.positions[p + 1], track.positions[p + 2], r[q], r[q + 1], r[q + 2],
                r[q + 3], w[v], w[v + 1], w[v + 2], w[v + 3], w[v + 4], w[v + 5], track.awake[slot]);
        return true;
    }

    /** Where the body was at the end of {@code tick}, into {@code out}; null where that is not held. */
    @Nullable
    public Vector3d position(long tick, Body body, Vector3d out) {
        Track track = tracks.get(body);
        int slot = slot(tick);
        if (track == null || track.ticks[slot] != tick) {
            return null;
        }
        return out.set(track.positions[3 * slot], track.positions[3 * slot + 1], track.positions[3 * slot + 2]);
    }

    /** How the body was turned at the end of {@code tick}, into {@code out}; null where that is not held. */
    @Nullable
    public Quaternionf rotation(long tick, Body body, Quaternionf out) {
        Track track = tracks.get(body);
        int slot = slot(tick);
        if (track == null || track.ticks[slot] != tick) {
            return null;
        }
        float[] r = track.rotations;
        return out.set(r[4 * slot], r[4 * slot + 1], r[4 * slot + 2], r[4 * slot + 3]);
    }

    /** The bodies with something held. */
    public Set<Body> bodies() {
        return Collections.unmodifiableSet(tracks.keySet());
    }

    public void forget(Body body) {
        tracks.remove(body);
    }

    /** Every body destroyed since let go. */
    public void prune() {
        tracks.keySet().removeIf(body -> !body.isValid());
    }

    public void clear() {
        tracks.clear();
    }

    private int slot(long tick) {
        return (int) Math.floorMod(tick, (long) capacity);
    }

    /** One body's ring: its states by slot, and the tick each slot holds. */
    private static final class Track {
        final long[] ticks;
        final double[] positions;
        final float[] rotations;
        final float[] velocities;
        final boolean[] awake;

        Track(int capacity) {
            ticks = new long[capacity];
            Arrays.fill(ticks, Long.MIN_VALUE);
            positions = new double[3 * capacity];
            rotations = new float[4 * capacity];
            velocities = new float[6 * capacity];
            awake = new boolean[capacity];
        }
    }
}
