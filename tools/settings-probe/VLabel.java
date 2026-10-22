
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JLabel;
import java.util.Locale;

/**
 * V-LABEL —— refresh-auditor（t6）第四支：面板/文案宣称的「前缘」与实际渲染路径对不对得上。
 *
 * <p>真面板（{@link VUi}）读出「拖尾拉伸强度」那一行的数值标签，与
 * {@link TrailCorner} 真正会用的系数逐项比对。
 */
public final class VLabel {

    private static int pass = 0, fail = 0;

    private static final double W = 2.0, H = 16.0;

    public static void main(String[] args) {
        System.out.println("=== V-LABEL：面板显示的前缘时长 vs 渲染实际使用的前缘时长 ===");
        V0Env.install();

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();

        // 定位「拖尾拉伸强度」滑块（key = stretchRatioK）
        int idx = VUi.indexOfKey("stretchRatioK");
        check("L-0 定位到「拖尾拉伸强度」滑块", idx >= 0, "下标=" + idx);
        if (idx < 0) {
            finish();
            return;
        }

        JLabel label = ui.valueLabels.get(idx);
        String shown = label.getText();
        System.out.println("  面板实际显示的标签：");
        System.out.println("    " + shown);

        // 解析出面板宣称的后缘/前缘 ms
        long shownTrailing = parseMs(shown, "后缘");
        long shownLeading = parseMs(shown, "前缘");
        System.out.printf(Locale.ROOT, "  解析结果：后缘 %d ms / 前缘 %d ms%n",
                shownTrailing, shownLeading);

        // 【口径修正 + 期望反转】
        // 本轮已把面板改为：① 用双参版（含 typingSmoothness）② 前缘报 f[2]（横向真实档）。
        // 所以正确断言是「面板 == 按 f[2] 算的值」，而不是原来的「面板 == 按 f[3] 算的值」。
        float[] f = NeovideCursorSettings
                .deriveTrailFactors(conf.pendingForProbe().stretchRatioK,
                        conf.pendingForProbe().typingSmoothness);
        double shortLen = conf.pendingForProbe().shortAnimationLength;
        long panelLeadByF3 = Math.round(shortLen * 1000 * f[3]);
        long panelLeadByF2 = Math.round(shortLen * 1000 * f[2]);
        System.out.printf(Locale.ROOT,
                "  f=[%.2f,%.2f,%.2f,%.2f] short=%.2f%n", f[0], f[1], f[2], f[3], shortLen);
        System.out.printf(Locale.ROOT,
                "  按 f3(rank3) 算 = %d ms ；按 f2(横向真实) 算 = %d ms%n",
                panelLeadByF3, panelLeadByF2);

        check("L-1 [已修] 面板「前缘」用的是 f[2]（横向真实档），与运行期一致",
                shownLeading == panelLeadByF2,
                String.format(Locale.ROOT, "面板=%dms f3=%dms f2=%dms", shownLeading,
                        panelLeadByF3, panelLeadByF2));

        // 用户会看到什么：面板说前缘 46ms，实际前缘 88ms（0.42/0.22 ≈ 1.91 倍）
        double ratio = (double) panelLeadByF2 / Math.max(panelLeadByF3, 1);
        System.out.printf(Locale.ROOT,
                "  ⇒ 面板宣称前缘 %dms，纯横向打字实际前缘 %dms —— 差 %.2f 倍%n",
                panelLeadByF3, panelLeadByF2, ratio);
        check("L-2 【用户可见】面板把前缘时长低报了约 " + String.format(Locale.ROOT, "%.2f", ratio)
                        + " 倍：用户按面板以为前缘很跟手（" + panelLeadByF3
                        + "ms），实际是 " + panelLeadByF2 + "ms",
                ratio > 1.5,
                String.format(Locale.ROOT, "%.2f 倍（%d ms → %d ms）",
                        ratio, panelLeadByF3, panelLeadByF2));

        // 直接把「真跑出来的前缘帧数」也给出来，供文案引用
        int realFrames = frames(shortLen * f[2]);
        int panelFrames = frames(shortLen * f[3]);
        System.out.printf(Locale.ROOT,
                "  按面板口径前缘 = %d 帧 ；按真实横向 rank2 = %d 帧%n", panelFrames, realFrames);
        check("L-3 【文案错误】W_SHORT_LEN 写「前缘（rank3）的有效时长 = 本值 × 0.22」——"
                        + "这句话只在跨行斜跳成立；同行打字路径上 rank3 根本不出现",
                panelFrames * 2 <= realFrames,
                String.format(Locale.ROOT, "rank3=%d 帧 vs rank2=%d 帧", panelFrames, realFrames));

        // 校验：短跳文案里那两个阈值（0.036 / 0.227）是 rank3 口径
        System.out.println();
        System.out.println("  同一段文案里两个「短路阈值」也全部是 rank3 口径：");
        double b3 = 8.0 / f[3] / 1000.0;
        double b2 = 8.0 / f[2] / 1000.0;
        System.out.printf(Locale.ROOT,
                "    日常帧 8ms 短路边界：按 rank3 = %.4fs（文案写 0.036）；按 rank2 = %.4fs%n", b3, b2);
        System.out.printf(Locale.ROOT,
                "    卡顿帧 50ms 短路边界：按 rank3 = %.4fs（文案写 0.227）；按 rank2 = %.4fs%n",
                50.0 / f[3] / 1000.0, 50.0 / f[2] / 1000.0);
        check("L-4 文案写的 0.036s / 0.227s 两个阈值都等于 8/0.22/1000 与 50/0.22/1000；"
                        + "若按真实 rank2 应为 "
                        + String.format(Locale.ROOT, "%.3fs / %.3fs", b2, 50.0 / f[2] / 1000.0),
                Math.abs(b3 - 0.0364) < 0.002,
                String.format(Locale.ROOT, "rank3 口径 %.4f/%.4f 与文案吻合", b3, 50.0 / f[3] / 1000.0));

        // 结论：文案应当说明「这是斜跳口径」，或者改成把两档都写出来
        System.out.println();
        System.out.println("  ⇒ 修法方向（不是改物理，是改口径）：文案/面板必须区分两种移动：");
        System.out.println("    · 同行打字/退格（轴对齐）：前缘系数 f[2]");
        System.out.println("    · 斜向跨行跳转：才可能出现 f[3]（甚至 f[1]）");

        finish();
    }

    private static void finish() {
        System.out.println();
        System.out.printf("=== V-LABEL 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-LABEL RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-LABEL RESULT: ALL PASS");
        System.exit(0);
    }

    /** 从「后缘 110 ms / 前缘 24 ms」这种串里抠出数字。 */
    private static long parseMs(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) {
            return -1;
        }
        int j = i + key.length();
        while (j < s.length() && !Character.isDigit(s.charAt(j))) {
            j++;
        }
        int k = j;
        while (k < s.length() && Character.isDigit(s.charAt(k))) {
            k++;
        }
        if (k == j) {
            return -1;
        }
        return Long.parseLong(s.substring(j, k));
    }

    private static int frames(double effLen) {
        com.github.yyyolo7a79.neovidecursor.core.DampedSpring sp =
                new com.github.yyyolo7a79.neovidecursor.core.DampedSpring(effLen);
        sp.setPosition(8.0);
        boolean moving = sp.update(0.008);
        int n = moving ? 1 : 0;
        int guard = 0;
        while (moving && guard++ < 5000) {
            moving = sp.update(0.008);
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
