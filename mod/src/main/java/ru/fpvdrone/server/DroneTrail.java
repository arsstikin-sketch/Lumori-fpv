package ru.fpvdrone.server;

import com.mojang.math.Transformation;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import org.joml.Matrix4f;
import ru.fpvdrone.mixin.server.BlockDisplayInvoker;
import ru.fpvdrone.mixin.server.DisplayInvoker;

/**
 * Полупрозрачный светящийся след за дроном: цепочка тонких «трубок» из цветного стекла.
 * Каждый отрезок постепенно сужается и исчезает через trail-seconds.
 */
public final class DroneTrail {
    private static final class Segment {
        final Display.BlockDisplay entity;
        final long born;
        final float yaw;
        final float pitch;
        final float length;

        Segment(Display.BlockDisplay entity, long born, float yaw, float pitch, float length) {
            this.entity = entity;
            this.born = born;
            this.yaw = yaw;
            this.pitch = pitch;
            this.length = length;
        }
    }

    private final ServerLevel level;
    private final UUID owner;
    private final boolean hiddenFromPilot;
    private final Deque<Segment> segments = new ArrayDeque<>();
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean hasLast;

    public DroneTrail(ServerLevel level, UUID owner, boolean hiddenFromPilot) {
        this.level = level;
        this.owner = owner;
        this.hiddenFromPilot = hiddenFromPilot;
    }

    public ServerLevel level() {
        return this.level;
    }

    /**
     * @param color   номер цвета (0..15)
     * @param flying  дрон армлен и летит (на земле след не рисуем)
     */
    public void tick(long now, RemoteState s, byte color, boolean flying, int lifeTicks, float width, int maxSegments) {
        // Старые отрезки: сужаем и удаляем.
        while (!this.segments.isEmpty() && now - this.segments.peekFirst().born > lifeTicks) {
            this.removeSegment(this.segments.pollFirst());
        }
        if (now % 4 == 0) {
            for (Segment seg : this.segments) {
                float left = 1.0F - (now - seg.born) / (float) lifeTicks;
                this.setShape(seg.entity, seg.yaw, seg.pitch, seg.length, Math.max(0.05F, left) * width, 4);
            }
        }

        if (s == null) {
            this.hasLast = false;
            return;
        }
        if (!this.hasLast) {
            this.lastX = s.x;
            this.lastY = s.y;
            this.lastZ = s.z;
            this.hasLast = true;
            return;
        }
        double dx = s.x - this.lastX;
        double dy = s.y - this.lastY;
        double dz = s.z - this.lastZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.25) {
            return; // слишком мало пролетел — ждём
        }
        if (len > 12.0 || !flying) {
            // Респавн/телепорт или дрон не летит — разрываем след.
            this.lastX = s.x;
            this.lastY = s.y;
            this.lastZ = s.z;
            return;
        }
        float yaw = (float) Math.atan2(dx, dz);
        float pitch = (float) Math.asin(Math.max(-1.0, Math.min(1.0, dy / len)));
        Display.BlockDisplay d = new Display.BlockDisplay(McLookup.blockDisplay(), this.level);
        d.setPos(this.lastX, this.lastY, this.lastZ);
        d.addTag(ServerDroneModel.TAG);
        ((BlockDisplayInvoker) d).invokeFpvdroneSetBlockState(ServerDroneModel.glass(color));
        DisplayInvoker inv = (DisplayInvoker) d;
        inv.invokeFpvdroneSetBrightness(new Brightness(15, 15));
        inv.invokeFpvdroneSetViewRange(2.0F);
        this.setShape(d, yaw, pitch, (float) len, width, 0);
        if (this.hiddenFromPilot) {
            DroneServer.registerPart(d, this.owner);
        } else {
            DroneServer.registerPart(d, null);
        }
        this.level.addFreshEntity(d);
        this.segments.addLast(new Segment(d, now, yaw, pitch, (float) len));
        while (this.segments.size() > maxSegments) {
            this.removeSegment(this.segments.pollFirst());
        }
        this.lastX = s.x;
        this.lastY = s.y;
        this.lastZ = s.z;
    }

    /** Трубка от точки отрезка вдоль направления (yaw, pitch) длиной length и толщиной width. */
    private void setShape(Display.BlockDisplay d, float yaw, float pitch, float length, float width, int interpolation) {
        if (d.isRemoved()) {
            return;
        }
        Mat4 m = Mat4.rotationY(yaw).mul(Mat4.rotationX(-pitch))
            .mul(Mat4.scale(width, width, length))
            .mul(Mat4.translation(-0.5, -0.5, 0.0));
        DisplayInvoker inv = (DisplayInvoker) d;
        inv.invokeFpvdroneSetInterpolationDuration(interpolation);
        inv.invokeFpvdroneSetInterpolationDelay(0);
        inv.invokeFpvdroneSetTransformation(new Transformation(new Matrix4f().set(m.m)));
    }

    private void removeSegment(Segment seg) {
        if (seg == null) {
            return;
        }
        DroneServer.unregisterPart(seg.entity);
        if (!seg.entity.isRemoved()) {
            seg.entity.discard();
        }
    }

    public void clear() {
        while (!this.segments.isEmpty()) {
            this.removeSegment(this.segments.pollFirst());
        }
        this.hasLast = false;
    }

    public boolean isEmpty() {
        return this.segments.isEmpty();
    }
}
