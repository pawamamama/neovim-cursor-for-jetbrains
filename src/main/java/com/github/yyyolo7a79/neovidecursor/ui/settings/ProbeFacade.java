package com.github.yyyolo7a79.neovidecursor.ui.settings;

import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.ColorPanel;
import com.intellij.ui.components.JBTextField;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JSlider;
import java.util.List;

/**
 * 探针门面（0.9.9 / Phase 0）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>重构前，{@code tools/settings-probe/} 下的 11 个探针文件共 <b>263 处</b>
 * 直接调用 {@code NeovideCursorConfigurable} 上的 {@code *ForProbe()} 方法。
 * 这构成一个致命耦合：<b>只要把面板拆成几个类，那 263 处就全部编译失败</b>，
 * 而这时你面对的是一个 200+ 断言的套件全红 ——
 * 分不清「我拆错了」还是「探针找错地方了」。
 *
 * <p>NEXT-REFACTOR.md 的 Phase 0 就是为这一点存在的：
 * <b>先立门面，再做拆分。</b>
 *
 * <h2>它怎么工作</h2>
 *
 * <p>门面<b>不搬走</b>任何实现，只声明「探针可以问面板什么」：
 * <pre>
 *   探针 ──▶ ProbeFacade ──▶ NeovideCursorConfigurable 的 *ForProbe()
 * </pre>
 *
 * <p>Phase 2 拆类时，只需让新的子类实现本接口，探针<b>一行都不用改</b>；
 * 生产类上的 {@code *ForProbe()} 也可以随之删掉。
 *
 * <h2>等价性要求（Phase 0 的硬判据）</h2>
 *
 * <p>本接口的每个方法都必须与它代理的原方法<b>语义完全一致</b>
 * （同一个对象、同一个快照、同样的顺序）。门面只做转发，<b>不做任何转换</b> ——
 * 一旦在这里做「顺手规整」，探针测的就不再是生产代码了。
 */
public interface ProbeFacade {

    /** 面板正在编辑的副本（85 处调用，最热的一个）。 */
    NeovideCursorSettings.State pendingForProbe();

    /** 面板打开时的比较基准。 */
    NeovideCursorSettings.State baselineForProbe();

    /** 是否处于「程序化改控件」的抑制态。 */
    boolean syncingForProbe();

    // ---------- 滑块三件套 ----------

    List<JSlider> slidersForProbe();

    List<JBTextField> textFieldsForProbe();

    List<JLabel> labelsForProbe();

    List<String> sliderKeysForProbe();

    int[] sliderRangeForProbe(int index);

    // ---------- 预设 ----------

    ComboBox<String> animPresetComboForProbe();

    ComboBox<String> colorPresetComboForProbe();

    JButton animRestoreButtonForProbe();

    JButton colorRestoreButtonForProbe();

    void pickAnimPresetForProbe(int index);

    void pickColorPresetForProbe(int index);

    boolean pickAnimPresetByNameForProbe(String name);

    void refreshPresetDisplayForProbe();

    // ---------- 其它控件 ----------

    ComboBox<String> trailModeComboForProbe();

    JLabel[] couplingHintsForProbe();

    ColorPanel tailColorPanelForProbe();

    ColorPanel shadowColorPanelForProbe();

    void onColorPanelChangedForProbe();
}
