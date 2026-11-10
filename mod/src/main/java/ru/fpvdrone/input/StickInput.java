package ru.fpvdrone.input;

/**
 * Превращает сырые оси/кнопки пульта в команды дрона по профилю этого пульта
 * (назначение осей, инверсия, калибровка, мёртвая зона).
 */
public final class StickInput {
    private StickInput() {
    }

    /**
     * @param throttle   0..1
     * @param yaw        -1..1 (вправо +)
     * @param pitch      -1..1 (стик от себя, нос вниз +)
     * @param roll       -1..1 (вправо +)
     * @param armSwitch  положение тумблера арма; null — арм с пульта не настроен
     * @param exitSwitch нажата кнопка выхода; null — не настроена
     */
    public record Sticks(boolean connected, String controllerName, float throttle, float yaw, float pitch, float roll,
                         Boolean armSwitch, Boolean exitSwitch) {
        public static final Sticks NONE = new Sticks(false, "", 0.0F, 0.0F, 0.0F, 0.0F, null, null);
    }

    public static Sticks read(String preferredController, ControllerProfiles profiles) {
        Joysticks.Device device = Joysticks.pick(preferredController);
        if (device == null) {
            return Sticks.NONE;
        }
        Joysticks.Snapshot snap = Joysticks.read(device);
        if (snap == null) {
            return Sticks.NONE;
        }
        return map(profiles.get(device.name()), snap);
    }

    public static Sticks map(ControllerProfile p, Joysticks.Snapshot snap) {
        float throttleRaw = calibrated(snap.axes(), p.axisThrottle, p);
        if (p.invertThrottle) {
            throttleRaw = -throttleRaw;
        }
        float throttle = Math.max(0.0F, Math.min(1.0F, (throttleRaw + 1.0F) * 0.5F));
        float yaw = centered(snap.axes(), p.axisYaw, p, p.invertYaw);
        float pitch = centered(snap.axes(), p.axisPitch, p, p.invertPitch);
        float roll = centered(snap.axes(), p.axisRoll, p, p.invertRoll);
        Boolean arm = channel(snap, p, p.armSource, p.armIndex, p.armThreshold, p.armInvert);
        Boolean exit = channel(snap, p, p.exitSource, p.exitIndex, p.exitThreshold, p.exitInvert);
        return new Sticks(true, snap.device().name(), throttle, yaw, pitch, roll, arm, exit);
    }

    /** Значение оси после калибровки, -1..1. */
    public static float calibrated(float[] axes, int index, ControllerProfile p) {
        if (index < 0 || index >= axes.length) {
            return 0.0F;
        }
        float lo = index < ControllerProfile.MAX_AXES ? p.axisMin[index] : -1.0F;
        float hi = index < ControllerProfile.MAX_AXES ? p.axisMax[index] : 1.0F;
        if (hi - lo < 0.05F) {
            lo = -1.0F;
            hi = 1.0F;
        }
        float v = (axes[index] - lo) / (hi - lo) * 2.0F - 1.0F;
        return Math.max(-1.0F, Math.min(1.0F, v));
    }

    private static float centered(float[] axes, int index, ControllerProfile p, boolean invert) {
        float v = calibrated(axes, index, p);
        if (invert) {
            v = -v;
        }
        float a = Math.abs(v);
        if (a <= p.deadzone) {
            return 0.0F;
        }
        return Math.signum(v) * (a - p.deadzone) / (1.0F - p.deadzone);
    }

    private static Boolean channel(Joysticks.Snapshot snap, ControllerProfile p, ControllerProfile.Source source, int index,
                                   float threshold, boolean invert) {
        boolean value;
        switch (source) {
            case BUTTON -> {
                if (index >= snap.buttons().length) {
                    return null;
                }
                value = snap.buttons()[index];
            }
            case AXIS -> {
                if (index >= snap.axes().length) {
                    return null;
                }
                value = calibrated(snap.axes(), index, p) > threshold;
            }
            default -> {
                return null;
            }
        }
        return invert != value;
    }
}
