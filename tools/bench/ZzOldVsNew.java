import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** 对比：修复前（rank 塌缩 0 2 2 0） vs 修复后（0 2 3 1），量拉伸与斜度。 */
public final class ZzOldVsNew {
    static final double W = 2.0, H = 32.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};

    public static void main(String[] a) {
        System.out.println("单行打字右移一字符（光标 2x32，x0.77 修复前后对比）");
        System.out.println("  拉伸 = 动画过程中 X 总跨度 - 原宽 2px");
        System.out.println("  斜度 = 同侧上下角最大 X 差");
        System.out.println("---------------------------------------------------------------------------------");
        System.out.printf("%-28s %-16s %-12s %-12s %s%n", "实现", "rank", "拉伸px", "斜px", "形状");
        System.out.println("---------------------------------------------------------------------------------");

        float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f);   // 默认 k=4.5
        System.out.printf("%-28s %-16s %-12.2f %-12.2f %s%n", "x0.76 及以前（塌缩）", "0 2 2 0",
                measure(f, true)[0], measure(f, true)[1], "规整矩形平移（有拉伸无斜）");
        System.out.printf("%-28s %-16s %-12.2f %-12.2f %s%n", "x0.77 起（修复）", "0 2 3 1",
                measure(f, false)[0], measure(f, false)[1], "平行四边形（有拉伸有斜）");
        System.out.println();

        System.out.println("=== 现在「斜度」参数各档的拉伸/斜度 ===");
        System.out.printf("%-10s %-24s %-12s %s%n", "斜度", "四角系数", "拉伸px", "斜px");
        for (int slope : new int[]{0, 25, 50, 82}) {
            float[] sf = NeovideCursorSettings.deriveTrailFactorsFromSlope(slope);
            double[] r = measure(sf, false);
            System.out.printf("%-10d %-24s %-12.2f %-12.2f%n", slope,
                    String.format("%.2f/%.2f/%.2f/%.2f", sf[0], sf[1], sf[2], sf[3]), r[0], r[1]);
        }
    }

    /** @param collapse true = 用旧的「计数」算法（会塌缩） */
    private static double[] measure(float[] factors, boolean collapse) {
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

        int[] rank = new int[4];
        if (collapse) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    if (al[j] < al[i]) rank[i]++;
                }
            }
        } else {
            int[] order = {0,1,2,3};
            for (int i = 1; i < 4; i++) {
                int key = order[i], j = i - 1;
                while (j >= 0 && al[order[j]] > al[key]) { order[j+1] = order[j]; j--; }
                order[j+1] = key;
            }
            for (int k = 0; k < 4; k++) rank[order[k]] = k;
        }
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