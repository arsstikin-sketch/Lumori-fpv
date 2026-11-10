package ru.fpvdrone.plugin;

import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.Location;

/** Скин без ресурспака: настоящий моб (одна сущность) повторяет поворот и наклон дрона. */
final class MobEntityVisual implements SkinVisual {
    private final LivingEntity mob;

    MobEntityVisual(Plugin plugin, Location at, SkinCatalog.Variant variant, double sizeBlocks, Player hideFrom, boolean glow) {
        this.mob = MobEntities.spawn(at, variant, sizeBlocks, e -> {
            if (glow) {
                e.setGlowing(true);
            }
        });
        if (this.mob != null && hideFrom != null) {
            hideFrom.hideEntity(plugin, this.mob);
        }
    }

    @Override
    public void update(World world, DroneState s) {
        if (!this.isValid()) {
            return;
        }
        float[] a = MobEntities.angles(s);
        MobEntities.pose(this.mob, world, s.x, s.y, s.z, a[0], a[1], a[2] > 0.5F);
    }

    @Override
    public void setColors(DroneColors colors) {
        // у настоящего моба цвет не меняется
    }

    @Override
    public void setVisibleFor(Plugin plugin, Player player, boolean visible) {
        if (this.mob == null) {
            return;
        }
        if (visible) {
            player.showEntity(plugin, this.mob);
        } else {
            player.hideEntity(plugin, this.mob);
        }
    }

    @Override
    public boolean isValid() {
        return this.mob != null && this.mob.isValid();
    }

    @Override
    public void remove() {
        if (this.mob != null && this.mob.isValid()) {
            this.mob.remove();
        }
    }
}
