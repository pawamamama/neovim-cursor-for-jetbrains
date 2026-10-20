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

    /** 滑块字段名（按契约 §6.2 的面板顺序，用最小/最大与步长交叉核对）。 */
    public static String nameOf(int index) {
        String[] names = {"cursorDisappearDelay", "cursorFadeOutDuration", "tailOpacity", "glowOpacity",
                "glowLayers", "shadowBlurFactor", "trailSize", "stretchRatioK",
                "animationLength", "shortAnimationLength"};
        return index >= 0 && index < names.length ? names[index] : ("#" + index);
    }
}
