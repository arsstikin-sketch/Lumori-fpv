package ru.fpvdrone.server;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import ru.fpvdrone.mixin.server.BlockDisplayInvoker;
import ru.fpvdrone.mixin.server.DisplayInvoker;

/**
 * Модель дрона из block_display-сущностей на сервере с модом (без плагина).
 * Их видят все игроки — и с модом, и без. Устроена так же, как в плагине FpvDrone.
 */
public final class ServerDroneModel {
    public static final String TAG = "fpvdrone_part";

    /** Цвета по умолчанию: рама, лучи, пропеллеры, моторы, камера, батарея, подсветка. */
    public static final byte[] DEFAULT_COLORS = {3, 3, 5, 1, 2, 7, 8};
    private static final String[] COLOR_NAMES = {
        "WHITE", "LIGHT_GRAY", "GRAY", "BLACK", "BROWN", "RED", "ORANGE", "YELLOW",
        "LIME", "GREEN", "CYAN", "LIGHT_BLUE", "BLUE", "PURPLE", "MAGENTA", "PINK"
    };
    private static final int C_FRAME = 0;
    private static final int C_ARMS = 1;
    private static final int C_PROPS = 2;
    private static final int C_MOTORS = 3;
    private static final int C_CAMERA = 4;
    private static final int C_BATTERY = 5;
    private static final int C_LED = 6;

    /** RGB цветов (для обводки и следа). */
    static final int[] RGB = {
        0xF9FFFE, 0x9D9D97, 0x474F52, 0x1D1D21, 0x835432, 0xB02E26, 0xF9801D, 0xFED83D,
        0x80C71F, 0x5E7C16, 0x169C9C, 0x3AB3DA, 0x3C44AA, 0x8932B8, 0xC74EBD, 0xF38BAA
    };

    private static final double[][] MOTORS = {
        {0.11, 0.0, 0.11}, {-0.11, 0.0, 0.11}, {0.11, 0.0, -0.11}, {-0.11, 0.0, -0.11}
    };
    private static final int[] SPIN_DIR = {1, -1, -1, 1};
    private static final double TINY = 1.0E-4;

    private final ServerLevel level;
    private final UUID owner;
    private final float scale;
    private byte[] colors;

    private final List<Display.BlockDisplay> all = new ArrayList<>();
    private final Display.BlockDisplay frame;
    private final Display.BlockDisplay battery;
    private final Display.BlockDisplay armA;
    private final Display.BlockDisplay armB;
    private final Display.BlockDisplay[] motors = new Display.BlockDisplay[4];
    private final Display.BlockDisplay[] props = new Display.BlockDisplay[4];
    private final Display.BlockDisplay camera;
    private final Display.BlockDisplay lens;
    private final Display.BlockDisplay led;
    private final Display.BlockDisplay ledFront;
    private final boolean bright;
    private final boolean glow;
    private final float[] propAngle = new float[4];
    private final boolean[] propIsGlass = new boolean[4];

    public ServerDroneModel(ServerLevel level, UUID owner, RemoteState s, byte[] colors, float scale, float viewRange,
                            boolean bright, boolean glow) {
        this.level = level;
        this.owner = owner;
        this.scale = scale;
        this.bright = bright;
        this.glow = glow;
        this.colors = colors == null ? DEFAULT_COLORS : colors;
        this.frame = this.spawn(s, solid(this.colors[C_FRAME]), viewRange, false);
        this.battery = this.spawn(s, solid(this.colors[C_BATTERY]), viewRange, false);
        this.armA = this.spawn(s, solid(this.colors[C_ARMS]), viewRange, false);
        this.armB = this.spawn(s, solid(this.colors[C_ARMS]), viewRange, false);
        for (int i = 0; i < 4; i++) {
            this.motors[i] = this.spawn(s, solid(this.colors[C_MOTORS]), viewRange, false);
            this.props[i] = this.spawn(s, solid(this.colors[C_PROPS]), viewRange, false);
            this.propAngle[i] = i * 45.0F;
        }
        this.camera = this.spawn(s, solid(this.colors[C_CAMERA]), viewRange, false);
        this.lens = this.spawn(s, McLookup.block("light_blue_stained_glass").defaultBlockState(), viewRange, false);
        this.led = this.spawn(s, solid(this.colors[C_LED]), viewRange, true);
        this.ledFront = this.spawn(s, solid(this.colors[C_LED]), viewRange, true);
    }

    public UUID owner() {
        return this.owner;
    }

    public ServerLevel level() {
        return this.level;
    }

    public List<Display.BlockDisplay> entities() {
        return this.all;
    }

    private Display.BlockDisplay spawn(RemoteState s, BlockState state, float viewRange, boolean glowing) {
        Display.BlockDisplay d = new Display.BlockDisplay(McLookup.blockDisplay(), this.level);
        d.setPos(s.x, s.y, s.z);
        d.addTag(TAG);
        ((BlockDisplayInvoker) d).invokeFpvdroneSetBlockState(state);
        DisplayInvoker inv = (DisplayInvoker) d;
        inv.invokeFpvdroneSetTeleportDuration(2);
        inv.invokeFpvdroneSetInterpolationDuration(2);
        inv.invokeFpvdroneSetViewRange(viewRange);
        if (glowing || this.bright) {
            // Полная яркость — дрон хорошо виден ночью и в пещерах.
            inv.invokeFpvdroneSetBrightness(new Brightness(15, 15));
        }
        if (this.glow) {
            d.setGlowingTag(true);
            inv.invokeFpvdroneSetGlowColor(RGB[(this.colors[C_LED] & 0xFF) % RGB.length]);
        }
        inv.invokeFpvdroneSetTransformation(new Transformation(new Matrix4f().set(Mat4.scale(TINY, TINY, TINY).m)));
        DroneServer.registerPart(d, this.owner);
        this.level.addFreshEntity(d);
        this.all.add(d);
        return d;
    }

    public void setColors(byte[] colors) {
        this.colors = colors;
        setBlock(this.frame, solid(colors[C_FRAME]));
        setBlock(this.battery, solid(colors[C_BATTERY]));
        setBlock(this.armA, solid(colors[C_ARMS]));
        setBlock(this.armB, solid(colors[C_ARMS]));
        for (int i = 0; i < 4; i++) {
            setBlock(this.motors[i], solid(colors[C_MOTORS]));
            this.propIsGlass[i] = !this.propIsGlass[i]; // заставит обновить блок пропеллера в update()
        }
        setBlock(this.camera, solid(colors[C_CAMERA]));
        setBlock(this.led, solid(colors[C_LED]));
        setBlock(this.ledFront, solid(colors[C_LED]));
        if (this.glow) {
            for (Display.BlockDisplay d : this.all) {
                ((DisplayInvoker) d).invokeFpvdroneSetGlowColor(RGB[(colors[C_LED] & 0xFF) % RGB.length]);
            }
        }
    }

    public void update(RemoteState s) {
        Mat4 root = Mat4.rotation(s.qx, s.qy, s.qz, s.qw);
        double k = this.scale;
        this.apply(this.frame, s, root.mul(box(0, 0, 0, 0.07, 0.025, 0.13, null, k)));
        this.apply(this.battery, s, root.mul(box(0, 0.03, -0.015, 0.045, 0.035, 0.075, null, k)));
        this.apply(this.armA, s, root.mul(box(0, 0, 0, 0.022, 0.012, 0.31, Mat4.rotationY(Math.toRadians(45)), k)));
        this.apply(this.armB, s, root.mul(box(0, 0, 0, 0.022, 0.012, 0.31, Mat4.rotationY(Math.toRadians(-45)), k)));

        boolean spinning = s.armed && !s.crashed;
        for (int i = 0; i < 4; i++) {
            double[] p = MOTORS[i];
            Mat4 motorM = s.arm[i] > 0.0F ? box(p[0], p[1] + 0.012, p[2], 0.028, 0.024, 0.028, null, k) : Mat4.scale(TINY, TINY, TINY);
            this.apply(this.motors[i], s, root.mul(motorM));

            float health = Math.min(s.prop[i], s.arm[i]);
            Mat4 propM;
            if (health <= 0.0F) {
                propM = Mat4.scale(TINY, TINY, TINY);
            } else {
                double size = 0.13 * (0.55 + 0.45 * health);
                if (spinning) {
                    this.propAngle[i] += SPIN_DIR[i] * 37.0F;
                    propM = box(p[0], p[1] + 0.03, p[2], size, 0.003, size, Mat4.rotationY(Math.toRadians(this.propAngle[i])), k);
                } else {
                    propM = box(p[0], p[1] + 0.03, p[2], size, 0.005, 0.018, Mat4.rotationY(Math.toRadians(this.propAngle[i])), k);
                }
            }
            if (this.propIsGlass[i] != spinning) {
                setBlock(this.props[i], spinning ? glass(this.colors[C_PROPS]) : solid(this.colors[C_PROPS]));
                this.propIsGlass[i] = spinning;
            }
            this.apply(this.props[i], s, root.mul(propM));
        }

        Mat4 camTilt = Mat4.translation(0, 0.025 * k, 0.07 * k).mul(Mat4.rotationX(Math.toRadians(-s.cameraAngleDeg)));
        this.apply(this.camera, s, root.mul(camTilt).mul(box(0, 0, 0, 0.03, 0.03, 0.025, null, k)));
        this.apply(this.lens, s, root.mul(camTilt).mul(box(0, 0, 0.0155, 0.017, 0.017, 0.006, null, k)));
        // Светодиоды: широкая полоса сзади и полоса под носом — заметно в темноте.
        this.apply(this.led, s, root.mul(box(0, 0.004, -0.075, 0.07, 0.016, 0.012, null, k)));
        this.apply(this.ledFront, s, root.mul(box(0, -0.016, 0.03, 0.05, 0.008, 0.07, null, k)));
    }

    private static Mat4 box(double cx, double cy, double cz, double sx, double sy, double sz, Mat4 rotation, double k) {
        Mat4 m = Mat4.translation(cx * k, cy * k, cz * k);
        if (rotation != null) {
            m = m.mul(rotation);
        }
        return m.mul(Mat4.scale(sx * k, sy * k, sz * k)).mul(Mat4.translation(-0.5, -0.5, -0.5));
    }

    private void apply(Display.BlockDisplay d, RemoteState s, Mat4 matrix) {
        if (d.isRemoved()) {
            return;
        }
        d.setPos(s.x, s.y, s.z);
        DisplayInvoker inv = (DisplayInvoker) d;
        inv.invokeFpvdroneSetInterpolationDelay(0);
        inv.invokeFpvdroneSetTransformation(new Transformation(new Matrix4f().set(matrix.m)));
    }

    public boolean isValid() {
        for (Display.BlockDisplay d : this.all) {
            if (d.isRemoved()) {
                return false;
            }
        }
        return true;
    }

    public void remove() {
        for (Display.BlockDisplay d : this.all) {
            DroneServer.unregisterPart(d);
            if (!d.isRemoved()) {
                d.discard();
            }
        }
        this.all.clear();
    }

    // ------------------------------------------------------------------ блоки по цветам

    private static void setBlock(Display.BlockDisplay d, BlockState state) {
        ((BlockDisplayInvoker) d).invokeFpvdroneSetBlockState(state);
    }

    private static String colorName(byte index) {
        int i = index & 0xFF;
        return COLOR_NAMES[i < COLOR_NAMES.length ? i : 3];
    }

    static BlockState solid(byte color) {
        return McLookup.block(colorName(color).toLowerCase(Locale.ROOT) + "_concrete").defaultBlockState();
    }

    static BlockState glass(byte color) {
        return McLookup.block(colorName(color).toLowerCase(Locale.ROOT) + "_stained_glass").defaultBlockState();
    }
}
