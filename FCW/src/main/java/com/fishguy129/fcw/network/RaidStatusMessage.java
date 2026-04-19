package com.fishguy129.fcw.network;

import com.fishguy129.fcw.client.ClientRaidState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

// Server-to-client raid snapshot used by the HUD, world overlays, and core renderer.
public record RaidStatusMessage(String dimensionId, BlockPos corePos, boolean active, String attackerName, String defenderName,
                                long progressTicks, long requiredTicks, List<UUID> attackerIds, List<UUID> defenderIds,
                                List<Long> defendedChunkLongs, boolean localPlayerAttacker, boolean localPlayerDefender) {
    public static void encode(RaidStatusMessage message, net.minecraft.network.FriendlyByteBuf buffer) {
        buffer.writeUtf(message.dimensionId);
        buffer.writeBlockPos(message.corePos);
        buffer.writeBoolean(message.active);
        if (message.active) {
            buffer.writeUtf(message.attackerName);
            buffer.writeUtf(message.defenderName);
            buffer.writeVarLong(message.progressTicks);
            buffer.writeVarLong(message.requiredTicks);
            writeUuidList(buffer, message.attackerIds);
            writeUuidList(buffer, message.defenderIds);
            writeLongList(buffer, message.defendedChunkLongs);
            buffer.writeBoolean(message.localPlayerAttacker);
            buffer.writeBoolean(message.localPlayerDefender);
        }
    }

    public static RaidStatusMessage decode(net.minecraft.network.FriendlyByteBuf buffer) {
        String dimensionId = buffer.readUtf();
        BlockPos corePos = buffer.readBlockPos();
        boolean active = buffer.readBoolean();
        if (!active) {
            return new RaidStatusMessage(dimensionId, corePos, false, "", "", 0L, 0L, List.of(), List.of(), List.of(), false, false);
        }
        return new RaidStatusMessage(
                dimensionId,
                corePos,
                true,
                buffer.readUtf(),
                buffer.readUtf(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                readUuidList(buffer),
                readUuidList(buffer),
                readLongList(buffer),
                buffer.readBoolean(),
                buffer.readBoolean()
        );
    }

    public static void handle(RaidStatusMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(message)));
        context.setPacketHandled(true);
    }

    private static void handleClient(RaidStatusMessage message) {
        ResourceLocation dimensionId = new ResourceLocation(message.dimensionId);
        if (message.active) {
            // One packet refreshes the whole local view so the client never has to diff raid state.
            ClientRaidState.update(
                    dimensionId,
                    message.corePos,
                    message.attackerName,
                    message.defenderName,
                    message.progressTicks,
                    message.requiredTicks,
                    message.attackerIds,
                    message.defenderIds,
                    message.defendedChunkLongs,
                    message.localPlayerAttacker,
                    message.localPlayerDefender
            );
        } else {
            ClientRaidState.remove(dimensionId, message.corePos);
        }
    }

    private static void writeUuidList(net.minecraft.network.FriendlyByteBuf buffer, List<UUID> ids) {
        buffer.writeVarInt(ids.size());
        for (UUID id : ids) {
            buffer.writeUUID(id);
        }
    }

    private static List<UUID> readUuidList(net.minecraft.network.FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<UUID> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ids.add(buffer.readUUID());
        }
        return ids;
    }

    private static void writeLongList(net.minecraft.network.FriendlyByteBuf buffer, List<Long> values) {
        buffer.writeVarInt(values.size());
        for (Long value : values) {
            buffer.writeLong(value);
        }
    }

    private static List<Long> readLongList(net.minecraft.network.FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<Long> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(buffer.readLong());
        }
        return values;
    }
}
