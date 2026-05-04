package com.fishguy129.fcw.config;

import net.minecraftforge.common.ForgeConfigSpec;

// Client configs (wouldn't have known if I didn't add this comment fr!)
public final class FCWClientConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLE_CORE_HOLOGRAM;
    public static final ForgeConfigSpec.DoubleValue CORE_HOLOGRAM_SCALE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CORE_ZONE_RING;
    public static final ForgeConfigSpec.DoubleValue CORE_ZONE_RING_ALPHA;
    public static final ForgeConfigSpec.BooleanValue ENABLE_OWN_CLAIM_OUTLINE;
    public static final ForgeConfigSpec.DoubleValue OWN_CLAIM_OUTLINE_ALPHA;
    public static final ForgeConfigSpec.DoubleValue OWN_CLAIM_OUTLINE_REVEAL_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_RAID_PLAYER_HIGHLIGHTS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_RAID_HUD;
    public static final ForgeConfigSpec.DoubleValue RAID_HUD_SCALE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_RAID_MUSIC;
    public static final ForgeConfigSpec.ConfigValue<String> RAID_MUSIC_SOUND;
    public static final ForgeConfigSpec.DoubleValue RAID_MUSIC_VOLUME;
    public static final ForgeConfigSpec.IntValue RAID_MUSIC_RANGE;
    public static final ForgeConfigSpec.IntValue CORE_ZONE_RING_CULL_DISTANCE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("coreRenderer");
        ENABLE_CORE_HOLOGRAM = builder.comment("Render the animated hologram above faction cores.")
                .define("enableCoreHologram", true);
        CORE_HOLOGRAM_SCALE = builder.comment("Global client-side scale multiplier for the faction core hologram.")
                .defineInRange("coreHologramScale", 1.0D, 0.5D, 2.5D);
        ENABLE_CORE_ZONE_RING = builder.comment("Render the world-space radius ring around nearby faction cores.")
                .define("enableCoreZoneRing", true);
        CORE_ZONE_RING_ALPHA = builder.comment("Opacity multiplier for the world-space core radius ring.")
                .defineInRange("coreZoneRingAlpha", 0.65D, 0.05D, 1.0D);
        ENABLE_OWN_CLAIM_OUTLINE = builder.comment("Render the owner-only in-world line outline for your faction's claimed chunks.")
                .define("enableOwnClaimOutline", true);
        OWN_CLAIM_OUTLINE_ALPHA = builder.comment("Opacity multiplier for the owner-only claimed chunk outline.")
                .defineInRange("ownClaimOutlineAlpha", 0.9D, 0.05D, 1.0D);
        OWN_CLAIM_OUTLINE_REVEAL_DISTANCE = builder.comment("Maximum distance in blocks where nearby claim-outline fence segments fade in.")
                .defineInRange("ownClaimOutlineRevealDistance", 7.0D, 1.0D, 64.0D);
        builder.pop();
        CORE_ZONE_RING_CULL_DISTANCE = builder.comment("Distance in blocks beyond which the core zone ring stops rendering. Set high to effectively disable culling.")
                .defineInRange("coreZoneRingCullDistance", 16, 16, 512);

        builder.push("raidVisuals");
        ENABLE_RAID_PLAYER_HIGHLIGHTS = builder.comment("Render red/blue raid highlights around active participants.")
                .define("enableRaidPlayerHighlights", true);
        ENABLE_RAID_HUD = builder.comment("Render the raid banner HUD while you are part of an active raid.")
                .define("enableRaidHud", true);
        RAID_HUD_SCALE = builder.comment("Global client-side scale multiplier for the raid HUD.")
                .defineInRange("raidHudScale", 1.0D, 0.7D, 2.0D);
        ENABLE_RAID_MUSIC = builder.comment("Play client-side raid ambience while you are in range of an active raid core.")
                .define("enableRaidMusic", true);
        RAID_MUSIC_SOUND = builder.comment("Sound event id used for the looping raid ambience.")
                .define("raidMusicSound", "minecraft:music.dragon");
        RAID_MUSIC_VOLUME = builder.comment("Client-side volume multiplier for raid ambience.")
                .defineInRange("raidMusicVolume", 0.42D, 0.0D, 2.0D);
        RAID_MUSIC_RANGE = builder.comment("Maximum range from the raid core where the ambience can be heard.")
                .defineInRange("raidMusicRange", 48, 8, 192);
        builder.pop();

        SPEC = builder.build();
    }

    private FCWClientConfig() {
    }
}
