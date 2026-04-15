package com.fishguy129.fcw.compat.ftbteams;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Uhh kinda same idea as the chunk compat helper: centralize the FTB Teams calls so the
// gameplay code reads like game rules instead of API "plumbing".
public class FtbTeamCompat {
    public boolean isLoaded() {
        return FTBTeamsAPI.api() != null && FTBTeamsAPI.api().isManagerLoaded();
    }

    public Optional<Team> resolveTeam(ServerPlayer player) {
        return isLoaded() ? FTBTeamsAPI.api().getManager().getTeamForPlayer(player) : Optional.empty();
    }

    public Optional<Team> resolveTeam(UUID playerId) {
        return isLoaded() ? FTBTeamsAPI.api().getManager().getTeamForPlayerID(playerId) : Optional.empty();
    }

    public Optional<Team> resolvePersonalTeam(UUID playerId) {
        return isLoaded() ? FTBTeamsAPI.api().getManager().getPlayerTeamForPlayerID(playerId) : Optional.empty();
    }

    public Optional<Team> resolveFactionTeam(ServerPlayer player) {
        return resolveTeam(player).filter(this::isFactionTeam);
    }

    public Optional<Team> resolveFactionTeam(UUID playerId) {
        return resolveTeam(playerId).filter(this::isFactionTeam);
    }

    public boolean isFactionTeam(@Nullable Team team) {
        return team != null && team.isValid() && team.isPartyTeam();
    }

    public Optional<Team> resolveTeamById(UUID teamId) {
        return isLoaded() ? FTBTeamsAPI.api().getManager().getTeamByID(teamId) : Optional.empty();
    }

    public boolean isLeader(Team team, UUID playerId) {
        return team.getRankForPlayer(playerId).isOwner();
    }

    public boolean isMember(Team team, UUID playerId) {
        return team.getRankForPlayer(playerId).isMemberOrBetter();
    }

    public boolean isOfficerOrBetter(Team team, UUID playerId) {
        return team.getRankForPlayer(playerId).isOfficerOrBetter();
    }

    public Set<UUID> members(Team team) {
        return team == null ? Collections.emptySet() : team.getMembers();
    }

    public Collection<ServerPlayer> onlineMembers(Team team) {
        return team == null ? Collections.emptyList() : team.getOnlineMembers();
    }

    public String displayName(Team team) {
        return team.getProperty(TeamProperties.DISPLAY_NAME);
    }

    public Component displayComponent(Team team) {
        return team.getName();
    }

    public int onlineCount(Team team) {
        return onlineMembers(team).size();
    }

    public boolean areAllied(@Nullable Team first, @Nullable Team second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getId().equals(second.getId())) {
            return true;
        }
        UUID firstOwner = first.getOwner();
        UUID secondOwner = second.getOwner();
        TeamRank firstView = first.getRankForPlayer(secondOwner);
        TeamRank secondView = second.getRankForPlayer(firstOwner);
        return firstView.isAllyOrBetter() || secondView.isAllyOrBetter();
    }

    public void notifyTeam(Team team, Component message) {
        for (ServerPlayer player : onlineMembers(team)) {
            player.displayClientMessage(message, false);
        }
    }

    public MinecraftServer server() {
        return FTBTeamsAPI.api().getManager().getServer();
    }
}
