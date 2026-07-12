package com.bilal.campfyre.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.RotatingCubeMapRenderer;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

// Shared "cozy campfyre" look for the setup/code/join/status screens: a
// dark, drop-shadowed card with a crisp two-tone border and a warm amber
// accent, plus a small pixel campfyre (logs + flame) icon next to titles and
// primary buttons - modeled after config-style Minecraft GUI mods (Cloth
// Config/YACL: flat rectangular panels, one accent color, a subtle beveled
// border) rather than bare vanilla text floating on the title-screen
// background.
//
// An earlier version chamfered the panel's corners (two overlapping rects
// that stopped short of the corners, to fake rounding) - per feedback that
// read as a rendering bug ("looks cut out at the edges"), not a rounded
// corner, so panels are now plain full rectangles. A rounded look on 1.20.1
// vanilla widgets really needs a texture; a fake corner-cut at this pixel
// scale doesn't read as "rounded," it reads as broken.
//
// Public (class + the DOT_ status colors) because TitleScreenMixin, in the
// mixin subpackage, colors the docked button's status dot with them - Java
// package-private doesn't extend to subpackages.
public final class CampfyreUi {

    static final int ACCENT = 0xFFE0902F;
    static final int ACCENT_BRIGHT = 0xFFFFC266;
    static final int PANEL_BG = 0xF01B1512;
    static final int PANEL_BORDER_OUTER = 0xFF2A1B0E;
    static final int PANEL_BORDER_INNER = 0xFF4A3218;
    static final int SHADOW = 0x70000000;
    static final int TITLE_COLOR = 0xFFF2B25C;
    static final int TEXT_COLOR = 0xFFD8C3A5;
    static final int ERROR_COLOR = 0xFFE86A6A;
    static final int SUCCESS_COLOR = 0xFF8ED17F;
    static final int BUTTON_BG = 0xFF2A2118;
    static final int BUTTON_BG_DISABLED = 0xFF1C1712;
    static final int BUTTON_BORDER = 0xFF5C452A;
    static final int DISABLED_TEXT = 0xFF7A7267;
    static final int LOG_HIGHLIGHT = 0xFF8B5A2B;
    static final int LOG_SHADOW = 0xFF4A2E15;
    static final int MUTED_TEXT = 0xFF9A8B78;
    static final int INNER_BOX_BG = 0xFF120E0B;

    // Status-dot colors (docked button + status screen connection line).
    public static final int DOT_CONNECTED = SUCCESS_COLOR;
    public static final int DOT_CONNECTING = ACCENT_BRIGHT;
    public static final int DOT_OFFLINE = 0xFFE86A6A;
    public static final int DOT_IDLE = 0xFF6E655A;

    // --- v2 additions: GUI revamp ("Night by the Fire") ---------------------
    // Same warm-amber/charcoal family as the tokens above, extended for the
    // new shared components (CampfyreScrollPane/CampfyreChip/text-field
    // framing) rather than replacing them - every existing call site above
    // keeps compiling and looking the same family of "cozy campfyre," just
    // with richer chrome around it.
    static final int PANEL_BG_TOP = 0xF02A2018;
    static final int SCROLLBAR_TRACK = 0x40000000;
    static final int SCROLLBAR_THUMB = 0xFF5C452A;
    static final int SCROLLBAR_THUMB_HOVER = ACCENT;
    static final int CHIP_BG = 0xFF241A10;
    static final int CHIP_BG_ACCENT = 0xFF3A2A14;
    static final int FOCUS_RING = ACCENT_BRIGHT;
    static final int ROW_HOVER_BG = 0x1EFFFFFF;
    static final int ROW_DIVIDER = 0x2AFFFFFF;

    private static final long BREATHE_PERIOD_MS = 4000;

    /** 0..1 sinusoidal wave, one full cycle every periodMs - the "firelight breathing" pulse. */
    static float breathe(long periodMs) {
        return (float) (Math.sin((System.currentTimeMillis() % periodMs) / (double) periodMs * Math.PI * 2) * 0.5 + 0.5);
    }

    // --- Easing kit: every animated value in the new components is
    // f(elapsed/duration), never a per-frame accumulator - these turn a
    // linear 0..1 progress into a natural-feeling curve. ---------------------
    static float easeOutCubic(float t) {
        float f = t - 1.0F;
        return f * f * f + 1.0F;
    }

    static float easeOutBack(float t) {
        float c1 = 1.70158F;
        float c3 = c1 + 1.0F;
        float f = t - 1.0F;
        return 1.0F + c3 * f * f * f + c1 * f * f;
    }

    static float easeInOutQuad(float t) {
        if (t < 0.5F) return 2.0F * t * t;
        float f = -2.0F * t + 2.0F;
        return 1.0F - (f * f) / 2.0F;
    }

    /** Linear 0..1 progress of `now` between startMs and startMs+durationMs, clamped. */
    static float progress(long startMs, long durationMs, long now) {
        if (durationMs <= 0) return 1.0F;
        float t = (now - startMs) / (float) durationMs;
        return Math.max(0.0F, Math.min(1.0F, t));
    }

    static float progress(long startMs, long durationMs) {
        return progress(startMs, durationMs, System.currentTimeMillis());
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // Same public panorama asset TitleScreen itself renders. Used instead of
    // re-rendering the live parent Screen behind our panels: parent.render()
    // drew the real, still-interactive Singleplayer/Multiplayer/Realms
    // buttons directly under our own panel (both sit near the vertical
    // middle of the screen), which bled ghost button text through the dim
    // scrim. Rendering just the background ourselves sidesteps that - same
    // authentic vanilla panorama, no foreign widgets underneath.
    private static final RotatingCubeMapRenderer PANORAMA = new RotatingCubeMapRenderer(TitleScreen.PANORAMA_CUBE_MAP);
    private static final Identifier PANORAMA_OVERLAY = new Identifier("textures/gui/title/background/panorama_overlay.png");

    // A small campfyre: a two-tone flame over a two-tone log base. -1 means
    // "skip this row". 12 wide x 10 tall.
    private static final int[] FLAME_OUTER_LEFT = {5, 4, 4, 3, 3, 2, 3, 2};
    private static final int[] FLAME_OUTER_RIGHT = {7, 8, 8, 9, 9, 10, 9, 10};
    private static final int[] FLAME_INNER_LEFT = {-1, -1, 5, 5, 4, 4, 5, 4};
    private static final int[] FLAME_INNER_RIGHT = {-1, -1, 7, 7, 8, 8, 7, 8};
    private static final int FLAME_ROWS = FLAME_OUTER_LEFT.length;
    static final int ICON_WIDTH = 12;
    static final int ICON_HEIGHT = FLAME_ROWS + 2;

    private CampfyreUi() {
    }

    static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    // Every panel is a fixed ~320px card, but several explainer lines were
    // drawn as single centered strings that measured 380-470px - they bled
    // past the panel edges on both sides. All multi-word prose on the
    // screens goes through this now: wrapped to the panel's inner width,
    // never past it. Returns the y just below the last line so callers can
    // stack content beneath without hardcoding how many lines the wrap
    // produced.
    static int drawCenteredWrapped(DrawContext context, TextRenderer tr, String text,
                                   int centerX, int y, int maxWidth, int color) {
        for (net.minecraft.text.OrderedText line : tr.wrapLines(net.minecraft.text.StringVisitable.plain(text), maxWidth)) {
            context.drawCenteredTextWithShadow(tr, line, centerX, y, color);
            y += 11;
        }
        return y;
    }

    /** Real animated vanilla panorama, dimmed - the backdrop for every Campfyre screen. */
    static void renderPanoramaBackdrop(DrawContext context, int width, int height, float delta) {
        // In-game (the screens are reachable via the Campfyre keybind now)
        // the world itself is the backdrop - drawing the title-screen
        // panorama over it would look broken. Dim the world instead, the
        // same way vanilla's pause menu does.
        if (net.minecraft.client.MinecraftClient.getInstance().world != null) {
            context.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);
            return;
        }
        PANORAMA.render(delta, 1.0F);
        RenderSystem.enableBlend();
        context.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        context.drawTexture(PANORAMA_OVERLAY, 0, 0, width, height, 0.0F, 0.0F, 16, 128, 16, 128);
        context.fill(0, 0, width, height, 0x99000000);
    }

    /**
     * Drop shadow + flat dark card with a two-tone border + soft top sheen,
     * drawn before any widgets/text. v2: the card itself is a subtle
     * top-to-bottom gradient (PANEL_BG_TOP -> PANEL_BG) instead of one flat
     * fill, and a hairline amber outline just outside the border breathes
     * (~4s sine wave) so every panel in the mod reads as lit by the same
     * living light source, with zero changes needed at any call site.
     */
    static void drawPanel(DrawContext context, int left, int top, int right, int bottom) {
        context.fill(left + 3, top + 4, right + 3, bottom + 4, SHADOW);
        int glowAlpha = 45 + (int) (35 * breathe(BREATHE_PERIOD_MS));
        context.drawBorder(left - 4, top - 4, (right - left) + 8, (bottom - top) + 8, withAlpha(ACCENT & 0xFFFFFF, glowAlpha));
        context.fill(left - 2, top - 2, right + 2, bottom + 2, PANEL_BORDER_OUTER);
        context.fill(left - 1, top - 1, right + 1, bottom + 1, PANEL_BORDER_INNER);
        context.fillGradient(left, top, right, bottom, PANEL_BG_TOP, PANEL_BG);
        context.fillGradient(left, top, right, top + (bottom - top) / 3, withAlpha(0xFFFFFF, 18), 0x00FFFFFF);
    }

    static void drawDivider(DrawContext context, int centerX, int y, int width) {
        context.fillGradient(centerX - width / 2, y, centerX, y + 1, withAlpha(ACCENT, 0), withAlpha(ACCENT, 160));
        context.fillGradient(centerX, y, centerX + width / 2, y + 1, withAlpha(ACCENT, 160), withAlpha(ACCENT, 0));
    }

    /** Small pixel campfyre + title text, centered together as one unit. */
    static void drawTitle(DrawContext context, TextRenderer tr, Text title, int centerX, int y) {
        int textWidth = tr.getWidth(title);
        int gap = 6;
        int totalWidth = ICON_WIDTH + gap + textWidth;
        int startX = centerX - totalWidth / 2;
        drawCampfyreIcon(context, startX, y - 2);
        context.drawTextWithShadow(tr, title, startX + ICON_WIDTH + gap, y, TITLE_COLOR);
    }

    /**
     * A tiny pixel campfyre: crossed logs with a two-tone flame above them.
     * Animated: the flame tip sways one pixel side to side, the bright inner
     * core bobs, and a spark pops off the tip every cycle - purely
     * time-based (~8 fps flicker), so every call site (titles, buttons, the
     * docked title-screen icon, toasts, the HUD badge) breathes for free.
     * The animation only ever ADDS or SHIFTS pixels within the same
     * ICON_WIDTH x ICON_HEIGHT box, so layout math stays identical to the
     * static version.
     */
    static void drawCampfyreIcon(DrawContext context, int left, int top) {
        int frame = (int) ((System.currentTimeMillis() / 130L) % 4);
        int sway = SWAY[frame];

        for (int row = 0; row < FLAME_ROWS; row++) {
            int y = top + row;
            // Only the tip (top two rows) sways; the base of the flame stays
            // planted on the logs.
            int dx = row < 2 ? sway : 0;
            if (FLAME_OUTER_LEFT[row] >= 0) {
                context.fill(left + dx + FLAME_OUTER_LEFT[row], y, left + dx + FLAME_OUTER_RIGHT[row], y + 1, ACCENT);
            }
            // The inner core bobs up a pixel on alternating frames - reads
            // as heat, costs nothing.
            int coreBob = (frame == 1 || frame == 2) ? -1 : 0;
            if (FLAME_INNER_LEFT[row] >= 0 && row + coreBob >= 0) {
                context.fill(left + FLAME_INNER_LEFT[row], y + coreBob, left + FLAME_INNER_RIGHT[row], y + coreBob + 1, ACCENT_BRIGHT);
            }
        }

        // A single spark that pops off above the tip mid-cycle.
        if (frame == 2) {
            context.fill(left + 6 + sway, top - 1, left + 7 + sway, top, withAlpha(ACCENT_BRIGHT & 0xFFFFFF, 200));
        }

        int logY = top + FLAME_ROWS;
        context.fill(left + 1, logY, left + ICON_WIDTH - 1, logY + 1, LOG_HIGHLIGHT);
        context.fill(left, logY + 1, left + ICON_WIDTH, logY + 2, LOG_SHADOW);
    }

    private static final int[] SWAY = {0, 1, 0, -1};

    /** The same animated campfyre, scaled up - the hero mark on the setup screen. */
    static void drawCampfyreIconScaled(DrawContext context, int left, int top, float scale) {
        context.getMatrices().push();
        context.getMatrices().translate(left, top, 0);
        context.getMatrices().scale(scale, scale, 1.0F);
        drawCampfyreIcon(context, 0, 0);
        context.getMatrices().pop();
    }

    /**
     * Slow-drifting embers rising past the panel's flanks - drawn AFTER the
     * panel so they float over its edges. Deterministic from wall-clock time
     * (no per-frame state to keep), each ember on its own period/phase so
     * they never move in lockstep. Subtle on purpose: single pixels, faded
     * alpha, confined to narrow bands just outside the panel's left/right
     * edges where they can't sit on top of text.
     */
    static void drawEmbers(DrawContext context, int panelLeft, int panelTop, int panelRight, int panelBottom) {
        long now = System.currentTimeMillis();
        int rise = panelBottom - panelTop + 16;
        for (int i = 0; i < EMBER_COUNT; i++) {
            // Fixed pseudo-random parameters per ember, stable across frames.
            int seed = (i * 0x9E3779B9) >>> 8; // Knuth-style scatter so embers don't correlate
            float phase = (seed % 1000) / 1000.0F;
            long period = 5200 + (seed % 7) * 900L; // 5.2s - 10.6s per climb
            float progress = ((now % period) / (float) period + phase) % 1.0F;

            boolean leftSide = (i % 2) == 0;
            int band = 3 + (seed % 9); // 3..11 px outside the edge
            int x = leftSide ? panelLeft - band : panelRight + band - 1;
            // Slight horizontal wobble as it climbs.
            x += (int) (Math.sin((progress * 6.28318) * (2 + i % 2)) * 1.5);

            int y = panelBottom + 6 - (int) (progress * rise);
            if (y < panelTop - 10) continue;

            // Fade in near the bottom, fade out toward the top.
            float fade = Math.min(1.0F, Math.min(progress * 4.0F, (1.0F - progress) * 2.5F));
            int alpha = (int) (150 * fade);
            if (alpha <= 8) continue;

            int color = (i % 3 == 0) ? ACCENT_BRIGHT : ACCENT;
            context.fill(x, y, x + 1, y + 1, withAlpha(color & 0xFFFFFF, alpha));
        }
    }

    private static final int EMBER_COUNT = 10;

    /** Inset well for showcased content (the invite code) - darker than the panel, thin accent frame. */
    static void drawInnerBox(DrawContext context, int left, int top, int right, int bottom) {
        context.fill(left, top, right, bottom, INNER_BOX_BG);
        context.drawBorder(left, top, right - left, bottom - top, withAlpha(ACCENT & 0xFFFFFF, 90));
    }

    /**
     * 3x3 status dot with a soft glow. The CONNECTING color pulses so "still
     * trying" is visibly different from a settled state even out of the
     * corner of your eye.
     */
    static void drawStatusDot(DrawContext context, int x, int y, int color, boolean pulse) {
        int alpha = 255;
        if (pulse) {
            float wave = (float) (Math.sin(System.currentTimeMillis() / 220.0) * 0.5 + 0.5);
            alpha = 120 + (int) (135 * wave);
        }
        context.fill(x - 1, y, x + 2, y + 1, withAlpha(color & 0xFFFFFF, alpha));
        context.fill(x, y - 1, x + 1, y + 2, withAlpha(color & 0xFFFFFF, alpha));
        context.fill(x, y, x + 1, y + 1, withAlpha(0xFFFFFF, Math.min(alpha, 160)));
    }

    // --- v2: small pill "chip" - host/you badges, the copy-success morph
    // target. Not a widget - a plain draw call, so a Row lambda can place one
    // inline without any extra bookkeeping. ---------------------------------
    static int chipWidth(TextRenderer tr, String label) {
        return tr.getWidth(label) + 10;
    }

    static void drawChip(DrawContext context, TextRenderer tr, String label, int x, int y, int height, int textColor, boolean accent) {
        int width = chipWidth(tr, label);
        context.fill(x, y, x + width, y + height, accent ? CHIP_BG_ACCENT : CHIP_BG);
        context.drawBorder(x, y, width, height, accent
                ? withAlpha(ACCENT & 0xFFFFFF, 170) : withAlpha(BUTTON_BORDER & 0xFFFFFF, 160));
        context.drawCenteredTextWithShadow(tr, Text.literal(label), x + width / 2, y + (height - tr.fontHeight) / 2, textColor);
    }

    /** Small pixel checkmark - the shape the "Copy Invite" button morphs into after a successful copy. */
    static void drawCheckmark(DrawContext context, int x, int y, int color) {
        context.fill(x, y + 3, x + 2, y + 5, color);
        context.fill(x + 2, y + 5, x + 4, y + 7, color);
        context.fill(x + 4, y + 1, x + 8, y + 5, color);
    }

    // --- v2: roster avatar. Deliberately NOT a live skin texture - roster
    // entries are coordinator identities (they can be shown from the title
    // screen, before the player has ever joined the shared world), so
    // there's no guaranteed PlayerListEntry/skin to draw. A deterministic
    // colored initial-letter medallion instead: same name always gets the
    // same color, host gets the flame mark in place of a letter.
    private static final int[] AVATAR_PALETTE = {
            0xFF8B5A2B, 0xFF6E4A2E, 0xFFA9702F, 0xFF5C7A4A, 0xFF4A6B7A, 0xFF7A4A6B, 0xFF6B5F53, 0xFF8E6A3A,
    };

    private static int avatarColor(String name) {
        return AVATAR_PALETTE[Math.floorMod(name.hashCode(), AVATAR_PALETTE.length)];
    }

    static void drawAvatar(DrawContext context, TextRenderer tr, String name, int x, int y, int size, boolean isHost, boolean glow) {
        if (glow) {
            int glowAlpha = 60 + (int) (50 * breathe(BREATHE_PERIOD_MS));
            context.fill(x - 1, y - 1, x + size + 1, y + size + 1, withAlpha(ACCENT & 0xFFFFFF, glowAlpha));
        }
        context.fill(x, y, x + size, y + size, withAlpha(avatarColor(name), 255));
        context.drawBorder(x, y, size, size, withAlpha(0x000000, 120));
        if (isHost) {
            drawCampfyreIcon(context, x + (size - ICON_WIDTH) / 2, y + (size - ICON_HEIGHT) / 2);
        } else {
            String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            context.drawCenteredTextWithShadow(tr, Text.literal(initial), x + size / 2, y + (size - tr.fontHeight) / 2 + 1, 0xFFF2E8DC);
        }
    }

    // --- v2: styled text field framing. Draws a dark inset box + label +
    // amber focus ring AROUND a real TextFieldWidget rather than
    // reimplementing text editing - the widget itself just has its own
    // vanilla background turned off (styleTextField, called once from
    // init()) so this frame shows through instead. -------------------------
    static void styleTextField(TextFieldWidget field) {
        field.setDrawsBackground(false);
        field.setEditableColor(TEXT_COLOR);
        field.setUneditableColor(DISABLED_TEXT);
    }

    static void drawFieldFrame(DrawContext context, TextRenderer tr, TextFieldWidget field, String label) {
        int x = field.getX();
        int y = field.getY();
        int w = field.getWidth();
        int h = field.getHeight();
        if (label != null) {
            context.drawTextWithShadow(tr, Text.literal(label), x, y - 11, MUTED_TEXT);
        }
        context.fill(x - 2, y - 2, x + w + 2, y + h + 2, INNER_BOX_BG);
        boolean focused = field.isFocused();
        context.drawBorder(x - 2, y - 2, w + 4, h + 4, focused
                ? withAlpha(FOCUS_RING & 0xFFFFFF, 220) : withAlpha(BUTTON_BORDER & 0xFFFFFF, 150));
    }

    // --- v2: scrollbar chrome shared by every CampfyreScrollPane. ----------
    static void drawScrollbarTrack(DrawContext context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + height, SCROLLBAR_TRACK);
    }

    static void drawScrollbarThumb(DrawContext context, int x, int y, int width, int height, boolean hovered) {
        context.fill(x, y, x + width, y + height, hovered ? SCROLLBAR_THUMB_HOVER : SCROLLBAR_THUMB);
    }
}
