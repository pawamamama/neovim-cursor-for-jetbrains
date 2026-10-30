import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * V-REVIEW-FIXES：代码审查抓到的 4 条 bug + 平滑移出预设，逐条锁住。
 *
 * <p><b>S-1 输入平滑不参与预设</b>：用户明确「平滑一直不参与预设」。
 * 0.9.67 我误读了「其他持久化 比如平滑关闭」，错误地把它放进预设域。
 *
 * <p><b>R-1 RenderScheduler.thread 必须是 volatile</b>：unregister/wakeUp 在无锁
 * 状态下读它，非 volatile 时可能读到陈旧值 → 跳过 interrupt → 多等 600ms。
 *
 * <p><b>R-2 RenderScheduler.loop 必须有 try/catch</b>：否则线程死后 thread 仍非 null，
 * 后续 register 全去 interrupt 死线程 → 所有动画永久停摆且无信号。
 *
 * <p><b>A-1 refreshTarget 的 catch 不能静默，且失败日志必须真的按节流输出</b>：
 * 原来只有一行注释（确定性异常 → 拖尾永久冻结且零日志）；0.9.73 加了 warn，
 * 但 2026-10-30 审计（U-M-4）发现「限流」是假动作 —— {@code refreshFrameGap}
 * 全仓没有一处 {@code +1}、恒为 0，{@code >= 1000} 恒假，所以只会打出第一行。
 * 现在断言的是生产判据 {@code shouldLogFailure} 的行为，不是源码里有没有那个 token。
 *
 * <p><b>M-1 syncConfigToStateAndPersist 必须有初始化守卫</b>：
 * CONFIG 未从 State 灌满时回写 = 用编译期默认值覆盖用户设置并落盘。
 */
public final class VReviewFixes {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-REVIEW-FIXES：审查发现的 bug 逐条锁定 ===");
        V0Env.install();

        // ---------- S-1：输入平滑不参与预设 ----------
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.typingSmoothness = 0.73f;                       // 用户调好的值
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            NeovideCursorConfigurable.applyAnimPresetFields(
                    s, CursorPresets.ANIM_PRESETS[i]);
        }
        check("S-1 切遍所有预设后 typingSmoothness 保持不变（平滑不参与预设）",
                Math.abs(s.typingSmoothness - 0.73f) < 1e-4f,
                "切完后 = " + s.typingSmoothness + "（期望 0.73）");

        // ---------- R-1：thread 必须 volatile ----------
        Field tf = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.RenderScheduler")
                .getDeclaredField("thread");
        check("R-1 RenderScheduler.thread 声明为 volatile",
                Modifier.isVolatile(tf.getModifiers()),
                Modifier.toString(tf.getModifiers()));

        // ---------- R-2 / A-1 / M-1：源码里必须有相应结构 ----------
        String rs = readSrc("ui/RenderScheduler.java");
        check("R-2 loop() 内有 try/catch 包裹（线程异常不会静默死亡）",
                rs.contains("catch (Throwable t)") && rs.contains("consecutiveFailures"),
                rs.contains("consecutiveFailures") ? "已加" : "缺失");
        check("R-2b 连续失败后会置 thread=null 自愈",
                rs.contains("thread = null") && rs.contains("重建"),
                rs.contains("重建") ? "已加" : "缺失");

        String ca = readSrc("ui/CaretAnimator.java");
        // ---------- A-1：契约变更（弱 → 强），非缺陷 ----------
        // 旧断言：ca.contains("refreshFailureCount") && ca.contains("refreshTarget 抛异常")
        //   —— 只要 token 在就 PASS。而 U-M-4 实测：refreshFrameGap 全仓没有一处 +1，
        //      恒为 0，`>= 1000` 恒假 —— 字段名在、日志也在，限流却是假的，旧断言照样绿。
        // 新断言：直接调用生产判据 shouldLogFailure(count)，断言「第几次该打日志」。
        //   语义 ⊇ 旧断言（节流行为正确隐含 catch 里确实有日志），
        //   那段日志文本由紧随其后的 A-1b 继续守住。
        check("A-1 失败日志节流行为：首次必打，之后每 1000 次一次（U-M-4）",
                throttleBehaviorOk(),
                "1/1001/2001 应打；2/1000/2000 不应打");
        check("A-1b refreshTarget 的 catch 仍有日志文本（旧断言语义保留）",
                ca.contains("refreshTarget 抛异常"),
                ca.contains("refreshTarget 抛异常") ? "已加" : "缺失");
        // A-1c（MEDIUM-3）：补上「计数判据真被用上」这一层。
        // 反例：把 refreshFailureCount 的自增与判据删掉、catch 改成无条件 LOG.warn
        // （精确回退成 U-M-4 那个洪水形态）—— A-1 的谓词行为仍对、A-1b 的文本仍在，
        // 两条都绿，而旧断言会红。这条把那个缺口补上，保证新断言语义 ⊇ 旧断言。
        check("A-1c catch 用的是计数判据，不是无条件打日志（防回退成洪水）",
                ca.contains("shouldLogFailure(refreshFailureCount)")
                        && ca.contains("refreshFailureCount++"),
                ca.contains("shouldLogFailure(refreshFailureCount)") ? "已用计数判据" : "缺失");

        String mgr = readSrc("ui/NeovideCaretManager.java");
        check("M-1 syncConfigToStateAndPersist 有初始化守卫",
                mgr.contains("CONFIG 尚未从 State 同步"),
                mgr.contains("CONFIG 尚未从 State 同步") ? "已加" : "缺失");

        // ---------- M-2：守卫真的会先拉再写（行为验证）----------
        // 直接调 syncConfigToStateAndPersist 需要完整平台环境，这里只验证
        // 「settingsSynced 为 false 时 syncStateToConfig 会被调用」这条结构。
        check("M-2 守卫里调用了 svc.syncStateToConfig() 再回写",
                mgr.contains("svc.syncStateToConfig();") || mgr.contains("syncStateToConfig();"),
                "已加");

        // ============ 2026-10-30 审计第 2 批（第 1 轮）：调度面与 tick 链 ============
        //
        // A-2..A-6 这五条是**结构断言**，不是行为断言 —— 它们守的路径都需要「让平台/日志子系统
        // 真的坏掉」才能触发（日志工厂被摘、调度线程连续抛 3 次、invokeLater 抛），
        // 离线探针造不出来。行为验证标注「需实机」，见 plans/audit-fix-batch2-round1.md §七。
        // 结构断言的价值：这些修复一旦被后人删掉，token 立刻消失 → 必红。

        // A-2（U-M-25）：runTick 必须自己捕获 tick 异常，并与 refreshTarget 用同一判据节流。
        // 原来只有 finally 没有 catch —— 确定性异常时 125~144 次/秒堆栈外抛，插件自己零线索。
        check("A-2 runTick 捕获 tick 异常、按同一判据节流，且成功即归零（U-M-25 / M-2）",
                ca.contains("tickFailureCount")
                        && ca.contains("shouldLogFailure(tickFailureCount)")
                        && ca.contains("tickFailureCount = 0"),
                ca.contains("tickFailureCount") ? "已加" : "缺失");

        // A-3（U-H-3）：调度线程置 null 后必须有人重建 —— wakeUp 里检测 thread==null，
        // 且限定集合非空（否则会留下一条空转线程）。重建与 register/loop 共用同一把类锁。
        check("A-3 wakeUp 在 thread==null 时就地重建（限集合非空，U-H-3）",
                rs.contains("thread == null && !ANIMATORS.isEmpty()"),
                rs.contains("thread == null && !ANIMATORS.isEmpty()") ? "已加" : "缺失");

        // A-4（U-H-4）：catch 体自己也要有人守护 —— 线程装 UEH，
        // 否则「处理异常的代码抛出」会让线程死亡且 thread 仍非 null（连重建入口都到不了）。
        // 次数判据：1 处定义 + 2 处调用（register / wakeUp）。只判 token 存在的话，
        // 把两条创建路径的调用都删掉、只留方法定义，断言仍然绿（LOW-1）。
        int uehHookCount = rs.split("installUncaughtHandler", -1).length - 1;
        check("A-4 调度线程装了 UEH，且两条创建路径都装上（U-H-4）",
                rs.contains("setUncaughtExceptionHandler") && uehHookCount == 3,
                "setUncaughtExceptionHandler=" + rs.contains("setUncaughtExceptionHandler")
                        + "，installUncaughtHandler 出现 " + uehHookCount + " 次（期望 3 = 1 定义 + 2 调用）");

        // A-5（U-M-5）：dispatchDue 的 catch 不许回滚 posted ——
        // 它的语义是「本轮至少投递了一个」，某个 animator 失败不等于本轮什么都没投。
        // 判据用「token 出现次数」而不是 contains：`boolean posted = false;` 本身也含这个子串。
        int postedFalseCount = rs.split("posted = false", -1).length - 1;
        check("A-5 dispatchDue 的 catch 不再回滚 posted（U-M-5）",
                postedFalseCount == 1,
                "\"posted = false\" 出现 " + postedFalseCount + " 次（期望 1 = 仅初始化那处）");

        // A-6（H-1，java-reviewer 复查发现）：连续失败计数必须是 loop() 的**局部变量**。
        // 原来是 static 字段 → 重建出来的新线程继承上一代的 3 次失败 →
        // 遇到一次偶发异常就再次 ≥3 退出 =「自愈」后只剩一条命。
        // 类锁的 monitor release/acquire 保证新线程一定读到旧值，不是可见性侥幸能躲的。
        check("A-6 连续失败计数是 loop 内局部变量（H-1）",
                rs.contains("int consecutiveFailures = 0;")
                        && !rs.contains("private static int consecutiveFailures"),
                rs.contains("int consecutiveFailures = 0;") ? "已改为局部变量" : "缺失");

        // A-7（MEDIUM-1，code-reviewer 复查发现）：`posted` 必须只在**投递成功后**置位。
        // 它同时是 loop() 的退避开关（`if (!dispatchDue()) sleep(1ms)`）：
        // 若写成"尝试过就 true"，invokeLater 持续抛时会跳过唯一退避 → 100% CPU 紧循环。
        int invokeIdx = rs.indexOf("SwingUtilities.invokeLater(animator::runTick)");
        int postedTrueIdx = rs.indexOf("posted = true;");
        check("A-7 posted 只在 invokeLater 成功之后置位（MEDIUM-1）",
                invokeIdx >= 0 && postedTrueIdx > invokeIdx,
                "invokeLater 位置=" + invokeIdx + "，posted=true 位置=" + postedTrueIdx
                        + "（期望后者更靠后）");

        // A-8（MEDIUM-2，code-reviewer 复查发现）：refreshTarget 的三条早退分支
        // （快速路径 / IME skip / 无拖尾）也是正常成功帧，必须一并归零失败计数。
        // 原来只在慢路径末尾归零，导致「偶发异常 → 之后一直快速路径 → count 卡 1
        // → 下一次异常被限流静默」。
        check("A-8 refreshTarget 的成功早退也归零失败计数（MEDIUM-2）",
                ca.contains("boolean success = false;") && ca.contains("if (success)"),
                ca.contains("boolean success = false;") ? "已用 finally 统一归零" : "缺失");

        System.out.println();
        System.out.printf("=== V-REVIEW-FIXES 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-REVIEW-FIXES RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-REVIEW-FIXES RESULT: ALL PASS");
    }

    /**
     * 直接调用生产判据 {@code CaretAnimator.shouldLogFailure(long)}
     * （纪律四-G：探针必须调用生产方法，不许自己复刻一遍取模）。
     *
     * <p>契约：第 1 次失败必打，之后每 1000 次再打一次（1001、2001…）。
     * 方法不存在时返回 false —— 让 A-1 报成一条可读的 FAIL，
     * 而不是让 NoSuchMethodException 把整个探针崩掉。
     */
    private static boolean throttleBehaviorOk() {
        try {
            Method m = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator")
                    .getDeclaredMethod("shouldLogFailure", long.class);
            m.setAccessible(true);
            return ((Boolean) m.invoke(null, 1L))
                    && !((Boolean) m.invoke(null, 2L))
                    && !((Boolean) m.invoke(null, 1000L))
                    && ((Boolean) m.invoke(null, 1001L))
                    && !((Boolean) m.invoke(null, 2000L))
                    && ((Boolean) m.invoke(null, 2001L));
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readSrc(String rel) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/github/yyyolo7a79/neovidecursor/" + rel)),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}