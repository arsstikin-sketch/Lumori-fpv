package ru.fpvdrone.server;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Состояние чужого дрона, присланное клиентом. Формат — ru.fpvdrone.net.DroneProtocol.
 */
public final class RemoteState {
    public static final byte VERSION = 1;
    public static final int FLAG_ACTIVE = 1;
    public static final int FLAG_ARMED = 2;
    public static final int FLAG_CRASHED = 4;

    public boolean active;
    public boolean armed;
    public boolean crashed;
    public boolean viewingDrone;
    public double x;
    public double y;
    public double z;
    /** Ориентация: локальные оси дрона +Z вперёд, +Y вверх, +X влево. */
    public float qx;
    public float qy;
    public float qz;
    public float qw = 1.0F;
    public float cameraAngleDeg;
    public float throttle;
    public float speed;
    /** Здоровье частей 0..1. Моторы: 0 FL, 1 FR, 2 RL, 3 RR. */
    public final float[] prop = {1.0F, 1.0F, 1.0F, 1.0F};
    public final float[] arm = {1.0F, 1.0F, 1.0F, 1.0F};
    public float camera = 1.0F;
    public float frame = 1.0F;

    /** @return null, если пакет повреждён или другой версии */
    public static RemoteState decode(byte[] data) {
        if (data == null || data.length < 2) {
            return null;
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(data);
            byte version = buf.get();
            if (version != VERSION) {
                return null;
            }
            int flags = buf.get() & 0xFF;
            RemoteState s = new RemoteState();
            s.active = (flags & FLAG_ACTIVE) != 0;
            s.armed = (flags & FLAG_ARMED) != 0;
            s.crashed = (flags & FLAG_CRASHED) != 0;
            s.viewingDrone = (flags & 32) != 0;
            if (!s.active) {
                return s;
            }
            s.x = buf.getDouble();
            s.y = buf.getDouble();
            s.z = buf.getDouble();
            s.qx = buf.getFloat();
            s.qy = buf.getFloat();
            s.qz = buf.getFloat();
            s.qw = buf.getFloat();
            s.cameraAngleDeg = buf.getFloat();
            s.throttle = buf.getFloat();
            s.speed = buf.getFloat();
            for (int i = 0; i < 4; i++) {
                s.prop[i] = (buf.get() & 0xFF) / 100.0F;
            }
            for (int i = 0; i < 4; i++) {
                s.arm[i] = (buf.get() & 0xFF) / 100.0F;
            }
            s.camera = (buf.get() & 0xFF) / 100.0F;
            s.frame = (buf.get() & 0xFF) / 100.0F;
            if (!s.isFinite()) {
                return null;
            }
            s.normalizeQuaternion();
            return s;
        } catch (BufferUnderflowException e) {
            return null;
        }
    }

    private boolean isFinite() {
        return Double.isFinite(this.x) && Double.isFinite(this.y) && Double.isFinite(this.z)
            && Float.isFinite(this.qx) && Float.isFinite(this.qy) && Float.isFinite(this.qz) && Float.isFinite(this.qw)
            && Float.isFinite(this.cameraAngleDeg) && Float.isFinite(this.throttle) && Float.isFinite(this.speed);
    }

    private void normalizeQuaternion() {
        double len = Math.sqrt((double) this.qx * this.qx + (double) this.qy * this.qy
            + (double) this.qz * this.qz + (double) this.qw * this.qw);
        if (len < 1.0E-6) {
            this.qx = 0.0F;
            this.qy = 0.0F;
            this.qz = 0.0F;
            this.qw = 1.0F;
            return;
        }
        this.qx = (float) (this.qx / len);
        this.qy = (float) (this.qy / len);
        this.qz = (float) (this.qz / len);
        this.qw = (float) (this.qw / len);
    }
}
