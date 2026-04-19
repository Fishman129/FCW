package com.fishguy129.fcw.item;

import com.fishguy129.fcw.FCWMod;
import com.fishguy129.fcw.config.FCWServerConfig;
import com.fishguy129.fcw.data.FCWSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Block item wrapper so we can validate a newly placed core immediately and roll
// it back cleanly if the placement rules fail.
public class FactionCoreItem extends BlockItem {
    private static final String TAG_FCW_CORE_DATA = "FCWCoreData";
    private static final String TAG_ORIGIN_TEAM_NAME = "OriginTeamName";
    private static final String TAG_SAVED_DIMENSION = "SavedDimension";
    private static final String TAG_UPGRADE_COUNT = "UpgradeCount";
    private static final String TAG_LAST_RELOCATION_TICK = "LastRelocationTick";
    private static final String TAG_HOLOGRAM_ITEMS = "HologramItems";
    private static final String TAG_SAVED_CLAIM_CHUNKS = "SavedClaimChunks";
    private static final String TAG_CHUNK = "Chunk";

    public FactionCoreItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        ItemStack originalStack = context.getItemInHand().copy();
        InteractionResult result = super.place(context);
        if (result.consumesAction() && context.getPlayer() instanceof ServerPlayer player) {
            // Vanilla placement already happened, so failed validation has to undo it here.
            if (!FCWMod.CORE_MANAGER.validatePostPlacement(player, context.getClickedPos(), originalStack)) {
                context.getLevel().removeBlock(context.getClickedPos(), false);
                if (!player.getAbilities().instabuild) {
                    player.getInventory().placeItemBackInInventory(originalStack);
                }
                return InteractionResult.FAIL;
            }
        }
        return result;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        StoredCoreData storedData = readStoredCoreData(stack).orElse(null);
        int upgrades = storedData == null ? 0 : storedData.upgradeCount();
        int claimCapacity = FCWServerConfig.BASE_CLAIM_CHUNKS.get() + (upgrades * FCWServerConfig.CLAIMS_PER_UPGRADE.get());

        tooltip.add(Component.translatable("item.fcw.faction_core.tip.title").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        tooltip.add(Component.translatable("item.fcw.faction_core.tip.deploy").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.fcw.faction_core.tip.claims_per_upgrade", FCWServerConfig.CLAIMS_PER_UPGRADE.get()).withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable("item.fcw.faction_core.tip.upgrades", upgrades).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.fcw.faction_core.tip.capacity", claimCapacity).withStyle(ChatFormatting.WHITE));

        if (storedData != null && !storedData.originTeamName().isBlank()) {
            tooltip.add(Component.translatable("item.fcw.faction_core.tip.origin", storedData.originTeamName()).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (storedData != null && !storedData.savedClaimChunks().isEmpty()) {
            tooltip.add(Component.translatable("item.fcw.faction_core.tip.footprint", storedData.savedClaimChunks().size()).withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.translatable("item.fcw.faction_core.tip.restore_shape").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("item.fcw.faction_core.tip.fresh").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    public static void storePackedCoreData(ItemStack stack, FCWSavedData.CoreRecord record, @Nullable String originTeamName) {
        CompoundTag storedData = stack.getOrCreateTagElement(TAG_FCW_CORE_DATA);
        storedData.putInt(TAG_UPGRADE_COUNT, record.upgradeCount());
        storedData.putLong(TAG_LAST_RELOCATION_TICK, record.lastRelocationTick());
        storedData.putString(TAG_SAVED_DIMENSION, record.dimension().location().toString());
        if (originTeamName != null && !originTeamName.isBlank()) {
            storedData.putString(TAG_ORIGIN_TEAM_NAME, originTeamName);
        } else {
            storedData.remove(TAG_ORIGIN_TEAM_NAME);
        }

        ListTag hologramItems = new ListTag();
        for (ItemStack hologramItem : FCWSavedData.CoreRecord.normalizedHologramItems(record.hologramItems())) {
            CompoundTag itemTag = new CompoundTag();
            hologramItem.save(itemTag);
            hologramItems.add(itemTag);
        }
        storedData.put(TAG_HOLOGRAM_ITEMS, hologramItems);

        ListTag savedClaimChunks = new ListTag();
        for (Long chunkLong : FCWSavedData.CoreRecord.normalizedSavedClaimChunks(record.savedClaimChunks())) {
            CompoundTag chunkTag = new CompoundTag();
            chunkTag.putLong(TAG_CHUNK, chunkLong);
            savedClaimChunks.add(chunkTag);
        }
        storedData.put(TAG_SAVED_CLAIM_CHUNKS, savedClaimChunks);
    }

    public static Optional<StoredCoreData> readStoredCoreData(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return Optional.empty();
        }

        if (tag.contains(TAG_FCW_CORE_DATA, Tag.TAG_COMPOUND)) {
            return Optional.of(readPackedCoreData(tag.getCompound(TAG_FCW_CORE_DATA)));
        }
        if (tag.contains("BlockEntityTag", Tag.TAG_COMPOUND)) {
            return Optional.of(readBlockEntityCoreData(tag.getCompound("BlockEntityTag")));
        }
        return Optional.empty();
    }

    private static StoredCoreData readPackedCoreData(CompoundTag tag) {
        ResourceLocation savedDimensionId = tag.contains(TAG_SAVED_DIMENSION, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(TAG_SAVED_DIMENSION))
                : null;
        return new StoredCoreData(
                savedDimensionId,
                tag.getInt(TAG_UPGRADE_COUNT),
                tag.getLong(TAG_LAST_RELOCATION_TICK),
                readHologramItems(tag.getList(TAG_HOLOGRAM_ITEMS, Tag.TAG_COMPOUND)),
                readSavedClaimChunks(tag.getList(TAG_SAVED_CLAIM_CHUNKS, Tag.TAG_COMPOUND)),
                tag.getString(TAG_ORIGIN_TEAM_NAME)
        );
    }

    private static StoredCoreData readBlockEntityCoreData(CompoundTag tag) {
        return new StoredCoreData(
                null,
                tag.getInt("upgradeCount"),
                0L,
                readHologramItems(tag.getList("hologramItems", Tag.TAG_COMPOUND)),
                List.of(),
                tag.getString("teamName")
        );
    }

    private static List<ItemStack> readHologramItems(ListTag listTag) {
        List<ItemStack> items = new ArrayList<>(listTag.size());
        for (int i = 0; i < listTag.size(); i++) {
            items.add(ItemStack.of(listTag.getCompound(i)));
        }
        return FCWSavedData.CoreRecord.normalizedHologramItems(items);
    }

    private static List<Long> readSavedClaimChunks(ListTag listTag) {
        List<Long> chunks = new ArrayList<>(listTag.size());
        for (int i = 0; i < listTag.size(); i++) {
            chunks.add(listTag.getCompound(i).getLong(TAG_CHUNK));
        }
        return FCWSavedData.CoreRecord.normalizedSavedClaimChunks(chunks);
    }

    public record StoredCoreData(@Nullable ResourceLocation savedDimensionId, int upgradeCount, long lastRelocationTick,
                                 List<ItemStack> hologramItems, List<Long> savedClaimChunks, String originTeamName) {
        public FCWSavedData.CoreRecord toPlacementRecord(UUID teamId, ResourceKey<Level> placedDimension, BlockPos pos) {
            List<Long> preservedShape = savedDimensionId != null && savedDimensionId.equals(placedDimension.location())
                    ? savedClaimChunks
                    : List.of();
            return new FCWSavedData.CoreRecord(
                    teamId,
                    placedDimension,
                    pos,
                    true,
                    false,
                    false,
                    upgradeCount,
                    lastRelocationTick,
                    FCWSavedData.CoreRecord.normalizedHologramItems(hologramItems),
                    FCWSavedData.CoreRecord.normalizedSavedClaimChunks(preservedShape)
            );
        }
    }
}
