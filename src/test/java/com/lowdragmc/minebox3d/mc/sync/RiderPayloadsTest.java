package com.lowdragmc.minebox3d.mc.sync;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The riders' payloads (doc 04 §11.2) read back what was written, the "on none" forms included. */
class RiderPayloadsTest {

    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T sent) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buffer, sent);
        T read = codec.decode(buffer);
        assertEquals(0, buffer.readableBytes(), "read to the end");
        return read;
    }

    @Test
    void whereEntitiesStandIsReadAsWritten() {
        RiderPayload sent = new RiderPayload(List.of(
                new RiderPayload.Rider(12, 0, 1.5f, -0.25f, 3, 90, -45.5f, 179, -30),
                RiderPayload.Rider.off(13),
                new RiderPayload.Rider(Integer.MAX_VALUE, 70_000, -200, 0, 0.001f, -720, 0, 1, 89.9f)));
        RiderPayload read = roundTrip(RiderPayload.CODEC, sent);
        assertEquals(sent, read);
        assertTrue(read.riders().get(0).sameAs(sent.riders().get(0)));
        assertFalse(read.riders().get(0).sameAs(new RiderPayload.Rider(12, 0, 1.5f, -0.25f, 3, 90, -45.5f, 179, 0)),
                "a new pitch is a change");
        assertEquals(new RiderPayload(List.of()), roundTrip(RiderPayload.CODEC, new RiderPayload(List.of())));
    }

    @Test
    void whereAPlayerStandsAndWhatItPushedAreReadAsWritten() {
        for (StandingPayload sent : List.of(new StandingPayload(0, 1, 2, 3, false), new StandingPayload(9_999, -0.5f, 0, 7, true),
                StandingPayload.off(false), StandingPayload.off(true))) {
            assertEquals(sent, roundTrip(StandingPayload.CODEC, sent));
        }
        PushPayload push = new PushPayload(42, 10.5f, -1, 0, 0.25f, 0.5f, -0.75f);
        assertEquals(push, roundTrip(PushPayload.CODEC, push));
    }
}
