import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** 量化：斜度参数对「拉伸长度」和「倾斜」各自的贡献（当前实现）。 */
public final class ZzSlopeEffect {
    static final double W = 2.0, H = 32.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};

    public static void main(String[] a) {
        System.out.println("单行打字右移一字符。拉伸 = 动画过程中四边形最大高度超出原高；斜度 = 同侧上下角最大 X 差");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("%-8s %-26s %-12s %-12s %s%n", "斜度", "四角系数", "最大拉伸px", "最大斜度px", "观感");
        System.out.println("--------------------------------------------------------------------------------");
        for (int slope : new int[]{0, 25, 50, 82, 100}) {
            float[] f = NeovideCursorSettings.deriveTrailFactorsFromSlope(slope);
            double[] r = measure(f);
            String feel = r[0] < 0.5 ? "★无拉伸" : (r[1] < 0.5 ? "有拉伸但无斜" : "拉伸+斜");
            System.out.printf("%-8d %-26s %-12.2f %-12.2f %s%n", slope,
                    String.format("%.2f/%.2f/%.2f/%.2f", f[0], f[1], f[2], f[3]),
                    r[0], r[1], feel);
        }
        System.out.println();
        System.out.println("=== 对照：上游 k=4.5 的系数（斜度 82 声称要对齐它）===");
        float[] up = NeovideCursorSettings.deriveTrailFactors(4.5f);
        double[] ru = measure(up);
        System.out.printf("  上游系数 %.2f/%.2f/%.2f/%.2f  -> 拉伸 %.2fpx  斜度 %.2fpx%n",
                up[0], up[1], up[2], up[3], ru[0], ru[1]);
    }

    /** @return {最大拉伸, 最大斜度} */
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
            double topAvg = (cs[0].getCurrentY() + cs[1].getCurrentY()) / 2;
            double botAvg = (cs[2].getCurrentY() + cs[3].getCurrentY()) / 2;
            maxStretch = Math.max(maxStretch, Math.abs(botAvg - topAvg));
            maxSlant = Math.max(maxSlant, Math.max(
                    Math.abs(cs[0].getCurrentX() - cs[3].getCurrentX()),
                    Math.abs(cs[1].getCurrentX() - cs[2].getCurrentX())));
            if (!any) break;
        }
        return new double[]{Math.max(0, maxStretch - H), maxSlant};
    }
}