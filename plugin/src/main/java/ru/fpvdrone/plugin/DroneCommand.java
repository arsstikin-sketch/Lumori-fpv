package ru.fpvdrone.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * /drone color <часть> <цвет>   — покрасить часть дрона
 * /drone color reset            — вернуть цвета по умолчанию
 * /drone colors                 — показать текущие цвета
 * /drone test [секунды]         — демо-дрон рядом с вами (проверить, как видят без мода)
 * /drone list                   — кто сейчас летает (админ)
 * /drone reload                 — перечитать config.yml (админ)
 */
final class DroneCommand implements CommandExecutor, TabCompleter {
    private static final String ADMIN = "fpvdrone.admin";
    private final FpvDronePlugin plugin;

    DroneCommand(FpvDronePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            this.help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "color", "цвет" -> this.color(sender, args);
            case "colors", "цвета" -> this.showColors(sender);
            case "test", "тест" -> this.test(sender, args);
            case "list" -> this.list(sender);
            case "reload" -> this.reload(sender);
            default -> this.help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("FPV Drone:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" /flyskin — выбрать моба, которым выглядит твой дрон", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(" /drone color <часть> <цвет> — покрасить дрон", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("   части: " + String.join(", ", DroneColors.partIds()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("   цвета: " + String.join(", ", DroneColors.COLORS), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" /drone color reset — цвета по умолчанию", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(" /drone colors — текущие цвета", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(" /drone test [сек] — демо-дрон рядом (проверка без мода)", NamedTextColor.YELLOW));
        if (sender.hasPermission(ADMIN)) {
            sender.sendMessage(Component.text(" /drone list, /drone reload — для админов", NamedTextColor.YELLOW));
        }
    }

    private void color(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Только для игроков", NamedTextColor.RED));
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
            this.plugin.applyColors(player, new DroneColors());
            sender.sendMessage(Component.text("Цвета дрона сброшены", NamedTextColor.GREEN));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Использование: /drone color <часть> <цвет>", NamedTextColor.RED));
            return;
        }
        DroneColors.Part part = DroneColors.Part.parse(args[1]);
        if (part == null) {
            sender.sendMessage(Component.text("Нет такой части. Части: " + String.join(", ", DroneColors.partIds()), NamedTextColor.RED));
            return;
        }
        String color = args[2].toLowerCase(Locale.ROOT);
        if (!DroneColors.isValidColor(color)) {
            sender.sendMessage(Component.text("Нет такого цвета. Цвета: " + String.join(", ", DroneColors.COLORS), NamedTextColor.RED));
            return;
        }
        DroneColors colors = this.plugin.colorsOf(player);
        colors.set(part, color);
        this.plugin.applyColors(player, colors);
        sender.sendMessage(Component.text("Цвет «" + part.ru + "»: " + color, NamedTextColor.GREEN));
    }

    private void showColors(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return;
        }
        DroneColors colors = this.plugin.colorsOf(player);
        sender.sendMessage(Component.text("Цвета вашего дрона:", NamedTextColor.GOLD));
        for (DroneColors.Part part : DroneColors.Part.values()) {
            sender.sendMessage(Component.text(" " + part.id() + " (" + part.ru + "): " + colors.get(part), NamedTextColor.YELLOW));
        }
    }

    private void test(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Только для игроков", NamedTextColor.RED));
            return;
        }
        int seconds = 30;
        if (args.length >= 2) {
            try {
                seconds = Math.max(3, Math.min(300, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                // оставляем 30
            }
        }
        this.plugin.startTestDrone(player, seconds);
        sender.sendMessage(Component.text("Демо-дрон (твой скин) летает вокруг тебя " + seconds + " с.", NamedTextColor.GREEN));
    }

    private void list(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Component.text("Нет прав", NamedTextColor.RED));
            return;
        }
        int flying = 0;
        sender.sendMessage(Component.text("Игроки с модом (дальность " + this.plugin.maxRange() + "):", NamedTextColor.GOLD));
        for (Map.Entry<UUID, FpvDronePlugin.PilotSession> e : this.plugin.sessions().entrySet()) {
            FpvDronePlugin.PilotSession s = e.getValue();
            if (!s.hasMod) {
                continue;
            }
            Player p = this.plugin.getServer().getPlayer(e.getKey());
            String name = p == null ? e.getKey().toString() : p.getName();
            String status = s.state == null ? "на земле" : String.format(Locale.ROOT, "в полёте, %.1f м/с%s", s.state.speed, s.state.armed ? "" : " (дизарм)");
            if (s.state != null) {
                flying++;
            }
            sender.sendMessage(Component.text(" " + name + ": " + status + (s.rejected > 0 ? ", отклонено пакетов: " + s.rejected : ""), NamedTextColor.YELLOW));
        }
        sender.sendMessage(Component.text("Сейчас летают: " + flying, NamedTextColor.GRAY));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Component.text("Нет прав", NamedTextColor.RED));
            return;
        }
        this.plugin.reloadAll();
        sender.sendMessage(Component.text("config.yml перечитан", NamedTextColor.GREEN));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("color", "colors", "test", "list", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("color")) {
            List<String> options = new ArrayList<>(DroneColors.partIds());
            options.add("reset");
            for (String s : options) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("color")) {
            for (String s : DroneColors.COLORS) {
                if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
