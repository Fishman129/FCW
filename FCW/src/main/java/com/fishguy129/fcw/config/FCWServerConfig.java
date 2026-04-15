package com.fishguy129.fcw.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

// Server configs (obviously)
public final class FCWServerConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue BREACH_RADIUS;
    public static final ForgeConfigSpec.IntValue BASE_CLAIM_CHUNKS;
    public static final ForgeConfigSpec.DoubleValue CORE_AMBIENT_EFFECT_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue CORE_RAID_EFFECT_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue CLAIMS_PER_UPGRADE;
    public static final ForgeConfigSpec.IntValue MAX_CLAIM_RANGE;
    public static final ForgeConfigSpec.BooleanValue SMART_CLAIM_EXPANSION;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_CONNECTED_CLAIMS;
    public static final ForgeConfigSpec.BooleanValue BLOCK_MANUAL_FTB_CLAIMS;
    public static final ForgeConfigSpec.BooleanValue ADMIN_BYPASS_STRUCTURED_CLAIMS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_ALLIED_RAIDING;
    public static final ForgeConfigSpec.BooleanValue RAID_FORCE_UNCLAIM_ON_SUCCESS;
    public static final ForgeConfigSpec.BooleanValue RAID_SUSPEND_PROTECTION_ON_SUCCESS;
    public static final ForgeConfigSpec.BooleanValue RAID_DROP_UPGRADES_ON_SUCCESS;
    public static final ForgeConfigSpec.IntValue RAID_BASE_DURATION_SECONDS;
    public static final ForgeConfigSpec.IntValue RAID_PRESENCE_RADIUS;
    public static final ForgeConfigSpec.IntValue RAID_LOGOUT_GRACE_SECONDS;
    public static final ForgeConfigSpec.BooleanValue KEEP_ACTIVE_RAID_CHUNKS_LOADED;
    public static final ForgeConfigSpec.ConfigValue<String> RAID_WORLD_SOUND_EVENT;
    public static final ForgeConfigSpec.DoubleValue RAID_WORLD_SOUND_VOLUME;
    public static final ForgeConfigSpec.BooleanValue DEFENDER_ONLINE_SCALING_ENABLED;
    public static final ForgeConfigSpec.IntValue DEFENDER_ONLINE_MIN_FACTION_SIZE;
    public static final ForgeConfigSpec.DoubleValue DEFENDER_ONLINE_RATIO_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue DEFENDER_ONLINE_MAX_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue DEFENDER_ZERO_ONLINE_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue DEFENDER_TIMER_MIN_SECONDS;
    public static final ForgeConfigSpec.IntValue DEFENDER_TIMER_MAX_SECONDS;
    public static final ForgeConfigSpec.BooleanValue DEFENDER_RECENT_JOINS_COUNT;
    public static final ForgeConfigSpec.IntValue DEFENDER_NEW_MEMBER_GRACE_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RAID_BASE_COST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RAID_SCALING_COST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RAID_EXACT_LEVEL_COSTS;
    public static final ForgeConfigSpec.BooleanValue UNCLAIM_ALL_ON_CORE_PACK;
    public static final ForgeConfigSpec.IntValue CORE_RELOCATION_COOLDOWN_SECONDS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("core");
        builder.comment(
                "Faction core settings.",
                "breachRadius controls the defended ground radius rendered around active cores.",
                "relocationCooldownSeconds controls how long a faction must wait before packing a placed core again.",
                "unclaimAllOnCorePack should usually stay true so packing a core fully releases faction claims before it is moved.");
        BREACH_RADIUS = builder.defineInRange("breachRadius", 7, 1, 32);
        BASE_CLAIM_CHUNKS = builder.defineInRange("baseClaimChunks", 1, 0, 4096);
        CORE_AMBIENT_EFFECT_MULTIPLIER = builder.defineInRange("ambientEffectMultiplier", 1.0D, 0.1D, 8.0D);
        CORE_RAID_EFFECT_MULTIPLIER = builder.defineInRange("raidEffectMultiplier", 1.25D, 0.1D, 8.0D);
        CORE_RELOCATION_COOLDOWN_SECONDS = builder.defineInRange("relocationCooldownSeconds", 300, 0, Integer.MAX_VALUE);
        UNCLAIM_ALL_ON_CORE_PACK = builder.define("unclaimAllOnCorePack", true);
        builder.pop();

        builder.push("claims");
        builder.comment(
                "Structured claim growth settings.",
                "claimsPerUpgrade is the amount of additional claim chunks unlocked by each upgrade level.",
                "smartClaimExpansion lets FCW route new claims around blocked/enemy chunks instead of failing on a rigid pattern.",
                "requireConnectedClaims keeps the final FCW territory as one connected shape when smart expansion is enabled.");
        CLAIMS_PER_UPGRADE = builder.defineInRange("claimsPerUpgrade", 4, 1, 512);
        MAX_CLAIM_RANGE = builder.defineInRange("maxClaimRange", 8, 0, 128);
        SMART_CLAIM_EXPANSION = builder.define("smartClaimExpansion", true);
        REQUIRE_CONNECTED_CLAIMS = builder.define("requireConnectedClaims", true);
        BLOCK_MANUAL_FTB_CLAIMS = builder.define("blockManualFtbClaims", true);
        ADMIN_BYPASS_STRUCTURED_CLAIMS = builder.define("adminBypassStructuredClaims", false);
        builder.pop();

        builder.push("raid");
        builder.comment(
                "Raid flow settings.",
                "Successful raids are intended to collapse the defending core and strip its territory.",
                "dropUpgradesOnSuccess controls whether invested claim upgrades are dropped back into the world as claim catalysts.",
                "worldSoundEvent and worldSoundVolume control the extra raid ambience emitted by the core itself.");
        ALLOW_ALLIED_RAIDING = builder.define("allowAlliedRaiding", false);
        RAID_FORCE_UNCLAIM_ON_SUCCESS = builder.define("forceUnclaimOnSuccess", true);
        RAID_SUSPEND_PROTECTION_ON_SUCCESS = builder.define("suspendProtectionOnSuccess", false);
        RAID_DROP_UPGRADES_ON_SUCCESS = builder.define("dropUpgradesOnSuccess", true);
        RAID_BASE_DURATION_SECONDS = builder.defineInRange("baseDurationSeconds", 300, 10, Integer.MAX_VALUE);
        RAID_PRESENCE_RADIUS = builder.defineInRange("presenceRadius", 16, 4, 96);
        RAID_LOGOUT_GRACE_SECONDS = builder.defineInRange("logoutGraceSeconds", 20, 0, Integer.MAX_VALUE);
        KEEP_ACTIVE_RAID_CHUNKS_LOADED = builder.define("keepActiveRaidChunksLoaded", true);
        RAID_WORLD_SOUND_EVENT = builder.define("worldSoundEvent", "minecraft:respawn_anchor_charge");
        RAID_WORLD_SOUND_VOLUME = builder.defineInRange("worldSoundVolume", 0.9D, 0.0D, 4.0D);
        builder.pop();

        builder.push("raidScaling");
        builder.comment(
                "Raid timer scaling settings based on defender online presence.");
        DEFENDER_ONLINE_SCALING_ENABLED = builder.define("enabled", true);
        DEFENDER_ONLINE_MIN_FACTION_SIZE = builder.defineInRange("minFactionSize", 3, 1, Integer.MAX_VALUE);
        DEFENDER_ONLINE_RATIO_THRESHOLD = builder.defineInRange("ratioThreshold", 0.5D, 0D, 1D);
        DEFENDER_ONLINE_MAX_MULTIPLIER = builder.defineInRange("maxPartialMultiplier", 2.5D, 1D, 100D);
        DEFENDER_ZERO_ONLINE_MULTIPLIER = builder.defineInRange("zeroOnlineMultiplier", 4D, 1D, 100D);
        DEFENDER_TIMER_MIN_SECONDS = builder.defineInRange("minFinalSeconds", 120, 1, Integer.MAX_VALUE);
        DEFENDER_TIMER_MAX_SECONDS = builder.defineInRange("maxFinalSeconds", 3600, 1, Integer.MAX_VALUE);
        DEFENDER_RECENT_JOINS_COUNT = builder.define("recentJoinsCount", false);
        DEFENDER_NEW_MEMBER_GRACE_SECONDS = builder.defineInRange("newMemberGraceSeconds", 3600, 0, Integer.MAX_VALUE);
        builder.pop();

        builder.push("raidItem");
        builder.comment(
                "Raid beacon crafting settings.",
                "baseCost is always applied when no exact recipe override exists for the current craft count.",
                "scalingCost is multiplied by the number of times that faction has already crafted raid beacons.",
                "exactLevelCosts lets you define a full recipe for an exact craft count using the format:",
                "craftCount|namespace:item=count,namespace:item=count",
                "Example: 0|minecraft:nether_star=1,minecraft:diamond=4",
                "Example: 3|minecraft:nether_star=2,minecraft:diamond=12,minecraft:echo_shard=4",
                "If an exactLevelCosts entry exists for the current craft count, it fully replaces baseCost plus scalingCost for that craft.");
        RAID_BASE_COST = builder.defineListAllowEmpty("baseCost", List.of("minecraft:nether_star=1", "minecraft:diamond=4"), FCWServerConfig::isItemEntry);
        RAID_SCALING_COST = builder.defineListAllowEmpty("scalingCost", List.of("minecraft:diamond=2"), FCWServerConfig::isItemEntry);
        RAID_EXACT_LEVEL_COSTS = builder.defineListAllowEmpty("exactLevelCosts", List.of(), FCWServerConfig::isExactRecipeEntry);
        builder.pop();

        SPEC = builder.build();
    }

    private FCWServerConfig() {
    }

    private static boolean isItemEntry(Object value) {
        return value instanceof String string && string.contains(":") && string.contains("=");
    }

    private static boolean isExactRecipeEntry(Object value) {
        return value instanceof String string && string.contains("|") && string.contains(":") && string.contains("=");
    }
}
