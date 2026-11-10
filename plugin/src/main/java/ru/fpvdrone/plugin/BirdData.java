package ru.fpvdrone.plugin;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Птицы и выдуманные существа: модели из «голов» (player_head со скином) и блоков, не больше 13 частей.
 * Данные выгружены из просмотрщика «Птичка в клетке» (вариант дизайна Б, серверный режим) в birds/birds.json.
 */
final class BirdData {
    static final class Mesh {
        String kind;      // head, block, glass, lamp
        float[] size;     // в пикселях модели
        String skin;      // id скина головы (birds/skins/<id>.png)
        String fallback;  // блок, если скин головы ещё не загружен
        String block;     // блок для kind = block/glass/lamp
    }

    static final class Node {
        String role;
        float[] p;
        float[] r;
        String ro;
        float[] s;
        Mesh mesh;
        List<Node> c;
    }

    static final class Variant {
        String name;
        double k;          // пикселей модели → блоков при «размере как в жизни»
        double standY;
        double foldK;
        boolean foldReal;
        double tiltRad;
        double stand;
        double hipY;
        double hipZ;
        double up;
        double low;
        int parts;
        Node rig;
    }

    static final class Tail {
        boolean fan;
        boolean swim;
        double spread;
        double tilt;
    }

    static final class Seg {
        String axis;
        double amp;
    }

    static final class Rest {
        double fl;
        double s1;
        double ro;
        double s2;
        double b2;
    }

    static final class Anim {
        double hz;
        String style;
        double dihedral;
        double diveTight;
        double ampK = 1.0;
        boolean noFold;
        Tail tail;
        Seg seg;
        boolean crestFlat;
        double neckFly;
        boolean inflate;
        Rest rest;
        Rest rest2;
    }

    static final class Bird {
        String id;
        String name;
        double len;
        double span;
        String group;
        Anim anim;
        List<Variant> variants = new ArrayList<>();

        boolean fantasy() {
            return "fantasy".equals(this.group);
        }
    }

    static final class Root {
        List<Bird> birds = new ArrayList<>();
        int skinCount;
    }

    private BirdData() {
    }

    static Root load(ClassLoader loader) throws Exception {
        try (InputStream in = loader.getResourceAsStream("birds/birds.json")) {
            if (in == null) {
                throw new IllegalStateException("В jar нет birds/birds.json");
            }
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Root.class);
        }
    }

    /** Все id скинов голов, которые встречаются в моделях. */
    static List<String> allSkins(Root root) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        // Сначала первая окраска каждой птицы — так все птицы быстро получают головы, потом остальные окраски.
        int maxVariants = 0;
        for (Bird b : root.birds) {
            maxVariants = Math.max(maxVariants, b.variants.size());
        }
        for (int i = 0; i < maxVariants; i++) {
            for (Bird b : root.birds) {
                if (i < b.variants.size()) {
                    collect(b.variants.get(i).rig, set);
                }
            }
        }
        return new ArrayList<>(set);
    }

    /** Скин для значка в панели: голова птицы (первая «головная» часть внутри узла head). */
    static String iconSkin(Bird b) {
        if (b.variants.isEmpty()) {
            return null;
        }
        Node head = find(b.variants.get(0).rig, "head");
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        collect(head != null ? head : b.variants.get(0).rig, set);
        if (set.isEmpty()) {
            collect(b.variants.get(0).rig, set);
        }
        return set.isEmpty() ? null : set.iterator().next();
    }

    private static Node find(Node n, String role) {
        if (role.equals(n.role)) {
            return n;
        }
        if (n.c != null) {
            for (Node c : n.c) {
                Node r = find(c, role);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    static void collect(Node n, java.util.Set<String> out) {
        if (n.mesh != null && "head".equals(n.mesh.kind) && n.mesh.skin != null) {
            out.add(n.mesh.skin);
        }
        if (n.c != null) {
            for (Node ch : n.c) {
                collect(ch, out);
            }
        }
    }
}
