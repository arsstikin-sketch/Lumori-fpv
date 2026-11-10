package ru.fpvdrone.plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix4f;

/**
 * Полупрозрачный светящийся след за дроном (цепочка тонких трубок из цветного стекла),
 * отрезки сужаются и исчезают через trail-seconds.
 */
final class DroneTrail {
    private static final class Segment {
        final BlockDisplay entity;
        final long born;
        final float yaw;
        final float pitch;
        final float length;

        Segment(BlockDisplay entity, long born, float yaw, float pitch, float length) {
            this.entity = entity;
            this.born = born;
            this.yaw = yaw;
            this.pitch = pitch;
            this.length = length;
        }
    }

    private final Plugin plugin;
    private final World world;
    private final Player hideFrom;
    private final Deque<Segment> segments = new ArrayDeque<>();
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean hasLast;

    DroneTrail(Plugin plugin, World world, Player hideFrom) {
        this.plugin = plugin;
        this.world = world;
        this.hideFrom = hideFrom;
    }

    World world() {
        return this.world;
    }

    void tick(long now, DroneState s, Material glass, boolean flying, int lifeTicks, float width, int maxSegments) {
        while (!this.segments.isEmpty() && now - this.segments.peekFirst().born > lifeTicks) {
            remove(this.segments.pollFirst());
        }
        if (now % 4 == 0) {
            for (Segment seg : this.segments) {
                float left = 1.0F - (now - seg.born) / (float) lifeTicks;
                shape(seg.entity, seg.yaw, seg.pitch, seg.length, Math.max(0.05F, left) * width, 4);
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
            return;
        }
        if (len > 12.0 || !flying) {
            this.lastX = s.x;
            this.lastY = s.y;
            this.lastZ = s.z;
            return;
        }
        float yaw = (float) Math.atan2(dx, dz);
        float pitch = (float) Math.asin(Math.max(-1.0, Math.min(1.0, dy / len)));
        Location at = new Location(this.world, this.lastX, this.lastY, this.lastZ, 0.0F, 0.0F);
        BlockDisplay d = this.world.spawn(at, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.setBlock(glass.createBlockData());
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(2.0F);
            e.setShadowRadius(0.0F);
        });
        shape(d, yaw, pitch, (float) len, width, 0);
        if (this.hideFrom != null) {
            this.hideFrom.hideEntity(this.plugin, d);
        }
        this.segments.addLast(new Segment(d, now, yaw, pitch, (float) len));
        while (this.segments.size() > maxSegments) {
            remove(this.segments.pollFirst());
        }
        this.lastX = s.x;
        this.lastY = s.y;
        this.lastZ = s.z;
    }

    private static void shape(BlockDisplay d, float yaw, float pitch, float length, float width, int interpolation) {
        if (!d.isValid()) {
            return;
        }
        Mat4 m = Mat4.rotationY(yaw).mul(Mat4.rotationX(-pitch))
            .mul(Mat4.scale(width, width, length))
            .mul(Mat4.translation(-0.5, -0.5, 0.0));
        d.setInterpolationDuration(interpolation);
        d.setInterpolationDelay(0);
        d.setTransformationMatrix(new Matrix4f().set(m.m));
    }

    private static void remove(Segment seg) {
        if (seg != null && seg.entity.isValid()) {
            seg.entity.remove();
        }
    }

    void clear() {
        while (!this.segments.isEmpty()) {
            remove(this.segments.pollFirst());
        }
        this.hasLast = false;
    }

    boolean isEmpty() {
        return this.segments.isEmpty();
    }
}
