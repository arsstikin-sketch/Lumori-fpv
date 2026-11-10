package ru.fpvdrone.mixin;

import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.fpvdrone.FpvDroneClient;

/** Не рисовать руки игрока в FPV-камере. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HandsMixin {
    @Inject(
        method = "submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void fpvdrone$hideHands(CallbackInfo ci) {
        if (FpvDroneClient.CONTROLLER != null && FpvDroneClient.CONTROLLER.isViewingDrone()) {
            ci.cancel();
        }
    }
}
