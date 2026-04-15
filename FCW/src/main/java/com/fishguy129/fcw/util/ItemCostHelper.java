package com.fishguy129.fcw.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Helper for turning config strings into real raid recipes and consuming them from inventories.
public final class ItemCostHelper {
    private ItemCostHelper() {
    }

    public static List<CostEntry> parseEntries(List<? extends String> entries) {
        List<CostEntry> parsed = new ArrayList<>();
        for (String entry : entries) {
            String[] split = entry.split("=");
            if (split.length != 2) {
                continue;
            }

            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(split[0]));
            if (item == null) {
                continue;
            }

            try {
                int count = Integer.parseInt(split[1]);
                if (count > 0) {
                    parsed.add(new CostEntry(item, count));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return parsed;
    }

    public static List<CostEntry> scaleCosts(List<CostEntry> base, List<CostEntry> scaling, int craftedCount) {
        Map<Item, Integer> totals = new HashMap<>();
        base.forEach(entry -> totals.merge(entry.item(), entry.count(), Integer::sum));
        scaling.forEach(entry -> totals.merge(entry.item(), entry.count() * craftedCount, Integer::sum));
        return totals.entrySet().stream().map(entry -> new CostEntry(entry.getKey(), entry.getValue())).toList();
    }

    public static Map<Integer, List<CostEntry>> parseLevelEntries(List<? extends String> entries) {
        Map<Integer, List<CostEntry>> parsed = new HashMap<>();
        for (String entry : entries) {
            String[] split = entry.split("\\|", 2);
            if (split.length != 2) {
                continue;
            }

            try {
                int craftCount = Integer.parseInt(split[0].trim());
                List<CostEntry> recipe = parseRecipe(split[1]);
                if (!recipe.isEmpty()) {
                    parsed.put(craftCount, recipe);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return parsed;
    }

    public static List<CostEntry> resolveRaidCosts(List<CostEntry> base, List<CostEntry> scaling,
                                                   Map<Integer, List<CostEntry>> exactByCraftCount,
                                                   int craftedCount) {
        // Exact overrides win outright. If there is no override, fall back to the additive scale.
        List<CostEntry> exact = exactByCraftCount.get(craftedCount);
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }
        return scaleCosts(base, scaling, craftedCount);
    }

    public static List<CostEntry> sorted(List<CostEntry> costs) {
        return costs.stream()
                .sorted(Comparator.comparing(entry -> entry.item().getDescriptionId()))
                .toList();
    }

    private static List<CostEntry> parseRecipe(String input) {
        List<String> recipeEntries = new ArrayList<>();
        for (String token : input.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                recipeEntries.add(trimmed);
            }
        }
        return parseEntries(recipeEntries);
    }

    public static boolean hasCosts(ServerPlayer player, List<CostEntry> costs) {
        Inventory inventory = player.getInventory();
        for (CostEntry cost : costs) {
            int found = 0;
            for (ItemStack stack : inventory.items) {
                if (stack.is(cost.item())) {
                    found += stack.getCount();
                    if (found >= cost.count()) {
                        break;
                    }
                }
            }
            if (found < cost.count()) {
                return false;
            }
        }
        return true;
    }

    public static void consume(ServerPlayer player, List<CostEntry> costs) {
        Inventory inventory = player.getInventory();
        for (CostEntry cost : costs) {
            int remaining = cost.count();
            for (ItemStack stack : inventory.items) {
                if (remaining <= 0) {
                    break;
                }
                if (stack.is(cost.item())) {
                    int removed = Math.min(remaining, stack.getCount());
                    stack.shrink(removed);
                    remaining -= removed;
                }
            }
        }
        inventory.setChanged();
    }

    public record CostEntry(Item item, int count) {
    }
}
