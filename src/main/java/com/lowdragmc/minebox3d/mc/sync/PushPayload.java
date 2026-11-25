package com.lowdragmc.minebox3d.mc.sync;

import com.lowdragmc.minebox3d.MineBox3D;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * <b>A push a player gave a synced body</b> (design doc {@code 04-entities-and-bodies.md} §11.2 #1): client to server. Its
 * client stops the player at the replica's surface and works out the inelastic collision of the two (EntityCollisions);
 * the server, which takes the client's word on bodies and so never meets the body in the player's move, applies the
 * impulse ({@code ix, iy, iz}, N·s) at the point ({@code x, y, z}, in the body's frame) — within what a player can give.
 */
public record PushPayload(int body, float ix, float iy, float iz, float x, float y, float z)
        implements CustomPacketPayload {

    public static final Type<PushPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MineBox3D.MOD_ID, "push"));
    public static final StreamCodec<FriendlyByteBuf, PushPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.body);
                buffer.writeFloat(payload.ix);
                buffer.writeFloat(payload.iy);
                buffer.writeFloat(payload.iz);
                buffer.writeFloat(payload.x);
                buffer.writeFloat(payload.y);
                buffer.writeFloat(payload.z);
            },
            buffer -> new PushPayload(
                    buffer.readVarInt(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
