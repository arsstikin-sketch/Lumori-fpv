package ru.fpvdrone.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * /flyskin admin — панель оператора: какие скины видят игроки (галочка) и размер каждого существа.
 * Настройки — в plugins/FpvDrone/skins-admin.yml, действуют сразу.
 */
final class AdminPanel implements Listener {
    private static final int PER_PAGE = 45;
    static final double MIN_SIZE = 0.2;
    static final double MAX_SIZE = 6.0;

    enum Filter {
        ALL("Все"), BIRDS("Птицы"), FANTASY("Выдуманные"), MOBS("Мобы и свои");

        final String title;

        Filter(String title) {
            this.title = title;
        }
    }

    private final FpvDronePlugin plugin;
    private final File file;
    private final Set<String> disabled = new HashSet<>();
    private final Map<String, Double> sizes = new HashMap<>();

    AdminPanel(FpvDronePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "skins-admin.yml");
    }

    // ================================================================== хранение

    void load() {
        this.disabled.clear();
        this.sizes.clear();
        if (!this.file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(this.file);
        for (String id : yml.getStringList("disabled")) {
            this.disabled.add(id.toLowerCase(Locale.ROOT));
        }
        var sec = yml.getConfigurationSection("sizes");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                double v = sec.getDouble(id);
                if (Double.isFinite(v) && v > 0) {
                    this.sizes.put(id.toLowerCase(Locale.ROOT), clamp(v));
                }
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of("Панель /flyskin admin: выключенные скины и размеры существ (блоков).",
            "Можно править руками, потом /flyskin reload."));
        List<String> list = new ArrayList<>(this.disabled);
        java.util.Collections.sort(list);
        yml.set("disabled", list);
        for (Map.Entry<String, Double> e : new java.util.TreeMap<>(this.sizes).entrySet()) {
            yml.set("sizes." + e.getKey(), Math.round(e.getValue() * 100.0) / 100.0);
        }
        try {
            this.plugin.getDataFolder().mkdirs();
            yml.save(this.file);
        } catch (Exception e) {
            this.plugin.getLogger().warning("Не удалось сохранить skins-admin.yml: " + e.getMessage());
        }
    }

    boolean enabled(SkinCatalog.Mob mob) {
        return mob.id.equalsIgnoreCase(this.plugin.defaultSkinId())
            || !this.disabled.contains(mob.id.toLowerCase(Locale.ROOT));
    }

    /** Размер, заданный админом, или null. */
    Double sizeOverride(SkinCatalog.Mob mob) {
        return this.sizes.get(mob.id.toLowerCase(Locale.ROOT));
    }

    private static double clamp(double v) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, v));
    }

    // ================================================================== окно

    /** Открытое окно: страница, раздел и какие скины в ячейках. */
    private static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }
        final int page;
        final Filter filter;
        final List<SkinCatalog.Mob> shown = new ArrayList<>();

        Holder(int page, Filter filter) {
            this.page = page;
            this.filter = filter;
        }
    }

    private List<SkinCatalog.Mob> list(Filter f) {
        List<SkinCatalog.Mob> out = new ArrayList<>();
        for (SkinCatalog.Mob m : this.plugin.allMobs()) {
            boolean bird = m.bird != null;
            boolean fantasy = bird && m.bird.fantasy();
            boolean ok = switch (f) {
                case ALL -> true;
                case BIRDS -> bird && !fantasy;
                case FANTASY -> fantasy;
                case MOBS -> !bird;
            };
            if (ok) {
                out.add(m);
            }
        }
        return out;
    }

    private final java.util.Map<java.util.UUID, String> openDiagnostics = new java.util.HashMap<>();

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void diagnoseOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (event.getInventory().getHolder(false) instanceof Holder) {
            openDiagnostics.put(event.getPlayer().getUniqueId(), event.isCancelled() ? "InventoryOpenEvent отменён" : "InventoryOpenEvent разрешён");
        }
    }

    private void chatPanel(Player player, int page, java.util.List<SkinCatalog.Mob> all) {
        player.sendMessage(Component.text("Панель в чате — нажмите на скин, чтобы изменить доступность:", NamedTextColor.GOLD));
        int start = page * PER_PAGE;
        for (int i = start; i < Math.min(all.size(), start + PER_PAGE); i++) {
            var mob = all.get(i);
            boolean enabled = this.enabled(mob);
            player.sendMessage(Component.text((enabled ? "[ВКЛ] " : "[ВЫКЛ] ") + mob.name,
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/fpvdrone:flyskin "
                    + (enabled ? "disable " : "enable ") + mob.id)));
        }
        if (page > 0) player.sendMessage(Component.text("◀ Предыдущая страница").clickEvent(
            net.kyori.adventure.text.event.ClickEvent.runCommand("/fpvdrone:flyskin admin " + page)));
        if (start + PER_PAGE < all.size()) player.sendMessage(Component.text("Следующая страница ▶").clickEvent(
            net.kyori.adventure.text.event.ClickEvent.runCommand("/fpvdrone:flyskin admin " + (page + 2))));
    }

    void open(Player player, int page, Filter filter) {
        if (!player.hasPermission("fpvdrone.admin")) {
            player.sendMessage(Component.text("Нет прав fpvdrone.admin", NamedTextColor.RED));
            return;
        }
        try {
            this.plugin.skinMenus().close(player, false);
            this.openUnsafe(player, page, filter);
        } catch (Throwable t) {
            this.plugin.getLogger().log(java.util.logging.Level.SEVERE, "Панель /flyskin admin не открылась", t);
            player.sendMessage(Component.text("Панель не открылась: " + t + " — подробности в консоли сервера", NamedTextColor.RED));
        }
    }

    private void openUnsafe(Player player, int page, Filter filter) {
        List<SkinCatalog.Mob> all = this.list(filter);
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(pages - 1, page));
        Holder h = new Holder(page, filter);
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Скины: " + filter.title + " (" + (page + 1) + "/" + pages + ")"));
        h.inventory = inv;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= all.size()) {
                break;
            }
            SkinCatalog.Mob m = all.get(idx);
            h.shown.add(m);
            inv.setItem(i, this.icon(m));
        }
        // нижний ряд: листание, фильтр, «всё вкл/выкл», справка
        inv.setItem(45, button(Material.ARROW, "◀ Назад", page > 0 ? "Страница " + page : "Это первая страница"));
        inv.setItem(46, button(Material.HOPPER, "Показать: " + filter.title, "Нажми — следующий раздел:",
            "Все → Птицы → Выдуманные → Мобы и свои"));
        int on = 0;
        for (SkinCatalog.Mob m : all) {
            if (this.enabled(m)) {
                on++;
            }
        }
        inv.setItem(47, button(Material.COMPARATOR, "Стабилизация: " + this.plugin.stabilizationLabel(), "Нажми: All → Acro → Level", "Применяется ко всем пилотам сразу"));
        inv.setItem(48, button(Material.LIME_DYE, "Включить все в разделе", "Сейчас включено: " + on + " из " + all.size()));
        inv.setItem(49, button(Material.BOOK, "Как пользоваться",
            "ЛКМ — включить / выключить скин",
            "ПКМ — размер +0.1 блока",
            "Shift + ПКМ — размер −0.1",
            "Shift + ЛКМ — размер +0.5",
            "Q (выбросить) — размер по умолчанию",
            "Выключенные скины не видны в /flyskin;",
            "у кого такой был — летает скин по умолчанию."));
        inv.setItem(50, button(Material.GRAY_DYE, "Выключить все в разделе", "Скин по умолчанию выключить нельзя"));
        inv.setItem(53, button(Material.ARROW, "Вперёд ▶", page < pages - 1 ? "Страница " + (page + 2) : "Это последняя страница"));
        this.openDiagnostics.remove(player.getUniqueId());
        var opened = player.openInventory(inv);
        String eventStatus = this.openDiagnostics.remove(player.getUniqueId());
        if (opened == null) {
            String handlers = java.util.Arrays.stream(org.bukkit.event.inventory.InventoryOpenEvent.getHandlerList().getRegisteredListeners())
                .map(listener -> listener.getPlugin().getName()).distinct().sorted().collect(java.util.stream.Collectors.joining(", "));
            String reason = eventStatus == null ? "InventoryOpenEvent не вызван" : eventStatus;
            player.sendMessage(Component.text("Окно панели не открылось: " + reason + ". Ниже доступно управление в чате.", NamedTextColor.YELLOW));
            this.plugin.getLogger().warning("Админ-панель " + player.getName() + ": " + reason
                + "; dead=" + player.isDead() + "; sleeping=" + player.isSleeping() + "; mode=" + player.getGameMode()
                + "; обработчики InventoryOpenEvent (не обязательно причина отказа): " + handlers);
            this.chatPanel(player, page, all);
            return;
        }
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline() && !(player.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder)) {
                player.sendMessage(Component.text("Панель открылась на сервере, но уже закрыта или заменена другим окном.", NamedTextColor.YELLOW));
                this.plugin.getLogger().warning("Админ-панель " + player.getName() + " закрыта/заменена до следующего тика.");
            }
        });
    }

    private ItemStack icon(SkinCatalog.Mob m) {
        boolean on = this.enabled(m);
        ItemStack it = this.baseIcon(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text((on ? "✔ " : "✘ ") + m.name, on ? NamedTextColor.GREEN : NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false).decorate(TextDecoration.BOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(line((on ? "Включён — игроки видят его в /flyskin" : "Выключен — в /flyskin его нет"), on ? NamedTextColor.GREEN : NamedTextColor.RED));
        Double ov = this.sizeOverride(m);
        double def = this.plugin.defaultSize(m);
        double cur = ov != null ? ov : def;
        lore.add(line("Размер в полёте: " + fmt(cur) + " бл." + (ov != null ? " (свой, по умолчанию " + fmt(def) + ")" : " (по умолчанию)"),
            NamedTextColor.YELLOW));
        lore.add(line("Вариантов окраски: " + m.variants.size(), NamedTextColor.GRAY));
        lore.add(line("id: " + m.id, NamedTextColor.DARK_GRAY));
        if (m.id.equalsIgnoreCase(this.plugin.defaultSkinId())) {
            lore.add(line("Скин по умолчанию (config.yml → skins.default)", NamedTextColor.AQUA));
        }
        lore.add(Component.empty());
        lore.add(line("ЛКМ — вкл/выкл · ПКМ / Shift+ПКМ — размер ±0.1", NamedTextColor.DARK_GRAY));
        lore.add(line("Shift+ЛКМ — +0.5 · Q — размер по умолчанию", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        it.setAmount(Math.max(1, Math.min(64, (int) Math.round(cur * 10))));
        return it;
    }

    private ItemStack baseIcon(SkinCatalog.Mob m) {
        if (m.bird != null) {
            // Administrative controls must not depend on downloaded skull textures.
            return new ItemStack(m.bird.fantasy() ? Material.AMETHYST_SHARD : Material.FEATHER);
        }
        if (m.custom) {
            return new ItemStack(Material.PAPER);
        }
        Material egg = Material.matchMaterial(m.id + "_spawn_egg");
        return new ItemStack(egg != null ? egg : Material.PHANTOM_MEMBRANE);
    }

    private static ItemStack button(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) {
            l.add(line(s, NamedTextColor.GRAY));
        }
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private static Component line(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ================================================================== клики

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Holder h)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize())) {
            return;
        }
        if (!player.hasPermission("fpvdrone.admin")) {
            Bukkit.getScheduler().runTask(this.plugin, () -> player.closeInventory());
            return;
        }
        int slot = event.getRawSlot();
        switch (slot) {
            case 45 -> {
                this.reopen(player, h, h.page - 1, h.filter);
                return;
            }
            case 53 -> {
                this.reopen(player, h, h.page + 1, h.filter);
                return;
            }
            case 46 -> {
                Filter next = Filter.values()[(h.filter.ordinal() + 1) % Filter.values().length];
                this.reopen(player, h, 0, next);
                return;
            }
            case 47 -> {
                this.plugin.cycleStabilization();
                this.reopen(player, h, h.page, h.filter);
                return;
            }
            case 48, 50 -> {
                boolean enable = slot == 48;
                Set<String> affected = new HashSet<>();
                for (SkinCatalog.Mob m : this.list(h.filter)) {
                    String id = m.id.toLowerCase(Locale.ROOT);
                    if (enable) {
                        if (this.disabled.remove(id)) affected.add(id);
                    } else if (!id.equalsIgnoreCase(this.plugin.defaultSkinId())) {
                        if (this.disabled.add(id)) affected.add(id);
                    }
                }
                this.changed(affected);
                this.reopen(player, h, h.page, h.filter);
                return;
            }
            default -> {
            }
        }
        if (slot < 0 || slot >= h.shown.size()) {
            return;
        }
        SkinCatalog.Mob m = h.shown.get(slot);
        String id = m.id.toLowerCase(Locale.ROOT);
        ClickType c = event.getClick();
        if (c == ClickType.LEFT) {
            if (this.disabled.contains(id)) {
                this.disabled.remove(id);
            } else if (id.equalsIgnoreCase(this.plugin.defaultSkinId())) {
                player.sendMessage(Component.text("Это скин по умолчанию — его нельзя выключить (смени skins.default в config.yml)", NamedTextColor.RED));
                return;
            } else {
                this.disabled.add(id);
            }
        } else if (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT || c == ClickType.SHIFT_LEFT) {
            double step = c == ClickType.RIGHT ? 0.1 : c == ClickType.SHIFT_RIGHT ? -0.1 : 0.5;
            Double ov = this.sizeOverride(m);
            double cur = ov != null ? ov : this.plugin.defaultSize(m);
            this.sizes.put(id, clamp(Math.round((cur + step) * 10.0) / 10.0));
        } else if (c == ClickType.DROP || c == ClickType.CONTROL_DROP) {
            this.sizes.remove(id);
        } else {
            return;
        }
        this.changed(Set.of(id));
        event.getInventory().setItem(slot, this.icon(m));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void reopen(Player player, Holder current, int page, Filter filter) {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) == current) {
                this.open(player, page, filter);
            }
        });
    }

    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder) {
                player.closeInventory();
            }
        }
    }

    boolean setEnabled(SkinCatalog.Mob mob, boolean enabled) {
        String id = mob.id.toLowerCase(Locale.ROOT);
        if (!enabled && id.equalsIgnoreCase(this.plugin.defaultSkinId())) return false;
        boolean changed = enabled ? this.disabled.remove(id) : this.disabled.add(id);
        if (changed) this.changed(Set.of(id));
        return true;
    }

    private void changed(Set<String> affected) {
        if (affected.isEmpty()) return;
        this.save();
        this.plugin.applyAdminSettings(affected);
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder h) {
                    this.open(viewer, h.page, h.filter);
                }
            }
        });
    }
}
