package ru.fpvdrone.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.joml.Matrix4f;

/**
 * Меню /flyskin прямо в мире, только для одного игрока (другие его не видят):
 * затемнение, карусель мобов (центральный крупный и яркий, боковые меньше и темнее),
 * подменю вариаций, кнопки. Управление: A/D или колёсико — листать, W или F — вариации,
 * Пробел — принять, Shift — назад; кнопки можно нажимать мышкой.
 */
final class SkinMenu {
    enum Mode { MOBS, VARIANTS }

    private static final double DIST = 2.0;      // от глаз до центрального моба
    private static final double SPACING = 0.85;  // между мобами в карусели

    private final FpvDronePlugin plugin;
    private final List<SkinCatalog.Mob> mobs;
    private final LivingEntity[] mobSlots = new LivingEntity[5];
    private final SkinCatalog.Variant[] mobSlotVariant = new SkinCatalog.Variant[5];
    private final double[] mobSlotOffset = new double[5];
    private final BirdVisual[] birdSlots = new BirdVisual[5];
    private final SkinCatalog.Variant[] birdSlotVariant = new SkinCatalog.Variant[5];
    private final double[] birdSlotOffset = new double[5];
    final Player player;
    final Location frozenAt;
    private final World world;
    private final double fx;
    private final double fz;
    private final double rx;
    private final double rz;
    private final double faceYaw;   // поворот модели «мордой к игроку»
    private final Location anchor;

    private final List<Entity> entities = new ArrayList<>();
    private final Map<UUID, Runnable> buttons = new HashMap<>();
    private TextDisplay title;
    private TextDisplay name;
    private TextDisplay variantButton;
    private TextDisplay acceptButton;
    private TextDisplay hint;

    private Mode mode = Mode.MOBS;
    private int mobCursor;
    private final int[] chosenVariant;
    private int variantCursor;
    private int tick;
    private int slideDelta;
    private int slideWait;
    private int busyTicks;
    private long lastClickTick = -10;

    // предыдущее состояние клавиш (реагируем на нажатие, а не на удержание)
    boolean lastLeft;
    boolean lastRight;
    boolean lastJump;
    boolean lastSneak;
    boolean lastForward;
    boolean lastBack;

    SkinMenu(FpvDronePlugin plugin, Player player, SkinCatalog.Variant current) {
        this.plugin = plugin;
        this.mobs = plugin.menuMobs();
        this.player = player;
        this.frozenAt = player.getLocation().clone();
        this.world = player.getWorld();
        Location eye = player.getEyeLocation();
        double yaw = Math.toRadians(eye.getYaw());
        this.fx = -Math.sin(yaw);
        this.fz = Math.cos(yaw);
        this.rx = -Math.cos(yaw);
        this.rz = -Math.sin(yaw);
        this.faceYaw = Math.atan2(-this.fx, -this.fz);
        this.anchor = new Location(this.world, eye.getX() + this.fx * DIST, eye.getY() - 0.1, eye.getZ() + this.fz * DIST, 0.0F, 0.0F);

        List<SkinCatalog.Mob> mobs = this.mobs;
        this.chosenVariant = new int[mobs.size()];
        for (int i = 0; i < mobs.size(); i++) {
            if (current != null && mobs.get(i) == current.mob) {
                this.mobCursor = i;
                this.chosenVariant[i] = Math.max(0, mobs.get(i).variants.indexOf(current));
            }
        }
        this.build(eye, yaw);
        this.refreshAll(0);
        this.updateTexts();
    }

    // ================================================================== создание сущностей

    private void build(Location eye, double yaw) {
        // Затемнение: два слоя тёмного стекла за каруселью.
        for (int layer = 0; layer < this.plugin.menuDarkLayers(); layer++) {
            double d = 2.9 + layer * 0.08;
            Location at = new Location(this.world, eye.getX() + this.fx * d, eye.getY(), eye.getZ() + this.fz * d, 0.0F, 0.0F);
            Mat4 m = Mat4.rotationY(-yaw).mul(Mat4.scale(16.0, 10.0, 0.02)).mul(Mat4.translation(-0.5, -0.5, -0.5));
            this.spawn(at, BlockDisplay.class, b -> {
                b.setBlock(Material.BLACK_STAINED_GLASS.createBlockData());
                b.setBrightness(new Display.Brightness(0, 0));
                b.setTransformationMatrix(new Matrix4f().set(m.m));
            });
        }
        this.title = this.text(0, 0.95, 0.75, Color.fromARGB(0, 0, 0, 0));
        this.name = this.text(0, -0.70, 0.65, Color.fromARGB(0, 0, 0, 0));
        this.variantButton = this.text(0, -0.90, 0.45, Color.fromARGB(150, 60, 40, 90));
        this.button(this.variantButton, 0, -0.90, 1.1, 0.16, this::toggleVariants);

        TextDisplay prev = this.text(-0.8, -1.13, 0.6, Color.fromARGB(150, 30, 30, 30));
        prev.text(Component.text(" ◀ ", NamedTextColor.WHITE));
        this.button(prev, -0.8, -1.13, 0.3, 0.2, () -> this.move(-1));
        this.acceptButton = this.text(0, -1.13, 0.6, Color.fromARGB(180, 30, 110, 40));
        this.button(this.acceptButton, 0, -1.13, 0.6, 0.2, this::accept);
        TextDisplay next = this.text(0.8, -1.13, 0.6, Color.fromARGB(150, 30, 30, 30));
        next.text(Component.text(" ▶ ", NamedTextColor.WHITE));
        this.button(next, 0.8, -1.13, 0.3, 0.2, () -> this.move(1));

        this.hint = this.text(0, -1.42, 0.33, Color.fromARGB(0, 0, 0, 0));
    }

    private <T extends Entity> T spawn(Location at, Class<T> type, Consumer<T> setup) {
        T entity = this.world.spawn(at, type, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            setup.accept(e);
        });
        this.player.showEntity(this.plugin, entity);
        this.entities.add(entity);
        return entity;
    }

    /** Точка на «экране» меню: dx вправо, dy вверх, чуть ближе к игроку, чем мобы. */
    private Location at(double dx, double dy, double forward) {
        return new Location(this.world,
            this.anchor.getX() + this.rx * dx + this.fx * forward,
            this.anchor.getY() + dy,
            this.anchor.getZ() + this.rz * dx + this.fz * forward, 0.0F, 0.0F);
    }

    private TextDisplay text(double dx, double dy, double scale, Color background) {
        return this.spawn(this.at(dx, dy, -0.15), TextDisplay.class, t -> {
            t.setBillboard(Display.Billboard.CENTER);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setBackgroundColor(background);
            t.setShadowed(true);
            t.setLineWidth(400);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTransformationMatrix(new Matrix4f().set(Mat4.scale(scale, scale, scale).m));
        });
    }

    private void button(TextDisplay label, double dx, double dy, double width, double height, Runnable action) {
        Location at = this.at(dx, dy - 0.03, -0.2);
        Interaction hitbox = this.spawn(at, Interaction.class, i -> {
            i.setInteractionWidth((float) width);
            i.setInteractionHeight((float) (height + 0.06));
            i.setResponsive(true);
        });
        this.buttons.put(hitbox.getUniqueId(), action);
    }

    // ================================================================== карусель

    private int count() {
        return this.mode == Mode.MOBS ? this.mobs.size() : this.currentMob().variants.size();
    }

    private int cursor() {
        return this.mode == Mode.MOBS ? this.mobCursor : this.variantCursor;
    }

    private SkinCatalog.Mob currentMob() {
        return this.mobs.get(this.mobCursor);
    }

    private SkinCatalog.Variant variantAt(int index) {
        if (this.mode == Mode.MOBS) {
            SkinCatalog.Mob mob = this.mobs.get(index);
            return mob.variants.get(Math.min(this.chosenVariant[index], mob.variants.size() - 1));
        }
        return this.currentMob().variants.get(index);
    }

    private SkinCatalog.Variant selected() {
        return this.variantAt(this.cursor());
    }

    /** Какие места карусели заняты при n элементах (без повторов одного и того же моба). */
    private static boolean visible(int offset, int n) {
        return switch (n) {
            case 1 -> offset == 0;
            case 2 -> offset == 0 || offset == 1;
            case 3 -> Math.abs(offset) <= 1;
            case 4 -> offset >= -1;
            default -> true;
        };
    }

    private static double fitFor(double offset) {
        double a = Math.min(3.0, Math.abs(offset));
        double[] fit = {0.85, 0.5, 0.32, 0.12};
        int i = (int) Math.floor(a);
        if (i >= 3) {
            return fit[3];
        }
        double t = a - i;
        return fit[i] + (fit[i + 1] - fit[i]) * t;
    }

    private static int brightnessFor(int offset) {
        return switch (Math.abs(offset)) {
            case 0 -> 15;
            case 1 -> 6;
            default -> 2;
        };
    }

    /** Перерисовать карусель. delta != 0 — начать анимацию сдвига. */
    private void refreshAll(int delta) {
        int n = this.count();
        int cursor = this.cursor();
        this.refreshBirds(delta, n, cursor);
        this.refreshMobs(delta, n, cursor);
    }

    /** Второй шаг сдвига: плавно доехать до своих мест. */
    private void finishSlide() {
        this.slideDelta = 0;
    }

    // ------------------------------------------------------------------ карусель из настоящих мобов

    private void refreshMobs(int delta, int n, int cursor) {
        for (int i = 0; i < 5; i++) {
            int offset = i - 2;
            if (!visible(offset, n)) {
                this.removeMob(i);
                continue;
            }
            SkinCatalog.Variant v = this.variantAt(Math.floorMod(cursor + offset, n));
            if (v.mob.bird != null) {
                this.removeMob(i);
                continue;
            }
            double from = delta == 0 ? offset : Math.max(-3.0, Math.min(3.0, offset + delta));
            if (this.mobSlotVariant[i] != v || this.mobSlots[i] == null || !this.mobSlots[i].isValid()) {
                this.removeMob(i);
                double[] p = this.mobPoint(from);
                LivingEntity mob = MobEntities.spawn(new Location(this.world, p[0], p[1], p[2]), v, this.mobSize(v, from), e -> {
                    e.setVisibleByDefault(false);
                    e.setPersistent(false);
                });
                if (mob != null) {
                    this.player.showEntity(this.plugin, mob);
                    this.entities.add(mob);
                    // Reassert visibility after the server has registered the entity tracker.
                    this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                        if (mob.isValid() && this.player.isOnline() && this.entities.contains(mob)) {
                            this.player.showEntity(this.plugin, mob);
                        }
                    });
                }
                this.mobSlots[i] = mob;
                this.mobSlotVariant[i] = v;
            }
            this.mobSlotOffset[i] = from;
            this.placeMob(i, 0.0, 0.0F);
        }
    }

    private void removeMob(int i) {
        LivingEntity mob = this.mobSlots[i];
        if (mob != null) {
            this.entities.remove(mob);
            if (mob.isValid()) {
                mob.remove();
            }
        }
        this.mobSlots[i] = null;
        this.mobSlotVariant[i] = null;
    }

    private double mobSize(SkinCatalog.Variant v, double offset) {
        return fitFor(offset) * (this.mode == Mode.VARIANTS ? v.scaleMul : 1.0F);
    }

    private double[] mobPoint(double offset) {
        double a = Math.abs(offset);
        return new double[] {
            this.anchor.getX() + this.rx * offset * SPACING + this.fx * a * 0.35,
            this.anchor.getY() - 0.05 * a,
            this.anchor.getZ() + this.rz * offset * SPACING + this.fz * a * 0.35};
    }

    private void placeMob(int i, double spin, float pitch) {
        LivingEntity mob = this.mobSlots[i];
        if (mob == null || !mob.isValid()) {
            return;
        }
        double offset = this.mobSlotOffset[i];
        double[] p = this.mobPoint(offset);
        float yaw = (float) -Math.toDegrees(this.faceYaw + offset * 0.45 + spin);
        MobEntities.pose(mob, this.world, p[0], p[1], p[2], yaw, pitch);
    }

    /** Сдвиг карусели и фигуры центрального моба: разворот и кивки. */
    private void tickMobs() {
        for (int i = 0; i < 5; i++) {
            LivingEntity mob = this.mobSlots[i];
            if (mob == null || !mob.isValid()) {
                continue;
            }
            double target = i - 2;
            double cur = this.mobSlotOffset[i];
            boolean moved = cur != target;
            if (moved) {
                double step = 0.25;
                cur = Math.abs(target - cur) <= step ? target : cur + Math.signum(target - cur) * step;
                this.mobSlotOffset[i] = cur;
                MobEntities.setSize(mob, this.mobSlotVariant[i], this.mobSize(this.mobSlotVariant[i], cur));
            }
            if (i == 2) {
                double spin = Math.toRadians(this.tick * 3.0);
                float pitch = (float) (Math.sin(this.tick * 0.08) * 35.0);
                this.placeMob(i, spin, pitch);
            } else if (moved || this.tick % 20 == 0) {
                this.placeMob(i, 0.0, 0.0F);
            }
        }
    }

    // ------------------------------------------------------------------ птицы в карусели

    private void refreshBirds(int delta, int n, int cursor) {
        BirdVisual[] previous = this.birdSlots.clone();
        SkinCatalog.Variant[] variants = this.birdSlotVariant.clone();
        double[] offsets = this.birdSlotOffset.clone();
        java.util.Arrays.fill(this.birdSlots, null);
        java.util.Arrays.fill(this.birdSlotVariant, null);
        for (int i = 0; i < 5; i++) {
            int offset = i - 2;
            SkinCatalog.Variant v = visible(offset, n) ? this.variantAt(Math.floorMod(cursor + offset, n)) : null;
            if (v == null || v.mob.bird == null) continue;
            double from = delta == 0 ? offset : Math.max(-3.0, Math.min(3.0, offset + delta));
            // Переносим саму модель между слотами: фаза взмахов и загруженные головы сохраняются.
            for (int j = 0; j < previous.length; j++) {
                if (variants[j] == v && previous[j] != null && previous[j].isValid()) {
                    this.birdSlots[i] = previous[j];
                    from = offsets[j];
                    previous[j] = null;
                    break;
                }
            }
            if (this.birdSlots[i] == null) {
                double[] p = this.mobPoint(from);
                BirdData.Bird b = v.mob.bird;
                double fit = this.mobSize(v, 0.0) * b.len / Math.max(b.len, b.span);
                this.birdSlots[i] = new BirdVisual(this.plugin, this.plugin.birdHeads(), new Location(this.world, p[0], p[1], p[2]),
                    b, v.birdVariant, fit, null, this.player, 32.0F, true);
            }
            this.birdSlotVariant[i] = v;
            this.birdSlotOffset[i] = from;
        }
        for (BirdVisual bird : previous) if (bird != null) bird.remove();
    }

    private void removeBird(int i) {
        if (this.birdSlots[i] != null) {
            this.birdSlots[i].remove();
        }
        this.birdSlots[i] = null;
        this.birdSlotVariant[i] = null;
    }

    /** Птицы в карусели: боковые стоят, центральная летает — машет, кружится, закладывает виражи. */
    private void tickBirds() {
        for (int i = 0; i < 5; i++) {
            BirdVisual bird = this.birdSlots[i];
            if (bird == null || !bird.isValid()) {
                continue;
            }
            double target = i - 2;
            double cur = this.birdSlotOffset[i];
            if (cur != target) {
                double step = 0.25;
                cur = Math.abs(target - cur) <= step ? target : cur + Math.signum(target - cur) * step;
                this.birdSlotOffset[i] = cur;
            }
            double[] p = this.mobPoint(cur);
            double a = Math.abs(cur);
            double shrink = Math.max(0.35, 1.0 - a * 0.25);
            Location at = new Location(this.world, p[0], p[1] - Math.min(1.0, Math.abs(cur)) * 0.15, p[2]);
            if (i == 2) {
                double spin = Math.toRadians(this.tick * 2.0);
                double bank = Math.sin(this.tick * 0.06) * 30.0;
                double pitch = Math.sin(this.tick * 0.045) * 25.0;
                double thr = 0.35 + 0.35 * (0.5 + 0.5 * Math.sin(this.tick * 0.03));
                bird.pose(at, this.faceYaw + Math.PI + spin, shrink, thr, pitch, bank, true);
            } else {
                bird.pose(at, this.faceYaw + Math.PI + cur * 0.45, shrink, 0.0, 0.0, 0.0, false);
            }
        }
    }

    private void updateTexts() {
        SkinCatalog.Mob mob = this.currentMob();
        SkinCatalog.Variant sel = this.selected();
        int n = this.count();
        if (this.mode == Mode.MOBS) {
            this.title.text(Component.text("Выбор скина", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
            this.name.text(Component.text(mob.name, NamedTextColor.WHITE).decorate(TextDecoration.BOLD)
                .append(Component.text("  " + (this.mobCursor + 1) + "/" + n, NamedTextColor.GRAY)));
            if (mob.variants.size() > 1) {
                this.variantButton.text(Component.text(" Вариант: " + sel.name + "  ▼ F ", NamedTextColor.LIGHT_PURPLE));
                this.variantButton.setBackgroundColor(Color.fromARGB(150, 60, 40, 90));
            } else {
                this.variantButton.text(Component.empty());
                this.variantButton.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            }
            this.acceptButton.text(Component.text(" Принять ", NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
            this.hint.text(Component.text("A/D или колёсико — листать · W или F — вариант\nПробел — принять · Shift — выйти", NamedTextColor.GRAY));
        } else {
            this.title.text(Component.text("Вариант: " + mob.name, NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD));
            this.name.text(Component.text(sel.name, NamedTextColor.WHITE).decorate(TextDecoration.BOLD)
                .append(Component.text("  " + (this.variantCursor + 1) + "/" + n, NamedTextColor.GRAY)));
            this.variantButton.text(Component.text(" ◀ Назад к мобам (S / Shift) ", NamedTextColor.GRAY));
            this.variantButton.setBackgroundColor(Color.fromARGB(150, 40, 40, 40));
            this.acceptButton.text(Component.text(" Выбрать ", NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
            this.hint.text(Component.text("A/D или колёсико — листать\nПробел — выбрать · Shift — назад", NamedTextColor.GRAY));
        }
    }

    // ================================================================== действия

    void move(int delta) {
        int n = this.count();
        if (n <= 1) {
            return;
        }
        if (this.mode == Mode.MOBS) {
            this.mobCursor = Math.floorMod(this.mobCursor + delta, n);
        } else {
            this.variantCursor = Math.floorMod(this.variantCursor + delta, n);
        }
        this.refreshAll(delta);
        this.updateTexts();
        this.player.playSound(this.player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.4F, 1.6F);
    }

    void toggleVariants() {
        if (this.mode == Mode.VARIANTS) {
            this.back();
            return;
        }
        SkinCatalog.Mob mob = this.currentMob();
        if (mob.variants.size() <= 1) {
            return;
        }
        this.mode = Mode.VARIANTS;
        this.variantCursor = this.chosenVariant[this.mobCursor];
        this.refreshAll(0);
        this.updateTexts();
        this.player.playSound(this.player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5F, 1.2F);
    }

    void openVariants() {
        if (this.mode == Mode.MOBS) {
            this.toggleVariants();
        }
    }

    /** Shift: из вариаций — назад к мобам, из мобов — закрыть без изменений. */
    void back() {
        if (this.mode == Mode.VARIANTS) {
            this.mode = Mode.MOBS;
            this.refreshAll(0);
            this.updateTexts();
            return;
        }
        this.plugin.skinMenus().close(this.player, true);
    }

    void accept() {
        if (this.mode == Mode.VARIANTS) {
            this.chosenVariant[this.mobCursor] = this.variantCursor;
            this.mode = Mode.MOBS;
            this.refreshAll(0);
            this.updateTexts();
            this.player.playSound(this.player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5F, 1.0F);
            return;
        }
        SkinCatalog.Variant chosen = this.selected();
        this.plugin.setSkin(this.player, chosen);
        this.player.playSound(this.player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.5F, 1.4F);
        this.player.sendMessage(Component.text("Скин дрона: " + chosen.fullName(), NamedTextColor.GREEN));
        this.plugin.skinMenus().close(this.player, false);
    }

    /** Клик по кнопке. @return true, если это была наша кнопка */
    boolean click(UUID entity, long serverTick) {
        Runnable action = this.buttons.get(entity);
        if (action == null) {
            return this.owns(entity);
        }
        if (serverTick - this.lastClickTick < 3) {
            return true; // правый клик приходит дважды (две руки)
        }
        this.lastClickTick = serverTick;
        action.run();
        return true;
    }

    boolean owns(UUID entity) {
        for (Entity e : this.entities) {
            if (e.getUniqueId().equals(entity)) {
                return true;
            }
        }
        return false;
    }

    /** @return false, если меню надо закрыть */
    boolean tick() {
        this.tick++;
        if (!this.player.isOnline() || this.player.getWorld() != this.world) {
            return false;
        }
        // Сдвиг в два шага: сначала мобы мгновенно встают на старые места (это должно дойти до клиента),
        // потом плавно едут на новые.
        if (this.slideDelta != 0 && --this.slideWait <= 0) {
            this.finishSlide();
        }
        if (this.tick % 20 == 0) {
            for (int i = 0; i < this.mobSlots.length; i++) {
                if (this.mobSlotVariant[i] != null && (this.mobSlots[i] == null || !this.mobSlots[i].isValid())) {
                    this.refreshMobs(0, this.count(), this.cursor());
                    break;
                }
            }
        }
        this.tickBirds();
        this.tickMobs();
        return true;
    }

    void diagnose() {
        for (int i = 0; i < this.mobSlots.length; i++) {
            LivingEntity mob = this.mobSlots[i];
            if (this.mobSlotVariant[i] == null) continue;
            this.player.sendMessage(Component.text("Слот " + i + ": " + this.mobSlotVariant[i].fullName()
                + (mob == null ? " — не создан" : " valid=" + mob.isValid() + " visible=" + this.player.canSee(mob)
                    + " invisible=" + mob.isInvisible() + " tracked=" + mob.getTrackedBy().contains(this.player)
                    + " height=" + String.format(java.util.Locale.ROOT, "%.2f", mob.getHeight()))));
        }
    }

    void remove() {
        for (int i = 0; i < 5; i++) {
            this.removeBird(i);
        }
        for (Entity e : this.entities) {
            if (e.isValid()) {
                e.remove();
            }
        }
        this.entities.clear();
        this.buttons.clear();
    }
}
