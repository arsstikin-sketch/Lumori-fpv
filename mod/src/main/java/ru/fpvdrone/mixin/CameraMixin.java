package ru.fpvdrone.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.fpvdrone.FpvDroneClient;
import ru.fpvdrone.flight.CameraApplier;
import ru.fpvdrone.flight.DroneController;

/**
 * Каждый кадр: шаг симуляции дрона и перенос камеры в дрон (позиция + поворот с креном).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private Entity entity;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(
        method = "update(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getViewRotationMatrix(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;")
    )
    private void fpvdrone$droneCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        DroneController controller = FpvDroneClient.CONTROLLER;
        if (controller == null) {
            return;
        }
        controller.onFrame(minecraft);
        if (controller.isViewingDrone() && this.entity == minecraft.player) {
            Vec3 pos = controller.renderPosition();
            this.setPosition(pos.x, pos.y, pos.z);
            CameraApplier.apply((Camera) (Object) this, controller.renderCameraOrientation());
        }
    }

    /** Камера «отделена» от игрока — тогда Minecraft рисует тело игрока, и его видно из дрона. */
    @Inject(method = "isDetached()Z", at = @At("HEAD"), cancellable = true)
    private void fpvdrone$detached(CallbackInfoReturnable<Boolean> cir) {
        DroneController controller = FpvDroneClient.CONTROLLER;
        if (controller != null && controller.isViewingDrone() && this.entity == Minecraft.getInstance().player) {
            cir.setReturnValue(true);
        }
    }
}
