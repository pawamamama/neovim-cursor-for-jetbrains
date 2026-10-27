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

    /**
     * 面板从上到下的 13 个滑块字段名。
     *
     * <p><b>0.9.71 补了 2 项</b>：{@code cursorWidthPx} / {@code cursorHeightRatio}
     * 在 0.9.57 加入面板时本表没跟上 —— 那份「第 11 个滑块走兜底」的旧注释说的问题
     * 又重演了一次。现在 13 项与面板实际一致。
     */
    public static final String[] KEYS = {
            // ===== 【顺序 = 面板真实渲染顺序，不是 SPECS 声明顺序】=====
            //
            // 这两个顺序从 0.9.62（S3 拆分 createPanel）起就不一致：
            //   SPECS 数组按「字段类别」声明；
            //   面板按 buildXxxGroup 的【调用顺序】渲染（Inline → Cross → Color →
            //   Color → Cursor → Glow → Fade）。
            //   ★ 注意 Cursor 在 Glow 之前（NeovideCursorConfigurable:851/854）。
            //
            // 之前 VUi 用 SPECS 顺序，探针拿到的 key 与实际滑块错位，
            // 但因为每档量程恰好能对上部分，没有立即爆红 —— 直到 0.9.79 加了
            // 第 14 个滑块（slopeTicks）才全线暴露：面板[0] 量程 [2,25]（= cursorFadeOut
            // 的旧值）却对应 KEYS[0]=cursorDisappearDelay。
            //
            // 现在严格按渲染顺序。校验方法：跑 ZzSliderOrder 打印面板量程，
            // 与下面 SPEC 的 [min,max] 逐项比对。
            "shortAnimationLength",   // [0]  Inline 组
            "typingSmoothness",       // [1]  Inline 组
            "stretchRatioK",          // [2]  Inline 组
            "slopeTicks",             // [3]  Inline 组（0.9.79 新增）
            "trailSize",              // [4]  Cross 组
            "animationLength",        // [5]  Cross 组
            "tailOpacity",            // [6]  Color 组
            "cursorWidthPx",          // [7]  Cursor 组（★ 在 Glow 之前，见 851/854 行）
            "cursorHeightRatio",      // [8]  Cursor 组
            "glowOpacity",            // [9]  Glow 组
            "glowLayers",             // [10] Glow 组
            "shadowBlurFactor",       // [11] Glow 组
            "cursorDisappearDelay",   // [12] Fade 组
            "cursorFadeOutDuration"   // [13] Fade 组
    };

    /** {min, max, step}，下标与 {@link #KEYS} 一一对应。 */
    private static final int[][] SPEC = {
            // 下标与 KEYS 一一对应（= 面板渲染顺序，见 KEYS 上方注释）
            {2, 25, 1},        // [0]  shortAnimationLength   —— 10ms 一档
            {0, 100, 5},       // [1]  typingSmoothness       —— 5% 一档
            {10, 80, 5},       // [2]  stretchRatioK          —— 0.5 倍一档
            {0, 100, 5},       // [3]  slopeTicks             —— 5% 一档（0.9.80 起进预设域，82=上游）
            {0, 100, 5},       // [4]  trailSize              —— 5% 一档
            {5, 35, 1},        // [5]  animationLength        —— 10ms 一档
            {0, 100, 5},       // [6]  tailOpacity            —— 5% 一档
            {1, 5, 1},         // [7]  cursorWidthPx          —— 1px 一档
            {30, 100, 10},     // [8]  cursorHeightRatio      —— 10% 一档
            {0, 100, 5},       // [9]  glowOpacity            —— 5% 一档
            {1, 12, 1},        // [10] glowLayers             —— 逐层
            {0, 100, 1},       // [11] shadowBlurFactor       —— 锐度 0–100，步长 1
            {0, 500, 50},      // [12] cursorDisappearDelay   —— 50ms 一档
            {0, 1000, 25},     // [13] cursorFadeOutDuration  —— 25ms 一档
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

    // ==================================================================
    // 按【文案】定位 —— 把「定位」与「验证顺序」两件事分开
    //
    // 【为什么需要这一组】0.9.71 之前探针普遍写成：
    //     JCheckBox diag = ui.checkBoxes.get(5);       // 用索引定位
    //     check("勾选后换预设，勾选仍在", ...);
    //
    // 这句话其实混了两个意图：
    //     ① 我想拿到「性能诊断」这个复选框          ← 定位
    //     ② 我想验证"第 6 个复选框就是性能诊断"    ← 顺序契约
    //
    // 混在一起的后果：S3 重构把组顺序改了，get(5) 从 paintDiag 变成 rgbGradient，
    // 于是断言红在「勾选后变了」——**报的是症状，不是病因**。
    // 我被这条错误信息误导，先怀疑了自己的下拉逻辑，浪费了一轮。
    //
    // 正确做法是分开写：
    //     assertIndex(ui, 5, "性能诊断");               // ① 顺序契约，变了就红得明白
    //     JCheckBox diag = ui.checkBoxByLabel("性能诊断"); // ② 定位，与顺序解耦
    //
    // 这样顺序一变，第一条报「第 6 个不再是性能诊断」，一眼知道病因。
    // ==================================================================

    /**
     * 按文案找复选框（包含匹配，忽略空白）。
     *
     * @return 找到的复选框；找不到返回 {@code null}（调用方必须据此判 FAIL）
     */
    public JCheckBox checkBoxByLabel(String labelPart) {
        for (JCheckBox cb : checkBoxes) {
            if (labelContains(cb.getText(), labelPart)) {
                return cb;
            }
        }
        return null;
    }

    /** 按文案找复选框的下标；找不到返回 -1。 */
    public int checkBoxIndexOf(String labelPart) {
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (labelContains(checkBoxes.get(i).getText(), labelPart)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 断言「第 {@code expectedIndex} 个复选框的文案包含 {@code labelPart}」。
     *
     * <p>这是顺序契约的显式表达 —— 与 {@link #checkBoxIndexOf} 分开用，
     * 前者验顺序、后者做定位。
     *
     * @return {@code true} 表示顺序符合预期
     */
    public boolean assertCheckBoxAt(int expectedIndex, String labelPart) {
        if (expectedIndex < 0 || expectedIndex >= checkBoxes.size()) {
            return false;
        }
        return labelContains(checkBoxes.get(expectedIndex).getText(), labelPart);
    }

    /** 按字段 key 找滑块（用 {@link #indexOfKey} + {@link #sliders}）；找不到返回 null。 */
    public JSlider sliderByKey(String key) {
        int i = indexOfKey(key);
        return (i >= 0 && i < sliders.size()) ? sliders.get(i) : null;
    }

    /** 按滑块取同一行里的输入框（与 {@link #sliderByKey} 配套）。找不到返回 null。 */
    public JTextComponent textFieldOf(JSlider slider) {
        if (slider == null) {
            return null;
        }
        int i = sliders.indexOf(slider);
        return (i >= 0 && i < textFields.size()) ? textFields.get(i) : null;
    }

    /**
     * 按滑块取同一行里的数值标签（与 {@link #textFieldOf} 对称）。找不到返回 null。
     *
     * <p><b>为什么需要它</b>：探针原来写 {@code ui.valueLabels.get(0)} 来取
     * 「第一个滑块」的标签，隐含假设「索引 0 = 想测的那个字段」。这个假设在
     * 面板渲染顺序变更后立刻破裂 —— 0 变成了 {@code shortAnimationLength}，
     * 于是「键入 333」测的是一个量程 2..25 的字段，报错变成看不懂的 {@code slider=25}。
     *
     * <p>按 key 定位则与顺序解耦：{@code labelOf(sliderByKey("cursorDisappearDelay"))}
     * 无论面板怎么重排都指向同一个字段。
     */
    public JLabel labelOf(JSlider slider) {
        if (slider == null) {
            return null;
        }
        int i = sliders.indexOf(slider);
        return (i >= 0 && i < valueLabels.size()) ? valueLabels.get(i) : null;
    }

    /** 文案包含判断：忽略 null、忽略首尾空白。 */
    private static boolean labelContains(String text, String part) {
        if (text == null || part == null) {
            return false;
        }
        return text.replaceAll("\\s+", "").contains(part.replaceAll("\\s+", ""));
    }

    /**
     * 面板里所有复选框的文案（供探针在失败时打印全表，便于一眼看出顺序）。
     */
    public String describeCheckBoxes() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            String t = checkBoxes.get(i).getText();
            sb.append(i).append(":").append(t == null || t.isEmpty() ? "(空)" : t);
        }
        return sb.toString();
    }
}
