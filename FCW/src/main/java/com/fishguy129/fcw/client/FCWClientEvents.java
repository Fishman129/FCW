package com.fishguy129.fcw.client;

import com.fishguy129.fcw.config.FCWClientConfig;
import com.fishguy129.fcw.core.CoreBreachShape;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// World overlays, raid HUD, and the looping audio cue.
public class FCWClientEvents {
    private static final ResourceLocation HOTBAR_OVERLAY = new ResourceLocation("minecraft", "hotbar");
    private static final int RAID_HUD_WIDTH = 252;
    private static final int RAID_HUD_HEIGHT = 72;

    // Fence silhouette tuning.
    private static final double CLAIM_WALL_HEIGHT = 2.62D;
    private static final double CLAIM_SEGMENT_LENGTH = 4.0D;

    // Fence piece sizing.
    private static final double CLAIM_POST_HALF_WIDTH = 0.25D;
    private static final double CLAIM_POST_CAP_OVERHANG = 0.045D;
    private static final double CLAIM_RAIL_HALF_THICKNESS = 0.10D;

    // Trim so rails tuck into posts cleanly.
    private static final double CLAIM_POST_TRIM = Math.max(0.07D, CLAIM_POST_HALF_WIDTH - 0.035D);

    // Geometry safety offsets.
    private static final double CLAIM_FACE_INSET = 0.014D;

    // Entrance arch tuning.
    private static final double GATE_SUPPORT_CLEARANCE = 0.72D;
    private static final double GATE_LINTEL_INSET = 0.42D;
    private static final double GATE_POST_HALF_WIDTH = 0.29D;

    // Path outline tuning.
    private static final double PATH_POST_HALF_WIDTH = 0.16D;
    private static final double PATH_RAIL_HALF_THICKNESS = 0.07D;
    private static final double PATH_HEIGHT = 1.18D;
    private static final double PATH_POST_TRIM = 0.12D;
    private static final double PATH_SEGMENT_LENGTH = 3.0D;
    private static final double CLAIM_STAKE_INTERVAL = 0.82D;
    private static final double CLAIM_STAKE_HALF_WIDTH = 0.072D;
    private static final double CLAIM_STAKE_HALF_DEPTH = 0.102D;
    private static final double PATH_STAKE_INTERVAL = 0.96D;
    private static final double PATH_STAKE_HALF_WIDTH = 0.050D;
    private static final double PATH_STAKE_HALF_DEPTH = 0.074D;
    private static final double PATH_START_PADDING = 0.28D;
    private static final double PATH_END_PADDING = 0.12D;
    private static final double MIN_RENDERABLE_INTERVAL = 0.18D;

    // Reveal / animation.
    private static final double CLAIM_OUTLINE_FADE_BAND_RATIO = 1.9D;
    private static final double CLAIM_OUTLINE_FADE_BAND_MIN = 10.0D;
    private static final float CLAIM_OUTLINE_FADE_IN_SPEED = 2.2F;
    private static final float CLAIM_OUTLINE_FADE_OUT_SPEED = 1.2F;
    private static final float CLAIM_OUTLINE_MIN_SEGMENT_VISIBILITY = 0.004F;
    private static final String RAID_ATTACKER_OUTLINE_TEAM = "fcw_raid_attacker_outline";
    private static final String RAID_DEFENDER_OUTLINE_TEAM = "fcw_raid_defender_outline";
    private static final float ZONE_RING_FADE_BAND = 24.0F;

    private static final Map<ClaimSegmentKey, Float> CLAIM_SEGMENT_VISIBILITY = new HashMap<>();
    private static final Map<UUID, String> RAID_OUTLINE_PREVIOUS_TEAMS = new HashMap<>();
    private static final Map<UUID, Boolean> RAID_OUTLINE_PREVIOUS_GLOWING = new HashMap<>();

    private RaidMusicSoundInstance activeRaidMusic;
    private long animatedOwnedOutlineSignature = Long.MIN_VALUE;
    private long lastClaimAnimationMillis = Util.getMillis();

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientRaidState.clear();
        ClientClaimOutlineState.clear();
        ClientEnemyClaimOutlineState.clear();
        CLAIM_SEGMENT_VISIBILITY.clear();
        clearRaidParticipantOutlines(Minecraft.getInstance());
        animatedOwnedOutlineSignature = Long.MIN_VALUE;
        lastClaimAnimationMillis = Util.getMillis();
        activeRaidMusic = null;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            clearRaidParticipantOutlines(minecraft);
            activeRaidMusic = null;
            return;
        }

        if (FCWClientConfig.ENABLE_RAID_PLAYER_HIGHLIGHTS.get()) {
            updateRaidParticipantOutlines(minecraft);
        } else {
            clearRaidParticipantOutlines(minecraft);
        }

        ClientRaidState.RaidVisual raid = ClientRaidState.localPlayerRaid(minecraft.level.dimension().location());
        if (raid == null || !FCWClientConfig.ENABLE_RAID_MUSIC.get()) {
            activeRaidMusic = null;
            return;
        }

        if (activeRaidMusic != null && !activeRaidMusic.isStopped()) {
            return;
        }

        SoundEvent soundEvent = resolveSound(FCWClientConfig.RAID_MUSIC_SOUND.get());
        if (soundEvent == null) {
            return;
        }

        activeRaidMusic = new RaidMusicSoundInstance(soundEvent, raid.dimensionId(), raid.corePos());
        minecraft.getSoundManager().play(activeRaidMusic);
    }

    @SubscribeEvent
    public void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer lineBuffer = bufferSource.getBuffer(RenderType.lines());
        VertexConsumer fillBuffer = bufferSource.getBuffer(RenderType.debugQuads());
        Vec3 cameraPos = event.getCamera().getPosition();

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        if (FCWClientConfig.ENABLE_CORE_ZONE_RING.get()) {
            renderOwnedCoreZoneRing(fillBuffer, lineBuffer, poseStack, minecraft.player);
            renderEnemyCoreZoneRings(fillBuffer, lineBuffer, poseStack, minecraft.player);
        }

        renderOwnedClaimOutline(fillBuffer, poseStack, minecraft.player);
        renderEnemyClaimOutlines(fillBuffer, poseStack, minecraft.player);

        if (FCWClientConfig.ENABLE_RAID_PLAYER_HIGHLIGHTS.get()) {
            for (Player player : minecraft.level.players()) {
                renderParticipantHighlight(lineBuffer, poseStack, player, minecraft.level.dimension().location(), minecraft.player);
            }
        }

        poseStack.popPose();
        bufferSource.endBatch(RenderType.debugQuads());
        bufferSource.endBatch(RenderType.lines());

        if (FCWClientConfig.ENABLE_RAID_PLAYER_HIGHLIGHTS.get()) {
            poseStack.pushPose();
            poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            for (Player player : minecraft.level.players()) {
                renderParticipantTag(bufferSource, poseStack, player, minecraft.level.dimension().location(), minecraft.player);
            }
            poseStack.popPose();
            bufferSource.endBatch();
        }
    }

    @SubscribeEvent
    public void onRenderHud(RenderGuiOverlayEvent.Post event) {
        if (!FCWClientConfig.ENABLE_RAID_HUD.get() || !HOTBAR_OVERLAY.equals(event.getOverlay().id())) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        ClientRaidState.RaidVisual raid = ClientRaidState.localPlayerRaid(minecraft.level.dimension().location());
        if (raid == null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        float scale = FCWClientConfig.RAID_HUD_SCALE.get().floatValue();
        int width = RAID_HUD_WIDTH;
        int height = RAID_HUD_HEIGHT;
        int x = (int) ((graphics.guiWidth() - (width * scale)) / 2F);
        int y = 10;
        long remainingSeconds = Mth.ceil(raid.remainingTicks() / 20.0D);
        int accent = raid.localPlayerAttacker() ? 0xFFFF6666 : 0xFF5EA7FF;
        float progress = raid.progressFraction();
        float tickTime = minecraft.level.getGameTime() + minecraft.getFrameTime();

        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1F);
        renderRaidHud(graphics, minecraft.font, raid, accent, progress, remainingSeconds, tickTime, width, height);
        graphics.pose().popPose();
    }

    private void renderRaidHud(GuiGraphics graphics, Font font, ClientRaidState.RaidVisual raid,
                               int accent, float progress, long remainingSeconds, float tickTime,
                               int width, int height) {
        int accentBright = brighten(accent, 42);
        int secondary = raid.localPlayerAttacker() ? 0xFF74C6FF : 0xFFFFA84A;
        int topGlow = mixColor(accentBright, secondary, 0.26F);
        int bottomGlow = darken(mixColor(accent, secondary, 0.50F), 58);
        int leftX = 10;
        int leftPanelWidth = 96;
        int centerX = 114;
        int centerWidth = 60;
        int rightX = 182;
        int rightPanelWidth = 60;
        int panelY = 14;
        int panelHeight = 28;
        int progressX = 12;
        int progressY = height - 14;
        int progressWidth = width - 24;
        int progressHeight = 9;
        String timerText = String.format("%02d:%02d", remainingSeconds / 60L, remainingSeconds % 60L);
        String clashText = raid.attackerIds().size() + "V" + raid.defenderIds().size();
        String statusText = raid.localPlayerAttacker() ? "BREACH PRESSURE" : "HOLD PRESSURE";
        String cueText = raid.localPlayerAttacker() ? "BREAK THE CORE" : "HOLD THE LINE";
        String targetLabel = raid.localPlayerAttacker() ? "TARGET" : "THREAT";
        float pulse = 0.5F + 0.5F * Mth.sin(tickTime * 0.18F);
        float surge = 0.5F + 0.5F * Mth.sin(tickTime * 0.11F + 1.7F);

        graphics.fillGradient(0, 0, width, height, 0xF0061016, 0xE4111B24);
        graphics.fill(1, 1, width - 1, height - 1, 0xE7091219);
        graphics.fillGradient(3, 3, width - 3, height - 3, 0xDB101B24, 0xB10C151D);
        graphics.fillGradient(0, 0, width, 4, withAlpha(topGlow, 205), withAlpha(topGlow, 0));
        graphics.fillGradient(0, height - 5, width, height, withAlpha(bottomGlow, 0), withAlpha(bottomGlow, 188));
        graphics.fillGradient(0, 0, 18, height, withAlpha(accentBright, 78), 0x00000000);
        graphics.fillGradient(width - 18, 0, width, height, 0x00000000, withAlpha(secondary, 76));
        graphics.fill(0, 0, width, 1, withAlpha(accentBright, 235));
        graphics.fill(0, 0, 2, height, withAlpha(accent, 215));
        graphics.fill(width - 2, 0, width, height, withAlpha(secondary, 185));
        graphics.fill(0, height - 1, width, height, withAlpha(darken(accent, 32), 188));

        drawHudBracket(graphics, 8, 7, 24, 13, accentBright);
        drawHudBracket(graphics, width - 32, 7, 24, 13, secondary);
        drawHudEnergyBand(graphics, 12, 6, width - 24, 6, accentBright, secondary, tickTime * 2.6F, 28);
        drawHudEnergyBand(graphics, 12, height - 24, width - 24, 5, secondary, accentBright, -tickTime * 2.1F, 34);

        drawHudCard(graphics, leftX, panelY, leftPanelWidth, panelHeight, accent, secondary);
        drawHudCard(graphics, centerX, panelY, centerWidth, panelHeight, secondary, accent);
        drawHudCard(graphics, rightX, panelY, rightPanelWidth, panelHeight, secondary, accent);
        drawHudObjectiveStrip(graphics, centerX - 18, 45, centerWidth + 36, 10, accentBright, secondary);

        graphics.drawString(font, Component.literal("WARSTATE"), 17, 5, withAlpha(secondary, 205), false);
        graphics.drawCenteredString(font, Component.literal(statusText), width / 2, 8, 0xFFE8F6FF);

        drawTrimmedString(graphics, font, Component.literal(raid.localRoleLabel()), leftX + 8, panelY + 5, leftPanelWidth - 16, 0xFFF7FBFF);
        graphics.drawString(font, Component.literal(targetLabel), leftX + 8, panelY + 17, withAlpha(accentBright, 208), false);
        drawTrimmedString(graphics, font, Component.literal(raid.oppositionName()), leftX + 8, panelY + 27, leftPanelWidth - 16, 0xFFD8EBF8);

        int timerColor = remainingSeconds <= 30L
                ? mixColor(0xFFFFF07B, accentBright, pulse)
                : 0xFFFFD47D;
        graphics.drawCenteredString(font, Component.literal(timerText), rightX + (rightPanelWidth / 2), panelY + 5, timerColor);
        graphics.drawString(font, Component.literal("CLASH"), rightX + 8, panelY + 17, 0xFF94AABC, false);
        graphics.drawString(font, Component.literal(clashText), rightX + rightPanelWidth - font.width(clashText), panelY + 17, 0xFFF5FBFF, false);
        graphics.drawString(font, Component.literal("ATK"), rightX + 8, panelY + 27, 0xFFFF8A8A, false);
        graphics.drawString(font, Component.literal(Integer.toString(raid.attackerIds().size())), rightX + 28, panelY + 27, 0xFFF5FBFF, false);
        graphics.drawString(font, Component.literal("DEF"), rightX + 40, panelY + 27, 0xFF7FC6FF, false);
        graphics.drawString(font, Component.literal(Integer.toString(raid.defenderIds().size())), rightX + rightPanelWidth + 5 - font.width(Integer.toString(raid.defenderIds().size())), panelY + 27, 0xFFF5FBFF, false);

        drawCenteredTrimmedString(graphics, font, Component.literal(cueText), width / 2 + 13, 47, 132, 0xFFD5E8F5);
        drawHudCenterpiece(graphics, raid, accentBright, secondary, tickTime, centerX + 4, panelY + 3, centerWidth - 8, panelHeight - 6);
        drawRaidProgressBar(graphics, font, progressX, progressY, progressWidth, progressHeight, progress,
                accent, accentBright, secondary, tickTime, surge);
    }

    private void drawHudBracket(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, withAlpha(color, 195));
        graphics.fill(x, y, x + 1, y + height, withAlpha(color, 195));
        graphics.fill(x + width - 10, y + height - 1, x + width, y + height, withAlpha(color, 135));
        graphics.fill(x + width - 1, y + 6, x + width, y + height, withAlpha(color, 135));
    }

    private void drawHudCard(GuiGraphics graphics, int x, int y, int width, int height, int accent, int secondary) {
        graphics.fillGradient(x, y, x + width, y + height, 0xD20D151C, 0xC10A1118);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xBA101B23);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, 0x780A1117);
        graphics.fill(x, y, x + 2, y + height, withAlpha(accent, 185));
        graphics.fill(x + width - 1, y + 3, x + width, y + height - 3, withAlpha(secondary, 110));
        graphics.fillGradient(x + 2, y, x + width - 2, y + 2, withAlpha(brighten(accent, 38), 145), 0x00000000);
        graphics.fillGradient(x + 4, y + height - 2, x + width - 4, y + height, 0x00000000, withAlpha(secondary, 110));
    }

    private void drawHudEnergyBand(GuiGraphics graphics, int x, int y, int width, int height,
                                   int primary, int secondary, float time, int spacing) {
        graphics.fill(x, y, x + width, y + height, 0x4A071015);
        int stride = Math.max(12, spacing);
        int offset = Mth.floor(time) % stride;
        if (offset < 0) {
            offset += stride;
        }

        for (int bandX = x - stride; bandX < x + width + stride; bandX += stride) {
            int startX = bandX + offset;
            int endX = startX + 10;
            if (endX <= x || startX >= x + width) {
                continue;
            }

            int visibleStart = Math.max(x, startX);
            int visibleEnd = Math.min(x + width, endX);
            int bandColor = ((bandX / stride) & 1) == 0 ? withAlpha(primary, 86) : withAlpha(secondary, 72);
            graphics.fillGradient(visibleStart, y, visibleEnd, y + height, bandColor, 0x00000000);
        }
    }

    private void drawHudMetric(GuiGraphics graphics, Font font, int x, int y, int width, int height,
                               String label, String value, int accent) {
        graphics.fillGradient(x, y, x + width, y + height, 0xD70B1218, 0xB7081014);
        graphics.fill(x, y, x + 2, y + height, withAlpha(accent, 188));
        graphics.drawString(font, Component.literal(label), x + 5, y + 2, withAlpha(accent, 215), false);
        graphics.drawString(font, Component.literal(value), x + width - 5 - font.width(value), y + 2, 0xFFF8FCFF, false);
    }

    private void drawHudObjectiveStrip(GuiGraphics graphics, int x, int y, int width, int height, int accent, int secondary) {
        graphics.fillGradient(x, y, x + width, y + height, 0xAA0A1217, 0x920A1116);
        graphics.fill(x, y, x + 2, y + height, withAlpha(accent, 172));
        graphics.fill(x + width - 2, y, x + width, y + height, withAlpha(secondary, 146));
        graphics.fillGradient(x + 2, y, x + width - 2, y + 1, withAlpha(accent, 136), 0x00000000);
    }

    private void drawHudCenterpiece(GuiGraphics graphics, ClientRaidState.RaidVisual raid,
                                    int accent, int secondary, float tickTime,
                                    int x, int y, int width, int height) {
        float wave = 0.5F + 0.5F * Mth.sin(tickTime * 0.15F);
        int sweepX = x + ((Mth.floor(tickTime * 2.4F) % (width + 26)) - 13);

        graphics.fillGradient(x, y, x + width, y + height, 0xD20B1318, 0xC3111821);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, 0xA0121A24);
        graphics.fillGradient(sweepX, y + 2, sweepX + 18, y + height - 2, withAlpha(secondary, 84), 0x00000000);
        graphics.fill(x + (width / 2) - 1, y + 3, x + (width / 2) + 1, y + height - 3, withAlpha(accent, 120));
        graphics.fill(x + 8, y + (height / 2) - 1, x + width - 8, y + (height / 2) + 1, withAlpha(secondary, 92));
        graphics.fill(x + 22, y + 4, x + 23, y + height - 4, withAlpha(accent, 70));
        graphics.fill(x + width - 23, y + 4, x + width - 22, y + height - 4, withAlpha(secondary, 70));

        graphics.pose().pushPose();
        graphics.pose().translate(x + (width / 2F) - 19F, y + 1.5F + (Mth.sin(tickTime * 0.19F) * 1.2F), 0F);
        graphics.pose().scale(1.08F, 1.08F, 1F);
        graphics.renderFakeItem(new ItemStack(raid.localPlayerAttacker() ? Items.NETHERITE_SWORD : Items.SHIELD), 0, 0);
        graphics.pose().popPose();

        graphics.pose().pushPose();
        graphics.pose().translate(x + (width / 2F) + 5F, y + 2.5F - (Mth.sin(tickTime * 0.23F + 1.2F) * 1.1F), 0F);
        graphics.pose().scale(0.92F + (wave * 0.06F), 0.92F + (wave * 0.06F), 1F);
        graphics.renderFakeItem(new ItemStack(wave > 0.56F ? Items.NETHER_STAR : Items.BLAZE_POWDER), 0, 0);
        graphics.pose().popPose();
    }

    private void drawRaidProgressBar(GuiGraphics graphics, Font font, int x, int y, int width, int height,
                                     float progress, int accent, int accentBright, int secondary,
                                     float tickTime, float surge) {
        int innerX = x + 2;
        int innerY = y + 2;
        int innerWidth = width - 4;
        int innerHeight = height - 4;
        int filledWidth = Mth.floor(innerWidth * progress);
        String percentText = Mth.floor(progress * 100F) + "%";
        int dividerCount = 9;

        graphics.fillGradient(x, y, x + width, y + height, 0xF00A1116, 0xE40B1318);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xE0091115);
        graphics.fill(innerX, innerY, innerX + innerWidth, innerY + innerHeight, 0xFF111A1E);

        if (filledWidth > 0) {
            graphics.fillGradient(innerX, innerY, innerX + filledWidth, innerY + innerHeight,
                    mixColor(accentBright, secondary, 0.18F), darken(accent, 18));
            graphics.fill(innerX, innerY, innerX + filledWidth, innerY + 1, withAlpha(accentBright, 215));

            for (int i = 0; i < 10; i++) {
                int stripeX = innerX - 18 + ((Mth.floor(tickTime * 3.6F) + (i * 22)) % (innerWidth + 40));
                int stripeEnd = stripeX + 10;
                if (stripeEnd <= innerX || stripeX >= innerX + filledWidth) {
                    continue;
                }

                int visibleStart = Math.max(innerX, stripeX);
                int visibleEnd = Math.min(innerX + filledWidth, stripeEnd);
                graphics.fillGradient(visibleStart, innerY, visibleEnd, innerY + innerHeight,
                        withAlpha(secondary, 112), 0x00000000);
            }

            int flareX = innerX + Mth.floor((filledWidth - 10) * surge);
            graphics.fillGradient(flareX, innerY - 1, flareX + 12, innerY + innerHeight + 1,
                    withAlpha(accentBright, 145), 0x00000000);
        }

        for (int i = 1; i < dividerCount; i++) {
            int dividerX = innerX + (innerWidth * i / dividerCount);
            graphics.fill(dividerX, innerY, dividerX + 1, innerY + innerHeight, 0x2AFFFFFF);
        }

        graphics.drawString(font, Component.literal("CORE PRESSURE"), x + 3, y - 9, withAlpha(secondary, 208), false);
        graphics.drawString(font, Component.literal(percentText), x + width - 3 - font.width(percentText), y - 9, 0xFFF4FBFF, false);
    }

    private void drawTrimmedString(GuiGraphics graphics, Font font, Component text, int x, int y, int width, int color) {
        Component draw = text;
        if (font.width(text) > width) {
            String trimmed = font.plainSubstrByWidth(text.getString(), Math.max(0, width - font.width("...")));
            draw = Component.literal(trimmed + "...");
        }
        graphics.drawString(font, draw, x, y, color, false);
    }

    private void drawCenteredTrimmedString(GuiGraphics graphics, Font font, Component text, int centerX, int y, int width, int color) {
        Component draw = text;
        if (font.width(text) > width) {
            String trimmed = font.plainSubstrByWidth(text.getString(), Math.max(0, width - font.width("...")));
            draw = Component.literal(trimmed + "...");
        }
        graphics.drawString(font, draw, centerX - (font.width(draw) / 2), y, color, false);
    }

    private void renderOwnedCoreZoneRing(VertexConsumer fillBuffer, VertexConsumer lineBuffer, PoseStack poseStack, LocalPlayer localPlayer) {
        ClientClaimOutlineState.ClaimOutline outline = ClientClaimOutlineState.get();
        if (outline == null || localPlayer.level() == null || !outline.dimensionId().equals(localPlayer.level().dimension().location())) {
            return;
        }

        double cullDist = FCWClientConfig.CORE_ZONE_RING_CULL_DISTANCE.get();
        double dx = localPlayer.getX() - (outline.corePos().getX() + 0.5D);
        double dz = localPlayer.getZ() - (outline.corePos().getZ() + 0.5D);
        float visibility = zoneRingRevealFactor(Math.sqrt(dx * dx + dz * dz), cullDist);
        if (visibility <= 0.0F) {
            return;
        }

        float tickTime = localPlayer.level().getGameTime() + Minecraft.getInstance().getFrameTime();
        renderCoreZoneRing(fillBuffer, lineBuffer, poseStack, outline.corePos(), outline.breachRadius(), false, tickTime, visibility);
    }

    private void renderEnemyCoreZoneRings(VertexConsumer fillBuffer, VertexConsumer lineBuffer, PoseStack poseStack, LocalPlayer localPlayer) {
        if (localPlayer.level() == null) {
            return;
        }

        ResourceLocation currentDimension = localPlayer.level().dimension().location();
        float tickTime = localPlayer.level().getGameTime() + Minecraft.getInstance().getFrameTime();
        double cullDist = FCWClientConfig.CORE_ZONE_RING_CULL_DISTANCE.get();

        for (ClientEnemyClaimOutlineState.EnemyOutline outline : ClientEnemyClaimOutlineState.all()) {
            if (!outline.dimensionId().equals(currentDimension)) {
                continue;
            }

            double dx = localPlayer.getX() - (outline.corePos().getX() + 0.5D);
            double dz = localPlayer.getZ() - (outline.corePos().getZ() + 0.5D);
            float visibility = zoneRingRevealFactor(Math.sqrt(dx * dx + dz * dz), cullDist);
            if (visibility <= 0.0F) {
                continue;
            }

            renderCoreZoneRing(fillBuffer, lineBuffer, poseStack, outline.corePos(), outline.breachRadius(), true, tickTime, visibility);
        }
    }

    private void renderCoreZoneRing(VertexConsumer fillBuffer, VertexConsumer lineBuffer, PoseStack poseStack,
                                    BlockPos corePos, int radius, boolean enemyPalette, float tickTime,
                                    float visibilityMult) {
        if (radius <= 0 || visibilityMult <= 0.0F) {
            return;
        }

        double centerX = corePos.getX() + 0.5D;
        double centerZ = corePos.getZ() + 0.5D;
        double baseY = corePos.getY() + 0.08D;
        float alpha = FCWClientConfig.CORE_ZONE_RING_ALPHA.get().floatValue() * visibilityMult;
        float pulse = 0.90F + 0.10F * Mth.sin(tickTime * 0.07F + (enemyPalette ? 0.8F : 0.0F));
        float r = enemyPalette ? 1.0F : 0.96F;
        float g = enemyPalette ? 0.22F : 0.96F;
        float b = enemyPalette ? 0.24F : 1.0F;
        float glowAlpha = alpha * pulse;
        double shellMinY = baseY + 0.04D;
        double shellMaxY = baseY + 1.04D;

        renderCoreZoneEdgeBand(fillBuffer, poseStack,
                centerX, centerZ, radius - 0.24D, radius + 0.24D,
                shellMinY, shellMaxY,
                r, g, b,
                glowAlpha * 0.14F, glowAlpha * 0.14F);

        renderCircularRingLine(lineBuffer, poseStack, centerX, shellMinY + 0.02D, centerZ, radius - 0.02D,
                tickTime * (enemyPalette ? 0.016F : -0.012F),
                r, g, b, glowAlpha * 0.92F);
        renderCircularRingLine(lineBuffer, poseStack, centerX, baseY + 0.52D, centerZ, radius + 0.02D,
                tickTime * (enemyPalette ? -0.012F : 0.016F),
                clamp01(r * 0.92F), clamp01(g * 0.92F), clamp01(b * 0.92F), glowAlpha * 0.52F);
        renderCircularRingLine(lineBuffer, poseStack, centerX, shellMaxY + 0.01D, centerZ, radius - 0.01D,
                tickTime * (enemyPalette ? 0.010F : -0.008F),
                clamp01(r * 1.04F), clamp01(g * 1.04F), clamp01(b * 1.04F), glowAlpha * 0.74F);
    }

    private void renderOwnedClaimOutline(VertexConsumer fillBuffer, PoseStack poseStack, LocalPlayer localPlayer) {
        if (!FCWClientConfig.ENABLE_OWN_CLAIM_OUTLINE.get()) {
            return;
        }

        ClientClaimOutlineState.ClaimOutline outline = ClientClaimOutlineState.get();
        if (outline == null || localPlayer.level() == null || !outline.dimensionId().equals(localPlayer.level().dimension().location())) {
            CLAIM_SEGMENT_VISIBILITY.clear();
            animatedOwnedOutlineSignature = Long.MIN_VALUE;
            return;
        }

        Set<Long> chunkLongs = outline.chunkLongsView();
        if (chunkLongs.isEmpty()) {
            CLAIM_SEGMENT_VISIBILITY.clear();
            animatedOwnedOutlineSignature = computeOwnedOutlineSignature(outline);
            return;
        }

        long outlineSignature = computeOwnedOutlineSignature(outline);
        if (outlineSignature != animatedOwnedOutlineSignature) {
            CLAIM_SEGMENT_VISIBILITY.clear();
            animatedOwnedOutlineSignature = outlineSignature;
            lastClaimAnimationMillis = Util.getMillis();
        }

        ChunkPos playerChunk = localPlayer.chunkPosition();
        double revealDistance = Math.max(2.0D, FCWClientConfig.OWN_CLAIM_OUTLINE_REVEAL_DISTANCE.get());
        double outerRevealDistance = revealDistance + Math.max(CLAIM_OUTLINE_FADE_BAND_MIN, revealDistance * CLAIM_OUTLINE_FADE_BAND_RATIO);
        int chunkRadius = Math.max(2, Mth.ceil((outerRevealDistance + 16.0D) / 16.0D) + 1);

        float pulse = 0.992F + 0.008F * Mth.sin((localPlayer.level().getGameTime() + Minecraft.getInstance().getFrameTime()) * 0.018F);
        int baseAlpha = Mth.clamp((int) (255F * FCWClientConfig.OWN_CLAIM_OUTLINE_ALPHA.get().floatValue() * pulse), 0, 255);
        if (baseAlpha <= 0) {
            return;
        }

        long now = Util.getMillis();
        float deltaSeconds = Mth.clamp((now - lastClaimAnimationMillis) / 1000.0F, 0.0F, 0.20F);
        lastClaimAnimationMillis = now;

        Vec3 playerPos = localPlayer.position();
        double wallBaseY = outline.corePos().getY() + 0.08D;
        Set<ClaimSegmentKey> touchedSegments = new HashSet<>();
        Set<PostKey> renderedPosts = new HashSet<>();
        EnumMap<Direction, PathGate> gates = computePathGates(chunkLongs, outline.corePos(), outline.breachPathWidth());

        for (Long chunkLong : chunkLongs) {
            ChunkPos chunkPos = new ChunkPos(chunkLong);
            if (Math.max(Math.abs(chunkPos.x - playerChunk.x), Math.abs(chunkPos.z - playerChunk.z)) > chunkRadius) {
                continue;
            }

            if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x, chunkPos.z - 1))) {
                renderClaimBoundaryWithGate(fillBuffer, poseStack,
                        chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                        chunkPos.getMaxBlockX() + 1D, chunkPos.getMinBlockZ(),
                        baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                        revealDistance, outerRevealDistance, deltaSeconds, touchedSegments,
                        matchingGate(gates, chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(), chunkPos.getMaxBlockX() + 1D, chunkPos.getMinBlockZ()),
                        false, renderedPosts);
            }
            if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x + 1, chunkPos.z))) {
                renderClaimBoundaryWithGate(fillBuffer, poseStack,
                        chunkPos.getMaxBlockX() + 1D, chunkPos.getMinBlockZ(),
                        chunkPos.getMaxBlockX() + 1D, chunkPos.getMaxBlockZ() + 1D,
                        baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                        revealDistance, outerRevealDistance, deltaSeconds, touchedSegments,
                        matchingGate(gates, chunkPos.getMaxBlockX() + 1D, chunkPos.getMinBlockZ(), chunkPos.getMaxBlockX() + 1D, chunkPos.getMaxBlockZ() + 1D),
                        false, renderedPosts);
            }
            if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x, chunkPos.z + 1))) {
                renderClaimBoundaryWithGate(fillBuffer, poseStack,
                        chunkPos.getMaxBlockX() + 1D, chunkPos.getMaxBlockZ() + 1D,
                        chunkPos.getMinBlockX(), chunkPos.getMaxBlockZ() + 1D,
                        baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                        revealDistance, outerRevealDistance, deltaSeconds, touchedSegments,
                        matchingGate(gates, chunkPos.getMaxBlockX() + 1D, chunkPos.getMaxBlockZ() + 1D, chunkPos.getMinBlockX(), chunkPos.getMaxBlockZ() + 1D),
                        false, renderedPosts);
            }
            if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x - 1, chunkPos.z))) {
                renderClaimBoundaryWithGate(fillBuffer, poseStack,
                        chunkPos.getMinBlockX(), chunkPos.getMaxBlockZ() + 1D,
                        chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                        baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                        revealDistance, outerRevealDistance, deltaSeconds, touchedSegments,
                        matchingGate(gates, chunkPos.getMinBlockX(), chunkPos.getMaxBlockZ() + 1D, chunkPos.getMinBlockX(), chunkPos.getMinBlockZ()),
                        false, renderedPosts);
            }
        }

        for (PathGate gate : gates.values()) {
            renderArch(fillBuffer, poseStack, gate, wallBaseY, baseAlpha, playerPos.x, playerPos.z, revealDistance, outerRevealDistance, false, renderedPosts);
            renderBreachPathOutline(fillBuffer, poseStack, outline.corePos(), outline.breachRadius(), outline.breachPathWidth(), gate,
                    wallBaseY, baseAlpha, playerPos.x, playerPos.z, revealDistance, outerRevealDistance,
                    false, renderedPosts);
        }

        if (!CLAIM_SEGMENT_VISIBILITY.isEmpty()) {
            for (ClaimSegmentKey cachedKey : Set.copyOf(CLAIM_SEGMENT_VISIBILITY.keySet())) {
                if (touchedSegments.contains(cachedKey)) {
                    continue;
                }

                float animatedReveal = animateSegmentVisibility(cachedKey, 0.0F, deltaSeconds);
                int cachedAlpha = Mth.clamp((int) (baseAlpha * easedVisibility(animatedReveal)), 0, 255);
                if (cachedAlpha > 0) {
                    renderWallSegment(fillBuffer, poseStack,
                            cachedKey.startX(), cachedKey.startZ(), cachedKey.endX(), cachedKey.endZ(),
                            wallBaseY, cachedAlpha, renderedPosts);
                }
            }
        }

        CLAIM_SEGMENT_VISIBILITY.entrySet().removeIf(entry -> entry.getValue() <= CLAIM_OUTLINE_MIN_SEGMENT_VISIBILITY);
    }

    private void renderEnemyClaimOutlines(VertexConsumer fillBuffer, PoseStack poseStack, LocalPlayer localPlayer) {
        List<ClientEnemyClaimOutlineState.EnemyOutline> enemyOutlines = ClientEnemyClaimOutlineState.all();
        if (enemyOutlines.isEmpty() || localPlayer.level() == null) {
            return;
        }

        ResourceLocation currentDimension = localPlayer.level().dimension().location();
        ChunkPos playerChunk = localPlayer.chunkPosition();
        Vec3 playerPos = localPlayer.position();

        double revealDistance = Math.max(2.0D, FCWClientConfig.OWN_CLAIM_OUTLINE_REVEAL_DISTANCE.get());
        double outerRevealDistance = revealDistance + Math.max(CLAIM_OUTLINE_FADE_BAND_MIN, revealDistance * CLAIM_OUTLINE_FADE_BAND_RATIO);
        int chunkRadius = Math.max(2, Mth.ceil((outerRevealDistance + 16.0D) / 16.0D) + 1);

        float pulse = 0.992F + 0.008F * Mth.sin((localPlayer.level().getGameTime() + Minecraft.getInstance().getFrameTime()) * 0.018F);
        int baseAlpha = Mth.clamp((int) (255F * FCWClientConfig.OWN_CLAIM_OUTLINE_ALPHA.get().floatValue() * pulse), 0, 255);
        if (baseAlpha <= 0) {
            return;
        }

        for (ClientEnemyClaimOutlineState.EnemyOutline outline : enemyOutlines) {
            if (!outline.dimensionId().equals(currentDimension)) {
                continue;
            }

            Set<Long> chunkLongs = outline.chunkLongsView();
            if (chunkLongs.isEmpty()) {
                continue;
            }

            double wallBaseY = outline.corePos().getY() + 0.08D;
            EnumMap<Direction, PathGate> gates = computePathGates(chunkLongs, outline.corePos(), outline.breachPathWidth());
            Set<PostKey> renderedPosts = new HashSet<>();

            for (Long chunkLong : chunkLongs) {
                ChunkPos chunkPos = new ChunkPos(chunkLong);
                if (Math.max(Math.abs(chunkPos.x - playerChunk.x), Math.abs(chunkPos.z - playerChunk.z)) > chunkRadius) {
                    continue;
                }

                if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x, chunkPos.z - 1))) {
                    renderClaimBoundaryWithGate(fillBuffer, poseStack,
                            chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                            chunkPos.getMaxBlockX() + 1D, chunkPos.getMinBlockZ(),
                            baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                            revealDistance, outerRevealDistance, 0.0F, null,
                            matchingGate(gates, chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(), chunkPos.getMaxBlockX() + 1D, chunkPos.getMinBlockZ()),
                            true, renderedPosts);
                }
                if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x + 1, chunkPos.z))) {
                    renderClaimBoundaryWithGate(fillBuffer, poseStack,
                            chunkPos.getMaxBlockX() + 1D, chunkPos.getMinBlockZ(),
                            chunkPos.getMaxBlockX() + 1D, chunkPos.getMaxBlockZ() + 1D,
                            baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                            revealDistance, outerRevealDistance, 0.0F, null,
                            matchingGate(gates, chunkPos.getMaxBlockX() + 1D, chunkPos.getMinBlockZ(), chunkPos.getMaxBlockX() + 1D, chunkPos.getMaxBlockZ() + 1D),
                            true, renderedPosts);
                }
                if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x, chunkPos.z + 1))) {
                    renderClaimBoundaryWithGate(fillBuffer, poseStack,
                            chunkPos.getMaxBlockX() + 1D, chunkPos.getMaxBlockZ() + 1D,
                            chunkPos.getMinBlockX(), chunkPos.getMaxBlockZ() + 1D,
                            baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                            revealDistance, outerRevealDistance, 0.0F, null,
                            matchingGate(gates, chunkPos.getMaxBlockX() + 1D, chunkPos.getMaxBlockZ() + 1D, chunkPos.getMinBlockX(), chunkPos.getMaxBlockZ() + 1D),
                            true, renderedPosts);
                }
                if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x - 1, chunkPos.z))) {
                    renderClaimBoundaryWithGate(fillBuffer, poseStack,
                            chunkPos.getMinBlockX(), chunkPos.getMaxBlockZ() + 1D,
                            chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                            baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                            revealDistance, outerRevealDistance, 0.0F, null,
                            matchingGate(gates, chunkPos.getMinBlockX(), chunkPos.getMaxBlockZ() + 1D, chunkPos.getMinBlockX(), chunkPos.getMinBlockZ()),
                            true, renderedPosts);
                }
            }

            for (PathGate gate : gates.values()) {
                renderArch(fillBuffer, poseStack, gate, wallBaseY, baseAlpha, playerPos.x, playerPos.z, revealDistance, outerRevealDistance, true, renderedPosts);
                renderBreachPathOutline(fillBuffer, poseStack, outline.corePos(), outline.breachRadius(), outline.breachPathWidth(), gate,
                        wallBaseY, baseAlpha, playerPos.x, playerPos.z, revealDistance, outerRevealDistance,
                        true, renderedPosts);
            }
        }
    }

    private long computeOwnedOutlineSignature(ClientClaimOutlineState.ClaimOutline outline) {
        long hash = 1469598103934665603L;
        hash = mixHash(hash, outline.dimensionId().hashCode());
        hash = mixHash(hash, outline.corePos().asLong());
        hash = mixHash(hash, outline.breachPathWidth());

        for (Long chunkLong : outline.chunkLongsView()) {
            hash = mixHash(hash, chunkLong);
        }
        return hash;
    }

    private long mixHash(long current, long value) {
        current ^= value;
        current *= 1099511628211L;
        return current;
    }

    private void renderClaimBoundaryWithGate(VertexConsumer fillBuffer, PoseStack poseStack,
                                             double startX, double startZ, double endX, double endZ,
                                             int baseAlpha, double baseY,
                                             double playerX, double playerZ,
                                             double revealDistance, double outerRevealDistance,
                                             float deltaSeconds, Set<ClaimSegmentKey> touchedSegments,
                                             PathGate gate, boolean enemyPalette, Set<PostKey> renderedPosts) {
        boolean vertical = Math.abs(endX - startX) < 1.0E-4D;
        double axisCoord = vertical ? startX : startZ;
        double edgeMinAlong = Math.min(vertical ? startZ : startX, vertical ? endZ : endX);
        double edgeMaxAlong = Math.max(vertical ? startZ : startX, vertical ? endZ : endX);
        boolean ascending = vertical ? endZ >= startZ : endX >= startX;

        if (gate == null) {
            renderBoundaryInterval(fillBuffer, poseStack,
                    vertical, axisCoord, edgeMinAlong, edgeMaxAlong, ascending,
                    baseAlpha, baseY, playerX, playerZ,
                    revealDistance, outerRevealDistance, deltaSeconds,
                    touchedSegments, enemyPalette, renderedPosts);
            return;
        }

        double lowerEnd = Math.max(edgeMinAlong, gate.minAlong() - GATE_SUPPORT_CLEARANCE);
        double upperStart = Math.min(edgeMaxAlong, gate.maxAlong() + GATE_SUPPORT_CLEARANCE);

        renderBoundaryInterval(fillBuffer, poseStack,
                vertical, axisCoord, edgeMinAlong, lowerEnd, ascending,
                baseAlpha, baseY, playerX, playerZ,
                revealDistance, outerRevealDistance, deltaSeconds,
                touchedSegments, enemyPalette, renderedPosts);

        renderBoundaryInterval(fillBuffer, poseStack,
                vertical, axisCoord, upperStart, edgeMaxAlong, ascending,
                baseAlpha, baseY, playerX, playerZ,
                revealDistance, outerRevealDistance, deltaSeconds,
                touchedSegments, enemyPalette, renderedPosts);
    }

    private void renderBoundaryInterval(VertexConsumer fillBuffer, PoseStack poseStack,
                                        boolean vertical, double axisCoord, double minAlong, double maxAlong, boolean ascending,
                                        int baseAlpha, double baseY,
                                        double playerX, double playerZ,
                                        double revealDistance, double outerRevealDistance,
                                        float deltaSeconds, Set<ClaimSegmentKey> touchedSegments,
                                        boolean enemyPalette, Set<PostKey> renderedPosts) {
        if (maxAlong - minAlong <= MIN_RENDERABLE_INTERVAL) {
            return;
        }

        double startAlong = ascending ? minAlong : maxAlong;
        double endAlong = ascending ? maxAlong : minAlong;
        double startX = vertical ? axisCoord : startAlong;
        double startZ = vertical ? startAlong : axisCoord;
        double endX = vertical ? axisCoord : endAlong;
        double endZ = vertical ? endAlong : axisCoord;

        double edgeLength = Math.max(Math.abs(endX - startX), Math.abs(endZ - startZ));
        int segments = Math.max(1, Mth.ceil(edgeLength / CLAIM_SEGMENT_LENGTH));

        for (int segment = 0; segment < segments; segment++) {
            double segmentStartProgress = segment / (double) segments;
            double segmentEndProgress = (segment + 1) / (double) segments;

            double segmentStartX = Mth.lerp(segmentStartProgress, startX, endX);
            double segmentStartZ = Mth.lerp(segmentStartProgress, startZ, endZ);
            double segmentEndX = Mth.lerp(segmentEndProgress, startX, endX);
            double segmentEndZ = Mth.lerp(segmentEndProgress, startZ, endZ);

            float reveal = edgeRevealFactor(playerX, playerZ, segmentStartX, segmentStartZ, segmentEndX, segmentEndZ, revealDistance, outerRevealDistance);

            int segmentAlpha;
            if (touchedSegments != null) {
                ClaimSegmentKey key = ClaimSegmentKey.of(segmentStartX, segmentStartZ, segmentEndX, segmentEndZ);
                touchedSegments.add(key);
                float animatedReveal = animateSegmentVisibility(key, reveal, deltaSeconds);
                float finalVisibility = easedVisibility(animatedReveal);
                segmentAlpha = Mth.clamp((int) (baseAlpha * finalVisibility), 0, 255);
            } else {
                segmentAlpha = Mth.clamp((int) (baseAlpha * reveal), 0, 255);
            }

            if (segmentAlpha <= 0) {
                continue;
            }

            if (enemyPalette) {
                renderEnemyWallSegment(fillBuffer, poseStack, segmentStartX, segmentStartZ, segmentEndX, segmentEndZ, baseY, segmentAlpha, renderedPosts);
            } else {
                renderWallSegment(fillBuffer, poseStack, segmentStartX, segmentStartZ, segmentEndX, segmentEndZ, baseY, segmentAlpha, renderedPosts);
            }
        }
    }

    private void renderBreachPathOutline(VertexConsumer fillBuffer, PoseStack poseStack,
                                         BlockPos corePos, int breachRadius, int breachPathWidth, PathGate gate,
                                         double baseY, int baseAlpha,
                                         double playerX, double playerZ,
                                         double revealDistance, double outerRevealDistance,
                                         boolean enemyPalette, Set<PostKey> renderedPosts) {
        if (breachPathWidth <= 0) {
            return;
        }

        double centerX = corePos.getX() + 0.5D;
        double centerZ = corePos.getZ() + 0.5D;
        double halfWidth = CoreBreachShape.halfPathWidth(breachPathWidth);

        if (gate.vertical()) {
            double sign = gate.direction() == Direction.EAST ? 1.0D : -1.0D;
            double pathStartX = centerX + (breachRadius * sign) + (PATH_START_PADDING * sign);
            double pathEndX = gate.axisCoord() - (PATH_END_PADDING * sign);

            if ((sign > 0.0D && pathEndX <= pathStartX + MIN_RENDERABLE_INTERVAL) || (sign < 0.0D && pathEndX >= pathStartX - MIN_RENDERABLE_INTERVAL)) {
                return;
            }

            double sideMinZ = centerZ - halfWidth;
            double sideMaxZ = centerZ + halfWidth;

            renderPathSide(fillBuffer, poseStack,
                    pathStartX, sideMinZ, pathEndX, sideMinZ,
                    baseY, baseAlpha, playerX, playerZ, revealDistance, outerRevealDistance,
                    enemyPalette, renderedPosts);

            renderPathSide(fillBuffer, poseStack,
                    pathStartX, sideMaxZ, pathEndX, sideMaxZ,
                    baseY, baseAlpha, playerX, playerZ, revealDistance, outerRevealDistance,
                    enemyPalette, renderedPosts);
        } else {
            double sign = gate.direction() == Direction.SOUTH ? 1.0D : -1.0D;
            double pathStartZ = centerZ + (breachRadius * sign) + (PATH_START_PADDING * sign);
            double pathEndZ = gate.axisCoord() - (PATH_END_PADDING * sign);

            if ((sign > 0.0D && pathEndZ <= pathStartZ + MIN_RENDERABLE_INTERVAL) || (sign < 0.0D && pathEndZ >= pathStartZ - MIN_RENDERABLE_INTERVAL)) {
                return;
            }

            double sideMinX = centerX - halfWidth;
            double sideMaxX = centerX + halfWidth;

            renderPathSide(fillBuffer, poseStack,
                    sideMinX, pathStartZ, sideMinX, pathEndZ,
                    baseY, baseAlpha, playerX, playerZ, revealDistance, outerRevealDistance,
                    enemyPalette, renderedPosts);

            renderPathSide(fillBuffer, poseStack,
                    sideMaxX, pathStartZ, sideMaxX, pathEndZ,
                    baseY, baseAlpha, playerX, playerZ, revealDistance, outerRevealDistance,
                    enemyPalette, renderedPosts);
        }
    }

    private void renderPathSide(VertexConsumer fillBuffer, PoseStack poseStack,
                                double startX, double startZ, double endX, double endZ,
                                double baseY, int baseAlpha,
                                double playerX, double playerZ,
                                double revealDistance, double outerRevealDistance,
                                boolean enemyPalette, Set<PostKey> renderedPosts) {
        double length = Math.max(Math.abs(endX - startX), Math.abs(endZ - startZ));
        int segments = Math.max(1, Mth.ceil(length / PATH_SEGMENT_LENGTH));

        for (int segment = 0; segment < segments; segment++) {
            double segmentStartProgress = segment / (double) segments;
            double segmentEndProgress = (segment + 1) / (double) segments;

            double segmentStartX = Mth.lerp(segmentStartProgress, startX, endX);
            double segmentStartZ = Mth.lerp(segmentStartProgress, startZ, endZ);
            double segmentEndX = Mth.lerp(segmentEndProgress, startX, endX);
            double segmentEndZ = Mth.lerp(segmentEndProgress, startZ, endZ);

            float reveal = edgeRevealFactor(playerX, playerZ, segmentStartX, segmentStartZ, segmentEndX, segmentEndZ, revealDistance, outerRevealDistance);
            int segmentAlpha = Mth.clamp((int) (baseAlpha * smootherStep(reveal)), 0, 255);
            if (segmentAlpha <= 0) {
                continue;
            }

            renderPathSegment(fillBuffer, poseStack, segmentStartX, segmentStartZ, segmentEndX, segmentEndZ, baseY, segmentAlpha, enemyPalette, renderedPosts);
        }
    }

    private void renderPathSegment(VertexConsumer fillBuffer, PoseStack poseStack,
                                   double startX, double startZ, double endX, double endZ,
                                   double baseY, int alpha,
                                   boolean enemyPalette, Set<PostKey> renderedPosts) {
        boolean alongX = Math.abs(endX - startX) >= Math.abs(endZ - startZ);

        double trimmedStartX = startX;
        double trimmedStartZ = startZ;
        double trimmedEndX = endX;
        double trimmedEndZ = endZ;

        if (alongX) {
            if (endX >= startX) {
                trimmedStartX += PATH_POST_TRIM;
                trimmedEndX -= PATH_POST_TRIM;
            } else {
                trimmedStartX -= PATH_POST_TRIM;
                trimmedEndX += PATH_POST_TRIM;
            }
        } else {
            if (endZ >= startZ) {
                trimmedStartZ += PATH_POST_TRIM;
                trimmedEndZ -= PATH_POST_TRIM;
            } else {
                trimmedStartZ -= PATH_POST_TRIM;
                trimmedEndZ += PATH_POST_TRIM;
            }
        }

        renderPathPostOnce(renderedPosts, fillBuffer, poseStack, startX, startZ, baseY, alpha, enemyPalette);
        renderPathPostOnce(renderedPosts, fillBuffer, poseStack, endX, endZ, baseY, alpha, enemyPalette);

        boolean hasTrimmedRail = alongX
                ? Math.abs(trimmedEndX - trimmedStartX) > 0.08D
                : Math.abs(trimmedEndZ - trimmedStartZ) > 0.08D;

        if (!hasTrimmedRail) {
            return;
        }

        if (enemyPalette) {
            renderSolidBeam(fillBuffer, poseStack,
                    trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                    baseY + 0.22D,
                    baseY + 0.34D,
                    PATH_RAIL_HALF_THICKNESS - 0.01D,
                    alpha,
                    0.26F, 0.08F, 0.07F);

            renderSolidBeam(fillBuffer, poseStack,
                    trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                    baseY + 0.52D,
                    baseY + 0.66D,
                    PATH_RAIL_HALF_THICKNESS,
                    alpha,
                    0.36F, 0.11F, 0.09F);

            renderSolidBeam(fillBuffer, poseStack,
                    trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                    baseY + 0.82D,
                    baseY + 0.96D,
                    PATH_RAIL_HALF_THICKNESS + 0.01D,
                    alpha,
                    0.46F, 0.13F, 0.10F);
        } else {
            renderSolidBeam(fillBuffer, poseStack,
                    trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                    baseY + 0.22D,
                    baseY + 0.34D,
                    PATH_RAIL_HALF_THICKNESS - 0.01D,
                    alpha,
                    0.26F, 0.18F, 0.10F);

            renderSolidBeam(fillBuffer, poseStack,
                    trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                    baseY + 0.52D,
                    baseY + 0.66D,
                    PATH_RAIL_HALF_THICKNESS,
                    alpha,
                    0.39F, 0.27F, 0.15F);

            renderSolidBeam(fillBuffer, poseStack,
                    trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                    baseY + 0.82D,
                    baseY + 0.96D,
                    PATH_RAIL_HALF_THICKNESS + 0.01D,
                    alpha,
                    0.52F, 0.38F, 0.22F);
        }

        renderPathStakes(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY, alpha, enemyPalette);
    }

    private void renderArch(VertexConsumer fillBuffer, PoseStack poseStack,
                            PathGate gate, double baseY, int baseAlpha,
                            double playerX, double playerZ,
                            double revealDistance, double outerRevealDistance,
                            boolean enemyPalette, Set<PostKey> renderedPosts) {
        double startX = gate.vertical() ? gate.axisCoord() : gate.minAlong();
        double startZ = gate.vertical() ? gate.minAlong() : gate.axisCoord();
        double endX = gate.vertical() ? gate.axisCoord() : gate.maxAlong();
        double endZ = gate.vertical() ? gate.maxAlong() : gate.axisCoord();

        float reveal = edgeRevealFactor(playerX, playerZ, startX, startZ, endX, endZ, revealDistance, outerRevealDistance);
        float visibility = smootherStep(reveal);
        int alpha = Mth.clamp((int) (baseAlpha * visibility), 0, 255);
        if (alpha <= 0) {
            return;
        }

        renderArchPostOnce(renderedPosts, fillBuffer, poseStack,
                gate.vertical() ? gate.axisCoord() : gate.minAlong(),
                gate.vertical() ? gate.minAlong() : gate.axisCoord(),
                baseY, alpha, enemyPalette);

        renderArchPostOnce(renderedPosts, fillBuffer, poseStack,
                gate.vertical() ? gate.axisCoord() : gate.maxAlong(),
                gate.vertical() ? gate.maxAlong() : gate.axisCoord(),
                baseY, alpha, enemyPalette);

        double lintelStartAlong = gate.minAlong() + GATE_LINTEL_INSET;
        double lintelEndAlong = gate.maxAlong() - GATE_LINTEL_INSET;
        if (lintelEndAlong - lintelStartAlong <= 0.12D) {
            return;
        }

        if (gate.vertical()) {
            renderSolidBeam(fillBuffer, poseStack,
                    gate.axisCoord(), lintelStartAlong,
                    gate.axisCoord(), lintelEndAlong,
                    baseY + 1.94D, baseY + 2.12D,
                    0.18D, alpha,
                    enemyPalette ? 0.34F : 0.38F,
                    enemyPalette ? 0.10F : 0.26F,
                    enemyPalette ? 0.08F : 0.14F);

            renderSolidBeam(fillBuffer, poseStack,
                    gate.axisCoord(), lintelStartAlong,
                    gate.axisCoord(), lintelEndAlong,
                    baseY + 2.12D, baseY + 2.32D,
                    0.24D, alpha,
                    enemyPalette ? 0.48F : 0.54F,
                    enemyPalette ? 0.14F : 0.36F,
                    enemyPalette ? 0.11F : 0.20F);

            renderSolidBeam(fillBuffer, poseStack,
                    gate.axisCoord(), lintelStartAlong - 0.08D,
                    gate.axisCoord(), lintelEndAlong + 0.08D,
                    baseY + 2.32D, baseY + 2.46D,
                    0.30D, alpha,
                    enemyPalette ? 0.58F : 0.66F,
                    enemyPalette ? 0.17F : 0.44F,
                    enemyPalette ? 0.13F : 0.25F);
        } else {
            renderSolidBeam(fillBuffer, poseStack,
                    lintelStartAlong, gate.axisCoord(),
                    lintelEndAlong, gate.axisCoord(),
                    baseY + 1.94D, baseY + 2.12D,
                    0.18D, alpha,
                    enemyPalette ? 0.34F : 0.38F,
                    enemyPalette ? 0.10F : 0.26F,
                    enemyPalette ? 0.08F : 0.14F);

            renderSolidBeam(fillBuffer, poseStack,
                    lintelStartAlong, gate.axisCoord(),
                    lintelEndAlong, gate.axisCoord(),
                    baseY + 2.12D, baseY + 2.32D,
                    0.24D, alpha,
                    enemyPalette ? 0.48F : 0.54F,
                    enemyPalette ? 0.14F : 0.36F,
                    enemyPalette ? 0.11F : 0.20F);

            renderSolidBeam(fillBuffer, poseStack,
                    lintelStartAlong - 0.08D, gate.axisCoord(),
                    lintelEndAlong + 0.08D, gate.axisCoord(),
                    baseY + 2.32D, baseY + 2.46D,
                    0.30D, alpha,
                    enemyPalette ? 0.58F : 0.66F,
                    enemyPalette ? 0.17F : 0.44F,
                    enemyPalette ? 0.13F : 0.25F);
        }

        renderGateTeeth(fillBuffer, poseStack, gate,
                lintelStartAlong, lintelEndAlong,
                baseY, alpha, enemyPalette);
    }

    private void renderEnemyWallSegment(VertexConsumer fillBuffer, PoseStack poseStack,
                                        double startX, double startZ, double endX, double endZ,
                                        double baseY, int alpha, Set<PostKey> renderedPosts) {
        boolean alongX = Math.abs(endX - startX) >= Math.abs(endZ - startZ);

        double trimmedStartX = startX;
        double trimmedStartZ = startZ;
        double trimmedEndX = endX;
        double trimmedEndZ = endZ;

        if (alongX) {
            if (endX >= startX) {
                trimmedStartX += CLAIM_POST_TRIM;
                trimmedEndX -= CLAIM_POST_TRIM;
            } else {
                trimmedStartX -= CLAIM_POST_TRIM;
                trimmedEndX += CLAIM_POST_TRIM;
            }
        } else {
            if (endZ >= startZ) {
                trimmedStartZ += CLAIM_POST_TRIM;
                trimmedEndZ -= CLAIM_POST_TRIM;
            } else {
                trimmedStartZ -= CLAIM_POST_TRIM;
                trimmedEndZ += CLAIM_POST_TRIM;
            }
        }

        renderEnemyWallPostOnce(renderedPosts, fillBuffer, poseStack, startX, startZ, baseY, alpha);
        renderEnemyWallPostOnce(renderedPosts, fillBuffer, poseStack, endX, endZ, baseY, alpha);

        boolean hasTrimmedRails = alongX
                ? Math.abs(trimmedEndX - trimmedStartX) > 0.12D
                : Math.abs(trimmedEndZ - trimmedStartZ) > 0.12D;

        if (!hasTrimmedRails) {
            return;
        }

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 0.22D,
                baseY + 0.34D,
                CLAIM_RAIL_HALF_THICKNESS - 0.005D,
                alpha,
                0.24F, 0.07F, 0.06F);

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 0.74D,
                baseY + 0.90D,
                CLAIM_RAIL_HALF_THICKNESS - 0.005D,
                alpha,
                0.35F, 0.10F, 0.08F);

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 1.26D,
                baseY + 1.42D,
                CLAIM_RAIL_HALF_THICKNESS - 0.01D,
                alpha,
                0.43F, 0.12F, 0.10F);

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 1.68D,
                baseY + 1.80D,
                CLAIM_RAIL_HALF_THICKNESS - 0.02D,
                alpha,
                0.52F, 0.15F, 0.12F);

        renderClaimStakes(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY, alpha, true);
    }

    private void renderEnemyWallPostOnce(Set<PostKey> renderedPosts, VertexConsumer fillBuffer, PoseStack poseStack,
                                         double x, double z, double baseY, int alpha) {
        if (renderedPosts.add(PostKey.of(x, z, baseY, 0))) {
            renderEnemyWallPost(fillBuffer, poseStack, x, z, baseY, alpha);
        }
    }

    private void renderEnemyWallPost(VertexConsumer fillBuffer, PoseStack poseStack,
                                     double x, double z, double baseY, int alpha) {
        double minX = x - CLAIM_POST_HALF_WIDTH;
        double maxX = x + CLAIM_POST_HALF_WIDTH;
        double minZ = z - CLAIM_POST_HALF_WIDTH;
        double maxZ = z + CLAIM_POST_HALF_WIDTH;

        double footTop = baseY + 0.26D;
        double postTop = baseY + CLAIM_WALL_HEIGHT - 0.16D;

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.065D, baseY, minZ - 0.065D,
                maxX + 0.065D, baseY + 0.14D, maxZ + 0.065D,
                alpha,
                0.13F, 0.04F, 0.04F);

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.035D, baseY + 0.14D, minZ - 0.035D,
                maxX + 0.035D, footTop, maxZ + 0.035D,
                alpha,
                0.20F, 0.06F, 0.05F);

        renderSolidBox(fillBuffer, poseStack,
                minX, footTop, minZ,
                maxX, postTop, maxZ,
                alpha,
                0.34F, 0.10F, 0.09F);

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.020D, baseY + 1.12D, minZ - 0.020D,
                maxX + 0.020D, baseY + 1.28D, maxZ + 0.020D,
                alpha,
                0.25F, 0.08F, 0.07F);

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.035D, postTop - 0.10D, minZ - 0.035D,
                maxX + 0.035D, postTop + 0.02D, maxZ + 0.035D,
                alpha,
                0.40F, 0.11F, 0.10F);

        renderSolidBox(fillBuffer, poseStack,
                minX - CLAIM_POST_CAP_OVERHANG, postTop + 0.02D, minZ - CLAIM_POST_CAP_OVERHANG,
                maxX + CLAIM_POST_CAP_OVERHANG, baseY + CLAIM_WALL_HEIGHT, maxZ + CLAIM_POST_CAP_OVERHANG,
                alpha,
                0.50F, 0.15F, 0.12F);

        renderSolidBox(fillBuffer, poseStack,
                x - 0.050D, baseY + CLAIM_WALL_HEIGHT, z - 0.050D,
                x + 0.050D, baseY + CLAIM_WALL_HEIGHT + 0.12D, z + 0.050D,
                alpha,
                0.58F, 0.19F, 0.13F);
    }

    private void renderWallSegment(VertexConsumer fillBuffer, PoseStack poseStack,
                                   double startX, double startZ, double endX, double endZ,
                                   double baseY, int alpha, Set<PostKey> renderedPosts) {
        boolean alongX = Math.abs(endX - startX) >= Math.abs(endZ - startZ);

        double trimmedStartX = startX;
        double trimmedStartZ = startZ;
        double trimmedEndX = endX;
        double trimmedEndZ = endZ;

        if (alongX) {
            if (endX >= startX) {
                trimmedStartX += CLAIM_POST_TRIM;
                trimmedEndX -= CLAIM_POST_TRIM;
            } else {
                trimmedStartX -= CLAIM_POST_TRIM;
                trimmedEndX += CLAIM_POST_TRIM;
            }
        } else {
            if (endZ >= startZ) {
                trimmedStartZ += CLAIM_POST_TRIM;
                trimmedEndZ -= CLAIM_POST_TRIM;
            } else {
                trimmedStartZ -= CLAIM_POST_TRIM;
                trimmedEndZ += CLAIM_POST_TRIM;
            }
        }

        renderWallPostOnce(renderedPosts, fillBuffer, poseStack, startX, startZ, baseY, alpha);
        renderWallPostOnce(renderedPosts, fillBuffer, poseStack, endX, endZ, baseY, alpha);

        boolean hasTrimmedRails = alongX
                ? Math.abs(trimmedEndX - trimmedStartX) > 0.12D
                : Math.abs(trimmedEndZ - trimmedStartZ) > 0.12D;

        if (!hasTrimmedRails) {
            return;
        }

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 0.22D,
                baseY + 0.34D,
                CLAIM_RAIL_HALF_THICKNESS - 0.005D,
                alpha,
                0.25F, 0.17F, 0.10F);

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 0.74D,
                baseY + 0.90D,
                CLAIM_RAIL_HALF_THICKNESS - 0.005D,
                alpha,
                0.39F, 0.27F, 0.16F);

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 1.26D,
                baseY + 1.42D,
                CLAIM_RAIL_HALF_THICKNESS - 0.01D,
                alpha,
                0.47F, 0.33F, 0.19F);

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 1.68D,
                baseY + 1.80D,
                CLAIM_RAIL_HALF_THICKNESS - 0.02D,
                alpha,
                0.58F, 0.43F, 0.26F);

        renderClaimStakes(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY, alpha, false);
    }

    private void renderWallPostOnce(Set<PostKey> renderedPosts, VertexConsumer fillBuffer, PoseStack poseStack,
                                    double x, double z, double baseY, int alpha) {
        if (renderedPosts.add(PostKey.of(x, z, baseY, 0))) {
            renderWallPost(fillBuffer, poseStack, x, z, baseY, alpha);
        }
    }

    private void renderPathPostOnce(Set<PostKey> renderedPosts, VertexConsumer fillBuffer, PoseStack poseStack,
                                    double x, double z, double baseY, int alpha, boolean enemyPalette) {
        if (renderedPosts.add(PostKey.of(x, z, baseY, 1))) {
            renderPathPost(fillBuffer, poseStack, x, z, baseY, alpha, enemyPalette);
        }
    }

    private void renderArchPostOnce(Set<PostKey> renderedPosts, VertexConsumer fillBuffer, PoseStack poseStack,
                                    double x, double z, double baseY, int alpha, boolean enemyPalette) {
        if (renderedPosts.add(PostKey.of(x, z, baseY, 2))) {
            renderArchPost(fillBuffer, poseStack, x, z, baseY, alpha, enemyPalette);
        }
    }

    private void renderPathPost(VertexConsumer fillBuffer, PoseStack poseStack,
                                double x, double z, double baseY, int alpha, boolean enemyPalette) {
        double minX = x - PATH_POST_HALF_WIDTH;
        double maxX = x + PATH_POST_HALF_WIDTH;
        double minZ = z - PATH_POST_HALF_WIDTH;
        double maxZ = z + PATH_POST_HALF_WIDTH;

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.02D, baseY, minZ - 0.02D,
                maxX + 0.02D, baseY + 0.12D, maxZ + 0.02D,
                alpha,
                enemyPalette ? 0.18F : 0.20F,
                enemyPalette ? 0.06F : 0.14F,
                enemyPalette ? 0.05F : 0.08F);

        renderSolidBox(fillBuffer, poseStack,
                minX, baseY + 0.12D, minZ,
                maxX, baseY + PATH_HEIGHT - 0.10D, maxZ,
                alpha,
                enemyPalette ? 0.34F : 0.38F,
                enemyPalette ? 0.11F : 0.25F,
                enemyPalette ? 0.08F : 0.14F);

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.02D, baseY + PATH_HEIGHT - 0.10D, minZ - 0.02D,
                maxX + 0.02D, baseY + PATH_HEIGHT, maxZ + 0.02D,
                alpha,
                enemyPalette ? 0.44F : 0.50F,
                enemyPalette ? 0.13F : 0.33F,
                enemyPalette ? 0.10F : 0.19F);

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.03D, baseY + PATH_HEIGHT, minZ - 0.03D,
                maxX + 0.03D, baseY + PATH_HEIGHT + 0.10D, maxZ + 0.03D,
                alpha,
                enemyPalette ? 0.54F : 0.60F,
                enemyPalette ? 0.16F : 0.40F,
                enemyPalette ? 0.12F : 0.24F);
    }

    private void renderWallPost(VertexConsumer fillBuffer, PoseStack poseStack,
                                double x, double z, double baseY, int alpha) {
        double minX = x - CLAIM_POST_HALF_WIDTH;
        double maxX = x + CLAIM_POST_HALF_WIDTH;
        double minZ = z - CLAIM_POST_HALF_WIDTH;
        double maxZ = z + CLAIM_POST_HALF_WIDTH;

        double footTop = baseY + 0.26D;
        double postTop = baseY + CLAIM_WALL_HEIGHT - 0.16D;

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.065D, baseY, minZ - 0.065D,
                maxX + 0.065D, baseY + 0.14D, maxZ + 0.065D,
                alpha,
                0.15F, 0.10F, 0.06F);

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.035D, baseY + 0.14D, minZ - 0.035D,
                maxX + 0.035D, footTop, maxZ + 0.035D,
                alpha,
                0.21F, 0.14F, 0.08F);

        renderSolidBox(fillBuffer, poseStack,
                minX, footTop, minZ,
                maxX, postTop, maxZ,
                alpha,
                0.38F, 0.26F, 0.15F);

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.018D, baseY + 1.10D, minZ - 0.018D,
                maxX + 0.018D, baseY + 1.28D, maxZ + 0.018D,
                alpha,
                0.28F, 0.19F, 0.10F);

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.035D, postTop - 0.10D, minZ - 0.035D,
                maxX + 0.035D, postTop + 0.02D, maxZ + 0.035D,
                alpha,
                0.45F, 0.31F, 0.18F);

        renderSolidBox(fillBuffer, poseStack,
                minX - CLAIM_POST_CAP_OVERHANG, postTop + 0.02D, minZ - CLAIM_POST_CAP_OVERHANG,
                maxX + CLAIM_POST_CAP_OVERHANG, baseY + CLAIM_WALL_HEIGHT, maxZ + CLAIM_POST_CAP_OVERHANG,
                alpha,
                0.58F, 0.42F, 0.24F);

        renderSolidBox(fillBuffer, poseStack,
                x - 0.050D, baseY + CLAIM_WALL_HEIGHT, z - 0.050D,
                x + 0.050D, baseY + CLAIM_WALL_HEIGHT + 0.12D, z + 0.050D,
                alpha,
                0.66F, 0.52F, 0.28F);
    }

    private void renderArchPost(VertexConsumer fillBuffer, PoseStack poseStack,
                                double x, double z, double baseY, int alpha, boolean enemyPalette) {
        double minX = x - GATE_POST_HALF_WIDTH;
        double maxX = x + GATE_POST_HALF_WIDTH;
        double minZ = z - GATE_POST_HALF_WIDTH;
        double maxZ = z + GATE_POST_HALF_WIDTH;

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.04D, baseY, minZ - 0.04D,
                maxX + 0.04D, baseY + 0.20D, maxZ + 0.04D,
                alpha,
                enemyPalette ? 0.18F : 0.20F,
                enemyPalette ? 0.06F : 0.14F,
                enemyPalette ? 0.05F : 0.08F);

        renderSolidBox(fillBuffer, poseStack,
                minX, baseY + 0.20D, minZ,
                maxX, baseY + 2.06D, maxZ,
                alpha,
                enemyPalette ? 0.36F : 0.40F,
                enemyPalette ? 0.11F : 0.26F,
                enemyPalette ? 0.09F : 0.15F);

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.026D, baseY + 1.08D, minZ - 0.026D,
                maxX + 0.026D, baseY + 1.24D, maxZ + 0.026D,
                alpha,
                enemyPalette ? 0.24F : 0.28F,
                enemyPalette ? 0.08F : 0.18F,
                enemyPalette ? 0.06F : 0.10F);

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.02D, baseY + 2.06D, minZ - 0.02D,
                maxX + 0.02D, baseY + 2.26D, maxZ + 0.02D,
                alpha,
                enemyPalette ? 0.46F : 0.50F,
                enemyPalette ? 0.13F : 0.32F,
                enemyPalette ? 0.10F : 0.18F);

        renderSolidBox(fillBuffer, poseStack,
                minX - 0.04D, baseY + 2.26D, minZ - 0.04D,
                maxX + 0.04D, baseY + 2.40D, maxZ + 0.04D,
                alpha,
                enemyPalette ? 0.56F : 0.64F,
                enemyPalette ? 0.16F : 0.42F,
                enemyPalette ? 0.12F : 0.24F);

        renderSolidBox(fillBuffer, poseStack,
                x - 0.06D, baseY + 2.40D, z - 0.06D,
                x + 0.06D, baseY + 2.52D, z + 0.06D,
                alpha,
                enemyPalette ? 0.64F : 0.72F,
                enemyPalette ? 0.18F : 0.48F,
                enemyPalette ? 0.13F : 0.28F);
    }

    private void renderClaimStakes(VertexConsumer fillBuffer, PoseStack poseStack,
                                   double startX, double startZ, double endX, double endZ,
                                   double baseY, int alpha, boolean enemyPalette) {
        boolean alongX = Math.abs(endX - startX) >= Math.abs(endZ - startZ);
        double length = Math.max(Math.abs(endX - startX), Math.abs(endZ - startZ));
        int stakes = Math.max(1, Mth.floor(length / CLAIM_STAKE_INTERVAL));

        for (int i = 0; i < stakes; i++) {
            double progress = (i + 0.5D) / stakes;
            double x = Mth.lerp(progress, startX, endX);
            double z = Mth.lerp(progress, startZ, endZ);
            double variation = pseudoNoise(x * 0.85D, baseY * 0.60D, z * 0.85D);
            double bodyTop = baseY + 1.82D + variation * 0.12D;
            double tipTop = bodyTop + 0.18D + Math.max(0.0D, variation) * 0.12D;
            double halfX = alongX ? CLAIM_STAKE_HALF_WIDTH : CLAIM_STAKE_HALF_DEPTH;
            double halfZ = alongX ? CLAIM_STAKE_HALF_DEPTH : CLAIM_STAKE_HALF_WIDTH;

            renderSolidBox(fillBuffer, poseStack,
                    x - halfX, baseY + 0.16D, z - halfZ,
                    x + halfX, bodyTop, z + halfZ,
                    alpha,
                    enemyPalette ? 0.37F : 0.42F,
                    enemyPalette ? 0.11F : 0.29F,
                    enemyPalette ? 0.09F : 0.16F);

            renderSolidBox(fillBuffer, poseStack,
                    x - halfX * 0.70D, bodyTop - 0.02D, z - halfZ * 0.70D,
                    x + halfX * 0.70D, tipTop, z + halfZ * 0.70D,
                    alpha,
                    enemyPalette ? 0.51F : 0.60F,
                    enemyPalette ? 0.14F : 0.41F,
                    enemyPalette ? 0.11F : 0.23F);
        }
    }

    private void renderPathStakes(VertexConsumer fillBuffer, PoseStack poseStack,
                                  double startX, double startZ, double endX, double endZ,
                                  double baseY, int alpha, boolean enemyPalette) {
        boolean alongX = Math.abs(endX - startX) >= Math.abs(endZ - startZ);
        double length = Math.max(Math.abs(endX - startX), Math.abs(endZ - startZ));
        int stakes = Math.max(1, Mth.floor(length / PATH_STAKE_INTERVAL));

        for (int i = 0; i < stakes; i++) {
            double progress = (i + 0.5D) / stakes;
            double x = Mth.lerp(progress, startX, endX);
            double z = Mth.lerp(progress, startZ, endZ);
            double variation = pseudoNoise(x * 1.15D, baseY * 0.50D, z * 1.15D);
            double bodyTop = baseY + 0.90D + variation * 0.08D;
            double tipTop = bodyTop + 0.16D + Math.max(0.0D, variation) * 0.08D;
            double halfX = alongX ? PATH_STAKE_HALF_WIDTH : PATH_STAKE_HALF_DEPTH;
            double halfZ = alongX ? PATH_STAKE_HALF_DEPTH : PATH_STAKE_HALF_WIDTH;

            renderSolidBox(fillBuffer, poseStack,
                    x - halfX, baseY + 0.14D, z - halfZ,
                    x + halfX, bodyTop, z + halfZ,
                    alpha,
                    enemyPalette ? 0.35F : 0.40F,
                    enemyPalette ? 0.11F : 0.27F,
                    enemyPalette ? 0.08F : 0.15F);

            renderSolidBox(fillBuffer, poseStack,
                    x - halfX * 0.64D, bodyTop - 0.02D, z - halfZ * 0.64D,
                    x + halfX * 0.64D, tipTop, z + halfZ * 0.64D,
                    alpha,
                    enemyPalette ? 0.48F : 0.56F,
                    enemyPalette ? 0.14F : 0.38F,
                    enemyPalette ? 0.11F : 0.21F);
        }
    }

    private void renderGateTeeth(VertexConsumer fillBuffer, PoseStack poseStack,
                                 PathGate gate, double lintelStartAlong, double lintelEndAlong,
                                 double baseY, int alpha, boolean enemyPalette) {
        double span = lintelEndAlong - lintelStartAlong;
        int teeth = Mth.clamp(Mth.floor(span / 0.78D), 3, 7);

        for (int i = 0; i < teeth; i++) {
            double progress = (i + 0.5D) / teeth;
            double along = Mth.lerp(progress, lintelStartAlong, lintelEndAlong);
            double toothBottom = baseY + 1.56D + (i & 1) * 0.06D;
            double toothTop = baseY + 1.98D;

            if (gate.vertical()) {
                renderSolidBox(fillBuffer, poseStack,
                        gate.axisCoord() - 0.08D, toothBottom, along - 0.07D,
                        gate.axisCoord() + 0.08D, toothTop, along + 0.07D,
                        alpha,
                        enemyPalette ? 0.26F : 0.28F,
                        enemyPalette ? 0.08F : 0.19F,
                        enemyPalette ? 0.06F : 0.10F);

                renderSolidBox(fillBuffer, poseStack,
                        gate.axisCoord() - 0.05D, toothBottom - 0.12D, along - 0.04D,
                        gate.axisCoord() + 0.05D, toothBottom, along + 0.04D,
                        alpha,
                        enemyPalette ? 0.34F : 0.38F,
                        enemyPalette ? 0.10F : 0.25F,
                        enemyPalette ? 0.08F : 0.14F);
            } else {
                renderSolidBox(fillBuffer, poseStack,
                        along - 0.07D, toothBottom, gate.axisCoord() - 0.08D,
                        along + 0.07D, toothTop, gate.axisCoord() + 0.08D,
                        alpha,
                        enemyPalette ? 0.26F : 0.28F,
                        enemyPalette ? 0.08F : 0.19F,
                        enemyPalette ? 0.06F : 0.10F);

                renderSolidBox(fillBuffer, poseStack,
                        along - 0.04D, toothBottom - 0.12D, gate.axisCoord() - 0.05D,
                        along + 0.04D, toothBottom, gate.axisCoord() + 0.05D,
                        alpha,
                        enemyPalette ? 0.34F : 0.38F,
                        enemyPalette ? 0.10F : 0.25F,
                        enemyPalette ? 0.08F : 0.14F);
            }
        }
    }

    private void renderSolidBeam(VertexConsumer fillBuffer, PoseStack poseStack,
                                 double startX, double startZ, double endX, double endZ,
                                 double minY, double maxY, double halfThickness,
                                 int alpha,
                                 float fillR, float fillG, float fillB) {
        double minX = Math.min(startX, endX);
        double maxX = Math.max(startX, endX);
        double minZ = Math.min(startZ, endZ);
        double maxZ = Math.max(startZ, endZ);

        if (Math.abs(endX - startX) >= Math.abs(endZ - startZ)) {
            minZ -= halfThickness;
            maxZ += halfThickness;
        } else {
            minX -= halfThickness;
            maxX += halfThickness;
        }

        renderSolidBox(fillBuffer, poseStack,
                minX, minY, minZ,
                maxX, maxY, maxZ,
                alpha, fillR, fillG, fillB);
    }

    private void renderSolidBox(VertexConsumer fillBuffer, PoseStack poseStack,
                                double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ,
                                int alpha,
                                float fillR, float fillG, float fillB) {
        if (alpha <= 0) {
            return;
        }

        minX += CLAIM_FACE_INSET;
        minY += CLAIM_FACE_INSET;
        minZ += CLAIM_FACE_INSET;
        maxX -= CLAIM_FACE_INSET;
        maxY -= CLAIM_FACE_INSET;
        maxZ -= CLAIM_FACE_INSET;

        if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
            return;
        }

        addWoodBox(fillBuffer, poseStack, minX, minY, minZ, maxX, maxY, maxZ, fillR, fillG, fillB, alpha / 255F);
    }

    private void addWoodBox(VertexConsumer buffer, PoseStack poseStack,
                            double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ,
                            float r, float g, float b, float a) {
        Matrix4f matrix = poseStack.last().pose();

        addWoodFaceHorizontal(buffer, matrix,
                minX, minY, minZ, maxX, minY, maxZ,
                0.72F * r, 0.72F * g, 0.72F * b, a, false);

        addWoodFaceHorizontal(buffer, matrix,
                minX, maxY, minZ, maxX, maxY, maxZ,
                clamp01(r * 1.10F), clamp01(g * 1.10F), clamp01(b * 1.10F), a, true);

        addWoodFaceVertical(buffer, matrix,
                minX, minY, minZ, maxX, maxY, minZ,
                0.84F * r, 0.84F * g, 0.84F * b, a, true);

        addWoodFaceVertical(buffer, matrix,
                maxX, minY, maxZ, minX, maxY, maxZ,
                1.02F * r, 1.02F * g, 1.02F * b, a, true);

        addWoodFaceVertical(buffer, matrix,
                minX, minY, maxZ, minX, maxY, minZ,
                0.76F * r, 0.76F * g, 0.76F * b, a, false);

        addWoodFaceVertical(buffer, matrix,
                maxX, minY, minZ, maxX, maxY, maxZ,
                0.92F * r, 0.92F * g, 0.92F * b, a, false);
    }

    private void addWoodFaceHorizontal(VertexConsumer buffer, Matrix4f matrix,
                                       double minX, double y, double minZ,
                                       double maxX, double ignoredY, double maxZ,
                                       float baseR, float baseG, float baseB, float a,
                                       boolean brighten) {
        int strips = 8;
        for (int i = 0; i < strips; i++) {
            double z0 = Mth.lerp(i / (double) strips, minZ, maxZ);
            double z1 = Mth.lerp((i + 1) / (double) strips, minZ, maxZ);

            double centerX = (minX + maxX) * 0.5D;
            double centerZ = (z0 + z1) * 0.5D;
            float grain = woodGrain(centerX, y, centerZ, false);
            float ring = woodRing(centerX, y, centerZ);
            float blockiness = blockWoodStep(centerX, y, centerZ);

            float variation = brighten
                    ? 1.0F + grain * 0.11F + ring * 0.05F + blockiness * 0.05F
                    : 1.0F + grain * 0.08F + ring * 0.04F + blockiness * 0.04F;

            float rr = clamp01(baseR * variation);
            float gg = clamp01(baseG * variation);
            float bb = clamp01(baseB * variation);

            addQuad(buffer, matrix,
                    (float) minX, (float) y, (float) z0,
                    (float) maxX, (float) y, (float) z0,
                    (float) maxX, (float) y, (float) z1,
                    (float) minX, (float) y, (float) z1,
                    rr, gg, bb, a);
        }
    }

    private void addWoodFaceVertical(VertexConsumer buffer, Matrix4f matrix,
                                     double x0, double minY, double z0,
                                     double x1, double maxY, double z1,
                                     float baseR, float baseG, float baseB, float a,
                                     boolean grainAlongX) {
        int bands = 11;
        for (int i = 0; i < bands; i++) {
            double y0 = Mth.lerp(i / (double) bands, minY, maxY);
            double y1 = Mth.lerp((i + 1) / (double) bands, minY, maxY);

            double centerX = (x0 + x1) * 0.5D;
            double centerY = (y0 + y1) * 0.5D;
            double centerZ = (z0 + z1) * 0.5D;

            float grain = woodGrain(centerX, centerY, centerZ, grainAlongX);
            float ring = woodRing(centerX, centerY, centerZ);
            float saw = sawCut(centerX, centerY, centerZ, grainAlongX);
            float blockiness = blockWoodStep(centerX, centerY, centerZ);

            float plankLine = ((i & 1) == 0) ? -0.030F : 0.020F;
            float baseMul = 1.0F
                    + grain * 0.15F
                    + ring * 0.05F
                    + saw * 0.04F
                    + blockiness * 0.05F
                    + plankLine;

            float rr0 = clamp01(baseR * (baseMul - 0.032F));
            float gg0 = clamp01(baseG * (baseMul - 0.032F));
            float bb0 = clamp01(baseB * (baseMul - 0.032F));

            float rr1 = clamp01(baseR * (baseMul + 0.032F));
            float gg1 = clamp01(baseG * (baseMul + 0.032F));
            float bb1 = clamp01(baseB * (baseMul + 0.032F));

            addQuadGradient(buffer, matrix,
                    (float) x0, (float) y0, (float) z0,
                    (float) x1, (float) y0, (float) z1,
                    (float) x1, (float) y1, (float) z1,
                    (float) x0, (float) y1, (float) z0,
                    rr0, gg0, bb0,
                    rr0, gg0, bb0,
                    rr1, gg1, bb1,
                    rr1, gg1, bb1,
                    a);
        }
    }

    private float woodGrain(double x, double y, double z, boolean grainAlongX) {
        double primaryAxis = grainAlongX ? x : z;
        double sideAxis = grainAlongX ? z : x;

        double longWave = Math.sin(primaryAxis * 1.15D + sideAxis * 0.28D);
        double mediumWave = Math.sin(primaryAxis * 3.25D + y * 0.78D + sideAxis * 0.50D);
        double fineWave = Math.sin(primaryAxis * 7.9D + sideAxis * 1.9D + y * 2.2D);
        double knotNoise = pseudoNoise(primaryAxis * 0.66D, y * 1.85D, sideAxis * 0.66D);

        double value = longWave * 0.44D + mediumWave * 0.28D + fineWave * 0.14D + knotNoise * 0.14D;
        return (float) Mth.clamp(value, -0.95D, 0.95D);
    }

    private float woodRing(double x, double y, double z) {
        double value = Math.sin((x + z) * 0.90D + y * 0.33D) * 0.55D
                + Math.sin((x - z) * 1.65D + y * 0.18D) * 0.45D;
        return (float) Mth.clamp(value, -1.0D, 1.0D);
    }

    private float sawCut(double x, double y, double z, boolean alongX) {
        double axis = alongX ? z : x;
        double value = Math.sin(axis * 14.0D + y * 0.65D) * 0.55D + Math.sin(axis * 6.5D) * 0.45D;
        return (float) Mth.clamp(value, -1.0D, 1.0D);
    }

    private float blockWoodStep(double x, double y, double z) {
        int ix = Mth.floor(x * 2.0D);
        int iy = Mth.floor(y * 3.0D);
        int iz = Mth.floor(z * 2.0D);
        int hash = ix * 73428767 ^ iy * 912931 ^ iz * 423733;
        hash ^= (hash >>> 13);
        hash *= 1274126177;
        int low = hash & 7;
        return (low / 7.0F) * 2.0F - 1.0F;
    }

    private double pseudoNoise(double x, double y, double z) {
        double n = Math.sin(x * 12.9898D + y * 78.233D + z * 37.719D) * 43758.5453D;
        double fract = n - Math.floor(n);
        return (fract * 2.0D) - 1.0D;
    }

    private float clamp01(float value) {
        return Mth.clamp(value, 0.0F, 1.0F);
    }

    private void addQuad(VertexConsumer buffer, Matrix4f matrix,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         float x3, float y3, float z3,
                         float x4, float y4, float z4,
                         float r, float g, float b, float a) {
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x3, y3, z3).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x4, y4, z4).color(r, g, b, a).endVertex();
    }

    private void addQuadGradient(VertexConsumer buffer, Matrix4f matrix,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3,
                                 float x4, float y4, float z4,
                                 float r1, float g1, float b1,
                                 float r2, float g2, float b2,
                                 float r3, float g3, float b3,
                                 float r4, float g4, float b4,
                                 float a) {
        buffer.vertex(matrix, x1, y1, z1).color(r1, g1, b1, a).endVertex();
        buffer.vertex(matrix, x2, y2, z2).color(r2, g2, b2, a).endVertex();
        buffer.vertex(matrix, x3, y3, z3).color(r3, g3, b3, a).endVertex();
        buffer.vertex(matrix, x4, y4, z4).color(r4, g4, b4, a).endVertex();
    }

    private void addQuadGradientAlpha(VertexConsumer buffer, Matrix4f matrix,
                                      float x1, float y1, float z1,
                                      float x2, float y2, float z2,
                                      float x3, float y3, float z3,
                                      float x4, float y4, float z4,
                                      float r1, float g1, float b1, float a1,
                                      float r2, float g2, float b2, float a2,
                                      float r3, float g3, float b3, float a3,
                                      float r4, float g4, float b4, float a4) {
        buffer.vertex(matrix, x1, y1, z1).color(r1, g1, b1, a1).endVertex();
        buffer.vertex(matrix, x2, y2, z2).color(r2, g2, b2, a2).endVertex();
        buffer.vertex(matrix, x3, y3, z3).color(r3, g3, b3, a3).endVertex();
        buffer.vertex(matrix, x4, y4, z4).color(r4, g4, b4, a4).endVertex();
    }

    private void renderCoreZoneEdgeBand(VertexConsumer fillBuffer, PoseStack poseStack,
                                        double centerX, double centerZ,
                                        double innerRadius, double outerRadius,
                                        double minY, double maxY,
                                        float r, float g, float b,
                                        float innerAlpha, float outerAlpha) {
        if (outerRadius <= innerRadius || maxY <= minY || (innerAlpha <= 0.0F && outerAlpha <= 0.0F)) {
            return;
        }

        Matrix4f matrix = poseStack.last().pose();
        int segments = CoreBreachShape.circleSegments(Mth.ceil((float) outerRadius));

        for (int i = 0; i < segments; i++) {
            double startAngle = ((Math.PI * 2.0D) * i / segments);
            double endAngle = ((Math.PI * 2.0D) * (i + 1) / segments);

            float innerX0 = (float) (centerX + Math.cos(startAngle) * innerRadius);
            float innerZ0 = (float) (centerZ + Math.sin(startAngle) * innerRadius);
            float innerX1 = (float) (centerX + Math.cos(endAngle) * innerRadius);
            float innerZ1 = (float) (centerZ + Math.sin(endAngle) * innerRadius);
            float outerX0 = (float) (centerX + Math.cos(startAngle) * outerRadius);
            float outerZ0 = (float) (centerZ + Math.sin(startAngle) * outerRadius);
            float outerX1 = (float) (centerX + Math.cos(endAngle) * outerRadius);
            float outerZ1 = (float) (centerZ + Math.sin(endAngle) * outerRadius);

            addQuadGradientAlpha(fillBuffer, matrix,
                    outerX0, (float) minY, outerZ0,
                    outerX1, (float) minY, outerZ1,
                    outerX1, (float) maxY, outerZ1,
                    outerX0, (float) maxY, outerZ0,
                    r, g, b, outerAlpha * 0.58F,
                    r, g, b, outerAlpha * 0.58F,
                    r, g, b, outerAlpha,
                    r, g, b, outerAlpha);

            addQuadGradientAlpha(fillBuffer, matrix,
                    innerX1, (float) minY, innerZ1,
                    innerX0, (float) minY, innerZ0,
                    innerX0, (float) maxY, innerZ0,
                    innerX1, (float) maxY, innerZ1,
                    r, g, b, innerAlpha * 0.58F,
                    r, g, b, innerAlpha * 0.58F,
                    r, g, b, innerAlpha,
                    r, g, b, innerAlpha);
        }
    }

    private void renderCircularRingLine(VertexConsumer lineBuffer, PoseStack poseStack,
                                        double centerX, double centerY, double centerZ,
                                        double radius,
                                        float rotation,
                                        float r, float g, float b, float alpha) {
        if (alpha <= 0.0F || radius <= 0.0D) {
            return;
        }

        int segments = CoreBreachShape.circleSegments(Mth.ceil((float) radius));
        for (int i = 0; i < segments; i++) {
            float startAngle = rotation + ((float) (Math.PI * 2D) * i / segments);
            float endAngle = rotation + ((float) (Math.PI * 2D) * (i + 1) / segments);
            float shimmer = 0.90F + 0.10F * Mth.sin(rotation * 7.0F + i * 0.55F);

            float startX = (float) (centerX + Math.cos(startAngle) * radius);
            float startZ = (float) (centerZ + Math.sin(startAngle) * radius);
            float endX = (float) (centerX + Math.cos(endAngle) * radius);
            float endZ = (float) (centerZ + Math.sin(endAngle) * radius);

            addLine(lineBuffer, poseStack,
                    startX, (float) centerY, startZ,
                    endX, (float) centerY, endZ,
                    clamp01(r * shimmer), clamp01(g * shimmer), clamp01(b * shimmer), alpha);
        }
    }

    private void renderParticipantHighlight(VertexConsumer lineBuffer, PoseStack poseStack,
                                            Player player, ResourceLocation dimensionId, LocalPlayer localPlayer) {
        ClientRaidState.RaidVisual raid = participantRaid(player, dimensionId, localPlayer);
        if (raid == null || player == localPlayer) {
            return;
        }

        boolean attacker = raid.isAttacker(player.getUUID());
        float r = attacker ? 1.0F : 0.34F;
        float g = attacker ? 0.22F : 0.60F;
        float b = attacker ? 0.28F : 1.0F;
        float tickTime = player.tickCount + Minecraft.getInstance().getFrameTime();
        double bodyY = player.getY() + (player.getBbHeight() * 0.58D);
        double footY = player.getY() + 0.08D;
        double crownY = player.getY() + player.getBbHeight() + 0.28D;

        renderOrbitRing(lineBuffer, poseStack, player.getX(), footY, player.getZ(), 0.52D, tickTime * 0.07F, r, g, b, 0.85F);
        renderOrbitRing(lineBuffer, poseStack, player.getX(), bodyY, player.getZ(), 0.42D, -tickTime * 0.09F, brightenColor(r, g, b, 0.18F), brightenColor(g, r, b, 0.02F), brightenColor(b, g, r, 0.18F), 0.68F);
        renderOrbitRing(lineBuffer, poseStack, player.getX(), crownY, player.getZ(), 0.26D, tickTime * 0.12F, brightenColor(r, g, b, 0.30F), brightenColor(g, r, b, 0.06F), brightenColor(b, g, r, 0.30F), 0.92F);
        renderSignalSpikes(lineBuffer, poseStack, player, tickTime, r, g, b);
    }

    private void renderParticipantTag(MultiBufferSource.BufferSource bufferSource, PoseStack poseStack, Player player,
                                      ResourceLocation dimensionId, LocalPlayer localPlayer) {
        ClientRaidState.RaidVisual raid = participantRaid(player, dimensionId, localPlayer);
        if (raid == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        boolean attacker = raid.isAttacker(player.getUUID());
        poseStack.pushPose();
        poseStack.translate(player.getX(), player.getY() + player.getBbHeight() + 1.55D, player.getZ());
        poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.028F, -0.028F, 0.028F);
        Component label = Component.literal(attacker ? "<<< ATTACKER >>>" : "<<< DEFENDER >>>");
        int color = attacker ? 0xFFFF7E7E : 0xFF7EC3FF;
        float x = -minecraft.font.width(label) / 2F;
        minecraft.font.drawInBatch(label, x, 0, color, false, poseStack.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
        poseStack.popPose();
    }

    private ClientRaidState.RaidVisual participantRaid(Player player, ResourceLocation dimensionId, LocalPlayer localPlayer) {
        for (ClientRaidState.RaidVisual raid : ClientRaidState.all()) {
            if (!raid.dimensionId().equals(dimensionId) || !raid.isParticipant(player.getUUID())) {
                continue;
            }
            if (!isInsideRaidArea(player, raid)) {
                continue;
            }
            return raid;
        }
        return null;
    }

    private float edgeRevealFactor(double playerX, double playerZ,
                                   double startX, double startZ,
                                   double endX, double endZ,
                                   double revealDistance, double outerRevealDistance) {
        if (revealDistance <= 0.0D) {
            return 1.0F;
        }

        double distance = distanceToSegment(playerX, playerZ, startX, startZ, endX, endZ);
        double fullRevealDistance = Math.max(1.5D, revealDistance * 0.70D);

        if (distance <= fullRevealDistance) {
            return 1.0F;
        }
        if (distance >= outerRevealDistance) {
            return 0.0F;
        }

        double fadeDistance = Math.max(0.5D, outerRevealDistance - fullRevealDistance);
        float progress = (float) Mth.clamp(1.0D - ((distance - fullRevealDistance) / fadeDistance), 0.0D, 1.0D);
        return smootherStep(progress);
    }

    private float animateSegmentVisibility(ClaimSegmentKey key, float targetVisibility, float deltaSeconds) {
        float currentVisibility = CLAIM_SEGMENT_VISIBILITY.getOrDefault(key, 0.0F);
        float speed = targetVisibility > currentVisibility ? CLAIM_OUTLINE_FADE_IN_SPEED : CLAIM_OUTLINE_FADE_OUT_SPEED;

        float blend = 1.0F - (float) Math.exp(-speed * Math.max(0.0F, deltaSeconds));
        float nextVisibility = Mth.lerp(Mth.clamp(blend, 0.0F, 1.0F), currentVisibility, targetVisibility);

        if (nextVisibility <= CLAIM_OUTLINE_MIN_SEGMENT_VISIBILITY && targetVisibility <= 0.0F) {
            CLAIM_SEGMENT_VISIBILITY.remove(key);
            return 0.0F;
        }

        CLAIM_SEGMENT_VISIBILITY.put(key, nextVisibility);
        return nextVisibility;
    }

    private float easedVisibility(float visibility) {
        return smootherStep(Mth.clamp(visibility, 0.0F, 1.0F));
    }

    private float smootherStep(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        return x * x * x * (x * (x * 6.0F - 15.0F) + 10.0F);
    }

    private float zoneRingRevealFactor(double dist, double cullDist) {
        double fadeBand = Math.max(8.0D, ZONE_RING_FADE_BAND);
        double fullRevealDist = Math.max(0.0D, cullDist - fadeBand);
        if (dist <= fullRevealDist) return 1.0F;
        if (dist >= cullDist) return 0.0F;
        return smootherStep((float) (1.0D - (dist - fullRevealDist) / fadeBand));
    }

    private double distanceToSegment(double playerX, double playerZ,
                                     double startX, double startZ,
                                     double endX, double endZ) {
        double deltaX = endX - startX;
        double deltaZ = endZ - startZ;
        double lengthSqr = (deltaX * deltaX) + (deltaZ * deltaZ);
        if (lengthSqr <= 1.0E-6D) {
            return Math.sqrt(Mth.square(playerX - startX) + Mth.square(playerZ - startZ));
        }

        double projection = ((playerX - startX) * deltaX + (playerZ - startZ) * deltaZ) / lengthSqr;
        double clampedProjection = Mth.clamp(projection, 0.0D, 1.0D);
        double closestX = startX + (deltaX * clampedProjection);
        double closestZ = startZ + (deltaZ * clampedProjection);
        return Math.sqrt(Mth.square(playerX - closestX) + Mth.square(playerZ - closestZ));
    }

    private boolean isInsideRaidArea(Player player, ClientRaidState.RaidVisual raid) {
        return raid.isInsideDefendedChunks(player.chunkPosition().x, player.chunkPosition().z);
    }

    private SoundEvent resolveSound(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : ForgeRegistries.SOUND_EVENTS.getValue(location);
    }

    private void updateRaidParticipantOutlines(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            clearRaidParticipantOutlines(minecraft);
            return;
        }

        Scoreboard scoreboard = minecraft.level.getScoreboard();
        PlayerTeam attackerTeam = ensureRaidOutlineTeam(scoreboard, RAID_ATTACKER_OUTLINE_TEAM, ChatFormatting.RED);
        PlayerTeam defenderTeam = ensureRaidOutlineTeam(scoreboard, RAID_DEFENDER_OUTLINE_TEAM, ChatFormatting.AQUA);
        Set<UUID> activeParticipants = new HashSet<>();

        for (Player player : minecraft.level.players()) {
            if (player == minecraft.player) {
                continue;
            }

            ClientRaidState.RaidVisual raid = participantRaid(player, minecraft.level.dimension().location(), minecraft.player);
            if (raid == null) {
                continue;
            }

            activeParticipants.add(player.getUUID());
            PlayerTeam targetTeam = raid.isAttacker(player.getUUID()) ? attackerTeam : defenderTeam;
            applyRaidOutline(scoreboard, player, targetTeam);
        }

        for (UUID trackedId : Set.copyOf(RAID_OUTLINE_PREVIOUS_TEAMS.keySet())) {
            if (!activeParticipants.contains(trackedId)) {
                Player player = minecraft.level.getPlayerByUUID(trackedId);
                if (player != null) {
                    clearRaidOutline(scoreboard, player);
                } else {
                    RAID_OUTLINE_PREVIOUS_TEAMS.remove(trackedId);
                    RAID_OUTLINE_PREVIOUS_GLOWING.remove(trackedId);
                }
            }
        }

        for (PlayerTeam outlineTeam : new PlayerTeam[]{attackerTeam, defenderTeam}) {
            for (String memberName : Set.copyOf(outlineTeam.getPlayers())) {
                boolean stillActive = minecraft.level.players().stream()
                        .anyMatch(p -> activeParticipants.contains(p.getUUID())
                                && p.getScoreboardName().equals(memberName));
                if (!stillActive) {
                    scoreboard.removePlayerFromTeam(memberName, outlineTeam);
                }
            }
        }
    }

    private void clearRaidParticipantOutlines(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) {
            RAID_OUTLINE_PREVIOUS_TEAMS.clear();
            RAID_OUTLINE_PREVIOUS_GLOWING.clear();
            return;
        }

        Scoreboard scoreboard = minecraft.level.getScoreboard();
        for (UUID trackedId : Set.copyOf(RAID_OUTLINE_PREVIOUS_TEAMS.keySet())) {
            Player player = minecraft.level.getPlayerByUUID(trackedId);
            if (player != null) {
                clearRaidOutline(scoreboard, player);
            } else {
                RAID_OUTLINE_PREVIOUS_TEAMS.remove(trackedId);
                RAID_OUTLINE_PREVIOUS_GLOWING.remove(trackedId);
            }
        }
    }

    private PlayerTeam ensureRaidOutlineTeam(Scoreboard scoreboard, String teamName, ChatFormatting color) {
        PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
        }
        team.setColor(color);
        return team;
    }

    private void applyRaidOutline(Scoreboard scoreboard, Player player, PlayerTeam targetTeam) {
        String scoreboardName = player.getScoreboardName();
        UUID playerId = player.getUUID();
        PlayerTeam currentTeam = scoreboard.getPlayersTeam(scoreboardName);

        RAID_OUTLINE_PREVIOUS_TEAMS.computeIfAbsent(playerId, ignored -> currentTeam == null ? "" : currentTeam.getName());
        RAID_OUTLINE_PREVIOUS_GLOWING.computeIfAbsent(playerId, ignored -> player.isCurrentlyGlowing());

        if (currentTeam == null || !currentTeam.getName().equals(targetTeam.getName())) {
            if (currentTeam != null) {
                scoreboard.removePlayerFromTeam(scoreboardName, currentTeam);
            }
            scoreboard.addPlayerToTeam(scoreboardName, targetTeam);
        }

        player.setGlowingTag(true);
    }

    private void clearRaidOutline(Scoreboard scoreboard, Player player) {
        String scoreboardName = player.getScoreboardName();
        UUID playerId = player.getUUID();
        PlayerTeam currentTeam = scoreboard.getPlayersTeam(scoreboardName);

        if (currentTeam != null && (RAID_ATTACKER_OUTLINE_TEAM.equals(currentTeam.getName()) || RAID_DEFENDER_OUTLINE_TEAM.equals(currentTeam.getName()))) {
            scoreboard.removePlayerFromTeam(scoreboardName, currentTeam);
        }

        String previousTeamName = RAID_OUTLINE_PREVIOUS_TEAMS.remove(playerId);
        if (previousTeamName != null && !previousTeamName.isBlank()) {
            PlayerTeam previousTeam = scoreboard.getPlayerTeam(previousTeamName);
            if (previousTeam != null) {
                scoreboard.addPlayerToTeam(scoreboardName, previousTeam);
            }
        }

        boolean wasGlowing = RAID_OUTLINE_PREVIOUS_GLOWING.remove(playerId) == Boolean.TRUE;
        player.setGlowingTag(wasGlowing);
    }

    private void renderOrbitRing(VertexConsumer lineBuffer, PoseStack poseStack,
                                 double centerX, double centerY, double centerZ,
                                 double radius, float rotation,
                                 float r, float g, float b, float alpha) {
        int segments = 24;
        for (int i = 0; i < segments; i++) {
            float startAngle = rotation + ((float) (Math.PI * 2D) * i / segments);
            float endAngle = rotation + ((float) (Math.PI * 2D) * (i + 1) / segments);
            float pulse = 0.82F + 0.18F * Mth.sin(rotation * 2.0F + i * 0.8F);

            float startX = (float) (centerX + Math.cos(startAngle) * radius);
            float startZ = (float) (centerZ + Math.sin(startAngle) * radius);
            float endX = (float) (centerX + Math.cos(endAngle) * radius);
            float endZ = (float) (centerZ + Math.sin(endAngle) * radius);
            addLine(lineBuffer, poseStack,
                    startX, (float) centerY, startZ,
                    endX, (float) centerY, endZ,
                    clamp01(r * pulse), clamp01(g * pulse), clamp01(b * pulse), alpha);
        }
    }

    private void renderSignalSpikes(VertexConsumer lineBuffer, PoseStack poseStack, Player player, float tickTime,
                                    float r, float g, float b) {
        double centerX = player.getX();
        double centerZ = player.getZ();
        double minY = player.getY() + 0.25D;
        double maxY = player.getY() + player.getBbHeight() + 0.14D;

        for (int i = 0; i < 4; i++) {
            double angle = tickTime * 0.06D + (Math.PI * 0.5D * i);
            double radius = 0.36D + 0.05D * Math.sin(tickTime * 0.16D + i);
            float pulse = 0.76F + 0.24F * Mth.sin(tickTime * 0.11F + i * 1.7F);

            float x0 = (float) (centerX + Math.cos(angle) * radius);
            float z0 = (float) (centerZ + Math.sin(angle) * radius);
            float x1 = (float) (centerX - Math.cos(angle + 0.55D) * (radius * 0.72D));
            float z1 = (float) (centerZ - Math.sin(angle + 0.55D) * (radius * 0.72D));

            addLine(lineBuffer, poseStack,
                    x0, (float) minY, z0,
                    x1, (float) maxY, z1,
                    clamp01(r * (pulse + 0.10F)), clamp01(g * (pulse + 0.10F)), clamp01(b * (pulse + 0.10F)), 0.74F);
        }
    }

    private void addLine(VertexConsumer lineBuffer, PoseStack poseStack,
                         float startX, float startY, float startZ,
                         float endX, float endY, float endZ,
                         float r, float g, float b, float alpha) {
        Vec3 normal = new Vec3(endX - startX, endY - startY, endZ - startZ).normalize();
        lineBuffer.vertex(poseStack.last().pose(), startX, startY, startZ).color(r, g, b, alpha)
                .normal(poseStack.last().normal(), (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
        lineBuffer.vertex(poseStack.last().pose(), endX, endY, endZ).color(r, g, b, alpha)
                .normal(poseStack.last().normal(), (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
    }

    private int brighten(int color, int amount) {
        int a = (color >>> 24) & 0xFF;
        int r = Math.min(255, ((color >>> 16) & 0xFF) + amount);
        int g = Math.min(255, ((color >>> 8) & 0xFF) + amount);
        int b = Math.min(255, (color & 0xFF) + amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private int darken(int color, int amount) {
        int a = (color >>> 24) & 0xFF;
        int r = Math.max(0, ((color >>> 16) & 0xFF) - amount);
        int g = Math.max(0, ((color >>> 8) & 0xFF) - amount);
        int b = Math.max(0, (color & 0xFF) - amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private int mixColor(int first, int second, float amount) {
        float clampedAmount = Mth.clamp(amount, 0.0F, 1.0F);
        int alpha = Mth.floor(Mth.lerp(clampedAmount, (first >>> 24) & 0xFF, (second >>> 24) & 0xFF));
        int red = Mth.floor(Mth.lerp(clampedAmount, (first >>> 16) & 0xFF, (second >>> 16) & 0xFF));
        int green = Mth.floor(Mth.lerp(clampedAmount, (first >>> 8) & 0xFF, (second >>> 8) & 0xFF));
        int blue = Mth.floor(Mth.lerp(clampedAmount, first & 0xFF, second & 0xFF));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private float brightenColor(float base, float otherA, float otherB, float amount) {
        return clamp01(base + ((1.0F - ((otherA + otherB) * 0.5F)) * amount));
    }

    private EnumMap<Direction, PathGate> computePathGates(Set<Long> chunkLongs, BlockPos corePos, int pathWidth) {
        EnumMap<Direction, PathGateAccumulator> accumulators = new EnumMap<>(Direction.class);
        if (pathWidth <= 0 || chunkLongs.isEmpty()) {
            return new EnumMap<>(Direction.class);
        }

        double centerX = corePos.getX() + 0.5D;
        double centerZ = corePos.getZ() + 0.5D;
        double halfWidth = CoreBreachShape.halfPathWidth(pathWidth);
        double corridorMinX = centerX - halfWidth;
        double corridorMaxX = centerX + halfWidth;
        double corridorMinZ = centerZ - halfWidth;
        double corridorMaxZ = centerZ + halfWidth;

        for (Long chunkLong : chunkLongs) {
            ChunkPos chunkPos = new ChunkPos(chunkLong);

            if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x, chunkPos.z - 1))) {
                considerGate(accumulators, Direction.NORTH, chunkPos.getMinBlockZ(),
                        chunkPos.getMinBlockX(), chunkPos.getMaxBlockX() + 1D,
                        centerZ, corridorMinX, corridorMaxX);
            }
            if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x + 1, chunkPos.z))) {
                considerGate(accumulators, Direction.EAST, chunkPos.getMaxBlockX() + 1D,
                        chunkPos.getMinBlockZ(), chunkPos.getMaxBlockZ() + 1D,
                        centerX, corridorMinZ, corridorMaxZ);
            }
            if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x, chunkPos.z + 1))) {
                considerGate(accumulators, Direction.SOUTH, chunkPos.getMaxBlockZ() + 1D,
                        chunkPos.getMinBlockX(), chunkPos.getMaxBlockX() + 1D,
                        centerZ, corridorMinX, corridorMaxX);
            }
            if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x - 1, chunkPos.z))) {
                considerGate(accumulators, Direction.WEST, chunkPos.getMinBlockX(),
                        chunkPos.getMinBlockZ(), chunkPos.getMaxBlockZ() + 1D,
                        centerX, corridorMinZ, corridorMaxZ);
            }
        }

        EnumMap<Direction, PathGate> gates = new EnumMap<>(Direction.class);
        for (Map.Entry<Direction, PathGateAccumulator> entry : accumulators.entrySet()) {
            PathGateAccumulator accumulator = entry.getValue();
            if (accumulator != null && accumulator.isValid()) {
                gates.put(entry.getKey(), accumulator.toGate(entry.getKey()));
            }
        }
        return gates;
    }

    private void considerGate(EnumMap<Direction, PathGateAccumulator> accumulators, Direction direction, double axisCoord,
                              double edgeMinAlong, double edgeMaxAlong, double coreAxis,
                              double corridorMinAlong, double corridorMaxAlong) {
        if ((direction == Direction.EAST || direction == Direction.SOUTH) && axisCoord <= coreAxis) {
            return;
        }
        if ((direction == Direction.WEST || direction == Direction.NORTH) && axisCoord >= coreAxis) {
            return;
        }

        double clampedMin = Math.max(edgeMinAlong, corridorMinAlong);
        double clampedMax = Math.min(edgeMaxAlong, corridorMaxAlong);
        if (!CoreBreachShape.overlapsAlong(clampedMin, clampedMax, corridorMinAlong, corridorMaxAlong)) {
            return;
        }

        PathGateAccumulator accumulator = accumulators.get(direction);
        if (accumulator == null) {
            accumulator = new PathGateAccumulator();
            accumulators.put(direction, accumulator);
        }
        accumulator.offer(direction, axisCoord, clampedMin, clampedMax);
    }

    private PathGate matchingGate(EnumMap<Direction, PathGate> gates, double startX, double startZ, double endX, double endZ) {
        for (PathGate gate : gates.values()) {
            if (gate.matchesBoundary(startX, startZ, endX, endZ)) {
                return gate;
            }
        }
        return null;
    }

    private static final class PathGateAccumulator {
        private double axisCoord;
        private double minAlong;
        private double maxAlong;
        private boolean initialized;

        private void offer(Direction direction, double candidateAxis, double candidateMinAlong, double candidateMaxAlong) {
            if (!initialized || isBetter(direction, candidateAxis, axisCoord)) {
                axisCoord = candidateAxis;
                minAlong = candidateMinAlong;
                maxAlong = candidateMaxAlong;
                initialized = true;
                return;
            }

            if (Math.abs(candidateAxis - axisCoord) <= 1.0E-4D) {
                minAlong = Math.min(minAlong, candidateMinAlong);
                maxAlong = Math.max(maxAlong, candidateMaxAlong);
            }
        }

        private boolean isValid() {
            return initialized && maxAlong > minAlong;
        }

        private PathGate toGate(Direction direction) {
            return new PathGate(direction, axisCoord, minAlong, maxAlong);
        }

        private boolean isBetter(Direction direction, double candidateAxis, double currentAxis) {
            return switch (direction) {
                case EAST, SOUTH -> candidateAxis < currentAxis;
                case WEST, NORTH -> candidateAxis > currentAxis;
                default -> false;
            };
        }
    }

    private record PathGate(Direction direction, double axisCoord, double minAlong, double maxAlong) {
        private boolean vertical() {
            return direction == Direction.EAST || direction == Direction.WEST;
        }

        private boolean matchesBoundary(double startX, double startZ, double endX, double endZ) {
            boolean verticalBoundary = Math.abs(endX - startX) < 1.0E-4D;
            if (vertical() != verticalBoundary) {
                return false;
            }

            double boundaryAxis = vertical() ? startX : startZ;
            if (Math.abs(boundaryAxis - axisCoord) > 1.0E-4D) {
                return false;
            }

            double boundaryMinAlong = Math.min(vertical() ? startZ : startX, vertical() ? endZ : endX);
            double boundaryMaxAlong = Math.max(vertical() ? startZ : startX, vertical() ? endZ : endX);
            return CoreBreachShape.overlapsAlong(boundaryMinAlong, boundaryMaxAlong, minAlong, maxAlong);
        }
    }

    private record ClaimSegmentKey(int startX, int startZ, int endX, int endZ) {
        private static final double KEY_SCALE = 8.0D;

        private static ClaimSegmentKey of(double startX, double startZ, double endX, double endZ) {
            return new ClaimSegmentKey(
                    Mth.floor(startX * KEY_SCALE),
                    Mth.floor(startZ * KEY_SCALE),
                    Mth.floor(endX * KEY_SCALE),
                    Mth.floor(endZ * KEY_SCALE)
            );
        }
    }

    private record PostKey(int x, int z, int y, int kind) {
        private static final double SCALE = 16.0D;

        private static PostKey of(double x, double z, double y, int kind) {
            return new PostKey(
                    Mth.floor(x * SCALE),
                    Mth.floor(z * SCALE),
                    Mth.floor(y * SCALE),
                    kind
            );
        }
    }
}
