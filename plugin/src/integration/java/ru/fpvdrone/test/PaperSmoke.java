package ru.fpvdrone.test;

import java.lang.reflect.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated Paper smoke test; never install this JAR on a normal server (it stops the server). */
public final class PaperSmoke extends JavaPlugin {
    private final List<Entity> spawned = new ArrayList<>();
    private Player nativePlayer;
    private int windows;
    private int failures;
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::runChecks, 5);
    }
    private Object call(Object target, String name, Object... args) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                return method.invoke(target instanceof Class<?> ? null : target, args);
            }
        }
        throw new NoSuchMethodException(name);
    }
    private void runChecks() {
        try {
            var plugin = Bukkit.getPluginManager().getPlugin("FpvDrone");
            if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException("FpvDrone is disabled");
            ClassLoader loader = plugin.getClass().getClassLoader();
            Player player = (Player) Proxy.newProxyInstance(loader, new Class<?>[] {Player.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "getUniqueId" -> new UUID(0, 123);
                    case "hasPermission", "isOnline" -> true;
                    case "openInventory" -> { windows++; getLogger().info("WINDOW OK: " + ((Inventory) args[0]).getSize()); yield null; }
                    case "sendMessage" -> { getLogger().warning("PLAYER MESSAGE: " + Arrays.toString(args)); yield null; }
                    case "getName" -> "SmokePlayer";
                    default -> null;
                };
            });
            Object panel = call(plugin, "adminPanel");
            Class<?> filter = Class.forName("ru.fpvdrone.plugin.AdminPanel$Filter", true, loader);
            for (Object f : filter.getEnumConstants()) {
                try { call(panel, "openUnsafe", player, 0, f); }
                catch (Throwable t) { failures++; getLogger().log(java.util.logging.Level.SEVERE, "ADMIN FAILURE " + f, t); }
            }
            try {
                nativePlayer = NativePlayer.create();
                Bukkit.dispatchCommand(nativePlayer, "fpvdrone:flyskin admin");
                if (nativePlayer.getOpenInventory().getTopInventory().getSize() != 54) throw new IllegalStateException("Native admin window missing");
                getLogger().info("NATIVE ADMIN OK");
                var view = nativePlayer.getOpenInventory();
                int slot = -1;
                for (int i = 0; i < 45; i++) {
                    var item = view.getTopInventory().getItem(i);
                    if (item != null && item.getItemMeta().lore() != null
                        && item.getItemMeta().lore().stream().anyMatch(line -> line.toString().contains("id: bee"))) { slot = i; break; }
                }
                if (slot < 0) throw new IllegalStateException("Bee icon missing");
                var click = new org.bukkit.event.inventory.InventoryClickEvent(view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                    slot, org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
                Bukkit.getPluginManager().callEvent(click);
                if (!click.isCancelled()) throw new IllegalStateException("Admin click not handled");
                Object bee = call(plugin, "findMob", "bee");
                if ((boolean) call(panel, "enabled", bee)) throw new IllegalStateException("Admin toggle did not disable bee");
                call(panel, "setEnabled", bee, true);
                getLogger().info("NATIVE ADMIN CLICK OK");
                nativePlayer.closeInventory();
                Bukkit.dispatchCommand(nativePlayer, "fpvdrone:flyskin set phantom");
                Bukkit.dispatchCommand(nativePlayer, "fpvdrone:flyskin");
                getLogger().info("NATIVE CAROUSEL OPEN, packets=" + NativePlayer.packets.size());
            } catch (Throwable t) { failures++; getLogger().log(java.util.logging.Level.SEVERE, "NATIVE FAILURE", t); }
            World world = Bukkit.getWorlds().getFirst();
            world.setDifficulty(Difficulty.PEACEFUL);
            Location at = world.getSpawnLocation().add(0, 5, 0);
            world.getChunkAt(at).load();
            Class<?> mobs = Class.forName("ru.fpvdrone.plugin.MobEntities", true, loader);
            List<?> catalog = (List<?>) call(call(plugin, "catalog"), "mobs");
            for (Object mob : catalog) {
                Field vf = mob.getClass().getDeclaredField("variants"); vf.setAccessible(true);
                for (Object variant : (List<?>) vf.get(mob)) {
                    try {
                        Entity entity = (Entity) call(mobs, "spawn", at, variant, 0.85, null);
                        if (entity == null || !entity.isValid()) throw new IllegalStateException("No valid entity");
                        spawned.add(entity);
                        getLogger().info("MOB OK " + entity.getType() + " width=" + entity.getWidth() + " height=" + entity.getHeight());
                    } catch (Throwable t) { failures++; getLogger().log(java.util.logging.Level.SEVERE, "MOB FAILURE", t); }
                }
            }
        } catch (Throwable t) { failures++; getLogger().log(java.util.logging.Level.SEVERE, "SMOKE FAILURE", t); }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Entity entity : spawned) { if (!entity.isValid()) failures++; entity.remove(); }
            long adds = NativePlayer.packets.stream().flatMap(p -> p instanceof net.minecraft.network.protocol.BundlePacket<?> bundle
                ? java.util.stream.StreamSupport.stream(bundle.subPackets().spliterator(), false) : java.util.stream.Stream.of(p))
                .filter(p -> p.getClass().getSimpleName().contains("AddEntity")).count();
            getLogger().info("NATIVE PACKET TYPES=" + NativePlayer.packets.stream().map(p -> p.getClass().getSimpleName()).distinct().toList());
            if (nativePlayer != null) Bukkit.dispatchCommand(nativePlayer, "fpvdrone:flyskin diagnose");
            getLogger().info("NATIVE ENTITY SPAWN PACKETS=" + adds);
            getLogger().info("SMOKE RESULT: windows=" + windows + " mobs=" + spawned.size() + " failures=" + failures);
            Bukkit.shutdown();
        }, 40);
    }
}
