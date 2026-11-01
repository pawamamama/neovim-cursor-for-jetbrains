package com.github.yyyolo7a79.neovidecursor.core;

/**
 * 辉光的「强度曲线」与「淡出曲线」—— <b>全项目唯一实现</b>。
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
 * 或一行高（约 32px）。用偏移量驱动，辉光就在运动时亮起、停下时熄灭，
 * 不需要额外维护任何状态机。
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
     * 辉光淡出系数（0~1）。
     *
     * <p>语义：光标停止后先保持满强度 {@code disappearDelayMs} 毫秒，
     * 再用 {@code fadeOutSeconds} 秒线性降到 0。只作用于<b>辉光层</b>，
     * 光标本体始终不透明（见 {@code CaretTrailPanel.setGlowFade} 的说明）。
     *
     * <p><b>为什么用「辉光最后可见时刻」而不是「是否还在动」作计时起点</b>：
     * 两者是同一条曲线上的读数 —— 判定「不动了」的那一刻，强度也已经归零，
     * 于是淡出计时刚开始就没有东西可淡。所以调用方要在强度 &gt; 0 的每一帧
     * 记录时刻，强度归零后用「最后一次的强度 × 本方法返回的 fade」当有效强度。
     *
     * @param overlayFadeOut   总开关；关闭时不淡出，恒为 1
     * @param lastVisibleNanos 辉光最后一次可见的时刻；{@code 0} = 从未亮过
     * @param nowNanos         当前时刻
     * @param disappearDelayMs 停止后保持满强度的时长（毫秒）
     * @param fadeOutSeconds   线性熄灭所需秒数
     * @return 0 = 已完全熄灭，1 = 满强度
     */
    public static float fade(boolean overlayFadeOut, long lastVisibleNanos, long nowNanos,
                             int disappearDelayMs, double fadeOutSeconds) {
        if (!overlayFadeOut) {
            return 1f;                              // 开关关闭：不淡出
        }
        if (lastVisibleNanos == 0L) {
            // 尚未出现过可见辉光：没有可淡的东西。
            // 返回 0 与返回 1 等价 —— 调用方只在强度 > 0 时才让 fade 参与乘法，
            // 强度为 0 时有效强度是 lastStrength(0) × fade = 0 × 任何值。
            return 0f;
        }
        double sinceStop = (nowNanos - lastVisibleNanos) / 1_000_000_000.0
                - disappearDelayMs / 1000.0;
        if (sinceStop <= 0) {
            return 1f;                              // 停止后 delay 内：保持满强度
        }
        if (fadeOutSeconds <= 0 || sinceStop >= fadeOutSeconds) {
            return 0f;                              // 淡出完毕
        }
        return (float) (1.0 - sinceStop / fadeOutSeconds);
    }

    /**
     * 淡出是否还需要继续出帧（2026-11-01，BUG-B 的修法）。
     *
     * <h2>为什么需要这个判据，而不是「fade 在 (0,1) 之间」</h2>
     *
     * <p>调用方（{@code CaretAnimator.tickSpring()}）原来写的是
     * {@code fade > 0f && fade < 1f}。问题出在<b>「淡出前保持时长」那一段</b>：
     * 此时 {@code fade == 1f}，判据为 false；而光标刚停下时弹簧通常也已经收敛
     * （{@code anyAnimating == false}），于是调度器降到空闲档
     * {@code IDLE_DELAY_MS = 120ms}。默认淡出时长只有 75ms ——
     * <b>整条淡出曲线比一个空闲帧还短</b>，下一次出帧时 {@code sinceStop} 已经越过终点，
     * fade 直接等于 0：用户看到的是「保持一会儿，然后瞬间消失」，而不是淡出。
     * 把保持时长调到 500ms 只会让「瞬间消失」晚 500ms 发生，观感上仍然「没有淡出」。
     *
     * <p>正确语义是：<b>只要还有可淡的辉光，就必须继续出帧</b>。
     * 「保持期」也是淡出过程的一部分（这一段的输出恒为 1），必须被采样，
     * 否则保持期与熄灭之间的边界只能靠空闲帧去碰运气。
     *
     * <p>判据放在本类而不是 {@code CaretAnimator}：后者需要真实 {@code Editor} 才能构造，
     * 探针无法直接驱动；这里是纯函数，逐段可断言（守门探针 {@code VFadeTick}）。
     *
     * @param overlayFadeOut        总开关；关闭时没有淡出过程
     * @param lastGlowStrength      辉光最后一次可见时的强度；0 = 从未亮过，没有可淡的东西
     * @param fade                  {@link #fade} 的返回值
     * @param millisUntilFadeStarts 保持期还剩多少毫秒；淡出已开始 / 无保持期时为 0
     * @param idleFrameMs           空闲档的帧间隔（{@code CaretAnimator.IDLE_DELAY_MS}）
     * @return true = 下一帧仍需以高帧率推进
     */
    public static boolean needsMoreFrames(boolean overlayFadeOut, float lastGlowStrength, float fade,
                                          long millisUntilFadeStarts, long idleFrameMs) {
        if (!overlayFadeOut) {
            return false;                 // 开关关闭：fade 恒为 1，也没有淡出可推进
        }
        // 写成 `!(x > 0f)` 而不是 `x <= 0f`：NaN 与 0 比较恒为 false，
        // 后者会让「强度算坏了」变成永久空转（与 strengthFor 的 M-4 同一类防御）。
        if (!(lastGlowStrength > 0f)) {
            return false;                 // 从未亮过 / 已彻底熄灭：没有可淡的东西
        }
        if (!(fade > 0f)) {
            return false;                 // NaN / 已结束：不再续帧
        }
        if (fade < 1f) {
            return true;                  // 淡出中：每一帧都在变，必须逐帧推进
        }
        // 保持期：画面逐帧完全相同（恒为「最后亮度 × 1」），没必要全速空转。
        // 但最后一段必须逐帧 —— 否则又会回到「淡出曲线比空闲帧还短、被整段跳过」的老问题。
        // 判据：距离淡出开始不足一个空闲帧时开始续帧。
        return millisUntilFadeStarts <= idleFrameMs;
    }

    /**
     * 本组这一帧是否应改用「保持亮度」—— 逐组判据（2026-11-01 发版前审查修正）。
     *
     * <h2>为什么判据必须逐组，而不是「全局本帧有没有移动」</h2>
     *
     * <p>{@code moved} 只表示「光标目标位置这一帧变没变」。一次按键之后弹簧还要推进
     * 十几帧，这些帧 {@code moved == false}，于是全局判据会把<b>所有</b>分组都切到
     * 保持亮度 —— 多光标下那个从头到尾没动过的光标也会满强度发光。
     * 这正是 {@code CaretTrailPanel} 注释里写明已修掉的串扰。
     *
     * <p>所以调用方按组传 {@code groupRecentlyActive}：本组在「保持 + 淡出」窗口内
     * 有过非零偏移才为 true。从未活动过的组永远拿不到保持亮度。
     *
     * <p>它仍然不能和 {@link #needsMoreFrames} 合成一个布尔：后者决定「还要不要出帧」，
     * 本方法决定「用哪个强度」，两者在运动期间答案恰好相反。
     *
     * @param overlayFadeOut      总开关；关闭时辉光随弹簧自然衰减，没有保持阶段
     * @param groupRecentlyActive 本组最近是否活动过（窗口 = 保持时长 + 淡出时长）
     * @param fade                {@link #fade} 的返回值
     * @return true = 本组可用保持亮度；false = 用逐组 {@link #strengthFor}
     */
    public static boolean useLastGlowForGroup(boolean overlayFadeOut, boolean groupRecentlyActive,
                                              float fade) {
        if (!overlayFadeOut) {
            return false;                 // 关闭淡出：随弹簧自然衰减，没有保持阶段
        }
        if (!(fade > 0f)) {
            return false;                 // 淡出结束 / NaN：不画
        }
        return groupRecentlyActive;
    }

    /**
     * 本组这一帧最终交给绘制层的辉光强度（生产与预览共用的唯一入口）。
     *
     * <p>两种状态：
     * <ul>
     *   <li>本组最近活动过 → 保持亮度（{@code lastGlowStrength} 在最后一次移动帧刚刷新，
     *       与逐组曲线连续，不会闪）；</li>
     *   <li>本组从未活动 / 已过淡出窗口 → 逐组曲线 {@link #strengthFor}，
     *       静止组自然是 0，不发光。</li>
     * </ul>
     *
     * <p><b>为什么不能加「偏移必须归零才保持」的条件</b>：弹簧收敛到 0.5px 以下需要时间，
     * 淡出开始时往往还有约 0.7px 残余。若此时退回逐组曲线，强度只剩 0.088 ——
     * 又变成「从 1.5% 淡到 0」，正是 B-4 要修的观感。判据只看「本组是否最近活动过」。
     *
     * @param offset              本组四角相对目标的最大偏移（像素）
     * @param holdGlow            保持亮度（调用方记录的「最后一次动作时的强度」）
     * @param overlayFadeOut      淡出总开关
     * @param groupRecentlyActive 本组最近是否活动过
     * @param fade                {@link #fade} 的返回值
     * @return 0~1 的辉光强度
     */
    public static float glowForGroup(double offset, float holdGlow,
                                     boolean overlayFadeOut, boolean groupRecentlyActive,
                                     float fade) {
        if (useLastGlowForGroup(overlayFadeOut, groupRecentlyActive, fade)) {
            return holdGlow;
        }
        return strengthFor(offset);
    }
}
