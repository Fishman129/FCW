package com.fishguy129.fcw.network;

import com.fishguy129.fcw.client.ClientClaimOutlineState;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// Server-to-client sync for the local faction's owned chunk outline.
public record ClaimOutlineMessage(boolean active, String dimensionId, int coreY, List<Long> chunkLongs) {
    public ClaimOutlineMessage(String dimensionId, int coreY, List<Long> chunkLongs) {
        this(true, dimensionId, coreY, List.copyOf(chunkLongs));
    }

    public static ClaimOutlineMessage clear() {
        return new ClaimOutlineMessage(false, "", 0, List.of());
    }

    public static void encode(ClaimOutlineMessage message, net.minecraft.network.FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.active);
        if (message.active) {
            buffer.writeUtf(message.dimensionId);
            buffer.writeVarInt(message.coreY);
            buffer.writeVarInt(message.chunkLongs.size());
            for (Long chunkLong : message.chunkLongs) {
                buffer.writeLong(chunkLong);
            }
        }
    }

    public static ClaimOutlineMessage decode(net.minecraft.network.FriendlyByteBuf buffer) {
        boolean active = buffer.readBoolean();
        if (!active) {
            return clear();
        }

        String dimensionId = buffer.readUtf();
        int coreY = buffer.readVarInt();
        int size = buffer.readVarInt();
        List<Long> chunkLongs = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            chunkLongs.add(buffer.readLong());
        }
        return new ClaimOutlineMessage(true, dimensionId, coreY, chunkLongs);
    }

    public static void handle(ClaimOutlineMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(message)));
        context.setPacketHandled(true);
    }

    private static void handleClient(ClaimOutlineMessage message) {
        if (!message.active) {
            ClientClaimOutlineState.clear();
            return;
        }
        ClientClaimOutlineState.update(new ResourceLocation(message.dimensionId), message.coreY, message.chunkLongs);
    }
}
