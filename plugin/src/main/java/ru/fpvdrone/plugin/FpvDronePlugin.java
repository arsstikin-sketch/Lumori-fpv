package ru.fpvdrone.plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Серверная часть FPV-дронов. Принимает от мода состояние дрона и показывает его всем игрокам.
 */
public final class FpvDronePlugin extends JavaPlugin implements Listener, PluginMessageListener {
    public static final String CHANNEL_STATE = "fpvdrone:state";
    public static final String CHANNEL_HELLO = "fpvdrone:hello";
    public static final String CHANNEL_CONFIG = "fpvdrone:config";
    public static final String CHANNEL_COLORS = "fpvdrone:colors";

    private final Map<UUID, PilotSession> sessions = new HashMap<>();
    private TestDrone testDrone;
    private long tick;

    // Настройки из config.yml
    private float maxRange;
    private final float[] fixedDamage = new float[5];
    private byte fixedLiquids = -1;
    private float modelScale;
    private float viewRange;
    private int timeoutTicks;
    private boolean hideOwnDrone;
    /** always-show, always-hide, hide-while-flying */
    private String ownDroneMode;
    private double ownShowDistance;
    private boolean birdRefreshPending;
    private long lastBirdRefresh;
    private boolean logRejected;
    private boolean brightModel;
    private boolean glowOutline;
    private boolean trailEnabled;
    private float trailSeconds;
    private float trailWidth;
    private boolean trailVisibleToPilot;

    // Скины мобов
    private int stabilizationPolicy;
    private boolean skinsEnabled;
    private final boolean entityMode = true;   // ресурспаков больше нет: только настоящие мобы и птицы
    private double predictionTicks;
    private String defaultSkin;
    private float skinSizeMul;
    private double skinYawOffsetDeg;
    private int menuDarkLayers;
    private SkinCatalog catalog;
    private final SkinMenus skinMenus = new SkinMenus(this);
    private NamespacedKey skinKey;

    // Птицы из голов и блоков (без модов и без пака)
    private boolean birdsEnabled;
    private double birdSizeMul;
    private double birdMinSize;
    private double birdMaxSize;
    private String mineskinKey;
    private int mineskinPerMinute;
    private BirdData.Root birdRoot;
    private final java.util.List<SkinCatalog.Mob> birdMobs = new java.util.ArrayList<>();
    private final BirdHeads birdHeads = new BirdHeads(this);
    private final AdminPanel adminPanel = new AdminPanel(this);
    private final HeadPreloader headPreloader = new HeadPreloader(this);
    /** Размеры скинов по умолчанию (до правок в панели). */
    private final Map<String, Float> baseSizes = new HashMap<>();

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.skinKey = new NamespacedKey(this, "flyskin");
        this.loadSettings();
        this.buildSkins();

        this.getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_STATE, this);
        this.getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_HELLO, this);
        this.getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_COLORS, this);
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_CONFIG);
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getServer().getPluginManager().registerEvents(this.skinMenus, this);
        this.getServer().getPluginManager().registerEvents(this.adminPanel, this);

        DroneCommand command = new DroneCommand(this);
        PluginCommand drone = this.getCommand("drone");
        if (drone != null) {
            drone.setExecutor(command);
            drone.setTabCompleter(command);
        }
        FlySkinCommand flySkin = new FlySkinCommand(this);
        PluginCommand fly = this.getCommand("flyskin");
        if (fly != null) {
            fly.setExecutor(flySkin);
            fly.setTabCompleter(flySkin);
        }
        this.getServer().getScheduler().runTaskTimer(this, this::onTick, 1L, 1L);
        // Догрузка скинов голов: сама стартует через 10 с после запуска, сторож раз в минуту перезапускает зависшую.
        this.getServer().getScheduler().runTaskLater(this, () -> this.autoUpload(false), 200L);
        this.getServer().getScheduler().runTaskTimer(this, () -> this.autoUpload(true), 1200L, 1200L);
        this.getLogger().info("FPV Drone включён. Дальность: " + (this.maxRange > 0 ? this.maxRange + " блоков" : "выбор игрока (до 512 блоков)"));
    }

    @Override
    public void onDisable() {
        this.birdHeads.stop();
        this.headPreloader.stopAll();
        this.adminPanel.closeAll();
        this.skinMenus.closeAll();
        for (PilotSession session : this.sessions.values()) {
            session.removeAll();
        }
        this.sessions.clear();
        if (this.testDrone != null) {
            this.testDrone.remove();
            this.testDrone = null;
        }
    }

    void loadSettings() {
        this.reloadConfig();
        this.maxRange = (float) Math.max(0.0, this.getConfig().getDouble("max-range", 0.0));
        String[] paths = {"durability", "safe-contact", "propeller-break", "motor-break", "frame-destroy"};
        for (int i = 0; i < paths.length; i++) {
            double value = this.getConfig().getDouble("damage." + paths[i], 0.0);
            this.fixedDamage[i] = Double.isFinite(value) && value > 0 ? (float) Math.min(1000, value) : 0;
        }
        String liquid = this.getConfig().getString("damage.liquids-destroy", "client");
        this.fixedLiquids = switch (liquid == null ? "" : liquid.toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> 1; case "false" -> 0; default -> -1;
        };
        this.modelScale = (float) Math.max(0.3, Math.min(5.0, this.getConfig().getDouble("model-scale", 1.5)));
        this.viewRange = (float) Math.max(0.2, Math.min(10.0, this.getConfig().getDouble("view-range", 2.0)));
        this.timeoutTicks = Math.max(5, this.getConfig().getInt("timeout-ticks", 40));
        this.hideOwnDrone = this.getConfig().getBoolean("hide-own-drone", true);
        String own = this.getConfig().getString("own-drone", this.hideOwnDrone ? "hide-while-flying" : "always-show");
        own = own == null ? "hide-while-flying" : own.trim().toLowerCase(java.util.Locale.ROOT);
        if (!own.equals("always-show") && !own.equals("always-hide")) {
            own = "hide-while-flying";
        }
        this.ownDroneMode = own;
        this.ownShowDistance = Math.max(0.0, this.getConfig().getDouble("own-drone-show-distance", 2.0));
        this.logRejected = this.getConfig().getBoolean("log-rejected-packets", true);
        this.brightModel = this.getConfig().getBoolean("bright-model", true);
        this.glowOutline = this.getConfig().getBoolean("glow-outline", false);
        this.trailEnabled = this.getConfig().getBoolean("trail.enabled", true);
        this.trailSeconds = (float) Math.max(0.5, Math.min(30.0, this.getConfig().getDouble("trail.seconds", 3.0)));
        this.trailWidth = (float) Math.max(0.01, Math.min(0.5, this.getConfig().getDouble("trail.width", 0.06)));
        this.trailVisibleToPilot = this.getConfig().getBoolean("trail.visible-to-pilot", true);
        String stabilization = this.getConfig().getString("stabilization.allowed", "All");
        this.stabilizationPolicy = switch (stabilization.toLowerCase(java.util.Locale.ROOT)) {
            case "acro" -> 1;
            case "level" -> 2;
            default -> 0;
        };
        this.skinsEnabled = this.getConfig().getBoolean("skins.enabled", true);
        this.predictionTicks = Math.max(0.0, Math.min(6.0, this.getConfig().getDouble("prediction-ticks",
            3.0)));
        int smooth = Math.max(0, Math.min(3, this.getConfig().getInt("smoothing-ticks", 1)));
        DroneVisual.smoothTicks = smooth;
        this.defaultSkin = this.getConfig().getString("skins.default", "phantom");
        this.skinSizeMul = (float) Math.max(0.1, Math.min(10.0, this.getConfig().getDouble("skins.size-multiplier", 1.0)));
        this.skinYawOffsetDeg = this.getConfig().getDouble("skins.model-yaw-offset", 0.0);
        this.menuDarkLayers = Math.max(0, Math.min(4, this.getConfig().getInt("skins.menu-darkness", 2)));
        this.birdsEnabled = this.getConfig().getBoolean("birds.enabled", true);
        this.birdSizeMul = Math.max(0.1, Math.min(10.0, this.getConfig().getDouble("birds.size-multiplier", 2.0)));
        this.birdMinSize = Math.max(0.1, this.getConfig().getDouble("birds.min-size", 0.6));
        this.birdMaxSize = Math.max(this.birdMinSize, this.getConfig().getDouble("birds.max-size", 2.5));
        this.mineskinKey = this.getConfig().getString("birds.mineskin-key", "");
        this.mineskinPerMinute = Math.max(1, Math.min(600, this.getConfig().getInt("birds.mineskin-per-minute", 18)));
        BirdVisual.smoothTicks = Math.max(1, Math.min(6, this.getConfig().getInt("birds.interpolation-ticks", 3)));
        BirdVisual.smoothRate = Math.max(2.0, Math.min(40.0, this.getConfig().getDouble("birds.smoothing", 10.0)));
        BirdVisual.maxFlapHz = Math.max(1.0, Math.min(10.0, this.getConfig().getDouble("birds.max-flap-hz", 4.0)));
        BirdVisual.headSize = Math.max(0.05, this.getConfig().getDouble("birds.head-size", 0.5));
        BirdVisual.headYawDeg = this.getConfig().getDouble("birds.head-yaw", 0.0);
        BirdVisual.headOffsetY = this.getConfig().getDouble("birds.head-offset-y", 0.0);
        try {
            BirdVisual.headTransform = org.bukkit.entity.ItemDisplay.ItemDisplayTransform.valueOf(
                this.getConfig().getString("birds.head-transform", "NONE").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            BirdVisual.headTransform = org.bukkit.entity.ItemDisplay.ItemDisplayTransform.NONE;
        }
    }

    /** Птицы: модели из jar и скины голов из bird-heads.yml. */
    private void buildBirds() {
        this.birdMobs.clear();
        this.birdHeads.load();
        if (!this.birdsEnabled) {
            return;
        }
        try {
            if (this.birdRoot == null) {
                this.birdRoot = BirdData.load(this.getClassLoader());
            }
        } catch (Exception e) {
            this.getLogger().log(Level.SEVERE, "Не удалось загрузить птиц (birds/birds.json)", e);
            return;
        }
        for (BirdData.Bird b : this.birdRoot.birds) {
            String id = b.id;
            if (this.catalog != null && this.catalog.mob(id) != null) {
                id = "bird_" + id;
            }
            SkinCatalog.Mob mob = new SkinCatalog.Mob(id, b.name + (b.fantasy() ? " ✦" : ""), 1, false, 0.0F, false,
                (float) this.birdSize(b), 0.0F, null);
            mob.bird = b;
            for (int i = 0; i < b.variants.size(); i++) {
                BirdData.Variant v = b.variants.get(i);
                String vid = slug(v.name, i);
                SkinCatalog.Variant sv = new SkinCatalog.Variant(vid, v.name, 1.0F, new String[0], new int[0][]);
                sv.birdVariant = i;
                mob.variant(sv);
            }
            this.birdMobs.add(mob);
        }
        java.util.List<String> all = BirdData.allSkins(this.birdRoot);
        this.getLogger().info("Птицы: " + this.birdMobs.size() + " видов, скинов голов загружено " + this.birdHeads.loaded(all)
            + " из " + all.size() + (this.birdHeads.loaded(all) < all.size()
            ? " (пока не загружены — части рисуются блоками; загрузить: /flyskin heads upload)" : ""));
    }

    private static String slug(String name, int index) {
        String[][] map = {{"а","a"},{"б","b"},{"в","v"},{"г","g"},{"д","d"},{"е","e"},{"ё","e"},{"ж","zh"},{"з","z"},{"и","i"},{"й","y"},
            {"к","k"},{"л","l"},{"м","m"},{"н","n"},{"о","o"},{"п","p"},{"р","r"},{"с","s"},{"т","t"},{"у","u"},{"ф","f"},{"х","h"},
            {"ц","c"},{"ч","ch"},{"ш","sh"},{"щ","sch"},{"ъ",""},{"ы","y"},{"ь",""},{"э","e"},{"ю","yu"},{"я","ya"}};
        String s = name.toLowerCase(java.util.Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (char ch : s.toCharArray()) {
            String rep = null;
            for (String[] m : map) {
                if (m[0].charAt(0) == ch) {
                    rep = m[1];
                    break;
                }
            }
            if (rep != null) {
                out.append(rep);
            } else if (Character.isLetterOrDigit(ch)) {
                out.append(ch);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
        }
        String r = out.toString().replaceAll("_+$", "");
        return r.isEmpty() ? "v" + index : r;
    }

    double birdSize(BirdData.Bird b) {
        return Math.max(this.birdMinSize, Math.min(this.birdMaxSize, b.len * this.birdSizeMul));
    }

    BirdHeads birdHeads() {
        return this.birdHeads;
    }

    java.util.List<String> birdSkins() {
        return this.birdRoot == null ? java.util.List.of() : BirdData.allSkins(this.birdRoot);
    }

    String mineskinKey() {
        return this.mineskinKey;
    }

    int mineskinPerMinute() {
        return this.mineskinPerMinute;
    }

    /** Все скины для меню и команд: мобы/свои скины + птицы. */
    java.util.List<SkinCatalog.Mob> allMobs() {
        java.util.List<SkinCatalog.Mob> list = new java.util.ArrayList<>(this.catalog.mobs());
        list.addAll(this.birdMobs);
        return list;
    }

    SkinCatalog.Mob findMob(String id) {
        SkinCatalog.Mob m = this.catalog.mob(id);
        if (m != null) {
            return m;
        }
        for (SkinCatalog.Mob b : this.birdMobs) {
            if (b.id.equalsIgnoreCase(id)) {
                return b;
            }
        }
        return null;
    }

    /**
     * Своя модель для пилота: «hide-while-flying» — не видна, пока дрон армлен (игрок управляет),
     * и видна, когда дрон выключен и стоит/лежит дальше own-drone-show-distance от глаз (игрок вернулся к себе).
     */
    private void updateOwnVisibility(PilotSession session, Player player) {
        if (session.visual == null || session.state == null) {
            return;
        }
        boolean want;
        switch (this.ownDroneMode) {
            case "always-show" -> want = true;
            case "always-hide" -> want = false;
            default -> {
                DroneState st = session.state;
                Location eye = player.getEyeLocation();
                double dx = st.x - eye.getX();
                double dy = st.y - eye.getY();
                double dz = st.z - eye.getZ();
                boolean far = dx * dx + dy * dy + dz * dz > this.ownShowDistance * this.ownShowDistance;
                want = (!st.armed || st.crashed) && far;
            }
        }
        if (want != session.ownVisible) {
            session.visual.setVisibleFor(this, player, want);
            session.ownVisible = want;
        }
    }

    // ---------------------------------------------------------------- панель /flyskin admin

    String stabilizationLabel() {
        return switch (this.stabilizationPolicy) { case 1 -> "Acro"; case 2 -> "Level"; default -> "All"; };
    }

    void cycleStabilization() {
        this.stabilizationPolicy = (this.stabilizationPolicy + 1) % 3;
        this.getConfig().set("stabilization.allowed", this.stabilizationLabel());
        this.saveConfig();
        for (Player player : this.getServer().getOnlinePlayers()) this.sendConfig(player);
    }

    AdminPanel adminPanel() {
        return this.adminPanel;
    }

    double defaultSize(SkinCatalog.Mob m) {
        Float f = this.baseSizes.get(m.id);
        return f != null ? f : m.size;
    }

    String defaultSkinId() {
        SkinCatalog.Variant configured = this.parseSkin(this.defaultSkin);
        if (configured != null) return configured.mob.id;
        for (SkinCatalog.Mob mob : this.allMobs()) {
            if (mob.bird != null || MobEntities.supports(mob)) return mob.id;
        }
        return "phantom";
    }

    private void applySizes() {
        for (SkinCatalog.Mob m : this.allMobs()) {
            Double ov = this.adminPanel.sizeOverride(m);
            m.size = (float) (ov != null ? ov : this.defaultSize(m));
        }
    }

    /** Галочка или размер поменялись в панели: применить сразу ко всем летающим. */
    void applyAdminSettings(java.util.Set<String> affected) {
        this.skinMenus.closeAll();
        this.applySizes();
        for (PilotSession session : this.sessions.values()) {
            Player player = this.getServer().getPlayer(session.playerId);
            if (player != null && this.skinAffected(player, affected)) {
                session.removeVisual();
                session.dirty = true;
            }
        }
        if (this.testDrone != null) {
            Player player = this.getServer().getPlayer(this.testDrone.owner());
            if (player != null && this.skinAffected(player, affected)) {
                int left = this.testDrone.ticksLeft();
                this.testDrone.remove();
                this.testDrone = new TestDrone(this, player, left);
            }
        }
    }

    private boolean skinAffected(Player player, java.util.Set<String> affected) {
        SkinCatalog.Variant stored = this.parseSkin(player.getPersistentDataContainer().get(this.skinKey, PersistentDataType.STRING));
        return (stored != null && affected.contains(stored.mob.id.toLowerCase(java.util.Locale.ROOT)))
            || affected.contains(this.skinOf(player).mob.id.toLowerCase(java.util.Locale.ROOT));
    }

    /** Скины голов догрузились — пересоздать птиц не чаще раза в 10 секунд (иначе модель «мигает»). */
    void refreshBirdVisuals() {
        this.birdRefreshPending = true;
    }

    /** Сам догружает недостающие головы (birds.auto-upload). watchdog=true — только если загрузка зависла или прервалась. */
    private void autoUpload(boolean watchdog) {
        if (!this.birdsEnabled || this.birdRoot == null || !this.getConfig().getBoolean("birds.auto-upload", true)) {
            return;
        }
        java.util.List<String> all = this.birdSkins();
        if (this.birdHeads.loaded(all) >= all.size()) {
            return;
        }
        if (this.birdHeads.stalled()) {
            this.getLogger().warning("Загрузка скинов голов зависла — перезапускаю");
            this.birdHeads.forceReset();
        } else if (this.birdHeads.busy() || (watchdog && this.birdHeads.stoppedByUser())) {
            return;
        }
        int perMinute = this.mineskinPerMinute;
        if (this.mineskinKey == null || this.mineskinKey.isBlank()) {
            perMinute = Math.min(perMinute, 6);
        }
        this.birdHeads.uploadMissing(all, this.mineskinKey, perMinute, this.getServer().getConsoleSender(), this::refreshBirdVisuals);
    }

    /** Загрузка голов закончилась — перерисовать птиц и прогрузить головы у всех игроков онлайн. */
    void onHeadsUploaded() {
        this.birdRefreshPending = true;
        long delay = 20L;
        for (Player p : this.getServer().getOnlinePlayers()) {
            this.headPreloader.schedule(p, delay);
            delay += 40L;
        }
    }

    HeadPreloader headPreloader() {
        return this.headPreloader;
    }

    void doRefreshBirdVisuals() {
        for (PilotSession session : this.sessions.values()) {
            if (session.visual instanceof BirdVisual) {
                session.removeVisual();
                session.dirty = true;
            }
        }
    }

    /** Собрать каталог мобов и ресурспак. */
    private void buildSkins() {
        Map<String, String> overrides = new HashMap<>();
        ConfigurationSection tex = this.getConfig().getConfigurationSection("skins.texture-overrides");
        if (tex != null) {
            for (String key : tex.getKeys(false)) {
                String value = tex.getString(key);
                if (value != null) {
                    overrides.put(key.contains(":") ? key : "minecraft:" + key, value.contains(":") ? value : "minecraft:" + value);
                }
            }
        }
        Map<String, Double> sizes = new HashMap<>();
        ConfigurationSection sz = this.getConfig().getConfigurationSection("skins.sizes");
        if (sz != null) {
            for (String key : sz.getKeys(false)) {
                sizes.put(key, sz.getDouble(key));
            }
        }
        java.util.List<SkinCatalog.Mob> custom = java.util.List.of();
        this.catalog = new SkinCatalog(overrides, sizes, custom);
        this.buildBirds();
        this.baseSizes.clear();
        for (SkinCatalog.Mob m : this.allMobs()) {
            this.baseSizes.put(m.id, m.size);
        }
        this.adminPanel.load();
        this.applySizes();
    }

    void reloadAll() {
        this.adminPanel.closeAll();
        this.skinMenus.closeAll();
        this.loadSettings();
        this.buildSkins();
        for (PilotSession session : this.sessions.values()) {
            session.removeVisual(); // пересоздастся со следующим пакетом с новым масштабом
            Player player = this.getServer().getPlayer(session.playerId);
            if (player != null && session.hasMod) {
                this.sendConfig(player);
            }
        }
    }

    // ---------------------------------------------------------------- сеть

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        PilotSession session = this.sessions.computeIfAbsent(player.getUniqueId(), PilotSession::new);
        if (CHANNEL_HELLO.equals(channel)) {
            session.hasMod = true;
            this.sendConfig(player);
            this.getLogger().info(player.getName() + " подключился с модом FPV Drone");
            return;
        }
        if (CHANNEL_COLORS.equals(channel)) {
            // Цвета, выбранные в настройках мода (7 байт: рама, лучи, пропеллеры, моторы, камера, батарея, подсветка).
            if (message.length >= 8 && message[0] == DroneState.VERSION) {
                DroneColors colors = new DroneColors();
                DroneColors.Part[] parts = DroneColors.Part.values();
                for (int i = 0; i < parts.length && i + 1 < message.length; i++) {
                    int index = message[i + 1] & 0xFF;
                    if (index < DroneColors.COLORS.size()) {
                        colors.set(parts[i], DroneColors.COLORS.get(index));
                    }
                }
                this.applyColors(player, colors);
            }
            return;
        }
        if (!CHANNEL_STATE.equals(channel)) {
            return;
        }
        if (!session.hasMod) {
            // hello мог потеряться — всё равно отправим настройки.
            session.hasMod = true;
            this.sendConfig(player);
        }
        DroneState state = DroneState.decode(message);
        if (state == null) {
            return;
        }
        session.lastPacketTick = this.tick;
        if (!state.active) {
            session.state = null;
            session.removeVisual();
            return;
        }
        if (this.stabilizationPolicy != 0 && (!state.modeReported
                || state.levelMode != (this.stabilizationPolicy == 2))) {
            session.state = null;
            session.removeVisual();
            if (session.rejected++ % 100 == 0) {
                this.sendConfig(player);
                player.sendMessage(net.kyori.adventure.text.Component.text("FPV Drone: сервер разрешает только "
                    + (this.stabilizationPolicy == 1 ? "Acro" : "Level") + ". Обновите мод до 0.5.0."));
            }
            return;
        }
        if (!this.isPlausible(player, state)) {
            session.rejected++;
            if (this.logRejected && session.rejected % 100 == 1) {
                this.getLogger().warning("Отклонено положение дрона " + player.getName() + " (слишком далеко от игрока / незагруженный чанк)");
            }
            session.state = null;
            session.removeVisual();
            return;
        }
        // Скорость дрона (блоков за тик) — для упреждения.
        if (session.state != null && this.tick > session.stateTick) {
            double dt = this.tick - session.stateTick;
            double vx = (state.x - session.state.x) / dt;
            double vy = (state.y - session.state.y) / dt;
            double vz = (state.z - session.state.z) / dt;
            double v2 = vx * vx + vy * vy + vz * vz;
            if (v2 < 9.0) { // больше 60 блоков/с — это телепорт/респаун, а не полёт
                session.vx = vx;
                session.vy = vy;
                session.vz = vz;
            } else {
                session.vx = session.vy = session.vz = 0.0;
            }
        }
        session.stateTick = this.tick;
        session.state = state;
        session.dirty = true;
    }

    private void sendConfig(Player player) {
        player.sendPluginMessage(this, CHANNEL_CONFIG, DroneState.encodeConfig(this.maxRange, this.stabilizationPolicy, this.fixedDamage, this.fixedLiquids));
    }

    /** Дрон должен быть рядом с игроком и в загруженных чанках. */
    private boolean isPlausible(Player player, DroneState state) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        double dx = state.x - eye.getX();
        double dy = state.y - eye.getY();
        double dz = state.z - eye.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        double limit = this.maxRange > 0.0F ? this.maxRange + 16.0 : 528.0;
        if (distSq > limit * limit) {
            return false;
        }
        int cx = (int) Math.floor(state.x) >> 4;
        int cz = (int) Math.floor(state.z) >> 4;
        return world.isChunkLoaded(cx, cz);
    }

    // ---------------------------------------------------------------- тики

    private void onTick() {
        this.tick++;
        Iterator<PilotSession> it = this.sessions.values().iterator();
        while (it.hasNext()) {
            PilotSession session = it.next();
            Player player = this.getServer().getPlayer(session.playerId);
            if (player == null) {
                session.removeAll();
                it.remove();
                continue;
            }
            if (session.state != null && this.tick - session.lastPacketTick > this.timeoutTicks) {
                session.state = null; // мод перестал слать пакеты (вылет, лаг)
            }
            this.tickTrail(session, player);
            if (session.state == null) {
                session.removeVisual();
                continue;
            }
            // Птиц обновляем каждый тик (плавность), остальные — когда пришёл пакет.
            if (!session.dirty && session.visual != null && !(session.visual instanceof BirdVisual)) {
                this.updateOwnVisibility(session, player);
                continue;
            }
            session.dirty = false;
            if (session.visual != null && !session.visual.isValid()) {
                session.removeVisual();
            }
            if (session.visual == null) {
                Location at = new Location(player.getWorld(), session.state.x, session.state.y, session.state.z);
                boolean hideAtStart = !"always-show".equals(this.ownDroneMode);
                session.visual = this.createVisual(player, at, hideAtStart ? player : null, this.brightModel, this.glowOutline);
                session.ownVisible = !hideAtStart;
            }
            DroneState shown = session.state;
            double lead = this.predictionTicks + (this.tick - session.stateTick);
            if (lead > 0.0 && shown.armed && !shown.crashed) {
                lead = Math.min(lead, this.predictionTicks + 2.0);
                shown = shown.shifted(session.vx * lead, session.vy * lead, session.vz * lead);
            }
            session.visual.update(player.getWorld(), shown);
            this.updateOwnVisibility(session, player);
        }
        if (this.birdRefreshPending && this.tick - this.lastBirdRefresh > 200) {
            this.birdRefreshPending = false;
            this.lastBirdRefresh = this.tick;
            this.doRefreshBirdVisuals();
        }
        this.skinMenus.tick();
        if (this.testDrone != null && !this.testDrone.tick()) {
            this.testDrone.remove();
            this.testDrone = null;
        }
    }

    private void tickTrail(PilotSession session, Player player) {
        if (!this.trailEnabled) {
            return;
        }
        if (session.trail != null && session.trail.world() != player.getWorld()) {
            session.trail.clear();
            session.trail = null;
        }
        if (session.trail == null && session.state != null) {
            session.trail = new DroneTrail(this, player.getWorld(), this.trailVisibleToPilot ? null : player);
        }
        if (session.trail == null) {
            return;
        }
        boolean flying = session.state != null && session.state.armed && !session.state.crashed;
        DroneColors colors = this.colorsOf(player);
        session.trail.tick(this.tick, session.state, colors.glass(DroneColors.Part.LED), flying,
            Math.max(10, Math.round(this.trailSeconds * 20.0F)), this.trailWidth, 400);
        if (session.state == null && session.trail.isEmpty()) {
            session.trail = null;
        }
    }

    // ---------------------------------------------------------------- события

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Если мод уже прислал hello до входа — ответим, когда придёт следующий пакет.
        this.sessions.computeIfAbsent(event.getPlayer().getUniqueId(), PilotSession::new);
        this.headPreloader.schedule(event.getPlayer(), 100L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PilotSession session = this.sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            session.removeAll();
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PilotSession session = this.sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            session.state = null;
            session.removeVisual();
        }
    }

    // ---------------------------------------------------------------- для команд

    DroneColors colorsOf(Player player) {
        return DroneColors.load(this, player);
    }

    void applyColors(Player player, DroneColors colors) {
        colors.save(this, player);
        PilotSession session = this.sessions.get(player.getUniqueId());
        if (session != null && session.visual != null) {
            session.visual.setColors(colors);
            session.dirty = true;
        }
        if (this.testDrone != null && this.testDrone.owner().equals(player.getUniqueId())) {
            this.testDrone.setColors(colors);
        }
    }

    void startTestDrone(Player player, int seconds) {
        if (this.testDrone != null) {
            this.testDrone.remove();
        }
        this.testDrone = new TestDrone(this, player, seconds * 20);
    }

    // ---------------------------------------------------------------- скины

    /** Модель дрона для игрока-владельца: скин моба или (если скины выключены) модель дрона. */
    SkinVisual createVisual(Player owner, Location at, Player hideFrom, boolean bright, boolean glow) {
        DroneColors colors = this.colorsOf(owner);
        if (this.skinsEnabled) {
            SkinCatalog.Variant bv = this.skinOf(owner);
            if (bv.mob.bird != null) {
                return new BirdVisual(this, this.birdHeads, at, bv.mob.bird, bv.birdVariant, bv.mob.size * this.skinSizeMul,
                    hideFrom, null, this.viewRange, bright);
            }
        }
        if (this.skinsEnabled && this.entityMode) {
            SkinCatalog.Variant v = this.skinOf(owner);
            return new MobEntityVisual(this, at, v, v.mob.size * this.skinSizeMul, hideFrom, glow);
        }
        return new DroneVisual(this, at, colors, this.modelScale, hideFrom, this.viewRange, bright, glow);
    }

    SkinCatalog.Variant skinOf(Player player) {
        String stored = player.getPersistentDataContainer().get(this.skinKey, PersistentDataType.STRING);
        SkinCatalog.Variant v = this.parseSkin(stored);
        if (v != null && !this.adminPanel.enabled(v.mob)) {
            v = null; // скин выключен в панели — летает скин по умолчанию
        }
        if (v == null) {
            v = this.parseSkin(this.defaultSkin);
            if (v == null) v = this.parseSkin(this.defaultSkinId());
        }
        if (v == null) {
            java.util.List<SkinCatalog.Mob> menu = this.menuMobs();
            v = !menu.isEmpty() ? menu.get(0).variants.get(0) : this.allMobs().get(0).variants.get(0);
        }
        return v;
    }

    private SkinCatalog.Variant parseSkin(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split(":", 2);
        SkinCatalog.Mob mob = this.findMob(parts[0]);
        if (mob == null || (this.entityMode && mob.bird == null && !MobEntities.supports(mob))) {
            return null;
        }
        SkinCatalog.Variant v = parts.length > 1 ? mob.variant(parts[1]) : null;
        return v != null ? v : mob.variants.get(0);
    }

    /** null — сбросить на скин по умолчанию. */
    void setSkin(Player player, SkinCatalog.Variant variant) {
        if (variant == null) {
            player.getPersistentDataContainer().remove(this.skinKey);
        } else {
            player.getPersistentDataContainer().set(this.skinKey, PersistentDataType.STRING, variant.mob.id + ":" + variant.id);
        }
        PilotSession session = this.sessions.get(player.getUniqueId());
        if (session != null) {
            session.removeVisual(); // пересоздастся со следующим пакетом
            session.dirty = true;
        }
        if (this.testDrone != null && this.testDrone.owner().equals(player.getUniqueId())) {
            int left = this.testDrone.ticksLeft();
            this.testDrone.remove();
            this.testDrone = new TestDrone(this, player, left);
        }
    }

    /** Мобы, которые можно выбрать: без ресурспака — только настоящие (свои скины требуют пак). */
    java.util.List<SkinCatalog.Mob> menuMobs() {
        java.util.List<SkinCatalog.Mob> list = new java.util.ArrayList<>();
        // сначала птицы (без модов и без пака), потом мобы
        for (SkinCatalog.Mob mob : this.birdMobs) {
            if (this.adminPanel.enabled(mob)) {
                list.add(mob);
            }
        }
        for (SkinCatalog.Mob mob : this.catalog.mobs()) {
            if ((!this.entityMode || MobEntities.supports(mob)) && this.adminPanel.enabled(mob)) {
                list.add(mob);
            }
        }
        if (list.isEmpty()) {
            SkinCatalog.Mob d = this.findMob(this.defaultSkinId());
            if (d != null) {
                list.add(d);
            }
        }
        return list;
    }

    boolean entityMode() {
        return this.entityMode;
    }

    // ---------------------------------------------------------------- защита мобов-скинов

    @EventHandler(ignoreCancelled = true)
    public void onSkinInteract(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getScoreboardTags().contains(MobEntities.TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSkinDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity().getScoreboardTags().contains(MobEntities.TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSkinCombust(org.bukkit.event.entity.EntityCombustEvent event) {
        if (event.getEntity().getScoreboardTags().contains(MobEntities.TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSkinTarget(org.bukkit.event.entity.EntityTargetEvent event) {
        if (event.getEntity().getScoreboardTags().contains(MobEntities.TAG)) {
            event.setCancelled(true);
        }
    }

    boolean isFlying(Player player) {
        PilotSession session = this.sessions.get(player.getUniqueId());
        return session != null && session.state != null;
    }

    boolean skinsEnabled() {
        return this.skinsEnabled;
    }

    SkinCatalog catalog() {
        return this.catalog;
    }

    SkinMenus skinMenus() {
        return this.skinMenus;
    }

    int menuDarkLayers() {
        return this.menuDarkLayers;
    }

    double skinYawOffsetRad() {
        return Math.toRadians(this.skinYawOffsetDeg);
    }

    Map<UUID, PilotSession> sessions() {
        return this.sessions;
    }

    float maxRange() {
        return this.maxRange;
    }

    /** Данные одного игрока. */
    static final class PilotSession {
        final UUID playerId;
        boolean hasMod;
        DroneState state;
        boolean dirty;
        long lastPacketTick;
        int rejected;
        SkinVisual visual;
        boolean ownVisible;
        DroneTrail trail;
        long stateTick;
        double vx;
        double vy;
        double vz;

        PilotSession(UUID playerId) {
            this.playerId = playerId;
        }

        void removeVisual() {
            if (this.visual != null) {
                this.visual.remove();
                this.visual = null;
            }
            this.ownVisible = false;
        }

        void removeAll() {
            this.removeVisual();
            if (this.trail != null) {
                this.trail.clear();
                this.trail = null;
            }
        }
    }
}
