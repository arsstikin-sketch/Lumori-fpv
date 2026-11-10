package ru.fpvdrone.flight;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Bounded attitude controller; yaw remains under pilot control. */
public final class LevelStabilizer {
    private final Quaternionf target = new Quaternionf();
    private final Quaternionf error = new Quaternionf();
    private final Vector3f forward = new Vector3f();
    private final Vector3f rates = new Vector3f();

    public Vector3f rates(Quaternionf orientation, float pitch, float roll, float maxAngle) {
        orientation.transform(forward.set(0, 0, 1));
        float heading = (float) Math.atan2(forward.x, forward.z);
        target.rotationY(heading).rotateX((float) Math.toRadians(pitch * maxAngle))
            .rotateZ((float) Math.toRadians(roll * maxAngle));
        orientation.conjugate(error).mul(target).normalize();
        if (error.w < 0) error.set(-error.x, -error.y, -error.z, -error.w);
        double length = Math.sqrt(error.x * error.x + error.y * error.y + error.z * error.z);
        float factor = length < 1e-6 ? 0 : (float) (Math.toDegrees(2 * Math.atan2(length, error.w)) * 5 / length);
        return rates.set(limit(error.x * factor), 0, limit(error.z * factor));
    }

    private static float limit(float value) { return Math.max(-360, Math.min(360, value)); }
}
