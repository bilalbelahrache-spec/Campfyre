package com.bilal.campfyre.client;

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
// snapping, let go and it eases back out FASTER than it eased in (see the
// asymmetric SMOOTHING_IN/SMOOTHING_OUT split below - feedback: return-to-
// normal read as sluggish with a single shared factor). Scroll while held
// adjusts how strong the zoom goes (MouseMixin intercepts the scroll event
// before vanilla's hotbar-switch logic ever sees it - scroll belongs to
// zoom, not the hotbar, for as long as the key is down). GameRendererMixin/
// MouseMixin read this class's state every FRAME (FOV interpolated by
// tickDelta the same way vanilla already interpolates its own sprint-FOV
// transition), so the zoom stays buttery even though progress itself only
// updates 20 times a second.
//
// While held, this also turns on vanilla's own "Cinematic Camera" option
// (GameOptions.smoothCameraEnabled, the same setting behind the normally-
// unbound "Toggle Cinematic Camera" keybind) instead of layering a second,
// hand-rolled look-damping pass on top of our own zoom sensitivity scale.
// Verified against the real mapped jar (javap -c on Mouse.class): when that
// flag is set, Mouse.updateMouse runs cursorDeltaX/Y through
// cursorXSmoother/cursorYSmoother (both SmoothUtil) BEFORE our own
// MouseMixin ModifyArgs hook ever sees the value (that hook fires at the
// changeLookDirection invoke, later in the same method) - so vanilla's own
// smoothing is what actually produces the "movement acceleration"/trailing-
// camera feel here, not a second easing layer of ours stacked in front of
// it (an earlier version carried its own pendingYaw/pendingPitch damping,
// which just doubled up on top of this and read as mushy/laggy).
//
// Public (class + the accessors below) for the same mixin-subpackage reason
// as CampfyreUi/CampfyreButton: GameRendererMixin/MouseMixin/KeyboardMixin
// live in the mixin subpackage, and Java package-private access doesn't
// extend to subpackages.
public final class CampfyreZoom {

    private static final float MIN_ZOOM_DIVISOR = 1.5F;
    private static final float MAX_ZOOM_DIVISOR = 10.0F;
    private static final float DEFAULT_ZOOM_DIVISOR = 4.0F;
    private static final float ZOOM_STEP_PER_NOTCH = 0.5F;
    // Fraction of the remaining distance to the target closed per tick - not
    // a fixed step, so the ease-in/ease-out feels natural at both ends
    // instead of a linear ramp. Zooming OUT (releasing the key) uses a
    // bigger fraction than zooming IN on purpose - a slower, deliberate
    // ease into the zoomed view reads as intentional, but that same pace
    // on the way back out just reads as the game being laggy to let go of.
    private static final float SMOOTHING_IN = 0.3F;
    private static final float SMOOTHING_OUT = 0.55F;

    private static KeyBinding zoomKey;
    private static volatile float progress = 0F;
    private static volatile float prevProgress = 0F;
    private static volatile float zoomStrength = DEFAULT_ZOOM_DIVISOR;
    // The zoom key's raw physical state, set directly from KeyboardMixin's
    // GLFW callback hook rather than read from KeyBinding.isPressed() - see
    // isHeld()'s own comment for why.
    private static volatile boolean rawHeld = false;
    // tick()-thread only (client thread, same as the GameOptions access
    // in applyCinematicCamera below) - tracks isHeld()'s value as of the
    // last tick so the Cinematic Camera toggle only fires on an actual
    // press/release edge, not every tick while held.
    private static boolean wasHeld = false;
    // The player's own Cinematic Camera setting from before we forced it on
    // for a zoom session, restored on release. null when we haven't
    // overridden it (either never zoomed yet this session, or already
    // restored it).
    private static Boolean previousSmoothCameraEnabled = null;

    private CampfyreZoom() {
    }

    public static void register(CampfyreClient mod) {
        zoomKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.campfyre.zoom", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C, "category.campfyre"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    // Called from KeyboardMixin at the HEAD of Keyboard.onKey, before
    // vanilla's own KeyBinding.setKeyPressed dispatch runs (which only fires
    // when no screen is open) and before any other mod's onKey mixin further
    // down the chain gets a chance to run first. Tracking the raw GLFW
    // press/release ourselves - instead of trusting KeyBinding.isPressed(),
    // which depends on that same shared dispatch - is what gives zoom actual
    // priority: another keybinding (vanilla's or another mod's) bound to the
    // same physical key can't starve zoom of its own press/release events,
    // because zoom never waits on that shared path in the first place.
    public static void onRawKeyEvent(int keyCode, int scanCode, int action) {
        if (zoomKey == null || !zoomKey.matchesKey(keyCode, scanCode)) return;
        if (action == GLFW.GLFW_PRESS) rawHeld = true;
        else if (action == GLFW.GLFW_RELEASE) rawHeld = false;
        // GLFW_REPEAT: key is still down, no state change - ignored.
    }

    private static void tick() {
        prevProgress = progress;
        boolean held = isHeld();
        float target = held ? 1F : 0F;
        progress += (target - progress) * (held ? SMOOTHING_IN : SMOOTHING_OUT);
        if (Math.abs(progress - target) < 0.001F) progress = target;

        if (held != wasHeld) {
            applyCinematicCamera(held);
            wasHeld = held;
        }
    }

    // Forces vanilla's own "Cinematic Camera" option on for the duration of
    // a zoom hold and restores whatever the player actually had it set to
    // on release - see the class comment for why we lean on vanilla's real
    // implementation here instead of a hand-rolled damping pass.
    private static void applyCinematicCamera(boolean held) {
        var options = MinecraftClient.getInstance().options;
        if (held) {
            previousSmoothCameraEnabled = options.smoothCameraEnabled;
            options.smoothCameraEnabled = true;
        } else if (previousSmoothCameraEnabled != null) {
            options.smoothCameraEnabled = previousSmoothCameraEnabled;
            previousSmoothCameraEnabled = null;
        }
    }

    /**
     * Whether the zoom key is down AND actually driving zoom right now - not
     * while a screen (Campfyre's own or any other) is open on top of the
     * world. MouseMixin and the tick loop both gate on this so a held key
     * can't creep FOV or eat scroll input behind a menu.
     */
    public static boolean isHeld() {
        return rawHeld && MinecraftClient.getInstance().currentScreen == null;
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

    private static double lookSensitivityFactor() {
        return 1.0 / divisorFor(progress);
    }

    /**
     * Scales one frame's raw cursorDeltaX/Y (MouseMixin's ModifyArgs into
     * ClientPlayerEntity.changeLookDirection) down proportional to zoom
     * strength - same idea vanilla itself uses for the spyglass
     * (Mouse.updateMouse's own isUsingSpyglass branch). The damped/trailing
     * "cinematic" feel on top of this comes from vanilla's own Cinematic
     * Camera smoothing (applyCinematicCamera, forced on for the hold) rather
     * than a second easing pass here.
     */
    public static double scaleLookDelta(double rawDelta) {
        return rawDelta * lookSensitivityFactor();
    }
}
