import com.github.yyyolo7a79.neovidecursor.core.GlowIntensity;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;

/**
 * V-PREVIEW-PARITY：预览与生产必须收到同一组参数。
 *
 * <p>锁四个用户报过的 bug：
 *   1. 斜度拉到 0，预览还是斜的（deriveTrailFactors 漏传 slope）
 *   2. 选「彩虹（动态）」，预览完全不变（没推 cursorRgbWash）
 *   3. 开辉光后预览里辉光不对、拖参数没反应、「关掉设置再打开才好」
 *      → 复选框（bindCheck）改动从来不推送预览：setter 只写 pending，
 *        全文件没有任何监听器把它接到 refreshPreviews 上。
 *   4. 预览的辉光「永不熄灭、亮度曲线和编辑器不同」
 *      → 预览从不调 setGlowFade、强度用自己的 maxOffset/8 而不是生产的 (offset-0.5)/2。
 *
 * <p><b>第 3/4 条为什么能进这个探针</b>：它们和 1/2 是同一个病因 ——
 * 「预览收到的东西 ≠ 生产收到的东西」。纪律四-G 说的是探针不许复刻生产逻辑，
 * 同一精神要求：同一个参数只允许有一条链路。这里逐条断言那条链路。
 */
public final class VPreviewParity {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        V0Env.install();
        System.out.println("=== V-PREVIEW-PARITY ===");

        // ---------- 1. 斜度进预览 ----------
        NeovideCursorSettings.State s = V0Env.svc.getState();
        s.stretchRatioK = 4.5f;
        s.typingSmoothness = 0f;
        s.slopeTicks = 0;

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        // 必须 reset 一次：构建面板时 previewConfig 还是构造默认值，
        // refreshPreviews 要等 reset() 才跑第一遍（那正是真实打开设置页的时序）。
        conf.reset();

        float[] atZero = previewFactors(conf);
        check("1 斜度 0 时预览的 trailFactors 是纯矩形（同侧两角相等）",
                atZero != null && Math.abs(atZero[0] - atZero[1]) < 0.02f,
                atZero == null ? "null" : fmt(atZero));

        s.slopeTicks = 100;
        conf.reset();
        float[] at100 = previewFactors(conf);
        check("2 斜度 100 时预览的 trailFactors 完全斜（同侧两角分离）",
                at100 != null && Math.abs(at100[0] - at100[1]) > 0.15f,
                at100 == null ? "null" : fmt(at100));

        check("3 斜度 0 与 100 的系数确实不同（证明 preview 真的读了 slope）",
                atZero != null && at100 != null
                        && Math.abs(atZero[0] - at100[0]) > 0.05f,
                (atZero == null || at100 == null) ? "null"
                        : "0档=" + fmt(atZero) + " 100档=" + fmt(at100));

        // ---------- 2. 彩虹进预览 ----------
        CursorPresets.ColorPreset rainbow = null;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p.rgbWash()) rainbow = p;
        }
        check("4 找到彩虹预设", rainbow != null, rainbow == null ? "无" : rainbow.name());

        if (rainbow != null) {
            s.tailColor = rainbow.tailColor();
            s.shadowColor = rainbow.shadowColor();
            s.rgbGradient = true;
            conf.reset();
            boolean isRainbow = previewRainbow(conf);
            // 面板的 pending 已经和 state 同步，isRainbowSelected 基于 pending
            check("5 选彩虹后预览收到 cursorRgbWash（彩虹能到预览）",
                    isRainbow, "预览 rgbWash=" + isRainbow);
        }

        // ---------- 3. 复选框改动要推送预览（2026-10-29） ----------
        //
        // 用户原话：「第一次从个人自定义预设开辉光，动画预览窗口光标辉光很大……
        //           只有关闭设置回来才好」。
        // 根因是 bindCheck 的 MutableProperty.set() 只写 pending 就结束了，
        // 于是 previewConfig.useShadow 永远停在「上一次 refreshPreviews 时的值」。
        //
        // 【为什么用「行标题」定位复选框】面板里所有复选框都是 row.checkBox("")，
        // 行内文字为空字符串，VUi.checkBoxByLabel("启用辉光") 找不到（返回 null）。
        // VRegr2 的 R-8 就是因此在 setSelected() 处 NPE 崩溃的。
        s.useShadow = false;
        conf.reset();                       // 先把预览推到「辉光关」
        Object before = previewField(conf, "useShadow");
        check("6 复位后预览的 useShadow = false（前置条件）",
                Boolean.FALSE.equals(before), "previewConfig.useShadow=" + before);

        JCheckBox shadowBox = checkBoxInRow(ui.panel.component, "启用辉光");
        check("7 定位到「启用辉光」复选框（按行标题，不按空文案）",
                shadowBox != null, shadowBox == null ? "未找到" : "ok");

        if (shadowBox != null) {
            // 【必须用 doClick(0)，不能用 setSelected】
            // Kotlin UI DSL 的 bindSelected 挂的是 ActionListener，而
            // JCheckBox.setSelected() 只触发 ItemListener / ChangeListener ——
            // 程序化改选中态**不会**走 setter。VRegr2 的 R-8 就是因为直接
            // setSelected 而观察不到任何效果（最后在 NPE 处崩掉整个探针）。
            // doClick(0) 才是「用户点了一下」：同步触发 actionPerformed。
            boolean start = shadowBox.isSelected();
            shadowBox.doClick(0);
            boolean nowSel = shadowBox.isSelected();
            boolean pend = conf.pendingForProbe().useShadow;
            Object after = previewField(conf, "useShadow");
            check("8 勾选「启用辉光」当场就推到预览（原来要关掉设置再打开）",
                    nowSel != start && pend == nowSel && Boolean.valueOf(nowSel).equals(after),
                    "勾选态 " + start + "→" + nowSel + " pending.useShadow=" + pend
                            + " previewConfig.useShadow=" + after);

            shadowBox.doClick(0);
            boolean nowSel2 = shadowBox.isSelected();
            boolean pend2 = conf.pendingForProbe().useShadow;
            Object after2 = previewField(conf, "useShadow");
            check("9 取消勾选也当场生效（双向都推）",
                    nowSel2 != nowSel && pend2 == nowSel2 && Boolean.valueOf(nowSel2).equals(after2),
                    "勾选态 " + nowSel + "→" + nowSel2 + " pending.useShadow=" + pend2
                            + " previewConfig.useShadow=" + after2);
        }

        // ---------- 4. 淡出三件套要推到预览 ----------
        s.overlayFadeOut = true;
        s.cursorDisappearDelay = 120;
        s.cursorFadeOutDuration = 0.2f;
        conf.reset();
        Object fadeOn = previewField(conf, "overlayFadeOut");
        Object delay = previewField(conf, "cursorDisappearDelay");
        Object dur = previewField(conf, "cursorFadeOutDuration");
        check("10 预览收到淡出三件套（开关 / 保持时长 / 熄灭时长）",
                Boolean.TRUE.equals(fadeOn)
                        && Integer.valueOf(120).equals(delay)
                        && Math.abs(((Number) (dur == null ? -1 : dur)).floatValue() - 0.2f) < 1e-4f,
                "fade=" + fadeOn + " delay=" + delay + " dur=" + dur);

        // ---------- 5. 辉光曲线只有一份实现 ----------
        //
        // 这两条锁的是「生产与预览共用 GlowIntensity」这件事本身：
        // 边界值一变（比如有人又加回 offset/8），上面 3/4 的接线仍然成立，
        // 但观感会再次分家 —— 所以曲线边界必须由探针钉死。
        check("11 强度曲线：0.5px 起算、2.5px 满强度（= 生产原值）",
                GlowIntensity.strengthFor(0.0) == 0f
                        && GlowIntensity.strengthFor(0.5) == 0f
                        && Math.abs(GlowIntensity.strengthFor(1.5) - 0.5f) < 1e-4f
                        && GlowIntensity.strengthFor(2.5) == 1f
                        && GlowIntensity.strengthFor(99.0) == 1f,
                String.format("0→%.2f 0.5→%.2f 1.5→%.2f 2.5→%.2f 99→%.2f",
                        GlowIntensity.strengthFor(0.0), GlowIntensity.strengthFor(0.5),
                        GlowIntensity.strengthFor(1.5), GlowIntensity.strengthFor(2.5),
                        GlowIntensity.strengthFor(99.0)));

        long t0 = 1_000_000_000L;           // 基准时刻（ns）
        float fOff = GlowIntensity.fade(false, 0L, t0, 50, 0.1);                     // 开关关
        float fNever = GlowIntensity.fade(true, 0L, t0, 50, 0.1);                    // 从未亮过
        float fHold = GlowIntensity.fade(true, t0, t0 + 40_000_000L, 50, 0.1);       // 40ms < delay
        float fMid = GlowIntensity.fade(true, t0, t0 + 100_000_000L, 50, 0.1);       // 50ms 后 50ms → 半程
        float fDone = GlowIntensity.fade(true, t0, t0 + 200_000_000L, 50, 0.1);      // 超出 fade 时长
        check("12 淡出曲线：关开关→1 / 从未亮→0 / delay 内→1 / 半程≈0.5 / 超时→0",
                fOff == 1f && fNever == 0f && fHold == 1f
                        && Math.abs(fMid - 0.5f) < 0.02f && fDone == 0f,
                String.format("off=%.2f never=%.2f hold=%.2f mid=%.2f done=%.2f",
                        fOff, fNever, fHold, fMid, fDone));

        System.out.println();
        System.out.printf("=== PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("RESULT: FAILED"); System.exit(1); }
        System.out.println("RESULT: ALL PASS");
    }

    /**
     * 按<b>行标题</b>找那个行里的复选框。
     *
     * <p>面板里所有复选框都是 {@code row.checkBox("")} —— 行内文案为空，标题在左侧
     * 那一列。所以 {@code VUi.checkBoxByLabel} 对它们一律返回 null
     * （VRegr2 的 R-8 就是栽在这里，NPE 崩掉整个探针）。
     *
     * <p>判据是结构性的：某个容器<b>直接子级</b>里有文字匹配的 JLabel，
     * 且它的子树里有 JCheckBox → 那就是这一行。
     */
    private static JCheckBox checkBoxInRow(Component root, String titlePart) {
        if (!(root instanceof Container)) {
            return null;
        }
        Container c = (Container) root;
        for (Component k : c.getComponents()) {
            if (k instanceof JLabel) {
                String t = ((JLabel) k).getText();
                if (t != null && t.contains(titlePart)) {
                    JCheckBox cb = firstCheckBox(c);
                    if (cb != null) {
                        return cb;
                    }
                }
            }
        }
        for (Component k : c.getComponents()) {
            if (k instanceof Container) {
                JCheckBox r = checkBoxInRow(k, titlePart);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static JCheckBox firstCheckBox(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof JCheckBox) {
                return (JCheckBox) k;
            }
            if (k instanceof Container) {
                JCheckBox r = firstCheckBox((Container) k);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 反射读面板 previewConfig 的某个字段（预览真正拿去画的那份配置）。 */
    static Object previewField(NeovideCursorConfigurable conf, String fieldName) {
        try {
            java.lang.reflect.Field f =
                    NeovideCursorConfigurable.class.getDeclaredField("previewConfig");
            f.setAccessible(true);
            Object cfg = f.get(conf);
            if (cfg == null) {
                return null;
            }
            java.lang.reflect.Field target = cfg.getClass().getField(fieldName);
            return target.get(cfg);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射取面板的 previewConfig.trailFactors */
    static float[] previewFactors(NeovideCursorConfigurable conf) {
        try {
            java.lang.reflect.Field f =
                    NeovideCursorConfigurable.class.getDeclaredField("previewConfig");
            f.setAccessible(true);
            Object cfg = f.get(conf);
            java.lang.reflect.Field tf = cfg.getClass().getField("trailFactors");
            return (float[]) tf.get(cfg);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射取面板的 isRainbowSelected 结果 */
    static boolean previewRainbow(NeovideCursorConfigurable conf) {
        try {
            java.lang.reflect.Method m =
                    NeovideCursorConfigurable.class.getDeclaredMethod("isRainbowSelected");
            m.setAccessible(true);
            return (Boolean) m.invoke(conf);
        } catch (Throwable t) {
            return false;
        }
    }

    static String fmt(float[] f) {
        if (f == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < f.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.2f", f[i]));
        }
        return sb.append("]").toString();
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}