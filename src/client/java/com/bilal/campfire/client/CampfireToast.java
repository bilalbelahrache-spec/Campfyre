package com.bilal.campfire.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;

// Campfire's own toast: the same dark card + amber accent + animated pixel
// flame as the rest of the mod's screens, instead of vanilla's stone-texture
// SystemToast. Every migration/connection notification a player sees now
// reads as "that's Campfire talking" at a glance, even mid-game with a dozen
// other mods showing popups.
//
// Toast API (verified via javap against the mapped 1.20.1 jar):
//   Visibility draw(DrawContext, ToastManager, long startTime)
// startTime is milliseconds since this toast appeared; return HIDE to
// dismiss. Width/height default to 160x32 via the interface; we widen
// slightly so two-line descriptions don't have to be brutally truncated.
class CampfireToast implements Toast {

    private static final long DISPLAY_MS = 6000;
    private static final int WIDTH = 180;
    private static final int HEIGHT = 32;

    // Kept as plain Strings: TextRenderer.trimToWidth(String, int) hands back
    // a String that DrawContext.drawTextWithShadow accepts directly, while
    // the Text/StringVisitable path needs an extra OrderedText conversion.
    private final String title;
    private final String description;

    CampfireToast(String title, String description) {
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
        // down the left edge, echoing CampfireButton's hover accent.
        context.fillGradient(0, 0, WIDTH, HEIGHT, CampfireUi.PANEL_BG_TOP, CampfireUi.PANEL_BG);
        context.drawBorder(0, 0, WIDTH, HEIGHT, CampfireUi.PANEL_BORDER_INNER);
        int stripAlpha = 200 + (int) (55 * CampfireUi.breathe(2600));
        context.fill(1, 1, 3, HEIGHT - 1, CampfireUi.withAlpha(CampfireUi.ACCENT & 0xFFFFFF, Math.min(255, stripAlpha)));
        context.fillGradient(1, 1, WIDTH - 1, HEIGHT / 2, CampfireUi.withAlpha(0xFFFFFF, 14), 0x00FFFFFF);

        CampfireUi.drawCampfireIcon(context, 7, (HEIGHT - CampfireUi.ICON_HEIGHT) / 2);

        TextRenderer tr = manager.getClient().textRenderer;
        int textX = 7 + CampfireUi.ICON_WIDTH + 6;
        int maxTextWidth = WIDTH - textX - 6;
        context.drawTextWithShadow(tr, tr.trimToWidth(title, maxTextWidth), textX, 6, CampfireUi.TITLE_COLOR);
        context.drawTextWithShadow(tr, tr.trimToWidth(description, maxTextWidth), textX, 18, CampfireUi.TEXT_COLOR);

        return startTime >= DISPLAY_MS ? Visibility.HIDE : Visibility.SHOW;
    }
}
