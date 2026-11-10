package ru.fpvdrone.plugin;

/**
 * Маленькая матрица 4x4 (column-major, как в JOML), чтобы собирать трансформации частей дрона.
 */
final class Mat4 {
    final float[] m = new float[16];

    static Mat4 identity() {
        Mat4 r = new Mat4();
        r.m[0] = 1.0F;
        r.m[5] = 1.0F;
        r.m[10] = 1.0F;
        r.m[15] = 1.0F;
        return r;
    }

    static Mat4 translation(double x, double y, double z) {
        Mat4 r = identity();
        r.m[12] = (float) x;
        r.m[13] = (float) y;
        r.m[14] = (float) z;
        return r;
    }

    static Mat4 scale(double x, double y, double z) {
        Mat4 r = new Mat4();
        r.m[0] = (float) x;
        r.m[5] = (float) y;
        r.m[10] = (float) z;
        r.m[15] = 1.0F;
        return r;
    }

    /** Поворот из единичного кватерниона (x, y, z, w). */
    static Mat4 rotation(double x, double y, double z, double w) {
        Mat4 r = identity();
        double xx = x * x;
        double yy = y * y;
        double zz = z * z;
        double xy = x * y;
        double xz = x * z;
        double yz = y * z;
        double wx = w * x;
        double wy = w * y;
        double wz = w * z;
        r.m[0] = (float) (1.0 - 2.0 * (yy + zz));
        r.m[1] = (float) (2.0 * (xy + wz));
        r.m[2] = (float) (2.0 * (xz - wy));
        r.m[4] = (float) (2.0 * (xy - wz));
        r.m[5] = (float) (1.0 - 2.0 * (xx + zz));
        r.m[6] = (float) (2.0 * (yz + wx));
        r.m[8] = (float) (2.0 * (xz + wy));
        r.m[9] = (float) (2.0 * (yz - wx));
        r.m[10] = (float) (1.0 - 2.0 * (xx + yy));
        return r;
    }

    static Mat4 rotationX(double radians) {
        double half = radians * 0.5;
        return rotation(Math.sin(half), 0.0, 0.0, Math.cos(half));
    }

    static Mat4 rotationY(double radians) {
        double half = radians * 0.5;
        return rotation(0.0, Math.sin(half), 0.0, Math.cos(half));
    }

    static Mat4 rotationZ(double radians) {
        double half = radians * 0.5;
        return rotation(0.0, 0.0, Math.sin(half), Math.cos(half));
    }

    /** this * other */
    Mat4 mul(Mat4 other) {
        Mat4 r = new Mat4();
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0F;
                for (int k = 0; k < 4; k++) {
                    sum += this.m[k * 4 + row] * other.m[col * 4 + k];
                }
                r.m[col * 4 + row] = sum;
            }
        }
        return r;
    }

    /** Повернуть вектор (x, y, z) этой матрицей без переноса. */
    double[] transformDirection(double x, double y, double z) {
        return new double[] {
            this.m[0] * x + this.m[4] * y + this.m[8] * z,
            this.m[1] * x + this.m[5] * y + this.m[9] * z,
            this.m[2] * x + this.m[6] * y + this.m[10] * z
        };
    }
}
