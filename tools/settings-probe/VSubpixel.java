import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;

/**
 * V-SUBPIXEL —— 亚像素坐标（0.9.9 抖动修复）。
 *
 * <h2>为什么这条探针必须存在</h2>
 *
 * <p>用户报「仍然抖动，1.2 都没大作用，我甚至关闭了辉光」。关掉辉光仍抖 →
 * 推翻了「辉光导致抖动」的假设。真因是<b>顶点坐标被取整</b>：
 * <pre>
 *   弹簧算出 100.37 / 100.82 / 101.15
 *   Math.round → 100 / 101 / 101
 *   → 每个角点每帧在 0.5px 门槛上横跳，四个角各自跳 → 「形状在抖」
 * </pre>
 *
 * <p>本探针断言的是<b>机械特征</b>，不是观感：
 * 亚像素输入必须原样读回；连续递进的输入必须产生<b>全不相等</b>的读数
 * （旧 int 实现会出现相等 —— 那正是抖动的可测量形态）。
 */
public final class VSubpixel {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-SUBPIXEL：顶点坐标必须保留亚像素精度 ===");
        // 本探针只测【坐标容器】，完全不碰平台服务，所以不需要 V0Env.install()。
        // CaretTrailPanel 需要一个 config（读 useShadow/glowLayers 等），给个默认值即可。
        CaretTrailPanel panel = new CaretTrailPanel(new NeovideConfig());

        // ---------------- P-1a：写入 100.37 必须原样读回 ----------------
        panel.updateCorners(100.37, 50.82,
                102.37, 50.82,
                102.37, 82.15,
                100.37, 82.15);
        double[] c1 = panel.cornersForProbe();
        check("P-1a 写入 100.37 原样读回（不是 100）",
                Math.abs(c1[0] - 100.37) < 1e-9,
                "读回=" + c1[0]);

        // ---------------- P-1b：坐标确实带小数 ----------------
        boolean hasFraction = false;
        for (double v : c1) {
            if (Math.abs(v - Math.rint(v)) > 1e-9) {
                hasFraction = true;
                break;
            }
        }
        check("P-1b 四个角点里至少有一个带小数（未被取整）", hasFraction,
                java.util.Arrays.toString(c1));

        // ---------------- P-2：连续递进输入 → 读数全不相等 ----------------
        // 这正是抖动的机械特征：int 实现下 100.10 与 100.37 都 round 成 100，
        // 相邻两次读数相等 = 顶点卡住 = 视觉上跳一下。
        double[] samples = {100.10, 100.37, 100.62, 100.88, 101.15, 101.40};
        double[] got = new double[samples.length];
        for (int i = 0; i < samples.length; i++) {
            panel.updateCorners(samples[i], 50.0, samples[i] + 2, 50.0,
                    samples[i] + 2, 82.0, samples[i], 82.0);
            got[i] = panel.cornersForProbe()[0];
        }
        int dup = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < got.length; i++) {
            sb.append(String.format(java.util.Locale.ROOT, "%.2f ", got[i]));
            if (Math.abs(got[i] - got[i - 1]) < 1e-9) {
                dup++;
            }
        }
        check("P-2 六次递进输入的相邻读数全不相等（旧的 int 实现会出现相等）",
                dup == 0, "读数=" + sb + "重复对=" + dup);

        // ---------------- P-3：改 double 后外扩方向向量仍有效 ----------------
        panel.updateCorners(100.0, 50.0, 102.0, 50.0, 102.0, 82.0, 100.0, 82.0);
        double[] dir = panel.offsetDirectionsForProbe();
        // 【断言口径必须用真实几何，不能凭直觉】第一版我断言「模长 = 1」，错了：
        // computeOffsetDirections 求的是【两个相邻边的外法线之和】（角平分线方向），
        // 直角处两单位向量正交 → |n1+n2| = √2 ≈ 1.414，这是矩形的正确结果。
        // 若真得到 1.0，反而说明只取了单边法线（外扩方向会偏）。
        boolean dirOk = true;
        StringBuilder lens = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            double len = Math.hypot(dir[i], dir[4 + i]);
            lens.append(String.format(java.util.Locale.ROOT, "%.4f ", len));
            if (Math.abs(len - Math.sqrt(2)) > 1e-6) {
                dirOk = false;
            }
        }
        check("P-3 矩形四角的外扩方向 = 相邻两边法线之和（模长 √2，几何未退化）", dirOk,
                "模长=" + lens);

        System.out.println();
        System.out.printf("=== V-SUBPIXEL 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-SUBPIXEL RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-SUBPIXEL RESULT: ALL PASS");
        System.exit(0);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + "   [" + detail + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}
