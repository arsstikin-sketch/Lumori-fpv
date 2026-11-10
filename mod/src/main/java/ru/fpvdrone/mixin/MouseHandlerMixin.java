package ru.fpvdrone.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.fpvdrone.FpvDroneClient;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Redirect(method = "turnPlayer(D)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void fpvdrone$mouse(LocalPlayer player, double dx, double dy) {
        var controller = FpvDroneClient.CONTROLLER;
        if (controller != null && controller.isViewingDrone()) {
            controller.mouseMotion(dx, dy);
        } else {
            player.turn(dx, dy);
        }
    }
}
