package ru.fpvdrone.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Модель моба в координатах ванильной модели сущности (пиксели, Y вниз, морда смотрит в -Z),
 * которая превращается в JSON-модель предмета для ресурспака.
 *
 * Описание повторяет ванильные ModelPart: кость (точка вращения + поворот вокруг одной оси)
 * и коробки с текстурным смещением texOffs(u, v). Развёртка текстуры такая же, как у сущностей,
 * поэтому подходят ванильные текстуры мобов без изменений.
 */
final class SkinModel {
    /** Коробка уже в «мировых» координатах сущности. */
    static final class Element {
        double x1, y1, z1, x2, y2, z2;   // координаты сущности (Y вниз)
        double ox, oy, oz;               // точка вращения
        char axis;                       // 'x', 'y', 'z' или 0
        double angle;                    // градусы, в координатах сущности
        int tex;                         // номер текстуры (0 — моб, 1 — наложение: сбруя)
        int u, v;
        double w, h, d;
        boolean mirror;
    }

    final List<Element> elements = new ArrayList<>();

    Bone root() {
        return new Bone(0, 0, 0, (char) 0, 0);
    }

    final class Bone {
        final double px, py, pz;
        final char axis;
        final double angle;

        Bone(double px, double py, double pz, char axis, double angle) {
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.axis = angle == 0 ? 0 : axis;
            this.angle = angle;
        }

        Bone child(double ox, double oy, double oz) {
            return this.child(ox, oy, oz, (char) 0, 0);
        }

        /** Дочерняя кость: смещение в системе родителя и собственный поворот. */
        Bone child(double ox, double oy, double oz, char axis, double angle) {
            double[] p = rotate(this.axis, this.angle, ox, oy, oz);
            char a;
            double total;
            if (angle == 0) {
                a = this.axis;
                total = this.angle;
            } else if (this.axis == 0 || this.axis == axis) {
                a = axis;
                total = this.angle + angle;
            } else {
                // В JSON-модели у элемента может быть поворот только вокруг одной оси — берём свой.
                a = axis;
                total = angle;
            }
            return new Bone(this.px + p[0], this.py + p[1], this.pz + p[2], a, total);
        }

        Bone box(int u, int v, double x, double y, double z, double w, double h, double d) {
            return this.add(0, u, v, x, y, z, w, h, d, false, 0);
        }

        Bone mbox(int u, int v, double x, double y, double z, double w, double h, double d) {
            return this.add(0, u, v, x, y, z, w, h, d, true, 0);
        }

        Bone add(int tex, int u, int v, double x, double y, double z, double w, double h, double d, boolean mirror, double inflate) {
            Element e = new Element();
            e.x1 = this.px + x - inflate;
            e.y1 = this.py + y - inflate;
            e.z1 = this.pz + z - inflate;
            e.x2 = this.px + x + w + inflate;
            e.y2 = this.py + y + h + inflate;
            e.z2 = this.pz + z + d + inflate;
            e.ox = this.px;
            e.oy = this.py;
            e.oz = this.pz;
            e.axis = this.axis;
            e.angle = this.angle;
            e.tex = tex;
            e.u = u;
            e.v = v;
            e.w = w;
            e.h = h;
            e.d = d;
            e.mirror = mirror;
            SkinModel.this.elements.add(e);
            return this;
        }
    }

    /** Поворот по правилу правой руки (как ModelPart). */
    static double[] rotate(char axis, double deg, double x, double y, double z) {
        if (axis == 0 || deg == 0) {
            return new double[] {x, y, z};
        }
        double r = Math.toRadians(deg);
        double c = Math.cos(r);
        double s = Math.sin(r);
        return switch (axis) {
            case 'x' -> new double[] {x, y * c - z * s, y * s + z * c};
            case 'y' -> new double[] {x * c + z * s, y, -x * s + z * c};
            default -> new double[] {x * c - y * s, x * s + y * c, z};
        };
    }

    // ------------------------------------------------------------------ JSON

    /** Габариты набора кадров в координатах JSON (X и Y перевёрнуты). [minX,minY,minZ,maxX,maxY,maxZ] */
    static double[] bounds(List<SkinModel> frames) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (SkinModel m : frames) {
            for (Element e : m.elements) {
                double[] from = {-e.x2, -e.y2, e.z1};
                double[] to = {-e.x1, -e.y1, e.z2};
                for (int i = 0; i < 3; i++) {
                    b[i] = Math.min(b[i], from[i]);
                    b[i + 3] = Math.max(b[i + 3], to[i]);
                }
            }
        }
        return b;
    }

    /** Наибольший допустимый размер модели в JSON (координаты должны быть в -16..32). */
    static final double MAX_UNITS = 46.0;

    /**
     * @param center центр габаритов (общий для всех кадров, чтобы модель не «прыгала»)
     * @param k      масштаб, чтобы влезть в ограничения JSON
     */
    String toJson(String[] textures, int[][] texSizes, double[] center, double k) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"textures\":{");
        for (int i = 0; i < textures.length; i++) {
            sb.append('"').append('t').append(i).append("\":\"").append(textures[i]).append("\",");
        }
        sb.append("\"particle\":\"").append(textures[0]).append("\"},\"elements\":[");
        boolean first = true;
        for (Element e : this.elements) {
            int t = Math.min(e.tex, textures.length - 1);
            int tw = texSizes[t][0];
            int th = texSizes[t][1];
            String faces = faces(e, t, tw, th);
            if (faces.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            double[] from = {-e.x2, -e.y2, e.z1};
            double[] to = {-e.x1, -e.y1, e.z2};
            double[] origin = {-e.ox, -e.oy, e.oz};
            sb.append("{\"from\":").append(vec(from, center, k)).append(",\"to\":").append(vec(to, center, k));
            double angle = quantize(e.axis == 'z' ? e.angle : -e.angle);
            if (e.axis != 0 && angle != 0) {
                sb.append(",\"rotation\":{\"angle\":").append(num(angle)).append(",\"axis\":\"").append(e.axis)
                    .append("\",\"origin\":").append(vec(origin, center, k)).append('}');
            }
            sb.append(",\"faces\":{").append(faces).append("}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Ванильная развёртка коробки → грани JSON. Север (-Z) — морда моба, восток (+X) — его правый бок. */
    private static String faces(Element e, int tex, int tw, int th) {
        double u = e.u;
        double v = e.v;
        double w = e.w;
        double h = e.h;
        double d = e.d;
        double[] north = {u + d, v + d, u + d + w, v + d + h};
        double[] south = {u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h};
        double[] east = {u, v + d, u + d, v + d + h};
        double[] west = {u + d + w, v + d, u + 2 * d + w, v + d + h};
        double[] up = {u + d + w, v + d, u + d, v};
        double[] down = {u + d + 2 * w, v, u + d + w, v + d};
        if (e.mirror) {
            double[] tmp = east;
            east = west;
            west = tmp;
            for (double[] f : new double[][] {north, south, east, west, up, down}) {
                double a = f[0];
                f[0] = f[2];
                f[2] = a;
            }
        }
        StringBuilder sb = new StringBuilder();
        face(sb, "north", north, w * h, tex, tw, th);
        face(sb, "south", south, w * h, tex, tw, th);
        face(sb, "east", east, d * h, tex, tw, th);
        face(sb, "west", west, d * h, tex, tw, th);
        face(sb, "up", up, w * d, tex, tw, th);
        face(sb, "down", down, w * d, tex, tw, th);
        return sb.toString();
    }

    private static void face(StringBuilder sb, String name, double[] uv, double area, int tex, int tw, int th) {
        if (area <= 0.0) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(',');
        }
        double su = 16.0 / tw;
        double sv = 16.0 / th;
        sb.append('"').append(name).append("\":{\"uv\":[")
            .append(num(clamp16(uv[0] * su))).append(',').append(num(clamp16(uv[1] * sv))).append(',')
            .append(num(clamp16(uv[2] * su))).append(',').append(num(clamp16(uv[3] * sv)))
            .append("],\"texture\":\"#t").append(tex).append("\"}");
    }

    private static double clamp16(double value) {
        return Math.max(0.0, Math.min(16.0, value));
    }

    /** JSON-модели допускают только -45, -22.5, 0, 22.5, 45. */
    static double quantize(double angle) {
        double q = Math.round(angle / 22.5) * 22.5;
        return Math.max(-45.0, Math.min(45.0, q));
    }

    private static String vec(double[] p, double[] center, double k) {
        return "[" + num((p[0] - center[0]) * k + 8.0) + "," + num((p[1] - center[1]) * k + 8.0) + ","
            + num((p[2] - center[2]) * k + 8.0) + "]";
    }

    static String num(double value) {
        double r = Math.round(value * 10000.0) / 10000.0;
        if (r == Math.rint(r)) {
            return Long.toString((long) r);
        }
        return String.format(Locale.ROOT, "%.4f", r).replaceAll("0+$", "");
    }
}
