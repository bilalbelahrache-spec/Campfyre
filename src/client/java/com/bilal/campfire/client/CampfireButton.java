package com.bilal.campfire.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

// Flat, dark card-style button used on the cozy Campfire screens in place of
// the vanilla stone-gradient button texture. Still a real ButtonWidget
// (click handling, focus, narration all come from the vanilla superclass);
// only renderButton() is replaced. Public (and constructors public) because
// TitleScreenMixin, in a different package, constructs one directly for the
// docked title-screen button.
public class CampfireButton extends ButtonWidget {

    private enum Style { TEXT, ICON_TEXT, ICON_ONLY }

    private final Style style;

    // Optional live status dot in the corner of an ICON_ONLY button (the
    // docked title-screen button uses it to show the coordinator link state
    // without the player opening anything). Re-queried every frame; 0 means
    // "no dot". Public for TitleScreenMixin (different package).
    private java.util.function.IntSupplier statusDotColor;

    public void setStatusDot(java.util.function.IntSupplier colorSupplier) {
        this.statusDotColor = colorSupplier;
    }

    public CampfireButton(int x, int y, int width, int height, Text message, PressAction onPress) {
        this(x, y, width, height, message, onPress, Style.TEXT);
    }

    // withIcon draws the small pixel campfire next to the label - reserved
    // for the two primary setup-screen choices, so it reads as a deliberate
    // brand mark rather than clutter on every small utility button (Copy
    // Code, Back, Close, ...).
    public CampfireButton(int x, int y, int width, int height, Text message, PressAction onPress, boolean withIcon) {
        this(x, y, width, height, message, onPress, withIcon ? Style.ICON_TEXT : Style.TEXT);
    }

    // Icon-only button - no label drawn at all, matching how vanilla's own
    // language/accessibility buttons work (icon + hover tooltip, no visible
    // text). `narrationMessage` is never rendered, only used for
    // narration/tooltip.
    public static CampfireButton iconOnly(int x, int y, int size, Text narrationMessage, PressAction onPress) {
        return iconOnly(x, y, size, size, narrationMessage, onPress);
    }

    // Non-square variant. Currently unused (the docked title-screen button
    // went back to a true 20x20 square after the 20x16 version read as
    // squashed) - kept as the general form the square overload delegates to.
    public static CampfireButton iconOnly(int x, int y, int width, int height, Text narrationMessage, PressAction onPress) {
        return new CampfireButton(x, y, width, height, narrationMessage, onPress, Style.ICON_ONLY);
    }

    private CampfireButton(int x, int y, int width, int height, Text message, PressAction onPress, Style style) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
        this.style = style;
    }

    @Override
    protected void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean hovered = isHovered() && active;

        context.fill(x, y, x + w, y + h, active ? CampfireUi.BUTTON_BG : CampfireUi.BUTTON_BG_DISABLED);
        context.fillGradient(x, y, x + w, y + h / 2,
                CampfireUi.withAlpha(0xFFFFFF, hovered ? 28 : 14), 0x00FFFFFF);
        context.drawBorder(x, y, w, h, hovered ? CampfireUi.ACCENT : CampfireUi.BUTTON_BORDER);

        switch (style) {
            case ICON_ONLY -> {
                if (hovered) {
                    context.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, CampfireUi.ACCENT);
                }
                CampfireUi.drawCampfireIcon(context, x + (w - CampfireUi.ICON_WIDTH) / 2, y + (h - CampfireUi.ICON_HEIGHT) / 2);
                if (statusDotColor != null) {
                    int dot = statusDotColor.getAsInt();
                    if (dot != 0) {
                        CampfireUi.drawStatusDot(context, x + w - 4, y + 3, dot, dot == CampfireUi.DOT_CONNECTING);
                    }
                }
            }
            case ICON_TEXT -> {
                if (hovered) {
                    context.fill(x + 1, y + 1, x + 3, y + h - 1, CampfireUi.ACCENT);
                }
                TextRenderer tr = MinecraftClient.getInstance().textRenderer;
                int textColor = !active ? CampfireUi.DISABLED_TEXT : hovered ? CampfireUi.TITLE_COLOR : CampfireUi.TEXT_COLOR;
                int textWidth = tr.getWidth(getMessage());
                int gap = 5;
                int totalWidth = CampfireUi.ICON_WIDTH + gap + textWidth;
                int startX = x + (w - totalWidth) / 2;
                CampfireUi.drawCampfireIcon(context, startX, y + (h - CampfireUi.ICON_HEIGHT) / 2);
                context.drawTextWithShadow(tr, getMessage(), startX + CampfireUi.ICON_WIDTH + gap,
                        y + (h - tr.fontHeight) / 2, textColor);
            }
            case TEXT -> {
                if (hovered) {
                    context.fill(x + 1, y + 1, x + 3, y + h - 1, CampfireUi.ACCENT);
                }
                TextRenderer tr = MinecraftClient.getInstance().textRenderer;
                int textColor = !active ? CampfireUi.DISABLED_TEXT : hovered ? CampfireUi.TITLE_COLOR : CampfireUi.TEXT_COLOR;
                context.drawCenteredTextWithShadow(tr, getMessage(), x + w / 2, y + (h - tr.fontHeight) / 2, textColor);
            }
        }
    }
}
