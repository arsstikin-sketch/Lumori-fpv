package ru.fpvdrone.input;

import java.util.*;
import java.nio.file.*;
import net.minecraft.client.KeyMapping;
import ru.fpvdrone.*;

/** Дополнительные кнопки действий, отдельные для каждого подключённого устройства. */
public final class ActionButtons {
    private static final Properties bindings = new Properties();
    private static final Path path = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("fpvdrone-buttons.properties");
    private static final Set<String> down = new HashSet<>();
    private static final Set<String> clicks = new HashSet<>();
    static { if (Files.exists(path)) try (var in = Files.newBufferedReader(path)) { bindings.load(in); } catch (Exception e) { FpvDroneClient.LOGGER.warn("Не удалось прочитать кнопки", e); } }
    public static KeyMapping[] actions() { return new KeyMapping[]{ModKeys.ARM,ModKeys.VIEW,ModKeys.EXIT,ModKeys.RESPAWN,ModKeys.STABILIZATION,ModKeys.SETTINGS,ModKeys.CONTROLLER_SETUP,net.minecraft.client.Minecraft.getInstance().options.keyUp,net.minecraft.client.Minecraft.getInstance().options.keyDown,net.minecraft.client.Minecraft.getInstance().options.keyLeft,net.minecraft.client.Minecraft.getInstance().options.keyRight,net.minecraft.client.Minecraft.getInstance().options.keyJump,net.minecraft.client.Minecraft.getInstance().options.keyShift}; }
    private static String id(String device, KeyMapping key) { return device + ":" + key.getName(); }
    public static int button(String device, KeyMapping key) { try { return Integer.parseInt(bindings.getProperty(id(device,key), "-1")); } catch (NumberFormatException e) { return -1; } }
    public static void assign(String device, KeyMapping key, int index) {
        bindings.setProperty(id(device,key), Integer.toString(index));
        if(index>=0 && (key==ModKeys.ARM || key==ModKeys.EXIT)) {
            var profile=FpvDroneClient.PROFILES.get(device);
            if(key==ModKeys.ARM) profile.armSource=ControllerProfile.Source.NONE;
            else profile.exitSource=ControllerProfile.Source.NONE;
            FpvDroneClient.PROFILES.save();
        }
        try (var out = Files.newBufferedWriter(path)) { bindings.store(out, "FPV Drone action buttons"); } catch (Exception e) { FpvDroneClient.LOGGER.warn("Не удалось сохранить кнопки", e); }
    }
    public static void poll() {
        var snapshot = Joysticks.read(Joysticks.pick(FpvDroneClient.SETTINGS.controllerName));
        clicks.clear();
        for (var key : actions()) {
            int i = snapshot == null ? -1 : button(snapshot.device().name(), key);
            boolean pressed = snapshot != null && i >= 0 && i < snapshot.buttons().length && snapshot.buttons()[i];
            if (pressed && !down.contains(key.getName())) clicks.add(key.getName());
            if (pressed) down.add(key.getName()); else down.remove(key.getName());
        }
    }
    public static boolean isDown(KeyMapping key) { return down.contains(key.getName()); }
    public static boolean clicked(KeyMapping key) { return clicks.remove(key.getName()); }
}
