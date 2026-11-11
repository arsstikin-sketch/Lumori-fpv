package ru.fpvdrone.flight;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import ru.fpvdrone.DroneSettings;
import ru.fpvdrone.damage.DamageModel;
import ru.fpvdrone.damage.DroneDamage;
import ru.fpvdrone.input.StickInput;

/**
 * Физика квадрокоптера: акро-режим (стики задают угловые скорости), тяга вдоль оси «вверх» дрона,
 * гравитация, сопротивление воздуха, столкновения с блоками Minecraft.
 */
public final class DronePhysics {
    public static final double STEP = 1.0 / 240.0;
    /** Половина размеров коробки столкновений, метры (5" дрон ~ 25 см по диагонали моторов). */
    private static final double HALF_W = 0.12;
    private static final double HALF_H = 0.04;

    private DronePhysics() {
    }

    public enum Result {
        OK, DESTROYED, BLOCKED
    }

    /**
     * Один шаг симуляции.
     *
     * @param rangeCenter точка, от которой меряется дальность (глаза игрока), может быть null
     * @param maxRange    ограничение дальности, 0 = нет
     */
    public static Result step(DroneState s, DroneDamage damage, StickInput.Sticks sticks, DroneSettings cfg,
                              Level level, Entity collisionEntity, Vec3 rangeCenter, float maxRange) {
        double dt = STEP;
        s.storePrevious();
        s.simTime += dt;
        if (s.destroyed) {
            return Result.DESTROYED;
        }

        double thrust = 0.0;
        Vector3f up = s.thrustDirection.set(0, 1, 0);
        if (s.spectatorMode && s.armed) {
            // Плавный разгон: цель скорости и ускорение сглаживаются отдельно.
            double desiredX = s.spectatorWishX * 10.0;
            double desiredY = s.spectatorWishY * 10.0;
            double desiredZ = s.spectatorWishZ * 10.0;
            double ax = (desiredX - s.vx) / 0.85;
            double ay = (desiredY - s.vy) / 0.85;
            double az = (desiredZ - s.vz) / 0.85;
            double a = Math.sqrt(ax * ax + ay * ay + az * az);
            if (a > 9.0) { ax *= 9.0 / a; ay *= 9.0 / a; az *= 9.0 / a; }
            double smooth = 1.0 - Math.exp(-dt / 0.18);
            s.spectatorAccelX += (ax - s.spectatorAccelX) * smooth;
            s.spectatorAccelY += (ay - s.spectatorAccelY) * smooth;
            s.spectatorAccelZ += (az - s.spectatorAccelZ) * smooth;
            s.vx += s.spectatorAccelX * dt;
            s.vy += s.spectatorAccelY * dt;
            s.vz += s.spectatorAccelZ * dt;
            // Локальное ускорение задаёт наклон. Взгляд управляет курсом независимо от крена.
            double sin = Math.sin(s.spectatorLookYaw), cos = Math.cos(s.spectatorLookYaw);
            double forward = s.spectatorAccelX * sin + s.spectatorAccelZ * cos;
            double sideways = -s.spectatorAccelX * cos + s.spectatorAccelZ * sin;
            float pitch = (float) Math.max(-0.28, Math.min(0.28, forward / 25.0));
            float roll = (float) Math.max(-0.38, Math.min(0.38, sideways / 20.0));
            Quaternionf target = new Quaternionf().rotateY((float) s.spectatorLookYaw)
                .rotateX((float) s.spectatorLookPitch + pitch).rotateZ(roll);
            s.orientation.slerp(target, (float) (1.0 - Math.exp(-dt / 0.16))).normalize();
            s.motor += (0.5F - s.motor) * (float) (1.0 - Math.exp(-dt / cfg.motorResponse));
            s.rollRate = s.pitchRate = s.yawRate = 0;
        } else {
        // ---------------- плавность управления: сглаживание стиков
        float tau = Math.max(cfg.controlSmoothing * 0.15F, s.keyboardControl ? cfg.keyboardSmoothing * 0.28F : 0.0F);
        float smooth = tau <= 0.001F ? 1.0F : (float) (1.0 - Math.exp(-dt / tau));
        s.smoothRoll += (sticks.roll() - s.smoothRoll) * smooth;
        s.smoothPitch += (sticks.pitch() - s.smoothPitch) * smooth;
        s.smoothYaw += (sticks.yaw() - s.smoothYaw) * smooth;
        s.smoothThrottle += (sticks.throttle() - s.smoothThrottle) * smooth;

        // ---------------- угловые скорости
        if (s.armed) {
            float intensity = cfg.controlIntensity;
            float targetRoll = intensity * Rates.degreesPerSecond(s.smoothRoll, cfg.rcRateRollPitch, cfg.superRateRollPitch, cfg.expoRollPitch);
            float targetPitch = intensity * Rates.degreesPerSecond(s.smoothPitch, cfg.rcRateRollPitch, cfg.superRateRollPitch, cfg.expoRollPitch);
            float targetYaw = intensity * Rates.degreesPerSecond(s.smoothYaw, cfg.rcRateYaw, cfg.superRateYaw, cfg.expoYaw);
            if (s.keyboardSmartSteering && !s.levelMode) {
                // Acro keeps manual pitch; roll is held at a speed-dependent bank angle.
                targetRoll = s.stabilizer.rates(s.orientation, 0, s.smoothRoll, cfg.levelMaxAngle).z;
            }
            if (s.levelMode) {
                Vector3f rates = s.stabilizer.rates(s.orientation, s.smoothPitch, s.smoothRoll, cfg.levelMaxAngle);
                targetPitch = rates.x;
                targetRoll = rates.z;
            }
            float k = (float) (1.0 - Math.exp(-dt / cfg.rateResponse));
            s.rollRate += (targetRoll - s.rollRate) * k;
            s.pitchRate += (targetPitch - s.pitchRate) * k;
            s.yawRate += (targetYaw - s.yawRate) * k;
            DamageModel.applyImbalance(s, damage, dt);
        } else {
            // Дизарм: никто не держит, вращение медленно затухает от воздуха.
            float decay = (float) Math.exp(-dt * cfg.tumbleDamping);
            s.rollRate *= decay;
            s.pitchRate *= decay;
            s.yawRate *= decay;
        }
        s.orientation
            .rotateY((float) Math.toRadians(-s.yawRate * dt))
            .rotateX((float) Math.toRadians(s.pitchRate * dt))
            .rotateZ((float) Math.toRadians(s.rollRate * dt))
            .normalize();

        // ---------------- моторы и тяга
        float target = 0.0F;
        if (s.armed) {
            float t = Rates.throttleCurve(s.smoothThrottle, cfg.throttleExpo) * cfg.throttleLimit;
            target = cfg.idleThrottle + (1.0F - cfg.idleThrottle) * t;
        }
        s.motor += (target - s.motor) * (float) (1.0 - Math.exp(-dt / cfg.motorResponse));
        double m = s.motor;
        double curve = cfg.thrustCurve;
        thrust = cfg.gravity * cfg.thrustToWeight * ((1.0 - curve) * m + curve * m * m) * damage.thrustScale();
        up.set(0.0F, 1.0F, 0.0F);
        s.orientation.transform(up);

        double speed = s.speed();
        // Брюхом вперёд (дрон «падает плашмя», скорость против оси «вверх») сопротивление больше.
        double along = speed > 1.0E-6 ? Math.max(0.0, -(s.vx * up.x + s.vy * up.y + s.vz * up.z) / speed) : 0.0;
        double shape = 1.0 + (cfg.bellyDrag - 1.0) * along * along;
        double drag = (cfg.dragQuadratic * speed + cfg.dragLinear) * shape;
        double ax = up.x * thrust - s.vx * drag;
        double ay = up.y * thrust - s.vy * drag - cfg.gravity;
        double az = up.z * thrust - s.vz * drag;
        s.vx += ax * dt;
        s.vy += ay * dt;
        s.vz += az * dt;
        }

        // ---------------- перемещение со столкновениями
        double dx = s.vx * dt;
        double dy = s.vy * dt;
        double dz = s.vz * dt;
        AABB box = new AABB(s.x - HALF_W, s.y - HALF_H, s.z - HALF_W, s.x + HALF_W, s.y + HALF_H, s.z + HALF_W);
        double[] allowed = collide(level, collisionEntity, box, dx, dy, dz);
        double bx = dx - allowed[0];
        double by = dy - allowed[1];
        double bz = dz - allowed[2];
        s.onGround = by < -1.0E-7;
        if (Math.abs(bx) > 1.0E-7 || Math.abs(by) > 1.0E-7 || Math.abs(bz) > 1.0E-7) {
            DamageModel.onCollision(s, damage, cfg, bx, by, bz);
            if (s.onGround && !(s.spectatorMode && s.armed)) {
                applyGroundFriction(s, thrust, up, cfg.gravity);
            }
            if (damage.isDestroyed()) {
                s.destroyed = true;
                s.armed = false;
                s.motor = 0.0F;
                return Result.DESTROYED;
            }
        }

        double nx = s.x + allowed[0];
        double ny = s.y + allowed[1];
        double nz = s.z + allowed[2];

        // ---------------- граница: незагруженные чанки и дальность
        BlockPos center = BlockPos.containing(nx, ny, nz);
        boolean outOfRange = false;
        if (rangeCenter != null && maxRange > 0.0F) {
            double rx = nx - rangeCenter.x;
            double ry = ny - rangeCenter.y;
            double rz = nz - rangeCenter.z;
            outOfRange = rx * rx + ry * ry + rz * rz > (double) maxRange * maxRange;
        }
        if (outOfRange || !level.hasChunkAt(center)) {
            // Невидимая стена: дальше не пускаем.
            s.vx = 0.0;
            s.vy = 0.0;
            s.vz = 0.0;
            return Result.BLOCKED;
        }
        s.x = nx;
        s.y = ny;
        s.z = nz;

        // ---------------- жидкости
        if (ru.fpvdrone.net.DroneNetClient.durability(cfg) < 4.0F
            && ru.fpvdrone.net.DroneNetClient.liquidsDestroy(cfg) && !level.getFluidState(center).isEmpty()) {
            damage.camera = 0.0F;
            s.destroyed = true;
            s.armed = false;
            s.motor = 0.0F;
            return Result.DESTROYED;
        }
        return Result.OK;
    }

    /**
     * Трение покоя: лежащий на земле дрон не «ползёт» от холостой тяги или микроскоростей.
     * Если тяга не может оторвать дрон и скорость вдоль земли мала — останавливаем его полностью.
     */
    private static void applyGroundFriction(DroneState s, double thrust, Vector3f up, double gravity) {
        double horizontal = Math.sqrt(s.vx * s.vx + s.vz * s.vz);
        double lift = thrust * up.y;
        double sideways = thrust * Math.sqrt(up.x * up.x + up.z * up.z);
        boolean pinned = lift < gravity * 0.92 && sideways < gravity * 0.6; // тяга меньше веса и слабо толкает вбок
        if (pinned && horizontal < 0.6) {
            s.vx = 0.0;
            s.vz = 0.0;
            if (s.vy < 0.0) {
                s.vy = 0.0;
            }
            if (!s.armed) {
                s.rollRate = 0.0F;
                s.pitchRate = 0.0F;
                s.yawRate = 0.0F;
            }
        }
    }

    /**
     * Сдвиг коробки с учётом блоков. Как у сущностей Minecraft: сначала Y, потом X, потом Z.
     * Все блоки с формой столкновения твёрдые (стекло, листва, двери и т.д.) — сквозь них не пролететь.
     */
    static double[] collide(Level level, Entity entity, AABB box, double dx, double dy, double dz) {
        AABB swept = box.expandTowards(new Vec3(dx, dy, dz));
        BlockPos min = BlockPos.containing(swept.minX - 0.01, swept.minY - 1.01, swept.minZ - 0.01);
        BlockPos max = BlockPos.containing(swept.maxX + 0.01, swept.maxY + 0.01, swept.maxZ + 0.01);
        CollisionContext context = CollisionContext.of(entity);
        List<VoxelShape> shapes = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(level, pos, context);
            if (!shape.isEmpty()) {
                shapes.add(shape.move(pos.getX(), pos.getY(), pos.getZ()));
            }
        }
        if (shapes.isEmpty()) {
            return new double[] {dx, dy, dz};
        }
        double ay = dy == 0.0 ? 0.0 : Shapes.collide(Direction.Axis.Y, box, shapes, dy);
        box = box.move(0.0, ay, 0.0);
        double ax = dx == 0.0 ? 0.0 : Shapes.collide(Direction.Axis.X, box, shapes, dx);
        box = box.move(ax, 0.0, 0.0);
        double az = dz == 0.0 ? 0.0 : Shapes.collide(Direction.Axis.Z, box, shapes, dz);
        return new double[] {ax, ay, az};
    }
}
