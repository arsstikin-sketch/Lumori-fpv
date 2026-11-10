package ru.fpvdrone;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Общие настройки мода (физика, рейты, поломки, экран, цвета). Файл config/fpvdrone.properties.
 * Настройки конкретного пульта — в {@link ru.fpvdrone.input.ControllerProfiles}.
 * Клавиши — обычные клавиши Minecraft (Настройки → Управление → Клавиши → FPV Drone).
 * Каждое public-поле читается и пишется автоматически.
 */
public final class DroneSettings {
    private static final String FILE_NAME = "fpvdrone.properties";

    // ------------------------------------------------------------------ пульт (общее)
    /** Выбранный пульт (имя устройства). Пусто = первый найденный. */
    public String controllerName = "";
    public boolean armRequiresLowThrottle = true;
    public boolean armSwitchLaunches = true;

    // ------------------------------------------------------------------ рейты (Betaflight)
    public float rcRateRollPitch = 1.0F;
    public float superRateRollPitch = 0.70F;
    public float expoRollPitch = 0.0F;
    public float rcRateYaw = 1.0F;
    public float superRateYaw = 0.70F;
    public float expoYaw = 0.0F;
    public float throttleExpo = 0.0F;
    /** Ограничение газа сверху (как throttle limit в Betaflight). */
    public float throttleLimit = 1.0F;
    public float idleThrottle = 0.05F;
    /** Интенсивность управления: общий множитель скорости вращения (1 = как в рейтах). */
    public float controlIntensity = 1.0F;
    /** Плавность управления: сглаживание стиков (0 = мгновенно, 1 = очень плавно). */
    public float controlSmoothing = 0.0F;

    // ------------------------------------------------------------------ физика дрона
    public DronePreset preset = DronePreset.RACE_5;
    public float thrustToWeight = 7.5F;
    /** Нелинейность тяги: 0 = тяга пропорциональна газу, 1 = квадрату газа. */
    public float thrustCurve = 0.8F;
    public float dragQuadratic = 0.045F;
    public float dragLinear = 0.10F;
    /** Множитель сопротивления, когда дрон летит «плашмя» (брюхом вперёд). */
    public float bellyDrag = 2.0F;
    public float motorResponse = 0.03F;
    public float rateResponse = 0.025F;
    /** Затухание вращения после дизарма (1/с). */
    public float tumbleDamping = 1.5F;
    public float gravity = 9.81F;

    // ------------------------------------------------------------------ камера
    public float cameraAngle = 25.0F;

    // ------------------------------------------------------------------ поломки
    public float durability = 1.0F;
    public float bumpSpeed = 2.0F;
    public float propBreakSpeed = 7.0F;
    public float armBreakSpeed = 16.0F;
    public float frameBreakSpeed = 30.0F;
    public float bounciness = 0.3F;
    public CrashAction crashAction = CrashAction.RESPAWN;
    public boolean liquidsDestroy = true;

    // ------------------------------------------------------------------ внешний вид (видят другие)
    public DroneColor colorFrame = DroneColor.BLACK;
    public DroneColor colorArms = DroneColor.BLACK;
    public DroneColor colorProps = DroneColor.RED;
    public DroneColor colorMotors = DroneColor.LIGHT_GRAY;
    public DroneColor colorCamera = DroneColor.GRAY;
    public DroneColor colorBattery = DroneColor.YELLOW;
    public DroneColor colorLed = DroneColor.LIME;

    // ------------------------------------------------------------------ экран
    public boolean showOsd = true;
    public boolean showDamagePanel = true;
    public boolean showSticks = true;
    public boolean hideVanillaHud = true;
    public boolean showCompass = true;
    /** Громкость моторов (0 = выкл). */
    public float motorVolume = 0.8F;

    // ------------------------------------------------------------------ дальность
    public float localMaxRange = 0.0F;

    private transient Path path;
    private transient boolean dirty;
    private transient long dirtySinceNanos;

    public enum CrashAction {
        EXIT, RESPAWN
    }

    /** 16 цветов Minecraft (порядок совпадает с плагином и серверной частью мода). */
    public enum DroneColor {
        WHITE, LIGHT_GRAY, GRAY, BLACK, BROWN, RED, ORANGE, YELLOW,
        LIME, GREEN, CYAN, LIGHT_BLUE, BLUE, PURPLE, MAGENTA, PINK;

        public String ru() {
            return switch (this) {
                case WHITE -> "белый";
                case LIGHT_GRAY -> "светло-серый";
                case GRAY -> "серый";
                case BLACK -> "чёрный";
                case BROWN -> "коричневый";
                case RED -> "красный";
                case ORANGE -> "оранжевый";
                case YELLOW -> "жёлтый";
                case LIME -> "лаймовый";
                case GREEN -> "зелёный";
                case CYAN -> "бирюзовый";
                case LIGHT_BLUE -> "голубой";
                case BLUE -> "синий";
                case PURPLE -> "фиолетовый";
                case MAGENTA -> "пурпурный";
                case PINK -> "розовый";
            };
        }
    }

    /** Готовые наборы физики. */
    public enum DronePreset {
        RACE_5("Гоночный 5\"", 9.0F, 0.85F, 0.040F, 0.08F, 0.020F, 0.020F),
        FREESTYLE_5("Фристайл 5\"", 7.5F, 0.80F, 0.045F, 0.10F, 0.030F, 0.025F),
        CINEWHOOP_3("Синевуп 3\"", 4.0F, 0.70F, 0.090F, 0.30F, 0.045F, 0.040F),
        TINYWHOOP("Тинивуп 65 мм", 3.0F, 0.60F, 0.200F, 0.80F, 0.025F, 0.030F),
        CUSTOM("Свой", 0, 0, 0, 0, 0, 0);

        public final String ru;
        final float tw;
        final float curve;
        final float dragQ;
        final float dragL;
        final float motor;
        final float rate;

        DronePreset(String ru, float tw, float curve, float dragQ, float dragL, float motor, float rate) {
            this.ru = ru;
            this.tw = tw;
            this.curve = curve;
            this.dragQ = dragQ;
            this.dragL = dragL;
            this.motor = motor;
            this.rate = rate;
        }
    }

    /** Применить пресет (кроме CUSTOM). */
    public void applyPreset(DronePreset p) {
        this.preset = p;
        if (p == DronePreset.CUSTOM) {
            return;
        }
        this.thrustToWeight = p.tw;
        this.thrustCurve = p.curve;
        this.dragQuadratic = p.dragQ;
        this.dragLinear = p.dragL;
        this.motorResponse = p.motor;
        this.rateResponse = p.rate;
    }

    public byte[] colorBytes() {
        return new byte[] {
            (byte) this.colorFrame.ordinal(), (byte) this.colorArms.ordinal(), (byte) this.colorProps.ordinal(),
            (byte) this.colorMotors.ordinal(), (byte) this.colorCamera.ordinal(), (byte) this.colorBattery.ordinal(),
            (byte) this.colorLed.ordinal()
        };
    }

    // ------------------------------------------------------------------ сохранение

    /** Отметить, что настройки изменились; запись на диск — через секунду (чтобы не писать на каждый сдвиг ползунка). */
    public void markDirty() {
        if (!this.dirty) {
            this.dirtySinceNanos = System.nanoTime();
        }
        this.dirty = true;
    }

    /** Вызывается каждый тик: сохраняет, если были изменения. @return true, если сохранили */
    public boolean saveIfDirty() {
        if (this.dirty && System.nanoTime() - this.dirtySinceNanos > 1_000_000_000L) {
            this.save();
            return true;
        }
        return false;
    }

    public static DroneSettings load(Path configDir) {
        DroneSettings s = new DroneSettings();
        s.path = configDir.resolve(FILE_NAME);
        if (Files.exists(s.path)) {
            Properties p = new Properties();
            try (Reader reader = Files.newBufferedReader(s.path, StandardCharsets.UTF_8)) {
                p.load(reader);
                for (Field field : DroneSettings.class.getFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    String raw = p.getProperty(field.getName());
                    if (raw != null) {
                        readField(s, field, raw.trim());
                    }
                }
            } catch (IOException | RuntimeException e) {
                FpvDroneClient.LOGGER.warn("[fpvdrone] Не удалось прочитать {}", s.path, e);
            }
        }
        s.clamp();
        s.save();
        return s;
    }

    public void save() {
        this.dirty = false;
        if (this.path == null) {
            return;
        }
        this.clamp();
        Properties p = new Properties();
        for (Field field : DroneSettings.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                Object value = field.get(this);
                if (value instanceof Float f) {
                    p.setProperty(field.getName(), String.format(Locale.ROOT, "%.4f", f));
                } else if (value instanceof Enum<?> e) {
                    p.setProperty(field.getName(), e.name());
                } else {
                    p.setProperty(field.getName(), String.valueOf(value));
                }
            } catch (IllegalAccessException ignored) {
                // поля public
            }
        }
        try {
            Files.createDirectories(this.path.getParent());
            try (Writer writer = Files.newBufferedWriter(this.path, StandardCharsets.UTF_8)) {
                p.store(writer, "FPV Drone settings");
            }
        } catch (IOException e) {
            FpvDroneClient.LOGGER.warn("[fpvdrone] Не удалось сохранить {}", this.path, e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void readField(DroneSettings target, Field field, String raw) {
        try {
            Class<?> type = field.getType();
            if (type == float.class) {
                field.setFloat(target, Float.parseFloat(raw));
            } else if (type == int.class) {
                field.setInt(target, Integer.parseInt(raw));
            } else if (type == boolean.class) {
                field.setBoolean(target, Boolean.parseBoolean(raw));
            } else if (type == String.class) {
                field.set(target, raw);
            } else if (type.isEnum()) {
                field.set(target, Enum.valueOf((Class<Enum>) type, raw.toUpperCase(Locale.ROOT)));
            }
        } catch (IllegalArgumentException | IllegalAccessException ignored) {
            // кривое значение — по умолчанию
        }
    }

    public void clamp() {
        DroneSettings d = DEFAULTS;
        if (this.controllerName == null) {
            this.controllerName = "";
        }
        if (this.crashAction == null) {
            this.crashAction = CrashAction.RESPAWN;
        }
        if (this.preset == null) {
            this.preset = DronePreset.CUSTOM;
        }
        for (Field field : DroneSettings.class.getFields()) {
            try {
                if (field.getType().isEnum() && field.get(this) == null && d != null) {
                    field.set(this, field.get(d));
                }
            } catch (IllegalAccessException ignored) {
                // public
            }
        }
        this.rcRateRollPitch = clamp(this.rcRateRollPitch, 0.1F, 2.55F);
        this.superRateRollPitch = clamp(this.superRateRollPitch, 0.0F, 0.95F);
        this.expoRollPitch = clamp(this.expoRollPitch, 0.0F, 1.0F);
        this.rcRateYaw = clamp(this.rcRateYaw, 0.1F, 2.55F);
        this.superRateYaw = clamp(this.superRateYaw, 0.0F, 0.95F);
        this.expoYaw = clamp(this.expoYaw, 0.0F, 1.0F);
        this.throttleExpo = clamp(this.throttleExpo, 0.0F, 1.0F);
        this.throttleLimit = clamp(this.throttleLimit, 0.2F, 1.0F);
        this.idleThrottle = clamp(this.idleThrottle, 0.0F, 0.2F);
        this.controlIntensity = clamp(this.controlIntensity, 0.3F, 2.0F);
        this.controlSmoothing = clamp(this.controlSmoothing, 0.0F, 1.0F);
        this.motorVolume = clamp(this.motorVolume, 0.0F, 1.0F);
        this.thrustToWeight = clamp(this.thrustToWeight, 1.5F, 15.0F);
        this.thrustCurve = clamp(this.thrustCurve, 0.0F, 1.0F);
        this.dragQuadratic = clamp(this.dragQuadratic, 0.0F, 0.3F);
        this.dragLinear = clamp(this.dragLinear, 0.0F, 2.0F);
        this.bellyDrag = clamp(this.bellyDrag, 1.0F, 5.0F);
        this.motorResponse = clamp(this.motorResponse, 0.005F, 0.3F);
        this.rateResponse = clamp(this.rateResponse, 0.005F, 0.3F);
        this.tumbleDamping = clamp(this.tumbleDamping, 0.0F, 10.0F);
        this.gravity = clamp(this.gravity, 0.0F, 30.0F);
        this.cameraAngle = clamp(this.cameraAngle, -20.0F, 80.0F);
        this.durability = clamp(this.durability, 0.25F, 4.0F);
        this.bumpSpeed = clamp(this.bumpSpeed, 0.5F, 6.0F);
        this.propBreakSpeed = clamp(this.propBreakSpeed, this.bumpSpeed + 0.5F, 25.0F);
        this.armBreakSpeed = clamp(this.armBreakSpeed, this.propBreakSpeed + 0.5F, 40.0F);
        this.frameBreakSpeed = clamp(this.frameBreakSpeed, this.armBreakSpeed + 0.5F, 60.0F);
        this.bounciness = clamp(this.bounciness, 0.0F, 0.8F);
        this.localMaxRange = clamp(this.localMaxRange, 0.0F, 1024.0F);
    }

    private static final DroneSettings DEFAULTS = new DroneSettings();

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
