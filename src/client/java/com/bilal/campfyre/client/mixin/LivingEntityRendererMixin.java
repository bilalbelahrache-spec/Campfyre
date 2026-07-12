package com.bilal.campfyre.client.mixin;

import com.bilal.campfyre.client.CampfyreFrameCache;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// CampfyreFrameCache's other half. Deliberately does the look-at at HEAD of
// render() - called once per rendered frame for every currently-VISIBLE
// mob - rather than reimplementing or intercepting any of this method's own
// (fairly intricate: riding/vehicle, sleeping, upside-down) head/body/pitch
// interpolation math further down. LivingEntity.lookAt is the same helper
// vanilla's own "/execute facing" uses (verified against the mapped jar -
// javap on LivingEntity.class shows it sets yaw/headYaw/bodyYaw/pitch AND
// resyncs their prev* counterparts so there's no one-frame interpolation
// snap), so setting the entity's real rotation fields here, before any of
// the vanilla bytecode below this injection point reads them, makes every
// later read in the method just naturally pick up the override with zero
// need to touch that logic at all. Recomputed every frame so mobs keep
// facing the target as it moves.
//
// Targets the LOCAL viewer only (MinecraftClient.getInstance().player) -
// this is entirely local/unsynced (CampfyreFrameCache.isActive() is
// per-client, so who this even fires FOR is independent per person), and
// every player who has it active sees mobs staring at themselves, not at
// whoever happens to be nearest.
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V", at = @At("HEAD"))
    private void campfyre$adjustEntityRotation(LivingEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (!CampfyreFrameCache.isActive()) return;
        if (!(entity instanceof MobEntity)) return;

        AbstractClientPlayerEntity viewer = MinecraftClient.getInstance().player;
        if (viewer == null || entity == viewer.getVehicle()) return;

        entity.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, viewer.getEyePos());
    }
}
