package com.fishguy129.fcw.blockentity;

import com.fishguy129.fcw.data.FCWSavedData;
import com.fishguy129.fcw.registry.FCWBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// This block entity just mirrors enough state for rendering and UI.
public class FactionCoreBlockEntity extends BlockEntity {
    private UUID teamId;
    private String teamName = "";
    private boolean active = true;
    private int upgradeCount;
    private int currentClaims;
    private int targetClaims;
    private int nextRaidCostScale;
    private int breachRadius;
    private List<ItemStack> hologramItems = List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);

    public FactionCoreBlockEntity(BlockPos pos, BlockState state) {
        super(FCWBlockEntities.FACTION_CORE.get(), pos, state);
    }

    public void syncFromRecord(FCWSavedData.CoreRecord coreRecord, String displayName, int currentClaims, int targetClaims, int nextRaidCostScale, int breachRadius) {
        // Only kick a block update when something players can actually see changed.
        boolean changed = !java.util.Objects.equals(this.teamId, coreRecord.teamId())
                || !this.teamName.equals(displayName)
                || this.active != coreRecord.active()
                || this.upgradeCount != coreRecord.upgradeCount()
                || this.currentClaims != currentClaims
                || this.targetClaims != targetClaims
                || this.nextRaidCostScale != nextRaidCostScale
                || this.breachRadius != breachRadius
                || !sameStacks(this.hologramItems, coreRecord.hologramItems());
        this.teamId = coreRecord.teamId();
        this.teamName = displayName;
        this.active = coreRecord.active();
        this.upgradeCount = coreRecord.upgradeCount();
        this.currentClaims = currentClaims;
        this.targetClaims = targetClaims;
        this.nextRaidCostScale = nextRaidCostScale;
        this.breachRadius = breachRadius;
        this.hologramItems = copyStacks(coreRecord.hologramItems());
        if (changed) {
            setChanged();
        }
        if (changed && level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public UUID getTeamId() {
        return teamId;
    }

    public boolean isActive() {
        return active;
    }

    public String getTeamName() {
        return teamName;
    }

    public int getUpgradeCount() {
        return upgradeCount;
    }

    public int getCurrentClaims() {
        return currentClaims;
    }

    public int getTargetClaims() {
        return targetClaims;
    }

    public int getNextRaidCostScale() {
        return nextRaidCostScale;
    }

    public int getBreachRadius() {
        return breachRadius;
    }

    public List<ItemStack> getHologramItems() {
        return copyStacks(hologramItems);
    }

    public Component getDisplayName() {
        return Component.translatable("block.fcw.faction_core");
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (teamId != null) {
            tag.putUUID("teamId", teamId);
        }
        if (!teamName.isEmpty()) {
            tag.putString("teamName", teamName);
        }
        tag.putBoolean("active", active);
        tag.putInt("upgradeCount", upgradeCount);
        tag.putInt("currentClaims", currentClaims);
        tag.putInt("targetClaims", targetClaims);
        tag.putInt("nextRaidCostScale", nextRaidCostScale);
        tag.putInt("breachRadius", breachRadius);
        net.minecraft.nbt.ListTag hologramList = new net.minecraft.nbt.ListTag();
        for (ItemStack stack : hologramItems) {
            CompoundTag itemTag = new CompoundTag();
            stack.save(itemTag);
            hologramList.add(itemTag);
        }
        tag.put("hologramItems", hologramList);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        teamId = tag.hasUUID("teamId") ? tag.getUUID("teamId") : null;
        teamName = tag.getString("teamName");
        active = tag.getBoolean("active");
        upgradeCount = tag.getInt("upgradeCount");
        currentClaims = tag.getInt("currentClaims");
        targetClaims = tag.getInt("targetClaims");
        nextRaidCostScale = tag.getInt("nextRaidCostScale");
        breachRadius = tag.getInt("breachRadius");
        net.minecraft.nbt.ListTag hologramList = tag.getList("hologramItems", net.minecraft.nbt.Tag.TAG_COMPOUND);
        List<ItemStack> loaded = new ArrayList<>();
        for (int i = 0; i < hologramList.size(); i++) {
            loaded.add(ItemStack.of(hologramList.getCompound(i)));
        }
        hologramItems = copyStacks(loaded);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        // The hologram UI is fixed to three slots, so normalize early and keep it stable.
        List<ItemStack> copied = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            if (stacks != null && i < stacks.size() && !stacks.get(i).isEmpty()) {
                ItemStack stack = stacks.get(i).copy();
                stack.setCount(1);
                copied.add(stack);
            } else {
                copied.add(ItemStack.EMPTY);
            }
        }
        return List.copyOf(copied);
    }

    private static boolean sameStacks(List<ItemStack> left, List<ItemStack> right) {
        for (int i = 0; i < 3; i++) {
            ItemStack a = i < left.size() ? left.get(i) : ItemStack.EMPTY;
            ItemStack b = i < right.size() ? right.get(i) : ItemStack.EMPTY;
            if (!ItemStack.isSameItemSameTags(a, b)) {
                return false;
            }
        }
        return true;
    }
}
