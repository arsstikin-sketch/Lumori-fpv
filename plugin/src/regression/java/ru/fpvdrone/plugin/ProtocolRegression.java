package ru.fpvdrone.plugin;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Standalone regression checks; no game server or test framework required. */
public final class ProtocolRegression {
    public static void main(String[] args) throws Exception {
        byte[] packet = ByteBuffer.allocate(64).put((byte) 1).put((byte) 3)
            .putDouble(10).putDouble(64).putDouble(-3)
            .putFloat(0).putFloat(0).putFloat(0).putFloat(1)
            .putFloat(30).putFloat(0.5F).putFloat(5)
            .put(new byte[] {100,100,100,100,100,100,100,100,100,100}).array();
        DroneState state = DroneState.decode(packet);
        check(!state.viewingDrone, "old client keeps own model visible");
        byte[] viewed = packet.clone();
        viewed[1] |= 32;
        check(DroneState.decode(viewed).viewingDrone, "camera flag decoded");
        check(state != null && state.active && state.armed && state.x == 10 && state.qw == 1, "valid state");
        for (int length = 0; length < packet.length; length++) {
            check(DroneState.decode(Arrays.copyOf(packet, length)) == null, "truncated state " + length);
        }
        byte[] malformed = packet.clone();
        malformed[0] = 2;
        check(DroneState.decode(malformed) == null, "unknown state version");
        ByteBuffer.wrap(malformed).put(0, (byte) 1).putDouble(2, Double.NaN);
        check(DroneState.decode(malformed) == null, "NaN coordinate");
        byte[] huge = packet.clone();
        for (int i = 0; i < 4; i++) ByteBuffer.wrap(huge).putFloat(26 + i * 4, Float.MAX_VALUE);
        state = DroneState.decode(huge);
        check(state != null && Math.abs(state.qx - 0.5F) < 0.00001, "finite quaternion overflow");
        for (int i = 0; i < 4; i++) ByteBuffer.wrap(huge).putFloat(26 + i * 4, 0);
        state = DroneState.decode(huge);
        check(state != null && state.qw == 1, "zero quaternion fallback");
        check(!DroneState.decode(new byte[] {1,0}).active, "inactive state");

        DroneState pose = new DroneState();
        float[] angles = MobEntities.angles(pose);
        check(Math.abs(angles[0]) < 0.001 && Math.abs(angles[1]) < 0.001, "neutral pose");
        pose.qz = 1; pose.qw = 0;
        angles = MobEntities.angles(pose);
        check(Math.abs(angles[0]) < 0.001 && Math.abs(angles[1]) < 0.001, "roll keeps heading");
        java.util.List<String> calls = new java.util.ArrayList<>();
        org.bukkit.entity.LivingEntity mob = (org.bukkit.entity.LivingEntity) java.lang.reflect.Proxy.newProxyInstance(
            ProtocolRegression.class.getClassLoader(), new Class<?>[] {org.bukkit.entity.LivingEntity.class}, (proxy, method, values) -> {
                calls.add(method.getName());
                if (method.getName().equals("getHeight")) return 2.0;
                if (method.getName().equals("teleport")) {
                    org.bukkit.Location at = (org.bukkit.Location) values[0];
                    check(at.getY() == 9 && at.getPitch() == 30, "centered pose");
                    return true;
                }
                return null;
            });
        MobEntities.pose(mob, null, 0, 10, 0, 45, 30);
        check(!calls.contains("customName") && !calls.contains("setCustomName"), "rotation must not rename mob");

        packet[1] = 25;
        state = DroneState.decode(packet);
        check(state.modeReported && state.levelMode, "Level mode flags");
        check(state.shifted(1, 0, 0).levelMode, "prediction keeps mode");
        for (int policy = 0; policy <= 2; policy++) {
            check(DroneState.encodeConfig(128, policy)[5] == policy, "server policy config");
        }
        byte[] fixed = DroneState.encodeConfig(256, 2, new float[] {4, 2, 7, 16, 30}, (byte) 0);
        check(fixed.length == 27 && fixed[5] == 2 && fixed[26] == 0
            && ByteBuffer.wrap(fixed, 6, 4).getFloat() == 4,
            "fixed damage and range payload");
        var gate = new org.bukkit.util.BoundingBox(0,0,0,1,2,3);
        check(Math.abs(DroneRaces.entryFraction(gate,new org.bukkit.util.Vector(-5,1,1),new org.bukkit.util.Vector(5,1,1))-.5)<1e-8,"fast drone crossing a thin gate");
        check(Double.isNaN(DroneRaces.entryFraction(gate,new org.bukkit.util.Vector(-5,4,1),new org.bukkit.util.Vector(5,4,1))),"missing the gate must not count");
        check(Double.isNaN(DroneRaces.entryFraction(gate,new org.bukkit.util.Vector(.5,1,1),new org.bukkit.util.Vector(.6,1,1))),"standing inside must not retrigger");
        BirdData.Root birds = BirdData.load(ProtocolRegression.class.getClassLoader());
        int count = 0;
        for (BirdData.Bird bird : birds.birds) {
            for (BirdData.Variant variant : bird.variants) {
                verifyHeadCenters(variant.rig);
                count++;
            }
        }
        System.out.println("Protocol and pose regressions passed; bird variants: " + count);
    }

    private static void verifyHeadCenters(BirdData.Node node) {
        if (node.mesh != null && "head".equals(node.mesh.kind)) {
            var pivot = new org.joml.Vector3f(node.p);
            var size = new org.joml.Vector3f(node.mesh.size).mul(2F);
            for (int i = 0; i < 12; i++) {
                var q = new org.joml.Quaternionf().rotationXYZ(i * 0.31F, i * 0.57F, i * 0.83F);
                var translation = BirdVisual.centeredHead(pivot, q, size, 0);
                // Фактический центр player_head после ванильного преобразования NONE.
                var renderedCenter = q.transform(new org.joml.Vector3f(0, -0.25F * size.y, 0)).add(translation);
                check(renderedCenter.distance(pivot) < 0.0001F, "head must stay at rig pivot after rotation");
            }
        }
        if (node.c != null) for (BirdData.Node child : node.c) verifyHeadCenters(child);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
