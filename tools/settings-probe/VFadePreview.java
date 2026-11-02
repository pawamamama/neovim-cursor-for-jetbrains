import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview;

import java.lang.reflect.Field;

/**
 * 守门探针：在真实预览窗里验证「目标曲线 + 限速下降」（B 方案，0.9.126）。
 *
 * <h2>它抓的是什么 bug</h2>
 *
 * <p>0.9.125 的淡出是「保持满亮 → 到点乘 0」：拖尾还在时亮度被钉在 1.0，
 * 只能等光标停下再淡。0.9.126 改成限速下降：目标仍是拖尾曲线，但每帧最多下降
 * {@code dt / fadeOutSeconds}。本探针驱动 {@link AnimationPreview}（与生产共用
 * {@code GlowIntensity.advanceGlow} 和同一套状态更新），断言：
 * <ul>
 *   <li>动作帧立即回到 1.0；</li>
 *   <li>同样 tick 数后，fade=300 明显比 fade=75 亮（参数真实有效）；</li>
 *   <li>无新动作时亮度单调不增，单帧降幅不超过 dt/fade；</li>
 *   <li>保持期内亮度不降；</li>
 *   <li>从未动作的预览亮度为 0。</li>
 * </ul>
 *
 * <p>为什么不直接驱动 {@code CaretAnimator}：它需要真实 {@code Editor}，
 * 而预览窗是同一套算法的可驱动实例（陷阱 6「预览与生产必须同源」的另一面 ——
 * 同源也让预览能当生产逻辑的试验台）。
 */
public final class VFadePreview {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== V-FADE-PREVIEW：限速下降的真实驱动（0.9.126）===");

        // ---------- P-1：动作帧立即回到 1.0 ----------
        AnimationPreview p75 = make(0, 0.075);
        AnimationPreview p300 = make(0, 0.300);
        CaretTrailPanel c75 = panelOf(p75);
        CaretTrailPanel c300 = panelOf(p300);

        p75.probeTick(true);
        p300.probeTick(true);
        float first75 = c75.getGlowStrengthForPreview();
        float first300 = c300.getGlowStrengthForPreview();
        check("P-1 动作帧亮度立即到 1（上升不受限速影响）",
                first75 >= 0.99f && first300 >= 0.99f,
                "75=" + first75 + " 300=" + first300);

        // ---------- P-2~P-4：同样 20 tick，fade=300 明显更亮 ----------
        for (int i = 0; i < 20; i++) {
            p75.probeTick(false);
            p300.probeTick(false);
            Thread.sleep(16);
        }
        float b75 = c75.getGlowStrengthForPreview();
        float b300 = c300.getGlowStrengthForPreview();
        check("P-2 20 tick 后 fade=300 明显比 fade=75 亮（参数有效）",
                b300 > b75 + 0.2f, "75=" + b75 + " 300=" + b300);
        check("P-3 fade=300 仍有余光（不是一步归零）",
                b300 > 0.05f, "300=" + b300);
        check("P-4 fade=75 已归零（短淡出确实先结束）",
                b75 <= 0.001f, "75=" + b75);

        // ---------- P-5 / P-6：单调性与单帧降幅 ----------
        AnimationPreview p30 = make(0, 0.300);
        CaretTrailPanel c30 = panelOf(p30);
        p30.probeTick(true);
        float prev = c30.getGlowStrengthForPreview();
        boolean monotonic = true;
        float maxDrop = 0f;
        for (int i = 0; i < 30; i++) {
            p30.probeTick(false);
            Thread.sleep(16);
            float cur = c30.getGlowStrengthForPreview();
            if (cur > prev + 1e-4f) {
                monotonic = false;
            }
            float drop = prev - cur;
            if (drop > maxDrop) {
                maxDrop = drop;
            }
            prev = cur;
        }
        check("P-5 无新动作时亮度单调不增", monotonic, "prev=" + prev);
        check("P-6 单帧降幅不超过 dt/fade（8ms/300ms ≈ 0.0267）",
                maxDrop <= 0.008f / 0.300f + 1e-3f, "maxDrop=" + maxDrop);

        // ---------- P-7：保持期内亮度不降 ----------
        // 保持期取 2000ms：15 tick（约 240ms 实时）远离边界，避免 GC 抖动导致假红；
        // 同时 15 tick 的模拟时间足以让拖尾目标归零，holdActive 失效时必然下降。
        AnimationPreview pHold = make(2000, 0.075);
        CaretTrailPanel cHold = panelOf(pHold);
        pHold.probeTick(true);
        float holdMin = 1f;
        // 【为什么是 15 tick】5 tick（约 80ms）时拖尾目标还没跌破满强度，
        // 即使把 holdActive 整个删掉，亮度也仍然是 1 —— P-7 会假通过。
        // 15 tick 能让目标先归零，同时仍在 2000ms 保持期内；
        // 变异 M3（holdActive 失效）下 holdMin=0.466，必红。
        for (int i = 0; i < 15; i++) {
            pHold.probeTick(false);
            Thread.sleep(16);
            holdMin = Math.min(holdMin, cHold.getGlowStrengthForPreview());
        }
        check("P-7 保持期内亮度不降（约 240ms < 2000ms）",
                holdMin >= 0.99f, "holdMin=" + holdMin);

        // ---------- P-8：从未动作的预览不发光 ----------
        AnimationPreview pStatic = make(0, 0.075);
        CaretTrailPanel cStatic = panelOf(pStatic);
        for (int i = 0; i < 5; i++) {
            pStatic.probeTick(false);
            Thread.sleep(16);
        }
        check("P-8 从未动作的预览亮度为 0（静止不发光）",
                cStatic.getGlowStrengthForPreview() <= 0.001f,
                "static=" + cStatic.getGlowStrengthForPreview());

        System.out.println();
        System.out.printf("=== V-FADE-PREVIEW 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-FADE-PREVIEW RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-FADE-PREVIEW RESULT: ALL PASS");
        System.exit(0);
    }

    private static AnimationPreview make(int delayMs, double fadeSec) {
        NeovideConfig c = new NeovideConfig();
        c.overlayFadeOut = true;
        c.useShadow = true;
        c.glowLayers = 8;
        c.glowOpacity = 0.55f;
        c.shadowBlurFactor = 0.2f;
        c.cursorDisappearDelay = delayMs;
        c.cursorFadeOutDuration = (float) fadeSec;
        AnimationPreview p = AnimationPreview.inline(c);
        p.setSize(520, 110);
        return p;
    }

    private static CaretTrailPanel panelOf(AnimationPreview p) throws Exception {
        Field f = AnimationPreview.class.getDeclaredField("panel");
        f.setAccessible(true);
        return (CaretTrailPanel) f.get(p);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   " + detail);
        }
    }
}
