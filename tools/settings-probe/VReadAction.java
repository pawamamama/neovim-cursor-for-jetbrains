import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.InlayModel;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.VisualPosition;

import javax.swing.JComponent;
import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

/**
 * V-READACTION：编辑器读取必须发生在平台 read action 内（平台 252 的硬要求）。
 *
 * <h2>它守的是什么 bug（2026-10-28，PyCharm 2025.2 / PY-252.23892.515 实测）</h2>
 *
 * <p>覆盖层光标冻结在第一行第一字符，移动与打字都不动。栈：
 * <pre>
 *   CaretAnimator.refreshTarget
 *     → CaretImpl.getOffset
 *       → EditorThreading.assertInteractionAllowed
 *         "Read access is allowed from inside read-action only"
 * </pre>
 * 同一份 jar 在 IDEA 2026.1（build 261）不触发 —— 这是<b>平台版本差异</b>。
 * 252 要求「读 Editor / Caret / Document 必须在 read action 内」，
 * 而我们的 tick 由 {@code invokeLater} 投递到 EDT：<b>在 EDT 上 ≠ 持有读锁</b>。
 *
 * <h2>探针怎么造出 252 的环境</h2>
 *
 * <p>不用真平台（离线门禁里没有），而是：
 * <ol>
 *   <li>{@code V0Env} 的 Application 代理<b>真的执行</b>
 *       {@code runReadAction(ThrowableComputable)} 并维护一个读锁深度
 *       （{@code ReadAction.compute} 在平台字节码里正是走这条路径）；</li>
 *   <li>本探针造的 Editor / CaretModel / Caret / Document / SelectionModel / InlayModel 桩，
 *       每个读取方法都先 {@link #guard(String)}：<b>不在 read action 内就
 *       {@code violations++} 并抛与平台同文的 {@code AssertionError}</b>。</li>
 * </ol>
 *
 * <p><b>为什么这是「同一行为」而不是复刻逻辑</b>（纪律四-G）：桩只做一件事 ——
 * 在读取入口检查读锁，这与 252 的 {@code assertInteractionAllowed} 判据相同；
 * 生产代码的算法一行都没有被复制进探针。
 *
 * <h2>断言</h2>
 * <ul>
 *   <li><b>R-1</b> 构造函数（含首次 refreshTarget）全程零违规；</li>
 *   <li><b>R-2</b> 构造函数确实进入过 read action（不是「碰巧没读」）；</li>
 *   <li><b>R-3</b> 慢路径帧（位置变化）零违规，<b>R-4</b> 且确实进过 read action；</li>
 *   <li><b>R-5</b> 慢路径帧仍报告「位置变了」（包装层不得改变行为）；</li>
 *   <li><b>R-6</b> 快速路径帧同样零违规、同样进过 read action
 *       （快速路径也要读 lineHeight / visualPosition / 文档戳，不能靠「不读」规避）；</li>
 *   <li><b>R-7</b> P0-5 调用次数契约不变：慢路径帧 {@code Caret.getOffset} 恰好 1 次；</li>
 *   <li><b>R-8</b> 快速路径帧不读 offset（昂贵调用只在慢路径）；</li>
 *   <li><b>R-9</b> caret 事件入口（caretAdded / caretRemoved / traceCaretEvent）零违规；</li>
 *   <li><b>R-10</b> <b>自证</b>：直接调用未被包装的实现体时，桩必须判红 ——
 *       否则前面全绿毫无意义（探针空转）。</li>
 * </ul>
 */
public final class VReadAction {

    private static int pass = 0;
    private static int fail = 0;

    // ---------------------------------------------------------------- 桩的读锁判据

    /** 不在 read action 内发生的编辑器读取次数。 */
    private static int violations = 0;
    /** 发生在 read action 内的编辑器读取次数（用于报告，证明真的读了）。 */
    private static int guardedReads = 0;

    /** 单帧调用次数统计（P0-5 契约）。 */
    private static int caretGetOffsetCalls = 0;
    private static int modelGetOffsetCalls = 0;

    /** 当前光标视觉位置：改它就能触发慢路径。 */
    private static VisualPosition vp = new VisualPosition(9, 4);
    /** 行 0 的行顶 y（桩的视觉行号 → 像素映射基准）。 */
    private static final int BASE_Y = 120;
    private static final int LINE_HEIGHT = 19;
    private static final long STAMP = 12345L;
    private static final int CARET_OFFSET = 40;

    private static CaretAnimator animator;
    private static Method refreshTarget;
    private static Method refreshTargetInReadAction;

    /**
     * 平台 252 的判据：编辑器读取必须持有读锁。
     *
     * <p>报错文本照抄 {@code EditorThreading.assertInteractionAllowed}，
     * 这样真实机上复现时栈里的字与这里完全一致，便于比对。
     */
    private static void guard(String what) {
        if (V0Env.inReadAction()) {
            guardedReads++;
            return;
        }
        violations++;
        throw new AssertionError(
                "Read access is allowed from inside read-action only [" + what + "]");
    }

    public static void main(String[] args) throws Exception {
        V0Env.install();

        System.out.println("=== V-READACTION：编辑器读取必须在 read action 内（平台 252） ===");

        // ---------------- R-1 / R-2：构造函数与首次定位 ----------------
        int callsBeforeCtor = V0Env.readActionCalls;
        String ctorError = null;
        try {
            buildAnimator();
        } catch (Throwable t) {
            // 构造期漏包 read action 时，桩会抛 AssertionError（252 同款）。
            // 不能让探针在这里直接崩掉 —— 那只能看到一个反射栈，
            // 看不出「是哪条判据红了」。接住它，交给 R-1 报成一条可读的 FAIL。
            // 反射 Constructor.newInstance 会把构造里的异常包成 InvocationTargetException，
            // 这里剥一层，让报告直接显示「AssertionError: Read access is allowed...」。
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            ctorError = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        int ctorCalls = V0Env.readActionCalls - callsBeforeCtor;

        check("R-1 构造函数（含首次 refreshTarget）全程零违规",
                animator != null && violations == 0,
                animator == null
                        ? "构造函数被桩判红：" + ctorError
                        : "违规读取=" + violations + "  guardedReads=" + guardedReads);
        if (animator == null) {
            finish();       // 后面每条断言都依赖 animator，无法继续 —— 直接出结论
            return;
        }
        check("R-2 构造函数确实进入过 read action（不是碰巧没读）",
                ctorCalls > 0,
                "readAction 调用次数=" + ctorCalls);

        // 让墨水归零，后续按帧统计
        violations = 0;
        guardedReads = 0;
        forceTrailInitialized();
        // 跑一帧让快速路径的基准就位
        refreshTarget.invoke(animator);
        violations = 0;

        // ---------------- R-3 / R-4 / R-5：慢路径帧 ----------------
        // 换一个视觉位置 → 快速路径失效 → 走完整读取
        vp = new VisualPosition(11, 6);
        int callsBeforeSlow = V0Env.readActionCalls;
        int vBeforeSlow = violations;
        caretGetOffsetCalls = 0;
        modelGetOffsetCalls = 0;
        boolean slowMoved = Boolean.TRUE.equals(refreshTarget.invoke(animator));

        check("R-3 慢路径帧（位置变化）全程零违规",
                violations == vBeforeSlow,
                "违规=" + (violations - vBeforeSlow) + "  readAction 调用="
                        + (V0Env.readActionCalls - callsBeforeSlow));
        check("R-4 慢路径帧确实进入过 read action",
                V0Env.readActionCalls > callsBeforeSlow,
                "readAction 调用次数=" + (V0Env.readActionCalls - callsBeforeSlow));
        check("R-5 慢路径帧必须报告「位置变了」（行为未被包装层破坏）",
                slowMoved,
                "refreshTarget 返回 " + slowMoved);

        // ---------------- R-7：P0-5 调用次数契约 ----------------
        check("R-7 慢路径帧 Caret.getOffset 恰好 1 次（P0-5 契约不变）",
                caretGetOffsetCalls == 1,
                "Caret.getOffset=" + caretGetOffsetCalls + " 次；CaretModel.getOffset="
                        + modelGetOffsetCalls + " 次");
        check("R-7b 慢路径帧 CaretModel.getOffset 恰好 1 次（判据与移动量共用同一读数）",
                modelGetOffsetCalls == 1,
                "CaretModel.getOffset=" + modelGetOffsetCalls + " 次");

        // ---------------- R-6 / R-8：快速路径帧 ----------------
        caretGetOffsetCalls = 0;
        modelGetOffsetCalls = 0;
        int callsBeforeFast = V0Env.readActionCalls;
        int vBeforeFast = violations;
        boolean fastMoved = Boolean.TRUE.equals(refreshTarget.invoke(animator));

        check("R-6 快速路径帧同样零违规（它也要读 lineHeight / vp / 文档戳）",
                !fastMoved && violations == vBeforeFast,
                "返回=" + fastMoved + "  违规=" + (violations - vBeforeFast));
        check("R-6b 快速路径帧也确实进入过 read action（不是靠「不读」规避）",
                V0Env.readActionCalls > callsBeforeFast,
                "readAction 调用次数=" + (V0Env.readActionCalls - callsBeforeFast));
        check("R-8 快速路径帧不读 offset（昂贵调用只在慢路径，契约不变）",
                caretGetOffsetCalls == 0 && modelGetOffsetCalls == 0,
                "Caret.getOffset=" + caretGetOffsetCalls + " 次；CaretModel.getOffset="
                        + modelGetOffsetCalls + " 次");

        // ---------------- R-9：caret 事件入口 ----------------
        // 打开诊断，让 traceCaretEvent 真的去读 vp / offset / 文档戳
        // （关闭时它第一句就早退，等于没覆盖）。
        V0Env.svc.getState().diagLogging = true;
        int vBeforeEvents = violations;
        int callsBeforeEvents = V0Env.readActionCalls;
        // CaretEvent 在本路径上不被使用（caretAdded/Removed 只读编辑器 + 记诊断），
        // 探针里没有真平台对象可传，传 null 即可。
        String eventError = null;
        try {
            animator.caretAdded(null);
            animator.caretRemoved(null);
            animator.caretPositionChanged(null);
        } catch (Throwable t) {
            // 漏包时桩会抛 AssertionError；接住它，让 R-9 报成可读的 FAIL 而不是崩栈。
            eventError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        check("R-9 caret 事件入口（caretAdded / caretRemoved / traceCaretEvent）零违规",
                eventError == null && violations == vBeforeEvents,
                eventError != null
                        ? "事件回调被桩判红：" + eventError
                        : "违规=" + (violations - vBeforeEvents) + "  readAction 调用="
                                + (V0Env.readActionCalls - callsBeforeEvents));
        V0Env.svc.getState().diagLogging = false;

        // ---------------- R-10：探针自证（桩必须抓得住） ----------------
        //
        // 直接调用 refreshTargetInReadAction —— 绕过 refreshTarget 的 read action 包装，
        // 这正是「变异测试：把包装去掉」的等价场景。桩必须判红，
        // 否则本探针永远绿，等于什么都没守。
        int vBeforeProof = violations;
        try {
            refreshTargetInReadAction.invoke(animator);
        } catch (Throwable ignored) {
            // 实现体内部 catch(Throwable) 会吞掉桩抛的 AssertionError 并返回 false；
            // 这里 catch 只是防止反射包装层把异常带出去。
        }
        check("R-10 自证：绕过 read action 包装时桩必须判红（否则本探针是空转）",
                violations > vBeforeProof,
                "绕过调用后违规=" + (violations - vBeforeProof) + "（期望 >= 1）");

        // 自证产生的违规不计入最终结论
        violations = 0;

        finish();
    }

    /** 统一出口：打印结果并给出退出码（失败时退出码 1，门禁据此判红）。 */
    private static void finish() {
        System.out.println();
        System.out.printf("=== V-READACTION 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.println("    （read action 累计调用 " + V0Env.readActionCalls
                + " 次，受保护的编辑器读取 " + guardedReads + " 次）");
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-READACTION RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-READACTION RESULT: ALL PASS");
        // 必须显式退出：AWT 的 EventQueue 线程不是守护线程（与 VInlay 同理）。
        System.exit(0);
    }

    // ---------------------------------------------------------------- 脚手架

    private static void buildAnimator() throws Exception {
        Caret primaryCaret = (Caret) Proxy.newProxyInstance(
                VReadAction.class.getClassLoader(),
                new Class<?>[]{Caret.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getVisualPosition":
                            guard("Caret.getVisualPosition");
                            return vp;
                        case "getOffset":
                            guard("Caret.getOffset");
                            caretGetOffsetCalls++;
                            return CARET_OFFSET;
                        case "getLogicalPosition":
                            guard("Caret.getLogicalPosition");
                            return new com.intellij.openapi.editor.LogicalPosition(vp.line, vp.column);
                        case "hasSelection":
                            guard("Caret.hasSelection");
                            return false;
                        case "getLeadSelectionPosition":
                            guard("Caret.getLeadSelectionPosition");
                            return vp;
                        case "getLeadSelectionOffset":
                            guard("Caret.getLeadSelectionOffset");
                            return CARET_OFFSET;
                        case "toString":
                            return "FakeCaret";
                        case "hashCode":
                            return 99;
                        case "equals":
                            return p == args[0];
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });

        java.util.List<Caret> carets = new ArrayList<>();
        carets.add(primaryCaret);

        CaretModel model = (CaretModel) Proxy.newProxyInstance(
                VReadAction.class.getClassLoader(),
                new Class<?>[]{CaretModel.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getVisualPosition":
                            guard("CaretModel.getVisualPosition");
                            return vp;
                        case "getOffset":
                            guard("CaretModel.getOffset");
                            modelGetOffsetCalls++;
                            return CARET_OFFSET;
                        case "getPrimaryCaret":
                            guard("CaretModel.getPrimaryCaret");
                            return primaryCaret;
                        case "getAllCarets":
                            guard("CaretModel.getAllCarets");
                            return carets;
                        case "addCaretListener":
                            guard("CaretModel.addCaretListener");
                            return null;
                        case "removeCaretListener":
                            guard("CaretModel.removeCaretListener");
                            return null;
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });

        JComponent content = new JComponent() { };
        content.setSize(900, 700);

        Document doc = (Document) Proxy.newProxyInstance(
                VReadAction.class.getClassLoader(),
                new Class<?>[]{Document.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getLineNumber":
                            guard("Document.getLineNumber");
                            return vp.line;
                        case "getLineStartOffset":
                            guard("Document.getLineStartOffset");
                            return 0;
                        case "getLineEndOffset":
                            guard("Document.getLineEndOffset");
                            return 100000;
                        case "getTextLength":
                            guard("Document.getTextLength");
                            return 100000;
                        case "getModificationStamp":
                            guard("Document.getModificationStamp");
                            return STAMP;
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });

        InlayModel inlayModel = (InlayModel) Proxy.newProxyInstance(
                VReadAction.class.getClassLoader(),
                new Class<?>[]{InlayModel.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "addListener":
                            guard("InlayModel.addListener");
                            return null;
                        case "hasBlockElements":
                            guard("InlayModel.hasBlockElements");
                            return false;
                        case "getBlockElementsInRange":
                            guard("InlayModel.getBlockElementsInRange");
                            return new ArrayList<>();
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });

        Editor editor = (Editor) Proxy.newProxyInstance(
                VReadAction.class.getClassLoader(),
                new Class<?>[]{Editor.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getCaretModel":
                            guard("Editor.getCaretModel");
                            return model;
                        case "visualPositionToXY":
                            // y 跟着视觉行号走：改 vp 后目标几何真的会变，
                            // 才能让这一帧成为「慢路径且 changed=true」。
                            guard("Editor.visualPositionToXY");
                            return new Point(200, BASE_Y + vp.line * LINE_HEIGHT);
                        case "offsetToXY":
                            // x 必须与 visualPositionToXY 一致：否则会命中 IME 几何判据
                            // （byOffset.x != p.x）被当成组合中间态而冻结本帧。
                            guard("Editor.offsetToXY");
                            return new Point(200, BASE_Y + vp.line * LINE_HEIGHT);
                        case "offsetToVisualPosition":
                            guard("Editor.offsetToVisualPosition");
                            return vp;
                        case "getDocument":
                            guard("Editor.getDocument");
                            return doc;
                        case "getSelectionModel":
                            guard("Editor.getSelectionModel");
                            return selectionModel();
                        case "getInlayModel":
                            guard("Editor.getInlayModel");
                            return inlayModel;
                        case "getLineHeight":
                            guard("Editor.getLineHeight");
                            return LINE_HEIGHT;
                        case "getContentComponent":
                            guard("Editor.getContentComponent");
                            return content;
                        case "getEditorKind":
                            guard("Editor.getEditorKind");
                            return EditorKind.MAIN_EDITOR;
                        case "isDisposed":
                            return false;
                        case "getProject":
                            return null;
                        case "toString":
                            return "FakeEditor";
                        case "hashCode":
                            return 7;
                        case "equals":
                            return p == args[0];
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });

        Constructor<?> ctor = CaretAnimator.class.getDeclaredConstructor(Editor.class, NeovideConfig.class);
        ctor.setAccessible(true);
        animator = (CaretAnimator) ctor.newInstance(editor, NeovideCaretManager.getConfig());

        refreshTarget = CaretAnimator.class.getDeclaredMethod("refreshTarget");
        refreshTarget.setAccessible(true);
        refreshTargetInReadAction =
                CaretAnimator.class.getDeclaredMethod("refreshTargetInReadAction");
        refreshTargetInReadAction.setAccessible(true);
    }

    private static SelectionModel selectionModel() {
        return (SelectionModel) Proxy.newProxyInstance(
                VReadAction.class.getClassLoader(),
                new Class<?>[]{SelectionModel.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getBlockSelectionStarts":
                            guard("SelectionModel.getBlockSelectionStarts");
                            return new int[0];
                        case "getBlockSelectionEnds":
                            guard("SelectionModel.getBlockSelectionEnds");
                            return new int[0];
                        case "hasSelection":
                            guard("SelectionModel.hasSelection");
                            return false;
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });
    }

    private static void forceTrailInitialized() throws Exception {
        Field cf = CaretAnimator.class.getDeclaredField("cameraTrail");
        cf.setAccessible(true);
        Object trail = cf.get(animator);
        if (trail != null) {
            Field f = trail.getClass().getDeclaredField("initialized");
            f.setAccessible(true);
            f.setBoolean(trail, true);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
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
