import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CaretSchemeWatcher;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.messages.SimpleMessageBusConnection;
import com.intellij.util.messages.Topic;
import kotlinx.coroutines.CoroutineScope;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 缺陷 4（切主题后原生光标复活）的离线探针（t2）。
 *
 * <p>复现路径与实测一致：勾选「隐藏原生光标」→ CARET_COLOR 被改成全透明 →
 * <b>换一个 scheme 对象</b>（等价于用户切主题）→ 新 scheme 的光标又是可见的。
 *
 * <p>三条断言：
 * <ul>
 *   <li><b>S1</b> {@code applyNativeCaretHidden()}（无参）在 savedCaretColor 非 null 时早退 →
 *       新 scheme 的 CARET_COLOR 仍是可见色 —— <b>这就是缺陷本身</b>；
 *   <li><b>S2</b> {@code applyNativeCaretHidden(true)}（force）→ 新 scheme 被改成全透明，
 *       且 {@code savedCaretColor} 更新为<b>新</b> scheme 的原色（否则恢复出来是旧主题色）；
 *   <li><b>S3</b> 走真实装配路径：{@link CaretSchemeWatcher#handleSchemeChange} 在
 *       「插件启用 + hideNativeCaret=true」时确实调用了 force 版本 → 切主题后光标仍隐藏；
 *       并且在 {@code hideNativeCaret=false} 时<b>不动</b>配色。
 * </ul>
 */
public final class ProbeSchemeChange {

    private static int pass = 0;
    private static int fail = 0;

    private static NeovideCursorSettings svc;
    private static EditorFactory factory;
    private static FakeColorsManager colors;
    private static FakeBus bus;

    /** 危险光标色（模拟 Darcula 的可见光标色），用于验证换主题后的"原色"确实变了。 */
    private static final Color OLD_SCHEME_CARET = new Color(0xA9, 0xB7, 0xC6);
    private static final Color NEW_SCHEME_CARET = new Color(0xFF, 0x00, 0x00);

    public static void main(String[] args) throws Exception {
        System.out.println("=== t2 探针：缺陷 4（切主题后原生光标复活）===");
        System.out.println();

        svc = new NeovideCursorSettings();
        installFakeApp();

        EditorColorsScheme oldScheme = newScheme(OLD_SCHEME_CARET);
        EditorColorsScheme newScheme = newScheme(NEW_SCHEME_CARET);
        colors.scheme = oldScheme;

        // ---- 起始状态：插件启用 + 勾选隐藏原生光标 ----
        svc.getState().enabled = true;
        svc.getState().hideNativeCaret = true;
        NeovideCaretManager.syncStateToConfig();
        NeovideCaretManager.clearSavedCaretColorForProbe();

        NeovideCaretManager.applyNativeCaretHidden();
        Color afterFirstHide = oldScheme.getColor(EditorColors.CARET_COLOR);
        check("S0 首次隐藏后旧 scheme 的光标被改成全透明",
                isTransparent(afterFirstHide), String.valueOf(afterFirstHide));
        check("S0b savedCaretColor 记下旧 scheme 的原色",
                OLD_SCHEME_CARET.equals(NeovideCaretManager.savedCaretColorForProbe()),
                String.valueOf(NeovideCaretManager.savedCaretColorForProbe()));

        // ===== S1：切主题（换 scheme 对象）→ 无参版本【现在也会隐藏新 scheme】=====
        //
        // 【t6 订正】本断言原来的期望是「新 scheme 仍可见（缺陷复现）」——
        // 那描述的是 t9 之前的行为：无参版本用【全局】savedCaretColor 早退，
        // 于是新对象被早退、仍是可见的。
        //
        // t6 修 t10-(R-18) 之后，早退判据改成「当前 scheme 是否在 HIDDEN_SCHEMES 里」，
        // 新对象不在表里 → 不早退 → 无参版本也会隐藏它。这【正是】期望行为：
        // 老调用点（每次 editorCreated）拿到的是新 scheme 时不该漏隐藏。
        //
        // 缺陷 4 本身的回归由 S2/S2b/S3/S4d 覆盖（force 重入 + 重新取原色 + TOPIC 端到端），
        // S1 现在改为守住「按 scheme 判定的新语义」。
        colors.scheme = newScheme;                       // ← 用户切主题
        NeovideCaretManager.applyNativeCaretHidden();    // 无参：老调用点
        Color afterPlainCall = newScheme.getColor(EditorColors.CARET_COLOR);
        check("S1 【R-18 新语义】无参版本遇到【新 scheme 对象】也会隐藏它"
                        + "（旧语义是全局早退、新对象漏隐藏）",
                isTransparent(afterPlainCall),
                "新 scheme 光标 = " + afterPlainCall
                        + "（若仍可见，说明早退还是全局判据 → 双套绘制）");
        check("S1b 同一个对象【再调一次】无参版本仍会早退（不会重复写配置）",
                NeovideCaretManager.hiddenSchemeCountForProbe() == 2,
                "记录数=" + NeovideCaretManager.hiddenSchemeCountForProbe() + "（期望 2）");

        // ===== S2：force 版本重新隐藏 + 重新取原色 =====
        NeovideCaretManager.applyNativeCaretHidden(true);
        Color afterForce = newScheme.getColor(EditorColors.CARET_COLOR);
        check("S2 force=true 后新 scheme 的光标被重新改成全透明",
                isTransparent(afterForce), String.valueOf(afterForce));
        check("S2b force=true 时 savedCaretColor 更新为【新】scheme 的原色（不是旧主题色）",
                NEW_SCHEME_CARET.equals(NeovideCaretManager.savedCaretColorForProbe()),
                "saved=" + NeovideCaretManager.savedCaretColorForProbe()
                        + "（旧色 " + OLD_SCHEME_CARET + "）");

        // ===== S3：走真实装配路径（CaretSchemeWatcher.handleSchemeChange）=====
        // 再换一个 scheme，模拟第二次切主题
        EditorColorsScheme thirdScheme = newScheme(new Color(0x00, 0xFF, 0x00));
        colors.scheme = thirdScheme;
        EditorColorsListener listener = CaretSchemeWatcher.getInstance().listenerForProbe();

        // 未 install 时 listener 为 null → 直接调 handleSchemeChange 模拟平台回调
        CaretSchemeWatcher.getInstance().handleSchemeChange(thirdScheme);
        check("S3 handleSchemeChange（插件启用 + hideNativeCaret=true）后光标被重新隐藏",
                isTransparent(thirdScheme.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(thirdScheme.getColor(EditorColors.CARET_COLOR)));

        // ===== S3b：hideNativeCaret=false 时不得动配色 =====
        EditorColorsScheme fourthScheme = newScheme(new Color(0x00, 0x00, 0xFF));
        colors.scheme = fourthScheme;
        NeovideCaretManager.clearSavedCaretColorForProbe();
        svc.getState().hideNativeCaret = false;
        NeovideCaretManager.syncStateToConfig();
        CaretSchemeWatcher.getInstance().handleSchemeChange(fourthScheme);
        Color untouched = fourthScheme.getColor(EditorColors.CARET_COLOR);
        check("S3b hideNativeCaret=false 时切主题【不】改配色（用户本来就要原生光标）",
                new Color(0x00, 0x00, 0xFF).equals(untouched), String.valueOf(untouched));

        // ===== S3c：enabled=false 时也不得动配色 =====
        svc.getState().hideNativeCaret = true;
        svc.getState().enabled = false;
        NeovideCaretManager.syncStateToConfig();
        NeovideCaretManager.clearSavedCaretColorForProbe();
        CaretSchemeWatcher.getInstance().handleSchemeChange(fourthScheme);
        check("S3c enabled=false 时切主题【不】改配色（插件已关闭不该改全局配色）",
                new Color(0x00, 0x00, 0xFF).equals(fourthScheme.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(fourthScheme.getColor(EditorColors.CARET_COLOR)));

        // ===== S4：真实装配 —— install() 必须把监听器挂到 TOPIC 上 =====
        svc.getState().enabled = true;
        svc.getState().hideNativeCaret = true;
        NeovideCaretManager.syncStateToConfig();

        CaretSchemeWatcher.getInstance().dispose();     // 清干净，验证 install 真建订阅
        boolean installed = CaretSchemeWatcher.getInstance().install();
        check("S4 install() 建立订阅（未装过时返回 true）", installed, String.valueOf(installed));
        check("S4b install() 幂等（第二次返回 false）",
                !CaretSchemeWatcher.getInstance().install(), "第二次 install");
        check("S4c 订阅的是 EditorColorsManager.TOPIC 上的 EditorColorsListener",
                bus.subscribedTopic == EditorColorsManager.TOPIC
                        && bus.subscribedListener instanceof EditorColorsListener,
                "topic=" + (bus.subscribedTopic == null ? "null" : bus.subscribedTopic.getDisplayName())
                        + " listener=" + (bus.subscribedListener == null
                                ? "null" : bus.subscribedListener.getClass().getName()));

        // ===== S4d：真正通过消息总线广播一次 scheme 变更 =====
        EditorColorsScheme fifthScheme = newScheme(new Color(0xFF, 0xAA, 0x00));
        colors.scheme = fifthScheme;
        bus.broadcast(fifthScheme);
        check("S4d 经 TOPIC 广播后新 scheme 的光标被重新隐藏（端到端）",
                isTransparent(fifthScheme.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(fifthScheme.getColor(EditorColors.CARET_COLOR)));

        // ===== S5：dispose 之后不得再改配色（防泄漏 / 防已销毁对象收回调）=====
        CaretSchemeWatcher.getInstance().dispose();
        EditorColorsScheme sixthScheme = newScheme(new Color(0x12, 0x34, 0x56));
        colors.scheme = sixthScheme;
        check("S5 dispose() 后 isInstalled()=false", !CaretSchemeWatcher.getInstance().isInstalled(),
                String.valueOf(CaretSchemeWatcher.getInstance().isInstalled()));
        CaretSchemeWatcher.getInstance().handleSchemeChange(sixthScheme);
        // handleSchemeChange 本身是纯函数，dispose 只断订阅；这里断言"断订阅后广播不再到达"
        check("S5b dispose() 后 connection/listener 都被清空",
                CaretSchemeWatcher.getInstance().listenerForProbe() == null,
                String.valueOf(CaretSchemeWatcher.getInstance().listenerForProbe()));

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

    private static boolean isTransparent(Color c) {
        return c != null && c.getAlpha() == 0;
    }

    private static EditorColorsScheme newScheme(Color caret) {
        DefaultColorsScheme s = new DefaultColorsScheme() {
            private final Map<Object, Color> values = new HashMap<>();

            @Override
            public Color getColor(com.intellij.openapi.editor.colors.ColorKey key) {
                if (EditorColors.CARET_COLOR.equals(key)) {
                    Color v = values.get(key);
                    return v != null ? v : caret;
                }
                return values.get(key);
            }

            @Override
            public void setColor(com.intellij.openapi.editor.colors.ColorKey key, Color value) {
                values.put(key, value);
            }

            @Override
            public Color getDefaultForeground() {
                return caret;
            }
        };
        return s;
    }

    // ---------------------------------------------------------------- fakes

    private static void installFakeApp() {
        bus = new FakeBus();
        factory = new FakeEditorFactory();
        colors = new FakeColorsManager();

        Application proxy = (Application) Proxy.newProxyInstance(
                ProbeSchemeChange.class.getClassLoader(),
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
                                if (c == EditorFactory.class) {
                                    return factory;
                                }
                                if (c == EditorColorsManager.class) {
                                    return colors;
                                }
                            }
                            return null;
                        case "getMessageBus":
                            return bus;
                        case "isDispatchThread":
                            return true;
                        case "saveSettings":
                            return null;
                        case "invokeLater":
                            ((Runnable) a[0]).run();
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

    /** 假消息总线：记录订阅并支持同步广播（immediateDelivery=false 的真总线走 invokeLater，等价于同步）。 */
    static final class FakeBus implements MessageBus {
        Topic<?> subscribedTopic;
        Object subscribedListener;

        @SuppressWarnings("unchecked")
        void broadcast(Object scheme) {
            if (subscribedListener instanceof EditorColorsListener) {
                ((EditorColorsListener) subscribedListener).globalSchemeChange((EditorColorsScheme) scheme);
            }
        }

        @Override public MessageBus getParent() { return null; }
        @Override public MessageBusConnection connect() { return newConn(); }
        @Override public SimpleMessageBusConnection simpleConnect() { return newConn(); }
        @Override public MessageBusConnection connect(com.intellij.openapi.Disposable d) { return newConn(); }
        @Override public SimpleMessageBusConnection connect(CoroutineScope s) { return newConn(); }
        @Override public <L> L syncPublisher(Topic<L> t) { return null; }
        @Override public <L> L syncAndPreloadPublisher(Topic<L> t) { return null; }
        @Override public void dispose() { }
        @Override public boolean isDisposed() { return false; }
        @Override public boolean hasUndeliveredEvents(Topic<?> t) { return false; }

        private MessageBusConnection newConn() {
            return (MessageBusConnection) Proxy.newProxyInstance(
                    ProbeSchemeChange.class.getClassLoader(),
                    new Class<?>[]{MessageBusConnection.class},
                    (p, m, a) -> {
                        switch (m.getName()) {
                            case "subscribe":
                                subscribedTopic = (Topic<?>) a[0];
                                subscribedListener = a[1];
                                return null;
                            case "disconnect":
                                subscribedTopic = null;
                                subscribedListener = null;
                                return null;
                            default:
                                return defaultValue(m.getReturnType());
                        }
                    });
        }
    }

    static final class FakeColorsManager extends EditorColorsManager {
        EditorColorsScheme scheme;

        @Override public EditorColorsScheme getGlobalScheme() { return scheme; }
        @Override public void addColorScheme(EditorColorsScheme s) { }
        @Override public EditorColorsScheme[] getAllSchemes() { return new EditorColorsScheme[0]; }
        @Override public void setGlobalScheme(EditorColorsScheme s) { scheme = s; }
        @Override public void setCurrentSchemeOnLafChange(EditorColorsScheme s) { scheme = s; }
        @Override public EditorColorsScheme getActiveVisibleScheme() { return scheme; }
        @Override public EditorColorsScheme getScheme(String name) { return scheme; }
        @Override public boolean isDefaultScheme(EditorColorsScheme s) { return false; }
        @Override public boolean isUseOnlyMonospacedFonts() { return true; }
        @Override public void setUseOnlyMonospacedFonts(boolean b) { }
        @Override public long getSchemeModificationCounter() { return 0L; }
    }

    static final class FakeEditorFactory extends EditorFactory {
        @Override public Document createDocument(CharSequence s) { return null; }
        @Override public Document createDocument(char[] s) { return null; }
        @Override public Document createDocument(boolean b) { return null; }
        @Override public Document createDocument(CharSequence s, boolean a, boolean b) { return null; }
        @Override public Editor createEditor(Document d) { return null; }
        @Override public Editor createViewer(Document d) { return null; }
        @Override public Editor createEditor(Document d, Project p) { return null; }
        @Override public Editor createEditor(Document d, Project p, EditorKind k) { return null; }
        @Override public Editor createEditor(Document d, Project p, FileType f, boolean b) { return null; }
        @Override public Editor createEditor(Document d, Project p, VirtualFile f, boolean b) { return null; }
        @Override public Editor createEditor(Document d, Project p, VirtualFile f, boolean b, EditorKind k) {
            return null;
        }
        @Override public Editor createViewer(Document d, Project p) { return null; }
        @Override public Editor createViewer(Document d, Project p, EditorKind k) { return null; }
        @Override public void releaseEditor(Editor e) { }
        @Override public Stream<Editor> editors(Document d, Project p) { return Stream.empty(); }
        @Override public Editor[] getAllEditors() { return new Editor[0]; }
        @Override public List<Editor> getEditorList() { return new ArrayList<>(); }
        @Override public void addEditorFactoryListener(EditorFactoryListener l) { }
        @Override public void addEditorFactoryListener(EditorFactoryListener l,
                                                       com.intellij.openapi.Disposable d) { }
        @Override public void removeEditorFactoryListener(EditorFactoryListener l) { }
        @Override public EditorEventMulticaster getEventMulticaster() { return null; }
        @Override public void refreshAllEditors() { }
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
