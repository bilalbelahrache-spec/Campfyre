package com.bilal.campfyre.client.mixin;

import com.bilal.campfyre.client.CampfyreZoom;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

// Two hooks so held-zoom behaves like a real camera lens, not just a
// narrower FOV:
//
// - Scrolling while zoomed adjusts zoom strength instead of switching
//   hotbar slots - scroll belongs to zoom for as long as the key is down.
//   Verified against the real mapped jar (javap -c on Mouse.onMouseScroll)
//   that vanilla only reaches its hotbar-scroll branch when
//   currentScreen == null; everything else (GUI scroll) routes through
//   Screen.mouseScrolled first. That's the exact same condition
//   CampfyreZoom.isHeld() already requires, so cancelling at HEAD here can
//   never eat a GUI's scroll input.
// - Mouse-look while zoomed is scaled down (CampfyreZoom.scaleLookDelta) -
//   the same idea vanilla itself already uses for the spyglass
//   (updateMouse's own isUsingSpyglass sensitivity branch, found the same
//   way), just proportional to however zoomed in Campfyre's zoom currently
//   is instead of a fixed spyglass amount. This mixin fires at the
//   changeLookDirection invoke, which is AFTER vanilla's own Cinematic
//   Camera smoothing (CampfyreZoom forces GameOptions.smoothCameraEnabled
//   on for the hold) already ran earlier in the same updateMouse call - so
//   the damped/trailing camera feel comes from vanilla's real
//   implementation, and this hook only needs to handle the sensitivity
//   scale, not a second easing pass on top of it.
@Mixin(Mouse.class)
abstract class MouseMixin {

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void campfyre$scrollAdjustsZoom(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (CampfyreZoom.isHeld()) {
            CampfyreZoom.adjustZoomLevel(vertical);
            ci.cancel();
        }
    }

    @ModifyArgs(method = "updateMouse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void campfyre$scaleLookForZoom(Args args) {
        if (!CampfyreZoom.isHeld()) return;
        args.set(0, CampfyreZoom.scaleLookDelta((double) args.get(0)));
        args.set(1, CampfyreZoom.scaleLookDelta((double) args.get(1)));
    }
}
