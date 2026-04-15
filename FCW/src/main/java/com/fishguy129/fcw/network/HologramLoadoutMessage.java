package com.fishguy129.fcw.network;

import com.fishguy129.fcw.FCWMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Client-to-server packet for the three hologram display slots on the core UI. 
// I just thought it would look cool lol can we pls keep it
public record HologramLoadoutMessage(BlockPos corePos, int slotIndex, boolean clear) {
    public static void encode(HologramLoadoutMessage message, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(message.corePos);
        buffer.writeVarInt(message.slotIndex);
        buffer.writeBoolean(message.clear);
    }

    public static HologramLoadoutMessage decode(FriendlyByteBuf buffer) {
        return new HologramLoadoutMessage(buffer.readBlockPos(), buffer.readVarInt(), buffer.readBoolean());
    }

    public static void handle(HologramLoadoutMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null) {
                FCWMod.CORE_MANAGER.setHologramDisplayItem(context.getSender(), message.corePos, message.slotIndex, message.clear);
            }
        });
        context.setPacketHandled(true);
    }
}
