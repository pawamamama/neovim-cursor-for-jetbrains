import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.lang.reflect.Field;

/**
 * V-TEXT-FACTS：面板文案里的事实性陈述必须与代码一致。
 *
 * <p><b>为什么需要它</b>：0.9.72 发现三处文案在骗人，而且都是「代码改了、文案没改」：
 * <ol>
 *   <li>「输入平滑」文案写「全局设置，不随预设变化」—— 0.9.67 已把它改回预设域</li>
 *   <li>「拖尾密度」文案写「也影响行内」—— 核实 {@code TrailCorner.jump} 短跳分支
 *       <b>完全不读 trailSize</b>，只读 shortAnimationLength + trailFactors</li>
 *   <li>「拉伸强度」文案没说「调它对跨行无效」，用户会以为它管全部</li>
 * </ol>
 *
 * <p>这三条都不是错别字，是<b>事实与实现不符</b>。文字本身测不出对错，
 * 但文字里的<b>可验证断言</b>能：比如「trailSize 只影响跨行」这件事，
 * 可以直接跑代码验证 —— 短跳分支里改 trailSize，四角时长不该变。
 *
 * <p>本探针就把这些断言拿去实测，文案与代码不一致时红。
 */
public final class VTextFacts {
    private static int pass = 0, fail = 0;
    private static final double FRAME_MS = 8.0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-TEXT-FACTS：文案的事实性陈述与代码一致性 ===");

        // ---------- T-1：文案说「trailSize 只影响跨行」----------
        // 短跳分支里把 trailSize 从 1.0 改到 0.0，四角时长必须【完全不变】
        double[] shortA = frames(1.0f, 1, true);      // 短跳，trailSize=1.0
        double[] shortB = frames(0.0f, 1, true);      // 短跳，trailSize=0.0
        check("T-1 短跳分支不受 trailSize 影响（文案：『只决定跨行』）",
                same(shortA, shortB),
                "trailSize=1.0 → " + j(shortA) + "  trailSize=0.0 → " + j(shortB));

        // ---------- T-2：文案说 trailSize=1.0 时跨行前缘「瞬间到位」----------
        //
        // 【探针第一版判据写错了，记下来】
        // 我原来断言 crossA[2] == 0.0，实测是 8ms 而非 0 —— 于是先怀疑代码。
        // 查 DampedSpring.update() 才明白：
        //     if (animationLength <= dt) { position = 0; return false; }
        // 当 leading = 0 时，**第一帧（dt=8ms）就命中短路归零**。
        // 所以「前缘消耗 1 帧 = 8ms」是正确行为，0ms 是不可能的
        // —— 任何角点至少要跑一帧才会被判为「静止」。
        //
        // 正确判据：前缘必须 ≤ 1 帧（8ms），而后缘必须是整整 animationLength（280ms）。
        // 这个比例（1:35）才是「瞬间到位」的定量含义。
        double[] crossA = frames(1.0f, 99, false);    // 跨行，trailSize=1.0
        check("T-2 trailSize=1.0 时跨行前缘 ≤1 帧、后缘为整段（文案：『瞬间到位』）",
                crossA[2] <= 8.0 && crossA[3] <= 8.0 && crossA[0] >= 400.0,
                "四角 = " + j(crossA) + "（前缘应为 1 帧 = 8ms，后缘应为整段）");

        // ---------- T-3：文案说调小 trailSize 前缘也开始拖 ----------
        double[] crossB = frames(0.5f, 99, false);    // 跨行，trailSize=0.5
        check("T-3 trailSize 调小后跨行前缘变长（文案：『前缘也开始拖』）",
                crossB[2] > crossA[2],
                "trailSize=1.0 前缘=" + crossA[2] + "ms  trailSize=0.5 前缘=" + crossB[2] + "ms");

        // ---------- T-4：文案说「输入平滑会在预设域」----------
        boolean smoothInPreset;
        try {
            com.github.yyyolo7a79.neovidecursor.settings.CursorPresets.AnimPreset.class
                    .getMethod("typingSmoothness");
            smoothInPreset = true;
        } catch (NoSuchMethodException e) {
            smoothInPreset = false;
        }
        check("T-4 AnimPreset 含 typingSmoothness（文案：不再说『全局不随预设』）",
                smoothInPreset, smoothInPreset ? "已进预设域" : "仍在预设域外 → 文案应改回『全局』");

        // ---------- T-5：文案说「拉伸强度会被输入平滑抵消」----------
        // 实测：smooth=0 时 k=8 与 k=4.5 的梯度比差异，应大于 smooth=1 时的差异
        float[] k45s0 = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        float[] k80s0 = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        float[] k45s1 = NeovideCursorSettings.deriveTrailFactors(4.5f, 1f);
        float[] k80s1 = NeovideCursorSettings.deriveTrailFactors(8.0f, 1f);
        double diffNoSmooth = Math.abs(k45s0[3] - k80s0[3]);
        double diffFullSmooth = Math.abs(k45s1[3] - k80s1[3]);
        check("T-5 平滑会削弱拉伸强度的差异（文案：『两者互相抵消』）",
                diffFullSmooth < diffNoSmooth,
                String.format("k 差异：smooth=0 时 %.3f，smooth=1 时 %.3f", diffNoSmooth, diffFullSmooth));

        // ---------- T-6：文案说「拉伸强度只影响单行」----------
        // 跨行分支里改 k 不该影响四角时长（跨行不读 trailFactors）
        double[] crossK1 = framesCrossWithK(1.0f);
        double[] crossK8 = framesCrossWithK(8.0f);
        check("T-6 跨行分支不受 stretchRatioK 影响（文案：『调它对换行无效』）",
                same(crossK1, crossK8),
                "k=1.0 → " + j(crossK1) + "  k=8.0 → " + j(crossK8));

        System.out.println();
        System.out.printf("=== V-TEXT-FACTS 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-TEXT-FACTS RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-TEXT-FACTS RESULT: ALL PASS");
    }

    /** 测一组四角时长（ms） */
    private static double[] frames(float trailSize, int charsMoved, boolean sameLine) {
        NeovideConfig cfg = baseConfig();
        cfg.trailSize = trailSize;
        return measure(cfg, charsMoved, sameLine);
    }

    /** 跨行分支下改 k，看四角是否变化 */
    private static double[] framesCrossWithK(float k) {
        NeovideConfig cfg = baseConfig();
        cfg.trailSize = 1.0f;
        cfg.trailFactors = NeovideCursorSettings.deriveTrailFactors(k, 0f);
        return measure(cfg, 99, false);
    }

    private static NeovideConfig baseConfig() {
        NeovideConfig cfg = new NeovideConfig();
        cfg.animationLength = 0.28f;
        cfg.shortAnimationLength = 0.11f;
        cfg.trailSize = 1.0f;
        cfg.trailFactors = NeovideCursorSettings.deriveTrailFactors(8.0f, 0.2f);
        cfg.typingMaxChars = 3f;
        return cfg;
    }

    /** 推进到四角静止，返回每角帧数（按 rank 归位） */
    private static double[] measure(NeovideConfig cfg, int charsMoved, boolean sameLine) {
        double w = 2.0, h = 32.0, cx = 100, cy = 100;
        double dx = sameLine ? 12.0 : 0.0;
        double dy = sameLine ? 0.0 : h;
        TrailCorner[] cs = new TrailCorner[4];
        double[][] rel = {{-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5}};
        for (int i = 0; i < 4; i++) {
            cs[i] = new TrailCorner(rel[i][0], rel[i][1], cfg);
            cs[i].setCharsMoved(charsMoved);
            cs[i].snapTo(w, h, cx, cy);
        }
        double[] al = new double[4];
        for (int i = 0; i < 4; i++) {
            al[i] = cs[i].calculateDirectionAlignment(w, h, cx + dx, cy + dy);
        }
        int[] rank = new int[4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (al[j] < al[i]) rank[i]++;
            }
        }
        for (int i = 0; i < 4; i++) {
            cs[i].jump(w, h, cx + dx, cy + dy, rank[i]);
        }
        double dt = FRAME_MS / 1000.0;
        int[] f = new int[4];
        boolean[] done = new boolean[4];
        for (int n = 0; n < 3000; n++) {
            boolean all = true;
            for (int i = 0; i < 4; i++) {
                if (done[i]) continue;
                if (cs[i].update(w, h, cx + dx, cy + dy, dt, false)) all = false;
                else { done[i] = true; f[i] = n + 1; }
            }
            if (all) break;
        }
        double[] byRank = new double[4];
        for (int i = 0; i < 4; i++) byRank[rank[i]] = Math.max(byRank[rank[i]], f[i] * FRAME_MS);
        return byRank;
    }

    private static boolean same(double[] a, double[] b) {
        for (int i = 0; i < 4; i++) {
            if (Math.abs(a[i] - b[i]) > 1.0) return false;
        }
        return true;
    }

    private static String j(double[] a) {
        return String.format("[%.0f,%.0f,%.0f,%.0f]", a[0], a[1], a[2], a[3]);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}