package ru.fpvdrone.plugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Цвета частей дрона. Хранятся в данных игрока (PersistentDataContainer), переживают перезаход и рестарт.
 */
public final class DroneColors {
    public enum Part {
        FRAME("рама", "black"),
        ARMS("лучи", "black"),
        PROPS("пропеллеры", "red"),
        MOTORS("моторы", "light_gray"),
        CAMERA("камера", "gray"),
        BATTERY("батарея", "yellow"),
        LED("подсветка", "lime");

        public final String ru;
        public final String defaultColor;

        Part(String ru, String defaultColor) {
            this.ru = ru;
            this.defaultColor = defaultColor;
        }

        public String id() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        public static Part parse(String raw) {
            if (raw == null) {
                return null;
            }
            String s = raw.toLowerCase(Locale.ROOT);
            for (Part part : values()) {
                if (part.id().equals(s) || part.ru.equals(s)) {
                    return part;
                }
            }
            return null;
        }
    }

    /** 16 цветов Minecraft (как у шерсти/бетона). */
    public static final List<String> COLORS = List.of(
        "white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow",
        "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"
    );

    private static final int[] RGB = {
        0xF9FFFE, 0x9D9D97, 0x474F52, 0x1D1D21, 0x835432, 0xB02E26, 0xF9801D, 0xFED83D,
        0x80C71F, 0x5E7C16, 0x169C9C, 0x3AB3DA, 0x3C44AA, 0x8932B8, 0xC74EBD, 0xF38BAA
    };

    /** RGB цвета по имени (для обводки). */
    public static int rgb(String color) {
        int i = COLORS.indexOf(color);
        return RGB[i < 0 ? 3 : i];
    }

    private final Map<Part, String> colors = new EnumMap<>(Part.class);

    public DroneColors() {
        for (Part part : Part.values()) {
            this.colors.put(part, part.defaultColor);
        }
    }

    public String get(Part part) {
        return this.colors.get(part);
    }

    public void set(Part part, String color) {
        this.colors.put(part, color);
    }

    /** Непрозрачный блок для части. */
    public Material solid(Part part) {
        return material(this.get(part), "_CONCRETE", Material.BLACK_CONCRETE);
    }

    /** Полупрозрачный блок (для вращающихся пропеллеров). */
    public Material glass(Part part) {
        return material(this.get(part), "_STAINED_GLASS", Material.GLASS);
    }

    private static Material material(String color, String suffix, Material fallback) {
        Material m = Material.matchMaterial(color.toUpperCase(Locale.ROOT) + suffix);
        return m == null ? fallback : m;
    }

    public static boolean isValidColor(String color) {
        return color != null && COLORS.contains(color.toLowerCase(Locale.ROOT));
    }

    public static DroneColors load(Plugin plugin, Player player) {
        DroneColors c = new DroneColors();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        for (Part part : Part.values()) {
            String value = pdc.get(key(plugin, part), PersistentDataType.STRING);
            if (isValidColor(value)) {
                c.set(part, value);
            }
        }
        return c;
    }

    public void save(Plugin plugin, Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        for (Part part : Part.values()) {
            pdc.set(key(plugin, part), PersistentDataType.STRING, this.get(part));
        }
    }

    private static NamespacedKey key(Plugin plugin, Part part) {
        return new NamespacedKey(plugin, "color_" + part.id());
    }

    public static List<String> partIds() {
        List<String> ids = new ArrayList<>();
        for (Part part : Part.values()) {
            ids.add(part.id());
        }
        return ids;
    }
}
