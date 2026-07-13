package com.bilal.campfyre.client.mixin;

import com.bilal.campfyre.client.CampfyreZoom;
import net.minecraft.client.Keyboard;
//? if >=1.21.9 {
/*import net.minecraft.client.input.KeyInput;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Gives the zoom key first priority over anything else bound to the same
// physical key: this fires at the HEAD of Keyboard.onKey, before vanilla's
// own KeyBinding.setKeyPressed dispatch (which CampfyreZoom used to depend
// on via KeyBinding.isPressed()) and before any other mod's own onKey mixin
// further down the chain gets a chance to run first. CampfyreZoom tracks the
// raw GLFW press/release itself from here, so zoom's own responsiveness can
// never be starved by another keybinding sharing the key, or by whatever
// order other mods' input mixins happen to run in - see
// CampfyreZoom.onRawKeyEvent's own comment. Deliberately non-cancellable:
// this only ever ADDS zoom's own tracking, it never stops vanilla (or any
// other mod) from also seeing the key - safely suppressing an arbitrary
// OTHER keybinding system-wide isn't something a targeted mixin like this
// can do without risking a totally unrelated action (e.g. if a player
// rebinds zoom onto a movement key) silently breaking instead.
@Mixin(Keyboard.class)
abstract class KeyboardMixin {

    //? if <1.21.9 {
    @Inject(method = "onKey", at = @At("HEAD"))
    private void campfyre$trackZoomKeyRaw(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        CampfyreZoom.onRawKeyEvent(key, scancode, action, modifiers);
    }
    //?}
    //? if >=1.21.9 {
    /*// onKey's own signature collapsed key/scancode/modifiers into one
    // KeyInput record at 1.21.9, same rework as KeyBinding.matchesKey -
    // verified via javap against the mapped jar (Mixin's own remap step
    // caught the stale descriptor here as a real "Invalid descriptor" apply
    // failure, not a compile error, since @Inject targets are matched by
    // method descriptor at Mixin-apply time).
    @Inject(method = "onKey", at = @At("HEAD"))
    private void campfyre$trackZoomKeyRaw(long window, int action, KeyInput input, CallbackInfo ci) {
        CampfyreZoom.onRawKeyEvent(input.key(), input.scancode(), action, input.modifiers());
    }
    *///?}
}
