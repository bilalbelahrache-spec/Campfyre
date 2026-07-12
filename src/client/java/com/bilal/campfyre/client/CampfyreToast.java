package com.bilal.campfyre.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;

// Campfyre's own toast: the same dark card + amber accent + animated pixel
// flame as the rest of the mod's screens, instead of vanilla's stone-texture
// SystemToast. Every migration/connection notification a player sees now
// reads as "that's Campfyre talking" at a glance, even mid-game with a dozen
// other mods showing popups.
//
// Toast API (verified via javap against the mapped 1.20.1 jar):
//   Visibility draw(DrawContext, ToastManager, long startTime)
// startTime is milliseconds since this toast appeared; return HIDE to
// dismiss. Width/height default to 160x32 via the interface; we widen
// slightly so two-line descriptions don't have to be brutally truncated.
class CampfyreToast implements Toast {

    private static final long DISPLAY_MS = 6000;
    private static final int WIDTH = 180;
    private static final int HEIGHT = 32;

    // Kept as plain Strings: TextRenderer.trimToWidth(String, int) hands back
    // a String that DrawContext.drawTextWithShadow accepts directly, while
    // the Text/StringVisitable path needs an extra OrderedText conversion.
    private final String title;
    private final String description;

    CampfyreToast(String title, String description) {
        this.title = title;
        this.description = description;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public Visibility draw(DrawContext context, ToastManager manager, long startTime) {
        // Card: gradient dark panel (same top-lit look as every screen's
        // panel now) with the two-tone border and a breathing accent strip
        // down the left edge, echoing CampfyreButton's hover accent.
        context.fillGradient(0, 0, WIDTH, HEIGHT, CampfyreUi.PANEL_BG_TOP, CampfyreUi.PANEL_BG);
        context.drawBorder(0, 0, WIDTH, HEIGHT, CampfyreUi.PANEL_BORDER_INNER);
        int stripAlpha = 200 + (int) (55 * CampfyreUi.breathe(2600));
        context.fill(1, 1, 3, HEIGHT - 1, CampfyreUi.withAlpha(CampfyreUi.ACCENT & 0xFFFFFF, Math.min(255, stripAlpha)));
        context.fillGradient(1, 1, WIDTH - 1, HEIGHT / 2, CampfyreUi.withAlpha(0xFFFFFF, 14), 0x00FFFFFF);

        CampfyreUi.drawCampfyreIcon(context, 7, (HEIGHT - CampfyreUi.ICON_HEIGHT) / 2);

        TextRenderer tr = manager.getClient().textRenderer;
        int textX = 7 + CampfyreUi.ICON_WIDTH + 6;
        int maxTextWidth = WIDTH - textX - 6;
        context.drawTextWithShadow(tr, tr.trimToWidth(title, maxTextWidth), textX, 6, CampfyreUi.TITLE_COLOR);
        context.drawTextWithShadow(tr, tr.trimToWidth(description, maxTextWidth), textX, 18, CampfyreUi.TEXT_COLOR);

        return startTime >= DISPLAY_MS ? Visibility.HIDE : Visibility.SHOW;
    }
}
