import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.VisualPosition;

import javax.swing.JComponent;
import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

/**
 * V-FONTSCALE：Ctrl+滚轮改字号后，光标必须重新贴合行位置。
 *
 * <p><b>它守的是什么 bug</b>：用户实测「Ctrl+鼠标滚轮快捷切换字号，光标纵向漂移」。
 *
 * <p>根因在 {@code CaretAnimator.refreshTarget()} 的快速路径：它只看
 * 「行号 + 列号 + 文档修改戳」，改字号时三者全不变 → 判定「没动」直接早退
 * → {@code cp.y} 与 {@code lineHeight} 都不重读 → 覆盖层按<b>旧行高</b>绘制
 * → 视觉上光标纵向偏出该在的位置。
 *
 * <p><b>本探针的判据</b>：让 {@code getLineHeight()} 在两次 refreshTarget 之间变化，
 * 断言第二帧必须<b>不被快速路径拦下</b>（即目标几何真的被重算）。
 *
 * <p><b>为什么用「目标几何」而不是读内部标志</b>（纪律四-G）：
 * 不去读 {@code forceResync} 这种内部变量 —— 那是影子副本，判据坏了探针也看不出来。
 * 直接比对 {@code SpringTrail} 上的 {@code height} 与 {@code centerY}，
 * <b>那是绘制真正用的那份数据</b>。行高变了，这两个值就必须跟着变。
 */
public final class VFontScale {

    private static int pass = 0;
    private static int fail = 0;

    /** 可变行高：编辑器桩每次 getLineHeight() 都读它。 */
    private static int lineHeight = 32;

    private static final int VP_LINE = 10;
    private static final int VP_COLUMN = 4;

    private static CaretAnimator animator;
    private static Method refreshTarget;
    private static Object primaryCaret;
    private static java.util.List<Object> carets;

    public static void main(String[] args) throws Exception {
        V0Env.install();

        System.out.println("=== V-FONTSCALE：Ctrl+滚轮改字号 → 光标必须重新贴合行位置 ===");

        buildAnimator();

        // 首帧：行高 32，让拖尾就位
        lineHeight = 32;
        refreshTarget.invoke(animator);
        forceTrailInitialized();

        double h32 = trailHeight();
        double cy32 = trailCenterY();
        System.out.println();
        System.out.println("--- 基线（行高 32）---");
        System.out.println("    trail.height=" + h32 + "  centerY=" + cy32);

        // 改字号：行高 32 → 48。行号/列号/文档戳一律不变（与真实缩放一致）。
        lineHeight = 48;
        refreshTarget.invoke(animator);
        double h48 = trailHeight();
        double cy48 = trailCenterY();

        check("F-1 行高变化后光标高度必须跟着变（快速路径不得早退）",
                Math.abs(h48 - h32) > 0.5,
                "行高 32→48，实测 height " + h32 + " → " + h48);

        check("F-2 行高变大后垂直中心必须下移（不是原地不动）",
                cy48 > cy32 + 0.5,
                "centerY " + cy32 + " → " + cy48);

        // 行高按比例变：48/32 = 1.5，cursorHeightRatio=1.0 时 height 应为 1.5 倍
        check("F-3 光标高度与行高成比例（1.5 倍）",
                Math.abs(h48 / h32 - 1.5) < 0.05,
                "比值 = " + (h48 / h32) + "（期望 1.5）");

        // 反向：缩回 32，必须也跟上（不能只处理放大）
        lineHeight = 32;
        refreshTarget.invoke(animator);
        double hBack = trailHeight();
        check("F-4 字号缩回后光标高度也必须跟着变（双向，不只放大）",
                Math.abs(hBack - h32) < 0.5,
                "缩回后 height=" + hBack + "（期望回到 " + h32 + "）");

        // 行高不变时不能每次都触发重算 —— 否则快速路径失效、性能垮掉。
        boolean first = Boolean.TRUE.equals(refreshTarget.invoke(animator));
        boolean second = Boolean.TRUE.equals(refreshTarget.invoke(animator));
        check("F-5 行高不变时快速路径仍然生效（不得每帧强制重算）",
                !second,
                "两帧返回值 first=" + first + " second=" + second + "（第二帧应为 false）");

        System.out.println();
        System.out.printf("=== V-FONTSCALE 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-FONTSCALE RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-FONTSCALE RESULT: ALL PASS");
    }

    // ---------------------------------------------------------------- 脚手架

    private static void buildAnimator() throws Exception {
        primaryCaret = Proxy.newProxyInstance(
                VFontScale.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.editor.Caret.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getVisualPosition":
                            return new VisualPosition(VP_LINE, VP_COLUMN);
                        case "getOffset":
                            return 40;
                        case "getLogicalPosition":
                            return new com.intellij.openapi.editor.LogicalPosition(VP_LINE, VP_COLUMN);
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
        carets = new ArrayList<>();
        carets.add(primaryCaret);

        CaretModel model = (CaretModel) Proxy.newProxyInstance(
                VFontScale.class.getClassLoader(),
                new Class<?>[]{CaretModel.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getVisualPosition":
                            return new VisualPosition(VP_LINE, VP_COLUMN);
                        case "getOffset":
                            return 40;
                        case "getPrimaryCaret":
                            return primaryCaret;
                        case "getAllCarets":
                            // 真实平台的 getAllCarets() 至少含主光标 —— 返回空列表
                            // 会让 trailSlotCount 算出 0 条拖尾，探针就什么都测不到。
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
                VFontScale.class.getClassLoader(),
                new Class<?>[]{Document.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getLineNumber":
                            return 0;
                        case "getLineStartOffset":
                            return 0;
                        case "getLineEndOffset":
                            return 100000;
                        case "getTextLength":
                            return 100000;
                        case "getModificationStamp":
                            // 关键：文档戳恒定 —— 与 Ctrl+滚轮缩放的真实情况一致
                            return 12345L;
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });

        Editor editor = (Editor) Proxy.newProxyInstance(
                VFontScale.class.getClassLoader(),
                new Class<?>[]{Editor.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getCaretModel":
                            return model;
                        case "visualPositionToXY":
                            return new Point(200, 120);
                        case "offsetToXY":
                            return new Point(200, 120);
                        case "getDocument":
                            return doc;
                        case "getSelectionModel":
                            return selectionModel();
                        case "getLineHeight":
                            return lineHeight;      // ← 可变：模拟 Ctrl+滚轮缩放
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

    /** 块选区模型桩：返回空数组表示「没有块选区」，refreshTarget 里据此走单光标分支。 */
    private static com.intellij.openapi.editor.SelectionModel selectionModel() {
        return (com.intellij.openapi.editor.SelectionModel) Proxy.newProxyInstance(
                VFontScale.class.getClassLoader(),
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

    /** 让主光标对应的 SpringTrail 进入「已初始化」状态，快速路径才有意义。 */
    private static void forceTrailInitialized() throws Exception {
        Object trail = mainTrail();
        if (trail != null) {
            Field f = trail.getClass().getDeclaredField("initialized");
            f.setAccessible(true);
            f.setBoolean(trail, true);
        }
    }

    /** 主光标对应的 SpringTrail（绘制真正用的那份数据，不是影子副本）。 */
    private static Object mainTrail() throws Exception {
        Field cf = CaretAnimator.class.getDeclaredField("cameraTrail");
        cf.setAccessible(true);
        Object t = cf.get(animator);
        if (t == null) {
            Field af = CaretAnimator.class.getDeclaredField("activeTrails");
            af.setAccessible(true);
            Object act = af.get(animator);
            if (act instanceof java.util.Collection) {
                java.util.Collection<?> c = (java.util.Collection<?>) act;
                if (!c.isEmpty()) {
                    t = c.iterator().next();
                }
            } else if (act instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) act;
                if (!m.isEmpty()) {
                    t = m.values().iterator().next();
                }
            }
        }
        return t;
    }

    private static double trailHeight() throws Exception {
        return num(mainTrail(), "height");
    }

    private static double trailCenterY() throws Exception {
        return num(mainTrail(), "centerY");
    }

    private static double num(Object o, String name) throws Exception {
        if (o == null) {
            return Double.NaN;
        }
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getDouble(o);
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
