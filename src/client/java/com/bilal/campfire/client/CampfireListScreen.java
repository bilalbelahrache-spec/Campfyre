package com.bilal.campfire.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// "Your Campfires" - the chooser for players in more than one friend group
// (each campfire = one group = one shared world, possibly on its own
// coordinator - people don't only ever have ONE circle of friends). Modeled
// on Essential's social hub: every campfire is a row with LIVE presence
// (who's around its fire right now, who's hosting), polled over plain HTTP
// from each entry's own coordinator, because the single live websocket only
// ever belongs to the active campfire. Picking a row swaps the active
// campfire and lands on its status screen.
//
// Public for the same mixin-subpackage reason as the other public screens:
// TitleScreenMixin routes the docked button here when there's actually a
// choice to make.
public class CampfireListScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 170;
    private static final int MAX_ROWS = 5;
    private static final int ROW_HEIGHT = 30;
    private static final long POLL_INTERVAL_MS = 4000;

    private final CampfireClient mod;
    private final Screen parent;

    private List<CampfireClient.CampfireEntry> entries = List.of();
    // Written by HTTP worker threads (fetchCampfireStatus callbacks), read
    // every frame by render() - hence concurrent, no bouncing needed.
    private final Map<String, CampfireClient.RemoteCampfireStatus> statuses = new ConcurrentHashMap<>();
    private long lastPollMs = 0;
    private final long openedAtMs = System.currentTimeMillis();

    public CampfireListScreen(CampfireClient mod, Screen parent) {
        super(Text.literal("Your Campfires"));
        this.mod = mod;
        this.parent = parent;
    }

    private int panelHeight() {
        return 92 + Math.min(Math.max(entries.size(), 1), MAX_ROWS) * ROW_HEIGHT;
    }

    private int panelTop() {
        return this.height / 2 - panelHeight() / 2;
    }

    @Override
    protected void init() {
        entries = mod.getCampfires();
        int centerX = this.width / 2;
        int rowsTop = panelTop() + 30;

        int shown = Math.min(entries.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            CampfireClient.CampfireEntry entry = entries.get(i);
            boolean active = entry.groupId.equals(mod.getGroupId());
            int rowY = rowsTop + i * ROW_HEIGHT;
            this.addDrawableChild(new CampfireButton(centerX + PANEL_HALF_WIDTH - 70, rowY + 3, 62, 20,
                    Text.literal(active ? "Open" : "Switch"), button -> {
                        if (!active) {
                            mod.switchToCampfire(entry.groupId);
                            // The switch can refuse (e.g. pressed from inside
                            // a world via the B key) - only move on if it
                            // actually took.
                            if (!entry.groupId.equals(mod.getGroupId())) return;
                        }
                        this.client.setScreen(new CampfireStatusScreen(mod, this));
                    }));
        }

        int panelBottom = panelTop() + panelHeight();
        this.addDrawableChild(new CampfireButton(centerX - 150, panelBottom - 50, 146, 20,
                Text.literal("Light a New Campfire"), button ->
                        this.client.setScreen(new CampfireCreateScreen(mod, this)), true));
        this.addDrawableChild(new CampfireButton(centerX + 4, panelBottom - 50, 146, 20,
                Text.literal("Join a Campfire"), button ->
                        this.client.setScreen(new CampfireJoinScreen(mod, this))));
        this.addDrawableChild(new CampfireButton(centerX - 60, panelBottom - 26, 120, 20,
                Text.literal("Close"), button -> this.close()));

        pollNow();
    }

    @Override
    public void tick() {
        super.tick();
        if (System.currentTimeMillis() - lastPollMs > POLL_INTERVAL_MS) {
            pollNow();
        }
    }

    private void pollNow() {
        lastPollMs = System.currentTimeMillis();
        for (CampfireClient.CampfireEntry entry : entries) {
            mod.fetchCampfireStatus(entry, status -> statuses.put(entry.groupId, status));
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        CampfireUi.renderPanoramaBackdrop(context, this.width, this.height, delta);

        int centerX = this.width / 2;
        int panelTop = panelTop();
        int panelBottom = panelTop + panelHeight();
        int panelLeft = centerX - PANEL_HALF_WIDTH;
        int panelRight = centerX + PANEL_HALF_WIDTH;
        CampfireUi.drawPanel(context, panelLeft, panelTop, panelRight, panelBottom);
        CampfireUi.drawEmbers(context, panelLeft, panelTop, panelRight, panelBottom);
        super.render(context, mouseX, mouseY, delta);

        CampfireUi.drawTitle(context, this.textRenderer, this.title, centerX, panelTop + 10);
        CampfireUi.drawDivider(context, centerX, panelTop + 23, 300);

        if (entries.isEmpty()) {
            CampfireUi.drawCenteredWrapped(context, this.textRenderer,
                    "No campfires yet - light a new one, or join a friend's with their invite.",
                    centerX, panelTop + 42, 300, CampfireUi.MUTED_TEXT);
        }

        int rowsTop = panelTop + 30;
        int shown = Math.min(entries.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            CampfireClient.CampfireEntry entry = entries.get(i);
            int rowY = rowsTop + i * ROW_HEIGHT;
            boolean active = entry.groupId.equals(mod.getGroupId());

            CampfireUi.drawCampfireIcon(context, panelLeft + 8, rowY + 3);

            String name = entry.worldName == null || entry.worldName.isBlank()
                    ? "Campfire " + entry.groupId : entry.worldName;
            if (active) name += " (current)";
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(this.textRenderer.trimToWidth(name, 220)),
                    panelLeft + 10 + CampfireUi.ICON_WIDTH + 5, rowY + 2,
                    active ? CampfireUi.ACCENT_BRIGHT : CampfireUi.TEXT_COLOR);

            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(this.textRenderer.trimToWidth(describeRow(entry), 220)),
                    panelLeft + 10 + CampfireUi.ICON_WIDTH + 5, rowY + 13, CampfireUi.MUTED_TEXT);
        }
        if (entries.size() > MAX_ROWS) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("+ " + (entries.size() - MAX_ROWS) + " more"),
                    panelLeft + 10, rowsTop + shown * ROW_HEIGHT + 2, CampfireUi.MUTED_TEXT);
        }

        CampfireUi.drawOpenFade(context, this.width, this.height, openedAtMs);
    }

    // The live one-liner under each campfire's name. Names come straight
    // from that campfire's coordinator; unreachable/unknown states say so
    // plainly instead of pretending an empty group.
    private String describeRow(CampfireClient.CampfireEntry entry) {
        CampfireClient.RemoteCampfireStatus status = statuses.get(entry.groupId);
        String prefix = entry.groupId + " - ";
        if (status == null) return prefix + "checking...";
        if (!status.reachable()) return prefix + "coordinator unreachable";
        if (!status.exists()) return prefix + "not known to its coordinator";
        if (status.online() == 0) return prefix + "no one around right now";
        String who = String.join(", ", status.memberNames());
        String hosting = status.hostName() != null ? " - " + status.hostName() + " is hosting" : "";
        return prefix + who + hosting;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
