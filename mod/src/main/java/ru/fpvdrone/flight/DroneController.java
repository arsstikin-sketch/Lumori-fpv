package ru.fpvdrone.flight;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Quaternionf;
import ru.fpvdrone.DroneSettings;
import ru.fpvdrone.FpvDroneClient;
import ru.fpvdrone.ModKeys;
import ru.fpvdrone.damage.DroneDamage;
import ru.fpvdrone.input.Joysticks;
import ru.fpvdrone.input.StickInput;
import ru.fpvdrone.net.DroneNetClient;
import ru.fpvdrone.sound.MotorSound;

/**
 * Жизненный цикл полёта: запуск, вид «я/дрон», арм/дизарм, шаги физики, разбивание и респавн.
 *
 * Дрон существует, пока он «активен». Камера может быть либо в дроне, либо у игрока
 * (клавиша «Вид»): в это время дрон дизармлен и лежит/падает там, где был, его видят другие.
 */
public final class DroneController {
    private static final int MAX_STEPS_PER_FRAME = 48;

    public final DroneState state = new DroneState();
    public final DroneDamage damage = new DroneDamage();

    private final ru.fpvdrone.input.KeyboardMouseInput keyboard = new ru.fpvdrone.input.KeyboardMouseInput();
    private boolean keyboardMode;
    private DroneSettings.KeyboardScheme lastKeyboardScheme;
    private long lastInputNanos;
    private boolean modeKeyPrev;

    public void mouseMotion(double dx, double dy) {
        if (keyboardMode && Minecraft.getInstance().gui.screen() == null) keyboard.mouse(dx, dy);
    }

    private boolean active;
    private boolean viewingDrone;
    private long lastFrameNanos;
    private double accumulator;
    private StickInput.Sticks sticks = StickInput.Sticks.NONE;

    private Boolean armSwitchPrev;
    private Boolean exitSwitchPrev;
    private boolean armKeyPrev;
    private boolean exitKeyPrev;
    private boolean respawnKeyPrev;
    private boolean viewKeyPrev;

    private float lastHealth = -1.0F;
    private long destroyedAtNanos;
    private long flightStartNanos;
    private boolean blockedByRange;
    private MotorSound sound;

    private String notice = "";
    private long noticeUntilNanos;

    /** Последний подключённый пульт (для подсказки «нажми K для настройки»). */
    private String lastControllerName = "";
    private long controllerNoticeUntil;

    public boolean isActive() {
        return this.active;
    }

    /** Камера сейчас в дроне (а не у игрока). */
    public boolean isViewingDrone() {
        return this.active && this.viewingDrone;
    }

    public StickInput.Sticks sticks() {
        return this.sticks;
    }

    public boolean isBlockedByRange() {
        return this.blockedByRange;
    }

    public long flightStartNanos() {
        return this.flightStartNanos;
    }

    public String notice() {
        return System.nanoTime() < this.noticeUntilNanos ? this.notice : "";
    }

    /** Подсказка о новом пульте (показывается и не в полёте). */
    public String controllerNotice() {
        return System.nanoTime() < this.controllerNoticeUntil
            ? "Пульт «" + this.lastControllerName + "» подключён. " + ModKeys.label(ModKeys.CONTROLLER_SETUP) + " — настройка осей"
            : "";
    }

    private void showNotice(String text) {
        this.notice = text;
        this.noticeUntilNanos = System.nanoTime() + 1_800_000_000L;
    }

    // ------------------------------------------------------------------ кадр

    /** Вызывается каждый кадр (из миксина камеры), до расчёта матрицы вида. */
    public void onFrame(Minecraft minecraft) {
        DroneSettings cfg = FpvDroneClient.SETTINGS;
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            if (this.active) {
                this.exit();
            }
            this.lastHealth = -1.0F;
            return;
        }

        this.sticks = StickInput.read(cfg.controllerName, FpvDroneClient.PROFILES);
        this.watchController();
        boolean useKeyboard = cfg.inputMode == DroneSettings.InputMode.KEYBOARD_MOUSE
            || (cfg.inputMode == DroneSettings.InputMode.AUTO && !this.sticks.connected());
        long inputNow = System.nanoTime();
        double inputDt = this.lastInputNanos == 0 ? 0 : (inputNow - this.lastInputNanos) / 1.0E9;
        this.lastInputNanos = inputNow;
        if (this.keyboardMode != useKeyboard || this.lastKeyboardScheme != cfg.keyboardScheme) {
            this.keyboard.reset();
            this.state.armed = false;
        }
        this.keyboardMode = useKeyboard;
        this.lastKeyboardScheme = cfg.keyboardScheme;
        this.state.keyboardSmartSteering = useKeyboard && cfg.keyboardScheme == DroneSettings.KeyboardScheme.KEYBOARD_ASSIST;
        this.state.spectatorMode = useKeyboard && cfg.keyboardScheme == DroneSettings.KeyboardScheme.SPECTATOR;
        boolean controls = this.isViewingDrone() && minecraft.gui.screen() == null && minecraft.isWindowActive();
        if (useKeyboard) {
            if (!controls) this.keyboard.reset();
            this.sticks = this.keyboard.sample(inputDt,
                controls && minecraft.options.keyUp.isDown(), controls && minecraft.options.keyDown.isDown(),
                controls && minecraft.options.keyLeft.isDown(), controls && minecraft.options.keyRight.isDown(),
                controls && minecraft.options.keyJump.isDown(), controls && minecraft.options.keyShift.isDown(),
                cfg.keyboardThrottleSpeed, cfg.mouseSensitivity, cfg.invertMousePitch,
                cfg.keyboardScheme, this.state.speed());
            if (this.state.spectatorMode && controls) {
                this.keyboard.spectator(this.state,
                    minecraft.options.keyUp.isDown(), minecraft.options.keyDown.isDown(),
                    minecraft.options.keyLeft.isDown(), minecraft.options.keyRight.isDown(),
                    minecraft.options.keyJump.isDown(), minecraft.options.keyShift.isDown(),
                    cfg.mouseSensitivity, cfg.invertMousePitch);
            }
        }
        if (this.isViewingDrone() && (!controls || !this.sticks.connected())) {
            this.state.armed = false;
            this.keyboard.reset();
        }
        this.state.levelMode = DroneNetClient.effectiveStabilization() == DroneSettings.Stabilization.LEVEL;
        this.handleInputs(minecraft, player, cfg);

        // Урон по игроку (или смерть) — дрон пропадает, где бы ни была камера.
        float health = player.getHealth();
        if (this.active && (player.isDeadOrDying() || (this.lastHealth >= 0.0F && health < this.lastHealth - 0.01F))) {
            this.showNotice("Игрок получил урон — дрон потерян");
            this.exit();
        }
        this.lastHealth = health;

        if (!this.active) {
            this.lastFrameNanos = 0L;
            return;
        }
        this.updateSound(minecraft, cfg);

        long now = System.nanoTime();
        if (this.lastFrameNanos == 0L) {
            this.lastFrameNanos = now;
        }
        double frameDt = Math.min(0.1, (now - this.lastFrameNanos) / 1.0E9);
        this.lastFrameNanos = now;

        if (this.state.destroyed) {
            this.handleDestroyed(now, cfg);
            return;
        }

        // Пока камера у игрока — стики не управляют дроном.
        StickInput.Sticks input = this.viewingDrone ? this.sticks : StickInput.Sticks.NONE;
        this.accumulator += frameDt;
        int steps = 0;
        Vec3 eye = player.getEyePosition();
        float range = DroneNetClient.effectiveMaxRange();
        this.blockedByRange = false;
        while (this.accumulator >= DronePhysics.STEP && steps < MAX_STEPS_PER_FRAME) {
            DronePhysics.Result result = DronePhysics.step(this.state, this.damage, input, cfg, minecraft.level, player, eye, range);
            this.accumulator -= DronePhysics.STEP;
            steps++;
            if (result == DronePhysics.Result.BLOCKED) {
                this.blockedByRange = true;
            }
            if (result == DronePhysics.Result.DESTROYED) {
                this.destroyedAtNanos = now;
                this.accumulator = 0.0;
                this.showNotice("ДРОН РАЗБИТ");
                break;
            }
        }
        if (steps == MAX_STEPS_PER_FRAME) {
            this.accumulator = 0.0;
        }
    }

    private void watchController() {
        String name = this.sticks.connected() ? this.sticks.controllerName() : "";
        if (!name.isEmpty() && !name.equals(this.lastControllerName)) {
            this.controllerNoticeUntil = System.nanoTime() + 6_000_000_000L;
        }
        this.lastControllerName = name;
    }

    private void handleDestroyed(long now, DroneSettings cfg) {
        long since = now - this.destroyedAtNanos;
        if (cfg.crashAction == DroneSettings.CrashAction.EXIT || !this.viewingDrone) {
            if (since > 800_000_000L) {
                this.exit();
            }
        } else if (since > 1_200_000_000L) {
            this.respawn();
        }
    }

    // ------------------------------------------------------------------ управление

    private void handleInputs(Minecraft minecraft, LocalPlayer player, DroneSettings cfg) {
        boolean screenOpen = minecraft.gui.screen() != null || !minecraft.isWindowActive();

        boolean modeKey = !screenOpen && isDown(ModKeys.STABILIZATION);
        if (modeKey && !this.modeKeyPrev) {
            if (DroneNetClient.serverStabilization == 0) {
                cfg.stabilization = cfg.stabilization == DroneSettings.Stabilization.ACRO
                    ? DroneSettings.Stabilization.LEVEL : DroneSettings.Stabilization.ACRO;
                cfg.markDirty();
            }
            this.state.levelMode = DroneNetClient.effectiveStabilization() == DroneSettings.Stabilization.LEVEL;
            this.showNotice("Режим: " + DroneNetClient.effectiveStabilization()
                + (DroneNetClient.serverStabilization == 0 ? "" : " (задан сервером)"));
        }
        this.modeKeyPrev = modeKey;
        boolean armKey = !screenOpen && isDown(ModKeys.ARM);
        boolean exitKey = !screenOpen && isDown(ModKeys.EXIT);
        boolean respawnKey = !screenOpen && isDown(ModKeys.RESPAWN);
        boolean viewKey = !screenOpen && isDown(ModKeys.VIEW);

        if (viewKey && !this.viewKeyPrev) {
            this.toggleView(player);
        }
        if (respawnKey && !this.respawnKeyPrev && this.active) {
            this.respawn();
            this.viewingDrone = true;
        }
        if (armKey && !this.armKeyPrev) {
            if (!this.active) {
                this.launch(player);
                this.tryArm(cfg);
            } else if (!this.viewingDrone) {
                this.toggleView(player);
            } else if (this.state.armed) {
                this.state.armed = false;
            } else {
                this.tryArm(cfg);
            }
        }
        if (exitKey && !this.exitKeyPrev && this.active) {
            this.exit();
        }
        this.armKeyPrev = armKey;
        this.exitKeyPrev = exitKey;
        this.respawnKeyPrev = respawnKey;
        this.viewKeyPrev = viewKey;

        // Тумблер арма на пульте — по положению, как на настоящем дроне.
        Boolean arm = this.sticks.armSwitch();
        if (arm != null && this.armSwitchPrev != null && !screenOpen) {
            if (arm && !this.armSwitchPrev) {
                if (!this.active && cfg.armSwitchLaunches) {
                    this.launch(player);
                } else if (this.active && !this.viewingDrone) {
                    this.toggleView(player);
                }
                if (this.active) {
                    this.tryArm(cfg);
                }
            } else if (!arm && this.armSwitchPrev && this.active) {
                this.state.armed = false;
            }
        }
        this.armSwitchPrev = arm;

        Boolean exit = this.sticks.exitSwitch();
        if (exit != null && this.exitSwitchPrev != null && exit && !this.exitSwitchPrev && this.active && !screenOpen) {
            this.exit();
        }
        this.exitSwitchPrev = exit;
    }

    private static boolean isDown(KeyMapping key) {
        return key != null && key.isDown();
    }

    /** Переключение камеры: игрок ↔ дрон. Дрон при этом остаётся в мире. */
    private void toggleView(LocalPlayer player) {
        if (!this.active) {
            this.launch(player);
            return;
        }
        if (this.viewingDrone) {
            this.viewingDrone = false;
            this.state.armed = false; // без пилота дрон дизармится и падает/лежит
            this.showNotice("Камера у игрока. " + ModKeys.label(ModKeys.VIEW) + " — обратно в дрон");
        } else {
            float range = DroneNetClient.effectiveMaxRange();
            Vec3 eye = player.getEyePosition();
            double dx = this.state.x - eye.x;
            double dy = this.state.y - eye.y;
            double dz = this.state.z - eye.z;
            if (range > 0.0F && dx * dx + dy * dy + dz * dz > (double) range * range) {
                this.showNotice("Дрон вне зоны связи — подойди ближе");
                return;
            }
            this.viewingDrone = true;
            this.lastFrameNanos = 0L;
        }
    }

    private void tryArm(DroneSettings cfg) {
        if (!this.active || this.state.destroyed || !this.viewingDrone) {
            return;
        }
        if (cfg.armRequiresLowThrottle && this.sticks.connected() && this.sticks.throttle() > 0.05F) {
            this.showNotice("Опусти газ, чтобы армить");
            return;
        }
        this.state.armed = true;
    }

    /** Запуск: дрон появляется у ног игрока (в режиме наблюдателя — у головы). */
    public void launch(LocalPlayer player) {
        Vec3 spawn = spawnPosition(player);
        this.state.resetAt(spawn.x, spawn.y, spawn.z, player.getYHeadRot());
        this.damage.repairAll();
        this.keyboard.reset();
        this.active = true;
        this.viewingDrone = true;
        this.accumulator = 0.0;
        this.lastFrameNanos = 0L;
        this.flightStartNanos = System.nanoTime();
        if (!this.sticks.connected()) {
            this.showNotice("Пульт не найден");
        }
    }

    private static Vec3 spawnPosition(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        if (player.isSpectator()) {
            return eye;
        }
        Vec3 feet = player.position();
        double yaw = Math.toRadians(player.getYHeadRot());
        // На полблока вперёд от ног и чуть выше земли.
        Vec3 front = new Vec3(feet.x - Math.sin(yaw) * 0.6, feet.y + 0.1, feet.z + Math.cos(yaw) * 0.6);
        if (isFree(player, front)) {
            return front;
        }
        Vec3 atFeet = new Vec3(feet.x, feet.y + 0.1, feet.z);
        return isFree(player, atFeet) ? atFeet : eye;
    }

    private static boolean isFree(LocalPlayer player, Vec3 pos) {
        Level level = player.level();
        BlockPos block = BlockPos.containing(pos.x, pos.y, pos.z);
        return level.getBlockState(block).getCollisionShape(level, block, CollisionContext.of(player)).isEmpty();
    }

    private void respawn() {
        this.state.resetAt(this.state.launchX, this.state.launchY, this.state.launchZ, this.state.launchYaw);
        this.damage.repairAll();
        this.keyboard.reset();
        this.accumulator = 0.0;
        this.flightStartNanos = System.nanoTime();
        this.showNotice("Новый дрон. Армить: " + ModKeys.label(ModKeys.ARM) + " / тумблер");
    }

    public void exit() {
        this.keyboard.reset();
        this.active = false;
        this.viewingDrone = false;
        this.state.armed = false;
        this.accumulator = 0.0;
        this.lastFrameNanos = 0L;
        if (this.sound != null) {
            this.sound.finish();
            this.sound = null;
        }
    }

    private void updateSound(Minecraft minecraft, DroneSettings cfg) {
        if (cfg.motorVolume <= 0.0F) {
            if (this.sound != null) {
                this.sound.finish();
                this.sound = null;
            }
            return;
        }
        if (this.sound == null || this.sound.isStopped()) {
            this.sound = new MotorSound(this);
            minecraft.getSoundManager().play(this.sound);
        }
    }

    /** Расстояние от дрона до глаз игрока, блоков. */
    public double distanceToPlayer(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        double dx = this.state.x - eye.x;
        double dy = this.state.y - eye.y;
        double dz = this.state.z - eye.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public boolean hasController() {
        return !Joysticks.devices().isEmpty();
    }

    // ------------------------------------------------------------------ для камеры

    private float alpha() {
        return (float) Math.max(0.0, Math.min(1.0, this.accumulator / DronePhysics.STEP));
    }

    public Vec3 renderPosition() {
        double a = this.alpha();
        DroneState s = this.state;
        return new Vec3(s.prevX + (s.x - s.prevX) * a, s.prevY + (s.y - s.prevY) * a, s.prevZ + (s.z - s.prevZ) * a);
    }

    public Quaternionf renderCameraOrientation() {
        Quaternionf q = new Quaternionf(this.state.prevOrientation).slerp(this.state.orientation, this.alpha());
        return this.state.spectatorMode ? q
            : q.rotateX((float) Math.toRadians(-FpvDroneClient.SETTINGS.cameraAngle));
    }
}
