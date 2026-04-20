package com.fishguy129.fcw.core;

import com.fishguy129.fcw.FCWMod;
import com.fishguy129.fcw.block.FactionCoreBlock;
import com.fishguy129.fcw.blockentity.FactionCoreBlockEntity;
import com.fishguy129.fcw.claims.ClaimExpansionPlanner;
import com.fishguy129.fcw.compat.ftbchunks.FtbChunkCompat;
import com.fishguy129.fcw.compat.ftbteams.FtbTeamCompat;
import com.fishguy129.fcw.config.FCWServerConfig;
import com.fishguy129.fcw.data.FCWSavedData;
import com.fishguy129.fcw.item.FactionCoreItem;
import com.fishguy129.fcw.item.RaidBeaconItem;
import com.fishguy129.fcw.network.ClaimOutlineMessage;
import com.fishguy129.fcw.network.EnemyClaimOutlineMessage;
import com.fishguy129.fcw.menu.FactionCoreMenu;
import com.fishguy129.fcw.network.FCWNetwork;
import com.fishguy129.fcw.network.CoreRecipeSyncMessage;
import com.fishguy129.fcw.registry.FCWBlocks;
import com.fishguy129.fcw.registry.FCWItems;
import com.fishguy129.fcw.util.ItemCostHelper;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.Containers;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;

import java.util.*;
import java.util.stream.Collectors;

// Owns the server-side core lifecycle: placement, upgrades, claim syncing,
// packing, visuals, and the bits raids need to tear a core down cleanly. 
// How long did this take me you ask? A lot.
public class CoreManager {
    private static final ThreadLocal<MutationScope> MUTATION_SCOPE = new ThreadLocal<>();

    private final FtbTeamCompat teamCompat;
    private final FtbChunkCompat chunkCompat;
    private boolean suppressCoreRemoval;

    public CoreManager(FtbTeamCompat teamCompat, FtbChunkCompat chunkCompat) {
        this.teamCompat = teamCompat;
        this.chunkCompat = chunkCompat;
    }

    public FCWSavedData data(MinecraftServer server) {
        return FCWSavedData.get(server);
    }

    public Optional<FCWSavedData.CoreRecord> getCoreForTeam(MinecraftServer server, UUID teamId) {
        return data(server).getCore(teamId);
    }

    public boolean isInternalClaim(Team team, dev.ftb.mods.ftblibrary.math.ChunkDimPos pos, boolean claim) {
        MutationScope scope = MUTATION_SCOPE.get();
        if (scope == null || !scope.teamId.equals(team.getId())) {
            return false;
        }
        return claim ? scope.claims.contains(pos) : scope.unclaims.contains(pos);
    }

    public void openCoreMenu(ServerPlayer player, BlockPos pos) {
        openCoreMenu(player, pos, false);
    }

    public boolean openCoreMenu(ServerPlayer player, BlockPos pos, boolean silentFailure) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof FactionCoreBlockEntity coreBlockEntity) || coreBlockEntity.getTeamId() == null) {
            return false;
        }

        Team team = teamCompat.resolveTeamById(coreBlockEntity.getTeamId()).orElse(null);
        if (team == null || (!teamCompat.isMember(team, player.getUUID()) && !player.hasPermissions(2))) {
            if (!silentFailure) {
                player.displayClientMessage(Component.translatable("message.fcw.core.not_yours").withStyle(ChatFormatting.RED), false);
            }
            return false;
        }

        FCWSavedData.CoreRecord record = data(level.getServer()).getCore(team.getId()).orElse(null);
        if (record == null || record.packed()) {
            if (!silentFailure) {
                player.displayClientMessage(Component.translatable("message.fcw.core.packed").withStyle(ChatFormatting.RED), false);
            }
            return false;
        }

        ContainerData menuData = buildMenuData(level.getServer(), player, team, record);
        MenuProvider provider = new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("screen.fcw.faction_core.title");
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory, net.minecraft.world.entity.player.Player menuPlayer) {
                return new FactionCoreMenu(containerId, inventory, pos, teamCompat.displayName(team), menuData);
            }
        };

        NetworkHooks.openScreen(player, provider, buffer -> {
            buffer.writeBlockPos(pos);
            buffer.writeUtf(teamCompat.displayName(team));
            // Send the rendered recipe snapshots with the menu open packet so the client
            // screen can stay dumb and avoid a second sync path.
            List<ItemCostHelper.CostEntry> baseCosts = ItemCostHelper.parseEntries(FCWServerConfig.RAID_BASE_COST.get());
            List<ItemCostHelper.CostEntry> scalingCosts = ItemCostHelper.parseEntries(FCWServerConfig.RAID_SCALING_COST.get());
            Map<Integer, List<ItemCostHelper.CostEntry>> exactCosts = ItemCostHelper.parseLevelEntries(FCWServerConfig.RAID_EXACT_LEVEL_COSTS.get());
            int craftedCount = data(level.getServer()).raidCraftCount(team.getId());
            FactionCoreMenu.writeRecipeCosts(buffer, ItemCostHelper.sorted(ItemCostHelper.resolveRaidCosts(baseCosts, scalingCosts, exactCosts, craftedCount)));
            FactionCoreMenu.writeRecipeCosts(buffer, ItemCostHelper.sorted(ItemCostHelper.resolveRaidCosts(baseCosts, scalingCosts, exactCosts, craftedCount + 1)));
            FactionCoreMenu.writeItemStacks(buffer, record.hologramItems());
        });
        return true;
    }

    private ContainerData buildMenuData(MinecraftServer server, ServerPlayer player, Team team, FCWSavedData.CoreRecord record) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                FCWSavedData.CoreRecord liveRecord = data(server).getCore(team.getId()).orElse(record);
                int currentClaims = chunkCompat.claimCount(team);
                return switch (index) {
                    case FactionCoreMenu.DATA_ACTIVE -> liveRecord.active() ? 1 : 0;
                    case FactionCoreMenu.DATA_UPGRADES -> liveRecord.upgradeCount();
                    case FactionCoreMenu.DATA_TARGET_CLAIMS -> Math.max(currentClaims, allowedClaimCount(liveRecord));
                    case FactionCoreMenu.DATA_CURRENT_CLAIMS -> currentClaims;
                    case FactionCoreMenu.DATA_RAID_COST -> data(server).raidCraftCount(team.getId());
                    case FactionCoreMenu.DATA_LEADER -> teamCompat.isLeader(team, player.getUUID()) ? 1 : 0;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 6;
            }
        };
    }

    public void onCorePlaced(ServerPlayer player, BlockPos pos) {
        validatePostPlacement(player, pos);
    }

    public boolean validatePostPlacement(ServerPlayer player, BlockPos pos) {
        return validatePostPlacement(player, pos, ItemStack.EMPTY);
    }

    public boolean validatePostPlacement(ServerPlayer player, BlockPos pos, ItemStack placementStack) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }

        Team team = teamCompat.resolveFactionTeam(player).orElse(null);
        if (team == null) {
            player.displayClientMessage(Component.translatable("message.fcw.team.required").withStyle(ChatFormatting.RED), false);
            return false;
        }

        if (!teamCompat.isLeader(team, player.getUUID()) && !player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("message.fcw.core.leader_required").withStyle(ChatFormatting.RED), false);
            return false;
        }

        FCWSavedData savedData = data(level.getServer());
        FCWSavedData.CoreRecord existing = savedData.getCore(team.getId()).orElse(null);
        if (existing != null && !existing.packed() && !existing.pos().equals(pos)) {
            player.displayClientMessage(Component.translatable("message.fcw.core.already_exists").withStyle(ChatFormatting.RED), false);
            return false;
        }

        FCWSavedData.CoreRecord storedItemRecord = FactionCoreItem.readStoredCoreData(placementStack)
                .map(storedData -> storedData.toPlacementRecord(team.getId(), level.dimension(), pos))
                .orElse(null);
        FCWSavedData.CoreRecord seedRecord = existing != null ? existing : storedItemRecord;

        FCWSavedData.CoreRecord record = new FCWSavedData.CoreRecord(
                team.getId(),
                level.dimension(),
                pos,
                true,
                false,
                false,
                seedRecord == null ? 0 : seedRecord.upgradeCount(),
                seedRecord == null ? 0L : seedRecord.lastRelocationTick(),
                seedRecord == null ? FCWSavedData.CoreRecord.normalizedHologramItems(List.of()) : seedRecord.hologramItems(),
                seedRecord == null ? List.of() : seedRecord.savedClaimChunks()
        );

        dev.ftb.mods.ftblibrary.math.ChunkDimPos coreChunk = new dev.ftb.mods.ftblibrary.math.ChunkDimPos(level, pos);
        if (chunkCompat.isClaimedByOtherTeam(team, coreChunk)) {
            player.displayClientMessage(Component.translatable("message.fcw.core.core_chunk_claimed").withStyle(ChatFormatting.RED), false);
            return false;
        }

        boolean restoreSavedFootprint = shouldRestoreSavedFootprint(seedRecord, level, pos);
        List<dev.ftb.mods.ftblibrary.math.ChunkDimPos> desiredClaims;
        if (restoreSavedFootprint) {
            desiredClaims = savedClaimPositions(seedRecord);
            if (!restoreExactSavedClaims(player, level, team, record, desiredClaims, existing)) {
                return false;
            }
        } else {
            ClaimExpansionPlanner.ExpansionPlan plan = resolveClaimPlan(team, record, List.of());
            if (!plan.success()) {
                player.displayClientMessage(Component.translatable(plan.failureKey()).withStyle(ChatFormatting.RED), false);
                return false;
            }

            desiredClaims = plan.desiredClaims();
            if (!applyStructuredClaims(player, level, team, plan, record, existing)) {
                return false;
            }
        }

        if (!ensureCorePlacementClaims(player, level, team, record, existing, pos)) {
            return false;
        }

        // Only persist the core after the territory shape is accepted and claimed.
        List<Long> persistedClaimChunks = currentClaimChunkLongs(team);
        FCWSavedData.CoreRecord persistedRecord = record.withSavedClaimChunks(persistedClaimChunks.isEmpty() ? chunkLongs(desiredClaims) : persistedClaimChunks);
        savedData.putCore(persistedRecord);
        syncClaimLimit(team, persistedRecord);
        syncCoreBlock(level, persistedRecord);
        syncClaimState(team, level.getServer());
        playPlacementEffects(level, pos);
        player.displayClientMessage(Component.translatable("message.fcw.core.placed").withStyle(ChatFormatting.GREEN), false);
        return true;
    }

    public boolean applyClaimUpgrade(ServerPlayer player, BlockPos corePos) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }

        Team team = teamCompat.resolveFactionTeam(player).orElse(null);
        FCWSavedData.CoreRecord record = findOwnedCore(level.getServer(), team, corePos);
        if (team == null || record == null) {
            player.displayClientMessage(Component.translatable("message.fcw.core.not_yours").withStyle(ChatFormatting.RED), false);
            return false;
        }

        if (!record.active()) {
            player.displayClientMessage(Component.translatable("message.fcw.core.inactive").withStyle(ChatFormatting.RED), false);
            return false;
        }

        if (hasUpgradeCap() && record.upgradeCount() >= maxCoreUpgrades()) {
            player.displayClientMessage(Component.translatable("message.fcw.core.max_upgrades_reached").withStyle(ChatFormatting.RED), false);
            return false;
        }

        FCWSavedData.CoreRecord upgraded = record.withUpgradeCount(record.upgradeCount() + 1);
        ClaimExpansionPlanner.ExpansionPlan plan = resolveClaimPlan(team, upgraded, List.of());
        if (!plan.success()) {
            player.displayClientMessage(Component.translatable(plan.failureKey()).withStyle(ChatFormatting.RED), false);
            return false;
        }

        if (!applyStructuredClaims(player, level, team, plan, upgraded, record)) {
            return false;
        }

        FCWSavedData.CoreRecord persistedUpgrade = upgraded.withSavedClaimChunks(chunkLongs(plan.desiredClaims()));
        data(level.getServer()).putCore(persistedUpgrade);
        syncClaimLimit(team, persistedUpgrade);
        syncCoreBlock(level, persistedUpgrade);
        syncClaimState(team, level.getServer());
        int gainedClaims = plan.newClaims().size();
        playUpgradeEffects(level, corePos, gainedClaims);
        player.displayClientMessage(Component.translatable("message.fcw.claims.expanded", gainedClaims).withStyle(ChatFormatting.GREEN), false);
        return true;
    }

    public void tryCraftRaidItem(ServerPlayer player, BlockPos corePos) {
        Team team = teamCompat.resolveFactionTeam(player).orElse(null);
        if (team == null) {
            player.displayClientMessage(Component.translatable("message.fcw.team.required").withStyle(ChatFormatting.RED), false);
            return;
        }

        FCWSavedData.CoreRecord record = findOwnedCore(player.server, team, corePos);
        if (record == null || !record.active()) {
            player.displayClientMessage(Component.translatable("message.fcw.core.not_yours").withStyle(ChatFormatting.RED), false);
            return;
        }

        FCWSavedData savedData = data(player.server);
        int craftedCount = savedData.raidCraftCount(team.getId());
        List<ItemCostHelper.CostEntry> costs = ItemCostHelper.resolveRaidCosts(
                ItemCostHelper.parseEntries(FCWServerConfig.RAID_BASE_COST.get()),
                ItemCostHelper.parseEntries(FCWServerConfig.RAID_SCALING_COST.get()),
                ItemCostHelper.parseLevelEntries(FCWServerConfig.RAID_EXACT_LEVEL_COSTS.get()),
                craftedCount
        );

        if (!ItemCostHelper.hasCosts(player, costs)) {
            player.displayClientMessage(Component.translatable("message.fcw.raid.cost_missing").withStyle(ChatFormatting.RED), false);
            return;
        }

        ItemCostHelper.consume(player, costs);
        ItemStack stack = new ItemStack(FCWItems.RAID_BEACON.get());
        RaidBeaconItem.bind(stack, team.getId(), teamCompat.displayName(team));
        player.getInventory().placeItemBackInInventory(stack);
        savedData.incrementRaidCraftCount(team.getId());
        player.inventoryMenu.broadcastChanges();
        player.displayClientMessage(Component.translatable("message.fcw.raid.item_created").withStyle(ChatFormatting.GREEN), false);
    }

    public void setHologramDisplayItem(ServerPlayer player, BlockPos corePos, int slotIndex, boolean clear) {
        if (slotIndex < 0 || slotIndex >= 3) {
            return;
        }

        Team team = teamCompat.resolveFactionTeam(player).orElse(null);
        FCWSavedData.CoreRecord record = findOwnedCore(player.server, team, corePos);
        if (team == null || record == null) {
            player.displayClientMessage(Component.translatable("message.fcw.core.not_yours").withStyle(ChatFormatting.RED), false);
            return;
        }

        ItemStack imprint = clear ? ItemStack.EMPTY : player.getMainHandItem().copy();
        if (!clear && imprint.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.fcw.core.display_item_missing").withStyle(ChatFormatting.RED), false);
            return;
        }

        if (!imprint.isEmpty()) {
            imprint.setCount(1);
        }

        FCWSavedData.CoreRecord updated = record.withHologramItem(slotIndex, imprint);
        data(player.server).putCore(updated);

        ServerLevel level = player.server.getLevel(updated.dimension());
        if (level != null) {
            syncCoreBlock(level, updated);
        }

        player.displayClientMessage(
                clear
                        ? Component.translatable("message.fcw.core.display_item_cleared", slotIndex + 1).withStyle(ChatFormatting.YELLOW)
                        : Component.translatable("message.fcw.core.display_item_set", slotIndex + 1, imprint.getHoverName()).withStyle(ChatFormatting.GREEN),
                false
        );
    }

    public void packCore(ServerPlayer player, BlockPos corePos) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        Team team = teamCompat.resolveFactionTeam(player).orElse(null);
        FCWSavedData.CoreRecord record = findOwnedCore(level.getServer(), team, corePos);
        if (team == null || record == null) {
            player.displayClientMessage(Component.translatable("message.fcw.core.not_yours").withStyle(ChatFormatting.RED), false);
            return;
        }

        if (!teamCompat.isLeader(team, player.getUUID()) && !player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("message.fcw.core.leader_required").withStyle(ChatFormatting.RED), false);
            return;
        }

        long cooldownTicks = FCWServerConfig.CORE_RELOCATION_COOLDOWN_SECONDS.get() * 20L;
        long now = level.getGameTime();
        if (record.lastRelocationTick() > 0 && now - record.lastRelocationTick() < cooldownTicks) {
            player.displayClientMessage(Component.translatable("message.fcw.core.relocation_cooldown").withStyle(ChatFormatting.RED), false);
            return;
        }

        if (data(level.getServer()).findRaidForTeam(team.getId()).isPresent()) {
            player.displayClientMessage(Component.translatable("message.fcw.core.raid_locked").withStyle(ChatFormatting.RED), false);
            return;
        }

        suppressCoreRemoval = true;
        try {
            // Pull the block, keep the record, and optionally strip the claims depending on config.
            level.setBlock(record.pos(), Blocks.AIR.defaultBlockState(), 3);
            FCWSavedData.CoreRecord packed = capturePackedCoreRecord(record, team, now);
            data(level.getServer()).putCore(packed);
            if (FCWServerConfig.UNCLAIM_ALL_ON_CORE_PACK.get()) {
                clearFactionClaimsForPacking(player.createCommandSourceStack(), team);
            } else {
                syncClaimLimitWhilePacked(team);
            }
            ItemStack coreStack = buildPackedCoreStack(packed, team);
            player.getInventory().placeItemBackInInventory(coreStack);
            level.playSound(null, corePos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 0.95F, 1.08F);
            level.playSound(null, corePos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5F, 0.8F);
            player.displayClientMessage(Component.translatable("message.fcw.core.packed_item_given").withStyle(ChatFormatting.GREEN), false);
        } finally {
            suppressCoreRemoval = false;
        }
    }

    public void onCoreBlockRemoved(Level level, BlockPos pos) {
        if (suppressCoreRemoval || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        data(serverLevel.getServer()).allCores().stream()
                .filter(record -> record.dimension().equals(level.dimension()) && record.pos().equals(pos))
                .findFirst()
                .ifPresent(record -> {
                    FCWMod.LOGGER.warn("Faction core for team {} was removed outside the validated FCW flow; tearing down FCW state", record.teamId());
                    forceRemoveCoreState(serverLevel.getServer(), record.teamId());
                });
    }

    public boolean isWithinBreachZone(Level level, BlockPos pos) {
        int radius = FCWServerConfig.BREACH_RADIUS.get();
        int radiusSq = radius * radius;
        for (FCWSavedData.CoreRecord record : data(level.getServer()).allCores()) {
            if (!record.active() || record.packed() || !record.dimension().equals(level.dimension())) {
                continue;
            }
            if (record.pos().equals(pos)) {
                continue;
            }
            int dx = pos.getX() - record.pos().getX();
            int dz = pos.getZ() - record.pos().getZ();
            if (dx * dx + dz * dz <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    public int allowedClaimCount(FCWSavedData.CoreRecord record) {
        return FCWServerConfig.BASE_CLAIM_CHUNKS.get() + effectiveUpgradeCount(record.upgradeCount()) * FCWServerConfig.CLAIMS_PER_UPGRADE.get();
    }

    public void tick(MinecraftServer server) {
        long time = server.overworld().getGameTime();
        if (time % 20L == 0L) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                syncClaimOutline(player);
            }
        }
        for (FCWSavedData.CoreRecord record : List.copyOf(data(server).allCores())) {
            if (teamCompat.resolveTeamById(record.teamId()).isEmpty()) {
                forceRemoveCoreState(server, record.teamId());
            }
        }
        if (time % 10L != 0L) {
            return;
        }

        // I pulse visuals on a cheap cadence.
        for (FCWSavedData.CoreRecord record : data(server).allCores()) {
            if (!record.active() || record.packed()) {
                continue;
            }

            ServerLevel level = server.getLevel(record.dimension());
            if (level == null || !level.isLoaded(record.pos())) {
                continue;
            }

            syncCoreDisplayState(level, record);

            FCWSavedData.RaidRecord activeRaid = activeRaidForCore(server, record);
            if (activeRaid != null) {
                spawnRaidCorePulse(level, record, activeRaid, time);
            } else {
                spawnAmbientCorePulse(level, record, time);
            }
        }
    }

    public void syncClaimLimit(Team team, FCWSavedData.CoreRecord record) {
        if (record.packed()) {
            syncClaimLimitWhilePacked(team);
            return;
        }
        Team limitHolder = claimLimitHolder(team);
        int currentExtra = chunkCompat.currentExtraClaimChunks(limitHolder);
        int currentMax = chunkCompat.currentMaxClaimChunks(limitHolder);
        int baseLimit = Math.max(0, currentMax - currentExtra);
        int desired = allowedClaimCount(record);
        chunkCompat.setExtraClaimChunks(limitHolder, Math.max(0, desired - baseLimit));
        if (!limitHolder.getId().equals(team.getId())) {
            chunkCompat.setExtraClaimChunks(team, 0);
            chunkCompat.refreshLimits(team);
        }
    }

    public void syncCoreBlock(ServerLevel level, FCWSavedData.CoreRecord record) {
        if (record.packed()) {
            return;
        }

        BlockState state = level.getBlockState(record.pos());
        if (!state.is(FCWBlocks.FACTION_CORE.get())) {
            level.setBlock(record.pos(), FCWBlocks.FACTION_CORE.get().defaultBlockState().setValue(FactionCoreBlock.ACTIVE, record.active()), 3);
        } else if (state.getValue(FactionCoreBlock.ACTIVE) != record.active()) {
            level.setBlock(record.pos(), state.setValue(FactionCoreBlock.ACTIVE, record.active()), 3);
        }

        BlockEntity blockEntity = level.getBlockEntity(record.pos());
        if (blockEntity instanceof FactionCoreBlockEntity coreBlockEntity) {
            String displayName = teamCompat.resolveTeamById(record.teamId()).map(teamCompat::displayName).orElse(record.teamId().toString());
            Team team = teamCompat.resolveTeamById(record.teamId()).orElse(null);
            int currentClaims = team == null ? 0 : chunkCompat.claimCount(team);
            coreBlockEntity.syncFromRecord(
                    record,
                    displayName,
                    currentClaims,
                    Math.max(currentClaims, allowedClaimCount(record)),
                    data(level.getServer()).raidCraftCount(record.teamId()),
                    FCWServerConfig.BREACH_RADIUS.get()
            );
        }
    }

    public void resetCore(CommandSourceStack source, UUID teamId) {
        forceRemoveCoreState(source.getServer(), teamId);
    }

    public void resetCore(CommandSourceStack source, Team team) {
        if (team != null) {
            forceRemoveCoreState(source.getServer(), team.getId(), team);
        }
    }

    public void clearFactionClaims(CommandSourceStack source, Team team) {
        if (team == null) {
            return;
        }
        clearFactionClaimsForPacking(source, team);
    }

    public void dropPackedCoreForDisband(MinecraftServer server, Team team) {
        if (server == null || team == null) {
            return;
        }

        FCWSavedData.CoreRecord record = data(server).getCore(team.getId()).orElse(null);
        if (record == null || record.packed()) {
            return;
        }

        ServerLevel level = server.getLevel(record.dimension());
        if (level == null) {
            return;
        }

        long now = level.getGameTime();
        ItemStack preservedCore = buildPackedCoreStack(capturePackedCoreRecord(record, team, now), team);
        Containers.dropItemStack(level,
                record.pos().getX() + 0.5D,
                record.pos().getY() + 0.6D,
                record.pos().getZ() + 0.5D,
                preservedCore);
    }

    public void destroyCoreForRaid(ServerLevel level, FCWSavedData.CoreRecord record, int droppedUpgradeCount) {
        if (level == null || record == null) {
            return;
        }

        if (droppedUpgradeCount > 0) {
            dropUpgradeLoot(level, record.pos(), droppedUpgradeCount);
        }

        suppressCoreRemoval = true;
        try {
            level.setBlock(record.pos(), Blocks.AIR.defaultBlockState(), 3);
        } finally {
            suppressCoreRemoval = false;
        }
    }

    public boolean repairTeam(MinecraftServer server, UUID teamId) {
        FCWSavedData.CoreRecord record = data(server).getCore(teamId).orElse(null);
        Team team = teamCompat.resolveTeamById(teamId).orElse(null);
        if (record == null || team == null) {
            return false;
        }

        if (record.packed()) {
            syncClaimLimitWhilePacked(team);
            return true;
        }

        ServerLevel level = server.getLevel(record.dimension());
        if (level == null || !level.isLoaded(record.pos()) || !level.getBlockState(record.pos()).is(FCWBlocks.FACTION_CORE.get())) {
            data(server).removeCore(teamId);
            return false;
        }

        syncCoreBlock(level, record);
        syncClaimLimit(team, record);
        return true;
    }

    public List<Component> debugLines(MinecraftServer server, UUID teamId) {
        List<Component> lines = new ArrayList<>();
        Team team = teamCompat.resolveTeamById(teamId).orElse(null);
        FCWSavedData.CoreRecord core = data(server).getCore(teamId).orElse(null);
        lines.add(Component.literal("Team: " + (team == null ? "missing" : teamCompat.displayName(team))));
        if (core == null) {
            lines.add(Component.literal("Core: none"));
        } else {
            lines.add(Component.literal("Core pos: " + core.pos()));
            lines.add(Component.literal("Core dim: " + core.dimension().location()));
            lines.add(Component.literal("Core active: " + core.active()));
            lines.add(Component.literal("Protection suspended: " + core.protectionSuspended()));
            lines.add(Component.literal("Core packed: " + core.packed()));
            lines.add(Component.literal("Upgrade count: " + core.upgradeCount()));
            lines.add(Component.literal("Allowed claims: " + allowedClaimCount(core)));
        }
        lines.add(Component.literal("Raid craft count: " + data(server).raidCraftCount(teamId)));
        return lines;
    }

    private FCWSavedData.CoreRecord findOwnedCore(MinecraftServer server, Team team, BlockPos pos) {
        if (team == null) {
            return null;
        }
        FCWSavedData.CoreRecord record = data(server).getCore(team.getId()).orElse(null);
        return record != null && !record.packed() && record.pos().equals(pos) ? record : null;
    }

    private boolean applyStructuredClaims(ServerPlayer player, ServerLevel level, Team team, ClaimExpansionPlanner.ExpansionPlan plan,
                                          FCWSavedData.CoreRecord desiredRecord, FCWSavedData.CoreRecord rollbackRecord) {
        Set<dev.ftb.mods.ftblibrary.math.ChunkDimPos> additions = new LinkedHashSet<>(plan.newClaims());
        List<dev.ftb.mods.ftblibrary.math.ChunkDimPos> claimedNow = new ArrayList<>();
        syncClaimLimit(team, desiredRecord);

        // First ask FTB if every chunk is legal, then actually
        // apply them so we do not leave unfinished claim shapes behind.
        try (MutationScope ignored = MutationScope.open(team.getId(), additions, Set.of())) {
            for (dev.ftb.mods.ftblibrary.math.ChunkDimPos chunkPos : additions) {
                ClaimResult result = chunkCompat.claim(player.createCommandSourceStack(), team, chunkPos, true);
                if (result != null && !result.isSuccess()) {
                    rollbackStructuredClaims(player, team, rollbackRecord, claimedNow);
                    player.displayClientMessage(result.getMessage().withStyle(ChatFormatting.RED), false);
                    return false;
                }
            }

            for (dev.ftb.mods.ftblibrary.math.ChunkDimPos chunkPos : additions) {
                ClaimResult result = chunkCompat.claim(player.createCommandSourceStack(), team, chunkPos, false);
                if (result != null && !result.isSuccess()) {
                    FCWMod.LOGGER.error("Unexpected claim failure after successful FCW prevalidation for {} at {}", team.getId(), chunkPos);
                    rollbackStructuredClaims(player, team, rollbackRecord, claimedNow);
                    player.displayClientMessage(result.getMessage().withStyle(ChatFormatting.RED), false);
                    return false;
                }
                claimedNow.add(chunkPos);
            }
        }

        return true;
    }

    private void rollbackStructuredClaims(ServerPlayer player, Team team, FCWSavedData.CoreRecord rollbackRecord,
                                          List<dev.ftb.mods.ftblibrary.math.ChunkDimPos> claimedNow) {
        if (!claimedNow.isEmpty()) {
            Set<dev.ftb.mods.ftblibrary.math.ChunkDimPos> rollbackClaims = new HashSet<>(claimedNow);
            try (MutationScope ignored = MutationScope.open(team.getId(), Set.of(), rollbackClaims)) {
                for (dev.ftb.mods.ftblibrary.math.ChunkDimPos chunkPos : claimedNow) {
                    chunkCompat.unclaim(player.createCommandSourceStack(), team, chunkPos, false);
                }
            }
        }

        if (rollbackRecord == null || rollbackRecord.packed()) {
            syncClaimLimitWhilePacked(team);
        } else {
            syncClaimLimit(team, rollbackRecord);
        }
    }

    public void playPlacementEffects(ServerLevel level, BlockPos pos) {
        burst(level, pos, ParticleTypes.END_ROD, 30, 0.55D, 0.65D, 0.55D, 0.08D);
        burst(level, pos, ParticleTypes.ENCHANT, 45, 0.9D, 0.9D, 0.9D, 0.12D);
        burst(level, pos, new DustParticleOptions(new Vector3f(0.18F, 0.96F, 0.88F), 1.3F), 20, 0.45D, 0.35D, 0.45D, 0.02D);
        level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.1F, 1.12F);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.8F, 0.7F);
    }

    public void playUpgradeEffects(ServerLevel level, BlockPos pos, int gainedClaims) {
        burst(level, pos, ParticleTypes.ELECTRIC_SPARK, 25 + gainedClaims * 2, 0.55D, 0.75D, 0.55D, 0.1D);
        burst(level, pos, ParticleTypes.WAX_ON, 18, 0.4D, 0.4D, 0.4D, 0.03D);
        burst(level, pos, ParticleTypes.ENCHANT, 32, 0.8D, 0.9D, 0.8D, 0.08D);
        level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.0F, 1.35F);
        level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.BLOCKS, 0.75F, 1.2F);
    }

    public void playRaidStartedEffects(ServerLevel level, BlockPos pos) {
        burst(level, pos, ParticleTypes.SOUL_FIRE_FLAME, 40, 0.7D, 0.9D, 0.7D, 0.04D);
        burst(level, pos, ParticleTypes.CRIT, 30, 0.65D, 0.65D, 0.65D, 0.1D);
        burst(level, pos, ParticleTypes.ELECTRIC_SPARK, 25, 0.55D, 0.55D, 0.55D, 0.08D);
        burst(level, pos, ParticleTypes.SCULK_SOUL, 18, 0.7D, 0.4D, 0.7D, 0.03D);
        burst(level, pos, ParticleTypes.SWEEP_ATTACK, 8, 0.38D, 0.32D, 0.38D, 0.0D);
        spawnSwordClashParticles(level, pos, level.getGameTime(), 1.5D);
        level.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.0F, 0.75F);
        level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.BLOCKS, 0.8F, 0.9F);
        level.playSound(null, pos, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 0.65F, 0.55F);
        level.playSound(null, pos, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.BLOCKS, 1.0F, 0.75F);
        level.playSound(null, pos, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.55F, 1.45F);
    }

    public void playRaidResolvedEffects(ServerLevel level, BlockPos pos, boolean success) {
        if (success) {
            burst(level, pos, ParticleTypes.SOUL, 40, 0.85D, 1.1D, 0.85D, 0.08D);
            burst(level, pos, ParticleTypes.SMOKE, 35, 0.8D, 0.6D, 0.8D, 0.05D);
            level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 0.9F, 0.8F);
            level.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.0F, 0.55F);
        } else {
            burst(level, pos, ParticleTypes.TOTEM_OF_UNDYING, 28, 0.75D, 0.95D, 0.75D, 0.12D);
            burst(level, pos, ParticleTypes.END_ROD, 24, 0.6D, 0.8D, 0.6D, 0.06D);
            level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.1F, 1.25F);
            level.playSound(null, pos, SoundEvents.ALLAY_ITEM_TAKEN, SoundSource.BLOCKS, 0.85F, 0.9F);
        }
    }

    private void spawnAmbientCorePulse(ServerLevel level, FCWSavedData.CoreRecord record, long time) {
        BlockPos pos = record.pos();
        double ambientScale = FCWServerConfig.CORE_AMBIENT_EFFECT_MULTIPLIER.get();
        double y = pos.getY() + 0.92D + Math.sin(time / 14D) * 0.04D;
        float hue = record.protectionSuspended() ? 0.95F : 0.52F;
        float green = record.protectionSuspended() ? 0.22F : 0.92F;
        float blue = record.protectionSuspended() ? 0.22F : 0.82F;

        level.sendParticles(new DustParticleOptions(new Vector3f(hue, green, blue), 0.9F),
                pos.getX() + 0.5D, y, pos.getZ() + 0.5D,
                scaledCount(2, ambientScale), 0.12D, 0.04D, 0.12D, 0.01D);
        level.sendParticles(ParticleTypes.END_ROD,
                pos.getX() + 0.5D, y + 0.08D, pos.getZ() + 0.5D,
                scaledCount(1, ambientScale), 0.16D, 0.01D, 0.16D, 0.0D);

        if (time % 40L == 0L) {
            int points = scaledCount(6, ambientScale);
            for (int i = 0; i < points; i++) {
                double angle = (Math.PI * 2D * i / points) + (time / 20D);
                double radius = 0.55D + 0.05D * Math.sin((time + i * 3D) / 8D);
                double x = pos.getX() + 0.5D + Math.cos(angle) * radius;
                double z = pos.getZ() + 0.5D + Math.sin(angle) * radius;
                level.sendParticles(ParticleTypes.ENCHANT, x, pos.getY() + 0.16D, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }

        if (time % 20L == 0L) {
            spawnBaseHalo(level, pos, false, ambientScale, time);
        }
        if (time % 15L == 0L) {
            spawnGroundSigil(level, pos, false, ambientScale, time);
        }
        if (time % 30L == 0L) {
            spawnZoneRingParticles(level, pos, FCWServerConfig.BREACH_RADIUS.get(), false, time);
        }
    }

    private void spawnRaidCorePulse(ServerLevel level, FCWSavedData.CoreRecord record, FCWSavedData.RaidRecord raid, long time) {
        BlockPos pos = record.pos();
        double raidScale = FCWServerConfig.CORE_RAID_EFFECT_MULTIPLIER.get();
        float progress = raid.requiredTicks() <= 0L ? 1F : Mth.clamp((float) raid.progressTicks() / (float) raid.requiredTicks(), 0F, 1F);
        level.sendParticles(new DustParticleOptions(new Vector3f(0.98F, 0.18F + (0.2F * (1.0F - progress)), 0.22F), 1.2F),
                pos.getX() + 0.5D, pos.getY() + 0.98D, pos.getZ() + 0.5D,
                scaledCount(5, raidScale), 0.22D, 0.14D, 0.22D, 0.01D);
        level.sendParticles(new DustParticleOptions(new Vector3f(0.26F, 0.58F, 1.0F), 1.0F),
                pos.getX() + 0.5D, pos.getY() + 0.86D, pos.getZ() + 0.5D,
                scaledCount(4, raidScale), 0.18D, 0.12D, 0.18D, 0.01D);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                pos.getX() + 0.5D, pos.getY() + 0.82D, pos.getZ() + 0.5D,
                scaledCount(2, raidScale), 0.24D, 0.06D, 0.24D, 0.002D);

        if (time % 20L == 0L) {
            spawnZoneRingParticles(level, pos, FCWServerConfig.BREACH_RADIUS.get(), true, time);
        }
        if (time % 10L == 0L) {
            spawnBaseHalo(level, pos, true, raidScale, time);
        }
        if (time % 8L == 0L) {
            spawnGroundSigil(level, pos, true, raidScale, time);
        }
        if (time % 6L == 0L) {
            spawnSwordClashParticles(level, pos, time, raidScale);
        }
        if (time % 80L == 0L) {
            level.playSound(null, pos, resolveConfiguredSound(FCWServerConfig.RAID_WORLD_SOUND_EVENT.get(), SoundEvents.RESPAWN_ANCHOR_CHARGE),
                    SoundSource.BLOCKS, FCWServerConfig.RAID_WORLD_SOUND_VOLUME.get().floatValue(), 0.85F + (progress * 0.25F));
        }
    }

    private void syncCoreDisplayState(ServerLevel level, FCWSavedData.CoreRecord record) {
        BlockEntity blockEntity = level.getBlockEntity(record.pos());
        if (!(blockEntity instanceof FactionCoreBlockEntity coreBlockEntity)) {
            return;
        }

        Team team = teamCompat.resolveTeamById(record.teamId()).orElse(null);
        int currentClaims = team == null ? 0 : chunkCompat.claimCount(team);
        String displayName = team == null ? record.teamId().toString() : teamCompat.displayName(team);
        coreBlockEntity.syncFromRecord(
                record,
                displayName,
                currentClaims,
                Math.max(currentClaims, allowedClaimCount(record)),
                data(level.getServer()).raidCraftCount(record.teamId()),
                FCWServerConfig.BREACH_RADIUS.get()
        );
    }

    private FCWSavedData.RaidRecord activeRaidForCore(MinecraftServer server, FCWSavedData.CoreRecord record) {
        for (FCWSavedData.RaidRecord raid : data(server).activeRaids()) {
            if (raid.dimension().equals(record.dimension()) && raid.corePos().equals(record.pos())) {
                return raid;
            }
        }
        return null;
    }

    private void clearFactionClaimsForPacking(CommandSourceStack source, Team team) {
        Set<dev.ftb.mods.ftblibrary.math.ChunkDimPos> claimedPositions = new HashSet<>();
        for (dev.ftb.mods.ftbchunks.api.ClaimedChunk chunk : List.copyOf(chunkCompat.claimedChunks(team))) {
            claimedPositions.add(chunk.getPos());
        }

        // I mark every outgoing unclaim as internal so the manual claim guard does not
        // trip over FCW cleaning up its own territory.
        try (MutationScope ignored = MutationScope.open(team.getId(), Set.of(), claimedPositions)) {
            chunkCompat.clearClaims(source, team);
        }

        syncClaimLimitWhilePacked(team);
        syncClaimState(team, source.getServer());
    }

    private ClaimExpansionPlanner.ExpansionPlan resolveClaimPlan(Team team, FCWSavedData.CoreRecord desiredRecord,
                                                                 List<dev.ftb.mods.ftblibrary.math.ChunkDimPos> savedFootprint) {
        if (savedFootprint != null && !savedFootprint.isEmpty()) {
            return ClaimExpansionPlanner.planRestoration(
                    desiredRecord,
                    chunkCompat.claimedChunks(team),
                    savedFootprint,
                    allowedClaimCount(desiredRecord),
                    FCWServerConfig.MAX_CLAIM_RANGE.get(),
                    pos -> chunkCompat.isClaimedByOtherTeam(team, pos),
                    FCWServerConfig.REQUIRE_CONNECTED_CLAIMS.get()
            );
        }
        return ClaimExpansionPlanner.plan(
                desiredRecord,
                chunkCompat.claimedChunks(team),
                allowedClaimCount(desiredRecord),
                FCWServerConfig.MAX_CLAIM_RANGE.get(),
                pos -> chunkCompat.isClaimedByOtherTeam(team, pos),
                FCWServerConfig.SMART_CLAIM_EXPANSION.get(),
                FCWServerConfig.REQUIRE_CONNECTED_CLAIMS.get()
        );
    }

    private boolean shouldRestoreSavedFootprint(FCWSavedData.CoreRecord existing, ServerLevel level, BlockPos pos) {
        // Packed core saves chunk list kinda like a ghost outline. If the new
        // core lands inside that old territory, restore the exact same claims.
        if (existing == null || !existing.dimension().equals(level.dimension()) || existing.savedClaimChunks().isEmpty()) {
            return false;
        }
        ChunkPos chunkPos = new ChunkPos(pos);
        return existing.savedClaimChunks().contains(ChunkPos.asLong(chunkPos.x, chunkPos.z));
    }

    private List<dev.ftb.mods.ftblibrary.math.ChunkDimPos> savedClaimPositions(FCWSavedData.CoreRecord record) {
        if (record == null || record.savedClaimChunks().isEmpty()) {
            return List.of();
        }
        List<dev.ftb.mods.ftblibrary.math.ChunkDimPos> positions = new ArrayList<>(record.savedClaimChunks().size());
        for (Long chunkLong : record.savedClaimChunks()) {
            ChunkPos chunkPos = new ChunkPos(chunkLong);
            positions.add(new dev.ftb.mods.ftblibrary.math.ChunkDimPos(record.dimension(), chunkPos.x, chunkPos.z));
        }
        return List.copyOf(positions);
    }

    private boolean restoreExactSavedClaims(ServerPlayer player, ServerLevel level, Team team, FCWSavedData.CoreRecord desiredRecord,
                                            Collection<dev.ftb.mods.ftblibrary.math.ChunkDimPos> savedClaims,
                                            FCWSavedData.CoreRecord rollbackRecord) {
        LinkedHashSet<dev.ftb.mods.ftblibrary.math.ChunkDimPos> desiredClaims = new LinkedHashSet<>();
        for (dev.ftb.mods.ftblibrary.math.ChunkDimPos pos : savedClaims) {
            if (pos != null && pos.dimension().equals(desiredRecord.dimension())) {
                desiredClaims.add(pos);
            }
        }

        dev.ftb.mods.ftblibrary.math.ChunkDimPos coreChunk = new dev.ftb.mods.ftblibrary.math.ChunkDimPos(level, desiredRecord.pos());
        desiredClaims.add(coreChunk);

        LinkedHashSet<dev.ftb.mods.ftblibrary.math.ChunkDimPos> additions = new LinkedHashSet<>();
        for (dev.ftb.mods.ftblibrary.math.ChunkDimPos pos : desiredClaims) {
            if (!chunkCompat.isClaimedByTeam(team, pos)) {
                additions.add(pos);
            }
        }

        syncClaimLimit(team, desiredRecord);
        if (additions.isEmpty()) {
            return true;
        }

        List<dev.ftb.mods.ftblibrary.math.ChunkDimPos> claimedNow = new ArrayList<>();
        try (MutationScope ignored = MutationScope.open(team.getId(), additions, Set.of())) {
            for (dev.ftb.mods.ftblibrary.math.ChunkDimPos chunkPos : additions) {
                ClaimResult result = chunkCompat.claim(player.createCommandSourceStack(), team, chunkPos, true);
                if (result != null && !result.isSuccess()) {
                    rollbackStructuredClaims(player, team, rollbackRecord, claimedNow);
                    player.displayClientMessage(result.getMessage().withStyle(ChatFormatting.RED), false);
                    return false;
                }
            }

            for (dev.ftb.mods.ftblibrary.math.ChunkDimPos chunkPos : additions) {
                ClaimResult result = chunkCompat.claim(player.createCommandSourceStack(), team, chunkPos, false);
                if (result != null && !result.isSuccess()) {
                    FCWMod.LOGGER.error("Failed to restore saved FCW claim footprint for {} at {}", team.getId(), chunkPos);
                    rollbackStructuredClaims(player, team, rollbackRecord, claimedNow);
                    player.displayClientMessage(result.getMessage().withStyle(ChatFormatting.RED), false);
                    return false;
                }
                claimedNow.add(chunkPos);
            }
        }

        return true;
    }

    private List<Long> currentClaimChunkLongs(Team team) {
        List<Long> chunks = new ArrayList<>();
        for (dev.ftb.mods.ftbchunks.api.ClaimedChunk chunk : chunkCompat.claimedChunks(team)) {
            chunks.add(ChunkPos.asLong(chunk.getPos().x(), chunk.getPos().z()));
        }
        return List.copyOf(chunks);
    }

    private List<Long> currentClaimChunkLongs(Team team, net.minecraft.resources.ResourceKey<Level> dimension) {
        List<Long> chunks = new ArrayList<>();
        for (dev.ftb.mods.ftbchunks.api.ClaimedChunk chunk : chunkCompat.claimedChunks(team)) {
            if (chunk.getPos().dimension().equals(dimension)) {
                chunks.add(ChunkPos.asLong(chunk.getPos().x(), chunk.getPos().z()));
            }
        }
        return List.copyOf(chunks);
    }

    private FCWSavedData.CoreRecord capturePackedCoreRecord(FCWSavedData.CoreRecord record, Team team, long packedAt) {
        return record.withSavedClaimChunks(resolvePackedClaimChunks(record, team)).withPacked(true, packedAt);
    }

    private ItemStack buildPackedCoreStack(FCWSavedData.CoreRecord record, Team team) {
        ItemStack stack = new ItemStack(FCWItems.FACTION_CORE.get());
        FactionCoreItem.storePackedCoreData(stack, record, team == null ? null : teamCompat.displayName(team));
        return stack;
    }

    private List<Long> resolvePackedClaimChunks(FCWSavedData.CoreRecord record, Team team) {
        LinkedHashSet<Long> packedChunks = new LinkedHashSet<>();
        if (record != null) {
            packedChunks.addAll(record.savedClaimChunks());
            packedChunks.add(ChunkPos.asLong(new ChunkPos(record.pos()).x, new ChunkPos(record.pos()).z));
        }
        if (team != null) {
            packedChunks.addAll(currentClaimChunkLongs(team));
        }
        return List.copyOf(packedChunks);
    }

    private List<Long> chunkLongs(Collection<dev.ftb.mods.ftblibrary.math.ChunkDimPos> positions) {
        List<Long> chunks = new ArrayList<>(positions.size());
        for (dev.ftb.mods.ftblibrary.math.ChunkDimPos pos : positions) {
            chunks.add(ChunkPos.asLong(pos.x(), pos.z()));
        }
        return List.copyOf(chunks);
    }

    private Set<dev.ftb.mods.ftblibrary.math.ChunkDimPos> claimedChunkPositions(UUID teamId) {
        Set<dev.ftb.mods.ftblibrary.math.ChunkDimPos> positions = new HashSet<>();
        for (dev.ftb.mods.ftbchunks.api.ClaimedChunk chunk : List.copyOf(chunkCompat.claimedChunks(teamId))) {
            positions.add(chunk.getPos());
        }
        return Set.copyOf(positions);
    }

    private void clearClaimPositions(CommandSourceStack source, Collection<dev.ftb.mods.ftblibrary.math.ChunkDimPos> positions) {
        Map<UUID, Team> ownerTeams = new LinkedHashMap<>();
        Map<UUID, Set<dev.ftb.mods.ftblibrary.math.ChunkDimPos>> positionsByOwner = new LinkedHashMap<>();

        for (dev.ftb.mods.ftblibrary.math.ChunkDimPos pos : positions) {
            dev.ftb.mods.ftbchunks.api.ClaimedChunk chunk = chunkCompat.getClaim(pos);
            if (chunk == null) {
                continue;
            }

            Team ownerTeam = chunk.getTeamData().getTeam();
            if (ownerTeam == null) {
                continue;
            }

            ownerTeams.putIfAbsent(ownerTeam.getId(), ownerTeam);
            positionsByOwner.computeIfAbsent(ownerTeam.getId(), ignored -> new LinkedHashSet<>()).add(pos);
        }

        for (Map.Entry<UUID, Set<dev.ftb.mods.ftblibrary.math.ChunkDimPos>> entry : positionsByOwner.entrySet()) {
            Team ownerTeam = ownerTeams.get(entry.getKey());
            if (ownerTeam == null || entry.getValue().isEmpty()) {
                continue;
            }

            try (MutationScope ignored = MutationScope.open(ownerTeam.getId(), Set.of(), Set.copyOf(entry.getValue()))) {
                for (dev.ftb.mods.ftblibrary.math.ChunkDimPos pos : entry.getValue()) {
                    chunkCompat.unclaim(source, ownerTeam, pos, false);
                }
            }
        }
    }

    private void syncClaimState(Team team, MinecraftServer server) {
        chunkCompat.syncClaimsToClients(team, server);
        syncClaimOutlines(team, server);
        syncEnemyClaimOutlinesToAll(server);
    }

    private void forceRemoveCoreState(MinecraftServer server, UUID teamId) {
        forceRemoveCoreState(server, teamId, null);
    }

    private void forceRemoveCoreState(MinecraftServer server, UUID teamId, Team knownTeam) {
        FCWSavedData.CoreRecord record = data(server).getCore(teamId).orElse(null);
        if (record != null) {
            ServerLevel level = server.getLevel(record.dimension());
            if (level != null && level.getBlockState(record.pos()).is(FCWBlocks.FACTION_CORE.get())) {
                suppressCoreRemoval = true;
                try {
                    level.setBlock(record.pos(), Blocks.AIR.defaultBlockState(), 3);
                } finally {
                    suppressCoreRemoval = false;
                }
            }
        }

        Team team = knownTeam != null ? knownTeam : teamCompat.resolveTeamById(teamId).orElse(null);
        Set<dev.ftb.mods.ftblibrary.math.ChunkDimPos> unclaims = new LinkedHashSet<>(savedClaimPositions(record));
        unclaims.addAll(claimedChunkPositions(teamId));
        if (team != null) {
            for (dev.ftb.mods.ftbchunks.api.ClaimedChunk chunk : List.copyOf(chunkCompat.claimedChunks(team))) {
                unclaims.add(chunk.getPos());
            }
        }
        if (!unclaims.isEmpty()) {
            clearClaimPositions(server.createCommandSourceStack(), unclaims);
        }
        if (team != null) {
            syncClaimLimitWhilePacked(team);
            if (team.getMembers().isEmpty()) {
                chunkCompat.purgeTeamData(team);
                syncClaimOutlines(team, server);
            } else {
                syncClaimState(team, server);
            }
        }

        data(server).removeCore(teamId);
        data(server).clearTeamMeta(teamId);
    }

    private boolean ensureCorePlacementClaims(ServerPlayer player, ServerLevel level, Team team, FCWSavedData.CoreRecord desiredRecord,
                                              FCWSavedData.CoreRecord rollbackRecord, BlockPos corePos) {
        dev.ftb.mods.ftblibrary.math.ChunkDimPos coreChunk = new dev.ftb.mods.ftblibrary.math.ChunkDimPos(level, corePos);
        if (chunkCompat.isClaimedByTeam(team, coreChunk) && chunkCompat.claimCount(team) > 0) {
            return true;
        }

        if (!chunkCompat.isClaimedByTeam(team, coreChunk)) {
            try (MutationScope ignored = MutationScope.open(team.getId(), Set.of(coreChunk), Set.of())) {
                ClaimResult check = chunkCompat.claim(player.createCommandSourceStack(), team, coreChunk, true);
                if (check != null && !check.isSuccess()) {
                    player.displayClientMessage(check.getMessage().withStyle(ChatFormatting.RED), false);
                    return false;
                }
                ClaimResult applied = chunkCompat.claim(player.createCommandSourceStack(), team, coreChunk, false);
                if (applied != null && !applied.isSuccess()) {
                    player.displayClientMessage(applied.getMessage().withStyle(ChatFormatting.RED), false);
                    return false;
                }
            }
        }

        if (chunkCompat.claimCount(team) > 0) {
            return true;
        }

        ClaimExpansionPlanner.ExpansionPlan fallbackPlan = ClaimExpansionPlanner.plan(
                desiredRecord,
                chunkCompat.claimedChunks(team),
                allowedClaimCount(desiredRecord),
                FCWServerConfig.MAX_CLAIM_RANGE.get(),
                pos -> chunkCompat.isClaimedByOtherTeam(team, pos),
                FCWServerConfig.SMART_CLAIM_EXPANSION.get(),
                FCWServerConfig.REQUIRE_CONNECTED_CLAIMS.get()
        );
        if (!fallbackPlan.success()) {
            player.displayClientMessage(Component.translatable(fallbackPlan.failureKey()).withStyle(ChatFormatting.RED), false);
            return false;
        }
        if (!applyStructuredClaims(player, level, team, fallbackPlan, desiredRecord, rollbackRecord)) {
            return false;
        }
        return chunkCompat.claimCount(team) > 0;
    }

    public void syncClaimOutline(ServerPlayer player) {
        Team team = teamCompat.resolveFactionTeam(player).orElse(null);
        if (team == null) {
            FCWNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), ClaimOutlineMessage.clear());
            return;
        }

        FCWSavedData.CoreRecord record = data(player.server).getCore(team.getId()).orElse(null);
        List<Long> chunks = currentClaimChunkLongs(team, player.level().dimension());
        FCWNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                chunks.isEmpty()
                        ? ClaimOutlineMessage.clear()
                        : new ClaimOutlineMessage(
                                player.level().dimension().location().toString(),
                                record == null ? player.blockPosition().getY() : record.pos().getY(),
                                chunks
                        ));
    }

    public void syncClaimOutlines(Team team, MinecraftServer server) {
        if (team == null || server == null) {
            return;
        }
        for (ServerPlayer player : teamCompat.onlineMembers(team)) {
            syncClaimOutline(player);
        }
    }

    public void syncEnemyClaimOutlines(ServerPlayer player) {
        if (!FCWServerConfig.BORDER_FENCE_VISIBLE_TO_ENEMIES.get()) {
            FCWNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), EnemyClaimOutlineMessage.clear());
            return;
        }

        Team playerTeam = teamCompat.resolveFactionTeam(player).orElse(null);
        List<EnemyClaimOutlineMessage.EnemyOutline> enemyOutlines = new ArrayList<>();

        for (FCWSavedData.CoreRecord record : data(player.server).allCores()) {
            if (!record.active() || record.packed()) {
                continue;
            }
            if (playerTeam != null && record.teamId().equals(playerTeam.getId())) {
                continue;
            }
            Team coreTeam = teamCompat.resolveTeamById(record.teamId()).orElse(null);
            if (coreTeam == null) {
                continue;
            }
            List<Long> chunks = currentClaimChunkLongs(coreTeam, player.level().dimension());
            if (!chunks.isEmpty()) {
                enemyOutlines.add(new EnemyClaimOutlineMessage.EnemyOutline(
                        player.level().dimension().location().toString(),
                        record.pos().getY(),
                        chunks
                ));
            }
        }

        FCWNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                enemyOutlines.isEmpty()
                        ? EnemyClaimOutlineMessage.clear()
                        : new EnemyClaimOutlineMessage(enemyOutlines));
    }

    public void syncEnemyClaimOutlinesToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncEnemyClaimOutlines(player);
        }
    }

    private void dropUpgradeLoot(ServerLevel level, BlockPos pos, int upgradeCount) {
        int remaining = Math.max(0, upgradeCount);
        while (remaining > 0) {
            int stackSize = Math.min(remaining, FCWItems.CLAIM_CATALYST.get().getMaxStackSize());
            Containers.dropItemStack(level,
                    pos.getX() + 0.5D,
                    pos.getY() + 0.8D,
                    pos.getZ() + 0.5D,
                    new ItemStack(FCWItems.CLAIM_CATALYST.get(), stackSize));
            remaining -= stackSize;
        }
    }

    private void syncClaimLimitWhilePacked(Team team) {
        Team limitHolder = claimLimitHolder(team);
        chunkCompat.setExtraClaimChunks(limitHolder, 0);
        chunkCompat.refreshLimits(limitHolder);
        if (!limitHolder.getId().equals(team.getId())) {
            chunkCompat.setExtraClaimChunks(team, 0);
            chunkCompat.refreshLimits(team);
        }
    }

    private void spawnZoneRingParticles(ServerLevel level, BlockPos pos, int radius, boolean raid, long time) {
        for (int i = 0; i < 18; i++) {
            double angle = (Math.PI * 2D * i / 18D) + (time * 0.03D);
            double x = pos.getX() + 0.5D + Math.cos(angle) * radius;
            double z = pos.getZ() + 0.5D + Math.sin(angle) * radius;
            if (raid) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, pos.getY() + 0.08D, z, 1, 0.03D, 0.01D, 0.03D, 0.005D);
                level.sendParticles(new DustParticleOptions(new Vector3f(0.88F, 0.22F, 0.26F), 0.8F), x, pos.getY() + 0.03D, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            } else {
                level.sendParticles(new DustParticleOptions(new Vector3f(0.24F, 0.86F, 0.95F), 0.65F), x, pos.getY() + 0.03D, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    private void spawnBaseHalo(ServerLevel level, BlockPos pos, boolean raid, double intensity, long time) {
        int points = scaledCount(10, intensity);
        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2D * i / points) + (time * 0.06D);
            double radius = raid ? 0.85D : 0.62D;
            double x = pos.getX() + 0.5D + Math.cos(angle) * radius;
            double z = pos.getZ() + 0.5D + Math.sin(angle) * radius;
            double y = pos.getY() + (raid ? 0.18D : 0.12D);
            if (raid) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 1, 0.01D, 0.01D, 0.01D, 0.01D);
                level.sendParticles(new DustParticleOptions(new Vector3f(0.95F, 0.28F, 0.24F), 0.85F), x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            } else {
                level.sendParticles(new DustParticleOptions(new Vector3f(0.20F, 0.86F, 0.94F), 0.7F), x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    private void spawnGroundSigil(ServerLevel level, BlockPos pos, boolean raid, double intensity, long time) {
        int points = scaledCount(14, intensity);
        double innerRadius = raid ? 0.78D : 0.58D;
        double outerRadius = raid ? 1.08D : 0.82D;
        double y = pos.getY() + 0.05D;
        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2D * i / points) - (time * (raid ? 0.09D : 0.05D));
            double innerX = pos.getX() + 0.5D + Math.cos(angle) * innerRadius;
            double innerZ = pos.getZ() + 0.5D + Math.sin(angle) * innerRadius;
            double outerX = pos.getX() + 0.5D + Math.cos(angle) * outerRadius;
            double outerZ = pos.getZ() + 0.5D + Math.sin(angle) * outerRadius;
            if (raid) {
                level.sendParticles(new DustParticleOptions(new Vector3f(0.98F, 0.32F, 0.28F), 0.72F), innerX, y, innerZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, outerX, y + 0.02D, outerZ, 1, 0.01D, 0.0D, 0.01D, 0.001D);
            } else {
                level.sendParticles(new DustParticleOptions(new Vector3f(0.25F, 0.9F, 0.98F), 0.58F), innerX, y, innerZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                level.sendParticles(ParticleTypes.ENCHANT, outerX, y + 0.03D, outerZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    private void spawnSwordClashParticles(ServerLevel level, BlockPos pos, long time, double intensity) {
        double sweep = 0.32D + (0.1D * Math.sin(time * 0.12D));
        double arcHeight = pos.getY() + 1.08D + (Math.sin(time * 0.18D) * 0.08D);
        for (int side = -1; side <= 1; side += 2) {
            double x = pos.getX() + 0.5D + (side * sweep);
            double z = pos.getZ() + 0.5D - (side * sweep * 0.65D);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, x, arcHeight, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            level.sendParticles(new DustParticleOptions(new Vector3f(side < 0 ? 0.92F : 0.24F, side < 0 ? 0.32F : 0.64F, 1.0F), 0.85F),
                    x, arcHeight, z, scaledCount(2, intensity), 0.05D, 0.04D, 0.05D, 0.005D);
        }

        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                pos.getX() + 0.5D, pos.getY() + 1.16D, pos.getZ() + 0.5D,
                scaledCount(5, intensity), 0.14D, 0.12D, 0.14D, 0.04D);
        level.sendParticles(ParticleTypes.CRIT,
                pos.getX() + 0.5D, pos.getY() + 1.12D, pos.getZ() + 0.5D,
                scaledCount(7, intensity), 0.18D, 0.12D, 0.18D, 0.08D);
    }

    private int scaledCount(int base, double scale) {
        return Math.max(1, Mth.ceil(base * scale));
    }

    private int effectiveUpgradeCount(int upgradeCount) {
        return hasUpgradeCap() ? Math.min(upgradeCount, maxCoreUpgrades()) : upgradeCount;
    }

    private boolean hasUpgradeCap() {
        return FCWServerConfig.MAX_CORE_UPGRADES.get() >= 0;
    }

    private int maxCoreUpgrades() {
        return FCWServerConfig.MAX_CORE_UPGRADES.get();
    }

    private net.minecraft.sounds.SoundEvent resolveConfiguredSound(String id, net.minecraft.sounds.SoundEvent fallback) {
        net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (location == null) {
            return fallback;
        }
        net.minecraft.sounds.SoundEvent soundEvent = ForgeRegistries.SOUND_EVENTS.getValue(location);
        return soundEvent == null ? fallback : soundEvent;
    }

    private <T extends net.minecraft.core.particles.ParticleOptions> void burst(ServerLevel level, BlockPos pos, T particle, int count, double dx, double dy, double dz, double speed) {
        level.sendParticles(particle, pos.getX() + 0.5D, pos.getY() + 0.6D, pos.getZ() + 0.5D, count, dx, dy, dz, speed);
    }

    private Team claimLimitHolder(Team team) {
        if (team != null && team.isPartyTeam()) {
            return teamCompat.resolvePersonalTeam(team.getOwner()).orElse(team);
        }
        return team;
    }

    private record MutationScope(UUID teamId, Set<dev.ftb.mods.ftblibrary.math.ChunkDimPos> claims,
                                 Set<dev.ftb.mods.ftblibrary.math.ChunkDimPos> unclaims) implements AutoCloseable {
        private static MutationScope open(UUID teamId, Set<dev.ftb.mods.ftblibrary.math.ChunkDimPos> claims,
                                          Set<dev.ftb.mods.ftblibrary.math.ChunkDimPos> unclaims) {
            // Mark FCW-owned claim changes so our event hook can ignore them.
            MutationScope scope = new MutationScope(teamId, claims, unclaims);
            MUTATION_SCOPE.set(scope);
            return scope;
        }

        @Override
        public void close() {
            MUTATION_SCOPE.remove();
        }
    }

    public void syncRecipesToPlayer(ServerPlayer player, BlockPos corePos) {
        Team team = teamCompat.resolveFactionTeam(player).orElse(null);
        if (team == null) return;
        FCWSavedData savedData = data(player.server);
        int craftedCount = savedData.raidCraftCount(team.getId());
        List<ItemCostHelper.CostEntry> base = ItemCostHelper.parseEntries(FCWServerConfig.RAID_BASE_COST.get());
        List<ItemCostHelper.CostEntry> scaling = ItemCostHelper.parseEntries(FCWServerConfig.RAID_SCALING_COST.get());
        Map<Integer, List<ItemCostHelper.CostEntry>> exact = ItemCostHelper.parseLevelEntries(FCWServerConfig.RAID_EXACT_LEVEL_COSTS.get());
        List<FactionCoreMenu.RecipeCost> current = ItemCostHelper.sorted(ItemCostHelper.resolveRaidCosts(base, scaling, exact, craftedCount))
                .stream().map(e -> new FactionCoreMenu.RecipeCost(e.item(), e.count())).toList();
        List<FactionCoreMenu.RecipeCost> next = ItemCostHelper.sorted(ItemCostHelper.resolveRaidCosts(base, scaling, exact, craftedCount + 1))
                .stream().map(e -> new FactionCoreMenu.RecipeCost(e.item(), e.count())).toList();
        FCWNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new CoreRecipeSyncMessage(corePos, current, next));
    }
}
