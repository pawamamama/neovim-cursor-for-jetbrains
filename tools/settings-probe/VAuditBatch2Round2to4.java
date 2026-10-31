import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;
import com.github.yyyolo7a79.neovidecursor.ui.CaretSchemeWatcher;
import com.github.yyyolo7a79.neovidecursor.ui.CursorDiag;
import com.github.yyyolo7a79.neovidecursor.ui.EdtWatchdog;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.InlayModel;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme;
import com.intellij.openapi.project.Project;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * V-AUDIT-BATCH2-R2TO4 —— 审计第 2 批「第 2~4 轮」的守门探针。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>这一批的共同点与前两轮相同，但形态更靠底层：<b>失败发生在「基础设施自己」身上</b>。
 *
 * <ul>
 *   <li><b>E 批（原生光标与挂载链）</b>：挂载失败被空 catch 吞（覆盖层永远不出现、连日志都没有）；
 *       记账在副作用之前（一次抛出就把「已隐藏」的假账永久留下，双光标不可救）；
 *       主题回调的线程假设被 javap 否证却没有任何防御。</li>
 *   <li><b>F 批（生命周期）</b>：setter 链失败后仍宣称「完成」；没有任何收尾钩子。</li>
 *   <li><b>B 批（诊断基础设施）</b>：诊断日志自己的写盘失败是静默的 ——
 *       而 {@code [tick]/[geom]/[paint]/[boot]} 的唯一出口就是那个文件。</li>
 * </ul>
 *
 * <p>这些点在<b>正常路径上全对</b>，所以「跑一遍功能」永远测不出来。必须把失败精确地喂进去。
 *
 * <h2>关于 T-5（EDT 转投）的诚实边界</h2>
 *
 * <p>离线探针没有真正的 EDT。本探针<b>不</b>断言「转发后线程正确」——
 * 那在桩里恒真，属于 AC-008 禁止的恒真断言。它断言的是
 * <b>「非 EDT 时确实调用了 invokeLater」</b>（计数），
 * 线程正确性<b>需实机验证</b>，已写在该断言名里。
 */
public final class VAuditBatch2Round2to4 {

    private static int pass;
    private static int fail;

    private static final List<String> WARNS = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> INFOS = Collections.synchronizedList(new ArrayList<>());

    private static final VisualPosition VP = new VisualPosition(10, 2);
    private static final long STAMP = 42L;
    private static final int LINE_HEIGHT = 16;
    private static final int OFFSET = 200;

    /**
     * 探针注入：{@code CaretModel.addCaretListener} 在<b>登记之后</b>抛异常。
     *
     * <p><b>为什么选这个点</b>：审计要证的是「构造中段失败后，已经交给平台的监听器
     * 会不会被摘掉」。抛点必须落在 addCaretListener <b>之后</b>、构造器返回<b>之前</b> ——
     * 而中间那段 {@code refreshTarget} 自己 catch 了异常（不会传播）、inlay 那段也是
     * try/catch，唯一能稳定注入的位置就是 addCaretListener 本身。
     * 真实世界里「add 成功、后续某步失败」与这里等价：撤销侧对未注册的监听器无害。
     */
    private static boolean throwOnCaretAdd;

    private static int caretAdds;
    private static int caretRemoves;

    public static void main(String[] args) {
        // 【必须第一句】生产类的 static final Logger 在类首次被触达时就创建了。
        Logger.setFactory(new CapturingFactory());

        System.out.println("=== V-AUDIT-BATCH2-R2TO4：审计第 2 批第 2~4 轮（E/F/B）===");

        V0Env.install();
        V0Env.colors.scheme = V0Env.newScheme(new Color(0xA9, 0xB7, 0xC6));

        e1();
        e2();
        e3();
        e4();
        f1();
        f2();
        b1();
        b2();
        b3();
        b4();
        b5();
        b6();
        b7();
        b8();

        finish();
    }

    // ==================== E-1：U-H-5 / U-M-22 ====================

    private static void e1() {
        System.out.println();
        System.out.println("--- E-1 挂载失败必须留痕，且不许留下监听器残留 ---");

        // ---- T-0 桩自检：正常路径必须能挂载 ----
        throwOnCaretAdd = false;
        caretAdds = 0;
        caretRemoves = 0;
        Editor good = newEditor();
        boolean ok = NeovideCaretManager.installForProbe(good);
        check("T-0 桩自检：正常编辑器可挂载且注册了 1 个 caret 监听器"
                        + "（它红 = 桩坏了，下面两条都不算数）",
                ok && caretAdds == 1, "install=" + ok + " add=" + caretAdds);
        clearAnimators();

        // ---- T-1：构造中段抛异常 ----
        throwOnCaretAdd = true;
        caretAdds = 0;
        caretRemoves = 0;
        int m1 = mark();
        Editor bad = newEditor();
        boolean badOk = NeovideCaretManager.installForProbe(bad);
        check("T-1 构造抛异常：install 必须返回 false，且 warn 里点名 installEditor",
                !badOk && anyWarnSince(m1, "installEditor"),
                "install=" + badOk + " warn=" + brief(m1));

        // ---- T-2：失败后必须把它已经登记的东西摘掉 ----
        check("T-2 构造抛异常后不许留下 caret 监听器（add 必须等于 remove）",
                caretAdds == 1 && caretRemoves == 1,
                "add=" + caretAdds + " remove=" + caretRemoves);
        throwOnCaretAdd = false;
        clearAnimators();
    }

    // ==================== E-2：U-H-6 ====================

    private static void e2() {
        System.out.println();
        System.out.println("--- E-2 先写色、成功后才记账 ---");

        NeovideCaretManager.clearHiddenSchemesForProbe();
        NeovideCaretManager.clearSavedCaretColorForProbe();
        EditorColorsScheme bad = throwingScheme(new Color(0x11, 0x22, 0x33));
        V0Env.colors.scheme = bad;

        int before = NeovideCaretManager.hiddenSchemeCountForProbe();
        NeovideCaretManager.applyNativeCaretHidden(false);   // 非 force 路径
        int after = NeovideCaretManager.hiddenSchemeCountForProbe();

        check("T-3 setColor 抛异常时不得把该 scheme 记成「已隐藏」"
                        + "（记了账就会在早退判据上永久早退 → 双光标不可救）",
                after == before, "账本 " + before + " → " + after + "（期望不变）");

        V0Env.colors.scheme = V0Env.newScheme(new Color(0xA9, 0xB7, 0xC6));
        NeovideCaretManager.clearHiddenSchemesForProbe();
        NeovideCaretManager.clearSavedCaretColorForProbe();
    }

    // ==================== E-3：U-M-17 ====================

    private static void e3() {
        System.out.println();
        System.out.println("--- E-3 恢复连续失败要有终点，不能永远静默重试 ---");

        NeovideCaretManager.clearHiddenSchemesForProbe();
        NeovideCaretManager.clearSavedCaretColorForProbe();
        EditorColorsScheme bad = throwingScheme(Color.RED);
        NeovideCaretManager.putHiddenSchemeForProbe(bad, Color.RED);

        int m = mark();
        NeovideCaretManager.restoreNativeCaret();
        boolean keep1 = NeovideCaretManager.hiddenSchemeCountForProbe() == 1;
        NeovideCaretManager.restoreNativeCaret();
        boolean keep2 = NeovideCaretManager.hiddenSchemeCountForProbe() == 1;
        NeovideCaretManager.restoreNativeCaret();
        boolean gone = NeovideCaretManager.hiddenSchemeCountForProbe() == 0;

        check("T-6 同一 scheme 连续 3 次恢复失败 → 从账本移除并 warn 一次"
                        + "（前两次必须保留重试）",
                keep1 && keep2 && gone && anyWarnSince(m, "连续失败 3 次"),
                "第1次留=" + keep1 + " 第2次留=" + keep2 + " 第3次清=" + gone
                        + " warn=" + brief(m));

        NeovideCaretManager.clearHiddenSchemesForProbe();
        NeovideCaretManager.clearSavedCaretColorForProbe();
    }

    // ==================== E-4 / E-5：U-M-18 / U-M-20 / U-M-16 ====================

    private static void e4() {
        System.out.println();
        System.out.println("--- E-4/E-5 主题监听的失败出口与 EDT 转投 ---");

        // ---- T-4：装不上时必须有一条日志出口 ----
        CaretSchemeWatcher.getInstance().dispose();
        V0Env.throwOnBusConnect = true;
        int m = mark();
        boolean installed = NeovideCaretManager.ensureSchemeWatcherForProbe();
        V0Env.throwOnBusConnect = false;
        check("T-4 消息总线连接失败：返回 false 且留一条日志（不许静默）",
                !installed && anyWarnSince(m, "主题监听"),
                "install=" + installed + " warn=" + brief(m));
        CaretSchemeWatcher.getInstance().dispose();

        // ---- T-5：非 EDT 必须转投 ----
        CaretSchemeWatcher w = CaretSchemeWatcher.getInstance();

        V0Env.dispatchThreadOverride = true;
        int before = V0Env.invokeLaterCalls;
        w.handleSchemeChange(V0Env.newScheme(Color.WHITE));
        int onEdt = V0Env.invokeLaterCalls - before;

        V0Env.dispatchThreadOverride = false;
        before = V0Env.invokeLaterCalls;
        w.handleSchemeChange(V0Env.newScheme(Color.WHITE));
        int offEdt = V0Env.invokeLaterCalls - before;
        V0Env.dispatchThreadOverride = true;

        check("T-5 非 EDT 时必须转投 invokeLater、EDT 上不转投"
                        + "（只证「走了转投分支」；转发后的线程正确性【需实机验证】）",
                onEdt == 0 && offEdt == 1,
                "EDT 转投=" + onEdt + "(期望0) 非EDT 转投=" + offEdt + "(期望1)");

        // ---- T-5b：队列模式证明「是转发，不是当场执行」 ----
        V0Env.dispatchThreadOverride = false;
        V0Env.queueInvokeLater = true;
        EditorColorsScheme q = V0Env.newScheme(Color.WHITE);
        V0Env.colors.scheme = q;

        int qBefore = V0Env.invokeLaterCalls;
        w.handleSchemeChange(q);
        int queued = V0Env.INVOKE_LATER_QUEUE.size();
        boolean unchanged = q.getColor(
                com.intellij.openapi.editor.colors.EditorColors.CARET_COLOR).getAlpha() > 0;
        int drained = V0Env.drainInvokeLater();
        boolean changed = q.getColor(
                com.intellij.openapi.editor.colors.EditorColors.CARET_COLOR).getAlpha() == 0;

        V0Env.queueInvokeLater = false;
        V0Env.dispatchThreadOverride = true;
        V0Env.colors.scheme = V0Env.newScheme(new Color(0xA9, 0xB7, 0xC6));
        NeovideCaretManager.clearHiddenSchemesForProbe();
        NeovideCaretManager.clearSavedCaretColorForProbe();

        check("T-5b 非 EDT 时是「转发」而不是当场执行：调用返回时队列里恰有 1 个任务、"
                        + "光标色未变；drain 之后才变透明",
                V0Env.invokeLaterCalls - qBefore == 1 && queued == 1 && unchanged
                        && drained == 1 && changed,
                "转投=" + (V0Env.invokeLaterCalls - qBefore) + " 队列=" + queued
                        + " 调用后未变=" + unchanged + " drain=" + drained
                        + " 之后变=" + changed);

        CaretSchemeWatcher.getInstance().dispose();
        V0Env.throwOnBusConnect = false;
    }

    // ==================== F 批 ====================

    private static void f1() {
        System.out.println();
        System.out.println("--- F-1 applyConfig 的完成行必须反映失败数（U-M-24）---");

        String ok = NeovideCaretManager.applySummaryForProbe(0, 3);
        String bad = NeovideCaretManager.applySummaryForProbe(2, 3);

        check("T-7 失败数为 0 时文案不含「失败」；大于 0 时必须写明失败数与 animator 数"
                        + "（端到端的 setter 失败注入需实机，这里守的是唯一的决策点）",
                ok != null && !ok.contains("失败")
                        && bad != null && bad.contains("失败") && bad.contains("2")
                        && bad.contains("3"),
                "0 失败=[" + ok + "] 2 失败=[" + bad + "]");
    }

    private static void f2() {
        System.out.println();
        System.out.println("--- F-2 插件收尾钩子（U-M-21）---");

        throwOnCaretAdd = false;
        Editor e = newEditor();
        NeovideCaretManager.installForProbe(e);
        int before = animatorCount();

        boolean threw = false;
        try {
            NeovideCaretManager.shutdownCleanup();
            NeovideCaretManager.shutdownCleanup();   // 幂等：重复调用不许抛
        } catch (Throwable t) {
            threw = true;
        }
        check("T-8 shutdownCleanup：可重入、不抛、清空 ANIMATORS",
                !threw && before >= 1 && animatorCount() == 0,
                "收尾前=" + before + " 收尾后=" + animatorCount() + " 抛出=" + threw);

        check("T-8b 收尾必须挂在平台 Disposer 上（这是不需要改 plugin.xml 的唯一挂点）",
                sourceContains("ui/NeovideCaretManager.java", "Disposer.register"),
                "见源码");
    }

    // ==================== B 批 ====================

    private static java.nio.file.Path tmpDiag;

    private static void b1() {
        System.out.println();
        System.out.println("--- B-1 诊断文件落点与命名（U-M-26 / U-L-7）---");

        System.clearProperty("neovide.cursor.diag.file");
        String name = CursorDiag.diagFileForProbe().getFileName().toString();
        String pid = String.valueOf(ProcessHandle.current().pid());
        check("T-13 文件名必须带 PID（主仓 + worktree 双实例不再互相截断）"
                        + "且落在 log 目录（真实落点【需实机验证】）",
                name.contains(pid) && name.contains("neovide-cursor-diag"),
                "文件名=" + name + " pid=" + pid);
        check("T-13b 源码必须调用 getLogPath，而不是只调 getConfigPath",
                sourceContains("ui/CursorDiag.java", "getLogPath"),
                "见源码");
    }

    private static void b2() {
        System.out.println();
        System.out.println("--- B-2 写盘失败必须可观测（U-H-7 / U-M-12）---");

        prepareDiagFile();
        CursorDiag.resetFileForProbe();
        CursorDiag.setFailingWriterForProbe();
        V0Env.svc.getState().diagLogging = true;
        CursorDiag.invalidateEnabled();

        int id = 770001;
        int m = mark();
        for (int i = 0; i < 64; i++) {
            // 慢绘制 → 每次都落一行到文件（走 writeTrace，不刷 stdout）
            CursorDiag.paintTraceForProbe(id, 5, 5_000_000L, false, null);
        }
        check("T-16 写盘失败必须被 checkError 发现：句柄关闭置 null + idea.log 留一条",
                CursorDiag.fileWriterIsNullForProbe()
                        && anyWarnSince(m, "独立诊断文件写入失败"),
                "writer=null?" + CursorDiag.fileWriterIsNullForProbe() + " warn=" + brief(m));

        V0Env.svc.getState().diagLogging = false;
        CursorDiag.invalidateEnabled();
        CursorDiag.resetFileForProbe();

        // ---- T-17 / T-18：文件根本建不出来（复查 H-1 / H-2）----
        // 把落点指向一个**目录**：newBufferedWriter 会抛，走的正是「打不开」这条路。
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("v-audit2-dir");
            System.setProperty("neovide.cursor.diag.file", dir.toString());
        } catch (Throwable t) {
            System.out.println("[VAuditBatch2] 建临时目录失败: " + t);
        }
        CursorDiag.resetFileForProbe();
        V0Env.svc.getState().diagLogging = true;
        CursorDiag.invalidateEnabled();

        int m17 = mark();
        CursorDiag.paintTraceForProbe(790001, 5, 5_000_000L, false, null);
        check("T-17 诊断文件「打不开」必须留痕（旧代码会 emit 一行；改造成 writeFile 后不能变静默）",
                anyWarnSince(m17, "无法创建"), "warn=" + brief(m17));

        int m18 = mark();
        for (int i = 0; i < 4; i++) {
            CursorDiag.paintTraceForProbe(790010 + i, 5, 5_000_000L, false, null);
        }
        int firstWarns = countWarnsSince(m18, "无法创建");
        int giveUpWarns = countWarnsSince(m18, "已放弃");
        check("T-18 连续打不开必须收敛：只补一条「已放弃」，之后不再尝试、不再刷日志",
                firstWarns == 0 && giveUpWarns == 1,
                "新增「无法创建」=" + firstWarns + "（期望 0，首条已在 T-17）"
                        + " 新增「已放弃」=" + giveUpWarns + "（期望 1）");

        V0Env.svc.getState().diagLogging = false;
        CursorDiag.invalidateEnabled();
        prepareDiagFile();
        CursorDiag.resetFileForProbe();
    }

    private static void b3() {
        System.out.println();
        System.out.println("--- B-3 paintTrace 状态翻转才记（U-H-8）---");

        prepareDiagFile();
        CursorDiag.resetFileForProbe();
        V0Env.svc.getState().diagLogging = true;
        CursorDiag.invalidateEnabled();

        int id = 770002;
        CursorDiag.paintTraceForProbe(id, 0, 1000, true, null);    // 进入空绘制
        int n1 = countTag("[paint]");
        CursorDiag.paintTraceForProbe(id, 0, 1000, true, null);    // 仍然空 → 不记
        int n2 = countTag("[paint]");
        CursorDiag.paintTraceForProbe(id, 3, 1000, false, null);   // 恢复 → 记
        int n3 = countTag("[paint]");
        CursorDiag.paintTraceForProbe(id, 0, 1000, true, null);    // 再次进入 → 记
        int n4 = countTag("[paint]");

        check("T-9 空绘制：进入记 1 行、重复不记、恢复记 1 行、再次进入再记 1 行"
                        + "（终身只记一次会让第二次真故障无痕）",
                n1 == 1 && n2 == 1 && n3 == 2 && n4 == 3,
                "n1=" + n1 + "(期望1) n2=" + n2 + "(1) n3=" + n3 + "(2) n4=" + n4 + "(3)");

        V0Env.svc.getState().diagLogging = false;
        CursorDiag.invalidateEnabled();
        CursorDiag.resetFileForProbe();
    }

    private static void b4() {
        System.out.println();
        System.out.println("--- B-4 [font-scale]/[ime-skip] 必须进独立文件（U-L-8）---");

        prepareDiagFile();
        CursorDiag.resetFileForProbe();
        V0Env.svc.getState().diagLogging = true;
        CursorDiag.invalidateEnabled();

        CursorDiag.fontScaleResyncForProbe(16, 20);
        CursorDiag.imeSkipPickedForProbe(1, 10, 5, 8);
        String text = readDiagFile();

        check("T-15 两条日志必须出现在独立文件里（原来只走 LOG.info，回收日志的人看不到）",
                text.contains("[font-scale]") && text.contains("[ime-skip]"),
                "有 font-scale=" + text.contains("[font-scale]")
                        + " 有 ime-skip=" + text.contains("[ime-skip]"));

        V0Env.svc.getState().diagLogging = false;
        CursorDiag.invalidateEnabled();
        CursorDiag.resetFileForProbe();
    }

    private static void b5() {
        System.out.println();
        System.out.println("--- B-5 EdtWatchdog 空闲退出（U-M-15）---");

        System.setProperty("neovide.cursor.diag.watchdog.idleMs", "30000");
        long now = System.nanoTime();
        boolean expired = EdtWatchdog.idleExpiredForProbe(now - 31_000_000_000L, now);
        boolean alive = EdtWatchdog.idleExpiredForProbe(now - 1_000_000_000L, now);
        System.clearProperty("neovide.cursor.diag.watchdog.idleMs");

        check("T-12 空闲 31 秒必须判为「该退出」，空闲 1 秒不退出（阈值可注入）",
                expired && !alive, "31s=" + expired + " 1s=" + alive);
        check("T-12b loop 必须真的调用空闲判定（否则纯函数绿了、线程仍常驻）",
                sourceContains("ui/EdtWatchdog.java", "idleExpired(lastDumpNanos"),
                "见源码");
    }

    private static void b6() {
        System.out.println();
        System.out.println("--- B-6 诊断分桶随 dispose 回收（U-M-13）---");

        prepareDiagFile();
        V0Env.svc.getState().diagLogging = true;
        CursorDiag.invalidateEnabled();

        throwOnCaretAdd = false;
        Editor e = newEditor();
        NeovideCaretManager.installForProbe(e);
        CaretAnimator a = firstAnimator();
        int id = a == null ? -1 : System.identityHashCode(a);

        CursorDiag.trailSnapshotForProbe(id);
        CursorDiag.frameSummaryForProbe(id, 1, 1, 1, 3);
        CursorDiag.paintTraceForProbe(id, 5, 5_000_000L, false, null);

        boolean before = CursorDiag.tableContainsForProbe(id);
        if (a != null) {
            a.dispose();
        }
        boolean after = CursorDiag.tableContainsForProbe(id);

        check("T-10 用过的四张分桶在 dispose 后必须不再含该 id"
                        + "（否则下一个 animator 继承旧额度/旧「已报」标志）",
                before && !after, "dispose 前=" + before + " 后=" + after + " id=" + id);

        V0Env.svc.getState().diagLogging = false;
        CursorDiag.invalidateEnabled();
        clearAnimators();
    }

    private static void b7() {
        System.out.println();
        System.out.println("--- B-7 [frame] 每 animator 行数上限（U-M-27）---");

        prepareDiagFile();
        CursorDiag.resetFileForProbe();
        V0Env.svc.getState().diagLogging = true;
        CursorDiag.invalidateEnabled();

        int id = 770003;
        for (int i = 0; i < 5002; i++) {
            CursorDiag.frameSummaryForProbe(id, i, 1, 1, 3);
        }
        int lines = countTag("[frame]");

        check("T-14 单个 animator 的 [frame] 必须是 5000 行 + 1 条汇总，第 5002 次不再增长",
                lines == 5001, "[frame] 行数=" + lines + "（期望 5001）");

        V0Env.svc.getState().diagLogging = false;
        CursorDiag.invalidateEnabled();
        CursorDiag.resetFileForProbe();
    }

    private static void b8() {
        System.out.println();
        System.out.println("--- B-8 身份隔离与诊断开关的即时性（U-M-14 / U-M-11）---");

        check("T-11 CursorDiag 不得再有 static glowLayersNow（跨 animator 串台）；"
                        + "层数必须作为 frameSummary 的入参",
                !sourceContains("ui/CursorDiag.java", "static int glowLayersNow")
                        && sourceContains("ui/CursorDiag.java", "int glowLayers, boolean targetMoved"),
                "无 static 字段=" + !sourceContains("ui/CursorDiag.java", "static int glowLayersNow")
                        + " 签名含参数="
                        + sourceContains("ui/CursorDiag.java", "int glowLayers, boolean targetMoved"));

        // 缓存的是「服务引用」而不是布尔值：设置一变必须立即生效。
        V0Env.svc.getState().diagLogging = false;
        CursorDiag.invalidateEnabled();
        boolean off = CursorDiag.enabled();
        V0Env.svc.getState().diagLogging = true;
        boolean on = CursorDiag.enabled();   // 故意不调 invalidate
        check("T-11b enabled() 必须在设置变更后立即生效（缓存服务引用、不缓存布尔值；"
                        + "大量既有探针依赖这个语义）",
                !off && on, "关时=" + off + " 打开后=" + on);
        V0Env.svc.getState().diagLogging = false;
        CursorDiag.invalidateEnabled();
    }

    // ==================== B 批辅助 ====================

    private static void prepareDiagFile() {
        try {
            if (tmpDiag == null) {
                tmpDiag = java.nio.file.Files.createTempFile("v-audit2-diag", ".log");
                tmpDiag.toFile().deleteOnExit();
            }
            System.setProperty("neovide.cursor.diag.file", tmpDiag.toString());
        } catch (Throwable t) {
            System.out.println("[VAuditBatch2] 建临时诊断文件失败: " + t);
        }
    }

    private static String readDiagFile() {
        try {
            return tmpDiag != null && java.nio.file.Files.exists(tmpDiag)
                    ? new String(java.nio.file.Files.readAllBytes(tmpDiag),
                    java.nio.charset.StandardCharsets.UTF_8)
                    : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static int countTag(String tag) {
        int n = 0;
        for (String line : readDiagFile().split("\n", -1)) {
            if (line.startsWith(tag)) {
                n++;
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static CaretAnimator firstAnimator() {
        try {
            Field f = NeovideCaretManager.class.getDeclaredField("ANIMATORS");
            f.setAccessible(true);
            Map<Editor, CaretAnimator> map = (Map<Editor, CaretAnimator>) f.get(null);
            return map.isEmpty() ? null : map.values().iterator().next();
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 桩 ====================

    private static Editor newEditor() {
        final JComponent content = new JComponent() {
            @Override
            public boolean isShowing() {
                return true;
            }
        };
        content.setSize(900, 700);

        final Caret primary = (Caret) Proxy.newProxyInstance(
                VAuditBatch2Round2to4.class.getClassLoader(),
                new Class<?>[]{Caret.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getVisualPosition":
                            return VP;
                        case "getOffset":
                            return OFFSET;
                        case "getLogicalPosition":
                            return new LogicalPosition(VP.line, VP.column);
                        case "hasSelection":
                            return false;
                        case "getLeadSelectionPosition":
                            return VP;
                        case "getLeadSelectionOffset":
                            return OFFSET;
                        case "toString":
                            return "FakeCaret";
                        case "hashCode":
                            return 99;
                        case "equals":
                            return p == args[0];
                        default:
                            return V0Env.defaultValue(m.getReturnType());
                    }
                });

        final List<Caret> carets = new ArrayList<>();
        carets.add(primary);

        final CaretModel model = (CaretModel) Proxy.newProxyInstance(
                VAuditBatch2Round2to4.class.getClassLoader(),
                new Class<?>[]{CaretModel.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getVisualPosition":
                            return VP;
                        case "getOffset":
                            return OFFSET;
                        case "getPrimaryCaret":
                            return primary;
                        case "getAllCarets":
                            return carets;
                        case "addCaretListener":
                            caretAdds++;
                            if (throwOnCaretAdd) {
                                throw new IllegalStateException("injected: addCaretListener 失败");
                            }
                            return null;
                        case "removeCaretListener":
                            caretRemoves++;
                            return null;
                        case "toString":
                            return "FakeCaretModel";
                        default:
                            return V0Env.defaultValue(m.getReturnType());
                    }
                });

        final Document doc = (Document) Proxy.newProxyInstance(
                VAuditBatch2Round2to4.class.getClassLoader(),
                new Class<?>[]{Document.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getLineNumber":
                            return VP.line;
                        case "getLineStartOffset":
                            return 0;
                        case "getLineEndOffset":
                            return 100000;
                        case "getTextLength":
                            return 100000;
                        case "getModificationStamp":
                            return STAMP;
                        default:
                            return V0Env.defaultValue(m.getReturnType());
                    }
                });

        final InlayModel inlayModel = (InlayModel) Proxy.newProxyInstance(
                VAuditBatch2Round2to4.class.getClassLoader(),
                new Class<?>[]{InlayModel.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "addListener":
                            return null;
                        case "hasBlockElements":
                            return false;
                        case "getBlockElementsInRange":
                            return new ArrayList<>();
                        default:
                            return V0Env.defaultValue(m.getReturnType());
                    }
                });

        final SelectionModel selectionModel = (SelectionModel) Proxy.newProxyInstance(
                VAuditBatch2Round2to4.class.getClassLoader(),
                new Class<?>[]{SelectionModel.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getBlockSelectionStarts":
                            return new int[0];
                        case "getBlockSelectionEnds":
                            return new int[0];
                        case "hasSelection":
                            return false;
                        default:
                            return V0Env.defaultValue(m.getReturnType());
                    }
                });

        // install() 会因为「没有 project」直接跳过 —— 必须给一个非 null 的桩，
        // 否则 T-0 会在完全没进构造器的情况下「通过」（四-G 假通过 A）。
        final Project project = (Project) Proxy.newProxyInstance(
                VAuditBatch2Round2to4.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (p, m, args) -> {
                    if ("toString".equals(m.getName())) {
                        return "FakeProject";
                    }
                    if ("hashCode".equals(m.getName())) {
                        return 17;
                    }
                    if ("equals".equals(m.getName())) {
                        return p == args[0];
                    }
                    return V0Env.defaultValue(m.getReturnType());
                });

        return (Editor) Proxy.newProxyInstance(
                VAuditBatch2Round2to4.class.getClassLoader(),
                new Class<?>[]{Editor.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getCaretModel":
                            return model;
                        case "visualPositionToXY":
                            return new Point(200, 100 + VP.line * LINE_HEIGHT);
                        case "offsetToXY":
                            // x 与 visualPositionToXY 一致：否则命中 IME 几何判据冻结本帧
                            return new Point(200, 100 + VP.line * LINE_HEIGHT);
                        case "offsetToVisualPosition":
                            return VP;
                        case "getDocument":
                            return doc;
                        case "getSelectionModel":
                            return selectionModel;
                        case "getInlayModel":
                            return inlayModel;
                        case "getLineHeight":
                            return LINE_HEIGHT;
                        case "getContentComponent":
                            return content;
                        case "getEditorKind":
                            return EditorKind.MAIN_EDITOR;
                        case "getProject":
                            return project;
                        case "isDisposed":
                            return false;
                        case "toString":
                            return "FakeEditor";
                        case "hashCode":
                            return 7;
                        case "equals":
                            return p == args[0];
                        default:
                            return V0Env.defaultValue(m.getReturnType());
                    }
                });
    }

    /** 一个「每次都拒绝写色」的 scheme：getColor 正常，setColor 必抛。 */
    private static EditorColorsScheme throwingScheme(final Color caret) {
        return new DefaultColorsScheme() {
            @Override
            public Color getColor(ColorKey key) {
                return caret;
            }

            @Override
            public void setColor(ColorKey key, Color value) {
                throw new IllegalStateException("injected: scheme 只读");
            }

            @Override
            public Color getDefaultForeground() {
                return caret;
            }
        };
    }

    /** 清空 ANIMATORS 并 dispose，避免测试之间互相污染（表是静态的）。 */
    @SuppressWarnings("unchecked")
    private static void clearAnimators() {
        try {
            Field f = NeovideCaretManager.class.getDeclaredField("ANIMATORS");
            f.setAccessible(true);
            Map<Editor, CaretAnimator> map = (Map<Editor, CaretAnimator>) f.get(null);
            for (CaretAnimator a : new ArrayList<>(map.values())) {
                try {
                    a.dispose();
                } catch (Throwable ignored) {
                }
            }
            map.clear();
        } catch (Throwable t) {
            System.out.println("[VAuditBatch2] 清理 ANIMATORS 失败: " + t);
        }
    }

    /** 反射读 ANIMATORS.size()（探针用；生产不暴露这个数）。 */
    @SuppressWarnings("unchecked")
    private static int animatorCount() {
        try {
            Field f = NeovideCaretManager.class.getDeclaredField("ANIMATORS");
            f.setAccessible(true);
            Map<Editor, CaretAnimator> map = (Map<Editor, CaretAnimator>) f.get(null);
            return map.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 在仓库源码里找一段文本（结构断言）。找不到仓库根时打印原因并返回 false。 */
    private static boolean sourceContains(String relativePath, String needle) {
        try {
            String repo = System.getProperty("neovide.repo");
            if (repo == null || repo.isEmpty()) {
                System.out.println("[VAuditBatch2] 缺少 -Dneovide.repo，无法做源码断言");
                return false;
            }
            java.nio.file.Path p = java.nio.file.Paths.get(repo,
                    "src/main/java/com/github/yyyolo7a79/neovidecursor", relativePath);
            String text = new String(java.nio.file.Files.readAllBytes(p),
                    java.nio.charset.StandardCharsets.UTF_8);
            return text.contains(needle);
        } catch (Throwable t) {
            System.out.println("[VAuditBatch2] 读源码失败: " + t);
            return false;
        }
    }

    // ==================== 工具 ====================

    private static int mark() {
        return WARNS.size();
    }

    private static int countWarnsSince(int m, String needle) {
        int n = 0;
        for (int i = Math.min(m, WARNS.size()); i < WARNS.size(); i++) {
            if (WARNS.get(i).contains(needle)) {
                n++;
            }
        }
        return n;
    }

    private static boolean anyWarnSince(int m, String needle) {
        for (int i = Math.min(m, WARNS.size()); i < WARNS.size(); i++) {
            if (WARNS.get(i).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String brief(int m) {
        if (WARNS.size() <= m) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = m; i < WARNS.size(); i++) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            String w = WARNS.get(i);
            sb.append(w.length() > 70 ? w.substring(0, 70) + "…" : w);
        }
        return sb.toString();
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        }
    }

    private static void finish() {
        System.out.println();
        System.out.printf("=== VAUDIT-BATCH2-R2TO4 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("VAUDIT-BATCH2-R2TO4 RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("VAUDIT-BATCH2-R2TO4 RESULT: ALL PASS");
        System.exit(0);
    }

    // ==================== 日志捕获（同 VAuditBatch1）====================

    private static final class CapturingFactory implements Logger.Factory {
        @Override
        public Logger getLoggerInstance(String category) {
            return new Capturing(category);
        }
    }

    private static final class Capturing extends Logger {
        private final String category;

        Capturing(String category) {
            this.category = category;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public void debug(String message, Throwable t) {
        }

        @Override
        public void info(String message, Throwable t) {
            INFOS.add(String.valueOf(message));
        }

        @Override
        public void warn(String message, Throwable t) {
            WARNS.add(String.valueOf(message));
        }

        @Override
        public void error(String message, Throwable t, String... details) {
            WARNS.add(String.valueOf(message));
        }

        @Override
        public String toString() {
            return "Capturing(" + category + ")";
        }
    }

    private VAuditBatch2Round2to4() {
    }
}
