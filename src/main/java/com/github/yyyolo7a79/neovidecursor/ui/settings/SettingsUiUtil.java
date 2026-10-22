package com.github.yyyolo7a79.neovidecursor.ui.settings;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.util.Locale;

/**
 * 设置面板的<b>纯函数工具集</b>（0.9.9 / Phase 2 第 2 步）。
 *
 * <h2>为什么单独一个类</h2>
 *
 * <p>这 7 个函数原来散在 {@code NeovideCursorConfigurable} 里，占约 60 行。
 * 它们<b>不读任何字段、不碰任何控件</b>，只有输入 → 输出。
 * 留在 2100 行的面板类里，会让「哪些逻辑需要面板状态」这件事变得看不出来。
 *
 * <h2>为什么这些可以无风险地搬走</h2>
 *
 * <p>判据：函数体内<b>不出现</b> {@code this}、不出现任何字段名。
 * 逐个核对过 —— 全都是纯计算。
 *
 * <h2>锐度换算的语义（M6，不要改）</h2>
 *
 * <p>{@link #blurOfSharpness} / {@link #sharpnessOf} 是<b>纯显示层映射</b>：
 * 真正写进 CONFIG 的仍然是 {@code shadowBlurFactor}（外扩幅度），
 * 而它是 {@link NeovideConfig} 的既有常量，本轮一个字节都没动。
 *
 * <p>映射：{@code blur = 0.05 + (100 - sharp) × 0.0055}
 * ⇒ 0 = 外扩 0.60（最柔）、100 = 外扩 0.05（最锐）。
 * 往返误差由 {@link #sharpnessOf} 的四舍五入吸收（同一 blur 读回同一锐度）。
 */
final class SettingsUiUtil {

    private SettingsUiUtil() {
    }

    /** 渲染模式 → 下拉显示文本。两处必须与 {@code MODE_ITEMS} 的常量一致。 */
    static String modeText(NeovideConfig.TrailMode mode, String springText, String afterimageText) {
        return mode == NeovideConfig.TrailMode.AFTERIMAGE ? afterimageText : springText;
    }

    /** 下拉显示文本 → 渲染模式。认不出的一律按 {@code SPRING}（默认值）。 */
    static NeovideConfig.TrailMode textToMode(String text, String afterimageText) {
        return afterimageText.equals(text)
                ? NeovideConfig.TrailMode.AFTERIMAGE
                : NeovideConfig.TrailMode.SPRING;
    }

    /** 下拉选中项是否代表「弹簧模式」。 */
    static boolean isSpringText(Object selected, String afterimageText) {
        return !afterimageText.equals(selected);
    }

    /**
     * 锐度（0–100）→ 外扩幅度（0.60–0.05）。纯显示换算，**不参与渲染**。
     *
     * @param sharp 锐度，越界会被夹到 [0,100]
     */
    static float blurOfSharpness(int sharp) {
        int v = Math.max(0, Math.min(100, sharp));
        return 0.05f + (100 - v) * 0.0055f;
    }

    /** 外扩幅度 → 锐度（0–100）。见 {@link #blurOfSharpness(int)}。 */
    static int sharpnessOf(float blur) {
        float b = Math.max(0.05f, Math.min(0.60f, blur));
        return Math.round((0.60f - b) / 0.0055f);
    }

    /** 两位小数（Locale.ROOT，避免某些区域用逗号做小数点）。 */
    static String fmt2(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * 深拷贝。
     *
     * <p>{@code State} 里全是标量与不可变 {@code String}，所以 {@code copyFrom}
     * 就是完整深拷贝（没有需要逐层复制的引用字段）。
     */
    static NeovideCursorSettings.State deepCopy(NeovideCursorSettings.State from) {
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        if (from != null) {
            s.copyFrom(from);
        }
        return s;
    }
}
