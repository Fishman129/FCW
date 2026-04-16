package com.fishguy129.fcw.menu;

import com.fishguy129.fcw.registry.FCWMenus;
import com.fishguy129.fcw.registry.FCWItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Menu (obviously)
public class FactionCoreMenu extends AbstractContainerMenu {
    public static final int DATA_ACTIVE = 0;
    public static final int DATA_UPGRADES = 1;
    public static final int DATA_TARGET_CLAIMS = 2;
    public static final int DATA_CURRENT_CLAIMS = 3;
    public static final int DATA_RAID_COST = 4;
    public static final int DATA_LEADER = 5;

    private final BlockPos corePos;
    private final String teamName;
    private final ContainerData data;
    private List<RecipeCost> currentRaidCosts;
    private List<RecipeCost> nextRaidCosts;
    private final List<ItemStack> hologramItems;

    public static FactionCoreMenu fromBuffer(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        String teamName = buffer.readUtf();
        List<RecipeCost> currentRaidCosts = readRecipeCosts(buffer);
        List<RecipeCost> nextRaidCosts = readRecipeCosts(buffer);
        List<ItemStack> hologramItems = readItemStacks(buffer);
        return new FactionCoreMenu(containerId, inventory, pos, teamName, new SimpleContainerData(6), currentRaidCosts, nextRaidCosts, hologramItems);
    }

    public FactionCoreMenu(int containerId, Inventory inventory, BlockPos corePos, String teamName, ContainerData data) {
        this(containerId, inventory, corePos, teamName, data, List.of(), List.of(), List.of());
    }

    public FactionCoreMenu(int containerId, Inventory inventory, BlockPos corePos, String teamName, ContainerData data,
                           List<RecipeCost> currentRaidCosts, List<RecipeCost> nextRaidCosts, List<ItemStack> hologramItems) {
        super(FCWMenus.FACTION_CORE_MENU.get(), containerId);
        this.corePos = corePos;
        this.teamName = teamName;
        this.data = data;
        this.currentRaidCosts = List.copyOf(currentRaidCosts);
        this.nextRaidCosts = List.copyOf(nextRaidCosts);
        this.hologramItems = new ArrayList<>(copyStacks(hologramItems));
        addDataSlots(data);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().isLoaded(corePos) && player.blockPosition().closerThan(corePos, 16D);
    }

    public BlockPos getCorePos() { return corePos; }
    public String getTeamName() { return teamName; }
    public boolean isActive() { return data.get(DATA_ACTIVE) > 0; }
    public int upgradeCount() { return data.get(DATA_UPGRADES); }
    public int targetClaims() { return data.get(DATA_TARGET_CLAIMS); }
    public int currentClaims() { return data.get(DATA_CURRENT_CLAIMS); }
    public int nextRaidCostScale() { return data.get(DATA_RAID_COST); }
    public boolean canRelocate() { return data.get(DATA_LEADER) > 0; }

    public List<ItemStack> hologramItems() { return copyStacks(hologramItems); }

    public void setHologramItem(int index, ItemStack stack) {
        if (index < 0 || index >= hologramItems.size()) {
            return;
        }
        ItemStack copy = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        if (!copy.isEmpty()) {
            copy.setCount(1);
        }
        hologramItems.set(index, copy);
    }

    public void updateRecipes(List<RecipeCost> current, List<RecipeCost> next) {
        this.currentRaidCosts = List.copyOf(current);
        this.nextRaidCosts = List.copyOf(next);
    }

    public List<ItemStack> currentRaidRecipe() {
        return currentRaidCosts.stream()
                .map(cost -> new ItemStack(cost.item(), cost.count()))
                .sorted(Comparator.comparing(ItemStack::getDescriptionId))
                .toList();
    }

    public List<ItemStack> nextRaidRecipe() {
        return nextRaidCosts.stream()
                .map(cost -> new ItemStack(cost.item(), cost.count()))
                .sorted(Comparator.comparing(ItemStack::getDescriptionId))
                .toList();
    }

    public ItemStack currentRaidResult() {
        return new ItemStack(FCWItems.RAID_BEACON.get());
    }

    public int predictedNextRecipeScale() {
        return nextRaidCostScale() + 1;
    }

    private List<ItemStack> stacksFor(List<RecipeCost> costs) {
        return costs.stream()
                .map(cost -> new ItemStack(cost.item(), cost.count()))
                .sorted(Comparator.comparing(stack -> stack.getDescriptionId()))
                .toList();
    }

    private static List<RecipeCost> readRecipeCosts(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<RecipeCost> costs = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Item item = ForgeRegistries.ITEMS.getValue(buffer.readResourceLocation());
            int count = buffer.readVarInt();
            if (item != null && count > 0) {
                costs.add(new RecipeCost(item, count));
            }
        }
        return costs;
    }

    public static void writeRecipeCosts(FriendlyByteBuf buffer, List<? extends com.fishguy129.fcw.util.ItemCostHelper.CostEntry> costs) {
        buffer.writeVarInt(costs.size());
        for (com.fishguy129.fcw.util.ItemCostHelper.CostEntry cost : costs) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(cost.item());
            if (id == null) {
                buffer.writeResourceLocation(new ResourceLocation("minecraft", "air"));
                buffer.writeVarInt(0);
            } else {
                buffer.writeResourceLocation(id);
                buffer.writeVarInt(cost.count());
            }
        }
    }

    public static void writeItemStacks(FriendlyByteBuf buffer, List<ItemStack> stacks) {
        List<ItemStack> copied = copyStacks(stacks);
        buffer.writeVarInt(copied.size());
        for (ItemStack stack : copied) {
            buffer.writeItem(stack);
        }
    }

    private static List<ItemStack> readItemStacks(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<ItemStack> stacks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            stacks.add(buffer.readItem());
        }
        return copyStacks(stacks);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        // The hologram layout is intentionally fixed at three display items.
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

    public record RecipeCost(Item item, int count) {}
}
