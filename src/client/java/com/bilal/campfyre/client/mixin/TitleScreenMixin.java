package com.bilal.campfyre.client.mixin;

import com.bilal.campfyre.client.CampfyreButton;
import com.bilal.campfyre.client.CampfyreClient;
import com.bilal.campfyre.client.CampfyreListScreen;
import com.bilal.campfyre.client.CampfyreSetupScreen;
import com.bilal.campfyre.client.CampfyreStatusScreen;
import com.bilal.campfyre.client.CampfyreUi;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Docks a small square "Campfyre" icon button onto the vanilla title screen
// - matching how mods like Essential tuck their own icon buttons alongside
// the existing row of small square icons, rather than a wide labeled button
// competing with the main menu column. Instead of the old behavior of
// replacing the whole title screen with CampfyreSetupScreen at launch, the
// player decides if/when to open it. Extending Screen here (matching
// TitleScreen's real superclass) is what gives this mixin compile-time
// access to inherited members like addDrawableChild/width/height/client -
// Mixin merges this class's members into the real TitleScreen at load time
// and discards the redundant constructor/inheritance.
//
// The layout math is copied verbatim from TitleScreen's own decompiled
// init() (genClientOnlySources), not guessed:
//   int l = this.height / 4 + 48;                     // Singleplayer row
//   initWidgetsNormal(l, 24);                          // + Multiplayer (l+24), Realms (l+48)
//   language button: x = this.width/2 - 124, y = l + 72 + 12, size 20x20
// It sits at the language button's LEFT, on the same row as vanilla's two
// square icon buttons (language and accessibility), as a perfect 20x20
// square with a 4px gap. An earlier version squeezed a 20x16 rectangle into
// the 16px strip ABOVE the language button - the only free space in that
// column - and it read as exactly what it was: a squashed non-square icon
// glued to its neighbor.
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    private static final int ICON_SIZE = 20; // same square as vanilla's language button
    private static final int ICON_GAP = 4;

    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void campfyre$addDockedButton(CallbackInfo ci) {
        int mainMenuRowY = this.height / 4 + 48;
        int languageButtonX = this.width / 2 - 124;
        int languageButtonY = mainMenuRowY + 72 + 12;

        String groupId = CampfyreClient.getInstance().getGroupId();
        Text tooltipText = Text.literal(groupId == null || groupId.isBlank()
                ? "Set up a shared world with friends"
                : "Manage your Campfyre (" + groupId + ")");

        CampfyreButton button = CampfyreButton.iconOnly(languageButtonX - ICON_SIZE - ICON_GAP, languageButtonY,
                ICON_SIZE, tooltipText, b -> campfyre$onDockedButtonClicked());
        button.setTooltip(Tooltip.of(tooltipText));
        // Live coordinator-link dot in the button's corner: green while
        // connected, pulsing amber while connecting, red when the
        // coordinator's unreachable - and nothing at all when no group is
        // configured yet, so a fresh install's title screen stays clean.
        button.setStatusDot(() -> {
            CampfyreClient mod = CampfyreClient.getInstance();
            return switch (mod.getStatus()) {
                case CONNECTED -> CampfyreUi.DOT_CONNECTED;
                case CONNECTING -> CampfyreUi.DOT_CONNECTING;
                case DISCONNECTED -> CampfyreUi.DOT_OFFLINE;
                case NOT_CONFIGURED -> 0;
            };
        });
        this.addDrawableChild(button);
    }

    private void campfyre$onDockedButtonClicked() {
        CampfyreClient mod = CampfyreClient.getInstance();
        String groupId = mod.getGroupId();
        boolean noActive = groupId == null || groupId.isBlank();
        // Multiple campfyres (or campfyres remembered but none active, e.g.
        // right after leaving one): the player has a choice to make, so open
        // the chooser. One campfyre: straight to its home screen, exactly as
        // before. Nothing at all: the create/join chooser.
        if (mod.hasMultipleCampfyres() || (noActive && mod.hasAnyCampfyres())) {
            this.client.setScreen(new CampfyreListScreen(mod, this));
        } else if (noActive) {
            this.client.setScreen(new CampfyreSetupScreen(mod, this));
        } else {
            this.client.setScreen(new CampfyreStatusScreen(mod, this));
        }
    }
}
