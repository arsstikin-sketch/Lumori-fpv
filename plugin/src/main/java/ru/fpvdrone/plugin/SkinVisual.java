package ru.fpvdrone.plugin;

import org.bukkit.World;

/** То, чем дрон выглядит для других игроков: модель дрона или скин моба. */
interface SkinVisual {
    void update(World world, DroneState state);

    void setColors(DroneColors colors);

    boolean isValid();

    void remove();

    /** Показать или спрятать модель от одного игрока (пилоту — пока он управляет). */
    default void setVisibleFor(org.bukkit.plugin.Plugin plugin, org.bukkit.entity.Player player, boolean visible) {
    }
}
