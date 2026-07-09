package com.bilal.campfire.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;

import java.util.List;

// Opened from the docked title-screen "Campfire" button when a group is
// already configured. This is the group's home screen now, not just a
// copy-the-invite utility: a live connection indicator, the roster of who's
// around the fire right this second (names stream in from the coordinator's
// state broadcasts), and ONE context-aware primary action that always says
// the most useful next thing - Open the World / Create the Shared World
// (first-ever host: builds the correctly-named world in one click), Join
// <name>'s World (same tiered direct-then-relay connect as an automatic
// migration), Reconnect when the coordinator link is down, or a disabled
// "Waiting for a host" when there's nothing to do but wait. Labels update
// every tick, so the screen tracks reality without ever being reopened.
public class CampfireStatusScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 170;
    private static final int PANEL_HEIGHT = 208;
    private static final int MAX_ROSTER_ROWS = 5;

    private final CampfireClient mod;
    private final Screen parent;

    private CampfireButton copyButton;
    private CampfireButton primaryButton;
    private CampfireButton leaveButton;
    private long copiedUntilMs = 0;
    private boolean joining = false;

    // What the primary button should currently do - recomputed each tick,
    // consulted on press, so the press always matches the visible label.
    // IN_WORLD exists because the Campfire keybind opens this screen from
    // INSIDE the shared world now - "Open the World"/"Join X's World" would
    // relaunch or reconnect into the world the player is already standing
    // in, so while describeHudStatus() says we're in it, the primary is a
    // calm disabled statement of that instead of a loaded gun.
    private enum PrimaryAction { RECONNECT, OPEN_WORLD, CREATE_WORLD, JOIN_HOST, WAITING, CONNECTING, IN_WORLD, PREPARING }

    private PrimaryAction primaryAction = PrimaryAction.WAITING;

    public CampfireStatusScreen(CampfireClient mod, Screen parent) {
        super(Text.literal(titleFor(mod)));
        this.mod = mod;
        this.parent = parent;
    }

    // The world's own name as the screen title when we know it ("Our
    // Campfire" beats a generic label), clamped so a maximal 32-char level
    // name can't push the title art off the panel.
    private static String titleFor(CampfireClient mod) {
        String name = mod.getActiveWorldName();
        if (name == null || name.isBlank()) return "Your Campfire";
        return name.length() > 28 ? name.substring(0, 27) + "..." : name;
    }

    private int panelTop() {
        return this.height / 2 - PANEL_HEIGHT / 2;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int panelTop = panelTop();
        int panelBottom = panelTop + PANEL_HEIGHT;

        // Copies the composite invite ("CODE@host:port") - the bare code
        // alone can't get a fresh install pointed at the right coordinator.
        copyButton = this.addDrawableChild(new CampfireButton(centerX + 52, panelTop + 49, 98, 20,
                Text.literal("Copy Invite"), button -> {
                    this.client.keyboard.setClipboard(mod.getInviteCode());
                    copiedUntilMs = System.currentTimeMillis() + 1500;
                }));

        primaryButton = this.addDrawableChild(new CampfireButton(centerX - 150, panelBottom - 50, 194, 20,
                Text.literal("..."), button -> onPrimary(), true));

        leaveButton = this.addDrawableChild(new CampfireButton(centerX + 52, panelBottom - 50, 98, 20,
                Text.literal("Leave"), button -> {
                    mod.leaveGroup();
                    this.client.setScreen(new TitleScreen());
                }));
        leaveButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                Text.literal("Forget this Campfire on this computer. The world and your friends' access stay untouched.")));

        // The "Campfires" nav button is always offered, not just once there
        // are already several - it's also the only way to ADD a second one
        // from a single-campfire state (Light a New Campfire / Join a
        // Campfire live on the list screen). Without this a player with
        // exactly one campfire had no path to ever reach the list at all.
        // Reuses the parent when we were opened FROM it (its init() re-runs
        // on setScreen, so its rows/statuses refresh anyway) instead of
        // growing a chain of stacked screens.
        this.addDrawableChild(new CampfireButton(centerX - 102, panelBottom - 26, 100, 20,
                Text.literal("Campfires"), button -> this.client.setScreen(
                        parent instanceof CampfireListScreen ? parent : new CampfireListScreen(mod, parent))));
        this.addDrawableChild(new CampfireButton(centerX + 2, panelBottom - 26, 100, 20,
                Text.literal("Close"), button -> this.close()));

        refreshDynamicWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        refreshDynamicWidgets();
    }

    private void refreshDynamicWidgets() {
        copyButton.setMessage(Text.literal(
                System.currentTimeMillis() < copiedUntilMs ? "Copied!" : "Copy Invite"));

        CampfireClient.CoordinatorStatus status = mod.getStatus();
        String inWorldStatus = mod.describeHudStatus();
        if (inWorldStatus != null) {
            primaryAction = PrimaryAction.IN_WORLD;
        } else if (joining) {
            primaryAction = PrimaryAction.CONNECTING;
        } else if (status != CampfireClient.CoordinatorStatus.CONNECTED) {
            primaryAction = PrimaryAction.RECONNECT;
        } else if (mod.isPreparingWorld()) {
            primaryAction = PrimaryAction.PREPARING;
        } else if (mod.isSelfHost() || mod.isNextUp()) {
            // Hosting is claim-based: nobody's hosting and we're at the front
            // of the queue, so opening the world is ours to offer. If the
            // group has a save anywhere (local or on the coordinator), Open
            // pulls the latest first; only a genuinely save-less fresh group
            // gets Create.
            primaryAction = mod.managedWorldExistsLocally() || mod.getKnownSaveVersion() > 0
                    ? PrimaryAction.OPEN_WORLD : PrimaryAction.CREATE_WORLD;
        } else if (mod.isSomeoneHosting()) {
            primaryAction = PrimaryAction.JOIN_HOST;
        } else {
            primaryAction = PrimaryAction.WAITING;
        }

        switch (primaryAction) {
            case RECONNECT -> setPrimary("Reconnect", status == CampfireClient.CoordinatorStatus.DISCONNECTED);
            case OPEN_WORLD -> setPrimary("Open the World", true);
            case CREATE_WORLD -> setPrimary("Create the Shared World", true);
            case JOIN_HOST -> {
                String host = mod.getHostName();
                setPrimary(host != null ? "Join " + host + "'s World" : "Join the World", true);
            }
            case WAITING -> setPrimary("Waiting for a host...", false);
            case CONNECTING -> setPrimary("Connecting...", false);
            case PREPARING -> setPrimary("Getting the world ready...", false);
            case IN_WORLD -> {
                String label = mod.describeHudStatus();
                setPrimary(label != null ? label : "In the world", false);
            }
        }
    }

    private void setPrimary(String label, boolean active) {
        primaryButton.setMessage(Text.literal(label));
        primaryButton.active = active;
    }

    private void onPrimary() {
        switch (primaryAction) {
            case RECONNECT -> mod.reconnectNow();
            case OPEN_WORLD -> mod.openManagedWorld(this);
            case CREATE_WORLD -> this.client.setScreen(new CampfireWorldNameScreen(mod, this));
            case JOIN_HOST -> {
                // connectToHostNow works out the best route (direct via
                // UPnP -> hole-punch -> relay) in the background and swaps
                // to the connect screen itself when it's ready.
                joining = true;
                mod.connectToHostNow();
            }
            default -> {
            }
        }
        refreshDynamicWidgets();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        CampfireUi.renderPanoramaBackdrop(context, this.width, this.height, delta);

        int centerX = this.width / 2;
        int panelTop = panelTop();
        int panelBottom = panelTop + PANEL_HEIGHT;
        int panelLeft = centerX - PANEL_HALF_WIDTH;
        int panelRight = centerX + PANEL_HALF_WIDTH;
        CampfireUi.drawPanel(context, panelLeft, panelTop, panelRight, panelBottom);
        CampfireUi.drawEmbers(context, panelLeft, panelTop, panelRight, panelBottom);
        super.render(context, mouseX, mouseY, delta);

        CampfireUi.drawTitle(context, this.textRenderer, this.title, centerX, panelTop + 10);
        CampfireUi.drawDivider(context, centerX, panelTop + 23, 300);

        renderConnectionLine(context, centerX, panelTop + 30);
        renderInviteBox(context, centerX, panelTop + 42);
        renderRoster(context, centerX, panelTop + 84);

        // Quiet provenance corner: which save generation the coordinator
        // holds - handy when someone asks "did my last session upload?"
        int saveVersion = mod.getKnownSaveVersion();
        if (saveVersion > 0) {
            String v = "world save v" + saveVersion;
            context.drawTextWithShadow(this.textRenderer, Text.literal(v),
                    panelRight - 6 - this.textRenderer.getWidth(v), panelBottom - 62, CampfireUi.MUTED_TEXT);
        }
    }

    private void renderConnectionLine(DrawContext context, int centerX, int y) {
        CampfireClient.CoordinatorStatus status = mod.getStatus();
        String label;
        int dotColor;
        boolean pulse = false;
        switch (status) {
            case CONNECTED -> {
                label = "Connected - " + mod.getCoordinatorHost();
                dotColor = CampfireUi.DOT_CONNECTED;
            }
            case CONNECTING -> {
                label = "Connecting to " + mod.getCoordinatorHost() + "...";
                dotColor = CampfireUi.DOT_CONNECTING;
                pulse = true;
            }
            case DISCONNECTED -> {
                label = "Offline - can't reach " + mod.getCoordinatorHost();
                dotColor = CampfireUi.DOT_OFFLINE;
            }
            default -> {
                label = "Not connected";
                dotColor = CampfireUi.DOT_IDLE;
            }
        }
        // The coordinator address is player-typed and unbounded - keep the
        // line inside the panel no matter what they configured.
        label = this.textRenderer.trimToWidth(label, PANEL_HALF_WIDTH * 2 - 24);
        int textWidth = this.textRenderer.getWidth(label);
        int startX = centerX - (textWidth + 8) / 2;
        CampfireUi.drawStatusDot(context, startX + 1, y + 3, dotColor, pulse);
        context.drawTextWithShadow(this.textRenderer, Text.literal(label), startX + 8, y,
                status == CampfireClient.CoordinatorStatus.DISCONNECTED ? CampfireUi.ERROR_COLOR : CampfireUi.TEXT_COLOR);
    }

    private void renderInviteBox(DrawContext context, int centerX, int top) {
        int boxLeft = centerX - 150;
        int boxRight = centerX + 44;
        CampfireUi.drawInnerBox(context, boxLeft, top, boxRight, top + 34);

        int boxCenter = (boxLeft + boxRight) / 2;
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(mod.getGroupId()).formatted(net.minecraft.util.Formatting.BOLD),
                boxCenter, top + 6, CampfireUi.TITLE_COLOR);
        String composite = this.textRenderer.trimToWidth(mod.getInviteCode(), boxRight - boxLeft - 8);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(composite),
                boxCenter, top + 19, CampfireUi.MUTED_TEXT);
    }

    private void renderRoster(DrawContext context, int centerX, int top) {
        int left = centerX - 150;
        int right = centerX + 150;
        List<CampfireClient.GroupMember> members = mod.getMembers();

        String header = "Around the fire" + (members.isEmpty() ? "" : " (" + members.size() + ")");
        context.drawTextWithShadow(this.textRenderer, Text.literal(header), left, top, CampfireUi.TITLE_COLOR);
        CampfireUi.drawDivider(context, centerX, top + 10, 300);

        int y = top + 15;
        if (mod.getStatus() != CampfireClient.CoordinatorStatus.CONNECTED) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("Roster unavailable while offline."), left + 4, y, CampfireUi.MUTED_TEXT);
            return;
        }
        if (members.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("Just you so far - send that invite!"), left + 4, y, CampfireUi.MUTED_TEXT);
            return;
        }

        int shown = Math.min(members.size(), MAX_ROSTER_ROWS);
        for (int i = 0; i < shown; i++) {
            CampfireClient.GroupMember m = members.get(i);
            int x = left + 4;
            if (m.host()) {
                CampfireUi.drawCampfireIcon(context, x, y - 1);
            }
            String name = m.name() + (m.you() ? " (you)" : "");
            context.drawTextWithShadow(this.textRenderer, Text.literal(name), x + CampfireUi.ICON_WIDTH + 5, y,
                    m.you() ? CampfireUi.ACCENT_BRIGHT : CampfireUi.TEXT_COLOR);

            String tag = m.host() ? "hosting" : (i == 0 && !mod.isSomeoneHosting() ? "next up" : null);
            if (tag != null) {
                context.drawTextWithShadow(this.textRenderer, Text.literal(tag),
                        right - 4 - this.textRenderer.getWidth(tag), y,
                        m.host() ? CampfireUi.ACCENT : CampfireUi.MUTED_TEXT);
            }
            y += 12;
        }
        if (members.size() > MAX_ROSTER_ROWS) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("+ " + (members.size() - MAX_ROSTER_ROWS) + " more"),
                    left + 4, y, CampfireUi.MUTED_TEXT);
            return;
        }

        // Friends the coordinator remembers who aren't online right now fill
        // whatever row budget the online list didn't use - greyed out, with
        // a "seen 2h ago" style tag. Makes the group read as a persistent
        // circle of friends instead of only whoever's connected this second.
        List<CampfireClient.AwayMember> away = mod.getAwayMembers();
        int awayRows = Math.min(away.size(), MAX_ROSTER_ROWS - shown);
        for (int i = 0; i < awayRows; i++) {
            CampfireClient.AwayMember a = away.get(i);
            context.drawTextWithShadow(this.textRenderer, Text.literal(a.name()),
                    left + 4 + CampfireUi.ICON_WIDTH + 5, y, CampfireUi.MUTED_TEXT);
            String tag = describeLastSeen(a.lastSeenMs());
            context.drawTextWithShadow(this.textRenderer, Text.literal(tag),
                    right - 4 - this.textRenderer.getWidth(tag), y, CampfireUi.MUTED_TEXT);
            y += 12;
        }
    }

    private static String describeLastSeen(long lastSeenMs) {
        if (lastSeenMs <= 0) return "away";
        long minutes = Math.max(0, (System.currentTimeMillis() - lastSeenMs) / 60000);
        if (minutes < 1) return "just left";
        if (minutes < 60) return "seen " + minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return "seen " + hours + "h ago";
        return "seen " + (hours / 24) + "d ago";
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
