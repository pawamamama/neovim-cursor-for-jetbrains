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
     * <p>位置：{@code <config>/neovide-cursor-diag.log}
     * 也就是 {@code %APPDATA%JetBrainsIntelliJIdea2026.1
eovide-cursor-diag.log}
     */
    static java.nio.file.Path diagFile() {
        try {
            com.intellij.openapi.application.PathManager pm = null;
            java.lang.reflect.Method m = com.intellij.openapi.application.PathManager.class
                    .getMethod("getConfigPath");
            Object v = m.invoke(null);
            if (v instanceof String) {
                return java.nio.file.Paths.get((String) v, "neovide-cursor-diag.log");
            }
        } catch (Throwable ignored) {
        }
        return java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                "neovide-cursor-diag.log");
    }

    /** 当前日志文件句柄（懒开，append 模式） */
    private static java.io.PrintWriter fileWriter;
    private static boolean fileTried;

    private static void writeFile(String line) {
        if (!fileTried) {
            fileTried = true;
            try {
                java.nio.file.Path f = diagFile();
                java.nio.file.Files.createDirectories(f.getParent());
                // 【每次启动清空】避免历史日志混入，每次排查都是干净的
                fileWriter = new java.io.PrintWriter(
                        java.nio.file.Files.newBufferedWriter(f,
                                java.nio.charset.StandardCharsets.UTF_8,
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
            } catch (Throwable t) {
                emit("neovide-cursor: 无法写独立诊断文件 " + t);
            }
        }
        if (fileWriter != null) {
            fileWriter.println(line);
            fileWriter.flush();       // 立即落盘，崩溃也能看到
        }
    }

    /** 统一出口：同时写 idea.log 和独立文件 */
    private static void emit(String line) {
        LOG.info(line);
        writeFile(line);
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

    /** 当前是否启用：设置项或系统属性，任一为真。 */
    public static boolean enabled() {
        if (Boolean.getBoolean(PROP_ENABLED)) {
            return true;
        }
        try {
            return com.intellij.openapi.application.ApplicationManager
                    .getApplication()
                    .getService(com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings.class)
                    .getState().diagLogging;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean shouldSample(long frameSeq) {
        return enabled() && (frameSeq % SAMPLE_EVERY_FRAMES) == 0;
    }

    /**
     * 单个光标的完整状态快照。
     *
     * <p>把「输入 / 中间量 / 输出 / 不变量结果」放在同一条里，
     * 排查时不用跨多条日志对照。检查 I2（静止收敛）与 I3（偏移合理）。
     */
    static void trailSnapshot(int idx, int total, String mode, int line,
                              double cx, double cy, double corner0X, double corner0Y,
                              double maxOffset, double targetW, double targetH,
                              boolean initialized, double dtMs) {
        if (!enabled()) {
            return;
        }
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
    static void imeSkipPicked(int skipN, int vpX, int offset, int offX) {
        if (!enabled()) {
            return;
        }
        // 每 5 帧记一次：判据若误判会连续命中，但要能看出「连续多久」
        if (skipN == 1 || skipN % 5 == 0) {
            LOG.info("[ime-skip] skip=" + skipN + " offset=" + offset
                    + " vpX=" + vpX + " offX=" + offX + " dx=" + (vpX - offX));
        }
    }

    static void frameSummary(int animatorId, long frameSeq, int caretCount, int trailCount,
                             boolean targetMoved, boolean anyOffsetMoved,
                             double blockSpread, double fps) {
        if (!enabled()) {
            return;
        }

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
                + " layers=" + glowLayersNow
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

    /** 最近一帧的辉光层数（由绘制路径写入，供 frameSummary 打印） */
    static int glowLayersNow = 0;

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
        if (!enabled()) {
            return;
        }
        emit("[error] " + where + ": " + t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : " — " + t.getMessage()));
    }
}