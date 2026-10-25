import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** 正确公式：slope=0 纯矩形（同侧两角取平均），slope=100 完全斜（= k 原值）。 */
public final class ZzSlopeCorrect {
    static final double W = 2.0, H = 32.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};

    /** @param t 0=纯矩形（同侧平均） 1=完全斜（k 原值） */
    static float[] blend(float[] base, float t) {
        t = Math.max(0f, Math.min(1f, t));
        float[] rect = base.clone();
        // rank 空间同侧配对：(0,1) 与 (2,3)，取平均 -> 四角同侧等速 -> 纯矩形
        for (int[] p : new int[][]{{0,1},{2,3}}) {
            float avg = (base[p[0]] + base[p[1]]) / 2f;
            rect[p[0]] = avg;
            rect[p[1]] = avg;
        }
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            out[i] = rect[i] + (base[i] - rect[i]) * t;
        }
        return out;
    }

    public static void main(String[] a) {
        float[] base = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        System.out.println("k=4.5 基础（= 上游）：" + fmt(base));
        System.out.println();
        System.out.println("  斜度   四角系数                        拉伸px    斜px     形状");
        System.out.println("  -------------------------------------------------------------------------");
        for (int slope : new int[]{0, 25, 50, 75, 100}) {
            float[] f = blend(base, slope / 100f);
            double[] r = measure(f);
            String shape = r[1] < 0.1 ? "★纯矩形（有拉伸无斜）"
                    : (slope == 100 ? "完全斜（= 上游）" : "斜 " + String.format("%.1f", r[1]) + "px");
            System.out.printf("  %-6d %-30s %-9.2f %-8.2f %s%n", slope, fmt(f), r[0], r[1], shape);
        }
    }

    static String fmt(float[] f) { return String.format("%.2f/%.2f/%.2f/%.2f", f[0],f[1],f[2],f[3]); }

    static double[] measure(float[] factors) {
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
        for (int i = 0; i < 4; i++) al[i] = cs[i].calculateDirectionAlignment(W, H, cx+dx, cy);
        int[] order = {0,1,2,3};
        for (int i = 1; i < 4; i++) {
            int key = order[i], j = i-1;
            while (j >= 0 && al[order[j]] > al[key]) { order[j+1]=order[j]; j--; }
            order[j+1]=key;
        }
        int[] rank = new int[4];
        for (int k = 0; k < 4; k++) rank[order[k]] = k;
        for (int i = 0; i < 4; i++) cs[i].jump(W, H, cx+dx, cy, rank[i]);
        double ms=0, mn=0;
        for (int fr = 0; fr < 300; fr++) {
            boolean any=false;
            for (int i=0;i<4;i++) any |= cs[i].update(W,H,cx+dx,cy,0.008,false);
            double minX=1e9,maxX=-1e9;
            for (int i=0;i<4;i++){ minX=Math.min(minX,cs[i].getCurrentX()); maxX=Math.max(maxX,cs[i].getCurrentX()); }
            ms = Math.max(ms, (maxX-minX)-W);
            mn = Math.max(mn, Math.max(Math.abs(cs[0].getCurrentX()-cs[3].getCurrentX()),
                    Math.abs(cs[1].getCurrentX()-cs[2].getCurrentX())));
            if (!any) break;
        }
        return new double[]{ms, mn};
    }
}