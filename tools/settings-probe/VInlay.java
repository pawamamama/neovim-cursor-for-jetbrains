import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;
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
 * V-INLAY：编辑器上方插入 block inlay（如 IDEA 自动显示的 {@code @Contract(pure = true)}）
 * 后，拖尾必须重新贴合到新的像素位置 —— 哪怕行号 / 列号 / 文档戳一个都没变。
 *
 * <p><b>它守的是什么 bug（BUG-006，用户 2026-10-28 报）</b>：写方法时 IDEA 在方法签名
 * 上方自动显示一行灰色的 {@code @Contract(pure = true)}（只参与渲染、不是文件内容）。
 * 该行把下方所有文本在屏幕上撑低一个行高，而我们的覆盖层停在了旧位置 ——
 * 表现为「光标跳到上一行 / 该在 21 行却画在 20 行」。
 *
 * <p><b>为什么快速路径抓不到它（用平台字节码核实过，不是推测）</b>：IDEA 261 的
 * {@code EditorCoordinateMapper} 里，
 * <ul>
 *   <li>{@code logToVisWithInlays()} 只加 {@code getAfterLineEndElementsForLogicalLine().size()}
 *       —— <b>block inlay（ABOVE_LINE / BELOW_LINE）不改变 {@code VisualPosition.line}</b>；</li>
 *   <li>{@code visualLineToYRange()} 却加 {@code getHeightOfBlockElementsBeforeVisualLine()}，
 *       而它的 lambda 只累加 {@code myShowAbove} 那些 inlay 的 {@code getHeightInPixels()}
 *       —— <b>y 会跟着 inlay 高度变</b>。</li>
 * </ul>
 * 也就是说这是个纯粹的「行号没变、像素变了」的场景，而 {@code SpringTrail.isAt(line, column, stamp)}
 * 这三个分量恰好全都不变 → 判定「光标没动」→ 早退 → 覆盖层冻在旧 y。
 *
 * <p><b>本探针的量</b>：
 * <ul>
 *   <li>I-0 生产代码必须订阅 {@code InlayModel} 的变化（否则下面两条根本不可能成立）；</li>
 *   <li>I-1 inlay 撑高 1 行后，垂直中心必须下移 1 个行高（这里取 19px）——
 *       <b>错位量就是 inlay 高度</b>，不是 2 行、也不是只错行号；</li>
 *   <li>I-2 inlay 移除后必须回到原位（双向，避免只处理「添加」）；</li>
 *   <li>I-3 没有任何 inlay 事件时快速路径仍要生效（修复不得退化成每帧重算）。</li>
 * </ul>
 *
 * <p><b>为什么读 {@code centerY} 而不是内部标志</b>（纪律四-G）：{@code SpringTrail.centerY}
 * 是绘制真正用的那份目标几何，拦不拦截、有没有重算，最终都要落到它上面。
 */
public final class VInlay {

    private static int pass = 0;
    private static int fail = 0;

    /** 行高固定 19px（IDEA 默认字号下的典型值），inlay 高度也取同一个值 = 恰好 1 行。 */
    private static final int LINE_HEIGHT = 19;
    /** 行顶 y。inlay 出现后整体下移 LINE_HEIGHT。 */
    private static final int BASE_Y = 120;
    private static final int VP_LINE = 9;
    private static final int VP_COLUMN = 4;
    private static final int CARET_OFFSET = 40;
    private static final long STAMP = 12345L;

    /** 当前 inlay 撑起的高度；0 = 没有 block inlay。 */
    private static int inlayHeight = 0;

    /** 生产代码注册到 InlayModel 上的监听器（没注册就是 null）。 */
    private static InlayModel.Listener inlayListener;

    private static CaretAnimator animator;
    private static Method refreshTarget;

    public static void main(String[] args) throws Exception {
        V0Env.install();

        System.out.println("=== V-INLAY：block inlay 撑高一行后拖尾必须重新落位 ===");

        buildAnimator();

        // 首帧：没有 inlay，让拖尾就位；再跑一帧让缓存进入「已同步」状态
        refreshTarget.invoke(animator);
        forceTrailInitialized();
        refreshTarget.invoke(animator);
        double cy0 = trailCenterY();
        System.out.println();
        System.out.println("--- 基线（无 inlay）--- centerY=" + cy0 + "（期望 " + (BASE_Y + LINE_HEIGHT / 2.0) + "）");

        check("I-0 生产代码必须订阅 InlayModel 的变化（否则下面的断言不可能成立）",
                inlayListener != null,
                inlayListener == null ? "没有注册任何 InlayModel.Listener" : "已注册 " + inlayListener.getClass().getName());

        // 无事件时快速路径必须仍生效 —— 放在改动几何之前测，证明基线是「不重算」
        boolean idleFrame = Boolean.TRUE.equals(refreshTarget.invoke(animator));
        check("I-3 没有 inlay 事件时快速路径仍然生效（修复不得退化成每帧重算）",
                !idleFrame,
                "两帧之间什么都没变，第二帧返回 " + idleFrame + "（应为 false）");

        // —— 关键场景：inlay 出现，行号/列号/文档戳全都不变，只有 y 变 ——
        inlayHeight = LINE_HEIGHT;
        fireListener("onAdded");
        boolean moved = Boolean.TRUE.equals(refreshTarget.invoke(animator));
        double cy1 = trailCenterY();

        check("I-1 inlay 撑高 1 行后，垂直中心必须下移 1 个行高（" + LINE_HEIGHT + "px）",
                Math.abs((cy1 - cy0) - LINE_HEIGHT) < 0.5,
                "centerY " + cy0 + " → " + cy1 + "，位移 " + (cy1 - cy0) + "px（期望 " + LINE_HEIGHT + "）");
        check("I-1b 该帧必须报告「位置变了」（否则调度会按静止降频，动画起不来）",
                moved,
                "refreshTarget 返回 " + moved);

        // —— 反向：inlay 被移除（折叠 / 关闭提示）后必须回到原位 ——
        inlayHeight = 0;
        fireListener("onRemoved");
        refreshTarget.invoke(animator);
        double cy2 = trailCenterY();
        check("I-2 inlay 移除后必须回到原位置（双向，不只处理添加）",
                Math.abs(cy2 - cy0) < 0.5,
                "centerY " + cy1 + " → " + cy2 + "（期望回到 " + cy0 + "）");

        System.out.println();
        System.out.printf("=== V-INLAY 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-INLAY RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-INLAY RESULT: ALL PASS");
        // 必须显式退出：AWT 的 EventQueue 线程不是守护线程，
        // 全绿时若自然返回，JVM 会一直挂着（实测：门禁里这一条永远跑不完）。
        System.exit(0);
    }

    /**
     * 触发生产代码注册的监听器。
     *
     * <p>用仿真 Inlay（ABOVE_LINE，高度 = 行高）而不是真实平台对象：本探针验的是
     * 「生产代码有没有把 inlay 变化当回事」，不是 IDEA 自己的排版。
     */
    private static void fireListener(String which) {
        if (inlayListener == null) {
            return;                     // I-0 已经报红，这里安静跳过，避免 NPE 掩盖真正的失败
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
                VInlay.class.getClassLoader(),
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

    // ---------------------------------------------------------------- 脚手架

    private static void buildAnimator() throws Exception {
        Object primaryCaret = Proxy.newProxyInstance(
                VInlay.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.editor.Caret.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getVisualPosition":
                            // 关键：inlay 出现前后完全不变（平台就是这样 —— block inlay 不占视觉行号）
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
                VInlay.class.getClassLoader(),
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
                VInlay.class.getClassLoader(),
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
                            // 关键：inlay 不改文档，戳必须恒定
                            return STAMP;
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });

        InlayModel inlayModel = (InlayModel) Proxy.newProxyInstance(
                VInlay.class.getClassLoader(),
                new Class<?>[]{InlayModel.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "addListener":
                            // 记下生产代码注册的监听器，稍后由探针触发
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
                VInlay.class.getClassLoader(),
                new Class<?>[]{Editor.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getCaretModel":
                            return model;
                        case "visualPositionToXY":
                            // 平台行为：y 随 block inlay 高度下移（见类注释里的字节码依据）
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
                VInlay.class.getClassLoader(),
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
