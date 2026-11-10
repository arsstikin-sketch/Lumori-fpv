package ru.fpvdrone.flight;

import org.joml.Quaternionf;

/**
 * Состояние симуляции дрона. Единицы: метры (= блоки), секунды, градусы/с.
 * Локальные оси дрона: +Z вперёд, +Y вверх, +X влево.
 */
public final class DroneState {
    public double x;
    public double y;
    public double z;
    public double prevX;
    public double prevY;
    public double prevZ;
    public double vx;
    public double vy;
    public double vz;
    public final Quaternionf orientation = new Quaternionf();
    public final Quaternionf prevOrientation = new Quaternionf();

    /** Угловые скорости в связанных осях, °/с. roll + = вправо, pitch + = нос вниз, yaw + = вправо. */
    public float rollRate;
    public float pitchRate;
    public float yawRate;

    /** Сглаженные стики (настройка «плавность управления»). */
    public float smoothRoll;
    public float smoothPitch;
    public float smoothYaw;
    public float smoothThrottle;
    /** Дрон лежит на земле (касание снизу на этом шаге). */
    public boolean onGround;

    /** Обороты моторов 0..1 (с инерцией). */
    public float motor;
    public boolean armed;
    /** Дрон разбит (рама или камера уничтожены, вода). */
    public boolean destroyed;
    public double simTime;

    /** Точка взлёта (для респавна). */
    public double launchX;
    public double launchY;
    public double launchZ;
    public float launchYaw;

    public void resetAt(double x, double y, double z, float minecraftYawDeg) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.prevX = x;
        this.prevY = y;
        this.prevZ = z;
        this.vx = 0.0;
        this.vy = 0.0;
        this.vz = 0.0;
        // Minecraft: yaw 0 = смотрит на +Z, 90 = на -X. Наш поворот вокруг +Y переводит +Z в (sin, 0, cos).
        this.orientation.identity().rotateY((float) Math.toRadians(-minecraftYawDeg));
        this.prevOrientation.set(this.orientation);
        this.rollRate = 0.0F;
        this.pitchRate = 0.0F;
        this.yawRate = 0.0F;
        this.motor = 0.0F;
        this.smoothRoll = 0.0F;
        this.smoothPitch = 0.0F;
        this.smoothYaw = 0.0F;
        this.smoothThrottle = 0.0F;
        this.onGround = false;
        this.armed = false;
        this.destroyed = false;
        this.launchX = x;
        this.launchY = y;
        this.launchZ = z;
        this.launchYaw = minecraftYawDeg;
    }

    public double speed() {
        return Math.sqrt(this.vx * this.vx + this.vy * this.vy + this.vz * this.vz);
    }

    public void storePrevious() {
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
        this.prevOrientation.set(this.orientation);
    }
}
