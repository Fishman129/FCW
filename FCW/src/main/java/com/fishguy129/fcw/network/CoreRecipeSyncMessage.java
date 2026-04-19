package com.fishguy129.fcw.network;

import com.fishguy129.fcw.menu.FactionCoreMenu;
import com.fishguy129.fcw.util.ItemCostHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// Server-to-client packet that pushes updated raid recipe costs to the core screen after a craft.
public record CoreRecipeSyncMessage(BlockPos corePos, List<FactionCoreMenu.RecipeCost> current, List<FactionCoreMenu.RecipeCost> next) {

    public static void encode(CoreRecipeSyncMessage msg, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(msg.corePos);
        FactionCoreMenu.writeRecipeCosts(buffer, msg.current.stream()
                .map(c -> new ItemCostHelper.CostEntry(c.item(), c.count())).toList());
        FactionCoreMenu.writeRecipeCosts(buffer, msg.next.stream()
                .map(c -> new ItemCostHelper.CostEntry(c.item(), c.count())).toList());
    }

    public static CoreRecipeSyncMessage decode(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        List<FactionCoreMenu.RecipeCost> current = readCosts(buffer);
        List<FactionCoreMenu.RecipeCost> next = readCosts(buffer);
        return new CoreRecipeSyncMessage(pos, current, next);
    }

    private static List<FactionCoreMenu.RecipeCost> readCosts(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<FactionCoreMenu.RecipeCost> costs = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Item item = ForgeRegistries.ITEMS.getValue(buffer.readResourceLocation());
            int count = buffer.readVarInt();
            if (item != null && count > 0) costs.add(new FactionCoreMenu.RecipeCost(item, count));
        }
        return costs;
    }

    public static void handle(CoreRecipeSyncMessage msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.containerMenu instanceof FactionCoreMenu menu
                    && menu.getCorePos().equals(msg.corePos)) {
                menu.updateRecipes(msg.current(), msg.next());
            }
        });
        ctx.setPacketHandled(true);
    }
}