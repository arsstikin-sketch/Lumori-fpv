package ru.fpvdrone.input;

import java.util.Arrays;

/**
 * Настройки одного конкретного пульта: какие оси за что отвечают, инверсия, калибровка, арм/выход.
 * У каждого пульта (по имени устройства) — свой профиль. Хранятся в config/fpvdrone-controllers.properties.
 */
public final class ControllerProfile {
    public static final int MAX_AXES = 32;

    public enum Source {
        NONE, BUTTON, AXIS
    }

    /** Имя устройства, как его сообщает система. */
    public String name = "";

    /** По умолчанию — типичный радиопульт (EdgeTX/OpenTX, режим джойстика): AETR. */
    public int axisRoll = 0;
    public int axisPitch = 1;
    public int axisThrottle = 2;
    public int axisYaw = 3;
    public boolean invertRoll = false;
    public boolean invertPitch = false;
    public boolean invertThrottle = false;
    public boolean invertYaw = false;
    public float deadzone = 0.02F;

    public final float[] axisMin = new float[MAX_AXES];
    public final float[] axisMax = new float[MAX_AXES];

    public Source armSource = Source.NONE;
    public int armIndex = 4;
    public float armThreshold = 0.5F;
    public boolean armInvert = false;

    public Source exitSource = Source.NONE;
    public int exitIndex = 5;
    public float exitThreshold = 0.5F;
    public boolean exitInvert = false;

    public ControllerProfile() {
        this.clearCalibration();
    }

    public ControllerProfile(String name) {
        this();
        this.name = name == null ? "" : name;
    }

    public void clearCalibration() {
        Arrays.fill(this.axisMin, -1.0F);
        Arrays.fill(this.axisMax, 1.0F);
    }

    public void clamp() {
        this.axisRoll = clampInt(this.axisRoll, 0, MAX_AXES - 1);
        this.axisPitch = clampInt(this.axisPitch, 0, MAX_AXES - 1);
        this.axisThrottle = clampInt(this.axisThrottle, 0, MAX_AXES - 1);
        this.axisYaw = clampInt(this.axisYaw, 0, MAX_AXES - 1);
        this.armIndex = clampInt(this.armIndex, 0, 127);
        this.exitIndex = clampInt(this.exitIndex, 0, 127);
        this.deadzone = Math.max(0.0F, Math.min(0.3F, this.deadzone));
        this.armThreshold = Math.max(-0.95F, Math.min(0.95F, this.armThreshold));
        this.exitThreshold = Math.max(-0.95F, Math.min(0.95F, this.exitThreshold));
        if (this.armSource == null) {
            this.armSource = Source.NONE;
        }
        if (this.exitSource == null) {
            this.exitSource = Source.NONE;
        }
        for (int i = 0; i < MAX_AXES; i++) {
            if (!(this.axisMax[i] - this.axisMin[i] > 0.05F)) {
                this.axisMin[i] = -1.0F;
                this.axisMax[i] = 1.0F;
            }
        }
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
