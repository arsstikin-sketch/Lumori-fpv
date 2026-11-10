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
        helloSent = false;
        wasActive = false;
        colorsDirty = true;
        serverHasSupport = false;
        serverMaxRange = 0.0F;
    }

    /** Итоговое ограничение дальности, блоков (0 = только загруженные чанки). */
    public static float effectiveMaxRange() {
        if (serverHasSupport && serverMaxRange > 0.0F) {
            return serverMaxRange;
        }
        return FpvDroneClient.SETTINGS == null ? 0.0F : FpvDroneClient.SETTINGS.localMaxRange;
    }
}
