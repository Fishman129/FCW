package com.fishguy129.fcw.client;

import com.fishguy129.fcw.config.FCWClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

// World overlays, raid HUD, and the looping audio cue.
public class FCWClientEvents {
    private static final ResourceLocation HOTBAR_OVERLAY = new ResourceLocation("minecraft", "hotbar");
    private static final double CORE_VISUAL_RADIUS = 7.0D;
    private RaidMusicSoundInstance activeRaidMusic;

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientRaidState.clear();
        activeRaidMusic = null;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            activeRaidMusic = null;
            return;
        }

        ClientRaidState.RaidVisual raid = ClientRaidState.localPlayerRaid(minecraft.level.dimension().location());
        if (raid == null || !FCWClientConfig.ENABLE_RAID_MUSIC.get()) {
            activeRaidMusic = null;
            return;
        }

        // Let the current sound instance keep running until it stops itself.
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
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer lineBuffer = bufferSource.getBuffer(RenderType.lines());
        Vec3 cameraPos = event.getCamera().getPosition();

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        if (FCWClientConfig.ENABLE_CORE_ZONE_RING.get()) {
            // The zone ring is shared raid context, so render it once per tracked core.
            for (ClientRaidState.RaidVisual raid : ClientRaidState.all()) {
                if (!raid.dimensionId().equals(minecraft.level.dimension().location())) {
                    continue;
                }
                renderRaidZone(lineBuffer, poseStack, raid, minecraft.player);
            }
        }

        if (FCWClientConfig.ENABLE_RAID_PLAYER_HIGHLIGHTS.get()) {
            for (Player player : minecraft.level.players()) {
                renderParticipantHighlight(lineBuffer, poseStack, player, minecraft.level.dimension().location(), minecraft.player);
            }
        }

        poseStack.popPose();
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

        // Keep the HUD anchored above the hotbar
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
        LevelRenderer.renderLineBox(poseStack, lineBuffer, new AABB(minX, y, minZ, maxX, y + 0.04D, maxZ), 1.0F, 0.36F, 0.36F, alpha);
        LevelRenderer.renderLineBox(poseStack, lineBuffer, new AABB(minX + 0.4D, y, minZ + 0.4D, maxX - 0.4D, y + 0.02D, maxZ - 0.4D), 0.34F, 0.62F, 1.0F, alpha * 0.85F);
    }

    private void renderParticipantHighlight(VertexConsumer lineBuffer, PoseStack poseStack,
                                            Player player, ResourceLocation dimensionId, LocalPlayer localPlayer) {
        for (ClientRaidState.RaidVisual raid : ClientRaidState.all()) {
            if (!raid.dimensionId().equals(dimensionId) || !raid.isParticipant(player.getUUID())) {
                continue;
            }
            if (!isInsideCoreZone(player, raid)) {
                continue;
            }
            if (localPlayer.distanceToSqr(raid.corePos().getX() + 0.5D, raid.corePos().getY() + 0.5D, raid.corePos().getZ() + 0.5D) > 96D * 96D) {
                continue;
            }

            boolean attacker = raid.isAttacker(player.getUUID());
            AABB box = player.getBoundingBox().inflate(0.18D, 0.12D, 0.18D);
            float r = attacker ? 1.0F : 0.34F;
            float g = attacker ? 0.24F : 0.58F;
            float b = attacker ? 0.24F : 1.0F;
            LevelRenderer.renderLineBox(poseStack, lineBuffer, box, r, g, b, 0.95F);
            LevelRenderer.renderLineBox(poseStack, lineBuffer, new AABB(box.minX - 0.04D, box.minY - 0.04D, box.minZ - 0.04D, box.maxX + 0.04D, box.maxY + 0.04D, box.maxZ + 0.04D), r, g, b, 0.5F);
            LevelRenderer.renderLineBox(poseStack, lineBuffer, new AABB(player.getX() - 0.1D, box.maxY, player.getZ() - 0.1D, player.getX() + 0.1D, box.maxY + 1.35D, player.getZ() + 0.1D), r, g, b, 0.72F);
            LevelRenderer.renderLineBox(poseStack, lineBuffer, new AABB(box.minX, box.maxY + 1.1D, box.minZ, box.maxX, box.maxY + 1.28D, box.maxZ), r, g, b, 0.7F);
            return;
        }
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

    private boolean isInsideCoreZone(Player player, ClientRaidState.RaidVisual raid) {
        double dx = player.getX() - (raid.corePos().getX() + 0.5D);
        double dz = player.getZ() - (raid.corePos().getZ() + 0.5D);
        return (dx * dx) + (dz * dz) <= CORE_VISUAL_RADIUS * CORE_VISUAL_RADIUS;
    }

    private ClientRaidState.RaidVisual participantRaid(Player player, ResourceLocation dimensionId, LocalPlayer localPlayer) {
        for (ClientRaidState.RaidVisual raid : ClientRaidState.all()) {
            if (!raid.dimensionId().equals(dimensionId) || !raid.isParticipant(player.getUUID())) {
                continue;
            }
            if (!isInsideCoreZone(player, raid)) {
                continue;
            }
            if (localPlayer.distanceToSqr(raid.corePos().getX() + 0.5D, raid.corePos().getY() + 0.5D, raid.corePos().getZ() + 0.5D) > 96D * 96D) {
                continue;
            }
            return raid;
        }
        return null;
    }

    private SoundEvent resolveSound(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : ForgeRegistries.SOUND_EVENTS.getValue(location);
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
}
