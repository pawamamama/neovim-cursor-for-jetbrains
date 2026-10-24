package com.github.yyyolo7a79.neovidecursor.ui;

import com.intellij.openapi.diagnostic.Logger;

/**
 * 光标诊断日志 —— 出问题时用户能直接导出、我据此定位。
 *
 * <p><b>为什么需要（2026-10-24 教训）</b>：
 * 「选区跟随」这个问题我改了三轮（0.9.22 / 0.9.24 / 0.9.27）才对，
 * 前两轮全靠推理，第三轮加了日志才拿到真相：
 * <pre>
 *   vp=29:17 → 29:23 → 29:38   （普通选区：vp 在动）
 *   lead=29:12 恒定
 *
 *   vp=29:4 恒定  lead=29:24   （块选区：lead 在动）
 * </pre>
 * <b>两种选区的活动端落在不同 API 上</b> —— 光靠读文档和推理永远看不出来。
 *
 * <p><b>设计原则</b>：
 * <ul>
 *   <li><b>默认关闭</b>（不污染日志、不影响性能）</li>
 *   <li>开关走系统属性，<b>发布版也能开</b>：{@code -Dneovide.cursor.diag=true}</li>
 *   <li>限流：每 {@link #SAMPLE_EVERY_FRAMES} 帧最多一条，避免 4 分钟 15000 条</li>
 *   <li>输出格式稳定，便于 {@code Select-String} 提取</li>
 * </ul>
 *
 * <p><b>用户怎么开</b>（不改代码、不用重打包）：
 * <ol>
 *   <li>Help → Edit Custom VM Options</li>
 *   <li>加一行 {@code -Dneovide.cursor.diag=true}</li>
 *   <li>重启 IDE</li>
 *   <li>复现问题后：Help → Show Log in Explorer，把 idea.log 给开发者</li>
 * </ol>
 */
final class CursorDiag {

    private static final Logger LOG = Logger.getInstance("neovide-cursor-diag");

    /** 系统属性名：开诊断 */
    static final String PROP_ENABLED = "neovide.cursor.diag";

    /** 系统属性名：采样间隔（帧），默认 15 */
    static final String PROP_SAMPLE = "neovide.cursor.diag.sample";

    /** 每 N 帧最多写一条 */
    private static final int SAMPLE_EVERY_FRAMES =
            parseInt(System.getProperty(PROP_SAMPLE), 15);

    /**
     * 是否启用。
     *
     * <p>用 {@code Boolean.getBoolean} 读系统属性 —— 静态 final，只读一次，
     * 每帧调用没有开销。
     */
    static final boolean ENABLED = Boolean.getBoolean(PROP_ENABLED);

    private CursorDiag() {
    }

    /** 该帧是否应该采样 */
    static boolean shouldSample(long frameSeq) {
        return ENABLED && (frameSeq % SAMPLE_EVERY_FRAMES) == 0;
    }

    /**
     * 记录一帧的「光标取值」。
     *
     * @param tag      场景标签（如 {@code "single"} / {@code "block"}）
     * @param hasSel   是否有选区
     * @param vp       getVisualPosition 的结果（line:column，null 写 "null"）
     * @param lead     getLeadSelectionPosition 的结果
     * @param used     实际用了哪个（"VP" / "LEAD"）
     */
    static void caretSample(String tag, boolean hasSel,
                            String vp, String lead, String used) {
        if (!ENABLED) {
            return;
        }
        LOG.info("[caret] tag=" + tag
                + " hasSel=" + hasSel
                + " vp=" + vp
                + " lead=" + lead
                + " used=" + used);
    }

    /**
     * 记录一帧的「实际绘制坐标」—— 与 {@link #caretSample} 对照，
     * 能看出「取值对了但没画出来」这类问题。
     */
    static void drawSample(String tag, int line, double corner0X,
                           double targetCx, double targetCy, boolean initialized) {
        if (!ENABLED) {
            return;
        }
        LOG.info("[draw] tag=" + tag
                + " line=" + line
                + " corner0X=" + fmt(corner0X)
                + " cx=" + fmt(targetCx)
                + " cy=" + fmt(targetCy)
                + " init=" + initialized);
    }

    /**
     * 记录块选区的原始数组（只在块选区模式下，限流）。
     *
     * <p>块选区最容易出错 —— 它是「N 行数组」而非单点，索引错位、起点/终点搞反
     * 都表现为「某几行不动」。
     */
    static void blockSample(int count, String startsHead, String endsHead) {
        if (!ENABLED) {
            return;
        }
        LOG.info("[block] count=" + count
                + " starts=" + startsHead
                + " ends=" + endsHead);
    }

    private static String fmt(double v) {
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
}
