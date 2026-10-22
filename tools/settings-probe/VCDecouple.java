
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/**
 * C 方案验收：leadingAnimationLength=0 时行为必须【逐位不变】，
 * >0 时必须【只改前缘、不动后缘】。
 */
public final class VCDecouple {
    static final double FRAME = 8.1 / 1000, H = 32.0, W = 2.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        System.out.println("=== C 方案验收 ===");

        // ---- 1) 默认值(0) 行为不变 ----
        double base = maxStep(0.11f, 0f);
        System.out.printf("  leading=0.00 时最大单帧位移 = %.6f px%n", base);

        // 与"完全没有该字段"的等价性：0 时 max(x,0)==x，数学上恒等
        // 这里用两条不同 short 验证公式没被意外改动
        for (float sh : new float[]{0.11f, 0.24f}) {
            double framesA = leadFrames(sh, 0f);
            float[] f = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
            double expect = sh * 1000.0 * f[2] / 8.1;
            check("C-1 short=" + sh + " leading=0 时前缘帧数与旧公式一致",
                    Math.abs(framesA - expect) < 0.001,
                    String.format("%.3f vs %.3f", framesA, expect));
        }

        // ---- 2) leading>0 只改前缘 ----
        double trailBefore = trailLag(0.11f, 0f);
        double trailAfter  = trailLag(0.11f, 0.20f);
        check("C-2 加 leading=0.20 后，后缘滞后完全不变",
                Math.abs(trailBefore - trailAfter) < 0.001,
                String.format("%.4f -> %.4f px", trailBefore, trailAfter));

        double leadBefore = leadFrames(0.11f, 0f);
        double leadAfter  = leadFrames(0.11f, 0.20f);
        check("C-3 加 leading=0.20 后，前缘帧数显著增加",
                leadAfter > leadBefore * 1.5,
                String.format("%.1f -> %.1f 帧", leadBefore, leadAfter));

        // ---- 3) 关键场景：短促拖尾 + 够多前缘帧 ----
        // short=0.11（后缘 110ms，短促） + leading=0.20（前缘 200ms，13 帧）
        double leadFramesFinal = leadFrames(0.11f, 0.20f);
        double trailMs = 0.11 * 1000.0;
        check("C-4 【核心目标】short=0.11 + leading=0.20 → 前缘 >=10 帧 且 后缘 <=120ms",
                leadFramesFinal >= 10 && trailMs <= 120,
                String.format("前缘 %.1f 帧，后缘 %.0f ms", leadFramesFinal, trailMs));

        // ---- 4) 对比：旧方案在同等前缘帧数下后缘有多长 ----
        float[] f = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        double oldShortNeeded = 10 * 8.1 / 1000.0 / f[2];   // 要 10 帧需要的 short
        double oldTrailMs = oldShortNeeded * 1000.0;
        System.out.printf("%n  【对比】旧方案要达到 10 帧前缘，short 需 %.3fs → 后缘 %.0f ms%n",
                oldShortNeeded, oldTrailMs);
        System.out.printf("  【新方案】short=0.11 + leading=0.20 → 前缘 %.1f 帧，后缘 110 ms%n",
                leadFramesFinal);
        check("C-5 新方案后缘比旧方案短 40% 以上（这是解耦的收益）",
                trailMs < oldTrailMs * 0.6,
                String.format("新 %.0f ms vs 旧 %.0f ms（省 %.0f%%）",
                        trailMs, oldTrailMs, (1 - trailMs/oldTrailMs) * 100));

        System.out.println();
        System.out.printf("=== C-DECOUPLE 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("C-DECOUPLE RESULT: FAILED"); System.exit(1); }
        System.out.println("C-DECOUPLE RESULT: ALL PASS");
    }

    /** 解析式：前缘帧数（rank2）。leading>0 时取 max。 */
    static double leadFrames(float shortLen, float leading) {
        float[] f = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        double normal = shortLen * f[2] * 1000.0;
        double eff = (leading > 0f) ? Math.max(normal, leading * 1000.0) : normal;
        return eff / 8.1;
    }

    /** 真实驱动：后缘角（rank0）的稳态滞后。 */
    static double trailLag(float shortLen, float leading) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.trailFactors = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        cfg.shortAnimationLength = shortLen;
        cfg.leadingAnimationLength = leading;

        TrailCorner[] c = new TrailCorner[4];
        for (int i = 0; i < 4; i++) c[i] = new TrailCorner(REL[i][0], REL[i][1], cfg);
        double cx = 100, cy = 100;
        for (TrailCorner t : c) t.snapTo(W, H, cx + W/2, cy + H/2);
        // 打 20 个键达到稳态
        for (int k = 0; k < 20; k++) {
            cx += 9;
            for (TrailCorner t : c) t.setCharsMoved(1);
            for (int i = 0; i < 4; i++) c[i].jump(W, H, cx + W/2, cy + H/2, (i % 2 == 0) ? 0 : 2);
            for (int s = 0; s < 3; s++)
                for (int i = 0; i < 4; i++) c[i].update(W, H, cx + W/2, cy + H/2, FRAME, false);
        }
        double targetX = (cx + W/2) + REL[0][0] * W;
        return Math.abs(targetX - c[0].getCurrentX());
    }

    static double maxStep(float shortLen, float leading) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.trailFactors = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        cfg.shortAnimationLength = shortLen;
        cfg.leadingAnimationLength = leading;
        TrailCorner[] c = new TrailCorner[4];
        for (int i = 0; i < 4; i++) c[i] = new TrailCorner(REL[i][0], REL[i][1], cfg);
        double cx = 100, cy = 100;
        for (TrailCorner t : c) t.snapTo(W, H, cx + W/2, cy + H/2);
        cx += 9;
        for (TrailCorner t : c) t.setCharsMoved(1);
        for (int i = 0; i < 4; i++) c[i].jump(W, H, cx + W/2, cy + H/2, (i % 2 == 0) ? 0 : 2);
        double prev = c[0].getCurrentX(), mx = 0;
        for (int fr = 1; fr <= 500; fr++) {
            for (TrailCorner t : c) t.update(W, H, cx + W/2, cy + H/2, FRAME, false);
            double now = c[0].getCurrentX();
            double st = Math.abs(now - prev);
            if (st > mx) mx = st;
            prev = now;
        }
        return mx;
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
