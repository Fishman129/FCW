package com.fishguy129.fcw.compat.ftbchunks;

import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.ChunkTeamData;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.data.ChunkTeamDataImpl;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Thin wrapper around FTB Chunks so the rest of the mod is not littered with
// API lookups and implementation-specific casts.
public class FtbChunkCompat {
    public boolean isLoaded() {
        return FTBChunksAPI.api() != null && FTBChunksAPI.api().isManagerLoaded();
    }

    public ChunkTeamData getData(Team team) {
        return FTBChunksAPI.api().getManager().getOrCreateData(team);
    }

    public ClaimResult claim(CommandSourceStack source, Team team, ChunkDimPos pos, boolean checkOnly) {
        return getData(team).claim(source, pos, checkOnly);
    }

    public ClaimResult unclaim(CommandSourceStack source, Team team, ChunkDimPos pos, boolean checkOnly) {
        return getData(team).unclaim(source, pos, checkOnly);
    }

    @Nullable
    public ClaimedChunk getClaim(ChunkDimPos pos) {
        return isLoaded() ? FTBChunksAPI.api().getManager().getChunk(pos) : null;
    }

    public boolean isClaimedByOtherTeam(Team team, ChunkDimPos pos) {
        ClaimedChunk chunk = getClaim(pos);
        return chunk != null && !chunk.getTeamData().getTeam().getId().equals(team.getId());
    }

    public boolean isClaimedByTeam(Team team, ChunkDimPos pos) {
        ClaimedChunk chunk = getClaim(pos);
        return chunk != null && chunk.getTeamData().getTeam().getId().equals(team.getId());
    }

    public Collection<? extends ClaimedChunk> claimedChunks(Team team) {
        return getData(team).getClaimedChunks();
    }

    public List<ClaimedChunk> claimedChunks(UUID teamId) {
        if (!isLoaded()) {
            return List.of();
        }
        return FTBChunksAPI.api().getManager().getClaimedChunksByTeam(chunk -> chunk.getTeamData().getTeam().getId().equals(teamId))
                .getOrDefault(teamId, Collections.emptyList())
                .stream()
                .toList();
    }

    public int claimCount(Team team) {
        return getData(team).getClaimedChunks().size();
    }

    public int currentExtraClaimChunks(Team team) {
        return getData(team).getExtraClaimChunks();
    }

    public int currentMaxClaimChunks(Team team) {
        return getData(team).getMaxClaimChunks();
    }

    public void setExtraClaimChunks(Team team, int extraClaimChunks) {
        ChunkTeamData data = getData(team);
        data.setExtraClaimChunks(extraClaimChunks);
        if (data instanceof ChunkTeamDataImpl impl) {
            // FTB caches the limits, so changing the number alone is not enough.
            impl.updateLimits();
            impl.markDirty();
        }
    }

    public void syncClaimsToClients(Team team, MinecraftServer server) {
        ChunkTeamData data = getData(team);
        if (data instanceof ChunkTeamDataImpl impl) {
            impl.syncChunksToAll(server);
        }
    }

    public void refreshLimits(Team team) {
        ChunkTeamData data = getData(team);
        if (data instanceof ChunkTeamDataImpl impl) {
            impl.updateLimits();
            impl.markDirty();
        }
    }

    public void clearClaims(CommandSourceStack source, Team team) {
        // Copy first so we are not mutating the collection we are iterating over.
        for (ClaimedChunk chunk : List.copyOf(claimedChunks(team))) {
            getData(team).unclaim(source, chunk.getPos(), false);
        }
    }

    public boolean getBypassProtection(UUID playerId) {
        return isLoaded() && FTBChunksAPI.api().getManager().getBypassProtection(playerId);
    }

    public void setBypassProtection(UUID playerId, boolean bypass) {
        if (isLoaded()) {
            FTBChunksAPI.api().getManager().setBypassProtection(playerId, bypass);
        }
    }

    public Optional<Team> ownerOf(Level level, BlockPos pos) {
        ClaimedChunk chunk = getClaim(new ChunkDimPos(level, pos));
        return chunk == null ? Optional.empty() : Optional.of(chunk.getTeamData().getTeam());
    }
}
