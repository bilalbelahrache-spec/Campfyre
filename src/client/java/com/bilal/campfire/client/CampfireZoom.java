package com.bilal.campfire.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

// Smooth hold-to-zoom, the same idea as OptiFine/Essential's C-key zoom (both
// closed-source, so this is our own implementation of the well-established
// open-source technique those mods popularized - not copied from either):
// hold the key and the camera eases in over a few frames instead of
// snapping, let go and it eases back out just as smoothly. Scroll while
// held adjusts how strong the zoom goes (MouseMixin intercepts the scroll
// event before vanilla's hotbar-switch logic ever sees it - scroll belongs
// to zoom, not the hotbar, for as long as the key is down). Only the eased
// 0..1 "progress" value and the scroll-adjustable "zoomStrength" ceiling
// live here; GameRendererMixin/MouseMixin read them every FRAME (FOV
// interpolated by tickDelta the same way vanilla already interpolates its
// own sprint-FOV transition), so the zoom stays buttery even though
// progress itself only updates 20 times a second.
//
// Public (class + the accessors below) for the same mixin-subpackage reason
// as CampfireUi/CampfireButton: GameRendererMixin/MouseMixin live in the
// mixin subpackage, and Java package-private access doesn't extend to
// subpackages.
public final class CampfireZoom {

    private static final float MIN_ZOOM_DIVISOR = 1.5F;
    private static final float MAX_ZOOM_DIVISOR = 10.0F;
    private static final float DEFAULT_ZOOM_DIVISOR = 4.0F;
    private static final float ZOOM_STEP_PER_NOTCH = 0.5F;
    // Fraction of the remaining distance to the target closed per tick - not
    // a fixed step, so the ease-in/ease-out feels natural at both ends
    // instead of a linear ramp.
    private static final float SMOOTHING = 0.3F;

    private static KeyBinding zoomKey;
    private static volatile float progress = 0F;
    private static volatile float prevProgress = 0F;
    private static volatile float zoomStrength = DEFAULT_ZOOM_DIVISOR;

    private CampfireZoom() {
    }

    public static void register(CampfireClient mod) {
        zoomKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.campfire.zoom", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C, "category.campfire"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        prevProgress = progress;
        float target = isHeld() ? 1F : 0F;
        progress += (target - progress) * SMOOTHING;
        if (Math.abs(progress - target) < 0.001F) progress = target;
    }

    /**
     * Whether the zoom key is down AND actually driving zoom right now - not
     * while a screen (Campfire's own or any other) is open on top of the
     * world. MouseMixin and the tick loop both gate on this so a held key
     * can't creep FOV or eat scroll input behind a menu.
     */
    public static boolean isHeld() {
        return zoomKey != null && zoomKey.isPressed()
                && MinecraftClient.getInstance().currentScreen == null;
    }

    /** Scroll while zoomed adjusts how strong the zoom goes, instead of switching hotbar slots. */
    public static void adjustZoomLevel(double scrollAmount) {
        zoomStrength = MathHelper.clamp(
                zoomStrength + (float) scrollAmount * ZOOM_STEP_PER_NOTCH,
                MIN_ZOOM_DIVISOR, MAX_ZOOM_DIVISOR);
    }

    private static double divisorFor(float easedProgress) {
        return 1.0 + easedProgress * (zoomStrength - 1.0);
    }

    /** Interpolated zoom divisor for this render frame - 1.0 means "no zoom, unchanged FOV". */
    public static double zoomDivisor(float tickDelta) {
        return divisorFor(MathHelper.lerp(tickDelta, prevProgress, progress));
    }

    /**
     * How much to scale mouse-look sensitivity by while zoomed, proportional
     * to how zoomed in we currently are - so panning feels controlled
     * instead of twitchy, the same idea vanilla itself already uses for the
     * spyglass (Mouse.updateMouse has its own isUsingSpyglass sensitivity
     * branch). 1.0 means unchanged. Uses the raw (non-interpolated)
     * progress, not zoomDivisor's tickDelta-smoothed one - this only scales
     * a per-frame input delta rather than driving anything rendered, so a
     * single tick of lag here is imperceptible and not worth threading a
     * tickDelta through updateMouse's injection for.
     */
    public static double lookSensitivityFactor() {
        return 1.0 / divisorFor(progress);
    }
}
