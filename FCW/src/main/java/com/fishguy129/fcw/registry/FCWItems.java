package com.fishguy129.fcw.registry;

import com.fishguy129.fcw.FCWMod;
import com.fishguy129.fcw.item.ClaimCatalystItem;
import com.fishguy129.fcw.item.FactionCoreItem;
import com.fishguy129.fcw.item.RaidBeaconItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// Item registrations
public final class FCWItems {
    public static final DeferredRegister<Item> REGISTRY = DeferredRegister.create(ForgeRegistries.ITEMS, FCWMod.MOD_ID);

    public static final RegistryObject<BlockItem> FACTION_CORE = REGISTRY.register("faction_core",
            () -> new FactionCoreItem(FCWBlocks.FACTION_CORE.get(), new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> CLAIM_CATALYST = REGISTRY.register("claim_catalyst",
            () -> new ClaimCatalystItem(new Item.Properties()));

    public static final RegistryObject<Item> RAID_BEACON = REGISTRY.register("raid_beacon",
            () -> new RaidBeaconItem(new Item.Properties().stacksTo(1)));

    private FCWItems() {
    }
}
