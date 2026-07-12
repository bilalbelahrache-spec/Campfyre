package com.bilal.campfyre.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.text.Text;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

import java.util.List;

// The owner-only control panel: gamemode, time, weather, difficulty, and a
// curated set of gamerules (see CampfyreClient.CURATED_GAME_RULES), applying
// live no matter who's currently hosting. Only ever reachable via the
// "Settings" nav button CampfyreStatusScreen shows exclusively to
// mod.isOwner() - this screen itself re-checks nothing beyond that, since
// the actual enforcement (is the sender REALLY the owner) happens
// server-side in CampfyreClient.handleOwnerSettingsChange, keyed off the
// coordinator-stamped fromPlayerId, not anything this screen asserts.
//
// Deliberately has no "enable cheats" control anywhere: every change here
// applies through direct server-API calls from trusted mod code (see
// CampfyreClient.applyOwnerSettingsChange), never by flipping the
// network-facing LAN cheats flag, which openToLan hardcodes off for
// everyone, permanently. That's a stronger guarantee than a literal cheats
// toggle would be, not a missing feature.
public class CampfyreWorldSettingsScreen extends Screen {

    private static final int PANEL_HALF_WIDTH = 180;
    private static final int ROW_WIDTH = 340;
    private static final int ITEM_HEIGHT = 24;
    // Desired height at generous window sizes, clamped for smaller ones -
    // pre-existing bug found via the GUI revamp's screenshot self-test: a
    // fixed 280 panel centered on the LOGICAL screen height overflows at the
    // common default window size (854x480 -> logical 240 at GUI scale 2),
    // clipping the title clean off the top.
    private static final int DESIRED_PANEL_HEIGHT = 280;
    private static final int MIN_PANEL_HEIGHT = 170;

    private final CampfyreClient mod;
    private final Screen parent;
    private SettingsList list;
    private boolean builtWithSomeoneHosting;

    public CampfyreWorldSettingsScreen(CampfyreClient mod, Screen parent) {
        super(Text.literal("World Settings"));
        this.mod = mod;
        this.parent = parent;
    }

    private int panelHeight() {
        return Math.max(MIN_PANEL_HEIGHT, Math.min(DESIRED_PANEL_HEIGHT, this.height - 20));
    }

    private int panelTop() {
        return this.height / 2 - panelHeight() / 2;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int panelTop = panelTop();
        int panelBottom = panelTop + panelHeight();

        builtWithSomeoneHosting = mod.isSomeoneHosting();
        if (builtWithSomeoneHosting) {
            list = new SettingsList(this.client, ROW_WIDTH, this.height, panelTop + 34, panelBottom - 30, ITEM_HEIGHT, mod);
            list.setLeftPos(centerX - ROW_WIDTH / 2);
            this.addDrawableChild(list);
        } else {
            list = null;
        }

        this.addDrawableChild(new CampfyreButton(centerX - 50, panelBottom - 26, 100, 20,
                Text.literal("Back"), button -> this.close()));
    }

    // The host can quit (or a new group can start with nobody hosting yet)
    // while the owner is sitting on this exact screen - without this, the
    // row list built at init() would keep rendering (and accepting clicks
    // that silently do nothing once nobody's there to receive them) right
    // underneath the "No one's hosting" message render() already shows,
    // instead of the two ever agreeing with each other.
    @Override
    public void tick() {
        super.tick();
        if (mod.isSomeoneHosting() != builtWithSomeoneHosting) {
            this.clearChildren();
            this.init();
        }
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        CampfyreUi.renderPanoramaBackdrop(context, this.width, this.height, delta);

        int centerX = this.width / 2;
        int panelTop = panelTop();
        int panelBottom = panelTop + panelHeight();
        CampfyreUi.drawPanel(context, centerX - PANEL_HALF_WIDTH, panelTop, centerX + PANEL_HALF_WIDTH, panelBottom);
        CampfyreUi.drawEmbers(context, centerX - PANEL_HALF_WIDTH, panelTop, centerX + PANEL_HALF_WIDTH, panelBottom);
        super.render(context, mouseX, mouseY, delta);

        CampfyreUi.drawTitle(context, this.textRenderer, this.title, centerX, panelTop + 10);
        CampfyreUi.drawDivider(context, centerX, panelTop + 23, 300);

        if (!mod.isSomeoneHosting()) {
            CampfyreUi.drawCenteredWrapped(context, this.textRenderer,
                    "No one's hosting right now - open the world to change settings.",
                    centerX, panelTop + 90, 300, CampfyreUi.MUTED_TEXT);
        }
    }

    // ---------- The scrollable row list ----------

    private static final class SettingsList extends ElementListWidget<SettingsList.Row> {
        SettingsList(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight, CampfyreClient mod) {
            super(client, width, height, top, bottom, itemHeight);
            setRenderBackground(false);
            buildRows(mod);
        }

        @Override
        public int getRowWidth() {
            return ROW_WIDTH;
        }

        @Override
        protected int getScrollbarPositionX() {
            return this.getRowRight() + 6;
        }

        private void buildRows(CampfyreClient mod) {
            addEntry(Row.actionRow("My Mode", modeButtons(mod, false)));
            addEntry(Row.actionRow("Everyone's Mode", modeButtons(mod, true)));
            addEntry(Row.actionRow("Time", List.of(
                    smallButton("Day", b -> mod.sendTimeChange(false)),
                    smallButton("Night", b -> mod.sendTimeChange(true)))));
            addEntry(Row.actionRow("Weather", List.of(
                    smallButton("Clear", b -> mod.sendWeatherChange("clear")),
                    smallButton("Rain", b -> mod.sendWeatherChange("rain")),
                    smallButton("Thunder", b -> mod.sendWeatherChange("thunder")))));
            addEntry(Row.actionRow("Difficulty", List.of(
                    smallButton("Peace", b -> mod.sendDifficultyChange(Difficulty.PEACEFUL)),
                    smallButton("Easy", b -> mod.sendDifficultyChange(Difficulty.EASY)),
                    smallButton("Normal", b -> mod.sendDifficultyChange(Difficulty.NORMAL)),
                    smallButton("Hard", b -> mod.sendDifficultyChange(Difficulty.HARD)))));
            addEntry(Row.header("Gamerules"));
            for (CampfyreClient.CuratedGameRule rule : CampfyreClient.CURATED_GAME_RULES) {
                addEntry(Row.toggleRow(rule.label(),
                        () -> currentGameRuleValue(mod, rule.key()),
                        enabled -> mod.sendGameRuleChange(rule.key(), enabled)));
            }
        }

        private static boolean currentGameRuleValue(CampfyreClient mod, GameRules.Key<GameRules.BooleanRule> key) {
            CampfyreClient.WorldSettingsSnapshot snapshot = mod.getWorldSettings();
            if (snapshot == null) return false;
            Boolean value = snapshot.gamerules().get(key.getName());
            return value != null && value;
        }

        private static List<CampfyreButton> modeButtons(CampfyreClient mod, boolean everyone) {
            return List.of(
                    smallButton("Surv", b -> mod.sendGameModeChange(everyone, GameMode.SURVIVAL)),
                    smallButton("Creat", b -> mod.sendGameModeChange(everyone, GameMode.CREATIVE)),
                    smallButton("Adv", b -> mod.sendGameModeChange(everyone, GameMode.ADVENTURE)),
                    smallButton("Spec", b -> mod.sendGameModeChange(everyone, GameMode.SPECTATOR)));
        }

        private static CampfyreButton smallButton(String label, CampfyreButton.PressAction onPress) {
            return new CampfyreButton(0, 0, 48, 20, Text.literal(label), onPress);
        }

        // One row: a label on the left, and either a fixed set of action
        // buttons (gamemode/time/weather/difficulty) or a single ON/OFF
        // toggle whose label is re-read from live state every frame (see
        // refresh) - so a gamerule flipped by the owner from a DIFFERENT
        // machine than the one currently hosting still visibly updates here
        // once the state broadcast round-trips, without needing to reopen
        // this screen.
        static final class Row extends ElementListWidget.Entry<Row> {
            private final String label;
            private final boolean header;
            private final List<CampfyreButton> buttons;
            private final Runnable refresh;

            private Row(String label, boolean header, List<CampfyreButton> buttons, Runnable refresh) {
                this.label = label;
                this.header = header;
                this.buttons = buttons;
                this.refresh = refresh;
            }

            static Row header(String label) {
                return new Row(label, true, List.of(), null);
            }

            static Row actionRow(String label, List<CampfyreButton> buttons) {
                return new Row(label, false, buttons, null);
            }

            static Row toggleRow(String label, java.util.function.BooleanSupplier currentValue, java.util.function.Consumer<Boolean> onToggle) {
                CampfyreButton toggle = new CampfyreButton(0, 0, 44, 20, Text.literal("..."), b -> {
                    boolean next = !currentValue.getAsBoolean();
                    onToggle.accept(next);
                });
                return new Row(label, false, List.of(toggle), () ->
                        toggle.setMessage(Text.literal(currentValue.getAsBoolean() ? "ON" : "OFF")));
            }

            @Override
            public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
                                int mouseX, int mouseY, boolean hovered, float tickDelta) {
                if (refresh != null) refresh.run();

                var tr = MinecraftClient.getInstance().textRenderer;
                if (header) {
                    context.drawCenteredTextWithShadow(tr, Text.literal(label), x + entryWidth / 2,
                            y + entryHeight / 2 - 4, CampfyreUi.ACCENT_BRIGHT);
                    CampfyreUi.drawDivider(context, x + entryWidth / 2, y + entryHeight - 3, entryWidth - 20);
                    return;
                }

                if (hovered) {
                    context.fill(x - 4, y, x + entryWidth, y + entryHeight - 1, CampfyreUi.ROW_HOVER_BG);
                }
                context.drawTextWithShadow(tr, Text.literal(label), x, y + entryHeight / 2 - 4, CampfyreUi.TEXT_COLOR);

                int bx = x + entryWidth;
                for (int i = buttons.size() - 1; i >= 0; i--) {
                    CampfyreButton button = buttons.get(i);
                    bx -= button.getWidth();
                    button.setX(bx);
                    button.setY(y);
                    button.render(context, mouseX, mouseY, tickDelta);
                    bx -= 4;
                }
            }

            @Override
            public List<? extends Element> children() {
                return buttons;
            }

            @Override
            public List<? extends Selectable> selectableChildren() {
                return buttons;
            }
        }
    }
}
