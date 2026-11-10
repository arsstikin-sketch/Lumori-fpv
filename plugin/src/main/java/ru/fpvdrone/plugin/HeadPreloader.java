package ru.fpvdrone.plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Предзагрузка скинов голов у игрока.
 * Minecraft скачивает картинку скина только когда голова впервые попадает на экран — отсюда «серые» птицы
 * и подгрузка по одной. Поэтому после входа игрока мы показываем ТОЛЬКО ему крошечные (невидимые глазу)
 * головы прямо перед глазами пачками по 24 штуки. Клиент скачивает и кэширует все текстуры заранее,
 * и потом птицы появляются сразу со скинами. Другие игроки этих голов не видят.
 */
final class HeadPreloader {
    private static final int BATCH = 24;
    private static final int BATCH_TICKS = 8;

    private final FpvDronePlugin plugin;
    private final Map<UUID, Job> running = new HashMap<>();

    HeadPreloader(FpvDronePlugin plugin) {
        this.plugin = plugin;
    }

    /** Запустить предзагрузку для игрока через delay тиков. */
    void schedule(Player player, long delay) {
        if (!this.plugin.getConfig().getBoolean("birds.preload-on-join", true)) {
            return;
        }
        UUID id = player.getUniqueId();
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            Player p = this.plugin.getServer().getPlayer(id);
            if (p != null && p.isOnline()) {
                this.start(p);
            }
        }, Math.max(1L, delay));
    }

    private void start(Player player) {
        Job old = this.running.get(player.getUniqueId());
        if (old != null) {
            old.finish();
        }
        List<ItemStack> heads = this.plugin.birdHeads().allItems(this.plugin.birdSkins());
        if (heads.isEmpty()) {
            return;
        }
        Job job = new Job(player, heads);
        BukkitTask task = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, job, 1L, 1L);
        job.task = task;
        this.running.put(player.getUniqueId(), job);
    }

    private final class Job implements Runnable {
        private final Player player;
        private final List<ItemStack> heads;
        private final ItemDisplay[] shown = new ItemDisplay[BATCH];
        private int next;
        private int ticks;
        BukkitTask task;

        Job(Player player, List<ItemStack> heads) {
            this.player = player;
            this.heads = heads;
        }

        @Override
        public void run() {
            if (!this.player.isOnline() || this.player.isDead()) {
                this.finish();
                return;
            }
            if (this.ticks % BATCH_TICKS == 0) {
                this.clear();
                if (this.next >= this.heads.size()) {
                    this.finish();
                    return;
                }
                for (int i = 0; i < BATCH && this.next < this.heads.size(); i++) {
                    this.shown[i] = this.spawn(this.heads.get(this.next++), i);
                }
            } else {
                // Держим головы перед глазами, даже если игрок крутит головой.
                for (int i = 0; i < BATCH; i++) {
                    ItemDisplay d = this.shown[i];
                    if (d != null && d.isValid()) {
                        d.teleport(this.spot(i));
                    }
                }
            }
            this.ticks++;
        }

        private Location spot(int i) {
            Location eye = this.player.getEyeLocation();
            Vector dir = eye.getDirection().normalize();
            // Чуть разносим головы, чтобы все попали в кадр: маленькая сетка 6×4 в 1.2 блока от глаз.
            Vector right = new Vector(-dir.getZ(), 0, dir.getX());
            if (right.lengthSquared() < 1.0E-4) {
                right = new Vector(1, 0, 0);
            }
            right.normalize();
            Vector up = right.clone().crossProduct(dir).normalize();
            double dx = ((i % 6) - 2.5) * 0.04;
            double dy = ((i / 6) - 1.5) * 0.04;
            Location at = eye.clone().add(dir.multiply(1.2)).add(right.multiply(dx)).add(up.multiply(dy));
            at.setYaw(0);
            at.setPitch(0);
            return at;
        }

        private ItemDisplay spawn(ItemStack item, int i) {
            Location at = this.spot(i);
            return at.getWorld().spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setVisibleByDefault(false);
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setBillboard(Display.Billboard.CENTER);
                d.setViewRange(1.0F);
                d.setTeleportDuration(1);
                // Крошечная голова: на экране меньше пикселя, но клиент всё равно скачивает её скин.
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(0.02F, 0.02F, 0.02F), new Quaternionf()));
                this.player.showEntity(HeadPreloader.this.plugin, d);
            });
        }

        private void clear() {
            for (int i = 0; i < BATCH; i++) {
                if (this.shown[i] != null) {
                    this.shown[i].remove();
                    this.shown[i] = null;
                }
            }
        }

        private void finish() {
            this.clear();
            if (this.task != null) {
                this.task.cancel();
            }
            HeadPreloader.this.running.remove(this.player.getUniqueId(), this);
        }
    }

    /** Остановить всё (выключение плагина). */
    void stopAll() {
        for (Job j : new java.util.ArrayList<>(this.running.values())) {
            j.finish();
        }
        this.running.clear();
    }
}
