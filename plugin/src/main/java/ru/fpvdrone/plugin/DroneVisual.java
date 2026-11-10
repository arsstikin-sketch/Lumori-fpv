package ru.fpvdrone.plugin;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix4f;

/**
 * Модель дрона из BlockDisplay-сущностей. Их видят все игроки, даже без мода.
 * Все части стоят в одной точке (позиция дрона), а форму и наклон задаёт матрица трансформации.
 */
public final class DroneVisual implements SkinVisual {
    /** Сглаживание движения, тиков (меньше — меньше отставание, но дёрганее). */
    static int smoothTicks = 1;

    /** Моторы в локальных координатах дрона (метры): +X влево, +Z вперёд. Порядок FL, FR, RL, RR. */
    private static final double[][] MOTORS = {
        {0.11, 0.0, 0.11},
        {-0.11, 0.0, 0.11},
        {0.11, 0.0, -0.11},
        {-0.11, 0.0, -0.11}
    };
    private static final int[] SPIN_DIR = {1, -1, -1, 1};
    private static final double TINY = 1.0E-4;

    private final Plugin plugin;
    private final float scale;
    private DroneColors colors;

    private final List<BlockDisplay> all = new ArrayList<>();
    private BlockDisplay frame;
    private BlockDisplay battery;
    private BlockDisplay armA;
    private BlockDisplay armB;
    private final BlockDisplay[] motors = new BlockDisplay[4];
    private final BlockDisplay[] props = new BlockDisplay[4];
    private BlockDisplay camera;
    private BlockDisplay lens;
    private BlockDisplay led;
    private BlockDisplay ledFront;
    private final boolean bright;
    private final boolean glow;

    private final float[] propAngle = new float[4];
    private final Material[] currentPropMaterial = new Material[4];

    public DroneVisual(Plugin plugin, Location location, DroneColors colors, float scale, Player hideFrom, float viewRange,
                       boolean bright, boolean glow) {
        this.plugin = plugin;
        this.colors = colors;
        this.scale = scale;
        this.bright = bright;
        this.glow = glow;
        World world = location.getWorld();
        Location spawnAt = new Location(world, location.getX(), location.getY(), location.getZ(), 0.0F, 0.0F);

        this.frame = this.spawn(spawnAt, colors.solid(DroneColors.Part.FRAME), hideFrom, viewRange, false);
        this.battery = this.spawn(spawnAt, colors.solid(DroneColors.Part.BATTERY), hideFrom, viewRange, false);
        this.armA = this.spawn(spawnAt, colors.solid(DroneColors.Part.ARMS), hideFrom, viewRange, false);
        this.armB = this.spawn(spawnAt, colors.solid(DroneColors.Part.ARMS), hideFrom, viewRange, false);
        for (int i = 0; i < 4; i++) {
            this.motors[i] = this.spawn(spawnAt, colors.solid(DroneColors.Part.MOTORS), hideFrom, viewRange, false);
            this.currentPropMaterial[i] = colors.solid(DroneColors.Part.PROPS);
            this.props[i] = this.spawn(spawnAt, this.currentPropMaterial[i], hideFrom, viewRange, false);
            this.propAngle[i] = i * 45.0F;
        }
        this.camera = this.spawn(spawnAt, colors.solid(DroneColors.Part.CAMERA), hideFrom, viewRange, false);
        this.lens = this.spawn(spawnAt, Material.LIGHT_BLUE_STAINED_GLASS, hideFrom, viewRange, false);
        this.led = this.spawn(spawnAt, colors.solid(DroneColors.Part.LED), hideFrom, viewRange, true);
        this.ledFront = this.spawn(spawnAt, colors.solid(DroneColors.Part.LED), hideFrom, viewRange, true);
    }

    private BlockDisplay spawn(Location at, Material material, Player hideFrom, float viewRange, boolean glowing) {
        BlockDisplay display = at.getWorld().spawn(at, BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.setBlock(material.createBlockData());
            d.setTeleportDuration(smoothTicks);
            d.setInterpolationDuration(smoothTicks);
            d.setInterpolationDelay(0);
            d.setViewRange(viewRange);
            d.setShadowRadius(0.0F);
            if (glowing || this.bright) {
                d.setBrightness(new Display.Brightness(15, 15));
            }
            if (this.glow) {
                d.setGlowing(true);
                d.setGlowColorOverride(org.bukkit.Color.fromRGB(DroneColors.rgb(this.colors.get(DroneColors.Part.LED))));
            }
            d.setTransformationMatrix(new Matrix4f().set(Mat4.scale(TINY, TINY, TINY).m));
        });
        if (hideFrom != null) {
            hideFrom.hideEntity(this.plugin, display);
        }
        this.all.add(display);
        return display;
    }

    @Override
    public void setColors(DroneColors colors) {
        this.colors = colors;
        this.frame.setBlock(colors.solid(DroneColors.Part.FRAME).createBlockData());
        this.battery.setBlock(colors.solid(DroneColors.Part.BATTERY).createBlockData());
        this.armA.setBlock(colors.solid(DroneColors.Part.ARMS).createBlockData());
        this.armB.setBlock(colors.solid(DroneColors.Part.ARMS).createBlockData());
        for (int i = 0; i < 4; i++) {
            this.motors[i].setBlock(colors.solid(DroneColors.Part.MOTORS).createBlockData());
            this.currentPropMaterial[i] = null; // обновится в update()
        }
        this.camera.setBlock(colors.solid(DroneColors.Part.CAMERA).createBlockData());
        this.led.setBlock(colors.solid(DroneColors.Part.LED).createBlockData());
        this.ledFront.setBlock(colors.solid(DroneColors.Part.LED).createBlockData());
        if (this.glow) {
            for (BlockDisplay d : this.all) {
                d.setGlowColorOverride(org.bukkit.Color.fromRGB(DroneColors.rgb(colors.get(DroneColors.Part.LED))));
            }
        }
    }

    /** Переместить и повернуть модель по состоянию дрона. */
    @Override
    public void update(World world, DroneState s) {
        Location location = new Location(world, s.x, s.y, s.z, 0.0F, 0.0F);
        Mat4 root = Mat4.rotation(s.qx, s.qy, s.qz, s.qw);
        double k = this.scale;

        this.apply(this.frame, location, root.mul(box(0, 0, 0, 0.07, 0.025, 0.13, null, k)));
        this.apply(this.battery, location, root.mul(box(0, 0.03, -0.015, 0.045, 0.035, 0.075, null, k)));
        this.apply(this.armA, location, root.mul(box(0, 0, 0, 0.022, 0.012, 0.31, Mat4.rotationY(Math.toRadians(45)), k)));
        this.apply(this.armB, location, root.mul(box(0, 0, 0, 0.022, 0.012, 0.31, Mat4.rotationY(Math.toRadians(-45)), k)));

        boolean spinning = s.armed && !s.crashed;
        Material propMaterial = spinning ? this.colors.glass(DroneColors.Part.PROPS) : this.colors.solid(DroneColors.Part.PROPS);
        for (int i = 0; i < 4; i++) {
            double[] p = MOTORS[i];
            boolean motorAlive = s.arm[i] > 0.0F;
            Mat4 motorM = motorAlive
                ? box(p[0], p[1] + 0.012, p[2], 0.028, 0.024, 0.028, null, k)
                : Mat4.scale(TINY, TINY, TINY);
            this.apply(this.motors[i], location, root.mul(motorM));

            Mat4 propM;
            float health = Math.min(s.prop[i], s.arm[i]);
            if (health <= 0.0F) {
                propM = Mat4.scale(TINY, TINY, TINY);
            } else {
                double size = 0.13 * (0.55 + 0.45 * health); // побитый пропеллер — короче
                if (spinning) {
                    this.propAngle[i] += SPIN_DIR[i] * 37.0F;
                    propM = box(p[0], p[1] + 0.03, p[2], size, 0.003, size, Mat4.rotationY(Math.toRadians(this.propAngle[i])), k);
                } else {
                    propM = box(p[0], p[1] + 0.03, p[2], size, 0.005, 0.018, Mat4.rotationY(Math.toRadians(this.propAngle[i])), k);
                }
            }
            if (this.currentPropMaterial[i] != propMaterial) {
                this.props[i].setBlock(propMaterial.createBlockData());
                this.currentPropMaterial[i] = propMaterial;
            }
            this.apply(this.props[i], location, root.mul(propM));
        }

        // Камера наклонена вверх на cameraAngleDeg (вращение вокруг локальной оси X).
        Mat4 camTilt = Mat4.translation(0, 0.025 * k, 0.07 * k).mul(Mat4.rotationX(Math.toRadians(-s.cameraAngleDeg)));
        this.apply(this.camera, location, root.mul(camTilt).mul(box(0, 0, 0, 0.03, 0.03, 0.025, null, k)));
        this.apply(this.lens, location, root.mul(camTilt).mul(box(0, 0, 0.0155, 0.017, 0.017, 0.006, null, k)));

        this.apply(this.led, location, root.mul(box(0, 0.004, -0.075, 0.07, 0.016, 0.012, null, k)));
        this.apply(this.ledFront, location, root.mul(box(0, -0.016, 0.03, 0.05, 0.008, 0.07, null, k)));
    }

    /**
     * Матрица для коробки: центр (cx, cy, cz), размер (sx, sy, sz) в метрах, доп. поворот вокруг центра.
     * BlockDisplay рисует блок от (0,0,0) до (1,1,1), поэтому сначала сдвигаем его центр в ноль.
     */
    private static Mat4 box(double cx, double cy, double cz, double sx, double sy, double sz, Mat4 rotation, double k) {
        Mat4 m = Mat4.translation(cx * k, cy * k, cz * k);
        if (rotation != null) {
            m = m.mul(rotation);
        }
        return m.mul(Mat4.scale(sx * k, sy * k, sz * k)).mul(Mat4.translation(-0.5, -0.5, -0.5));
    }

    private void apply(BlockDisplay display, Location location, Mat4 matrix) {
        if (!display.isValid()) {
            return;
        }
        display.teleport(location);
        display.setInterpolationDelay(0);
        display.setTransformationMatrix(new Matrix4f().set(matrix.m));
    }

    @Override
    public void setVisibleFor(Plugin plugin, Player player, boolean visible) {
        for (BlockDisplay d : this.all) {
            if (visible) {
                player.showEntity(plugin, d);
            } else {
                player.hideEntity(plugin, d);
            }
        }
    }

    @Override
    public boolean isValid() {
        for (BlockDisplay display : this.all) {
            if (!display.isValid()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void remove() {
        for (BlockDisplay display : this.all) {
            if (display.isValid()) {
                display.remove();
            }
        }
        this.all.clear();
    }
}
