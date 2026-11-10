package ru.fpvdrone.mixin.server;

import com.mojang.math.Transformation;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к приватным сеттерам display-сущностей (те же, что использует команда /data). */
@Mixin(Display.class)
public interface DisplayInvoker {
    @Invoker("setTransformation")
    void invokeFpvdroneSetTransformation(Transformation transformation);

    @Invoker("setTransformationInterpolationDuration")
    void invokeFpvdroneSetInterpolationDuration(int ticks);

    @Invoker("setTransformationInterpolationDelay")
    void invokeFpvdroneSetInterpolationDelay(int ticks);

    @Invoker("setPosRotInterpolationDuration")
    void invokeFpvdroneSetTeleportDuration(int ticks);

    @Invoker("setViewRange")
    void invokeFpvdroneSetViewRange(float range);

    @Invoker("setGlowColorOverride")
    void invokeFpvdroneSetGlowColor(int rgb);

    @Invoker("setBrightnessOverride")
    void invokeFpvdroneSetBrightness(Brightness brightness);
}
