package com.fishguy129.fcw.client;

import com.fishguy129.fcw.config.FCWClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.Util;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// World overlays, raid HUD, and the looping audio cue.
public class FCWClientEvents {
    private static final ResourceLocation HOTBAR_OVERLAY = new ResourceLocation("minecraft", "hotbar");
    private static final double CORE_VISUAL_RADIUS = 7.0D;

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

    // Reveal / animation.
    private static final double CLAIM_OUTLINE_FADE_BAND_RATIO = 1.9D;
    private static final double CLAIM_OUTLINE_FADE_BAND_MIN = 10.0D;
    private static final float CLAIM_OUTLINE_FADE_IN_SPEED = 2.2F;
    private static final float CLAIM_OUTLINE_FADE_OUT_SPEED = 1.2F;
    private static final float CLAIM_OUTLINE_MIN_SEGMENT_VISIBILITY = 0.004F;
    private static final String RAID_ATTACKER_OUTLINE_TEAM = "fcw_raid_attacker_outline";
    private static final String RAID_DEFENDER_OUTLINE_TEAM = "fcw_raid_defender_outline";

    private static final Map<ClaimSegmentKey, Float> CLAIM_SEGMENT_VISIBILITY = new HashMap<>();
    private static final Map<UUID, String> RAID_OUTLINE_PREVIOUS_TEAMS = new HashMap<>();
    private static final Map<UUID, Boolean> RAID_OUTLINE_PREVIOUS_GLOWING = new HashMap<>();

    private RaidMusicSoundInstance activeRaidMusic;
    private ClientClaimOutlineState.ClaimOutline animatedClaimOutline;
    private long lastClaimAnimationMillis = Util.getMillis();

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientRaidState.clear();
        ClientClaimOutlineState.clear();
        ClientEnemyClaimOutlineState.clear();
        CLAIM_SEGMENT_VISIBILITY.clear();
        clearRaidParticipantOutlines(Minecraft.getInstance());
        animatedClaimOutline = null;
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
            for (ClientRaidState.RaidVisual raid : ClientRaidState.all()) {
                if (!raid.dimensionId().equals(minecraft.level.dimension().location())) {
                    continue;
                }
                renderRaidZone(lineBuffer, poseStack, raid, minecraft.player);
            }
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
        int width = 198;
        int height = 42;
        int x = (int) ((graphics.guiWidth() - (width * scale)) / 2F);
        int y = 14;
        long remainingSeconds = Mth.ceil(raid.remainingTicks() / 20.0D);
        int accent = raid.localPlayerAttacker() ? 0xFFFF6666 : 0xFF5EA7FF;
        float progress = raid.progressFraction();

        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1F);
        graphics.fillGradient(0, 0, width, height, 0xDE0E1720, 0xCC182632);
        graphics.fill(0, 0, width, 2, accent);
        graphics.fill(0, 0, 4, height, accent);
        graphics.fillGradient(8, height - 8, width - 8, height - 4, 0xFF0B1318, 0xFF0B1318);
        int fill = Mth.floor((width - 16) * progress);
        if (fill > 0) {
            graphics.fillGradient(8, height - 8, 8 + fill, height - 4, brighten(accent, 18), darken(accent, 20));
        }
        for (int i = 0; i < 5; i++) {
            graphics.fill(12 + (i * 34), 6, 24 + (i * 34), 7, 0x22FFFFFF);
        }
        graphics.drawString(minecraft.font, Component.literal(raid.localRoleLabel()), 12, 6, 0xFFF4FBFF, false);
        graphics.drawString(minecraft.font, Component.literal(raid.oppositionName()), 12, 19, 0xFFD5E7F3, false);
        graphics.drawString(minecraft.font, Component.literal(String.format("%02d:%02d", remainingSeconds / 60L, remainingSeconds % 60L)), 140, 6, 0xFFFFD47D, false);
        graphics.drawString(minecraft.font, Component.literal("Swordline"), 140, 19, 0xFF9DB6C5, false);
        graphics.drawString(minecraft.font, Component.literal((int) (progress * 100F) + "%"), 167, 31, 0xFFEFF8FF, false);
        graphics.renderFakeItem(new ItemStack(Items.IRON_SWORD), 108, 11);
        graphics.pose().popPose();
    }

    private void renderRaidZone(VertexConsumer lineBuffer, PoseStack poseStack, ClientRaidState.RaidVisual raid, LocalPlayer localPlayer) {
        if (localPlayer.distanceToSqr(raid.corePos().getX() + 0.5D, raid.corePos().getY() + 0.5D, raid.corePos().getZ() + 0.5D) > 96D * 96D) {
            return;
        }

        double radius = CORE_VISUAL_RADIUS;
        double minX = raid.corePos().getX() + 0.5D - radius;
        double maxX = raid.corePos().getX() + 0.5D + radius;
        double minZ = raid.corePos().getZ() + 0.5D - radius;
        double maxZ = raid.corePos().getZ() + 0.5D + radius;
        double y = raid.corePos().getY() + 0.04D;
        float alpha = FCWClientConfig.CORE_ZONE_RING_ALPHA.get().floatValue();

        LevelRenderer.renderLineBox(poseStack, lineBuffer, new AABB(minX, y, minZ, maxX, y + 0.04D, maxZ), 0.82F, 0.34F, 0.28F, alpha * 0.90F);
        LevelRenderer.renderLineBox(poseStack, lineBuffer, new AABB(minX + 0.4D, y, minZ + 0.4D, maxX - 0.4D, y + 0.02D, maxZ - 0.4D), 0.76F, 0.62F, 0.36F, alpha * 0.62F);
    }

    private void renderOwnedClaimOutline(VertexConsumer fillBuffer, PoseStack poseStack, LocalPlayer localPlayer) {
        if (!FCWClientConfig.ENABLE_OWN_CLAIM_OUTLINE.get()) {
            return;
        }

        ClientClaimOutlineState.ClaimOutline outline = ClientClaimOutlineState.get();
        if (outline == null || localPlayer.level() == null || !outline.dimensionId().equals(localPlayer.level().dimension().location())) {
            CLAIM_SEGMENT_VISIBILITY.clear();
            animatedClaimOutline = null;
            return;
        }

        Set<Long> chunkLongs = outline.chunkLongsView();
        if (chunkLongs.isEmpty()) {
            CLAIM_SEGMENT_VISIBILITY.clear();
            animatedClaimOutline = outline;
            return;
        }

        if (!outline.equals(animatedClaimOutline)) {
            CLAIM_SEGMENT_VISIBILITY.clear();
            animatedClaimOutline = outline;
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
        double wallBaseY = outline.coreY() + 0.08D;
        Set<ClaimSegmentKey> touchedSegments = new HashSet<>();

        for (Long chunkLong : chunkLongs) {
            ChunkPos chunkPos = new ChunkPos(chunkLong);
            if (Math.max(Math.abs(chunkPos.x - playerChunk.x), Math.abs(chunkPos.z - playerChunk.z)) > chunkRadius) {
                continue;
            }

            if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x, chunkPos.z - 1))) {
                renderClaimBoundary(fillBuffer, poseStack,
                        chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                        chunkPos.getMaxBlockX() + 1D, chunkPos.getMinBlockZ(),
                        baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                        revealDistance, outerRevealDistance, deltaSeconds, touchedSegments);
            }
            if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x + 1, chunkPos.z))) {
                renderClaimBoundary(fillBuffer, poseStack,
                        chunkPos.getMaxBlockX() + 1D, chunkPos.getMinBlockZ(),
                        chunkPos.getMaxBlockX() + 1D, chunkPos.getMaxBlockZ() + 1D,
                        baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                        revealDistance, outerRevealDistance, deltaSeconds, touchedSegments);
            }
            if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x, chunkPos.z + 1))) {
                renderClaimBoundary(fillBuffer, poseStack,
                        chunkPos.getMaxBlockX() + 1D, chunkPos.getMaxBlockZ() + 1D,
                        chunkPos.getMinBlockX(), chunkPos.getMaxBlockZ() + 1D,
                        baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                        revealDistance, outerRevealDistance, deltaSeconds, touchedSegments);
            }
            if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x - 1, chunkPos.z))) {
                renderClaimBoundary(fillBuffer, poseStack,
                        chunkPos.getMinBlockX(), chunkPos.getMaxBlockZ() + 1D,
                        chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                        baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                        revealDistance, outerRevealDistance, deltaSeconds, touchedSegments);
            }
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
                            wallBaseY, cachedAlpha);
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

        long now = Util.getMillis();
        float deltaSeconds = Mth.clamp((now - lastClaimAnimationMillis) / 1000.0F, 0.0F, 0.20F);

        for (ClientEnemyClaimOutlineState.EnemyOutline outline : enemyOutlines) {
            if (!outline.dimensionId().equals(currentDimension)) {
                continue;
            }

            Set<Long> chunkLongs = outline.chunkLongsView();
            if (chunkLongs.isEmpty()) {
                continue;
            }

            double wallBaseY = outline.coreY() + 0.08D;

            for (Long chunkLong : chunkLongs) {
                ChunkPos chunkPos = new ChunkPos(chunkLong);
                if (Math.max(Math.abs(chunkPos.x - playerChunk.x), Math.abs(chunkPos.z - playerChunk.z)) > chunkRadius) {
                    continue;
                }

                if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x, chunkPos.z - 1))) {
                    renderEnemyClaimBoundary(fillBuffer, poseStack,
                            chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                            chunkPos.getMaxBlockX() + 1D, chunkPos.getMinBlockZ(),
                            baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                            revealDistance, outerRevealDistance, deltaSeconds);
                }
                if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x + 1, chunkPos.z))) {
                    renderEnemyClaimBoundary(fillBuffer, poseStack,
                            chunkPos.getMaxBlockX() + 1D, chunkPos.getMinBlockZ(),
                            chunkPos.getMaxBlockX() + 1D, chunkPos.getMaxBlockZ() + 1D,
                            baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                            revealDistance, outerRevealDistance, deltaSeconds);
                }
                if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x, chunkPos.z + 1))) {
                    renderEnemyClaimBoundary(fillBuffer, poseStack,
                            chunkPos.getMaxBlockX() + 1D, chunkPos.getMaxBlockZ() + 1D,
                            chunkPos.getMinBlockX(), chunkPos.getMaxBlockZ() + 1D,
                            baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                            revealDistance, outerRevealDistance, deltaSeconds);
                }
                if (!chunkLongs.contains(ChunkPos.asLong(chunkPos.x - 1, chunkPos.z))) {
                    renderEnemyClaimBoundary(fillBuffer, poseStack,
                            chunkPos.getMinBlockX(), chunkPos.getMaxBlockZ() + 1D,
                            chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                            baseAlpha, wallBaseY, playerPos.x, playerPos.z,
                            revealDistance, outerRevealDistance, deltaSeconds);
                }
            }
        }
    }

    private void renderEnemyClaimBoundary(VertexConsumer fillBuffer, PoseStack poseStack,
                                          double startX, double startZ, double endX, double endZ,
                                          int baseAlpha, double baseY,
                                          double playerX, double playerZ,
                                          double revealDistance, double outerRevealDistance,
                                          float deltaSeconds) {
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
            int segmentAlpha = Mth.clamp((int) (baseAlpha * reveal), 0, 255);
            if (segmentAlpha <= 0) {
                continue;
            }

            renderEnemyWallSegment(fillBuffer, poseStack, segmentStartX, segmentStartZ, segmentEndX, segmentEndZ, baseY, segmentAlpha);
        }
    }

    private void renderEnemyWallSegment(VertexConsumer fillBuffer, PoseStack poseStack,
                                        double startX, double startZ, double endX, double endZ,
                                        double baseY, int alpha) {
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

        boolean hasTrimmedRails = alongX
                ? Math.abs(trimmedEndX - trimmedStartX) > 0.12D
                : Math.abs(trimmedEndZ - trimmedStartZ) > 0.12D;

        renderEnemyWallPost(fillBuffer, poseStack, startX, startZ, baseY, alpha);
        renderEnemyWallPost(fillBuffer, poseStack, endX, endZ, baseY, alpha);

        if (!hasTrimmedRails) {
            return;
        }

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 0.28D,
                baseY + 0.40D,
                CLAIM_RAIL_HALF_THICKNESS - 0.005D,
                alpha,
                0.38F, 0.12F, 0.10F);

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 0.88D,
                baseY + 1.07D,
                CLAIM_RAIL_HALF_THICKNESS,
                alpha,
                0.48F, 0.14F, 0.12F);

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 1.48D,
                baseY + 1.66D,
                CLAIM_RAIL_HALF_THICKNESS - 0.01D,
                alpha,
                0.42F, 0.13F, 0.11F);
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
                minX - 0.035D, baseY, minZ - 0.035D,
                maxX + 0.035D, footTop, maxZ + 0.035D,
                alpha,
                0.18F, 0.06F, 0.05F);

        renderSolidBox(fillBuffer, poseStack,
                minX, footTop, minZ,
                maxX, postTop, maxZ,
                alpha,
                0.44F, 0.12F, 0.10F);

        renderSolidBox(fillBuffer, poseStack,
                minX - CLAIM_POST_CAP_OVERHANG, postTop, minZ - CLAIM_POST_CAP_OVERHANG,
                maxX + CLAIM_POST_CAP_OVERHANG, baseY + CLAIM_WALL_HEIGHT, maxZ + CLAIM_POST_CAP_OVERHANG,
                alpha,
                0.52F, 0.16F, 0.12F);

        renderSolidBox(fillBuffer, poseStack,
                x - 0.032D, baseY + 1.05D, z - 0.032D,
                x + 0.032D, baseY + 1.20D, z + 0.032D,
                alpha,
                0.72F, 0.22F, 0.14F);
    }

    private void renderClaimBoundary(VertexConsumer fillBuffer, PoseStack poseStack,
                                     double startX, double startZ, double endX, double endZ,
                                     int baseAlpha, double baseY,
                                     double playerX, double playerZ,
                                     double revealDistance, double outerRevealDistance,
                                     float deltaSeconds, Set<ClaimSegmentKey> touchedSegments) {
        double edgeLength = Math.max(Math.abs(endX - startX), Math.abs(endZ - startZ));
        int segments = Math.max(1, Mth.ceil(edgeLength / CLAIM_SEGMENT_LENGTH));

        for (int segment = 0; segment < segments; segment++) {
            double segmentStartProgress = segment / (double) segments;
            double segmentEndProgress = (segment + 1) / (double) segments;

            double segmentStartX = Mth.lerp(segmentStartProgress, startX, endX);
            double segmentStartZ = Mth.lerp(segmentStartProgress, startZ, endZ);
            double segmentEndX = Mth.lerp(segmentEndProgress, startX, endX);
            double segmentEndZ = Mth.lerp(segmentEndProgress, startZ, endZ);

            ClaimSegmentKey key = ClaimSegmentKey.of(segmentStartX, segmentStartZ, segmentEndX, segmentEndZ);
            touchedSegments.add(key);

            float reveal = edgeRevealFactor(playerX, playerZ, segmentStartX, segmentStartZ, segmentEndX, segmentEndZ, revealDistance, outerRevealDistance);
            float animatedReveal = animateSegmentVisibility(key, reveal, deltaSeconds);
            float finalVisibility = easedVisibility(animatedReveal);
            int segmentAlpha = Mth.clamp((int) (baseAlpha * finalVisibility), 0, 255);
            if (segmentAlpha <= 0) {
                continue;
            }

            renderWallSegment(fillBuffer, poseStack, segmentStartX, segmentStartZ, segmentEndX, segmentEndZ, baseY, segmentAlpha);
        }
    }

    private void renderWallSegment(VertexConsumer fillBuffer, PoseStack poseStack,
                                   double startX, double startZ, double endX, double endZ,
                                   double baseY, int alpha) {
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

        boolean hasTrimmedRails = alongX
                ? Math.abs(trimmedEndX - trimmedStartX) > 0.12D
                : Math.abs(trimmedEndZ - trimmedStartZ) > 0.12D;

        renderWallPost(fillBuffer, poseStack, startX, startZ, baseY, alpha);
        renderWallPost(fillBuffer, poseStack, endX, endZ, baseY, alpha);

        if (!hasTrimmedRails) {
            return;
        }

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 0.28D,
                baseY + 0.40D,
                CLAIM_RAIL_HALF_THICKNESS - 0.005D,
                alpha,
                0.44F, 0.28F, 0.15F);

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 0.88D,
                baseY + 1.07D,
                CLAIM_RAIL_HALF_THICKNESS,
                alpha,
                0.52F, 0.34F, 0.18F);

        renderSolidBeam(fillBuffer, poseStack,
                trimmedStartX, trimmedStartZ, trimmedEndX, trimmedEndZ,
                baseY + 1.48D,
                baseY + 1.66D,
                CLAIM_RAIL_HALF_THICKNESS - 0.01D,
                alpha,
                0.48F, 0.31F, 0.17F);
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
                minX - 0.035D, baseY, minZ - 0.035D,
                maxX + 0.035D, footTop, maxZ + 0.035D,
                alpha,
                0.22F, 0.14F, 0.08F);

        renderSolidBox(fillBuffer, poseStack,
                minX, footTop, minZ,
                maxX, postTop, maxZ,
                alpha,
                0.49F, 0.31F, 0.17F);

        renderSolidBox(fillBuffer, poseStack,
                minX - CLAIM_POST_CAP_OVERHANG, postTop, minZ - CLAIM_POST_CAP_OVERHANG,
                maxX + CLAIM_POST_CAP_OVERHANG, baseY + CLAIM_WALL_HEIGHT, maxZ + CLAIM_POST_CAP_OVERHANG,
                alpha,
                0.58F, 0.38F, 0.20F);

        renderSolidBox(fillBuffer, poseStack,
                x - 0.032D, baseY + 1.05D, z - 0.032D,
                x + 0.032D, baseY + 1.20D, z + 0.032D,
                alpha,
                0.74F, 0.62F, 0.24F);
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

    private float brightenColor(float base, float otherA, float otherB, float amount) {
        return clamp01(base + ((1.0F - ((otherA + otherB) * 0.5F)) * amount));
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
}
