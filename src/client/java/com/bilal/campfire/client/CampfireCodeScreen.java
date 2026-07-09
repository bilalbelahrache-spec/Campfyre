package com.bilal.campfire.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

// Shown right after a brand-new campfire is created. The code shown here is
// the ONLY way friends can find this group (see isValidGroupId on the
// coordinator - there's no separate password/account layer), so this screen
// exists purely to make sure the host actually sees and copies it before
// moving on, instead of it flashing by in a toast and being lost.
public class CampfireCodeScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 160;

    private final CampfireClient mod;
    private final String code;
    private final Screen parent;
    private CampfireButton copyButton;
    private long copiedUntilMs = 0;

    public CampfireCodeScreen(CampfireClient mod, String code, Screen parent) {
        super(Text.literal("Your Campfire is Lit"));
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
        copyButton = this.addDrawableChild(new CampfireButton(centerX - buttonWidth - 5, panelTop + 112, buttonWidth, 20,
                Text.literal("Copy Invite"), button -> {
                    this.client.keyboard.setClipboard(mod.getInviteCode());
                    copiedUntilMs = System.currentTimeMillis() + 1500;
                }, true));

        this.addDrawableChild(new CampfireButton(centerX + 5, panelTop + 112, buttonWidth, 20,
                Text.literal("Continue"), button -> onContinue()));
    }

    @Override
    public void tick() {
        super.tick();
        copyButton.setMessage(Text.literal(
                System.currentTimeMillis() < copiedUntilMs ? "Copied!" : "Copy Invite"));
    }

    private void onContinue() {
        mod.connectToCoordinator();
        // Land on the live status screen instead of dumping straight back to
        // the title screen: the creator immediately sees the roster fill in
        // and the "Create the Shared World" button as their obvious next step.
        this.client.setScreen(new CampfireStatusScreen(mod, parent));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        CampfireUi.renderPanoramaBackdrop(context, this.width, this.height, delta);

        int centerX = this.width / 2;
        int panelTop = this.height / 2 - 74;
        int panelBottom = panelTop + 148;
        CampfireUi.drawPanel(context, centerX - PANEL_HALF_WIDTH, panelTop, centerX + PANEL_HALF_WIDTH, panelBottom);
        CampfireUi.drawEmbers(context, centerX - PANEL_HALF_WIDTH, panelTop, centerX + PANEL_HALF_WIDTH, panelBottom);
        super.render(context, mouseX, mouseY, delta);

        CampfireUi.drawTitle(context, this.textRenderer, this.title, centerX, panelTop + 10);
        CampfireUi.drawDivider(context, centerX, panelTop + 23, 280);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Send this invite to your friends so they can join:"),
                centerX, panelTop + 32, CampfireUi.TEXT_COLOR);

        // The invite gets a proper showcase box - it's the single most
        // important string this mod ever shows anyone.
        CampfireUi.drawInnerBox(context, centerX - 130, panelTop + 46, centerX + 130, panelTop + 84);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(code).formatted(Formatting.BOLD),
                centerX, panelTop + 54, CampfireUi.TITLE_COLOR);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(this.textRenderer.trimToWidth(mod.getInviteCode(), 250)),
                centerX, panelTop + 69, CampfireUi.MUTED_TEXT);

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("It's a house key - anyone holding it can join."),
                centerX, panelTop + 94, CampfireUi.MUTED_TEXT);
    }

    @Override
    public void close() {
        onContinue();
    }
}
