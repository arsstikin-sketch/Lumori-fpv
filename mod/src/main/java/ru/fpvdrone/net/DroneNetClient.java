package ru.fpvdrone.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import ru.fpvdrone.FpvDroneClient;
import ru.fpvdrone.flight.DroneController;

/**
 * Клиентская сеть: отправка состояния дрона и цветов на сервер (где стоит этот же мод или плагин FpvDrone)
 * и приём настроек сервера.
 */
public final class DroneNetClient {
    public static volatile boolean serverHasSupport;
    public static volatile float serverMaxRange;
    public static volatile float serverDurability, serverBumpSpeed, serverPropBreakSpeed;
    public static volatile float serverArmBreakSpeed, serverFrameBreakSpeed;
    public static volatile byte serverLiquidsDestroy = -1;

    public static float durability(ru.fpvdrone.DroneSettings c) { return serverDurability > 0 ? serverDurability : c.durability; }
    public static float bumpSpeed(ru.fpvdrone.DroneSettings c) { return serverBumpSpeed > 0 ? serverBumpSpeed : c.bumpSpeed; }
    public static float propBreakSpeed(ru.fpvdrone.DroneSettings c) { return serverPropBreakSpeed > 0 ? serverPropBreakSpeed : c.propBreakSpeed; }
    public static float armBreakSpeed(ru.fpvdrone.DroneSettings c) { return serverArmBreakSpeed > 0 ? serverArmBreakSpeed : c.armBreakSpeed; }
    public static float frameBreakSpeed(ru.fpvdrone.DroneSettings c) { return serverFrameBreakSpeed > 0 ? serverFrameBreakSpeed : c.frameBreakSpeed; }
    public static boolean liquidsDestroy(ru.fpvdrone.DroneSettings c) {
        return serverLiquidsDestroy == -1 ? c.liquidsDestroy : serverLiquidsDestroy == 1;
    }

    /** 0 = All, 1 = Acro, 2 = Level. */
    public static volatile int serverStabilization;

    public static ru.fpvdrone.DroneSettings.Stabilization effectiveStabilization() {
        return switch (serverStabilization) {
            case 1 -> ru.fpvdrone.DroneSettings.Stabilization.ACRO;
            case 2 -> ru.fpvdrone.DroneSettings.Stabilization.LEVEL;
            default -> FpvDroneClient.SETTINGS.stabilization;
        };
    }

    private static boolean helloSent;
    private static boolean wasActive;
    private static boolean colorsDirty = true;

    private DroneNetClient() {
    }

    /** Типы пакетов регистрируются в общей точке входа (FpvDroneMod); здесь — только приём. */
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(DronePayloads.Config.TYPE, (payload, context) -> {
            float range = DroneProtocol.decodeConfigRange(payload.data());
            if (range >= 0.0F) {
                serverStabilization = DroneProtocol.decodeConfigStabilization(payload.data());
                var damage = DroneProtocol.decodeDamageConfig(payload.data());
                serverDurability = damage[0]; serverBumpSpeed = damage[1]; serverPropBreakSpeed = damage[2];
                serverArmBreakSpeed = damage[3]; serverFrameBreakSpeed = damage[4];
                serverLiquidsDestroy = DroneProtocol.decodeLiquidsConfig(payload.data());
                serverMaxRange = range;
                serverHasSupport = true;
                FpvDroneClient.LOGGER.info("[fpvdrone] Сервер поддерживает FPV Drone, дальность {}", range);
            }
        });
    }

    public static void colorsChanged() {
        colorsDirty = true;
    }

    /** Конец каждого клиентского тика (20 раз в секунду). */
    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        if (!helloSent && ClientPlayNetworking.canSend(DronePayloads.Hello.TYPE)) {
            ClientPlayNetworking.send(new DronePayloads.Hello(DroneProtocol.encodeHello()));
            helloSent = true;
            colorsDirty = true;
        }
        if (colorsDirty && ClientPlayNetworking.canSend(DronePayloads.Colors.TYPE)) {
            ClientPlayNetworking.send(new DronePayloads.Colors(DroneProtocol.encodeColors(FpvDroneClient.SETTINGS.colorBytes())));
            colorsDirty = false;
        }
        if (!ClientPlayNetworking.canSend(DronePayloads.State.TYPE)) {
            return;
        }
        DroneController controller = FpvDroneClient.CONTROLLER;
        boolean active = controller.isActive();
        if (active) {
            byte[] data = DroneProtocol.encodeState(controller.state, controller.damage, FpvDroneClient.SETTINGS.cameraAngle);
            ClientPlayNetworking.send(new DronePayloads.State(data));
        } else if (wasActive) {
            ClientPlayNetworking.send(new DronePayloads.State(DroneProtocol.encodeInactive()));
        }
        wasActive = active;
    }

    public static void onDisconnect() {
        serverStabilization = 0;
        helloSent = false;
        wasActive = false;
        colorsDirty = true;
        serverHasSupport = false;
        serverMaxRange = 0.0F;
        serverDurability = serverBumpSpeed = serverPropBreakSpeed = serverArmBreakSpeed = serverFrameBreakSpeed = 0;
        serverLiquidsDestroy = -1;
    }

    /** Итоговое ограничение дальности в блоках. */
    public static float effectiveMaxRange() {
        if (serverHasSupport && serverMaxRange > 0.0F) {
            return serverMaxRange;
        }
        return FpvDroneClient.SETTINGS == null ? 0.0F : FpvDroneClient.SETTINGS.localMaxRange;
    }
}
