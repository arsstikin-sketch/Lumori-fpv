package ru.fpvdrone.input;

import ru.fpvdrone.DroneSettings;

/** Per-pilot mouse deltas and retained keyboard throttle; independent of frame rate. */
public final class KeyboardMouseInput {
    private double dx, dy;
    private float throttle;
    private float filteredMouseX, filteredMouseY;
    private double lookYaw, lookPitch;
    private boolean lookInitialized;

    public void mouse(double x, double y) {
        if (Double.isFinite(x) && Double.isFinite(y)) { dx += x; dy += y; }
    }

    public StickInput.Sticks sample(double dt, boolean forward, boolean back, boolean left, boolean right,
                                    boolean jump, boolean sneak, float throttleSpeed, float sensitivity,
                                    boolean invertPitch, DroneSettings.KeyboardScheme scheme, double speed) {
        return sample(dt, forward, back, left, right, jump, sneak, throttleSpeed, sensitivity,
            invertPitch, scheme, speed, 0.0F);
    }

    public StickInput.Sticks sample(double dt, boolean forward, boolean back, boolean left, boolean right,
                                    boolean jump, boolean sneak, float throttleSpeed, float sensitivity,
                                    boolean invertPitch, DroneSettings.KeyboardScheme scheme, double speed, float keyboardSmoothing) {
        dt = Math.max(0.001, Math.min(0.1, dt));
        boolean assist = scheme == DroneSettings.KeyboardScheme.KEYBOARD_ASSIST;
        if (scheme == DroneSettings.KeyboardScheme.SPECTATOR) {
            throttle = 0;
        } else {
            throttle = clamp(throttle + (float) dt * throttleSpeed *
                (((assist ? jump : forward) ? 1 : 0) - ((assist ? sneak : back) ? 1 : 0)), 0, 1);
        }
        float rawMouseX = clamp((float) (dx / dt * 0.003 * sensitivity), -1, 1);
        float rawMouseY = clamp((float) (dy / dt * 0.003 * sensitivity) * (invertPitch ? -1 : 1), -1, 1);
        float tau = Math.max(0.0F, Math.min(1.0F, keyboardSmoothing)) * 0.10F;
        float mouseBlend = tau <= 0.001F ? 1.0F : (float) (1.0 - Math.exp(-dt / tau));
        filteredMouseX += (rawMouseX - filteredMouseX) * mouseBlend;
        filteredMouseY += (rawMouseY - filteredMouseY) * mouseBlend;
        float mouseX = filteredMouseX, mouseY = filteredMouseY;
        if (scheme != DroneSettings.KeyboardScheme.SPECTATOR) clearMouse();
        float turn = (right ? 1 : 0) - (left ? 1 : 0);
        return switch (scheme) {
            case FPV_SWAPPED -> sticks(throttle, turn, mouseY, mouseX);
            case SPECTATOR -> sticks(0, 0, 0, 0);
            case KEYBOARD_ASSIST -> {
                // Near hover yaw dominates; at speed bank gradually helps the turn.
                float bank = (float) Math.min(1.0, Math.max(0.0, speed) / 8.0) * 0.55F;
                yield sticks(throttle, turn * (1.0F - bank * 0.4F),
                    (forward ? -1 : 0) + (back ? 1 : 0), turn * bank);
            }
        };
    }

    public void beginSpectator(org.joml.Quaternionf orientation) {
        var forward = orientation.transform(new org.joml.Vector3f(0, 0, 1));
        lookYaw = Math.atan2(forward.x, forward.z);
        lookPitch = -Math.asin(Math.max(-1, Math.min(1, forward.y)));
        lookInitialized = true;
        clearMouse();
    }

    public void spectator(ru.fpvdrone.flight.DroneState state, boolean forward, boolean back,
                          boolean left, boolean right, boolean jump, boolean sneak, float sensitivity, boolean invertPitch) {
        if (!lookInitialized) beginSpectator(state.orientation);
        lookYaw -= Math.toRadians(dx * 0.15 * sensitivity);
        lookPitch = Math.max(-Math.PI * 0.49, Math.min(Math.PI * 0.49,
            lookPitch + Math.toRadians(dy * 0.15 * sensitivity * (invertPitch ? -1 : 1))));
        clearMouse();
        float yaw = (float) lookYaw;
        float pitch = (float) lookPitch;
        state.spectatorLookYaw = yaw;
        state.spectatorLookPitch = pitch;
        double f = (forward ? 1 : 0) - (back ? 1 : 0);
        double side = (right ? 1 : 0) - (left ? 1 : 0);
        double vertical = (jump ? 1 : 0) - (sneak ? 1 : 0);
        double cosYaw = Math.cos(lookYaw), sinYaw = Math.sin(lookYaw);
        double cosPitch = Math.cos(lookPitch), sinPitch = Math.sin(lookPitch);
        double x = f * sinYaw * cosPitch - side * cosYaw;
        double y = -f * sinPitch + vertical;
        double z = f * cosYaw * cosPitch + side * sinYaw;
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length > 1) { x /= length; y /= length; z /= length; }
        state.spectatorWishX = x;
        state.spectatorWishY = y;
        state.spectatorWishZ = z;
    }

    private static StickInput.Sticks sticks(float throttle, float yaw, float pitch, float roll) {
        return new StickInput.Sticks(true, "Клавиатура", throttle, yaw, pitch, roll, null, null);
    }

    public void clearMouse() { dx = dy = 0; }
    public void reset() { throttle = 0; filteredMouseX = filteredMouseY = 0; lookInitialized = false; clearMouse(); }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
}
