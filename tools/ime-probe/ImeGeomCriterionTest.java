import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;

import javax.swing.JComponent;
import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * F1-c 几何判据的离线验证 harness。
 *
 * <p><b>做法</b>：用 {@link Proxy} 伪造 {@code Editor} / {@code CaretModel}，把
 * {@link CaretAnimator#refreshTarget()}（private，反射调用）真正跑起来，
 * 由我们注入 {@code getVisualPosition()} / {@code getOffset()} /
 * {@code visualPositionToXY()} / {@code offsetToXY()} 的返回值。
 *
 * <p><b>为什么不重写判据</b>：重写一遍等于「验证我自己的理解」。
 * 这里驱动的是<b>编译产物里的真实代码</b>，判据写错了就会原形毕露。
 *
 * <p>覆盖的边界（与契约 §2.3 逐条对应）：
 * <ol>
 *   <li>首次调用（未初始化）— 不得误判</li>
 *   <li>forceResync（窗口尺寸变化）— 必须优先放行</li>
 *   <li>正常移动（x 一致）— 必须放行</li>
 *   <li><b>IME 中间态（x 不一致、offset 与 column 都变了）— 必须拦截</b>
 *       ← 这正是批次2 的 F1-b 治不了、本批要治的那一种</li>
 *   <li>连续中间态 — 必须在上界处强制放行</li>
 *   <li>中间态结束（x 恢复一致）— 必须立即放行</li>
 *   <li>offsetToXY 抛异常 — 必须放行（不得变成永久冻结）</li>
 *   <li>getOffset 取不到（-1）— 判据不介入</li>
 *   <li>快速路径（vp 未变）— 不得调用昂贵的坐标换算</li>
 *   <li>每帧 getOffset 调用次数 = 1（P0-5）</li>
 * </ol>
 */
public final class ImeGeomCriterionTest {

    private static int passed = 0;
    private static int failed = 0;

    // ---- 注入用的可变量 ----
    private static int vpLine = 0;
    private static int vpColumn = 0;
    private static int caretOffset = 0;
    private static Point xyByVisual = new Point(100, 50);
    private static Point xyByOffset = new Point(100, 50);
    private static boolean offsetToXyThrows = false;
    private static boolean getOffsetThrows = false;
    // ---- 假 Document 的状态（虚拟空间判定要用） ----
    /** 假文档内容：单行时 lineStart=0, lineEnd=lineLen */
    private static int docLineStart = 0;
    private static int docLineEnd = 1000;
    private static int docTextLength = 1000;
    /** 该行真实字符数（getLineEndOffset - getLineStartOffset） */
    private static int docLineLength = 1000;

    private static int getOffsetCalls = 0;
    private static int visualToXyCalls = 0;
    private static int offsetToXyCalls = 0;

    private static CaretAnimator animator;
    private static Method refreshTarget;
    private static Field forceResyncField;
    private static Field imeSkipFramesField;

    public static void main(String[] args) throws Exception {
        System.out.println("=== F1-c 几何判据验证（驱动真实的 CaretAnimator.refreshTarget） ===");
        System.out.println();

        buildAnimator();

        System.out.println("--- A. 边界逐条 ---");
        a1_firstCall();
        a2_forceResync();
        a3_normalMove();
        a4_imeMidComposition();
        a5_consecutiveSkips();
        a6_compositionEnds();
        a7_offsetToXyThrows();
        a8_getOffsetUnavailable();
        a9_fastPath();
        a10_getOffsetCallCount();
        a11_visualOnlyVsOffset();
        a12_frozenUpperBoundKeepsState();
        a13_virtualSpaceNotBlocked();
        a13b_lineEndButNotVirtualSpace();

        System.out.println();
        System.out.println("--- B. 物理/几何未变（判据只影响「何时重算」，不影响算法） ---");
        b1_criterionDoesNotTouchPhysics();

        System.out.println();
        System.out.printf("=== 结果：通过 %d，失败 %d ===%n", passed, failed);
        if (failed > 0) {
            throw new AssertionError("有 " + failed + " 项未通过");
        }
        System.out.println("ALL OK");
    }

    // ==================== 搭架子 ====================

    /** 探针用的假 Caret：只需 getVisualPosition / getOffset */
    private static Object mockCaret;

    private static Object buildMockCaret() {
        return Proxy.newProxyInstance(
                ImeGeomCriterionTest.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.editor.Caret.class},
                (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "getVisualPosition":
                            return new com.intellij.openapi.editor.VisualPosition(vpLine, vpColumn);
                        case "getOffset":
                            return caretOffset;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static void buildAnimator() throws Exception {
        mockCaret = buildMockCaret();
        // CaretModel：只实现本判据用到的三个方法
        Object caretModel = Proxy.newProxyInstance(
                ImeGeomCriterionTest.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.editor.CaretModel.class},
                (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "getVisualPosition":
                            return new com.intellij.openapi.editor.VisualPosition(vpLine, vpColumn);
                        case "getOffset":
                            getOffsetCalls++;
                            if (getOffsetThrows) {
                                throw new IllegalStateException("模拟 getOffset 失败");
                            }
                            return caretOffset;
                        case "getPrimaryCaret":
                            return mockCaret;
                        case "getAllCarets": {
                            // 多光标改造后 refreshTarget 以 getAllCarets() 为准，
                            // 探针必须提供一个"主光标"对象，否则循环为空、
                            // refreshTarget 恒返回 false（第一版适配正是漏了这里）。
                            java.util.List<Object> one = new ArrayList<>();
                            one.add(mockCaret);
                            return one;
                        }
                        case "addCaretListener":
                        case "removeCaretListener":
                            return null;
                        case "getLogicalPosition":
                            return new com.intellij.openapi.editor.LogicalPosition(0, 0);
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        JComponent content = new JComponent() {
        };
        content.setSize(800, 600);

        // 假 Document：只为虚拟空间判定提供 3 个数
        Object document = Proxy.newProxyInstance(
                ImeGeomCriterionTest.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.editor.Document.class},
                (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "getLineNumber":
                            return 0;
                        case "getLineStartOffset":
                            return docLineStart;
                        case "getLineEndOffset":
                            return docLineEnd;
                        case "getTextLength":
                            return docTextLength;
                        case "getLineCount":
                            return 1;
                        case "toString":
                            return "FakeDocument";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        Object editor = Proxy.newProxyInstance(
                ImeGeomCriterionTest.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.editor.Editor.class},
                (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "getCaretModel":
                            return caretModel;
                        case "visualPositionToXY":
                            visualToXyCalls++;
                            return new Point(xyByVisual);
                        case "offsetToXY": {
                            offsetToXyCalls++;
                            if (offsetToXyThrows) {
                                throw new IllegalStateException("模拟 offsetToXY 失败");
                            }
                            // 支持可变参数形式
                            return new Point(xyByOffset);
                        }
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
                            return null;
                        case "toString":
                            return "FakeEditor";
                        case "hashCode":
                            return 1;
                        case "equals":
                            return proxy == margs[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        NeovideConfig config = new NeovideConfig();

        // CaretAnimator(Editor, NeovideConfig)
        java.lang.reflect.Constructor<?> ctor = CaretAnimator.class.getDeclaredConstructor(
                com.intellij.openapi.editor.Editor.class, NeovideConfig.class);
        ctor.setAccessible(true);
        animator = (CaretAnimator) ctor.newInstance(editor, config);

        refreshTarget = CaretAnimator.class.getDeclaredMethod("refreshTarget");
        refreshTarget.setAccessible(true);

        forceResyncField = field("forceResync");
        imeSkipFramesField = field("imeSkipFrames");

        // 【多光标改造适配 2026-10-23】
        //
        // lastCaretOffset / initialized / centerX / centerY / corners 已从
        // CaretAnimator 下沉到 SpringTrail（每个 Caret 一份）。
        // 判据逻辑仍在 CaretAnimator.refreshTarget 里，因此本测试要
        // 「注入一个已就位的主光标拖尾」，而不是直接写那几个字段。
        //
        // 做法：调用 refreshTarget 一次让 activeTrails 里有条目，
        // 然后通过 cameraTrail 字段拿到那份 SpringTrail 并写它的字段。
        cameraTrailField = field("cameraTrail");
        activeTrailsField = field("activeTrails");
        initBaselineTrail();
    }

    private static Field cameraTrailField;
    private static Field activeTrailsField;

    /** 取当前主光标拖尾（多光标改造后，单光标状态都在它身上） */
    private static Object mainTrail() throws Exception {
        Object t = cameraTrailField.get(animator);
        if (t == null) {
            java.util.List<?> act = (java.util.List<?>) activeTrailsField.get(animator);
            if (act != null && !act.isEmpty()) {
                t = act.get(0);
            }
        }
        return t;
    }

    private static boolean getTrailBool(String name) throws Exception {
        Object t = mainTrail();
        if (t == null) { return false; }
        Field f = t.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(t);
    }

    private static int getTrailInt(String name) throws Exception {
        Object t = mainTrail();
        if (t == null) { return Integer.MIN_VALUE; }
        Field f = t.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(t);
    }

    private static void setTrailField(String name, Object value) throws Exception {
        Object t = mainTrail();
        if (t == null) {
            return;     // 尚未建立拖尾：调用方会先跑一帧
        }
        Field f = t.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(t, value);
    }

    /** 建一个已就位的主光标拖尾（跑一帧 refreshTarget 即可） */
    private static void initBaselineTrail() throws Exception {
        xyByVisual = new Point(100, 50);
        xyByOffset = new Point(100, 50);
        caretOffset = 10;
        vpColumn = 0;
        refreshTarget.invoke(animator);
        setTrailField("initialized", true);
        setTrailField("lastCaretOffset", 10);
    }

    private static Field field(String name) throws Exception {
        Field f = CaretAnimator.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) {
            return null;
        }
        if (t == boolean.class) {
            return false;
        }
        if (t == int.class) {
            return 0;
        }
        if (t == long.class) {
            return 0L;
        }
        if (t == double.class) {
            return 0d;
        }
        if (t == float.class) {
            return 0f;
        }
        return null;
    }

    // ==================== 断言 ====================

    private static void check(String name, boolean cond, String detail) {
        if (cond) {
            passed++;
            System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "   " + detail));
        } else {
            failed++;
            System.out.println("  FAIL  " + name + "   " + detail);
        }
    }

    private static boolean call() throws Exception {
        return (Boolean) refreshTarget.invoke(animator);
    }

    private static void resetCounters() {
        getOffsetCalls = 0;
        visualToXyCalls = 0;
        offsetToXyCalls = 0;
        offsetToXyThrows = false;
        getOffsetThrows = false;
    }

    // ==================== A 组：边界 ====================

    /**
     * 把 animator 重置到一个"已定位、状态干净"的基线。
     *
     * <p><b>这一步是必需的</b>：{@code refreshTarget()} 的返回值语义是
     * 「{@code centerX/centerY/cursorHeight} 是否发生了变化」，而不是「是否放行」。
     * 于是若上一用例恰好把 centerX 留在了本用例要算出的同一个值上，
     * 即便路径完全正确也会返回 false，把测试自己坑掉。
     *
     * <p>同时把 {@code initialized} 显式设为 true（除 A1 专门测首次定位外），
     * 否则会走 {@code snapTo} 首次吸附分支。
     */
    private static void resetToStableBaseline(int lastOffset, int lastColumn,
                                              double cx, double cy) throws Exception {
        // 【顺序很重要】先跑一帧把拖尾建起来（这会消耗计数），
        // 再 resetCounters() 清零 —— 反过来会让基线帧的调用被算进被测帧。
        // 这是多光标改造适配时踩到的坑（第一版把 resetCounters 放在了前面，
        // 导致 A9/A10 的调用计数恒为 2）。
        xyByVisual = new Point(100, 50);
        xyByOffset = new Point(100, 50);
        caretOffset = lastOffset;
        vpColumn = lastColumn;
        forceResyncField.setBoolean(animator, false);
        forceResyncField.setBoolean(animator, true);   // 强制走完整路径建立拖尾
        refreshTarget.invoke(animator);

        resetCounters();
        forceResyncField.setBoolean(animator, false);
        imeSkipFramesField.setInt(animator, 0);

        setTrailField("initialized", true);
        setTrailField("lastCaretOffset", lastOffset);
        setTrailField("centerX", cx);
        setTrailField("centerY", cy);
        // 缓存键必须与「即将被测的那一帧」匹配，否则快速路径不会命中：
        //   A9 会设 vpColumn = lastColumn，期望命中 → 这里就要写同样的值。
        setTrailField("lastLine", vpLine);
        setTrailField("lastColumn", lastColumn);
        // mock Document 的 getModificationStamp 恒返回同一个值（见 buildAnimator 的 Proxy），
        // 这里用 0 与之对齐即可。
        setTrailField("lastStamp", 0L);
        field("cursorHeight").setDouble(animator, 32);
        field("cursorWidth").setDouble(animator, 2);
        vpLine = 0;
        // 默认假文档：光标不在行尾（lineEnd 远大于 caretOffset），
        // 因此虚拟空间判定恒为 false —— 不会干扰其它用例。
        docLineStart = 0;
        docLineEnd = 1000;
        docTextLength = 1000;
        docLineLength = 1000;
    }
    private static void a1_firstCall() throws Exception {
        resetCounters();
        forceResyncField.setBoolean(animator, false);
        // 多光标改造后：主光标拖尾状态注入
        xyByVisual = new Point(100, 50);
        xyByOffset = new Point(100, 50);
        caretOffset = 10;
        vpColumn = 0;
        refreshTarget.invoke(animator);
        setTrailField("lastCaretOffset", -1);
        setTrailField("initialized", false);
        // 清掉上次遗留的目标点：refreshTarget 的返回值语义是
        // "中心点/高度是否变化"，遗留同值会让正确的路径也返回 false。
        setTrailField("centerX", -999.0);
        setTrailField("centerY", -999.0);
        field("cursorHeight").setDouble(animator, -999);

        // 即使几何不一致，首次也绝不能判为中间态（否则永远画不出第一帧）
        xyByVisual = new Point(100, 50);
        xyByOffset = new Point(120, 50);
        caretOffset = 30;
        vpColumn = 9;

        boolean moved = call();
        boolean init = getTrailBool("initialized");
        check("A1 首次调用不得误判（几何不一致也要正常定位）",
                moved && init,
                "refreshTarget=" + moved + " initialized=" + init);
        check("A1b 首次定位后 lastVisualColumn 已记为该 vp",
                getTrailInt("lastColumn") == 9,
                "lastVisualColumn=" + getTrailInt("lastColumn") + "（期望 9）");
    }

    private static void a2_forceResync() throws Exception {
        resetToStableBaseline(10, 3, 1000, 1000);
        forceResyncField.setBoolean(animator, true);

        // 几何不一致 —— 但 forceResync 必须优先
        xyByVisual = new Point(100, 50);
        xyByOffset = new Point(140, 50);
        caretOffset = 10;
        vpColumn = 3;

        boolean moved = call();
        check("A2 forceResync 优先放行（不得被几何判据拦截）",
                moved,
                "refreshTarget=" + moved + "（期望 true）");
        check("A2b forceResync 已被清零",
                !forceResyncField.getBoolean(animator),
                "forceResync=" + forceResyncField.getBoolean(animator));
    }

    private static void a3_normalMove() throws Exception {
        resetToStableBaseline(10, 3, 1000, 1000);

        // 正常移动：x 一致
        xyByVisual = new Point(140, 50);
        xyByOffset = new Point(140, 50);
        caretOffset = 14;
        vpColumn = 7;

        boolean moved = call();
        // centerX 变了才算 moved=true；这里 140+1=141 != 旧 centerX
        check("A3 正常移动（x 一致）必须放行",
                moved,
                "refreshTarget=" + moved + "（期望 true）");
        check("A3b lastVisualColumn 已更新",
                getTrailInt("lastColumn") == 7,
                "lastVisualColumn=" + getTrailInt("lastColumn") + "（期望 7）");
    }

    /** ★ 本批核心：offset 与 column 都变了，但几何不一致 → 必须拦截 */
    private static void a4_imeMidComposition() throws Exception {
        resetToStableBaseline(14, 7, 1000, 1000);

        double beforeCenterX = centerX();

        // 关键场景：offset 从 14 → 17（变了！），column 从 7 → 9（也变了！）
        // 但两个 API 给出的 x 差半个字宽 —— 这正是批次2 的 F1-b 抓不到的形态
        caretOffset = 17;
        vpColumn = 9;
        xyByVisual = new Point(150, 50);   // vp 说：在 150
        xyByOffset = new Point(140, 50);   // offset 说：真实字符边界在 140

        boolean moved = call();

        check("A4 ★ IME 中间态（offset 与 column 都变、x 不一致）必须被拦截",
                !moved,
                "refreshTarget=" + moved + "（期望 false）");
        check("A4b 被拦时不更新 lastVisualColumn",
                getTrailInt("lastColumn") == 7,
                "lastVisualColumn=" + getTrailInt("lastColumn") + "（期望仍为 7）");
        check("A4c 被拦时光标中心点不变（不画错位置）",
                centerX() == beforeCenterX,
                "centerX " + beforeCenterX + " -> " + centerX());
        check("A4d imeSkipFrames 已自增",
                imeSkipFramesField.getInt(animator) == 1,
                "imeSkipFrames=" + imeSkipFramesField.getInt(animator));
    }

    /** 连续中间态：必须在 IME_MAX_SKIP_FRAMES 处强制放行 */
    private static void a5_consecutiveSkips() throws Exception {
        resetToStableBaseline(20, 4, 1000, 1000);

        // 恒定中间态：每次调用都应被拦，直到上界
        caretOffset = 20;
        xyByVisual = new Point(200, 50);
        xyByOffset = new Point(180, 50);

        int skipped = 0;
        int released = 0;
        int releaseAt = -1;
        for (int i = 1; i <= 30; i++) {
            boolean moved = call();
            if (!moved) {
                skipped++;
            } else {
                released++;
                if (releaseAt < 0) {
                    releaseAt = i;
                }
            }
        }
        check("A5 连续中间态必须在上界处强制放行（不得无限期冻结）",
                released > 0,
                "30 帧内：拦截 " + skipped + " 次，放行 " + released + " 次，首次放行在第 " + releaseAt + " 帧");
        check("A5b 首次放行发生在 ≈ IME_MAX_SKIP_FRAMES 处",
                releaseAt == 16,
                "首次放行在第 " + releaseAt + " 帧（期望 16 = 15 次跳过后放行）");
    }

    private static void a6_compositionEnds() throws Exception {
        resetToStableBaseline(30, 5, 1000, 1000);

        // 组合结束：几何恢复一致
        caretOffset = 33;
        vpColumn = 8;
        xyByVisual = new Point(260, 50);
        xyByOffset = new Point(260, 50);

        boolean moved = call();
        check("A6 组合结束（x 恢复一致）必须立即放行",
                moved,
                "refreshTarget=" + moved + "（期望 true）");
        check("A6b imeSkipFrames 已清零",
                imeSkipFramesField.getInt(animator) == 0,
                "imeSkipFrames=" + imeSkipFramesField.getInt(animator));
    }

    private static void a7_offsetToXyThrows() throws Exception {
        resetToStableBaseline(40, 6, 1000, 1000);

        offsetToXyThrows = true;
        caretOffset = 44;
        vpColumn = 9;
        xyByVisual = new Point(300, 50);

        boolean moved = call();
        check("A7 offsetToXY 抛异常时必须放行（不得变成永久冻结）",
                moved,
                "refreshTarget=" + moved + "（期望 true）");
    }

    private static void a8_getOffsetUnavailable() throws Exception {
        resetToStableBaseline(50, 4, 1000, 1000);

        getOffsetThrows = true;
        vpColumn = 7;
        xyByVisual = new Point(340, 50);

        boolean moved = call();
        check("A8 getOffset 取不到时判据不介入（正常放行）",
                moved,
                "refreshTarget=" + moved + "（期望 true）");
        check("A8b getOffset 失败不影响 lastCaretOffset（保持旧值）",
                getTrailInt("lastCaretOffset") == 50,
                "lastCaretOffset=" + getTrailInt("lastCaretOffset") + "（期望 50）");
    }

    private static void a9_fastPath() throws Exception {
        resetToStableBaseline(50, 7, 1000, 1000);
        vpColumn = 7;      // 与 lastVisualColumn 相同 → 快速路径命中

        boolean moved = call();
        check("A9 快速路径命中时不重算",
                !moved,
                "refreshTarget=" + moved);
        check("A9b 快速路径下不得调用 visualPositionToXY",
                visualToXyCalls == 0,
                "visualPositionToXY 调用 " + visualToXyCalls + " 次（期望 0）");
        check("A9c 快速路径下不得调用 offsetToXY",
                offsetToXyCalls == 0,
                "offsetToXY 调用 " + offsetToXyCalls + " 次（期望 0）");
        check("A9d 快速路径下不得调用 getOffset",
                getOffsetCalls == 0,
                "getOffset 调用 " + getOffsetCalls + " 次（期望 0）");
    }

    private static void a10_getOffsetCallCount() throws Exception {
        int maxCalls = 0;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            resetToStableBaseline(60, 2, 1000, 1000);
            vpColumn = 3 + i;
            caretOffset = 61 + i;
            xyByVisual = new Point(400 + i, 50);
            xyByOffset = new Point(400 + i, 50);
            call();
            maxCalls = Math.max(maxCalls, getOffsetCalls);
            detail.append(getOffsetCalls).append(' ');
        }
        check("A10 非快速路径帧每帧 getOffset 恰好 1 次（P0-5）",
                maxCalls == 1,
                "各帧调用次数 = " + detail.toString().trim() + "（期望全 1）");
    }

    private static void a11_visualOnlyVsOffset() throws Exception {
        resetToStableBaseline(70, 1, 1000, 1000);

        // 反向：offset 说 500，vp 说 480 —— 同样不一致，同样应拦
        caretOffset = 71;
        vpColumn = 5;
        xyByVisual = new Point(480, 50);
        xyByOffset = new Point(500, 50);

        boolean moved = call();
        check("A11 反向不一致（p.x > q.x）同样应被拦截",
                !moved,
                "refreshTarget=" + moved + "（期望 false）");
    }

    private static void a12_frozenUpperBoundKeepsState() throws Exception {
        resetToStableBaseline(80, 2, 1000, 1000);

        // 真实 IME 组合期间 vp 每帧都在变（组合串在增长/候选在换），
        // 因此这里让 vpColumn 每帧递增 —— 这也顺便验证了：
        // 强制放行不会把 imeSkipFrames 卡在非零，下一轮仍有完整的 15 帧预算。
        caretOffset = 80;
        xyByVisual = new Point(200, 50);
        xyByOffset = new Point(180, 50);

        // 【注意】不能用 refreshTarget 的返回值判断"是否放行"：
        // 它返回的是"中心点是否变化"，当放行后算出的中心点与旧值相同（本例 x 恒定）
        // 时同样返回 false。改用"lastVisualColumn 是否被更新"作为放行信号 ——
        // 判据拦截时明确不更新它，这正是可观测的区分点。
        int released = 0;
        int firstReleaseAt = -1;
        int secondReleaseAt = -1;
        for (int i = 1; i <= 40; i++) {
            vpColumn = 3 + i;          // vp 持续变化（组合串在长）
            int prevLastColumn = getTrailInt("lastColumn");
            call();
            boolean acceptedThisFrame = getTrailInt("lastColumn") != prevLastColumn;
            if (acceptedThisFrame) {
                released++;
                if (firstReleaseAt < 0) {
                    firstReleaseAt = i;
                } else if (secondReleaseAt < 0) {
                    secondReleaseAt = i;
                }
            }
        }
        check("A12 长时间中间态下仍会周期性放行（不会永久卡死）",
                released >= 2,
                "40 帧内放行 " + released + " 次（首次 @" + firstReleaseAt + "，第二次 @" + secondReleaseAt + "）");
        check("A12b 两次强制放行的间隔 = IME_MAX_SKIP_FRAMES + 1 = 16 帧",
                secondReleaseAt - firstReleaseAt == 16,
                "间隔 = " + (secondReleaseAt - firstReleaseAt) + " 帧（期望 16）");
    }

    /**
     * 【回归防护】光标推到行尾之后（virtual space）时，两个 API 本就应当给出不同 x。
     *
     * <p>这是 IntelliJ 的默认行为（virtual space 默认开启）：按 → 键可以把光标
     * 推到行尾之后，{@code visualPositionToXY} 会画到行尾之外，
     * 而 {@code offsetToXY} 被钳到 textLength 只能返回行尾。
     *
     * <p>若判据不排除这种情况，用户每次按 → 到行尾之后覆盖层都会被冻结 ——
     * 一个比原 bug 更显眼的新缺陷。本用例专门守住它。
     */
    private static void a13_virtualSpaceNotBlocked() throws Exception {
        resetToStableBaseline(10, 3, 1000, 1000);

        // 构造虚拟空间：这一行只有 10 个字符，光标在行尾(offset=10)，
        // 但 vp.column 已经被推到 14（超出行尾 4 列）—— 这正是按 → 键的效果。
        docLineStart = 0;
        docLineEnd = 10;
        docTextLength = 10;
        docLineLength = 10;

        caretOffset = 10;              // 正好在行尾（最后一个字符之后）
        vpColumn = 14;                 // 超出该行 10 个字符 → 虚拟空间
        xyByVisual = new Point(600, 50);   // 画到行尾之外
        xyByOffset = new Point(300, 50);   // 被钳到行尾 → 不同

        boolean moved = call();
        check("A13 virtual-space（光标在行尾之后）不得被判为 IME 中间态",
                moved,
                "refreshTarget=" + moved + "（期望 true：这是设计行为，不是中间态）");
        check("A13b virtual-space 帧不消耗冻结预算",
                imeSkipFramesField.getInt(animator) == 0,
                "imeSkipFrames=" + imeSkipFramesField.getInt(animator) + "（期望 0）");
    }

    /**
     * 对照：同样 offset==行尾、但 vp.column 没超出 → 不构成虚拟空间，
     * 此时 x 不一致仍应判为中间态（证明排除条件足够窄，没有把真实中间态一并放过）。
     */
    private static void a13b_lineEndButNotVirtualSpace() throws Exception {
        resetToStableBaseline(10, 3, 1000, 1000);

        docLineStart = 0;
        docLineEnd = 10;
        docTextLength = 10;
        docLineLength = 10;

        caretOffset = 10;              // 在行尾
        vpColumn = 10;                 // 恰好等于行字符数 → 没超出，不是虚拟空间
        xyByVisual = new Point(310, 50);
        xyByOffset = new Point(300, 50);   // 仍不一致

        boolean moved = call();
        check("A13b 行尾但非 virtual-space 时，x 不一致仍应判为中间态",
                !moved,
                "refreshTarget=" + moved + "（期望 false：排除条件不能过宽）");
    }
    // ==================== B 组：物理未变 ====================

    private static void b1_criterionDoesNotTouchPhysics() throws Exception {
        // 判据只决定「这一帧要不要更新目标点」，绝不该碰弹簧参数。
        // 这里验证：被拦的帧里，四个角点坐标完全没动。
        resetToStableBaseline(90, 3, 1000, 1000);

        caretOffset = 90;
        vpColumn = 4;
        xyByVisual = new Point(120, 50);
        xyByOffset = new Point(100, 50);

        // 多光标改造后：四角在主光标那份 SpringTrail 上
        Object mainTrail = mainTrail();
        Field cornersField = mainTrail.getClass().getDeclaredField("corners");
        cornersField.setAccessible(true);
        Object corners = cornersField.get(mainTrail);
        int n = java.lang.reflect.Array.getLength(corners);
        double[][] before = new double[n][2];
        for (int i = 0; i < n; i++) {
            Object c = java.lang.reflect.Array.get(corners, i);
            Method gx = c.getClass().getMethod("getCurrentX");
            Method gy = c.getClass().getMethod("getCurrentY");
            before[i][0] = (double) gx.invoke(c);
            before[i][1] = (double) gy.invoke(c);
        }

        boolean moved = call();
        boolean same = true;
        for (int i = 0; i < n; i++) {
            Object c = java.lang.reflect.Array.get(corners, i);
            Method gx = c.getClass().getMethod("getCurrentX");
            Method gy = c.getClass().getMethod("getCurrentY");
            if (before[i][0] != (double) gx.invoke(c) || before[i][1] != (double) gy.invoke(c)) {
                same = false;
            }
        }
        check("B1 被拦的帧不触碰角点坐标（判据只影响「何时重算」）",
                !moved && same,
                "moved=" + moved + " cornersUnchanged=" + same);
    }

    /** 主光标拖尾的目标中心 x（多光标改造后状态在 SpringTrail 上） */
    private static double centerX() throws Exception {
        Object t = mainTrail();
        if (t == null) { return Double.NaN; }
        Field f = t.getClass().getDeclaredField("centerX");
        f.setAccessible(true);
        return f.getDouble(t);
    }
}
