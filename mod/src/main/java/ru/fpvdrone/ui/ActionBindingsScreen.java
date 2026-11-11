package ru.fpvdrone.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import ru.fpvdrone.*;
import ru.fpvdrone.input.*;

/** Назначение клавиатуры и кнопок пульта в меню мода. */
public final class ActionBindingsScreen extends Screen {
    private final Screen parent;
    private KeyMapping capture;
    private boolean gamepad;
    private boolean[] previous = new boolean[0];
    private String device = "";
    private int page;
    public ActionBindingsScreen(Screen parent) { super(Component.literal("Назначение действий")); this.parent = parent; }
    @Override protected void init() {
        clearWidgets();
        int x = 12;
        int buttonWidth = (width - 54) / 2;
        addRenderableWidget(new StringWidget(x, 8, 300, 20, capture == null ? title : Component.literal("Нажмите кнопку; Esc — отмена"), font));
        var keys = ActionButtons.actions();
        int rows = Math.max(1, (height - 72) / 44);
        int pages = (keys.length + rows - 1) / rows;
        page = Math.min(page, pages - 1);
        var d = Joysticks.pick(FpvDroneClient.SETTINGS.controllerName);
        for (int j = page * rows; j < Math.min(keys.length, (page + 1) * rows); j++) {
            var key = keys[j]; int y = 34 + (j - page * rows) * 44;
            addRenderableWidget(new StringWidget(x,y,width-24,12,Component.translatable(key.getName()),font));
            addRenderableWidget(Button.builder(key.getTranslatedKeyMessage(), b -> { capture=key; gamepad=false; init(); }).bounds(x,y+15,buttonWidth,20).build());
            int index = d == null ? -1 : ActionButtons.button(d.name(),key);
            var assign = addRenderableWidget(Button.builder(Component.literal(index < 0 ? "Пульт: —" : "Пульт: " + (index + 1)), b -> {
                var snap = Joysticks.read(Joysticks.pick(FpvDroneClient.SETTINGS.controllerName));
                if (snap != null) { capture=key; gamepad=true; device=snap.device().name(); previous=snap.buttons().clone(); init(); }
            }).bounds(x+buttonWidth+4,y+15,buttonWidth,20).build());
            assign.active = d != null;
            addRenderableWidget(Button.builder(Component.literal("×"), b -> { key.setKey(InputConstants.UNKNOWN); KeyMapping.resetMapping(); Minecraft.getInstance().options.save(); if(d!=null) ActionButtons.assign(d.name(),key,-1); init(); }).bounds(width-32,y+15,20,20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Страница " + (page+1) + "/" + pages), b -> {page=(page+1)%pages; init();}).bounds(width/2-150,height-28,145,20).build());
        addRenderableWidget(Button.builder(Component.literal("Готово"),b->onClose()).bounds(width/2+5,height-28,145,20).build());
    }
    @Override public boolean keyPressed(KeyEvent e) {
        if(capture==null) return super.keyPressed(e);
        if(e.key()==InputConstants.KEY_ESCAPE) {capture=null;init();return true;}
        if(!gamepad) {capture.setKey(InputConstants.getKey(e));KeyMapping.resetMapping();Minecraft.getInstance().options.save();capture=null;init();}
        return true;
    }
    @Override public void tick() {
        if(capture==null || !gamepad) return;
        var snap=Joysticks.read(Joysticks.pick(device));
        if(snap==null || !snap.device().name().equals(device)) return;
        for(int i=0;i<snap.buttons().length;i++) if(snap.buttons()[i] && (i>=previous.length || !previous[i])) {ActionButtons.assign(device,capture,i);capture=null;init();break;}
        previous=snap.buttons().clone();
    }
    @Override public void onClose(){Minecraft.getInstance().gui.setScreen(parent);}
}
