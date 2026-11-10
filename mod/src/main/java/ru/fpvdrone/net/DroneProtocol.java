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
 *   byte  flags   (1 = в полёте, 2 = армлен, 4 = разбит, 8 = Level, 16 = режим сообщён)
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
 * config (сервер -> клиент), 27 байт: byte version, float maxRange (0 = выбор игрока),
 *   byte stabilization (0 = All, 1 = Acro, 2 = Level), пять float повреждений
 *   (0 = выбор игрока), byte liquids (-1 = выбор игрока, 0 = нет, 1 = да).
 *   Старый 6-байтный пакет без фиксированных повреждений также поддерживается.
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
        int flags = FLAG_ACTIVE | 16 | (state.levelMode ? 8 : 0);
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

    /** Zero numeric values mean client choice; -1 liquid override means client choice. */
    public static float[] decodeDamageConfig(byte[] data) {
        float[] values = new float[5];
        if (decodeConfigRange(data) < 0 || data.length < 27) return values;
        ByteBuffer buf = ByteBuffer.wrap(data, 6, 20);
        for (int i = 0; i < values.length; i++) {
            float v = buf.getFloat();
            values[i] = Float.isFinite(v) && v > 0 ? v : 0;
        }
        return values;
    }

    public static byte decodeLiquidsConfig(byte[] data) {
        if (decodeConfigRange(data) < 0 || data.length < 27) return -1;
        return data[26] >= -1 && data[26] <= 1 ? data[26] : -1;
    }

    public static int decodeConfigStabilization(byte[] data) {
        return decodeConfigRange(data) >= 0 && data[5] >= 0 && data[5] <= 2 ? data[5] : 0;
    }

    public static byte[] encodeHello() {
        return new byte[] {VERSION};
    }

    /** @return дальность из пакета config или -1, если пакет непонятный */
    public static float decodeConfigRange(byte[] data) {
        if (data == null || data.length < 6 || data[0] != VERSION) {
            return -1.0F;
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.get();
        float range = buf.getFloat();
        return Float.isFinite(range) && range >= 0.0F ? range : -1.0F;
    }
}
