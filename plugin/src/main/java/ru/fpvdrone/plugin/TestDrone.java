package ru.fpvdrone.plugin;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Демо-дрон для проверки без мода: летает кругами вокруг места, где стоял игрок,
 * с креном в повороте, «кивками» по тангажу и наклонённой камерой. Видят все, включая автора команды.
 */
final class TestDrone {
    private final UUID owner;
    private final World world;
    private final double cx;
    private final double cy;
    private final double cz;
    private final int durationTicks;
    private final SkinVisual visual;
    private int age;

    TestDrone(FpvDronePlugin plugin, Player player, int durationTicks) {
        this.owner = player.getUniqueId();
        Location base = player.getLocation();
        this.world = base.getWorld();
        this.cx = base.getX();
        this.cy = base.getY() + 2.5;
        this.cz = base.getZ();
        this.durationTicks = durationTicks;
        this.visual = plugin.createVisual(player, new Location(this.world, this.cx, this.cy, this.cz), null, true, false);
    }

    int ticksLeft() {
        return Math.max(20, this.durationTicks - this.age);
    }

    UUID owner() {
        return this.owner;
    }

    void setColors(DroneColors colors) {
        this.visual.setColors(colors);
    }

    /** @return false, когда демо закончилось */
    boolean tick() {
        this.age++;
        if (this.age > this.durationTicks || !this.visual.isValid()) {
            return false;
        }
        double t = this.age / 20.0;
        double radius = 4.0;
        double omega = 0.9; // рад/с
        double angle = t * omega;

        DroneState s = new DroneState();
        s.active = true;
        s.armed = this.age > 20;
        s.x = this.cx + Math.cos(angle) * radius;
        s.z = this.cz + Math.sin(angle) * radius;
        s.y = this.cy + Math.sin(t * 1.7) * 0.6;
        s.throttle = 0.5F;
        s.cameraAngleDeg = 30.0F;

        // Направление полёта — касательная к окружности. Локальный +Z дрона смотрит вперёд.
        double heading = Math.atan2(-Math.sin(angle), Math.cos(angle)); // поворот вокруг +Y: +Z -> направление полёта
        double bank = Math.toRadians(28.0); // крен внутрь поворота (центр круга справа от дрона)
        double pitch = Math.toRadians(18.0 + Math.sin(t * 2.3) * 8.0); // нос вниз — летим вперёд
        Mat4 r = Mat4.rotationY(heading).mul(Mat4.rotationX(pitch)).mul(rotationZ(bank));
        float[] q = toQuaternion(r);
        s.qx = q[0];
        s.qy = q[1];
        s.qz = q[2];
        s.qw = q[3];

        // Через 10 секунд «ломаем» один пропеллер, чтобы было видно повреждения.
        if (this.age > 200) {
            s.prop[1] = 0.0F;
            s.prop[2] = 0.5F;
        }
        this.visual.update(this.world, s);
        return true;
    }

    void remove() {
        this.visual.remove();
    }

    private static Mat4 rotationZ(double radians) {
        double half = radians * 0.5;
        return Mat4.rotation(0.0, 0.0, Math.sin(half), Math.cos(half));
    }

    /** Кватернион из матрицы поворота (column-major). */
    private static float[] toQuaternion(Mat4 r) {
        float[] m = r.m;
        double m00 = m[0];
        double m11 = m[5];
        double m22 = m[10];
        double m21 = m[6];
        double m12 = m[9];
        double m02 = m[8];
        double m20 = m[2];
        double m10 = m[1];
        double m01 = m[4];
        double trace = m00 + m11 + m22;
        double x;
        double y;
        double z;
        double w;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1.0) * 2.0;
            w = 0.25 * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2.0;
            w = (m21 - m12) / s;
            x = 0.25 * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2.0;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25 * s;
            z = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2.0;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25 * s;
        }
        return new float[] {(float) x, (float) y, (float) z, (float) w};
    }
}
