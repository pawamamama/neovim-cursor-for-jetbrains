import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** 修正指标：横向移动时，拉伸 = X 方向的形变（不是 Y）。 */
public final class ZzSlopeEffect2 {
    static final double W = 2.0, H = 32.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};

    public static void main(String[] a) {
        System.out.println("单行打字右移一字符（光标 2x32）");
        System.out.println("  拉伸 = 四角 X 总跨度 - 原宽（形变多大）");
        System.out.println("  斜度 = 同侧上下角 X 差（倾斜多少）");
        System.out.println("-------------------------------------------------------------------");
        System.out.printf("%-8s %-24s %-12s %-12s %s%n", "斜度", "四角系数", "拉伸px", "斜px", "观感");
        System.out.println("-------------------------------------------------------------------");
        for (int slope : new int[]{0, 25, 50, 82, 100}) {
            float[] f = NeovideCursorSettings.deriveTrailFactorsFromSlope(slope);
            double[] r = measure(f);
            String feel = (r[0] < 1.0 && r[1] < 0.5) ? "★有拉伸无斜（用户想要的）"
                    : (r[0] < 1.0 ? "有拉伸有斜" : "★完全无拉伸（bug）");
            System.out.printf("%-8d %-24s %-12.2f %-12.2f %s%n", slope,
                    String.format("%.2f/%.2f/%.2f/%.2f", f[0], f[1], f[2], f[3]),
                    r[0], r[1], feel);
        }
    }

    /** @return {X向拉伸, 斜度} */
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