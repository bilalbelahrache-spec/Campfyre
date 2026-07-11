package com.bilal.campfire.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

// Shown when the player opens the docked title-screen "Campfire" button
// before any group is configured - this is their first choice: start a
// brand-new shared world for their friends, or join one a friend already
// created. Purely a chooser now: creating (and its coordinator-address
// question) lives on CampfireCreateScreen, joining on CampfireJoinScreen,
// so this screen stays a calm two-option fork with no fields to puzzle over.
// Nothing talks to the coordinator from here.
public class CampfireSetupScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 160;
    private static final int PANEL_HEIGHT = 172;
    // Inner width for wrapped prose - the panel is 320 wide, minus margins.
    private static final int TEXT_WRAP_WIDTH = 300;

    private final CampfireClient mod;
    private final Screen parent;

    public CampfireSetupScreen(CampfireClient mod, Screen parent) {
        super(Text.literal("Welcome to Campfire"));
        this.mod = mod;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int buttonWidth = 220;
        int panelTop = this.height / 2 - 86;

        this.addDrawableChild(new CampfireButton(centerX - buttonWidth / 2, panelTop + 92, buttonWidth, 20,
                Text.literal("Light a New Campfire"),
                button -> this.client.setScreen(new CampfireCreateScreen(mod, parent)), true));

        this.addDrawableChild(new CampfireButton(centerX - buttonWidth / 2, panelTop + 132, buttonWidth, 20,
                Text.literal("Join a Campfire"),
                button -> this.client.setScreen(new CampfireJoinScreen(mod, parent)), true));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        CampfireUi.renderPanoramaBackdrop(context, this.width, this.height, delta);

        int centerX = this.width / 2;
        int panelTop = this.height / 2 - 86;
        int panelBottom = panelTop + PANEL_HEIGHT;
        CampfireUi.drawPanel(context, centerX - PANEL_HALF_WIDTH, panelTop, centerX + PANEL_HALF_WIDTH, panelBottom);
        CampfireUi.drawEmbers(context, centerX - PANEL_HALF_WIDTH, panelTop, centerX + PANEL_HALF_WIDTH, panelBottom);
        super.render(context, mouseX, mouseY, delta);

        // Hero mark: the animated flame at 3x, big enough to feel like a
        // splash, small enough to leave the two choices center stage.
        CampfireUi.drawCampfireIconScaled(context, centerX - (CampfireUi.ICON_WIDTH * 3) / 2, panelTop + 8, 3.0F);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, panelTop + 46, CampfireUi.TITLE_COLOR);
        CampfireUi.drawDivider(context, centerX, panelTop + 58, 280);
        CampfireUi.drawCenteredWrapped(context, this.textRenderer,
                "One world for the whole friend group - no server rental, no port forwarding.",
                centerX, panelTop + 64, TEXT_WRAP_WIDTH, CampfireUi.TEXT_COLOR);

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Start a fresh world and get an invite to share."),
                centerX, panelTop + 115, CampfireUi.MUTED_TEXT);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Got an invite? Paste it and pull up a seat."),
                centerX, panelTop + 155, CampfireUi.MUTED_TEXT);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
