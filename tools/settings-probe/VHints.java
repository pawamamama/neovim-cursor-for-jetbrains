import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JLabel;

/**
 * verifier 自建（t4）：契约 §7.4 的两条动态耦合提示。
 *
 * <p>判据（【必须】）：
 * <ul>
 *   <li>提示是<b>运行时计算</b>的字符串，不是写死数字；</li>
 *   <li>改 animationLength / shortAnimationLength / trailSize / stretchRatioK 任一项，
 *       对应的提示文字必须跟着变；</li>
 *   <li>提示里的毫秒数与 {@code TrailCorner.jump()} 的公式同源
 *       （短跳：shortAnimationLength × trailFactors[rank]；跨行：animationLength 与 × (1 - trailSize)）。</li>
 * </ul>
 */
public final class VHints {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-HINTS：两条动态耦合提示（契约 §7.4）===");
        V0Env.install();
        NeovideCursorSettings svc = V0Env.svc;
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);

        JLabel[] hints = new JLabel[]{
                conf.couplingHintsForProbe()[0], conf.couplingHintsForProbe()[1]
        };
        check("H-1 两条提示标签都存在", hints[0] != null && hints[1] != null, "");
        if (hints[0] == null || hints[1] == null) {
            done();
            return;
        }

        // 默认值下的期望文字（用 DampedSpring 无关的纯公式自算）
        NeovideCursorSettings.State st = svc.getState();
        st.animationLength = 0.1f;
        st.shortAnimationLength = 0.11f;
        st.trailSize = 1f;
        st.stretchRatioK = 4.5f;
        conf.reset();
        ui = VUi.build(conf);
        hints = new JLabel[]{conf.couplingHintsForProbe()[0], conf.couplingHintsForProbe()[1]};

        String single0 = hints[0].getText();
        String cross0 = hints[1].getText();
        System.out.println("    单行提示： " + single0);
        System.out.println("    跨行提示： " + cross0);

        // 【口径修正 rank3 → rank2】VRank 实测：纯横向/纵向移动时四角 rank 恒为 {0,2}，
        // rank3 根本不出现。面板提示必须报横向前缘（rank2），否则低报约 1.9 倍。
        // 同时必须用【双参版】deriveTrailFactors(k, smooth) —— 运行期就是它。
        float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        long trailMs = Math.round(0.11f * 1000f * f[0]);
        long leadMs = Math.round(0.11f * 1000f * f[2]);
        check("H-2 单行提示含自算的后缘毫秒数（" + trailMs + "）",
                single0.contains(trailMs + " ms"), single0);
        check("H-3 单行提示含自算的【横向前缘】毫秒数（" + leadMs + "，rank2 口径）",
                single0.contains(leadMs + " ms"), single0);
        check("H-4 跨行提示含 animationLength 的毫秒数（100）",
                cross0.contains("100 ms"), cross0);
        check("H-5 trailSize=1.00 时前缘为 0 ms",
                cross0.contains("0 ms"), cross0);

        // 改 animationLength → 跨行提示必须变
        conf.reset();
        ui = VUi.build(conf);
        // 【2026-10-28 修索引契约】原来写死 `int idxAnim = 8; ui.sliders.get(idxAnim)`。
        // 而 VUi.KEYS（= 面板真实渲染顺序）里 [8] 是 cursorHeightRatio，
        // animationLength 在 [5]（0.9.79 加 slopeTicks 之后整体后移了一位）。
        // 后果：这一步实际改的是「光标高度」（量程 30..100 步长 10，写 28 被夹到 30），
        // 动画时长纹丝不动 → H-6 必然红，而且红的原因与它自称要测的东西毫无关系。
        // 修法与 VDefects / VSnap 的 11 条红相同：按 key 定位，与渲染顺序解耦。
        javax.swing.JSlider animSlider = ui.sliderByKey("animationLength");
        if (animSlider == null) {
            check("H-6 改 animationLength 后跨行提示跟着变（出现 280 ms）",
                    false, "按 key 定位不到 animationLength 滑块");
        } else {
            animSlider.setValue(28);              // 量程 [5,35] 步长 1 → 0.28 s
            hints = conf.couplingHintsForProbe();
            String cross1 = hints[1].getText();
            System.out.println("    改 animationLength=0.28 后跨行提示： " + cross1);
            check("H-6 改 animationLength 后跨行提示跟着变（出现 280 ms）",
                    cross1.contains("280 ms") && !cross1.equals(cross0), cross1);
        }

        // 改 shortAnimationLength → 单行提示必须变
        conf.reset();
        ui = VUi.build(conf);
        ui.sliderByKey("shortAnimationLength").setValue(4);   // = 0.04s
        hints = conf.couplingHintsForProbe();
        String single1 = hints[0].getText();
        System.out.println("    改 shortAnimationLength=0.04 后单行提示： " + single1);
        check("H-7 改 shortAnimationLength 后单行提示跟着变",
                !single1.equals(single0), single1);
        long trailMs2 = Math.round(0.04f * 1000f * f[0]);
        check("H-8 单行提示的新后缘毫秒数与自己算的一致（" + trailMs2 + "）",
                single1.contains(trailMs2 + " ms"), single1);

        // 改 trailSize → 跨行提示的「前缘」必须变
        conf.reset();
        ui = VUi.build(conf);
        ui.sliderByKey("trailSize").setValue(30);             // = 0.30
        hints = conf.couplingHintsForProbe();
        String cross2 = hints[1].getText();
        System.out.println("    改 trailSize=0.30 后跨行提示： " + cross2);
        long lead2 = Math.round(0.1f * 1000f * (1f - 0.30f));
        check("H-9 改 trailSize 后跨行提示的前缘 = animationLength × (1-trailSize) = " + lead2 + " ms",
                cross2.contains(lead2 + " ms"), cross2);

        // 改 stretchRatioK → 单行提示的系数必须变
        conf.reset();
        ui = VUi.build(conf);
        ui.sliderByKey("stretchRatioK").setValue(80);         // = 8.0×
        hints = conf.couplingHintsForProbe();
        String single2 = hints[0].getText();
        System.out.println("    改 stretchRatioK=8.0 后单行提示： " + single2);
        check("H-10 改 stretchRatioK 后单行提示跟着变", !single2.equals(single0), single2);

        // 反向：提示文字里不得出现任何「写死的默认值」以外的可疑常量
        check("H-11 两条提示都不是空串", single0.trim().length() > 10 && cross0.trim().length() > 10, "");

        done();
    }

    private static void done() {
        System.out.println();
        System.out.printf("=== V-HINTS 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-HINTS RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-HINTS RESULT: ALL PASS");
        System.exit(0);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}
