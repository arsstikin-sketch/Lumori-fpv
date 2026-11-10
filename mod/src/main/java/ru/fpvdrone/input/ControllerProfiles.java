package ru.fpvdrone.input;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import ru.fpvdrone.FpvDroneClient;

/**
 * Хранилище профилей пультов. Ключ — имя устройства. Новый пульт получает профиль по умолчанию,
 * и все изменения (назначения, калибровка, арм) сохраняются только для него.
 *
 * Файл: config/fpvdrone-controllers.properties, ключи вида "<номер>.<поле>", имя пульта в "<номер>.name".
 */
public final class ControllerProfiles {
    private static final String FILE_NAME = "fpvdrone-controllers.properties";

    private final Map<String, ControllerProfile> profiles = new LinkedHashMap<>();
    private Path path;

    public static ControllerProfiles load(Path configDir) {
        ControllerProfiles store = new ControllerProfiles();
        store.path = configDir.resolve(FILE_NAME);
        if (Files.exists(store.path)) {
            Properties p = new Properties();
            try (Reader reader = Files.newBufferedReader(store.path, StandardCharsets.UTF_8)) {
                p.load(reader);
                for (int i = 0; i < 256; i++) {
                    String name = p.getProperty(i + ".name");
                    if (name == null) {
                        continue;
                    }
                    ControllerProfile profile = read(p, i + ".", name);
                    store.profiles.put(name, profile);
                }
            } catch (IOException | RuntimeException e) {
                FpvDroneClient.LOGGER.warn("[fpvdrone] Не удалось прочитать {}", store.path, e);
            }
        }
        return store;
    }

    /** Профиль для устройства; если такого ещё нет — создаётся по умолчанию (и сразу сохраняется). */
    public ControllerProfile get(String deviceName) {
        String key = deviceName == null ? "" : deviceName;
        ControllerProfile profile = this.profiles.get(key);
        if (profile == null) {
            profile = new ControllerProfile(key);
            this.profiles.put(key, profile);
            this.save();
        }
        return profile;
    }

    public int count() {
        return this.profiles.size();
    }

    public void save() {
        if (this.path == null) {
            return;
        }
        Properties p = new Properties();
        int i = 0;
        for (ControllerProfile profile : this.profiles.values()) {
            profile.clamp();
            write(p, i + ".", profile);
            i++;
        }
        try {
            Files.createDirectories(this.path.getParent());
            try (Writer writer = Files.newBufferedWriter(this.path, StandardCharsets.UTF_8)) {
                p.store(writer, "FPV Drone controller profiles (one per controller)");
            }
        } catch (IOException e) {
            FpvDroneClient.LOGGER.warn("[fpvdrone] Не удалось сохранить {}", this.path, e);
        }
    }

    private static void write(Properties p, String prefix, ControllerProfile c) {
        p.setProperty(prefix + "name", c.name);
        p.setProperty(prefix + "axisRoll", Integer.toString(c.axisRoll));
        p.setProperty(prefix + "axisPitch", Integer.toString(c.axisPitch));
        p.setProperty(prefix + "axisThrottle", Integer.toString(c.axisThrottle));
        p.setProperty(prefix + "axisYaw", Integer.toString(c.axisYaw));
        p.setProperty(prefix + "invertRoll", Boolean.toString(c.invertRoll));
        p.setProperty(prefix + "invertPitch", Boolean.toString(c.invertPitch));
        p.setProperty(prefix + "invertThrottle", Boolean.toString(c.invertThrottle));
        p.setProperty(prefix + "invertYaw", Boolean.toString(c.invertYaw));
        p.setProperty(prefix + "deadzone", fmt(c.deadzone));
        p.setProperty(prefix + "axisMin", join(c.axisMin));
        p.setProperty(prefix + "axisMax", join(c.axisMax));
        p.setProperty(prefix + "armSource", c.armSource.name());
        p.setProperty(prefix + "armIndex", Integer.toString(c.armIndex));
        p.setProperty(prefix + "armThreshold", fmt(c.armThreshold));
        p.setProperty(prefix + "armInvert", Boolean.toString(c.armInvert));
        p.setProperty(prefix + "exitSource", c.exitSource.name());
        p.setProperty(prefix + "exitIndex", Integer.toString(c.exitIndex));
        p.setProperty(prefix + "exitThreshold", fmt(c.exitThreshold));
        p.setProperty(prefix + "exitInvert", Boolean.toString(c.exitInvert));
    }

    private static ControllerProfile read(Properties p, String prefix, String name) {
        ControllerProfile c = new ControllerProfile(name);
        c.axisRoll = readInt(p, prefix + "axisRoll", c.axisRoll);
        c.axisPitch = readInt(p, prefix + "axisPitch", c.axisPitch);
        c.axisThrottle = readInt(p, prefix + "axisThrottle", c.axisThrottle);
        c.axisYaw = readInt(p, prefix + "axisYaw", c.axisYaw);
        c.invertRoll = readBool(p, prefix + "invertRoll", c.invertRoll);
        c.invertPitch = readBool(p, prefix + "invertPitch", c.invertPitch);
        c.invertThrottle = readBool(p, prefix + "invertThrottle", c.invertThrottle);
        c.invertYaw = readBool(p, prefix + "invertYaw", c.invertYaw);
        c.deadzone = readFloat(p, prefix + "deadzone", c.deadzone);
        readArray(p.getProperty(prefix + "axisMin"), c.axisMin);
        readArray(p.getProperty(prefix + "axisMax"), c.axisMax);
        c.armSource = readSource(p, prefix + "armSource", c.armSource);
        c.armIndex = readInt(p, prefix + "armIndex", c.armIndex);
        c.armThreshold = readFloat(p, prefix + "armThreshold", c.armThreshold);
        c.armInvert = readBool(p, prefix + "armInvert", c.armInvert);
        c.exitSource = readSource(p, prefix + "exitSource", c.exitSource);
        c.exitIndex = readInt(p, prefix + "exitIndex", c.exitIndex);
        c.exitThreshold = readFloat(p, prefix + "exitThreshold", c.exitThreshold);
        c.exitInvert = readBool(p, prefix + "exitInvert", c.exitInvert);
        c.clamp();
        return c;
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String join(float[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(fmt(values[i]));
        }
        return sb.toString();
    }

    private static void readArray(String raw, float[] out) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length && i < out.length; i++) {
            try {
                out[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException ignored) {
                // оставляем значение по умолчанию
            }
        }
    }

    private static int readInt(Properties p, String key, int def) {
        try {
            String v = p.getProperty(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float readFloat(Properties p, String key, float def) {
        try {
            String v = p.getProperty(key);
            return v == null ? def : Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean readBool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static ControllerProfile.Source readSource(Properties p, String key, ControllerProfile.Source def) {
        String v = p.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return ControllerProfile.Source.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
