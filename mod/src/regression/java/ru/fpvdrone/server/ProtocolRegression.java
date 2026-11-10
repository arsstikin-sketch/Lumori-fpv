package ru.fpvdrone.server;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Standalone regression checks; no game server or test framework required. */
public final class ProtocolRegression {
    public static void main(String[] args) {
        byte[] packet = ByteBuffer.allocate(64).put((byte) 1).put((byte) 3)
            .putDouble(10).putDouble(64).putDouble(-3)
            .putFloat(0).putFloat(0).putFloat(0).putFloat(1)
            .putFloat(30).putFloat(0.5F).putFloat(5)
            .put(new byte[] {100,100,100,100,100,100,100,100,100,100}).array();
        RemoteState state = RemoteState.decode(packet);
        check(state != null && state.active && state.armed && state.x == 10 && state.qw == 1, "valid state");
        for (int length = 0; length < packet.length; length++) {
            check(RemoteState.decode(Arrays.copyOf(packet, length)) == null, "truncated state " + length);
        }
        byte[] malformed = packet.clone();
        malformed[0] = 2;
        check(RemoteState.decode(malformed) == null, "unknown state version");
        ByteBuffer.wrap(malformed).put(0, (byte) 1).putDouble(2, Double.NaN);
        check(RemoteState.decode(malformed) == null, "NaN coordinate");
        byte[] huge = packet.clone();
        for (int i = 0; i < 4; i++) ByteBuffer.wrap(huge).putFloat(26 + i * 4, Float.MAX_VALUE);
        state = RemoteState.decode(huge);
        check(state != null && Math.abs(state.qx - 0.5F) < 0.00001, "finite quaternion overflow");
        for (int i = 0; i < 4; i++) ByteBuffer.wrap(huge).putFloat(26 + i * 4, 0);
        state = RemoteState.decode(huge);
        check(state != null && state.qw == 1, "zero quaternion fallback");
        check(!RemoteState.decode(new byte[] {1,0}).active, "inactive state");

        check(ru.fpvdrone.net.DroneProtocol.decodeConfigRange(new byte[] {1,0,0,0,0}) == -1, "truncated config");
        for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -1}) {
            check(ru.fpvdrone.net.DroneProtocol.decodeConfigRange(ru.fpvdrone.net.DroneProtocol.encodeConfig(value)) == -1, "invalid range");
        }
        byte[] config = ru.fpvdrone.net.DroneProtocol.encodeConfig(128);
        check(ru.fpvdrone.net.DroneProtocol.decodeConfigRange(config) == 128, "valid range");
        config[0] = 2;
        check(ru.fpvdrone.net.DroneProtocol.decodeConfigRange(config) == -1, "unknown config version");

        controls();
        System.out.println("Protocol and pose regressions passed");
    }

    private static void controls() {
        var a = new ru.fpvdrone.input.KeyboardMouseInput();
        var b = new ru.fpvdrone.input.KeyboardMouseInput();
        ru.fpvdrone.input.StickInput.Sticks sa = null, sb = null;
        for (int i = 0; i < 30; i++) sa = a.sample(1.0 / 30, true, false, false, false, false, false,
            .35F, 1, false, ru.fpvdrone.DroneSettings.KeyboardScheme.FPV_SWAPPED, 0);
        for (int i = 0; i < 120; i++) sb = b.sample(1.0 / 120, true, false, false, false, false, false,
            .35F, 1, false, ru.fpvdrone.DroneSettings.KeyboardScheme.FPV_SWAPPED, 0);
        check(Math.abs(sa.throttle() - sb.throttle()) < 1e-5, "throttle frame independence");
        a.mouse(2, 3);
        sa = a.sample(.02, false, false, false, true, false, false, .35F, 1, false,
            ru.fpvdrone.DroneSettings.KeyboardScheme.FPV_SWAPPED, 0);
        check(sa.yaw() == 1 && sa.roll() > 0 && sa.pitch() > 0, "remaining mouse layout");
        var assisted = new ru.fpvdrone.input.KeyboardMouseInput();
        assisted.mouse(500, 500);
        var hover = assisted.sample(.02, true, false, false, true, false, false, .35F, 1, false,
            ru.fpvdrone.DroneSettings.KeyboardScheme.KEYBOARD_ASSIST, 0);
        check(hover.throttle() == 0 && hover.pitch() == -1 && hover.yaw() == 1 && hover.roll() == 0,
            "hover steering ignores mouse and uses W for pitch");
        var fast = assisted.sample(.02, false, false, false, true, true, false, .35F, 1, false,
            ru.fpvdrone.DroneSettings.KeyboardScheme.KEYBOARD_ASSIST, 8);
        check(fast.throttle() > 0 && fast.roll() > 0 && fast.yaw() > 0 && fast.yaw() < hover.yaw(),
            "bank joins yaw with speed and Space raises throttle");
        var brake = assisted.sample(.02, false, false, false, false, false, true, .35F, 1, false,
            ru.fpvdrone.DroneSettings.KeyboardScheme.KEYBOARD_ASSIST, 8);
        check(brake.throttle() == 0, "Shift lowers throttle");
        var spectator = new ru.fpvdrone.input.KeyboardMouseInput();
        var spectatorState = new ru.fpvdrone.flight.DroneState();
        spectator.beginSpectator(spectatorState.orientation);
        spectator.mouse(-40, 20);
        var spectatorInput = spectator.sample(.02, true, false, false, false, true, false, .35F, 1, false,
            ru.fpvdrone.DroneSettings.KeyboardScheme.SPECTATOR, 0);
        check(spectatorInput.throttle() == 0, "spectator movement must not raise retained throttle");
        spectator.spectator(spectatorState, true, false, false, false, false, false, 1, false);
        check(spectatorState.spectatorWishX > 0 && spectatorState.spectatorWishY < 0
            && spectatorState.spectatorWishZ > 0, "look direction drives forward flight");
        spectator.spectator(spectatorState, false, false, false, true, false, false, 1, false);
        check(spectatorState.spectatorWishX < 0 && spectatorState.spectatorWishY == 0,
            "strafe is relative to look direction");
        spectator.spectator(spectatorState, false, false, false, false, true, false, 1, false);
        check(spectatorState.spectatorWishY == 1, "Space raises altitude");
        spectator.spectator(spectatorState, false, false, false, false, false, true, 1, false);
        check(spectatorState.spectatorWishY == -1, "Shift lowers altitude");
        var cfg = new ru.fpvdrone.DroneSettings();
        cfg.durability = 4;
        var damage = new ru.fpvdrone.damage.DroneDamage();
        try {
            var apply = ru.fpvdrone.damage.DamageModel.class.getDeclaredMethod("applyDamage",
                org.joml.Vector3f.class, double.class, ru.fpvdrone.DroneSettings.class,
                ru.fpvdrone.damage.DroneDamage.class);
            apply.setAccessible(true);
            apply.invoke(null, new org.joml.Vector3f(0, 0, 1), 100.0, cfg, damage);
            check(damage.isPristine(), "infinite durability survives severe collision");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        byte[] oldServer = ru.fpvdrone.net.DroneProtocol.encodeConfig(0);
        check(ru.fpvdrone.net.DroneProtocol.decodeDamageConfig(oldServer)[0] == 0
            && ru.fpvdrone.net.DroneProtocol.decodeLiquidsConfig(oldServer) == -1,
            "old server leaves damage controls unlocked");
        var level = new ru.fpvdrone.flight.LevelStabilizer();
        for (float roll : new float[] {.7F, -1.2F, 3.1F}) {
            var q = new org.joml.Quaternionf().rotationZ(roll).rotateX(.3F);
            for (int i = 0; i < 1200; i++) {
                var rates = level.rates(q, 0, 0, 45);
                q.rotateX((float) Math.toRadians(rates.x / 240)).rotateZ((float) Math.toRadians(rates.z / 240)).normalize();
            }
            var up = q.transform(new org.joml.Vector3f(0, 1, 0));
            check(up.y > .999, "Level must recover to horizon, including inverted attitude");
        }
        var rates = level.rates(new org.joml.Quaternionf(), 1, -1, 45);
        check(rates.x > 0 && rates.z < 0, "Level command direction");
        var state = new ru.fpvdrone.flight.DroneState();
        state.levelMode = true;
        byte[] data = ru.fpvdrone.net.DroneProtocol.encodeState(state, new ru.fpvdrone.damage.DroneDamage(), 25);
        check((data[1] & 24) == 24, "report Level to server");
        byte[] config = ru.fpvdrone.net.DroneProtocol.encodeConfig(128);
        for (int policy = 0; policy <= 2; policy++) {
            config[5] = (byte) policy;
            check(ru.fpvdrone.net.DroneProtocol.decodeConfigStabilization(config) == policy, "decode server mode");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
