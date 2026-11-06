package com.lowdragmc.minebox3d.mc.sync;

import com.lowdragmc.minebox3d.MineBox3D;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * <b>Where a player — or the vehicle it steers — stands on a synced body</b> (design doc
 * {@code 04-entities-and-bodies.md} §11.2 #3): client to server, right before each move packet, while it stands on (or is
 * still carried off) one: the body's network number and its feet in the body's frame; {@code body} -1 once it is on
 * none. The server puts the move that follows at that point of its own body before it checks it (SyncedRiders): the
 * client's world position is its replica's, a few ticks behind.
 */
public record StandingPayload(int body, float x, float y, float z, boolean vehicle) implements CustomPacketPayload {

    public static final Type<StandingPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MineBox3D.MOD_ID, "standing"));
    public static final StreamCodec<FriendlyByteBuf, StandingPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.vehicle);
                buffer.writeVarInt(payload.body + 1);
                if (payload.body >= 0) {
                    buffer.writeFloat(payload.x);
                    buffer.writeFloat(payload.y);
                    buffer.writeFloat(payload.z);
                }
            },
            buffer -> {
                boolean vehicle = buffer.readBoolean();
                int body = buffer.readVarInt() - 1;
                return body < 0 ? off(vehicle)
                        : new StandingPayload(body, buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), vehicle);
            });

    /** On no body. */
    public static StandingPayload off(boolean vehicle) {
        return new StandingPayload(-1, 0, 0, 0, vehicle);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
