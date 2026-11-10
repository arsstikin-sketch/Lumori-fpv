package ru.fpvdrone.damage;

import org.joml.Vector3f;
import ru.fpvdrone.DroneSettings;
import ru.fpvdrone.flight.DroneState;
import ru.fpvdrone.net.DroneNetClient;

/**
 * Поломки «как в Liftoff»: чем сильнее удар, тем сильнее ломается часть, которой дрон ударился.
 * Лёгкие касания ничего не ломают — дрон отскакивает и летит дальше.
 * Полностью дрон разбивается, только если уничтожена рама или камера.
 */
public final class DamageModel {
    /** Направления на моторы в локальных координатах: +X = влево, +Z = вперёд. Порядок FL, FR, RL, RR. */
    private static final float[][] MOTOR_DIRS = {
        {0.7071F, 0.7071F},
        {-0.7071F, 0.7071F},
        {0.7071F, -0.7071F},
        {-0.7071F, -0.7071F}
    };

    private DamageModel() {
    }

    /**
     * Обработать столкновение. Меняет скорость и угловые скорости в state, повреждения в damage.
     *
     * @param bx by bz  заблокированная часть перемещения (что хотели пройти минус что прошли)
     * @return скорость удара (м/с), 0 если касание без повреждений
     */
    public static float onCollision(DroneState state, DroneDamage damage, DroneSettings settings, double bx, double by, double bz) {
        double len = Math.sqrt(bx * bx + by * by + bz * bz);
        if (len < 1.0E-9) {
            return 0.0F;
        }
        double nx = bx / len;
        double ny = by / len;
        double nz = bz / len;
        double impact = Math.max(0.0, state.vx * nx + state.vy * ny + state.vz * nz);
        // Скорость вдоль поверхности.
        double tx = state.vx - nx * impact;
        double ty = state.vy - ny * impact;
        double tz = state.vz - nz * impact;

        if (impact < DroneNetClient.bumpSpeed(settings)) {
            // Касание / скольжение / посадка: гасим скорость «в стену», немного трения.
            state.vx = tx * 0.92;
            state.vy = ty * 0.92;
            state.vz = tz * 0.92;
            return 0.0F;
        }

        Vector3f local = new Vector3f((float) nx, (float) ny, (float) nz);
        state.orientation.transformInverse(local);
        applyDamage(local, impact, settings, damage);
        damage.lastImpactSpeed = (float) impact;
        damage.lastImpactNanos = System.nanoTime();

        if (damage.isDestroyed()) {
            state.vx = 0.0;
            state.vy = 0.0;
            state.vz = 0.0;
            return (float) impact;
        }

        // Отскок: часть скорости «в стену» возвращается, вдоль стены — трение.
        double e = settings.bounciness;
        state.vx = tx * 0.8 - nx * impact * e;
        state.vy = ty * 0.8 - ny * impact * e;
        state.vz = tz * 0.8 - nz * impact * e;

        // Удар закручивает дрон.
        float kick = (float) Math.min(900.0, impact * 45.0);
        state.rollRate += local.x * kick;
        state.pitchRate -= local.z * kick;
        state.yawRate += local.x * local.z * kick * 0.5F;
        return (float) impact;
    }

    static void applyDamage(Vector3f local, double speed, DroneSettings settings, DroneDamage damage) {
        if (DroneNetClient.durability(settings) >= 4.0F) return;
        float fragility = 1.0F / Math.max(0.25F, DroneNetClient.durability(settings));

        float[] weight = new float[4];
        double horizontal = Math.sqrt(local.x * local.x + local.z * local.z);
        if (horizontal > 0.3) {
            double hx = local.x / horizontal;
            double hz = local.z / horizontal;
            // Удар углом -> одному мотору (вес 1), ровно в лоб -> двум передним по 0.5.
            for (int i = 0; i < 4; i++) {
                double d = Math.max(0.0, hx * MOTOR_DIRS[i][0] + hz * MOTOR_DIRS[i][1]);
                weight[i] = (float) (d * d * horizontal);
            }
        }
        if (local.y > 0.3F) {
            // Удар сверху (перевернулся / потолок) — достаётся всем пропеллерам.
            for (int i = 0; i < 4; i++) {
                weight[i] += local.y * 0.5F;
            }
        }
        boolean belly = local.y < -0.6F; // удар брюхом — жёсткая посадка

        float propSeverity = severity(speed, DroneNetClient.bumpSpeed(settings), DroneNetClient.propBreakSpeed(settings)) * fragility;
        float armSeverity = severity(speed, DroneNetClient.propBreakSpeed(settings), DroneNetClient.armBreakSpeed(settings)) * fragility;
        for (int i = 0; i < 4; i++) {
            damage.prop[i] = Math.max(0.0F, damage.prop[i] - weight[i] * propSeverity);
            float armHit = weight[i] * armSeverity + (belly ? 0.25F * armSeverity : 0.0F);
            damage.arm[i] = Math.max(0.0F, damage.arm[i] - armHit);
        }

        float front = Math.max(0.0F, local.z);
        float cameraBreak = DroneNetClient.frameBreakSpeed(settings) * 0.7F;
        float cameraSeverity = severity(speed, cameraBreak * 0.5F, cameraBreak) * fragility;
        damage.camera = Math.max(0.0F, damage.camera - front * cameraSeverity);

        float frameSeverity = severity(speed, DroneNetClient.frameBreakSpeed(settings) * 0.45F, DroneNetClient.frameBreakSpeed(settings)) * fragility;
        damage.frame = Math.max(0.0F, damage.frame - frameSeverity);
    }

    /** 0 при скорости <= from, 1 при скорости = to, дальше растёт (до 4). */
    private static float severity(double speed, float from, float to) {
        if (speed <= from) {
            return 0.0F;
        }
        double t = (speed - from) / Math.max(0.01, to - from);
        return (float) Math.min(4.0, t);
    }

    /** «Увод» от неравномерной тяги повреждённых моторов и вибрация. Вызывается на каждом шаге физики. */
    public static void applyImbalance(DroneState state, DroneDamage damage, double dt) {
        if (!state.armed || damage.isPristine()) {
            return;
        }
        float throttle = state.motor;
        if (throttle < 0.02F) {
            return;
        }
        float fl = damage.motorEfficiency(DroneDamage.FL);
        float fr = damage.motorEfficiency(DroneDamage.FR);
        float rl = damage.motorEfficiency(DroneDamage.RL);
        float rr = damage.motorEfficiency(DroneDamage.RR);

        float roll = (fl + rl) - (fr + rr);   // левая сторона слабее -> заваливается влево
        float pitch = (rl + rr) - (fl + fr);  // перед слабее -> клюёт носом
        float yaw = (fl + rr) - (fr + rl);

        float scale = 9000.0F * throttle * (float) dt;
        float shake = damage.wear() * throttle * 2600.0F * (float) dt;
        double t = state.simTime;
        state.rollRate += roll * scale + (float) Math.sin(t * 57.0) * shake;
        state.pitchRate += pitch * scale + (float) Math.sin(t * 49.0 + 1.3) * shake;
        state.yawRate += yaw * scale * 0.4F;
    }
}
