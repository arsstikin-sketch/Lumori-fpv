package ru.fpvdrone.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import ru.fpvdrone.FpvDroneClient;
import ru.fpvdrone.flight.DroneController;
import ru.fpvdrone.flight.DroneState;

/**
 * Звук моторов: чем больше газ, тем громче и выше тон — как у настоящего FPV-дрона.
 * Звучит из точки, где находится дрон (если смотришь от игрока — слышно издалека).
 */
public final class MotorSound extends AbstractTickableSoundInstance {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(FpvDroneClient.MOD_ID, "motor");
    private static final SoundEvent EVENT = SoundEvent.createVariableRangeEvent(ID);

    private final DroneController controller;
    private boolean finished;

    public MotorSound(DroneController controller) {
        super(EVENT, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.controller = controller;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        this.pitch = 0.6F;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.relative = false;
        this.follow();
    }

    private void follow() {
        DroneState s = this.controller.state;
        this.x = s.x;
        this.y = s.y;
        this.z = s.z;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (this.finished || !this.controller.isActive()) {
            this.stop();
            return;
        }
        this.follow();
        DroneState s = this.controller.state;
        float motor = s.armed ? s.motor : 0.0F;
        float master = FpvDroneClient.SETTINGS.motorVolume;
        // Холостой ход — тихое жужжание, полный газ — громкий высокий вой.
        float target = motor <= 0.001F ? 0.0F : (0.18F + 0.82F * (float) Math.sqrt(motor));
        this.volume += (target * master - this.volume) * 0.5F;
        float targetPitch = 0.55F + 1.35F * motor;
        this.pitch += (targetPitch - this.pitch) * 0.6F;
        this.pitch = Math.max(0.5F, Math.min(2.0F, this.pitch));
    }

    public void finish() {
        this.finished = true;
    }
}
