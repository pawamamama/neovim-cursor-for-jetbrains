import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.messages.SimpleMessageBusConnection;
import com.intellij.util.messages.Topic;
import kotlinx.coroutines.CoroutineScope;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * verifier 自建（t4）：离线平台桩。
 * <p>只提供探针真正会碰到的三个服务之外的最小集；所有断言都自己算，不引用实现者的任何数字。
 */
public final class V0Env {

    public static NeovideCursorSettings svc;
    public static FakeProps props;
    public static FakeColors colors;
    public static FakeBus bus;
    public static FakeEditorFactory editors;

    /**
     * 探针注入：{@code > 0} 时下一次「取 PropertiesComponent 服务」抛异常，计数递减。
     *
     * <p><b>默认 0，只有 {@code VAuditBatch1} 会设它。</b>模拟审计 L-5 的场景：
     * {@code ApplicationManager.getApplication().getService(PropertiesComponent.class)}
     * 在平台早期初始化时抛异常。
     */
    public static int throwOnPropertiesLookup;

    /**
     * 探针注入：{@code Application.isDispatchThread()} 的返回值。
     *
     * <p><b>默认 true（= 现状）。</b>生产代码里这个调用等价于
     * {@code SwingUtilities.isEventDispatchThread()}，但离线探针没有真正的 EDT，
     * 后者<b>恒为 false</b>。审计 U-M-20 要给「回调不在 EDT 时转投」补一条守门断言，
     * 必须能<b>从探针侧翻转这个判定</b>，否则「非 EDT 分支」永远走不到（假通过）；
     * 而改用 {@code assertIsDispatchThread()} 又会把「可能并发」变成「必定崩溃」。
     */
    public static volatile boolean dispatchThreadOverride = true;

    /**
     * 探针计数：{@code Application.invokeLater(Runnable)} 被调用的次数。
     *
     * <p>用来断言「非 EDT 时确实<em>转了</em>一次」，而不是只看「最终结果相同」
     * —— 后者在本桩里恒成立（桩把 invokeLater 实现成立即 run），
     * 是一条<b>恒真断言</b>（AC-008 红线）。
     */
    public static int invokeLaterCalls;

    /**
     * 探针注入：{@code true} 时 {@code invokeLater(Runnable)} 只入队、不立刻执行。
     *
     * <p><b>默认 false（= 立即 run，既有 60 个探针依赖这个语义）。</b>
     * 审计 U-M-20 要证明「非 EDT 时是<em>转发</em>而不是当场执行」——
     * 只数调用次数不够（直接执行也会 +1），必须能观察「调用返回时活还没干、队列里有 1 个」。
     */
    public static volatile boolean queueInvokeLater;

    /** {@link #queueInvokeLater} 为 true 时的待执行队列。 */
    public static final java.util.List<Runnable> INVOKE_LATER_QUEUE = new java.util.ArrayList<>();

    /** 执行并清空队列，返回执行了几个（探针用）。 */
    public static int drainInvokeLater() {
        int n;
        synchronized (INVOKE_LATER_QUEUE) {
            n = INVOKE_LATER_QUEUE.size();
            for (Runnable r : new java.util.ArrayList<>(INVOKE_LATER_QUEUE)) {
                r.run();
            }
            INVOKE_LATER_QUEUE.clear();
        }
        return n;
    }

    /** 探针注入：{@code true} 时 {@code MessageBus.connect(...)} 抛异常（模拟总线故障）。 */
    public static boolean throwOnBusConnect;

    public static final ExperimentalUIStub EXPERIMENTAL_UI = new ExperimentalUIStub();

    private V0Env() {
    }

    public static void install() {
        svc = new NeovideCursorSettings();
        props = new FakeProps();
        colors = new FakeColors();
        bus = new FakeBus();
        editors = new FakeEditorFactory();

        Application proxy = (Application) Proxy.newProxyInstance(
                V0Env.class.getClassLoader(),
                new Class<?>[]{Application.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "getService":
                            return service(a);
                        case "isDispatchThread":
                            return dispatchThreadOverride;
                        // 【平台 252】生产代码用 ReadAction.compute 包住编辑器读取。
                        // 平台字节码核实：ReadAction.compute → computeBlocking →
                        // Application.runReadAction(ThrowableComputable)。
                        // 所以这里必须**真的执行**它 —— 若沿用 defaultValue（返回 null），
                        // 修复后的生产代码在探针里一次编辑器读取都不会发生，
                        // 探针测到的就是个空壳（典型的「假通过」）。
                        case "runReadAction":
                        case "maybeRunReadAction":
                            return runReadAction(a[0]);
                        case "isReadAccessAllowed":
                            return inReadAction();
                        case "saveSettings":
                            return null;
                        case "invokeLater":
                            invokeLaterCalls++;
                            if (queueInvokeLater) {
                                synchronized (INVOKE_LATER_QUEUE) {
                                    INVOKE_LATER_QUEUE.add((Runnable) a[0]);
                                }
                            } else {
                                ((Runnable) a[0]).run();
                            }
                            return null;
                        case "getMessageBus":
                            return bus;
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

    // ---------------------------------------------------------------- read action 支持

    /**
     * 当前线程被多少个 read action 包着。
     *
     * <p>探针用它模拟平台 252 的断言：桩 Editor 在读取时检查 {@link #inReadAction()}，
     * 不在其中就抛与平台原文一致的 {@code AssertionError}。
     */
    private static final ThreadLocal<Integer> READ_DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 累计进入 read action 的次数（VReadAction 断言「生产代码确实包装过」）。 */
    public static int readActionCalls;

    /** 当前是否处于 read action 内 —— 桩 Editor 的判据，也是平台 252 的判据。 */
    public static boolean inReadAction() {
        return READ_DEPTH.get() > 0;
    }

    /**
     * 真正执行 {@code runReadAction} 的语义：加深度 → 跑 → 减深度。
     *
     * <p><b>为什么不能用 {@code defaultValue}（返回 null 而不执行）</b>：那样
     * {@code ReadAction.compute} 在探针里会直接返回 null，生产代码的读取<b>全部不发生</b>，
     * 探针测到的就是一个空壳 —— 这正是 CLAUDE.md 陷阱 4 里的「假通过」。
     *
     * @param body 平台传入的 Runnable 或 ThrowableComputable
     */
    private static Object runReadAction(Object body) {
        readActionCalls++;
        READ_DEPTH.set(READ_DEPTH.get() + 1);
        try {
            if (body instanceof ThrowableComputable) {
                try {
                    return ((ThrowableComputable<?, ?>) body).compute();
                } catch (Throwable t) {
                    throw rethrow(t);
                }
            }
            if (body instanceof Runnable) {
                ((Runnable) body).run();
            }
            return null;
        } finally {
            READ_DEPTH.set(READ_DEPTH.get() - 1);
        }
    }

    /**
     * 原样抛出 {@code Error}。
     *
     * <p>桩 Editor 抛的是 {@code AssertionError}（与平台 252 同类型）——
     * 若在这里包成 RuntimeException，被测代码的 {@code catch (Throwable)} 照样吞掉，
     * 但探针栈里就看不出「这是断言失败」，排查时会被误导。
     */
    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof Error) {
            throw (Error) t;
        }
        if (t instanceof RuntimeException) {
            return (RuntimeException) t;
        }
        return new RuntimeException(t);
    }

    private static Object service(Object[] a) {
        if (a == null || a.length != 1) {
            return null;
        }
        Class<?> c = (Class<?>) a[0];
        if (c == NeovideCursorSettings.class) {
            return svc;
        }
        if (c == PropertiesComponent.class) {
            if (throwOnPropertiesLookup > 0) {
                throwOnPropertiesLookup--;
                throw new IllegalStateException("V0Env: 注入的 PropertiesComponent 获取失败");
            }
            return props;
        }
        if (c == com.intellij.ui.ExperimentalUI.class) {
            return EXPERIMENTAL_UI;
        }
        if (c == com.intellij.openapi.actionSystem.ActionManager.class) {
            return FakeActionManager.INSTANCE;
        }
        if (c == EditorColorsManager.class) {
            return colors;
        }
        if (c == EditorFactory.class) {
            return editors;
        }
        if ("com.intellij.util.SingleAlarmSharedCoroutineScopeHolder".equals(c.getName())) {
            return scopeHolder();
        }
        return null;
    }

    /**
     * DialogPanel.registerValidators → ComponentValidator → SingleAlarm 会向平台要这个
     * （包级可见的）服务。离线探针没有平台，反射构造一个即可 —— 它只被持有，不会被触发。
     */
    private static Object scopeHolder() {
        if (SCOPE_HOLDER != null) {
            return SCOPE_HOLDER;
        }
        try {
            Class<?> holder = Class.forName("com.intellij.util.SingleAlarmSharedCoroutineScopeHolder");
            java.lang.reflect.Constructor<?> ctor =
                    holder.getConstructor(kotlinx.coroutines.CoroutineScope.class);
            ctor.setAccessible(true);
            kotlinx.coroutines.CoroutineScope scope = kotlinx.coroutines.CoroutineScopeKt.CoroutineScope(
                    kotlin.coroutines.EmptyCoroutineContext.INSTANCE);
            SCOPE_HOLDER = ctor.newInstance(scope);
        } catch (Throwable t) {
            System.out.println("[V0Env] scopeHolder 构造失败（ComponentValidator 可能不可用）: " + t);
            SCOPE_HOLDER = null;
        }
        return SCOPE_HOLDER;
    }

    private static Object SCOPE_HOLDER;

    // ------------------------------------------------------------------ stubs

    public static final class ExperimentalUIStub extends com.intellij.ui.ExperimentalUI {
        @Override public void saveCurrentValueAndReapplyDefaultLaf() { }
    }

    static final class FakeActionManager extends com.intellij.openapi.actionSystem.ActionManager {
        static final FakeActionManager INSTANCE = new FakeActionManager();

        private static final com.intellij.openapi.actionSystem.AnAction STUB =
                new com.intellij.openapi.actionSystem.AnAction() {
                    @Override public void actionPerformed(
                            @org.jetbrains.annotations.NotNull com.intellij.openapi.actionSystem.AnActionEvent e) { }
                };

        @Override public com.intellij.openapi.actionSystem.ActionPopupMenu createActionPopupMenu(
                String g, com.intellij.openapi.actionSystem.ActionGroup a) { return null; }
        @Override public com.intellij.openapi.actionSystem.ActionToolbar createActionToolbar(
                String p, com.intellij.openapi.actionSystem.ActionGroup g, boolean h) { return null; }
        @Override public com.intellij.openapi.actionSystem.AnAction getAction(String id) { return STUB; }
        @Override public String getId(com.intellij.openapi.actionSystem.AnAction a) { return "stub"; }
        @Override public void registerAction(String id, com.intellij.openapi.actionSystem.AnAction a) { }
        @Override public void registerAction(String id, com.intellij.openapi.actionSystem.AnAction a,
                                            com.intellij.openapi.extensions.PluginId p) { }
        @Override public void unregisterAction(String id) { }
        @Override public void replaceAction(String id, com.intellij.openapi.actionSystem.AnAction a) { }
        @Override public String[] getActionIds(String id) { return new String[0]; }
        @Override public List<String> getActionIdList(String id) { return new ArrayList<>(); }
        @Override public boolean isGroup(String id) { return false; }
        @Override public com.intellij.openapi.actionSystem.AnAction getActionOrStub(String id) { return STUB; }
        @Override public void addTimerListener(com.intellij.openapi.actionSystem.TimerListener l) { }
        @Override public void removeTimerListener(com.intellij.openapi.actionSystem.TimerListener l) { }
        @Override public com.intellij.openapi.util.ActionCallback tryToExecute(
                com.intellij.openapi.actionSystem.AnAction a, java.awt.event.InputEvent e,
                java.awt.Component c, String p, boolean x) { return null; }
        @Override public com.intellij.openapi.actionSystem.KeyboardShortcut getKeyboardShortcut(String id) { return null; }
    }

    public static final class FakeProps extends PropertiesComponent {

        /**
         * 探针注入：让<b>下一次</b> {@code isValueSet} 抛异常，用掉后自动复位。
         *
         * <p><b>默认 false，只有 {@code VAuditBatch1} 会打开它。</b>它模拟审计 H-3
         * 描述的场景：「升级后首次启动、平台早期初始化 / 服务竞态导致读旧键抛异常」。
         *
         * <p>为什么是「一次性」而不是「一直抛」：H-3 要证明的是<b>一次瞬时异常</b>
         * 会不会造成不可逆丢配置。若让它一直抛，后面的 {@code deleteOldKeysIfPresent}
         * 也会抛，旧键无论如何都删不掉，探针就测不出「修之前键确实被删了」这个对照。
         */
        public static boolean throwOnceOnIsValueSet;

        final Map<String, String> map = new HashMap<>();
        @Override public void unsetValue(String n) { map.remove(n); }

        @Override public boolean isValueSet(String n) {
            if (throwOnceOnIsValueSet) {
                throwOnceOnIsValueSet = false;
                throw new IllegalStateException("V0Env.FakeProps: 注入的读旧键失败");
            }
            return map.containsKey(n);
        }
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

    /** 假配色管理器：scheme 可替换，用来模拟「切主题」。 */
    public static final class FakeColors extends EditorColorsManager {
        public EditorColorsScheme scheme;

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

    /** 假消息总线：记录订阅并能同步广播（等价于 immediateDelivery=false 时的 invokeLater 投递）。 */
    public static final class FakeBus implements MessageBus {
        public Topic<?> subscribedTopic;
        public Object subscribedListener;
        public int connectCount;
        public int disconnectCount;

        @SuppressWarnings("unchecked")
        public void broadcastScheme(EditorColorsScheme scheme) {
            if (subscribedListener instanceof EditorColorsListener) {
                ((EditorColorsListener) subscribedListener).globalSchemeChange(scheme);
            }
        }

        @Override public MessageBus getParent() { return null; }
        @Override public MessageBusConnection connect() { return newConn(); }
        @Override public SimpleMessageBusConnection simpleConnect() { return newConn(); }
        @Override public MessageBusConnection connect(Disposable d) { return newConn(); }
        @Override public SimpleMessageBusConnection connect(CoroutineScope s) { return newConn(); }
        @Override public <L> L syncPublisher(Topic<L> t) { return null; }
        @Override public <L> L syncAndPreloadPublisher(Topic<L> t) { return null; }
        @Override public void dispose() { }
        @Override public boolean isDisposed() { return false; }
        @Override public boolean hasUndeliveredEvents(Topic<?> t) { return false; }

        private MessageBusConnection newConn() {
            if (throwOnBusConnect) {
                throw new IllegalStateException("V0Env.FakeBus: 注入的总线连接失败");
            }
            connectCount++;
            return (MessageBusConnection) Proxy.newProxyInstance(
                    V0Env.class.getClassLoader(),
                    new Class<?>[]{MessageBusConnection.class},
                    (p, m, a) -> {
                        switch (m.getName()) {
                            case "subscribe":
                                subscribedTopic = (Topic<?>) a[0];
                                subscribedListener = a[1];
                                return null;
                            case "disconnect":
                                disconnectCount++;
                                subscribedTopic = null;
                                subscribedListener = null;
                                return null;
                            default:
                                return defaultValue(m.getReturnType());
                        }
                    });
        }
    }

    public static final class FakeEditorFactory extends EditorFactory {
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
        @Override public void addEditorFactoryListener(EditorFactoryListener l, Disposable d) { }
        @Override public void removeEditorFactoryListener(EditorFactoryListener l) { }
        @Override public EditorEventMulticaster getEventMulticaster() { return null; }
        @Override public void refreshAllEditors() { }
    }

    /** 可控的配色方案：每个对象自带一份 color 表。 */
    public static EditorColorsScheme newScheme(java.awt.Color caret) {
        DefaultColorsScheme s = new DefaultColorsScheme() {
            private final Map<ColorKey, java.awt.Color> values = new HashMap<>();

            @Override public java.awt.Color getColor(ColorKey key) {
                java.awt.Color v = values.get(key);
                return v != null ? v : caret;
            }

            @Override public void setColor(ColorKey key, java.awt.Color value) {
                values.put(key, value);
            }

            @Override public java.awt.Color getDefaultForeground() { return caret; }
        };
        return s;
    }

    /**
     * 关掉整棵组件树上的 ComponentValidator（离线探针必需）。
     *
     * <p>DialogPanel.registerValidators 会为每个输入框装一个 ComponentValidator，它每次
     * revalidate() 都会走 {@code ComponentValidator.createPopupBuilder} → 平台的
     * JBPopupFactory —— 那是真 IDE 才有的服务。探针里没有，会 NPE。
     *
     * <p>做法不是伪造 JBPopupFactory（它有 31 个抽象方法，伪造等于自欺），而是把
     * {@code disableValidation} 置 true：平台自己的 updateInfo() 第一句就是
     * {@code if (disableValidation) return;}，这是官方支持的旁路，不是绕过被测逻辑。
     * 被测的绑定 / 吸附 / 脏字段逻辑与校验器无关。
     */
    public static void disableValidators(java.awt.Component root) {
        int n = 0;
        try {
            java.util.List<java.awt.Component> all = new java.util.ArrayList<>();
            collect(root, all);
            for (java.awt.Component c : all) {
                if (!(c instanceof javax.swing.JComponent)) {
                    continue;
                }
                java.util.Optional<com.intellij.openapi.ui.ComponentValidator> v =
                        com.intellij.openapi.ui.ComponentValidator.getInstance((javax.swing.JComponent) c);
                if (v.isPresent()) {
                    // ① disableValidation=true：updateInfo 的第一句就 return
                    java.lang.reflect.Field f =
                            com.intellij.openapi.ui.ComponentValidator.class.getDeclaredField("disableValidation");
                    f.setAccessible(true);
                    f.setBoolean(v.get(), true);
                    // ② validator=null：revalidate() 的守卫是 validator != null 才 updateInfo。
                    //    DialogPanel.reset() 会把控件文字回灌一遍，触发 document 变更 →
                    //    ComponentValidator.revalidate()。置 null 后这条路径彻底静默。
                    java.lang.reflect.Field g =
                            com.intellij.openapi.ui.ComponentValidator.class.getDeclaredField("validator");
                    g.setAccessible(true);
                    g.set(v.get(), null);
                    n++;
                }
            }
        } catch (Throwable t) {
            System.out.println("[V0Env] disableValidators 失败: " + t);
        }
        System.out.println("[V0Env] 已停用 " + n + " 个 ComponentValidator");
    }

    private static void collect(java.awt.Component c, java.util.List<java.awt.Component> out) {
        if (c == null) {
            return;
        }
        out.add(c);
        if (c instanceof java.awt.Container) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                collect(k, out);
            }
        }
    }

    public static Object defaultValue(Class<?> t) {
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
}

