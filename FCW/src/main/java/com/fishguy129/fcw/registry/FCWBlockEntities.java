package com.fishguy129.fcw.registry;

import com.fishguy129.fcw.FCWMod;
import com.fishguy129.fcw.blockentity.FactionCoreBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// Block entity registrations
public final class FCWBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> REGISTRY = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, FCWMod.MOD_ID);

    public static final RegistryObject<BlockEntityType<FactionCoreBlockEntity>> FACTION_CORE = REGISTRY.register("faction_core",
            () -> BlockEntityType.Builder.of(FactionCoreBlockEntity::new, FCWBlocks.FACTION_CORE.get()).build(null));

    private FCWBlockEntities() {
    }
}
