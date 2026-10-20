import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.DialogPanel;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSlider;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 缺陷 1 / 缺陷 2 / 缺陷 3 的离线探针（t2）。
 *
 * <p>三组断言，各自独立：
 * <ul>
 *   <li><b>B1-x（缺陷 1，滑块跟手）</b>：驱动 {@code slider.setValue(...)} →
 *       右侧数值标签必须跟着变、{@code pending} 必须被写入；
 *   <li><b>B2-x（缺陷 2，刻度挤压）</b>：每个滑块的 {@code labelTable} 条目数必须 ≤ 3，
 *       且 {@code paintLabels=true} / {@code paintTicks=false}；
 *   <li><b>B3-x（缺陷 3，Tools 覆盖面板）</b>：复现「面板打开 → Tools 改字段 → 面板点 OK」
 *       → Tools 的改动必须保住；用户真拖过的字段以用户为准；两者同时发生也必须都对。
 *   <li><b>B4-x（缺陷 1 的 L3 次生缺陷 / V11）</b>：{@code isModified()} 的双向正确性。
 * </ul>
 *
 * <p>退出码：0 = 全通过；1 = 有失败项（脚本据此判红）。
 */
public final class Probe4Bugs {

    private static int pass = 0;
    private static int fail = 0;

    private static NeovideCursorSettings svc;
    private static final FakeProps PC = new FakeProps();

    /** createComponent() 的返回值（探针要遍历它找复选框）。 */
    private static JComponent panelComponent;

    /**
     * 10 个滑块的标识（与面板从上到下的顺序一致）。
     *
     * <p>0.9.5 的 t3 把 {@code animationLength} / {@code shortAnimationLength} 也做成可调，
     * 因此数量从 8 变成 10（契约 §7.1）。**缺陷 1 的判据随之变成 10/10**。
     */
    private static final String[] SLIDER_NAMES = {
            "淡出前保持时长", "淡出时长", "拖尾不透明度", "辉光峰值不透明度",
            "辉光层数", "辉光外扩幅度", "拖尾密度", "拖尾拉伸强度",
            "跨行动画时长", "短动画时长", "输入平滑"
    };

    /** 每个滑块拖动后的目标值（都在各自刻度网格上：50 / 25 / 5 / 5 / 1 / 5 / 5 / 5 / 1 / 1）。 */
    // M4 起 11 个滑块：最后一个 typingSmoothness 拖到 60（0–100 步长 5）
    private static final int[] SLIDER_TARGET = {250, 500, 40, 20, 4, 10, 30, 20, 20, 10, 60};

    public static void main(String[] args) throws Exception {
        System.out.println("=== t2 探针：4 个缺陷（滑块跟手 / 刻度挤压 / Tools 覆盖 / 切主题复活）===");
        System.out.println("（本类只覆盖前三个；缺陷 4 在 ProbeSchemeChange）");
        System.out.println();

        svc = new NeovideCursorSettings();
        installFakeApp();

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        JComponent comp = conf.createComponent();
        panelComponent = comp;
        check("B0 面板可实例化（createComponent 非 null）", comp != null,
                comp == null ? "null" : comp.getClass().getName());

        List<JComponent> all = new ArrayList<>();
        collect(comp, all);

        // ============ 缺陷 1 / 2 ============
        List<JSlider> sliders = conf.slidersForProbe();
        List<JLabel> labels = conf.labelsForProbe();
        // 0.9.6 / M4：新增第 11 个滑块「输入平滑」
        check("B1-0 面板里有 11 个滑块（与 labelsForProbe 等长；t3 起含两个动画时序参数 + M4 输入平滑）",
                sliders.size() == 11 && labels.size() == 11,
                "sliders=" + sliders.size() + " labels=" + labels.size());

        defect1(sliders, labels, conf);
        defect2(sliders);
        defect3(conf);

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

    // ---------------------------------------------------------------- 缺陷 1
    private static void defect1(List<JSlider> sliders, List<JLabel> labels,
                                NeovideCursorConfigurable conf) {
        System.out.println();
        System.out.println("--- 缺陷 1：滑块拖动时数值标签必须实时跟随（修复前 0/8）---");

        int followed = 0;
        int listenerOk = 0;
        for (int i = 0; i < sliders.size(); i++) {
            JSlider s = sliders.get(i);
            JLabel l = labels.get(i);

            // 每个滑块恰好 1 个「自己挂的」ChangeListener（契约 §2.3：不许用 showValueHint 污染它）
            int n = s.getChangeListeners().length;
            boolean one = n == 1;
            if (one) {
                listenerOk++;
            }

            String before = l.getText();
            s.setValue(SLIDER_TARGET[i]);
            String after = l.getText();
            boolean moved = after != null && !after.equals(before);

            int pendingTick = tickOf(conf, i);
            boolean pendingWritten = pendingTick == s.getValue();

            boolean ok = moved && pendingWritten;
            if (ok) {
                followed++;
            }
            System.out.println("    " + (ok ? "PASS " : "FAIL ") + SLIDER_NAMES[i]
                    + "  listener=" + n + "  「" + before + "」→「" + after + "」"
                    + "  pendingTick=" + pendingTick + "  sliderValue=" + s.getValue());
        }
        check("B1-1 11/11 滑块拖动时数值标签跟随（修复前 0/8；M4 起 11 个）", followed == 11,
                followed + "/11 跟随");
        check("B1-2 每个滑块恰好 1 个 ChangeListener（不用 showValueHint 污染判据）",
                listenerOk == 11, listenerOk + "/11 恰为 1");

        // 幂等：吸附下一轮 setValue 可能触发两次 ChangeEvent，结果必须相同
        JSlider s0 = sliders.get(0);
        s0.setValue(100);
        String t1 = labels.get(0).getText();
        s0.setValue(100);
        String t2 = labels.get(0).getText();
        check("B1-3 监听器幂等：重复 setValue 同一值标签不变", t1.equals(t2), "「" + t1 + "」/「" + t2 + "」");
    }

    // ---------------------------------------------------------------- 缺陷 2
    private static void defect2(List<JSlider> sliders) {
        System.out.println();
        System.out.println("--- 缺陷 2：刻度标签条目数 ≤ 3（修复前 51 / 201 / 101 条）---");
        int okCount = 0;
        for (int i = 0; i < sliders.size(); i++) {
            JSlider s = sliders.get(i);
            int n = s.getLabelTable() == null ? 0 : s.getLabelTable().size();
            boolean ok = n >= 2 && n <= 3 && s.getPaintLabels() && !s.getPaintTicks();
            if (ok) {
                okCount++;
            }
            System.out.println("    " + (ok ? "PASS " : "FAIL ") + SLIDER_NAMES[i]
                    + "  labelTable=" + n + "  paintLabels=" + s.getPaintLabels()
                    + "  paintTicks=" + s.getPaintTicks());
        }
        check("B2-1 11/11 滑块的 labelTable 条目数 ≤ 3 且 ≥ 2", okCount == 11, okCount + "/11");
        check("B2-2 11/11 滑块 paintLabels=true（labelTable 不会自动打开它）",
                countPaintLabels(sliders), "期望 11");
        check("B2-3 11/11 滑块 paintTicks=false（不画刻度线，只留 label 位置语义）",
                countPaintTicks(sliders), "期望 11");
        check("B2-4 吸附仍开启（setSnapToTicks(true)，关掉 paintTicks 不影响吸附）",
                countSnap(sliders), "期望 11");
    }

    private static boolean countPaintLabels(List<JSlider> sliders) {
        int n = 0;
        for (JSlider s : sliders) {
            if (s.getPaintLabels()) {
                n++;
            }
        }
        return n == 11;
    }

    private static boolean countPaintTicks(List<JSlider> sliders) {
        int n = 0;
        for (JSlider s : sliders) {
            if (!s.getPaintTicks()) {
                n++;
            }
        }
        return n == 11;
    }

    private static boolean countSnap(List<JSlider> sliders) {
        int n = 0;
        for (JSlider s : sliders) {
            if (s.getSnapToTicks()) {
                n++;
            }
        }
        return n == 11;
    }

    // ---------------------------------------------------------------- 缺陷 3
    private static void defect3(NeovideCursorConfigurable conf) {
        System.out.println();
        System.out.println("--- 缺陷 3：面板打开期间 Tools 的改动不得被覆盖（修复前会被冲回）---");

        NeovideCursorSettings.State st = svc.getState();

        // ===== 场景 A（复现用户实测路径）：面板打开 → Tools 关掉「辉光淡出」→ 面板点 OK（没碰任何控件）=====
        st.enabled = true;
        st.trailMode = com.github.yyyolo7a79.neovidecursor.core.NeovideConfig.TrailMode.SPRING;
        st.hideNativeCaret = true;
        st.alwaysHighFps = true;
        st.overlayFadeOut = true;
        st.cursorDisappearDelay = 50;
        st.cursorFadeOutDuration = 0.075f;
        st.tailColor = "#FFC0CB";
        st.tailOpacity = 1.0f;
        st.useShadow = true;
        st.shadowColor = "#FFC0CB";
        st.glowOpacity = 0.55f;
        st.glowLayers = 8;
        st.shadowBlurFactor = 0.2f;
        st.trailSize = 1f;
        st.stretchRatioK = 4.5f;
        conf.reset();                                   // 面板打开（拍 baseline）

        st.overlayFadeOut = false;                      // ← Tools 菜单：取消「辉光淡出」
        conf.apply();                                   // ← 用户点 OK，没改任何控件
        check("B3-1 【用户实测场景】Tools 关掉 overlayFadeOut 后点 OK，false 必须保住",
                !svc.getState().overlayFadeOut,
                "overlayFadeOut=" + svc.getState().overlayFadeOut + "（修复前被冲回 true）");

        // ===== 场景 B：用户真拖了滑块 + Tools 改了另一个字段 → 两者都不丢 =====
        st.glowLayers = 8;
        st.overlayFadeOut = true;
        conf.reset();                                   // 面板打开（新 baseline）

        List<JSlider> sliders = conf.slidersForProbe();
        sliders.get(4).setValue(4);                     // 用户拖「辉光层数」8 → 4
        st.overlayFadeOut = false;                      // Tools 改「辉光淡出」→ false（用户没碰）
        conf.apply();
        check("B3-2 用户拖过的字段以用户为准（glowLayers=4）",
                svc.getState().glowLayers == 4, "glowLayers=" + svc.getState().glowLayers);
        check("B3-3 用户没碰的字段保住 Tools 的值（overlayFadeOut=false）",
                !svc.getState().overlayFadeOut, "overlayFadeOut=" + svc.getState().overlayFadeOut);

        // ===== 场景 C：用户在面板里改字段 → 必须生效（别把修法做成「谁都不写」）=====
        conf.reset();
        List<JSlider> s2 = conf.slidersForProbe();
        s2.get(0).setValue(300);                        // 淡出前保持时长 50 → 300
        conf.apply();
        check("B3-4 面板里用户真改的字段必须生效（cursorDisappearDelay=300）",
                svc.getState().cursorDisappearDelay == 300,
                "cursorDisappearDelay=" + svc.getState().cursorDisappearDelay);

        // ===== 场景 D：连续两次 apply，第二次不得把第一次的值误判为非脏 =====
        conf.reset();
        List<JSlider> s3 = conf.slidersForProbe();
        s3.get(4).setValue(6);
        conf.apply();                                   // glowLayers 8→6
        conf.apply();                                   // 再点一次 OK（没改）
        check("B3-5 连续两次 apply 第二次不丢第一次的值（glowLayers 仍 6）",
                svc.getState().glowLayers == 6, "glowLayers=" + svc.getState().glowLayers);

        // ===== 场景 E（V11 断言 1，防 B-1）：拖滑块 → isModified() 必须 true =====
        conf.reset();
        check("B4-1 reset 后 isModified()=false", !conf.isModified(), String.valueOf(conf.isModified()));
        conf.slidersForProbe().get(0).setValue(400);
        check("B4-2 【V11-1 防 B-1】拖动滑块后 isModified()=true（应用按钮不能变灰）",
                conf.isModified(), String.valueOf(conf.isModified()));

        // ===== 场景 F（V11 断言 2）：只点复选框 → isModified() 必须仍为 true =====
        conf.reset();
        JCheckBox box = findCheckBox(conf, 0);
        check("B4-3 找到「启用拖尾光标」复选框", box != null, box == null ? "null" : "ok");
        if (box != null) {
            box.doClick();
            check("B4-4 【V11-2 防 B-1】只点复选框后 isModified()=true（不得回归）",
                    conf.isModified(), String.valueOf(conf.isModified()));
        }

        // ===== 场景 G（V11 断言 3，防 B-2）：精确值 333 落库后 slider=350，isModified 必须回落 false =====
        conf.reset();
        NeovideCursorSettings.State st2 = svc.getState();
        st2.cursorDisappearDelay = 333;                 // 模拟「XML 里是精确值 333」（不在 50 的刻度上）
        conf.reset();
        int sliderShown = conf.slidersForProbe().get(0).getValue();
        check("B4-5 精确值 333 时滑块吸附显示 350", sliderShown == 350, "滑块显示 " + sliderShown);
        check("B4-6 【V11-3 防 B-2】精确值 333 / 刻度 350 时 isModified()=false（OK 按钮不永亮）",
                !conf.isModified(), String.valueOf(conf.isModified()));

        // ===== 场景 H（V11 断言 4）：apply 后 isModified()=false =====
        conf.apply();
        check("B4-7 【V11-4】apply() 后 isModified()=false", !conf.isModified(),
                String.valueOf(conf.isModified()));
        check("B4-8 apply() 后精确值 333 被原样落库（滑块的 350 不得覆盖它）",
                svc.getState().cursorDisappearDelay == 333,
                "cursorDisappearDelay=" + svc.getState().cursorDisappearDelay);
    }

    // ---------------------------------------------------------------- 工具

    /** 读某个滑块当前对应的 pending 刻度值。 */
    private static int tickOf(NeovideCursorConfigurable conf, int index) {
        NeovideCursorSettings.State p = conf.pendingForProbe();
        switch (index) {
            case 0: return p.cursorDisappearDelay;
            case 1: return Math.round(p.cursorFadeOutDuration * 1000f);
            case 2: return Math.round(p.tailOpacity * 100f);
            case 3: return Math.round(p.glowOpacity * 100f);
            case 4: return p.glowLayers;
            case 5: return Math.round(p.shadowBlurFactor * 100f);
            case 6: return Math.round(p.trailSize * 100f);
            case 7: return Math.round(p.stretchRatioK * 10f);
            case 8: return Math.round(p.animationLength * 100f);
            case 9: return Math.round(p.shortAnimationLength * 100f);
            case 10: return Math.round(p.typingSmoothness * 100f);   // M4：输入平滑
            default: return Integer.MIN_VALUE;
        }
    }

    private static JCheckBox findCheckBox(NeovideCursorConfigurable conf, int index) {
        JComponent c = panelComponent;
        List<JComponent> all = new ArrayList<>();
        collect(c, all);
        int seen = 0;
        for (JComponent x : all) {
            if (x instanceof JCheckBox) {
                if (seen == index) {
                    return (JCheckBox) x;
                }
                seen++;
            }
        }
        return null;
    }

    private static void collect(java.awt.Component c, List<JComponent> out) {
        if (c == null) {
            return;
        }
        if (c instanceof JComponent) {
            out.add((JComponent) c);
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                collect(child, out);
            }
        }
    }

    /**
     * DSL 的 comment 标签会向平台要 ExperimentalUI（决定字体）。真机里它是平台自带服务，
     * 离线探针里必须补一个桩，否则 comment(...) 一建就抛
     * "Cannot find service com.intellij.ui.ExperimentalUI" —— 那是**探针环境缺失**。
     */
    private static final com.intellij.ui.ExperimentalUI EXPERIMENTAL_UI =
            new com.intellij.ui.ExperimentalUI() {
                @Override
                public void saveCurrentValueAndReapplyDefaultLaf() {
                    // 探针里没有 LaF 可保存
                }
            };

    /**
     * headless 探针用的 ActionManager 桩。
     *
     * <p><b>为什么 0.9.5 起必须补</b>：面板改用 {@code collapsibleGroup} 后，
     * 它构造时会 {@code ActionUtil.getShortcutSet("CollapsiblePanel-toggle")}
     * → {@code ActionManager.getInstance().getAction(id)}。真机上平台自带该 action；
     * headless 探针里 {@code getInstance()} 返回 null → NPE。
     * 这属于<b>探针环境缺失</b>（契约 §9.2 已预告），不是被测代码的缺陷。
     *
     * <p><b>为什么必须手写而不是 Proxy</b>：{@code ActionManager} 是<b>抽象类</b>不是接口。
     */
    static final class FakeActionManager extends com.intellij.openapi.actionSystem.ActionManager {
        static final FakeActionManager INSTANCE = new FakeActionManager();

        @Override public com.intellij.openapi.actionSystem.ActionPopupMenu createActionPopupMenu(
                String place, com.intellij.openapi.actionSystem.ActionGroup g) { return null; }
        @Override public com.intellij.openapi.actionSystem.ActionToolbar createActionToolbar(
                String place, com.intellij.openapi.actionSystem.ActionGroup g, boolean h) { return null; }
        @Override public com.intellij.openapi.actionSystem.AnAction getAction(String id) { return null; }
        @Override public String getId(com.intellij.openapi.actionSystem.AnAction a) { return null; }
        @Override public void registerAction(String id, com.intellij.openapi.actionSystem.AnAction a) { }
        @Override public void registerAction(String id, com.intellij.openapi.actionSystem.AnAction a,
                                           com.intellij.openapi.extensions.PluginId p) { }
        @Override public void unregisterAction(String id) { }
        @Override public void replaceAction(String id, com.intellij.openapi.actionSystem.AnAction a) { }
        @Override public String[] getActionIds(String prefix) { return new String[0]; }
        @Override public List<String> getActionIdList(String prefix) { return new ArrayList<>(); }
        @Override public boolean isGroup(String id) { return false; }
        @Override public com.intellij.openapi.actionSystem.AnAction getActionOrStub(String id) { return null; }
        @Override public void addTimerListener(com.intellij.openapi.actionSystem.TimerListener l) { }
        @Override public void removeTimerListener(com.intellij.openapi.actionSystem.TimerListener l) { }
        @Override public com.intellij.openapi.util.ActionCallback tryToExecute(
                com.intellij.openapi.actionSystem.AnAction a, java.awt.event.InputEvent e,
                java.awt.Component c, String place, boolean now) { return null; }
        @Override public com.intellij.openapi.actionSystem.KeyboardShortcut getKeyboardShortcut(
                String id) { return null; }
    }

    /**
    /**
     * headless 探针用的 {@code JBPopupFactory} 桩。
     *
     * <p>0.9.5 起每个滑块配一个 {@code intTextField}，它注册的文本校验器在文本变化时会走
     * {@code ComponentValidator.updateInfo} → {@code JBPopupFactory.getInstance()} 建提示气泡；
     * headless 下为 null → NPE。同属探针环境缺失，不是被测代码的缺陷。
     *
     * <p>必须手写而不是 Proxy：{@code JBPopupFactory} 是抽象类。
     */
    static final class FakePopupFactory extends com.intellij.openapi.ui.popup.JBPopupFactory {
        static final FakePopupFactory INSTANCE = new FakePopupFactory();

        @Override public <T> com.intellij.openapi.ui.popup.IPopupChooserBuilder<T> createPopupChooserBuilder(List<? extends T> list) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createConfirmation(String t, Runnable r, int d) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createConfirmation(String t, String y, String n, Runnable yr, int d) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createConfirmation(String t, String y, String n, Runnable yr, Runnable nr, int d) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopupStep createActionsStep(com.intellij.openapi.actionSystem.ActionGroup g, com.intellij.openapi.actionSystem.DataContext c, String title, boolean dbl, boolean left, String s, java.awt.Component comp, boolean b, int n, boolean x) { return null; }
        @Override public com.intellij.ui.awt.RelativePoint guessBestPopupLocation(javax.swing.JComponent c) { return null; }
        @Override public com.intellij.ui.awt.RelativePoint guessBestPopupLocation(com.intellij.openapi.actionSystem.DataContext c) { return null; }
        @Override public com.intellij.ui.awt.RelativePoint guessBestPopupLocation(com.intellij.openapi.editor.Editor e) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createActionGroupPopup(String title, com.intellij.openapi.actionSystem.ActionGroup g, com.intellij.openapi.actionSystem.DataContext c, boolean a, boolean b, boolean d, Runnable r, int n, com.intellij.openapi.util.Condition<? super com.intellij.openapi.actionSystem.AnAction> cond) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createActionGroupPopup(String title, com.intellij.openapi.actionSystem.ActionGroup g, com.intellij.openapi.actionSystem.DataContext c, com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid aid, boolean cancel, Runnable r, int n, com.intellij.openapi.util.Condition<? super com.intellij.openapi.actionSystem.AnAction> cond, String place) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createListPopup(com.intellij.openapi.ui.popup.ListPopupStep s) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createListPopup(com.intellij.openapi.ui.popup.ListPopupStep s, int w) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createListPopup(com.intellij.openapi.project.Project p, com.intellij.openapi.ui.popup.ListPopupStep s, java.util.function.Function<? super javax.swing.ListCellRenderer, ? extends javax.swing.ListCellRenderer> f) { return null; }
        @Override public com.intellij.openapi.ui.popup.TreePopup createTree(com.intellij.openapi.ui.popup.JBPopup p, com.intellij.openapi.ui.popup.TreePopupStep s, Object o) { return null; }
        @Override public com.intellij.openapi.ui.popup.TreePopup createTree(com.intellij.openapi.ui.popup.TreePopupStep s) { return null; }
        @Override public com.intellij.openapi.ui.popup.ComponentPopupBuilder createComponentPopupBuilder(javax.swing.JComponent content, javax.swing.JComponent focused) { return popupBuilder(); }
        @Override public boolean isBestPopupLocationVisible(com.intellij.openapi.editor.Editor e) { return false; }
        @Override public java.awt.Point getCenterOf(javax.swing.JComponent a, javax.swing.JComponent b) { return null; }
        @Override public List<com.intellij.openapi.ui.popup.JBPopup> getChildPopups(java.awt.Component c) { return new ArrayList<>(); }
        @Override public boolean isPopupActive() { return false; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createBalloonBuilder(javax.swing.JComponent c) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createDialogBalloonBuilder(javax.swing.JComponent c, String title) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createHtmlTextBalloonBuilder(String html, javax.swing.Icon icon, java.awt.Color fg, java.awt.Color bg, javax.swing.event.HyperlinkListener l) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createHtmlTextBalloonBuilder(com.intellij.util.ui.Html html, javax.swing.Icon icon, java.awt.Color fg, java.awt.Color bg, javax.swing.event.HyperlinkListener l) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createHtmlTextBalloonBuilder(String html, com.intellij.openapi.ui.MessageType type, javax.swing.event.HyperlinkListener l) { return null; }
        @Override public com.intellij.openapi.ui.popup.JBPopup createMessage(String msg) { return null; }
        @Override public com.intellij.openapi.ui.popup.Balloon getParentBalloonFor(java.awt.Component c) { return null; }
        @Override protected <T> com.intellij.openapi.ui.popup.PopupChooserBuilder.PopupComponentAdapter<T> createPopupComponentAdapter(com.intellij.openapi.ui.popup.PopupChooserBuilder<T> b, javax.swing.JList<T> l) { return null; }
        @Override protected <T> com.intellij.openapi.ui.popup.PopupChooserBuilder.PopupComponentAdapter<T> createPopupComponentAdapter(com.intellij.openapi.ui.popup.PopupChooserBuilder<T> b, javax.swing.JTree t) { return null; }
        @Override protected <T> com.intellij.openapi.ui.popup.PopupChooserBuilder.PopupComponentAdapter<T> createPopupComponentAdapter(com.intellij.openapi.ui.popup.PopupChooserBuilder<T> b, javax.swing.JTable t) { return null; }
    }

    /** ComponentPopupBuilder 是接口 → Proxy 可行；链式 setter 返回 this。
     */
    private static com.intellij.openapi.ui.popup.ComponentPopupBuilder popupBuilder() {
        return (com.intellij.openapi.ui.popup.ComponentPopupBuilder) Proxy.newProxyInstance(
                Probe4Bugs.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.ui.popup.ComponentPopupBuilder.class},
                (pr, m, a) -> m.getReturnType().isInstance(pr) ? pr : null);
    }

    /**
     * headless 探针用的 {@code SingleAlarmSharedCoroutineScopeHolder} 替身。
     *
     * <p>{@code DialogPanel.registerValidators → ComponentValidator → SingleAlarm}
     * 会向平台要这个服务。它是<b>包私有类</b>且构造器是 {@code (CoroutineScope)}，
     * 只能反射建；{@code CoroutineScope} 用 {@code GlobalScope.INSTANCE}。
     */
    private static Object newAlarmHolder(Class<?> holderClass) {
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

    private static void installFakeApp() {
        Application proxy = (Application) Proxy.newProxyInstance(
                Probe4Bugs.class.getClassLoader(),
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
                                    return PC;
                                }
                                if (c == com.intellij.ui.ExperimentalUI.class) {
                                    return EXPERIMENTAL_UI;
                                }
                                if (c == com.intellij.openapi.ui.popup.JBPopupFactory.class) { return FakePopupFactory.INSTANCE; }
                                if (c == com.intellij.openapi.actionSystem.ActionManager.class) {
                                    return FakeActionManager.INSTANCE;
                                }
                                if ("SingleAlarmSharedCoroutineScopeHolder".equals(c.getSimpleName())) {
                                    return newAlarmHolder(c);
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
                            return null;            // 本探针不测主题监听
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
