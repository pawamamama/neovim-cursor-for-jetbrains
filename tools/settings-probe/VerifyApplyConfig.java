import com.github.yyyolo7a79.neovidecursor.core.AfterimageTrail;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.VisualPosition;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * verifier 自建探针 V —— applyConfig() 生效性（三类缓存）+ 跳过 S7 反例 + 空集合/已 dispose 的鲁棒性。
 *
 * <p>驱动 build\verify-t4\src-classes 里【verifier 自己从当前工作区源码编译出来】的
 * 真 {@link NeovideCaretManager#applyConfig()} 与 {@link CaretAnimator}，平台侧只伪造
 * Application / PropertiesComponent / Editor。不重写任何被测逻辑。
 */
public final class VerifyApplyConfig {

    private static int pass = 0;
    private static int fail = 0;

    private static FakeProps pc = new FakeProps();
    private static FakeApp app = new FakeApp();
    private static NeovideCursorSettings svc;

    private static CaretAnimator animator;
    private static CaretTrailPanel panel;
    private static Method refreshTarget;
    private static Editor lastEditor;

    // 假编辑器的光标状态（想让它"不动"就保持这两个值不变）
    private static int vpLine = 3;
    private static int vpColumn = 7;
    private static int caretOffset = 40;
    private static int lineHeight = 32;

    public static void main(String[] args) throws Exception {
        System.out.println("=== verifier 探针 V：applyConfig 生效性 ===");

        app.pc = pc;
        svc = new NeovideCursorSettings();
        installFakeApp();
        System.out.println("  假 Application 已注入；getService 可取到 PropertiesComponent = "
                + (ApplicationManager.getApplication().getService(PropertiesComponent.class) == pc));

        v1_baseline();
        v2_applyConfig();
        v3_skipS7();
        v4_onModeChangedOnly();
        v5_idempotent();
        v6_robustness();
        v7_derivedFactors();

        System.out.println();
        System.out.printf("=== 结果：通过 %d，失败 %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("HAS FAILURES");
            System.exit(1);
        }
        System.out.println("ALL OK");
        // 显式退出：headless AWT 的 EDT / 事件线程是非守护线程，
        // 不 exit 的话 JVM 会一直等它们（探针看起来"挂起"）。RegimeProbe 也这么做。
        System.exit(0);
    }

    // ==================== V1：建立已知初值 ====================

    private static void v1_baseline() throws Exception {
        System.out.println();
        System.out.println("--- V1. 建立初值：shadowBlurFactor=0.2 / tailColor=#FFC0CB / opacity=1.0 / lifetime=0.22 ---");
        NeovideConfig cfg = NeovideCaretManager.getConfig();
        cfg.shadowBlurFactor = 0.2f;
        cfg.tailColor = "#FFC0CB";
        cfg.tailOpacity = 1.0f;
        cfg.shadowColor = "#FFC0CB";
        cfg.afterimageLifetime = 0.22f;
        cfg.useShadow = true;

        svc.syncConfigToState();
        svc.getState().shadowBlurFactor = 0.2f;
        svc.getState().tailColor = "#FFC0CB";
        svc.getState().tailOpacity = 1.0f;
        svc.getState().shadowColor = "#FFC0CB";

        buildAnimator();
        tick();

        System.out.println("  实测： glowPadding=" + intField(animator, "glowPadding")
                + "  fillColor=" + hex(colorField(panel, "fillColor"))
                + "  glowColor=" + hex(colorField(panel, "glowColor"))
                + "  lifetime=" + lifetime()
                + "  cursorHeight=" + dblField(animator, "cursorHeight"));
        check("V1 初值 glowPadding = 11（ceil(max(3, 0.2*32))+4 = 7+4）",
                intField(animator, "glowPadding") == 11, "实际 " + intField(animator, "glowPadding"));
        check("V1 初值 fillColor = #FFC0CB alpha=255",
                hex(colorField(panel, "fillColor")).equals("#FFC0CB alpha=255"), hex(colorField(panel, "fillColor")));
        check("V1 初值 lifetime ≈ 0.22",
                Math.abs(lifetime() - 0.22) < 1e-6, "实际 " + lifetime());
    }

    // ==================== V2：applyConfig 后三类缓存都变 ====================

    private static void v2_applyConfig() throws Exception {
        System.out.println();
        System.out.println("--- V2. 改值 → applyConfig()（光标全程不动）→ 三类缓存是否真的刷新 ---");
        NeovideConfig cfg = NeovideCaretManager.getConfig();
        NeovideCursorSettings.State s = svc.getState();

        s.shadowBlurFactor = 0.6f;
        s.tailColor = "#00FF00";
        s.tailOpacity = 0.5f;
        s.shadowColor = "#00FF00";
        // afterimageLifetime 不在第一期 State 里（契约 §6.1 明确不进 UI），只能经 CONFIG 改
        cfg.afterimageLifetime = 0.5f;

        int padBefore = intField(animator, "glowPadding");
        Color fillBefore = colorField(panel, "fillColor");
        Color glowBefore = colorField(panel, "glowColor");
        double lifeBefore = lifetime();

        NeovideCaretManager.applyConfig();
        tick();   // 观察 EDT 帧（applyConfig 只在 EDT 上同步跑，慢路径要一帧）

        int padAfter = intField(animator, "glowPadding");
        Color fillAfter = colorField(panel, "fillColor");
        Color glowAfter = colorField(panel, "glowColor");
        double lifeAfter = lifetime();

        System.out.println("  改前： glowPadding=" + padBefore + " fillColor=" + hex(fillBefore)
                + " glowColor=" + hex(glowBefore) + " lifetime=" + lifeBefore);
        System.out.println("  改后： glowPadding=" + padAfter + " fillColor=" + hex(fillAfter)
                + " glowColor=" + hex(glowAfter) + " lifetime=" + lifeAfter);

        check("V2-1 glowPadding 已刷新 11 → 24（ceil(max(3,0.6*32))+4 = 20+4）",
                padBefore == 11 && padAfter == 24, padBefore + " → " + padAfter);
        check("V2-2 CaretTrailPanel.fillColor 已刷新 #FFC0CB/1.0 → #00FF00/0.5（alpha=round(255*0.5)=128）",
                hex(fillBefore).equals("#FFC0CB alpha=255") && hex(fillAfter).equals("#00FF00 alpha=128"),
                hex(fillBefore) + " → " + hex(fillAfter));
        check("V2-3 CaretTrailPanel.glowColor 已刷新且 alpha 恒 255（refreshStyles 硬编码 1.0f）",
                hex(glowBefore).equals("#FFC0CB alpha=255") && hex(glowAfter).equals("#00FF00 alpha=255"),
                hex(glowBefore) + " → " + hex(glowAfter));
        check("V2-4 AfterimageTrail.lifetime 已刷新 0.22 → 0.5",
                Math.abs(lifeBefore - 0.22) < 1e-6 && Math.abs(lifeAfter - 0.5) < 1e-9,
                lifeBefore + " → " + lifeAfter);
        check("V2-5 CONFIG 与 state 一致（applyConfig 的 S1 真的写进去了）",
                cfg.shadowBlurFactor == 0.6f && "#00FF00".equals(cfg.tailColor),
                "cfg.shadowBlurFactor=" + cfg.shadowBlurFactor + " cfg.tailColor=" + cfg.tailColor);
        check("V2-6 trailFactors 已按 stretchRatioK 重建为新数组（不是 State 的引用）",
                cfg.trailFactors != null && cfg.trailFactors.length == 4, arr(cfg.trailFactors));

        // 反向可逆
        s.shadowBlurFactor = 0.2f;
        NeovideCaretManager.applyConfig();
        tick();
        check("V2-7 glowPadding 可逆：24 → 11", intField(animator, "glowPadding") == 11,
                "实际 " + intField(animator, "glowPadding"));
    }

    // ==================== V3：跳过 S7 的反例 ====================

    private static void v3_skipS7() throws Exception {
        System.out.println();
        System.out.println("--- V3. 反例：只写字段（S1），不调 S7 → 三类缓存必须全部保持旧值 ---");
        NeovideConfig cfg = NeovideCaretManager.getConfig();
        cfg.shadowBlurFactor = 0.2f;
        cfg.tailColor = "#FFC0CB";
        cfg.tailOpacity = 1.0f;
        cfg.shadowColor = "#FFC0CB";
        cfg.afterimageLifetime = 0.22f;
        svc.getState().shadowBlurFactor = 0.2f;
        svc.getState().tailColor = "#FFC0CB";
        svc.getState().tailOpacity = 1.0f;
        svc.getState().shadowColor = "#FFC0CB";
        buildAnimator();
        tick();

        System.out.println("  改前： glowPadding=" + intField(animator, "glowPadding")
                + "  fillColor=" + hex(colorField(panel, "fillColor"))
                + "  lifetime=" + lifetime());

        svc.getState().shadowBlurFactor = 0.6f;
        svc.getState().tailColor = "#00FF00";
        svc.getState().tailOpacity = 0.5f;
        svc.getState().shadowColor = "#00FF00";
        cfg.afterimageLifetime = 0.5f;

        svc.syncStateToConfig();   // 只做 S1
        tick();
        tick();                    // 多给一帧：证明它确实不会自愈

        int pad = intField(animator, "glowPadding");
        Color fill = colorField(panel, "fillColor");
        Color glow = colorField(panel, "glowColor");
        double life = lifetime();
        System.out.println("  跳过 S7： glowPadding=" + pad + "  fillColor=" + hex(fill)
                + "  glowColor=" + hex(glow) + "  lifetime=" + life);

        check("[跳过 S7] glowPadding 仍是 11（0.6 没被应用）", pad == 11, "实际 " + pad);
        check("[跳过 S7] fillColor 仍是 #FFC0CB", hex(fill).equals("#FFC0CB alpha=255"), hex(fill));
        check("[跳过 S7] glowColor 仍是 #FFC0CB", hex(glow).equals("#FFC0CB alpha=255"), hex(glow));
        check("[跳过 S7] lifetime 仍是 0.22（不会自愈）", Math.abs(life - 0.22) < 1e-6, "实际 " + life);
    }

    // ==================== V4：只调 onModeChanged（不调 refreshGlowPadding）====================

    private static void v4_onModeChangedOnly() throws Exception {
        System.out.println();
        System.out.println("--- V4. 独立复核 captain 第 7 条：只调 onModeChanged() 能否刷到 glowPadding ---");
        System.out.println("  依据：CaretAnimator 慢路径在算 padding 之前有一条早退");
        System.out.println("        if (newCenterX == centerX && newCenterY == centerY && height == cursorHeight) return false;");
        System.out.println("        光标静止 ⇒ 恒成立 ⇒ forceResync 单独不足以到达 :1018-1019");

        int padBefore = intField(animator, "glowPadding");
        Color fillBefore = colorField(panel, "fillColor");
        double lifeBefore = lifetime();
        System.out.println("  调用前： glowPadding=" + padBefore + " fillColor=" + hex(fillBefore)
                + " lifetime=" + lifeBefore);

        animator.onModeChanged();     // 只做 S7 的上半段
        tick();
        tick();

        int pad = intField(animator, "glowPadding");
        Color fill = colorField(panel, "fillColor");
        double life = lifetime();
        System.out.println("  调用后： glowPadding=" + pad + " fillColor=" + hex(fill) + " lifetime=" + life);
        System.out.println("  forceResync（tick 后） = " + boolField(animator, "forceResync"));

        check("V4-1 onModeChanged 确实刷新了颜色（fillColor 变 #00FF00/0.5）",
                hex(fill).equals("#00FF00 alpha=128"), hex(fill));
        check("V4-2 onModeChanged 确实刷新了 lifetime（0.22 → 0.5）",
                Math.abs(life - 0.5) < 1e-9, "实际 " + life);
        check("V4-3 onModeChanged 【不能】刷新 glowPadding（仍是 11，证明 refreshGlowPadding 不可省）",
                pad == 11, "实际 " + pad + "（期望 11）");

        // 再补 refreshGlowPadding() —— 单独就能修好（包内方法，走反射）
        Method rgp = CaretAnimator.class.getDeclaredMethod("refreshGlowPadding");
        rgp.setAccessible(true);
        rgp.invoke(animator);
        tick();
        check("V4-4 补调用 refreshGlowPadding() 后 glowPadding 变 24（= 11→24，0.6 的目标值）",
                intField(animator, "glowPadding") == 24, "实际 " + intField(animator, "glowPadding"));
    }

    // ==================== V5：幂等 ====================

    private static void v5_idempotent() throws Exception {
        System.out.println();
        System.out.println("--- V5. applyConfig 幂等：连调两次，第二次 S2–S6 全跳过 ---");
        // 先让状态settle
        NeovideCaretManager.applyConfig();
        tick();

        int savesBefore = app.saves;
        int padBefore = intField(animator, "glowPadding");
        NeovideCaretManager.applyConfig();
        int savesAfterFirst = app.saves;
        NeovideCaretManager.applyConfig();
        int savesAfterSecond = app.saves;

        System.out.println("  saveSettings 调用数：前=" + savesBefore + " 第一次后=" + savesAfterFirst
                + " 第二次后=" + savesAfterSecond);
        check("V5-1 第二次 applyConfig 没有触发 setter（无新增 saveSettings）",
                savesAfterSecond == savesAfterFirst, "第二次新增 " + (savesAfterSecond - savesAfterFirst));
        check("V5-2 第二次调用后 glowPadding 不变（值幂等）",
                intField(animator, "glowPadding") == padBefore, padBefore + " → " + intField(animator, "glowPadding"));
    }

    // ==================== V6：空集合 / 已 dispose 的鲁棒性 ====================

    private static void v6_robustness() throws Exception {
        System.out.println();
        System.out.println("--- V6. ANIMATORS 为空 / 编辑器已 dispose 时 applyConfig 不得抛异常 ---");

        Map<Object, Object> map = animatorsMap();
        Object saved = map.remove(lastEditor);
        check("V6-0 已把唯一 animator 从 ANIMATORS 移除（集合为空）", map.isEmpty(), "size=" + map.size());

        String err1 = null;
        try {
            NeovideCaretManager.applyConfig();
        } catch (Throwable t) {
            err1 = t.toString();
        }
        check("V6-1 ANIMATORS 为空时 applyConfig 不抛异常", err1 == null, "异常 = " + err1);

        // 放回去，然后 dispose 掉，再 apply
        if (saved != null) {
            map.put(lastEditor, saved);
        }
        animator.dispose();
        Method isDisposed = CaretAnimator.class.getDeclaredMethod("isDisposed");
        isDisposed.setAccessible(true);
        Object disposedFlag = isDisposed.invoke(animator);
        check("V6-2 dispose 后 isDisposed() = true", Boolean.TRUE.equals(disposedFlag), String.valueOf(disposedFlag));
        String err2 = null;
        try {
            NeovideCaretManager.applyConfig();
        } catch (Throwable t) {
            err2 = t.toString();
        }
        check("V6-3 编辑器已 dispose 时 applyConfig 不抛异常（内部 per-animator try 兜底）",
                err2 == null, "异常 = " + err2);
        map.clear();
    }

    // ==================== V7：派生公式在真实 CONFIG 上 ====================

    private static void v7_derivedFactors() {
        System.out.println();
        System.out.println("--- V7. syncStateToConfig 重建的 trailFactors 逐位复现 ---");
        NeovideConfig cfg = NeovideCaretManager.getConfig();
        svc.getState().stretchRatioK = 4.5f;
        svc.syncStateToConfig();
        // 【0.9.91 改方向：期望值随斜度（slopeTicks）默认值一起变，断言没删只改期望（纪律二.6）】
        //
        // 原来期望 {1.0, 0.72, 0.42, 0.22} —— 那是 slope=100（完全斜）的产物。
        // 0.9.91 起 State 默认 slope=0（纯矩形，不斜），派生结果变成
        // {0.86, 0.86, 0.32, 0.32}：同侧两角等速 → 左右边缘垂直 → 矩形，
        // 但 rank0 与 rank2 之间仍有差 → **拉伸保留**。
        //
        // 两个数组的差别正是「斜 vs 不斜」的数值体现：
        //   1.00/0.72/0.42/0.22  四档互不相同 → 平行四边形
        //   0.86/0.86/0.32/0.32  两两相等     → 规整矩形
        //
        // 实测（2026-10-27，deriveTrailFactors(4.5f, 0f, 0f)）：
        //   raw = 0x3F5C28F6 / 0x3F5C28F6 / 0x3EA3D70A / 0x3EA3D70A
        //   与字面量 0.86f / 0.32f 逐位相同，所以下面的 raw 比较可靠。
        float[] expect = {0.86f, 0.86f, 0.32f, 0.32f};
        boolean same = cfg.trailFactors.length == 4;
        for (int i = 0; i < 4; i++) {
            same &= Float.floatToRawIntBits(cfg.trailFactors[i]) == Float.floatToRawIntBits(expect[i]);
        }
        check("V7-1 拉伸强度 k=4.5、斜度=0 ⇒ CONFIG.trailFactors 逐位 == {0.86f,0.86f,0.32f,0.32f}（纯矩形）",
                same, arr(cfg.trailFactors));
        float[] first = cfg.trailFactors;
        svc.syncStateToConfig();
        float[] second = cfg.trailFactors;
        check("V7-2 每次 syncStateToConfig 都重建新数组（不是复用同一引用）",
                first != second && first.length == 4 && second.length == 4,
                "identity 相同? " + (first == second));
    }

    // ==================== 假平台 / 假编辑器 ====================

    static final class FakeApp {
        FakeProps pc;
        int saves;
        boolean throwOnSave;
    }

    private static void installFakeApp() {
        Application proxy = (Application) Proxy.newProxyInstance(
                VerifyApplyConfig.class.getClassLoader(),
                new Class<?>[]{Application.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getService":
                            if (args != null && args.length == 1) {
                                Class<?> c = (Class<?>) args[0];
                                if (c == PropertiesComponent.class) {
                                    return app.pc;
                                }
                                if (c == NeovideCursorSettings.class) {
                                    return svc;
                                }
                            }
                            return null;
                        case "isDispatchThread":
                            return true;
                        case "saveSettings":
                            app.saves++;
                            if (app.throwOnSave) {
                                throw new IllegalStateException("模拟落盘失败");
                            }
                            return null;
                        case "invokeLater":
                            ((Runnable) args[0]).run();
                            return null;
                        case "toString":
                            return "FakeApp";
                        case "hashCode":
                            return 1;
                        case "equals":
                            return p == args[0];
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });
        ApplicationManager.setApplication(proxy, () -> { });
    }

    private static void buildAnimator() throws Exception {
        CaretModel model = (CaretModel) Proxy.newProxyInstance(
                VerifyApplyConfig.class.getClassLoader(),
                new Class<?>[]{CaretModel.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getVisualPosition":
                            return new VisualPosition(vpLine, vpColumn);
                        case "getOffset":
                            return caretOffset;
                        case "getAllCarets":
                            return new ArrayList<>();
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
                VerifyApplyConfig.class.getClassLoader(),
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
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });

        Editor editor = (Editor) Proxy.newProxyInstance(
                VerifyApplyConfig.class.getClassLoader(),
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
                        case "getLineHeight":
                            return lineHeight;
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

        mockCaret = Proxy.newProxyInstance(
                VerifyApplyConfig.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.editor.Caret.class},
                (proxy, m, margs) -> {
                    switch (m.getName()) {
                        case "getVisualPosition":
                            return new VisualPosition(vpLine, vpColumn);
                        case "getOffset":
                            return caretOffset;
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });

        Constructor<?> ctor = CaretAnimator.class.getDeclaredConstructor(Editor.class, NeovideConfig.class);
        ctor.setAccessible(true);
        animator = (CaretAnimator) ctor.newInstance(editor, NeovideCaretManager.getConfig());
        panel = (CaretTrailPanel) fieldValue(animator, "panel");
        lastEditor = editor;

        refreshTarget = CaretAnimator.class.getDeclaredMethod("refreshTarget");
        refreshTarget.setAccessible(true);

        // 【多光标改造适配 2026-10-23】
        // initialized / lastCaretOffset 已从 CaretAnimator 下沉到 SpringTrail。
        // 先跑一帧把主光标拖尾建起来，再把这两个值注入到那份拖尾上。
        refreshTarget.invoke(animator);
        Object mainTrail = mainTrail();
        if (mainTrail != null) {
            trailField(mainTrail, "initialized").setBoolean(mainTrail, true);
            trailField(mainTrail, "lastCaretOffset").setInt(mainTrail, caretOffset);
        }

        // 【多光标改造适配 2026-10-23 补充】
        // refreshGlowPadding() 读的是 cursorWidth/cursorHeight 两个字段。
        // 改造后它们只在 refreshTarget 里被赋值，而 mock 的 getLineHeight()
        // 可能返回 0 —— 那样 padding 永远算不出正确值。
        // 这里显式注入探针期望的尺寸（与 V1 的 11 初值一致：0.2*32+4=11）。
        field(animator, "cursorWidth").setDouble(animator, 2);
        field(animator, "cursorHeight").setDouble(animator, 32);

        Map<Object, Object> map = animatorsMap();
        map.clear();
        map.put(editor, animator);
    }

    /** 主光标对应的 SpringTrail（多光标改造后单光标状态都在它身上） */
    private static Object mainTrail() throws Exception {
        Object t = fieldValue(animator, "cameraTrail");
        if (t == null) {
            Object act = fieldValue(animator, "activeTrails");
            if (act instanceof java.util.List<?> list && !list.isEmpty()) {
                t = list.get(0);
            }
        }
        return t;
    }

    private static Field trailField(Object trail, String name) throws Exception {
        Field f = trail.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> animatorsMap() throws Exception {
        Field f = NeovideCaretManager.class.getDeclaredField("ANIMATORS");
        f.setAccessible(true);
        return (Map<Object, Object>) f.get(null);
    }

    private static void tick() throws Exception {
        refreshTarget.invoke(animator);
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

    // ==================== 工具 ====================

    private static double lifetime() throws Exception {
        Object trail = fieldValue(animator, "afterimageTrail");
        Method m = AfterimageTrail.class.getMethod("getLifetime");
        return (Double) m.invoke(trail);
    }

    private static Field field(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object fieldValue(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }

    private static int intField(Object target, String name) throws Exception {
        return (Integer) fieldValue(target, name);
    }

    private static double dblField(Object target, String name) throws Exception {
        return (Double) fieldValue(target, name);
    }

    private static boolean boolField(Object target, String name) throws Exception {
        return (Boolean) fieldValue(target, name);
    }

    private static Color colorField(Object target, String name) throws Exception {
        return (Color) fieldValue(target, name);
    }

    private static String hex(Color c) {
        if (c == null) {
            return "null";
        }
        return String.format("#%02X%02X%02X alpha=%d", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    private static String arr(float[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(a[i]);
        }
        return sb.append("]").toString();
    }

    private static Object mockCaret;

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
