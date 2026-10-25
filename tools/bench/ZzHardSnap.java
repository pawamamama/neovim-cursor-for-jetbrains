import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** 对比 hardSnap 开/关的单行打字效果。 */
public final class ZzHardSnap {
    static final double W = 2.0, H = 32.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};

    public static void main(String[] a) {
        System.out.println("单行打字右移（k=4.5、斜度 100）");
        System.out.println("  snap阈值  拉伸px   斜度px   形状");
        System.out.println("  --------------------------------------------------");
        for (float thr : new float[]{0f, 0.5f, 0.9f}) {
            double[] r = measure(thr);
            String shape = (thr == 0f) ? "对称（两侧一起收）"
                    : (r[1] > 2.5 ? "★半边（前缘咬死，后缘拖）" : "轻微");
            System.out.printf("  %-9s %-8.2f %-8.2f %s%n",
                    thr == 0f ? "关闭" : String.valueOf(thr), r[0], r[1], shape);
        }
    }

    static double[] measure(float thr) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.trailFactors = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f, 100);
        cfg.shortAnimationLength = 0.11f;
        cfg.typingMaxChars = 3f;
        cfg.animationResetThreshold = thr;
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