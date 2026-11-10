package ru.fpvdrone;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Constructor;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Клавиши мода — обычные клавиши Minecraft («Настройки → Управление → Клавиши → FPV Drone»
 * или кнопка «Клавиши…» в настройках мода). Назначение — нажатием нужной клавиши.
 */
public final class ModKeys {
    public static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(FpvDroneClient.MOD_ID, "main"));

    public static KeyMapping ARM;
    public static KeyMapping EXIT;
    public static KeyMapping RESPAWN;
    public static KeyMapping SETTINGS;
    public static KeyMapping VIEW;
    public static KeyMapping CONTROLLER_SETUP;

    private ModKeys() {
    }

    public static void register() {
        ARM = KeyMappingHelper.registerKeyMapping(create("key.fpvdrone.arm", InputConstants.KEY_R));
        EXIT = KeyMappingHelper.registerKeyMapping(create("key.fpvdrone.exit", InputConstants.KEY_BACKSPACE));
        RESPAWN = KeyMappingHelper.registerKeyMapping(create("key.fpvdrone.respawn", InputConstants.KEY_H));
        VIEW = KeyMappingHelper.registerKeyMapping(create("key.fpvdrone.view", InputConstants.KEY_V));
        CONTROLLER_SETUP = KeyMappingHelper.registerKeyMapping(create("key.fpvdrone.controller", InputConstants.KEY_K));
        SETTINGS = KeyMappingHelper.registerKeyMapping(create("key.fpvdrone.settings", InputConstants.KEY_O));
    }

    /**
     * Создать клавишу. В разных версиях у KeyMapping разные конструкторы:
     * (имя, код, категория) или (имя, тип ввода, код, категория). Подбираем подходящий.
     */
    private static KeyMapping create(String name, int key) {
        try {
            for (Constructor<?> c : KeyMapping.class.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 3 && p[0] == String.class && p[1] == int.class && p[2] == KeyMapping.Category.class) {
                    return (KeyMapping) c.newInstance(name, key, CATEGORY);
                }
            }
            for (Constructor<?> c : KeyMapping.class.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 4 && p[0] == String.class && p[1].isEnum() && p[2] == int.class && p[3] == KeyMapping.Category.class) {
                    Object keyboardType = p[1].getEnumConstants()[0]; // первый тип — клавиатура
                    return (KeyMapping) c.newInstance(name, keyboardType, key, CATEGORY);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[fpvdrone] Не удалось создать клавишу " + name, e);
        }
        throw new IllegalStateException("[fpvdrone] Не найден конструктор KeyMapping для " + name);
    }

    /** Текущая клавиша для подсказок на экране (например «R»). */
    public static String label(KeyMapping key) {
        return key == null ? "?" : key.getTranslatedKeyMessage().getString();
    }
}
