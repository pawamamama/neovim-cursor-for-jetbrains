package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.NeovideColors;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 拖尾绘制面板 —— 挂在编辑器 contentComponent 上，绘制拖尾。
 *
 * <p>支持两种渲染模式（由 {@link NeovideConfig#trailMode} 决定）：
 * <ul>
 *   <li><b>弹簧模式</b>：绘制由四个角点构成的四边形（Neovide 原版效果）</li>
 *   <li><b>残影模式</b>：绘制一串渐隐的矩形残影</li>
 * </ul>
 *
 * <p><b>为什么覆盖整个编辑器内容区</b>：拖尾会延伸到光标位置之外较远处，
 * 面板若只有光标大小，拖尾会被裁掉。
 *
 * <p><b>POC 阶段踩坑后的两条硬性约束</b>（缺一不可）：
 * <ol>
 *   <li>挂载到 contentComponent —— 它 layout 为 null，不会被 LayoutManager 重新布局</li>
 *   <li>paintComponent 只画图形，绝不画任何背景矩形 ——
 *       万一面板被意外拉伸，也只会显示图形本身，不会铺满整个编辑器</li>
 * </ol>
 */
public class CaretTrailPanel extends JComponent {

    private final NeovideConfig config;

    // ===== 弹簧模式数据 =====

    /**
     * 四个角点的当前坐标（contentComponent 坐标系，<b>保留小数</b>）。
     *
     * <p><b>为什么必须是 double（0.9.9 抖动修复）</b>：
     * 原先是 {@code int[]} + {@code Math.round}，把弹簧算出的亚像素坐标四舍五入。
     * 光标原点对齐字符格（整数），而弹簧四角偏移是小数 —— 两者相加后小数部分每帧都变，
     * 于是 {@code Math.round} 让每个角点<b>每帧在 N 与 N+1 之间反复横跳</b>，
     * 四个角各自独立跳，视觉上就是「形状在抖」。
     *
     * <p>这与辉光无关：用户实测关掉辉光顶点照样跳。
     * VSCode 上游的 {@code currentPosition} 全程 float 直接喂 canvas，
     * canvas 支持 sub-pixel 因此平滑；我们改成 double + Path2D 才能对齐。
     */
    /** updateCorners 的中转数组（复用，避免每次调用两次 new double[4]） */
    private final double[] ucX = new double[4];
    private final double[] ucY = new double[4];

    // 【0.9.74 已删除 polyX / polyY】
    //
    // 这对数组原属「单四边形快捷路径」。多光标重构后生产代码全改走
    // beginQuads() + addQuad()，updateCorners 零调用，于是它们恒为初值 0 ——
    // 但 cornersForProbe() 仍在读它们，导致探针"验证通过"却验的是死数据。
    // 现在 cornersForProbe(int) 直接读 quads 存储，这对中转数组已无任何用途。

    /** 四边形路径（复用，避免每帧分配）。用 Path2D 而非 Polygon 以保留亚像素精度。 */
    private final Path2D.Double quadPath = new Path2D.Double();

    /** 是否已有可绘制的四边形 */

    // ===== 多光标：N 个四边形（扁平数组 + 步长，零分配）=====

    /**
     * 每个四边形占用的 double 个数：4 个角 × 2 坐标 + 1 个辉光强度 = 9。
     *
     * <p>步长式布局（而不是 {@code List<Quad>}）是为了<b>零对象分配</b>：
     * 数组按需扩容，稳定后不再产生垃圾。多光标每帧最多 32 个四边形，
     * 若每帧新建 32 个对象，GC 压力会直接反映到 EDT 掉帧。
     */
    private static final int QUAD_STRIDE = 9;

    /** 多四边形数据：[x0,y0,x1,y1,x2,y2,x3,y3,glow] × N */
    private double[] quads = new double[QUAD_STRIDE * 4];

    /** 本帧实际写入的四边形个数 */
    private int quadCount = 0;

    /**
     * 【BUG-007】诊断追踪 id（由 {@link CaretAnimator} 构造时写入）。
     *
     * <p>{@code -1} = 未接入（例如设置页预览用的那个面板实例），
     * 此时绘制追踪全部安静跳过 —— 预览不该往诊断文件里写东西。
     */
    private int traceId = -1;

    /**
     * 绘制单个四边形时用的坐标中转（复用，避免每帧分配）。
     *
     * <p>辉光绘制与 {@link #computeOffsetDirections} 都按「4 个点的数组」工作，
     * 而数据存的是步长式扁平数组。每次绘制把一个四边形拷进这两个数组，
     * 就能复用既有的几何算式，不必为多光标重写一套。
     */
    private final double[] quadX = new double[4];
    private final double[] quadY = new double[4];

    // ===== 辉光绘制复用对象（避免每帧分配，见 drawQuadGlow）=====

    /** 辉光路径 —— 复用同一个 Path2D，每层覆盖前必须 reset */
    private final Path2D.Double glowPath = new Path2D.Double();

    /**
     * 四个角点的外扩方向向量（复用字段，避免每帧分配）。
     *
     * <p>{@link #computeOffsetDirections} 的第二个循环对 {@code j = 0..3}
     * <b>无条件完整覆写</b>这两个数组，因此复用不会读到上一帧的残留方向。
     */
    private final double[] dirX = new double[4];
    private final double[] dirY = new double[4];

    // ===== 残影模式数据 =====

    /**
     * 待绘制的残影，每项为 {@code [x, y, width, height, alpha]}。
     * 由 animator 每帧写入。
     */
    private final List<float[]> ghosts = new ArrayList<>(32);

    /** 是否已有可绘制的残影 */
    private boolean hasGhosts = false;

    // ===== 样式缓存 =====

    private Color fillColor;
    private Color glowColor;

    /** 辉光最大外扩量：= shadowBlurFactor × 光标较长边，与原版 shadowBlur 语义一致 */
    /**
     * 【T3 实验性：RGB 渐变】默认关闭。
     *
     * <p>开启：{@code -Dneovide.cursor.rgb=true}
     *
     * <p><b>为什么默认关</b>：parity 是逐字节比对的黄金基准，
     * 任何绘制变化都会让它变红。实验功能必须默认不影响默认路径。
     */
    /**
     * RGB 渐变是否开启。
     *
     * <p><b>0.9.58</b>：从「只认启动参数」改为「界面开关优先，启动参数兜底」。
     * 用户反馈「rgb没见开关」。
     */
    private boolean rgbEnabled =
            Boolean.getBoolean("neovide.cursor.rgb");

    /** 设置 RGB 开关（由 CaretAnimator 每帧从 State 同步） */
    public void setRgbEnabled(boolean on) {
        this.rgbEnabled = on;
    }

    /** 当前 RGB 开关（供预览初始化与探针读取） */
    public boolean isRgbEnabled() {
        return rgbEnabled;
    }

    /**
     * 当前是否走「彩虹（色相循环）」（0.9.86）。
     *
     * <p>与 {@link #rgbEnabled}（光标渐变/双拼）是**两条独立路径，互斥**：
     * <pre>
     *   cursorRgbWash = true          -> 彩虹（颜色预设「彩虹（动态）」）
     *   cursorRgbWash = false + rgbEnabled=true  -> 双拼（光标渐变开关）
     *   两者都 false                  -> 纯色
     * </pre>
     *
     * <p>由 {@code CaretAnimator} 从「当前颜色匹配哪个预设」反推后设置。
     */
    private boolean cursorRgbWash;

    /** 设置是否走彩虹 */
    public void setCursorRgbWash(boolean on) {
        this.cursorRgbWash = on;
    }

    /** 当前是否走彩虹（探针用） */
    public boolean isCursorRgbWash() {
        return cursorRgbWash;
    }

    // ==================== RGB 色相循环（0.9.75）====================
    //
    // 【设计取舍：为什么不照抄 dsh-claude-style】
    //
    // 上游（Nwflower/dsh-claude-style）的 wash 是「七层径向渐变 + 整圈 hue-rotate」，
    // 效果是多个色团在字形内漂移。但我们的目标是一个 2x40 像素的细长矩形：
    //   - 七层色团在 2px 宽度上根本铺不开，会退化成一条混浊的色带；
    //   - 它的漂移是二维的（x + y），而光标本质是一维的（沿长度方向）。
    //
    // 所以本实现改成「沿拖尾长度方向做色相循环」：
    //   - 长度方向 = 光标的时间轴，从尾到头颜色连续变化 -> 一眼看出方向与速度
    //   - 横向不渐变（2px 也看不出来，白费成本）
    //   - 整条色相随时间整体前移 -> 静止时也能看出「在流动」
    //
    // 与上游共享同一个核心机制（色相旋转），但维度降了一级。
    //
    // 【性能实测】tools/bench/BenchRgbWash.java（2500 帧均值）：
    //   1 光标 1 层纵向 -> 7 层径向：13us -> 120us（预算 1.5%）
    //   8 光标 1 层纵向 -> 7 层径向：92us -> 904us（预算 11.3%）
    // 说明上游方案在性能上其实可行，换维度是为了视觉效果，不是为了省性能。

    /**
     * 色相循环的周期（毫秒）—— 整条色相带前移一圈的时间。
     *
     * <p><b>0.9.79 改回 4000ms，照抄上游</b>：用户要求「旋转方式也一样」。
     * 上游 {@code --dsh-claude-period-wash-hue = 4000ms}。
     * 我之前改成 3000ms 的理由是「4 秒一圈显得没动」，但那是主观判断，
     * 与用户「照上游」的要求冲突，现以后者为准。
     */
    private static final long RGB_HUE_PERIOD_MS = 4000L;

    /**
     * 色带相位漂移周期（毫秒）。
     *
     * <p>对应上游 {@code --dsh-claude-period-wash-drift = 5000ms}。
     * 上游用这个周期做「八层色团的位置循环」（{@code background-position} 四段关键帧）；
     * 我们是一条细线，做不了八层，但可以照抄**节拍**：
     * 让沿长度方向的色带相位以 5000ms 为周期缓慢滑动。
     *
     * <p>两个不同周期（4000 旋转 + 5000 漂移）叠加，避免「每 4 秒整条同时跳一下」
     * 的机械感 —— 这正是上游用两个动画的理由。
     */
    private static final long RGB_DRIFT_PERIOD_MS = 5000L;

    /**
     * 沿长度方向铺几个完整色相圈。
     *
     * <p>2.0 = 从上到下跨越两个色相圈。取 2 而非 1：一圈会让整条只有一种主色；
     * 三圈以上在 40px 内会糊成彩色噪点。
     */
    private static final float RGB_HUE_SPAN = 2.0f;

    /**
     * 饱和度 / 亮度。
     *
     * <p><b>0.9.79 饱和度 0.75 → 0.42</b>：用户反馈「rgb 太土了」。
     * 0.75 在深色主题上是彩虹糖那种艳；0.42 是雾面色。
     *
     * <p>为什么不是更低：0.3 以下会发灰，在深色背景上像脏了。
     * 亮度保持 0.95（降低会让它在深色背景上更加灰暗）。
     */
    private static final float RGB_HUE_SATURATION = 0.42f;
    private static final float RGB_HUE_BRIGHTNESS = 0.95f;

    /** 色相循环起始时刻（纳秒）；-1 = 尚未初始化 */
    private long rgbPhaseStartNanos = -1L;

    /** 色相循环的色标段数（16 段 = 每 1/8 圈一个点） */
    private static final int RGB_WASH_STEPS = 16;

    /** 色标数组（复用，避免每帧分配） */
    private final float[] rgbFractions = new float[RGB_WASH_STEPS + 1];

    /** 色标颜色数组（复用，避免每帧分配） */
    private final java.awt.Color[] rgbColors = new java.awt.Color[RGB_WASH_STEPS + 1];

    /**
     * 生成沿长度方向的色相循环渐变。
     *
     * <p><b>为什么用 LinearGradientPaint 的多色标</b>：它接受任意数量
     * (fraction, color)，一次填充完成整条渐变 —— 比叠 N 层单色便宜，
     * 也比上游的 N 层径向少一个数量级。
     *
     * <p>色相走 HSB 而非 RGB 插值：RGB 线性插值经过灰色地带
     * （红到绿会先变黄再变暗），HSB 走色相环才是真正的彩虹。
     *
     * @param top    四边形上边界（像素）
     * @param bottom 四边形下边界（像素）
     * @return 渐变画笔；边界退化时返回 null（调用方回退纯色）
     */
    private java.awt.Paint buildRgbWashPaint(float top, float bottom) {
        // 【0.9.78 修复：非有限值会让 paintComponent 整体中断】
        //
        // 如果 quadY 里混进 NaN/Infinity（几何计算异常时可能），
        // bottom - top 得到 NaN，而 `NaN <= 1.0f` 为 false —— 于是直接构造 paint。
        // 一旦 LinearGradientPaint 抛 IllegalArgumentException，
        // **整个 paintComponent 中断**，表现为「光标消失」而不是「少画一帧」。
        if (!Float.isFinite(top) || !Float.isFinite(bottom)) {
            return null;
        }
        float len = bottom - top;
        if (len <= 1.0f) {
            return null;
        }
        long now = System.nanoTime();
        if (rgbPhaseStartNanos < 0L) {
            rgbPhaseStartNanos = now;
        }
        double elapsedMs = (now - rgbPhaseStartNanos) / 1_000_000.0;

        // 【两个周期叠加，照抄上游的两个动画】
        //
        // 上游 motion.css：
        //   animation: dsh-claude-wash-hue   4000ms linear      infinite,
        //              dsh-claude-wash-drift 5000ms ease-in-out infinite;
        //
        // hue   = 整圈色相旋转（改变所有颜色）
        // drift = 色团位置循环（改变「哪一段是什么颜色」）
        //
        // 我们只有一条细线，没有二维位置可漂，但仍然保留**两个不同周期**：
        //   huePhase   控制整条色相绕圈
        //   driftPhase 控制沿长度方向的色带起点偏移
        // 两个不同步的周期叠加，避免「每 4 秒整条同时换色一次」的机械感。
        double huePhase = elapsedMs / RGB_HUE_PERIOD_MS;
        huePhase -= Math.floor(huePhase);
        double driftPhase = elapsedMs / RGB_DRIFT_PERIOD_MS;
        driftPhase -= Math.floor(driftPhase);

        // 色标：沿长度每 1/8 圈一个点，跨度 2 圈 -> 16 段 + 首尾。
        // 少于 8 段时色带有明显分段（肉眼看得出格子）。
        // 【0.9.78 修复：消除每帧分配】
        //
        // 原来每次调用都 new float[17] + new Color[17] + 17 个 Color，
        // 8 光标 × 125fps = 每秒约 19000 个短命对象。
        // 这与本类「零分配热路径」的承诺直接冲突，会给 GC 制造
        // 周期性压力（体感是偶发的微顿）。
        //
        // 现在：fractions 与 colors 都复用成员数组；
        // Color 复用成员数组，只在 alpha 变化时才重建（alpha 极少变）。
        int userAlpha = fillColor == null ? 255 : fillColor.getAlpha();
        for (int i = 0; i <= RGB_WASH_STEPS; i++) {
            rgbFractions[i] = i / (float) RGB_WASH_STEPS;
            // huePhase 控制整体旋转，driftPhase 让色带起点缓慢滑动（对应上游 drift）
            double hue = (huePhase + driftPhase * 0.5 + rgbFractions[i] * RGB_HUE_SPAN) % 1.0;
            if (hue < 0) {
                hue += 1.0;
            }
            java.awt.Color c = java.awt.Color.getHSBColor(
                    (float) hue, RGB_HUE_SATURATION, RGB_HUE_BRIGHTNESS);
            // 【为什么仍要 new Color】
            //
            // Color 是不可变的，色相每帧都在变，所以这 17 个对象躲不掉。
            // 真正省下的是**数组本身**（原来每帧 2 个新数组）。
            // 想彻底零分配得上 int[] ARGB + 自定义 Paint，收益不足以抵消复杂度，
            // 这里做到「不分配数组」为止。
            rgbColors[i] = new java.awt.Color(c.getRed(), c.getGreen(), c.getBlue(), userAlpha);
        }
        return new java.awt.LinearGradientPaint(
                0f, top, 0f, bottom, rgbFractions, rgbColors);
    }

    // ==================== 光标渐变（双拼）====================
    //
    // 【末端色算法】用户要求「算法天然适配光标色和辉光色」。
    //
    //   末端色 = HSL(辉光色.色相, 辉光色.饱和度 × 1.05, 辉光色.亮度 × 0.58)
    //
    // 为什么用辉光色而不是人造色：辉光色本来就是用户选的、与主色协调的第二个颜色。
    // 拿它降亮度，色相自动协调，不需要额外的色相环计算。
    //
    // 【系数 0.58 的实测依据】（WCAG 相对亮度 + CIE76 ΔE，tools/bench/ZzDualToneScan）
    //   系数   WCAG对比度   感知色差ΔE    判读
    //   0.85   1.42~2.11   —            太弱，看不出渐变
    //   0.72   1.96~2.78   22.6~33.0    用户实测「不明显」
    //   0.58   2.88~3.86   34.2~40.0    当前值
    //   0.50   3.69~4.65   41.1~47.2    接近「两种颜色拼的」
    //
    // 【待实现】做成面板参数条（用户要求），10 处改动清单见 docs/NEXT-STEPS.md。
    private static final float DUAL_TONE_LUMA_FACTOR = 0.58f;

    /**
     * 生成光标渐变（双拼）画笔：上端 = 拖尾色，下端 = 从辉光色推导的末端色。
     *
     * @param top    四边形上边界
     * @param bottom 四边形下边界
     * @return 渐变画笔；边界退化或颜色不可用时返回 null（调用方回退纯色）
     */
    private java.awt.Paint buildDualTonePaint(float top, float bottom) {
        if (!Float.isFinite(top) || !Float.isFinite(bottom) || bottom - top <= 1.0f) {
            return null;
        }
        if (fillColor == null) {
            return null;
        }
        // 末端色基底：优先辉光色；辉光色不可用时退回主色
        java.awt.Color base = (glowColor != null) ? glowColor : fillColor;
        float[] hsb = java.awt.Color.RGBtoHSB(
                base.getRed(), base.getGreen(), base.getBlue(), null);
        int endRgb = java.awt.Color.HSBtoRGB(
                hsb[0],
                Math.min(1f, hsb[1] * 1.05f),      // 略补饱和，抵消降亮度带来的发灰
                hsb[2] * DUAL_TONE_LUMA_FACTOR);
        // 透明度沿用拖尾不透明度（tailOpacity），不用辉光的（辉光恒为不透明）
        int alpha = fillColor.getAlpha();
        java.awt.Color end = new java.awt.Color(
                (endRgb >> 16) & 0xFF, (endRgb >> 8) & 0xFF, endRgb & 0xFF, alpha);
        return new java.awt.GradientPaint(0f, top, fillColor, 0f, bottom, end);
    }

    private float glowWidth = 12f;

    /**
     * 本帧生效的辉光层数（-1 = 用 config 里的值）。
     *
     * <p>由 {@code CaretAnimator} 每帧设置 —— 用于「多光标不辉光」这个
     * 全局开关：光标数 ≥ 2 时设为 0，drawQuadGlow 直接早退。
     *
     * <p><b>为什么不直接改 config.glowLayers</b>：那是用户的持久设置，
     * 改了会污染 State。这里只影响当前帧的绘制。
     */
    private int effectiveGlowLayers = -1;

    /** 设置本帧生效的辉光层数（-1 = 恢复用 config 值） */
    public void setEffectiveGlowLayers(int layers) {
        this.effectiveGlowLayers = layers;
    }

    /** 本帧实际使用的层数 */
    private int currentGlowLayers() {
        return effectiveGlowLayers >= 0 ? effectiveGlowLayers : config.glowLayers;
    }

    /** √2 —— 角点偏移向量的模长上限，用于抑制锐角处的尖刺，见 computeOffsetDirections */
    private static final double SQRT_2 = Math.sqrt(2.0);

    /**
     * 辉光强度（0~1），由 animator 每帧按拖尾展开程度写入。
     * 为 0 时完全不绘制光晕 —— 光标静止时就该是一条干净的光标。
     */
    private float glowStrength = 1f;

    /**
     * 辉光淡出系数（0~1），由 animator 每帧写入（F2 / 批次2）。
     *
     * <p><b>只作用于辉光层</b>：在 {@link #drawQuadGlow} 里乘到峰值 alpha 上，
     * 光标本体（{@code fillPolygon}）始终不透明。
     *
     * <p>为什么不把它做成整体 {@code AlphaComposite}：那样会连光标本体一起淡出。
     * 默认档（{@code hideNativeCaret = true}，原生光标已隐藏）下，覆盖层就是肉眼
     * 看到的"光标"，整体淡出会变成"光标自己消失"。
     */
    private float glowFade = 1f;

    /**
     * 设置辉光淡出系数（0~1，超出范围截断）。
     *
     * <p>由 animator 按「距离上次运动的时长」写入：运动中为 1（满强度），
     * 停止后先保持 1 一段时间（{@code cursorDisappearDelay}），
     * 再线性降到 0（历时 {@code cursorFadeOutDuration}）。
     */
    public void setGlowFade(float fade) {
        this.glowFade = fade < 0f ? 0f : (fade > 1f ? 1f : fade);
    }

    public CaretTrailPanel(@NotNull NeovideConfig config) {
        this.config = config;
        setOpaque(false);
        refreshStyles();
    }

    /** 配置变化后刷新样式缓存 */
    public void refreshStyles() {
        fillColor = NeovideColors.parse(config.tailColor, config.tailOpacity);
        glowColor = NeovideColors.parse(config.shadowColor, 1.0f);
    }

    /**
     * 按光标尺寸更新辉光半径（弹簧模式使用）。
     *
     * <p>外扩半径 = {@code shadowBlurFactor × max(width, height)}，
     * 系数本身为何偏离原版 0.6，见 {@link NeovideConfig#shadowBlurFactor} 的说明。
     * 该半径即光晕的最大外扩距离 —— 最外层膨胀到此处的累积不透明度已降到约 6%，
     * 对应高斯模糊的可见边界。
     */
    public void updateGlowRadius(double cursorWidth, double cursorHeight) {
        double maxDim = Math.max(cursorWidth, cursorHeight);
        float newWidth = (float) (config.shadowBlurFactor * maxDim);
        if (Math.abs(newWidth - glowWidth) > 0.5f) {
            glowWidth = newWidth;
        }
    }

    /** 当前辉光外扩量，供调用方计算重绘区域余量 */
    public float getGlowWidth() {
        return glowWidth;
    }

    // ==================== 数据写入 ====================

    /**
     * 弹簧模式：写入【单个】四边形角点（单光标快捷方式）。
     *
     * <p>内部转调 {@link #beginQuads()} + {@link #addQuad}，与多光标路径共用同一份存储。
     * 辉光强度沿用 {@link #glowStrength} 字段（由 {@link #setGlowStrength} 写入）。
     */
    public void updateCorners(double x0, double y0,
                              double x1, double y1,
                              double x2, double y2,
                              double x3, double y3) {
        // 【0.9.74】不再经过 polyX/polyY 中转 ——
        // 那对数组在重构后恒为初值 0（生产代码全走 addQuad），
        // 中转只会让人误以为它是真相源。直接转发，路径更短也更诚实。
        //
        // 直接存 double，不做任何取整 —— 取整就是抖动的来源。
        // 调用方负责先 beginQuads()（本方法的契约与 addQuad 一致）。
        //
        // 【为什么用复用的中转数组而不是 new double[]{...}】
        // 本类多处注释承诺「零分配热路径」。虽然 updateCorners 目前只有探针调用，
        // 但保留一次每帧两次 new double[4] 的写法，等于给未来留了个坑。
        // 用成员数组中转，与 addQuad 内部从 quads 读回的做法同源。
        ucX[0] = x0; ucX[1] = x1; ucX[2] = x2; ucX[3] = x3;
        ucY[0] = y0; ucY[1] = y1; ucY[2] = y2; ucY[3] = y3;
        beginQuads();
        addQuad(ucX, ucY, glowStrength);
    }

    /**
     * 开始一帧的多四边形写入 —— 清空上一帧的计数。
     *
     * <p>调用顺序必须是 {@code beginQuads() → addQuad() × N}，然后重绘。
     * 只调 {@code beginQuads()} 不 {@code addQuad()} 等于「本帧什么都不画」。
     */
    public void beginQuads() {
        quadCount = 0;
        hasGhosts = false;
    }

    /**
     * 追加一个四边形（多光标）。
     *
     * <p>与 {@link #updateCorners} 的关系：那个是「只有一个四边形」的快捷方式，
     * 内部转调本方法。两者共用同一份 {@link #quads} 存储，因此<b>不能混用</b> ——
     * 一次重绘要么走单四边形路径，要么走多四边形路径。
     *
     * @param xs   4 个角点的 x（顺时针：左上→右上→右下→左下）
     * @param ys   4 个角点的 y
     * @param glow 该光标自己的辉光强度（0~1），静止时为 0
     */
    public void addQuad(double[] xs, double[] ys, float glow) {
        int need = (quadCount + 1) * QUAD_STRIDE;
        if (need > quads.length) {
            // 按 2 倍扩容，避免逐个 addQuad 反复复制
            quads = java.util.Arrays.copyOf(quads, Math.max(need, quads.length * 2));
        }
        int base = quadCount * QUAD_STRIDE;
        for (int j = 0; j < 4; j++) {
            quads[base + j * 2] = xs[j];
            quads[base + j * 2 + 1] = ys[j];
        }
        quads[base + 8] = glow < 0f ? 0f : Math.min(glow, 1f);
        quadCount++;
        hasGhosts = false;
    }

    /** 本帧写入的四边形个数（探针与重绘区域计算用） */
    public int quadCount() {
        return quadCount;
    }

    /**
     * 探针：读取第 i 个四边形的 8 个坐标 + 辉光。
     *
     * @return 长度 9 的数组 [x0,y0,...,x3,y3,glow]；索引越界返回 null
     */
    public double[] quadForProbe(int i) {
        if (i < 0 || i >= quadCount) {
            return null;
        }
        double[] out = new double[QUAD_STRIDE];
        System.arraycopy(quads, i * QUAD_STRIDE, out, 0, QUAD_STRIDE);
        return out;
    }

    /** 残影模式：写入残影列表（每项 [x, y, width, height, alpha]） */
    public void updateGhosts(@NotNull List<float[]> shapes) {
        ghosts.clear();
        ghosts.addAll(shapes);
        hasGhosts = true;
    }

    /**
     * 设置辉光强度（0~1），超出范围会被截断。
     *
     * <p>由 animator 每帧按「拖尾展开程度」写入：完全静止时为 0（不画光晕），
     * 正常移动时为 1（满强度，与之前的观感完全一致）。
     */
    public void setGlowStrength(float strength) {
        this.glowStrength = strength < 0f ? 0f : (strength > 1f ? 1f : strength);
    }

    /**
     * 探针：第 {@code i} 个四边形的四个角点坐标（double）。
     *
     * <p><b>0.9.74 修复：原实现读的是 {@code polyX/polyY}，而那对数组已恒为 0。</b>
     *
     * <p>历史：这对数组原属「单四边形快捷路径」（{@link #updateCorners}）。
     * 但多光标重构后，生产代码全部改走 {@code beginQuads() + addQuad()}，
     * {@code updateCorners} 变成**零调用**，于是 {@code polyX/polyY} 永远是初值 0。
     *
     * <p>后果：探针读到 8 个 0，却"验证通过"——是**假的安全感**
     * （与 0.9.66 的 VPreviewSizeLive 只验字段不验绘制同类）。
     *
     * <p>现在改读真正的 {@code quads} 存储，与绘制路径同源。
     *
     * @param i 四边形下标
     * @return 长度 8 的数组：x0,y0,x1,y1,x2,y2,x3,y3；越界返回 {@code null}
     */
    public double[] cornersForProbe(int i) {
        if (i < 0 || i >= quadCount) {
            return null;
        }
        int base = i * QUAD_STRIDE;
        return new double[]{
                quads[base], quads[base + 1],
                quads[base + 2], quads[base + 3],
                quads[base + 4], quads[base + 5],
                quads[base + 6], quads[base + 7]
        };
    }

    /**
     * 兼容旧签名：返回第 0 个四边形的角点。
     *
     * @deprecated 用 {@link #cornersForProbe(int)} 明确指定下标 ——
     *             多光标时"第 0 个"不等于"主光标"，容易误判。
     */
    @Deprecated
    public double[] cornersForProbe() {
        return cornersForProbe(0);
    }

    /**
     * 探针：按当前四角算一遍外扩方向向量。
     *
     * <p>调用的是生产用的 {@link #computeOffsetDirections}，
     * 用于确认「容器从 Polygon 改成 double[] 之后几何算式没有退化」。
     *
     * @return 长度 8：dirX0..3, dirY0..3
     */
    public double[] offsetDirectionsForProbe() {
        // 【0.9.74】同 cornersForProbe()：不能读恒为 0 的 polyX/polyY，
        // 要从真正的 quads 存储取第 0 个四边形。
        if (quadCount <= 0) {
            return null;
        }
        int base = 0;
        quadX[0] = quads[base];
        quadX[1] = quads[base + 2];
        quadX[2] = quads[base + 4];
        quadX[3] = quads[base + 6];
        quadY[0] = quads[base + 1];
        quadY[1] = quads[base + 3];
        quadY[2] = quads[base + 5];
        quadY[3] = quads[base + 7];
        computeOffsetDirections(quadX, quadY, dirX, dirY);
        return new double[]{
                dirX[0], dirX[1], dirX[2], dirX[3],
                dirY[0], dirY[1], dirY[2], dirY[3]
        };
    }

    /**
     * 探针：第 i 个四边形【自己的】辉光外扩方向向量。
     *
     * <p><b>为什么需要它</b>：代码审查发现辉光外层循环曾错用 {@code polyX/polyY}
     * （只保存最后写入的那个四边形），多光标时所有光晕会重叠在同一位置。
     * 本方法走的是与 {@link #paintQuad} 相同的取数路径（先拷 quadX/quadY 再算方向），
     * 因此能区分「读对了」和「读错了」。
     *
     * @return 长度 8：dirX0..3, dirY0..3；索引越界返回 null
     */
    public double[] glowDirectionsForProbe(int i) {
        if (i < 0 || i >= quadCount) {
            return null;
        }
        int base = i * QUAD_STRIDE;
        for (int j = 0; j < 4; j++) {
            quadX[j] = quads[base + j * 2];
            quadY[j] = quads[base + j * 2 + 1];
        }
        computeOffsetDirections(quadX, quadY, dirX, dirY);
        return new double[]{
                dirX[0], dirX[1], dirX[2], dirX[3],
                dirY[0], dirY[1], dirY[2], dirY[3]
        };
    }

    /**
     * 探针：绘制第 i 个四边形时，辉光层实际使用的【坐标】。
     *
     * <p><b>为什么这个比方向向量更能发现问题</b>：
     * 矩形四角的外法线恒为轴向量（±1,0）或（0,±1），<b>与尺寸无关</b> ——
     * 所以「方向向量」无法区分「读对了坐标」还是「读错了坐标」。
     * 而坐标本身能直接比对：若实现错用 {@code polyX/polyY}，
     * 所有四边形返回的都是同一个（最后写入的）坐标。
     *
     * @return 长度 8：[x0,y0,...,x3,y3]；索引越界返回 null
     */
    public double[] glowCoordsForProbe(int i) {
        if (i < 0 || i >= quadCount) {
            return null;
        }
        int base = i * QUAD_STRIDE;
        return new double[]{
                quads[base], quads[base + 1],
                quads[base + 2], quads[base + 3],
                quads[base + 4], quads[base + 5],
                quads[base + 6], quads[base + 7]
        };
    }

    /** 接入诊断追踪（{@code CaretAnimator} 构造时调用；预览面板不接）。 */
    public void setTraceId(int traceId) {
        this.traceId = traceId;
    }

    /** 清除全部绘制数据（光标不可见 / 模式切换时调用） */
    public void clearCorners() {
        hasGhosts = false;
        // 【审查修正】必须一并清 quadCount —— 绘制循环看的是 quadCount，
        // 只清 quadCount 的话下一帧仍会把旧四边形画出来（清不干净）。
        quadCount = 0;
        ghosts.clear();
    }

    /**
     * 【BUG-007】带原因的清空。
     *
     * <p>「谁把画面清掉了」是排查「光标消失」的第一现场：清空之后如果那一帧没有
     * 重新 {@code addQuad}，面板就是空的，而原生光标又是隐藏的 → 屏幕上什么都不剩。
     * 所以每次清空都留一行原因。
     */
    public void clearCorners(String reason) {
        clearCorners();
        CursorDiag.clearTrace(traceId, reason);
    }

    // ==================== 绘制 ====================

    /**
     * 返回 false 让鼠标事件穿透到下层编辑器。
     * 否则这个覆盖整个编辑器的面板会挡住所有点击与文本选择操作。
     */
    @Override
    public boolean contains(int x, int y) {
        return false;
    }

    @Override
    protected void paintComponent(Graphics g) {
        // 【BUG-007】诊断：本帧画了多少、花了多久。
        // 两次 nanoTime 约 50ns，144fps 下可忽略；关闭 diag 时只多这两个调用。
        long paintStart = System.nanoTime();
        if (quadCount <= 0 && !hasGhosts) {
            CursorDiag.paintTrace(traceId, quadCount, System.nanoTime() - paintStart, true, null);
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            if (hasGhosts) {
                paintGhosts(g2);
            }
            // 弹簧模式：逐四边形绘制（单光标时 quadCount == 1，行为与旧路径一致）
            for (int i = 0; i < quadCount; i++) {
                paintQuad(g2, i);
            }
        } catch (RuntimeException | Error e) {
            // 【BUG-007】绘制异常以前是【完全静默】的（Swing 只把它打到 stderr），
            // 而它的后果恰恰就是「光标消失」—— 先留一行证据，再按原样抛出，
            // 不改变既有行为（不吞异常）。
            CursorDiag.paintTrace(traceId, quadCount, System.nanoTime() - paintStart, false, e);
            throw e;
        } finally {
            g2.dispose();
        }
        CursorDiag.paintTrace(traceId, quadCount, System.nanoTime() - paintStart, false, null);
    }

    // ==================== 弹簧模式绘制 ====================

    private void paintQuad(Graphics2D g2, int index) {
        // 把一个四边形从步长数组拷进中转数组，供辉光几何算式使用
        int base = index * QUAD_STRIDE;
        for (int j = 0; j < 4; j++) {
            quadX[j] = quads[base + j * 2];
            quadY[j] = quads[base + j * 2 + 1];
        }
        Path2D.Double path = buildQuadPath(index);
        float glow = (float) quads[base + 8];

        // 【曾试过的优化：改描边 —— 实测更慢，已撤除】
        //
        // 想法：N 层 fill 是把整块面光栅化 N 遍，改成 N 道同心 stroke 只需光栅化边，
        // 面积近乎恒定，应该更快。
        //
        // 实测（生产代码 CaretTrailPanel，2026-10-25，3000 帧均值）：
        //   1 光标 8 层：fill 216µs → stroke 230µs（0.94x）
        //   8 光标 8 层：fill 1603µs → stroke 1701µs（0.94x）
        //   （8/6/4 层各档位都是 0.90~0.94x，稳定更慢）
        //
        // 原因：Java2D 的 draw 要先由 BasicStroke 把路径"加宽"成新的多边形再做
        // 抗锯齿填充 —— 等于「构造几何 + 填充」，而 fill 只有"填充"这一步。
        // 细长四边形（2×32）的 stroke 几何构造尤其不划算。
        //
        // 结论：这条路走不通，老老实实保留逐层 fill。
        drawQuadGlow(g2, glow);

        // 主体实心填充（画在光晕之上，保证光标本身清晰锐利）
        //
        // ==================== 0.9.86：三条互斥的填充路径 ====================
        //
        // 【用户明确的三条语义，逐字】
        //   「不要光标颜色渐变开关 和 彩虹颜色预设分错了，这是不同的东西 要做互斥」
        //   「光标颜色渐变开关只用于纯色渐变」
        //   「辉光按之前调查的不要动不然更乱」
        //
        //   ① 彩虹（颜色预设「彩虹（动态）」）→ 色相循环，见 buildRgbWashPaint
        //      实现完全按 docs/design/RGB-WASH-REFERENCE.md：
        //        两个动画叠加（wash-hue 4000ms + wash-drift 5000ms）
        //        沿长度铺 RGB_HUE_SPAN 圈 → 多个颜色同时可见
        //   ② 光标渐变开关开 → 双拼（上端拖尾色 / 下端从辉光色算出的末端色）
        //   ③ 都没有 → 纯色
        //
        // 【互斥】彩虹优先。选了彩虹预设时，即使「光标渐变」开着也走彩虹 ——
        //   否则用户选彩虹却看到双拼，功能对不上名字（0.9.85 就是这个问题）。
        //
        // 【辉光不动】始终用 glowColor（= shadowColor）。查过全部 git 历史：
        //   从引入双拼到现在，辉光从未跟随过光标本体色。
        if (cursorRgbWash) {
            // ① 彩虹：按 MD 的两个动画实现
            float top = (float) Math.min(Math.min(quadY[0], quadY[1]), Math.min(quadY[2], quadY[3]));
            float bottom = (float) Math.max(Math.max(quadY[0], quadY[1]), Math.max(quadY[2], quadY[3]));
            java.awt.Paint wash = buildRgbWashPaint(top, bottom);
            if (wash != null) {
                g2.setPaint(wash);
            } else {
                g2.setColor(fillColor);
            }
        } else if (rgbEnabled) {
            // ② 光标渐变（双拼）
            float top = (float) Math.min(Math.min(quadY[0], quadY[1]), Math.min(quadY[2], quadY[3]));
            float bottom = (float) Math.max(Math.max(quadY[0], quadY[1]), Math.max(quadY[2], quadY[3]));
            java.awt.Paint dual = buildDualTonePaint(top, bottom);
            if (dual != null) {
                g2.setPaint(dual);
            } else {
                g2.setColor(fillColor);
            }
        } else {
            // ③ 纯色
            g2.setColor(fillColor);
        }
        g2.fill(path);
    }

    /**
     * 用四角的 double 坐标构造路径。
     *
     * <p>用 {@link Path2D.Double} 而不是 {@code Polygon}：
     * Polygon 的构造函数只吃 {@code int[]}，会把亚像素坐标截断，导致顶点每帧跳 1px（抖动）。
     * Path2D 保留 double，配合抗锯齿即可在亚像素级平滑移动 —— 与 canvas 行为一致。
     */
    private Path2D.Double buildQuadPath(int index) {
        int base = index * QUAD_STRIDE;
        quadPath.reset();
        quadPath.moveTo(quads[base], quads[base + 1]);
        quadPath.lineTo(quads[base + 2], quads[base + 3]);
        quadPath.lineTo(quads[base + 4], quads[base + 5]);
        quadPath.lineTo(quads[base + 6], quads[base + 7]);
        quadPath.closePath();
        return quadPath;
    }

    /**
     * 绘制弹簧模式的辉光 —— 模拟原版 Canvas 的 {@code shadowBlur} 高斯模糊。
     *
     * <p><b>做法：多层同心外扩 + 四边形填充。</b>
     * 从最外层（外扩量最大）逐层向内绘制，层与层之间由 SrcOver 自然累加，
     * 于是形成「内亮外暗」的连续渐变 —— 即视觉上的发光。
     *
     * <p><b>每层 alpha 的取法</b>：设每层独立不透明度为 A，N 层叠加后最内层的
     * 累积不透明度为 {@code 1 - (1-A)^N}。令其恰好等于 {@link NeovideConfig#glowOpacity}
     * 反解出 {@code A = 1 - (1-peak)^(1/N)}，这样「N 层」与「强度」两个参数互不干扰：
     * 加层数只会让渐变更细腻，不会让光晕整体变亮。
     *
     * <p><b>为什么半径按线性分布</b>：累积不透明度在半径 r 处 = {@code 1-(1-A)^k}，
     * k 为覆盖该点的层数。半径线性分布时 k 也线性，
     * 算出在 {@code r ≈ glowWidth/2} 处刚好衰减到峰值的约 60%，与高斯曲线吻合。
     *
     * <p><b>光晕必须贴着四边形本身，不能用包围盒</b>（曾如此实现，效果失败）：
     * 光标的四个角点在拖尾时会被拉成一条斜向的细长四边形，
     * 其<b>包围盒</b>却能覆盖大半个编辑器 —— 于是跨行跳转时光晕会先撑成一个
     * 巨大方块再收缩，完全不是"贴着拖尾发光"。改为逐边外法线平移 + 相邻边求交
     * 的精确外扩后，光晕始终贴合四边形轮廓。
     *
     * <p><b>为什么不用「多层居中描边」</b>（更早的实现，同样失败）：
     * 描边路径是矩形的<b>边界线</b>而非内部，各层向外只扩 {@code strokeWidth/2}。
     * IntelliJ 光标极窄（2×32），于是最亮的那层只向外扩不到 1px 且随即被
     * 不透明主体盖住；而真正扩得够远的层，alpha 已衰减到 2% 以下。
     */
    private void drawQuadGlow(Graphics2D g2, float quadGlow) {
        if (!config.useShadow || currentGlowLayers() <= 0 || glowWidth <= 0.5f) {
            return;
        }

        int layers = currentGlowLayers();

        // 峰值再乘上强度：光标静止时 glowStrength 为 0，此处直接返回，光晕完全不绘制
        //
        // 【F2 / 批次2】再乘上 glowFade：拖尾停止后辉光淡出，光标本体不受影响。
        // 这是本批唯一改动的算式位置 —— 其余几何算法（角点、外扩方向、层数、
        // 半序、Math.pow 反解）一字未改。
        // 【多光标】强度改为逐四边形传入（quadGlow），不再读全局 glowStrength。
        //
        // 为什么要改：多光标时每个光标独立运动，有的在动（辉光满强度）、
        // 有的已静止（辉光应为 0）。若仍读全局字段，静止的光标也会发光。
        //
        // 单光标路径由 updateCorners() 把 glowStrength 写进四边形槽位，
        // 因此行为与改造前完全一致。
        float peak = config.glowOpacity * (glowColor.getAlpha() / 255f)
                * quadGlow * glowFade;
        if (peak <= 0f) {
            return;
        }

        // 反解每层独立 alpha，使 N 层累积后恰好达到 peak
        double layerAlpha = 1.0 - Math.pow(1.0 - peak, 1.0 / layers);
        int argb = (int) Math.round(layerAlpha * 255);
        if (argb < 1) {
            return;
        }

        g2.setColor(new Color(glowColor.getRed(), glowColor.getGreen(),
                glowColor.getBlue(), argb));

        // 角点的外扩方向：沿该方向平移 radius，即"四边形向外偏移 radius"
        computeOffsetDirections(quadX, quadY, dirX, dirY);

        // 从最外层向内绘制，半径线性分布
        for (int i = layers; i >= 1; i--) {
            double radius = glowWidth * (double) i / layers;

            glowPath.reset();
            for (int j = 0; j < 4; j++) {
                // 【审查修正】这里原本是 polyX/polyY —— 但多光标时那两个数组
                // 只保存"最后一次 updateCorners 写入的那个四边形"，
                // 于是所有光晕都会画在同一个位置。
                // 必须用 paintQuad 传下来的 quadX/quadY（当前这个四边形）。
                double x = quadX[j] + dirX[j] * radius;
                double y = quadY[j] + dirY[j] * radius;
                if (j == 0) {
                    glowPath.moveTo(x, y);
                } else {
                    glowPath.lineTo(x, y);
                }
            }
            glowPath.closePath();
            g2.fill(glowPath);
        }
    }


    /**
     * 计算四个角点各自的「外扩方向向量」——沿该方向平移 r，
     * 得到的就是「四边形精确向外偏移 r」的结果。
     *
     * <p>做法：每条边先取指向远离图形中心一侧的单位外法线 n；
     * 角点 j 由相邻两条边（prev→j 与 j→next）共同决定，
     * 偏移向量为 {@code (n1 + n2) / (1 + n1·n2)}。
     *
     * <p>该式的来历：两条平移后的边，其交点到原角点的距离为 {@code r / cos(θ/2)}，
     * 方向沿 {@code n1 + n2}，而 {@code 1 + n1·n2 = 2cos²(θ/2)}，代入即得。
     * 两边正交时（矩形）分母为 1，退化成 {@code n1 + n2} —— 正是对角线方向。
     */
    /**
     * 外法线的工作数组（复用，避免每次调用都 new double[4]）。
     *
     * <p>本方法是<b>每帧 × 每光标</b>调用：8 光标 @125fps = 1000 次/秒，
     * 每次原要分配两个 double[4]（共 64 字节）= 每秒 64KB 的纯垃圾。
     * 虽然不大，但这类小数组正好落在 TLAB 之外、容易触发年轻代回收。
     * 本类只在 EDT 上使用，单线程访问，复安全。
     */
    private final double[] offNx = new double[4];
    private final double[] offNy = new double[4];

    private void computeOffsetDirections(double[] px, double[] py,
                                         double[] outDirX, double[] outDirY) {
        final double[] nx = offNx;
        final double[] ny = offNy;
        java.util.Arrays.fill(nx, 0.0);
        java.util.Arrays.fill(ny, 0.0);

        double cx = 0;
        double cy = 0;
        for (int j = 0; j < 4; j++) {
            cx += px[j];
            cy += py[j];
        }
        cx /= 4.0;
        cy /= 4.0;

        // 逐边求单位外法线
        for (int j = 0; j < 4; j++) {
            int next = (j + 1) % 4;
            double ex = px[next] - px[j];
            double ey = py[next] - py[j];
            double len = Math.hypot(ex, ey);
            if (len < 1e-6) {
                // 退化边：留零向量，该角点不参与外扩
                continue;
            }

            double vx = -ey / len;
            double vy = ex / len;

            // 两个候选方向里取远离中心的那一个
            double mx = (px[j] + px[next]) / 2.0 - cx;
            double my = (py[j] + py[next]) / 2.0 - cy;
            if (vx * mx + vy * my < 0) {
                vx = -vx;
                vy = -vy;
            }
            nx[j] = vx;
            ny[j] = vy;
        }

        // 相邻两条边的法线合成出角点的偏移向量
        for (int j = 0; j < 4; j++) {
            int prev = (j + 3) % 4;

            double n1x = nx[prev];
            double n1y = ny[prev];
            double n2x = nx[j];
            double n2y = ny[j];

            double denom = 1.0 + (n1x * n2x + n1y * n2y);
            double dirX;
            double dirY;
            if (Math.abs(denom) < 1e-6) {
                // 相邻两边接近反向（图形已退化）：直接用法线和，避免除零
                dirX = n1x + n2x;
                dirY = n1y + n2y;
            } else {
                dirX = (n1x + n2x) / denom;
                dirY = (n1y + n2y) / denom;
            }

            // 限制偏移向量的模长不超过 √2。
            //
            // 该式的数学解在「锐角」处会失控：夹角 θ 越小，交点越远，
            // 偏移量 ∝ 1/cos(θ/2)。拖尾时光标被拉成细长斜条，前后缘正是锐角，
            // 实测偏移可达目标值的 2.4 倍，光晕会甩出一根长尖刺。
            //
            // 而真实的高斯模糊对锐角是「磨圆」而非「延长」，因此这里按 √2 截断：
            // 正交处（静止矩形）的偏移量恰为 √2，不受影响；锐角处则被压回来。
            double mag = Math.hypot(dirX, dirY);
            if (mag > SQRT_2) {
                double scale = SQRT_2 / mag;
                dirX *= scale;
                dirY *= scale;
            }
            outDirX[j] = dirX;
            outDirY[j] = dirY;
        }
    }

    // ==================== 残影模式绘制 ====================

    /**
     * 绘制残影。
     *
     * <p>残影本身就是按时间渐隐叠加的，视觉上已经足够柔和，
     * 因此不额外绘制光晕 —— 既省开销，也避免多个残影的光晕互相叠加变糊。
     * 仅对最浓的几个残影（alpha 较高者）补一点边缘辉光，保持与弹簧模式的一致性。
     */
    private void paintGhosts(Graphics2D g2) {
        int red = glowColor.getRed();
        int green = glowColor.getGreen();
        int blue = glowColor.getBlue();

        for (float[] shape : ghosts) {
            int x = Math.round(shape[0]);
            int y = Math.round(shape[1]);
            int w = Math.max(1, Math.round(shape[2]));
            int h = Math.max(1, Math.round(shape[3]));
            float alpha = Math.max(0f, Math.min(1f, shape[4]));

            if (alpha <= 0.02f) {
                continue;
            }

            // 头部残影（alpha 高）补一圈辉光，使其更接近"光标本体"的观感。
            // 描边居中绘制，外扩量 = strokeWidth / 2，因此取 2 倍 glowWidth
            // 使外扩恰好等于 glowWidth —— 与弹簧模式同一语义，两者观感一致。
            if (config.useShadow && alpha > 0.55f) {
                int glowAlpha = Math.round(alpha * config.glowOpacity * 60f);
                if (glowAlpha > 3) {
                    g2.setStroke(new BasicStroke(
                            Math.max(2f, glowWidth * 2f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.setColor(new Color(red, green, blue, Math.min(255, glowAlpha)));
                    g2.drawRect(x, y, w, h);
                }
            }

            int a = Math.round(alpha * fillColor.getAlpha());
            g2.setColor(new Color(fillColor.getRed(), fillColor.getGreen(),
                    fillColor.getBlue(), a));
            g2.fillRect(x, y, w, h);
        }
    }

    // ==================== 设置页预览支持 ====================

    /**
     * 预览用：直接把自己画到给定 Graphics 上，不依赖自身的 bounds。
     *
     * <p><b>为什么需要</b>：面板正常挂在 editor 的 contentComponent 上，
     * 靠自己的 paintComponent 绘制。设置页的预览窗是另一个容器，
     * 需要借用同一套绘制逻辑（辉光层数、渐变、quad 路径）才能保证观感一致。
     *
     * <p>调用方负责：{@code beginQuads()} → {@code addQuad(...)} → 本方法。
     *
     * @param g2 预览窗的 Graphics2D（调用方负责 dispose）
     * @param w  可用宽度
     * @param h  可用高度
     */
    public void paintForPreview(Graphics2D g2, int w, int h) {
        if (quadCount <= 0 && !hasGhosts) {
            return;
        }
        if (hasGhosts) {
            paintGhosts(g2);
        }
        for (int i = 0; i < quadCount; i++) {
            paintQuad(g2, i);
        }
    }

    /** 预览用：读当前辉光强度（setGlowStrength 写入的就是它） */
    public float getGlowStrengthForPreview() {
        return glowStrength;
    }
}
