/** 反推：上游 trailFactors {1.0, 0.72, 0.42, 0.22} 对应我的 slope 参数是多少。 */
public final class ZzSlopeReverse {
    public static void main(String[] a) {
        // 我的公式（NeovideCursorSettings.deriveTrailFactorsFromSlope）
        //   minFac = 1.0 - t*0.95,  t = slope/100
        //   out[i] = 1.0 - (1.0-minFac) * (1.0-shape[i]) / 0.78
        //   shape  = {1.0, 0.72, 0.42, 0.22}
        // 要 out[3] == 0.22：
        //   1 - (1-minFac) * (1-0.22)/0.78 = 0.22
        //   1 - (1-minFac) = 0.22  =>  minFac = 0.22
        //   1 - t*0.95 = 0.22  =>  t = 0.821  =>  slope = 82.1
        System.out.println("反推结果：");
        for (double t : new double[]{0.80, 0.82, 0.82105, 0.85}) {
            double minFac = 1.0 - t * 0.95;
            double[] shape = {1.0, 0.72, 0.42, 0.22};
            System.out.printf("  slope=%.1f  minFac=%.4f  ->  [", t * 100, minFac);
            for (int i = 0; i < 4; i++) {
                double v = 1.0 - (1.0 - minFac) * (1.0 - shape[i]) / 0.78;
                System.out.printf("%.3f%s", v, i < 3 ? ", " : "");
            }
            System.out.println("]");
        }
        System.out.println();
        System.out.println("上游 k=4.5 = [1.000, 0.720, 0.420, 0.220]");
        System.out.println("=> 对齐值 slope = 82（四角系数逐位相同）");
    }
}