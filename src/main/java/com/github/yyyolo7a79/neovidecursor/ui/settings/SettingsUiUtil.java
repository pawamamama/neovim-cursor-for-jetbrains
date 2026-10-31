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
 * <h2>锐度换算的语义（M6 + P-H-2）</h2>
 *
 * <p>{@link #blurOfSharpness} / {@link #sharpnessOf} 是<b>纯显示层映射</b>：
 * 真正写进 CONFIG 的仍然是 {@code shadowBlurFactor}（外扩幅度），
 * 而它是 {@link NeovideConfig} 的既有常量，本轮一个字节都没动。
 *
 * <p><b>为什么是两段线性、而不是一条直线（2026-10-31 / 第 3 批审查 P-H-2）</b>：
 * 旧映射 {@code blur = 0.05 + (100 - sharp) × 0.0055} 的网格上根本没有
 * {@code State.shadowBlurFactor} 的出厂值 <b>0.2</b> —— 0.2 读回锐度 73，
 * 而 73 又写回 0.1985，差 1.5e-3 ≫ {@code EPS=1e-6}。后果是用户点一下锐度输入框
 * 再移开（<b>什么都没改</b>）→ {@code normalizeTextField()} 把 0.1985 写进 pending
 * → 面板变脏 → 当场派生一个「X 副本」。
 *
 * <p>一条直线不可能同时穿过 (0, 0.60)、(73, 0.20)、(100, 0.05) 三点
 * （0.2 在 0.60→0.05 的 72.7% 处，不是整数刻度）。所以网格改成
 * <b>以默认值为锚点的两段线性</b>：
 * <pre>
 *   锐度 0   → 外扩 0.60   （最柔，端点不变）
 *   锐度 73  → 外扩 0.20   （= 出厂默认，精确落在整数网格上）
 *   锐度 100 → 外扩 0.05   （最锐，端点不变）
 * </pre>
 * 两段的步长都由端点算出，因此 {@code sharpnessOf(blurOfSharpness(v)) == v}
 * 对 0..100 全部成立；与旧映射的最大偏差 0.0015（32px 行高下约 0.05px），
 * 端点和既有取值（0.15↔82 等）一个都没变 —— 观感零变化。
 *
 * <p><b>不要「顺手」把它改回单一直线</b>：那会让默认值重新落到网格外，
 * 上面那条「点一下输入框就多出副本」立刻复发（{@code VBatch3Fixes} 的 T-2 守门）。
 */
final class SettingsUiUtil {

    /**
     * 出厂默认外扩幅度：与 {@code NeovideCursorSettings.State.shadowBlurFactor} 一致。
     *
     * <p><b>改 State 的默认值时必须同步改这里</b>，否则「点一下输入框就变脏」会回来
     * （两边不一致时锚点落空）。{@code VBatch3Fixes} 的 T-2 用
     * {@code new State().shadowBlurFactor} 守门，改漏会红。
     */
    static final float DEFAULT_BLUR = 0.2f;

    /** 默认外扩对应的锐度刻度 —— 网格的锚点，必须落在整数上。 */
    static final int DEFAULT_SHARPNESS = 73;

    /** 最柔（锐度 0）/ 最锐（锐度 100）两端的外扩值，与旧映射一致。 */
    static final float SOFT_BLUR = 0.60f;
    static final float SHARP_BLUR = 0.05f;

    private static final int SOFT_SHARPNESS = 0;
    private static final int SHARP_SHARPNESS = 100;

    /** 73..100 段的步长（默认 → 最锐）。 */
    private static final float STEP_SHARP =
            (DEFAULT_BLUR - SHARP_BLUR) / (SHARP_SHARPNESS - DEFAULT_SHARPNESS);

    /** 0..73 段的步长（最柔 → 默认）。 */
    private static final float STEP_SOFT =
            (SOFT_BLUR - DEFAULT_BLUR) / (DEFAULT_SHARPNESS - SOFT_SHARPNESS);

    static {
        // 两个分母都是编译期常量；一旦有人把锚点改到端点，步长会变成 ±Inf / NaN，
        // 整个映射静默失效。宁可类加载时立刻炸，也不要用户看到一条歪掉的网格。
        if (DEFAULT_SHARPNESS <= SOFT_SHARPNESS || DEFAULT_SHARPNESS >= SHARP_SHARPNESS) {
            throw new IllegalStateException(
                    "锐度网格锚点必须在 (0,100) 内部：DEFAULT_SHARPNESS=" + DEFAULT_SHARPNESS);
        }
    }

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
     * <p>两段线性，锚点是默认值（73 ↔ 0.20）。见类注释里的「为什么」。
     * 写法故意让 {@code v == DEFAULT_SHARPNESS} 时乘法项为 0 ——
     * 这样默认值返回的是<b>逐位相等</b>的 0.2f，而不是「无限接近」。
     *
     * @param sharp 锐度，越界会被夹到 [0,100]
     */
    static float blurOfSharpness(int sharp) {
        int v = Math.max(SOFT_SHARPNESS, Math.min(SHARP_SHARPNESS, sharp));
        if (v >= DEFAULT_SHARPNESS) {
            return DEFAULT_BLUR + (DEFAULT_SHARPNESS - v) * STEP_SHARP;
        }
        return DEFAULT_BLUR + (DEFAULT_SHARPNESS - v) * STEP_SOFT;
    }

    /** 外扩幅度 → 锐度（0–100）。见 {@link #blurOfSharpness(int)}。 */
    static int sharpnessOf(float blur) {
        // NaN 下 Math.min/max 不夹取，Math.round(NaN)=0 会静默变成「最柔」。
        // 显式给默认档：读不出来的值按出厂默认显示，至少不会把用户带到网格另一头。
        if (Float.isNaN(blur)) {
            return DEFAULT_SHARPNESS;
        }
        float b = Math.max(SHARP_BLUR, Math.min(SOFT_BLUR, blur));
        if (b >= DEFAULT_BLUR) {
            return Math.round(DEFAULT_SHARPNESS - (b - DEFAULT_BLUR) / STEP_SOFT);
        }
        return Math.round(DEFAULT_SHARPNESS + (DEFAULT_BLUR - b) / STEP_SHARP);
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
