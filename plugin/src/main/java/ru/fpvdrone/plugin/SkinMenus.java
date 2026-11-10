package ru.fpvdrone.plugin;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Открытые меню /flyskin и управление ими (клавиши, колёсико, клики по кнопкам). */
final class SkinMenus implements Listener {
    private final FpvDronePlugin plugin;
    private final Map<UUID, SkinMenu> open = new HashMap<>();
    private long tick;

    SkinMenus(FpvDronePlugin plugin) {
        this.plugin = plugin;
    }

    boolean isOpen(Player player) {
        return this.open.containsKey(player.getUniqueId());
    }

    void open(Player player) {
        this.close(player, false);
        SkinMenu menu = new SkinMenu(this.plugin, this.plugin.catalog(), player, this.plugin.skinOf(player));
        Input input = player.getCurrentInput();
        menu.lastLeft = input.isLeft();
        menu.lastRight = input.isRight();
        menu.lastJump = input.isJump();
        menu.lastSneak = input.isSneak();
        menu.lastForward = input.isForward();
        menu.lastBack = input.isBackward();
        this.open.put(player.getUniqueId(), menu);
    }

    /** @param cancelled true — вышли без выбора */
    void close(Player player, boolean cancelled) {
        SkinMenu menu = this.open.remove(player.getUniqueId());
        if (menu == null) {
            return;
        }
        menu.remove();
        if (cancelled && player.isOnline()) {
            player.sendMessage(Component.text("Выбор скина закрыт без изменений", NamedTextColor.GRAY));
        }
    }

    void closeAll() {
        for (SkinMenu menu : new ArrayList<>(this.open.values())) {
            menu.remove();
        }
        this.open.clear();
    }

    void tick() {
        this.tick++;
        if (this.open.isEmpty()) {
            return;
        }
        for (SkinMenu menu : new ArrayList<>(this.open.values())) {
            if (!menu.tick()) {
                this.close(menu.player, false);
            }
        }
    }

    // ================================================================== управление

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        SkinMenu menu = this.open.get(event.getPlayer().getUniqueId());
        if (menu == null) {
            return;
        }
        Input in = event.getInput();
        boolean left = in.isLeft() && !menu.lastLeft;
        boolean right = in.isRight() && !menu.lastRight;
        boolean jump = in.isJump() && !menu.lastJump;
        boolean sneak = in.isSneak() && !menu.lastSneak;
        boolean forward = in.isForward() && !menu.lastForward;
        boolean back = in.isBackward() && !menu.lastBack;
        menu.lastLeft = in.isLeft();
        menu.lastRight = in.isRight();
        menu.lastJump = in.isJump();
        menu.lastSneak = in.isSneak();
        menu.lastForward = in.isForward();
        menu.lastBack = in.isBackward();
        if (left) {
            menu.move(-1);
        } else if (right) {
            menu.move(1);
        } else if (jump) {
            menu.accept();
        } else if (sneak || back) {
            menu.back();
        } else if (forward) {
            menu.openVariants();
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        SkinMenu menu = this.open.get(event.getPlayer().getUniqueId());
        if (menu != null) {
            event.setCancelled(true);
            menu.toggleVariants();
        }
    }

    @EventHandler
    public void onScroll(PlayerItemHeldEvent event) {
        SkinMenu menu = this.open.get(event.getPlayer().getUniqueId());
        if (menu == null) {
            return;
        }
        event.setCancelled(true);
        int diff = Math.floorMod(event.getNewSlot() - event.getPreviousSlot(), 9);
        menu.move(diff <= 4 ? 1 : -1);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (this.open.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        SkinMenu menu = this.open.get(event.getPlayer().getUniqueId());
        if (menu != null && menu.click(event.getRightClicked().getUniqueId(), this.tick)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        SkinMenu menu = this.open.get(event.getPlayer().getUniqueId());
        if (menu != null && menu.click(event.getAttacked().getUniqueId(), this.tick)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // В меню не ставим блоки и не используем предметы.
        if (this.open.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Игрок стоит на месте, пока открыто меню (вертеть головой можно). */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        SkinMenu menu = this.open.get(event.getPlayer().getUniqueId());
        if (menu == null) {
            return;
        }
        Location from = menu.frozenAt;
        Location to = event.getTo();
        if (to.getX() != from.getX() || to.getY() != from.getY() || to.getZ() != from.getZ()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            event.setTo(fixed);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (this.open.containsKey(event.getPlayer().getUniqueId()) && event.getFrom().distanceSquared(event.getTo()) > 1.0) {
            this.close(event.getPlayer(), true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && this.open.containsKey(player.getUniqueId())) {
            this.close(player, true);
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        this.close(event.getPlayer(), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.close(event.getPlayer(), false);
    }
}
