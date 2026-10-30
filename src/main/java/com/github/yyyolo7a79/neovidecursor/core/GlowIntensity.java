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
}
