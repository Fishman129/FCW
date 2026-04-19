package com.fishguy129.fcw.event;

import com.fishguy129.fcw.FCWMod;
import com.fishguy129.fcw.config.FCWServerConfig;
import com.fishguy129.fcw.data.FCWSavedData;
import com.fishguy129.fcw.registry.FCWBlocks;
import com.fishguy129.fcw.registry.FCWItems;
import dev.architectury.event.CompoundEventResult;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.event.ClaimedChunkEvent;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.event.PlayerChangedTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerJoinedPartyTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerLeftPartyTeamEvent;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Bridges for Forge/FTB events into FCW, especially around protected core zones.
public class FCWForgeEvents {
    private final Map<UUID, Boolean> scopedBypassOriginal = new HashMap<>();
    private final Map<UUID, Integer> scopedBypassDepth = new HashMap<>();

    public FCWForgeEvents() {
        ClaimedChunkEvent.BEFORE_CLAIM.register(this::beforeClaim);
        ClaimedChunkEvent.BEFORE_UNCLAIM.register(this::beforeUnclaim);
        TeamEvent.PLAYER_JOINED_PARTY.register(this::playerJoinedParty);
        TeamEvent.PLAYER_LEFT_PARTY.register(this::playerLeftParty);
        TeamEvent.PLAYER_CHANGED.register(this::playerChangedTeam);
        TeamEvent.DELETED.register(this::teamDeleted);
    }

    private CompoundEventResult<ClaimResult> beforeClaim(net.minecraft.commands.CommandSourceStack source, dev.ftb.mods.ftbchunks.api.ClaimedChunk claimedChunk) {
        Team team = claimedChunk.getTeamData().getTeam();
        if (!FCWServerConfig.BLOCK_MANUAL_FTB_CLAIMS.get()) {
            return CompoundEventResult.pass();
        }
        if (shouldAllowAdminClaimBypass(source)) {
            return CompoundEventResult.pass();
        }
        if (FCWMod.CORE_MANAGER.isInternalClaim(team, claimedChunk.getPos(), true)) {
            return CompoundEventResult.pass();
        }
        return CompoundEventResult.interruptTrue(ClaimResult.customProblem("message.fcw.claims.manual_blocked"));
    }

    private CompoundEventResult<ClaimResult> beforeUnclaim(net.minecraft.commands.CommandSourceStack source, dev.ftb.mods.ftbchunks.api.ClaimedChunk claimedChunk) {
        Team team = claimedChunk.getTeamData().getTeam();
        if (!FCWServerConfig.BLOCK_MANUAL_FTB_CLAIMS.get()) {
            return CompoundEventResult.pass();
        }
        if (shouldAllowAdminClaimBypass(source)) {
            return CompoundEventResult.pass();
        }
        if (FCWMod.CORE_MANAGER.isInternalClaim(team, claimedChunk.getPos(), false)) {
            return CompoundEventResult.pass();
        }
        return CompoundEventResult.interruptTrue(ClaimResult.customProblem("message.fcw.claims.manual_blocked"));
    }

    private boolean shouldAllowAdminClaimBypass(net.minecraft.commands.CommandSourceStack source) {
        return FCWServerConfig.ADMIN_BYPASS_STRUCTURED_CLAIMS.get() && source.hasPermission(2) && source.getEntity() == null;
    }

    private void playerJoinedParty(PlayerJoinedPartyTeamEvent event) {
        MinecraftServer server = event.getPlayer().server;
        FCWSavedData.get(server).setMemberJoinTick(event.getTeam().getId(), event.getPlayer().getUUID(), server.overworld().getGameTime());
        FCWMod.CORE_MANAGER.syncClaimOutline(event.getPlayer());
    }

    private void playerLeftParty(PlayerLeftPartyTeamEvent event) {
        MinecraftServer server = FCWMod.TEAM_COMPAT.server();
        if (server == null) {
            return;
        }
        FCWSavedData.get(server).removeMemberJoinTick(event.getTeam().getId(), event.getPlayerId());
        FCWMod.RAID_MANAGER.handleTeamChange(event.getPlayerId());
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(event.getPlayerId());
        if (player != null) {
            FCWMod.CORE_MANAGER.syncClaimOutline(player);
        }
    }

    private void playerChangedTeam(PlayerChangedTeamEvent event) {
        FCWMod.RAID_MANAGER.handleTeamChange(event.getPlayerId());
        MinecraftServer server = FCWMod.TEAM_COMPAT.server();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(event.getPlayerId());
        if (player != null) {
            FCWMod.CORE_MANAGER.syncClaimOutline(player);
        }
    }

    private void teamDeleted(TeamEvent event) {
        MinecraftServer server = FCWMod.TEAM_COMPAT.server();
        Team deletedTeam = event.getTeam();
        if (server == null || deletedTeam == null) {
            return;
        }

        FCWMod.RAID_MANAGER.cancelRaid(server, deletedTeam.getId());
        FCWMod.CORE_MANAGER.dropPackedCoreForDisband(server, deletedTeam);
        FCWMod.CORE_MANAGER.resetCore(server.createCommandSourceStack(), deletedTeam);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            FCWMod.CORE_MANAGER.syncClaimOutline(player);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FCWMod.CORE_MANAGER.tick(event.getServer());
            FCWMod.RAID_MANAGER.tick(event.getServer());
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FCWMod.RAID_MANAGER.syncRaidsToPlayer(player);
            FCWMod.CORE_MANAGER.syncClaimOutline(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (handleCoreInteraction(event, player)) {
            return;
        }

        if (shouldBypassForRightClick(player, event.getPos(), event.getFace(), event.getHand())) {
            beginScopedBypass(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void afterRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            endScopedBypass(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && shouldBypass(player, event.getPos())) {
            beginScopedBypass(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void afterLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            endScopedBypass(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && shouldBypass(player, event.getPos())) {
            beginScopedBypass(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void afterBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            endScopedBypass(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && shouldBypass(player, event.getPos())) {
            beginScopedBypass(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void afterBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            endScopedBypass(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeFillBucket(FillBucketEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && event.getTarget() instanceof net.minecraft.world.phys.BlockHitResult hitResult
                && shouldBypass(player, hitResult.getBlockPos())) {
            beginScopedBypass(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void afterFillBucket(FillBucketEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            endScopedBypass(player);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FCWMod.RAID_MANAGER.handleLogout(player);
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FCWMod.RAID_MANAGER.handleDeath(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && shouldBypass(player, event.getPos())) {
            event.setCanceled(false);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && shouldBypass(player, event.getPos())) {
            event.setCanceled(false);
            event.setCancellationResult(InteractionResult.PASS);
            event.setUseBlock(net.minecraftforge.eventbus.api.Event.Result.ALLOW);
            event.setUseItem(net.minecraftforge.eventbus.api.Event.Result.ALLOW);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && shouldBypass(player, event.getPos())) {
            event.setCanceled(false);
            event.setCancellationResult(InteractionResult.PASS);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player && shouldBypass(player, event.getTarget().blockPosition())) {
            event.setCanceled(false);
            event.setCancellationResult(InteractionResult.PASS);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getEntity() instanceof ServerPlayer player && shouldBypass(player, event.getTarget().blockPosition())) {
            event.setCanceled(false);
            event.setCancellationResult(InteractionResult.PASS);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && shouldBypass(player, event.getTarget().blockPosition())) {
            event.setCanceled(false);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onFillBucket(FillBucketEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getTarget() instanceof net.minecraft.world.phys.BlockHitResult hitResult && shouldBypass(player, hitResult.getBlockPos())) {
            event.setCanceled(false);
        }
    }

    private boolean shouldBypass(ServerPlayer player, net.minecraft.core.BlockPos pos) {
        if (player == null || player.level().isClientSide()) {
            return false;
        }
        if (player.level().getBlockState(pos).is(FCWBlocks.FACTION_CORE.get()) && !player.getMainHandItem().is(FCWItems.RAID_BEACON.get())) {
            return false;
        }
        return FCWMod.CORE_MANAGER.isWithinBreachZone(player.level(), pos) || FCWMod.RAID_MANAGER.isProtectionSuspended(player.level(), pos);
    }

    private boolean shouldBypassForRightClick(ServerPlayer player, net.minecraft.core.BlockPos clickedPos, net.minecraft.core.Direction face, InteractionHand hand) {
        if (shouldBypass(player, clickedPos)) {
            return true;
        }
        if (player.getItemInHand(hand).getItem() instanceof BlockItem && face != null) {
            return shouldBypass(player, clickedPos.relative(face));
        }
        return false;
    }

    private boolean handleCoreInteraction(PlayerInteractEvent.RightClickBlock event, ServerPlayer player) {
        if (!player.level().getBlockState(event.getPos()).is(FCWBlocks.FACTION_CORE.get())) {
            return false;
        }

        // Let the core act like a hub: beacon starts raids,
        // catalyst upgrades claims, empty hand opens the UI.
        net.minecraft.world.item.ItemStack heldStack = player.getItemInHand(event.getHand());
        if (heldStack.is(FCWItems.RAID_BEACON.get())) {
            boolean success = FCWMod.RAID_MANAGER.startRaid(player, event.getPos(), heldStack);
            if (success) {
                heldStack.shrink(1);
            }
            consumeCoreInteraction(event, success ? InteractionResult.SUCCESS : InteractionResult.FAIL);
            return true;
        }

        if (heldStack.is(FCWItems.CLAIM_CATALYST.get())) {
            boolean success = FCWMod.CORE_MANAGER.applyClaimUpgrade(player, event.getPos());
            if (success) {
                heldStack.shrink(1);
            }
            consumeCoreInteraction(event, success ? InteractionResult.SUCCESS : InteractionResult.FAIL);
            return true;
        }

        if (heldStack.getItem() instanceof BlockItem) {
            return false;
        }

        boolean opened = FCWMod.CORE_MANAGER.openCoreMenu(player, event.getPos(), false);
        consumeCoreInteraction(event, opened ? InteractionResult.SUCCESS : InteractionResult.FAIL);
        return true;
    }

    private void consumeCoreInteraction(PlayerInteractEvent.RightClickBlock event, InteractionResult result) {
        event.setCanceled(true);
        event.setCancellationResult(result);
        event.setUseBlock(net.minecraftforge.eventbus.api.Event.Result.DENY);
        event.setUseItem(net.minecraftforge.eventbus.api.Event.Result.DENY);
    }

    private void beginScopedBypass(ServerPlayer player) {
        UUID playerId = player.getUUID();
        int depth = scopedBypassDepth.getOrDefault(playerId, 0);
        if (depth == 0) {
            // We only want bypass while FCW is intentionally letting someone interact
            // inside a protected zone, so restore the original flag afterward.
            scopedBypassOriginal.put(playerId, FCWMod.CHUNK_COMPAT.getBypassProtection(playerId));
            FCWMod.CHUNK_COMPAT.setBypassProtection(playerId, true);
        }
        scopedBypassDepth.put(playerId, depth + 1);
    }

    private void endScopedBypass(ServerPlayer player) {
        UUID playerId = player.getUUID();
        Integer depth = scopedBypassDepth.get(playerId);
        if (depth == null) {
            return;
        }
        if (depth <= 1) {
            boolean original = scopedBypassOriginal.getOrDefault(playerId, false);
            FCWMod.CHUNK_COMPAT.setBypassProtection(playerId, original);
            scopedBypassDepth.remove(playerId);
            scopedBypassOriginal.remove(playerId);
        } else {
            scopedBypassDepth.put(playerId, depth - 1);
        }
    }
}
