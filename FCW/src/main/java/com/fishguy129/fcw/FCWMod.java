package com.fishguy129.fcw;

import com.fishguy129.fcw.client.FCWClientEvents;
import com.fishguy129.fcw.client.FactionCoreBlockEntityRenderer;
import com.fishguy129.fcw.client.FactionCorePolishedScreen;
import com.fishguy129.fcw.command.FCWCommands;
import com.fishguy129.fcw.compat.ftbchunks.FtbChunkCompat;
import com.fishguy129.fcw.compat.ftbteams.FtbTeamCompat;
import com.fishguy129.fcw.config.FCWClientConfig;
import com.fishguy129.fcw.config.FCWServerConfig;
import com.fishguy129.fcw.core.CoreManager;
import com.fishguy129.fcw.event.FCWForgeEvents;
import com.fishguy129.fcw.network.FCWNetwork;
import com.fishguy129.fcw.raid.RaidManager;
import com.fishguy129.fcw.registry.FCWBlockEntities;
import com.fishguy129.fcw.registry.FCWBlocks;
import com.fishguy129.fcw.registry.FCWItems;
import com.fishguy129.fcw.registry.FCWMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(FCWMod.MOD_ID)
public class FCWMod {
    public static final String MOD_ID = "fcw";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static final FtbTeamCompat TEAM_COMPAT = new FtbTeamCompat();
    public static final FtbChunkCompat CHUNK_COMPAT = new FtbChunkCompat();
    public static final CoreManager CORE_MANAGER = new CoreManager(TEAM_COMPAT, CHUNK_COMPAT);
    public static final RaidManager RAID_MANAGER = new RaidManager(TEAM_COMPAT, CHUNK_COMPAT, CORE_MANAGER);

    public FCWMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, FCWClientConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, FCWServerConfig.SPEC);

        FCWBlocks.REGISTRY.register(modBus);
        FCWItems.REGISTRY.register(modBus);
        FCWBlockEntities.REGISTRY.register(modBus);
        FCWMenus.REGISTRY.register(modBus);

        FCWNetwork.init();

        modBus.addListener(this::onClientSetup);
        MinecraftForge.EVENT_BUS.register(new FCWForgeEvents());
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(FCWMenus.FACTION_CORE_MENU.get(), FactionCorePolishedScreen::new);
            BlockEntityRenderers.register(FCWBlockEntities.FACTION_CORE.get(), FactionCoreBlockEntityRenderer::new);
        });
        MinecraftForge.EVENT_BUS.register(new FCWClientEvents());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        FCWCommands.register(event.getDispatcher());
    }
}
