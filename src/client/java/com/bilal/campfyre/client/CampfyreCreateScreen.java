package com.bilal.campfyre.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

// The "Light a New Campfyre" flow, split out of the setup chooser so the
// coordinator-address question only ever appears to the one player it
// applies to: whoever creates the group decides which coordinator it lives
// on, and the composite invite carries that address to everyone else -
// joiners never see this screen. Minting happens on a background thread
// (it's a network round trip; the old inline call froze the render thread
// until the HTTP timeout when the coordinator was down).
public class CampfyreCreateScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 160;

    private final CampfyreClient mod;
    private final Screen parent;
    private TextFieldWidget coordinatorField;
    private CampfyreButton lightButton;
    private Text status = Text.empty();
    private int statusColor = CampfyreUi.ERROR_COLOR;
    private volatile boolean minting = false;
    // Same reasoning as CampfyreJoinScreen: mintNewGroupId()'s background
    // thread can finish well after the player has navigated away (Back
    // stays enabled while minting) - without this, a delayed response
    // force-opened CampfyreCodeScreen over whatever the player was doing by
    // then, including mid-game.
    private volatile boolean closed = false;

    public CampfyreCreateScreen(CampfyreClient mod, Screen parent) {
        super(Text.literal("Light a New Campfyre"));
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
        CampfyreUi.styleTextField(coordinatorField);
        this.addDrawableChild(coordinatorField);
        this.setInitialFocus(coordinatorField);

        lightButton = this.addDrawableChild(new CampfyreButton(centerX - 122, panelTop + 104, 150, 20,
                Text.literal("Light It"), button -> onCreate(), true));

        this.addDrawableChild(new CampfyreButton(centerX + 34, panelTop + 104, 88, 20,
                Text.literal("Back"), button -> this.close()));
    }

    private void onCreate() {
        if (minting) return;
        minting = true;
        lightButton.active = false;
        setStatus("Asking the coordinator for a fresh invite...", CampfyreUi.TEXT_COLOR);
        mod.setCoordinatorHost(coordinatorField.getText());

        new Thread(() -> {
            CampfyreClient.MintOutcome outcome = mod.mintNewGroupId();
            this.client.execute(() -> {
                if (closed) return;
                minting = false;
                lightButton.active = true;
                if (outcome.success()) {
                    this.client.setScreen(new CampfyreCodeScreen(mod, mod.getGroupId(), parent));
                } else {
                    setStatus(outcome.reason(), CampfyreUi.ERROR_COLOR);
                }
            });
        }, "campfyre-mint").start();
    }

    private void setStatus(String message, int color) {
        status = Text.literal(message);
        statusColor = color;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        CampfyreUi.renderPanoramaBackdrop(context, this.width, this.height, delta);

        int centerX = this.width / 2;
        int panelTop = this.height / 2 - 74;
        int panelBottom = panelTop + 148;
        CampfyreUi.drawPanel(context, centerX - PANEL_HALF_WIDTH, panelTop, centerX + PANEL_HALF_WIDTH, panelBottom);
        CampfyreUi.drawEmbers(context, centerX - PANEL_HALF_WIDTH, panelTop, centerX + PANEL_HALF_WIDTH, panelBottom);
        CampfyreUi.drawFieldFrame(context, this.textRenderer, coordinatorField, "Coordinator address:");
        super.render(context, mouseX, mouseY, delta);

        CampfyreUi.drawTitle(context, this.textRenderer, this.title, centerX, panelTop + 10);
        CampfyreUi.drawDivider(context, centerX, panelTop + 23, 280);
        CampfyreUi.drawCenteredWrapped(context, this.textRenderer,
                "Ready to go - no setup needed, just light it.",
                centerX, panelTop + 32, 300, CampfyreUi.TEXT_COLOR);
        CampfyreUi.drawCenteredWrapped(context, this.textRenderer,
                "Running your own coordinator? Change it below.",
                centerX, panelTop + 44, 300, CampfyreUi.MUTED_TEXT);

        if (!status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, status, centerX, panelTop + 133, statusColor);
        }
    }

    @Override
    public void removed() {
        closed = true;
        super.removed();
    }

    @Override
    public void close() {
        // Back to wherever the player actually came from: the campfyre list
        // (which re-inits itself on setScreen) when opened from there, the
        // create/join chooser otherwise.
        this.client.setScreen(parent instanceof CampfyreListScreen
                ? parent : new CampfyreSetupScreen(mod, parent));
    }
}
