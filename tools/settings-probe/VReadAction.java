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
 *   <li><b>R-11</b> 诊断采样帧（{@code diagFrame == true}）的整条 tick 链路零违规
 *       （U-H-2：{@code tickSpring} 里 {@code getAllCarets()} 曾未包 read action）；
 *       <b>R-11b</b> 且该帧确实到达了 {@code frameSummary} 的读取点（不是空转）；</li>
 *   <li><b>R-12</b> {@code dispose()} 摘 caret 监听器零违规
 *       （U-M-19：与注册侧口径必须一致）；</li>
 *   <li><b>R-13</b> {@code caretAdded} 只读一次 {@code getAllCarets}
 *       （U-M-9：一次取值、两处用）。</li>
 * </ul>
 *
 * <h2>2026-10-30 补漏（第 2 批审计 U-H-2 / U-M-19）</h2>
 *
 * <p>本探针原先给 {@code getAllCarets} / {@code removeCaretListener} 都挂了 guard，
 * 但<b>只驱动 refreshTarget 系</b>，从不调 {@code runTick()} / {@code dispose()} ——
 * 两条真实缺口因此在 1035 PASS 下静默存在。R-11..R-13 补的就是这个驱动面。
 * 与「假通过」的区别：桩没变，变的是<b>真的把生产路径跑起来</b>。
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

    /**
     * {@code getAllCarets()} 的调用次数 —— 供两条新契约使用：
     * <ul>
     *   <li><b>R-11b</b>：证明诊断采样帧的 tick 链路<b>确实走到了</b> {@code frameSummary}
     *       的参数求值处，而不是因为别处的早退而"碰巧没读"（四-G 假通过 A：
     *       探针没覆盖到目标路径却全绿）；</li>
     *   <li><b>R-13</b>：锁住 U-M-9 的新契约「一次取值、两处用」——原来
     *       {@code caretAdded} 调两次 {@code getAllCarets()}，两次返回值必然相同
     *       （都在事件之后读），白付一次整表复制 + 排序（java-reviewer javap 实证内部 {@code List.sort}）。</li>
     * </ul>
     */
    private static int getAllCaretsCalls = 0;

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
    /** 调度线程投递到 EDT 的单帧任务入口（包私有）——R-11 用它驱动整条 tick 链。 */
    private static Method runTick;

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
        // 【MEDIUM-4】R-11 依赖「frameSeq == 15 命中采样」，而采样周期来自系统属性
        // `neovide.cursor.diag.sample`（CursorDiag 的 static final，类初始化时读）。
        // 若外层 JVM 带了这个 -D，探针写死的 14→15 就不再命中，R-11b 会报出
        // 与真正原因无关的"期望相差 1"。这里在类初始化之前钉死周期。
        System.setProperty("neovide.cursor.diag.sample", "15");
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

        // ================= R-13 / R-11 / R-11b / R-12：tick 链与 dispose =================
        //
        // 【为什么原来全绿却漏掉 U-H-2 / U-M-19】本探针给 getAllCarets 与
        // removeCaretListener 都挂了 guard，但驱动的只有 refreshTarget 系 ——
        // 从不调 runTick() / dispose()。于是两道真实缺口在 1035 PASS 下静默存在
        // （陷阱 4 假通过：探针不驱动被测路径）。
        //
        // 先把 animator 从调度器摘掉：构造期 register() 起的共享调度线程会并发投递
        // runTick，干扰下面按「单次调用」统计的计数（四-G-2 并发假红）。
        detachFromScheduler();

        // ---------- R-13：caretAdded 只读一次 getAllCarets（U-M-9 新契约） ----------
        getAllCaretsCalls = 0;
        animator.caretAdded(null);
        check("R-13 caretAdded 只读一次 getAllCarets（一次取值两处用）",
                getAllCaretsCalls == 1,
                "getAllCarets=" + getAllCaretsCalls + " 次（期望 1；2 次 = U-M-9 未修）");

        // ---------- R-11 / R-11b：诊断采样帧的 tick 链（U-H-2） ----------
        //
        // 用两帧做差分（M-1，否则 R-11b 会假通过）：
        //   ① 参考帧：frameSeq 置 0 → 慢路径 ++ 成 1 → shouldSample(1) 为假 → 诊断块不执行；
        //   ② 采样帧：frameSeq 置 14 → 慢路径 ++ 成 15 → 15 % 15 == 0 → 诊断块执行。
        //
        // 为什么不能只断言「getAllCarets > 0」：refreshTarget 的慢路径自己就会调
        // getAllCarets（CaretAnimator 的慢路径实现），诊断块一次不执行也满足 > 0 ——
        // 那正是本探针文件头引用的「四-G 假通过 A」。差分才能证明诊断块真的执行了。
        V0Env.svc.getState().diagLogging = true;

        // ① 参考帧（不命中采样）
        setField(animator, "frameSeq", 0L);
        vp = new VisualPosition(12, 1);
        getAllCaretsCalls = 0;
        try {
            runTick.invoke(animator);
        } catch (Throwable ignored) {
            // 参考帧不走诊断块，RED 阶段也不该抛
        }
        int baselineCalls = getAllCaretsCalls;

        // ② 采样帧（命中采样）
        setField(animator, "frameSeq", 14L);
        vp = new VisualPosition(13, 2);
        getAllCaretsCalls = 0;
        int vBeforeTick = violations;
        int callsBeforeTick = V0Env.readActionCalls;
        String tickError = null;
        try {
            runTick.invoke(animator);
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            tickError = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        int sampledCalls = getAllCaretsCalls;
        check("R-11 诊断采样帧的 tick 链路零违规（U-H-2）",
                tickError == null && violations == vBeforeTick,
                tickError != null
                        ? "tick 被桩判红：" + tickError
                        : "违规=" + (violations - vBeforeTick) + "  readAction="
                                + (V0Env.readActionCalls - callsBeforeTick));
        check("R-11b 采样帧比不采样帧多读一次 getAllCarets（证明诊断块真的执行了）",
                sampledCalls == baselineCalls + 1,
                "不采样帧=" + baselineCalls + " 次，采样帧=" + sampledCalls + " 次（期望相差 1）"
                        + (tickError != null ? "；采样帧异常=" + tickError : ""));
        V0Env.svc.getState().diagLogging = false;

        // ---------- R-12：dispose() 摘监听器（U-M-19） ----------
        // dispose 会真的把 animator 拆掉，必须放最后。
        int vBeforeDispose = violations;
        String disposeError = null;
        try {
            animator.dispose();
        } catch (Throwable t) {
            disposeError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        check("R-12 dispose() 摘 caret 监听器零违规（U-M-19）",
                disposeError == null && violations == vBeforeDispose,
                disposeError != null
                        ? "dispose 抛出：" + disposeError
                        : "违规=" + (violations - vBeforeDispose));

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
                            getAllCaretsCalls++;
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

        // 【R-11 的前提，加强而非放宽】{@code CaretAnimator.tick()} 里有
        // `if (!content.isShowing()) { … return; }` —— 真实编辑器不可见时确实该早退。
        // （不写行号：本项目的行号引用已经漂过多次，方法名足够定位。）
        // 但离线探针里的 JComponent 永远 isShowing()==false，于是 tick 永远走不到
        // tickSpring 的 frameSummary，R-11 会变成「目标路径一次没执行还报绿」
        // 的空转（四-G 假通过 A 的原样复刻）。这里显式声明"可见"，
        // 让 tick 走完整个绘制链。该 override 只影响 tick 路径：现有 R-1..R-10
        // 都不读 isShowing()，判据不变。
        JComponent content = new JComponent() {
            @Override
            public boolean isShowing() {
                return true;
            }
        };
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
        runTick = CaretAnimator.class.getDeclaredMethod("runTick");
        runTick.setAccessible(true);
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

    /**
     * 把 animator 从共享调度器摘掉，并**真正排空**调度线程与 EDT 队列，
     * 消除并发投递对按次计数的干扰（MEDIUM-4）。
     *
     * <p>只从集合移除是不够的：R-9 的 {@code caretAdded} 已经把 {@code nextDueNanos}
     * 置为「现在」，调度线程可能正在投递一次的途中。而 {@code V0Env} 只桩了
     * {@code Application.invokeLater}，桩不到 {@code SwingUtilities} 的静态方法 ——
     * 所以那次任务会真的进 EDT，在 R-13 / R-11 的计数窗口里改 {@code frameSeq}
     * 与 {@code getAllCaretsCalls}。
     */
    private static void detachFromScheduler() throws Exception {
        Class<?> rs = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.RenderScheduler");
        Method unreg = rs.getDeclaredMethod("unregister", CaretAnimator.class);
        unreg.setAccessible(true);
        unreg.invoke(null, animator);
        // 等调度线程真正退出：unregister 已 interrupt，loop 醒来会看到集合空并 return。
        Field tf = rs.getDeclaredField("thread");
        tf.setAccessible(true);
        Object t = tf.get(null);
        if (t instanceof Thread th) {
            th.join(1000);
        }
        // 排空 EDT：此前那次唤醒投递的 runTick 若还在队列里，会污染下面的计数。
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
    }

    /** 反射设置实例字段（用于把 frameSeq 推到诊断采样边界）。 */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
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
