package ru.fpvdrone.ui;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * Обычный ползунок Minecraft: тянется мышью, двигается стрелками. Значение применяется сразу.
 */
public final class FloatSlider extends AbstractSliderButton {
    private final String label;
    private final float min;
    private final float max;
    private final float step;
    private final Consumer<Float> setter;
    private final Function<Float, String> format;

    public FloatSlider(int x, int y, int width, int height, String label, float min, float max, float step,
                       Supplier<Float> getter, Consumer<Float> setter, Function<Float, String> format) {
        super(x, y, width, height, Component.empty(), toSlider(getter.get(), min, max));
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.setter = setter;
        this.format = format;
        this.updateMessage();
    }

    private static double toSlider(float v, float min, float max) {
        if (max <= min) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (v - min) / (double) (max - min)));
    }

    /** Текущее значение с учётом шага. */
    public float current() {
        float v = (float) (this.min + this.value * (this.max - this.min));
        if (this.step > 0.0F) {
            v = Math.round((v - this.min) / this.step) * this.step + this.min;
        }
        return Math.max(this.min, Math.min(this.max, v));
    }

    @Override
    protected void updateMessage() {
        if (this.format == null) {
            return; // вызов из конструктора родителя, поля ещё не готовы
        }
        this.setMessage(Component.literal(this.label + ": " + this.format.apply(this.current())));
    }

    @Override
    protected void applyValue() {
        if (this.setter != null) {
            this.setter.accept(this.current());
        }
    }
}
