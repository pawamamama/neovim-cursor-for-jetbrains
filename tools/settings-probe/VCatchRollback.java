import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimatorProbe;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayModel;
import com.intellij.openapi.editor.VisualPosition;

import javax.swing.JComponent;
import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

/**
 * V-CATCH-ROLLBACK —— 慢路径中途抛出后，下一帧必须仍然重算（U-M-7，2026-10-31）。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>{@code CaretAnimator.refreshTarget()} 原来的写法是：<b>刚进慢路径正文就</b>
 * {@code forceResync = false; inlayGeometryDirty = false;}，
 * 然后在逐光标循环里先 {@code trail.markAt(...)}（记下「这个光标已经同步到这个戳」），
 * 再算目标几何。于是只要这两点之间任何一句抛出：
 *
 * <ol>
 *   <li>「下一帧必须重算」的两个请求被清掉了；</li>
 *   <li>「这个光标已到位」的戳已经标上了；</li>
 *   <li>外层 {@code catch (Throwable)} 吞掉异常并 {@code return false}。</li>
 * </ol>
 *
 * 下一帧的顶层判据（{@code !forceResync && !fontScaleChanged && !inlayChanged && isAt(...)}）
 * 于是命中快速路径，拖尾永久停在不该停的位置 —— 而这正是 BUG-006 的症状
 * （inlay 撑高一行后光标画在上一行），只是这次由一次偶发异常触发，日志里只有
 * 第一行 warn，之后连异常都不再产生。
 *
 * <h2>六条断言</h2>
 *
 * <ul>
 *   <li><b>C-1</b>：注入的异常被生产代码吞掉（不穿出 {@code refreshTarget}），本帧报告「没更新」；</li>
 *   <li><b>C-2</b>：抛出之后「重算请求」必须<b>还在</b>（旧实现在这里就已经清空了 —— 这条是主红点）；</li>
 *   <li><b>C-3</b>：此时目标几何确实还没算出来（证明现场是「戳已标、活没干」）；</li>
 *   <li><b>C-4</b>：下一帧（无异常）必须真的重算 —— 目标几何下移一个行高，且该帧报告「变了」；</li>
 *   <li><b>C-5</b>：重算成功后请求被消费（不然后续每帧都走慢路径，变成性能回归）；</li>
 *   <li><b>C-6</b>：再下一帧回到快速路径（静止时仍然零重算）。</li>
 * </ul>
 *
 * <p><b>为什么用「注入一次 markAt 之后的异常」而不是随便找个会抛的输入</b>：
 * 抛出点必须落在 {@code markAt} <b>之后</b>。落在之前的话 {@code isAt(...)} 仍为假，
 * 下一帧本来就会重算 —— 那就测不到「标志已清 + 戳已标」这个真正的组合。
 * 钩子由生产代码提供（{@link CaretAnimatorProbe#setAfterMarkHook}），探针不参与时序。
 *
 * <p><b>变异验证</b>：把清零挪回慢路径开头（原来的写法）→ C-2、C-3、C-4 必须一起变红。
 */
public final class VCatchRollback {

    private static int pass = 0;
    private static int fail = 0;

    private static final int LINE_HEIGHT = 19;
    private static final int BASE_Y = 120;
    private static final int VP_LINE = 9;
    private static final int VP_COLUMN = 4;
    private static final int CARET_OFFSET = 40;
    private static final long STAMP = 12345L;

    /** 当前 inlay 撑起的高度；0 = 没有 block inlay。 */
    private static int inlayHeight = 0;

    /** 生产代码注册到 InlayModel 上的监听器。 */
    private static InlayModel.Listener inlayListener;

    private static CaretAnimator animator;
    private static Method refreshTarget;

    public static void main(String[] args) throws Exception {
        V0Env.install();

        System.out.println("=== V-CATCH-ROLLBACK：慢路径中途抛出后，下一帧必须仍然重算（U-M-7）===");

        buildAnimator();

        // ---- 基线：没有 inlay，让拖尾就位并进入「已同步」状态 ----
        refreshTarget.invoke(animator);
        forceTrailInitialized();
        refreshTarget.invoke(animator);
        double cy0 = trailCenterY();
        System.out.println();
        System.out.println("--- 基线 --- centerY=" + cy0 + "（期望 " + (BASE_Y + LINE_HEIGHT / 2.0) + "）");

        // ---- 制造「几何变了」：inlay 撑高一行，行号/列号/文档戳全不变 ----
        inlayHeight = LINE_HEIGHT;
        fireListener("onAdded");

        // ---- 现场：给全部光标 markAt 之后抛一次 ----
        CaretAnimatorProbe.setAfterMarkHook(() -> {
            throw new IllegalStateException("VCatchRollback: 注入的慢路径中断");
        });
        boolean threwOut = false;
        boolean updated = false;
        try {
            updated = Boolean.TRUE.equals(refreshTarget.invoke(animator));
        } catch (java.lang.reflect.InvocationTargetException e) {
            threwOut = true;            // 说明异常穿出了 refreshTarget（它本该自己 catch(Throwable)）
        } finally {
            CaretAnimatorProbe.setAfterMarkHook(null);
        }

        check("C-1 注入的异常被生产代码吞掉，本帧报告「没更新」（不许穿到 EDT）",
                !threwOut && !updated, "穿出=" + threwOut + " refreshTarget=" + updated);
        check("C-2 抛出之后「下一帧必须重算」的请求必须还在（旧实现在这里已清空）",
                pendingResync(), "pendingResync=" + pendingResync());

        double cyMid = trailCenterY();
        check("C-3 此时目标几何还没算出来（现场 = 戳已标、活没干）",
                Math.abs(cyMid - cy0) < 0.5, "centerY=" + cyMid + "（基线 " + cy0 + "）");

        // ---- 下一帧（无异常）：必须真的重算 ----
        boolean retried = Boolean.TRUE.equals(refreshTarget.invoke(animator));
        double cy1 = trailCenterY();
        check("C-4 下一帧必须重算：垂直中心下移一个行高，且该帧报告「变了」",
                retried && Math.abs((cy1 - cy0) - LINE_HEIGHT) < 0.5,
                "返回=" + retried + " centerY " + cy0 + " → " + cy1
                        + "，位移 " + (cy1 - cy0) + "px（期望 " + LINE_HEIGHT + "）");
        check("C-5 重算成功后请求被消费（否则后续每帧都走慢路径 = 性能回归）",
                !pendingResync(), "pendingResync=" + pendingResync());

        boolean idle = Boolean.TRUE.equals(refreshTarget.invoke(animator));
        check("C-6 再下一帧回到快速路径（静止时仍然零重算）",
                !idle, "第三帧返回 " + idle + "（应为 false）");

        System.out.println();
        System.out.printf("=== V-CATCH-ROLLBACK 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-CATCH-ROLLBACK RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-CATCH-ROLLBACK RESULT: ALL PASS");
        // AWT EventQueue 不是守护线程：全绿时自然返回会让 JVM 挂住（与 VInlay 同一个坑）。
        System.exit(0);
    }

    // ---------------------------------------------------------------- 脚手架（与 VInlay 同构）

    private static void buildAnimator() throws Exception {
        Object primaryCaret = Proxy.newProxyInstance(
                VCatchRollback.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.editor.Caret.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getVisualPosition":
                            // block inlay 不占视觉行号 —— 行号/列始终不变
                            return new VisualPosition(VP_LINE, VP_COLUMN);
                        case "getOffset":
                            return CARET_OFFSET;
                        case "getLogicalPosition":
                            return new com.intellij.openapi.editor.LogicalPosition(VP_LINE, VP_COLUMN);
                        case "hasSelection":
                            return false;
                        case "getLeadSelectionPosition":
                            return new VisualPosition(VP_LINE, VP_COLUMN);
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
        java.util.List<Object> carets = new ArrayList<>();
        carets.add(primaryCaret);

        CaretModel model = (CaretModel) Proxy.newProxyInstance(
                VCatchRollback.class.getClassLoader(),
                new Class<?>[]{CaretModel.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getVisualPosition":
                            return new VisualPosition(VP_LINE, VP_COLUMN);
                        case "getOffset":
                            return CARET_OFFSET;
                        case "getPrimaryCaret":
                            return primaryCaret;
                        case "getAllCarets":
                            return carets;
                        case "addCaretListener":
                        case "removeCaretListener":
                            return null;
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });

        JComponent content = new JComponent() { };
        content.setSize(900, 700);

        Document doc = (Document) Proxy.newProxyInstance(
                VCatchRollback.class.getClassLoader(),
                new Class<?>[]{Document.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getLineNumber":
                            return VP_LINE;
                        case "getLineStartOffset":
                            return 0;
                        case "getLineEndOffset":
                            return 100000;
                        case "getTextLength":
                            return 100000;
                        case "getModificationStamp":
                            // inlay 不改文档，戳恒定 —— 快速路径的第三个分量骗人
                            return STAMP;
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });

        InlayModel inlayModel = (InlayModel) Proxy.newProxyInstance(
                VCatchRollback.class.getClassLoader(),
                new Class<?>[]{InlayModel.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "addListener":
                            if (args != null && args.length > 0 && args[0] instanceof InlayModel.Listener) {
                                inlayListener = (InlayModel.Listener) args[0];
                            }
                            return null;
                        case "hasBlockElements":
                            return inlayHeight > 0;
                        case "getBlockElementsInRange":
                            return new ArrayList<>();
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });

        Editor editor = (Editor) Proxy.newProxyInstance(
                VCatchRollback.class.getClassLoader(),
                new Class<?>[]{Editor.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getCaretModel":
                            return model;
                        case "visualPositionToXY":
                            return new Point(200, BASE_Y + inlayHeight);
                        case "offsetToXY":
                            return new Point(200, BASE_Y + inlayHeight);
                        case "getDocument":
                            return doc;
                        case "getSelectionModel":
                            return selectionModel();
                        case "getInlayModel":
                            return inlayModel;
                        case "getLineHeight":
                            return LINE_HEIGHT;
                        case "getContentComponent":
                            return content;
                        case "isDisposed":
                            return false;
                        case "getEditorKind":
                            return EditorKind.MAIN_EDITOR;
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
    }

    private static com.intellij.openapi.editor.SelectionModel selectionModel() {
        return (com.intellij.openapi.editor.SelectionModel) Proxy.newProxyInstance(
                VCatchRollback.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.editor.SelectionModel.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getBlockSelectionStarts":
                        case "getBlockSelectionEnds":
                            return new int[0];
                        case "hasSelection":
                            return false;
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });
    }

    /**
     * 生产里「下一帧必须重算」的两个请求是否还挂着。
     *
     * <p>读的是 {@code CaretAnimator} 自己的字段（探针不复制判据）——
     * {@code forceResync} 与 {@code inlayGeometryDirty} 任一为真即「还没干完」。
     */
    private static boolean pendingResync() throws Exception {
        return boolField("forceResync") || boolField("inlayGeometryDirty");
    }

    private static boolean boolField(String name) throws Exception {
        Field f = CaretAnimator.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(animator);
    }

    private static void forceTrailInitialized() throws Exception {
        Object trail = mainTrail();
        if (trail != null) {
            Field f = trail.getClass().getDeclaredField("initialized");
            f.setAccessible(true);
            f.setBoolean(trail, true);
        }
    }

    private static Object mainTrail() throws Exception {
        Field cf = CaretAnimator.class.getDeclaredField("cameraTrail");
        cf.setAccessible(true);
        return cf.get(animator);
    }

    /** 绘制真正用的目标几何（不是影子副本）。 */
    private static double trailCenterY() throws Exception {
        Object t = mainTrail();
        if (t == null) {
            return Double.NaN;
        }
        Field f = t.getClass().getDeclaredField("centerY");
        f.setAccessible(true);
        return f.getDouble(t);
    }

    /** 触发生产代码注册的 inlay 监听器（仿真 Inlay：ABOVE_LINE，高度 = 当前撑高量）。 */
    private static void fireListener(String which) {
        if (inlayListener == null) {
            return;                     // 生产没注册监听器时，C-4 会以「没重算」的形式报红
        }
        Inlay<?> fake = fakeInlay();
        switch (which) {
            case "onAdded":
                inlayListener.onAdded(fake);
                break;
            case "onRemoved":
                inlayListener.onRemoved(fake);
                break;
            default:
                inlayListener.onUpdated(fake);
                break;
        }
    }

    private static Inlay<?> fakeInlay() {
        return (Inlay<?>) Proxy.newProxyInstance(
                VCatchRollback.class.getClassLoader(),
                new Class<?>[]{Inlay.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getPlacement":
                            return Inlay.Placement.ABOVE_LINE;
                        case "getHeightInPixels":
                            return inlayHeight;
                        case "getOffset":
                            return CARET_OFFSET;
                        case "isValid":
                            return true;
                        case "toString":
                            return "FakeInlay(" + inlayHeight + "px)";
                        case "hashCode":
                            return 31;
                        case "equals":
                            return p == args[0];
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });
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
