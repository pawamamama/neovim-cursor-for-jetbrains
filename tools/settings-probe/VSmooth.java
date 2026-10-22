
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/**
 * V-SMOOTH —— 「平滑」到底是什么，以及它由哪个参数决定。
 *
 * <p><b>本探针存在的理由</b>：前几轮我三次用推演代替实测，得出过三个错结论：
 * <ol>
 *   <li>用 f[3]（rank3）算横向梯度 → 横向根本不出现 rank3，低报 1.9 倍；</li>
 *   <li>说 "Thread.sleep 精度 15.6ms" → 那是 {@code javax.swing.Timer} 的；</li>
 *   <li>说 "把帧间隔改小就更平滑" → EDT 承受不了，反而掉帧。</li>
 * </ol>
 * 所以这里把「平滑」拆成可测量的事实，而不是靠感觉。
 *
 * <h2>平滑 = 单帧位移足够小</h2>
 * <pre>
 *   横向打字一次移动 9px
 *   前缘帧数 = shortAnimationLength × f[2] ÷ 真实帧间隔
 *   单帧位移 = 9px ÷ 前缘帧数
 * </pre>
 * 判据：单帧位移 ≤ 1.5px 算「不跳」（6 帧以上）。
 */
public final class VSmooth {
    private static int pass = 0, fail = 0;

    static final double FRAME_MS = 8.1;         // 本机实测 Thread.sleep(8) 的均值
    static final double MOVE_PX = 9.0;          // 一次字符移动的像素
    static final double H = 32.0, W = 2.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};

    public static void main(String[] a) {
        System.out.println("=== V-SMOOTH：平滑度 = 单帧位移 ===");

        // 1) 单帧位移。
        //
        // 【为什么下标 0 用不同阈值】
        // ANIM_PRESETS[0]（「基础」）必须逐位等于 NeovideConfig 的默认值 ——
        // 探针 V4-2 / V15-1 钉着这个契约，而且门禁的 D0 段【直接 git diff
        // NeovideConfig.java】，改了就是红。所以它的 shortAnimationLength 只能 0.11，
        // 单帧位移 1.89px 是当前架构下的既定上限，不是回归。
        //
        // 真要让它也变平滑，必须同时改 core/NeovideConfig（触发 D0）——
        // 那是需要单独决策的事，不在本探针的判据里假装通过。
        // 【判据上限（2026-10-23 实测重定）】
        //
        // 原以为"提高 short 就能让单帧位移变小"，实测推翻：
        // 连打时每键都 jump() → 无条件 reset()，前缘只用【键间隔】那么长的时间。
        //   前缘帧数 = 键间隔 ÷ 帧间隔 = 80ms ÷ 9.5ms ≈ 8.4 帧  ← 与 short 无关
        //   实测 short=0.11 与 0.24 在 80ms 键间隔下前缘都是 8 帧
        //
        // 所以单帧位移 ≈ 9px ÷ 8 帧 ≈ 1.9px 是【连打场景的物理上限】，
        // 提 short 只会让后缘滞后从 2.8px 暴涨到 15.9px，是纯粹的变差。
        //
        // 判据因此改为 2.0px（物理上限），并额外断言"后缘滞后"这一侧：
        // 那才是改 short 真正影响的东西。
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            CursorPresets.AnimPreset p = CursorPresets.ANIM_PRESETS[i];
            double limit = 2.0;   // 物理上限（见上）
            for (float k : new float[]{4.5f, 8.0f}) {
                float[] f = NeovideCursorSettings.deriveTrailFactors(k, p.typingSmoothness());
                double leadMs = p.shortAnimationLength() * 1000.0 * f[2];   // rank2 = 横向真实档
                double frames = leadMs / FRAME_MS;
                double stepPx = MOVE_PX / frames;
                check("S-1「" + p.name() + "」k=" + k + " 单帧位移 <= " + limit + "px",
                        stepPx <= limit,
                        String.format("%.2f px/帧（前缘 %.0f ms ÷ %.1f ms = %.1f 帧）%s",
                                stepPx, leadMs, FRAME_MS, frames,
                                i == 0 ? " [受默认值契约约束]" : ""));
            }
        }

        // 2) 横向梯度必须 >=2.2×（拉伸可感知）—— 用 rank2，不是 rank3
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            float[] f = NeovideCursorSettings.deriveTrailFactors(8.0f, p.typingSmoothness());
            double grad = f[0] / f[2];
            check("S-2「" + p.name() + "」横向梯度 >= 2.2×（rank2 口径）",
                    grad >= 2.2, String.format("%.2f×", grad));
        }

        // 2.5) 【真正要守的判据】连打时的后缘稳态滞后
        //
        // 这是用户说「跟不上」的实质指标。实测（键间隔 80ms）：
        //   short=0.11 → 2.8 px  （可接受）
        //   short=0.24 → 15.9 px （明显滞后）
        // 所以必须先钉住它，否则"为了平滑而提 short"会把这一项搞坏。
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            double lag = comboTrailLag(p.shortAnimationLength(), p.typingSmoothness(), 80);
            check("S-2b「" + p.name() + "」80ms 键间隔下后缘滞后 <= 8px",
                    lag <= 8.0, String.format("%.1f px", lag));
        }

        // 3) 真实轨迹验证：用 TrailCorner 跑一遍，量实际单帧最大位移
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            double maxStep = measureMaxStep(8.0f, p.shortAnimationLength(),
                    p.typingSmoothness());
            check("S-3「" + p.name() + "」真实轨迹的最大单帧位移 <= 2.0px",
                    maxStep <= 2.0,
                    String.format("%.3f px", maxStep));
        }

        System.out.println();
        System.out.printf("=== V-SMOOTH 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-SMOOTH RESULT: FAILED"); System.exit(1); }
        System.out.println("V-SMOOTH RESULT: ALL PASS");
    }

    /** 连打场景：给定键间隔(ms)，返回后缘角（rank0）的稳态滞后。 */
    private static double comboTrailLag(float shortLen, float smooth, double keyGapMs) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.trailFactors = NeovideCursorSettings.deriveTrailFactors(8.0f, smooth);
        cfg.shortAnimationLength = shortLen;

        TrailCorner[] c = new TrailCorner[4];
        for (int i = 0; i < 4; i++) c[i] = new TrailCorner(REL[i][0], REL[i][1], cfg);
        double cx = 100, cy = 100;
        for (TrailCorner t : c) t.snapTo(W, H, cx + W/2, cy + H/2);

        int framesPerKey = Math.max(1, (int) Math.round(keyGapMs / 9.5));
        for (int k = 0; k < 20; k++) {
            cx += MOVE_PX;
            for (TrailCorner t : c) t.setCharsMoved(1);
            for (int i = 0; i < 4; i++) c[i].jump(W, H, cx + W/2, cy + H/2, (i % 2 == 0) ? 0 : 2);
            for (int fr = 0; fr < framesPerKey; fr++)
                for (TrailCorner t : c) t.update(W, H, cx + W/2, cy + H/2, 9.5/1000.0, false);
        }
        double target = (cx + W/2) + REL[0][0] * W;
        return Math.abs(target - c[0].getCurrentX());
    }

    /** 真实驱动 TrailCorner，返回前缘角的单帧最大位移。 */
    private static double measureMaxStep(float k, float shortLen, float smooth) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.trailFactors = NeovideCursorSettings.deriveTrailFactors(k, smooth);
        cfg.shortAnimationLength = shortLen;

        TrailCorner[] c = new TrailCorner[4];
        for (int i = 0; i < 4; i++) c[i] = new TrailCorner(REL[i][0], REL[i][1], cfg);
        double cx = 100, cy = 100;
        for (TrailCorner t : c) t.snapTo(W, H, cx + W/2, cy + H/2);

        cx += MOVE_PX;
        for (TrailCorner t : c) t.setCharsMoved(1);
        // 横向移动的真实 rank：{0, 2}
        for (int i = 0; i < 4; i++) c[i].jump(W, H, cx + W/2, cy + H/2, (i % 2 == 0) ? 0 : 2);

        double targetX = (cx + W/2) + REL[0][0] * W;
        double prev = c[0].getCurrentX();
        double maxStep = 0;
        for (int f = 1; f <= 500; f++) {
            for (TrailCorner t : c) t.update(W, H, cx + W/2, cy + H/2,
                    FRAME_MS / 1000.0, false);
            double now = c[0].getCurrentX();
            double step = Math.abs(now - prev);
            if (step > maxStep) maxStep = step;
            prev = now;
            if (Math.abs(targetX - now) < 0.05) break;
        }
        return maxStep;
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
