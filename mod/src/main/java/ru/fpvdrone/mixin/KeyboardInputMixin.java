package ru.fpvdrone.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.fpvdrone.FpvDroneClient;

/** Пока летает дрон, игрок стоит на месте (WASD/прыжок не двигают тело). */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void fpvdrone$freezePlayer(CallbackInfo ci) {
        if (FpvDroneClient.CONTROLLER != null && FpvDroneClient.CONTROLLER.isViewingDrone()) {
            this.moveVector = Vec2.ZERO;
            this.keyPresses = Input.EMPTY;
        }
    }
}
