package ru.fpvdrone.plugin;

import java.util.Locale;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Vex;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Настоящие мобы как скин (без ресурспака): без ИИ, бессмертные, без звука и гравитации.
 * Поворот — рысканием, наклон — тангажом (фантом наклоняется всем телом, остальные — головой),
 * полёт вверх ногами — именем «Dinnerbone» (так игра переворачивает любого моба).
 */
final class MobEntities {
    static final String TAG = "fpvdrone_skin";

    private MobEntities() {
    }

    /** Во сколько раз игра увеличивает модель моба сама (гасты огромные). */
    private static double renderScale(String mobId) {
        return switch (mobId) {
            case "ghast" -> 4.5;
            case "happy_ghast" -> 4.0;
            default -> 1.0;
        };
    }

    /** Наибольший размер моба в блоках при масштабе 1. */
    static double vanillaSize(SkinCatalog.Variant v) {
        return Math.max(0.2, v.modelUnits / 16.0 * renderScale(v.mob.id));
    }

    static boolean supports(SkinCatalog.Mob mob) {
        return !mob.custom && type(mob) != null;
    }

    static EntityType type(SkinCatalog.Mob mob) {
        try {
            return Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(mob.id));
        } catch (Exception e) {
            return null;
        }
    }

    /** Заспавнить моба нужного вида и вариации. sizeBlocks — желаемый наибольший размер. */
    static LivingEntity spawn(Location at, SkinCatalog.Variant v, double sizeBlocks, Consumer<Entity> extra) {
        EntityType type = type(v.mob);
        if (type == null || type.getEntityClass() == null) {
            return null;
        }
        Entity e = spawnAs(at.getWorld(), at, type.getEntityClass(), ent -> {
            ent.setPersistent(false);
            ent.setSilent(true);
            ent.setInvulnerable(true);
            ent.setGravity(false);
            ent.addScoreboardTag(TAG);
            if (ent instanceof LivingEntity living) {
                living.setAI(false);
                living.setCollidable(false);
                living.setRemoveWhenFarAway(false);
                living.setCanPickupItems(false);
            }
            if (ent instanceof Mob mob) {
                mob.setAware(false);
            }
            applyVariant(ent, v);
            if (ent instanceof LivingEntity living) {
                setSize(living, v, sizeBlocks);
            }
            if (extra != null) {
                extra.accept(ent);
            }
        });
        return e instanceof LivingEntity living ? living : null;
    }

    private static <T extends Entity> T spawnAs(World world, Location at, Class<T> type, Consumer<Entity> setup) {
        return world.spawn(at, type, setup::accept);
    }

    private static void applyVariant(Entity e, SkinCatalog.Variant v) {
        String id = v.id;
        if (e instanceof Bee bee) {
            bee.setHasNectar(id.contains("nectar"));
            if (id.contains("angry")) {
                bee.setAnger(Integer.MAX_VALUE / 2);
            }
        }
        if (e instanceof Ageable ageable) {
            if (id.equals("baby") || id.equals("ghastling")) {
                ageable.setBaby();
                if (ageable instanceof Breedable breedable) {
                    breedable.setAgeLock(true); // не вырастет
                }
            } else {
                ageable.setAdult();
            }
        }
        if (e instanceof Parrot parrot) {
            parrot.setVariant(switch (id) {
                case "blue" -> Parrot.Variant.BLUE;
                case "green" -> Parrot.Variant.GREEN;
                case "yellow_blue" -> Parrot.Variant.CYAN;
                case "grey" -> Parrot.Variant.GRAY;
                default -> Parrot.Variant.RED;
            });
        }
        if (e instanceof Ghast ghast) {
            ghast.setCharging(id.equals("shooting"));
        }
        if (e instanceof Vex vex) {
            vex.setCharging(id.equals("charging"));
        }
        if (e instanceof Bat bat) {
            bat.setAwake(true);
        }
        if (e instanceof Phantom phantom) {
            phantom.setShouldBurnInDay(false);
        }
        if (v.mob.id.equals("happy_ghast") && e instanceof LivingEntity living) {
            Material harness = Material.matchMaterial(id.toUpperCase(Locale.ROOT) + "_HARNESS");
            EntityEquipment eq = living.getEquipment();
            if (harness != null && eq != null) {
                eq.setItem(EquipmentSlot.BODY, new ItemStack(harness));
            }
        }
    }

    static void setSize(LivingEntity e, SkinCatalog.Variant v, double sizeBlocks) {
        AttributeInstance scale = e.getAttribute(Attribute.SCALE);
        if (scale == null) {
            return;
        }
        double base = vanillaSize(v);
        if (v.id.equals("baby") || v.id.equals("ghastling")) {
            base *= 0.5; // детёныша игра и так уменьшает
        }
        scale.setBaseValue(Math.max(0.0625, Math.min(16.0, sizeBlocks / base)));
    }

    /**
     * Поставить моба центром в точку и повернуть.
     * @param yaw   рыскание Minecraft, градусы
     * @param pitch тангаж Minecraft (+ — нос вниз), градусы
     */
    static void pose(LivingEntity e, World world, double x, double y, double z, float yaw, float pitch, boolean upsideDown) {
        double half = e.getHeight() / 2.0;
        Location at = new Location(world, x, y - half, z, yaw, upsideDown ? -pitch : pitch);
        e.teleport(at);
        e.setBodyYaw(yaw);
        boolean flipped = e.customName() != null;
        if (flipped != upsideDown) {
            e.customName(upsideDown ? Component.text("Dinnerbone") : null);
            e.setCustomNameVisible(false);
        }
    }

    /** Рыскание/тангаж/«вверх ногами» из кватерниона дрона (+Z вперёд, +Y вверх). */
    static float[] angles(DroneState s) {
        Mat4 r = Mat4.rotation(s.qx, s.qy, s.qz, s.qw);
        double[] f = r.transformDirection(0, 0, 1);
        double[] u = r.transformDirection(0, 1, 0);
        double horizontal = Math.sqrt(f[0] * f[0] + f[2] * f[2]);
        double yawRad;
        if (horizontal < 1.0E-3) {
            // смотрит строго вверх/вниз — направление берём по «верху»
            // нос вниз — «верх» дрона смотрит по ходу, нос вверх — против хода
            double k = f[1] < 0 ? 1.0 : -1.0;
            yawRad = Math.atan2(-u[0] * k, u[2] * k);
        } else {
            yawRad = Math.atan2(-f[0], f[2]);
        }
        float yaw = (float) Math.toDegrees(yawRad);
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1.0, Math.min(1.0, f[1]))));
        float up = u[1] < 0 ? 1.0F : 0.0F;
        return new float[] {yaw, pitch, up};
    }
}
