import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** 验证生产路径：deriveTrailFactors(k, smooth, slope) 的斜度 0 是否保留拉伸。 */
public final class ZzSlopeProd {
    static final double W = 2.0, H = 32.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};

    public static void main(String[] a) {
        System.out.println("生产路径 deriveTrailFactors(k=4.5, smooth=0, slope=X)");
        System.out.println("  斜度   四角系数                        拉伸px    斜px    平均");
        System.out.println("  --------------------------------------------------------------------");
        for (int slope : new int[]{0, 25, 50, 82, 100}) {
            float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f, slope);
            double[] r = measure(f);
            double avg = (f[0]+f[1]+f[2]+f[3])/4.0;
            String flag = (slope == 0 && r[0] > 5) ? "  ← 拉伸保留 ✓" : "";
            System.out.printf("  %-6d %-30s %-9.2f %-7.2f %.3f%s%n", slope,
                    String.format("%.2f/%.2f/%.2f/%.2f", f[0], f[1], f[2], f[3]),
                    r[0], r[1], avg, flag);
        }
        System.out.println();
        System.out.println("默认（slope=82）：" + fmt(NeovideCursorSettings.deriveTrailFactors(4.5f, 0f, 82)));
        System.out.println("上游 k=4.5      ：" + fmt(NeovideCursorSettings.deriveTrailFactors(4.5f, 0f)));
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
        double cx = 100, cy = 100, dx = 12;
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
        double ms = 0, mn = 0;
        for (int fr = 0; fr < 300; fr++) {
            boolean any = false;
            for (int i = 0; i < 4; i++) any |= cs[i].update(W, H, cx + dx, cy, 0.008, false);
            double minX = 1e9, maxX = -1e9;
            for (int i = 0; i < 4; i++) {
                minX = Math.min(minX, cs[i].getCurrentX());
                maxX = Math.max(maxX, cs[i].getCurrentX());
            }
            ms = Math.max(ms, (maxX - minX) - W);
            mn = Math.max(mn, Math.max(Math.abs(cs[0].getCurrentX()-cs[3].getCurrentX()),
                    Math.abs(cs[1].getCurrentX()-cs[2].getCurrentX())));
            if (!any) break;
        }
        return new double[]{ms, mn};
    }
}