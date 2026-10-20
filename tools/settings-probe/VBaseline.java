import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * verifier 自建（t4）：<b>真·修复前基线</b>对照探针 —— 同一份断言在
 * <ol>
 *   <li>{@code git archive HEAD} 导出的 0.9.4 源码树（build\v-baseline）上跑一次，</li>
 *   <li>当前工作区（0.9.5）源码树上再跑一次，</li>
 * </ol>
 * 期望：基线红、当前绿。这是「修复前必红」的最强证据 ——
 * 不是对着变异副本，而是对着真实的历史版本。
 *
 * <p>只用两棵树都有的 API：组件树遍历 + {@code isModified()} + 反射调
 * {@code NeovideCaretManager.applyNativeCaretHidden()}（0.9.4 只有无参版本）。
 * <b>不引用</b> 0.9.5 才有的 {@code *ForProbe} 钩子，因此两棵树都能编译。
 */
public final class VBaseline {

    private static int pass = 0;
    private static int fail = 0;

    private static final List<JSlider> SLIDERS = new ArrayList<>();
    private static final List<JTextComponent> TEXTS = new ArrayList<>();
    private static final List<JLabel> LABELS = new ArrayList<>();
    private static final List<JCheckBox> BOXES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String tag = args.length > 0 ? args[0] : "?";
        System.out.println("=== V-BASELINE 对照探针（树 = " + tag + "）===");

        V0Env.install();
        NeovideCursorSettings svc = V0Env.svc;

        com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable conf =
                new com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable();
        JComponent panel = conf.createComponent();
        V0Env.disableValidators(panel);
        collect(panel);

        System.out.println("    组件统计：slider=" + SLIDERS.size() + " textField=" + TEXTS.size()
                + " label=" + LABELS.size() + " checkbox=" + BOXES.size());

        // ---- 缺陷 1：滑块拖动后右侧数值标签是否跟随 ----
        int followed = 0;
        int oneListener = 0;
        for (int i = 0; i < SLIDERS.size(); i++) {
            JSlider s = SLIDERS.get(i);
            if (s.getChangeListeners().length == 1) {
                oneListener++;
            }
            String before = LABELS.get(i).getText();
            s.setValue(s.getValue() == s.getMaximum() ? s.getMinimum() : s.getMaximum());
            String after = LABELS.get(i).getText();
            if (after != null && !after.equals(before)) {
                followed++;
            }
        }
        check("B-1 滑块拖动时标签跟随（0.9.4 期望 0/N；0.9.5 期望 N/N）",
                followed == SLIDERS.size() && SLIDERS.size() > 0,
                followed + "/" + SLIDERS.size() + " 跟随");
        check("B-2 每个滑块恰好 1 个自挂 ChangeListener（0.9.4 期望 0；0.9.5 期望 N）",
                oneListener == SLIDERS.size() && SLIDERS.size() > 0,
                oneListener + "/" + SLIDERS.size() + " 恰为 1");

        // ---- 缺陷 2：刻度标签条目数 ----
        int crowded = 0;
        StringBuilder detail = new StringBuilder();
        for (JSlider s : SLIDERS) {
            int n = s.getLabelTable() == null ? 0 : s.getLabelTable().size();
            if (n > 3) {
                crowded++;
            }
            detail.append(n).append("/");
        }
        check("B-3 刻度标签条目数 ≤ 3（0.9.4 期望大面积超；0.9.5 期望全 ≤3）",
                crowded == 0, "超限 " + crowded + " 个，条目数 = " + detail);

        // ---- 缺陷 3：Tools 改动被面板 apply 覆盖 ----
        NeovideCursorSettings.State st = svc.getState();
        setBool(st, "overlayFadeOut", true);
        setBool(st, "alwaysHighFps", true);
        setBool(st, "enabled", true);
        conf.reset();
        setBool(st, "overlayFadeOut", false);
        conf.apply();
        boolean kept = !getBool(svc.getState(), "overlayFadeOut");
        check("B-4 Tools 关掉的 overlayFadeOut 不被面板 apply 冲回（0.9.4 期望被冲回 true）",
                kept, "overlayFadeOut=" + getBool(svc.getState(), "overlayFadeOut"));

        // ---- V11：拖滑块后 isModified() ----
        conf.reset();
        if (!SLIDERS.isEmpty()) {
            JSlider s = SLIDERS.get(0);
            s.setValue(s.getValue() == s.getMaximum() ? s.getMinimum() : s.getMaximum());
        }
        check("B-5 拖滑块后 isModified()=true（0.9.4 现状也是 true；不得回归）",
                conf.isModified(), String.valueOf(conf.isModified()));

        // ---- 缺陷 4：切主题后无参 applyNativeCaretHidden 是否早退 ----
        svc.getState().enabled = true;
        svc.getState().hideNativeCaret = true;
        NeovideCaretManagerBridge.sync();
        NeovideCaretManagerBridge.clearSaved();
        NeovideCaretManagerBridge.applyHidden();

        java.awt.Color oldCaret = new java.awt.Color(0xA9, 0xB7, 0xC6);
        java.awt.Color newCaret = new java.awt.Color(0xFF, 0x00, 0x00);
        com.intellij.openapi.editor.colors.EditorColorsScheme oldScheme = V0Env.newScheme(oldCaret);
        com.intellij.openapi.editor.colors.EditorColorsScheme newScheme = V0Env.newScheme(newCaret);
        V0Env.colors.scheme = oldScheme;
        NeovideCaretManagerBridge.clearSaved();
        NeovideCaretManagerBridge.applyHidden();

        V0Env.colors.scheme = newScheme;                       // 用户切主题
        // 两棵树的真实路径不同，探针按「树上有没有监听器」自动选路径：
        //   0.9.4：全仓库对 EditorColorsListener / EditorColorsManager.TOPIC 的引用数是 0，
        //          切主题后没有任何回调，原生光标直接复活 → 这是缺陷 4；
        //   0.9.5：CaretSchemeWatcher 订阅 TOPIC，回调里调 force 版重新隐藏。
        // 若两棵树都只调无参 applyNativeCaretHidden，0.9.5 也会（按设计）早退，
        // 那样这条断言测的就不是「缺陷是否修好」，而是「早退语义是否保留」—— 会误报。
        String how = NeovideCaretManagerBridge.notifySchemeChange(newScheme);
        java.awt.Color after = newScheme.getColor(
                com.intellij.openapi.editor.colors.EditorColors.CARET_COLOR);
        boolean revived = after != null && after.getAlpha() > 0;
        check("B-6 切主题后原生光标【不】复活（0.9.4 期望复活 = 缺陷；0.9.5 走监听器 → 不复活）",
                !revived, "路径=" + how + "  新 scheme 光标 = " + after + "（复活=" + revived + "）");

        System.out.println();
        System.out.printf("=== V-BASELINE(%s) 结果：PASS %d，FAIL %d ===%n", tag, pass, fail);
        System.out.flush();
        System.out.println(fail > 0 ? "V-BASELINE RESULT: HAS FAILURES" : "V-BASELINE RESULT: ALL PASS");
        System.exit(fail > 0 ? 1 : 0);
    }

    // ---------------------------------------------------------------- 反射桥（两棵树都不需要新 API）

    static final class NeovideCaretManagerBridge {
        static void applyHidden() {
            try {
                Method m = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager")
                        .getMethod("applyNativeCaretHidden");
                m.invoke(null);
            } catch (Throwable t) {
                System.out.println("    [bridge] applyNativeCaretHidden 调用失败: " + t);
            }
        }

        static void sync() {
            try {
                Class.forName("com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager")
                        .getMethod("syncStateToConfig").invoke(null);
            } catch (Throwable ignored) {
                // 0.9.4 也有这个方法；失败就算了，下面读 CONFIG 不受影响
            }
        }

        /**
         * 模拟「配色方案变更」事件。
         *
         * @return 实际走的路径描述（用于区分「树上有没有监听器」）
         */
        static String notifySchemeChange(com.intellij.openapi.editor.colors.EditorColorsScheme scheme) {
            Class<?> watcher;
            try {
                watcher = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.CaretSchemeWatcher");
            } catch (Throwable t) {
                applyHidden();                       // 0.9.4：没人监听，老调用点原样
                return "0.9.4 无监听器（只有无参 applyNativeCaretHidden）";
            }
            try {
                Object inst = watcher.getMethod("getInstance").invoke(null);
                watcher.getMethod("handleSchemeChange",
                                com.intellij.openapi.editor.colors.EditorColorsScheme.class)
                        .invoke(inst, scheme);
                return "0.9.5 CaretSchemeWatcher.handleSchemeChange";
            } catch (Throwable t) {
                System.out.println("    [bridge] 监听器路径调用失败，退回无参版本: " + t);
                applyHidden();
                return "fallback 无参 applyNativeCaretHidden";
            }
        }

        static void clearSaved() {
            try {
                Class.forName("com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager")
                        .getMethod("clearSavedCaretColorForProbe").invoke(null);
            } catch (Throwable ignored) {
                // 老树没有这个探针钩子 → 用反射把字段清空
                try {
                    java.lang.reflect.Field f = Class.forName(
                                    "com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager")
                            .getDeclaredField("savedCaretColor");
                    f.setAccessible(true);
                    f.set(null, null);
                } catch (Throwable ignored2) {
                    // 放弃：本断言会因此偏保守
                }
            }
        }
    }

    // ---------------------------------------------------------------- 反射读写 State（两棵树字段名相同）

    static void setBool(NeovideCursorSettings.State s, String name, boolean v) {
        try {
            NeovideCursorSettings.State.class.getField(name).setBoolean(s, v);
        } catch (Throwable ignored) {
        }
    }

    static boolean getBool(NeovideCursorSettings.State s, String name) {
        try {
            return NeovideCursorSettings.State.class.getField(name).getBoolean(s);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 组件树

    static void collect(Component c) {
        if (c == null) {
            return;
        }
        // 面板是扁平结构：每行依次是 [标题 JLabel] [滑块] [输入框(0.9.5)] [数值标签]
        if (c instanceof JSlider) {
            SLIDERS.add((JSlider) c);
        }
        if (c instanceof JCheckBox) {
            BOXES.add((JCheckBox) c);
        }
        if (c instanceof Container) {
            Component[] kids = ((Container) c).getComponents();
            for (int i = 0; i < kids.length; i++) {
                Component k = kids[i];
                if (k instanceof JCheckBox) {
                    BOXES.add((JCheckBox) k);
                }
                if (k instanceof JSlider) {
                    SLIDERS.add((JSlider) k);
                    // 滑块之后第一个 JTextComponent 是输入框，其后第一个 JLabel 是数值标签
                    JTextComponent tf = null;
                    JLabel lb = null;
                    for (int j = i + 1; j < kids.length && j <= i + 3; j++) {
                        if (tf == null && kids[j] instanceof JTextComponent) {
                            tf = (JTextComponent) kids[j];
                        } else if (tf != null && lb == null && kids[j] instanceof JLabel) {
                            lb = (JLabel) kids[j];
                        }
                    }
                    if (tf == null) {
                        // 0.9.4 没有输入框：数值标签紧跟在滑块后面
                        for (int j = i + 1; j < kids.length && j <= i + 2; j++) {
                            if (kids[j] instanceof JLabel) {
                                lb = (JLabel) kids[j];
                                break;
                            }
                        }
                    }
                    TEXTS.add(tf);
                    LABELS.add(lb == null ? new JLabel("") : lb);
                }
            }
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + "   [" + detail + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}
