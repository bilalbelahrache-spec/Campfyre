package com.bilal.campfire.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
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
// v2: the old fixed-page pager (MAX_ROWS, prev/next buttons, a panel that
// grew taller with every remembered campfire) is gone - the list is a real
// CampfireScrollPane now, so a player with any number of campfires can reach
// every one of them by scrolling instead of clicking through pages, and the
// panel itself stays a fixed, predictable size.
//
// Public for the same mixin-subpackage reason as the other public screens:
// TitleScreenMixin routes the docked button here when there's actually a
// choice to make.
public class CampfireListScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 170;
    // Desired height at generous window sizes, clamped for smaller ones -
    // see CampfireStatusScreen's DESIRED_PANEL_HEIGHT comment for why: a
    // fixed tall panel overflows the LOGICAL screen height at the common
    // default window size (854x480 -> logical 240 at GUI scale 2), clipping
    // the title off. HEADER + FOOTER is the fixed chrome around the scroll
    // viewport; the viewport itself is whatever's left.
    private static final int DESIRED_PANEL_HEIGHT = 268;
    private static final int MIN_PANEL_HEIGHT = 160;
    private static final int HEADER_HEIGHT = 30;
    private static final int FOOTER_HEIGHT = 60;
    private static final int ROW_HEIGHT = 34;
    private static final long POLL_INTERVAL_MS = 4000;

    private final CampfireClient mod;
    private final Screen parent;

    private List<CampfireClient.CampfireEntry> entries = List.of();
    // Written by HTTP worker threads (fetchCampfireStatus callbacks), read
    // every frame by the row renderer - hence concurrent, no bouncing needed.
    private final Map<String, CampfireClient.RemoteCampfireStatus> statuses = new ConcurrentHashMap<>();
    private long lastPollMs = 0;

    private CampfireScrollPane list;

    public CampfireListScreen(CampfireClient mod, Screen parent) {
        super(Text.literal("Your Campfires"));
        this.mod = mod;
        this.parent = parent;
    }

    private int panelHeight() {
        return Math.max(MIN_PANEL_HEIGHT, Math.min(DESIRED_PANEL_HEIGHT, this.height - 20));
    }

    private int viewportHeight() {
        return panelHeight() - HEADER_HEIGHT - FOOTER_HEIGHT;
    }

    private int panelTop() {
        return this.height / 2 - panelHeight() / 2;
    }

    @Override
    protected void init() {
        entries = mod.getCampfires();
        int centerX = this.width / 2;
        int panelTop = panelTop();
        int panelBottom = panelTop + panelHeight();
        int rowsTop = panelTop + HEADER_HEIGHT;

        list = new CampfireScrollPane(centerX - PANEL_HALF_WIDTH + 4, rowsTop, PANEL_HALF_WIDTH * 2 - 8, viewportHeight(),
                emptyRow(), this::buildRows);
        this.addDrawableChild(list);

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

    private CampfireScrollPane.Row emptyRow() {
        return new CampfireScrollPane.Row() {
            @Override
            public int height() {
                return viewportHeight();
            }

            @Override
            public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY, boolean hovered, float delta) {
                CampfireUi.drawCenteredWrapped(context, textRenderer,
                        "No campfires yet - light a new one, or join a friend's with their invite.",
                        x + width / 2, y + 16, width - 20, CampfireUi.MUTED_TEXT);
            }
        };
    }

    private List<CampfireScrollPane.Row> buildRows() {
        List<CampfireScrollPane.Row> rows = new ArrayList<>(entries.size());
        for (CampfireClient.CampfireEntry entry : entries) {
            rows.add(campfireRow(entry));
        }
        return rows;
    }

    // The whole row is one click target (opens/switches) - a right-aligned
    // "Open"/"Switch" badge shows the action, but a player doesn't have to
    // land a click inside it precisely.
    private CampfireScrollPane.Row campfireRow(CampfireClient.CampfireEntry entry) {
        boolean active = entry.groupId.equals(mod.getGroupId());
        return new CampfireScrollPane.Row() {
            @Override
            public int height() {
                return ROW_HEIGHT;
            }

            @Override
            public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY, boolean hovered, float delta) {
                CampfireUi.drawAvatar(context, textRenderer, entry.groupId, x + 4, y + (ROW_HEIGHT - 16) / 2, 16, false, false);

                String name = entry.worldName == null || entry.worldName.isBlank()
                        ? "Campfire " + entry.groupId : entry.worldName;
                if (active) name += " (current)";
                int textX = x + 4 + 16 + 6;
                // Leaves room for the badge (~64px incl. gap) on the right.
                int textMaxWidth = width - (textX - x) - 64;
                context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(name, textMaxWidth)),
                        textX, y + 6, active ? CampfireUi.ACCENT_BRIGHT : CampfireUi.TEXT_COLOR);
                context.drawTextWithShadow(textRenderer,
                        Text.literal(textRenderer.trimToWidth(describeRow(entry), textMaxWidth)),
                        textX, y + 18, CampfireUi.MUTED_TEXT);

                String badgeLabel = active ? "Open" : "Switch";
                int badgeHeight = 16;
                int badgeWidth = CampfireUi.chipWidth(textRenderer, badgeLabel);
                int badgeX = x + width - badgeWidth - 6;
                int badgeY = y + (ROW_HEIGHT - badgeHeight) / 2;
                CampfireUi.drawChip(context, textRenderer, badgeLabel, badgeX, badgeY, badgeHeight,
                        hovered ? CampfireUi.TITLE_COLOR : CampfireUi.TEXT_COLOR, hovered);
            }

            @Override
            public boolean onClick(double mouseX, double mouseY, int button) {
                if (!active) {
                    mod.switchToCampfire(entry.groupId);
                    // The switch can refuse (e.g. pressed from inside a
                    // world via the B key) - only move on if it actually took.
                    if (!entry.groupId.equals(mod.getGroupId())) return true;
                }
                CampfireListScreen.this.client.setScreen(new CampfireStatusScreen(mod, CampfireListScreen.this));
                return true;
            }
        };
    }

    @Override
    public void tick() {
        super.tick();
        // Mirrors live poll results into the rows every tick - cheap (a
        // handful of campfires at most) and keeps "checking..." -> real
        // status transitions visible without reopening the screen.
        if (list != null) list.refresh();
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
