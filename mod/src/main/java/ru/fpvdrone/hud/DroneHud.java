package ru.fpvdrone.hud;

import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import ru.fpvdrone.DroneSettings;
import ru.fpvdrone.FpvDroneClient;
import ru.fpvdrone.ModKeys;
import ru.fpvdrone.damage.DroneDamage;
import ru.fpvdrone.flight.DroneController;
import ru.fpvdrone.flight.DroneState;
import ru.fpvdrone.input.StickInput;
import ru.fpvdrone.net.DroneNetClient;

/**
 * OSD как в FPV-очках: состояние арма, газ, скорость, высота, время полёта,
 * положение стиков, повреждения и связь.
 */
public final class DroneHud {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int GREEN = 0xFF55FF55;
    private static final int GRAY = 0xFFAAAAAA;

    private DroneHud() {
    }

    public static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        DroneController c = FpvDroneClient.CONTROLLER;
        DroneSettings cfg = FpvDroneClient.SETTINGS;
        if (c == null || cfg == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int w = g.guiWidth();
        int h = g.guiHeight();

        // Подсказка о подключённом пульте — видна и без полёта.
        String controllerNotice = c.controllerNotice();
        if (!controllerNotice.isEmpty() && !c.isViewingDrone()) {
            int tw = font.width(controllerNotice);
            g.fill((w - tw) / 2 - 4, 4, (w + tw) / 2 + 4, 17, 0x90000000);
            g.text(font, controllerNotice, (w - tw) / 2, 6, YELLOW, true);
        }
        if (!c.isActive()) {
            return;
        }
        DroneState s = c.state;
        if (!c.isViewingDrone()) {
            // Камера у игрока, дрон где-то в мире.
            String line = String.format(Locale.ROOT, "Дрон: %d бл. · %s — в дрон · %s — убрать",
                minecraft.player == null ? 0 : Math.round(c.distanceToPlayer(minecraft.player)),
                ModKeys.label(ModKeys.VIEW), ModKeys.label(ModKeys.EXIT));
            int tw = font.width(line);
            g.fill(w - tw - 12, h - 62, w - 4, h - 48, 0x90000000);
            g.text(font, line, w - tw - 8, h - 59, WHITE, true);
            String notice = c.notice();
            if (!notice.isEmpty()) {
                g.text(font, notice, (w - font.width(notice)) / 2, h / 2 + 30, YELLOW, true);
            }
            return;
        }
        if (cfg.showCompass && minecraft.player != null) {
            drawCompass(g, font, c, minecraft.player.getEyePosition(), w);
        }

        if (cfg.showOsd) {
            int x = 8;
            int y = 8;
            String armText = s.destroyed ? "РАЗБИТ" : (s.armed ? "ARMED" : "DISARMED");
            int armColor = s.destroyed ? RED : (s.armed ? GREEN : YELLOW);
            g.text(font, armText, x, y, armColor, true);
            g.text(font, String.format(Locale.ROOT, "Газ %3d%%", Math.round(c.sticks().throttle() * 100.0F)), x, y + 11, WHITE, true);
            g.text(font, String.format(Locale.ROOT, "%3.0f км/ч", s.speed() * 3.6), x, y + 22, WHITE, true);
            g.text(font, String.format(Locale.ROOT, "Выс %+.1f м", s.y - s.launchY), x, y + 33, WHITE, true);
            long secs = (System.nanoTime() - c.flightStartNanos()) / 1_000_000_000L;
            g.text(font, String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60), x, y + 44, WHITE, true);
            if (!c.sticks().connected()) {
                g.text(font, "Пульт не найден", x, y + 58, RED, true);
            }

            // Связь / дальность — под панелью повреждений справа.
            float range = DroneNetClient.effectiveMaxRange();
            String link;
            int linkColor;
            if (range > 0.0F && minecraft.player != null) {
                Vec3 eye = minecraft.player.getEyePosition();
                double dx = s.x - eye.x;
                double dy = s.y - eye.y;
                double dz = s.z - eye.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double ratio = dist / range;
                link = String.format(Locale.ROOT, "%d / %d бл.", Math.round(dist), Math.round(range));
                linkColor = ratio > 0.85 ? RED : (ratio > 0.6 ? YELLOW : GREEN);
            } else if (minecraft.player != null) {
                link = String.format(Locale.ROOT, "До игрока %d бл.", Math.round(c.distanceToPlayer(minecraft.player)));
                linkColor = GRAY;
            } else {
                link = "";
                linkColor = GRAY;
            }
            g.text(font, link, w - font.width(link) - 8, cfg.showDamagePanel ? 84 : 8, linkColor, true);
            if (c.isBlockedByRange()) {
                String warn = "ГРАНИЦА СИГНАЛА";
                g.text(font, warn, (w - font.width(warn)) / 2, h / 2 - 30, RED, true);
            }

            if (!s.armed && !s.destroyed) {
                String hint = "Арм: " + ModKeys.label(ModKeys.ARM) + (c.sticks().armSwitch() != null ? " или тумблер" : "");
                g.text(font, hint, (w - font.width(hint)) / 2, h / 2 + 20, YELLOW, true);
            }
            String notice = c.notice();
            if (!notice.isEmpty()) {
                g.text(font, notice, (w - font.width(notice)) / 2, h / 2 - 16, notice.contains("РАЗБИТ") ? RED : WHITE, true);
            }
        }

        if (cfg.showSticks) {
            drawSticks(g, c.sticks(), w, h);
        }
        if (cfg.showDamagePanel) {
            drawDamage(g, font, c.damage, w);
        }
    }

    /**
     * Полоса компаса сверху: стороны света по направлению носа дрона и метка «⌂» — где стоит игрок.
     * Если игрок сзади, метка прижимается к краю со стрелкой.
     */
    private static void drawCompass(GuiGraphicsExtractor g, Font font, DroneController c, Vec3 playerEye, int w) {
        DroneState s = c.state;
        org.joml.Vector3f fwd = new org.joml.Vector3f(0.0F, 0.0F, 1.0F);
        s.orientation.transform(fwd);
        // Курс в системе Minecraft: 0 = юг (+Z), 90 = запад (-X), 180 = север, 270 = восток.
        double heading = Math.toDegrees(Math.atan2(-fwd.x, fwd.z));
        int half = 110;
        int cx = w / 2;
        int y = 20;
        g.fill(cx - half - 4, y - 2, cx + half + 4, y + 12, 0x70000000);
        double pxPerDeg = half / 90.0;
        String[] names = {"Ю", "ЮЗ", "З", "СЗ", "С", "СВ", "В", "ЮВ"};
        for (int i = 0; i < 24; i++) {
            double yaw = i * 15.0;
            double rel = wrap(yaw - heading);
            if (Math.abs(rel) > 90.0) {
                continue;
            }
            int x = cx + (int) Math.round(rel * pxPerDeg);
            if (i % 3 == 0) {
                String label = names[(i / 3) % 8];
                int color = label.equals("С") ? RED : WHITE;
                g.text(font, label, x - font.width(label) / 2, y + 1, color, true);
            } else {
                g.fill(x, y + 3, x + 1, y + 8, 0x90FFFFFF);
            }
        }
        g.fill(cx, y - 4, cx + 1, y - 1, 0xFFFFFFFF);

        // Где игрок
        double dx = playerEye.x - s.x;
        double dz = playerEye.z - s.z;
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = wrap(bearing - heading);
        double dist = Math.sqrt(dx * dx + dz * dz + (playerEye.y - s.y) * (playerEye.y - s.y));
        String mark;
        int x;
        if (rel < -90.0) {
            mark = "◄⌂";
            x = cx - half;
        } else if (rel > 90.0) {
            mark = "⌂►";
            x = cx + half - font.width("⌂►");
        } else {
            mark = "⌂";
            x = cx + (int) Math.round(rel * pxPerDeg) - font.width("⌂") / 2;
        }
        g.text(font, mark, x, y + 13, 0xFF55FFFF, true);
        String d = Math.round(dist) + " бл.";
        g.text(font, d, Math.max(cx - half, Math.min(cx + half - font.width(d), x - font.width(d) / 2 + 3)), y + 23, 0xFF55FFFF, true);
    }

    private static double wrap(double deg) {
        double r = deg % 360.0;
        if (r > 180.0) {
            r -= 360.0;
        }
        if (r < -180.0) {
            r += 360.0;
        }
        return r;
    }

    private static void drawSticks(GuiGraphicsExtractor g, StickInput.Sticks st, int w, int h) {
        int size = 34;
        int gap = 10;
        int y0 = h - size - 44;
        int leftX = w / 2 - size - gap / 2;
        int rightX = w / 2 + gap / 2;
        // Mode 2: левый стик — газ (вертикаль) и рыскание, правый — тангаж и крен.
        drawStick(g, leftX, y0, size, st.yaw(), st.throttle() * 2.0F - 1.0F);
        drawStick(g, rightX, y0, size, st.roll(), -st.pitch());
    }

    private static void drawStick(GuiGraphicsExtractor g, int x, int y, int size, float sx, float sy) {
        g.fill(x, y, x + size, y + size, 0x60000000);
        g.fill(x + size / 2, y, x + size / 2 + 1, y + size, 0x40FFFFFF);
        g.fill(x, y + size / 2, x + size, y + size / 2 + 1, 0x40FFFFFF);
        int px = x + Math.round((sx * 0.5F + 0.5F) * (size - 4)) + 2;
        int py = y + Math.round((-sy * 0.5F + 0.5F) * (size - 4)) + 2;
        g.fill(px - 2, py - 2, px + 2, py + 2, 0xFFFFFFFF);
    }

    private static void drawDamage(GuiGraphicsExtractor g, Font font, DroneDamage d, int w) {
        int pw = 118;
        int ph = 70;
        int x0 = w - pw - 6;
        int y0 = 6;
        g.fill(x0, y0, x0 + pw, y0 + ph, 0x90000000);
        int cx = x0 + 28;
        int cy = y0 + 32;
        drawArm(g, cx, cy, -1, -1, d.arm[DroneDamage.FL]);
        drawArm(g, cx, cy, 1, -1, d.arm[DroneDamage.FR]);
        drawArm(g, cx, cy, -1, 1, d.arm[DroneDamage.RL]);
        drawArm(g, cx, cy, 1, 1, d.arm[DroneDamage.RR]);
        drawProp(g, cx - 15, cy - 15, d.prop[DroneDamage.FL]);
        drawProp(g, cx + 15, cy - 15, d.prop[DroneDamage.FR]);
        drawProp(g, cx - 15, cy + 15, d.prop[DroneDamage.RL]);
        drawProp(g, cx + 15, cy + 15, d.prop[DroneDamage.RR]);
        g.fill(cx - 4, cy - 7, cx + 4, cy + 7, healthColor(d.frame));
        g.fill(cx - 2, cy - 10, cx + 2, cy - 6, healthColor(d.camera));

        int tx = x0 + 54;
        int ty = y0 + 6;
        g.text(font, "Рама " + pct(d.frame), tx, ty, WHITE, true);
        g.text(font, "Кам. " + pct(d.camera), tx, ty + 10, WHITE, true);
        g.text(font, "Тяга " + pct(d.thrustScale()), tx, ty + 20, WHITE, true);
        long since = System.nanoTime() - d.lastImpactNanos;
        if (d.lastImpactNanos != 0L && since < 2_500_000_000L) {
            g.text(font, String.format(Locale.ROOT, "Удар %.1f", d.lastImpactSpeed), tx, ty + 30, RED, true);
        }
    }

    private static void drawArm(GuiGraphicsExtractor g, int cx, int cy, int sx, int sy, float health) {
        int color = healthColor(health);
        for (int i = 3; i <= 12; i += 3) {
            int px = cx + sx * i;
            int py = cy + sy * i;
            g.fill(px - 1, py - 1, px + 2, py + 2, color);
        }
    }

    private static void drawProp(GuiGraphicsExtractor g, int px, int py, float health) {
        if (health <= 0.0F) {
            g.fill(px - 5, py - 1, px + 5, py + 1, 0xFF555555);
            g.fill(px - 1, py - 5, px + 1, py + 5, 0xFF555555);
            return;
        }
        g.fill(px - 6, py - 6, px + 6, py + 6, healthColor(health) & 0xA0FFFFFF);
    }

    private static int healthColor(float health) {
        if (health <= 0.0F) {
            return 0xFF444444;
        }
        if (health >= 0.75F) {
            return 0xFF40D040;
        }
        if (health >= 0.4F) {
            return 0xFFE0D030;
        }
        return 0xFFE03030;
    }

    private static String pct(float v) {
        return Math.round(Math.max(0.0F, Math.min(1.0F, v)) * 100.0F) + "%";
    }
}
