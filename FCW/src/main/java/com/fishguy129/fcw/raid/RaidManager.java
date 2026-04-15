package com.fishguy129.fcw.raid;

import com.fishguy129.fcw.compat.ftbchunks.FtbChunkCompat;
import com.fishguy129.fcw.compat.ftbteams.FtbTeamCompat;
import com.fishguy129.fcw.config.FCWServerConfig;
import com.fishguy129.fcw.core.CoreManager;
import com.fishguy129.fcw.data.FCWSavedData;
import com.fishguy129.fcw.item.RaidBeaconItem;
import com.fishguy129.fcw.network.FCWNetwork;
import com.fishguy129.fcw.network.RaidStatusMessage;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

import java.util.*;

// Owns the raid lifecycle, from beacon use through victory/failure cleanup. Someone kill me this took forever
public class RaidManager {
    private final FtbTeamCompat teamCompat;
    private final FtbChunkCompat chunkCompat;
    private final CoreManager coreManager;

    public RaidManager(FtbTeamCompat teamCompat, FtbChunkCompat chunkCompat, CoreManager coreManager) {
        this.teamCompat = teamCompat;
        this.chunkCompat = chunkCompat;
        this.coreManager = coreManager;
    }

    public boolean startRaid(ServerPlayer player, BlockPos corePos, ItemStack stack) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }

        UUID boundTeam = RaidBeaconItem.boundTeam(stack);
        Team attacker = teamCompat.resolveFactionTeam(player).orElse(null);
        if (attacker == null || boundTeam == null || !boundTeam.equals(attacker.getId())) {
            player.displayClientMessage(Component.translatable("message.fcw.raid.invalid_item").withStyle(ChatFormatting.RED), false);
            return false;
        }

        FCWSavedData.CoreRecord defenderCore = coreManager.data(level.getServer()).allCores().stream()
                .filter(record -> record.dimension().equals(level.dimension()) && record.pos().equals(corePos))
                .findFirst()
                .orElse(null);
        if (defenderCore == null || defenderCore.teamId().equals(attacker.getId()) || !defenderCore.active()) {
            player.displayClientMessage(Component.translatable("message.fcw.raid.invalid_target").withStyle(ChatFormatting.RED), false);
            return false;
        }

        Team defender = teamCompat.resolveTeamById(defenderCore.teamId()).orElse(null);
        if (defender == null) {
            player.displayClientMessage(Component.translatable("message.fcw.raid.invalid_target").withStyle(ChatFormatting.RED), false);
            return false;
        }

        if (!FCWServerConfig.ALLOW_ALLIED_RAIDING.get() && teamCompat.areAllied(attacker, defender)) {
            player.displayClientMessage(Component.translatable("message.fcw.raid.allied_blocked").withStyle(ChatFormatting.RED), false);
            return false;
        }

        if (FCWSavedData.get(level.getServer()).findRaidForTeam(attacker.getId()).isPresent()
                || FCWSavedData.get(level.getServer()).findRaidForTeam(defender.getId()).isPresent()) {
            player.displayClientMessage(Component.translatable("message.fcw.raid.already_active").withStyle(ChatFormatting.RED), false);
            return false;
        }

        return createRaid(level, attacker, defender, corePos);
    }

    public boolean forceStartRaid(MinecraftServer server, UUID attackerTeamId, UUID defenderTeamId) {
        Team attacker = teamCompat.resolveTeamById(attackerTeamId).orElse(null);
        Team defender = teamCompat.resolveTeamById(defenderTeamId).orElse(null);
        FCWSavedData.CoreRecord defenderCore = coreManager.getCoreForTeam(server, defenderTeamId).orElse(null);
        if (attacker == null || defender == null || defenderCore == null) {
            return false;
        }
        ServerLevel level = server.getLevel(defenderCore.dimension());
        if (level == null) {
            return false;
        }
        if (FCWSavedData.get(server).findRaidForTeam(attackerTeamId).isPresent() || FCWSavedData.get(server).findRaidForTeam(defenderTeamId).isPresent()) {
            return false;
        }
        return createRaid(level, attacker, defender, defenderCore.pos());
    }

    public void tick(MinecraftServer server) {
        for (FCWSavedData.RaidRecord raid : List.copyOf(FCWSavedData.get(server).activeRaids())) {
            tickRaid(server, raid);
        }
    }

    private void tickRaid(MinecraftServer server, FCWSavedData.RaidRecord raid) {
        ServerLevel level = server.getLevel(raid.dimension());
        Team attacker = teamCompat.resolveTeamById(raid.attackerTeamId()).orElse(null);
        Team defender = teamCompat.resolveTeamById(raid.defenderTeamId()).orElse(null);
        FCWSavedData.CoreRecord defenderCore = coreManager.getCoreForTeam(server, raid.defenderTeamId()).orElse(null);
        if (level == null || attacker == null || defender == null || defenderCore == null || !defenderCore.active()) {
            finishRaid(server, raid, false);
            return;
        }

        long now = level.getGameTime();
        raid.logoutDeadlines().entrySet().removeIf(entry -> {
            // Attackers who stay gone past the grace window are treated as eliminated.
            if (now >= entry.getValue()) {
                raid.eliminatedAttackers().add(entry.getKey());
                return true;
            }
            return false;
        });

        List<ServerPlayer> attackers = validAttackers(level, attacker, raid);
        List<ServerPlayer> defenders = validDefenders(level, defender, raid.corePos());
        attackers.forEach(player -> player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, true, false, true)));
        defenders.forEach(player -> player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, true, false, true)));

        if (attackers.isEmpty()) {
            finishRaid(server, raid, false);
            return;
        }

        raid.incrementProgress();
        FCWSavedData.get(server).setDirty();
        if (raid.progressTicks() % 20L == 0L) {
            syncRaidStatus(server, raid);
        }
        if (raid.progressTicks() >= raid.requiredTicks()) {
            finishRaid(server, raid, true);
        }
    }

    private List<ServerPlayer> validAttackers(ServerLevel level, Team attacker, FCWSavedData.RaidRecord raid) {
        if (level == null || attacker == null) {
            return List.of();
        }
        int radius = FCWServerConfig.RAID_PRESENCE_RADIUS.get();
        return teamCompat.onlineMembers(attacker).stream()
                .filter(player -> player.level().dimension().equals(level.dimension()))
                .filter(player -> !raid.eliminatedAttackers().contains(player.getUUID()))
                .filter(player -> player.blockPosition().closerThan(raid.corePos(), radius))
                .toList();
    }

    private List<ServerPlayer> validDefenders(ServerLevel level, Team defender, BlockPos corePos) {
        if (level == null || defender == null) {
            return List.of();
        }
        int radius = FCWServerConfig.RAID_PRESENCE_RADIUS.get();
        return teamCompat.onlineMembers(defender).stream()
                .filter(player -> player.level().dimension().equals(level.dimension()))
                .filter(player -> player.blockPosition().closerThan(corePos, radius))
                .toList();
    }

    private void finishRaid(MinecraftServer server, FCWSavedData.RaidRecord raid, boolean success) {
        Team attacker = teamCompat.resolveTeamById(raid.attackerTeamId()).orElse(null);
        Team defender = teamCompat.resolveTeamById(raid.defenderTeamId()).orElse(null);
        FCWSavedData savedData = FCWSavedData.get(server);
        FCWSavedData.CoreRecord defenderCore = coreManager.getCoreForTeam(server, raid.defenderTeamId()).orElse(null);

        if (success && defender != null && defenderCore != null) {
            ServerLevel defenderLevel = server.getLevel(defenderCore.dimension());
            if (defenderLevel != null) {
                coreManager.destroyCoreForRaid(defenderLevel, defenderCore, FCWServerConfig.RAID_DROP_UPGRADES_ON_SUCCESS.get());
            }
            coreManager.resetCore(server.createCommandSourceStack(), defender.getId());
            coreManager.clearFactionClaims(server.createCommandSourceStack(), defender);
        }

        if (attacker != null) {
            teamCompat.notifyTeam(attacker, Component.translatable(success ? "message.fcw.raid.attackers_win" : "message.fcw.raid.defenders_win").withStyle(success ? ChatFormatting.RED : ChatFormatting.GOLD));
        }
        if (defender != null) {
            teamCompat.notifyTeam(defender, Component.translatable(success ? "message.fcw.raid.attackers_win" : "message.fcw.raid.defenders_win").withStyle(success ? ChatFormatting.RED : ChatFormatting.GOLD));
        }

        ServerLevel level = server.getLevel(raid.dimension());
        if (level != null) {
            raid.forcedChunks().forEach(chunkLong -> {
                ChunkPos pos = new ChunkPos(chunkLong);
                level.setChunkForced(pos.x, pos.z, false);
            });
            coreManager.playRaidResolvedEffects(level, raid.corePos(), success);
        }

        clearRaidStatus(server, raid);
        savedData.removeRaid(raid.raidId());
    }

    public void handleLogout(ServerPlayer player) {
        Team team = teamCompat.resolveFactionTeam(player).orElse(null);
        if (team == null) {
            return;
        }

        FCWSavedData.get(player.server).findRaidForTeam(team.getId()).ifPresent(raid -> {
            if (raid.attackerTeamId().equals(team.getId())) {
                raid.logoutDeadlines().put(player.getUUID(), player.server.overworld().getGameTime() + FCWServerConfig.RAID_LOGOUT_GRACE_SECONDS.get() * 20L);
                FCWSavedData.get(player.server).setDirty();
            }
        });
    }

    public void handleDeath(ServerPlayer player) {
        Team team = teamCompat.resolveFactionTeam(player).orElse(null);
        if (team == null) {
            return;
        }

        FCWSavedData.get(player.server).findRaidForTeam(team.getId()).ifPresent(raid -> {
            if (raid.attackerTeamId().equals(team.getId())) {
                raid.eliminatedAttackers().add(player.getUUID());
                FCWSavedData.get(player.server).setDirty();
            }
        });
    }

    public void handleTeamChange(UUID playerId) {
        MinecraftServer server = teamCompat.server();
        if (server == null) {
            return;
        }

        for (FCWSavedData.RaidRecord raid : FCWSavedData.get(server).activeRaids()) {
            raid.eliminatedAttackers().add(playerId);
        }
        FCWSavedData.get(server).setDirty();
    }

    public boolean isProtectionSuspended(Level level, BlockPos pos) {
        Team owner = chunkCompat.ownerOf(level, pos).orElse(null);
        if (owner == null) {
            return false;
        }
        return coreManager.getCoreForTeam(level.getServer(), owner.getId()).map(FCWSavedData.CoreRecord::protectionSuspended).orElse(false);
    }

    public void cancelRaid(MinecraftServer server, UUID teamId) {
        FCWSavedData.get(server).findRaidForTeam(teamId).ifPresent(raid -> finishRaid(server, raid, false));
    }

    public void syncRaidsToPlayer(ServerPlayer player) {
        for (FCWSavedData.RaidRecord raid : FCWSavedData.get(player.server).activeRaids()) {
            sendRaidStatus(player, player.server, raid);
        }
    }

    private boolean createRaid(ServerLevel level, Team attacker, Team defender, BlockPos corePos) {
        FCWSavedData savedData = FCWSavedData.get(level.getServer());
        long now = level.getGameTime();
        long requiredTicks = computeRequiredTicks(level.getServer(), defender, now);
        Set<Long> forcedChunks = FCWServerConfig.KEEP_ACTIVE_RAID_CHUNKS_LOADED.get() ? forceRaidChunks(level, corePos) : Set.of();
        FCWSavedData.RaidRecord raid = new FCWSavedData.RaidRecord(
                UUID.randomUUID(),
                attacker.getId(),
                defender.getId(),
                level.dimension(),
                corePos,
                now,
                0L,
                requiredTicks,
                new HashSet<>(),
                new HashMap<>(),
                new HashSet<>(forcedChunks)
        );

        savedData.putRaid(raid);
        coreManager.playRaidStartedEffects(level, corePos);
        syncRaidStatus(level.getServer(), raid);
        teamCompat.notifyTeam(attacker, Component.translatable("message.fcw.raid.started_attacker", teamCompat.displayName(defender)).withStyle(ChatFormatting.RED));
        teamCompat.notifyTeam(defender, Component.translatable("message.fcw.raid.started_defender", teamCompat.displayName(attacker)).withStyle(ChatFormatting.GOLD));
        return true;
    }

    private long computeRequiredTicks(MinecraftServer server, Team defender, long now) {
        long ticks = FCWServerConfig.RAID_BASE_DURATION_SECONDS.get() * 20L;
        if (!FCWServerConfig.DEFENDER_ONLINE_SCALING_ENABLED.get()) {
            return ticks;
        }

        // Recent joins can be excluded so factions cannot pad their timer by inviting
        // people right before a fight.
        int memberCount = 0;
        for (UUID memberId : teamCompat.members(defender)) {
            long joinTick = FCWSavedData.get(server).memberJoinTick(defender.getId(), memberId);
            boolean recent = joinTick > 0 && now - joinTick < FCWServerConfig.DEFENDER_NEW_MEMBER_GRACE_SECONDS.get() * 20L;
            if (recent && !FCWServerConfig.DEFENDER_RECENT_JOINS_COUNT.get()) {
                continue;
            }
            memberCount++;
        }

        if (memberCount < FCWServerConfig.DEFENDER_ONLINE_MIN_FACTION_SIZE.get()) {
            return clampTicks(ticks);
        }

        int online = teamCompat.onlineCount(defender);
        double multiplier;
        if (online <= 0) {
            multiplier = FCWServerConfig.DEFENDER_ZERO_ONLINE_MULTIPLIER.get();
        } else {
            double ratio = (double) online / (double) Math.max(memberCount, 1);
            double threshold = FCWServerConfig.DEFENDER_ONLINE_RATIO_THRESHOLD.get();
            if (ratio >= threshold) {
                multiplier = 1D;
            } else {
                double progress = threshold <= 0D ? 0D : ratio / threshold;
                multiplier = 1D + (1D - progress) * (FCWServerConfig.DEFENDER_ONLINE_MAX_MULTIPLIER.get() - 1D);
            }
        }

        return clampTicks((long) (ticks * multiplier));
    }

    private long clampTicks(long ticks) {
        long min = FCWServerConfig.DEFENDER_TIMER_MIN_SECONDS.get() * 20L;
        long max = FCWServerConfig.DEFENDER_TIMER_MAX_SECONDS.get() * 20L;
        return Math.max(min, Math.min(max, ticks));
    }

    private Set<Long> forceRaidChunks(ServerLevel level, BlockPos corePos) {
        Set<Long> forced = new HashSet<>();
        // Keep the battlefield loaded while the raid is active so progress does not
        // hinge on somebody standing close enough to tick the area.
        int chunkRadius = (FCWServerConfig.RAID_PRESENCE_RADIUS.get() / 16) + 1;
        ChunkPos origin = new ChunkPos(corePos);
        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                int x = origin.x + dx;
                int z = origin.z + dz;
                level.setChunkForced(x, z, true);
                forced.add(ChunkPos.asLong(x, z));
            }
        }
        return forced;
    }

    private void syncRaidStatus(MinecraftServer server, FCWSavedData.RaidRecord raid) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendRaidStatus(player, server, raid);
        }
    }

    private void clearRaidStatus(MinecraftServer server, FCWSavedData.RaidRecord raid) {
        RaidStatusMessage message = new RaidStatusMessage(raid.dimension().location().toString(), raid.corePos(), false, "", "", 0L, 0L, List.of(), List.of(), false, false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            FCWNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    private void sendRaidStatus(ServerPlayer player, MinecraftServer server, FCWSavedData.RaidRecord raid) {
        Team attacker = teamCompat.resolveTeamById(raid.attackerTeamId()).orElse(null);
        Team defender = teamCompat.resolveTeamById(raid.defenderTeamId()).orElse(null);
        List<UUID> attackers = validAttackers(server.getLevel(raid.dimension()), attacker, raid).stream().map(ServerPlayer::getUUID).toList();
        List<UUID> defenders = validDefenders(server.getLevel(raid.dimension()), defender, raid.corePos()).stream().map(ServerPlayer::getUUID).toList();
        Team viewerTeam = teamCompat.resolveFactionTeam(player).orElse(null);
        RaidStatusMessage message = new RaidStatusMessage(
                raid.dimension().location().toString(),
                raid.corePos(),
                true,
                attacker == null ? raid.attackerTeamId().toString() : teamCompat.displayName(attacker),
                defender == null ? raid.defenderTeamId().toString() : teamCompat.displayName(defender),
                raid.progressTicks(),
                raid.requiredTicks(),
                attackers,
                defenders,
                viewerTeam != null && viewerTeam.getId().equals(raid.attackerTeamId()),
                viewerTeam != null && viewerTeam.getId().equals(raid.defenderTeamId())
        );
        FCWNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
