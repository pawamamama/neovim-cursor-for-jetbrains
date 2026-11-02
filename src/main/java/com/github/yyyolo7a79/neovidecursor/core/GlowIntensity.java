package com.github.yyyolo7a79.neovidecursor.core;

/**
 * 辉光的「强度曲线」与「限速淡出」—— <b>全项目唯一实现</b>。
 *
 * <h2>为什么要有这个类（2026-10-29）</h2>
 *
 * <p>在它出现之前，同一条曲线存在三份实现：
 * <ol>
 *   <li>{@code CaretAnimator.tickSpring()} 里 6 行 clamp（生产，全局强度）；</li>
 *   <li>{@code CaretAnimator.glowStrengthForValue()} 里同样的 6 行（生产，逐四边形强度）；</li>
 *   <li>{@code AnimationPreview.step()} 里完全不同的 {@code min(1, maxOffset / 8)}（预览）。</li>
 * </ol>
 *
 * <p>前两份是重复（同一条曲线抄两遍），第三份是<b>分家</b>：预览要偏移到 8px 才满强度，
 * 而生产 2.5px 就满了。于是同一个参数在「预览」和「真实编辑器」里亮度曲线不同 ——
 * 用户拖滑块时看到的是预览的曲线，回车后看到的是生产的曲线。
 * 这与纪律四-G（不许复刻逻辑）和陷阱 6（预览与生产必须同源）是同一类错误。
 *
 * <p>现在：曲线只在这个类里写一次，两边都调。守门探针 {@code VPreviewParity}
 * 直接断言本类的边界值，任何人改动曲线都会立刻变红。
 *
 * <h2>为什么强度用「偏移量」而不是「时间」</h2>
 *
 * <p>偏移量 = 四个角相对目标位置的当前距离（{@code TrailCorner.getOffsetMagnitude()}）。
 * 它天然表达「拖尾展开到什么程度」：静止时为 0，真实移动时至少是一个字符宽（约 8px）
 * 或一行高（约 32px）。用偏移量驱动「目标亮度」，运动时亮起、静止时目标为 0。
 *
 * <p>【0.9.126】淡出不再是「到点乘 0」，而是 {@link #advanceTrailGlow} 的限速下降：
 * 状态是「当前亮度 + 最后一次移动时刻」，由调用方按 trail 维护；组内只共享显示亮度。
 */
public final class GlowIntensity {

    /**
     * 偏移量区间的下端（像素）：偏移 ≤ 此值视为静止，辉光强度为 0。
     *
     * <p>取 0.5 而不是 0：弹簧收敛到亚像素时仍有微小抖动，若下界是 0，
     * 静止状态下会断断续续闪 1~2% 的辉光。
     */
    public static final double OFFSET_START = 0.5;

    /**
     * 偏移量区间的上端（像素）：偏移 ≥ 此值即满强度。
     *
     * <p>区间只有 2px 宽，因为这两个状态本来就离得很近：静止时偏移为 0，
     * 而任何真实移动的偏移至少是一个字符宽（约 8px）或一行高（约 32px）。
     * 窄区间既保证「移动时光晕完全没被动过」，又让停下时的熄灭不突兀。
     */
    public static final double OFFSET_END = 2.5;

    private GlowIntensity() {
    }

    /**
     * 偏移量 → 辉光强度（0~1，超出区间截断）。
     *
     * @param offset 四角相对目标的最大偏移（像素）；低于区间下端按 0 处理
     * @return 0 = 完全不画辉光，1 = 满强度
     */
    public static float strengthFor(double offset) {
        // 【M-4，2026-10-30】非有限偏移按「静止」处理。
        //
        // NaN 会让下面两个比较都为 false，于是原样返回 NaN；调用方的判据是
        // `strength > 0f`，而 `NaN > 0f` 恒为 false —— 辉光被当成「静止」永不亮，
        // 且没有任何日志。这类「看起来像没开辉光」的问题最难查。
        //
        // 语义边界：+Infinity 以前会走到 `s > 1f` 返回 1f（满强度），现在返回 0f。
        // 这是有意的保守化 —— 非有限偏移本身就是非法输入，误亮比不亮更容易掩盖问题。
        if (!Double.isFinite(offset)) {
            return 0f;
        }
        float s = (float) ((offset - OFFSET_START) / (OFFSET_END - OFFSET_START));
        if (s < 0f) {
            return 0f;
        }
        if (s > 1f) {
            return 1f;
        }
        return s;
    }

    /**
     * 辉光亮度的一帧推进 —— <b>目标曲线 + 限速下降</b>（0.9.126 B 方案）。
     *
     * <p>这是「跟着拖尾一起暗，但淡出参数仍然有效」的核心：
     * <ul>
     *   <li>目标始终是 {@link #strengthFor(double)}。拖尾还在时目标不会归零，
     *       所以跨行缎带不会中途灭（0.9.118 的回归）；</li>
     *   <li>目标高于当前亮度时立即上升 —— 每次按键必须立刻回到满值（AC-2）；</li>
     *   <li>目标低于当前亮度时，每帧最多下降 {@code dt / fadeOutSeconds}。
     *       这就是「淡出时长」的实际作用：不是决定何时开始，而是决定降多慢；</li>
     *   <li>保持期内不下降，保证「保持时长」仍然有效。</li>
     * </ul>
     *
     * <p><b>为什么不用「到点乘 0」</b>：0.9.125 把计时起点挂在「最后一次可见」，
     * 拖尾收拢期间计时被每帧重置，于是只能等光标停下再淡 —— 用户感知为「停下才熄灭」。
     * 限速下降把「什么时候开始」交给拖尾曲线，把「降多慢」交给参数，两者不再打架。
     *
     * @param overlayFadeOut 总开关；关闭时直接返回目标曲线（旧行为）
     * @param current        当前亮度（0~1）
     * @param target         目标亮度（通常是 {@link #strengthFor(double)} 的返回值）
     * @param dtSeconds      本帧时长（秒）
     * @param fadeOutSeconds 淡出时长（秒）：每帧最多下降 dt / fadeOutSeconds
     * @param holdActive     是否处于「保持期」；true = 本帧不下降
     * @return 本帧结束时的亮度（0~1）
     */
    public static float advanceGlow(boolean overlayFadeOut, float current, float target,
                                    double dtSeconds, double fadeOutSeconds,
                                    boolean holdActive) {
        if (!overlayFadeOut) {
            return Float.isFinite(target) ? target : 0f;
        }
        // 非有限值按 0 处理：NaN 会让所有比较为 false，静默画出「没有辉光」，
        // 与 strengthFor 的 M-4 守卫同构。
        if (!Float.isFinite(current) || !Float.isFinite(target)) {
            return 0f;
        }
        if (target >= current) {
            return target;                 // 上升立即：按键立刻回到满值
        }
        if (holdActive) {
            return current;                // 保持期：本帧不降
        }
        if (!(fadeOutSeconds > 0)) {
            return target;                 // 0 / 负数：立即到位
        }
        if (!Double.isFinite(fadeOutSeconds)) {
            // NaN / Infinity：立即到位。若返回 current，亮度会永久冻结，
            // 而 needsMoreFrames 仍会每帧返回 true —— EDT 永久空转（审查 M-1）。
            return target;
        }
        if (!Double.isFinite(dtSeconds)) {
            return target;                 // 非法步长：立即到位，避免冻结
        }
        if (!(dtSeconds > 0)) {
            return current;                // 零步长：本帧不动
        }
        double maxDrop = dtSeconds / fadeOutSeconds;
        return (float) Math.max(target, current - maxDrop);
    }

    /**
     * 单个 trail 的辉光推进 —— 生产与预览共用的唯一入口（0.9.126）。
     *
     * <p>把「目标 = 自身偏移曲线」「保持期 = 自身最后一次移动」「下降速度受
     * 淡出时长限制」三件事合成一次调用。状态必须按 trail 保存，不能把组亮度
     * 写回成员：否则拆组/移除活跃光标后，从未移动的 trail 会带着组亮度单独发光
     * （2026-11-02 审查 H-1 / M-1）。
     *
     * <p>组内显示亮度由调用方取 max（相邻行看起来是一个整体），但每个 trail
     * 自己的状态只由自己的 offset / lastMove 决定。
     *
     * @param overlayFadeOut 总开关
     * @param current        本 trail 当前亮度（0~1）
     * @param offset         本 trail 当前最大偏移（像素）
     * @param lastMoveNanos  本 trail 最后一次移动的时刻；0 = 从未移动
     * @param nowNanos       当前时刻
     * @param dtSeconds      本帧时长（秒）
     * @param delayMs        保持时长（毫秒）
     * @param fadeOutSeconds 淡出时长（秒）
     * @return 本帧结束时的亮度（0~1）
     */
    public static float advanceTrailGlow(boolean overlayFadeOut, float current, double offset,
                                         long lastMoveNanos, long nowNanos, double dtSeconds,
                                         int delayMs, double fadeOutSeconds) {
        float target = strengthFor(offset);
        long holdRemaining = holdRemainingMs(lastMoveNanos, nowNanos, delayMs);
        boolean holdActive = overlayFadeOut && lastMoveNanos != 0L && holdRemaining > 0;
        return advanceGlow(overlayFadeOut, current, target, dtSeconds, fadeOutSeconds, holdActive);
    }

    /**
     * 保持期还剩多少毫秒（0 = 已结束或从未移动）。
     *
     * @param lastMoveNanos 最后一次移动的时刻；0 = 从未移动
     * @param nowNanos      当前时刻
     * @param delayMs       保持时长（毫秒）
     */
    public static long holdRemainingMs(long lastMoveNanos, long nowNanos, int delayMs) {
        if (lastMoveNanos == 0L || delayMs <= 0) {
            return 0L;
        }
        if (nowNanos <= lastMoveNanos) {
            return delayMs;
        }
        long elapsedMs = (nowNanos - lastMoveNanos) / 1_000_000L;
        return elapsedMs >= delayMs ? 0L : delayMs - elapsedMs;
    }

    /**
     * 淡出是否还需要继续出帧（0.9.126 版）。
     *
     * <p>与 0.9.125 的区别：判据从「全局 fade 乘子」改成「本组亮度」。
     * 保持期画面恒定，可以降到空闲档；但最后一段必须逐帧，否则淡出曲线会被
     * 一个 120ms 的空闲帧整段跨过去（BUG-B 的老问题）。
     *
     * @param overlayFadeOut  总开关
     * @param brightness      本组当前亮度
     * @param target          本组目标亮度
     * @param holdActive      是否处于保持期
     * @param holdRemainingMs 保持期还剩多少毫秒
     * @param idleFrameMs     空闲档帧间隔
     * @return true = 下一帧仍需以高帧率推进
     */
    public static boolean needsMoreFrames(boolean overlayFadeOut, float brightness, float target,
                                          boolean holdActive, long holdRemainingMs,
                                          long idleFrameMs) {
        if (!overlayFadeOut) {
            return false;
        }
        if (!(brightness > 0f) || !Float.isFinite(target)) {
            return false;
        }
        if (target > 0f) {
            return true;                   // 拖尾还在可见区间：继续画
        }
        if (!holdActive) {
            return true;                   // 正在下降：逐帧
        }
        return holdRemainingMs <= idleFrameMs;
    }

}
