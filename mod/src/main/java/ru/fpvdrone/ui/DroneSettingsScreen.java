package ru.fpvdrone.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import ru.fpvdrone.DroneSettings;
import ru.fpvdrone.FpvDroneClient;
import ru.fpvdrone.flight.Rates;
import ru.fpvdrone.input.ControllerProfile;
import ru.fpvdrone.input.Joysticks;
import ru.fpvdrone.net.DroneNetClient;

/**
 * Настройки FPV Drone на обычных виджетах Minecraft: ползунки тянутся мышью,
 * все изменения применяются сразу (даже в полёте) и сохраняются автоматически.
 */
public final class DroneSettingsScreen extends Screen {
    private enum Tab {
        CONTROLLER("Пульт"), RATES("Рейты"), CONTROL("Управление"), PHYSICS("Физика"),
        DAMAGE("Поломки"), LOOK("Вид"), SCREEN("Экран и звук");

        final String ru;

        Tab(String ru) {
            this.ru = ru;
        }
    }

    /** Описание одной настройки: как создать виджет в заданном месте. */
    private interface Entry {
        AbstractWidget create(int x, int y, int width);
    }

    private static Tab lastTab = Tab.CONTROLLER;

    private final Screen parent;
    private Tab tab = lastTab;
    private int page;

    public DroneSettingsScreen(Screen parent) {
        super(Component.literal("FPV Drone — настройки"));
        this.parent = parent;
    }

    private DroneSettings c() {
        return FpvDroneClient.SETTINGS;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.infoWidgets.clear();
        this.infoTexts.clear();
        int cx = this.width / 2;
        this.addRenderableWidget(new StringWidget(cx - 150, 6, 300, 12, this.title, this.font));

        // Вкладки
        Tab[] tabs = Tab.values();
        int tabW = Math.min(90, (this.width - 20) / tabs.length);
        int tabsX = cx - tabW * tabs.length / 2;
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            String label = t == this.tab ? "[" + t.ru + "]" : t.ru;
            this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
                this.tab = t;
                lastTab = t;
                this.page = 0;
                this.init();
            }).bounds(tabsX + i * tabW, 20, tabW - 2, 20).build());
        }

        // Настройки вкладки — в две колонки
        List<Entry> entries = this.entries(this.tab);
        int colW = Math.min(200, (this.width - 30) / 2);
        int top = 48;
        int bottomReserve = 34;
        int rows = Math.max(1, (this.height - top - bottomReserve) / 24);
        int perPage = rows * 2;
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        this.page = Math.min(this.page, pages - 1);
        int start = this.page * perPage;
        for (int i = start; i < Math.min(entries.size(), start + perPage); i++) {
            int local = i - start;
            int col = local % 2;
            int row = local / 2;
            int x = col == 0 ? cx - colW - 4 : cx + 4;
            int y = top + row * 24;
            this.addRenderableWidget(entries.get(i).create(x, y, colW));
        }

        // Низ: страницы, сброс, готово
        int by = this.height - 26;
        if (pages > 1) {
            this.addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                this.page = (this.page + pages - 1) % pages;
                this.init();
            }).bounds(cx - 210, by, 20, 20).build());
            this.addRenderableWidget(new StringWidget(cx - 188, by + 5, 40, 12,
                Component.literal((this.page + 1) + "/" + pages), this.font));
            this.addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                this.page = (this.page + 1) % pages;
                this.init();
            }).bounds(cx - 146, by, 20, 20).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Сбросить вкладку"), b -> {
            this.resetTab(this.tab);
            this.init();
        }).bounds(cx - 120, by, 116, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Готово"), b -> this.onClose())
            .bounds(cx + 4, by, 116, 20).build());
    }

    // ------------------------------------------------------------------ содержимое вкладок

    private List<Entry> entries(Tab t) {
        DroneSettings c = this.c();
        List<Entry> list = new ArrayList<>();
        switch (t) {
            case CONTROLLER -> {
                list.add(action("Настроить пульт…", "Оси «подвигай стиком», тумблер арма, кнопка выхода, калибровка. У каждого пульта свой профиль.",
                    () -> Minecraft.getInstance().gui.setScreen(new ControllerSetupScreen(this))));
                list.add(action("Клавиши…", "Стандартный экран клавиш Minecraft, раздел «FPV Drone». Нажми на действие, потом нужную клавишу.",
                    () -> Minecraft.getInstance().gui.setScreen(new KeyBindsScreen(this, Minecraft.getInstance().options))));
                list.add(slider("Мёртвая зона (этот пульт)", "Маленькие отклонения стиков около центра игнорируются. Своя у каждого пульта.",
                    0.0F, 0.3F, 0.01F, () -> profile().deadzone, v -> {
                        profile().deadzone = v;
                        FpvDroneClient.PROFILES.save();
                    }, DroneSettingsScreen::pct));
                list.add(toggle("Арм только при нуле газа", "Как в Betaflight: защита от взлёта «в лицо».",
                    () -> c.armRequiresLowThrottle, v -> c.armRequiresLowThrottle = v));
                list.add(toggle("Тумблер арма запускает FPV", "Включил арм на пульте — камера сразу переходит в дрон.",
                    () -> c.armSwitchLaunches, v -> c.armSwitchLaunches = v));
            }
            case RATES -> {
                list.add(slider("Крен/тангаж: RC Rate", "Чувствительность в центре стика.", 0.1F, 2.55F, 0.01F,
                    () -> c.rcRateRollPitch, v -> c.rcRateRollPitch = v, DroneSettingsScreen::num));
                list.add(slider("Рыскание: RC Rate", "Чувствительность рыскания в центре стика.", 0.1F, 2.55F, 0.01F,
                    () -> c.rcRateYaw, v -> c.rcRateYaw = v, DroneSettingsScreen::num));
                list.add(slider("Крен/тангаж: Super Rate", "Насколько быстрее вращение на краю стика.", 0.0F, 0.95F, 0.01F,
                    () -> c.superRateRollPitch, v -> c.superRateRollPitch = v, DroneSettingsScreen::num));
                list.add(slider("Рыскание: Super Rate", "", 0.0F, 0.95F, 0.01F,
                    () -> c.superRateYaw, v -> c.superRateYaw = v, DroneSettingsScreen::num));
                list.add(slider("Крен/тангаж: Expo", "Мягче в центре стика.", 0.0F, 1.0F, 0.01F,
                    () -> c.expoRollPitch, v -> c.expoRollPitch = v, DroneSettingsScreen::num));
                list.add(slider("Рыскание: Expo", "", 0.0F, 1.0F, 0.01F,
                    () -> c.expoYaw, v -> c.expoYaw = v, DroneSettingsScreen::num));
                list.add(info(() -> "Макс. крен: " + Math.round(Rates.degreesPerSecond(1.0F, c.rcRateRollPitch, c.superRateRollPitch, c.expoRollPitch)
                    * c.controlIntensity) + " °/с"));
                list.add(info(() -> "Макс. рыскание: " + Math.round(Rates.degreesPerSecond(1.0F, c.rcRateYaw, c.superRateYaw, c.expoYaw)
                    * c.controlIntensity) + " °/с"));
                list.add(slider("Экспонента газа", "0 = линейно. Больше = точнее висение.", 0.0F, 1.0F, 0.01F,
                    () -> c.throttleExpo, v -> c.throttleExpo = v, DroneSettingsScreen::num));
                list.add(slider("Ограничение газа", "Максимум газа (Throttle Limit).", 0.2F, 1.0F, 0.01F,
                    () -> c.throttleLimit, v -> c.throttleLimit = v, DroneSettingsScreen::pct));
                list.add(slider("Холостой ход", "Обороты при арме с газом в нуле.", 0.0F, 0.2F, 0.01F,
                    () -> c.idleThrottle, v -> c.idleThrottle = v, DroneSettingsScreen::pct));
            }
            case CONTROL -> {
                list.add(slider("Интенсивность управления", "Общий множитель скорости вращения поверх рейтов. 1 = как в рейтах.",
                    0.3F, 2.0F, 0.05F, () -> c.controlIntensity, v -> c.controlIntensity = v, v -> String.format(Locale.ROOT, "x%.2f", v)));
                list.add(slider("Плавность управления", "Сглаживание стиков: 0 = мгновенный отклик, больше = мягче и плавнее.",
                    0.0F, 1.0F, 0.05F, () -> c.controlSmoothing, v -> c.controlSmoothing = v, DroneSettingsScreen::pct));
                list.add(slider("Наклон камеры", "Угол камеры вверх. Гонщики летают с 25–45°. Другие игроки видят этот наклон.",
                    -20.0F, 80.0F, 1.0F, () -> c.cameraAngle, v -> c.cameraAngle = v, v -> Math.round(v) + "°"));
            }
            case PHYSICS -> {
                list.add(cycle("Тип дрона", "Готовый набор физики. Ползунки ниже переставятся сразу.",
                    DroneSettings.DronePreset.values(), () -> c.preset, v -> {
                        c.applyPreset(v);
                        this.init();
                    }, v -> v.ru));
                list.add(slider("Тяговооружённость", "Во сколько раз тяга больше веса. Гоночный 5\" — 8–10, синевуп — 3–4.",
                    1.5F, 15.0F, 0.1F, () -> c.thrustToWeight, v -> custom(() -> c.thrustToWeight = v), v -> String.format(Locale.ROOT, "%.1f : 1", v)));
                list.add(slider("Нелинейность тяги", "0 = тяга ровно по газу, 1 = как у настоящих моторов.",
                    0.0F, 1.0F, 0.05F, () -> c.thrustCurve, v -> custom(() -> c.thrustCurve = v), DroneSettingsScreen::num));
                list.add(slider("Сопротивление воздуха", "Больше — ниже максимальная скорость.",
                    0.0F, 0.3F, 0.005F, () -> c.dragQuadratic, v -> custom(() -> c.dragQuadratic = v), v -> String.format(Locale.ROOT, "%.3f", v)));
                list.add(slider("Вязкость воздуха", "Больше — дрон быстрее «останавливается» сам.",
                    0.0F, 2.0F, 0.01F, () -> c.dragLinear, v -> custom(() -> c.dragLinear = v), DroneSettingsScreen::num));
                list.add(slider("Отклик моторов", "За сколько секунд моторы набирают обороты.",
                    0.005F, 0.3F, 0.005F, () -> c.motorResponse, v -> custom(() -> c.motorResponse = v), v -> String.format(Locale.ROOT, "%.3f с", v)));
                list.add(slider("Отклик вращения", "За сколько секунд дрон выходит на заданную скорость вращения (жёсткость PID).",
                    0.005F, 0.3F, 0.005F, () -> c.rateResponse, v -> custom(() -> c.rateResponse = v), v -> String.format(Locale.ROOT, "%.3f с", v)));
                list.add(slider("Сопротивление плашмя", "Во сколько раз сильнее тормозит, когда дрон падает брюхом вниз.",
                    1.0F, 5.0F, 0.1F, () -> c.bellyDrag, v -> c.bellyDrag = v, v -> String.format(Locale.ROOT, "x%.1f", v)));
                list.add(slider("Затухание кувырка", "Как быстро гаснет вращение после дизарма.",
                    0.0F, 10.0F, 0.1F, () -> c.tumbleDamping, v -> c.tumbleDamping = v, DroneSettingsScreen::num));
                list.add(slider("Гравитация", "9.81 = Земля.", 0.0F, 30.0F, 0.1F,
                    () -> c.gravity, v -> c.gravity = v, v -> String.format(Locale.ROOT, "%.1f м/с²", v)));
            }
            case DAMAGE -> {
                list.add(cycle("Когда дрон разбит", "Новый дрон на старте или возврат к игроку.",
                    DroneSettings.CrashAction.values(), () -> c.crashAction, v -> c.crashAction = v,
                    v -> v == DroneSettings.CrashAction.RESPAWN ? "новый на старте" : "к игроку"));
                list.add(slider("Прочность", "1 = обычная, 2 = ломается вдвое меньше.", 0.25F, 4.0F, 0.05F,
                    () -> c.durability, v -> c.durability = v, v -> String.format(Locale.ROOT, "x%.2f", v)));
                list.add(slider("Безопасное касание", "Удары медленнее этой скорости ничего не ломают.", 0.5F, 6.0F, 0.1F,
                    () -> c.bumpSpeed, v -> c.bumpSpeed = v, DroneSettingsScreen::mps));
                list.add(slider("Поломка пропеллера", "Прямой удар с этой скоростью ломает пропеллер.", 3.0F, 25.0F, 0.5F,
                    () -> c.propBreakSpeed, v -> c.propBreakSpeed = v, DroneSettingsScreen::mps));
                list.add(slider("Поломка луча/мотора", "", 6.0F, 40.0F, 0.5F,
                    () -> c.armBreakSpeed, v -> c.armBreakSpeed = v, DroneSettingsScreen::mps));
                list.add(slider("Разрушение рамы", "Дрон разбит полностью.", 10.0F, 60.0F, 0.5F,
                    () -> c.frameBreakSpeed, v -> c.frameBreakSpeed = v, DroneSettingsScreen::mps));
                list.add(slider("Отскок", "Насколько дрон отскакивает от стен.", 0.0F, 0.8F, 0.05F,
                    () -> c.bounciness, v -> c.bounciness = v, DroneSettingsScreen::num));
                list.add(toggle("Вода и лава убивают дрон", "", () -> c.liquidsDestroy, v -> c.liquidsDestroy = v));
            }
            case LOOK -> {
                list.add(color("Рама", () -> c.colorFrame, v -> c.colorFrame = v));
                list.add(color("Лучи", () -> c.colorArms, v -> c.colorArms = v));
                list.add(color("Пропеллеры", () -> c.colorProps, v -> c.colorProps = v));
                list.add(color("Моторы", () -> c.colorMotors, v -> c.colorMotors = v));
                list.add(color("Камера", () -> c.colorCamera, v -> c.colorCamera = v));
                list.add(color("Батарея", () -> c.colorBattery, v -> c.colorBattery = v));
                list.add(color("Подсветка и след", () -> c.colorLed, v -> c.colorLed = v));
            }
            case SCREEN -> {
                list.add(toggle("OSD (газ, скорость, время)", "", () -> c.showOsd, v -> c.showOsd = v));
                list.add(toggle("Компас и направление к игроку", "Полоса компаса сверху и метка, где стоит игрок.", () -> c.showCompass, v -> c.showCompass = v));
                list.add(toggle("Панель повреждений", "", () -> c.showDamagePanel, v -> c.showDamagePanel = v));
                list.add(toggle("Стики на экране", "", () -> c.showSticks, v -> c.showSticks = v));
                list.add(toggle("Прятать интерфейс Minecraft", "Хотбар, прицел, здоровье в полёте.", () -> c.hideVanillaHud, v -> c.hideVanillaHud = v));
                list.add(slider("Громкость моторов", "0 = без звука.", 0.0F, 1.0F, 0.05F,
                    () -> c.motorVolume, v -> c.motorVolume = v, DroneSettingsScreen::pct));
                list.add(slider("Дальность (без сервера)", "Сколько блоков можно улететь от игрока. На сервере с модом/плагином действует дальность сервера.",
                    0.0F, 512.0F, 8.0F, () -> c.localMaxRange, v -> c.localMaxRange = v,
                    v -> v <= 0.0F ? "только чанки" : Math.round(v) + " бл."));
                list.add(info(() -> {
                    float r = DroneNetClient.effectiveMaxRange();
                    return r > 0.0F ? "Сейчас действует: " + Math.round(r) + " блоков" + (DroneNetClient.serverHasSupport ? " (сервер)" : "")
                        : "Сейчас: до границы загруженных чанков";
                }));
            }
        }
        return list;
    }

    private void custom(Runnable apply) {
        apply.run();
        this.c().preset = DroneSettings.DronePreset.CUSTOM;
    }

    private void resetTab(Tab t) {
        DroneSettings c = this.c();
        DroneSettings d = new DroneSettings();
        switch (t) {
            case CONTROLLER -> {
                c.armRequiresLowThrottle = d.armRequiresLowThrottle;
                c.armSwitchLaunches = d.armSwitchLaunches;
                profile().deadzone = new ControllerProfile().deadzone;
                FpvDroneClient.PROFILES.save();
            }
            case RATES -> {
                c.rcRateRollPitch = d.rcRateRollPitch;
                c.superRateRollPitch = d.superRateRollPitch;
                c.expoRollPitch = d.expoRollPitch;
                c.rcRateYaw = d.rcRateYaw;
                c.superRateYaw = d.superRateYaw;
                c.expoYaw = d.expoYaw;
                c.throttleExpo = d.throttleExpo;
                c.throttleLimit = d.throttleLimit;
                c.idleThrottle = d.idleThrottle;
            }
            case CONTROL -> {
                c.controlIntensity = d.controlIntensity;
                c.controlSmoothing = d.controlSmoothing;
                c.cameraAngle = d.cameraAngle;
            }
            case PHYSICS -> {
                c.applyPreset(d.preset);
                c.bellyDrag = d.bellyDrag;
                c.tumbleDamping = d.tumbleDamping;
                c.gravity = d.gravity;
            }
            case DAMAGE -> {
                c.crashAction = d.crashAction;
                c.durability = d.durability;
                c.bumpSpeed = d.bumpSpeed;
                c.propBreakSpeed = d.propBreakSpeed;
                c.armBreakSpeed = d.armBreakSpeed;
                c.frameBreakSpeed = d.frameBreakSpeed;
                c.bounciness = d.bounciness;
                c.liquidsDestroy = d.liquidsDestroy;
            }
            case LOOK -> {
                c.colorFrame = d.colorFrame;
                c.colorArms = d.colorArms;
                c.colorProps = d.colorProps;
                c.colorMotors = d.colorMotors;
                c.colorCamera = d.colorCamera;
                c.colorBattery = d.colorBattery;
                c.colorLed = d.colorLed;
                DroneNetClient.colorsChanged();
            }
            case SCREEN -> {
                c.showOsd = d.showOsd;
                c.showCompass = d.showCompass;
                c.showDamagePanel = d.showDamagePanel;
                c.showSticks = d.showSticks;
                c.hideVanillaHud = d.hideVanillaHud;
                c.motorVolume = d.motorVolume;
                c.localMaxRange = d.localMaxRange;
            }
        }
        c.markDirty();
    }

    // ------------------------------------------------------------------ фабрики виджетов

    private static ControllerProfile profile() {
        Joysticks.Device device = Joysticks.pick(FpvDroneClient.SETTINGS.controllerName);
        return FpvDroneClient.PROFILES.get(device == null ? "" : device.name());
    }

    private static Entry slider(String name, String tip, float min, float max, float step, Supplier<Float> get, Consumer<Float> set,
                                Function<Float, String> fmt) {
        return (x, y, w) -> {
            FloatSlider s = new FloatSlider(x, y, w, 20, name, min, max, step, get, v -> {
                set.accept(v);
                FpvDroneClient.SETTINGS.markDirty();
            }, fmt);
            if (!tip.isEmpty()) {
                s.setTooltip(Tooltip.create(Component.literal(tip)));
            }
            return s;
        };
    }

    private static Entry toggle(String name, String tip, Supplier<Boolean> get, Consumer<Boolean> set) {
        return (x, y, w) -> {
            Button b = Button.builder(Component.literal(name + ": " + (get.get() ? "Да" : "Нет")), btn -> {
                set.accept(!get.get());
                FpvDroneClient.SETTINGS.markDirty();
                btn.setMessage(Component.literal(name + ": " + (get.get() ? "Да" : "Нет")));
            }).bounds(x, y, w, 20).build();
            if (!tip.isEmpty()) {
                b.setTooltip(Tooltip.create(Component.literal(tip)));
            }
            return b;
        };
    }

    private static <E> Entry cycle(String name, String tip, E[] values, Supplier<E> get, Consumer<E> set, Function<E, String> fmt) {
        return (x, y, w) -> {
            Button b = Button.builder(Component.literal(name + ": " + fmt.apply(get.get())), btn -> {
                E current = get.get();
                int index = 0;
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == current) {
                        index = i;
                    }
                }
                set.accept(values[(index + 1) % values.length]);
                FpvDroneClient.SETTINGS.markDirty();
                btn.setMessage(Component.literal(name + ": " + fmt.apply(get.get())));
            }).bounds(x, y, w, 20).build();
            if (!tip.isEmpty()) {
                b.setTooltip(Tooltip.create(Component.literal(tip)));
            }
            return b;
        };
    }

    private static Entry color(String name, Supplier<DroneSettings.DroneColor> get, Consumer<DroneSettings.DroneColor> set) {
        return cycle(name, "Этот цвет видят другие игроки.", DroneSettings.DroneColor.values(), get, v -> {
            set.accept(v);
            DroneNetClient.colorsChanged();
        }, DroneSettings.DroneColor::ru);
    }

    private static Entry action(String name, String tip, Runnable run) {
        return (x, y, w) -> {
            Button b = Button.builder(Component.literal(name), btn -> run.run()).bounds(x, y, w, 20).build();
            b.setTooltip(Tooltip.create(Component.literal(tip)));
            return b;
        };
    }

    private final List<StringWidget> infoWidgets = new ArrayList<>();
    private final List<Supplier<String>> infoTexts = new ArrayList<>();

    /** Строка-подсказка (только текст), обновляется каждый тик. */
    private Entry info(Supplier<String> text) {
        return (x, y, w) -> {
            StringWidget widget = new StringWidget(x, y + 5, w, 12, Component.literal(text.get()), Minecraft.getInstance().font);
            this.infoWidgets.add(widget);
            this.infoTexts.add(text);
            return widget;
        };
    }

    @Override
    public void tick() {
        super.tick();
        for (int i = 0; i < this.infoWidgets.size(); i++) {
            this.infoWidgets.get(i).setMessage(Component.literal(this.infoTexts.get(i).get()));
        }
    }

    private static String num(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String mps(float v) {
        return String.format(Locale.ROOT, "%.1f м/с", v);
    }

    private static String pct(float v) {
        return Math.round(v * 100.0F) + "%";
    }

    @Override
    public void onClose() {
        FpvDroneClient.SETTINGS.save();
        FpvDroneClient.PROFILES.save();
        this.minecraft.gui.setScreen(this.parent);
    }
}
