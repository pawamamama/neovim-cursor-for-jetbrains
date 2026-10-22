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
     * 短跳分支的<b>前缘</b>时长下限（秒）。0 = 不启用（沿用旧行为）。
     *
     * <h2>为什么需要这个字段（C 方案：前缘与后缘解耦）</h2>
     *
     * <p>短跳时四角时长原本都是 {@code shortAnimationLength × trailFactors[rank]}。
     * 这导致一个<b>结构性矛盾</b>（第三轮审查实测证明）：
     * <pre>
     *   要「平滑」（前缘跨够帧）  →  shortAnimationLength 必须大
     *   要「拖尾短」（后缘别太长）→  shortAnimationLength 必须小
     *   ↑ 两者共用同一个乘数，数学上不可能同时满足
     * </pre>
     *
     * <p>实测（k=8, smooth=0）：前缘 ≥10 帧需 short ≥ 0.229s，
     * 而后缘滞后 ≤8px 需 short ≤ 0.036s —— 相差 0.193s，区间不相交。
     *
     * <h2>本字段怎么解耦</h2>
     *
     * <p>在短跳分支里，若本值 &gt; 0，则<b>前缘两角（rank 2/3）</b>的时长取
     * {@code max(原时长, 本值)}；后缘两角（rank 0/1）仍用
     * {@code shortAnimationLength × trailFactors[rank]} 不变。
     *
     * <p>于是：
     * <ul>
     *   <li>调大本值 → 前缘跨更多帧 → <b>更平滑</b>，且<b>完全不动后缘</b>；</li>
     *   <li>调小 {@code shortAnimationLength} → 后缘更短 → <b>拖尾更短促</b>，且前缘被本值托住不跳。</li>
     * </ul>
     *
     * <h2>为什么默认 0</h2>
     *
     * <p><b>0 表示完全不启用</b>，四角时长与历史版本逐位相同 ——
     * 这样 parity 的逐字节基线在默认值下仍然成立，不会因为"新增了一个字段"就让
     * 所有物理快照全变。只有在预设里显式设了非 0 值才生效。
     *
     * <p>范围与 {@link #shortAnimationLength} 同量级。
     */
    public float leadingAnimationLength = 0f;

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

    /** 动画重置阈值：时长小于该值时不重置弹簧，避免抖动 */
    public float animationResetThreshold = 0.09f;

    // ==================== 辉光淡出（F2 / 批次2）====================

    /**
     * 是否启用辉光淡出。
     *
     * <p>开启后，拖尾停止运动时辉光不再"啪一下消失"，而是先保持
     * {@link #cursorDisappearDelay} 毫秒，再用 {@link #cursorFadeOutDuration}
     * 秒线性熄灭。
     *
     * <p><b>只淡辉光，不淡光标本体。</b>光标本体始终不透明 —— 因为默认档
     * （{@link #hideNativeCaret} = true）下原生光标已隐藏，覆盖层就是肉眼看到的
     * "光标"，整体淡出会变成"光标自己消失"。
     *
     * <p>关闭时行为与 0.9.1 完全一致（辉光瞬时归零），可随时在 Tools 菜单切换。
     */
    public boolean overlayFadeOut = true;

    /**
     * 辉光淡出前的保持时长（毫秒）：拖尾停止后，辉光保持满强度这么久，
     * 之后才开始淡出。
     *
     * <p>取值与上游 {@code 6bed09d} 一致（50ms）。调大 = 停止后辉光停留更久。
     */
    public int cursorDisappearDelay = 50;

    /**
     * 辉光淡出时长（秒）：从满强度线性降到 0 所花的时间。
     *
     * <p>取值与上游 {@code 6bed09d} 一致（0.075s = 75ms）。
     * 配合 {@link #cursorDisappearDelay}，停止后约 125ms 完全熄灭。
     */
    public float cursorFadeOutDuration = 0.075f;

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

    /** 光标宽度（像素）—— IntelliJ 默认光标较细 */
    public float cursorWidth = 2f;

    /**
     * 是否隐藏原生光标（改全局配色的 CARET_COLOR 为透明）。
     *
     * <p><b>【批次2 / F3】默认值改为 true —— 此处有意偏离上游踩坑 #19。</b>
     *
     * <p>上游踩坑 #19 的结论是「拖尾不该当光标用」，理由是：隐藏原生光标后，
     * 肉眼看到的「光标」就是覆盖层，于是「光标何时出现在新位置」完全由调度器决定：
     * <ul>
     *   <li>默认档空闲降频到 120ms（失焦 600ms）
     *       → 停手后第一次删除要等一个空闲周期 → 用户感知「卡」</li>
     *   <li>常驻刷新恒 8ms → 该等待消失 → 看起来就正常</li>
     * </ul>
     *
     * <p><b>为什么本机反过来选择 true</b>：本插件的 {@link #alwaysHighFps} 默认为
     * {@code true}，调度恒为 8ms（约 125fps），上游担心的「空闲降频导致跟随滞后」
     * 在这台机器上并不存在。而保留原生光标时，用户实测<b>「取消勾选之后卡一点」</b>——
     * 因为此时 IDE 要画原生光标、插件同时画覆盖层，两套绘制叠加，反而更重。
     *
     * <p>这是<b>用户实测胜过上游结论</b>的一例，故默认改为 {@code true}。
     * 仍可通过 Tools 菜单「隐藏原生光标（常驻绘制）」随时取消勾选、切回叠加模式。
     */
    public boolean hideNativeCaret = true;

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
