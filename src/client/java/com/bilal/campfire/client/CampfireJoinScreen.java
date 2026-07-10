package com.bilal.campfire.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.Locale;

// Where a friend pastes the invite someone else's CampfireCodeScreen showed
// them. The field is parsed LIVE as they type/paste: a composite invite
// ("CODE@host:port") immediately shows which code and which coordinator it
// carries, a bare code shows which already-configured coordinator it'll be
// checked against - so a malformed or truncated paste is visible before the
// Join button is ever pressed, not after a confusing failure. "Join" still
// asks the coordinator whether the code actually corresponds to a real group
// (mod.checkGroupExists) before ever connecting - getGroup() on the
// coordinator would otherwise lazily spin up a brand-new empty group for a
// typo'd code instead of rejecting it. On success this lands on
// CampfireStatusScreen, so the first thing a newly joined player sees is the
// live roster of who's already around the fire.
public class CampfireJoinScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 160;

    private final CampfireClient mod;
    private final Screen parent;
    private TextFieldWidget codeField;
    private CampfireButton joinButton;
    private Text status = Text.empty();
    private int statusColor = CampfireUi.ERROR_COLOR;
    private volatile boolean checking = false;
    private final long openedAtMs = System.currentTimeMillis();

    public CampfireJoinScreen(CampfireClient mod, Screen parent) {
        super(Text.literal("Join a Campfire"));
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
        this.addDrawableChild(codeField);
        this.setInitialFocus(codeField);

        joinButton = this.addDrawableChild(new CampfireButton(centerX - 122, panelTop + 108, 150, 20,
                Text.literal("Join"), button -> onJoin(), true));

        this.addDrawableChild(new CampfireButton(centerX + 34, panelTop + 108, 88, 20,
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
            setStatus("Paste an invite first.", CampfireUi.ERROR_COLOR);
            return;
        }
        if (invite.code().isEmpty()) {
            setStatus("That invite is missing the code before the '@'.", CampfireUi.ERROR_COLOR);
            return;
        }
        if (invite.address() != null && invite.address().isEmpty()) {
            setStatus("That invite is missing its address after the '@'.", CampfireUi.ERROR_COLOR);
            return;
        }

        // A composite invite carries the coordinator address with it - point
        // this client at that coordinator before checking the code, so a
        // fresh install joins with a single paste and never has to hand-edit
        // campfire.json. A bare code (no '@') is checked against whatever
        // coordinator is already configured.
        if (invite.address() != null) {
            mod.setCoordinatorHost(invite.address());
        }

        checking = true;
        joinButton.active = false;
        setStatus("", CampfireUi.TEXT_COLOR);
        mod.checkGroupExists(invite.code(), result -> {
            checking = false;
            joinButton.active = true;
            switch (result) {
                case EXISTS -> {
                    mod.joinGroup(invite.code());
                    this.client.setScreen(new CampfireStatusScreen(mod, parent));
                }
                case NOT_FOUND -> setStatus("No Campfire with that code - double-check it.", CampfireUi.ERROR_COLOR);
                case INVALID_CODE -> setStatus("That invite code looks wrong - check what you pasted.", CampfireUi.ERROR_COLOR);
                case BUSY -> setStatus("The coordinator's busy right now - try again shortly.", CampfireUi.ERROR_COLOR);
                case UNREACHABLE -> setStatus("Couldn't reach the coordinator - try again.", CampfireUi.ERROR_COLOR);
            }
        });
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
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Paste the invite your friend sent you."),
                centerX, panelTop + 32, CampfireUi.TEXT_COLOR);

        renderLiveParse(context, centerX, panelTop + 72);

        if (checking) {
            // Animated ellipsis so a slow coordinator reads as "working",
            // not "frozen".
            int dots = (int) ((System.currentTimeMillis() / 350) % 4);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Checking with the coordinator" + ".".repeat(dots)),
                    centerX, panelTop + 136, CampfireUi.TEXT_COLOR);
        } else if (!status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, status, centerX, panelTop + 136, statusColor);
        }

        CampfireUi.drawOpenFade(context, this.width, this.height, openedAtMs);
    }

    // What the current field contents will actually DO, updated every frame:
    // the code half and (for composite invites) the coordinator it points
    // at, or the already-configured coordinator for a bare code.
    private void renderLiveParse(DrawContext context, int centerX, int y) {
        String input = codeField.getText();
        if (input.trim().isEmpty()) {
            CampfireUi.drawCenteredWrapped(context, this.textRenderer,
                    "Invites look like CODE@address - the address points at your group's coordinator.",
                    centerX, y, 300, CampfireUi.MUTED_TEXT);
            return;
        }

        ParsedInvite invite = ParsedInvite.from(input);
        if (invite.code().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Missing the code before the '@'"),
                    centerX, y, CampfireUi.ERROR_COLOR);
            return;
        }

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Code: " + invite.code()),
                centerX, y, CampfireUi.SUCCESS_COLOR);
        String where;
        int whereColor;
        if (invite.address() == null) {
            where = "Coordinator: " + mod.getCoordinatorHost() + " (from your settings)";
            whereColor = CampfireUi.MUTED_TEXT;
        } else if (invite.address().isEmpty()) {
            where = "Missing the address after the '@'";
            whereColor = CampfireUi.ERROR_COLOR;
        } else {
            where = "Coordinator: " + invite.address();
            whereColor = CampfireUi.SUCCESS_COLOR;
        }
        // Coordinator addresses are player-typed and unbounded - trim rather
        // than wrap here, this line sits right above the buttons.
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(this.textRenderer.trimToWidth(where, 300)), centerX, y + 12, whereColor);
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
