package com.lowdragmc.minebox3d.mc;

import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.RewindHistory;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * <b>A level's physics rolled back and simulated again</b> (EntityStudio doc 61 §3.3, §3.8) — Unreal's networked
 * physics resimulation in its bubble mode: the bodies around what something {@linkplain #watch watches} are recorded at
 * the end of every tick, a second's worth ({@link RewindHistory}); {@linkplain #resimulate simulated again} from a past
 * tick, they are put back as they were then and the ticks since are run again, their listeners giving each its inputs
 * again and known bodies put where they were known to be; the rest of the world stands still meanwhile and is as it
 * was after. What was drawn fades into what is over 0.3 s ({@link Body#addRenderError}); what the ticks run again set
 * off — contacts, joint forces — is dropped, as Unreal's resimulation raises no game events.
 *
 * <p>Nothing is recorded while nothing is watched. On the level's thread.
 */
public final class LevelRewind {

    /** Ticks held: a second, Unreal's {@code MaxSupportedLatencyPrediction}. */
    public static final int HISTORY_TICKS = 20;
    /** How far round what is watched bodies are recorded and run again, blocks (doc 61 §6: the bubble). */
    public static final double BUBBLE_RADIUS = 8;

    private static final float TICK_SECONDS = 1 / 20f;

    /** Past this far from where a body is known to have been, it is run again: Unreal's ResimErrorThreshold, 10 cm. */
    public static final double POSITION_THRESHOLD = 0.1;
    /** ...or turned this far from it: Unreal's 4°. */
    public static final float ROTATION_THRESHOLD_DEGREES = 4;

    private final LevelPhysics physics;
    private final RewindHistory history = new RewindHistory(HISTORY_TICKS);
    private final Map<Object, Supplier<Vec3>> watched = new LinkedHashMap<>();
    /** By tick: the bodies whose state then is known ({@link #know}), not recorded — put there when run again. */
    private final java.util.TreeMap<Long, java.util.Set<Body>> known = new java.util.TreeMap<>();

    private long recordedTick = Long.MIN_VALUE;
    private long asked = Long.MAX_VALUE;
    private int resimulations;

    LevelRewind(LevelPhysics physics) {
        this.physics = physics;
    }

    /** Records the bodies within {@link #BUBBLE_RADIUS} of {@code centre} from the next tick on, under {@code key}. */
    public void watch(Object key, Supplier<Vec3> centre) {
        watched.put(key, centre);
    }

    public void unwatch(Object key) {
        watched.remove(key);
    }

    public boolean isWatching() {
        return !watched.isEmpty();
    }

    /** The bodies as recorded: to compare with what is known of them (the server's states). */
    public RewindHistory history() {
        return history;
    }

    /** The last tick recorded, or {@link Long#MIN_VALUE}. */
    public long recordedTick() {
        return recordedTick;
    }

    /** How many times it has simulated again: the debugger's, and tests'. */
    public int resimulations() {
        return resimulations;
    }

    /** After a tick's steps: the bodies round what is watched, as they are at its end. */
    void afterSteps(long tick) {
        if (watched.isEmpty()) {
            if (recordedTick != Long.MIN_VALUE) {
                history.clear();
                known.clear();
                recordedTick = Long.MIN_VALUE;
            }
            return;
        }
        List<Vec3> centres = new ArrayList<>(watched.size());
        for (Supplier<Vec3> centre : watched.values()) {
            Vec3 at = centre.get();
            if (at != null) {
                centres.add(at);
            }
        }
        float[] bounds = new float[6];
        for (Body body : physics.world().bodies()) {
            if (body.isValid()
                    && body.type() != BodyType.STATIC
                    && body.isEnabled()
                    && near(body.bounds(bounds), centres)) {
                history.record(tick, body);
            }
        }
        history.prune();
        recordedTick = tick;
        known.headMap(tick - HISTORY_TICKS, true).clear();
    }

    /**
     * Whether a body's bounds reach into the bubble round any centre. ⚠️ Not where its origin is: a long deck stood on
     * at its far end would be out of the run again, and what stands on it fall through it there.
     */
    private static boolean near(float[] bounds, List<Vec3> centres) {
        for (Vec3 centre : centres) {
            double dx = Math.max(0, Math.max(bounds[0] - centre.x, centre.x - bounds[3]));
            double dy = Math.max(0, Math.max(bounds[1] - centre.y, centre.y - bounds[4]));
            double dz = Math.max(0, Math.max(bounds[2] - centre.z, centre.z - bounds[5]));
            if (dx * dx + dy * dy + dz * dz <= BUBBLE_RADIUS * BUBBLE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where {@code body} is known to have been at the end of {@code tick} — the server's word — against where it was
     * recorded then: further off than {@link #POSITION_THRESHOLD} or {@link #ROTATION_THRESHOLD_DEGREES}, it is held
     * as known and the ticks after are run again from there (at the start of the next tick). Not recorded then — it
     * came into the bubble since, or that is older than the history — it is left as it is.
     *
     * @return whether it was off
     */
    public boolean know(
            long tick,
            Body body,
            double x,
            double y,
            double z,
            float qx,
            float qy,
            float qz,
            float qw,
            float vx,
            float vy,
            float vz,
            float wx,
            float wy,
            float wz) {
        Vector3d was = history.position(tick, body, new Vector3d());
        Quaternionf turned = history.rotation(tick, body, new Quaternionf());
        if (was == null || turned == null) {
            // not recorded then: not rolled back, as Unreal's. ⚠️ Nor put there: a second old, it would throw away
            // all that was run since
            return false;
        }
        double off = was.distance(x, y, z);
        float degrees = (float)
                Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(turned.dot(new Quaternionf(qx, qy, qz, qw))))));
        if (off <= POSITION_THRESHOLD && degrees <= ROTATION_THRESHOLD_DEGREES) {
            return false;
        }
        history.put(tick, body, x, y, z, qx, qy, qz, qw, vx, vy, vz, wx, wy, wz, true);
        known.computeIfAbsent(tick, t -> java.util.Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(body);
        requestResimulation(tick + 1);
        return true;
    }

    /**
     * Asks for the ticks from {@code from} on to be simulated again at the start of the next tick, before anything is
     * given that tick's targets; asked twice, from the earlier.
     */
    public void requestResimulation(long from) {
        asked = Math.min(asked, from);
    }

    /** Whether the ticks from {@code from} on can be run again: recorded, and not older than the history. */
    public boolean canResimulateFrom(long from) {
        long last = recordedTick;
        return !watched.isEmpty() && last != Long.MIN_VALUE && from <= last && last - (from - 1) < HISTORY_TICKS;
    }

    /**
     * What was asked run now, if anything was: the tick does it before its steps, and a predictor runs it sooner —
     * before its own next frame, which the run would otherwise start from the old ending of (EntityStudio doc 61 §3.9).
     *
     * @return whether a run happened
     */
    public boolean resimulateIfAsked() {
        if (asked == Long.MAX_VALUE) {
            return false;
        }
        long from = asked;
        asked = Long.MAX_VALUE;
        return resimulate(from);
    }

    /**
     * The bodies recorded at the end of tick {@code from} − 1 put back as they were, and ticks {@code from} to the last
     * recorded run again: each tick its listeners told before its steps
     * ({@link LevelPhysics.TickListener#beforeResimTick}) and after
     * ({@link LevelPhysics.TickListener#afterResimTick}), a kinematic body driven as it was, and every body then
     * recorded anew — or, where its place for that tick is known ({@link #know}), put there. The rest of the world is
     * out of the simulation meanwhile and put back after.
     *
     * @return false, nothing touched, where that tick is not held — older than the history, or nothing watched then
     */
    public boolean resimulate(long from) {
        long last = recordedTick;
        if (watched.isEmpty() || last == Long.MIN_VALUE || from > last || last - (from - 1) >= HISTORY_TICKS) {
            return false;
        }
        List<Body> inside = new ArrayList<>();
        for (Body body : history.bodies()) {
            if (body.isValid() && history.has(from - 1, body)) {
                inside.add(body);
            }
        }
        if (inside.isEmpty()) {
            return false;
        }
        PhysicsWorld world = physics.world();
        // as they are drawn now: what is drawn fades from there to where they are run to
        Map<Body, double[]> drawn = new IdentityHashMap<>();
        for (Body body : inside) {
            Vector3d p = body.position(new Vector3d());
            Quaternionf q = body.rotation(new Quaternionf());
            drawn.put(body, new double[] {p.x, p.y, p.z, q.x, q.y, q.z, q.w});
        }
        // the rest still: out of the simulation, and back as they are after
        RewindHistory held = new RewindHistory(1);
        List<Body> outside = new ArrayList<>();
        for (Body body : world.bodies()) {
            if (body.isValid() && body.type() != BodyType.STATIC && body.isEnabled() && !drawn.containsKey(body)) {
                held.record(0, body);
                outside.add(body);
                body.setEnabled(false);
            }
        }
        try {
            for (Body body : inside) {
                history.restore(from - 1, body);
            }
            Vector3d target = new Vector3d();
            Quaternionf turn = new Quaternionf();
            for (long tick = from; tick <= last; tick++) {
                physics.tellResim(true, tick);
                for (Body body : inside) {
                    // a kinematic body is driven as it was: from the last tick's end to this one's
                    if (body.isValid()
                            && body.type() == BodyType.KINEMATIC
                            && history.position(tick, body, target) != null) {
                        history.restore(tick - 1, body);
                        body.setTargetTransform(
                                target.x, target.y, target.z, history.rotation(tick, body, turn), TICK_SECONDS);
                    }
                }
                physics.stepWorld();
                physics.tellResim(false, tick);
                java.util.Set<Body> knownThen = known.get(tick);
                for (Body body : inside) {
                    if (!body.isValid()) {
                        continue;
                    }
                    // where the server said it was then, it is put; the rest go on as run
                    if (knownThen != null && knownThen.contains(body)) {
                        history.restore(tick, body);
                    } else {
                        history.record(tick, body);
                    }
                }
            }
            known.headMap(last, true).clear();
        } finally {
            for (Body body : outside) {
                if (body.isValid()) {
                    body.setEnabled(true);
                    held.restore(0, body);
                }
            }
            world.discardEvents();
        }
        Vector3d now = new Vector3d();
        Quaternionf q = new Quaternionf();
        for (Body body : inside) {
            if (!body.isValid()) {
                continue;
            }
            double[] was = drawn.get(body);
            body.position(now);
            body.rotation(q);
            Quaternionf turned =
                    new Quaternionf((float) was[3], (float) was[4], (float) was[5], (float) was[6]).mul(q.conjugate());
            double dx = was[0] - now.x, dy = was[1] - now.y, dz = was[2] - now.z;
            if (dx != 0 || dy != 0 || dz != 0 || Math.abs(turned.w) < 1) {
                body.addRenderError(dx, dy, dz, turned);
            }
        }
        resimulations++;
        return true;
    }
}
