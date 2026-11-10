package ru.fpvdrone.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Все летающие мобы (кроме визера и дракона), их вариации и модели.
 * Геометрия повторяет ванильные модели сущностей; анимация крыльев — несколькими кадрами-моделями.
 */
final class SkinCatalog {
    /** Описание одной модели (одного кадра). */
    interface Geometry {
        /** @param s фаза взмаха от -1 до 1 (для зацикленных — от 0 до 1) */
        void build(SkinModel m, Variant variant, double s);
    }

    static final class Variant {
        final String id;
        final String name;
        final String[] textures;   // id текстур без "textures/" и ".png"
        final int[][] texSizes;    // размер развёртки (как в модели сущности, не в пикселях картинки)
        final float scaleMul;
        Mob mob;
        /** Размер модели после сборки (единицы JSON-модели, 16 = 1 блок). */
        double modelUnits = 16.0;
        /** Птица: номер варианта окраски в BirdData.Bird.variants. */
        int birdVariant = -1;

        Variant(String id, String name, float scaleMul, String[] textures, int[][] texSizes) {
            this.id = id;
            this.name = name;
            this.scaleMul = scaleMul;
            this.textures = textures;
            this.texSizes = texSizes;
        }

        String itemKey(int frame) {
            return this.mob.id + "_" + this.id + "_" + frame;
        }

        String fullName() {
            return this.mob.variants.size() > 1 ? this.mob.name + " (" + this.name.toLowerCase(Locale.ROOT) + ")" : this.mob.name;
        }
    }

    static final class Mob {
        final String id;
        final String name;
        final int frames;
        final boolean loop;          // кадры по кругу (ифрит) или туда-обратно (крылья)
        final float flapSpeed;       // кадров за тик при полном газе
        final boolean alwaysAnimate; // анимация и без газа
        float size;                  // размер в полёте, блоков (наибольшая сторона)
        final float pitchDeg;        // наклон «носом вперёд» в полёте
        float yawDeg;                // доворот модели (если в редакторе морда смотрела не туда)
        boolean custom;              // свой скин из папки skins/
        BirdData.Bird bird;          // птица из голов и блоков (не моб и не модель из пака)
        final Geometry geometry;
        final List<Variant> variants = new ArrayList<>();

        Mob(String id, String name, int frames, boolean loop, float flapSpeed, boolean alwaysAnimate, float size,
            float pitchDeg, Geometry geometry) {
            this.id = id;
            this.name = name;
            this.frames = frames;
            this.loop = loop;
            this.flapSpeed = flapSpeed;
            this.alwaysAnimate = alwaysAnimate;
            this.size = size;
            this.pitchDeg = pitchDeg;
            this.geometry = geometry;
        }

        Mob variant(Variant v) {
            v.mob = this;
            this.variants.add(v);
            return this;
        }

        Variant variant(String id) {
            for (Variant v : this.variants) {
                if (v.id.equalsIgnoreCase(id)) {
                    return v;
                }
            }
            return null;
        }

        /** Кадр анимации для фазы (фаза растёт на flapSpeed за тик). */
        int frameAt(double phase) {
            if (this.frames <= 1) {
                return 0;
            }
            int p = (int) Math.floor(phase);
            if (this.loop) {
                return Math.floorMod(p, this.frames);
            }
            int period = 2 * this.frames - 2;
            int i = Math.floorMod(p, period);
            return i < this.frames ? i : period - i;
        }

        /** Кадр «крылья в среднем положении». */
        int restFrame() {
            return this.loop ? 0 : this.frames / 2;
        }

        double flapValue(int frame) {
            if (this.frames <= 1) {
                return 0.0;
            }
            if (this.loop) {
                return frame / (double) this.frames;
            }
            return frame / (double) (this.frames - 1) * 2.0 - 1.0;
        }
    }

    final List<Mob> mobs = new ArrayList<>();
    private final Map<String, Mob> byId = new LinkedHashMap<>();

    SkinCatalog(Map<String, String> textureOverrides, Map<String, Double> sizes) {
        this(textureOverrides, sizes, List.of());
    }

    SkinCatalog(Map<String, String> textureOverrides, Map<String, Double> sizes, List<Mob> customMobs) {
        this.defineAll();
        for (Mob mob : customMobs) {
            String id = mob.id;
            for (Mob existing : this.mobs) {
                if (existing.id.equals(id)) {
                    id = null;
                    break;
                }
            }
            if (id != null) {
                this.mobs.add(mob);
            }
        }
        for (Mob mob : this.mobs) {
            this.byId.put(mob.id, mob);
            Double size = sizes.get(mob.id);
            if (size != null && size > 0.05) {
                mob.size = (float) Math.min(8.0, size);
            }
            for (Variant v : mob.variants) {
                for (int i = 0; i < v.textures.length; i++) {
                    String override = textureOverrides.get(v.textures[i]);
                    if (override != null && !override.isBlank()) {
                        v.textures[i] = override.trim();
                    }
                }
            }
        }
        this.computeUnits();
    }

    /** Размер модели каждого варианта (раньше считал ресурспак): нужен, чтобы задать мобам размер в блоках. */
    private void computeUnits() {
        for (Mob mob : this.mobs) {
            if (mob.geometry == null) {
                continue;
            }
            for (Variant v : mob.variants) {
                try {
                    double[] b = SkinModel.bounds(this.frames(v));
                    double maxDim = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
                    double k = maxDim > SkinModel.MAX_UNITS ? SkinModel.MAX_UNITS / maxDim : 1.0;
                    v.modelUnits = Math.max(1.0, maxDim * k);
                } catch (RuntimeException ignored) {
                    v.modelUnits = 16.0;
                }
            }
        }
    }

    Mob mob(String id) {
        return id == null ? null : this.byId.get(id.toLowerCase(Locale.ROOT));
    }

    List<Mob> mobs() {
        return Collections.unmodifiableList(this.mobs);
    }

    /** Строит кадры модели варианта. */
    List<SkinModel> frames(Variant v) {
        List<SkinModel> list = new ArrayList<>();
        for (int f = 0; f < v.mob.frames; f++) {
            SkinModel m = new SkinModel();
            v.mob.geometry.build(m, v, v.mob.flapValue(f));
            list.add(m);
        }
        return list;
    }

    // ================================================================== мобы

    private static int[][] tex(int w, int h) {
        return new int[][] {{w, h}};
    }

    private static Variant var(String id, String name, String texture, int w, int h) {
        return new Variant(id, name, 1.0F, new String[] {"minecraft:" + texture}, tex(w, h));
    }

    private void defineAll() {
        // --- Тихоня (allay), развёртка 32x32
        this.add(new Mob("allay", "Тихоня", 3, false, 0.8F, false, 0.7F, 15.0F, (m, v, s) -> {
            SkinModel.Bone root = m.root();
            root.child(0, -3.99, 0).box(0, 0, -2.5, -5, -2.5, 5, 5, 5);
            SkinModel.Bone body = root.child(0, -4, 0);
            body.box(0, 10, -1.5, 0, -1, 3, 4, 2);
            body.add(0, 0, 16, -1.5, 0, -1, 3, 5, 2, false, -0.2);
            body.child(-1.75, 0.5, 0).add(0, 23, 0, -0.75, -0.5, -1, 1, 4, 2, false, -0.01);
            body.child(1.75, 0.5, 0).add(0, 23, 6, -0.25, -0.5, -1, 1, 4, 2, false, -0.01);
            double spread = 22.5 + 22.5 * s;
            body.child(-0.5, 0, 0.6, 'y', -spread).box(16, 14, 0, 1, 0, 0, 5, 8);
            body.child(0.5, 0, 0.6, 'y', spread).box(16, 14, 0, 1, 0, 0, 5, 8);
        }).variant(var("normal", "Обычная", "entity/allay/allay", 32, 32)));

        // --- Летучая мышь, развёртка 32x32 (с 26.1 текстура в entity/bat/)
        this.add(new Mob("bat", "Летучая мышь", 3, false, 1.2F, false, 0.6F, 45.0F, (m, v, s) -> {
            SkinModel.Bone root = m.root();
            SkinModel.Bone body = root.child(0, 17, 0);
            body.box(0, 7, -1.5, 0, -1, 3, 5, 2);
            SkinModel.Bone head = root.child(0, 17, 0);
            head.box(0, 0, -2, -3, -1, 4, 3, 2);
            head.child(-1.5, -2, 0).box(1, 15, -2.5, -4, 0, 3, 5, 0);
            head.child(1.1, -3, 0).box(8, 15, -0.1, -3, 0, 3, 5, 0);
            double a = 22.5 * s;
            SkinModel.Bone rw = body.child(-1.5, 0, 0, 'y', a);
            rw.box(12, 0, -2, -2, 0, 2, 7, 0);
            rw.child(-2, 0, 0, 'y', a).box(16, 0, -6, -2, 0, 6, 8, 0);
            SkinModel.Bone lw = body.child(1.5, 0, 0, 'y', -a);
            lw.box(12, 7, 0, -2, 0, 2, 7, 0);
            lw.child(2, 0, 0, 'y', -a).box(16, 8, 0, -2, 0, 6, 8, 0);
            body.child(0, 5, 0).box(16, 16, -1.5, 0, 0, 3, 2, 0);
        }).variant(var("normal", "Обычная", "entity/bat/bat", 32, 32)));

        // --- Пчела, развёртка 64x64
        Mob bee = new Mob("bee", "Пчела", 3, false, 1.6F, false, 0.6F, 0.0F, (m, v, s) -> {
            SkinModel.Bone root = m.root();
            root.box(0, 0, -3.5, -4, -5, 7, 7, 10);
            root.box(26, 7, 0, -1, 5, 0, 1, 2);
            SkinModel.Bone ant = root.child(0, -2, -5);
            ant.box(2, 0, 1.5, -2, -3, 1, 2, 3);
            ant.box(2, 3, -2.5, -2, -3, 1, 2, 3);
            double a = 22.5 * s;
            root.child(-1.5, -4, -3, 'z', a).box(0, 18, -9, 0, 0, 9, 0, 6);
            root.child(1.5, -4, -3, 'z', -a).mbox(0, 18, 0, 0, 0, 9, 0, 6);
            root.child(1.5, 3, -2).box(26, 1, -5, 0, 0, 7, 2, 0);
            root.child(1.5, 3, 0).box(26, 3, -5, 0, 0, 7, 2, 0);
            root.child(1.5, 3, 2).box(26, 5, -5, 0, 0, 7, 2, 0);
        });
        bee.variant(var("normal", "Обычная", "entity/bee/bee", 64, 64));
        bee.variant(var("angry", "Злая", "entity/bee/bee_angry", 64, 64));
        bee.variant(var("nectar", "С пыльцой", "entity/bee/bee_nectar", 64, 64));
        bee.variant(var("angry_nectar", "Злая с пыльцой", "entity/bee/bee_angry_nectar", 64, 64));
        bee.variant(new Variant("baby", "Пчелёнок", 0.55F, new String[] {"minecraft:entity/bee/bee"}, tex(64, 64)));
        this.add(bee);

        // --- Ифрит: уменьшен, стержни вращаются (4 кадра по кругу), развёртка 64x32
        this.add(new Mob("blaze", "Ифрит", 4, true, 0.25F, true, 0.8F, 0.0F, (m, v, s) -> {
            SkinModel.Bone root = m.root();
            root.box(0, 0, -4, -4, -4, 8, 8, 8);
            double phi = s * Math.PI / 2.0;
            double[] radius = {9, 7, 5};
            double[] height = {-2, 2, 11};
            double[] start = {0.0, Math.PI / 4.0, 0.4712};
            double[] dir = {-1, 1, -1};
            for (int ring = 0; ring < 3; ring++) {
                for (int i = 0; i < 4; i++) {
                    double angle = start[ring] + dir[ring] * phi + i * Math.PI / 2.0;
                    double x = Math.cos(angle) * radius[ring];
                    double z = Math.sin(angle) * radius[ring];
                    root.child(x, height[ring], z).box(0, 16, -1, 0, -1, 2, 8, 2);
                }
            }
        }).variant(var("normal", "Обычный", "entity/blaze/blaze", 64, 32)));

        // --- Гаст: огромный, поэтому в полёте сильно уменьшен. Развёртка 64x32
        int[] ghastTentacles = {9, 11, 8, 13, 10, 7, 12, 9, 10};
        Mob ghast = new Mob("ghast", "Гаст", 1, false, 0F, false, 1.2F, 0.0F, (m, v, s) -> ghastBody(m, ghastTentacles, false));
        ghast.variant(var("normal", "Обычный", "entity/ghast/ghast", 64, 32));
        ghast.variant(var("shooting", "Стреляет", "entity/ghast/ghast_shooting", 64, 32));
        this.add(ghast);

        // --- Счастливый гаст: без сбруи, 16 цветов сбруи, гастёнок. Развёртка 64x64
        int[] happyTentacles = {5, 6, 4, 7, 5, 4, 6, 5, 5};
        Mob happy = new Mob("happy_ghast", "Счастливый гаст", 1, false, 0F, false, 1.2F, 0.0F,
            (m, v, s) -> ghastBody(m, happyTentacles, v.textures.length > 1));
        happy.variant(var("plain", "Без сбруи", "entity/ghast/happy_ghast", 64, 64));
        String[][] harness = {
            {"white", "Белая сбруя"}, {"light_gray", "Светло-серая сбруя"}, {"gray", "Серая сбруя"}, {"black", "Чёрная сбруя"},
            {"brown", "Коричневая сбруя"}, {"red", "Красная сбруя"}, {"orange", "Оранжевая сбруя"}, {"yellow", "Жёлтая сбруя"},
            {"lime", "Лаймовая сбруя"}, {"green", "Зелёная сбруя"}, {"cyan", "Бирюзовая сбруя"}, {"light_blue", "Голубая сбруя"},
            {"blue", "Синяя сбруя"}, {"purple", "Фиолетовая сбруя"}, {"magenta", "Сиреневая сбруя"}, {"pink", "Розовая сбруя"}
        };
        for (String[] h : harness) {
            happy.variant(new Variant(h[0], h[1], 1.0F,
                new String[] {"minecraft:entity/ghast/happy_ghast", "minecraft:entity/equipment/happy_ghast_body/" + h[0] + "_harness"},
                new int[][] {{64, 64}, {64, 64}}));
        }
        happy.variant(new Variant("ghastling", "Гастёнок", 0.45F, new String[] {"minecraft:entity/ghast/happy_ghast_baby"}, tex(64, 64)));
        this.add(happy);

        // --- Попугай: 5 расцветок, развёртка 32x32
        Mob parrot = new Mob("parrot", "Попугай", 3, false, 1.2F, false, 0.6F, 20.0F, (m, v, s) -> {
            SkinModel.Bone root = m.root();
            root.child(0, 16.5, -3, 'x', 22.5).box(2, 8, -1.5, 0, -1.5, 3, 6, 3);
            root.child(0, 21.07, 1.16, 'x', 45).box(22, 1, -1.5, -1, -1, 3, 4, 1);
            double a = 22.5 + 22.5 * s;
            root.child(1.5, 16.94, -2.76, 'z', -a).box(19, 8, -0.5, 0, -1.5, 1, 5, 3);
            root.child(-1.5, 16.94, -2.76, 'z', a).box(19, 8, -0.5, 0, -1.5, 1, 5, 3);
            SkinModel.Bone head = root.child(0, 15.69, -2.76);
            head.box(2, 2, -1, -1.5, -1, 2, 3, 2);
            head.child(0, -2, -1).box(10, 0, -1, -0.5, -2, 2, 1, 4);
            head.child(0, -0.5, -1.5).box(11, 7, -0.5, -1, -0.5, 1, 2, 1);
            head.child(0, -1.75, -2.45).box(16, 7, -0.5, 0, -0.5, 1, 2, 1);
            head.child(0, -2.15, 0.15, 'x', -22.5).box(2, 18, 0, -4, -2, 0, 5, 4);
            root.child(1, 22, -1.05).box(14, 18, -0.5, 0, -0.5, 1, 2, 1);
            root.child(-1, 22, -1.05).box(14, 18, -0.5, 0, -0.5, 1, 2, 1);
        });
        parrot.variant(var("red_blue", "Красный", "entity/parrot/parrot_red_blue", 32, 32));
        parrot.variant(var("blue", "Синий", "entity/parrot/parrot_blue", 32, 32));
        parrot.variant(var("green", "Зелёный", "entity/parrot/parrot_green", 32, 32));
        parrot.variant(var("yellow_blue", "Голубой", "entity/parrot/parrot_yellow_blue", 32, 32));
        parrot.variant(var("grey", "Серый", "entity/parrot/parrot_grey", 32, 32));
        this.add(parrot);

        // --- Фантом, развёртка 64x64 (с 26.1 текстура в entity/phantom/)
        this.add(new Mob("phantom", "Фантом", 3, false, 0.5F, false, 1.2F, 0.0F, (m, v, s) -> {
            SkinModel.Bone body = m.root();
            body.box(0, 8, -3, -2, -8, 5, 3, 9);
            SkinModel.Bone tail = body.child(0, -2, 1);
            tail.box(3, 20, -2, 0, 0, 3, 2, 6);
            tail.child(0, 0.5, 6).box(4, 29, -1, 0, 0, 1, 1, 6);
            double a = 22.5 * s;
            SkinModel.Bone lw = body.child(2, -2, -8, 'z', a);
            lw.box(23, 12, 0, 0, 0, 6, 2, 9);
            lw.child(6, 0, 0, 'z', a).box(16, 24, 0, 0, 0, 13, 1, 9);
            SkinModel.Bone rw = body.child(-3, -2, -8, 'z', -a);
            rw.mbox(23, 12, -6, 0, 0, 6, 2, 9);
            rw.child(-6, 0, 0, 'z', -a).mbox(16, 24, -13, 0, 0, 13, 1, 9);
            body.child(0, 1, -7).box(0, 0, -4, -2, -5, 7, 3, 5);
        }).variant(var("normal", "Обычный", "entity/phantom/phantom", 64, 64)));

        // --- Вредина, развёртка 32x32
        Mob vex = new Mob("vex", "Вредина", 3, false, 0.8F, false, 0.7F, 15.0F, (m, v, s) -> {
            SkinModel.Bone root = m.root();
            root.child(0, 20, 0).box(0, 0, -2.5, -5, -2.5, 5, 5, 5);
            SkinModel.Bone body = root.child(0, 20, 0);
            body.box(0, 10, -1.5, 0, -1, 3, 4, 2);
            body.add(0, 0, 16, -1.5, 1, -1, 3, 5, 2, false, -0.2);
            body.child(-1.75, 0.25, 0).add(0, 23, 0, -1.25, -0.5, -1, 2, 4, 2, false, -0.1);
            body.child(1.75, 0.25, 0).add(0, 23, 6, -0.75, -0.5, -1, 2, 4, 2, false, -0.1);
            double spread = 22.5 + 22.5 * s;
            body.child(0.5, 1, 1, 'y', spread).mbox(16, 22, 0, 0, 0, 0, 5, 8);
            body.child(-0.5, 1, 1, 'y', -spread).box(16, 22, 0, 0, 0, 0, 5, 8);
        });
        vex.variant(var("normal", "Обычная", "entity/illager/vex", 32, 32));
        vex.variant(var("charging", "Атакует", "entity/illager/vex_charging", 32, 32));
        this.add(vex);
    }

    private static void ghastBody(SkinModel m, int[] tentacles, boolean harness) {
        SkinModel.Bone root = m.root();
        root.box(0, 0, -8, -8, -8, 16, 16, 16);
        if (harness) {
            root.add(1, 0, 0, -8, -8, -8, 16, 16, 16, false, 0.5);
        }
        for (int i = 0; i < 9; i++) {
            double x = (((i % 3) - (i / 3 % 2) * 0.5 + 0.25) / 2.0 * 2.0 - 1.0) * 5.0;
            double z = ((i / 3) / 2.0 * 2.0 - 1.0) * 5.0;
            root.child(x, 7, z).box(0, 0, -1, 0, -1, 2, tentacles[i], 2);
        }
    }

    private void add(Mob mob) {
        this.mobs.add(mob);
    }
}
