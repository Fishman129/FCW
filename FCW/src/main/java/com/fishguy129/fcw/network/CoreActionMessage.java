package com.fishguy129.fcw.network;

import com.fishguy129.fcw.FCWMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Small client-to-server packet for button presses coming from the core screen.
public record CoreActionMessage(BlockPos corePos, BlockPos targetPos, Action action) {
    public enum Action {
        CRAFT_RAID_ITEM,
        PACK_CORE
    }

    public static void encode(CoreActionMessage message, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(message.corePos);
        buffer.writeEnum(message.action);
        buffer.writeBoolean(message.targetPos != null);
        if (message.targetPos != null) {
            buffer.writeBlockPos(message.targetPos);
        }
    }

    public static CoreActionMessage decode(FriendlyByteBuf buffer) {
        BlockPos corePos = buffer.readBlockPos();
        Action action = buffer.readEnum(Action.class);
        BlockPos targetPos = buffer.readBoolean() ? buffer.readBlockPos() : null;
        return new CoreActionMessage(corePos, targetPos, action);
    }

    public static void handle(CoreActionMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() == null) {
                return;
            }

            switch (message.action) {
                case CRAFT_RAID_ITEM -> {
                    FCWMod.CORE_MANAGER.tryCraftRaidItem(context.getSender(), message.corePos);
                    FCWMod.CORE_MANAGER.syncRecipesToPlayer(context.getSender(), message.corePos);
                }
                case PACK_CORE -> FCWMod.CORE_MANAGER.packCore(context.getSender(), message.corePos);
            }
        });
        context.setPacketHandled(true);
    }
}
