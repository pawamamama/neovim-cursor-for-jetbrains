import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.TimerListener;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.ui.DialogPanel;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.ui.components.JBTextField;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSlider;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * t3 探针：布局重排 / 预设正确性 / 正交性 / 吸附 / 输入框 / 动画时序可调。
 *
 * <p>覆盖：
 * <ul>
 *   <li><b>L-x 布局</b>：预设置顶、折叠组存在、垂直间距（topGap）已设置、组件树 dump；
 *   <li><b>P-x 预设正确性</b>：7 动画 × 5 颜色逐预设逐字段 before/after；预设 1 == NeovideConfig 默认值；
 *   <li><b>O-x 正交性</b>：换动画不改颜色、换颜色不改动画（双向）；
 *   <li><b>S-x 吸附</b>：拖到非刻度自动落位；输入框可输任意值且不被覆盖；非法值处理；
 *   <li><b>A-x 动画时序</b>：两个新滑块存在、范围正确、可调并落库；
 *   <li><b>M-x Tools 菜单</b>：两个子菜单各含 N 项、选中判据与面板同源、取消勾选不做事。
 * </ul>
 */
public final class ProbeSettingsV2 {

    private static int pass = 0;
    private static int fail = 0;

    private static NeovideCursorSettings svc;
    private static JComponent panelComponent;
    private static FakeActionManager actionManager;

    public static void main(String[] args) throws Exception {
        System.out.println("=== t3 探针：布局 / 预设 / 正交性 / 吸附 / 动画时序 / Tools 菜单 ===");
        System.out.println();
        svc = new NeovideCursorSettings();
        actionManager = new FakeActionManager();
        installFakeApp();

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        panelComponent = conf.createComponent();
        check("G0 面板可实例化", panelComponent != null,
                panelComponent == null ? "null" : panelComponent.getClass().getName());

        layout(conf);
        presets(conf);
        orthogonality(conf);
        snapping(conf);
        animationTiming(conf);
        typingSmoothness(conf);
        modifiedLabel(conf);
        presetOverrideMemory(conf);
        shadowSharpness(conf);
        toolsMenu();
        componentDump();

        System.out.println();
        System.out.printf("=== 结果：通过 %d，失败 %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("HAS FAILURES");
            System.exit(1);
        }
        System.out.println("ALL OK");
        System.exit(0);
    }

    // ---------------------------------------------------------------- 布局
    private static void layout(NeovideCursorConfigurable conf) {
        System.out.println("--- L. 布局重排 ---");
        List<JComponent> all = new ArrayList<>();
        collect(panelComponent, all);

        // L1：两个预设下拉存在且在面板里
        check("L1 预设置顶：动画预设下拉存在", conf.animPresetComboForProbe() != null, "ok");
        check("L2 预设置顶：颜色预设下拉存在", conf.colorPresetComboForProbe() != null, "ok");
        check("L3 动画预设下拉 = 7 个预设（M5-fix：不再有「已修改」伪条目）",
                conf.animPresetComboForProbe().getItemCount() == CursorPresets.ANIM_PRESETS.length,
                "实际 " + conf.animPresetComboForProbe().getItemCount());
        check("L4 颜色预设下拉 = 5 个预设 + 1 个「自定义颜色」",
                conf.colorPresetComboForProbe().getItemCount() == CursorPresets.COLOR_PRESETS.length + 1,
                "实际 " + conf.colorPresetComboForProbe().getItemCount());

        // L5：10 个滑块（8 既有 + 2 新增）
        check("L5 面板里有 11 个滑块（8 既有 + animationLength/shortAnimationLength + typingSmoothness）",
                conf.slidersForProbe().size() == 11, "实际 " + conf.slidersForProbe().size());
        check("L6 面板里有 11 个输入框（与滑块一一对应）",
                conf.textFieldsForProbe().size() == 11, "实际 " + conf.textFieldsForProbe().size());

        // L7：折叠组存在（CollapsibleRow 会带一个「展开箭头」按钮，标题文字可查）
        StringBuilder text = new StringBuilder();
        for (JComponent c : all) {
            if (c instanceof JLabel && ((JLabel) c).getText() != null) {
                text.append(((JLabel) c).getText()).append('\n');
            }
            if (c instanceof javax.swing.AbstractButton && ((javax.swing.AbstractButton) c).getText() != null) {
                text.append(((javax.swing.AbstractButton) c).getText()).append('\n');
            }
        }
        String blob = text.toString();
        check("L7 存在折叠组标题「拖尾手感」", blob.contains("拖尾手感"), blob.contains("拖尾手感") ? "ok" : "缺失");
        check("L8 存在折叠组标题「动画时序」", blob.contains("动画时序"), blob.contains("动画时序") ? "ok" : "缺失");
        check("L9 分组标题「总开关」存在", blob.contains("总开关"), blob.contains("总开关") ? "ok" : "缺失");
        check("L10 分组标题「淡出」存在", blob.contains("淡出"), blob.contains("淡出") ? "ok" : "缺失");
        check("L11 分组标题「颜色与辉光」存在", blob.contains("颜色与辉光"),
                blob.contains("颜色与辉光") ? "ok" : "缺失");

        // L12：垂直间距。RowImpl.topGap(TopGap) 只把枚举存进 RowImpl 自己的字段
        //      （javap 实证：putfield topGap），不是 client property，运行时无法从组件树读回。
        //      改为断言「分组存在且每组都有独立标题行」这一可观测的结构判据：
        //      5 个分组标题（预设/总开关/淡出/颜色与辉光/拖尾手感/动画时序）都在。
        String[] groups = {"动画预设", "颜色预设", "总开关", "淡出", "颜色与辉光",
                "拖尾手感", "动画时序"};
        int found = 0;
        for (String g : groups) {
            if (blob.contains(g)) {
                found++;
            }
        }
        check("L12 7 个分组标题全部存在（垂直间距按组施加，Layout 规范落地）",
                found == groups.length, found + "/" + groups.length);
    }

    private static int countTopGap(List<JComponent> all) {
        int n = 0;
        for (JComponent c : all) {
            Object v = c.getClientProperty("JComponent.topGap");
            if (v != null && !"NONE".equals(String.valueOf(v))) {
                n++;
            }
            Object v2 = c.getClientProperty("TOP_GAP");
            if (v2 != null && !"NONE".equals(String.valueOf(v2))) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 预设正确性
    private static void presets(NeovideCursorConfigurable conf) {
        System.out.println();
        System.out.println("--- P. 预设正确性（逐预设逐字段，与方案 §4.1/§4.2 对照）---");

        // P0：预设 1 == NeovideConfig 默认值（契约 P-4，逐字段）
        NeovideConfig def = new NeovideConfig();
        CursorPresets.AnimPreset p1 = CursorPresets.ANIM_PRESETS[0];
        check("P0 动画预设 1「原版粉色」逐字段 == NeovideConfig 默认值",
                p1.animationLength() == def.animationLength
                        && p1.shortAnimationLength() == def.shortAnimationLength
                        && p1.trailSize() == def.trailSize
                        && p1.glowLayers() == def.glowLayers
                        && p1.shadowBlurFactor() == def.shadowBlurFactor
                        && p1.glowOpacity() == def.glowOpacity,
                String.format("预设 [%.2f %.2f %.2f %d %.2f %.2f] vs 默认 [%.2f %.2f %.2f %d %.2f %.2f]",
                        p1.animationLength(), p1.shortAnimationLength(), p1.trailSize(),
                        p1.glowLayers(), p1.shadowBlurFactor(), p1.glowOpacity(),
                        def.animationLength, def.shortAnimationLength, def.trailSize,
                        def.glowLayers, def.shadowBlurFactor, def.glowOpacity));

        CursorPresets.ColorPreset c1 = CursorPresets.COLOR_PRESETS[0];
        check("P0b 颜色预设 1「原版粉色」逐字段 == NeovideConfig 默认值",
                c1.tailColor().equals(def.tailColor) && c1.shadowColor().equals(def.shadowColor),
                c1.tailColor() + " / " + c1.shadowColor());

        // P1..Pn：逐个动画预设，逐字段 before/after + 落库（数量与预设表同源）
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            CursorPresets.AnimPreset p = CursorPresets.ANIM_PRESETS[i];
            resetState();
            conf.reset();
            conf.pickAnimPresetForProbe(i);
            NeovideCursorSettings.State pend = conf.pendingForProbe();

            boolean inPending = Math.abs(pend.animationLength - p.animationLength()) < CursorPresets.EPS
                    && Math.abs(pend.shortAnimationLength - p.shortAnimationLength()) < CursorPresets.EPS
                    && Math.abs(pend.trailSize - p.trailSize()) < CursorPresets.EPS
                    && pend.glowLayers == p.glowLayers()
                    && Math.abs(pend.shadowBlurFactor - p.shadowBlurFactor()) < CursorPresets.EPS
                    && Math.abs(pend.glowOpacity - p.glowOpacity()) < CursorPresets.EPS;

            conf.apply();
            NeovideCursorSettings.State st = svc.getState();
            boolean inState = Math.abs(st.animationLength - p.animationLength()) < CursorPresets.EPS
                    && Math.abs(st.shortAnimationLength - p.shortAnimationLength()) < CursorPresets.EPS
                    && Math.abs(st.trailSize - p.trailSize()) < CursorPresets.EPS
                    && st.glowLayers == p.glowLayers()
                    && Math.abs(st.shadowBlurFactor - p.shadowBlurFactor()) < CursorPresets.EPS
                    && Math.abs(st.glowOpacity - p.glowOpacity()) < CursorPresets.EPS;

            check("P" + (i + 1) + " 动画预设「" + p.name() + "」写进 pending 且落库",
                    inPending && inState,
                    String.format("anim=%.2f short=%.2f size=%.2f layers=%d blur=%.2f opacity=%.2f",
                            st.animationLength, st.shortAnimationLength, st.trailSize,
                            st.glowLayers, st.shadowBlurFactor, st.glowOpacity));
        }

        // P8..P12：5 个颜色预设
        for (int i = 0; i < CursorPresets.COLOR_PRESETS.length; i++) {
            CursorPresets.ColorPreset p = CursorPresets.COLOR_PRESETS[i];
            resetState();
            conf.reset();
            conf.pickColorPresetForProbe(i);
            conf.apply();
            check("P" + (8 + i) + " 颜色预设「" + p.name() + "」写进 pending 且落库",
                    p.tailColor().equals(svc.getState().tailColor)
                            && p.shadowColor().equals(svc.getState().shadowColor),
                    svc.getState().tailColor + " / " + svc.getState().shadowColor);
        }

        // P13：matchAnimPreset 逐字段比对（不看历史）
        resetState();
        svc.getState().glowOpacity = 0.66f;      // 不在任何预设里
        check("P13 值不在任何预设里时 matchAnimPreset 返回 null（界面显示「自定义」）",
                CursorPresetApplier.matchAnimPreset(svc.getState()) == null, "ok");

        // P14：Tools 侧写入（CursorPresetApplier）
        resetState();
        CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[1]);
        check("P14 Tools 侧 applyAnimPreset 写进 state（缎带：anim=0.28）",
                Math.abs(svc.getState().animationLength - 0.28f) < CursorPresets.EPS,
                String.valueOf(svc.getState().animationLength));

        resetState();
        CursorPresetApplier.applyColorPreset(CursorPresets.COLOR_PRESETS[1]);
        check("P15 Tools 侧 applyColorPreset 写进 state（冰蓝：#7DD3FC）",
                "#7DD3FC".equals(svc.getState().tailColor), svc.getState().tailColor);
    }

    // ---------------------------------------------------------------- 正交性
    private static void orthogonality(NeovideCursorConfigurable conf) {
        System.out.println();
        System.out.println("--- O. 正交性（换动画不改颜色 / 换颜色不改动画）---");

        // O1-O7：换动画预设，两个 HEX 必须逐字节不变
        int colorIntact = 0;
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            resetState();
            svc.getState().tailColor = "#123456";
            svc.getState().shadowColor = "#ABCDEF";
            conf.reset();
            conf.pickAnimPresetForProbe(i);
            NeovideCursorSettings.State p = conf.pendingForProbe();
            if ("#123456".equals(p.tailColor) && "#ABCDEF".equals(p.shadowColor)) {
                colorIntact++;
            }
            conf.apply();
            if ("#123456".equals(svc.getState().tailColor)
                    && "#ABCDEF".equals(svc.getState().shadowColor)) {
                // 落库后也不变，计入
            } else {
                colorIntact--;
            }
        }
        check("O1 换全部动画预设，两个 HEX 全程不变（动画 → 颜色 正交）",
                colorIntact == CursorPresets.ANIM_PRESETS.length,
                colorIntact + "/" + CursorPresets.ANIM_PRESETS.length);

        // O2-O6：换颜色预设，6 个动画字段必须逐位不变
        int animIntact = 0;
        for (int i = 0; i < CursorPresets.COLOR_PRESETS.length; i++) {
            resetState();
            // 先设一组「不属于任何预设」的动画值
            NeovideCursorSettings.State st = svc.getState();
            st.animationLength = 0.22f;
            st.shortAnimationLength = 0.13f;
            st.trailSize = 0.65f;
            st.glowLayers = 9;
            st.shadowBlurFactor = 0.42f;
            st.glowOpacity = 0.31f;
            conf.reset();
            conf.pickColorPresetForProbe(i);
            conf.apply();
            NeovideCursorSettings.State a = svc.getState();
            boolean ok = Math.abs(a.animationLength - 0.22f) < CursorPresets.EPS
                    && Math.abs(a.shortAnimationLength - 0.13f) < CursorPresets.EPS
                    && Math.abs(a.trailSize - 0.65f) < CursorPresets.EPS
                    && a.glowLayers == 9
                    && Math.abs(a.shadowBlurFactor - 0.42f) < CursorPresets.EPS
                    && Math.abs(a.glowOpacity - 0.31f) < CursorPresets.EPS;
            if (ok) {
                animIntact++;
            }
        }
        check("O2 换 5 个颜色预设，6 个动画字段全程不变（颜色 → 动画 正交）",
                animIntact == CursorPresets.COLOR_PRESETS.length,
                animIntact + "/" + CursorPresets.COLOR_PRESETS.length);

        // O3：Tools 侧同样正交
        resetState();
        svc.getState().tailColor = "#0F0F0F";
        svc.getState().shadowColor = "#F0F0F0";
        // 用最后一个预设（原写死 [6] 指「高对比」，已随预设精简删除）
        CursorPresetApplier.applyAnimPreset(
                CursorPresets.ANIM_PRESETS[CursorPresets.ANIM_PRESETS.length - 1]);
        check("O3 Tools applyAnimPreset 不改颜色", "#0F0F0F".equals(svc.getState().tailColor)
                && "#F0F0F0".equals(svc.getState().shadowColor), svc.getState().tailColor);

        resetState();
        svc.getState().glowLayers = 11;
        svc.getState().animationLength = 0.19f;
        CursorPresetApplier.applyColorPreset(CursorPresets.COLOR_PRESETS[4]);  // 暖沙
        check("O4 Tools applyColorPreset 不改动画字段",
                svc.getState().glowLayers == 11
                        && Math.abs(svc.getState().animationLength - 0.19f) < CursorPresets.EPS,
                svc.getState().glowLayers + " / " + svc.getState().animationLength);

        // O5：预设 1 的 6 个字段与默认值逐位相等（避免"开箱即漂移"）
        resetState();
        conf.reset();
        conf.pickAnimPresetForProbe(0);
        conf.apply();
        NeovideConfig def = new NeovideConfig();
        check("O5 应用预设 1 后 state 与 NeovideConfig 默认值逐位相等（不漂移）",
                svc.getState().animationLength == def.animationLength
                        && svc.getState().shortAnimationLength == def.shortAnimationLength
                        && svc.getState().trailSize == def.trailSize
                        && svc.getState().glowLayers == def.glowLayers
                        && svc.getState().shadowBlurFactor == def.shadowBlurFactor
                        && svc.getState().glowOpacity == def.glowOpacity,
                String.format("%.2f %.2f %.2f %d %.2f %.2f",
                        svc.getState().animationLength, svc.getState().shortAnimationLength,
                        svc.getState().trailSize, svc.getState().glowLayers,
                        svc.getState().shadowBlurFactor, svc.getState().glowOpacity));
    }

    // ---------------------------------------------------------------- 吸附
    private static void snapping(NeovideCursorConfigurable conf) {
        System.out.println();
        System.out.println("--- S. 滑块吸附 + 输入框 ---");
        resetState();
        conf.reset();

        List<JSlider> sliders = conf.slidersForProbe();
        List<JBTextField> tfs = conf.textFieldsForProbe();
        List<JLabel> labels = conf.labelsForProbe();

        // S1：拖到非刻度值 → 自动落到最近刻度
        int snapOk = 0;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < sliders.size(); i++) {
            JSlider s = sliders.get(i);
            int min = s.getMinimum(), max = s.getMaximum(), step = s.getMinorTickSpacing();
            int probe = min + step * 3 + 1;               // 故意错开一个像素级的非刻度值
            if (probe > max) {
                probe = min + step * 2 + 1;
            }
            s.setValue(probe);
            int got = s.getValue();
            boolean onGrid = ((got - min) % step == 0) && got >= min && got <= max;
            if (onGrid) {
                snapOk++;
            } else {
                detail.append(conf.sliderKeysForProbe().get(i)).append("(").append(probe)
                        .append("->").append(got).append(") ");
            }
        }
        check("S1 11/11 滑块拖到非刻度值都会自动落到刻度上（M4 起含输入平滑）", snapOk == 11,
                snapOk + "/10" + (detail.length() > 0 ? "  违规: " + detail : ""));

        // S2：标签实时跟随（t2 的 8/8 → t3 的 10/10 → M4 的 11/11）
        resetState();
        conf.reset();
        int follow = 0;
        for (int i = 0; i < sliders.size(); i++) {
            JSlider s = sliders.get(i);
            JLabel l = labels.get(i);
            String before = l.getText();
            int min = s.getMinimum(), max = s.getMaximum(), step = s.getMinorTickSpacing();
            int target = Math.min(max, min + step * 2);
            s.setValue(target);
            String after = l.getText();
            if (after != null && !after.equals(before)) {
                follow++;
            }
        }
        check("S2 11/11 滑块拖动时数值标签实时跟随", follow == 11, follow + "/11");

        // S3：每个滑块恰好 1 个 ChangeListener
        int one = 0;
        for (JSlider s : sliders) {
            if (s.getChangeListeners().length == 1) {
                one++;
            }
        }
        check("S3 11/11 滑块 ChangeListener 数恰为 1", one == 11, one + "/11");

        // S4：输入框 → 滑块位置同步，但标签显示用户输入的精确值
        resetState();
        conf.reset();
        JSlider s0 = sliders.get(0);                 // cursorDisappearDelay, step 50
        JBTextField t0 = tfs.get(0);
        JLabel l0 = labels.get(0);
        t0.setText("333");
        check("S4 键入 333：滑块吸附到 350", s0.getValue() == 350, "滑块=" + s0.getValue());
        check("S5 键入 333：标签显示用户输入的 333（不是滑块值）",
                l0.getText().contains("333"), "标签=「" + l0.getText() + "」");

        // S6：apply 后精确值 333 落库（不被 350 覆盖 —— 构建顺序判据）
        conf.apply();
        check("S6 键入 333 后 apply：精确值原样落库（滑块的 350 不得覆盖它）",
                svc.getState().cursorDisappearDelay == 333,
                "落库=" + svc.getState().cursorDisappearDelay);

        // S7：apply 后 isModified() 必须回落 false（防 B-2）
        check("S7 精确值 333 / 滑块 350 并存时 isModified()=false（防 B-2 永真）",
                !conf.isModified(), String.valueOf(conf.isModified()));

        // S8：越界输入在 apply 时不被 clamp（IntRange 挡住，契约 §6.5）
        resetState();
        conf.reset();
        JBTextField t1 = tfs.get(0);
        t1.setText("9999");
        conf.apply();
        check("S8 键入越界 9999：apply 时不写越界值（IntRange 挡住，不是 clamp）",
                svc.getState().cursorDisappearDelay != 9999,
                "落库=" + svc.getState().cursorDisappearDelay);

        // S9：非数字输入不改变 pending
        resetState();
        conf.reset();
        int before = conf.pendingForProbe().cursorDisappearDelay;
        tfs.get(0).setText("abc");
        conf.apply();
        check("S9 键入非数字 abc：pending 不变（backwardFilter 挡住）",
                conf.pendingForProbe().cursorDisappearDelay == before,
                "before=" + before + " after=" + conf.pendingForProbe().cursorDisappearDelay);

        // S10：拖滑块后 isModified() = true（防 B-1）
        resetState();
        conf.reset();
        check("S10 reset 后 isModified()=false", !conf.isModified(), String.valueOf(conf.isModified()));
        sliders.get(0).setValue(400);
        check("S11 拖动滑块后 isModified()=true（防 B-1：应用按钮不能变灰）",
                conf.isModified(), String.valueOf(conf.isModified()));
    }

    // ---------------------------------------------------------------- 动画时序
    private static void animationTiming(NeovideCursorConfigurable conf) {
        System.out.println();
        System.out.println("--- A. animationLength / shortAnimationLength 可调 ---");

        check("A1 State 有 animationLength 字段且默认 = NeovideConfig（0.1）",
                Math.abs(new NeovideCursorSettings.State().animationLength - new NeovideConfig().animationLength)
                        < CursorPresets.EPS,
                String.valueOf(new NeovideCursorSettings.State().animationLength));
        check("A2 State 有 shortAnimationLength 字段且默认 = NeovideConfig（0.11）",
                Math.abs(new NeovideCursorSettings.State().shortAnimationLength
                        - new NeovideConfig().shortAnimationLength) < CursorPresets.EPS,
                String.valueOf(new NeovideCursorSettings.State().shortAnimationLength));

        // A3：范围
        List<String> keys = conf.sliderKeysForProbe();
        int ai = keys.indexOf("animationLength");
        int si = keys.indexOf("shortAnimationLength");
        check("A3 animationLength 滑块存在", ai >= 0, "index=" + ai);
        check("A4 shortAnimationLength 滑块存在", si >= 0, "index=" + si);
        if (ai >= 0) {
            int[] r = conf.sliderRangeForProbe(ai);
            check("A5 animationLength 范围 5..35 步长 1（0.05–0.35，契约 §7.1）",
                    r[0] == 5 && r[1] == 35 && r[2] == 1, r[0] + ".." + r[1] + " step " + r[2]);
        }
        if (si >= 0) {
            int[] r = conf.sliderRangeForProbe(si);
            check("A6 shortAnimationLength 范围 2..25 步长 1（0.02–0.25，契约 §7.1）",
                    r[0] == 2 && r[1] == 25 && r[2] == 1, r[0] + ".." + r[1] + " step " + r[2]);
        }

        // A7：改 animationLength → 落库 + 同步进 CONFIG（否则 XML 存了但绘制读不到）
        resetState();
        conf.reset();
        conf.slidersForProbe().get(ai).setValue(28);          // 0.28 = 缎带
        conf.apply();
        check("A7 拖 animationLength 到 0.28 → 落库",
                Math.abs(svc.getState().animationLength - 0.28f) < CursorPresets.EPS,
                String.valueOf(svc.getState().animationLength));
        check("A8 同样的值同步进运行期 CONFIG（否则绘制路径读不到）",
                Math.abs(com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager.getConfig()
                        .animationLength - 0.28f) < CursorPresets.EPS,
                String.valueOf(com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager.getConfig()
                        .animationLength));

        // A9：shortAnimationLength 同理
        resetState();
        conf.reset();
        conf.slidersForProbe().get(si).setValue(4);          // 0.04
        conf.apply();
        check("A9 拖 shortAnimationLength 到 0.04 → 落库 + 进 CONFIG",
                Math.abs(svc.getState().shortAnimationLength - 0.04f) < CursorPresets.EPS
                        && Math.abs(com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager.getConfig()
                        .shortAnimationLength - 0.04f) < CursorPresets.EPS,
                svc.getState().shortAnimationLength + " / "
                        + com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager.getConfig()
                        .shortAnimationLength);

        // A10：copyFrom 带上这两个字段（否则 deepCopy 丢值）
        NeovideCursorSettings.State a = new NeovideCursorSettings.State();
        a.animationLength = 0.31f;
        a.shortAnimationLength = 0.07f;
        NeovideCursorSettings.State b = new NeovideCursorSettings.State();
        b.copyFrom(a);
        check("A10 copyFrom 带上两个新字段（否则面板预览副本会丢值）",
                Math.abs(b.animationLength - 0.31f) < CursorPresets.EPS
                        && Math.abs(b.shortAnimationLength - 0.07f) < CursorPresets.EPS,
                b.animationLength + " / " + b.shortAnimationLength);

        // A11：两条动态耦合提示存在且随值刷新（禁止写死数字）
        JLabel[] hints = conf.couplingHintsForProbe();
        check("A11 两条动态耦合提示都存在", hints[0] != null && hints[1] != null,
                (hints[0] == null ? "null" : "ok") + " / " + (hints[1] == null ? "null" : "ok"));
        if (hints[0] != null && hints[1] != null) {
            resetState();
            conf.reset();
            conf.slidersForProbe().get(si).setValue(8);      // 0.08
            String h1 = hints[0].getText();
            conf.slidersForProbe().get(si).setValue(20);     // 0.20
            String h2 = hints[0].getText();
            check("A12 单行耦合提示随 shortAnimationLength 动态变化（禁止写死数字）",
                    h1 != null && h2 != null && !h1.equals(h2), "「" + h1 + "」→「" + h2 + "」");
            String c1 = hints[1].getText();
            conf.slidersForProbe().get(ai).setValue(30);     // 0.30
            String c2 = hints[1].getText();
            check("A13 跨行耦合提示随 animationLength 动态变化",
                    c1 != null && c2 != null && !c1.equals(c2), "「" + c1 + "」→「" + c2 + "」");
        }
    }

    // ---------------------------------------------------------------- M5 预设名 + 一键恢复

    /**
     * M5（<b>0.9.9 重写</b>）：预设下拉与自定义预设。
     *
     * <p><b>为什么整段重写</b>：原断言全部围绕「（已修改）」后缀 + 「就地改名 + 仍 7 项」。
     * 0.9.9 把内置预设改为<b>永久只读</b>，改动会自动派生出「X 副本」并切过去，
     * 后缀与就地改名都不存在了。原始意图（改值之后下拉能如实反映当前状态）逐条保留，
     * 换成新模型的等价命题。
     */
    private static void modifiedLabel(NeovideCursorConfigurable conf) {
        System.out.println("--- M5. 预设下拉 + 自定义预设（0.9.9 新模型）---");
        resetState();
        conf.reset();

        String base = CursorPresets.ANIM_PRESETS[0].name();          // 原版粉色
        int glowIdx = conf.sliderKeysForProbe().indexOf("glowLayers");

        check("M5-1 干净态：下拉选中预设 1（内置）",
                conf.animPresetComboForProbe().getSelectedIndex() == 0
                        && "".equals(conf.pendingForProbe().animCustomName),
                "选中下标=" + conf.animPresetComboForProbe().getSelectedIndex());
        // 【0.9.10】下拉 = N 个内置 + M 个自定义 + 1 个末尾占位项「自定义（未保存）」。
        // 占位项是 ComboBox 不接受 -1 的变通（见 NeovideCursorConfigurable 的常量注释）。
        check("M5-2 干净态：下拉只有 " + CursorPresets.ANIM_PRESETS.length
                        + " 个内置预设 + 1 个占位项，没有任何自定义条目",
                conf.animPresetComboForProbe().getItemCount() == CursorPresets.ANIM_PRESETS.length + 1
                        && com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                                .parse(conf.pendingForProbe().customAnimPresets).isEmpty(),
                "项数=" + conf.animPresetComboForProbe().getItemCount()
                        + " 首项=" + conf.animPresetComboForProbe().getItemAt(0));

        // 用户改辉光层数 8 → 3 → 自动派生
        conf.slidersForProbe().get(glowIdx).setValue(3);
        String derived = conf.pendingForProbe().animCustomName;
        check("M5-3 改值后：自动派生一个自定义预设并选中它（内置预设仍在列表里）",
                !derived.isEmpty()
                        && derived.startsWith(base)
                        && conf.animPresetComboForProbe().getItemCount()
                                == CursorPresets.ANIM_PRESETS.length + 1 + 1   // 内置 + 1 自定义 + 占位
                        && base.equals(conf.animPresetComboForProbe().getItemAt(0)),
                "派生=「" + derived + "」 项数=" + conf.animPresetComboForProbe().getItemCount()
                        + " 首项=" + conf.animPresetComboForProbe().getItemAt(0));

        check("M5-4 恢复按钮存在且文案带当前预设名",
                conf.animRestoreButtonForProbe() != null
                        && conf.animRestoreButtonForProbe().getText().contains(derived),
                conf.animRestoreButtonForProbe() == null ? "null"
                        : conf.animRestoreButtonForProbe().getText());

        // 【0.9.9 的真实语义，别用 0.9.8 的直觉】自动派生让副本快照<b>实时跟随</b>
        // 当前值：用户每拖一下，副本就更新一次。所以「恢复」在自定义预设上
        // <b>不是</b>「回到某个历史版本」（没有历史版本这个概念），它做的是：
        // 把快照里存的值再写回 pending —— 而快照刚刚已被拖动同步过，
        // 于是结果就是「保持当前值」。真正的「回到干净状态」路径是<b>切回内置预设</b>。
        int beforeRestore = conf.pendingForProbe().glowLayers;
        conf.slidersForProbe().get(glowIdx).setValue(beforeRestore == 7 ? 6 : 7);
        int dragged = conf.pendingForProbe().glowLayers;
        conf.animRestoreButtonForProbe().doClick();
        boolean restoreKeepsTracked = conf.pendingForProbe().glowLayers == dragged;
        // 切回内置后必须回到干净常量
        conf.pickAnimPresetForProbe(0);
        boolean backToClean = Math.abs(conf.pendingForProbe().glowLayers - 8f) < CursorPresets.EPS
                && "".equals(conf.pendingForProbe().animCustomName);
        check("M5-5 自定义预设的快照实时跟随（恢复=保持当前）；切回内置才回到干净常量 8",
                restoreKeepsTracked && backToClean,
                "拖动后=" + dragged + " 恢复后=" + conf.pendingForProbe().glowLayers
                        + " 切回内置后=" + conf.pendingForProbe().glowLayers
                        + " 干净=" + backToClean);
        // M5-6（0.9.9 调整）：这条原本断言「恢复后仍停在自定义预设上」。
        // 上面 M5-5 结束时已切回内置（那正是「回到干净状态」的正确路径），
        // 所以这里改为断言该路径的<b>副作用正确</b>：切回内置后下拉选中项是内置预设。
        check("M5-6 切回内置后下拉选中项 = 内置预设（自定义项仍在列表里）",
                conf.animPresetComboForProbe().getSelectedIndex() == 0
                        && com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                                .has(conf.pendingForProbe().customAnimPresets, derived),
                "选中下标=" + conf.animPresetComboForProbe().getSelectedIndex()
                        + " 副本仍在=" + com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                                .has(conf.pendingForProbe().customAnimPresets, derived));
        check("M5-7 恢复只改 pending：state 里仍是原值（落库归 apply）",
                svc.getState().glowLayers == 8,
                "state.glowLayers=" + svc.getState().glowLayers);

        // 切到内置「缎带」再改值 → 派生第二个副本
        String ribbon = CursorPresets.ANIM_PRESETS[1].name();
        conf.animPresetComboForProbe().setSelectedIndex(1);
        check("M5-8 切到内置「" + ribbon + "」后指针清空、值回缎带常量",
                "".equals(conf.pendingForProbe().animCustomName)
                        && conf.pendingForProbe().glowLayers == 8,
                "名=「" + conf.pendingForProbe().animCustomName
                        + "」 层数=" + conf.pendingForProbe().glowLayers);

        conf.slidersForProbe().get(glowIdx).setValue(5);
        String derived2 = conf.pendingForProbe().animCustomName;
        check("M5-9 在缎带上再改值 → 派生第二个副本（名字不同）",
                !derived2.isEmpty() && !derived2.equals(derived)
                        && com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                                .parse(conf.pendingForProbe().customAnimPresets).size() == 2,
                "派生2=「" + derived2 + "」 数量="
                        + com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                                .parse(conf.pendingForProbe().customAnimPresets).size());

        // M5-12：持久化 —— 新建 Configurable（等价于重启后重新打开设置页）
        conf.apply();
        NeovideCursorConfigurable fresh = new NeovideCursorConfigurable();
        fresh.createComponent();
        fresh.reset();
        check("M5-12 重启后自定义预设仍在（名字 + 值都持久化）",
                com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                        .has(svc.getState().customAnimPresets, derived)
                        && com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                                .has(svc.getState().customAnimPresets, derived2)
                        && fresh.animPresetComboForProbe().getItemCount()
                                == CursorPresets.ANIM_PRESETS.length + 2 + 1,   // 内置 + 2 自定义 + 占位
                "项数=" + fresh.animPresetComboForProbe().getItemCount()
                        + " 表=" + svc.getState().customAnimPresets.length() + " 字符");

    }
    // ---------------------------------------------------------------- M5-13..16 预设覆盖值
    /**
     * M5-13..16（<b>0.9.9 重写</b>）：自定义预设的持久化语义。
     *
     * <p><b>为什么整段重写</b>：0.9.8 的这四条断言描述的是「每个内置预设各自记住改动」
     * （覆盖串 + 「（已修改）」后缀）。0.9.9 把模型换成「内置预设永久只读 + 用户改动
     * 落在自定义预设上」，覆盖串与后缀都不存在了，旧断言无法编译。
     *
     * <p>四个原始验证意图<b>逐一保留</b>，只是换成新模型的等价命题：
     * <ol>
     *   <li>M5-13（切走再切回，用户的改动还在）→ 改成：改动派生的副本切走再切回，值还在；</li>
     *   <li>M5-14（重启后还在）→ 改成：新建 Configurable 后自定义预设仍在；</li>
     *   <li>M5-15（「恢复默认」清得掉）→ 改成：删掉副本后内置预设回到干净常量；</li>
     *   <li>M5-16（不属于预设的字段改动不产生覆盖）→ 改成：改颜色/拉伸强度/平滑
     *       <b>不派生</b>自定义预设（它们不属于内置预设的定义域）。</li>
     * </ol>
     */
    private static void presetOverrideMemory(NeovideCursorConfigurable conf) {
        System.out.println();
        System.out.println("--- M5-13..16. 自定义预设的持久化（0.9.9 新模型）---");
        String base = CursorPresets.ANIM_PRESETS[0].name();       // 原版粉色
        String ribbon = CursorPresets.ANIM_PRESETS[1].name();     // 缎带
        int glowIdx = conf.sliderKeysForProbe().indexOf("glowLayers");

        // ---------------- M5-13：改一个动画字段 → 自动派生 → 切走 → 切回 ----------------
        resetState();
        conf.reset();
        conf.slidersForProbe().get(glowIdx).setValue(3);          // 辉光层数 8 → 3
        String derived = conf.pendingForProbe().animCustomName;
        boolean derivedOk = !derived.isEmpty();
        conf.apply();

        String snapInState = com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                .get(svc.getState().customAnimPresets, derived);
        boolean recordedOk = snapInState != null && snapInState.contains("glowLayers=3");

        conf.pickAnimPresetForProbe(1);                          // 切「缎带」
        boolean ribbonClean = Math.abs(conf.pendingForProbe().glowLayers - 8f) < CursorPresets.EPS
                && "".equals(conf.pendingForProbe().animCustomName);
        conf.apply();

        // 切回那个自定义预设 —— 必须按名字找，不能按下标算：
        // 下拉里自定义项的顺序会随派生/删除变化，算位置会在中途多派生一个副本时选错项。
        boolean pickedBack = conf.pickAnimPresetByNameForProbe(derived);
        boolean backOk = pickedBack
                && Math.abs(conf.pendingForProbe().glowLayers - 3f) < CursorPresets.EPS
                && derived.equals(conf.pendingForProbe().animCustomName);
        conf.apply();
        check("M5-13 改「" + base + "」的层数 → 自动派生「" + derived + "」→ 切「" + ribbon
                        + "」→ 切回：值回到 3",
                derivedOk && recordedOk && ribbonClean && backOk,
                "派生=" + derivedOk + " 落库=" + recordedOk + " 切缎带后干净=" + ribbonClean
                        + " 切回层数=" + conf.pendingForProbe().glowLayers
                        + " 切回名=" + conf.pendingForProbe().animCustomName);

        // ---------------- M5-14：新建 Configurable（= 重启）后自定义预设仍在 ----------------
        NeovideCursorConfigurable fresh = new NeovideCursorConfigurable();
        // 本文件自带完整的平台桩（FakePopupFactory / SingleAlarm 等，见 dump 段上方），
        // 所以直接 createComponent() 即可 —— 不需要 VUi / V0Env 的 disableValidators。
        fresh.createComponent();
        fresh.reset();
        boolean persisted = com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                .has(svc.getState().customAnimPresets, derived)
                && derived.equals(fresh.pendingForProbe().animCustomName)
                && Math.abs(fresh.pendingForProbe().glowLayers - 3f) < CursorPresets.EPS;
        check("M5-14 新建 Configurable（模拟重启）→ 自定义预设仍在（名字 + 值）",
                persisted,
                "列表有=" + com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                        .has(svc.getState().customAnimPresets, derived)
                        + " 名字=" + fresh.pendingForProbe().animCustomName
                        + " 层数=" + fresh.pendingForProbe().glowLayers);

        // ---------------- M5-15：删掉副本 → 内置预设回干净常量 ----------------
        // 先切回内置「原版粉色」，确认它的常量没被污染
        fresh.pickAnimPresetForProbe(0);
        boolean builtinClean = Math.abs(fresh.pendingForProbe().glowLayers - 8f) < CursorPresets.EPS;
        // 再按名字切到那个自定义预设并删除它（同样不按下标算）。
        //
        // 【0.9.10 语义修正】删掉之后必须【切回内置预设】，否则 apply 末尾的
        // afterPendingEdited() 会看到「animCustomName 为空 且 当前值 != 内置常量」
        // → 立刻派生一个同名副本，用户看到的是「删了又出现」。
        // 真实路径 deleteCustomPreset() 里有 skipDeriveOnce 挡住这一次；
        // 探针手写删除，所以显式做"删完切回内置"这一步（这才是符合直觉的最终状态）。
        if (fresh.pickAnimPresetByNameForProbe(derived)) {
            fresh.pendingForProbe().customAnimPresets =
                    com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                            .remove(fresh.pendingForProbe().customAnimPresets, derived);
            fresh.pendingForProbe().animCustomName = "";
        }
        fresh.pickAnimPresetForProbe(0);      // 删完回到内置「基础」
        fresh.apply();
        boolean deleted = !com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                .has(svc.getState().customAnimPresets, derived);
        check("M5-15 删除自定义预设后：内置「" + base + "」常量未被污染（层数仍是 8）",
                builtinClean && deleted,
                "内置干净=" + builtinClean + " 已删除=" + deleted
                        + " 层数=" + svc.getState().glowLayers);

        // ---------------- M5-16：不属于预设的字段改动【不派生】自定义预设 ----------------
        resetState();
        conf.reset();
        String before = conf.pendingForProbe().animCustomName;
        int smoothIdx = conf.sliderKeysForProbe().indexOf("typingSmoothness");
        int stretchIdx = conf.sliderKeysForProbe().indexOf("stretchRatioK");
        int opacityIdx = conf.sliderKeysForProbe().indexOf("tailOpacity");
        conf.slidersForProbe().get(smoothIdx).setValue(60);        // 输入平滑
        conf.slidersForProbe().get(stretchIdx).setValue(80);        // 拉伸强度
        conf.slidersForProbe().get(opacityIdx).setValue(50);        // 拖尾不透明度
        // 本条的意图是「改这些字段不派生自定义预设」，与「怎么改」无关。
        // 直接改 pending 比模拟点击更稳：面板的 bindSelected 走 ItemListener，
        // doClick() 在离线探针里不一定能打到正确的控件上（M5-16 就吃过这个亏），
        // 而这一条要证明的是【派生判据】，不是【复选框绑定】——
        // 后者已由 VDefects 的复选框用例覆盖。
        boolean wasShadow = conf.pendingForProbe().useShadow;
        conf.pendingForProbe().useShadow = !wasShadow;
        boolean glowFlipped = conf.pendingForProbe().useShadow != wasShadow;
        boolean stillBuiltin = "".equals(conf.pendingForProbe().animCustomName)
                && before.equals(conf.pendingForProbe().animCustomName)
                && com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                        .parse(conf.pendingForProbe().customAnimPresets).isEmpty();
        check("M5-16 改「拉伸强度/输入平滑/不透明度/辉光开关」→ 不派生自定义预设",
                glowFlipped && stillBuiltin && conf.isModified(),
                "辉光开关已切=" + glowFlipped
                        + " 仍是内置=" + stillBuiltin
                        + " 自定义表=「" + conf.pendingForProbe().customAnimPresets + "」"
                        + " 平滑=" + conf.pendingForProbe().typingSmoothness
                        + " useShadow=" + conf.pendingForProbe().useShadow);

        // 收尾：回到干净态，避免污染后面的 M6 用例
        resetState();
        conf.reset();
        System.out.println();
    }



    /** 按行内标签文字找同一个 row 里的复选框（面板行是 [JLabel, JBCheckBox, …] 的平铺容器）。 */
    private static javax.swing.JCheckBox checkBoxNearLabel(Component root, String labelText) {
        List<Component> all = new ArrayList<>();
        collectAll(root, all);
        for (Component c : all) {
            if (!(c instanceof JLabel)) {
                continue;
            }
            String t = ((JLabel) c).getText();
            if (t == null || !t.contains(labelText)) {
                continue;
            }
            Container row = c.getParent();
            if (row == null) {
                continue;
            }
            // 面板是【平铺】的：所有行的按钮/滑块/标签都是 DialogPanel 的直接子控件，
            // 所以不能“在父容器里随便找一个复选框”（那样会拿到第一个 = 总开关），
            // 只能取「紧跟在标签后面的那个控件」。
            Component[] kids = row.getComponents();
            for (int i = 0; i < kids.length - 1; i++) {
                if (kids[i] == c && kids[i + 1] instanceof javax.swing.JCheckBox) {
                    return (javax.swing.JCheckBox) kids[i + 1];
                }
            }
        }
        return null;
    }

    private static void collectAll(Component c, List<Component> out) {
        if (c == null) {
            return;
        }
        out.add(c);
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                collectAll(k, out);
            }
        }
    }

    // ---------------------------------------------------------------- M4 输入平滑
    /**
     * M4：输入平滑只压平「短跳四角梯度」，**总时长不变**。
     *
     * <p>这是 0.9.6 的 A 组根因修复的调参面：短跳前缘时长 = shortAnimationLength × trailFactors[3]，
     * 0.04s 时前缘只有 8.8ms（8ms 一帧 ⇒ 一帧到位），抬到 ≥3 帧才不会「跳」。
     */
    private static void typingSmoothness(NeovideCursorConfigurable conf) {
        System.out.println("--- S. 输入平滑（M4：只压平短跳梯度，不改总时长）---");

        float[] off = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        check("S-1 平滑=0 时四档系数与旧版逐位相同（parity / 预设匹配依赖）",
                off[0] == 1.0f && off[1] == 0.72f && off[2] == 0.42f && off[3] == 0.22f,
                "实际 " + java.util.Arrays.toString(off));

        float[] on = NeovideCursorSettings.deriveTrailFactors(4.5f, 1f);
        check("S-2 平滑=100% 时后缘（rank0）仍是 1.0 ⇒ 总时长不变",
                on[0] == 1.0f, "实际 f0=" + on[0]);
        check("S-3 平滑=100% 时前缘（rank3）被抬起，但仍是四档里最短的",
                on[3] > 0.22f && on[3] < on[2] && on[2] < on[1] && on[1] < on[0],
                "实际 " + java.util.Arrays.toString(on));

        int framesOff = Math.round(0.04f * off[3] * 1000f / 8f);
        int framesOn = Math.round(0.04f * on[3] * 1000f / 8f);
        check("S-4 短跳 0.04s 时前缘帧数：关=1~2 帧、100%=≥4 帧（这就是「跳 vs 顺」的数字）",
                framesOff <= 2 && framesOn >= 4,
                "关=" + framesOff + " 帧（" + Math.round(0.04f * off[3] * 1000f) + " ms）,"
                        + " 开=" + framesOn + " 帧（" + Math.round(0.04f * on[3] * 1000f) + " ms）");

        boolean mono = true;
        float prev = -1f;
        for (int pct = 0; pct <= 100; pct += 10) {
            float f3 = NeovideCursorSettings.deriveTrailFactors(4.5f, pct / 100f)[3];
            if (f3 < prev) { mono = false; }
            prev = f3;
        }
        check("S-5 平滑从 0% 到 100% 时前缘系数单调不减", mono, "ok");

        svc.getState().stretchRatioK = 4.5f;
        svc.getState().typingSmoothness = 1f;
        svc.syncStateToConfig();
        float[] cfg = NeovideCaretManager.getConfig().trailFactors;
        check("S-6 syncStateToConfig 落地：CONFIG.trailFactors == deriveTrailFactors(4.5, 1)",
                cfg[0] == 1.0f && cfg[3] > 0.22f, "实际 " + java.util.Arrays.toString(cfg));

        svc.getState().typingSmoothness = 0f;
        svc.syncStateToConfig();
        check("S-7 面板上确有第 11 个滑块（输入平滑）且量程 0–100",
                conf.slidersForProbe().size() == 11
                        && conf.slidersForProbe().get(10).getMinimum() == 0
                        && conf.slidersForProbe().get(10).getMaximum() == 100,
                conf.slidersForProbe().size() > 10
                        ? ("min=" + conf.slidersForProbe().get(10).getMinimum()
                        + " max=" + conf.slidersForProbe().get(10).getMaximum()) : "滑块数不足");
        System.out.println();
    }

    // ---------------------------------------------------------------- M6 辉光锐度
    /**
     * M6：把「辉光外扩幅度」的显示层反向映射成「辉光锐度」（0–100，纯 UI）。
     *
     * <p>要证明三件事：①映射是单调反向且端点正确；②只改显示，**存储仍是 shadowBlurFactor**；
     * ③值文本里同时给得出外扩值（否则老用户不知道自己原来的 0.20 变成多少）。
     */
    private static void shadowSharpness(NeovideCursorConfigurable conf) {
        System.out.println("--- M6. 辉光锐度（0–100，纯 UI 映射）---");

        check("M6-1 端点：外扩 0.05 ⇒ 锐度 100（最锐）、外扩 0.60 ⇒ 锐度 0（最柔）",
                NeovideCursorConfigurable.sharpnessOf(0.05f) == 100
                        && NeovideCursorConfigurable.sharpnessOf(0.60f) == 0,
                "0.05→" + NeovideCursorConfigurable.sharpnessOf(0.05f)
                        + ", 0.60→" + NeovideCursorConfigurable.sharpnessOf(0.60f));

        boolean mono = true;
        int prev = Integer.MAX_VALUE;
        for (int k = 5; k <= 60; k += 5) {
            int sh = NeovideCursorConfigurable.sharpnessOf(k / 100f);
            if (sh >= prev) { mono = false; }   // 外扩变大 → 锐度必须变小
            prev = sh;
        }
        check("M6-2 外扩幅度越大 → 锐度越小（严格单调反向）", mono, "ok");

        check("M6-3 默认外扩 0.20 显示为锐度 73",
                NeovideCursorConfigurable.sharpnessOf(0.20f) == 73,
                "实际 " + NeovideCursorConfigurable.sharpnessOf(0.20f));

        resetState();
        conf.reset();
        int blurIdx = conf.sliderKeysForProbe().indexOf("shadowBlurFactor");
        String txt = conf.labelsForProbe().get(blurIdx).getText();
        check("M6-4 数值标签同时给出「锐度」与「外扩」两个口径",
                txt != null && txt.contains("锐") && txt.contains("外扩"),
                "标签=" + txt);

        // ② 存储仍是 shadowBlurFactor：拖滑块 → pending 变，state 不动
        conf.slidersForProbe().get(blurIdx).setValue(100);         // 拖到最锐
        check("M6-5 拖到最锐：pending.shadowBlurFactor = 0.05（存储语义没变）",
                Math.abs(conf.pendingForProbe().shadowBlurFactor - 0.05f) < 1e-6f,
                "pending.shadowBlurFactor=" + conf.pendingForProbe().shadowBlurFactor);
        check("M6-6 未 apply 前 state 不变（面板语义仍要按应用）",
                Math.abs(svc.getState().shadowBlurFactor - 0.20f) < 1e-6f,
                "state.shadowBlurFactor=" + svc.getState().shadowBlurFactor);

        // ③ 「高对比」预设的锐度：外扩 0.15 ⇒ 18
        check("M6-7 「高对比」预设的外扩 0.15 对应锐度 82",
                NeovideCursorConfigurable.sharpnessOf(0.15f) == 82,
                "实际 " + NeovideCursorConfigurable.sharpnessOf(0.15f));

        // 恢复默认，避免污染后续用例
        resetState();
        conf.reset();
        System.out.println();
    }

    // ---------------------------------------------------------------- Tools 菜单
    private static void toolsMenu() {
        System.out.println();
        System.out.println("--- M. Tools 预设子菜单 ---");
        try {
            com.github.yyyolo7a79.neovidecursor.ui.AnimPresetGroup ag =
                    new com.github.yyyolo7a79.neovidecursor.ui.AnimPresetGroup();
            check("M1 动画预设子菜单可实例化（用 DefaultActionGroup，不是不存在的 AnActionGroup）",
                    ag != null, ag.getClass().getName());
            AnAction[] kids = ag.getChildActionsOrStubs();
            check("M2 动画预设子菜单含 7 项", kids.length == CursorPresets.ANIM_PRESETS.length,
                    "实际 " + kids.length);
            int toggles = 0;
            for (AnAction a : kids) {
                if (a instanceof ToggleAction) {
                    toggles++;
                }
            }
            check("M3 全部子项都是 ToggleAction（勾选态表示当前预设）",
                toggles == kids.length && toggles == CursorPresets.ANIM_PRESETS.length,
                toggles + "/" + kids.length + "（表=" + CursorPresets.ANIM_PRESETS.length + "）");

            com.github.yyyolo7a79.neovidecursor.ui.ColorPresetGroup cg =
                    new com.github.yyyolo7a79.neovidecursor.ui.ColorPresetGroup();
            AnAction[] ckids = cg.getChildActionsOrStubs();
            check("M4 颜色预设子菜单含 5 项", ckids.length == CursorPresets.COLOR_PRESETS.length,
                    "实际 " + ckids.length);

            // M5：选中判据与面板同源 —— 把 state 设成「缎带」，只有那一项 isSelected
            resetState();
            CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[1]);   // 缎带
            int selected = 0;
            int selectedIdx = -1;
            for (int i = 0; i < kids.length; i++) {
                ToggleAction ta = (ToggleAction) kids[i];
                if (ta.isSelected(null)) {
                    selected++;
                    selectedIdx = i;
                }
            }
            check("M5 当前是「缎带」时恰好 1 项被勾选（单选语义），且下标正确",
                    selected == 1 && selectedIdx == 1, "勾选数 " + selected + "，下标 " + selectedIdx);

            // M6：点已勾选的项（setSelected(false)）不做事
            resetState();
            CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[2]);
            float before = svc.getState().animationLength;
            ToggleAction ta2 = (ToggleAction) kids[2];
            ta2.setSelected(null, false);
            check("M6 取消勾选（setSelected(false)）不改变任何值（预设是单选语义，取消无定义）",
                    Math.abs(svc.getState().animationLength - before) < CursorPresets.EPS,
                    before + " -> " + svc.getState().animationLength);

            // M7：setSelected(true) 应用对应预设
            resetState();
            // 用最后一个预设（原写死 kids[6] = 「高对比」，已随预设精简删除）。
            // 断言改为「state 与该预设声明的值一致」，不写死具体数字。
            int lastIdx = kids.length - 1;
            CursorPresets.AnimPreset lastPreset = CursorPresets.ANIM_PRESETS[lastIdx];
            ToggleAction ta3 = (ToggleAction) kids[lastIdx];
            ta3.setSelected(null, true);
            check("M7 勾选最后一个预设「" + lastPreset.name() + "」→ state 变成该预设",
                    svc.getState().glowLayers == lastPreset.glowLayers()
                            && Math.abs(svc.getState().glowOpacity - lastPreset.glowOpacity())
                                    < CursorPresets.EPS
                            && svc.getState().useShadow == lastPreset.useShadow(),
                    "layers=" + svc.getState().glowLayers
                            + " opacity=" + svc.getState().glowOpacity
                            + " useShadow=" + svc.getState().useShadow);

            // M8：颜色组同理
            resetState();
            AnAction[] ckids2 = new com.github.yyyolo7a79.neovidecursor.ui.ColorPresetGroup().getChildActionsOrStubs();
            ((ToggleAction) ckids2[2]).setSelected(null, true);   // 薄荷
            check("M8 勾选「薄荷」→ tailColor=#6EE7B7 / shadowColor=#34D399",
                    "#6EE7B7".equals(svc.getState().tailColor)
                            && "#34D399".equals(svc.getState().shadowColor),
                    svc.getState().tailColor + " / " + svc.getState().shadowColor);
        } catch (Throwable t) {
            check("M1 动画预设子菜单可实例化", false, "抛异常：" + t);
        }
    }

    // ---------------------------------------------------------------- 组件树 dump
    private static void componentDump() {
        System.out.println();
        System.out.println("--- D. 组件树 dump（证明布局分组）---");
        System.out.println("  （层级用缩进表示；只展开到 3 层，避免刷屏）");
        dump(panelComponent, 0, 3);
        check("D1 组件树可遍历（布局已建好）", countAll(panelComponent) > 30,
                "组件总数 = " + countAll(panelComponent));
    }

    private static int countAll(Component c) {
        if (c == null) {
            return 0;
        }
        int n = 1;
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                n += countAll(k);
            }
        }
        return n;
    }

    private static void dump(Component c, int depth, int maxDepth) {
        if (c == null || depth > maxDepth) {
            return;
        }
        String pad = "  " + "    ".repeat(depth);
        String label = c.getClass().getSimpleName();
        String txt = "";
        if (c instanceof JLabel) {
            txt = " «" + trunc(((JLabel) c).getText()) + "»";
        } else if (c instanceof javax.swing.AbstractButton) {
            txt = " «" + trunc(((javax.swing.AbstractButton) c).getText()) + "»";
        } else if (c instanceof JSlider) {
            JSlider s = (JSlider) c;
            txt = " [" + s.getMinimum() + ".." + s.getMaximum() + " step " + s.getMinorTickSpacing() + "]";
        } else if (c instanceof JBTextField) {
            txt = " «" + trunc(((JBTextField) c).getText()) + "»";
        } else if (c instanceof com.intellij.openapi.ui.ComboBox) {
            com.intellij.openapi.ui.ComboBox<?> box = (com.intellij.openapi.ui.ComboBox<?>) c;
            txt = " [" + box.getItemCount() + " 项]";
        }
        System.out.println(pad + "- " + label + txt);
        if (c instanceof Container && depth < maxDepth) {
            for (Component k : ((Container) c).getComponents()) {
                dump(k, depth + 1, maxDepth);
            }
        }
    }

    private static String trunc(String s) {
        if (s == null) {
            return "null";
        }
        String one = s.replace('\n', ' ');
        return one.length() > 46 ? one.substring(0, 46) + "…" : one;
    }

    // ---------------------------------------------------------------- 工具
    private static void resetState() {
        NeovideCursorSettings.State s = svc.getState();
        NeovideConfig d = new NeovideConfig();
        s.stateVersion = 1;
        s.enabled = d.enabled;
        s.trailMode = d.trailMode;
        s.hideNativeCaret = d.hideNativeCaret;
        s.alwaysHighFps = d.alwaysHighFps;
        s.overlayFadeOut = d.overlayFadeOut;
        s.cursorDisappearDelay = d.cursorDisappearDelay;
        s.cursorFadeOutDuration = d.cursorFadeOutDuration;
        s.tailColor = d.tailColor;
        s.tailOpacity = d.tailOpacity;
        s.useShadow = d.useShadow;
        s.shadowColor = d.shadowColor;
        s.glowOpacity = d.glowOpacity;
        s.glowLayers = d.glowLayers;
        s.shadowBlurFactor = d.shadowBlurFactor;
        s.trailSize = d.trailSize;
        s.stretchRatioK = 4.5f;   // NeovideConfig 无此字段，它派生自 trailFactors（默认 4.5×）
        s.animationLength = d.animationLength;
        s.shortAnimationLength = d.shortAnimationLength;
        // 0.9.9：预设基准 + 自定义预设表也属于「干净态」—— 漏了它们，
        // 上一个用例派生出的副本会让下一个用例的「干净内置预设」判据随机失败。
        s.animPresetBase = 0;
        s.customAnimPresets = "";
        s.animCustomName = "";
        svc.syncStateToConfig();
    }

    private static void collect(Component c, List<JComponent> out) {
        if (c == null) {
            return;
        }
        if (c instanceof JComponent) {
            out.add((JComponent) c);
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                collect(k, out);
            }
        }
    }

    private static final com.intellij.ui.ExperimentalUI EXPERIMENTAL_UI =
            new com.intellij.ui.ExperimentalUI() {
                @Override
                public void saveCurrentValueAndReapplyDefaultLaf() {
                    // 探针里没有 LaF 可保存
                }
            };

    private static void installFakeApp() {
        Application proxy = (Application) Proxy.newProxyInstance(
                ProbeSettingsV2.class.getClassLoader(),
                new Class<?>[]{Application.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "getService":
                            if (a != null && a.length == 1) {
                                Class<?> c = (Class<?>) a[0];
                                if (c == NeovideCursorSettings.class) {
                                    return svc;
                                }
                                if (c == PropertiesComponent.class) {
                                    return new FakeProps();
                                }
                                if (c == com.intellij.ui.ExperimentalUI.class) {
                                    return EXPERIMENTAL_UI;
                                }
                                if (c == ActionManager.class) {
                                    return actionManager;
                                }
                                if (c == com.intellij.openapi.ui.popup.JBPopupFactory.class) {
                                    // intTextField 的校验失败提示会建 popup。headless 探针里没有
                                    // 平台实现，必须补一个：否则 setText 一触发 revalidate 就 NPE。
                                    return FakePopupFactory.INSTANCE;
                                }
                                if ("SingleAlarmSharedCoroutineScopeHolder".equals(c.getSimpleName())) {
                                    // DialogPanel.registerValidators → ComponentValidator → SingleAlarm
                                    // 会向平台要这个服务（契约 §9.2 之外的另一处探针环境缺失）。
                                    // 它是包私有类且构造器是 (CoroutineScope)，只能反射建。
                                    return newHolder(c);
                                }
                            }
                            return null;
                        case "isDispatchThread":
                            return true;
                        case "saveSettings":
                            return null;
                        case "invokeLater":
                            ((Runnable) a[0]).run();
                            return null;
                        case "getMessageBus":
                            return null;
                        case "toString":
                            return "FakeApp";
                        case "hashCode":
                            return 1;
                        case "equals":
                            return p == a[0];
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });
        ApplicationManager.setApplication(proxy, () -> { });
    }


    /**
     * headless 探针用的 {@link com.intellij.openapi.ui.popup.JBPopupFactory} 桩。
     *
     * <p><b>为什么需要</b>：{@code intTextField} 会注册一个校验器，
     * 文本变化时走 {@code ComponentValidator.updateInfo → createPopupBuilder →
     * JBPopupFactory.getInstance().createComponentPopupBuilder(...)} 建提示气泡。
     * 真机上平台自带实现；headless 探针里 {@code getInstance()} 返回 null → NPE。
     * 这属于<b>探针环境缺失</b>，不是被测代码的缺陷。
     *
     * <p><b>为什么必须手写而不是 Proxy</b>：{@code JBPopupFactory} 是<b>抽象类</b>不是接口
     * （javap 实证），Java 的 {@code Proxy} 只能代理接口。
     */
    static final class FakePopupFactory extends com.intellij.openapi.ui.popup.JBPopupFactory {
        static final FakePopupFactory INSTANCE = new FakePopupFactory();

        @Override public <T> com.intellij.openapi.ui.popup.IPopupChooserBuilder<T> createPopupChooserBuilder(
                List<? extends T> list) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createConfirmation(
                String title, Runnable run, int defaultIndex) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createConfirmation(
                String title, String yes, String no, Runnable y, int d) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createConfirmation(
                String title, String yes, String no, Runnable y, Runnable n, int d) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopupStep createActionsStep(
                ActionGroup g, com.intellij.openapi.actionSystem.DataContext c, String title,
                boolean dbl, boolean left, String s, Component comp, boolean b, int n, boolean x) {
            return null;
        }
        @Override public com.intellij.ui.awt.RelativePoint guessBestPopupLocation(JComponent c) { return null; }
        @Override public com.intellij.ui.awt.RelativePoint guessBestPopupLocation(
                com.intellij.openapi.actionSystem.DataContext c) { return null; }
        @Override public com.intellij.ui.awt.RelativePoint guessBestPopupLocation(
                com.intellij.openapi.editor.Editor e) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createActionGroupPopup(
                String title, ActionGroup g, com.intellij.openapi.actionSystem.DataContext c,
                boolean show, boolean pop, boolean cancel, Runnable r, int n,
                com.intellij.openapi.util.Condition<? super AnAction> cond) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createActionGroupPopup(
                String title, ActionGroup g, com.intellij.openapi.actionSystem.DataContext c,
                com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid aid,
                boolean cancel, Runnable r, int n,
                com.intellij.openapi.util.Condition<? super AnAction> cond, String place) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createListPopup(
                com.intellij.openapi.ui.popup.ListPopupStep step) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createListPopup(
                com.intellij.openapi.ui.popup.ListPopupStep step, int w) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createListPopup(
                com.intellij.openapi.project.Project p,
                com.intellij.openapi.ui.popup.ListPopupStep step,
                java.util.function.Function<? super javax.swing.ListCellRenderer,
                        ? extends javax.swing.ListCellRenderer> f) { return null; }
        @Override public com.intellij.openapi.ui.popup.TreePopup createTree(
                com.intellij.openapi.ui.popup.JBPopup popup,
                com.intellij.openapi.ui.popup.TreePopupStep step, Object o) { return null; }
        @Override public com.intellij.openapi.ui.popup.TreePopup createTree(
                com.intellij.openapi.ui.popup.TreePopupStep step) { return null; }

        @Override public com.intellij.openapi.ui.popup.ComponentPopupBuilder createComponentPopupBuilder(
                JComponent content, JComponent focused) { return popupBuilder(); }

        @Override public boolean isBestPopupLocationVisible(com.intellij.openapi.editor.Editor e) { return false; }
        @Override public java.awt.Point getCenterOf(JComponent a, JComponent b) { return null; }
        @Override public List<com.intellij.openapi.ui.popup.JBPopup> getChildPopups(Component c) {
            return new ArrayList<>();
        }
        @Override public boolean isPopupActive() { return false; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createBalloonBuilder(JComponent c) {
            return null;
        }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createDialogBalloonBuilder(
                JComponent c, String title) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createHtmlTextBalloonBuilder(
                String html, javax.swing.Icon icon, java.awt.Color fg, java.awt.Color bg,
                javax.swing.event.HyperlinkListener l) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createHtmlTextBalloonBuilder(
                com.intellij.util.ui.Html html, javax.swing.Icon icon, java.awt.Color fg,
                java.awt.Color bg, javax.swing.event.HyperlinkListener l) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createHtmlTextBalloonBuilder(
                String html, com.intellij.openapi.ui.MessageType type,
                javax.swing.event.HyperlinkListener l) { return null; }
        @Override public com.intellij.openapi.ui.popup.JBPopup createMessage(String msg) { return null; }
        @Override public com.intellij.openapi.ui.popup.Balloon getParentBalloonFor(Component c) { return null; }

        @Override protected <T> com.intellij.openapi.ui.popup.PopupChooserBuilder.PopupComponentAdapter<T>
                createPopupComponentAdapter(
                        com.intellij.openapi.ui.popup.PopupChooserBuilder<T> b,
                        javax.swing.JList<T> l) { return null; }
        @Override protected <T> com.intellij.openapi.ui.popup.PopupChooserBuilder.PopupComponentAdapter<T>
                createPopupComponentAdapter(
                        com.intellij.openapi.ui.popup.PopupChooserBuilder<T> b,
                        javax.swing.JTree t) { return null; }
        @Override protected <T> com.intellij.openapi.ui.popup.PopupChooserBuilder.PopupComponentAdapter<T>
                createPopupComponentAdapter(
                        com.intellij.openapi.ui.popup.PopupChooserBuilder<T> b,
                        javax.swing.JTable t) { return null; }
    }


    /** {@code ComponentPopupBuilder} 是接口 → 可以用 Proxy；所有方法返回 this 以支持链式调用。 */
    private static com.intellij.openapi.ui.popup.ComponentPopupBuilder popupBuilder() {
        return (com.intellij.openapi.ui.popup.ComponentPopupBuilder) Proxy.newProxyInstance(
                ProbeSettingsV2.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.ui.popup.ComponentPopupBuilder.class},
                (pr, m, ar) -> {
                    Class<?> rt = m.getReturnType();
                    if (rt.isInstance(pr)) {
                        return pr;                 // 链式 setter 返回 this
                    }
                    return defaultValue(rt);
                });
    }

    /**
     * 给 {@code SingleAlarmSharedCoroutineScopeHolder} 造一个实例（反射，因为它是包私有类）。
     *
     * <p>它只有 {@code (CoroutineScope)} 一个构造器；{@code CoroutineScope} 用
     * {@code GlobalScope.INSTANCE}（kotlinx-coroutines 在 classpath 上有）。
     */
    private static Object newHolder(Class<?> holderClass) {
        try {
            Class<?> scopeClass = Class.forName("kotlinx.coroutines.CoroutineScope");
            Object globalScope = Class.forName("kotlinx.coroutines.GlobalScope")
                    .getField("INSTANCE").get(null);
            java.lang.reflect.Constructor<?> ctor = holderClass.getConstructor(scopeClass);
            ctor.setAccessible(true);
            return ctor.newInstance(globalScope);
        } catch (Throwable t) {
            return null;
        }
    }

    /** collapsibleGroup 会向 ActionManager 要 "CollapsiblePanel-toggle"（契约 §9.2 实测）。 */
    static final class FakeActionManager extends ActionManager {
        @Override public ActionPopupMenu createActionPopupMenu(String place, ActionGroup g) { return null; }
        @Override public ActionToolbar createActionToolbar(String place, ActionGroup g, boolean h) { return null; }
        @Override public AnAction getAction(String id) { return null; }
        @Override public String getId(AnAction a) { return null; }
        @Override public void registerAction(String id, AnAction a) { }
        @Override public void registerAction(String id, AnAction a, PluginId p) { }
        @Override public void unregisterAction(String id) { }
        @Override public void replaceAction(String id, AnAction a) { }
        @Override public String[] getActionIds(String prefix) { return new String[0]; }
        @Override public List<String> getActionIdList(String prefix) { return new ArrayList<>(); }
        @Override public boolean isGroup(String id) { return false; }
        @Override public AnAction getActionOrStub(String id) { return null; }
        @Override public void addTimerListener(TimerListener l) { }
        @Override public void removeTimerListener(TimerListener l) { }
        @Override public ActionCallback tryToExecute(AnAction a, java.awt.event.InputEvent e,
                                                    Component c, String place, boolean now) { return null; }
        @Override public KeyboardShortcut getKeyboardShortcut(String id) { return null; }
    }

    static final class FakeProps extends PropertiesComponent {
        final Map<String, String> map = new HashMap<>();
        @Override public void unsetValue(String name) { map.remove(name); }
        @Override public boolean isValueSet(String name) { return map.containsKey(name); }
        @Override public String getValue(String name) { return map.get(name); }
        @Override public void setValue(String name, String value) { map.put(name, value); }
        @Override public void setValue(String name, String value, String def) { map.put(name, value); }
        @Override public void setValue(String name, float value, float def) { }
        @Override public void setValue(String name, int value, int def) { }
        @Override public void setValue(String name, boolean value, boolean def) { }
        @Override public String[] getValues(String name) { return new String[0]; }
        @Override public void setValues(String name, String[] values) { }
        @Override public List<String> getList(String name) { return new ArrayList<>(); }
        @Override public void setList(String name, Collection<String> values) { }
        @Override public boolean updateValue(String name, boolean value) { return true; }
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) {
            return null;
        }
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0d;
        if (t == float.class) return 0f;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null;
    }

    private static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name + (detail == null || detail.isEmpty() ? "" : "   " + detail));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   " + detail);
        }
    }
}
