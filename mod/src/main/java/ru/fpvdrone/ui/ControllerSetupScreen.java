package ru.fpvdrone.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.fpvdrone.DroneSettings;
import ru.fpvdrone.input.ControllerProfile;
import ru.fpvdrone.FpvDroneClient;
import ru.fpvdrone.input.Joysticks;
import ru.fpvdrone.input.StickInput;

/**
 * Настройка пульта: живые значения всех осей и кнопок, назначение каналов «подвигай стиком»,
 * арм/выход с кнопки или тумблера, калибровка.
 */
public final class ControllerSetupScreen extends Screen {
    private static final int MAX_AXES_SHOWN = 10;
    private static final long CAPTURE_TIMEOUT_NANOS = 7_000_000_000L;

    private enum Target {
        THROTTLE("Газ"), YAW("Рыскание"), PITCH("Тангаж"), ROLL("Крен"), ARM("Арм"), EXIT("Выход");

        final String ru;

        Target(String ru) {
            this.ru = ru;
        }
    }

    private final Screen parent;
    private final DroneSettings cfg = FpvDroneClient.SETTINGS;

    private Button controllerButton;
    private final Button[] assignButtons = new Button[Target.values().length];
    private final Button[] invertButtons = new Button[Target.values().length];
    private Button calibrateButton;
    private final List<StringWidget> axisLines = new ArrayList<>();
    private StringWidget buttonsLine;
    private StringWidget previewLine;
    private StringWidget statusLine;

    private Target capture;
    private long captureStart;
    private float[] captureAxesBase;
    private boolean[] captureButtonsBase;

    /** Найденная при назначении ось, у которой сейчас записываются крайние положения (-1 = нет). */
    private int axisSession = -1;
    private long axisSessionStart;
    private float axisSessionMin;
    private float axisSessionMax;
    private boolean axisSessionNegative;

    private boolean calibrating;
    private float[] calMin;
    private float[] calMax;

    /** Профиль выбранного сейчас пульта (у каждого пульта свой). */
    private ControllerProfile profile() {
        Joysticks.Device device = Joysticks.pick(this.cfg.controllerName);
        return FpvDroneClient.PROFILES.get(device == null ? "" : device.name());
    }

    private void saveProfile() {
        FpvDroneClient.PROFILES.save();
    }

    public ControllerSetupScreen(Screen parent) {
        super(Component.literal("FPV Drone — настройка пульта"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.axisLines.clear();
        int cx = this.width / 2;
        int leftX = cx - 300;
        int rightX = cx + 8;

        this.addRenderableWidget(new StringWidget(cx - 150, 6, 300, 12, this.title, this.font));
        this.controllerButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.cycleController())
            .bounds(cx - 150, 20, 300, 20).build());

        for (int i = 0; i < MAX_AXES_SHOWN; i++) {
            this.axisLines.add(this.addRenderableWidget(new StringWidget(leftX, 48 + i * 12, 290, 11, Component.empty(), this.font)));
        }
        this.buttonsLine = this.addRenderableWidget(new StringWidget(leftX, 48 + MAX_AXES_SHOWN * 12 + 4, 290, 11, Component.empty(), this.font));

        int y = 46;
        for (Target t : Target.values()) {
            final Target target = t;
            this.addRenderableWidget(new StringWidget(rightX, y + 5, 60, 11, Component.literal(t.ru), this.font));
            this.assignButtons[t.ordinal()] = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.startCapture(target))
                .bounds(rightX + 62, y, 130, 20).build());
            this.invertButtons[t.ordinal()] = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.toggleInvert(target))
                .bounds(rightX + 196, y, 64, 20).build());
            if (t == Target.ARM || t == Target.EXIT) {
                this.addRenderableWidget(Button.builder(Component.literal("✕"), b -> this.clearChannel(target))
                    .bounds(rightX + 264, y, 20, 20).build());
            }
            y += 22;
        }
        this.calibrateButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.toggleCalibration())
            .bounds(rightX + 62, y + 4, 198, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Сбросить калибровку"), b -> {
            this.profile().clearCalibration();
            this.saveProfile();
            this.status("Калибровка сброшена");
        }).bounds(rightX + 62, y + 26, 198, 20).build());

        this.previewLine = this.addRenderableWidget(new StringWidget(cx - 300, this.height - 58, 600, 11, Component.empty(), this.font));
        this.statusLine = this.addRenderableWidget(new StringWidget(cx - 300, this.height - 44, 600, 11, Component.empty(), this.font));
        this.addRenderableWidget(Button.builder(Component.literal("Готово"), b -> this.onClose())
            .bounds(cx - 75, this.height - 28, 150, 20).build());
        this.refreshLabels();
        this.status("Нажми «назначить» и подвигай нужным стиком / переключи тумблер");
    }

    // ------------------------------------------------------------------ действия

    private void cycleController() {
        List<Joysticks.Device> devices = Joysticks.devices();
        if (devices.isEmpty()) {
            this.status("Пульт не найден. Подключи по USB (режим джойстика) и подожди пару секунд.");
            return;
        }
        int index = 0;
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).name().equals(this.cfg.controllerName)) {
                index = (i + 1) % devices.size();
                break;
            }
        }
        this.cfg.controllerName = devices.get(index).name();
        this.cfg.save();
        this.capture = null;
        this.axisSession = -1;
        this.calibrating = false;
        this.refreshLabels();
        this.status("Выбран «" + this.cfg.controllerName + "». У этого пульта свои назначения и калибровка.");
    }

    private void startCapture(Target target) {
        Joysticks.Snapshot snap = this.snapshot();
        if (snap == null) {
            this.status("Пульт не найден");
            return;
        }
        this.capture = target;
        this.axisSession = -1;
        this.captureStart = System.nanoTime();
        this.captureAxesBase = snap.axes().clone();
        this.captureButtonsBase = snap.buttons().clone();
        if (target == Target.ARM || target == Target.EXIT) {
            this.status(target.ru + ": переключи тумблер или нажми кнопку на пульте…");
        } else {
            this.status(target.ru + ": подвигай нужным стиком" + (target == Target.THROTTLE ? " (сначала ВВЕРХ)" : "") + ", потом поводи до упора в обе стороны");
        }
        this.refreshLabels();
    }

    private void toggleInvert(Target t) {
        switch (t) {
            case THROTTLE -> this.profile().invertThrottle = !this.profile().invertThrottle;
            case YAW -> this.profile().invertYaw = !this.profile().invertYaw;
            case PITCH -> this.profile().invertPitch = !this.profile().invertPitch;
            case ROLL -> this.profile().invertRoll = !this.profile().invertRoll;
            case ARM -> this.profile().armInvert = !this.profile().armInvert;
            case EXIT -> this.profile().exitInvert = !this.profile().exitInvert;
        }
        this.saveProfile();
        this.refreshLabels();
    }

    private void clearChannel(Target t) {
        if (t == Target.ARM) {
            this.profile().armSource = ControllerProfile.Source.NONE;
        } else if (t == Target.EXIT) {
            this.profile().exitSource = ControllerProfile.Source.NONE;
        }
        this.saveProfile();
        this.refreshLabels();
    }

    private void toggleCalibration() {
        Joysticks.Snapshot snap = this.snapshot();
        if (!this.calibrating) {
            if (snap == null) {
                this.status("Пульт не найден");
                return;
            }
            this.calibrating = true;
            this.calMin = snap.axes().clone();
            this.calMax = snap.axes().clone();
            this.status("Калибровка: поводи ВСЕ стики по кругу до упора, потом нажми «Готово» на этой кнопке");
        } else {
            this.calibrating = false;
            if (this.calMin != null) {
                ControllerProfile profile = this.profile();
                int changed = 0;
                for (int i = 0; i < this.calMin.length && i < ControllerProfile.MAX_AXES; i++) {
                    if (this.calMax[i] - this.calMin[i] > 0.2F) {
                        profile.axisMin[i] = this.calMin[i];
                        profile.axisMax[i] = this.calMax[i];
                        changed++;
                    }
                }
                this.saveProfile();
                this.status("Калибровка сохранена (осей: " + changed + ")");
            }
        }
        this.refreshLabels();
    }

    private Joysticks.Snapshot snapshot() {
        Joysticks.Device device = Joysticks.pick(this.cfg.controllerName);
        return device == null ? null : Joysticks.read(device);
    }

    // ------------------------------------------------------------------ тик

    @Override
    public void tick() {
        super.tick();
        Joysticks.Snapshot snap = this.snapshot();
        this.updateLive(snap);
        if (snap == null) {
            return;
        }
        if (this.calibrating) {
            for (int i = 0; i < snap.axes().length && i < this.calMin.length; i++) {
                this.calMin[i] = Math.min(this.calMin[i], snap.axes()[i]);
                this.calMax[i] = Math.max(this.calMax[i], snap.axes()[i]);
            }
        }
        if (this.capture != null && this.axisSession >= 0) {
            this.tickAxisSession(snap);
        } else if (this.capture != null) {
            this.tickCapture(snap);
        }
    }

    private void tickCapture(Joysticks.Snapshot snap) {
        if (System.nanoTime() - this.captureStart > CAPTURE_TIMEOUT_NANOS) {
            this.status(this.capture.ru + ": ничего не нажато, отменено");
            this.capture = null;
            this.refreshLabels();
            return;
        }
        Target t = this.capture;
        boolean switchTarget = t == Target.ARM || t == Target.EXIT;
        if (switchTarget) {
            for (int i = 0; i < snap.buttons().length && i < this.captureButtonsBase.length; i++) {
                if (snap.buttons()[i] != this.captureButtonsBase[i]) {
                    // Кнопка включилась -> «нажата = вкл». Тумблер выключился -> инверсия.
                    boolean invert = !snap.buttons()[i];
                    this.assignSwitch(t, ControllerProfile.Source.BUTTON, i, 0.5F, invert);
                    return;
                }
            }
        }
        int bestAxis = -1;
        float bestDelta = 0.0F;
        for (int i = 0; i < snap.axes().length && i < this.captureAxesBase.length; i++) {
            float delta = Math.abs(snap.axes()[i] - this.captureAxesBase[i]);
            if (delta > bestDelta) {
                bestDelta = delta;
                bestAxis = i;
            }
        }
        float need = switchTarget ? 0.6F : 0.5F;
        if (bestAxis < 0 || bestDelta < need) {
            return;
        }
        if (switchTarget) {
            float base = StickInput.calibrated(this.captureAxesBase, bestAxis, this.profile());
            float now = StickInput.calibrated(snap.axes(), bestAxis, this.profile());
            float threshold = (base + now) * 0.5F;
            boolean invert = now < base; // включение идёт вниз
            if (invert) {
                // Храним «вкл = выше порога», а инверсия переворачивает.
                this.assignSwitch(t, ControllerProfile.Source.AXIS, bestAxis, threshold, true);
            } else {
                this.assignSwitch(t, ControllerProfile.Source.AXIS, bestAxis, threshold, false);
            }
        } else {
            // Как в FPV Freecam: нашли ось — 2,5 секунды записываем её крайние положения (калибровка этой оси).
            this.axisSession = bestAxis;
            this.axisSessionStart = System.nanoTime();
            this.axisSessionMin = Math.min(this.captureAxesBase[bestAxis], snap.axes()[bestAxis]);
            this.axisSessionMax = Math.max(this.captureAxesBase[bestAxis], snap.axes()[bestAxis]);
            this.axisSessionNegative = snap.axes()[bestAxis] < this.captureAxesBase[bestAxis];
            this.refreshLabels();
        }
    }

    /** Запись крайних положений найденной оси; через 2,5 с назначаем ось вместе с калибровкой. */
    private void tickAxisSession(Joysticks.Snapshot snap) {
        int axis = this.axisSession;
        if (axis >= snap.axes().length) {
            this.axisSession = -1;
            return;
        }
        float v = snap.axes()[axis];
        this.axisSessionMin = Math.min(this.axisSessionMin, v);
        this.axisSessionMax = Math.max(this.axisSessionMax, v);
        double elapsed = (System.nanoTime() - this.axisSessionStart) / 1.0E9;
        float range = this.axisSessionMax - this.axisSessionMin;
        Target t = this.capture;
        if (elapsed < 2.5 || range < 0.5F) {
            this.status(String.format(java.util.Locale.ROOT, "%s: ось %d — води стиком до упора в обе стороны… %.1f с (размах %.0f%%)",
                t.ru, axis, Math.max(0.0, 2.5 - elapsed), range * 50.0F));
            return;
        }
        ControllerProfile p = this.profile();
        switch (t) {
            case THROTTLE -> {
                p.axisThrottle = axis;
                // Газ сначала двигали вверх: если ось при этом уменьшилась — инвертируем.
                p.invertThrottle = this.axisSessionNegative;
            }
            case YAW -> p.axisYaw = axis;
            case PITCH -> p.axisPitch = axis;
            case ROLL -> p.axisRoll = axis;
            default -> {
            }
        }
        if (axis < ControllerProfile.MAX_AXES) {
            p.axisMin[axis] = this.axisSessionMin;
            p.axisMax[axis] = this.axisSessionMax;
        }
        this.status(t.ru + " = ось " + axis + ", откалибрована (" + String.format(java.util.Locale.ROOT, "%.2f…%.2f", this.axisSessionMin, this.axisSessionMax) + ")");
        this.axisSession = -1;
        this.capture = null;
        this.saveProfile();
        this.refreshLabels();
    }

    private void assignSwitch(Target t, ControllerProfile.Source source, int index, float threshold, boolean invert) {
        if (t == Target.ARM) {
            this.profile().armSource = source;
            this.profile().armIndex = index;
            this.profile().armThreshold = threshold;
            this.profile().armInvert = invert;
        } else {
            this.profile().exitSource = source;
            this.profile().exitIndex = index;
            this.profile().exitThreshold = threshold;
            this.profile().exitInvert = invert;
        }
        this.saveProfile();
        this.status(t.ru + " = " + (source == ControllerProfile.Source.BUTTON ? "кнопка " : "ось ") + index
            + ". Сейчас положение = ВКЛ.");
        this.capture = null;
        this.refreshLabels();
    }

    // ------------------------------------------------------------------ отображение

    private void updateLive(Joysticks.Snapshot snap) {
        if (snap == null) {
            for (StringWidget line : this.axisLines) {
                line.setMessage(Component.empty());
            }
            this.axisLines.get(0).setMessage(Component.literal("Пульт не подключён"));
            this.buttonsLine.setMessage(Component.empty());
            this.previewLine.setMessage(Component.empty());
            return;
        }
        ControllerProfile profile = this.profile();
        for (int i = 0; i < this.axisLines.size(); i++) {
            if (i >= snap.axes().length) {
                this.axisLines.get(i).setMessage(Component.empty());
                continue;
            }
            float v = StickInput.calibrated(snap.axes(), i, profile);
            this.axisLines.get(i).setMessage(Component.literal(
                String.format(Locale.ROOT, "Ось %d %s %+.2f%s", i, bar(v), v, this.usage(i))));
        }
        StringBuilder sb = new StringBuilder("Кнопки:");
        boolean any = false;
        for (int i = 0; i < snap.buttons().length; i++) {
            if (snap.buttons()[i]) {
                sb.append(' ').append(i);
                any = true;
            }
        }
        if (!any) {
            sb.append(snap.buttons().length == 0 ? " нет" : " (ничего не нажато, всего " + snap.buttons().length + ")");
        }
        this.buttonsLine.setMessage(Component.literal(sb.toString()));

        StickInput.Sticks st = StickInput.map(profile, snap);
        String arm = st.armSwitch() == null ? "клавиша " + ru.fpvdrone.ModKeys.label(ru.fpvdrone.ModKeys.ARM) : (st.armSwitch() ? "ВКЛ" : "выкл");
        this.previewLine.setMessage(Component.literal(String.format(Locale.ROOT,
            "Газ %3d%%   Рыск %+.2f   Тангаж %+.2f   Крен %+.2f   Арм: %s",
            Math.round(st.throttle() * 100.0F), st.yaw(), st.pitch(), st.roll(), arm)));
    }

    private String usage(int axis) {
        StringBuilder sb = new StringBuilder();
        if (axis == this.profile().axisThrottle) {
            sb.append(" газ");
        }
        if (axis == this.profile().axisYaw) {
            sb.append(" рыск");
        }
        if (axis == this.profile().axisPitch) {
            sb.append(" тангаж");
        }
        if (axis == this.profile().axisRoll) {
            sb.append(" крен");
        }
        if (this.profile().armSource == ControllerProfile.Source.AXIS && axis == this.profile().armIndex) {
            sb.append(" арм");
        }
        if (this.profile().exitSource == ControllerProfile.Source.AXIS && axis == this.profile().exitIndex) {
            sb.append(" выход");
        }
        return sb.length() == 0 ? "" : "  ←" + sb;
    }

    private static String bar(float v) {
        int cells = 16;
        int pos = Math.round((v * 0.5F + 0.5F) * (cells - 1));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cells; i++) {
            sb.append(i == pos ? '#' : (i == cells / 2 ? '+' : '-'));
        }
        return sb.append(']').toString();
    }

    private void refreshLabels() {
        Joysticks.Device device = Joysticks.pick(this.cfg.controllerName);
        this.controllerButton.setMessage(Component.literal("Пульт: " + (device == null ? "не найден" : device.name()) + "  (сменить)"));
        for (Target t : Target.values()) {
            this.assignButtons[t.ordinal()].setMessage(Component.literal(this.capture == t ? "> жду… <" : this.channelLabel(t)));
            this.invertButtons[t.ordinal()].setMessage(Component.literal(this.inverted(t) ? "Инв: да" : "Инв: нет"));
        }
        this.calibrateButton.setMessage(Component.literal(this.calibrating ? "Калибровка всех осей: ГОТОВО" : "Калибровка всех осей"));
    }

    private String channelLabel(Target t) {
        return switch (t) {
            case THROTTLE -> "Ось " + this.profile().axisThrottle;
            case YAW -> "Ось " + this.profile().axisYaw;
            case PITCH -> "Ось " + this.profile().axisPitch;
            case ROLL -> "Ось " + this.profile().axisRoll;
            case ARM -> sourceLabel(this.profile().armSource, this.profile().armIndex);
            case EXIT -> sourceLabel(this.profile().exitSource, this.profile().exitIndex);
        };
    }

    private static String sourceLabel(ControllerProfile.Source source, int index) {
        return switch (source) {
            case NONE -> "Нет (назначить)";
            case BUTTON -> "Кнопка " + index;
            case AXIS -> "Тумблер-ось " + index;
        };
    }

    private boolean inverted(Target t) {
        return switch (t) {
            case THROTTLE -> this.profile().invertThrottle;
            case YAW -> this.profile().invertYaw;
            case PITCH -> this.profile().invertPitch;
            case ROLL -> this.profile().invertRoll;
            case ARM -> this.profile().armInvert;
            case EXIT -> this.profile().exitInvert;
        };
    }

    private void status(String text) {
        if (this.statusLine != null) {
            this.statusLine.setMessage(Component.literal(text));
        }
    }

    @Override
    public void onClose() {
        this.cfg.save();
        this.saveProfile();
        this.minecraft.gui.setScreen(this.parent);
    }
}
