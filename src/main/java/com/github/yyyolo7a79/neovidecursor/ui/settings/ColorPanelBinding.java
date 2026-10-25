package com.github.yyyolo7a79.neovidecursor.ui.settings;

import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.intellij.ui.ColorPanel;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link ColorPanel} 与 {@link NeovideCursorSettings.State} 某个 HEX 字段的双向绑定。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>{@code ColorPanel} <b>不是</b> {@code DialogPanel} 的绑定控件 —— 平台不追踪它。
 * 于是每个颜色面板都要在<b>三个方向</b>各写一遍手工同步：
 *
 * <pre>
 *   reset()      State → 控件   panel.setSelectedColor(parseHex(s.tailColor))
 *   apply()      控件 → State   s.tailColor = toHex(panel.getSelectedColor())
 *   isModified() 控件 vs State  比对两者是否不同
 * </pre>
 *
 * <p><b>漏一个的后果是静默的</b>：
 * <ul>
 *   <li>漏 reset → 打开面板看到默认色而不是用户选的色</li>
 *   <li>漏 apply → 选了颜色点「应用」没保存</li>
 *   <li>漏 isModified → 改了颜色「应用」按钮不点亮，用户以为没改上</li>
 * </ul>
 *
 * <p>0.9.67 之前有 2 个面板 × 3 个方向 = 6 处，加上 refreshPresets 里的回读共 10 处
 * 手写代码。加第三个颜色面板时要再写 3 处，很容易漏。
 *
 * <h2>它怎么解决</h2>
 *
 * <p>把「哪个控件 ↔ 哪个字段 ↔ 怎么转换」在<b>一处</b>说清，三个方向都由这里提供。
 * 调用方只剩三次批量调用：
 *
 * <pre>
 *   colorBindings.pullAll(state);      // reset
 *   colorBindings.pushAll(state);      // apply
 *   colorBindings.anyDiffers(state);   // isModified
 * </pre>
 *
 * <p>加第三个颜色面板 = 往 Group 里加一个实例，三个方向自动全有。
 * 探针 {@code VColorBinding} 断言「Group 里的实例数 == 面板上的 ColorPanel 数」，
 * 漏登记会红。
 */
final class ColorPanelBinding {

    private final String name;
    private final Supplier<ColorPanel> panelRef;
    private final Function<NeovideCursorSettings.State, String> getter;
    private final BiConsumer<NeovideCursorSettings.State, String> setter;

    /**
     * @param name     字段名（诊断与探针用）
     * @param panelRef 取控件引用 —— 用 Supplier 而不是直接传值，
     *                 因为控件在 createPanel 里才创建，而绑定要在那之前登记
     * @param getter   State → HEX
     * @param setter   (State, HEX) → void
     */
    ColorPanelBinding(String name,
                      Supplier<ColorPanel> panelRef,
                      Function<NeovideCursorSettings.State, String> getter,
                      BiConsumer<NeovideCursorSettings.State, String> setter) {
        this.name = name;
        this.panelRef = panelRef;
        this.getter = getter;
        this.setter = setter;
    }

    String name() {
        return name;
    }

    /** 控件 → State。控件为空或未选色时不动 State（保持原值）。 */
    void pushTo(NeovideCursorSettings.State s) {
        ColorPanel p = panelRef.get();
        if (p == null || s == null) {
            return;
        }
        Color c = p.getSelectedColor();
        if (c == null) {
            return;
        }
        setter.accept(s, NeovideCursorConfigurable.toHex(c));
    }

    /** State → 控件。控件为空时跳过（未建面板的场景）。 */
    void pullFrom(NeovideCursorSettings.State s) {
        ColorPanel p = panelRef.get();
        if (p == null || s == null) {
            return;
        }
        p.setSelectedColor(NeovideCursorConfigurable.parseHex(getter.apply(s)));
    }

    /** 控件与 State 是否不同（isModified 用）。控件为空时视为「没不同」。 */
    boolean differs(NeovideCursorSettings.State s) {
        ColorPanel p = panelRef.get();
        if (p == null || s == null) {
            return false;
        }
        Color c = p.getSelectedColor();
        if (c == null) {
            return false;
        }
        String panelHex = NeovideCursorConfigurable.toHex(c);
        String stateHex = getter.apply(s);
        return panelHex != null && !panelHex.equalsIgnoreCase(stateHex);
    }

    /** 一组绑定：三个方向各一次调用即可覆盖全部颜色面板。 */
    static final class Group {
        private final List<ColorPanelBinding> items = new ArrayList<>();

        void add(ColorPanelBinding b) {
            items.add(b);
        }

        int size() {
            return items.size();
        }

        List<ColorPanelBinding> all() {
            return items;
        }

        void pullAll(NeovideCursorSettings.State s) {
            for (ColorPanelBinding b : items) {
                b.pullFrom(s);
            }
        }

        void pushAll(NeovideCursorSettings.State s) {
            for (ColorPanelBinding b : items) {
                b.pushTo(s);
            }
        }

        boolean anyDiffers(NeovideCursorSettings.State s) {
            for (ColorPanelBinding b : items) {
                if (b.differs(s)) {
                    return true;
                }
            }
            return false;
        }
    }
}