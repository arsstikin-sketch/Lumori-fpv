package ru.fpvdrone.flight;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.fpvdrone.FpvDroneClient;

/**
 * Поворачивает камеру Minecraft по ориентации дрона, включая крен.
 * Сначала пробует Camera.setRotation(yaw, pitch, roll); если такого метода нет —
 * напрямую выставляет поля камеры. Оба пути ищутся рефлексией один раз.
 */
public final class CameraApplier {
    private static final Method SET_ROTATION_3 = method("setRotation", float.class, float.class, float.class);
    private static final Field F_ROTATION = field("rotation");
    private static final Field F_FORWARDS = field("forwards");
    private static final Field F_UP = field("up");
    private static final Field F_LEFT = field("left");
    private static final Field F_XROT = field("xRot");
    private static final Field F_YROT = field("yRot");
    private static final Field F_DIRTY = field("matrixPropertiesDirty");
    /** Поворот на 180° вокруг Y: камера Minecraft смотрит вдоль -Z, а нос дрона — вдоль +Z. */
    private static final Quaternionf FLIP = new Quaternionf().rotateY((float) Math.PI);

    private static boolean warned;

    private CameraApplier() {
    }

    /** @param droneCamera ориентация камеры дрона (локальные оси дрона: +Z вперёд, +Y вверх, +X влево) */
    public static void apply(Camera camera, Quaternionf droneCamera) {
        Quaternionf mc = new Quaternionf(droneCamera).mul(FLIP);
        // Minecraft: rotation = rotationYXZ(PI - yaw, -pitch, -roll)
        Vector3f euler = new Vector3f();
        mc.getEulerAnglesYXZ(euler);
        float yawDeg = (float) Math.toDegrees(Math.PI - euler.y);
        float pitchDeg = (float) Math.toDegrees(-euler.x);
        float rollDeg = (float) Math.toDegrees(-euler.z);

        if (SET_ROTATION_3 != null) {
            try {
                SET_ROTATION_3.invoke(camera, yawDeg, pitchDeg, rollDeg);
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                warnOnce(e);
            }
        }
        if (F_ROTATION != null && F_FORWARDS != null && F_UP != null && F_LEFT != null) {
            try {
                ((Quaternionf) F_ROTATION.get(camera)).set(mc);
                ((Vector3f) F_FORWARDS.get(camera)).set(0.0F, 0.0F, -1.0F).rotate(mc);
                ((Vector3f) F_UP.get(camera)).set(0.0F, 1.0F, 0.0F).rotate(mc);
                ((Vector3f) F_LEFT.get(camera)).set(-1.0F, 0.0F, 0.0F).rotate(mc);
                if (F_XROT != null) {
                    F_XROT.setFloat(camera, pitchDeg);
                }
                if (F_YROT != null) {
                    F_YROT.setFloat(camera, yawDeg);
                }
                if (F_DIRTY != null) {
                    F_DIRTY.setInt(camera, F_DIRTY.getInt(camera) | 3);
                }
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                warnOnce(e);
            }
        }
        warnOnce(new IllegalStateException("Не найден способ повернуть камеру"));
    }

    private static void warnOnce(Throwable t) {
        if (!warned) {
            warned = true;
            FpvDroneClient.LOGGER.warn("[fpvdrone] Проблема с поворотом камеры", t);
        }
    }

    private static Method method(String name, Class<?>... args) {
        try {
            Method m = Camera.class.getDeclaredMethod(name, args);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    private static Field field(String name) {
        try {
            Field f = Camera.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | RuntimeException e) {
            return null;
        }
    }
}
