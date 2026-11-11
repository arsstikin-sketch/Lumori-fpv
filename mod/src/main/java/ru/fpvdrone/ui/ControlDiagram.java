package ru.fpvdrone.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import ru.fpvdrone.DroneSettings;
import ru.fpvdrone.FpvDroneClient;
import ru.fpvdrone.input.StickInput;

/** White line diagrams on the screen's existing transparent content layer. */
final class ControlDiagram implements Renderable {
    private static final int WHITE = 0xffffffff;
    private final boolean controller;
    ControlDiagram(boolean controller) { this.controller = controller; }

    /** Размер схемы учитывается и при размещении настроек, и при рисовании. */
    static float scale(int width, int height) {
        return Math.min(1F, Math.min((width - 24) / 440F, Math.max(64, height - 116) / 132F));
    }

    static int reservedHeight(int width, int height) {
        return Math.round(132 * scale(width, height)) + 42;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        float scale = scale(g.guiWidth(), g.guiHeight());
        g.pose().pushMatrix();
        g.pose().translate((g.guiWidth() - 440 * scale) / 2, g.guiHeight() - 34 - 132 * scale);
        g.pose().scale(scale, scale);
        if (controller) drawController(g, 0, 0);
        else drawKeyboard(g, 0, 0, FpvDroneClient.SETTINGS.keyboardScheme);
        g.pose().popMatrix();
    }

    private static void drawKeyboard(GuiGraphicsExtractor g, int x, int y, DroneSettings.KeyboardScheme scheme) {
        label(g, "КЛАВИАТУРА", x, y);
        boolean fpv = scheme == DroneSettings.KeyboardScheme.FPV_SWAPPED;
        boolean free = scheme == DroneSettings.KeyboardScheme.SPECTATOR;
        keyPair(g, x, y + 20, "W", "S", fpv ? "Газ + / −" : free ? "Вперёд / назад" : "Тангаж");
        keyPair(g, x, y + 46, "A", "D", fpv ? "Рыскание" : free ? "Влево / вправо" : "Рыскание + крен");
        if (!fpv) {
            keyRow(g, x, y + 72, "SPACE", free ? "Высота +" : "Газ +");
            keyRow(g, x, y + 98, "SHIFT", free ? "Высота −" : "Газ −");
        }
        int mx = x + 268;
        label(g, "МЫШЬ", mx, y);
        g.outline(mx, y + 24, 36, 54, WHITE);
        line(g, mx + 18, y + 24, mx + 18, y + 44);
        line(g, mx, y + 44, mx + 36, y + 44);
        if (scheme == DroneSettings.KeyboardScheme.KEYBOARD_ASSIST) {
            label(g, "Не используется", mx, y + 94);
        } else {
            callout(g, mx + 36, y + 34, mx + 50, y + 30, "↕ Тангаж");
            callout(g, mx + 36, y + 64, mx + 50, y + 60, free ? "↔ Рыскание" : "↔ Крен");
            label(g, free ? "Направление камеры" : "Поворот дрона", mx, y + 94);
        }
    }

    private static void keyPair(GuiGraphicsExtractor g, int x, int y, String a, String b, String text) {
        key(g, x, y, 26, a);
        key(g, x + 32, y, 26, b);
        callout(g, x + 59, y + 10, x + 76, y + 6, text);
    }

    private static void keyRow(GuiGraphicsExtractor g, int x, int y, String key, String text) {
        key(g, x, y, 58, key);
        callout(g, x + 59, y + 10, x + 76, y + 6, text);
    }

    private static void drawController(GuiGraphicsExtractor g, int x, int y) {
        var sticks = StickInput.read(FpvDroneClient.SETTINGS.controllerName, FpvDroneClient.PROFILES);
        label(g, sticks.connected() ? "ПУЛЬТ ПОДКЛЮЧЁН" : "ПУЛЬТ НЕ ПОДКЛЮЧЁН", x + 143, y);
        // Контур корпуса, два стика и центральная антенна.
        line(g, x + 120, y + 25, x + 320, y + 25);
        line(g, x + 120, y + 25, x + 106, y + 98);
        line(g, x + 106, y + 98, x + 334, y + 98);
        line(g, x + 334, y + 98, x + 320, y + 25);
        line(g, x + 220, y + 16, x + 220, y + 25);
        stick(g, x + 151, y + 61, sticks.connected() ? sticks.yaw() : 0,
            sticks.connected() ? 1 - 2 * sticks.throttle() : 0);
        stick(g, x + 289, y + 61, sticks.connected() ? sticks.roll() : 0,
            sticks.connected() ? sticks.pitch() : 0);
        label(g, "Газ ↕", x + 134, y + 108);
        label(g, "Рыскание ↔", x + 118, y + 121);
        label(g, "Тангаж ↕", x + 264, y + 108);
        label(g, "Крен ↔", x + 269, y + 121);
    }

    private static void stick(GuiGraphicsExtractor g, int x, int y, float horiz, float vert) {
        g.outline(x - 27, y - 27, 54, 54, WHITE);
        line(g, x - 27, y, x + 27, y);
        line(g, x, y - 27, x, y + 27);
        int px = x + Math.round(Math.max(-1, Math.min(1, horiz)) * 23);
        int py = y + Math.round(Math.max(-1, Math.min(1, vert)) * 23);
        g.outline(px - 3, py - 3, 7, 7, WHITE);
    }

    private static void key(GuiGraphicsExtractor g, int x, int y, int width, String name) {
        g.outline(x, y, width, 20, WHITE);
        label(g, name, x + (width - Minecraft.getInstance().font.width(name)) / 2, y + 6);
    }
    private static void callout(GuiGraphicsExtractor g, int x, int y, int labelX, int labelY, String name) {
        line(g, x, y, labelX - 3, labelY + 4);
        label(g, name, labelX, labelY);
    }
    private static void label(GuiGraphicsExtractor g, String value, int x, int y) {
        g.text(Minecraft.getInstance().font, value, x, y, WHITE, false);
    }
    private static void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, err = dx - dy;
        while (true) {
            g.fill(x1, y1, x1 + 1, y1 + 1, WHITE);
            if (x1 == x2 && y1 == y2) return;
            int e = 2 * err;
            if (e > -dy) { err -= dy; x1 += sx; }
            if (e < dx) { err += dx; y1 += sy; }
        }
    }
}
