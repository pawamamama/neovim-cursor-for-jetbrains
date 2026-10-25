import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/**
 * V-PREVIEW-BRANCH：预览窗必须走对分支。
 *
 * <p><b>背景</b>：用户报「单行删除预览窗拉得很诡异」，而真实单行只是比原版长一点。
 *
 * <p><b>两条判据的鲁棒性天差地别</b>：
 * <pre>
 *   charsMoved >= 0 → sameLine && charsMoved <= typingMaxChars(3)      ← 语义判据
 *   charsMoved == -1 → |jumpX| <= shortMoveThreshold(8) && |jumpY|<=0.001  ← 像素判据
 * </pre>
 * 像素判据里 {@code jumpX = deltaX / width}，而预览的 width = cursorW ≈ 1.8px、
 * 一个字符宽 8~17px（随字号变）⇒ {@code jumpX} 落在 4.4~9.4，<b>正好骑在阈值 8 上</b>。
 * 实测（本探针 B-5）：charW=8 判成短跳（16 帧），charW=17 判成跨行（48 帧）——
 * <b>同一个单行操作，结果随字号漂移</b>。
 *
 * <p>所以预览必须显式设 {@code charsMoved}，不依赖像素判据。
 *
 * <p><b>注意 B-1 的判据怎么写的</b>：显式设 charsMoved=1 时，两个字号走的是
 * <b>同一条分支</b>，但四角时长仍会有几帧差异 —— 因为位移量不同（8px vs 17px），
 * 弹簧初始偏移不同。这是物理正常的，不是缺陷。所以 B-1 断言的是
 * 「分支一致」（都走短跳 ⇒ 末尾两角接近 0 而首角在 16~19 帧量级），
 * 不是「数值全等」。
 */
public final class VPreviewBranch {
    private static int pass = 0, fail = 0;
    private static final double FRAME_MS = 8.0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-PREVIEW-BRANCH：预览窗分支判定 ===");

        NeovideConfig cfg = new NeovideConfig();
        cfg.animationLength = 0.28f;
        cfg.shortAnimationLength = 0.11f;
        cfg.trailSize = 1.0f;
        cfg.trailFactors = NeovideCursorSettings.deriveTrailFactors(8.0f, 0.2f);
        cfg.typingMaxChars = 3f;
        cfg.shortMoveThreshold = 8f;

        double h = 32.0, cx = 100, cy = 100, cursorW = 1.8;
        double charWsmall = 8.0, charWbig = 17.0;

        int[] inSmall  = frames(cfg, cursorW, h, cx, cy, 1, charWsmall, 0.0);
        int[] inBig    = frames(cfg, cursorW, h, cx, cy, 1, charWbig, 0.0);
        int[] crossD   = frames(cfg, cursorW, h, cx, cy, 99, charWsmall, h);
        int[] fbSmall  = frames(cfg, cursorW, h, cx, cy, -1, charWsmall, 0.0);
        int[] fbBig    = frames(cfg, cursorW, h, cx, cy, -1, charWbig, 0.0);

        report("单行 charsMoved=1  (charW=8 )", inSmall);
        report("单行 charsMoved=1  (charW=17)", inBig);
        report("跨行 charsMoved=99", crossD);
        report("兜底 -1 (charW=8 )", fbSmall);
        report("兜底 -1 (charW=17)", fbBig);

        // B-1：显式设值时，两个字号必须走【同一条分支】——
        // 判据：都不出现「后缘 40+ 帧」的跨行特征
        boolean bothShort = inSmall[0] < 30 && inBig[0] < 30;
        check("B-1 charsMoved=1 时两字号都走短跳分支（后缘 < 30 帧）",
                bothShort,
                "charW=8 后缘 " + inSmall[0] + " 帧 / charW=17 后缘 " + inBig[0] + " 帧"
                        + "（跨行档是 40+）");

        // B-2：单行必须有梯度
        boolean gradient = inSmall[0] > inSmall[2] && inSmall[2] > 0;
        check("B-2 单行四角有梯度（后缘 > 前缘 > 0）", gradient,
                "后缘 " + inSmall[0] + " / 前缘 " + inSmall[2] + " 帧");

        // B-3：跨行后缘明显长于单行
        double ratio = (double) crossD[0] / Math.max(1, inSmall[0]);
        check("B-3 跨行后缘约 2.5~3.5× 单行最长角", ratio > 2.0 && ratio < 3.6,
                String.format("%.2f×（%d vs %d 帧）", ratio, crossD[0], inSmall[0]));

        // B-4：单行与跨行必须不同
        check("B-4 单行与跨行时长分布不同", inSmall[0] != crossD[0],
                "单行 " + j(inSmall) + " vs 跨行 " + j(crossD));

        // B-5（核心）：兜底分支随字号漂移
        boolean drifts = !java.util.Arrays.equals(fbSmall, fbBig);
        check("B-5 兜底分支随字号漂移（证明不能依赖像素判据）", drifts,
                "charW=8 → " + j(fbSmall) + " / charW=17 → " + j(fbBig)
                        + (drifts ? "  ← 漂移" : "  ← 未漂移"));

        // B-6：AnimationPreview 里有 setCharsMoved
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/github/yyyolo7a79/"
                        + "neovidecursor/ui/settings/AnimationPreview.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        int calls = 0, idx = 0;
        while ((idx = src.indexOf("setCharsMoved(", idx)) >= 0) { calls++; idx++; }
        check("B-6 AnimationPreview 调用了 setCharsMoved", calls >= 1, "出现 " + calls + " 次");

        // B-7：预览必须区分单行/跨行两种模式（避免又退回「一套算法套两种场景」）
        boolean hasCrossConst = src.contains("CROSS_CHARS_MOVED");
        check("B-7 预览用常量区分跨行（不写魔法数字 99）", hasCrossConst,
                hasCrossConst ? "CROSS_CHARS_MOVED 已定义" : "缺失");

        System.out.println();
        System.out.printf("=== V-PREVIEW-BRANCH 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-PREVIEW-BRANCH RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-PREVIEW-BRANCH RESULT: ALL PASS");
    }

    /** 推进到四角静止，返回每角帧数（按 rank 归位，并列取 max 避免覆盖） */
    private static int[] frames(NeovideConfig cfg, double w, double h,
                                double cx, double cy, int charsMoved,
                                double dx, double dy) {
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
        for (int n = 0; n < 2000; n++) {
            boolean allDone = true;
            for (int i = 0; i < 4; i++) {
                if (done[i]) continue;
                if (cs[i].update(w, h, cx + dx, cy + dy, dt, false)) allDone = false;
                else { done[i] = true; f[i] = n + 1; }
            }
            if (allDone) break;
        }
        int[] byRank = new int[4];
        for (int i = 0; i < 4; i++) byRank[rank[i]] = Math.max(byRank[rank[i]], f[i]);
        return byRank;
    }

    private static String j(int[] a) { return "[" + a[0] + "," + a[1] + "," + a[2] + "," + a[3] + "]"; }

    private static void report(String label, int[] d) {
        System.out.printf("    %-28s → %s 帧 ≈ [%.0f,%.0f,%.0f,%.0f] ms%n", label, j(d),
                d[0] * FRAME_MS, d[1] * FRAME_MS, d[2] * FRAME_MS, d[3] * FRAME_MS);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
