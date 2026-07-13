package com.bilal.campfyre.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

// A small badge in the top-left corner while you're actually in the shared
// world: the animated flame plus "Hosting for 2 friends" / "At <name>'s camp
// - via relay". It answers the two questions players otherwise have to guess
// at mid-game ("is anyone actually with me?", "did I get a direct connection
// or am I on the relay?") without opening anything - then GETS OUT OF THE
// WAY: it shows for a few seconds whenever what it says changes (entering
// the world, a friend joining/leaving, the connection tier shifting) and
// fades out, instead of sitting on screen for the whole session. The B
// keybind's status screen is the always-available view. Renders nothing at
// all outside the managed world, so it can't bother anyone's unrelated
// singleplayer/multiplayer sessions.
final class CampfyreHud {

    private static final int PADDING = 4;
    private static final int MARGIN = 4;

    private static final long SHOW_MS = 6000;
    private static final long FADE_MS = 700;

    // What the badge said when it last showed, and until when it's visible.
    // Wall-clock based like every other animation in CampfyreUi - no
    // per-frame state to accumulate.
    private static String lastShownText;
    private static long visibleUntilMs;

    private CampfyreHud() {
    }

    static void register(CampfyreClient mod) {
        HudRenderCallback.EVENT.register((context, tickDelta) -> render(mod, context));
    }

    private static void render(CampfyreClient mod, DrawContext context) {
        String status = mod.describeHudStatus();
        if (status == null) {
            // Out of the shared world - forget what we showed, so re-entering
            // always announces itself again.
            lastShownText = null;
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        // stay out of the F3 overlay's way
        //? if <1.20.2 {
        if (client.options.debugEnabled) return;
        //?}
        //? if >=1.20.2 {
        /*if (client.inGameHud.getDebugHud().shouldShowDebugHud()) return;
        *///?}
        String detail = mod.describeHudDetail();

        // Any change in what the badge would say earns it a fresh few seconds
        // on screen; saying the same thing forever earns it nothing.
        String text = status + "\n" + (detail == null ? "" : detail);
        long now = System.currentTimeMillis();
        if (!text.equals(lastShownText)) {
            lastShownText = text;
            visibleUntilMs = now + SHOW_MS;
        }
        long remaining = visibleUntilMs - now;
        if (remaining <= 0) return;

        int alpha = remaining >= FADE_MS ? 255 : (int) (255 * remaining / FADE_MS);
        if (alpha < 16) return; // vanilla's text renderer misdraws near-zero alpha

        TextRenderer tr = client.textRenderer;
        int textX = MARGIN + PADDING + CampfyreUi.ICON_WIDTH + 5;
        int textWidth = Math.max(tr.getWidth(status), detail != null ? tr.getWidth(detail) : 0);
        int boxRight = textX + textWidth + PADDING;
        int boxBottom = MARGIN + PADDING + (detail != null ? 21 : 12) + PADDING;

        context.fillGradient(MARGIN, MARGIN, boxRight, boxBottom,
                CampfyreUi.withAlpha(CampfyreUi.PANEL_BG_TOP & 0xFFFFFF, 150 * alpha / 255),
                CampfyreUi.withAlpha(0x1B1512, 150 * alpha / 255));
        int stripAlpha = (int) ((160 + 90 * CampfyreUi.breathe(2600)) * alpha / 255);
        context.fill(MARGIN, MARGIN, MARGIN + 2, boxBottom, CampfyreUi.withAlpha(CampfyreUi.ACCENT & 0xFFFFFF, Math.min(255, stripAlpha)));

        // The pixel flame has no alpha channel - draw it only while the badge
        // is fully opaque so the fade reads as the card dissolving, not the
        // flame lingering on its own.
        if (alpha == 255) {
            CampfyreUi.drawCampfyreIcon(context, MARGIN + PADDING + 1, MARGIN + PADDING + 1);
        }
        context.drawTextWithShadow(tr, status, textX, MARGIN + PADDING + 1,
                (CampfyreUi.TITLE_COLOR & 0xFFFFFF) | (alpha << 24));
        if (detail != null) {
            context.drawTextWithShadow(tr, detail, textX, MARGIN + PADDING + 12,
                    (CampfyreUi.MUTED_TEXT & 0xFFFFFF) | (alpha << 24));
        }
    }
}
