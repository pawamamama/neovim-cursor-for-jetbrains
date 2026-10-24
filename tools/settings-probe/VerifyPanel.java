import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.DialogPanel;
import com.intellij.ui.ColorPanel;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * verifier 自建探针 U —— 面板在「无 Project / 无 Editor」环境下能否实例化并跑通
 * createComponent / reset / isModified / apply。
 *
 * <p>这是真实的崩溃点：IntelliJ 的 buildSearchableOptions 与 TraverseUIStarter 会
 * **在没有打开项目的情况下**实例化每一个 Configurable 并调用 createComponent()。
 * 这一步炸了 = 真机启动时设置页直接崩。
 *
 * <p>驱动的是 verifier 自己编译的 {@link NeovideCursorConfigurable}。
 */
public final class VerifyPanel {

    private static int pass = 0;
    private static int fail = 0;

    private static NeovideCursorSettings svc;
    private static FakeProps pc = new FakeProps();
    private static int saves = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== verifier 探针 U：面板在无 Project / 无 Editor 环境下的实例化与往返 ===");
        svc = new NeovideCursorSettings();
        installFakeApp();

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        check("U1 构造器不创建 Swing 组件即可成功（无参构造）", conf != null, conf.getClass().getName());
        // 0.9.7 起 fork 独立 id：com.pawamamama.neovidecursor.settings
        check("U2 getId() 是插件 id 前缀", NeovideCursorConfigurable.ID.startsWith("com.pawamamama.neovidecursor"),
                NeovideCursorConfigurable.ID);
        check("U3 实现 SearchableConfigurable", conf instanceof com.intellij.openapi.options.SearchableConfigurable,
                "ok");
        check("U4 实现 Configurable", conf instanceof Configurable, "ok");

        long t0 = System.nanoTime();
        JComponent comp;
        try {
            comp = conf.createComponent();
        } catch (Throwable t) {
            System.out.println("  createComponent 抛异常：" + t);
            t.printStackTrace(System.out);
            comp = null;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        check("U5 createComponent() 在无 Project / 无 Editor 下不抛异常且返回非 null", comp != null,
                comp == null ? "null" : comp.getClass().getName() + "，耗时 " + ms + " ms");
        check("U6 返回的是 DialogPanel", comp instanceof DialogPanel,
                comp == null ? "null" : comp.getClass().getName());

        List<JComponent> all = new ArrayList<>();
        collect(comp, all);
        int sliders = 0, checks = 0, combos = 0, colors = 0, labels = 0;
        for (JComponent c : all) {
            if (c instanceof JSlider) sliders++;
            else if (c instanceof JCheckBox) checks++;
            else if (c instanceof JComboBox) combos++;
            else if (c instanceof ColorPanel) colors++;
            else if (c instanceof JLabel) labels++;
        }
        System.out.println("  组件统计： 总数=" + all.size() + " slider=" + sliders + " checkbox=" + checks
                + " combo=" + combos + " colorPanel=" + colors + " label=" + labels);
        // 0.9.5 的 t3 把 animationLength / shortAnimationLength 也做成可调 → 8 变 10。
        // 断言随之更新，但语义不变：滑块总数必须等于「可调数值参数」的个数。
        // 0.9.6 / M4：新增第 11 个滑块「输入平滑」（只压平短跳四角梯度，不改总时长）
        check("U7 11 个滑块（8 既有 + animationLength / shortAnimationLength + typingSmoothness）",
                sliders == 11, "实际 " + sliders);
        // 0.9.6 / M1：性能诊断从 Tools 菜单挪进面板「高级（诊断）」折叠组 → 5 变 6。
        // 断言随之更新，语义不变：复选框总数必须等于「面板上的布尔开关」个数
        // （性能诊断是唯一不持久化的那个，见 NeovideCursorConfigurable#paintDiag）。
        // 0.9.28：新增「诊断日志」复选框（CursorDiag 开关）→ 6 变 7。
        check("U8 7 个复选框（enabled / hideNativeCaret / alwaysHighFps / overlayFadeOut / useShadow / 性能诊断 / 诊断日志）",
                checks == 7, "实际 " + checks);
        // 0.9.5 的 t3 新增两个预设下拉（动画 / 颜色），加上 trailMode = 3 个。
        check("U9 3 个下拉（trailMode + 动画预设 + 颜色预设）", combos == 3, "实际 " + combos);
        check("U10 2 个 ColorPanel（tailColor / shadowColor）", colors == 2, "实际 " + colors);

        // reset 回显
        svc.getState().tailColor = "#12AB34";
        svc.getState().shadowColor = "#ABCDEF";
        svc.getState().glowLayers = 9;
        svc.getState().trailSize = 0.5f;
        conf.reset();
        System.out.println("  reset 后 state=" + svc.getState());
        ColorPanel[] cps = new ColorPanel[2];
        int ci = 0;
        for (JComponent c : all) {
            if (c instanceof ColorPanel && ci < 2) {
                cps[ci++] = (ColorPanel) c;
            }
        }
        check("U11 reset() 把 tailColor 回显进 ColorPanel",
                cps[0] != null && hex(cps[0].getSelectedColor()).equals("#12AB34"),
                cps[0] == null ? "null" : hex(cps[0].getSelectedColor()));
        check("U12 reset() 把 shadowColor 回显进 ColorPanel",
                cps[1] != null && hex(cps[1].getSelectedColor()).equals("#ABCDEF"),
                cps[1] == null ? "null" : hex(cps[1].getSelectedColor()));
        check("U13 reset() 之后 isModified() = false", !conf.isModified(), String.valueOf(conf.isModified()));

        // 只改非绑定控件（ColorPanel）→ isModified 必须为 true
        cps[0].setSelectedColor(new Color(0x65, 0x43, 0x21));
        check("U14 只改 ColorPanel 时 isModified() = true（DialogPanel 追踪不到它）",
                conf.isModified(), String.valueOf(conf.isModified()));

        // apply：颜色写回 state，并经 applyConfig 进 CONFIG
        conf.apply();
        System.out.println("  apply 后 state=" + svc.getState());
        check("U15 apply() 把 ColorPanel 的值写回 state.tailColor",
                "#654321".equals(svc.getState().tailColor), svc.getState().tailColor);
        check("U16 apply() 走到了 applyConfig()（CONFIG.tailColor 同步）",
                "#654321".equals(NeovideCaretManager.getConfig().tailColor),
                NeovideCaretManager.getConfig().tailColor);
        check("U17 apply() 之后 isModified() = false", !conf.isModified(), String.valueOf(conf.isModified()));

        // 契约 §2.2 的七条警告文案必须逐字出现在面板里
        // 注释文案在 DSL 里是 DslLabel（继承 JEditorPane，不是 JLabel），下拉文案是 JComboBox 的 item，
        // 三者都要收，否则会误判成"文案缺失"。
        StringBuilder text = new StringBuilder();
        for (JComponent c : all) {
            if (c instanceof JEditorPane) {
                String s = ((JEditorPane) c).getText();
                if (s != null) {
                    text.append(s).append('\n');
                }
            }
            if (c instanceof JLabel) {
                String s = ((JLabel) c).getText();
                if (s != null) {
                    text.append(s).append('\n');
                }
            }
            if (c instanceof AbstractButton) {
                String s = ((AbstractButton) c).getText();
                if (s != null) {
                    text.append(s).append('\n');
                }
            }
            if (c instanceof JComboBox) {
                JComboBox<?> box = (JComboBox<?>) c;
                for (int i = 0; i < box.getItemCount(); i++) {
                    Object it = box.getItemAt(i);
                    if (it != null) {
                        text.append(it).append('\n');
                    }
                }
            }
        }
        // DSL 的 comment 是 HTML JEditorPane 文本：中文被转成 &#NNNNN; 数字实体，
        // 比较前必须解码，否则会把"文案写对了"误判成缺失。
        String blob = unescapeHtml(text.toString());
        System.out.println("  [调试] 解码后的文案片段（含 &#NNNNN; 解码，前 12 行）：");
        String[] dbg = blob.split("\n");
        for (int i = 0; i < Math.min(12, dbg.length); i++) {
            System.out.println("    " + i + ": " + (dbg[i].length() > 120 ? dbg[i].substring(0, 120) + "…" : dbg[i]));
        }
        String[] required = {
                "常驻刷新 = 调度线程每秒向 EDT 投递约 125 次任务，且永不停歇",
                "隐藏原生光标 = 把全局配色方案的 CARET_COLOR 改成全透明",
                "「隐藏原生光标」勾选时覆盖层就是唯一的「光标」",
                "层数越多渐变越平滑",
                "「锐度」是外扩幅度的反向说法",   // M6：文案主语从「0.6」改成「锐度」
                "只调整后缘与前缘的时长比（拉伸倍率）",
                "辉光淡出关闭时不使用这两个值",
                "弹簧拉伸（Neovide 原版效果）",
                "残影轨迹（低帧率更稳）",
                "rank0 最拖、rank3 最跟手"
        };
        int missing = 0;
        for (String r : required) {
            boolean ok = blob.contains(r);
            if (!ok) {
                missing++;
            }
            System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + "文案存在: " + r);
        }
        check("U18 契约 §2.2 的警告文案 + §2.4 的下拉文案全部出现在面板中", missing == 0,
                "缺失 " + missing + " 条");

        // ================= M2：Tools 侧改值 → 面板显示必须同源（2026-10-20）=================
        //
        // 用户 0.9.5 反馈：「tools 和主设置的总开关那一栏有些设置好像没有同步」。
        // 状态层早就有护栏（t7 的 T-01 覆盖「两次 apply 不冲掉 Tools 的值」），
        // 但【显示层】没有一条断言 —— 面板打开时若不复算，用户看到的就是旧勾选状态。
        //
        //  faithfulness：Tools 侧改值的真实路径是 setXxx() → CONFIG → syncConfigToState() → state，
        // 面板只读 state（reset 时）。这里直接写 state 与之一一对应，且不依赖 EditorFactory。
        List<JCheckBox> boxes = new ArrayList<>();
        for (JComponent c : all) {
            if (c instanceof JCheckBox) { boxes.add((JCheckBox) c); }
        }
        check("U19 复选框数量=7（性能诊断 + 诊断日志在末位，顺序=构建顺序）", boxes.size() == 7, "实际 " + boxes.size());

        String[] names = {"enabled", "hideNativeCaret", "alwaysHighFps", "overlayFadeOut", "useShadow"};

        // A：全部关掉 → reset → 一个都不许勾（显示不能残留上一轮的值）
        svc.getState().enabled = false;
        svc.getState().hideNativeCaret = false;
        svc.getState().alwaysHighFps = false;
        svc.getState().overlayFadeOut = false;
        svc.getState().useShadow = false;
        conf.reset();
        int sel = 0;
        for (JCheckBox b : boxes) { if (b.isSelected()) { sel++; } }
        check("U20 五个布尔全 false 时 reset 后勾选数=0（含性能诊断）", sel == 0, "实际勾选 " + sel);

        // B：逐个只打开一个 → reset → 【必须恰好只有一个】勾上，且就是它自己。
        //
        // 【为什么改成自动推断而不是硬编码下标】
        // 分组的视觉顺序会随「按职责重排」变化，而 boxes 是按【组件树遍历顺序】
        // 收集的，不是代码构建顺序。0.9.36 重排分组后，硬编码的
        // {"enabled","hideNativeCaret","alwaysHighFps","overlayFadeOut","useShadow"}
        // 与实际盒子顺序对不上，报了「useShadow→box4 期望 true 实际 false」。
        //
        // 断言的本意是「显示与 state 同源（reset 后每个盒子反映各自字段）」，
        // 不依赖顺序 —— 所以改成：把某一个字段设为 true，其余 false，
        // 断言「恰好 1 个盒子被勾上」。顺序变了也照样成立。
        boolean displayOk = true;
        String displayDetail = "";
        for (int i = 0; i < names.length; i++) {
            svc.getState().enabled = false;
            svc.getState().hideNativeCaret = false;
            svc.getState().alwaysHighFps = false;
            svc.getState().overlayFadeOut = false;
            svc.getState().useShadow = false;
            switch (i) {
                case 0: svc.getState().enabled = true; break;
                case 1: svc.getState().hideNativeCaret = true; break;
                case 2: svc.getState().alwaysHighFps = true; break;
                case 3: svc.getState().overlayFadeOut = true; break;
                default: svc.getState().useShadow = true; break;
            }
            conf.reset();
            int selectedCount = 0;
            int selectedAt = -1;
            for (int k = 0; k < boxes.size(); k++) {
                if (boxes.get(k).isSelected()) {
                    selectedCount++;
                    selectedAt = k;
                }
            }
            if (selectedCount != 1) {
                displayOk = false;
                displayDetail += names[i] + "→勾选数=" + selectedCount
                        + "(期望1，位置=" + selectedAt + ") ";
            }
        }
        check("U21 Tools 侧逐个改布尔后 reset，只有对应的复选框勾上（显示与 state 同源）",
                displayOk, displayDetail.isEmpty() ? "5 个字段全部正确" : displayDetail);

        // C：Tools 关掉常驻刷新 → 面板 reset 显示未勾选 → 点 apply 不得把它改回 true
        svc.getState().alwaysHighFps = true;
        conf.reset();
        svc.getState().alwaysHighFps = false;      // ← Tools「常驻刷新」取消勾选
        conf.reset();                              // ← 用户重新打开设置页
        check("U22 Tools 关掉常驻刷新后，面板复选框显示未勾选",
                !boxes.get(2).isSelected(), "实际勾选=" + boxes.get(2).isSelected());
        conf.apply();
        check("U23 面板 apply 不得把 Tools 的 alwaysHighFps=false 冲回 true",
                !svc.getState().alwaysHighFps, "state.alwaysHighFps=" + svc.getState().alwaysHighFps);

        // D：最后一个原 Tools 项 —— 残影模式。Tools 切到 AFTERIMAGE 后，面板下拉必须跟着变。
        svc.getState().trailMode = NeovideConfig.TrailMode.SPRING;
        conf.reset();
        svc.getState().trailMode = NeovideConfig.TrailMode.AFTERIMAGE;   // ← Tools「残影模式」
        conf.reset();
        Object modeItem = conf.trailModeComboForProbe().getSelectedItem();
        check("U24 Tools 切到残影模式后，面板下拉显示「残影轨迹」",
                modeItem != null && String.valueOf(modeItem).contains("残影"),
                "下拉显示=" + modeItem);

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

    private static void collect(Component c, List<JComponent> out) {
        if (c == null) {
            return;
        }
        if (c instanceof JComponent) {
            out.add((JComponent) c);
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collect(child, out);
            }
        }
    }

    /** 把 &#NNNNN; / &amp; / &lt; / &gt; / &quot; / &nbsp; 解码成字符。 */
    private static String unescapeHtml(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = s.substring(i + 1, semi);
                    String rep = null;
                    if (ent.startsWith("#x") || ent.startsWith("#X")) {
                        try {
                            rep = new String(Character.toChars(Integer.parseInt(ent.substring(2), 16)));
                        } catch (Exception ignored) {
                            // 保持原样
                        }
                    } else if (ent.startsWith("#")) {
                        try {
                            rep = new String(Character.toChars(Integer.parseInt(ent.substring(1))));
                        } catch (Exception ignored) {
                            // 保持原样
                        }
                    } else if (ent.equals("amp")) {
                        rep = "&";
                    } else if (ent.equals("lt")) {
                        rep = "<";
                    } else if (ent.equals("gt")) {
                        rep = ">";
                    } else if (ent.equals("quot")) {
                        rep = "\"";
                    } else if (ent.equals("nbsp")) {
                        rep = " ";
                    }
                    if (rep != null) {
                        out.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String hex(Color c) {
        if (c == null) {
            return "null";
        }
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * DSL 的 comment 标签会向平台要 ExperimentalUI（决定字体）。真机里它是平台自带服务，
     * 离线探针里必须补一个桩，否则 comment(...) 一建就抛
     * "Cannot find service com.intellij.ui.ExperimentalUI" —— 那是**探针环境缺失**，
     * 不是被测代码的缺陷。
     */
    private static final com.intellij.ui.ExperimentalUI EXPERIMENTAL_UI =
            new com.intellij.ui.ExperimentalUI() {
                @Override
                public void saveCurrentValueAndReapplyDefaultLaf() {
                    // 探针里没有 LaF 可保存
                }
            };

    /**
     * headless 探针用的 ActionManager 桩（collapsibleGroup 构造时要 CollapsiblePanel-toggle）。
     *
     * <p>ActionManager 是抽象类，只能手写不能 Proxy。
     */
    static final class FakeActionManager extends com.intellij.openapi.actionSystem.ActionManager {
        static final FakeActionManager INSTANCE = new FakeActionManager();
        @Override public com.intellij.openapi.actionSystem.ActionPopupMenu createActionPopupMenu(String place, com.intellij.openapi.actionSystem.ActionGroup g) { return null; }
        @Override public com.intellij.openapi.actionSystem.ActionToolbar createActionToolbar(String place, com.intellij.openapi.actionSystem.ActionGroup g, boolean h) { return null; }
        @Override public com.intellij.openapi.actionSystem.AnAction getAction(String id) { return null; }
        @Override public String getId(com.intellij.openapi.actionSystem.AnAction a) { return null; }
        @Override public void registerAction(String id, com.intellij.openapi.actionSystem.AnAction a) { }
        @Override public void registerAction(String id, com.intellij.openapi.actionSystem.AnAction a, com.intellij.openapi.extensions.PluginId p) { }
        @Override public void unregisterAction(String id) { }
        @Override public void replaceAction(String id, com.intellij.openapi.actionSystem.AnAction a) { }
        @Override public String[] getActionIds(String prefix) { return new String[0]; }
        @Override public List<String> getActionIdList(String prefix) { return new ArrayList<>(); }
        @Override public boolean isGroup(String id) { return false; }
        @Override public com.intellij.openapi.actionSystem.AnAction getActionOrStub(String id) { return null; }
        @Override public void addTimerListener(com.intellij.openapi.actionSystem.TimerListener l) { }
        @Override public void removeTimerListener(com.intellij.openapi.actionSystem.TimerListener l) { }
        @Override public com.intellij.openapi.util.ActionCallback tryToExecute(com.intellij.openapi.actionSystem.AnAction a, java.awt.event.InputEvent e, java.awt.Component c, String place, boolean now) { return null; }
        @Override public com.intellij.openapi.actionSystem.KeyboardShortcut getKeyboardShortcut(String id) { return null; }
    }

    /** SingleAlarmSharedCoroutineScopeHolder 替身（包私有类，反射建）。 */
    private static Object newAlarmHolder(Class<?> holderClass) {
        try {
            Class<?> scopeClass = Class.forName("kotlinx.coroutines.CoroutineScope");
            Object globalScope = Class.forName("kotlinx.coroutines.GlobalScope").getField("INSTANCE").get(null);
            java.lang.reflect.Constructor<?> ctor = holderClass.getConstructor(scopeClass);
            ctor.setAccessible(true);
            return ctor.newInstance(globalScope);
        } catch (Throwable t) {
            return null;
        }
    }

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
                VerifyPanel.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.ui.popup.ComponentPopupBuilder.class},
                (pr, m, a) -> m.getReturnType().isInstance(pr) ? pr : null);
    }

    private static void installFakeApp() {
        Application proxy = (Application) Proxy.newProxyInstance(
                VerifyPanel.class.getClassLoader(),
                new Class<?>[]{Application.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getService":
                            if (args != null && args.length == 1) {
                                Class<?> c = (Class<?>) args[0];
                                if (c == NeovideCursorSettings.class) {
                                    return svc;
                                }
                                if (c == PropertiesComponent.class) {
                                    return pc;
                                }
                                if (c == com.intellij.openapi.actionSystem.ActionManager.class) { return FakeActionManager.INSTANCE; }
                                if (c == com.intellij.openapi.ui.popup.JBPopupFactory.class) { return FakePopupFactory.INSTANCE; }
                                if ("SingleAlarmSharedCoroutineScopeHolder".equals(c.getSimpleName())) { return newAlarmHolder(c); }
                                if (c == com.intellij.ui.ExperimentalUI.class) {
                                    return EXPERIMENTAL_UI;
                                }
                            }
                            return null;
                        case "isDispatchThread":
                            return true;
                        case "saveSettings":
                            saves++;
                            return null;
                        case "invokeLater":
                            ((Runnable) args[0]).run();
                            return null;
                        case "toString":
                            return "FakeApp";
                        case "hashCode":
                            return 1;
                        case "equals":
                            return p == args[0];
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
