package ru.fpvdrone.flight;

/**
 * Рейты как в Betaflight (тип «Betaflight»): RC Rate, Super Rate, Expo.
 * При RC Rate 1.0 и Super Rate 0.7 полный стик = 667 °/с.
 */
public final class Rates {
    private Rates() {
    }

    public static float degreesPerSecond(float stick, float rcRate, float superRate, float expo) {
        float rc = Math.max(-1.0F, Math.min(1.0F, stick));
        float abs = Math.abs(rc);
        if (expo > 0.0F) {
            rc = rc * abs * abs * abs * expo + rc * (1.0F - expo);
        }
        float rate = rcRate;
        if (rate > 2.0F) {
            rate += 14.54F * (rate - 2.0F);
        }
        float angleRate = 200.0F * rate * rc;
        if (superRate > 0.0F) {
            float factor = 1.0F / Math.max(0.01F, Math.min(1.0F, 1.0F - abs * superRate));
            angleRate *= factor;
        }
        return angleRate;
    }

    /** Экспонента газа: 0 = линейно, 1 = сильно «мягкий» низ. */
    public static float throttleCurve(float throttle, float expo) {
        float t = Math.max(0.0F, Math.min(1.0F, throttle));
        return t * (1.0F - expo) + t * t * t * expo;
    }
}
