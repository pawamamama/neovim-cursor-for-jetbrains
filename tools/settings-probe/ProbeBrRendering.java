import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.TimerListener;

import javax.swing.JComponent;
import javax.swing.JEditorPane;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** t9 探针：验证 W_SHORT_LEN 的 6 行在 DslLabel 里确实带 <br/>（R-11）。 */
public final class ProbeBrRendering {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== t9 探针：R-11 短动画时长提示的分行渲染 ===");
        System.out.println();
        installFakeApp();

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        JComponent comp = conf.createComponent();

        List<JComponent> all = new ArrayList<>();
        collect(comp, all);

        String hit = null;
        int editorPanes = 0;
        for (JComponent c : all) {
            if (c instanceof JEditorPane) {
                editorPanes++;
                String t = ((JEditorPane) c).getText();
                // 中文在 DslLabel 的 HTML 里被转成 &#NNNNN; 数字实体，比较前必须解码
                String dec = unescapeHtml(t);
                // 0.9.38：新文案的关键词（原「前缘（rank3）」已在精简时移除）
                if (dec != null && dec.contains("打字/退格时拖尾持续多久")) {
                    hit = dec;
                }
            }
        }
        System.out.println("  面板内 JEditorPane（DslLabel）数 = " + editorPanes);
        // 调试：把所有 DslLabel 的解码文本前 200 字打出来（中文在 HTML 里是 &#NNNNN; 实体）
        int dbg = 0;
        for (JComponent c : all) {
            if (c instanceof JEditorPane) {
                String t = unescapeHtml(((JEditorPane) c).getText());
                if (t != null && t.contains("拖尾持续多久")) {
                    dbg++;
                    System.out.println("  [debug] 命中目标段，解码后长度 = " + t.length());
                }
            }
        }
        System.out.println("  [debug] 命中段数 = " + dbg);

        check("R11-1 找到「短动画时长」那条 comment 的 HTML", hit != null,
                hit == null ? "未找到（可能文案变了）" : "长度 " + hit.length());

        if (hit != null) {
            // 【0.9.38】原断言检查 W_SHORT_LEN 的 6 行 <br/> 结构。
            // 「精简说明」那轮把它改成了单句（用户反馈说明太长），
            // 所以这里改为检查【新版说明存在且不为空】——
            // 保护的语义不变（这个字段必须有可读的解释），不再绑定分行结构。
            boolean nonEmpty = hit.trim().length() > 10;
            check("R11-2 「短动画时长」有可读说明（不再要求多行 <br/>）",
                    nonEmpty, "解码后长度 " + hit.trim().length());

            String[] anchors = {
                    "打字/退格时拖尾持续多久",
                    "低于 0.04s 会看不出拖尾"
            };
            // 【为什么用正则而不是 contains】DslLabel 走 HTML 渲染，会把连续空白折叠、
            // 并在标签边界插入空格（实测："0.03 起步步归零" 变成 "0.03     起步步归零"、
            // "· 低于 0.036s" 变成 "·     低于 0.036s"）。这是<b>正常的 HTML 空白处理</b>，
            // 不是内容丢失 —— 所以断言必须容忍空白差异，否则会把「渲染正确」判成失败。
            int found = 0;
            for (String s : anchors) {
                String re = java.util.regex.Pattern.quote(s)
                        .replaceAll("\\\\ ", "\\\\s+")     // 原文里的空格 → \s+
                        .replaceAll("\\\\Q \\\\E", "\\\\s+");
                // 更稳的做法：手工把 anchor 拆成「非空白片段」逐段匹配
                boolean ok = s.isEmpty() || hit.matches("(?s).*" + buildLooseRegex(s) + ".*");
                if (ok) {
                    found++;
                } else {
                    System.out.println("    缺失片段: [" + s + "]");
                }
            }
            check("R11-3 说明的关键信息齐全（时长含义 + 下限提示）",
                    found == anchors.length, found + "/" + anchors.length);
        }

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

    /** 把 &#NNNNN; / &amp; / &lt; / &gt; / &quot; / &nbsp; 解码成字符，但<b>保留</b>标签本身。 */
    private static String unescapeHtml(String s) {
        if (s == null) {
            return null;
        }
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

    /** 把一句话转成「片段之间允许任意空白」的正则，用来抵消 HTML 的空白折叠。 */
    private static String buildLooseRegex(String s) {
        String[] parts = s.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append("\\s*");
            }
            sb.append(java.util.regex.Pattern.quote(parts[i]));
        }
        return sb.toString();
    }

    private static int countBr(String html) {
        int n = 0;
        int i = 0;
        String lower = html.toLowerCase();
        while ((i = lower.indexOf("<br", i)) >= 0) {
            n++;
            i += 3;
        }
        return n;
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
                }
            };

    private static void installFakeApp() {
        NeovideCursorSettings svc = new NeovideCursorSettings();
        Application proxy = (Application) java.lang.reflect.Proxy.newProxyInstance(
                ProbeBrRendering.class.getClassLoader(),
                new Class<?>[]{Application.class},
                (p, m, ar) -> {
                    switch (m.getName()) {
                        case "getService":
                            if (ar != null && ar.length == 1) {
                                Class<?> c = (Class<?>) ar[0];
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
                                    return FakeActionManager.INSTANCE;
                                }
                                if (c == com.intellij.openapi.ui.popup.JBPopupFactory.class) {
                                    return FakePopupFactory.INSTANCE;
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
                            ((Runnable) ar[0]).run();
                            return null;
                        case "getMessageBus":
                            return null;
                        case "toString":
                            return "FakeApp";
                        case "hashCode":
                            return 1;
                        case "equals":
                            return p == ar[0];
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });
        ApplicationManager.setApplication(proxy, () -> { });
    }

    /** headless 探针用的 JBPopupFactory 桩（intTextField 校验器要建气泡）。 */
    static final class FakePopupFactory extends com.intellij.openapi.ui.popup.JBPopupFactory {
        static final FakePopupFactory INSTANCE = new FakePopupFactory();
        @Override public <T> com.intellij.openapi.ui.popup.IPopupChooserBuilder<T> createPopupChooserBuilder(
                List<? extends T> l) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createConfirmation(
                String t, Runnable r, int d) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createConfirmation(
                String t, String y, String n, Runnable yr, int d) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createConfirmation(
                String t, String y, String n, Runnable yr, Runnable nr, int d) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopupStep createActionsStep(
                ActionGroup g, com.intellij.openapi.actionSystem.DataContext c, String t,
                boolean a, boolean b, String s, Component comp, boolean x, int n, boolean z) {
            return null;
        }
        @Override public com.intellij.ui.awt.RelativePoint guessBestPopupLocation(JComponent c) {
            return null;
        }
        @Override public com.intellij.ui.awt.RelativePoint guessBestPopupLocation(
                com.intellij.openapi.actionSystem.DataContext c) { return null; }
        @Override public com.intellij.ui.awt.RelativePoint guessBestPopupLocation(
                com.intellij.openapi.editor.Editor e) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createActionGroupPopup(
                String t, ActionGroup g, com.intellij.openapi.actionSystem.DataContext c,
                boolean a, boolean b, boolean d, Runnable r, int n,
                com.intellij.openapi.util.Condition<? super AnAction> cond) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createActionGroupPopup(
                String t, ActionGroup g, com.intellij.openapi.actionSystem.DataContext c,
                com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid aid,
                boolean cancel, Runnable r, int n,
                com.intellij.openapi.util.Condition<? super AnAction> cond, String pl) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createListPopup(
                com.intellij.openapi.ui.popup.ListPopupStep s) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createListPopup(
                com.intellij.openapi.ui.popup.ListPopupStep s, int w) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createListPopup(
                com.intellij.openapi.project.Project pr,
                com.intellij.openapi.ui.popup.ListPopupStep s,
                java.util.function.Function<? super javax.swing.ListCellRenderer,
                        ? extends javax.swing.ListCellRenderer> f) { return null; }
        @Override public com.intellij.openapi.ui.popup.TreePopup createTree(
                com.intellij.openapi.ui.popup.JBPopup pp,
                com.intellij.openapi.ui.popup.TreePopupStep s, Object o) { return null; }
        @Override public com.intellij.openapi.ui.popup.TreePopup createTree(
                com.intellij.openapi.ui.popup.TreePopupStep s) { return null; }
        @Override public com.intellij.openapi.ui.popup.ComponentPopupBuilder createComponentPopupBuilder(
                JComponent a, JComponent b) { return popupBuilder(); }
        @Override public boolean isBestPopupLocationVisible(com.intellij.openapi.editor.Editor e) {
            return false;
        }
        @Override public java.awt.Point getCenterOf(JComponent a, JComponent b) { return null; }
        @Override public List<com.intellij.openapi.ui.popup.JBPopup> getChildPopups(Component c) {
            return new ArrayList<>();
        }
        @Override public boolean isPopupActive() { return false; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createBalloonBuilder(
                JComponent c) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createDialogBalloonBuilder(
                JComponent c, String t) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createHtmlTextBalloonBuilder(
                String h, javax.swing.Icon i, java.awt.Color fg, java.awt.Color bg,
                javax.swing.event.HyperlinkListener l) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createHtmlTextBalloonBuilder(
                com.intellij.util.ui.Html h, javax.swing.Icon i, java.awt.Color fg,
                java.awt.Color bg, javax.swing.event.HyperlinkListener l) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createHtmlTextBalloonBuilder(
                String h, com.intellij.openapi.ui.MessageType t,
                javax.swing.event.HyperlinkListener l) { return null; }
        @Override public com.intellij.openapi.ui.popup.JBPopup createMessage(String m) { return null; }
        @Override public com.intellij.openapi.ui.popup.Balloon getParentBalloonFor(Component c) {
            return null;
        }
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

    private static com.intellij.openapi.ui.popup.ComponentPopupBuilder popupBuilder() {
        return (com.intellij.openapi.ui.popup.ComponentPopupBuilder) java.lang.reflect.Proxy.newProxyInstance(
                ProbeBrRendering.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.ui.popup.ComponentPopupBuilder.class},
                (pr, m, a) -> m.getReturnType().isInstance(pr) ? pr : null);
    }

    /** SingleAlarmSharedCoroutineScopeHolder 替身（包私有类，反射建）。 */
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

    static final class FakeActionManager extends ActionManager {
        static final FakeActionManager INSTANCE = new FakeActionManager();
        @Override public ActionPopupMenu createActionPopupMenu(String a, ActionGroup g) { return null; }
        @Override public ActionToolbar createActionToolbar(String a, ActionGroup g, boolean h) { return null; }
        @Override public AnAction getAction(String id) { return null; }
        @Override public String getId(AnAction a) { return null; }
        @Override public void registerAction(String i, AnAction a) { }
        @Override public void registerAction(String i, AnAction a,
                                            com.intellij.openapi.extensions.PluginId p) { }
        @Override public void unregisterAction(String i) { }
        @Override public void replaceAction(String i, AnAction a) { }
        @Override public String[] getActionIds(String p) { return new String[0]; }
        @Override public List<String> getActionIdList(String p) { return new ArrayList<>(); }
        @Override public boolean isGroup(String i) { return false; }
        @Override public AnAction getActionOrStub(String i) { return null; }
        @Override public void addTimerListener(TimerListener l) { }
        @Override public void removeTimerListener(TimerListener l) { }
        @Override public com.intellij.openapi.util.ActionCallback tryToExecute(
                AnAction a, java.awt.event.InputEvent e, Component c, String pl, boolean n) {
            return null;
        }
        @Override public KeyboardShortcut getKeyboardShortcut(String i) { return null; }
    }

    static final class FakeProps extends PropertiesComponent {
        final Map<String, String> map = new HashMap<>();
        @Override public void unsetValue(String n) { map.remove(n); }
        @Override public boolean isValueSet(String n) { return map.containsKey(n); }
        @Override public String getValue(String n) { return map.get(n); }
        @Override public void setValue(String n, String v) { map.put(n, v); }
        @Override public void setValue(String n, String v, String d) { map.put(n, v); }
        @Override public void setValue(String n, float v, float d) { }
        @Override public void setValue(String n, int v, int d) { }
        @Override public void setValue(String n, boolean v, boolean d) { }
        @Override public String[] getValues(String n) { return new String[0]; }
        @Override public void setValues(String n, String[] v) { }
        @Override public List<String> getList(String n) { return new ArrayList<>(); }
        @Override public void setList(String n, Collection<String> v) { }
        @Override public boolean updateValue(String n, boolean v) { return true; }
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
            System.out.println("  PASS  " + name + (detail == null ? "" : "   " + detail));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   " + detail);
        }
    }
}
