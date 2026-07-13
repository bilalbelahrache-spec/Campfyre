package com.bilal.campfyre.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

// Shown right after a brand-new campfyre is created. The code shown here is
// the ONLY way friends can find this group (see isValidGroupId on the
// coordinator - there's no separate password/account layer), so this screen
// exists purely to make sure the host actually sees and copies it before
// moving on, instead of it flashing by in a toast and being lost.
public class CampfyreCodeScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 160;

    private final CampfyreClient mod;
    private final String code;
    private final Screen parent;
    private CampfyreButton copyButton;
    private long copiedUntilMs = 0;
    private final long openedAtMs = System.currentTimeMillis();

    public CampfyreCodeScreen(CampfyreClient mod, String code, Screen parent) {
        super(Text.literal("Your Campfyre is Lit"));
        this.mod = mod;
        this.code = code;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int panelTop = this.height / 2 - 74;
        int buttonWidth = 150;

        // What actually lands on the clipboard is the composite invite
        // ("CODE@host:port"), not the bare code - a bare code is useless to
        // a friend whose fresh install still points at localhost, which is
        // exactly the failure a real-world test hit. Pasting the composite
        // into the join screen configures their coordinator automatically.
        copyButton = this.addDrawableChild(new CampfyreButton(centerX - buttonWidth - 5, panelTop + 112, buttonWidth, 20,
                Text.literal("Copy Invite"), button -> {
                    this.client.keyboard.setClipboard(mod.getInviteCode());
                    copiedUntilMs = System.currentTimeMillis() + 1500;
                }, true));

        this.addDrawableChild(new CampfyreButton(centerX + 5, panelTop + 112, buttonWidth, 20,
                Text.literal("Continue"), button -> onContinue()));
    }

    @Override
    public void tick() {
        super.tick();
        boolean copied = System.currentTimeMillis() < copiedUntilMs;
        copyButton.setMessage(Text.literal(copied ? "Copied!" : "Copy Invite"));
        copyButton.setShowCheckmark(copied);
    }

    private void onContinue() {
        mod.connectToCoordinator();
        // Land on the live status screen instead of dumping straight back to
        // the title screen: the creator immediately sees the roster fill in
        // and the "Create the Shared World" button as their obvious next step.
        this.client.setScreen(new CampfyreStatusScreen(mod, parent));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        CampfyreUi.renderPanoramaBackdrop(context, this.width, this.height, delta);

        int centerX = this.width / 2;
        int panelTop = this.height / 2 - 74;
        int panelBottom = panelTop + 148;
        CampfyreUi.drawPanel(context, centerX - PANEL_HALF_WIDTH, panelTop, centerX + PANEL_HALF_WIDTH, panelBottom);
        CampfyreUi.drawEmbers(context, centerX - PANEL_HALF_WIDTH, panelTop, centerX + PANEL_HALF_WIDTH, panelBottom);
        super.render(context, mouseX, mouseY, delta);

        CampfyreUi.drawTitle(context, this.textRenderer, this.title, centerX, panelTop + 10);
        CampfyreUi.drawDivider(context, centerX, panelTop + 23, 280);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Send this invite to your friends so they can join:"),
                centerX, panelTop + 32, CampfyreUi.TEXT_COLOR);

        // The invite gets a proper showcase box - it's the single most
        // important string this mod ever shows anyone. "Ignition": the box
        // lands with a small easeOutBack bounce and a one-shot spark burst
        // radiating from its center, instead of just appearing - it's the
        // one moment in the whole mod worth making a small deal of.
        float ignition = CampfyreUi.easeOutBack(CampfyreUi.progress(openedAtMs, 260));
        int slideY = (int) ((1.0F - ignition) * 14);

        CampfyreUi.drawInnerBox(context, centerX - 130, panelTop + 46 + slideY, centerX + 130, panelTop + 84 + slideY);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(code).formatted(Formatting.BOLD),
                centerX, panelTop + 54 + slideY, CampfyreUi.TITLE_COLOR);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(CampfyreUi.trimWithEllipsis(this.textRenderer, mod.getInviteCode(), 250)),
                centerX, panelTop + 69 + slideY, CampfyreUi.MUTED_TEXT);
        renderIgnitionSparks(context, centerX, panelTop + 65);

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("It's a house key - anyone holding it can join."),
                centerX, panelTop + 94, CampfyreUi.MUTED_TEXT);
    }

    // One-shot spark burst radiating from the invite box's center for the
    // first ~500ms this screen is open - deterministic from openedAtMs (a
    // per-spark seed decides its angle/distance/color), so it never needs
    // per-frame state and, like every other animation in this mod, is just
    // f(now - openedAtMs).
    private static final int IGNITION_SPARK_COUNT = 10;
    private static final long IGNITION_SPARK_MS = 500;

    private void renderIgnitionSparks(DrawContext context, int centerX, int centerY) {
        long elapsed = System.currentTimeMillis() - openedAtMs;
        if (elapsed >= IGNITION_SPARK_MS) return;
        float t = elapsed / (float) IGNITION_SPARK_MS;
        for (int i = 0; i < IGNITION_SPARK_COUNT; i++) {
            int seed = (i * 0x9E3779B9) >>> 8;
            double angle = (seed % 360) * Math.PI / 180.0;
            double distance = t * (18 + (seed % 16));
            int x = centerX + (int) Math.round(Math.cos(angle) * distance);
            int y = centerY - (int) Math.round(Math.abs(Math.sin(angle)) * distance) - (int) (t * 8);
            int alpha = (int) (210 * (1.0F - t));
            if (alpha <= 8) continue;
            int color = (i % 2 == 0) ? CampfyreUi.ACCENT_BRIGHT : CampfyreUi.ACCENT;
            context.fill(x, y, x + 1, y + 1, CampfyreUi.withAlpha(color & 0xFFFFFF, alpha));
        }
    }

    @Override
    public void close() {
        onContinue();
    }
}
