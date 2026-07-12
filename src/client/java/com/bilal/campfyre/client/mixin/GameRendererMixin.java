package com.bilal.campfyre.client.mixin;

import com.bilal.campfyre.client.CampfyreZoom;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Zoom is implemented by scaling GameRenderer's own FOV calculation, the
// same approach every other Minecraft zoom mod (OptiFine, Zoomify, etc.)
// uses - there's no separate "camera distance" to move, the whole game IS
// rendered at whatever FOV this method returns. Verified against the real
// mapped jar (javap) rather than guessed: getFov is private and returns a
// double, signature (Camera, float tickDelta, boolean changingFov) - Mixin
// injects into the bytecode directly, so the target's own visibility
// doesn't matter here.
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void campfyre$applyZoom(Camera camera, float tickDelta, boolean changingFov,
                                     CallbackInfoReturnable<Double> cir) {
        double divisor = CampfyreZoom.zoomDivisor(tickDelta);
        if (divisor > 1.0) {
            cir.setReturnValue(cir.getReturnValue() / divisor);
        }
    }
}
