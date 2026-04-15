package com.fishguy129.fcw.command;

import com.fishguy129.fcw.FCWMod;
import com.fishguy129.fcw.registry.FCWItems;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

// Admin and testing commands. These are mostly here so we can recover a server
// state without reaching straight for NBT edits. You're welcome!
public final class FCWCommands {
    private FCWCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fcw")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("givecore")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> giveCore(context, EntityArgument.getPlayer(context, "player"), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(context -> giveCore(context, EntityArgument.getPlayer(context, "player"), IntegerArgumentType.getInteger(context, "count"))))))
                .then(Commands.literal("resetcore")
                        .then(Commands.argument("team", UuidArgument.uuid())
                                .executes(context -> {
                                    UUID teamId = UuidArgument.getUuid(context, "team");
                                    FCWMod.CORE_MANAGER.resetCore(context.getSource(), teamId);
                                    context.getSource().sendSuccess(() -> Component.literal("Core reset for " + teamId), true);
                                    return 1;
                                })))
                .then(Commands.literal("grantprogress")
                        .then(Commands.argument("team", UuidArgument.uuid())
                                .then(Commands.argument("levels", IntegerArgumentType.integer(1))
                                        .executes(context -> {
                                            UUID teamId = UuidArgument.getUuid(context, "team");
                                            int levels = IntegerArgumentType.getInteger(context, "levels");
                                            var core = FCWMod.CORE_MANAGER.getCoreForTeam(context.getSource().getServer(), teamId).orElse(null);
                                            if (core != null) {
                                                FCWMod.CORE_MANAGER.data(context.getSource().getServer()).putCore(core.withUpgradeCount(core.upgradeCount() + levels));
                                                context.getSource().sendSuccess(() -> Component.literal("Granted " + levels + " upgrades"), true);
                                            }
                                            return 1;
                                        }))))
                .then(Commands.literal("startraid")
                        .then(Commands.argument("attacker", UuidArgument.uuid())
                                .then(Commands.argument("defender", UuidArgument.uuid())
                                        .executes(context -> {
                                            UUID attacker = UuidArgument.getUuid(context, "attacker");
                                            UUID defender = UuidArgument.getUuid(context, "defender");
                                            boolean started = FCWMod.RAID_MANAGER.forceStartRaid(context.getSource().getServer(), attacker, defender);
                                            context.getSource().sendSuccess(() -> Component.literal("Force start raid result: " + started), true);
                                            return started ? 1 : 0;
                                        }))))
                .then(Commands.literal("cancelraid")
                        .then(Commands.argument("team", UuidArgument.uuid())
                                .executes(context -> {
                                    UUID teamId = UuidArgument.getUuid(context, "team");
                                    FCWMod.RAID_MANAGER.cancelRaid(context.getSource().getServer(), teamId);
                                    context.getSource().sendSuccess(() -> Component.literal("Cancelled raid for " + teamId), true);
                                    return 1;
                                })))
                .then(Commands.literal("debugteam")
                        .then(Commands.argument("team", UuidArgument.uuid())
                                .executes(context -> debugTeam(context, UuidArgument.getUuid(context, "team")))))
                .then(Commands.literal("resyncclaims")
                        .then(Commands.argument("team", UuidArgument.uuid())
                                .executes(context -> {
                                    UUID teamId = UuidArgument.getUuid(context, "team");
                                    FCWMod.TEAM_COMPAT.resolveTeamById(teamId).ifPresent(team -> FCWMod.CHUNK_COMPAT.syncClaimsToClients(team, context.getSource().getServer()));
                                    context.getSource().sendSuccess(() -> Component.literal("Resynced claims for " + teamId), true);
                                    return 1;
                                })))
                .then(Commands.literal("repair")
                        .then(Commands.argument("team", UuidArgument.uuid())
                                .executes(context -> {
                                    UUID teamId = UuidArgument.getUuid(context, "team");
                                    boolean repaired = FCWMod.CORE_MANAGER.repairTeam(context.getSource().getServer(), teamId);
                                    context.getSource().sendSuccess(() -> Component.literal("Repair result for " + teamId + ": " + repaired), true);
                                    return repaired ? 1 : 0;
                                }))));
    }

    private static int giveCore(CommandContext<CommandSourceStack> context, ServerPlayer player, int count) {
        player.getInventory().placeItemBackInInventory(new ItemStack(FCWItems.FACTION_CORE.get(), count));
        context.getSource().sendSuccess(() -> Component.literal("Gave " + count + " faction core(s) to " + player.getGameProfile().getName()), true);
        return 1;
    }

    private static int debugTeam(CommandContext<CommandSourceStack> context, UUID teamId) {
        FCWMod.CORE_MANAGER.debugLines(context.getSource().getServer(), teamId).forEach(line -> context.getSource().sendSuccess(() -> line, false));
        return 1;
    }
}
