package com.lowdragmc.minebox3d.mc.sync;

import com.lowdragmc.minebox3d.mc.entity.StepUpOn;
import com.lowdragmc.minebox3d.natives.NativeLibrary;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.MassData;
import com.lowdragmc.minebox3d.physics.SurfaceMaterial;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The network sync's payload reads back what was written, every kind of shape included; refuses to send what it cannot;
 * and refuses, while reading, a shape whose numbers do not fit its kind.
 */
class BodySyncPayloadTest {

    @BeforeAll
    static void load() {
        NativeLibrary.load();
    }

    private static BodySyncPayload.State state(int id) {
        return new BodySyncPayload.State(id, 12_345_678.25, -60.5, -29_999_000.125, 0, 0.6f, 0, 0.8f, 1, -2, 3, 0.1f, 0.2f,
                0.3f);
    }

    private static BodySyncPayload.SyncedShape shape(Geometry geometry, long category, long mask, int group, boolean sensor) {
        SurfaceMaterial surface = new SurfaceMaterial();
        surface.friction = 0.25f + Math.abs(group % 4);
        surface.restitution = 0.5f;
        surface.rollingResistance = 0.125f;
        return BodySyncPayload.SyncedShape.of(geometry, category, mask, group, sensor, surface,
                StepUpOn.values()[Math.floorMod(group, StepUpOn.values().length)]);
    }

    private static BodySyncPayload roundTrip(BodySyncPayload sent) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        BodySyncPayload.CODEC.encode(buffer, sent);
        BodySyncPayload read = BodySyncPayload.CODEC.decode(buffer);
        assertEquals(0, buffer.readableBytes(), "read to the end");
        return read;
    }

    @Test
    void whatIsWrittenIsWhatIsRead() {
        Geometry.Sphere sphere = new Geometry.Sphere(0.1f, 0.2f, 0.3f, 0.4f);
        Geometry.Capsule capsule = new Geometry.Capsule(0, 0, 0, 0, 1, 0, 0.25f);
        Geometry.Box box = new Geometry.Box(0.5f, 0.25f, 0.125f, 1, 2, 3, 0, 0.6f, 0, 0.8f);
        Geometry.Hull hull = Geometry.Hull.of(-1, -1, -1, 1, -1, -1, -1, 1, -1, 1, 1, -1, -1, -1, 1, 1, -1, 1, -1, 1, 1, 1, 1, 1);
        List<BodySyncPayload.SyncedShape> shapes = List.of(
                shape(sphere, 1, -1L, 0, false),
                shape(capsule, 2, 5, -3, true),
                shape(box, 4, 6, 7, false),
                shape(hull, Long.MIN_VALUE, Long.MAX_VALUE, Integer.MIN_VALUE, true));
        BodySyncPayload.Dynamics mass = new BodySyncPayload.Dynamics(31, 0.1f, -0.2f, 0.3f,
                new float[]{1, 0.5f, 0, 0.5f, 2, 0, 0, 0, 3}, true, 0.25f, 1.5f, 0, true);
        BodySyncPayload read = roundTrip(new BodySyncPayload(
                List.of(new BodySyncPayload.Spawn(7, "a crate", 42, 3, (1L << 62) | 0xFFFFFFFFL, shapes, state(7), mass,
                                new org.joml.Vector3f(0.1f, -0.2f, 0.3f)),
                        new BodySyncPayload.Spawn(8, null, -1, 0, 0, List.of(), state(8), null, null)),
                List.of(state(3), state(300_000)),
                new int[]{1, 2, 1_000_000}));

        assertEquals(2, read.spawns().size());
        BodySyncPayload.Spawn crate = read.spawns().getFirst();
        assertEquals(7, crate.id());
        assertEquals("a crate", crate.name());
        assertEquals(42, crate.userData());
        assertEquals(3, crate.tag());
        assertEquals((1L << 62) | 0xFFFFFFFFL, crate.ignoredTag(), "an entity it never meets, the largest id");
        assertEquals(state(7), crate.state(), "positions in double: millions out, to the last bit");
        List<BodySyncPayload.SyncedShape> got = crate.shapes();
        assertEquals(4, got.size());
        assertEquals(sphere, got.get(0).geometry());
        assertEquals(capsule, got.get(1).geometry());
        assertEquals(box, got.get(2).geometry());
        // the receiving end builds Box3D's hull of the points again: the same corners, not necessarily in the same order
        assertEquals(corners(hull), corners((Geometry.Hull) got.get(3).geometry()));
        for (int i = 0; i < 4; i++) {
            BodySyncPayload.SyncedShape s = shapes.get(i), r = got.get(i);
            assertEquals(s.categoryBits(), r.categoryBits(), "category of " + i);
            assertEquals(s.maskBits(), r.maskBits(), "mask of " + i);
            assertEquals(s.groupIndex(), r.groupIndex(), "group of " + i);
            assertEquals(s.sensor(), r.sensor(), "sensor of " + i);
            assertEquals(s.stepUpOn(), r.stepUpOn(), "step-up of " + i);
            SurfaceMaterial surface = r.surface(new SurfaceMaterial());
            assertEquals(s.friction(), surface.friction, "friction of " + i);
            assertEquals(0.5f, surface.restitution, "restitution of " + i);
            assertEquals(0.125f, surface.rollingResistance, "rolling resistance of " + i);
        }
        // what the crate weighs, and that entities push it; nothing for the other (not simulated on the server)
        BodySyncPayload.Dynamics readMass = crate.dynamics();
        assertNotNull(readMass);
        assertEquals(31, readMass.mass());
        assertEquals(-0.2f, readMass.cy());
        assertArrayEquals(mass.inertia(), readMass.inertia());
        assertTrue(readMass.pushedByEntities());
        // how it moves besides: what a client simulating it moves it by (doc 61 §3.9)
        assertEquals(0.25f, readMass.linearDamping());
        assertEquals(1.5f, readMass.angularDamping());
        assertEquals(0, readMass.gravityScale(), "weightless");
        assertTrue(readMass.fixedRotation());
        assertTrue(readMass.isUsable());
        assertFalse(new BodySyncPayload.Dynamics(1, 0, 0, 0, new float[9], true, -1, 0, 1, false).isUsable(),
                "a negative damping");
        assertFalse(new BodySyncPayload.Dynamics(1, 0, 0, 0, new float[9], true, 0, 0, Float.NaN, false).isUsable(),
                "a NaN gravity scale");
        // what a client needs to have to simulate it: all it touches, and the joints that hold it
        BodySyncPayload.State held = new BodySyncPayload.State(9, 1, 2, 3, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, false, 3);
        BodySyncPayload heldRead = roundTrip(new BodySyncPayload(List.of(), List.of(held), new int[0]));
        assertEquals(held, heldRead.states().getFirst());
        // how a body moves changed: told on its own, the replica kept
        BodySyncPayload tuned = roundTrip(new BodySyncPayload(List.of(), List.of(), new int[0], false, 5, 6,
                List.of(new BodySyncPayload.Tuning(7, mass))));
        assertFalse(tuned.isEmpty());
        assertEquals(7, tuned.tunings().getFirst().id());
        assertEquals(0, tuned.tunings().getFirst().dynamics().gravityScale());
        assertEquals(1.5f, tuned.tunings().getFirst().dynamics().angularDamping());
        MassData data = readMass.into(new MassData());
        assertEquals(2, data.inertia.m11(), "column by column");
        assertEquals(0.5f, data.inertia.m10());
        assertNull(read.spawns().get(1).dynamics());
        // the centre of mass of a dynamic one; none for the other
        assertEquals(new org.joml.Vector3f(0.1f, -0.2f, 0.3f), crate.centre());
        assertNull(read.spawns().get(1).centre());
        float[] inertia = new float[9];
        assertFalse(new BodySyncPayload.Dynamics(0, 0, 0, 0, inertia, true).isUsable(), "no mass");
        assertFalse(new BodySyncPayload.Dynamics(1, 0, 0, 0, new float[]{Float.NaN, 0, 0, 0, 0, 0, 0, 0, 0}, true)
                .isUsable());
        assertFalse(new BodySyncPayload.Dynamics(1, Float.NaN, 0, 0, inertia, true).isUsable(), "NaN centre");
        SurfaceMaterial kept = new SurfaceMaterial();
        kept.friction = 0.6f;
        new BodySyncPayload.SyncedShape((byte) 0, new float[4], 1, -1, 0, false, Float.NaN, -1, 0, StepUpOn.YES)
                .surface(kept);
        assertEquals(0.6f, kept.friction, "an unusable friction leaves the replica's own");
        assertNull(read.spawns().get(1).name());
        assertEquals(List.of(state(3), state(300_000)), read.states());
        assertArrayEquals(new int[]{1, 2, 1_000_000}, read.removals());
        assertFalse(read.isEmpty());
        assertFalse(read.settled());
        assertTrue(new BodySyncPayload(List.of(), List.of(), new int[0]).isEmpty());
        BodySyncPayload settled = roundTrip(BodySyncPayload.SETTLED);
        assertTrue(settled.settled() && !settled.isEmpty(), "the settled mark alone, read back");
        assertEquals(BodySyncPayload.NO_FRAME, read.frame(), "nobody's frame by default");
        // the server's tick and the player's frame it ran: what its client holds the states against (doc 61 §3.9)
        BodySyncPayload stamped = roundTrip(new BodySyncPayload(List.of(), List.of(state(3)), new int[0], false,
                12_345_678_901L, -7));
        assertEquals(12_345_678_901L, stamped.tick());
        assertEquals(-7, stamped.frame());
    }

    private static Set<List<Float>> corners(Geometry.Hull hull) {
        Set<List<Float>> corners = new HashSet<>();
        float[] p = hull.points();
        for (int i = 0; i < p.length; i += 3) {
            corners.add(List.of(p[i], p[i + 1], p[i + 2]));
        }
        return corners;
    }

    @Test
    void meshesAndHeightFieldsAreNotSent() {
        assertNull(shape(Geometry.Mesh.of(null), 1, -1, 0, false));
        assertNull(shape(new Geometry.HeightField(null), 1, -1, 0, false));
        assertNotNull(shape(Geometry.Box.of(1, 1, 1), 1, -1, 0, false));
    }

    /** One shape, of that kind with that many numbers, as the wire carries it. */
    private static FriendlyByteBuf oneShape(byte kind, int numbers) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(1);
        buffer.writeVarInt(1);
        buffer.writeBoolean(false);
        buffer.writeLong(0);
        buffer.writeLong(0); // no tags
        buffer.writeLong(0);
        buffer.writeVarInt(1);
        buffer.writeByte(kind);
        buffer.writeVarInt(numbers);
        for (int i = 0; i < numbers; i++) {
            buffer.writeFloat(i);
        }
        buffer.writeLong(1);
        buffer.writeLong(-1);
        buffer.writeInt(0);
        buffer.writeBoolean(false);
        buffer.writeFloat(0.6f); // friction, restitution, rolling resistance
        buffer.writeFloat(0);
        buffer.writeFloat(0);
        buffer.writeByte(StepUpOn.YES.ordinal());
        BodySyncPayload.State s = state(1);
        buffer.writeDouble(s.x());
        buffer.writeDouble(s.y());
        buffer.writeDouble(s.z());
        for (float f : new float[]{s.qx(), s.qy(), s.qz(), s.qw(), s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz()}) {
            buffer.writeFloat(f);
        }
        buffer.writeBoolean(true); // touching only what clients have
        buffer.writeByte(0); // no joints
        buffer.writeBoolean(false); // not simulated on the server
        buffer.writeBoolean(false); // no centre of mass
        buffer.writeVarInt(0);
        buffer.writeVarIntArray(new int[0]);
        buffer.writeBoolean(false); // not the settled mark
        buffer.writeVarLong(0); // the server's tick
        buffer.writeInt(BodySyncPayload.NO_FRAME);
        buffer.writeVarInt(0); // no tunings
        return buffer;
    }

    @Test
    void aShapeWhoseNumbersDoNotFitItsKindIsRefused() {
        // the hand-written wire is the codec's: a sphere's four numbers read back, to the end
        FriendlyByteBuf sphere = oneShape((byte) 0, 4);
        assertEquals(new Geometry.Sphere(0, 1, 2, 3),
                BodySyncPayload.CODEC.decode(sphere).spawns().getFirst().shapes().getFirst().geometry());
        assertEquals(0, sphere.readableBytes(), "read to the end");
        assertThrows(IllegalArgumentException.class, () -> BodySyncPayload.CODEC.decode(oneShape((byte) 0, 5)), "a sphere of 5");
        assertThrows(IllegalArgumentException.class, () -> BodySyncPayload.CODEC.decode(oneShape((byte) 2, 9)), "a box of 9");
        assertThrows(IllegalArgumentException.class, () -> BodySyncPayload.CODEC.decode(oneShape((byte) 3, 9)), "a hull of 3 points");
        assertThrows(IllegalArgumentException.class, () -> BodySyncPayload.CODEC.decode(oneShape((byte) 3, 13)), "a hull of 4⅓ points");
        assertThrows(IllegalArgumentException.class,
                () -> BodySyncPayload.CODEC.decode(oneShape((byte) 3, 3 * (Geometry.Hull.MAX_VERTICES + 1))), "a hull past the most");
        assertThrows(IllegalArgumentException.class, () -> BodySyncPayload.CODEC.decode(oneShape((byte) 9, 4)), "no such kind");
        assertDoesNotThrow(() -> BodySyncPayload.CODEC.decode(oneShape((byte) 3, 12)), "a hull of 4 points");
    }

    @Test
    void anUnusableStateIsKnownAsOne() {
        assertTrue(state(1).isUsable());
        assertFalse(new BodySyncPayload.State(1, Double.NaN, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0).isUsable(), "NaN position");
        assertFalse(new BodySyncPayload.State(1, 0, Double.POSITIVE_INFINITY, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0).isUsable());
        assertFalse(new BodySyncPayload.State(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0).isUsable(), "a zero rotation");
        assertFalse(new BodySyncPayload.State(1, 0, 0, 0, 0, 0, 0, 1, Float.NaN, 0, 0, 0, 0, 0).isUsable(), "NaN velocity");
        assertTrue(new BodySyncPayload.State(1, 0, 0, 0, 0, 0, 0, 1.01f, 0, 0, 0, 0, 0, 0).isUsable(), "near enough unit");
    }
}
