package ru.fpvdrone.net;

import java.nio.ByteBuffer;
import org.joml.Quaternionf;
import ru.fpvdrone.damage.DroneDamage;
import ru.fpvdrone.flight.DroneState;

/**
 * Формат пакетов (big-endian). Совпадает с плагином (ru.fpvdrone.plugin.DroneState).
 *
 * state (клиент -> сервер), версия 1:
 *   byte  version = 1
 *   byte  flags   (1 = в полёте, 2 = армлен, 4 = разбит)
 *   -- дальше только если флаг 1 --
 *   double x, y, z          позиция дрона
 *   float  qx, qy, qz, qw   ориентация (локальные оси: +Z вперёд, +Y вверх, +X влево)
 *   float  cameraAngleDeg   наклон камеры вверх
 *   float  throttle         обороты моторов 0..1
 *   float  speed            скорость, м/с
 *   byte[4] prop health 0..100 (FL, FR, RL, RR)
 *   byte[4] arm health 0..100
 *   byte   camera health 0..100
 *   byte   frame health 0..100
 *
 * hello  (клиент -> сервер): byte version
 * colors (клиент -> сервер): byte version, 7 байт — номера цветов (0..15) для:
 *        рама, лучи, пропеллеры, моторы, камера, батарея, подсветка
 * config (сервер -> клиент): byte version, float maxRange (0 = нет), byte flags
 */
public final class DroneProtocol {
    public static final byte VERSION = 1;
    public static final int FLAG_ACTIVE = 1;
    public static final int FLAG_ARMED = 2;
    public static final int FLAG_CRASHED = 4;

    private DroneProtocol() {
    }

    public static byte[] encodeInactive() {
        return new byte[] {VERSION, 0};
    }

    public static byte[] encodeState(DroneState state, DroneDamage damage, float cameraAngle) {
        ByteBuffer buf = ByteBuffer.allocate(64);
        int flags = FLAG_ACTIVE;
        if (state.armed) {
            flags |= FLAG_ARMED;
        }
        if (state.destroyed) {
            flags |= FLAG_CRASHED;
        }
        buf.put(VERSION);
        buf.put((byte) flags);
        buf.putDouble(state.x);
        buf.putDouble(state.y);
        buf.putDouble(state.z);
        Quaternionf q = state.orientation;
        buf.putFloat(q.x);
        buf.putFloat(q.y);
        buf.putFloat(q.z);
        buf.putFloat(q.w);
        buf.putFloat(cameraAngle);
        buf.putFloat(state.motor);
        buf.putFloat((float) state.speed());
        for (int i = 0; i < 4; i++) {
            buf.put(DroneDamage.toByte(damage.prop[i]));
        }
        for (int i = 0; i < 4; i++) {
            buf.put(DroneDamage.toByte(damage.arm[i]));
        }
        buf.put(DroneDamage.toByte(damage.camera));
        buf.put(DroneDamage.toByte(damage.frame));
        return buf.array();
    }

    public static byte[] encodeColors(byte[] colors) {
        byte[] out = new byte[1 + colors.length];
        out[0] = VERSION;
        System.arraycopy(colors, 0, out, 1, colors.length);
        return out;
    }

    public static byte[] encodeConfig(float maxRange) {
        ByteBuffer buf = ByteBuffer.allocate(6);
        buf.put(VERSION);
        buf.putFloat(maxRange);
        buf.put((byte) 0);
        return buf.array();
    }

    public static byte[] encodeHello() {
        return new byte[] {VERSION};
    }

    /** @return дальность из пакета config или -1, если пакет непонятный */
    public static float decodeConfigRange(byte[] data) {
        if (data == null || data.length < 5) {
            return -1.0F;
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.get();
        return buf.getFloat();
    }
}
