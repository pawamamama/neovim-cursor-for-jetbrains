import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview;

import java.lang.reflect.Field;

/**
 * 守门探针：辉光淡出在「真实驱动」下的亮度曲线（BUG-B 第二次修，2026-11-01）。
 *
 * <h2>它抓的是什么 bug</h2>
 *
 * <p>旧实现用「辉光最后一次<b>可见</b>的强度」当淡出起点。弹簧收敛的最后几帧强度
 * 本来就接近 0（offset 刚过 0.5px 时 {@code strengthFor} ≈ 0.015），
 * 于是「从最后亮度淡出」= 从 1.5% 淡到 0：淡出代码在跑，肉眼完全看不见。
 * 用户原话「改到最大 500 观感没有变化」就是这个。
 *
 * <p>修法：起点改用「最后一次<b>移动</b>时的强度」（通常 1.0），并且停止移动后
 * 不再让弹簧的自然衰减先把它吃掉。本探针驱动 <b>AnimationPreview</b>（与生产共用
 * {@code GlowIntensity} 和同一套 {@code glowForGroup} 判据），逐帧采样面板亮度，
 * 断言：保持期亮度接近满值、随后单调下降、最终归零。
 *
 * <p>为什么不直接驱动 {@code CaretAnimator}：它需要真实 {@code Editor}，
 * 而预览窗是同一套算法的可驱动实例（陷阱 6「预览与生产必须同源」的另一面 ——
 * 同源也让预览能当生产逻辑的试验台）。
 */
public final class VFadePreview {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== V-FADE-PREVIEW：驱动预览窗，逐帧采样淡出曲线 ===");

        int delay = 300;            // ms
        double duration = 0.4;      // s

        NeovideConfig c = new NeovideConfig();
        c.overlayFadeOut = true;
        c.useShadow = true;
        c.glowLayers = 8;
        c.glowOpacity = 0.55f;
        c.shadowBlurFactor = 0.2f;
        c.cursorDisappearDelay = delay;
        c.cursorFadeOutDuration = (float) duration;

        AnimationPreview p = AnimationPreview.inline(c);
        p.setSize(520, 110);
        p.probeTick(true);          // 一次「动作」：光标跳变 → 辉光点亮

        Field f = AnimationPreview.class.getDeclaredField("panel");
        f.setAccessible(true);
        CaretTrailPanel panel = (CaretTrailPanel) f.get(p);

        long t0 = System.nanoTime();
        float maxGlow = 0f;
        float glowAtFadeStart = -1f;
        float prevFade = 1f;
        boolean monotonic = true;
        int fadeSteps = 0;
        boolean sawFullHold = false;

        for (int i = 0; i < 100; i++) {
            p.probeTick(false);
            float fade = panel.glowFadeForProbe();
            float glow = panel.getGlowStrengthForPreview();
            long ms = (System.nanoTime() - t0) / 1_000_000L;

            if (glow > maxGlow) {
                maxGlow = glow;
            }
            if (fade >= 0.99f && glow >= 0.9f) {
                sawFullHold = true;
            }
            if (glowAtFadeStart < 0f && fade < 0.99f) {
                glowAtFadeStart = glow;          // 淡出刚开始时「还剩多少光」
            }
            if (fade < prevFade - 1e-4f) {
                fadeSteps++;
            } else if (fade > prevFade + 1e-4f) {
                monotonic = false;               // 淡出过程中不允许变亮
            }
            prevFade = fade;
            if (fade <= 0f && ms > (delay + duration * 1000.0) * 0.8) {
                break;
            }
            Thread.sleep(16);
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;

        check("P-1 保持期亮度接近满值（≥0.9）—— 不是「从 1.5% 淡出」",
                sawFullHold && maxGlow >= 0.9f,
                "maxGlow=" + maxGlow + " sawFullHold=" + sawFullHold);
        check("P-2 淡出开始时仍有足够的亮度可淡（glow ≥ 0.9）",
                glowAtFadeStart >= 0.9f,
                "glowAtFadeStart=" + glowAtFadeStart);
        check("P-3 淡出单调下降（中途不反弹）",
                monotonic, "monotonic=" + monotonic);
        check("P-4 淡出至少被采样到 5 帧（不是一步跳到 0）",
                fadeSteps >= 5, "fadeSteps=" + fadeSteps);
        check("P-5 最终归零，且总时长与 delay+duration 同量级（0.6x~2.5x）",
                prevFade <= 0f
                        && totalMs >= (delay + duration * 1000.0) * 0.6
                        && totalMs <= (delay + duration * 1000.0) * 2.5,
                "fade=" + prevFade + " totalMs=" + totalMs
                        + " 期望≈" + (long) (delay + duration * 1000.0));

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
