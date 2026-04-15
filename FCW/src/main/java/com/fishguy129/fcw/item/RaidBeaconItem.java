package com.fishguy129.fcw.item;

import com.fishguy129.fcw.FCWMod;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

// Consumable raid starter item. I made it be bound to the crafting faction so it cannot
// be passed around or traded/sold
public class RaidBeaconItem extends Item {
    public static final String TAG_BOUND_TEAM = "BoundTeamId";
    public static final String TAG_BOUND_TEAM_NAME = "BoundTeamName";

    public RaidBeaconItem(Properties properties) {
        super(properties);
    }

    public static void bind(ItemStack stack, UUID teamId, String teamName) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID(TAG_BOUND_TEAM, teamId);
        tag.putString(TAG_BOUND_TEAM_NAME, teamName);
    }

    @Nullable
    public static UUID boundTeam(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.hasUUID(TAG_BOUND_TEAM) ? tag.getUUID(TAG_BOUND_TEAM) : null;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!context.getLevel().isClientSide() && context.getPlayer() instanceof ServerPlayer player) {
            if (FCWMod.RAID_MANAGER.startRaid(player, context.getClickedPos(), context.getItemInHand())) {
                context.getItemInHand().shrink(1);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.FAIL;
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_BOUND_TEAM_NAME)) {
            tooltip.add(Component.translatable("item.fcw.raid_beacon.bound", tag.getString(TAG_BOUND_TEAM_NAME)).withStyle(ChatFormatting.GRAY));
        }
    }
}
