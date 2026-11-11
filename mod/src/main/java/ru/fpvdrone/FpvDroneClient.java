package ru.fpvdrone;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fpvdrone.flight.DroneController;
import ru.fpvdrone.hud.DroneHud;
import ru.fpvdrone.input.ControllerProfiles;
import ru.fpvdrone.input.Joysticks;
import ru.fpvdrone.net.DroneNetClient;
import ru.fpvdrone.ui.ControllerSetupScreen;
import ru.fpvdrone.ui.DroneSettingsScreen;

/** Клиентская часть FPV Drone: полёт, пульт, камера, OSD, настройки. */
public final class FpvDroneClient implements ClientModInitializer {
    public static final String MOD_ID = FpvDroneMod.MOD_ID;
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static DroneSettings SETTINGS;
    public static ControllerProfiles PROFILES;
    public static DroneController CONTROLLER;

    @Override
    public void onInitializeClient() {
        SETTINGS = DroneSettings.load(FabricLoader.getInstance().getConfigDir());
        PROFILES = ControllerProfiles.load(FabricLoader.getInstance().getConfigDir());
        CONTROLLER = new DroneController();
        ModKeys.register();
        DroneNetClient.init();

        HudElementRegistry.attachElementAfter(VanillaHudElements.SUBTITLES, Identifier.fromNamespaceAndPath(MOD_ID, "osd"), DroneHud::render);
        hideDuringFlight(VanillaHudElements.CROSSHAIR);
        hideDuringFlight(VanillaHudElements.HOTBAR);
        hideDuringFlight(VanillaHudElements.HEALTH_BAR);
        hideDuringFlight(VanillaHudElements.ARMOR_BAR);
        hideDuringFlight(VanillaHudElements.FOOD_BAR);
        hideDuringFlight(VanillaHudElements.AIR_BAR);
        hideDuringFlight(VanillaHudElements.EXPERIENCE_LEVEL);
        // Серверный таймер гонки использует action bar: оставляем его видимым.
        hideDuringFlight(VanillaHudElements.HELD_ITEM_TOOLTIP);

        ClientTickEvents.END_CLIENT_TICK.register(FpvDroneClient::onEndTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            CONTROLLER.exit();
            DroneNetClient.onDisconnect();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            SETTINGS.save();
            PROFILES.save();
            Joysticks.shutdown();
        });
        LOGGER.info("[fpvdrone] FPV Drone (клиент) загружен");
    }

    private static void onEndTick(Minecraft minecraft) {
        ru.fpvdrone.input.ActionButtons.poll();
        DroneNetClient.tick(minecraft);
        SETTINGS.saveIfDirty();
        if (ModKeys.SETTINGS.consumeClick() || ru.fpvdrone.input.ActionButtons.clicked(ModKeys.SETTINGS)) {
            if (minecraft.gui.screen() == null) {
                minecraft.gui.setScreen(new DroneSettingsScreen(null));
            }
        }
        if (ModKeys.CONTROLLER_SETUP.consumeClick() || ru.fpvdrone.input.ActionButtons.clicked(ModKeys.CONTROLLER_SETUP)) {
            if (minecraft.gui.screen() == null) {
                minecraft.gui.setScreen(new ControllerSetupScreen(null));
            }
        }
    }

    private static void hideDuringFlight(Identifier id) {
        HudElementRegistry.replaceElement(id, original -> (HudElement) (graphics, deltaTracker) -> {
            if (CONTROLLER != null && CONTROLLER.isViewingDrone() && SETTINGS.hideVanillaHud) {
                return;
            }
            original.extractRenderState(graphics, deltaTracker);
        });
    }
}
