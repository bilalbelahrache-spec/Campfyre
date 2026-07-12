package com.bilal.campfyre.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

// The one question a brand-new group's first host gets asked before the
// shared world is created: what should it be CALLED? The world's folder name
// is pinned to the group code (isManagedWorld() keys on it - that part is
// non-negotiable), but the display name players see in the world list is
// free-form and lives in level.dat, so it travels inside the save zip to
// every future host. Without this screen the world list filled up with
// entries titled "DK77JD6332" - technically correct, cozy as a tax form.
class CampfyreWorldNameScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 160;
    private static final int MAX_NAME_LENGTH = 32;

    private final CampfyreClient mod;
    private final Screen parent;
    private TextFieldWidget nameField;

    CampfyreWorldNameScreen(CampfyreClient mod, Screen parent) {
        super(Text.literal("Name the Shared World"));
        this.mod = mod;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int fieldWidth = 240;
        int panelTop = this.height / 2 - 74;

        nameField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, panelTop + 62, fieldWidth, 18,
                Text.literal("World name"));
        nameField.setMaxLength(MAX_NAME_LENGTH);
        nameField.setText("Our Campfyre");
        CampfyreUi.styleTextField(nameField);
        this.addDrawableChild(nameField);
        this.setInitialFocus(nameField);

        this.addDrawableChild(new CampfyreButton(centerX - 122, panelTop + 104, 150, 20,
                Text.literal("Create the World"), button -> onCreate(), true));

        this.addDrawableChild(new CampfyreButton(centerX + 34, panelTop + 104, 88, 20,
                Text.literal("Back"), button -> this.close()));
    }

    private void onCreate() {
        // createManagedWorld swaps to vanilla's world-loading screens itself;
        // a blank field just falls back to the group code as the name.
        mod.createManagedWorld(nameField.getText());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        CampfyreUi.renderPanoramaBackdrop(context, this.width, this.height, delta);

        int centerX = this.width / 2;
        int panelTop = this.height / 2 - 74;
        int panelBottom = panelTop + 148;
        CampfyreUi.drawPanel(context, centerX - PANEL_HALF_WIDTH, panelTop, centerX + PANEL_HALF_WIDTH, panelBottom);
        CampfyreUi.drawEmbers(context, centerX - PANEL_HALF_WIDTH, panelTop, centerX + PANEL_HALF_WIDTH, panelBottom);
        CampfyreUi.drawFieldFrame(context, this.textRenderer, nameField, "World name:");
        super.render(context, mouseX, mouseY, delta);

        CampfyreUi.drawTitle(context, this.textRenderer, this.title, centerX, panelTop + 10);
        CampfyreUi.drawDivider(context, centerX, panelTop + 23, 280);
        CampfyreUi.drawCenteredWrapped(context, this.textRenderer,
                "Call it anything - this is the name everyone sees.",
                centerX, panelTop + 32, 300, CampfyreUi.TEXT_COLOR);
        // One measured line (~280px < 300) - anything longer would wrap into
        // the buttons at panelTop + 104.
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Created on this computer, shared with the group."),
                centerX, panelTop + 88, CampfyreUi.MUTED_TEXT);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
