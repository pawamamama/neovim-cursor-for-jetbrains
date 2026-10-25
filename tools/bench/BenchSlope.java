import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** 量化单行打字时「斜度」由谁控制。 */
public final class BenchSlope {
    static final double W = 2.0, H = 32.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};

    public static void main(String[] a) {
        System.out.println("单行打字右移一个字符，量四角时长与斜度");
        System.out.println("斜度 = 同一侧上下角在动画结束时刻的 X 差（px）");
        System.out.println("-------------------------------------------------------------------------");
        System.out.printf("%-34s %-22s %-10s %s%n", "参数", "四角时长ms TL/TR/BR/BL", "峰值斜度", "倾角");
        System.out.println("-------------------------------------------------------------------------");

        // 基线
        show("默认 k=4.5 smooth=0", 4.5f, 0f, 0.11f);
        // 拉伸强度
        show("k=8.0 smooth=0", 8.0f, 0f, 0.11f);
        show("k=1.0 smooth=0", 1.0f, 0f, 0.11f);
        // 输入平滑（压平梯度）
        show("k=4.5 smooth=0.5", 4.5f, 0.5f, 0.11f);
        show("k=4.5 smooth=1.0", 4.5f, 1.0f, 0.11f);
        // 短动画时长（整体时长，不改变比例）
        show("k=4.5 smooth=0 short=0.20", 4.5f, 0f, 0.20f);
        show("k=4.5 smooth=0 short=0.05", 4.5f, 0f, 0.05f);
        System.out.println();
        System.out.println("=== trailFactors 本身 ===");
        for (float k : new float[]{1.0f, 4.5f, 8.0f}) {
            float[] f = NeovideCursorSettings.deriveTrailFactors(k, 0f);
            System.out.printf("  k=%.1f -> [%.3f, %.3f, %.3f, %.3f]  梯度比 %.2fx%n",
                    k, f[0], f[1], f[2], f[3], f[0] / Math.max(f[3], 0.001f));
        }
    }

    private static void show(String label, float k, float smooth, float shortLen) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.shortAnimationLength = shortLen;
        cfg.trailFactors = NeovideCursorSettings.deriveTrailFactors(k, smooth);
        cfg.typingMaxChars = 3f;

        TrailCorner[] cs = new TrailCorner[4];
        double cx = 100, cy = 100, dx = 12.0;
        for (int i = 0; i < 4; i++) {
            cs[i] = new TrailCorner(REL[i][0], REL[i][1], cfg);
            cs[i].setCharsMoved(1);
            cs[i].snapTo(W, H, cx, cy);
        }
        double[] al = new double[4];
        for (int i = 0; i < 4; i++) al[i] = cs[i].calculateDirectionAlignment(W, H, cx + dx, cy);
        // 稳定排序 + 索引 tiebreak（与生产一致）
        int[] order = {0,1,2,3};
        for (int i = 1; i < 4; i++) {
            int key = order[i], j = i - 1;
            while (j >= 0 && al[order[j]] > al[key]) { order[j+1] = order[j]; j--; }
            order[j+1] = key;
        }
        int[] rank = new int[4];
        for (int kk = 0; kk < 4; kk++) rank[order[kk]] = kk;
        for (int i = 0; i < 4; i++) cs[i].jump(W, H, cx + dx, cy, rank[i]);

        double dt = 0.008;
        double maxSlope = 0;
        for (int frame = 0; frame < 200; frame++) {
            boolean any = false;
            for (int i = 0; i < 4; i++) {
                if (cs[i].update(W, H, cx + dx, cy, dt, false)) any = true;
            }
            double left = Math.abs(cs[0].getCurrentX() - cs[3].getCurrentX());
            double right = Math.abs(cs[1].getCurrentX() - cs[2].getCurrentX());
            maxSlope = Math.max(maxSlope, Math.max(left, right));
            if (!any) break;
        }
        // 四角时长（用于展示比例）
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            float fac = cfg.trailFactors[rank[i]];
            t.append(String.format("%.0f ", shortLen * 1000 * fac));
        }
        double angle = Math.toDegrees(Math.atan(maxSlope / H));
        System.out.printf("%-34s %-22s %-10.2f %.1f°%n", label, t.toString().trim(), maxSlope, angle);
    }
}