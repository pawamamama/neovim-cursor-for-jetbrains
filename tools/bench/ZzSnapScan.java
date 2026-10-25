/** 逐步扫描阈值，找出真正产生「半边」效果的区间。 */
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

public final class ZzSnapScan {
    static final double W = 2.0, H = 32.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};

    public static void main(String[] a) {
        System.out.println("扫描对齐度阈值（打字右移，四角对齐度 -0.707/0.707/0.707/-0.707）");
        System.out.println("  阈值     吸附角数   拉伸px   斜度px   说明");
        System.out.println("  -------------------------------------------------------------------");
        for (float thr : new float[]{0f, 0.3f, 0.5f, 0.69f, 0.707f, 0.71f, 0.8f, 0.95f, 1.0f}) {
            Result r = measure(thr);
            String note = r.snapped == 0 ? "无吸附（对称）"
                    : r.snapped == 1 ? "★1 角吸附（半边）"
                    : r.snapped + " 角吸附";
            System.out.printf("  %-8s %-10d %-8.2f %-8.2f %s%n",
                    thr == 0f ? "关闭" : String.valueOf(thr), r.snapped, r.stretch, r.slant, note);
        }
    }

    static class Result { int snapped; double stretch, slant; }

    static Result measure(float thr) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.trailFactors = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f, 100);
        cfg.shortAnimationLength = 0.11f;
        cfg.typingMaxChars = 3f;
        cfg.animationResetThreshold = thr;
        double cx = 100, cy = 100, dx = 12;

        // 先数吸附角
        int snapped = 0;
        for (int i = 0; i < 4; i++) {
            TrailCorner c = new TrailCorner(REL[i][0], REL[i][1], cfg);
            c.snapTo(W, H, cx, cy);
            double al = c.calculateDirectionAlignment(W, H, cx+dx, cy);
            if (thr > 0 && al > thr) snapped++;
        }

        TrailCorner[] cs = new TrailCorner[4];
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
        Result r = new Result();
        r.snapped = snapped; r.stretch = ms; r.slant = mn;
        return r;
    }
}