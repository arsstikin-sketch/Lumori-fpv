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

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = g.guiWidth() / 2 - 158, y = g.guiHeight() - 150;
        if (controller) drawController(g, x, y);
        else drawKeyboard(g, x, y, FpvDroneClient.SETTINGS.keyboardScheme);
    }

    private static void drawKeyboard(GuiGraphicsExtractor g, int x, int y, DroneSettings.KeyboardScheme scheme) {
        label(g, "КЛАВИАТУРА И МЫШЬ", x, y);
        key(g, x + 28, y + 22, 22, "W");
        key(g, x + 4, y + 48, 22, "A");
        key(g, x + 28, y + 48, 22, "S");
        key(g, x + 52, y + 48, 22, "D");
        key(g, x + 4, y + 76, 72, "SPACE");
        key(g, x + 4, y + 100, 72, "SHIFT");
        g.outline(x + 107, y + 22, 42, 52, WHITE);
        line(g, x + 128, y + 22, x + 128, y + 74);
        label(g, "МЫШЬ", x + 106, y + 78);
        String ws, ad, space, shift, mouse;
        switch (scheme) {
            case FPV_SWAPPED -> { ws = "газ ±"; ad = "рыскание"; space = "—"; shift = "—"; mouse = "↕ тангаж, ↔ крен"; }
            case KEYBOARD_ASSIST -> { ws = "тангаж"; ad = "рыск + крен"; space = "газ +"; shift = "газ −"; mouse = "не управляет"; }
            case SPECTATOR -> { ws = "вперёд/назад"; ad = "в стороны"; space = "высота +"; shift = "высота −"; mouse = "направление взгляда"; }
            default -> { return; }
        }
        callout(g, x + 78, y + 33, x + 162, y + 30, "W/S: " + ws);
        callout(g, x + 78, y + 59, x + 162, y + 48, "A/D: " + ad);
        callout(g, x + 78, y + 88, x + 162, y + 66, "SPACE: " + space);
        callout(g, x + 78, y + 112, x + 162, y + 84, "SHIFT: " + shift);
        callout(g, x + 149, y + 41, x + 162, y + 104, mouse);
    }

    private static void drawController(GuiGraphicsExtractor g, int x, int y) {
        var sticks = StickInput.read(FpvDroneClient.SETTINGS.controllerName, FpvDroneClient.PROFILES);
        label(g, sticks.connected() ? "ПУЛЬТ: " + sticks.controllerName() : "ПУЛЬТ НЕ ПОДКЛЮЧЁН", x, y);
        int leftX = x + 69, rightX = x + 237, cy = y + 63;
        g.outline(x + 18, y + 22, 280, 100, WHITE);
        stick(g, leftX, cy, sticks.connected() ? sticks.yaw() : 0,
            sticks.connected() ? 1 - 2 * sticks.throttle() : 0);
        stick(g, rightX, cy, sticks.connected() ? sticks.roll() : 0,
            sticks.connected() ? sticks.pitch() : 0);
        label(g, "Рыскание ↔", leftX - 35, y + 103);
        label(g, "Газ ↕", leftX - 18, y + 113);
        label(g, "Крен ↔", rightX - 22, y + 103);
        label(g, "Тангаж ↕", rightX - 31, y + 113);
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
