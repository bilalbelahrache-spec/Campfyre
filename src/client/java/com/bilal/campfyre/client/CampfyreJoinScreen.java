package com.bilal.campfyre.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.Locale;

// Where a friend pastes the invite someone else's CampfyreCodeScreen showed
// them. The field is parsed LIVE as they type/paste: a composite invite
// ("CODE@host:port") immediately shows which code and which coordinator it
// carries, a bare code shows which already-configured coordinator it'll be
// checked against - so a malformed or truncated paste is visible before the
// Join button is ever pressed, not after a confusing failure. "Join" still
// asks the coordinator whether the code actually corresponds to a real group
// (mod.checkGroupExists) before ever connecting - getGroup() on the
// coordinator would otherwise lazily spin up a brand-new empty group for a
// typo'd code instead of rejecting it. On success this lands on
// CampfyreStatusScreen, so the first thing a newly joined player sees is the
// live roster of who's already around the fire.
public class CampfyreJoinScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 160;

    private final CampfyreClient mod;
    private final Screen parent;
    private TextFieldWidget codeField;
    private CampfyreButton joinButton;
    private Text status = Text.empty();
    private int statusColor = CampfyreUi.ERROR_COLOR;
    private volatile boolean checking = false;
    // mod.checkGroupExists's callback can land well after the request was
    // fired (up to its own HTTP timeout) - without this, pressing Join then
    // navigating away before the response arrives (Back is never disabled
    // while checking) let a long-delayed response silently swap the active
    // campfyre config and force this screen open again over whatever the
    // player is doing by then, including mid-game.
    private volatile boolean closed = false;

    public CampfyreJoinScreen(CampfyreClient mod, Screen parent) {
        super(Text.literal("Join a Campfyre"));
        this.mod = mod;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int fieldWidth = 240;
        int panelTop = this.height / 2 - 74;

        codeField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, panelTop + 46, fieldWidth, 18,
                Text.literal("Invite code"));
        // Room for the composite "CODE@host:port" form, not just a bare code.
        codeField.setMaxLength(160);
        codeField.setPlaceholder(Text.literal("CODE@host:port"));
        CampfyreUi.styleTextField(codeField);
        this.addDrawableChild(codeField);
        this.setInitialFocus(codeField);

        joinButton = this.addDrawableChild(new CampfyreButton(centerX - 122, panelTop + 108, 150, 20,
                Text.literal("Join"), button -> onJoin(), true));

        this.addDrawableChild(new CampfyreButton(centerX + 34, panelTop + 108, 88, 20,
                Text.literal("Back"), button -> this.close()));
    }

    // The live-preview parse and the on-join parse share this so they can
    // never disagree about what a given paste means.
    private record ParsedInvite(String code, String address) {
        static ParsedInvite from(String input) {
            String trimmed = input.trim();
            int at = trimmed.indexOf('@');
            if (at < 0) return new ParsedInvite(trimmed.toUpperCase(Locale.ROOT), null);
            return new ParsedInvite(
                    trimmed.substring(0, at).trim().toUpperCase(Locale.ROOT),
                    trimmed.substring(at + 1).trim());
        }
    }

    private void onJoin() {
        if (checking) return;
        ParsedInvite invite = ParsedInvite.from(codeField.getText());
        if (invite.code().isEmpty() && invite.address() == null) {
            setStatus("Paste an invite first.", CampfyreUi.ERROR_COLOR);
            return;
        }
        if (invite.code().isEmpty()) {
            setStatus("That invite is missing the code before the '@'.", CampfyreUi.ERROR_COLOR);
            return;
        }
        if (invite.address() != null && invite.address().isEmpty()) {
            setStatus("That invite is missing its address after the '@'.", CampfyreUi.ERROR_COLOR);
            return;
        }

        // A composite invite carries the coordinator address with it - point
        // this client at that coordinator before checking the code, so a
        // fresh install joins with a single paste and never has to hand-edit
        // campfyre.json. A bare code (no '@') is checked against whatever
        // coordinator is already configured.
        if (invite.address() != null) {
            mod.setCoordinatorHost(invite.address());
        }

        checking = true;
        joinButton.active = false;
        setStatus("", CampfyreUi.TEXT_COLOR);
        mod.checkGroupExists(invite.code(), result -> {
            if (closed) return;
            checking = false;
            joinButton.active = true;
            switch (result) {
                case EXISTS -> {
                    mod.joinGroup(invite.code());
                    this.client.setScreen(new CampfyreStatusScreen(mod, parent));
                }
                case NOT_FOUND -> setStatus("No Campfyre with that code - double-check it.", CampfyreUi.ERROR_COLOR);
                case INVALID_CODE -> setStatus("That invite code looks wrong - check what you pasted.", CampfyreUi.ERROR_COLOR);
                case BUSY -> setStatus("The coordinator's busy right now - try again shortly.", CampfyreUi.ERROR_COLOR);
                case UNREACHABLE -> setStatus("Couldn't reach the coordinator - try again.", CampfyreUi.ERROR_COLOR);
            }
        });
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
        CampfyreUi.drawFieldFrame(context, this.textRenderer, codeField, null);
        super.render(context, mouseX, mouseY, delta);

        CampfyreUi.drawTitle(context, this.textRenderer, this.title, centerX, panelTop + 10);
        CampfyreUi.drawDivider(context, centerX, panelTop + 23, 280);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Paste the invite your friend sent you."),
                centerX, panelTop + 32, CampfyreUi.TEXT_COLOR);

        renderLiveParse(context, centerX, panelTop + 72);

        if (checking) {
            // Animated ellipsis so a slow coordinator reads as "working",
            // not "frozen".
            int dots = (int) ((System.currentTimeMillis() / 350) % 4);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Checking with the coordinator" + ".".repeat(dots)),
                    centerX, panelTop + 136, CampfyreUi.TEXT_COLOR);
        } else if (!status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, status, centerX, panelTop + 136, statusColor);
        }
    }

    // What the current field contents will actually DO, updated every frame:
    // the code half and (for composite invites) the coordinator it points
    // at, or the already-configured coordinator for a bare code.
    private void renderLiveParse(DrawContext context, int centerX, int y) {
        String input = codeField.getText();
        if (input.trim().isEmpty()) {
            CampfyreUi.drawCenteredWrapped(context, this.textRenderer,
                    "Invites look like CODE@address - the address points at your group's coordinator.",
                    centerX, y, 300, CampfyreUi.MUTED_TEXT);
            return;
        }

        ParsedInvite invite = ParsedInvite.from(input);
        if (invite.code().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Missing the code before the '@'"),
                    centerX, y, CampfyreUi.ERROR_COLOR);
            return;
        }

        // codeField allows up to 160 chars (room for a composite invite) and
        // this echoes back whatever was typed/pasted verbatim - unlike the
        // "Coordinator: ..." line right below, this one had no trim, so any
        // long paste (garbage, a mis-copied paragraph) overflowed the panel.
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(this.textRenderer.trimToWidth("Code: " + invite.code(), 300)),
                centerX, y, CampfyreUi.SUCCESS_COLOR);
        String where;
        int whereColor;
        if (invite.address() == null) {
            where = "Coordinator: " + mod.getCoordinatorHost() + " (from your settings)";
            whereColor = CampfyreUi.MUTED_TEXT;
        } else if (invite.address().isEmpty()) {
            where = "Missing the address after the '@'";
            whereColor = CampfyreUi.ERROR_COLOR;
        } else {
            where = "Coordinator: " + invite.address();
            whereColor = CampfyreUi.SUCCESS_COLOR;
        }
        // Coordinator addresses are player-typed and unbounded - trim rather
        // than wrap here, this line sits right above the buttons.
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(this.textRenderer.trimToWidth(where, 300)), centerX, y + 12, whereColor);
    }

    @Override
    public void removed() {
        // Fires whenever this screen stops being the active one, however
        // that happens (explicit close(), or setScreen swapping to a
        // completely different screen) - the one universal hook for "a
        // still-in-flight checkGroupExists callback landing after this
        // point must not act."
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
