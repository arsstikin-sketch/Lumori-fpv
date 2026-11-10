package ru.fpvdrone.plugin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * Скины голов для птиц. Каждая «головная» часть модели — голова игрока со своим скином (8×8 на грань).
 * Скин нужно один раз загрузить через MineSkin: он кладёт картинку на свои аккаунты Minecraft и отдаёт строку textures.
 * Твой собственный скин при этом НЕ меняется.
 * Строки хранятся в plugins/FpvDrone/bird-heads.yml. Пока скин не загружен, часть рисуется блоком похожего цвета.
 */
final class BirdHeads {
    private static final String API = "https://api.mineskin.org";
    private static final Pattern VALUE = Pattern.compile("\"value\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\"");
    private static final Pattern JOB_ID = Pattern.compile("\"job\"\\s*:\\s*\\{[^}]*?\"id\"\\s*:\\s*\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern ANY_ID = Pattern.compile("\"(?:id|jobId)\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([a-z_]+)\"");

    private final FpvDronePlugin plugin;
    private final File file;
    /** Ключ — отпечаток картинки (не номер!), поэтому в новых версиях плагина загруженные головы не теряются. */
    private final Map<String, String> values = new java.util.concurrent.ConcurrentHashMap<>();
    /** id картинки в этой версии (s0001…) → отпечаток её пикселей. */
    private final Map<String, String> index = new HashMap<>();
    private final Map<String, ItemStack> items = new HashMap<>();
    private final AtomicBoolean uploading = new AtomicBoolean(false);
    private volatile boolean stop;
    private volatile String progress = "";

    BirdHeads(FpvDronePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bird-heads.yml");
    }

    void load() {
        this.values.clear();
        this.items.clear();
        this.loadIndex();
        if (!this.file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(this.file);
        boolean legacy = false;
        for (String key : yml.getKeys(false)) {
            String v = yml.getString(key);
            if (v == null || v.isBlank()) {
                continue;
            }
            if (key.startsWith("h_")) {
                this.values.put(key.substring(2), v.trim());
            } else {
                // файл версии 0.7.0: ключи s0001…; картинки в 0.7.1 те же, переводим в отпечатки
                String hash = this.index.get(key);
                if (hash != null) {
                    this.values.put(hash, v.trim());
                    legacy = true;
                }
            }
        }
        if (legacy) {
            try {
                Files.copy(this.file.toPath(), new File(this.file.getParentFile(), "bird-heads-0.7.0-backup.yml").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                // копия не обязательна
            }
            this.save();
            this.plugin.getLogger().info("bird-heads.yml переведён в новый формат (копия старого — bird-heads-0.7.0-backup.yml)");
        }
    }

    private void loadIndex() {
        this.index.clear();
        try (InputStream in = this.plugin.getClass().getClassLoader().getResourceAsStream("birds/skins-index.json")) {
            if (in == null) {
                return;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"(s\\d+)\"\\s*:\\s*\"([0-9a-f]+)\"").matcher(json);
            while (m.find()) {
                this.index.put(m.group(1), m.group(2));
            }
        } catch (Exception e) {
            this.plugin.getLogger().warning("Нет birds/skins-index.json: " + e.getMessage());
        }
    }

    private String key(String skin) {
        String h = skin == null ? null : this.index.get(skin);
        return h != null ? h : skin;
    }

    private synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>(this.values);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            yml.set("h_" + e.getKey(), e.getValue());
        }
        yml.options().setHeader(java.util.List.of(
            "Скины голов птиц, загруженные через MineSkin. НЕ удаляй этот файл и сохрани копию.",
            "Ключ — отпечаток картинки, поэтому при обновлении плагина головы не теряются:",
            "новая версия догрузит только изменившиеся картинки (/flyskin heads upload)."));
        try {
            this.plugin.getDataFolder().mkdirs();
            yml.save(this.file);
        } catch (Exception e) {
            this.plugin.getLogger().warning("Не удалось сохранить bird-heads.yml: " + e.getMessage());
        }
    }

    boolean has(String skin) {
        return skin != null && this.values.containsKey(this.key(skin));
    }

    int loaded(List<String> all) {
        int n = 0;
        for (String s : all) {
            if (this.values.containsKey(this.key(s))) {
                n++;
            }
        }
        return n;
    }

    /** Голова игрока с нужным скином или null, если скин ещё не загружен. */
    ItemStack item(String skin) {
        String value = this.values.get(this.key(skin));
        if (value == null) {
            return null;
        }
        return this.items.computeIfAbsent(this.key(skin), s -> {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(("fpvbird:" + s).getBytes(StandardCharsets.UTF_8)), "fpvbird");
            profile.setProperty(new ProfileProperty("textures", value));
            meta.setPlayerProfile(profile);
            head.setItemMeta(meta);
            return head;
        }).clone();
    }

    boolean busy() {
        return this.uploading.get();
    }

    String progress() {
        return this.progress;
    }

    void stop() {
        this.stop = true;
        this.userStopped = true;
    }

    private volatile boolean userStopped;

    /** Остановлено командой или ключ не принят — сторож не перезапускает сам. */
    boolean stoppedByUser() {
        return this.userStopped;
    }

    /** Выгрузить картинки скинов в папку плагина (для ручной загрузки, если нужно). */
    int exportPngs(List<String> all) throws Exception {
        File dir = new File(this.plugin.getDataFolder(), "bird-skins");
        dir.mkdirs();
        int n = 0;
        for (String id : all) {
            byte[] png = this.png(id);
            if (png != null) {
                Files.write(new File(dir, id + ".png").toPath(), png);
                n++;
            }
        }
        return n;
    }

    private byte[] png(String id) throws Exception {
        try (InputStream in = this.plugin.getClass().getClassLoader().getResourceAsStream("birds/skins/" + id + ".png")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private volatile long lastActivity;
    private volatile int generation;

    /** Загрузка «зависла» (нет ответа больше 3 минут) — сторож перезапустит её. */
    boolean stalled() {
        return this.uploading.get() && System.currentTimeMillis() - this.lastActivity > 180_000L;
    }

    /** Сбросить зависшую загрузку, чтобы её можно было запустить заново. */
    void forceReset() {
        this.generation++;
        this.uploading.set(false);
    }

    /**
     * Загрузить все недостающие скины через MineSkin (в фоне). Ключ — бесплатный, с account.mineskin.org.
     * Порядок: сначала первая окраска каждой птицы (чтобы все птицы быстро получили головы), потом остальные.
     * Неудачные повторяются в конце (до трёх кругов). Каждые 10 скинов результат сохраняется в файл.
     * @param perMinute сколько скинов в минуту (бесплатный ключ — 20)
     */
    void uploadMissing(List<String> all, String apiKey, int perMinute, CommandSender sender, Runnable onAnyLoaded) {
        if (!this.uploading.compareAndSet(false, true)) {
            sender.sendMessage(Component.text("Загрузка уже идёт: " + this.progress, NamedTextColor.YELLOW));
            return;
        }
        this.stop = false;
        this.userStopped = false;
        this.lastActivity = System.currentTimeMillis();
        int myGen = ++this.generation;
        List<String> todo = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String s : all) {
            if (!this.values.containsKey(this.key(s)) && seen.add(this.key(s))) {
                todo.add(s);
            }
        }
        if (todo.isEmpty()) {
            this.uploading.set(false);
            sender.sendMessage(Component.text("Все " + all.size() + " скинов уже загружены", NamedTextColor.GREEN));
            return;
        }
        long gapMs = Math.max(300L, 60_000L / Math.max(1, perMinute));
        int minutes = (int) Math.ceil(todo.size() * gapMs / 60_000.0);
        sender.sendMessage(Component.text("Загружаю " + todo.size() + " скинов через MineSkin, примерно " + minutes
            + " мин. Игроки могут играть. Прогресс: /flyskin heads status", NamedTextColor.GREEN));
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
            int total = todo.size();
            int done = 0;
            List<String> queue = new ArrayList<>(todo);
            for (int pass = 0; pass < 3 && !queue.isEmpty() && !this.stop && myGen == this.generation; pass++) {
                List<String> failedList = new ArrayList<>();
                for (String id : queue) {
                    if (this.stop || myGen != this.generation) {
                        break;
                    }
                    if (this.values.containsKey(this.key(id))) {
                        continue;
                    }
                    this.progress = done + " из " + total + (pass > 0 ? " (повтор неудачных, круг " + (pass + 1) + ")" : "");
                    String value = null;
                    long nextGap = gapMs;
                    for (int attempt = 0; attempt < 5 && value == null && !this.stop && myGen == this.generation; attempt++) {
                        try {
                            Result r = this.generate(http, id, apiKey);
                            value = r.value;
                            if (r.nextMs > 0) {
                                nextGap = Math.max(300L, r.nextMs);
                            }
                        } catch (RateLimited e) {
                            this.lastActivity = System.currentTimeMillis();
                            sleep(e.waitMs);
                        } catch (Exception e) {
                            this.plugin.getLogger().warning("MineSkin, скин " + id + " (попытка " + (attempt + 1) + "): " + e.getMessage());
                            sleep(4000L * (attempt + 1));
                        }
                        this.lastActivity = System.currentTimeMillis();
                    }
                    if (value != null) {
                        this.values.put(this.key(id), value);
                        done++;
                        if (done % 10 == 0) {
                            this.save();
                            this.plugin.getLogger().info("Скины птиц: загружено " + done + " из " + total);
                        }
                        Bukkit.getScheduler().runTask(this.plugin, onAnyLoaded);
                    } else {
                        failedList.add(id);
                    }
                    sleep(nextGap);
                }
                queue = failedList;
            }
            this.save();
            if (myGen != this.generation) {
                return; // эту загрузку заменила новая
            }
            int left = queue.size();
            this.progress = "готово: " + done + " из " + total + (left > 0 ? ", не получилось: " + left : "");
            this.uploading.set(false);
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                this.plugin.getLogger().info("Скины птиц: " + this.progress);
                this.plugin.onHeadsUploaded();
                if (!(sender instanceof org.bukkit.entity.Player p) || p.isOnline()) {
                    sender.sendMessage(Component.text("Скины птиц: " + this.progress
                        + (left > 0 ? ". Запусти /flyskin heads upload ещё раз — догрузит недостающие." : ""), left > 0 ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
                }
            });
        });
    }

    private static final class Result {
        String value;
        long nextMs;
    }

    /** Все загруженные головы — для предзагрузки у игроков. */
    List<ItemStack> allItems(List<String> skins) {
        List<ItemStack> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String s : skins) {
            if (seen.add(this.key(s))) {
                ItemStack it = this.item(s);
                if (it != null) {
                    out.add(it);
                }
            }
        }
        return out;
    }

    private static final class RateLimited extends Exception {
        final long waitMs;

        RateLimited(long waitMs) {
            super("rate limit");
            this.waitMs = waitMs;
        }
    }

    private Result generate(HttpClient http, String id, String apiKey) throws Exception {
        Result result = new Result();
        byte[] png = this.png(id);
        if (png == null) {
            throw new IllegalStateException("нет картинки в jar");
        }
        String boundary = "----fpvbird" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "variant", "classic");
        field(body, boundary, "visibility", "unlisted");
        field(body, boundary, "name", "fpvbird_" + id);
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + id + ".png\"\r\n"
            + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = this.request(API + "/v2/queue", apiKey)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        String text = res.body() == null ? "" : res.body();
        if (res.statusCode() == 429) {
            throw new RateLimited(retryAfter(res, 15000L));
        }
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            this.stop = true;
            this.userStopped = true;
            throw new IllegalStateException("ключ MineSkin не принят (код " + res.statusCode() + "). Проверь birds.mineskin-key");
        }
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("код " + res.statusCode() + ": " + shorten(text));
        }
        result.nextMs = nextDelay(text);
        String value = skinValue(text);
        if (value != null) {
            result.value = value;
            return result;
        }
        Matcher jm = JOB_ID.matcher(text);
        String jobId = jm.find() ? jm.group(1) : null;
        if (jobId == null) {
            Matcher am = ANY_ID.matcher(text);
            jobId = am.find() ? am.group(1) : null;
        }
        if (jobId == null) {
            throw new IllegalStateException("непонятный ответ: " + shorten(text));
        }
        // Ждём, пока задача в очереди MineSkin выполнится (обычно пара секунд).
        for (int i = 0; i < 45 && !this.stop; i++) {
            sleep(2000L);
            this.lastActivity = System.currentTimeMillis();
            HttpResponse<String> st = http.send(this.request(API + "/v2/queue/" + jobId, apiKey).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            String s = st.body() == null ? "" : st.body();
            if (st.statusCode() == 429) {
                sleep(retryAfter(st, 5000L));
                continue;
            }
            value = skinValue(s);
            if (value != null) {
                result.value = value;
                long n2 = nextDelay(s);
                if (n2 > 0) {
                    result.nextMs = n2;
                }
                return result;
            }
            Matcher sm = STATUS.matcher(s);
            if (sm.find() && sm.group(1).equals("failed")) {
                throw new IllegalStateException("MineSkin не смог сгенерировать: " + shorten(s));
            }
        }
        throw new IllegalStateException("не дождался ответа очереди");
    }

    private HttpRequest.Builder request(String url, String apiKey) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(40))
            .header("User-Agent", "FpvDrone-plugin/" + this.plugin.getPluginMeta().getVersion())
            .header("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + apiKey.trim());
        }
        return b;
    }

    private static final Pattern NEXT_REL = Pattern.compile("\"next\"\\s*:\\s*\\{[^}]*?\"relative\"\\s*:\\s*(\\d+)", Pattern.DOTALL);

    /** Через сколько мс MineSkin разрешает следующий запрос (поле rateLimit.next.relative), 0 — неизвестно. */
    private static long nextDelay(String json) {
        Matcher m = NEXT_REL.matcher(json);
        if (m.find()) {
            try {
                return Math.min(60_000L, Long.parseLong(m.group(1)));
            } catch (NumberFormatException ignored) {
                // нет числа
            }
        }
        return 0L;
    }

    /** value из skin.texture.data.value (V2) или data.texture.value (старый формат). */
    private static String skinValue(String json) {
        int t = json.indexOf("\"texture\"");
        if (t < 0) {
            return null;
        }
        Matcher m = VALUE.matcher(json);
        if (m.find(t)) {
            return m.group(1);
        }
        return null;
    }

    private static long retryAfter(HttpResponse<String> res, long def) {
        String h = res.headers().firstValue("Retry-After").orElse(null);
        if (h != null) {
            try {
                return (long) (Double.parseDouble(h.trim()) * 1000.0) + 500L;
            } catch (NumberFormatException ignored) {
                // дальше — значение по умолчанию
            }
        }
        return def;
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
            .getBytes(StandardCharsets.UTF_8));
    }

    private static String shorten(String s) {
        s = s.replaceAll("\\s+", " ");
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
