package ru.fpvdrone.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import ru.fpvdrone.FpvDroneMod;

/**
 * Поиск блоков и типов сущностей по id через реестр игры («minecraft:red_concrete»),
 * без привязки к именам констант: в Minecraft 26.x часть констант переименована.
 */
public final class McLookup {
    private static final Map<String, Block> BLOCK_CACHE = new HashMap<>();
    private static EntityType<?> blockDisplayType;
    private static boolean warned;

    private McLookup() {
    }

    /** Блок по id без namespace, например "red_concrete". Если не найден — стекло. */
    public static Block block(String path) {
        return BLOCK_CACHE.computeIfAbsent(path, p -> {
            Object value = registryLookup("BLOCK", Identifier.fromNamespaceAndPath("minecraft", p));
            if (value instanceof Block b) {
                return b;
            }
            warn("блок minecraft:" + p);
            return Blocks.GLASS;
        });
    }

    /** Тип сущности minecraft:block_display. */
    public static EntityType<?> blockDisplay() {
        if (blockDisplayType == null) {
            Object value = registryLookup("ENTITY_TYPE", Identifier.fromNamespaceAndPath("minecraft", "block_display"));
            if (value instanceof EntityType<?> type) {
                blockDisplayType = type;
            } else {
                // Запасной путь: статическое поле с именем BLOCK_DISPLAY в любом классе-держателе типов.
                blockDisplayType = staticField(EntityType.class, "BLOCK_DISPLAY");
                if (blockDisplayType == null) {
                    blockDisplayType = staticFieldIn("net.minecraft.world.entity.EntityTypes", "BLOCK_DISPLAY");
                }
            }
            if (blockDisplayType == null) {
                throw new IllegalStateException("[fpvdrone] Не найден тип сущности minecraft:block_display");
            }
        }
        return blockDisplayType;
    }

    /** Теги сущности (Entity.getTags / entityTags в разных версиях). */
    @SuppressWarnings("unchecked")
    public static boolean hasTag(Entity entity, String tag) {
        for (String name : new String[] {"getTags", "entityTags", "tags"}) {
            try {
                Method m = Entity.class.getMethod(name);
                Object result = m.invoke(entity);
                if (result instanceof Collection<?> c) {
                    return ((Collection<Object>) c).contains(tag);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // пробуем следующее имя
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ реестр через рефлексию

    private static Object registryLookup(String registryField, Identifier id) {
        try {
            Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Object registry = registries.getField(registryField).get(null);
            for (String name : new String[] {"getValue", "get", "getOptional"}) {
                for (Method m : registry.getClass().getMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != 1
                        || !m.getParameterTypes()[0].isAssignableFrom(Identifier.class)) {
                        continue;
                    }
                    Object result = unwrap(m.invoke(registry, id));
                    if (result != null) {
                        return result;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn("реестр " + registryField + ": " + e);
        }
        return null;
    }

    private static Object unwrap(Object o) throws ReflectiveOperationException {
        if (o instanceof Optional<?> opt) {
            o = opt.orElse(null);
        }
        if (o == null) {
            return null;
        }
        // Holder / Reference -> value()
        if (!(o instanceof Block) && !(o instanceof EntityType<?>)) {
            try {
                Method value = o.getClass().getMethod("value");
                return value.invoke(o);
            } catch (NoSuchMethodException ignored) {
                return o;
            }
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    private static <T> T staticField(Class<?> owner, String name) {
        try {
            Field f = owner.getField(name);
            if (Modifier.isStatic(f.getModifiers())) {
                return (T) f.get(null);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // нет поля
        }
        return null;
    }

    private static <T> T staticFieldIn(String className, String name) {
        try {
            return staticField(Class.forName(className), name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static void warn(String what) {
        if (!warned) {
            warned = true;
            FpvDroneMod.LOGGER.warn("[fpvdrone] Не удалось найти {}", what);
        }
    }
}
