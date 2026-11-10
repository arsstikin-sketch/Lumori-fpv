package ru.fpvdrone.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * /flyskin                       — открыть меню выбора скина
 * /flyskin set <моб> [вариант]   — выбрать без меню
 * /flyskin list                  — все мобы и вариации
 * /flyskin reset                 — скин по умолчанию
 * /flyskin reload                — перечитать настройки (админ)
 * /flyskin heads ...             — скины голов для птиц: status, upload [ключ], stop, export, reload
 * /flyskin admin                 — панель админа: какие скины видят игроки и их размеры
 */
final class FlySkinCommand implements CommandExecutor, TabCompleter {
    private static final String ADMIN = "fpvdrone.admin";
    private final FpvDronePlugin plugin;

    FlySkinCommand(FpvDronePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission(ADMIN)) {
                sender.sendMessage(Component.text("Нет прав", NamedTextColor.RED));
                return true;
            }
            this.plugin.reloadAll();
            sender.sendMessage(Component.text("Настройки перечитаны", NamedTextColor.GREEN));
            return true;
        }
        if (sub.equals("heads") || sub.equals("головы")) {
            this.heads(sender, args);
            return true;
        }
        if (sub.equals("list")) {
            this.list(sender);
            return true;
        }
        if (sub.equals("admin") || sub.equals("админ")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Component.text("Панель открывается только в игре", NamedTextColor.RED));
            } else if (!sender.hasPermission(ADMIN)) {
                sender.sendMessage(Component.text("Нет прав", NamedTextColor.RED));
            } else {
                this.plugin.adminPanel().open(p, 0, AdminPanel.Filter.ALL);
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Только для игроков", NamedTextColor.RED));
            return true;
        }
        if (!this.plugin.skinsEnabled()) {
            sender.sendMessage(Component.text("Скины мобов выключены в config.yml (skins.enabled)", NamedTextColor.RED));
            return true;
        }
        switch (sub) {
            case "" , "menu", "меню" -> this.openMenu(player);
            case "set" -> this.set(player, args);
            case "reset" -> {
                this.plugin.setSkin(player, null);
                player.sendMessage(Component.text("Скин по умолчанию: " + this.plugin.skinOf(player).fullName(), NamedTextColor.GREEN));
            }
            default -> player.sendMessage(Component.text("/flyskin — меню, /flyskin set <моб> [вариант], /flyskin list, /flyskin reset, /flyskin admin", NamedTextColor.YELLOW));
        }
        return true;
    }

    private void openMenu(Player player) {
        if (this.plugin.isFlying(player)) {
            player.sendMessage(Component.text("Сначала выйди из дрона — меню появится перед тобой", NamedTextColor.RED));
            return;
        }
        this.plugin.skinMenus().open(player);
    }

    private void set(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("/flyskin set <моб> [вариант]", NamedTextColor.RED));
            return;
        }
        SkinCatalog.Mob mob = this.plugin.findMob(args[1]);
        if (mob == null) {
            player.sendMessage(Component.text("Нет такого моба. /flyskin list", NamedTextColor.RED));
            return;
        }
        if (!this.plugin.adminPanel().enabled(mob) && !player.hasPermission(ADMIN)) {
            player.sendMessage(Component.text("Этот скин выключен администратором", NamedTextColor.RED));
            return;
        }
        SkinCatalog.Variant variant = args.length >= 3 ? mob.variant(args[2]) : mob.variants.get(0);
        if (variant == null) {
            player.sendMessage(Component.text("Нет такого варианта. /flyskin list", NamedTextColor.RED));
            return;
        }
        this.plugin.setSkin(player, variant);
        player.sendMessage(Component.text("Скин дрона: " + variant.fullName(), NamedTextColor.GREEN));
    }

    private void list(CommandSender sender) {
        sender.sendMessage(Component.text("Скины (моб: варианты):", NamedTextColor.GOLD));
        for (SkinCatalog.Mob mob : this.plugin.allMobs()) {
            List<String> ids = new ArrayList<>();
            for (SkinCatalog.Variant v : mob.variants) {
                ids.add(v.id);
            }
            sender.sendMessage(Component.text(" " + mob.id + " (" + mob.name + "): ", NamedTextColor.YELLOW)
                .append(Component.text(String.join(", ", ids), NamedTextColor.GRAY)));
        }
    }

    /** /flyskin heads status|upload|stop|export|reload — скины голов для птиц. */
    private void heads(CommandSender sender, String[] args) {
        String sub = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        BirdHeads heads = this.plugin.birdHeads();
        List<String> all = this.plugin.birdSkins();
        if (!sub.equals("status") && !sender.hasPermission(ADMIN)) {
            sender.sendMessage(Component.text("Нет прав", NamedTextColor.RED));
            return;
        }
        switch (sub) {
            case "upload" -> {
                String key = args.length >= 3 ? args[2] : this.plugin.mineskinKey();
                int perMinute = this.plugin.mineskinPerMinute();
                if (key == null || key.isBlank()) {
                    perMinute = Math.min(perMinute, 6);
                    sender.sendMessage(Component.text("Ключ MineSkin не указан — пробую без ключа, медленно (6 в минуту). "
                        + "С бесплатным ключом с account.mineskin.org будет втрое быстрее: birds.mineskin-key в config.yml", NamedTextColor.YELLOW));
                }
                heads.uploadMissing(all, key, perMinute, sender, this.plugin::refreshBirdVisuals);
            }
            case "stop" -> {
                heads.stop();
                sender.sendMessage(Component.text("Остановлю после текущего скина. Продолжить: /flyskin heads upload", NamedTextColor.YELLOW));
            }
            case "export" -> {
                try {
                    int n = heads.exportPngs(all);
                    sender.sendMessage(Component.text("Картинки скинов (" + n + ") сохранены в plugins/FpvDrone/bird-skins/", NamedTextColor.GREEN));
                } catch (Exception e) {
                    sender.sendMessage(Component.text("Не получилось: " + e.getMessage(), NamedTextColor.RED));
                }
            }
            case "reload" -> {
                heads.load();
                this.plugin.doRefreshBirdVisuals();
                sender.sendMessage(Component.text("bird-heads.yml перечитан: " + heads.loaded(all) + " из " + all.size(), NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Скины голов птиц: загружено " + heads.loaded(all) + " из " + all.size()
                + (heads.busy() ? ". Идёт загрузка: " + heads.progress() : (heads.progress().isEmpty() ? "" : ". Последняя загрузка: " + heads.progress())),
                NamedTextColor.GOLD));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : new String[] {"set", "list", "reset", "reload", "heads", "admin"}) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("heads")) {
            for (String s : new String[] {"status", "upload", "stop", "export", "reload"}) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            for (SkinCatalog.Mob mob : this.plugin.allMobs()) {
                if (mob.id.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(mob.id);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            SkinCatalog.Mob mob = this.plugin.findMob(args[1]);
            if (mob != null) {
                for (SkinCatalog.Variant v : mob.variants) {
                    if (v.id.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                        out.add(v.id);
                    }
                }
            }
        }
        return out;
    }
}
