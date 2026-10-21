import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * verifier 自建（t4）组件定位器。
 * <p><b>不使用实现者的 *_ForProbe 钩子</b>：全部通过遍历 createPanel() 的组件树定位，
 * 顺序与面板从上到下一致（同行三列的先后 = 滑块 → 输入框 → 数值标签）。
 */
public final class VUi {

    public final List<JSlider> sliders = new ArrayList<>();
    public final List<JTextComponent> textFields = new ArrayList<>();
    public final List<JLabel> valueLabels = new ArrayList<>();
    public final List<JCheckBox> checkBoxes = new ArrayList<>();
    public final List<com.intellij.openapi.ui.ComboBox> combos = new ArrayList<>();
    public final List<JLabel> rowTitles = new ArrayList<>();

    public final DialogPanelHolder panel = new DialogPanelHolder();

    public static final class DialogPanelHolder {
        public JComponent component;
    }

    public static VUi build(NeovideCursorConfigurable conf) {
        VUi ui = new VUi();
        // 必须走 createComponent()：BoundConfigurable 用它缓存 DialogPanel，
        // reset()/apply()/isModified() 都作用在那一个实例上；直接 createPanel() 会造出第二份 UI。
        JComponent panel = conf.createComponent();
        ui.panel.component = panel;
        V0Env.disableValidators(panel);   // 离线探针没有 JBPopupFactory，必须走平台官方的 disableValidation 旁路

        // 面板是扁平结构：每个 row 依次是 [标题 JLabel] [控件...]
        Component[] kids = ((Container) panel).getComponents();
        for (int i = 0; i < kids.length; i++) {
            Component c = kids[i];
            if (c instanceof JSlider) {
                ui.sliders.add((JSlider) c);
                // 【不要假设「滑块之后紧跟输入框」】—— 构建顺序本身就是被测项（V13），
                // 若定位器依赖那个顺序，顺序一反探针就自己崩掉，测的就不是被测代码了。
                // 因此：输入框取「同一行内距离最近的 JTextComponent」（前后都看），
                // 数值标签取「滑块之后第一个 JLabel」。
                ui.textFields.add(nearestTextComponent(kids, i));
                ui.valueLabels.add(firstLabelAfter(kids, i));
            } else if (c instanceof JCheckBox) {
                ui.checkBoxes.add((JCheckBox) c);
            } else if (c instanceof com.intellij.openapi.ui.ComboBox) {
                ui.combos.add((com.intellij.openapi.ui.ComboBox) c);
            } else if (c instanceof JLabel) {
                ui.rowTitles.add((JLabel) c);
            }
        }
        return ui;
    }

    /** 同一行里距离最近的输入框（前或后都算）。 */
    private static JTextComponent nearestTextComponent(Component[] kids, int sliderIndex) {
        for (int d = 1; d < 4; d++) {
            int back = sliderIndex - d;
            int fwd = sliderIndex + d;
            if (back >= 0 && kids[back] instanceof JTextComponent) {
                return (JTextComponent) kids[back];
            }
            if (fwd < kids.length && kids[fwd] instanceof JTextComponent) {
                return (JTextComponent) kids[fwd];
            }
        }
        return null;
    }

    /** 滑块之后第一个 JLabel = 右侧数值标签（滑块之前那个 JLabel 是行标题，不能取）。 */
    private static JLabel firstLabelAfter(Component[] kids, int sliderIndex) {
        for (int j = sliderIndex + 1; j < kids.length && j <= sliderIndex + 4; j++) {
            if (kids[j] instanceof JSlider) {
                break;
            }
            if (kids[j] instanceof JLabel) {
                return (JLabel) kids[j];
            }
        }
        return null;
    }

    public static int valueOf(JTextComponent t) {
        try {
            return Integer.parseInt(t.getText().trim());
        } catch (Throwable e) {
            return Integer.MIN_VALUE;
        }
    }

    // ==================================================================
    // 字段名 + 量程表（探针侧【唯一】刻度来源）
    //
    // 【为什么必须有这张表】0.9.8 之前，探针把「第几个滑块 = 哪个字段 = 什么步长」
    // 抄成两份：VDefects.stepOf(int) 一份、VSnap.snapOnDrag() 的 steps[] 一份。
    // 结果 M4 加了第 11 个滑块（typingSmoothness）之后：
    //   · VDefects 的 steps 数组只有 10 项 → 第 11 项走兜底，且 D0b/D1-1/D1-2/D2-1
    //     四条的判据写死 "10/10" → 永远红（假失败）；
    //   · VSnap 的 steps 数组只有 10 项，但循环上界是 sliders.size()=11
    //     → 第 11 次迭代直接 AIOOBE，**整个探针在第一条断言处就中断**，
    //       后面的 TYP / ILL / BND 十几条断言根本没执行（这是最坏的形态：
    //       红的地方看不见，绿的地方是假的）。
    //   · 两处都把 shadowBlurFactor 的步长抄成 5，实际是 1（见下面的 SPEC）
    //     → 探针强行写 16 并期望落到 5 的网格上，必然失败。
    //
    // 所以：**量程与步长只在下面这张表里写一次**，任何探针要步长都来这里取。
    // 这张表是探针侧的独立副本（不读生产代码的 SliderSpec），因为它就是被校验的对象 —
    // 用被测方的值去校验被测方，等于自证。
    // ==================================================================

    /** 面板从上到下的 11 个滑块字段名（顺序 = 契约 §6.2 + M4 增补的书写顺序）。 */
    public static final String[] KEYS = {
            "cursorDisappearDelay", "cursorFadeOutDuration", "tailOpacity", "glowOpacity",
            "glowLayers", "shadowBlurFactor", "trailSize", "stretchRatioK",
            "animationLength", "shortAnimationLength", "typingSmoothness"};

    /** {min, max, step}，下标与 {@link #KEYS} 一一对应。 */
    private static final int[][] SPEC = {
            {0, 500, 50},      // cursorDisappearDelay   —— 50ms 一档
            {0, 1000, 25},     // cursorFadeOutDuration  —— 25ms 一档
            {0, 100, 5},       // tailOpacity            —— 5% 一档
            {0, 100, 5},       // glowOpacity            —— 5% 一档
            {1, 12, 1},        // glowLayers             —— 逐层
            {0, 100, 1},       // shadowBlurFactor       —— 显示层是「锐度 0–100」，步长 1
            {0, 100, 5},       // trailSize              —— 5% 一档
            {10, 80, 5},       // stretchRatioK          —— 0.5 倍一档
            {5, 35, 1},        // animationLength        —— 10ms 一档
            {2, 25, 1},        // shortAnimationLength   —— 10ms 一档
            {0, 100, 5},       // typingSmoothness       —— 5% 一档（M4 新增，第 11 个）
    };

    /** 滑块字段名（按面板从上到下的顺序）。 */
    public static String nameOf(int index) {
        return index >= 0 && index < KEYS.length ? KEYS[index] : ("#" + index);
    }

    /** 第 index 个滑块的 {min, max, step} 副本；越界返回 null（调用方必须据此判 FAIL）。 */
    public static int[] spec(int index) {
        return index >= 0 && index < SPEC.length ? SPEC[index].clone() : null;
    }

    /** 第 index 个滑块的步长；越界返回 -1（不得静默退化成 1）。 */
    public static int stepOf(int index) {
        int[] s = spec(index);
        return s == null ? -1 : s[2];
    }

    /** 字段名 → 面板下标；找不到返回 -1。 */
    public static int indexOfKey(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }
}
