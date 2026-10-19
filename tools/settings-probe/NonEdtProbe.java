import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;

import java.util.stream.Stream;

import javax.swing.JComponent;
import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 探针 D —— 非 EDT 分支专项覆盖（t5 审查 R-01 / R-02 / R-03 的回归护栏）。
 *
 * <p><b>为什么必须单独一个探针</b>：t2/t3/t4 的全部离线探针里，
 * 假 Application 的 {@code isDispatchThread()} **一律 return true**
 * （{@code ApplyConfigProbe:111} / {@code PanelProbe:421} / t4 的三个 Verify*）。
 * 于是「非 EDT」这条分支**零覆盖** —— R-02、R-03 两个 high/medium 缺陷正是从这里漏出去的。
 * 本探针的假 Application 默认 {@code isDispatchThread() == false}，
 * {@code invokeLater} 只是把任务<b>入队</b>（不内联执行），由探针显式 {@link #drainEdt()} 决定何时跑，
 * 从而能分别观察「排队前」与「EDT 执行后」两个时刻。
 *
 * <p>覆盖：
 * <ul>
 *   <li><b>D1 / R-01</b>：{@code state.enabled=false} 时首次 {@code editorCreated} 之后
 *       {@code ANIMATORS} 必须为空（同步要早于 enabled 早退）。</li>
 *   <li><b>D2 / R-02</b>：非 EDT 调 {@code applyConfig()}，EDT 执行完后
 *       setter 链必须真的跑过（{@code saveSettings ≥ 1}）——快照必须早于写 CONFIG。</li>
 *   <li><b>D3 / R-03</b>：非 EDT 迁移 —— 排队阶段不得删旧键、不得回退 stateVersion；
 *       EDT 执行且刷盘成功后旧键才被删；刷盘失败则旧键保留。</li>
 * </ul>
 *
 * 可原样复跑：{@code pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-non-edt-probe.ps1}
 */
public final class NonEdtProbe {

    private static int pass = 0;
    private static int fail = 0;

    // ---- 假平台状态 ----
    private static FakePropertiesComponent pc;
    private static NeovideCursorSettings svc;
    private static final Deque<Runnable> edtQueue = new ArrayDeque<>();
    private static int saveSettingsCalls = 0;
    private static boolean throwOnSave = false;

    // ---- 假编辑器状态 ----
    private static final StubEditorFactory EDITOR_FACTORY = new StubEditorFactory();

    public static void main(String[] args) throws Exception {
        System.out.println("=== 探针 D：非 EDT 分支专项（R-01 / R-02 / R-03）===");

        installFakePlatform();

        d1_syncBeforeEnabled();
        d2_snapshotBeforeSync();
        d3_migrationOffEdt();

        System.out.println();
        System.out.printf("=== 结果：通过 %d，失败 %d ===%n", pass, fail);
        if (fail > 0) {
            throw new AssertionError("有 " + fail + " 项未通过");
        }
        System.out.println("ALL OK");
    }

    // ==================== D1 / R-01 ====================

    /**
     * R-01：{@code if (!CONFIG.enabled) return;} 原来在 {@code syncStateToConfig()} 之前。
     *
     * <p>启动瞬间 CONFIG.enabled 是编译期默认 {@code true} → 不早退 → 同步成 {@code false}
     * → 但代码继续 install()，于是「用户关掉了插件」重启后覆盖层又回来了。
     */
    private static void d1_syncBeforeEnabled() throws Exception {
        System.out.println();
        System.out.println("--- D1 / R-01：state.enabled=false 时首次 editorCreated ---");

        resetWorld();
        // 持久化状态：用户关掉了插件
        svc.getState().enabled = false;
        svc.getState().stateVersion = 1;
        // CONFIG 仍是编译期默认（模拟 IDEA 刚启动、还没同步过）
        NeovideCaretManager.getConfig().enabled = true;
        setManagerFlag("initialScanDone", false);
        setManagerFlag("settingsSynced", false);

        Map<Object, Object> animators = animatorsMap();
        animators.clear();

        Object editor = fakeEditor("editor-1");
        NeovideCaretManager mgr = new NeovideCaretManager();
        mgr.editorCreated(editorEvent(editor));

        System.out.println("  改后： ANIMATORS.size() = " + animators.size()
                + "，CONFIG.enabled = " + NeovideCaretManager.getConfig().enabled
                + "，isEnabled() = " + NeovideCaretManager.isEnabled());
        check("R-01 修后：state.enabled=false 时 editorCreated 后 ANIMATORS 为空",
                animators.isEmpty(), "实际 size=" + animators.size());
        check("R-01 修后：CONFIG.enabled 已被同步为 false（同步早于早退）",
                !NeovideCaretManager.getConfig().enabled,
                "实际 " + NeovideCaretManager.getConfig().enabled);

        // ---- 对照：enabled=true 时仍然正常挂载（证明不是把功能整体关掉了）----
        resetWorld();
        svc.getState().enabled = true;
        svc.getState().stateVersion = 1;
        NeovideCaretManager.getConfig().enabled = true;
        setManagerFlag("initialScanDone", false);
        setManagerFlag("settingsSynced", false);
        Map<Object, Object> animators2 = animatorsMap();
        animators2.clear();

        Object editor2 = fakeEditor("editor-2");
        new NeovideCaretManager().editorCreated(editorEvent(editor2));
        System.out.println("  对照： state.enabled=true 时 ANIMATORS.size() = " + animators2.size());
        check("对照：state.enabled=true 时 editorCreated 后 ANIMATORS 有 1 个（功能未被误关）",
                animators2.size() == 1, "实际 size=" + animators2.size());
    }

    // ==================== D2 / R-02 ====================

    /**
     * R-02：非 EDT 分支原来在 invokeLater 之后立刻 {@code syncStateToConfig()}，
     * 于是 EDT 侧 S0 现拍到的是**新**值 → changed() 全 false → setter 链永不执行。
     *
     * <p>修法：S0 在任何写 CONFIG 之前拍，并作为参数传给 EDT 侧。
     * 判据：EDT 执行完之后 {@code saveSettings ≥ 1}（每个 setter 都会落盘）。
     */
    private static void d2_snapshotBeforeSync() throws Exception {
        System.out.println();
        System.out.println("--- D2 / R-02：非 EDT 调 applyConfig() ---");

        resetWorld();
        svc.getState().stateVersion = 1;
        svc.getState().enabled = true;
        svc.getState().alwaysHighFps = true;      // 持久化：开
        svc.getState().overlayFadeOut = true;
        svc.getState().hideNativeCaret = true;
        svc.getState().trailMode = NeovideConfig.TrailMode.SPRING;

        NeovideConfig cfg = NeovideCaretManager.getConfig();
        // CONFIG 是"旧"状态：两个开关都关着
        cfg.enabled = true;
        cfg.alwaysHighFps = false;
        cfg.overlayFadeOut = false;
        cfg.hideNativeCaret = true;
        cfg.trailMode = NeovideConfig.TrailMode.SPRING;

        saveSettingsCalls = 0;
        edtQueue.clear();

        System.out.println("  调用前： CONFIG.alwaysHighFps=" + cfg.alwaysHighFps
                + " overlayFadeOut=" + cfg.overlayFadeOut
                + "；state 里两者都是 true → 应触发两个 setter");
        System.out.println("  调用 applyConfig()（当前线程 isDispatchThread()=false）…");

        NeovideCaretManager.applyConfig();

        System.out.println("  排队后立刻： edtQueue.size()=" + edtQueue.size()
                + "，CONFIG.alwaysHighFps=" + cfg.alwaysHighFps
                + "（应已同步为新值），saveSettingsCalls=" + saveSettingsCalls);
        check("非 EDT 调用后 invokeLater 真的入队了", edtQueue.size() >= 1,
                "实际队列 " + edtQueue.size());
        check("非 EDT 调用后 CONFIG 立刻可见新值（读取契约不变）",
                cfg.alwaysHighFps, "实际 alwaysHighFps=" + cfg.alwaysHighFps);

        int beforeDrain = saveSettingsCalls;
        drainEdt();
        int afterDrain = saveSettingsCalls;
        System.out.println("  EDT 执行后： saveSettingsCalls 从 " + beforeDrain + " 增至 " + afterDrain);
        check("R-02 修后：EDT 执行完时 setter 链真的跑过（saveSettings ≥ 1）",
                afterDrain >= 1, "实际本次新增 " + (afterDrain - beforeDrain));
        check("R-02 修后：SETTER 链确实把 alwaysHighFps 落了盘（说明 changed() 为真）",
                afterDrain - beforeDrain >= 1,
                "新增 " + (afterDrain - beforeDrain) + " 次（alwaysHighFps / overlayFadeOut 两个 setter 都会落盘）");
    }

    // ==================== D3 / R-03 ====================

    /**
     * R-03：非 EDT 时 {@code persistQuietly()} 原来返回 false，但延迟回调里**无条件**删旧键，
     * 于是 migrateIfNeeded 说「旧键保留」、实际却删了，且 stateVersion 永远停在 0。
     *
     * <p>修法：三态 PersistResult。DEFERRED 时不删键、不回退版本号；
     * 删键只在延迟回调里 saveSettings **成功之后**发生。
     */
    private static void d3_migrationOffEdt() throws Exception {
        System.out.println();
        System.out.println("--- D3 / R-03：非 EDT 下的旧键迁移 ---");

        // ---- 情形 1：刷盘成功 ----
        resetWorld();
        pc.map.put("neovide.cursor.alwaysHighFps", "false");
        pc.map.put("neovide.cursor.overlayFadeOut", "false");
        pc.unsetCalls = 0;
        throwOnSave = false;
        edtQueue.clear();

        svc.noStateLoaded();     // 触发 migrateIfNeeded（当前线程非 EDT）

        System.out.println("  [刷盘将成功] 排队后： stateVersion=" + svc.getState().stateVersion
                + "，旧键 present=" + pc.isValueSet("neovide.cursor.alwaysHighFps")
                + "，队列=" + edtQueue.size());
        check("R-03：DEFERRED 阶段 stateVersion 前进到 1（不再回退，保证收敛）",
                svc.getState().stateVersion == 1, "实际 " + svc.getState().stateVersion);
        check("R-03：DEFERRED 阶段**不删**旧键（删键必须等确认落盘）",
                pc.isValueSet("neovide.cursor.alwaysHighFps"),
                "旧键仍在 = " + pc.isValueSet("neovide.cursor.alwaysHighFps"));
        check("R-03：旧键的值已被搬进 state（alwaysHighFps=false 保留）",
                !svc.getState().alwaysHighFps, "实际 " + svc.getState().alwaysHighFps);

        drainEdt();
        System.out.println("  [刷盘将成功] EDT 执行后： 旧键 present="
                + pc.isValueSet("neovide.cursor.alwaysHighFps") + "，" + "stateVersion="
                + svc.getState().stateVersion);
        check("R-03：EDT 刷盘成功后旧键被删除",
                !pc.isValueSet("neovide.cursor.alwaysHighFps")
                        && !pc.isValueSet("neovide.cursor.overlayFadeOut"),
                "剩余 keys=" + pc.map.keySet());
        check("R-03：收敛后 stateVersion 仍为 1", svc.getState().stateVersion == 1,
                "实际 " + svc.getState().stateVersion);

        // ---- 情形 2：刷盘失败 → 旧键必须保留 ----
        resetWorld();
        pc.map.put("neovide.cursor.alwaysHighFps", "false");
        pc.unsetCalls = 0;
        throwOnSave = true;
        edtQueue.clear();

        svc.noStateLoaded();
        drainEdt();
        System.out.println("  [刷盘失败] EDT 执行后： 旧键 present="
                + pc.isValueSet("neovide.cursor.alwaysHighFps"));
        check("R-03：EDT 刷盘失败时旧键**保留**（下次启动可重试）",
                pc.isValueSet("neovide.cursor.alwaysHighFps"),
                "旧键仍在 = " + pc.isValueSet("neovide.cursor.alwaysHighFps"));
        throwOnSave = false;
    }

    // ==================== 世界重置 / 驱动 ====================

    private static void resetWorld() throws Exception {
        pc = new FakePropertiesComponent();
        svc = new NeovideCursorSettings();
        saveSettingsCalls = 0;
        throwOnSave = false;
        edtQueue.clear();
        StubEditorFactory.EDITOR_LIST.clear();
        installFakePlatform();
        animatorsMap().clear();
        // CONFIG 回到编译期默认
        NeovideConfig fresh = new NeovideConfig();
        NeovideConfig live = NeovideCaretManager.getConfig();
        live.enabled = fresh.enabled;
        live.trailMode = fresh.trailMode;
        live.hideNativeCaret = fresh.hideNativeCaret;
        live.alwaysHighFps = fresh.alwaysHighFps;
        live.overlayFadeOut = fresh.overlayFadeOut;
    }

    private static void drainEdt() {
        while (!edtQueue.isEmpty()) {
            Runnable r = edtQueue.poll();
            try {
                r.run();
            } catch (Throwable t) {
                System.out.println("    (EDT 任务抛异常: " + t + ")");
            }
        }
    }

    private static void installFakePlatform() {
        Application app = (Application) Proxy.newProxyInstance(
                NonEdtProbe.class.getClassLoader(),
                new Class<?>[]{Application.class},
                (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "getService":
                            if (margs != null && margs.length == 1) {
                                if (margs[0] == NeovideCursorSettings.class) {
                                    return svc;
                                }
                                if (margs[0] == PropertiesComponent.class) {
                                    return pc;
                                }
                                // EditorFactory.getInstance() 就是
                                // Application.getService(EditorFactory.class)（见它的字节码），
                                // 所以桩从这里注入。
                                if (margs[0] == EditorFactory.class) {
                                    return EDITOR_FACTORY;
                                }
                            }
                            return null;
                        case "isDispatchThread":
                            // ★ 本探针的核心：非 EDT
                            return false;
                        case "invokeLater":
                            edtQueue.add((Runnable) margs[0]);
                            return null;
                        case "saveSettings":
                            saveSettingsCalls++;
                            if (throwOnSave) {
                                throw new IllegalStateException("模拟刷盘失败");
                            }
                            return null;
                        case "toString":
                            return "FakeApplicationNonEdt";
                        case "hashCode":
                            return 1;
                        case "equals":
                            return proxy == margs[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        ApplicationManager.setApplication(app, () -> { });
    }

    // ==================== 假编辑器 ====================

    private static Object fakeEditor(String tag) throws Exception {
        Object caretModel = Proxy.newProxyInstance(
                NonEdtProbe.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.editor.CaretModel.class},
                (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "getVisualPosition":
                            return new com.intellij.openapi.editor.VisualPosition(0, 0);
                        case "getOffset":
                            return 0;
                        case "getAllCarets":
                            return new ArrayList<>();
                        case "addCaretListener":
                        case "removeCaretListener":
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        JComponent content = new JComponent() { };
        content.setSize(200, 100);

        Object document = Proxy.newProxyInstance(
                NonEdtProbe.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.editor.Document.class},
                (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "getLineNumber":
                            return 0;
                        case "getLineStartOffset":
                            return 0;
                        case "getLineEndOffset":
                            return 1000;
                        case "getTextLength":
                            return 1000;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        return Proxy.newProxyInstance(
                NonEdtProbe.class.getClassLoader(),
                new Class<?>[]{Editor.class},
                (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "getCaretModel":
                            return caretModel;
                        case "visualPositionToXY":
                            return new Point(100, 50);
                        case "offsetToXY":
                            return new Point(100, 50);
                        case "getDocument":
                            return document;
                        case "getLineHeight":
                            return 32;
                        case "getContentComponent":
                            return content;
                        case "isDisposed":
                            return false;
                        case "getEditorKind":
                            return com.intellij.openapi.editor.EditorKind.MAIN_EDITOR;
                        case "getProject":
                            // 必须非 null，否则 install() 会在 :204 直接 return
                            return Proxy.newProxyInstance(
                                    NonEdtProbe.class.getClassLoader(),
                                    new Class<?>[]{com.intellij.openapi.project.Project.class},
                                    (p2, m2, a2) -> {
                                        if (m2.getName().equals("toString")) {
                                            return "FakeProject";
                                        }
                                        if (m2.getName().equals("hashCode")) {
                                            return 42;
                                        }
                                        if (m2.getName().equals("equals")) {
                                            return p2 == a2[0];
                                        }
                                        return defaultValue(m2.getReturnType());
                                    });
                        case "toString":
                            return "FakeEditor(" + tag + ")";
                        case "hashCode":
                            return tag.hashCode();
                        case "equals":
                            return proxy == margs[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    /**
     * 造一个 {@code EditorFactoryEvent}。
     *
     * <p><b>为什么用 new 而不是 Proxy</b>：{@code EditorFactoryEvent} 是 <b>final 类</b>，
     * {@code Proxy.newProxyInstance} 只接受接口。它的公开构造器要求真实 {@code EditorFactory}
     * （离线环境里没有），所以这里用「分配而不执行构造器 + 反射写 final 字段」的标准手法。
     * 被测代码只调 {@code event.getEditor()}，别的字段用不到。
     */
    private static EditorFactoryEvent editorEvent(Object editor) throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        EditorFactoryEvent ev = (EditorFactoryEvent) unsafe.allocateInstance(EditorFactoryEvent.class);

        // EventObject.source（protected transient）：不走反射也没关系 ——
        // 被测的 editorCreated 只调 event.getEditor()，getSource() 从未被用到。
        // 若这里反射写 source，JDK 17+ 会因 java.base 未 opens java.util 而拒绝，反而多一个坑。

        // myEditor（private final）
        Field editorField = EditorFactoryEvent.class.getDeclaredField("myEditor");
        editorField.setAccessible(true);
        Object base = unsafe.objectFieldOffset(editorField);
        unsafe.putObject(ev, (long) base, editor);

        return ev;
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    // ==================== 反射工具 ====================

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> animatorsMap() throws Exception {
        Field f = NeovideCaretManager.class.getDeclaredField("ANIMATORS");
        f.setAccessible(true);
        return (Map<Object, Object>) f.get(null);
    }

    private static void setManagerFlag(String name, boolean value) throws Exception {
        Field f = NeovideCaretManager.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setBoolean(null, value);
    }


    /**
     * 极简 {@link EditorFactory} 桩：只实现 {@code getAllEditors()}（被测代码唯一用到的方法）。
     *
     * <p>为什么需要它：{@code editorCreated} 的首次分支里会调
     * {@code EditorFactory.getInstance().getAllEditors()} 补建已打开的编辑器；
     * 离线环境没有 IDE 实例，{@code getInstance()} 返回 null → NPE。
     * {@link EditorFactory} 是<b>抽象类</b>而非接口，所以只能继承，不能 Proxy。
     * 其余抽象方法一律抛 {@code UnsupportedOperationException}（被测路径不会碰）。
     */
    static final class StubEditorFactory extends EditorFactory {
        static final List<Object> EDITOR_LIST = new ArrayList<>();

        @Override public Editor[] getAllEditors() {
            return EDITOR_LIST.toArray(new Editor[0]);
        }

        @Override public Document createDocument(CharSequence text) { throw new UnsupportedOperationException(); }
        @Override public Document createDocument(char[] text) { throw new UnsupportedOperationException(); }
        @Override public Document createDocument(boolean forEditing) { throw new UnsupportedOperationException(); }
        @Override public Document createDocument(CharSequence text, boolean forEditing, boolean threaded) { throw new UnsupportedOperationException(); }
        @Override public Editor createEditor(Document document) { throw new UnsupportedOperationException(); }
        @Override public Editor createViewer(Document document) { throw new UnsupportedOperationException(); }
        @Override public Editor createEditor(Document document, com.intellij.openapi.project.Project project) { throw new UnsupportedOperationException(); }
        @Override public Editor createEditor(Document document, com.intellij.openapi.project.Project project, com.intellij.openapi.editor.EditorKind kind) { throw new UnsupportedOperationException(); }
        @Override public Editor createEditor(Document document, com.intellij.openapi.project.Project project, com.intellij.openapi.fileTypes.FileType fileType, boolean isViewer) { throw new UnsupportedOperationException(); }
        @Override public Editor createEditor(Document document, com.intellij.openapi.project.Project project, com.intellij.openapi.vfs.VirtualFile file, boolean isViewer) { throw new UnsupportedOperationException(); }
        @Override public Editor createEditor(Document document, com.intellij.openapi.project.Project project, com.intellij.openapi.vfs.VirtualFile file, boolean isViewer, com.intellij.openapi.editor.EditorKind kind) { throw new UnsupportedOperationException(); }
        @Override public Editor createViewer(Document document, com.intellij.openapi.project.Project project) { throw new UnsupportedOperationException(); }
        @Override public Editor createViewer(Document document, com.intellij.openapi.project.Project project, com.intellij.openapi.editor.EditorKind kind) { throw new UnsupportedOperationException(); }
        @Override public void releaseEditor(Editor editor) { throw new UnsupportedOperationException(); }
        @Override public java.util.stream.Stream<Editor> editors(Document document, com.intellij.openapi.project.Project project) { return java.util.stream.Stream.empty(); }
        @Override public java.util.List<Editor> getEditorList() { return new ArrayList<>(); }
        @Override public void addEditorFactoryListener(com.intellij.openapi.editor.event.EditorFactoryListener listener) { }
        @Override public void addEditorFactoryListener(com.intellij.openapi.editor.event.EditorFactoryListener listener, com.intellij.openapi.Disposable parentDisposable) { }
        @Override public void removeEditorFactoryListener(com.intellij.openapi.editor.event.EditorFactoryListener listener) { }
        @Override public com.intellij.openapi.editor.event.EditorEventMulticaster getEventMulticaster() { throw new UnsupportedOperationException(); }
        @Override public void refreshAllEditors() { }
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
        return null;
    }

    // ==================== 假 PropertiesComponent ====================

    static final class FakePropertiesComponent extends PropertiesComponent {
        final Map<String, String> map = new HashMap<>();
        int unsetCalls = 0;

        @Override public void unsetValue(String name) { map.remove(name); unsetCalls++; }
        @Override public boolean isValueSet(String name) { return map.containsKey(name); }
        @Override public String getValue(String name) { return map.get(name); }
        @Override public void setValue(String name, String value) { map.put(name, value); }
        @Override public void setValue(String name, String value, String defaultValue) { map.put(name, value); }
        @Override public void setValue(String name, float value, float defaultValue) { map.put(name, String.valueOf(value)); }
        @Override public void setValue(String name, int value, int defaultValue) { map.put(name, String.valueOf(value)); }
        @Override public void setValue(String name, boolean value, boolean defaultValue) { map.put(name, String.valueOf(value)); }
        @Override public String[] getValues(String name) { return new String[0]; }
        @Override public void setValues(String name, String[] values) { }
        @Override public List<String> getList(String name) { return new ArrayList<>(); }
        @Override public void setList(String name, java.util.Collection<String> values) { }
        @Override public boolean updateValue(String name, boolean value) { map.put(name, String.valueOf(value)); return true; }
        @Override public int getInt(String name, int defaultValue) { return defaultValue; }
        @Override public long getLong(String name, long defaultValue) { return defaultValue; }
        @Override public float getFloat(String name, float defaultValue) { return defaultValue; }
    }

    // ==================== 断言 ====================

    private static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "   " + detail));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   " + detail);
        }
    }
}
