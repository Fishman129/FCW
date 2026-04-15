package com.fishguy129.fcw.registry;

import com.fishguy129.fcw.FCWMod;
import com.fishguy129.fcw.menu.FactionCoreMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// Menu registrations
public final class FCWMenus {
    public static final DeferredRegister<MenuType<?>> REGISTRY = DeferredRegister.create(ForgeRegistries.MENU_TYPES, FCWMod.MOD_ID);

    public static final RegistryObject<MenuType<FactionCoreMenu>> FACTION_CORE_MENU = REGISTRY.register("faction_core",
            () -> IForgeMenuType.create(FactionCoreMenu::fromBuffer));

    private FCWMenus() {
    }
}
