package ru.fpvdrone.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import ru.fpvdrone.FpvDroneMod;
import ru.fpvdrone.net.DronePayloads;
import ru.fpvdrone.net.DroneProtocol;

/**
 * Серверная часть мода. Работает на Fabric-сервере и в одиночной игре (встроенный сервер):
 * принимает состояние дронов от игроков и показывает их всем остальным моделью из block_display.
 * На Paper вместо этого работает плагин FpvDrone — протокол тот же.
 */
public final class DroneServer {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    /** Какой сущности-части какой пилот владеет (для скрытия от самого пилота). */
    private static final Map<Entity, UUID> PART_OWNERS = new IdentityHashMap<>();

    private static float maxRange = 128.0F;
    private static float modelScale = 1.5F;
    private static float viewRange = 2.0F;
    private static int timeoutTicks = 40;
    private static boolean brightModel = true;
    private static boolean glowOutline = false;
    private static boolean trailEnabled = true;
    private static float trailSeconds = 3.0F;
    private static float trailWidth = 0.06F;
    private static boolean trailVisibleToPilot = true;
    private static long tick;

    private DroneServer() {
    }

    private static final class Session {
        final UUID player;
        boolean hasMod;
        RemoteState state;
        boolean dirty;
        long lastPacketTick;
        byte[] colors = ServerDroneModel.DEFAULT_COLORS;
        ServerDroneModel model;
        DroneTrail trail;

        Session(UUID player) {
            this.player = player;
        }

        void removeModel() {
            if (this.model != null) {
                this.model.remove();
                this.model = null;
            }
        }

        void removeAll() {
            this.removeModel();
            if (this.trail != null) {
                this.trail.clear();
                this.trail = null;
            }
        }
    }

    public static void init() {
        loadConfig();

        ServerPlayNetworking.registerGlobalReceiver(DronePayloads.Hello.TYPE, (payload, context) -> {
            Session s = session(context.player());
            s.hasMod = true;
            sendConfig(context.player());
            FpvDroneMod.LOGGER.info("[fpvdrone] {} подключился с модом FPV Drone", context.player().getName().getString());
        });
        ServerPlayNetworking.registerGlobalReceiver(DronePayloads.Colors.TYPE, (payload, context) -> {
            byte[] data = payload.data();
            if (data.length >= 8 && data[0] == DroneProtocol.VERSION) {
                byte[] colors = new byte[7];
                System.arraycopy(data, 1, colors, 0, 7);
                Session s = session(context.player());
                s.colors = colors;
                if (s.model != null) {
                    s.model.setColors(colors);
                    s.dirty = true;
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(DronePayloads.State.TYPE, (payload, context) ->
            onState(context.player(), payload.data()));

        ServerTickEvents.END_SERVER_TICK.register(DroneServer::onTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Session s = SESSIONS.remove(handler.getPlayer().getUUID());
            if (s != null) {
                s.removeAll();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Session s : SESSIONS.values()) {
                s.removeAll();
            }
            SESSIONS.clear();
            PART_OWNERS.clear();
        });
        // Части дронов, оставшиеся в мире после вылета сервера, удаляем при загрузке.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!PART_OWNERS.containsKey(entity) && McLookup.hasTag(entity, ServerDroneModel.TAG)) {
                entity.discard();
            }
        });
    }

    private static Session session(ServerPlayer player) {
        return SESSIONS.computeIfAbsent(player.getUUID(), Session::new);
    }

    private static void sendConfig(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, DronePayloads.Config.TYPE)) {
            ServerPlayNetworking.send(player, new DronePayloads.Config(DroneProtocol.encodeConfig(maxRange)));
        }
    }

    private static void onState(ServerPlayer player, byte[] data) {
        Session s = session(player);
        if (!s.hasMod) {
            s.hasMod = true;
            sendConfig(player);
        }
        RemoteState state = RemoteState.decode(data);
        if (state == null) {
            return;
        }
        s.lastPacketTick = tick;
        if (!state.active || !isPlausible(player, state)) {
            s.state = null;
            s.removeModel();
            return;
        }
        s.state = state;
        s.dirty = true;
    }

    /** Дрон должен быть рядом с игроком и в загруженном чанке. */
    private static boolean isPlausible(ServerPlayer player, RemoteState state) {
        Vec3 eye = player.getEyePosition();
        double dx = state.x - eye.x;
        double dy = state.y - eye.y;
        double dz = state.z - eye.z;
        double limit = maxRange > 0.0F ? maxRange + 16.0 : 512.0;
        if (dx * dx + dy * dy + dz * dz > limit * limit) {
            return false;
        }
        return player.level().hasChunkAt(BlockPos.containing(state.x, state.y, state.z));
    }

    private static void onTick(MinecraftServer server) {
        tick++;
        Iterator<Session> it = SESSIONS.values().iterator();
        while (it.hasNext()) {
            Session s = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(s.player);
            if (player == null) {
                s.removeAll();
                it.remove();
                continue;
            }
            if (s.state != null && tick - s.lastPacketTick > timeoutTicks) {
                s.state = null;
            }
            ServerLevel level = (ServerLevel) player.level();
            if (s.state == null) {
                s.removeModel();
            } else {
                if (s.model != null && (s.model.level() != level || !s.model.isValid())) {
                    s.removeModel();
                }
                if (s.model == null) {
                    s.model = new ServerDroneModel(level, s.player, s.state, s.colors, modelScale, viewRange, brightModel, glowOutline);
                    s.dirty = true;
                }
                if (s.dirty) {
                    s.dirty = false;
                    s.model.update(s.state);
                }
            }
            // След
            if (trailEnabled) {
                if (s.trail != null && s.trail.level() != level) {
                    s.trail.clear();
                    s.trail = null;
                }
                if (s.trail == null && s.state != null) {
                    s.trail = new DroneTrail(level, s.player, !trailVisibleToPilot);
                }
                if (s.trail != null) {
                    boolean flying = s.state != null && s.state.armed && !s.state.crashed;
                    s.trail.tick(tick, s.state, s.colors[6], flying,
                        Math.max(10, Math.round(trailSeconds * 20.0F)), trailWidth, 400);
                    if (s.state == null && s.trail.isEmpty()) {
                        s.trail = null;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ скрытие от пилота

    static void registerPart(Entity part, UUID owner) {
        PART_OWNERS.put(part, owner);
    }

    static void unregisterPart(Entity part) {
        PART_OWNERS.remove(part);
    }

    public static boolean isPart(Entity entity) {
        return PART_OWNERS.containsKey(entity);
    }

    public static boolean isHiddenFrom(Entity entity, ServerPlayer player) {
        UUID owner = PART_OWNERS.get(entity);
        if (owner == null || !owner.equals(player.getUUID())) return false;
        Session session = SESSIONS.get(owner);
        return session != null && session.state != null && session.state.viewingDrone;
    }

    // ------------------------------------------------------------------ config/fpvdrone-server.properties

    private static void loadConfig() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("fpvdrone-server.properties");
        Properties p = new Properties();
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                FpvDroneMod.LOGGER.warn("[fpvdrone] Не удалось прочитать {}", path, e);
            }
        }
        maxRange = readFloat(p, "max-range", 128.0F, 0.0F, 2048.0F);
        modelScale = readFloat(p, "model-scale", 1.5F, 0.3F, 5.0F);
        viewRange = readFloat(p, "view-range", 2.0F, 0.2F, 10.0F);
        timeoutTicks = (int) readFloat(p, "timeout-ticks", 40.0F, 5.0F, 400.0F);
        brightModel = readBool(p, "bright-model", true);
        glowOutline = readBool(p, "glow-outline", false);
        trailEnabled = readBool(p, "trail-enabled", true);
        trailSeconds = readFloat(p, "trail-seconds", 3.0F, 0.5F, 30.0F);
        trailWidth = readFloat(p, "trail-width", 0.06F, 0.01F, 0.5F);
        trailVisibleToPilot = readBool(p, "trail-visible-to-pilot", true);
        p.setProperty("max-range", Float.toString(maxRange));
        p.setProperty("model-scale", Float.toString(modelScale));
        p.setProperty("view-range", Float.toString(viewRange));
        p.setProperty("timeout-ticks", Integer.toString(timeoutTicks));
        p.setProperty("bright-model", Boolean.toString(brightModel));
        p.setProperty("glow-outline", Boolean.toString(glowOutline));
        p.setProperty("trail-enabled", Boolean.toString(trailEnabled));
        p.setProperty("trail-seconds", Float.toString(trailSeconds));
        p.setProperty("trail-width", Float.toString(trailWidth));
        p.setProperty("trail-visible-to-pilot", Boolean.toString(trailVisibleToPilot));
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                p.store(w, "FPV Drone server. max-range: blocks from player (0 = loaded chunks). model-scale: drone size. bright-model: full brightness at night. glow-outline: outline through walls. trail-*: translucent trail behind drones");
            }
        } catch (IOException e) {
            FpvDroneMod.LOGGER.warn("[fpvdrone] Не удалось сохранить {}", path, e);
        }
    }

    private static boolean readBool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static float readFloat(Properties p, String key, float def, float min, float max) {
        try {
            String v = p.getProperty(key);
            float f = v == null ? def : Float.parseFloat(v.trim());
            return Math.max(min, Math.min(max, f));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
