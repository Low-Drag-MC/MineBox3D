package com.lowdragmc.minebox3d.mc.sync;

import com.lowdragmc.minebox3d.MineBox3D;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * <b>Where entities stand on synced bodies</b> (design doc {@code 04-entities-and-bodies.md} §11.2 #3): server to one
 * client, a tick's changes — an entity it sees that stands on a body it has, where on it (the body's network number,
 * the feet in its frame, the entity's turn, its head's and its body's relative to the body's, its pitch), or
 * {@code body} -1 for one that has left. The client keeps those entities there on its replicas, so the two move
 * together: Minecraft's own movement packets, a few ticks apart and eased in, would have them slide about on a moving
 * deck.
 */
public record RiderPayload(List<Rider> riders) implements CustomPacketPayload {

    public static final Type<RiderPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MineBox3D.MOD_ID, "riders"));
    public static final StreamCodec<FriendlyByteBuf, RiderPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.riders.size());
                for (Rider rider : payload.riders) {
                    buffer.writeVarInt(rider.entity);
                    buffer.writeVarInt(rider.body + 1);
                    if (rider.body >= 0) {
                        buffer.writeFloat(rider.x);
                        buffer.writeFloat(rider.y);
                        buffer.writeFloat(rider.z);
                        buffer.writeFloat(rider.yaw);
                        buffer.writeFloat(rider.headYaw);
                        buffer.writeFloat(rider.bodyYaw);
                        buffer.writeFloat(rider.pitch);
                    }
                }
            },
            buffer -> {
                int count = buffer.readVarInt();
                List<Rider> riders = new ArrayList<>(Math.clamp(count, 0, 256));
                for (int i = 0; i < count; i++) {
                    int entity = buffer.readVarInt();
                    int body = buffer.readVarInt() - 1;
                    riders.add(
                            body < 0
                                    ? Rider.off(entity)
                                    : new Rider(
                                            entity,
                                            body,
                                            buffer.readFloat(),
                                            buffer.readFloat(),
                                            buffer.readFloat(),
                                            buffer.readFloat(),
                                            buffer.readFloat(),
                                            buffer.readFloat(),
                                            buffer.readFloat()));
                }
                return new RiderPayload(riders);
            });

    /** An entity on a body, or off one ({@code body} -1). */
    public record Rider(
            int entity, int body, float x, float y, float z, float yaw, float headYaw, float bodyYaw, float pitch) {

        public static Rider off(int entity) {
            return new Rider(entity, -1, 0, 0, 0, 0, 0, 0, 0);
        }

        /** Whether the client, told {@code other}, would place the entity no differently. */
        boolean sameAs(Rider other) {
            return other != null
                    && other.body == body
                    && Math.abs(other.x - x) < 1e-4f
                    && Math.abs(other.y - y) < 1e-4f
                    && Math.abs(other.z - z) < 1e-4f
                    && Math.abs(other.yaw - yaw) < 0.05f
                    && Math.abs(other.headYaw - headYaw) < 0.05f
                    && Math.abs(other.bodyYaw - bodyYaw) < 0.05f
                    && Math.abs(other.pitch - pitch) < 0.05f;
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
