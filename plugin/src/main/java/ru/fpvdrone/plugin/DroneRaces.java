package ru.fpvdrone.plugin;

import java.io.File;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Личные прохождения трасс. Порядок ворот определяется порядком их добавления. */
final class DroneRaces implements CommandExecutor, TabCompleter, Listener {
    private final FpvDronePlugin plugin;
    private final File file;
    private final YamlConfiguration data;
    private final NamespacedKey toolKey;
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<UUID, Sample> samples = new HashMap<>();
    private final Map<UUID, String> selected = new HashMap<>();
    private final Map<String, List<Gate>> tracks = new TreeMap<>();
    private int ticks;

    private record Sample(UUID world, Vector point, long time) {}
    private record Gate(UUID world, BoundingBox box) {}
    private static final class Run {
        final String track;
        final long started;
        int next = 1;
        Run(String track, long started) { this.track = track; this.started = started; }
    }

    DroneRaces(FpvDronePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "races.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        this.toolKey = new NamespacedKey(plugin, "race-selector");
        var root = data.getConfigurationSection("tracks");
        if (root != null) for (String id : root.getKeys(false)) {
            try {
                List<Gate> gates = new ArrayList<>();
                for (Map<?, ?> map : data.getMapList("tracks." + id + ".gates")) {
                    gates.add(new Gate(UUID.fromString(map.get("world").toString()), new BoundingBox(
                        number(map, "x1"), number(map, "y1"), number(map, "z1"),
                        number(map, "x2"), number(map, "y2"), number(map, "z2"))));
                }
                tracks.put(id, gates);
            } catch (RuntimeException e) { plugin.getLogger().warning("Пропущена повреждённая трасса " + id); }
        }
    }

    private static double number(Map<?, ?> map, String key) { return ((Number) map.get(key)).doubleValue(); }
    private void save() {
        try { data.save(file); }
        catch (Exception e) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Не удалось сохранить races.yml", e); }
    }
    private void saveTrack(String id) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (Gate gate : tracks.get(id)) {
            var box = gate.box(); Map<String, Object> value = new LinkedHashMap<>();
            value.put("world", gate.world().toString());
            value.put("x1", box.getMinX()); value.put("y1", box.getMinY()); value.put("z1", box.getMinZ());
            value.put("x2", box.getMaxX()); value.put("y2", box.getMaxY()); value.put("z2", box.getMaxZ());
            values.add(value);
        }
        data.set("tracks." + id + ".gates", values); save();
    }
    private boolean ready(String id) { var gates = tracks.get(id); return gates != null && gates.size() >= 2; }
    private static String format(long ms) { return String.format(Locale.ROOT, "%d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000); }
    private void list(CommandSender sender, boolean records) {
        sender.sendMessage(Component.text("Трассы — нажмите название:"));
        for (String id : tracks.keySet()) sender.sendMessage(Component.text(id + (ready(id) ? "" : " [нужен финиш]"))
            .clickEvent(ClickEvent.runCommand("/dronerace " + (records ? "records " : "join ") + id)));
    }
    private void archiveRecords(String id) {
        var records = data.getConfigurationSection("records." + id);
        if (records != null) {
            data.set("archived-records." + id + "." + System.currentTimeMillis(), records.getValues(true));
            data.set("records." + id, null);
        }
        runs.entrySet().removeIf(e -> e.getValue().track.equals(id));
        samples.clear();
    }
    private void giveTool(Player player) {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        var meta = item.getItemMeta();
        meta.displayName(Component.text("Выделитель ворот дрона"));
        meta.lore(List.of(Component.text("ЛКМ по блоку: первый угол"), Component.text("ПКМ по блоку: второй угол")));
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        if (!player.getInventory().addItem(item).isEmpty()) player.sendMessage(Component.text("Освободите место в инвентаре и повторите команду."));
        else player.sendMessage(Component.text("Выделитель получен. ЛКМ/ПКМ по двум угловым блокам."));
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void selectCorner(PlayerInteractEvent event) {
        if (!event.getPlayer().hasPermission("fpvdrone.race.admin") || event.getClickedBlock() == null) return;
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        Location[] points = selections.computeIfAbsent(event.getPlayer().getUniqueId(), unused -> new Location[2]);
        int index = action == Action.LEFT_CLICK_BLOCK ? 0 : 1;
        points[index] = event.getClickedBlock().getLocation();
        event.getPlayer().sendMessage(Component.text("Угол " + (index + 1) + ": " + points[index].getBlockX() + ", "
            + points[index].getBlockY() + ", " + points[index].getBlockZ()));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String op = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String id = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        try {
            if (op.equals("list")) { list(sender, false); return true; }
            if (op.equals("records")) {
                if (id.isEmpty()) { list(sender, true); return true; }
                if (!tracks.containsKey(id)) throw new IllegalArgumentException("Нет такой трассы");
                var records = data.getConfigurationSection("records." + id);
                sender.sendMessage(Component.text("Рекорды трассы " + id + ":"));
                if (records == null) { sender.sendMessage(Component.text("Пока нет результатов")); return true; }
                var ids = new ArrayList<>(records.getKeys(false));
                ids.sort(Comparator.comparingLong(key -> records.getLong(key + ".ms")));
                int rank = 0;
                for (String uuid : ids) sender.sendMessage(Component.text((++rank) + ". " + records.getString(uuid + ".name", uuid)
                    + " — " + format(records.getLong(uuid + ".ms"))));
                return true;
            }
            if (!(sender instanceof Player player)) throw new IllegalArgumentException("Команда доступна в игре");
            UUID owner = player.getUniqueId();
            if (op.equals("join")) {
                if (!ready(id)) throw new IllegalArgumentException("Трасса ещё не готова");
                selected.put(owner, id); runs.remove(owner); samples.remove(owner);
                player.sendMessage(Component.text("Трасса " + id + " выбрана. Пролетите стартовые ворота."));
                return true;
            }
            if (op.equals("leave")) { selected.remove(owner); runs.remove(owner); samples.remove(owner); player.sendActionBar(Component.empty()); return true; }
            if (!player.hasPermission("fpvdrone.race.admin")) throw new IllegalArgumentException("Доступны list, join, leave и records");
            switch (op) {
                case "tool" -> giveTool(player);
                case "create" -> {
                    if (!id.matches("[a-z0-9_-]{1,32}") || tracks.containsKey(id)) throw new IllegalArgumentException("Укажите новое имя: a-z, 0-9, _ или -");
                    tracks.put(id, new ArrayList<>()); saveTrack(id);
                    player.sendMessage(Component.text("Создана трасса " + id));
                }
                case "gate" -> {
                    if (args.length != 2 || !tracks.containsKey(id)) throw new IllegalArgumentException("Использование: /dronerace gate <трасса>");
                    Location[] points = selections.get(owner);
                    if (points == null || points[0] == null || points[1] == null || !points[0].getWorld().equals(points[1].getWorld()))
                        throw new IllegalArgumentException("Сначала выделите два угла инструментом в одном мире");
                    UUID world = points[0].getWorld().getUID(); var gates = tracks.get(id);
                    if (!gates.isEmpty() && !gates.getFirst().world().equals(world)) throw new IllegalArgumentException("Все ворота должны быть в одном мире");
                    Location a = points[0], b = points[1];
                    var box = new BoundingBox(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                        Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
                    if (box.getWidthX() > 64 || box.getHeight() > 64 || box.getWidthZ() > 64) throw new IllegalArgumentException("Ворота: максимум 64 блока по каждой оси");
                    for (Gate gate : gates) if (gate.box().overlaps(box)) throw new IllegalArgumentException("Ворота не должны пересекаться");
                    archiveRecords(id); gates.add(new Gate(world, box)); saveTrack(id);
                    player.sendMessage(Component.text("Добавлены ворота №" + gates.size() + ". Первый — старт, последний — финиш."));
                }
                case "undo" -> {
                    if (!tracks.containsKey(id) || tracks.get(id).isEmpty()) throw new IllegalArgumentException("Нет ворот для удаления");
                    archiveRecords(id); tracks.get(id).removeLast(); saveTrack(id);
                }
                default -> sender.sendMessage(Component.text("/dronerace tool | create <имя> | gate <имя> | undo <имя> | list | join <имя> | leave | records [имя]"));
            }
        } catch (IllegalArgumentException e) { sender.sendMessage(Component.text(e.getMessage())); }
        return true;
    }

    void tick() {
        ticks++;
        selected.entrySet().removeIf(entry -> plugin.getServer().getPlayer(entry.getKey()) == null);
        samples.keySet().retainAll(selected.keySet()); runs.keySet().retainAll(selected.keySet());
        long now = System.nanoTime();
        for (var entry : selected.entrySet()) {
            UUID uuid = entry.getKey(); Player player = plugin.getServer().getPlayer(uuid);
            String id = entry.getValue(); List<Gate> gates = tracks.get(id);
            if (!ready(id)) continue;
            var session = plugin.sessions().get(uuid); DroneState state = session == null ? null : session.state;
            if (state == null || !state.active || state.crashed) { runs.remove(uuid); samples.remove(uuid); continue; }
            Vector point = new Vector(state.x, state.y, state.z); UUID world = player.getWorld().getUID();
            Sample old = samples.put(uuid, new Sample(world, point, now));
            Run run = runs.get(uuid);
            if (old != null && old.world().equals(world) && now - old.time() < 1_000_000_000L && gates.getFirst().world().equals(world)) {
                double previousFraction = -1;
                for (int crossedCount = 0; crossedCount < gates.size(); crossedCount++) {
                    int next = run == null ? 0 : run.next;
                    Gate gate = gates.get(next);
                    double fraction = entryFraction(gate.box(), old.point(), point);
                    if (!Double.isFinite(fraction) || fraction < previousFraction) break;
                    long crossing = old.time() + (long) ((now - old.time()) * fraction);
                    if (run == null) { run = new Run(id, crossing); runs.put(uuid, run); }
                    else if (next == gates.size() - 1) {
                        long ms = Math.max(1, (crossing - run.started) / 1_000_000);
                        String path = "records." + id + "." + uuid;
                        if (!data.contains(path + ".ms") || ms < data.getLong(path + ".ms")) {
                            data.set(path + ".ms", ms); data.set(path + ".name", player.getName()); save();
                        }
                        player.sendMessage(Component.text("Финиш " + id + ": " + format(ms)));
                        player.sendActionBar(Component.text("Финиш: " + format(ms)));
                        runs.remove(uuid); run = null; break;
                    } else run.next++;
                    previousFraction = fraction;
                }
            }
            if (run != null) {
                player.sendActionBar(Component.text(id + " | " + format((now - run.started) / 1_000_000)
                    + " | Ворота " + run.next + "/" + (gates.size() - 1)));
                if (ticks % 5 == 0 && gates.get(run.next).world().equals(world)) draw(player, gates.get(run.next),
                    run.next == gates.size() - 1 ? Color.RED : Color.YELLOW);
            }
        }
    }

    /** Вход в объём между пакетами; проверяется только ожидаемый следующий объём. */
    static double entryFraction(BoundingBox box, Vector from, Vector to) {
        if (box.contains(from)) return Double.NaN;
        Vector delta = to.clone().subtract(from); double length = delta.length();
        if (length < 1e-9) return Double.NaN;
        var hit = box.rayTrace(from, delta, length);
        return hit == null ? Double.NaN : hit.getHitPosition().distance(from) / length;
    }
    private void draw(Player player, Gate gate, Color color) {
        BoundingBox box = gate.box();
        if (player.getLocation().toVector().distanceSquared(box.getCenter()) > 512 * 512) return;
        double[] low = {box.getMinX(), box.getMinY(), box.getMinZ()};
        double[] high = {box.getMaxX(), box.getMaxY(), box.getMaxZ()};
        for (int axis = 0; axis < 3; axis++) for (int side = 0; side < 4; side++) for (int step = 0; step <= 8; step++) {
            double[] point = low.clone(); point[axis] += (high[axis] - low[axis]) * step / 8.0;
            int a = (axis + 1) % 3, b = (axis + 2) % 3;
            point[a] = (side & 1) == 0 ? low[a] : high[a];
            point[b] = (side & 2) == 0 ? low[b] : high[b];
            player.spawnParticle(Particle.DUST, point[0], point[1], point[2], 1, 0, 0, 0, 0,
                new Particle.DustOptions(color, 1.2F), true);
        }
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> choices = args.length == 1 ? List.of("list", "records", "join", "leave", "tool", "create", "gate", "undo")
            : new ArrayList<>(tracks.keySet());
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.startsWith(prefix)).toList();
    }
}
