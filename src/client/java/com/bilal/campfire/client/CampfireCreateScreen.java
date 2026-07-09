package com.bilal.campfire.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

// The "Light a New Campfire" flow, split out of the setup chooser so the
// coordinator-address question only ever appears to the one player it
// applies to: whoever creates the group decides which coordinator it lives
// on, and the composite invite carries that address to everyone else -
// joiners never see this screen. Minting happens on a background thread
// (it's a network round trip; the old inline call froze the render thread
// until the HTTP timeout when the coordinator was down).
public class CampfireCreateScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 160;

    private final CampfireClient mod;
    private final Screen parent;
    private TextFieldWidget coordinatorField;
    private CampfireButton lightButton;
    private Text status = Text.empty();
    private int statusColor = CampfireUi.ERROR_COLOR;
    private volatile boolean minting = false;

    public CampfireCreateScreen(CampfireClient mod, Screen parent) {
        super(Text.literal("Light a New Campfire"));
        this.mod = mod;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int fieldWidth = 240;
        int panelTop = this.height / 2 - 74;

        coordinatorField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, panelTop + 74, fieldWidth, 18,
                Text.literal("Coordinator address"));
        coordinatorField.setMaxLength(128);
        coordinatorField.setText(mod.getCoordinatorHost());
        this.addDrawableChild(coordinatorField);
        this.setInitialFocus(coordinatorField);

        lightButton = this.addDrawableChild(new CampfireButton(centerX - 122, panelTop + 104, 150, 20,
                Text.literal("Light It"), button -> onCreate(), true));

        this.addDrawableChild(new CampfireButton(centerX + 34, panelTop + 104, 88, 20,
                Text.literal("Back"), button -> this.close()));
    }

    private void onCreate() {
        if (minting) return;
        minting = true;
        lightButton.active = false;
        setStatus("Asking the coordinator for a fresh invite...", CampfireUi.TEXT_COLOR);
        mod.setCoordinatorHost(coordinatorField.getText());

        new Thread(() -> {
            CampfireClient.MintOutcome outcome = mod.mintNewGroupId();
            this.client.execute(() -> {
                minting = false;
                lightButton.active = true;
                if (outcome.success()) {
                    this.client.setScreen(new CampfireCodeScreen(mod, mod.getGroupId(), parent));
                } else {
                    setStatus(outcome.reason(), CampfireUi.ERROR_COLOR);
                }
            });
        }, "campfire-mint").start();
    }

    private void setStatus(String message, int color) {
        status = Text.literal(message);
        statusColor = color;
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
        CampfireUi.drawCenteredWrapped(context, this.textRenderer,
                "Ready to go - no setup needed, just light it.",
                centerX, panelTop + 32, 300, CampfireUi.TEXT_COLOR);
        CampfireUi.drawCenteredWrapped(context, this.textRenderer,
                "Running your own coordinator? Change it below.",
                centerX, panelTop + 44, 300, CampfireUi.MUTED_TEXT);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Coordinator address:"),
                centerX - 120, panelTop + 63, CampfireUi.TEXT_COLOR);

        if (!status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, status, centerX, panelTop + 133, statusColor);
        }
    }

    @Override
    public void close() {
        // Back to wherever the player actually came from: the campfire list
        // (which re-inits itself on setScreen) when opened from there, the
        // create/join chooser otherwise.
        this.client.setScreen(parent instanceof CampfireListScreen
                ? parent : new CampfireSetupScreen(mod, parent));
    }
}
