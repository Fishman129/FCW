package com.fishguy129.fcw.client;

import com.fishguy129.fcw.blockentity.FactionCoreBlockEntity;
import com.fishguy129.fcw.config.FCWClientConfig;
import com.fishguy129.fcw.registry.FCWItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// Handles the floating core hologram and the extra raid readout above it.
public class FactionCoreBlockEntityRenderer implements BlockEntityRenderer<FactionCoreBlockEntity> {
    private final ItemRenderer itemRenderer;
    private final Font font;

    public FactionCoreBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
        this.font = context.getFont();
    }

    @Override
    public void render(FactionCoreBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        // Bail out early when the core is missing ownership data or the client turned the effect off.
        if (blockEntity.getLevel() == null || blockEntity.getTeamId() == null || !FCWClientConfig.ENABLE_CORE_HOLOGRAM.get()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        int visibleRadius = Math.max(1, blockEntity.getBreachRadius());
        if (minecraft.player.distanceToSqr(blockEntity.getBlockPos().getX() + 0.5D, blockEntity.getBlockPos().getY() + 0.5D, blockEntity.getBlockPos().getZ() + 0.5D) > visibleRadius * visibleRadius) {
            return;
        }

        long time = blockEntity.getLevel().getGameTime();
        float ticks = time + partialTick;
        float bob = 0.08F * Mth.sin((ticks + blockEntity.getBlockPos().asLong()) * 0.09F);
        ClientRaidState.RaidVisual raidVisual = ClientRaidState.get(blockEntity.getLevel().dimension().location(), blockEntity.getBlockPos());
        double baseScale = FCWClientConfig.CORE_HOLOGRAM_SCALE.get();
        ItemStack displayStack = raidVisual != null ? new ItemStack(FCWItems.RAID_BEACON.get()) : new ItemStack(FCWItems.CLAIM_CATALYST.get());

        poseStack.pushPose();
        poseStack.translate(0.5D, 1.26D + bob, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees((ticks * 3.0F) % 360F));
        float scale = raidVisual != null ? 0.82F : 0.68F;
        poseStack.scale((float) (scale * baseScale), (float) (scale * baseScale), (float) (scale * baseScale));
        itemRenderer.renderStatic(displayStack, ItemDisplayContext.GROUND, packedLight, packedOverlay, poseStack, buffer, blockEntity.getLevel(), 0);
        poseStack.popPose();

        renderOrbiters(blockEntity, raidVisual, ticks, poseStack, buffer, packedLight, packedOverlay, baseScale);

        if (raidVisual != null) {
            renderRaidLabel(blockEntity, raidVisual, partialTick, poseStack, buffer, packedLight);
        } else {
            renderIdleLabel(blockEntity, partialTick, poseStack, buffer, packedLight);
        }
    }

    private void renderIdleLabel(FactionCoreBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        String teamName = blockEntity.getTeamName().isBlank() ? "UNBOUND CORE" : blockEntity.getTeamName();
        Component stateLine = Component.literal(blockEntity.isActive() ? "CORE ONLINE" : "CORE INACTIVE");
        Component claimsLine = Component.literal("Claims " + blockEntity.getCurrentClaims());
        Component upgradeLine = Component.literal("Upgrades " + blockEntity.getUpgradeCount() + "  Radius " + blockEntity.getBreachRadius());

        poseStack.pushPose();
        poseStack.translate(0.5D, 2.04D + Mth.sin((blockEntity.getLevel().getGameTime() + partialTick) * 0.07F) * 0.04F, 0.5D);
        poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.0185F, -0.0185F, 0.0185F);
        drawLine(poseStack, buffer, Component.literal(teamName), 0xFF9DF6FF, packedLight, -18);
        drawLine(poseStack, buffer, stateLine, blockEntity.isActive() ? 0xFF63F0D2 : 0xFFF56A6A, packedLight, -4);
        drawLine(poseStack, buffer, claimsLine, 0xFFDCEAF5, packedLight, 10);
        drawLine(poseStack, buffer, upgradeLine, 0xFFB6D4E6, packedLight, 24);
        poseStack.popPose();
    }

    private void renderRaidLabel(FactionCoreBlockEntity blockEntity, ClientRaidState.RaidVisual raidVisual, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        long remainingSeconds = Mth.ceil(raidVisual.remainingTicks() / 20.0D);
        String timerText = String.format("%02d:%02d", remainingSeconds / 60L, remainingSeconds % 60L);
        String bar = progressBar(raidVisual.progressFraction());
        String versus = raidVisual.attackerName() + " vs " + raidVisual.defenderName();
        String zone = "Zone " + blockEntity.getBreachRadius() + "  Forge x" + blockEntity.getNextRaidCostScale();

        poseStack.pushPose();
        poseStack.translate(0.5D, 2.1D + Mth.sin((blockEntity.getLevel().getGameTime() + partialTick) * 0.08F) * 0.05F, 0.5D);
        poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.021F, -0.021F, 0.021F);
        drawLine(poseStack, buffer, Component.literal("RAID LOCK " + timerText), 0xFFFF6B6B, packedLight, -22);
        drawLine(poseStack, buffer, Component.literal(bar), 0xFFFFD27A, packedLight, -8);
        drawLine(poseStack, buffer, Component.literal(versus), 0xFF71B8FF, packedLight, 6);
        drawLine(poseStack, buffer, Component.literal(zone), 0xFFDDE8F3, packedLight, 20);
        poseStack.popPose();
    }

    private void renderOrbiters(FactionCoreBlockEntity blockEntity, ClientRaidState.RaidVisual raidVisual, float ticks, PoseStack poseStack,
                                MultiBufferSource buffer, int packedLight, int packedOverlay, double baseScale) {
        List<ItemStack> orbiters = resolveOrbiterItems(blockEntity, raidVisual);
        double[] heights = {1.02D, 1.16D, 1.3D};
        float[] radii = {0.68F, 0.54F, 0.44F};
        for (int i = 0; i < orbiters.size(); i++) {
            renderOrbiter(blockEntity, ticks, poseStack, buffer, packedLight, packedOverlay, orbiters.get(i),
                    i * (360F / orbiters.size()), radii[Math.min(i, radii.length - 1)], heights[Math.min(i, heights.length - 1)], baseScale);
        }
    }

    private void renderOrbiter(FactionCoreBlockEntity blockEntity, float ticks, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                               int packedOverlay, ItemStack stack, float offsetDegrees, float radius, double y, double baseScale) {
        poseStack.pushPose();
        float angle = (ticks * 4F + offsetDegrees) % 360F;
        double radians = Math.toRadians(angle);
        poseStack.translate(0.5D + (Math.cos(radians) * radius), y + (Math.sin((ticks + offsetDegrees) * 0.07F) * 0.04F),
                0.5D + (Math.sin(radians) * radius));
        poseStack.mulPose(Axis.YP.rotationDegrees(-angle));
        float scale = (float) (0.34F * baseScale);
        poseStack.scale(scale, scale, scale);
        itemRenderer.renderStatic(stack, ItemDisplayContext.GROUND, packedLight, packedOverlay, poseStack, buffer, blockEntity.getLevel(), 0);
        poseStack.popPose();
    }

    private List<ItemStack> resolveOrbiterItems(FactionCoreBlockEntity blockEntity, ClientRaidState.RaidVisual raidVisual) {
        List<ItemStack> configured = new ArrayList<>();
        for (ItemStack stack : blockEntity.getHologramItems()) {
            if (!stack.isEmpty()) {
                configured.add(stack);
            }
        }
        if (!configured.isEmpty()) {
            return configured;
        }

        // Fall back to a simple themed set before customization. Someone please ban the Jeffhammer item
        // because I have a feeling Jeffhammer will want to use a Jeffhammer.
        List<ItemStack> fallback = new ArrayList<>();
        fallback.add(raidVisual != null ? new ItemStack(Items.IRON_SWORD) : new ItemStack(Items.COMPASS));
        fallback.add(raidVisual != null ? new ItemStack(Items.SHIELD) : new ItemStack(Items.AMETHYST_SHARD));
        if (raidVisual != null) {
            fallback.add(new ItemStack(Items.CLOCK));
        }
        return fallback;
    }

    private void drawLine(PoseStack poseStack, MultiBufferSource buffer, Component text, int color, int packedLight, int y) {
        float x = -font.width(text) / 2F;
        font.drawInBatch(text, x, y, color, false, poseStack.last().pose(), buffer, Font.DisplayMode.SEE_THROUGH, 0, packedLight);
    }

    private String progressBar(float progress) {
        int filled = Mth.clamp(Mth.floor(progress * 10F), 0, 10);
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            builder.append(i < filled ? "#" : "-");
        }
        return builder.append(']').toString();
    }

    @Override
    public boolean shouldRenderOffScreen(FactionCoreBlockEntity blockEntity) {
        return false;
    }

    @Override
    public int getViewDistance() {
        return 24;
    }
}
