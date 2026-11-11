package ru.fpvdrone.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Птица вместо дрона: до 13 сущностей-витрин (головы игроков со скином + блоки), видно всем без модов и без пака.
 * Курс и крен — как у дрона, нос — по направлению полёта; крылья машут от газа, складываются в пике,
 * хвост раскрывается, голова держит горизонт. Дрон стоит выключенным — птица стоит на земле со сложенными крыльями.
 */
final class BirdVisual implements SkinVisual {
    static int smoothTicks = 3;
    /** Сглаживание движения на сервере, 1/с: меньше — плавнее, но модель чуть отстаёт от дрона. */
    static double smoothRate = 10.0;
    /** Взмахи не чаще, чем сервер успевает показать (20 кадров/с), иначе крылья «дёргаются». */
    static double maxFlapHz = 4.0;
    /** Размер головы игрока (блоков) при масштабе 1 в item_display. */
    static double headSize = 0.5;
    /** Доворот головы-предмета, градусов (если «морда» скина смотрит не туда). */
    static double headYawDeg = 0.0;
    static double headOffsetY = 0.0;
    static ItemDisplay.ItemDisplayTransform headTransform = ItemDisplay.ItemDisplayTransform.NONE;

    private static final double D2R = Math.PI / 180.0;
    private static final double TINY = 1.0E-4;

    private final Plugin plugin;
    private final BirdData.Bird bird;
    private final BirdData.Variant var;
    private final double sizeBlocks;
    private final List<Part> parts = new ArrayList<>();
    /** Невидимая «опора»: все части сидят на ней пассажирами и двигаются вместе, без рассинхрона. */
    private final ItemDisplay rootEntity;
    // сглаженные курс/нос/крен и позиция
    private boolean smoothInit;
    private double sYaw;
    private double sPitch;
    private double sRoll;
    private double sx;
    private double sy;
    private double sz;
    private final boolean bright;
    private final boolean preview;
    private boolean poseInitialized;
    private double previewYaw;
    private double previewPitch;
    private double previewBank;

    // состояние анимации
    private double ph = Math.random() * 6.0;
    private double ph2;
    private double feS;
    private double closed;
    private double bt;
    private double spin;
    private double inf;
    private double perchS = 1.0;
    private double legsS;
    private double hy;
    private double hz;
    private double lookY;
    private double lookT;
    private long lastNanos;
    private double px = Double.NaN;
    private double py;
    private double pz;
    private double vx;
    private double vy;
    private double vz;

    private static final class Part {
        final BirdData.Node node;
        final Display display;
        final boolean head;

        Part(BirdData.Node node, Display display, boolean head) {
            this.node = node;
            this.display = display;
            this.head = head;
        }
    }

    BirdVisual(Plugin plugin, BirdHeads heads, Location at, BirdData.Bird bird, int variant, double sizeBlocks,
               Player hideFrom, Player onlyFor, float viewRange, boolean bright) {
        this.plugin = plugin;
        this.preview = onlyFor != null;
        this.bird = bird;
        this.var = bird.variants.get(Math.max(0, Math.min(bird.variants.size() - 1, variant)));
        this.sizeBlocks = sizeBlocks;
        this.bright = bright;
        Location spawnAt = new Location(at.getWorld(), at.getX(), at.getY(), at.getZ(), 0.0F, 0.0F);
        this.rootEntity = at.getWorld().spawn(spawnAt, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setTeleportDuration(smoothTicks);
            e.setViewRange(viewRange);
            e.setShadowRadius(0.0F);
            if (onlyFor != null) {
                e.setVisibleByDefault(false);
            }
        });
        if (hideFrom != null) {
            hideFrom.hideEntity(plugin, this.rootEntity);
        }
        if (onlyFor != null) {
            onlyFor.showEntity(plugin, this.rootEntity);
        }
        List<BirdData.Node> meshes = new ArrayList<>();
        collectMeshes(this.var.rig, meshes);
        for (BirdData.Node n : meshes) {
            BirdData.Mesh m = n.mesh;
            Display d;
            boolean isHead = false;
            ItemStack headItem = "head".equals(m.kind) ? heads.item(m.skin) : null;
            if (headItem != null) {
                isHead = true;
                d = at.getWorld().spawn(spawnAt, ItemDisplay.class, e -> {
                    e.setItemStack(headItem);
                    e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    this.setup(e, viewRange, false);
                    if (onlyFor != null) {
                        e.setVisibleByDefault(false);
                    }
                });
            } else {
                String id = "head".equals(m.kind) ? m.fallback : m.block;
                Material mat = material(id);
                boolean lamp = "lamp".equals(m.kind);
                d = at.getWorld().spawn(spawnAt, BlockDisplay.class, e -> {
                    e.setBlock(mat.createBlockData());
                    this.setup(e, viewRange, lamp);
                    if (onlyFor != null) {
                        e.setVisibleByDefault(false);
                    }
                });
            }
            if (hideFrom != null) {
                hideFrom.hideEntity(plugin, d);
            }
            if (onlyFor != null) {
                onlyFor.showEntity(plugin, d);
            }
            this.parts.add(new Part(n, d, isHead));
            this.rootEntity.addPassenger(d);
        }
    }

    private void setup(Display e, float viewRange, boolean glow) {
        e.setPersistent(false);
        e.setTeleportDuration(smoothTicks);
        e.setInterpolationDuration(smoothTicks);
        e.setInterpolationDelay(0);
        e.setViewRange(viewRange);
        e.setShadowRadius(0.0F);
        if (glow || this.bright) {
            e.setBrightness(new Display.Brightness(15, 15));
        }
        e.setTransformationMatrix(new Matrix4f().set(Mat4.scale(TINY, TINY, TINY).m));
    }

    /** Только для одного игрока (превью в меню): создать скрытыми. */
    static void hideByDefault(List<Display> list) {
        for (Display d : list) {
            d.setVisibleByDefault(false);
        }
    }

    List<Display> displays() {
        List<Display> out = new ArrayList<>();
        for (Part p : this.parts) {
            out.add(p.display);
        }
        return out;
    }

    private static Material material(String id) {
        if (id != null) {
            Material m = Material.matchMaterial(id);
            if (m != null && m.isBlock()) {
                return m;
            }
        }
        return Material.WHITE_WOOL;
    }

    private static void collectMeshes(BirdData.Node n, List<BirdData.Node> out) {
        if (n.mesh != null) {
            out.add(n);
        }
        if (n.c != null) {
            for (BirdData.Node c : n.c) {
                collectMeshes(c, out);
            }
        }
    }

    // ================================================================== обновление от дрона

    @Override
    public void update(World world, DroneState s) {
        double dt = this.tickDt();
        if (Double.isNaN(this.px)) {
            this.px = s.x;
            this.py = s.y;
            this.pz = s.z;
        }
        if (dt > 0.0) {
            double nvx = (s.x - this.px) / dt;
            double nvy = (s.y - this.py) / dt;
            double nvz = (s.z - this.pz) / dt;
            double v2 = nvx * nvx + nvy * nvy + nvz * nvz;
            if (v2 < 120.0 * 120.0) {
                double a = Math.min(1.0, dt * 8.0);
                this.vx += (nvx - this.vx) * a;
                this.vy += (nvy - this.vy) * a;
                this.vz += (nvz - this.vz) * a;
            }
        }
        this.px = s.x;
        this.py = s.y;
        this.pz = s.z;

        double[] e = eulerYXZ(s.qx, s.qy, s.qz, s.qw);
        double sp = Math.sqrt(this.vx * this.vx + this.vy * this.vy + this.vz * this.vz);
        double gamma = sp > 0.5 ? Math.asin(Math.max(-1.0, Math.min(1.0, this.vy / sp))) : 0.0;
        double w = sm((sp - 3.0) / 6.0);
        double visX = e[0] * (1.0 - w) + (-gamma) * w * 0.9;

        boolean grounded = !s.armed || s.crashed;
        this.perchS += ((grounded ? 1.0 : 0.0) - this.perchS) * Math.min(1.0, dt * 6.0);
        double perch = this.perchS;

        // земля под птицей: при посадке ставим лапы на блок, при подлёте — выпускаем лапы
        double groundDist = 9.0;
        RayTraceResult hit = world.rayTraceBlocks(new Location(world, s.x, s.y + 0.2, s.z), new org.bukkit.util.Vector(0, -1, 0), 4.0,
            org.bukkit.FluidCollisionMode.NEVER, true);
        double groundY = s.y - 0.2;
        if (hit != null) {
            groundY = hit.getHitPosition().getY();
            groundDist = s.y - groundY;
        }
        double scale = this.sizeBlocks / this.bird.len;
        double B = this.sizeBlocks;
        double legsT = grounded ? 0.0 : sm(1.0 - groundDist / (B * 1.5 + 0.3)) * sm(1.0 - sp / 5.0);
        this.legsS += (legsT - this.legsS) * Math.min(1.0, dt * 5.0);

        double pitchDeg = -visX / D2R * (1.0 - perch);
        double bankDeg = e[2] / D2R * (1.0 - perch);
        double thr = s.armed ? s.throttle : 0.0;

        double yRoot = s.y;
        if (perch > 0.01) {
            double standY = groundY + this.var.standY * scale;
            yRoot = s.y + (standY - s.y) * sm(perch);
        }
        // Плавность важнее точности: позицию и углы ведём «пружиной» к дрону.
        double tYaw = e[1];
        double tPitch = visX * (1.0 - perch);
        double tRoll = e[2] * (1.0 - perch);
        if (!this.smoothInit) {
            this.smoothInit = true;
            this.sYaw = tYaw;
            this.sPitch = tPitch;
            this.sRoll = tRoll;
            this.sx = s.x;
            this.sy = yRoot;
            this.sz = s.z;
        } else {
            double k = 1.0 - Math.exp(-dt * smoothRate);
            this.sYaw += wrap(tYaw - this.sYaw) * k;
            this.sPitch += wrap(tPitch - this.sPitch) * k;
            this.sRoll += wrap(tRoll - this.sRoll) * k;
            double jump = (s.x - this.sx) * (s.x - this.sx) + (yRoot - this.sy) * (yRoot - this.sy) + (s.z - this.sz) * (s.z - this.sz);
            if (jump > 64.0) {
                this.sx = s.x;
                this.sy = yRoot;
                this.sz = s.z;
            } else {
                double kp = 1.0 - Math.exp(-dt * smoothRate * 1.5);
                this.sx += (s.x - this.sx) * kp;
                this.sy += (yRoot - this.sy) * kp;
                this.sz += (s.z - this.sz) * kp;
            }
        }
        Quaternionf orient = new Quaternionf().rotateY((float) this.sYaw).rotateX((float) this.sPitch).rotateZ((float) this.sRoll);
        Location loc = new Location(world, this.sx, this.sy, this.sz, 0.0F, 0.0F);
        this.apply(loc, orient, scale, dt, thr, -this.sPitch / D2R, this.sRoll / D2R, perch, true);
    }

    /** Превью в меню: птица на месте, крутится и машет. */
    void pose(Location at, double yaw, double shrink, double throttle, double pitchDeg, double bankDeg, boolean flying) {
        double dt = 0.05; // Меню обновляется раз в серверный тик.
        if (!this.poseInitialized) {
            this.previewYaw = yaw;
            this.perchS = flying ? 0 : 1;
            this.poseInitialized = true;
        }
        this.previewYaw += wrap(yaw - this.previewYaw) * 0.25;
        this.previewPitch += (pitchDeg - this.previewPitch) * 0.25;
        this.previewBank += (bankDeg - this.previewBank) * 0.25;
        double target = flying ? 0.0 : 1.0;
        this.perchS += (target - this.perchS) * Math.min(1.0, dt * 6.0);
        Quaternionf orient = new Quaternionf().rotateY((float) this.previewYaw);
        this.apply(at, orient, this.sizeBlocks / this.bird.len * shrink, dt, throttle, this.previewPitch, this.previewBank, this.perchS, false);
    }

    private static double wrap(double a) {
        return Math.atan2(Math.sin(a), Math.cos(a));
    }

    private double tickDt() {
        long now = System.nanoTime();
        double dt = this.lastNanos == 0L ? 0.05 : (now - this.lastNanos) / 1.0E9;
        this.lastNanos = now;
        return Math.max(0.0, Math.min(0.2, dt));
    }

    // ================================================================== поза (как в просмотрщике, только полёт)

    private static final class Wp {
        double fl;
        double s1;
        double ro;
        double s2;
        double b2;

        Wp(double fl, double s1, double ro, double s2, double b2) {
            this.fl = fl;
            this.s1 = s1;
            this.ro = ro;
            this.s2 = s2;
            this.b2 = b2;
        }

        Wp lerp(Wp o, double t) {
            return new Wp(this.fl + (o.fl - this.fl) * t, this.s1 + (o.s1 - this.s1) * t, this.ro + (o.ro - this.ro) * t,
                this.s2 + (o.s2 - this.s2) * t, this.b2 + (o.b2 - this.b2) * t);
        }
    }

    private Wp wingPose(double phase, double fe, double thr) {
        BirdData.Anim a = this.bird.anim;
        double sp = Math.sin(phase);
        double cp = Math.cos(phase);
        double up = Math.max(0.0, cp);
        if ("hover".equals(a.style)) {
            return new Wp(a.dihedral + 8 * fe + 10 * sp * fe, -8 + 50 * sp * fe, 45 * cp * fe, 6, 0);
        }
        double amp = (18 + 30 * thr) * fe * a.ampK;
        return new Wp(a.dihedral + amp * sp + 3 * fe, 6 + 10 * up * fe, -6 * cp * fe, 14 + 50 * up * fe, 0.55 * amp * Math.sin(phase - 1.3));
    }

    private static Wp rest(BirdData.Rest r) {
        return r == null ? null : new Wp(r.fl, r.s1, r.ro, r.s2, r.b2);
    }

    private void apply(Location loc, Quaternionf orient, double scale, double dt, double thr, double pitchDeg, double bankDeg,
                       double perch, boolean flightOrient) {
        BirdData.Anim a = this.bird.anim;
        String style = a.style == null ? "flap" : a.style;
        double dive = sm((-pitchDeg - 12.0) / 35.0);
        double flapAmt = ("glide".equals(style) || "spin".equals(style)) ? 0.0 : sm((thr - 0.15) / 0.3) * (1.0 - dive);
        double closedT = 0.0;
        if ("bounding".equals(style) && flapAmt > 0.3 && perch < 0.5) {
            this.bt += dt;
            closedT = (this.bt % 0.9) > 0.6 ? 1.0 : 0.0;
        }
        this.closed += (closedT - this.closed) * Math.min(1.0, dt * 7.0);
        double feT = flapAmt * (1.0 - this.closed) * (1.0 - perch);
        this.feS += (feT - this.feS) * Math.min(1.0, dt * 5.0);
        double fe = this.feS;
        double moving = ("hover".equals(style) || "pulse".equals(style) || fe > 0.05 || perch < 0.5) ? 1.0 : 0.15;
        this.ph += dt * 2 * Math.PI * Math.min(a.hz * (0.7 + 0.5 * thr), this.preview ? Math.min(2.0, maxFlapHz) : maxFlapHz) * moving;
        double sp = Math.sin(this.ph);
        double bk = bankDeg / 40.0;

        Wp p = this.wingPose(this.ph, fe, thr);
        double tg = a.diveTight;
        double tuck = a.noFold ? dive * 0.6 : Math.max(dive, this.closed);
        Wp dv = new Wp(2, 48 + 25 * tg, 0, 50 + 25 * tg, -4);
        p = p.lerp(dv, tuck);
        Wp restW = rest(a.rest);
        if (restW == null) {
            restW = this.var.foldReal ? new Wp(-2, 86, -82, 0, 0) : new Wp(-4, 85, -20, 25, 0);
        }
        p = p.lerp(restW, sm(perch * 1.6));
        Wp p2 = this.wingPose(this.ph - 0.8, fe, thr).lerp(dv, tuck);
        Wp rest2 = rest(a.rest2);
        p2 = p2.lerp(rest2 != null ? rest2 : restW, sm(perch * 1.6));

        Map<String, Quaternionf> rot = new HashMap<>();
        Map<String, double[]> scl = new HashMap<>();
        for (int side = 0; side < 2; side++) {
            double s = side == 0 ? 1.0 : -1.0;
            String suf = side == 0 ? "L" : "R";
            double turnFold = (1 - perch) * (1 - tuck) * Math.max(0.0, -bk * s) * 12.0;
            rot.put("wingSh" + suf, eulerZYX(p.ro * D2R, s * (p.s1 + turnFold) * D2R, s * p.fl * D2R));
            rot.put("wingWr" + suf, eulerZYX(0, s * (p.s2 + turnFold) * D2R, s * p.b2 * D2R));
            rot.put("wing2Sh" + suf, eulerZYX(p2.ro * D2R, s * (p2.s1 + turnFold) * D2R, s * p2.fl * D2R));
            rot.put("wing2Wr" + suf, eulerZYX(0, s * p2.s2 * D2R, s * p2.b2 * D2R));
            if (this.var.foldReal && a.rest == null) {
                scl.put("wingSh" + suf, new double[] {1.0 - this.var.foldK * sm(perch), 1, 1});
            }
        }
        // туловище: в полёте наклон даёт ориентация всей модели; на земле — стойка
        double hov = "hover".equals(style) ? 28 * fe : 0.0;
        double groundRx = -this.var.stand * D2R;
        double flyRx = flightOrient ? 0.0 : -(pitchDeg + hov) * D2R;
        if (flightOrient) {
            flyRx = -hov * D2R;
        }
        double rx = flyRx + (groundRx - flyRx) * sm(perch);
        double bodyRy = 0.0;
        if ("spin".equals(style)) {
            this.spin += dt * (perch > 0.5 ? 0 : 4 + 10 * thr + 6 * dive);
            bodyRy = this.spin;
        }
        rot.put("body", eulerYXZ(rx, bodyRy, flightOrient ? 0.0 : bankDeg * D2R * (1 - perch)));
        double bsx = 1;
        double bsy = 1;
        double bsz = 1;
        if (a.inflate) {
            double want = thr > 0.9 ? 1.0 : 0.0;
            this.inf += (want - this.inf) * Math.min(1.0, dt * 4.0);
            bsx = bsy = bsz = 1 + 0.45 * this.inf;
        }
        if ("pulse".equals(style)) {
            double pu = Math.sin(this.ph);
            bsx *= 1 + 0.08 * pu;
            bsy *= 1 - 0.14 * pu * (1 - perch * 0.7);
            bsz *= 1 + 0.08 * pu;
        }
        scl.put("body", new double[] {bsx, bsy, bsz});
        // хвост
        if (a.tail != null) {
            double spr = (1 - tuck) * (0.3 + 0.45 * thr + (pitchDeg > 8 ? 0.25 : 0) + Math.abs(bk) * 0.3) * (1 - 0.8 * perch);
            if (a.tail.fan) {
                scl.put("tailMesh", new double[] {0.8 + 0.4 * spr, 1, 1});
            }
            double swim = a.tail.swim ? Math.sin(this.ph - 1) * (10 + 15 * fe) : 0.0;
            double tx = this.var.tiltRad + (-4 * fe * sp + (pitchDeg < -20 ? 5 : 0) + perch * this.var.stand * 0.5 + swim) * D2R;
            rot.put("tail", eulerXYZ(tx, bk * 10 * D2R * (1 - perch), 0));
        }
        // звенья ленты / брюшка
        if (a.seg != null) {
            this.ph2 += dt * (perch > 0.5 ? 1.2 : 2.5 + 3 * fe);
            for (int i = 0; i < 16; i++) {
                double ang = Math.sin(this.ph2 - i * 0.7) * a.seg.amp * (perch > 0.5 ? 0.35 : 1.0) * D2R;
                if ("x".equals(a.seg.axis)) {
                    rot.put("seg" + i, eulerXYZ(ang, bk * 5 * D2R, 0));
                } else {
                    rot.put("seg" + i, eulerXYZ(0, ang + bk * 4 * D2R, 0));
                }
            }
        }
        // щупальца
        for (int i = 0; i < 12; i++) {
            double ta = i / 8.0 * Math.PI * 2;
            double wv = Math.sin(this.ph - 0.7 + ta) * (14 + 10 * (1 - perch)) * D2R;
            rot.put("tent" + i, eulerXYZ(wv * Math.sin(ta), 0, wv * Math.cos(ta)));
        }
        // лапы: в полёте поджаты, у земли выпущены, на земле — стойка
        double f1 = 18 * D2R;
        double f2 = -14 * D2R;
        double t = Math.max(sm(perch), this.legsS);
        double gh = f1 - rx;
        double gk = f2 - f1;
        double ga = -f2;
        for (String suf : new String[] {"L", "R"}) {
            rot.put("hip" + suf, eulerXYZ(1.45 + (gh - 1.45) * t, 0, 0));
            rot.put("knee" + suf, eulerXYZ(0.9 + (gk - 0.9) * t, 0, 0));
            rot.put("ank" + suf, eulerXYZ(0.5 + (ga - 0.5) * t, 0, 0));
        }
        // голова: держит горизонт в крене, смотрит в поворот; на земле оглядывается
        this.lookT -= dt;
        if (this.lookT <= 0) {
            this.lookT = perch > 0.5 ? 0.6 + Math.random() * 2.0 : 1.0 + Math.random() * 2.0;
            this.lookY = perch > 0.5 ? (Math.random() * 2 - 1) * 60 : (Math.random() * 2 - 1) * 8;
        }
        double hk = Math.min(1.0, dt * 11.0);
        this.hy += (this.lookY - bankDeg * 0.75 * (1 - perch) - this.hy) * hk;
        this.hz += (bankDeg * (flightOrient ? -0.6 : 0.35) * (1 - perch) - this.hz) * hk;
        double headX = -rx * 0.55 + (flightOrient ? pitchDeg * 0.5 * D2R * (1 - perch) : 0.0);
        rot.put("head", eulerYXZ(headX, this.hy * D2R, this.hz * D2R));
        if (a.crestFlat) {
            rot.put("crest", eulerXYZ(-38 * (1 - perch) * D2R, 0, 0));
        }
        if (a.neckFly != 0) {
            rot.put("neck", eulerXYZ(a.neckFly * D2R * (1 - sm(perch)), 0, 0));
        }

        // Собираем части: у каждой отдельно положение, поворот и размер (без «перекосов»),
        // поэтому игра плавно сглаживает их и детали не растягиваются и не дёргаются.
        double k = scale * this.var.k;
        Xf root = new Xf(new Vector3f(), new Quaternionf(orient), new Vector3f((float) k, (float) k, (float) k));
        Map<BirdData.Node, Xf> world = new HashMap<>();
        this.walk(this.var.rig, root, rot, scl, world);
        Quaternionf noRot = new Quaternionf();
        for (Part part : this.parts) {
            if (!part.display.isValid()) {
                continue;
            }
            Xf x = world.get(part.node);
            if (x == null) {
                continue;
            }
            float[] sz = part.node.mesh.size;
            Vector3f size;
            Vector3f pos;
            Quaternionf q;
            if (part.head) {
                float h = (float) headSize;
                size = new Vector3f(x.s.x * sz[0] / h, x.s.y * sz[1] / h, x.s.z * sz[2] / h);
                q = new Quaternionf(x.q).rotateY((float) (headYawDeg * D2R));
                pos = centeredHead(x.p, q, size, (float) (headOffsetY * h));
            } else {
                size = new Vector3f(x.s.x * Math.max(0.05F, sz[0]), x.s.y * Math.max(0.05F, sz[1]), x.s.z * Math.max(0.05F, sz[2]));
                q = new Quaternionf(x.q);
                // блок рисуется от угла (0,0,0) до (1,1,1) — сдвигаем, чтобы центр был в точке части
                pos = new Vector3f(x.p).sub(q.transform(new Vector3f(size).mul(0.5F)));
            }
            part.display.setInterpolationDelay(0);
            // Поза меняется каждый тик: длинная интерполяция отрывает концы крыльев от шарниров.
            part.display.setInterpolationDuration(1);
            part.display.setTransformation(new Transformation(pos, q, size, noRot));
        }
        if (this.rootEntity.isValid() && this.rootEntity.getLocation().distanceSquared(loc) > 1.0E-8) {
            this.rootEntity.teleport(loc, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
    }

    /** NONE: центр ванильной головы расположен на Y=-0.25 относительно item_display. */
    static Vector3f centeredHead(Vector3f pivot, Quaternionf rotation, Vector3f scale, float offset) {
        return new Vector3f(pivot).add(rotation.transform(new Vector3f(0, (0.25F + offset) * scale.y, 0)));
    }

    /** Положение, поворот и размер узла в координатах модели (блоки). */
    private static final class Xf {
        final Vector3f p;
        final Quaternionf q;
        final Vector3f s;

        Xf(Vector3f p, Quaternionf q, Vector3f s) {
            this.p = p;
            this.q = q;
            this.s = s;
        }
    }

    private void walk(BirdData.Node n, Xf parent, Map<String, Quaternionf> rot, Map<String, double[]> scl, Map<BirdData.Node, Xf> out) {
        Quaternionf lr = n.role != null && rot.containsKey(n.role) ? rot.get(n.role) : euler(n.r, n.ro);
        float lsx = n.s != null ? n.s[0] : 1F;
        float lsy = n.s != null ? n.s[1] : 1F;
        float lsz = n.s != null ? n.s[2] : 1F;
        double[] sc = n.role != null ? scl.get(n.role) : null;
        if (sc != null) {
            lsx *= (float) sc[0];
            lsy *= (float) sc[1];
            lsz *= (float) sc[2];
        }
        Vector3f lp = new Vector3f(n.p[0] * parent.s.x, n.p[1] * parent.s.y, n.p[2] * parent.s.z);
        Vector3f p = new Vector3f(parent.p).add(parent.q.transform(lp));
        Quaternionf q = new Quaternionf(parent.q).mul(lr);
        Vector3f s = new Vector3f(parent.s.x * lsx, parent.s.y * lsy, parent.s.z * lsz);
        Xf me = new Xf(p, q, s);
        if (n.mesh != null) {
            out.put(n, me);
        }
        if (n.c != null) {
            for (BirdData.Node c : n.c) {
                this.walk(c, me, rot, scl, out);
            }
        }
    }

    // ================================================================== математика

    static double sm(double x) {
        x = Math.max(0.0, Math.min(1.0, x));
        return x * x * (3 - 2 * x);
    }

    /** Порядок как в three.js: 'XYZ' → Rx·Ry·Rz и т. д. */
    private static Quaternionf euler(float[] r, String order) {
        Quaternionf q = new Quaternionf();
        if (r == null) {
            return q;
        }
        if (order == null) {
            order = "XYZ";
        }
        for (char c : order.toCharArray()) {
            switch (c) {
                case 'X' -> q.rotateX(r[0]);
                case 'Y' -> q.rotateY(r[1]);
                default -> q.rotateZ(r[2]);
            }
        }
        return q;
    }

    private static Quaternionf eulerXYZ(double x, double y, double z) {
        return new Quaternionf().rotateX((float) x).rotateY((float) y).rotateZ((float) z);
    }

    private static Quaternionf eulerZYX(double x, double y, double z) {
        return new Quaternionf().rotateZ((float) z).rotateY((float) y).rotateX((float) x);
    }

    private static Quaternionf eulerYXZ(double x, double y, double z) {
        return new Quaternionf().rotateY((float) y).rotateX((float) x).rotateZ((float) z);
    }

    /** Кватернион → углы (x — нос вниз, y — курс, z — крен), порядок YXZ, как в three.js. */
    static double[] eulerYXZ(double qx, double qy, double qz, double qw) {
        Mat4 m = Mat4.rotation(qx, qy, qz, qw);
        double m13 = m.m[8];
        double m23 = m.m[9];
        double m33 = m.m[10];
        double m21 = m.m[1];
        double m22 = m.m[5];
        double m11 = m.m[0];
        double m31 = m.m[2];
        double x = Math.asin(-Math.max(-1.0, Math.min(1.0, m23)));
        double y;
        double z;
        if (Math.abs(m23) < 0.9999999) {
            y = Math.atan2(m13, m33);
            z = Math.atan2(m21, m22);
        } else {
            y = Math.atan2(-m31, m11);
            z = 0.0;
        }
        return new double[] {x, y, z};
    }

    // ================================================================== SkinVisual

    @Override
    public void setColors(DroneColors colors) {
        // окраска птицы задаётся вариантом скина
    }

    @Override
    public void setVisibleFor(Plugin plugin, Player player, boolean visible) {
        for (Part p : this.parts) {
            if (visible) {
                player.showEntity(plugin, p.display);
            } else {
                player.hideEntity(plugin, p.display);
            }
        }
        if (visible) {
            player.showEntity(plugin, this.rootEntity);
        } else {
            player.hideEntity(plugin, this.rootEntity);
        }
    }

    @Override
    public boolean isValid() {
        if (!this.rootEntity.isValid()) {
            return false;
        }
        for (Part p : this.parts) {
            if (!p.display.isValid()) {
                return false;
            }
        }
        return !this.parts.isEmpty();
    }

    @Override
    public void remove() {
        for (Part p : this.parts) {
            if (p.display.isValid()) {
                p.display.remove();
            }
        }
        this.parts.clear();
        if (this.rootEntity.isValid()) {
            this.rootEntity.remove();
        }
    }

    int count() {
        return this.parts.size();
    }

    static boolean groundBelow(Block b) {
        return b != null && b.getType().isSolid();
    }
}
