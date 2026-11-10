package ru.fpvdrone.mixin.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.fpvdrone.server.DroneServer;

/** Пилоту не отправляем его собственную модель дрона — она бы закрывала FPV-камеру. */
@Mixin(Entity.class)
public abstract class EntityTrackingMixin {
    @Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void fpvdrone$hideOwnDrone(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (DroneServer.isHiddenFrom((Entity) (Object) this, player)) {
            cir.setReturnValue(false);
        }
    }

    /** Части дронов и следа никогда не сохраняются в мир (после перезапуска сервера их не будет). */
    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true, require = 0)
    private void fpvdrone$dontSaveParts(CallbackInfoReturnable<Boolean> cir) {
        if (DroneServer.isPart((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
