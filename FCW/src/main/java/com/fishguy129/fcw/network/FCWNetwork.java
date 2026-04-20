package com.fishguy129.fcw.network;

import com.fishguy129.fcw.FCWMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

// Central packet registration
public final class FCWNetwork {
    private static final String PROTOCOL = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(FCWMod.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int nextId = 0;

    private FCWNetwork() {
    }

    public static void init() {
        CHANNEL.registerMessage(nextId++, CoreActionMessage.class, CoreActionMessage::encode, CoreActionMessage::decode, CoreActionMessage::handle);
        CHANNEL.registerMessage(nextId++, RaidStatusMessage.class, RaidStatusMessage::encode, RaidStatusMessage::decode, RaidStatusMessage::handle);
        CHANNEL.registerMessage(nextId++, HologramLoadoutMessage.class, HologramLoadoutMessage::encode, HologramLoadoutMessage::decode, HologramLoadoutMessage::handle);
        CHANNEL.registerMessage(nextId++, CoreRecipeSyncMessage.class, CoreRecipeSyncMessage::encode, CoreRecipeSyncMessage::decode, CoreRecipeSyncMessage::handle);
        CHANNEL.registerMessage(nextId++, ClaimOutlineMessage.class, ClaimOutlineMessage::encode, ClaimOutlineMessage::decode, ClaimOutlineMessage::handle);
        CHANNEL.registerMessage(nextId++, EnemyClaimOutlineMessage.class, EnemyClaimOutlineMessage::encode, EnemyClaimOutlineMessage::decode, EnemyClaimOutlineMessage::handle);
    }
}
