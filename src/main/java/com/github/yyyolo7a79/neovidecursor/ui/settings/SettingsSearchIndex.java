package com.github.yyyolo7a79.neovidecursor.ui.settings;

import javax.swing.JLabel;
import java.util.List;

/**
 * 设置页搜索的关键字映射（0.9.9 / Phase 2 第 1 步：从面板类抽出）。
 *
 * <h2>为什么单独一个类</h2>
 *
 * <p>这段逻辑原来在 {@code NeovideCursorConfigurable} 里，占 77 行，
 * 但它<b>只依赖「字段 key → 数值标签」这一件事</b>（一个 {@code List<SliderBinding>}），
 * 与面板的任何状态都无关。把它留在 2000 行的面板类里，只会让「这个类到底管什么」更糊。
 *
 * <p><b>行为逐字不变</b>：关键字顺序、命中后的目标字段、{@code has()} 的子串语义
 * 全部与抽取前完全一致 —— 搜索是用户可感知的功能，任何「顺手优化」都是风险。
 *
 * <h2>为什么不做像素级高亮</h2>
 *
 * <p>平台自己的 {@code TraverseUIStarter} 才是搜索高亮的权威实现，
 * 手工再写一套只会与它打架。
 */
final class SettingsSearchIndex {

    private SettingsSearchIndex() {
    }

    /**
     * 关键字 → 面板上最能代表它的那个数值标签。
     *
     * <p><b>顺序有意义</b>：先匹配到的赢。「常驻 / 高帧」必须排在「辉光 / 层」前面，
     * 否则搜「常驻刷新」会先命中 glowLayers 那条。
     *
     * @param bindings 面板的滑块绑定（提供「字段 key → 数值标签」映射）
     * @param key      已 {@code trim().toLowerCase()} 的关键字
     * @return 目标标签；无匹配返回 {@code null}
     */
    static JLabel targetFor(List<NeovideCursorConfigurable.SliderBinding> bindings, String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        if (has(key, "常驻", "高帧", "highfps", "fps", "刷新")) {
            return labelOf(bindings, "glowLayers");
        }
        if (has(key, "淡出", "fade")) {
            return labelOf(bindings, "cursorDisappearDelay");
        }
        if (has(key, "层", "layer", "辉光", "glow")) {
            return labelOf(bindings, "glowLayers");
        }
        if (has(key, "外扩", "模糊", "blur", "光晕")) {
            return labelOf(bindings, "shadowBlurFactor");
        }
        if (has(key, "不透明", "透明", "opacity")) {
            return labelOf(bindings, "glowOpacity");
        }
        if (has(key, "拉伸", "stretch", "拖尾", "trail")) {
            return labelOf(bindings, "stretchRatioK");
        }
        if (has(key, "密度", "size")) {
            return labelOf(bindings, "trailSize");
        }
        if (has(key, "跨行", "animationLength", "动画时长")) {
            return labelOf(bindings, "animationLength");
        }
        if (has(key, "短动画", "shortanimation", "打字")) {
            return labelOf(bindings, "shortAnimationLength");
        }
        return null;
    }

    /** 按字段 key 找对应的数值标签；找不到返回 {@code null}。 */
    private static JLabel labelOf(List<NeovideCursorConfigurable.SliderBinding> bindings, String key) {
        if (bindings == null) {
            return null;
        }
        for (NeovideCursorConfigurable.SliderBinding b : bindings) {
            if (key.equals(b.spec.key)) {
                return b.valueLabel;
            }
        }
        return null;
    }

    /** 任一 needle 出现在 haystack 里即为真（子串匹配，不是分词）。 */
    private static boolean has(String haystack, String... needles) {
        if (haystack == null) {
            return false;
        }
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
