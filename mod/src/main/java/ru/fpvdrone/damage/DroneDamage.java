package ru.fpvdrone.damage;

/**
 * Состояние повреждений дрона. Здоровье каждой части от 0 (сломана) до 1 (целая).
 * Порядок моторов: 0 = передний левый, 1 = передний правый, 2 = задний левый, 3 = задний правый.
 */
public final class DroneDamage {
    public static final int FL = 0;
    public static final int FR = 1;
    public static final int RL = 2;
    public static final int RR = 3;

    public final float[] prop = {1.0F, 1.0F, 1.0F, 1.0F};
    public final float[] arm = {1.0F, 1.0F, 1.0F, 1.0F};
    public float camera = 1.0F;
    public float frame = 1.0F;
    /** Скорость последнего удара, который что-то повредил (для HUD). */
    public float lastImpactSpeed;
    /** Время последнего повреждающего удара (System.nanoTime). */
    public long lastImpactNanos;

    public void repairAll() {
        for (int i = 0; i < 4; i++) {
            this.prop[i] = 1.0F;
            this.arm[i] = 1.0F;
        }
        this.camera = 1.0F;
        this.frame = 1.0F;
        this.lastImpactSpeed = 0.0F;
        this.lastImpactNanos = 0L;
    }

    /** Эффективность мотора с учётом пропеллера и луча (0..1). */
    public float motorEfficiency(int index) {
        float p = this.prop[index];
        float a = this.arm[index];
        if (p <= 0.0F || a <= 0.0F) {
            return 0.0F;
        }
        // Повреждённый пропеллер тянет хуже, но не линейно: сколотый край ещё даёт тягу.
        float propFactor = 0.25F + 0.75F * p;
        float armFactor = 0.35F + 0.65F * a;
        return propFactor * armFactor;
    }

    /** Средняя тяга относительно целого дрона. */
    public float thrustScale() {
        float sum = 0.0F;
        for (int i = 0; i < 4; i++) {
            sum += this.motorEfficiency(i);
        }
        return sum / 4.0F;
    }

    /** Насколько сильно дрон «трясёт» от повреждённых пропеллеров (0..1). */
    public float wear() {
        float sum = 0.0F;
        for (int i = 0; i < 4; i++) {
            sum += 1.0F - Math.min(this.prop[i], this.arm[i]);
        }
        return sum / 4.0F;
    }

    public boolean isDestroyed() {
        return this.frame <= 0.0F || this.camera <= 0.0F;
    }

    public boolean isPristine() {
        for (int i = 0; i < 4; i++) {
            if (this.prop[i] < 1.0F || this.arm[i] < 1.0F) {
                return false;
            }
        }
        return this.camera >= 1.0F && this.frame >= 1.0F;
    }

    public static byte toByte(float health) {
        return (byte) Math.round(Math.max(0.0F, Math.min(1.0F, health)) * 100.0F);
    }
}
