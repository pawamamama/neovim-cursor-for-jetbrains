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
final class CursorDiag {

    private static final Logger LOG = Logger.getInstance("neovide-cursor-diag");

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
    static boolean enabled() {
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

        LOG.info("[trail] " + idx + "/" + total
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
    static void frameSummary(long frameSeq, int caretCount, int trailCount,
                             boolean targetMoved, boolean anyOffsetMoved,
                             double blockSpread) {
        if (!enabled()) {
            return;
        }
        boolean countOk = (caretCount == trailCount);
        // I1：目标动了但没有任何角点动 → 拖尾冻结
        boolean i1Ok = !(targetMoved && !anyOffsetMoved);
        String i4 = blockSpread < 0
                ? "NA"
                : (blockSpread <= 1.0 ? "OK" : "BAD(差" + f(blockSpread) + "px)");

        // 【fps 直接算在这里】之前 fpsTick 在 tickSpring 的另一处，
        // 那条路径有多个早退，实测打不出来。frameSummary 已被 shouldSample
        // 限流且确认在输出，把 fps 挂这里最稳。
        String fpsText = "";
        long now = System.nanoTime();
        if (fpsWindowStart == 0) {
            fpsWindowStart = now;
        } else {
            fpsFrameCount++;
            long elapsed = now - fpsWindowStart;
            if (elapsed > 0) {
                fpsText = String.format(" fps=%.1f", fpsFrameCount * 1e9 / elapsed);
            }
            if (elapsed >= FPS_WINDOW_NANOS) {
                fpsWindowStart = now;
                fpsFrameCount = 0;
            }
        }

        LOG.info("[frame] #" + frameSeq
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

        LOG.info("[pick] caret=" + caretOffset + " lead=" + leadOffset
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
        LOG.info("[caret] " + tag
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
        LOG.info("[block] count=" + count
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

    // ==================== 帧率基线测量 ====================

    /**
     * 帧率统计窗口（纳秒）：每满 1 秒输出一次。
     */
    private static final long FPS_WINDOW_NANOS = 1_000_000_000L;

    /** 最近一次记录的光标数 / 辉光层数（供 frameSummary 打印） */
    static int glowLayersNow = 0;

    private static long fpsWindowStart = 0;
    private static int fpsFrameCount = 0;
    private static int fpsTrailCount = 0;
    private static int fpsGlowLayers = 0;

    /**
     * 记录一帧，每秒输出一次真实 fps —— 用于「多光标辉光该不该降级」的基线测量。
     *
     * <p><b>为什么需要</b>：用户要调研「辉光层数随光标数降级」，
     * 但前提是「多光标 + 满辉光确实卡」。这个前提没人验证过。
     * 没有基线就做优化 = 盲目优化。
     *
     * <p>输出格式（每秒一行）：
     * <pre>
     *   [fps] 118.3 帧/秒  光标=8  拖尾=8  辉光层=8
     * </pre>
     *
     * <p>启用方式：设置页勾选「诊断日志」，或 {@code -Dneovide.cursor.diag=true}。
     */
    static void fpsTick(int trailCount, int glowLayers) {
        if (!enabled()) {
            return;
        }
        long now = System.nanoTime();
        if (fpsWindowStart == 0) {
            fpsWindowStart = now;
            return;
        }
        fpsFrameCount++;
        fpsTrailCount = trailCount;
        fpsGlowLayers = glowLayers;

        long elapsed = now - fpsWindowStart;
        if (elapsed >= FPS_WINDOW_NANOS) {
            double fps = fpsFrameCount * 1e9 / elapsed;
            LOG.info(String.format(
                    "[fps] %.1f 帧/秒  光标=%d  拖尾=%d  辉光层=%d",
                    fps, trailCount, trailCount, glowLayers));
            fpsWindowStart = now;
            fpsFrameCount = 0;
        }
    }

    /** 重置帧率窗口（切换光标数时调用，避免平均值被旧数据污染） */
    static void fpsReset() {
        fpsWindowStart = 0;
        fpsFrameCount = 0;
    }
}