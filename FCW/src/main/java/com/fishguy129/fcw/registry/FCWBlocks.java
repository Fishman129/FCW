package com.fishguy129.fcw.registry;

import com.fishguy129.fcw.FCWMod;
import com.fishguy129.fcw.block.FactionCoreBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// Block registrations
public final class FCWBlocks {
    public static final DeferredRegister<Block> REGISTRY = DeferredRegister.create(ForgeRegistries.BLOCKS, FCWMod.MOD_ID);

    public static final RegistryObject<Block> FACTION_CORE = REGISTRY.register("faction_core",
            () -> new FactionCoreBlock(BlockBehaviour.Properties.of()
                    .strength(-1.0F, 3_600_000.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .noOcclusion()
                    .requiresCorrectToolForDrops()));

    private FCWBlocks() {
    }
}
