package com.fishguy129.fcw.client;

import com.fishguy129.fcw.menu.FactionCoreMenu;
import com.fishguy129.fcw.network.CoreActionMessage;
import com.fishguy129.fcw.network.FCWNetwork;
import com.fishguy129.fcw.network.HologramLoadoutMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// Core screen (As you'll notice I never make textures for UIs, 
// working in web development just made me used to "drawing" the UIs myself).
// Why is the filed named "Polished"? Because I had an earlier version which got too complicated and I hated
// everything about it so I just deleted everything and started everything over.
public class FactionCorePolishedScreen extends AbstractContainerScreen<FactionCoreMenu> {
    private static final int PANEL_BORDER = 0xFF223945;
    private static final int PANEL_TOP = 0xF0101820;
    private static final int PANEL_BOTTOM = 0xD9182832;
    private static final int ACCENT_CYAN = 0xFF59D8F5;
    private static final int ACCENT_TEAL = 0xFF33E0C0;
    private static final int ACCENT_ORANGE = 0xFFFFA968;
    private static final int ACCENT_RED = 0xFFFF6666;
    private static final int ACCENT_BLUE = 0xFF5CA6FF;
    private static final int TEXT_BRIGHT = 0xFFF5FBFF;
    private static final int TEXT_MAIN = 0xFFD8E6EF;
    private static final int TEXT_MUTED = 0xFF9DB4C1;
    private static final int TEXT_DIM = 0xFF738D9B;

    private final List<HoverSlot> hoverSlots = new ArrayList<>();
    private final List<GhostSlot> displaySlots = new ArrayList<>();

    private StyledButton overviewTab;
    private StyledButton forgeTab;
    private StyledButton displayTab;
    private StyledButton raidButton;
    private StyledButton relocateButton;

    private View activeView = View.OVERVIEW;

    public FactionCorePolishedScreen(FactionCoreMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 352;
        imageHeight = 224;
        inventoryLabelY = 10000;
        titleLabelY = 10000;
    }

    @Override
    protected void init() {
        super.init();
        int footerY = topPos + imageHeight - 30;

        overviewTab = addRenderableWidget(new StyledButton(leftPos + 18, topPos + 40, 78, 18,
                Component.translatable("screen.fcw.faction_core.tab_overview"), ACCENT_CYAN, button -> setView(View.OVERVIEW)));
        forgeTab = addRenderableWidget(new StyledButton(leftPos + 102, topPos + 40, 78, 18,
                Component.translatable("screen.fcw.faction_core.tab_forge"), ACCENT_ORANGE, button -> setView(View.FORGE)));
        displayTab = addRenderableWidget(new StyledButton(leftPos + 186, topPos + 40, 78, 18,
                Component.translatable("screen.fcw.faction_core.tab_display"), ACCENT_BLUE, button -> setView(View.DISPLAY)));

        raidButton = addRenderableWidget(new StyledButton(leftPos + 18, footerY, 144, 22,
                Component.translatable("screen.fcw.faction_core.craft_raid_item"), ACCENT_ORANGE,
                button -> FCWNetwork.CHANNEL.sendToServer(new CoreActionMessage(menu.getCorePos(), null, CoreActionMessage.Action.CRAFT_RAID_ITEM))));
        relocateButton = addRenderableWidget(new StyledButton(leftPos + imageWidth - 162, footerY, 144, 22,
                Component.translatable("screen.fcw.faction_core.pack_core"), ACCENT_CYAN,
                button -> FCWNetwork.CHANNEL.sendToServer(new CoreActionMessage(menu.getCorePos(), null, CoreActionMessage.Action.PACK_CORE))));

        syncButtons();
    }

    @Override
    public void containerTick() {
        super.containerTick();
        syncButtons();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activeView == View.DISPLAY) {
            // Left click mirrors the held item into a hologram slot, right click clears it.
            for (GhostSlot slot : displaySlots) {
                if (slot.contains(mouseX, mouseY)) {
                    boolean clear = button == 1;
                    if (!clear && minecraft != null && minecraft.player != null) {
                        ItemStack held = minecraft.player.getMainHandItem();
                        if (!held.isEmpty()) {
                            ItemStack copy = held.copy();
                            copy.setCount(1);
                            menu.setHologramItem(slot.index(), copy);
                        }
                    } else if (clear) {
                        menu.setHologramItem(slot.index(), ItemStack.EMPTY);
                    }
                    FCWNetwork.CHANNEL.sendToServer(new HologramLoadoutMessage(menu.getCorePos(), slot.index(), clear));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        hoverSlots.clear();
        displaySlots.clear();
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        renderSlotTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        long time = minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() : 0L;
        float shimmer = 0.5F + 0.5F * Mth.sin(time * 0.08F);

        drawFrame(graphics, leftPos, topPos, imageWidth, imageHeight);
        drawPanel(graphics, leftPos + 10, topPos + 10, imageWidth - 20, 22, 0xE0122430, 0xCA1A313E);
        graphics.fillGradient(leftPos + 10, topPos + 10, leftPos + imageWidth - 10, topPos + 13, ACCENT_CYAN, ACCENT_TEAL);
        graphics.drawString(font, Component.translatable("screen.fcw.faction_core.title"), leftPos + 18, topPos + 17, TEXT_BRIGHT, false);
        drawStatusPill(graphics, leftPos + imageWidth - 86, topPos + 14,
                menu.isActive() ? Component.translatable("screen.fcw.faction_core.status_online") : Component.translatable("screen.fcw.faction_core.status_offline"),
                menu.isActive() ? ACCENT_TEAL : ACCENT_RED);

        drawPanel(graphics, leftPos + 10, topPos + 66, imageWidth - 20, 118, PANEL_TOP, PANEL_BOTTOM);
        drawPanel(graphics, leftPos + 10, topPos + imageHeight - 38, imageWidth - 20, 26, 0xD5121F28, 0xC2192933);
        graphics.drawString(font, titleForView(), leftPos + 18, topPos + 70, TEXT_BRIGHT, false);

        if (activeView == View.OVERVIEW) {
            renderOverview(graphics);
        } else if (activeView == View.FORGE) {
            renderForge(graphics);
        } else {
            renderDisplay(graphics);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    private void renderOverview(GuiGraphics graphics) {
        int x = leftPos + 16;
        int y = topPos + 94;
        drawCard(graphics, x, y, 132, 82, ACCENT_CYAN);
        drawCard(graphics, x + 138, y, 78, 82, ACCENT_TEAL);
        drawCard(graphics, x + 222, y, 104, 82, ACCENT_RED);

        graphics.drawString(font, Component.translatable("screen.fcw.faction_core.card_faction"), x + 10, y + 10, ACCENT_CYAN, false);
        drawTrimmed(graphics, Component.translatable("screen.fcw.faction_core.team", menu.getTeamName()), x + 10, y + 24, 110, TEXT_BRIGHT);
        drawTrimmed(graphics, Component.translatable("screen.fcw.faction_core.core_pos", menu.getCorePos().getX(), menu.getCorePos().getY(), menu.getCorePos().getZ()), x + 10, y + 38, 110, TEXT_MAIN);
        drawTrimmed(graphics, Component.translatable("screen.fcw.faction_core.upgrades", menu.upgradeCount()), x + 10, y + 52, 110, TEXT_MAIN);
        drawTrimmed(graphics, Component.translatable("screen.fcw.faction_core.zone_radius", 7), x + 10, y + 66, 110, TEXT_DIM);

        graphics.drawString(font, Component.translatable("screen.fcw.faction_core.card_claims"), x + 148, y + 10, ACCENT_TEAL, false);
        graphics.drawString(font, Component.literal(String.valueOf(menu.currentClaims())), x + 148, y + 26, TEXT_BRIGHT, false);
        drawBar(graphics, x + 148, y + 42, 54, 7, Mth.clamp((float) menu.currentClaims() / Math.max(1, menu.targetClaims()), 0F, 1F), ACCENT_TEAL, 0xFF17343B);

        graphics.drawString(font, Component.translatable("screen.fcw.faction_core.card_status"), x + 232, y + 10, ACCENT_RED, false);
        drawTrimmed(graphics, raidStatusLine(), x + 232, y + 26, 82, TEXT_MAIN);
    }

    private void renderForge(GuiGraphics graphics) {
        int x = leftPos + 16;
        int y = topPos + 94;
        drawCard(graphics, x, y, 156, 82, ACCENT_ORANGE);
        drawCard(graphics, x + 162, y, 164, 82, ACCENT_CYAN);

        graphics.drawString(font, Component.translatable("screen.fcw.faction_core.recipe_current"), x + 10, y + 10, TEXT_BRIGHT, false);
        renderRecipeGrid(graphics, menu.currentRaidRecipe(), x + 10, y + 26, 3, 6);
        drawTrimmed(graphics, Component.translatable("screen.fcw.faction_core.recipe_scale_now", menu.nextRaidCostScale()), x + 10, y + 66, 136, TEXT_DIM);

        graphics.drawString(font, Component.translatable("screen.fcw.faction_core.recipe_next"), x + 172, y + 10, TEXT_BRIGHT, false);
        renderItemSlot(graphics, menu.currentRaidResult(), x + 172, y + 28);
        graphics.drawString(font, Component.literal(">"), x + 196, y + 33, ACCENT_ORANGE, false);
        renderRecipeGrid(graphics, menu.nextRaidRecipe(), x + 210, y + 26, 2, 4);
    }

    private void renderDisplay(GuiGraphics graphics) {
        int x = leftPos + 16;
        int y = topPos + 94;
        drawCard(graphics, x, y, 132, 82, ACCENT_BLUE);
        drawCard(graphics, x + 138, y, 188, 82, ACCENT_CYAN);

        graphics.drawString(font, Component.translatable("screen.fcw.faction_core.display_now"), x + 10, y + 10, TEXT_BRIGHT, false);
        drawTrimmed(graphics, Component.translatable("screen.fcw.faction_core.display_hint"), x + 10, y + 26, 110, TEXT_MAIN);
        ItemStack held = minecraft != null && minecraft.player != null ? minecraft.player.getMainHandItem() : ItemStack.EMPTY;
        renderItemSlot(graphics, held, x + 10, y + 48);
        drawTrimmed(graphics, held.isEmpty() ? Component.translatable("screen.fcw.faction_core.display_empty_hand") : held.getHoverName(), x + 34, y + 53, 86, TEXT_MUTED);

        graphics.drawString(font, Component.translatable("screen.fcw.faction_core.display_slots"), x + 148, y + 10, TEXT_BRIGHT, false);
        List<ItemStack> hologramItems = menu.hologramItems();
        for (int i = 0; i < 3; i++) {
            int slotX = x + 148 + (i * 54);
            int slotY = y + 34;
            renderGhostSlot(graphics, slotX, slotY, i, hologramItems.get(i));
            graphics.drawString(font, Component.literal(String.valueOf(i + 1)), slotX + 5, slotY + 25, TEXT_DIM, false);
        }
    }

    private void renderRecipeGrid(GuiGraphics graphics, List<ItemStack> stacks, int x, int y, int columns, int maxVisible) {
        int visible = Math.min(stacks.size(), maxVisible);
        for (int i = 0; i < visible; i++) {
            renderItemSlot(graphics, stacks.get(i), x + (i % columns) * 22, y + (i / columns) * 22);
        }
        if (stacks.size() > maxVisible) {
            graphics.drawString(font, Component.translatable("screen.fcw.faction_core.recipe_more", stacks.size() - maxVisible), x, y + 44, TEXT_MUTED, false);
        }
    }

    private void renderGhostSlot(GuiGraphics graphics, int x, int y, int index, ItemStack stack) {
        graphics.fill(x - 3, y - 3, x + 21, y + 21, 0xDD091217);
        graphics.fillGradient(x - 2, y - 2, x + 20, y + 20, 0xFF1B313B, 0xFF101C24);
        graphics.fill(x - 2, y - 2, x + 20, y - 1, 0x55FFFFFF);
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x, y);
            graphics.renderItemDecorations(font, stack, x, y);
            hoverSlots.add(new HoverSlot(x - 3, y - 3, 24, 24, stack.copy(),
                    Component.translatable("screen.fcw.faction_core.display_slot_tooltip", index + 1)));
        } else {
            graphics.drawString(font, Component.literal("+"), x + 5, y + 4, TEXT_DIM, false);
            hoverSlots.add(new HoverSlot(x - 3, y - 3, 24, 24, ItemStack.EMPTY,
                    Component.translatable("screen.fcw.faction_core.display_slot_empty", index + 1)));
        }
        displaySlots.add(new GhostSlot(index, x - 3, y - 3, 24, 24));
    }

    private void renderItemSlot(GuiGraphics graphics, ItemStack stack, int x, int y) {
        graphics.fill(x - 2, y - 2, x + 18, y + 18, 0xD8091218);
        graphics.fillGradient(x - 1, y - 1, x + 17, y + 17, 0xFF1B313B, 0xFF101C24);
        graphics.fill(x - 2, y - 2, x + 18, y - 1, 0x55FFFFFF);
        graphics.fill(x - 2, y + 17, x + 18, y + 18, 0x66000000);
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x, y);
            graphics.renderItemDecorations(font, stack, x, y);
        }
    }

    private void renderSlotTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        for (HoverSlot slot : hoverSlots) {
            if (slot.contains(mouseX, mouseY)) {
                if (!slot.stack().isEmpty()) {
                    graphics.renderTooltip(font, slot.stack(), mouseX, mouseY);
                } else {
                    graphics.renderTooltip(font, slot.fallback(), mouseX, mouseY);
                }
                return;
            }
        }
    }

    private void setView(View view) {
        activeView = view;
        syncButtons();
    }

    private void syncButtons() {
        // Tabs only act like tabs when the current one is disabled.
        relocateButton.active = menu.canRelocate();
        overviewTab.active = activeView != View.OVERVIEW;
        forgeTab.active = activeView != View.FORGE;
        displayTab.active = activeView != View.DISPLAY;
    }

    private Component titleForView() {
        return switch (activeView) {
            case OVERVIEW -> Component.translatable("screen.fcw.faction_core.section_overview");
            case FORGE -> Component.translatable("screen.fcw.faction_core.section_recipe");
            case DISPLAY -> Component.translatable("screen.fcw.faction_core.section_display");
        };
    }

    private Component raidStatusLine() {
        if (minecraft == null || minecraft.level == null) {
            return Component.translatable("screen.fcw.faction_core.raid_idle");
        }
        ClientRaidState.RaidVisual raid = ClientRaidState.get(minecraft.level.dimension().location(), menu.getCorePos());
        if (raid == null) {
            return Component.translatable("screen.fcw.faction_core.raid_idle");
        }
        long remaining = Math.max(0L, Mth.ceil(raid.remainingTicks() / 20.0D));
        return Component.translatable("screen.fcw.faction_core.raid_active", remaining / 60L, String.format("%02d", remaining % 60L), raid.attackerName());
    }

    private void drawTrimmed(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        Component draw = text;
        if (font.width(text) > width) {
            String trimmed = font.plainSubstrByWidth(text.getString(), Math.max(0, width - font.width("...")));
            draw = Component.literal(trimmed + "...");
        }
        graphics.drawString(font, draw, x, y, color, false);
    }

    private void drawFrame(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fillGradient(x, y, x + width, y + height, 0xFF061017, 0xFF0D1920);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF14232C);
        graphics.fill(x + 3, y + 3, x + width - 3, y + height - 3, 0xFF091119);
        graphics.fill(x + 4, y + 4, x + width - 4, y + height - 4, 0xF00E171D);
        graphics.fill(x, y, x + width, y + 1, ACCENT_CYAN);
        graphics.fill(x, y + height - 1, x + width, y + height, ACCENT_ORANGE);
    }

    private void drawPanel(GuiGraphics graphics, int x, int y, int width, int height, int top, int bottom) {
        graphics.fillGradient(x, y, x + width, y + height, top, bottom);
        graphics.fill(x, y, x + width, y + 1, PANEL_BORDER);
        graphics.fill(x, y + height - 1, x + width, y + height, PANEL_BORDER);
        graphics.fill(x, y, x + 1, y + height, PANEL_BORDER);
        graphics.fill(x + width - 1, y, x + width, y + height, PANEL_BORDER);
    }

    private void drawCard(GuiGraphics graphics, int x, int y, int width, int height, int accent) {
        drawPanel(graphics, x, y, width, height, PANEL_TOP, PANEL_BOTTOM);
        graphics.fill(x, y, x + 3, y + height, accent);
        graphics.fillGradient(x + 3, y, x + width, y + 2, (150 << 24) | (accent & 0x00FFFFFF), 0x00FFFFFF);
    }

    private void drawStatusPill(GuiGraphics graphics, int x, int y, Component text, int color) {
        graphics.fill(x, y, x + 64, y + 14, 0xDD081116);
        graphics.fill(x, y, x + 4, y + 14, color);
        graphics.drawString(font, text, x + 10, y + 4, TEXT_BRIGHT, false);
    }

    private void drawBar(GuiGraphics graphics, int x, int y, int width, int height, float progress, int fillColor, int bgColor) {
        graphics.fill(x, y, x + width, y + height, 0xFF0B1318);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, bgColor);
        int fill = Mth.floor((width - 2) * progress);
        if (fill > 0) {
            graphics.fillGradient(x + 1, y + 1, x + 1 + fill, y + height - 1, brighten(fillColor, 18), darken(fillColor, 20));
        }
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

    private enum View {
        OVERVIEW,
        FORGE,
        DISPLAY
    }

    private record GhostSlot(int index, int x, int y, int width, int height) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private record HoverSlot(int x, int y, int width, int height, ItemStack stack, Component fallback) {
        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private static class StyledButton extends Button {
        private final int accent;

        protected StyledButton(int x, int y, int width, int height, Component message, int accent, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.accent = accent;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int bgTop = active ? 0xF0121E27 : 0xA50F171F;
            int bgBottom = active ? 0xD51A2C37 : 0x8B16242C;
            if (isHoveredOrFocused()) {
                bgTop = brighten(bgTop, 10);
                bgBottom = brighten(bgBottom, 10);
            }
            graphics.fillGradient(getX(), getY(), getX() + width, getY() + height, bgTop, bgBottom);
            graphics.fill(getX(), getY(), getX() + width, getY() + 2, accent);
            graphics.fill(getX(), getY(), getX() + 2, getY() + height, accent);
            graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, 0x66000000);
            graphics.fill(getX(), getY(), getX() + width, getY() + 1, 0x55FFFFFF);
            graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(), getX() + width / 2, getY() + (height - 8) / 2, active ? TEXT_BRIGHT : TEXT_MUTED);
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            defaultButtonNarrationText(narrationElementOutput);
        }

        private int brighten(int color, int amount) {
            int a = (color >>> 24) & 0xFF;
            int r = Math.min(255, ((color >>> 16) & 0xFF) + amount);
            int g = Math.min(255, ((color >>> 8) & 0xFF) + amount);
            int b = Math.min(255, (color & 0xFF) + amount);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }
}
