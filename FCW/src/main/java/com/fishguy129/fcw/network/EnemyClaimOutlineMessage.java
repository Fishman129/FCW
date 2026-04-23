package com.fishguy129.fcw.network;

import com.fishguy129.fcw.client.ClientEnemyClaimOutlineState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record EnemyClaimOutlineMessage(List<EnemyOutline> outlines) {
    public record EnemyOutline(String dimensionId, int coreX, int coreY, int coreZ,
                               int breachRadius, int breachPathWidth, List<Long> chunkLongs) {
    }

    public static EnemyClaimOutlineMessage clear() {
        return new EnemyClaimOutlineMessage(List.of());
    }

    public static void encode(EnemyClaimOutlineMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.outlines.size());
        for (EnemyOutline outline : message.outlines) {
            buffer.writeUtf(outline.dimensionId);
            buffer.writeVarInt(outline.coreX);
            buffer.writeVarInt(outline.coreY);
            buffer.writeVarInt(outline.coreZ);
            buffer.writeVarInt(outline.breachRadius);
            buffer.writeVarInt(outline.breachPathWidth);
            buffer.writeVarInt(outline.chunkLongs.size());
            for (Long chunkLong : outline.chunkLongs) {
                buffer.writeLong(chunkLong);
            }
        }
    }

    public static EnemyClaimOutlineMessage decode(FriendlyByteBuf buffer) {
        int outlineCount = buffer.readVarInt();
        List<EnemyOutline> outlines = new ArrayList<>(outlineCount);
        for (int i = 0; i < outlineCount; i++) {
            String dimensionId = buffer.readUtf();
            int coreX = buffer.readVarInt();
            int coreY = buffer.readVarInt();
            int coreZ = buffer.readVarInt();
            int breachRadius = buffer.readVarInt();
            int breachPathWidth = buffer.readVarInt();
            int chunkCount = buffer.readVarInt();
            List<Long> chunkLongs = new ArrayList<>(chunkCount);
            for (int j = 0; j < chunkCount; j++) {
                chunkLongs.add(buffer.readLong());
            }
            outlines.add(new EnemyOutline(dimensionId, coreX, coreY, coreZ, breachRadius, breachPathWidth, chunkLongs));
        }
        return new EnemyClaimOutlineMessage(List.copyOf(outlines));
    }

    public static void handle(EnemyClaimOutlineMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(message)));
        context.setPacketHandled(true);
    }

    private static void handleClient(EnemyClaimOutlineMessage message) {
        if (message.outlines.isEmpty()) {
            ClientEnemyClaimOutlineState.clear();
            return;
        }
        ClientEnemyClaimOutlineState.update(message.outlines);
    }
}
