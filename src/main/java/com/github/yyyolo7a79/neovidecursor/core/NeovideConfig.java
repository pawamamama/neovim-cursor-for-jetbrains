package com.github.yyyolo7a79.neovidecursor.core;

/**
 * Neovide 拖尾光标配置。
 *
 * <p>字段语义与 VS Code 版 {@code neovide-cursor.js} 的 {@code cursorConfig} <b>完全对齐</b>，
 * 便于用户在两个平台间迁移配置、对比效果。命名刻意保留原始风格。
 *
 * <p>原始定义见：neo-cursor-fix/extension/assets/neovide-cursor.js 第 2-26 行
 */
public class NeovideConfig {

    /** 拖尾颜色（HEX 格式） */
    public String tailColor = "#FFC0CB";

    /** 拖尾不透明度（0~1） */
    public float tailOpacity = 1.0f;

    /** 是否启用辉光 */
    public boolean useShadow = true;

    /** 辉光颜色（HEX 格式） */
    public String shadowColor = "#FFC0CB";

    /**
     * 辉光大小系数：实际外扩半径 = 该系数 × 光标较长边。
     *
     * <p><b>本项偏离了原版 JS 的 0.6，这是有意为之。</b>
     * 原版 {@code cursorConfig.shadowBlurFactor = 0.6} 描述的是 Canvas
     * {@code shadowBlur}（高斯模糊的<i>直径</i>）—— 模糊会把能量摊开，
     * 图形越窄、峰值被稀释得越厉害。VS Code 的光标有 8px 宽，尚能撑住；
     * 而 IntelliJ 光标只有 2px 宽，同一数值直接当作膨胀半径使用时，
     * 会得到一团宽约 40px 的椭圆雾（是光标宽度的 20 倍），远谈不上"贴着光标发光"。
     *
     * <p>0.2 是按"光晕总宽与残影模式观感一致"（约 14px）反推得到的。
     * 想还原原版那种夸张的大范围光雾，把它调回 0.6 即可。
     */
    public float shadowBlurFactor = 0.2f;

    /**
     * 辉光层数：层数越多渐变越平滑。
     *
     * <p><b>层数不影响光晕整体亮度</b> —— 每层的独立不透明度会按层数反解
     * （{@code A = 1 - (1-peak)^(1/N)}），保证 N 层叠加后峰值恒等于
     * {@link #glowOpacity}。加层只让过渡更细腻，减层不会变暗。
     *
     * <p><b>为什么从 12 降到 6</b>：JFR 采样显示 {@code CaretTrailPanel.drawQuadGlow}
     * 里的 {@code g2.fill(path)} 占 EDT 采样的 <b>9.3%</b>，是全插件最大热点。
     * 每一层都要走 {@code AAShapePipe} 抗锯齿 + {@code MaskFill} alpha 混合，
     * 而这两者都在 <b>CPU</b> 上完成 —— Java2D 的 GPU 管线不加速
     * {@code fill(Path2D)}，所以只能靠减少层数来降成本。
     *
     * <p><b>观感代价极小</b>：辉光外扩半径 = {@code shadowBlurFactor × 行高}
     * ≈ 0.2 × 32 = 6.4px。12 层时每层步进 0.53px，6 层时 1.07px ——
     * 在 6.4px 的范围内，1px 的步进肉眼几乎无法分辨。
     *
     * <p>若确实看出硬边，调到 8 即可（成本仍比 12 低三分之一）。
     */
    public int glowLayers = 8;

    /**
     * 辉光峰值不透明度（只影响光晕，不影响光标主体）。
     * 即紧贴光标处光晕的最终不透明度，向外的衰减均以此为基准。
     */
    public float glowOpacity = 0.55f;

    // ==================== 常驻刷新 ====================

    /**
     * 是否让调度器<b>永远保持高帧率</b>，不再在空闲时降频。
     *
     * <p><b>它解决的是什么</b>：默认策略下，光标停止后调度间隔会从 8ms 涨到
     * {@code IDLE_DELAY_MS}(120ms) 甚至 {@code IDLE_UNFOCUSED_DELAY_MS}(600ms)。
     * 这是省电设计，但代价是<b>停手后第一次操作会"冷启动"</b>：按下删除到真正
     * 画出第一帧之间，最长可能要等一个空闲周期；即使随后的
     * {@code lastNanos} / {@code smoothedDt} 都已归位，
     * 那一帧的时序本身仍然是"久等一帧才动"。
     *
     * <p>开启后，无论有没有动画都按 {@code ACTIVE_DELAY_MS}(8ms, 约 125fps) 运行，
     * 冷启动延迟被彻底消除 —— 任何时候按键，下一帧最多 8ms 就到。
     *
     * <p><b>代价（务必知悉）</b>：调度线程会每秒向 EDT 投递约 125 次任务，
     * 且<b>永不停歇</b>。EDT 是单线程，编辑器繁忙时这些任务会和编辑器抢时间。
     * 实测 EDT 上已有约 12.6% 被 {@code ScaledBlit} 等 IDE 自身绘制占用，
     * 本插件占约 10.7%；常驻后这一份只增不减。
     * <b>开标签页很多、或电脑较慢时，可能反而更卡。</b>
     *
     * <p><b>本版默认开启（true）。</b>
     *
     * <p>实测结论：不开常驻刷新时，空闲降频会让「停手后第一次操作」出现
     * 100ms 级的延迟，肉眼可辨；而 EDT 抢占本来就存在（IDE 自身绘制占 12.6%），
     * 常驻刷新带来的额外负担在实践中小于它消除的等待。
     *
     * <p>因此本版把它设为默认，且<b>不再提供菜单开关</b> —— 用户已验证
     * 「常驻刷新挡位非常舒适」，保留一个永远该开着的开关只会造成困惑。
     * 需要关闭时可临时改本字段后重新构建。
     */
    public boolean alwaysHighFps = true;

    /** 光标停止移动后，延迟多久淡出（毫秒） */
    public int cursorDisappearDelay = 50;

    /** 光标淡出时长（秒） */
    public float cursorFadeOutDuration = 0.075f;

    /** 常规动画时长（秒）—— 数值越大拖尾越长。原版：0.10 */
    public float animationLength = 0.1f;

    /**
     * 短距离移动时的动画时长（秒）—— 打字 / 退格 / 同行删除。
     *
     * <p><b>本版取 0.11</b>。取值沿革：
     * <ul>
     *   <li>原版 VS Code 是 <b>0.04</b> —— 在 60fps + 8px 粗光标下够用</li>
     *   <li>但本插件 8ms 帧率（125fps）+ 2px 细光标，0.04 只有 5 帧，
     *       四个角时长过于接近，<b>拉伸几乎看不出来</b></li>
     *   <li>0.08 时观感「输入舒服」，但单行拉伸仍然太弱</li>
     *   <li><b>0.11</b>：rank0 达 110ms（13.8 帧），后缘/前缘 4.5 倍，
     *       拉伸清晰可见，同时前缘仍只有 24ms（3 帧）不拖沓</li>
     * </ul>
     *
     * <p>参考 DSH 主题的 {@code --dsh-claude-duration-instant = 80ms}（输入框光标），
     * 但那是纯 {@code transform} 平移、无拉伸，本插件要同时表现拉伸故取更长。
     */
    public float shortAnimationLength = 0.11f;

    /**
     * 拖尾密度（0~1）。原版 {@code ANIMATION_SETTINGS.trailSize}，默认 1。
     *
     * <p>它决定「前缘」与「后缘」的动画时长差异：
     * <pre>
     *   leading  = animationLength × (1 - trailSize)   → trailSize=1 时为 0
     *   trailing = animationLength                      → 恒为 animationLength
     * </pre>
     *
     * <p>trailSize=1（原版默认）：前缘瞬间到位、后缘慢拖，对比最强、拖尾最明显。
     * trailSize=0：四角同时长，无拖尾、接近纯平移。
     */
    public float trailSize = 1f;

    // ==================== 单行（打字）四角滞后系数 ====================
    //
    // 这组系数【只用于短跳分支】（打字 / 退格 / 同行删除），
    // 跨行跳转仍走原版 trailSize 三档逻辑，互不影响。

    /**
     * 四个角点的滞后系数（rank 0 = 最靠后、最慢；rank 3 = 最靠前、最跟手）。
     *
     * <p>短跳分支的实际时长 = {@link #shortAnimationLength} × 该系数。
     *
     * <p><b>为什么需要它</b>：原版的短跳分支给四角【同一个】时长，
     * 四角等速 → 形状变化只由起点差异决定 → <b>拉伸几乎看不见</b>。
     * 实测反馈「单行光标拉伸几乎没法看」正是这个原因。
     *
     * <p><b>本版取值 {@code {1.0, 0.72, 0.42, 0.22}}</b>：配合
     * {@code shortAnimationLength = 0.11}，四角为
     * <pre>
     *   rank0 = 110ms (13.8 帧)   后缘，慢拖
     *   rank1 =  79ms ( 9.9 帧)
     *   rank2 =  46ms ( 5.8 帧)
     *   rank3 =  24ms ( 3.0 帧)   前缘，紧跟
     * </pre>
     * 后缘/前缘达 <b>4.5 倍</b>，拉伸清晰；而前缘只剩 3 帧，不会拖沓。
     *
     * <p>早版曾用 {@code {1.0, 0.9, 0.5, 0.3}} —— rank0 与 rank1 只差 8ms，
     * 后缘近乎刚性，这是「拉伸看不见」的直接原因。
     */
    public float[] trailFactors = {1.0f, 0.72f, 0.42f, 0.22f};

    /** 短距离阈值（单位：光标尺寸倍数）—— 仅在拿不到字符偏移时的兜底判据 */
    public float shortMoveThreshold = 8f;

    /**
     * 短跳判定的字符数上限：移动不超过几个字符时，视为「打字类」移动。
     *
     * <p>判据：{@code |本次offset差| ≤ 本值} 且 纵向在同一行。
     *
     * <p><b>为什么用字符偏移（offset）而不是像素</b>：
     * 编辑器本来就用 offset 定位，差值是精确的字符个数，
     * 与字体、字号、全角半角、连字完全无关。像素反推的做法反复失败：
     * <ul>
     *   <li>写死 16px：字号 20px 时中文约 20px，删一个中文就超出</li>
     *   <li>FontMetrics('m')：西文推进量，中文是 2 倍</li>
     *   <li>第0列→第1列实测宽度：取的是<b>行首字符</b>，
     *       行首是窄英文、光标在中文区时仍会翻倍</li>
     * </ul>
     * 三次都导致「英文丝滑、中文卡」。
     *
     * <p>取 3 的含义：连续快删 1~3 个字符仍走短跳分支（有拉伸、平滑），
     * 4 个以上视为跳转，走跨行分支（前缘瞬时吸附的大幅拖尾）。
     * 早期取 2 会导致连删 3 个字符就切换分支、拉伸暴涨 4.5 倍。
     */
    public float typingMaxChars = 3f;

    // ==================== 以下为历史遗留，保留字段避免破坏引用 ====================

    /** @deprecated 已被 {@link #typingMaxChars} 取代 */
    @Deprecated
    public float shortJumpMaxChars = 2.0f;

    /** @deprecated 已被 {@link #typingMaxChars} 取代 */
    @Deprecated
    public float typingMergeChars = 6.0f;


    /** @deprecated 原版无硬吸附，改由 trailSize 让前缘时长为 0 */
    @Deprecated
    public boolean useHardSnap = true;

    /** @deprecated 原版无此系数 */
    @Deprecated
    public float leadingSnapFactor = 0.1f;

    /** 动画重置阈值：时长小于该值时不重置弹簧，避免抖动 */
    public float animationResetThreshold = 0.09f;

    /**
     * 拖尾最大位移系数：单个角点相对目标的最大偏移 = 该系数 × 光标较长边。
     *
     * <p>本项已与原版 JS <b>严格对齐</b>（60，约 1920px）。
     *
     * <p><b>注意这是一项性能取舍</b>：该系数只做「限幅」，对相邻行等常规移动
     * <b>完全无影响</b>（偏移量远达不到阈值）；它只在跨多行跳转时起作用 ——
     * 值越大，拖尾越完整、越接近原版观感，但 Swing 覆盖层必须局部重绘，
     * 偏移越大重绘包围盒越大，跨十行以上跳转时可能退化成接近全屏的重绘。
     *
     * <p>若跳转时明显掉帧，把本值调小即可（曾用过 4 = 约 128px，
     * 代价是跨行拖尾会被截断，正常移动观感不受影响）。
     */
    public float maxTrailDistanceFactor = 60f;

    /** 硬吸附时的动画时长（秒） */
    public float snapAnimationLength = 0.02f;

    /** 光标宽度（像素）—— IntelliJ 默认光标较细 */
    public float cursorWidth = 2f;

    /**
     * 是否隐藏原生光标（改全局配色的 CARET_COLOR 为透明）。
     *
     * <p><b>【上游踩坑 #19】本项默认改为 false —— 保留原生光标。</b>
     *
     * <p>上游原话：「拖尾不该当光标用」。隐藏原生光标后，<b>肉眼看到的「光标」
     * 就是覆盖层</b>，于是「光标何时出现在新位置」完全由调度器决定：
     * <ul>
     *   <li>默认档空闲降频到 120ms（失焦 600ms）
     *       → 停手后第一次删除要等一个空闲周期 → 用户感知「卡」</li>
     *   <li>常驻刷新恒 8ms → 该等待消失 → 看起来就正常</li>
     * </ul>
     * 这正是「开常驻刷新就好、不开就卡」的<b>结构性原因</b>，
     * 换任何动画参数都治不了 —— 因为问题不在动画，在「谁负责跟随」。
     *
     * <p>改为 false 后：原生光标负责<b>零延迟跟随</b>（IDE 自己绘制，不受我们的
     * 调度影响），覆盖层只在移动时叠加拖尾、停下后淡出。
     * 这同时消掉踩坑 #18 的根源（不再改全局配色）和控制台补画光标的一半工作量。
     *
     * <p>仍可用 Tools 菜单「隐藏原生光标（常驻绘制）」切回旧行为。
     */
    public boolean hideNativeCaret = false;

    /** 全局开关：关掉后立即恢复原生光标与干净界面 */
    public boolean enabled = true;

    // ===== 渲染模式 =====

    /** 拖尾渲染模式：弹簧（Neovide 原版效果）或残影（低帧率更稳） */
    public TrailMode trailMode = TrailMode.SPRING;

    /**
     * 残影存活时长（秒）：仅 {@link TrailMode#AFTERIMAGE} 使用。
     * 数值越大，拖尾越长。
     */
    public float afterimageLifetime = 0.22f;

    /** 拖尾渲染模式 */
    public enum TrailMode {
        /** 弹簧模型：光标被拉伸成四边形 —— Neovide 原版效果 */
        SPRING,
        /**
         * 残影模型：一串渐隐的残影。
         * 它记录的是每帧的真实位置而非帧间插值，因此低帧率下更稳定。
         */
        AFTERIMAGE
    }
}
