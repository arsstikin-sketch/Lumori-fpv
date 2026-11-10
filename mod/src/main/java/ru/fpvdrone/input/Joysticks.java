package ru.fpvdrone.input;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLJoystick;
import org.lwjgl.sdl.SDLStdinc;
import ru.fpvdrone.FpvDroneClient;

/**
 * Доступ к пультам и джойстикам через SDL3 (он уже есть в Minecraft 26.x через LWJGL).
 * Всегда читаем «сырой» джойстик: так видны все оси и кнопки радиопульта, включая тумблеры.
 */
public final class Joysticks {
    // SDL_INIT_GAMEPAD (включает и джойстики), так же делает FPV Freecam.
    private static final int SDL_INIT_JOYSTICK = 0x00002000;

    private static boolean initialized;
    private static boolean failed;
    private static final Map<Integer, Long> OPEN = new HashMap<>();
    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static List<Device> cachedDevices = List.of();
    private static long lastScanNanos;

    private Joysticks() {
    }

    public record Device(int id, String name) {
    }

    /** Снимок состояния одного устройства. Оси -1..1, кнопки 0/1. */
    public record Snapshot(Device device, float[] axes, boolean[] buttons) {
    }

    private static boolean init() {
        if (initialized) {
            return true;
        }
        if (failed) {
            return false;
        }
        initialized = SDLInit.SDL_InitSubSystem(SDL_INIT_JOYSTICK);
        if (!initialized) {
            failed = true;
            FpvDroneClient.LOGGER.error("[fpvdrone] SDL не смог включить джойстики: {}", SDLError.SDL_GetError());
        }
        return initialized;
    }

    /** Список подключённых устройств (обновляется не чаще 2 раз в секунду). */
    public static List<Device> devices() {
        if (!init()) {
            return List.of();
        }
        long now = System.nanoTime();
        if (now - lastScanNanos < 500_000_000L && !cachedDevices.isEmpty()) {
            return cachedDevices;
        }
        lastScanNanos = now;
        List<Device> result = new ArrayList<>();
        IntBuffer ids = SDLJoystick.SDL_GetJoysticks();
        if (ids != null) {
            try {
                for (int i = ids.position(); i < ids.limit(); i++) {
                    int id = ids.get(i);
                    String name = NAMES.computeIfAbsent(id, key -> {
                        String n = SDLJoystick.SDL_GetJoystickNameForID(key);
                        return n == null || n.isBlank() ? "Джойстик " + key : n;
                    });
                    result.add(new Device(id, name));
                }
            } finally {
                SDLStdinc.SDL_free(ids);
            }
        }
        // Закрыть отключённые.
        OPEN.entrySet().removeIf(entry -> {
            boolean stillThere = result.stream().anyMatch(d -> d.id() == entry.getKey());
            if (!stillThere) {
                SDLJoystick.SDL_CloseJoystick(entry.getValue());
                NAMES.remove(entry.getKey());
            }
            return !stillThere;
        });
        cachedDevices = result;
        return result;
    }

    /** Найти устройство по имени из настроек; если не найдено — первое. */
    public static Device pick(String preferredName) {
        List<Device> list = devices();
        if (list.isEmpty()) {
            return null;
        }
        if (preferredName != null && !preferredName.isBlank()) {
            for (Device d : list) {
                if (d.name().equals(preferredName)) {
                    return d;
                }
            }
        }
        return list.get(0);
    }

    /** Прочитать состояние. null — устройство пропало. */
    public static Snapshot read(Device device) {
        if (device == null || !init()) {
            return null;
        }
        Long handle = OPEN.get(device.id());
        if (handle == null) {
            long h = SDLJoystick.SDL_OpenJoystick(device.id());
            if (h == 0L) {
                return null;
            }
            OPEN.put(device.id(), h);
            handle = h;
        }
        long joystick = handle;
        SDLJoystick.SDL_UpdateJoysticks();
        if (!SDLJoystick.SDL_JoystickConnected(joystick)) {
            SDLJoystick.SDL_CloseJoystick(joystick);
            OPEN.remove(device.id());
            lastScanNanos = 0L;
            return null;
        }
        int axisCount = Math.max(0, SDLJoystick.SDL_GetNumJoystickAxes(joystick));
        int buttonCount = Math.max(0, SDLJoystick.SDL_GetNumJoystickButtons(joystick));
        float[] axes = new float[axisCount];
        boolean[] buttons = new boolean[buttonCount];
        for (int i = 0; i < axisCount; i++) {
            short raw = SDLJoystick.SDL_GetJoystickAxis(joystick, i);
            axes[i] = raw < 0 ? raw / 32768.0F : raw / 32767.0F;
        }
        for (int i = 0; i < buttonCount; i++) {
            buttons[i] = SDLJoystick.SDL_GetJoystickButton(joystick, i);
        }
        return new Snapshot(device, axes, buttons);
    }

    public static void shutdown() {
        for (long handle : OPEN.values()) {
            SDLJoystick.SDL_CloseJoystick(handle);
        }
        OPEN.clear();
        if (initialized) {
            SDLInit.SDL_QuitSubSystem(SDL_INIT_JOYSTICK);
            initialized = false;
        }
    }
}
