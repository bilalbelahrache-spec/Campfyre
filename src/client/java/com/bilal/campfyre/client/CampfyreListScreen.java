package com.bilal.campfyre.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// "Your Campfyres" - the chooser for players in more than one friend group
// (each campfyre = one group = one shared world, possibly on its own
// coordinator - people don't only ever have ONE circle of friends). Modeled
// on Essential's social hub: every campfyre is a row with LIVE presence
// (who's around its fire right now, who's hosting), polled over plain HTTP
// from each entry's own coordinator, because the single live websocket only
// ever belongs to the active campfyre. Picking a row swaps the active
// campfyre and lands on its status screen.
//
// v2: the old fixed-page pager (MAX_ROWS, prev/next buttons, a panel that
// grew taller with every remembered campfyre) is gone - the list is a real
// CampfyreScrollPane now, so a player with any number of campfyres can reach
// every one of them by scrolling instead of clicking through pages, and the
// panel itself stays a fixed, predictable size.
//
// Public for the same mixin-subpackage reason as the other public screens:
// TitleScreenMixin routes the docked button here when there's actually a
// choice to make.
public class CampfyreListScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 170;
    // Desired height at generous window sizes, clamped for smaller ones -
    // see CampfyreStatusScreen's DESIRED_PANEL_HEIGHT comment for why: a
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

    private final CampfyreClient mod;
    private final Screen parent;

    private List<CampfyreClient.CampfyreEntry> entries = List.of();
    // Written by HTTP worker threads (fetchCampfyreStatus callbacks), read
    // every frame by the row renderer - hence concurrent, no bouncing needed.
    private final Map<String, CampfyreClient.RemoteCampfyreStatus> statuses = new ConcurrentHashMap<>();
    private long lastPollMs = 0;

    private CampfyreScrollPane list;

    public CampfyreListScreen(CampfyreClient mod, Screen parent) {
        super(Text.literal("Your Campfyres"));
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
        entries = mod.getCampfyres();
        int centerX = this.width / 2;
        int panelTop = panelTop();
        int panelBottom = panelTop + panelHeight();
        int rowsTop = panelTop + HEADER_HEIGHT;

        list = new CampfyreScrollPane(centerX - PANEL_HALF_WIDTH + 4, rowsTop, PANEL_HALF_WIDTH * 2 - 8, viewportHeight(),
                emptyRow(), this::buildRows);
        this.addDrawableChild(list);

        this.addDrawableChild(new CampfyreButton(centerX - 150, panelBottom - 50, 146, 20,
                Text.literal("Light a New Campfyre"), button ->
                        this.client.setScreen(new CampfyreCreateScreen(mod, this)), true));
        this.addDrawableChild(new CampfyreButton(centerX + 4, panelBottom - 50, 146, 20,
                Text.literal("Join a Campfyre"), button ->
                        this.client.setScreen(new CampfyreJoinScreen(mod, this))));
        this.addDrawableChild(new CampfyreButton(centerX - 60, panelBottom - 26, 120, 20,
                Text.literal("Close"), button -> this.close()));

        pollNow();
    }

    private CampfyreScrollPane.Row emptyRow() {
        return new CampfyreScrollPane.Row() {
            @Override
            public int height() {
                return viewportHeight();
            }

            @Override
            public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY, boolean hovered, float delta) {
                CampfyreUi.drawCenteredWrapped(context, textRenderer,
                        "No campfyres yet - light a new one, or join a friend's with their invite.",
                        x + width / 2, y + 16, width - 20, CampfyreUi.MUTED_TEXT);
            }
        };
    }

    private List<CampfyreScrollPane.Row> buildRows() {
        List<CampfyreScrollPane.Row> rows = new ArrayList<>(entries.size());
        for (CampfyreClient.CampfyreEntry entry : entries) {
            rows.add(campfyreRow(entry));
        }
        return rows;
    }

    // The whole row is one click target (opens/switches) - a right-aligned
    // "Open"/"Switch" badge shows the action, but a player doesn't have to
    // land a click inside it precisely.
    private CampfyreScrollPane.Row campfyreRow(CampfyreClient.CampfyreEntry entry) {
        boolean active = entry.groupId.equals(mod.getGroupId());
        return new CampfyreScrollPane.Row() {
            @Override
            public int height() {
                return ROW_HEIGHT;
            }

            @Override
            public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY, boolean hovered, float delta) {
                CampfyreUi.drawAvatar(context, textRenderer, entry.groupId, x + 4, y + (ROW_HEIGHT - 16) / 2, 16, false, false);

                String name = entry.worldName == null || entry.worldName.isBlank()
                        ? "Campfyre " + entry.groupId : entry.worldName;
                if (active) name += " (current)";
                int textX = x + 4 + 16 + 6;
                // Leaves room for the badge (~64px incl. gap) on the right.
                int textMaxWidth = width - (textX - x) - 64;
                context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(name, textMaxWidth)),
                        textX, y + 6, active ? CampfyreUi.ACCENT_BRIGHT : CampfyreUi.TEXT_COLOR);
                context.drawTextWithShadow(textRenderer,
                        Text.literal(textRenderer.trimToWidth(describeRow(entry), textMaxWidth)),
                        textX, y + 18, CampfyreUi.MUTED_TEXT);

                String badgeLabel = active ? "Open" : "Switch";
                int badgeHeight = 16;
                int badgeWidth = CampfyreUi.chipWidth(textRenderer, badgeLabel);
                int badgeX = x + width - badgeWidth - 6;
                int badgeY = y + (ROW_HEIGHT - badgeHeight) / 2;
                CampfyreUi.drawChip(context, textRenderer, badgeLabel, badgeX, badgeY, badgeHeight,
                        hovered ? CampfyreUi.TITLE_COLOR : CampfyreUi.TEXT_COLOR, hovered);
            }

            @Override
            public boolean onClick(double mouseX, double mouseY, int button) {
                if (!active) {
                    mod.switchToCampfyre(entry.groupId);
                    // The switch can refuse (e.g. pressed from inside a
                    // world via the B key) - only move on if it actually took.
                    if (!entry.groupId.equals(mod.getGroupId())) return true;
                }
                CampfyreListScreen.this.client.setScreen(new CampfyreStatusScreen(mod, CampfyreListScreen.this));
                return true;
            }
        };
    }

    @Override
    public void tick() {
        super.tick();
        // Mirrors live poll results into the rows every tick - cheap (a
        // handful of campfyres at most) and keeps "checking..." -> real
        // status transitions visible without reopening the screen.
        if (list != null) list.refresh();
        if (System.currentTimeMillis() - lastPollMs > POLL_INTERVAL_MS) {
            pollNow();
        }
    }

    private void pollNow() {
        lastPollMs = System.currentTimeMillis();
        for (CampfyreClient.CampfyreEntry entry : entries) {
            mod.fetchCampfyreStatus(entry, status -> statuses.put(entry.groupId, status));
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        CampfyreUi.renderPanoramaBackdrop(context, this.width, this.height, delta);

        int centerX = this.width / 2;
        int panelTop = panelTop();
        int panelBottom = panelTop + panelHeight();
        int panelLeft = centerX - PANEL_HALF_WIDTH;
        int panelRight = centerX + PANEL_HALF_WIDTH;
        CampfyreUi.drawPanel(context, panelLeft, panelTop, panelRight, panelBottom);
        CampfyreUi.drawEmbers(context, panelLeft, panelTop, panelRight, panelBottom);
        super.render(context, mouseX, mouseY, delta);

        CampfyreUi.drawTitle(context, this.textRenderer, this.title, centerX, panelTop + 10);
        CampfyreUi.drawDivider(context, centerX, panelTop + 23, 300);
    }

    // The live one-liner under each campfyre's name. Names come straight
    // from that campfyre's coordinator; unreachable/unknown states say so
    // plainly instead of pretending an empty group.
    private String describeRow(CampfyreClient.CampfyreEntry entry) {
        CampfyreClient.RemoteCampfyreStatus status = statuses.get(entry.groupId);
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
