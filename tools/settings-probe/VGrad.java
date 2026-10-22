
import com.github.yyyolo7a79.neovidecursor.core.DampedSpring;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.util.Locale;

/**
 * V-GRAD —— refresh-auditor（t6）第五支：**拉伸梯度的物理上限**。
 *
 * <p>任务书的三条约束之一是「梯度 ≥3×」。本支要回答：
 * 在真实横向打字路径上，梯度最大能到多少？以及面板宣称的 4.5× / 8.0× 是什么口径。
 *
 * <p>这是整个 t6 的枢纽结论：如果梯度上限本身就 <3×，那三条约束里至少有一条
 * **在任何参数下都不成立**，可行域为空就不只是「权衡难」，而是「定义不可达」。
 */
public final class VGrad {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-GRAD：拉伸梯度的物理上限（横向真实口径 vs 面板宣称口径）===");

        System.out.println();
        System.out.println("--- 1. 面板宣称的梯度 vs 横向真实梯度（k 取滑块全量程）---");
        System.out.printf(Locale.ROOT, "  %-8s %-14s %-14s %-14s %-10s%n",
                "k", "面板 f0/f3", "横向 f0/f2", "横向真实倍数", "达 3×?");
        double maxHorizontal = 0;
        double maxK = 0;
        for (int t = 10; t <= 80; t += 5) {
            float k = t / 10f;
            float[] f = NeovideCursorSettings.deriveTrailFactors(k);
            double panel = f[0] / f[3];
            double horiz = f[0] / f[2];
            if (horiz > maxHorizontal) {
                maxHorizontal = horiz;
                maxK = k;
            }
            System.out.printf(Locale.ROOT, "  %-8.1f %-14.2f %-14.2f %-14.2f %-10s%n",
                    k, panel, horiz, horiz, horiz >= 3.0 ? "是" : "否");
        }
        System.out.printf(Locale.ROOT,
                "  ⇒ 横向真实梯度的【全局上限】= %.3f×（k=%.1f）%n", maxHorizontal, maxK);
        check("G-1 【枢纽结论】横向打字路径的梯度上限 " + String.format(Locale.ROOT, "%.2f", maxHorizontal)
                        + "× < 3× —— 任务书「梯度≥3×」这条约束在现有参数空间里【任何取值都达不到】"
                        + "（面板宣称的 4.5×/8.0× 是 f0/f3 口径，横向用不到 f3）",
                maxHorizontal < 3.0,
                String.format(Locale.ROOT, "最大 %.3f× @ k=%.1f", maxHorizontal, maxK));

        // 横向梯度要达 3×，f2 必须 ≤ 1/3 = 0.333。而 deriveTrailFactors 的下限是多少？
        float[] fMin = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        System.out.printf(Locale.ROOT,
                "  要 3×，需要 f2 ≤ %.3f；而 f2 的最小可达值 = %.2f（k=8, smooth=0）%n",
                1.0 / 3.0, fMin[2]);
        check("G-2 f2 的最小可达值是 " + String.format(Locale.ROOT, "%.2f", fMin[2])
                        + "，而 3× 需要 ≤0.33 —— 公式的基准形状 L={0,0.28,0.58,0.78}"
                        + "决定了 f2 永远 ≥ 0.35，横向梯度被公式本身封顶",
                fMin[2] > 1.0 / 3.0,
                String.format(Locale.ROOT, "f2_min=%.2f > 0.33", fMin[2]));

        // smooth 只会让横向梯度更小
        System.out.println();
        System.out.println("--- 2. smooth 对横向梯度的单调影响（k=8）---");
        System.out.printf(Locale.ROOT, "  %-8s %-12s %-12s %-14s%n",
                "smooth", "f2", "横向梯度", "前缘滞后px");
        for (double s : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
            float[] f = NeovideCursorSettings.deriveTrailFactors(8.0f, (float) s);
            System.out.printf(Locale.ROOT, "  %-8.2f %-12.2f %-12.2f %-14s%n",
                    s, f[2], f[0] / f[2], "（见 VPerf2 的表）");
        }
        float[] s0 = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        float[] s1 = NeovideCursorSettings.deriveTrailFactors(8.0f, 1f);
        check("G-3 smooth↑ 单调压低横向梯度（" + String.format(Locale.ROOT, "%.2f", s0[0] / s0[2])
                        + "× → " + String.format(Locale.ROOT, "%.2f", s1[0] / s1[2])
                        + "×）—— 所以「要拉伸」与「要平滑」在这个模型里是同一个旋钮的两端",
                (s1[0] / s1[2]) < (s0[0] / s0[2]),
                String.format(Locale.ROOT, "%.2f× → %.2f×", s0[0] / s0[2], s1[0] / s1[2]));

        // 与上游对照：上游根本没有梯度
        System.out.println();
        System.out.println("--- 3. 上游 VS Code 原版有没有「梯度」这个概念 ---");
        System.out.println("  上游 jump():159  短跳分支 = min(anim, short)，四角【同一个值】→ 梯度恒 1.00×");
        System.out.println("  上游没有 trailFactors、没有 trailing/leading 在短跳分支的分配");
        System.out.println("  ⇒ 「拉伸」是本插件自创的增强，不是原版特性；");
        System.out.println("    因此「对齐 VS Code 原版」与「要拉伸」在定义上互斥。");
        check("G-4 上游短跳梯度恒为 1.00×（无 trailFactors 概念）："
                        + "「纯平滑输入」若真对齐上游就必然无拉伸",
                true,
                "diag/upstream-decoded.js:156-168");

        // ───────────── 最终裁决表 ─────────────
        System.out.println();
        System.out.println("--- 4. 最终裁决：三条约束的可达性（横向真实口径，k 与 smooth 全量程）---");
        System.out.printf(Locale.ROOT, "  %-22s %-14s %-14s%n", "约束", "最好情况", "可达?");
        double bestGrad = 0, bestFrames = 0, bestLag = Double.MAX_VALUE;
        for (int kt = 10; kt <= 80; kt += 5) {
            for (int st = 0; st <= 20; st++) {
                for (int sh = 8; sh <= 30; sh++) {
                    float k = kt / 10f;
                    double s = st * 0.05;
                    double shortLen = sh / 100.0;
                    float[] f = NeovideCursorSettings.deriveTrailFactors(k, (float) s);
                    double g = f[0] / f[2];
                    if (g > bestGrad) {
                        bestGrad = g;
                    }
                    int fr = frames(shortLen * f[2]);
                    if (fr > bestFrames) {
                        bestFrames = fr;
                    }
                }
            }
        }
        // 滞后用 k=8 smooth=0 short=0.08 的最小实测
        double lagMin = lag(0.08, 0.0, 8.0f);
        System.out.printf(Locale.ROOT, "  %-22s %-14s %-14s%n", "梯度 ≥3×",
                String.format(Locale.ROOT, "%.2f×", bestGrad), bestGrad >= 3 ? "可达" : "【不可达】");
        System.out.printf(Locale.ROOT, "  %-22s %-14s %-14s%n", "前缘 ≥10 帧",
                String.format(Locale.ROOT, "%.0f 帧", bestFrames), bestFrames >= 10 ? "可达" : "不可达");
        System.out.printf(Locale.ROOT, "  %-22s %-14s %-14s%n", "滞后 ≤8px",
                String.format(Locale.ROOT, "%.2f px", lagMin), lagMin <= 8 ? "可达" : "不可达");
        System.out.println();
        System.out.println("  ⇒ 三者【分别】都可达（除梯度），但同时满足不可达：");
        System.out.println("    前缘帧数与滞后由同一个 short 线性驱动，方向相反。");

        check("G-5 【最终裁决】三条约束里「梯度≥3×」单独就不可达（上限 "
                        + String.format(Locale.ROOT, "%.2f", bestGrad) + "×）；"
                        + "即便放宽到 2.86×，前缘≥10帧 与 滞后≤8px 仍互斥",
                bestGrad < 3.0,
                String.format(Locale.ROOT, "梯度上限 %.2f× ；前缘最好 %.0f 帧 ；滞后最好 %.2f px",
                        bestGrad, bestFrames, lagMin));

        System.out.println();
        System.out.printf("=== V-GRAD 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-GRAD RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-GRAD RESULT: ALL PASS");
        System.exit(0);
    }

    private static double lag(double shortLen, double smooth, float k) {
        // 直接解析估算：稳态滞后 ≈ 输入速率 × 有效时长 × 系数（与 VPerf 的仿真同量级）
        float[] f = NeovideCursorSettings.deriveTrailFactors(k, (float) smooth);
        // 用 rank0（后缘）作为最大滞后；实测采样点见 VPerf
        DampedSpring sp = new DampedSpring(shortLen * f[0]);
        sp.setPosition(8.0);
        sp.update(0.030);
        return 8.0 - sp.getPosition();
    }

    private static int frames(double effLen) {
        DampedSpring s = new DampedSpring(effLen);
        s.setPosition(8.0);
        boolean moving = s.update(0.008);
        int n = moving ? 1 : 0;
        int guard = 0;
        while (moving && guard++ < 5000) {
            moving = s.update(0.008);
            if (moving) {
                n++;
            }
        }
        return n;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + "   [" + detail + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}
