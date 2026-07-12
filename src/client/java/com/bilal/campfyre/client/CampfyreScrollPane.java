package com.bilal.campfyre.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Supplier;

/**
 * The centerpiece of the GUI revamp: a scissor-clipped list that scrolls by
 * mouse wheel, keyboard (arrows/Page Up/Down while focused), and a drag
 * handle on its own thin scrollbar - used by the campfyres list and the
 * in-world roster (the two lists that had to stop being capped/paginated)
 * and by the world-settings list.
 *
 * Rows are renderer lambdas (Row), not nested ClickableWidgets. A nested
 * real widget would need its own hover/focus/position bookkeeping duplicated
 * against this pane's scroll offset - two separate places computing row
 * position is exactly what made the old page-based CampfyreListScreen's
 * pager math drift from its own row layout. One flat row list here is the
 * single source of truth for "what's under the mouse."
 *
 * Verified against the mapped 1.20.1 Yarn jar this session:
 * DrawContext.enableScissor(int x1, int y1, int x2, int y2) takes viewport
 * CORNERS, not a width/height pair - callers below compute x+width/y+height
 * themselves. Element.mouseScrolled(double, double, double) is 3-arg with a
 * single signed amount (this MC version predates the later horizontal/
 * vertical split).
 */
class CampfyreScrollPane extends ClickableWidget {

    interface Row {
        int height();

        void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY, boolean hovered, float delta);

        /** mouseX/mouseY arrive already relative to this row's own top-left. */
        default boolean onClick(double mouseX, double mouseY, int button) {
            return false;
        }
    }

    private static final int SCROLLBAR_WIDTH = 4;
    private static final int SCROLLBAR_GAP = 3;
    private static final double WHEEL_ROW_UNIT = 18.0;
    private static final long ROW_STAGGER_MS = 40;
    private static final int ROW_STAGGER_CAP = 8;
    private static final long ROW_FADE_MS = 180;

    private final Row emptyRow;
    private final Supplier<List<Row>> rowsSupplier;

    private List<Row> rows = List.of();
    private double scrollTarget = 0;
    private double scrollOffset = 0;

    private boolean draggingThumb = false;
    private double dragStartMouseY;
    private double dragStartScroll;

    private final long openedAtMs = System.currentTimeMillis();

    CampfyreScrollPane(int x, int y, int width, int height, Row emptyRow, Supplier<List<Row>> rowsSupplier) {
        super(x, y, width, height, Text.empty());
        this.emptyRow = emptyRow;
        this.rowsSupplier = rowsSupplier;
        refresh();
    }

    /** Re-pulls rows from the supplier - call from the owning screen's tick() so the pane always mirrors live state. */
    void refresh() {
        rows = rowsSupplier.get();
        double max = maxScroll();
        if (scrollTarget > max) scrollTarget = max;
    }

    private List<Row> visibleRows() {
        if (!rows.isEmpty()) return rows;
        return emptyRow != null ? List.of(emptyRow) : List.of();
    }

    private int contentHeight() {
        int total = 0;
        for (Row row : visibleRows()) total += row.height();
        return total;
    }

    private double maxScroll() {
        return Math.max(0, contentHeight() - getHeight());
    }

    private int scrollbarReserve() {
        return maxScroll() > 0 ? SCROLLBAR_WIDTH + SCROLLBAR_GAP : 0;
    }

    @Override
    protected void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        double max = maxScroll();

        // The TARGET is the only thing wheel/drag/keyboard input ever sets
        // directly (a plain stored value); easing the rendered offset toward
        // it every frame is pure visual smoothing, not animation state that
        // needs to be replayed from an event timestamp.
        scrollOffset = CampfyreUi.lerp((float) scrollOffset, (float) MathHelper.clamp(scrollTarget, 0, max), 0.35F);
        if (Math.abs(scrollOffset - scrollTarget) < 0.5) scrollOffset = MathHelper.clamp(scrollTarget, 0, max);

        context.enableScissor(x, y, x + w, y + h);

        List<Row> visible = visibleRows();
        int rowY = y - (int) Math.round(scrollOffset);
        int reserve = scrollbarReserve();
        for (int index = 0; index < visible.size(); index++) {
            Row row = visible.get(index);
            int rowHeight = row.height();
            if (rowY + rowHeight >= y && rowY <= y + h) {
                boolean hovered = isHovered() && mouseX >= x && mouseX < x + w
                        && mouseY >= Math.max(rowY, y) && mouseY < Math.min(rowY + rowHeight, y + h);
                if (hovered) {
                    context.fill(x, Math.max(rowY, y), x + w - reserve, Math.min(rowY + rowHeight, y + h), CampfyreUi.ROW_HOVER_BG);
                }

                // Entrance stagger, capped so scrolling a new row into view
                // later never replays a multi-second cascade.
                float rowAlpha = 1.0F;
                if (index < ROW_STAGGER_CAP) {
                    long start = openedAtMs + index * ROW_STAGGER_MS;
                    rowAlpha = CampfyreUi.easeOutCubic(CampfyreUi.progress(start, ROW_FADE_MS));
                }
                if (rowAlpha > 0.02F) {
                    context.getMatrices().push();
                    context.getMatrices().translate(0.0, (1.0F - rowAlpha) * 4.0, 0.0);
                    // Row's x/width match the frame onClick's mouseX is
                    // translated into (both relative to getX()) - rows add
                    // their own small internal padding rather than this pane
                    // insetting for them, so the two frames can't drift.
                    row.render(context, x, rowY, w - reserve, mouseX, mouseY, hovered, delta);
                    context.getMatrices().pop();
                }
                if (index < visible.size() - 1) {
                    context.fill(x, rowY + rowHeight - 1, x + w - reserve, rowY + rowHeight, CampfyreUi.ROW_DIVIDER);
                }
            }
            rowY += rowHeight;
        }

        context.disableScissor();

        if (max > 0) {
            int trackX = x + w - SCROLLBAR_WIDTH;
            CampfyreUi.drawScrollbarTrack(context, trackX, y, SCROLLBAR_WIDTH, h);
            int thumbHeight = (int) Math.max(12, h * (h / (double) contentHeight()));
            int thumbY = y + (int) ((h - thumbHeight) * (scrollOffset / max));
            boolean thumbHovered = mouseX >= trackX && mouseX < trackX + SCROLLBAR_WIDTH
                    && mouseY >= thumbY && mouseY < thumbY + thumbHeight;
            CampfyreUi.drawScrollbarThumb(context, trackX, thumbY, SCROLLBAR_WIDTH, thumbHeight, thumbHovered || draggingThumb);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!isMouseOver(mouseX, mouseY)) return false;
        scrollTarget = MathHelper.clamp(scrollTarget - amount * WHEEL_ROW_UNIT, 0, maxScroll());
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || !isMouseOver(mouseX, mouseY)) return false;

        double max = maxScroll();
        if (max > 0) {
            int trackX = getX() + getWidth() - SCROLLBAR_WIDTH;
            if (mouseX >= trackX && mouseX < trackX + SCROLLBAR_WIDTH) {
                draggingThumb = true;
                dragStartMouseY = mouseY;
                dragStartScroll = scrollTarget;
                setFocused(true);
                return true;
            }
        }

        int rowY = getY() - (int) Math.round(scrollOffset);
        for (Row row : visibleRows()) {
            int rowHeight = row.height();
            // Only a row (or the visible slice of one) actually inside the
            // clipped viewport can be clicked - a row half-scrolled out
            // behind the clipped edge must not be reachable through it.
            if (mouseY >= Math.max(rowY, getY()) && mouseY < Math.min(rowY + rowHeight, getY() + getHeight())) {
                setFocused(true);
                return row.onClick(mouseX - getX(), mouseY - rowY, button);
            }
            rowY += rowHeight;
        }
        setFocused(true);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!draggingThumb) return false;
        double max = maxScroll();
        if (max <= 0) return true;
        double trackHeight = getHeight();
        double thumbHeight = Math.max(12, trackHeight * (trackHeight / contentHeight()));
        double draggableRange = trackHeight - thumbHeight;
        if (draggableRange <= 0) return true;
        double deltaScroll = (mouseY - dragStartMouseY) / draggableRange * max;
        scrollTarget = MathHelper.clamp(dragStartScroll + deltaScroll, 0, max);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingThumb) {
            draggingThumb = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused()) return false;
        double max = maxScroll();
        if (max <= 0) return false;
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            scrollTarget = MathHelper.clamp(scrollTarget + WHEEL_ROW_UNIT, 0, max);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            scrollTarget = MathHelper.clamp(scrollTarget - WHEEL_ROW_UNIT, 0, max);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            scrollTarget = MathHelper.clamp(scrollTarget + getHeight() * 0.9, 0, max);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            scrollTarget = MathHelper.clamp(scrollTarget - getHeight() * 0.9, 0, max);
            return true;
        }
        return false;
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(NarrationPart.TITLE, Text.literal("Scrollable list"));
    }
}
