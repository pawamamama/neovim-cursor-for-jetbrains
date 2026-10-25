import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** 验证 C 方案：slope 只放大「同侧上下角的差」，不改平均值。 */
public final class ZzSlopeOverlay {
    static final double W = 2.0, H = 32.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};

    /** C 方案：在基础梯度上叠加斜度（放大同侧两角差，保持中点不变） */
    public static float[] withSlope(float[] base, int slope) {
        if (slope <= 0) {
            return base.clone();
        }
        float t = Math.min(slope, 100) / 100f;
        float[] out = base.clone();
        // 同侧配对：TL(0)-BL(3)，TR(1)-BR(2)
        //
        // 【关键：不能简单放大 half 再 clamp】
        // 放大后 out[3] 会撞到 0 被截断，继续放大只会把 out[0] 顶到 1，
        // 结果「斜度反而变小」（实测 75→8.13、100→6.73）。
        //
        // 正确做法：把**中点保持不变**，把 half 朝「极限差」插值 ——
        // 极限 = 中点能给出的最大差（小的一侧到 0，大的一侧到 2*mid，仍 ≤1）。
        // 【配对必须按 rank，不是按 index】
        //
        // 打字右移时 rank = {0, 2, 3, 1}（TL/TR/BR/BL），
        // 「斜」来自同侧上下两角时长不同，即：
        //   左边缘 = rank0（TL, 110ms）vs rank1（BL, 79ms）
        //   右边缘 = rank2（TR, 46ms）vs rank3（BR, 24ms）
        //
        // 所以要在 **rank 空间** 配对：(0,1) 与 (2,3)。
        for (int[] pair : new int[][]{{0, 1}, {2, 3}}) {
            int hi = pair[0], lo = pair[1];
            float mid = (base[hi] + base[lo]) / 2f;
            float half0 = (base[hi] - base[lo]) / 2f;
            // 极限 half：mid±half 必须落在 [0,1]
            float maxHalf = Math.min(1f - mid, mid);
            float half = half0 + (maxHalf - half0) * t;
            out[hi] = mid + half;
            out[lo] = mid - half;
        }
        return out;
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    public static void main(String[] a) {
        float[] base = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        System.out.println("基础梯度（k=4.5，= 上游原版）：" + fmt(base));
        System.out.println();
        System.out.println("  斜度   四角系数                        拉伸px    斜px    平均时长");
        System.out.println("  ----------------------------------------------------------------------");
        for (int slope : new int[]{0, 25, 50, 75, 100}) {
            float[] f = withSlope(base, slope);
            double[] r = measure(f);
            double avg = (f[0] + f[1] + f[2] + f[3]) / 4;
            System.out.printf("  %-6d %-30s %-9.2f %-7.2f %.3f%n", slope, fmt(f), r[0], r[1], avg);
        }
    }

    private static String fmt(float[] f) {
        return String.format("%.2f/%.2f/%.2f/%.2f", f[0], f[1], f[2], f[3]);
    }

    private static double[] measure(float[] factors) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.trailFactors = factors;
        cfg.shortAnimationLength = 0.11f;
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
        int[] order = {0,1,2,3};
        for (int i = 1; i < 4; i++) {
            int key = order[i], j = i - 1;
            while (j >= 0 && al[order[j]] > al[key]) { order[j+1] = order[j]; j--; }
            order[j+1] = key;
        }
        int[] rank = new int[4];
        for (int k = 0; k < 4; k++) rank[order[k]] = k;
        for (int i = 0; i < 4; i++) cs[i].jump(W, H, cx + dx, cy, rank[i]);
        double maxStretch = 0, maxSlant = 0;
        for (int fr = 0; fr < 300; fr++) {
            boolean any = false;
            for (int i = 0; i < 4; i++) any |= cs[i].update(W, H, cx + dx, cy, 0.008, false);
            double minX = 1e9, maxX = -1e9;
            for (int i = 0; i < 4; i++) {
                minX = Math.min(minX, cs[i].getCurrentX());
                maxX = Math.max(maxX, cs[i].getCurrentX());
            }
            maxStretch = Math.max(maxStretch, (maxX - minX) - W);
            maxSlant = Math.max(maxSlant, Math.max(
                    Math.abs(cs[0].getCurrentX() - cs[3].getCurrentX()),
                    Math.abs(cs[1].getCurrentX() - cs[2].getCurrentX())));
            if (!any) break;
        }
        return new double[]{maxStretch, maxSlant};
    }
}