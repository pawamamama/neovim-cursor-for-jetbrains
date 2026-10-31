package com.github.yyyolo7a79.neovidecursor.ui;

import com.intellij.openapi.diagnostic.Logger;

/**
 * 光标诊断日志 —— 带「不变量自检」，让日志本身就能判断对错。
 *
 * <h2>设计思路：日志要有探针的覆盖性</h2>
 *
 * <p>第一版（0.9.28）只记录了「输入」和「部分输出」：
 * <pre>
 *   [caret] hasSel=true vp=29:4 lead=29:24 used=LEAD
 *   [draw]  corner0X=368.0 cx=369.0 cy=1045.5
 * </pre>
 * 结果排查时只能靠人眼比对数字 —— 看不出「哪一步断了」。
 *
 * <p>本版每条日志都附带<b>不变量检查结果</b>（OK / BAD 直接写出来），
 * 不用事后推理。覆盖的 7 条：
 * <table>
 *   <tr><th>#</th><th>不变量</th><th>违反说明</th></tr>
 *   <tr><td>I1</td><td>目标变化时角点应随之移动</td><td>拖尾冻结</td></tr>
 *   <tr><td>I2</td><td>静止时角点应收敛（偏移→0）</td><td>永不收敛</td></tr>
 *   <tr><td>I3</td><td>偏移不得超过合理上限</td><td>拖尾失控拉长</td></tr>
 *   <tr><td>I4</td><td>块选区各行目标 x 应一致</td><td>斜度不一致</td></tr>
 *   <tr><td>I5</td><td>活动拖尾数应等于 caret 数</td><td>某些光标没画</td></tr>
 *   <tr><td>I6</td><td>块选区方向应与选中数组一致</td><td>选错数组</td></tr>
 *   <tr><td>I7</td><td>普通光标跟随点应为 vp</td><td>跟随点错误</td></tr>
 * </table>
 *
 * <h2>开关</h2>
 * <p>设置面板「诊断日志」复选框，或 {@code -Dneovide.cursor.diag=true}。
 * 默认关闭，每 15 帧采样一次。
 */
public final class CursorDiag {

    private static final Logger LOG = Logger.getInstance("neovide-cursor-diag");

    /**
     * 诊断日志的独立文件路径。
     *
     * <p><b>为什么不用 idea.log</b>：混在 IDEA 主日志里，
     * 我需要用户复制粘贴才能看到 —— 慢且容易漏。
     * 写到独立文件后，我直接读盘即可。
     *
     * <p>位置（2026-10-30 起）：{@code <log>/neovide-cursor-diag-<pid>.log}
     * —— 走 {@code PathManager.getLogPath()}（不是 config：config 会被导出/漫游带走），
     * 文件名带 PID（双 IDE 实例不互相截断）。旧注释里的 config 路径已过期：
     * 也就是 {@code %APPDATA%JetBrainsIntelliJIdea2026.1
eovide-cursor-diag.log}
     */
    static java.nio.file.Path diagFile() {
        // ① 探针覆盖（唯一用途：把输出钉到临时文件，绝不碰用户的真实日志）
        String override = System.getProperty("neovide.cursor.diag.file");
        if (override != null && !override.isEmpty()) {
            return java.nio.file.Paths.get(override);
        }
        // ② 【U-L-7】文件名带 PID：主仓 + worktree 两个 IDE 并行调试时，
        //    固定名 + TRUNCATE_EXISTING 会互相截断对方的证据。
        String name = "neovide-cursor-diag-" + currentPid() + ".log";
        // ③ 【U-M-26】log 目录，不是 config 目录。
        //    理由：config 目录会被「Export Settings」/域漫游/备份代理整目录带走，
        //    而这份文件的内容是逐次按键的时间线。
        try {
            java.lang.reflect.Method m = com.intellij.openapi.application.PathManager.class
                    .getMethod("getLogPath");
            Object v = m.invoke(null);
            if (v instanceof String) {
                return java.nio.file.Paths.get((String) v, name);
            }
        } catch (Throwable ignored) {
            // 老平台没有 getLogPath 时退回 config 目录（仍然可用，只是位置不理想）
        }
        try {
            java.lang.reflect.Method m = com.intellij.openapi.application.PathManager.class
                    .getMethod("getConfigPath");
            Object v = m.invoke(null);
            if (v instanceof String) {
                return java.nio.file.Paths.get((String) v, name);
            }
        } catch (Throwable ignored) {
        }
        return java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), name);
    }

    private static long currentPid() {
        try {
            return ProcessHandle.current().pid();
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 探针入口：暴露最终落点（T-13 要断言文件名带 PID）。 */
    public static java.nio.file.Path diagFileForProbe() {
        return diagFile();
    }

    /**
     * 当前日志文件句柄（懒开）。
     *
     * <p><b>字段名与类型不能改</b>：{@code VTrailCap} 用反射按名字读写
     * {@code fileTried} / {@code fileWriter}（它把这俩换成内存 sink 来断言行内容）。
     */
    private static java.io.PrintWriter fileWriter;
    private static boolean fileTried;

    /** 【U-M-12】fileTried / fileWriter 的跨线程守卫：看门狗与 EDT 会并发创建。 */
    private static final Object FILE_LOCK = new Object();

    private static int linesSinceCheck;
    /** 【复查 H-1/H-2】「打不开」与「写失败」分开计数：它们的重试语义不同。 */
    private static int openFailures;
    private static int writeFailures;
    /** 是否已经成功打开过一次：首次 TRUNCATE（每次启动清空），重建 APPEND（不抹已采证据）。 */
    private static boolean everOpened;
    private static long bytesWritten;
    private static int totalLines;
    private static boolean totalCapLogged;
    private static boolean gaveUp;

    /** 每写这么多行检查一次写盘错误（每行 checkError 会把成本摊在 EDT 上）。 */
    private static final int FILE_CHECK_EVERY = 64;
    /** 【U-H-7】重建上限：设备坏了就不要无限重试。 */
    private static final int MAX_FILE_REOPEN = 3;
    /** 【U-M-27】单行上限：异常 message / 预设名可能很长。 */
    private static final int MAX_LINE_CHARS = 2000;
    /** 【U-M-27】总行数闸门。 */
    private static final int TOTAL_MAX_LINES = 200_000;
    /** 【U-M-27】文件字节上限。 */
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    /**
     * 写一行到独立文件。
     *
     * <p>【U-H-7】PrintWriter 的契约是吞 IOException、只置内部 error 标志 ——
     * 本类全文原来没有一处 {@code checkError()}，于是磁盘满 / 文件被独占打开 /
     * 轮转后句柄失效，全部表现为「后续写入静默丢弃」，而 {@code fileWriter != null}
     * 恒真、永不重试。用户把一份半截且无停更标记的文件发回来，结论建立在不存在的证据上。
     *
     * @return 需要打到 idea.log 的事件文案；{@code null} = 无事件。
     *         返回而不是直接打日志，是为了不在持 {@link #FILE_LOCK} 时进日志框架。
     */
    private static String writeFile(String line) {
        synchronized (FILE_LOCK) {
            String openEvent = ensureWriterLocked();
            if (fileWriter == null) {
                // 【复查 H-1】「文件根本建不出来」必须有一条出口 —— 旧代码在这里会 emit
                // 一行「无法写独立诊断文件」，改造成 writeFile 之后漏掉了，
                // 于是整类故障（目录不可写 / 磁盘只读 / 同名目录占位）变成零日志。
                return openEvent;
            }
            // 【U-M-27】总量闸门放在 writeFile 而不是 emit：writeTrace 是另一条出口，
            // 只有它们共同的这里才能覆盖全部。
            if (totalLines >= TOTAL_MAX_LINES || bytesWritten >= MAX_FILE_BYTES) {
                if (!totalCapLogged) {
                    totalCapLogged = true;
                    rawWriteLocked("[diag] 已达诊断输出上限（行 " + TOTAL_MAX_LINES
                            + " / 字节 " + MAX_FILE_BYTES + "），后续所有行不再输出");
                }
                return null;
            }
            totalLines++;
            String cut = line.length() > MAX_LINE_CHARS
                    ? line.substring(0, MAX_LINE_CHARS) + "…(截断)"
                    : line;
            fileWriter.println(cut);
            fileWriter.flush();       // 立即落盘，崩溃也能看到
            // 【复查 L-2】按 UTF-8 字节计，不是 char 数（中文 3 字节/字，否则上限虚高 3 倍）
            bytesWritten += cut.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 2L;
            if (++linesSinceCheck >= FILE_CHECK_EVERY) {
                linesSinceCheck = 0;
                if (fileWriter.checkError()) {
                    closeWriterLocked();
                    // 【复查 H-2】写失败单独计数、重建时不清零 —— 否则「打开成功、写入失败」
                    // （典型：磁盘满）会无限重建：每 64 行一条 warn，且每次重建 TRUNCATE
                    // 把已经采到的证据抹掉，最后只剩不到 64 行。
                    writeFailures++;
                    if (writeFailures == 1) {
                        return "neovide-cursor: 独立诊断文件写入失败，[tick] 类证据已中断"
                                + "（将尝试重建，最多 " + MAX_FILE_REOPEN + " 次）；"
                                + "独立文件不再可信，请以 idea.log 为准";
                    }
                    if (writeFailures >= MAX_FILE_REOPEN) {
                        gaveUp = true;
                        return "neovide-cursor: 独立诊断文件重建 " + MAX_FILE_REOPEN
                                + " 次仍失败，已放弃（此后独立文件不再有输出，idea.log 仍有）";
                    }
                }
            }
            return null;
        }
    }

    /** 不受总量闸门约束的底层写（只用于「已达上限」那一行本身，避免递归）。 */
    private static void rawWriteLocked(String line) {
        if (fileWriter == null) {
            return;
        }
        fileWriter.println(line);
        fileWriter.flush();
    }

    /**
     * 懒打开文件。
     *
     * @return 需要打到 idea.log 的事件文案（「打不开」「已放弃」）；{@code null} = 无事件。
     */
    private static String ensureWriterLocked() {
        if (fileWriter != null) {
            return null;
        }
        if (gaveUp) {
            return null;
        }
        try {
            java.nio.file.Path f = diagFile();
            if (f.getParent() != null) {
                java.nio.file.Files.createDirectories(f.getParent());
            }
            // 【每次启动清空】首次打开用 TRUNCATE（同一个 PID 被复用时仍然清空）；
            // 【复查 H-2】错误后的重建用 APPEND —— 否则每次重建都会把已经采到的证据截成 0。
            fileWriter = everOpened
                    ? new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(f,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND))
                    : new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(f,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
            fileTried = true;
            openFailures = 0;
            linesSinceCheck = 0;
            if (!everOpened) {
                totalLines = 0;
                totalCapLogged = false;
                bytesWritten = 0;
            }
            everOpened = true;
            return null;
        } catch (Throwable t) {
            fileTried = true;
            openFailures++;
            if (openFailures == 1) {
                // 【复查 H-1】这是「文件根本建不出来」，与「写失败」是两回事，都必须留痕。
                return "neovide-cursor: 独立诊断文件无法创建（" + diagFile() + "）："
                        + t.getClass().getSimpleName()
                        + "；诊断输出只剩 idea.log 一条通路";
            }
            if (openFailures >= MAX_FILE_REOPEN) {
                gaveUp = true;
                return "neovide-cursor: 独立诊断文件连续 " + MAX_FILE_REOPEN
                        + " 次创建失败，已放弃独立文件输出（idea.log 仍有）";
            }
            return null;
        }
    }

    private static void closeWriterLocked() {
        try {
            fileWriter.close();
        } catch (Throwable ignored) {
        }
        fileWriter = null;
    }

    /** 写文件并把「证据通道出问题」的事件转给 idea.log（锁外打）。 */
    private static void writeFileAndReport(String line) {
        String event = writeFile(line);
        if (event != null) {
            LOG.warn(event);
        }
    }

    /** 统一出口：同时写 idea.log 和独立文件 */
    private static void emit(String line) {
        LOG.info(line);
        writeFileAndReport(line);
    }

    static final String PROP_ENABLED = "neovide.cursor.diag";
    static final String PROP_SAMPLE = "neovide.cursor.diag.sample";

    private static final int SAMPLE_EVERY_FRAMES =
            parseInt(System.getProperty(PROP_SAMPLE), 15);

    /** 拖尾偏移的合理上限（像素）。超过说明物理失控。 */
    private static final double MAX_REASONABLE_OFFSET = 200.0;

    /** 「已到位」阈值（像素），与 DampedSpring 的静止判据一致。 */
    private static final double SETTLED_EPSILON = 0.5;

    private CursorDiag() {
    }

    /**
     * 【U-M-11】服务引用的缓存。
     *
     * <p><b>为什么只缓存服务引用、不缓存布尔值</b>：本方法每帧被调用 2~5 次，
     * 每次都要走 {@code ApplicationManager.getApplication().getService(...)}
     * （全局属性表锁 + 服务定位）。但大量既有探针（{@code VReadAction} / {@code VTrailCap} /
     * {@code VAuditBatch1}）都是<b>直接改 {@code state.diagLogging} 然后依赖立即生效</b> ——
     * 缓存布尔值会让它们全部变红，而且是「假红」（看起来像生产坏了）。
     * 缓存引用则两者兼得：服务定位一次，字段每次读。
     */
    private static volatile com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings cachedService;

    /** 上一次成功读到的值：服务不可用时沿用它，而不是一律 false（U-M-11）。 */
    private static volatile boolean lastKnownEnabled;

    /** 「服务未就绪」的 warn 只记一次（U-M-11 的限流）。 */
    private static volatile boolean lookupWarned;

    /** 当前是否启用：设置项或系统属性，任一为真。 */
    public static boolean enabled() {
        if (Boolean.getBoolean(PROP_ENABLED)) {
            lastKnownEnabled = true;
            return true;
        }
        com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings svc = cachedService;
        if (svc == null) {
            try {
                com.intellij.openapi.application.Application app =
                        com.intellij.openapi.application.ApplicationManager.getApplication();
                if (app == null) {
                    return lastKnownEnabled;
                }
                svc = app.getService(
                        com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings.class);
                cachedService = svc;
            } catch (Throwable t) {
                // 【U-M-11】原来这里 return false —— 恰好在「最需要证据」的时刻
                // 把整个诊断通道哑掉。改为沿用上次的值，并留一行（只一次）。
                if (!lookupWarned) {
                    lookupWarned = true;
                    LOG.warn("neovide-cursor: 诊断开关读取失败（服务未就绪），沿用上一次的值 "
                            + lastKnownEnabled + "；本条只记一次", t);
                }
                return lastKnownEnabled;
            }
        }
        if (svc == null) {
            return lastKnownEnabled;
        }
        boolean v;
        try {
            v = svc.getState().diagLogging;
        } catch (Throwable t) {
            return lastKnownEnabled;
        }
        boolean was = lastKnownEnabled;
        lastKnownEnabled = v;
        if (v && !was) {
            // 【U-M-15a】诊断从关变开时也把看门狗拉起来 —— 原来只有「新建 animator」一个启动源，
            // 用户在设置里勾上却不开新文件时永远抓不到卡顿。
            //
            // 【复查 M-1】必须包 try：enabled() 在 paintComponent / runTick / frameSummary 的
            // 入口上，看门狗创建失败（OOM / IllegalThreadState）绝不能变成一次绘制异常。
            try {
                EdtWatchdog.start();
            } catch (Throwable ignored) {
                // 看门狗起不来只是少了 [edt] 证据，不影响任何绘制行为
            }
        }
        return v;
    }

    /**
     * 非 EDT（看门狗线程）专用：只读 volatile 快照。
     *
     * <p>{@code diagLogging} 是普通字段，看门狗线程读它可能长期读到 false（U-M-11 的最后一面）。
     * 快照由 EDT 侧的 {@link #enabled()} 每次刷新，volatile 保证可见性。
     */
    public static boolean enabledForBackgroundThread() {
        return lastKnownEnabled;
    }

    /** 设置生效后失效服务缓存（唯一全局入口 {@code NeovideCaretManager.applyConfig} 调它）。 */
    public static void invalidateEnabled() {
        cachedService = null;
        lookupWarned = false;
    }

    static boolean shouldSample(long frameSeq) {
        return enabled() && (frameSeq % SAMPLE_EVERY_FRAMES) == 0;
    }

    /**
     * [trail] 日志的每 animator 行数上限。
     *
     * <p><b>为什么必须限量</b>：这条日志每 {@link #SAMPLE_EVERY_FRAMES} 帧打一行，
     * 而且原实现<b>没有任何截断</b> —— 8 光标场景实测 2 分钟产出 328KB
     * （见 {@code docs/NEXT-STEPS.md} §7.1-2）。长时间挂着 IDE，
     * 独立诊断文件会被撑到几十 MB，真正要看的那几行反而被埋掉。
     *
     * <p><b>为什么是 5000</b>：与 {@link #TRACE_MAX_LINES} 取同一个数。
     * 同一份诊断文件里两种行不该有两种口径 —— 否则「日志怎么被截断的」
     * 要分两处解释，排查的人得记两个数字。
     *
     * <p><b>达到上限后</b>：停止输出，只补一行汇总（与 {@link #writeTrace} 同一策略）。
     * 选「停止 + 一条汇总」而不是「丢弃最旧的」：诊断日志的作用是
     * <b>证明某件事发生过</b>，证明过之后重复记录的边际价值递减；
     * 而丢弃最旧会把「一开始发生了什么」弄丢。
     *
     * <p><b>按 animator 分桶</b>：{@link #TRACES} 按 identityHashCode 隔离多编辑器，
     * 这里沿用同一约定 —— 否则开两个编辑器时它们会互相吃掉对方的额度，
     * 表现成「这个窗口的 [trail] 日志莫名没了」。
     */
    private static final int TRAIL_MAX_LINES = 5000;

    /**
     * 单个 animator 的 [trail] 行计数。
     *
     * <p>刻意<b>不</b>并进 {@link Trace}：Trace 表示的是「首用追踪（boot/evt/tick…）」
     * 的状态，[trail] 是另一套前缀、另一套截断规则。放一起就是
     * 「一个结构承担两个职责」（纪律四-J 判据 1）。
     */
    private static final class TrailBudget {
        int lines;
        boolean capLogged;
    }

    private static final java.util.Map<Integer, TrailBudget> TRAIL_BUDGETS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static TrailBudget trailBudgetOf(int animatorId) {
        return TRAIL_BUDGETS.computeIfAbsent(animatorId, k -> new TrailBudget());
    }

    /** 【U-M-27】[frame] 的每 animator 行数预算（与 [trail] 同结构、同上限）。 */
    private static final java.util.Map<Integer, TrailBudget> FRAME_BUDGETS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static TrailBudget frameBudgetOf(int animatorId) {
        return FRAME_BUDGETS.computeIfAbsent(animatorId, k -> new TrailBudget());
    }

    /**
     * 【U-M-13】注销某个 animator 的全部分桶。
     *
     * <p>四张表必须一起清 —— 漏一张就是「新 animator 继承了旧额度/旧标志」
     * （对象被 GC 后 identityHashCode 会被复用）。
     *
     * <p>包级可见：由 {@code CaretAnimator.dispose()} 在最后一步调用。
     */
    static void forget(int animatorId) {
        TRAIL_BUDGETS.remove(animatorId);
        FRAME_BUDGETS.remove(animatorId);
        SAMPLE_STATE.remove(animatorId);
        TRACES.remove(animatorId);
    }

    /** 探针用：四张表里是否还有这个 id 的条目（T-10）。 */
    public static boolean tableContainsForProbe(int animatorId) {
        return TRAIL_BUDGETS.containsKey(animatorId)
                || FRAME_BUDGETS.containsKey(animatorId)
                || SAMPLE_STATE.containsKey(animatorId)
                || TRACES.containsKey(animatorId);
    }

    /** 探针用：四张表的当前尺寸 [trail, frame, sample, trace]（冷启动应为全 0）。 */
    public static int[] tableSizesForProbe() {
        return new int[]{TRAIL_BUDGETS.size(), FRAME_BUDGETS.size(),
                SAMPLE_STATE.size(), TRACES.size()};
    }

    // ==================== 探针入口（B 批）====================

    /** 探针入口：驱动绘制追踪（T-9）。 */
    public static void paintTraceForProbe(int id, int quads, long costNs,
                                          boolean earlyReturn, Throwable error) {
        paintTrace(id, quads, costNs, earlyReturn, error);
    }

    /** 探针入口：驱动帧汇总（T-10 / T-14）。 */
    public static void frameSummaryForProbe(int id, long seq, int carets, int trails, int glowLayers) {
        frameSummary(id, seq, carets, trails, glowLayers, false, false, -1, 0);
    }

    /** 探针入口：驱动 [trail] 分桶（T-10）。 */
    public static void trailSnapshotForProbe(int id) {
        trailSnapshot(id, 0, 1, "spring", 0, 100, 100, 100, 100, 0, 8, 20, true, 8);
    }

    /** 探针入口：驱动 [font-scale]（T-15）。 */
    public static void fontScaleResyncForProbe(int from, int to) {
        fontScaleResync(from, to);
    }

    /** 探针入口：驱动 [ime-skip]（T-15）。 */
    public static void imeSkipPickedForProbe(int skipN, int vpX, int offset, int offX) {
        imeSkipPicked(skipN, vpX, offset, offX);
    }

    /** 探针入口：把独立文件句柄恢复到「未打开」，并清空计数器（让每个测试从干净文件开始）。 */
    public static void resetFileForProbe() {
        synchronized (FILE_LOCK) {
            closeWriterLocked();
            fileTried = false;
            openFailures = 0;
            writeFailures = 0;
            everOpened = false;
            gaveUp = false;
            totalLines = 0;
            totalCapLogged = false;
            bytesWritten = 0;
            linesSinceCheck = 0;
        }
    }

    /** 探针入口：把当前 writer 换成「一写就失败」的 PrintWriter（T-16）。 */
    public static void setFailingWriterForProbe() {
        synchronized (FILE_LOCK) {
            closeWriterLocked();
            java.io.Writer bad = new java.io.Writer() {
                @Override
                public void write(char[] c, int off, int len) throws java.io.IOException {
                    throw new java.io.IOException("injected: 写盘失败");
                }

                @Override
                public void flush() throws java.io.IOException {
                    throw new java.io.IOException("injected: flush 失败");
                }

                @Override
                public void close() {
                }
            };
            fileWriter = new java.io.PrintWriter(bad);
            fileTried = true;
            everOpened = true;
            linesSinceCheck = 0;
            openFailures = 0;
            writeFailures = 0;
            gaveUp = false;
            totalLines = 0;
            totalCapLogged = false;
            bytesWritten = 0;
        }
    }

    /**
     * 【复查 M】看门狗退场必须留痕。
     *
     * <p>否则「日志里没有 [edt] 行」会被读成「没有发生 EDT 卡顿」——
     * 而真实原因可能是「看门狗 5 分钟没事干，早退场了」。本项目已经因这类
     * 「缺失的证据把方向带偏」吃过一次亏（U-H-8）。
     */
    static void watchdogExit(String reason) {
        writeFileAndReport("[edt] " + traceStamp() + " watchdog 停止采样：" + reason
                + "（下次启用诊断 / 新建编辑器时重启）");
    }

    /** 探针入口：当前 writer 是否已关闭并置 null（T-16）。 */
    public static boolean fileWriterIsNullForProbe() {
        synchronized (FILE_LOCK) {
            return fileWriter == null;
        }
    }

    /**
     * 单个光标的完整状态快照。
     *
     * <p>把「输入 / 中间量 / 输出 / 不变量结果」放在同一条里，
     * 排查时不用跨多条日志对照。检查 I2（静止收敛）与 I3（偏移合理）。
     *
     * <p><b>行数上限</b>：每 animator 最多 {@link #TRAIL_MAX_LINES} 行，
     * 之后只输出一次汇总。前 N 行的内容与加限之前<b>逐字节一致</b> ——
     * 闸门只决定「要不要打」，不参与拼接。
     *
     * @param animatorId 调用方 animator 的 identityHashCode；用于按窗口分桶计数
     */
    static void trailSnapshot(int animatorId, int idx, int total, String mode, int line,
                              double cx, double cy, double corner0X, double corner0Y,
                              double maxOffset, double targetW, double targetH,
                              boolean initialized, double dtMs) {
        if (!enabled()) {
            return;
        }
        TrailBudget budget = trailBudgetOf(animatorId);
        if (budget.lines >= TRAIL_MAX_LINES) {
            if (!budget.capLogged) {
                budget.capLogged = true;
                emit("[trail] animator=" + animatorId + " 已达上限 " + TRAIL_MAX_LINES
                        + " 行，后续不再输出");
            }
            return;
        }
        budget.lines++;
        boolean offsetSane = maxOffset >= 0 && maxOffset <= MAX_REASONABLE_OFFSET;
        boolean settled = maxOffset <= SETTLED_EPSILON;

        emit("[trail] " + idx + "/" + total
                + " mode=" + mode
                + " line=" + line
                + " cx=" + f(cx) + " cy=" + f(cy)
                + " c0=(" + f(corner0X) + "," + f(corner0Y) + ")"
                + " off=" + f(maxOffset)
                + " wh=(" + f(targetW) + "x" + f(targetH) + ")"
                + " init=" + initialized
                + " dt=" + f(dtMs) + "ms"
                + " | I2状态=" + (settled ? "静止" : "动")
                + " I3偏移合理=" + (offsetSane ? "OK" : "BAD(>" + f(MAX_REASONABLE_OFFSET) + ")"));
    }

    /**
     * 帧级汇总 —— 检查 I1（不冻结）/ I4（块选区行对齐）/ I5（数量一致）。
     *
     * @param blockSpread 块选区各行目标 x 的最大差值；非块选区传 -1
     */
    /**
     * 记录一次「被 IME 几何判据拦截」。
     *
     * <p><b>为什么需要这条日志</b>：用户报「f;f; 这种全角输入会撕裂掉帧，纯英文不会」。
     * 现有 IME 判据是「{@code offsetToXY(offset).x != visualPositionToXY(vp).x} 就冻结本帧」——
     * 如果它在正常输入下误判，表现就是帧被大量跳过（观感掉帧）。
     *
     * <p>但原来这条路径**一个字节都不输出**，于是无法区分：
     * <ul>
     *   <li>判据在误冻结（日志有大量 ime-skip）</li>
     *   <li>判据没介入，掉帧在别处（日志无 ime-skip，应查 EDT 负载 / 重绘面积）</li>
     * </ul>
     *
     * @param skipN  连续跳过的第几帧（1 起）
     * @param vpX    visualPositionToXY 给出的 x
     * @param offset 当前 caret offset
     * @param offX   offsetToXY 给出的 x（与 vpX 不同才会走到这里）
     */
    /**
     * 行高变化触发的强制重同步（Ctrl+滚轮缩放字号）。
     *
     * <p><b>为什么值得记一行</b>：这条判据命中说明「快速路径差点把光标留在旧位置」。
     * 若日志出现大量本行，说明字号在频繁变化；若用户报漂移却<b>没有</b>本行，
     * 说明问题不在字号判据上，要去查别处 —— 与本项目既有的
     * {@link #imeSkipPicked} 同一套排查思路。
     *
     * @param fromLineHeight 变化前的行高
     * @param toLineHeight   变化后的行高
     */
    static void fontScaleResync(int fromLineHeight, int toLineHeight) {
        if (!enabled()) {
            return;
        }
        // 【U-L-8】与类注释 515 的纪律一致：这类「证据」只写独立文件，不刷 idea.log。
        writeFileAndReport("[font-scale] 行高 " + fromLineHeight + " -> " + toLineHeight
                + "，强制重算光标几何");
    }

    static void imeSkipPicked(int skipN, int vpX, int offset, int offX) {
        if (!enabled()) {
            return;
        }
        // 每 5 帧记一次：判据若误判会连续命中，但要能看出「连续多久」
        if (skipN == 1 || skipN % 5 == 0) {
            // 【U-L-8】同上：只进独立文件（也受 U-M-27 的总量闸门约束）。
            writeFileAndReport("[ime-skip] skip=" + skipN + " offset=" + offset
                    + " vpX=" + vpX + " offX=" + offX + " dx=" + (vpX - offX));
        }
    }

    static void frameSummary(int animatorId, long frameSeq, int caretCount, int trailCount,
                             int glowLayers, boolean targetMoved, boolean anyOffsetMoved,
                             double blockSpread, double fps) {
        if (!enabled()) {
            return;
        }
        // 【U-M-27】[frame] 是唯一有数量级风险的逐帧出口（8 光标 ≈75 行/秒 → 45MB/小时）。
        // 与 [trail] 同策略、同上限：同一份文件里两种行不该有两种口径。
        TrailBudget fb = frameBudgetOf(animatorId);
        if (fb.lines >= TRACE_MAX_LINES) {
            if (!fb.capLogged) {
                fb.capLogged = true;
                emit("[frame] animator=" + animatorId + " 已达上限 " + TRACE_MAX_LINES
                        + " 行，后续不再输出");
            }
            return;
        }
        fb.lines++;

        // 【真实每帧间隔：用「本 animator 自己的序号差 ÷ 时间差」直接算】
        //
        // 这是唯一口径正确的算法，而且只用本行能拿到的两个量：
        //   frameSeq 是每个 animator 的实例字段（多编辑器不串台）
        //   System.nanoTime 是单调时钟
        // 相邻两次采样之间序号一定前进了 SAMPLE_EVERY_FRAMES（采样是 %N==0），
        // 所以 (时间差 / 序号差) 就是真正的平均每帧耗时。
        //
        // 反例（都踩过）：数「采样了几次」再除以时间是采样率不是帧率（少算 15 倍）；
        // 用 static 计数器会被多编辑器重复累加。两种都让日志报的 fps 完全不可信 ——
        // 用户据此以为多光标掉到 58fps，而实测每帧 8.5ms（117fps）。
        double realFps = -1;
        long nowNs = System.nanoTime();
        // 按 animator 隔离（见 SAMPLE_STATE 的注释：static 版会让 85% 样本失效）
        long[] st = sampleStateOf(animatorId);
        if (st[0] > 0 && frameSeq > st[0]) {
            long dSeq = frameSeq - st[0];
            long dNs = nowNs - st[1];
            if (dNs > 0) {
                realFps = dSeq * 1e9 / dNs;
            }
        }
        st[0] = frameSeq;
        st[1] = nowNs;
        boolean countOk = (caretCount == trailCount);
        // I1：目标动了但没有任何角点动 → 拖尾冻结
        boolean i1Ok = !(targetMoved && !anyOffsetMoved);
        String i4 = blockSpread < 0
                ? "NA"
                : (blockSpread <= 1.0 ? "OK" : "BAD(差" + f(blockSpread) + "px)");

        // 【fps 由调用方传入，本类不再自己数】
        //
        // 曾经在这里用 static 计数器自己算，连中两个坑：
        //
        //   ① 少算 15 倍：frameSummary 只在采样帧被调（frameSeq % 15 == 0），
        //      计数器数的是「采样次数」而不是「帧数」。日志打 fps=6.8，
        //      而同段日志时间戳是 145ms 一行 → 真实 103fps。用户据此
        //      以为帧数卡死了。
        //   ② 多编辑器串台：static 计数器被所有 animator 共用，开两个编辑器
        //      时帧率表观翻倍。
        //
        // 现在是 CaretAnimator#recordFrameStats 每帧记账、每满 1 秒锁定读数，
        // 口径干净且天然按 animator 隔离。这里只负责打印。
        // 同时打出两个数：
        //   fps  = recordFrameStats 的「活动期」帧率（只统计有动画的帧）
        //   real = 本次采样区间内的真实帧率（含静止帧，是无争议的实测值）
        // 两者不一致时以 real 为准 —— 它就是「屏幕上每秒刷新多少次」。
        StringBuilder fpsText = new StringBuilder();
        if (realFps > 0) {
            fpsText.append(String.format(" fps=%.1f", realFps));
        } else {
            fpsText.append(" fps=?");
        }
        if (fps > 0) {
            fpsText.append(String.format(" activeFps=%.1f", fps));
        }

        emit("[frame] #" + frameSeq
                + " carets=" + caretCount + " trails=" + trailCount
                + " layers=" + glowLayers
                + fpsText
                + " targetMoved=" + targetMoved
                + " offsetMoved=" + anyOffsetMoved
                + " | I1不冻结=" + (i1Ok ? "OK" : "BAD")
                + " I4行对齐=" + i4
                + " I5数量=" + (countOk ? "OK" : "BAD"));
    }

    /**
     * 块选区的方向判定（I6）。
     *
     * <p>用户报过「向右扩失效」——根因是两个数组各管一个方向。
     * 这条把「方向 / 选中的数组 / offset」绑在一起，一眼看出选错没有。
     */
    static void blockPick(int caretOffset, int leadOffset, String which, int chosenOffset) {
        if (!enabled()) {
            return;
        }
        boolean rightward = caretOffset >= leadOffset;
        boolean i6Ok = rightward ? "ENDS".equals(which) : "STARTS".equals(which);

        emit("[pick] caret=" + caretOffset + " lead=" + leadOffset
                + " 方向=" + (rightward ? "向右扩" : "向左扩")
                + " 选了=" + which
                + " offset=" + chosenOffset
                + " | I6方向匹配=" + (i6Ok ? "OK" : "BAD"));
    }

    /** 普通光标的跟随点选择（I7）。 */
    static void caretFollow(String tag, boolean hasSelection,
                            String vp, String lead, String used) {
        if (!enabled()) {
            return;
        }
        boolean i7Ok = "VP".equals(used);
        emit("[caret] " + tag
                + " hasSel=" + hasSelection
                + " vp=" + vp + " lead=" + lead
                + " used=" + used
                + " | I7跟随点=" + (i7Ok ? "OK" : "BAD"));
    }

    /** 块选区数组原值（触发式）。 */
    static void blockArrays(int count, String startsHead, String endsHead) {
        if (!enabled()) {
            return;
        }
        emit("[block] count=" + count
                + " starts=" + startsHead
                + " ends=" + endsHead);
    }

    private static String f(double v) {
        return String.format("%.1f", v);
    }

    private static int parseInt(String s, int def) {
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    // ==================== 帧率 ====================
    //
    // 【U-M-14 删除那个 static 的 glowLayersNow 字段】
    // 它被每个 animator 每帧覆写、被别的 animator 读 —— [frame] 行的 layers= 报的是别人的值，
    // 正是本类定义过的「日志撒谎」形态。现在改为 frameSummary 的入参：
    // 「本 animator 本帧生效值」由调用方传入，结构上不可能串台。

    // ==================== 真实帧率采样状态（0.9.81 修复）====================
    //
    // 【原实现的 bug】lastSampleSeq / lastSampleNs 是 **static** 字段。
    // 实测日志：303 条 frame 行里 257 条打出 fps=?（84.8%），
    // 相邻帧号差为 0 的占 30/39 —— 说明多个 CaretAnimator 实例在互相踩。
    //
    // 机制：编辑器 A 的 animator 采样到 #795 记下 (795, t1)；
    //   编辑器 B 的 animator 也在 #795 采样，读到 lastSampleSeq=795，
    //   dSeq = 795 - 795 = 0 → 不算 fps → 打 fps=?，并把 (795, t2) 覆盖回去。
    //   于是只有「每批的第一个」能算出来，其余全是问号。
    //
    // 这与注释里警告过的「static 计数器被多编辑器共用导致表观翻倍」是同一类错误 ——
    // 上次是翻倍，这次是让 85% 的样本失效。
    //
    // 修法：按 animator 身份隔离。用身份哈希做 key，避免残留（弱引用清不掉）。
    private static final java.util.Map<Integer, long[]> SAMPLE_STATE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 取该 animator 的采样状态 [lastSeq, lastNs]；首次调用时初始化为「无历史」 */
    private static long[] sampleStateOf(int animatorId) {
        return SAMPLE_STATE.computeIfAbsent(animatorId, k -> new long[]{0L, 0L});
    }

    // ==================== 覆盖性补充 ====================

    /**
     * 光标数变化（多光标增删/合并）—— 这是排查「光标乱套」的第一现场。
     *
     * <p>以前只在 idea.log 里靠 frameSummary 的 carets=N 间接推断，
     * 但看不到「从几个变成几个、什么时候变的」。
     */
    public static void caretCountChanged(int before, int after, String reason) {
        if (!enabled()) {
            return;
        }
        emit("[carets] " + before + " → " + after + "  (" + reason + ")");
    }

    /**
     * 设置变更 —— 排查「改了没生效」时，先确认值到底有没有变。
     *
     * @param key   字段名
     * @param from  旧值
     * @param to    新值
     */
    public static void configChanged(String key, Object from, Object to) {
        if (!enabled()) {
            return;
        }
        emit("[config] " + key + ": " + from + " → " + to);
    }

    /**
     * 预设切换 —— 排查「切预设手感没变」的直接证据。
     */
    public static void presetApplied(String presetName, float stretchRatioK,
                              float animationLength, float shortAnimationLength) {
        if (!enabled()) {
            return;
        }
        emit(String.format("[preset] %s  k=%.1f anim=%.2f short=%.2f",
                presetName, stretchRatioK, animationLength, shortAnimationLength));
    }

    /**
     * 异常兜底 —— 被 catch 吞掉的异常要留痕。
     *
     * <p>纪律：异常不许静默。诊断开启时至少写一行；关着时零成本（先判 {@link #enabled()}）。
     *
     * <p><b>【0.9.88】不是所有 catch 都该接这里</b>，判据两条，缺一不可：
     * <ol>
     *   <li><b>不是每帧路径</b>。{@code refreshTarget} 里那几个 try 每秒跑 125 次，
     *       接进来会把独立日志刷成一片，真正的线索被埋掉 —— 那等于从"没有日志"
     *       变成"日志没用"，比原来更糟。</li>
     *   <li><b>静默失败有用户可见后果</b>。压栈失败、销毁时 remove 失败这类，
     *       失败本身就是可接受的结局，接了只会制造噪声。</li>
     * </ol>
     *
     * <p>当前接线点（都满足上面两条，且已带兜底值所以不会崩）：
     * <ul>
     *   <li>{@code CaretAnimator.syncGlobalSettings} —— 读不到设置就退回默认值，
     *       表现为「我调了光标宽度它自己变回去」</li>
     *   <li>{@code CaretAnimator.resolveRgbWash} —— 取预设失败则彩虹静默失效，
     *       表现为「选了彩虹但颜色不对」</li>
     * </ul>
     */
    public static void swallowed(String where, Throwable t) {
        // 【复查 M-2】catch 体自己不许抛：本方法被十几处 catch 调用，内部会走
        // enabled()（服务定位 / 可能建线程）与日志框架 —— 任一抛出都会
        // **替换掉原始异常**并冲出调用方，把「单个失败不影响其他」变成新的崩溃点。
        try {
            if (!enabled()) {
                return;
            }
            String text = "[error] " + where + ": " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " — " + t.getMessage());
            // 【复查 M-4】异常本体要交给 Logger：原来只传格式化后的字符串，栈永远不存在，
            // 「知道发生过、不知道在哪」对 restoreNativeCaret 这类逐条失败毫无帮助。
            LOG.info(text, t);
            writeFileAndReport(text);
        } catch (Throwable ignored) {
            // 连「记录失败」都失败时，静默是唯一不制造新事故的选择
        }
    }

    // ==================== 【BUG-007】首用追踪 ====================
    //
    // 【为什么需要这一整套】BUG-007 的现象是「启动后第一次碰键盘必定卡一下」。
    // 排查时发现现有日志结构上答不了这个问题：
    //
    //   · [frame]/[trail] 里没有 rawDt —— rawDt 只在 idea.log 的 debug 行里；
    //   · [frame] 的 #N 是「慢路径刷新序号」，不是渲染帧号；默认 %15 采样，
    //     所以启动首帧 #1 永远不打印；
    //   · 没有「isShowing / 面板尺寸 / 绘制耗时 / 投递延迟」这些决定性字段；
    //   · 没有任何「第一次改文档」的标记 —— 而用户的实测（鼠标移动不卡、
    //     第一次按键才卡）证明触发点正是「第一次修改文档」，不是「第一次用光标」。
    //
    // 【三条纪律】
    //   ① 只写独立文件，不走 LOG.info：144fps 下把 idea.log 刷爆会毁掉证据。
    //   ② 限量：每 animator 最多 TRACE_MAX_LINES 行，且只在「值得看」的帧输出，
    //      否则 144fps × 几分钟 = 几十万行，真正的那几十帧会被埋掉。
    //   ③ 默认关：全部先判 enabled()（即 diagLogging），关着时生产路径零变化。
    //
    // 【不动既有行格式】[frame]/[trail] 被 build/analyze-log.ps1 与 diag/extract.py
    // 用固定顺序正则解析，所以这里只用新前缀（boot/evt/tick/geom/paint/clear）。

    /** 每 animator 的追踪行数上限 —— 144fps 下必须有的硬闸门。 */
    private static final int TRACE_MAX_LINES = 5000;

    /** 超过它就一定记：正常帧约 7ms（144fps），20ms 已是肉眼可感的掉帧。 */
    private static final double TRACE_SLOW_DT_MS = 20.0;

    /** 构造后无条件记录多少帧（覆盖启动期）。 */
    private static final int TRACE_FRAMES_AFTER_BOOT = 40;

    /** 首次发现文档戳变化后无条件记录多少帧（正片：BUG-007 就在这段里）。 */
    private static final int TRACE_FRAMES_AFTER_EDIT = 60;

    /** 单次绘制超过它就记一行（正常一帧绘制在数十微秒量级）。 */
    private static final long TRACE_SLOW_PAINT_NS = 4_000_000L;

    private static final long TRACE_T0_NANOS = System.nanoTime();
    private static final long TRACE_T0_WALL_MS = System.currentTimeMillis();

    /** 每个 animator 的追踪状态（按 identityHashCode 隔离，多编辑器不串台）。 */
    private static final class Trace {
        int lines;
        long ticks;
        long stamp = Long.MIN_VALUE;
        boolean stampSeen;
        int afterEditLeft;
        boolean firstEditLogged;
        boolean notShowingLogged;
        boolean noTrailLogged;
        boolean fastPathLogged;
        boolean imeSkipLogged;
        String lastClearReason;
        /** 【U-H-8】上一帧是否处于「空绘制」状态：翻转才记，而不是终身只记一次。 */
        boolean lastEmptyPaint;
        /** 【U-H-8】上一帧是否处于「绘制异常」状态。 */
        boolean lastPaintError;
        /** 【U-H-8】上一次记录的异常类名：不同类型异常不能互相吃掉名额。 */
        String lastPaintErrorClass;
    }

    private static final java.util.Map<Integer, Trace> TRACES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Trace traceOf(int id) {
        return TRACES.computeIfAbsent(id, k -> new Trace());
    }

    /**
     * 统一的时间戳：墙钟（便于和你按下按键的时刻对照）+ 相对本类加载的毫秒数。
     *
     * <p>既有 [frame]/[trail] 没有时间戳，导致「日志里哪一段是我按那一下」
     * 只能靠猜 —— 这条新格式把它补上。
     */
    private static String traceStamp() {
        long ns = System.nanoTime();
        long wallMs = TRACE_T0_WALL_MS + (ns - TRACE_T0_NANOS) / 1_000_000L;
        String hms = java.time.Instant.ofEpochMilli(wallMs)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalTime().toString();
        return hms + " +" + String.format("%.0f", (ns - TRACE_T0_NANOS) / 1e6) + "ms";
    }

    private static void writeTrace(int id, String tag, String body) {
        if (id < 0 || !enabled()) {
            return;
        }
        Trace tr = traceOf(id);
        if (tr.lines >= TRACE_MAX_LINES) {
            if (tr.lines == TRACE_MAX_LINES) {
                tr.lines++;
                writeFileAndReport("[" + tag + "] id=" + id + " " + traceStamp()
                        + " 已达追踪上限 " + TRACE_MAX_LINES + " 行，后续不再输出");
            }
            return;
        }
        tr.lines++;
        writeFileAndReport("[" + tag + "] id=" + id + " " + traceStamp() + " " + body);
    }

    /** 构造完成时一行：记录这个 animator 诞生时的环境（尺寸 / 可见性 / 关键开关）。 */
    static void bootTrace(int id, String kind, int contentW, int contentH, boolean showing,
                          boolean hideNative, boolean alwaysHighFps, int targetFps,
                          String trailMode) {
        if (!enabled()) {
            return;
        }
        writeTrace(id, "boot", "kind=" + kind
                + " content=" + contentW + "x" + contentH
                + " showing=" + showing
                + " hideNative=" + hideNative
                + " alwaysHighFps=" + alwaysHighFps
                + " targetFps=" + targetFps
                + " trailMode=" + trailMode);
    }

    /**
     * 光标事件（稀疏，不按帧限量）。
     *
     * <p>{@code stampChanged=true} 即「这一次操作改了文档」—— 用户实测
     * 「鼠标移动不卡、第一次按键才卡」，所以这个标记是定位的锚点。
     * 首次出现时额外打 {@code FIRST-EDIT}，方便直接在日志里搜。
     */
    static void caretEventTrace(int id, String kind, int vpLine, int vpColumn,
                                int offset, long stamp) {
        if (!enabled()) {
            return;
        }
        Trace tr = traceOf(id);
        boolean changed = tr.stampSeen && stamp != tr.stamp;
        boolean firstEdit = false;
        if (changed) {
            tr.stamp = stamp;
            tr.afterEditLeft = TRACE_FRAMES_AFTER_EDIT;
            if (!tr.firstEditLogged) {
                tr.firstEditLogged = true;
                firstEdit = true;
            }
        } else if (!tr.stampSeen) {
            tr.stampSeen = true;
            tr.stamp = stamp;
        }
        writeTrace(id, "evt", "type=" + kind
                + " vp=" + vpLine + ":" + vpColumn
                + " offset=" + offset
                + " stamp=" + stamp
                + " stampChanged=" + changed
                + (firstEdit ? " FIRST-EDIT" : ""));
    }

    /**
     * 慢路径读到文档戳时同步一次（兼作「第一次改文档」的兜底判据）。
     *
     * <p>为什么还需要它：有些修改（撤销、格式化）未必伴随 visible position 变化，
     * 光靠 caret 事件可能漏。这里的 stamp 读本来就在慢路径上，零额外平台调用。
     */
    static void noteStampTrace(int id, long stamp) {
        if (!enabled()) {
            return;
        }
        Trace tr = traceOf(id);
        if (!tr.stampSeen) {
            tr.stampSeen = true;
            tr.stamp = stamp;
            return;
        }
        if (stamp == tr.stamp) {
            return;
        }
        tr.stamp = stamp;
        tr.afterEditLeft = TRACE_FRAMES_AFTER_EDIT;
        if (!tr.firstEditLogged) {
            tr.firstEditLogged = true;
            writeTrace(id, "evt", "type=stamp-only FIRST-EDIT stamp=" + stamp);
        }
    }

    /** 几何追踪窗口内才需要多调一次 offsetToXY —— 热路径上不能无条件付这个成本。 */
    static boolean geometryWanted(int id) {
        if (!enabled()) {
            return false;
        }
        Trace tr = traceOf(id);
        return tr.ticks <= TRACE_FRAMES_AFTER_BOOT + 1 || tr.afterEditLeft > 0;
    }

    /** 慢路径算出的几何（正片证据：判定「半字符偏移」是否真实发生）。 */
    static void geometryTrace(int id, int vpLine, int vpColumn, double px, double py,
                              float lineHeight, int offsetX, double cx, double cy,
                              double w, double h) {
        if (!geometryWanted(id)) {
            return;
        }
        writeTrace(id, "geom", "vp=" + vpLine + ":" + vpColumn
                + " cp=(" + f(px) + "," + f(py) + ")"
                + " lineHeight=" + f(lineHeight)
                + " vpX=" + f(px) + " offsetX=" + offsetX
                + " dx=" + (offsetX == Integer.MIN_VALUE ? "?" : f(px - offsetX))
                + " target=(" + f(cx) + "," + f(cy) + ") " + f(w) + "x" + f(h));
    }

    /**
     * 每帧一行（限量）。由 {@code tick()} 在<b>每个</b>出口调用。
     *
     * @param note 出口原因：{@code ok} / {@code no-trail} / {@code not-showing}
     */
    static void tickTrace(int id, long rawDtNs, double dt, boolean recovery,
                          boolean showing, int trails, int quads,
                          int panelW, int panelH, int contentW, int contentH,
                          int delayMs, long dispatchLagNs, long costNs, String note) {
        if (!enabled()) {
            return;
        }
        Trace tr = traceOf(id);
        tr.ticks++;
        double rawDtMs = rawDtNs / 1e6;
        boolean want = tr.ticks <= TRACE_FRAMES_AFTER_BOOT
                || tr.afterEditLeft > 0
                || rawDtMs > TRACE_SLOW_DT_MS
                || onceForNote(tr, note);
        if (tr.afterEditLeft > 0) {
            tr.afterEditLeft--;
        }
        if (!want) {
            return;
        }
        writeTrace(id, "tick", "n=" + tr.ticks
                + " rawDt=" + ms(rawDtMs)
                + " dt=" + ms(dt * 1000)
                + " recovery=" + recovery
                + " showing=" + showing
                + " trails=" + trails
                + " quads=" + quads
                + " panel=" + panelW + "x" + panelH
                + " content=" + contentW + "x" + contentH
                + " delayMs=" + delayMs
                + " dispatchLag=" + (dispatchLagNs < 0 ? "?" : ms(dispatchLagNs / 1e6))
                + " cost=" + (costNs / 1000) + "us"
                + " note=" + note);
    }

    /** 出口原因只在「首次出现」时记一次，避免 no-trail / not-showing 每帧刷屏。 */
    private static boolean onceForNote(Trace tr, String note) {
        if ("not-showing".equals(note)) {
            if (tr.notShowingLogged) {
                return false;
            }
            tr.notShowingLogged = true;
            return true;
        }
        if ("no-trail".equals(note)) {
            if (tr.noTrailLogged) {
                return false;
            }
            tr.noTrailLogged = true;
            return true;
        }
        return false;
    }

    /** 快速路径 / IME 判据命中：只在各自首次出现时记一行（它们会连续命中）。 */
    static void tickPathTrace(int id, String which) {
        if (!enabled()) {
            return;
        }
        Trace tr = traceOf(id);
        if ("fast".equals(which)) {
            if (tr.fastPathLogged) {
                return;
            }
            tr.fastPathLogged = true;
        } else {
            if (tr.imeSkipLogged) {
                return;
            }
            tr.imeSkipLogged = true;
        }
        writeTrace(id, "tick", "path=" + which + " n=" + tr.ticks);
    }

    /**
     * 绘制一行：{@code quadCount==0} 早退（= 这一帧什么都没画）、耗时长、或异常。
     *
     * <p>{@code quadCount==0} 是关键判据 —— 面板透明、原生光标又被隐藏时，
     * 这就是「光标消失」在代码上的唯一形态。
     */
    static void paintTrace(int id, int quads, long costNs, boolean earlyReturn,
                           Throwable error) {
        if (!enabled()) {
            return;
        }
        Trace tr = traceOf(id);
        // 【U-H-8】原实现是「终身只记一次」：编辑器未布局完时那次正常空绘制会消耗掉
        // 唯一的名额，之后真故障（U-H-5/U-H-6 都能造成）进入同一状态时不再记录；
        // 绘制异常也只记首个类名，后续不同类型异常无痕。
        // 排查者按「[paint] 里没有 earlyReturn 行」反推「面板一直有内容」——
        // 方向被日志机制自己带错，比没有日志更糟。
        boolean errorNow = error != null;
        boolean emptyNow = error == null && earlyReturn && quads == 0;
        boolean record = false;
        String flip = "";

        if (errorNow != tr.lastPaintError) {
            tr.lastPaintError = errorNow;
            record = true;
        }
        if (errorNow) {
            String cls = error.getClass().getSimpleName();
            if (!cls.equals(tr.lastPaintErrorClass)) {
                tr.lastPaintErrorClass = cls;
                record = true;
            }
        } else {
            tr.lastPaintErrorClass = null;
        }
        if (emptyNow != tr.lastEmptyPaint) {
            tr.lastEmptyPaint = emptyNow;
            record = true;
            flip = emptyNow ? " flip=into-empty" : " flip=out-empty";
        }
        if (!errorNow && !emptyNow && costNs >= TRACE_SLOW_PAINT_NS) {
            record = true;
        }
        if (!record) {
            return;
        }
        writeTrace(id, "paint", "quads=" + quads
                + " cost=" + (costNs / 1000) + "us"
                + " earlyReturn=" + earlyReturn + flip
                + (error == null ? "" : " error=" + error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : " — " + error.getMessage())));
    }

    /**
     * 清空绘制数据（= 覆盖层这一帧/这一段没东西可画）。
     *
     * <p><b>同一原因只记一次</b>：首次采集实测清空行刷了 24444 条（5 个隐藏编辑器
     * 每帧一条），把真正的那几十帧埋在中间，而且每行都 flush 一次，本身就成了
     * EDT 负担。改为按原因去重 —— 状态变化仍然看得见，噪声没了。
     */
    static void clearTrace(int id, String reason) {
        if (!enabled()) {
            return;
        }
        Trace tr = traceOf(id);
        if (reason.equals(tr.lastClearReason)) {
            return;
        }
        tr.lastClearReason = reason;
        writeTrace(id, "clear", "reason=" + reason);
    }

    /** 毫秒格式（三位小数，6.944ms 这种帧间隔才看得出来）。 */
    private static String ms(double v) {
        return String.format("%.3f", v);
    }

    /**
     * EDT 被占住的一次采样（由 {@link EdtWatchdog} 调用，见该类的说明）。
     *
     * <p>不按 animator 分桶：EDT 只有一条，这个问题与哪个编辑器无关，所以直接写文件、
     * 由看门狗自己限量（{@code EdtWatchdog.MAX_DUMPS}，当前 20 次）。
     *
     * @param waitedNs    ping 投递到执行之间的间隔
     * @param stackCostNs 抓栈本身花的时间；≈waitedNs 说明当时是 JVM 全局暂停（GC），
     *                    远小于 waitedNs 才说明是 EDT 上某个方法在跑
     */
    static void edtBlocked(long waitedNs, long stackCostNs, String frames) {
        if (!enabledForBackgroundThread()) {
            // 【U-M-11】看门狗在自己的线程里跑：读 volatile 快照，不去碰服务定位与非 volatile 字段。
            return;
        }
        writeFileAndReport("[edt] " + traceStamp()
                + " waited=" + ms(waitedNs / 1e6) + "ms"
                + " stackCost=" + (stackCostNs < 0 ? "?" : ms(stackCostNs / 1e6) + "ms")
                + " " + frames);
    }
}