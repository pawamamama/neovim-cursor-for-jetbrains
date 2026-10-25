import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** 如果给「斜度」一个独立参数，能推到多斜？会不会失真？ */
public final class BenchSlopeRange {
    static final double W = 2.0, H = 32.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};

    public static void main(String[] a) {
        System.out.println("模拟「斜度」独立参数：直接放大 trailFactors 的梯度差");
        System.out.println("slope=0 -> 四角等速（无斜）  slope=100 -> 极端梯度");
        System.out.println("--------------------------------------------------------------------------");
        System.out.printf("%-10s %-26s %-10s %-8s %s%n", "斜度", "四角系数", "峰值斜px", "倾角", "观感");
        System.out.println("--------------------------------------------------------------------------");
        for (int slope : new int[]{0, 25, 50, 75, 100}) {
            float[] f = slopeFactors(slope);
            double[] r = measure(f);
            String feel = slope == 0 ? "纯矩形"
                    : slope <= 25 ? "轻微倾斜"
                    : slope <= 50 ? "明显斜缎带"
                    : slope <= 75 ? "很斜" : "极端，可能失真";
            System.out.printf("%-10d %-26s %-10.2f %-8.1f %s%n", slope,
                    String.format("%.2f/%.2f/%.2f/%.2f", f[0], f[1], f[2], f[3]),
                    r[0], r[1], feel);
        }
    }

    /** slope 0..100 映射到四角系数梯度 1.0 -> 0.22（现有 k=4.5 的值）*/ 
    private static float[] slopeFactors(int slope) {
        float t = slope / 100f;
        // 最短角从 1.0（无梯度）线性降到 0.05（极端）
        float minFac = 1.0f - t * 0.95f;
        // 四角在 1.0 与 minFac 之间按 {1, 0.72, 0.42, 0.22} 的形状分配
        float[] shape = {1.0f, 0.72f, 0.42f, 0.22f};
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            out[i] = 1.0f - (1.0f - minFac) * (1.0f - shape[i]) / 0.78f;
        }
        return out;
    }

    private static double[] measure(float[] factors) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.trailFactors = factors;
        cfg.shortAnimationLength = 0.11f;
        cfg.typingMaxChars = 3f;
        NeoidePlaceholder();
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
        double maxSlope = 0;
        for (int fr = 0; fr < 200; fr++) {
            boolean any = false;
            for (int i = 0; i < 4; i++) any |= cs[i].update(W, H, cx + dx, cy, 0.008, false);
            maxSlope = Math.max(maxSlope, Math.max(
                    Math.abs(cs[0].getCurrentX() - cs[3].getCurrentX()),
                    Math.abs(cs[1].getCurrentX() - cs[2].getCurrentX())));
            if (!any) break;
        }
        return new double[]{maxSlope, Math.toDegrees(Math.atan(maxSlope / H))};
    }

    private static void NeoidePlaceholder() {}
}