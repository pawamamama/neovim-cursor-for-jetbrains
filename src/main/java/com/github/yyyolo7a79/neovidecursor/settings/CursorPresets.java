package com.github.yyyolo7a79.neovidecursor.settings;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.9.5 的预设表。<b>纯常量，不是真相源。</b>
 *
 * <p>它是 {@code core.NeovideConfig} 默认值之外的唯一一份「成套取值」，但同一时刻只有
 * <b>一组</b>值生效（写进 {@code NeovideCursorSettings.State}）。
 * 它<b>不被持久化</b>（契约 §5.2 的 P-1：预设是纯动作，不是状态），
 * 也<b>不被任何绘制代码直接读取</b>。
 *
 * <p><b>为什么放在 {@code settings} 包而不是 {@code core}</b>：它与
 * {@link NeovideCursorSettings.State} 同属「持久化层」的配套常量，
 * 且不参与任何绘制计算。契约 §5.4 的示例路径是 {@code core/CursorPresets.java}，
 * 但 §5.8 的机械判据只约束「不构造运行期配置对象」与「不出现光标管理器类名」，
 * 与包位置无关；放在这里还能让 {@code core/} 目录保持零 diff
 * （硬纪律 1 的最强形态：{@code core/} 下除 {@code NeovideConfig} 外也不新增文件）。
 *
 * <p><b>为什么用「刻度整数」而不是 float 字面量</b>（契约 §5.4）：
 * {@code 0.28f} 这类字面量在源码里看着对，但 1 ulp 问题会让探针的 {@code ==} 断言随机失败。
 * 存 {@code 28} 这个整数、写入时 {@code 28 / 100f}，往返就完全可控。
 *
 * <p><b>本类【禁止】引用运行期配置或绘制入口</b>（契约 §5.8 的 N-1 与「预设直接写运行期配置」两条）：
 * 预设表若直接写运行期配置，就会在「默认值 / 持久化 / 运行期」之外再造出第四个真相源。
 * 写入职责在 {@code ui.CursorPresetApplier}，本类只提供数据与判据 ——
 * 因此本文件里<b>不出现</b>运行期配置类的构造，也<b>不出现</b>光标管理器的类名
 * （这样才能让 §5.8 的机械 grep 判据恒为 0）。
 */
public final class CursorPresets {

    private CursorPresets() {
    }

    /**
     * 浮点比对的容差（契约 §5.3）。
     *
     * <p>必须用 EPS 而不是 {@code ==}：float 经 XML 往返在当前实现下是逐位精确的，
     * 但 {@code /100f} 这类除法在往返中可能产生 1 ulp 差异。
     * {@code 1e-6f} 远大于 ulp 量级（{@code 0.1f} 的 ulp ≈ {@code 7.5e-9}），
     * 又远小于最小刻度（0.01）。
     */
    public static final float EPS = 1e-6f;

    // ==================== 动画预设 ====================

    /**
     * 一个动画预设：6 个字段，全部是<b>刻度整数</b>。
     *
     * <p>这 6 个字段就是契约 §5.3「匹配维度表」里参与比对的全部维度 ——
     * 多一个字段就多一种「永远匹配不上」的可能。
     * {@code trailFactors} / {@code stretchRatioK} / {@code trailMode}
     * <b>刻意不参与</b>：
     * <ul>
     *   <li>{@code stretchRatioK}：方案 §4.1 明写「预设一律沿用 4.5×」。若参与比对，
     *       用户调过拉伸强度后所有预设都会变「自定义」，违背「动画/颜色正交」；</li>
     *   <li>{@code trailMode}：它是独立开关，参与比对会让「残影模式」与「自定义」混淆。</li>
     * </ul>
     */
    public static final class AnimPreset {
        private final String name;
        private final int animLengthTicks;      // ×100 → 0.10s 存 10
        private final int shortLengthTicks;     // ×100 → 0.11s 存 11
        private final int trailSizeTicks;       // ×100 → 1.0 存 100
        private final int glowLayers;
        private final int blurFactorTicks;      // ×100 → 0.20 存 20
        private final int glowOpacityTicks;     // ×100 → 0.55 存 55

        /**
         * 是否启用辉光。
         *
         * <p><b>为什么这个字段必须在预设里（团队审查 F-05）</b>：
         * 预设表里已经有 3 个辉光参数（layers / blur / opacity），
         * 唯独「开不开辉光」这个<b>总闸</b>不在 —— 归类自相矛盾。
         *
         * <p>实际后果：用户在某预设下关了辉光，这个值被持久化；
         * 之后切到任何别的预设，<b>辉光都不会回来</b>
         * （用户实测：「缎带没有扩散了，就是持久化了」）。
         *
         * <p>而且「纯平滑输入」这个预设<b>必须</b>能表达「关辉光 + 关淡出」，
         * 6 字段模型根本写不出来。
         */
        private final boolean useShadow;

        /** 是否启用淡出。理由同 {@link #useShadow}。 */
        private final boolean overlayFadeOut;

        /**
         * 输入平滑（{@code typingSmoothness} ×100，0–100）。
         *
         * <p><b>为什么它必须在预设里（用户实测反馈 2026-10-23）</b>：
         * 这个字段直接乘进四角系数（{@code f'[i] = f[i] + (1-f[i]) × smooth × 0.8}），
         * 是<b>拉伸强度的头号旋钮</b>。它不在预设域时会出现：
         * <pre>
         *   用户把 smooth 拖到 100 → 四角系数 [1, 0.94, 0.87, 0.83]，梯度只剩 1.20×
         *   → 切任何预设都是规整矩形（"横向拉伸变小了，全部预设都这样"）
         *   → 且四角几乎同步，整个矩形滞后 22.81px（"连续删除光标跟不上"）
         * </pre>
         * 也就是说：<b>预设如果管不了它，预设就等于失效一半</b>。
         */

        /**
         * 拉伸强度（{@code stretchRatioK × 10}，10–80 → 1.0–8.0）。
         *
         * <p><b>为什么必须进预设（2026-10-24 用户实测）</b>：
         * 它和 {@code typingSmoothness} 一起决定 {@code trailFactors}
         * （见 {@code NeovideCursorSettings#deriveTrailFactors(k, smooth)}）。
         * 之前只让 smooth 进预设、k 不进 —— 结果切任何预设，
         * k 都保持用户上次拖出来的值，三个预设的手感实际完全相同。
         *
         * <p>用户原话：「切换预设手感动效没回来」「怎么切换都覆盖不了」。
         */
        private final int stretchTicks;

        // ==================== 0.9.67 补齐的 4 个字段 ====================
        //
        // 【为什么必须补】用户实测：「拖尾透明度不随预设改变了，这触碰红线了」。
        // 根因不是它"改坏了"，而是它<b>从来就不在预设里</b> —— 从 0.9.5 起
        // AnimPreset 就只有 9 个字段，而 tailOpacity / 淡出两项 / 输入平滑
        // 一直是持久级配置。
        //
        // 后果是两套清单不一致造成的「恢复默认值失效」：
        //   · 自定义副本的快照存 24 个字段（SettingsField.PRESET_FIELDS）
        //   · 内置预设只写 9 个（applyAnimPresetFields）
        // 于是切到副本后点「恢复为默认值」，只有 9 个字段被拉回，
        // 剩下 15 个（含 tailOpacity）停在副本值 —— 看起来就是「恢复没生效」。
        //
        // 【原则】预设 = 一整套观感。凡是「拖尾长什么样」的字段都该在这里；
        // 凡是「设备/功能开关」都不该（rgbGradient / cursorWidthPx /
        // cursorHeightRatio / enabled / hideNativeCaret / alwaysHighFps /
        // trailMode / multiCursorNoGlow / diagLogging）。

        /** 拖尾不透明度（×100，0–100）。0.9.67 补入 —— 与 glowOpacity 同级。 */
        private final int tailOpacityTicks;

        /** 淡出前保持时长（毫秒，0–500，步长 50）。 */
        private final int disappearDelayMs;

        /** 淡出时长（毫秒，0–1000，步长 25）。 */
        private final int fadeOutMs;

        /**
         * 输入平滑（×100，0–100）—— <b>0.9.73 起不再参与预设写入</b>。
         *
         * <p><b>决策变更记录</b>：
         * <ul>
         *   <li>0.9.46~0.9.66：平滑是全局持久项，<b>不随预设</b>（正确）</li>
         *   <li>0.9.67：我误读了用户那句「其他持久化 比如平滑关闭」——
         *       那是「平滑持久化、默认关闭」，不是「平滑进预设」。
         *       于是错误地把 smoothTicks 加进预设并让 applyAnimPresetFields 写它。</li>
         *   <li>0.9.73：用户澄清「平滑一直不参与预设」，恢复正确行为</li>
         * </ul>
         *
         * <p><b>这个字段和它的访问器为什么还留着</b>：
         * {@code VRegr2} / {@code VPreset9} 等探针在做「预设字段域」比对时会调
         * {@code typingSmoothness()}。删它要改一串探针，而它已不再被
         * <b>任何生产代码</b>读写 —— 保留成本低于删除成本。这是明确记录的技术债。
         *
         * <p>真实行为是：用户调好的平滑值在任何预设切换中都保持不变。
         */
        private final int smoothTicks;

        /**
         * 斜度（0–100）。
         *
         * <p><b>0.9.80 进预设域</b>（用户要求「这个参数进预设吧，初始就是对齐上游的参数，
         * 改这个参数会生成副本」）。
         *
         * <p><b>82 是「对齐上游」的值</b>：反推自 {@code trailFactors = {1.0, 0.72, 0.42, 0.22}}，
         * slope=82 时我的公式产出 [1.000, 0.720, 0.420, 0.220]，与上游逐位相同。
         * （推导见 tools/bench/ZzSlopeReverse.java）
         */
        private final int slopeTicks;

        private AnimPreset(String name, int animLengthTicks, int shortLengthTicks,
                           int trailSizeTicks, int glowLayers,
                           int blurFactorTicks, int glowOpacityTicks,
                           boolean useShadow, boolean overlayFadeOut,
                           int stretchTicks,
                           int tailOpacityTicks, int disappearDelayMs, int fadeOutMs,
                           int smoothTicks, int slopeTicks) {
            this.name = name;
            this.animLengthTicks = animLengthTicks;
            this.shortLengthTicks = shortLengthTicks;
            this.trailSizeTicks = trailSizeTicks;
            this.glowLayers = glowLayers;
            this.blurFactorTicks = blurFactorTicks;
            this.glowOpacityTicks = glowOpacityTicks;
            this.useShadow = useShadow;
            this.overlayFadeOut = overlayFadeOut;
            this.stretchTicks = stretchTicks;
            this.tailOpacityTicks = tailOpacityTicks;
            this.disappearDelayMs = disappearDelayMs;
            this.fadeOutMs = fadeOutMs;
            this.smoothTicks = smoothTicks;
            this.slopeTicks = slopeTicks;
        }

        /** 拖尾不透明度（0–1）。 */
        public float tailOpacity() {
            return tailOpacityTicks / 100f;
        }

        public int tailOpacityTicksValue() {
            return tailOpacityTicks;
        }

        /** 淡出前保持时长（毫秒）。 */
        public int cursorDisappearDelay() {
            return disappearDelayMs;
        }

        /** 淡出时长（秒，State 里以秒存储）。 */
        public float cursorFadeOutDuration() {
            return fadeOutMs / 1000f;
        }

        /** 输入平滑（0–1）。 */
        public float typingSmoothness() {
            return smoothTicks / 100f;
        }

        public int typingSmoothnessTicks() {
            return smoothTicks;
        }

        /**
         * 斜度（0–100）：0 = 纯矩形（有拉伸、无倾斜），100 = 完全斜（= 对齐上游原值）。
         *
         * <p><b>{@link #ANIM_PRESETS} 的实际取值是 基础 0 / 缎带 0 / 纯平滑输入 100。</b>
         *
         * <p>【2026-10-29 修正】这里原本写「三个预设都是 82 = 对齐上游」—— 那是 0.9.80
         * 的旧值。0.9.87 起语义改成「只有纯平滑输入倾斜，其余两个只拉伸不倾斜」，
         * 常量早已是 0/0/100。过期注释的代价是实测过的：用户报「我配的斜度丢了、
         * 之前不是 100」，而正确答案就是「纯平滑输入的斜度常量本来就是 100」——
         * 照着旧注释排查只会往数据丢失的方向找。
         *
         * <p>同理，{@code applyAnimPresetFields} 上方那条「三个内置预设都是 82」
         * 也在同一天一并修正。
         */
        public int slopeTicksValue() {
            return slopeTicks;
        }

        /** 拉伸强度（1.0–8.0）。见 {@link #stretchTicks} 的注释。 */
        public float stretchRatioK() {
            return stretchTicks / 10f;
        }

        /** 拉伸强度（10–80，×10 存储）。 */
        public int stretchRatioKTicks() {
            return stretchTicks;
        }

        public String name() {
            return name;
        }

        public float animationLength() {
            return animLengthTicks / 100f;
        }

        public int animationLengthTicks() {
            return animLengthTicks;
        }

        public float shortAnimationLength() {
            return shortLengthTicks / 100f;
        }

        public int shortAnimationLengthTicks() {
            return shortLengthTicks;
        }

        public float trailSize() {
            return trailSizeTicks / 100f;
        }

        public int trailSizeTicks() {
            return trailSizeTicks;
        }

        public int glowLayers() {
            return glowLayers;
        }

        public float shadowBlurFactor() {
            return blurFactorTicks / 100f;
        }

        public int shadowBlurFactorTicks() {
            return blurFactorTicks;
        }

        public float glowOpacity() {
            return glowOpacityTicks / 100f;
        }

        public int glowOpacityTicks() {
            return glowOpacityTicks;
        }

        /** 该预设是否启用辉光。见字段注释（F-05）。 */
        public boolean useShadow() {
            return useShadow;
        }

        /** 该预设是否启用淡出。 */
        public boolean overlayFadeOut() {
            return overlayFadeOut;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 3 个动画预设（0.9.10 精简：基础 / 缎带 / 纯平滑输入）。
     *
     * <p>第 8 个「残影模式」<b>不在表里</b>（契约 §0.2 的 R-3）：它只改 {@code trailMode}，
     * 不改任何动画字段；混进预设会让「预设」同时承载两种语义。它归 Tools 菜单既有第 3 项。
     *
     * <p>全部落在各自滑块的刻度网格上（契约 §6.2 的 10 滑块刻度表）：
     * {@code animationLength} 步长 1、{@code shortAnimationLength} 步长 1、
     * {@code trailSize}/{@code shadowBlurFactor}/{@code glowOpacity} 步长 5、
     * {@code glowLayers} 步长 1。
     */
    /**
     * <p><b>0.9.6 / M3：七个预设的短跳时长统一成 0.11s。</b>
     *
     * <p>原因（数据）：{@code shortAnimationLength} 是「同行打字/退格」的拖尾总时长，
     * 而<b>前缘（rank3）的有效时长 = 本值 × trailFactors[3] = 本值 × 0.22</b>。
     * 于是 {@code 0.04s} 的前缘只有 <b>8.8ms</b> —— 在 8ms 一帧的调度下就是 <b>1 帧位移完</b>，
     * 肉眼看就是「光标直接跳过去」，后缘还留着拖尾，所以显得「前半截断了」。
     * 这也是 {@code W_SHORT_LEN} 文案里「0.03 起步步归零、0.04 起能跑」的同一句话。
     *
     * <p>统一到 0.11 之后前缘 ≈ 24ms（3 帧），四个角都能展开；各预设之间的差异仍然由
     * {@code animationLength} / {@code trailSize} / {@code layers} / {@code blur} / {@code opacity} 承担，
     * 所以「快/平滑/柔和/高比对观感」这一层语义没有被抹平。
     */
    public static final AnimPreset[] ANIM_PRESETS = {
            //  名称            anim short trail layers blur opacity 辉光   淡出   k     alpha 淡出前 淡出时长 平滑  斜度
            // ① 基础：= State 默认值。日常用这个。
            //   【用户要求 2026-10-25】首次安装 = 基础 + 粉色 + 2px + 顶格 100%，
            //   总开关除「多光标关闭辉光」外全开，其余持久化项取默认（含平滑关闭 = 0）。
            //   所以这里 tailOpacity=100（全不透明）、淡出前 50ms、淡出 75ms、平滑 0，
            //   与 State 的默认值逐位一致 —— 保证「首次安装的观感 == 点『基础』的观感」。
            //
            // 最后一参 = 斜度：0 = 纯矩形（有拉伸、无倾斜）；100 = 完全斜（= 对齐上游原值）。
            // 【2026-10-29 修】这里原来有**两行互相矛盾**的「第 14 参 = 斜度」注释
            // （一行说 100 一行说 0），而且都写「第 14 参」——实际是第 15 参（前面还有「平滑」）。
            new AnimPreset("基础",              10,  11,  100,  8,   20,  55,  true,  true,   45, 100,  50,  75,  0,   0),
            // ② 缎带：跨行动画拉长，斜缎带最明显。
            new AnimPreset("缎带",              28,  11,  100,  8,   20,  55,  true,  true,   80, 100,  50,  75,  0,   0),
            // ③ 纯平滑输入：对齐 VS Code 原版的拖尾观感 —— 无辉光扩散、无淡出。
            //
            // 【blur 必须填 60 而不是 0（第二轮审查 F2，high）】
            // shadowBlurFactor 的持久化合法区间是 0.05–0.60（loadState 会 clamp）。
            // 填 0 → 落盘时被静默改成 0.05 → 那是**最锐**端（外扩 0.05），
            // 与我原本写的注释「得到柔和光雾」正好相反；
            // 而且回读后 0.05 ≠ 预设声明的 0.0 → matchAnimPreset 匹配不上
            // → 下拉显示「基准项」但实际已经不是那个预设（显示层撒谎）。
            //
            // 60 = 0.60 = 最柔端。这是「万一用户手动开回辉光时得到柔和光雾」的正确取值。
            // 唯一保留倾斜的预设（用户要求：只它单行倾斜）
            new AnimPreset("纯平滑输入",        10,  11,  100,  8,   60,  30,  false, false,   20, 100,  50,  75,  20, 100),
    };

    /** 预设 1 的<b>下标</b>（= 原版粉色 = {@link NeovideConfig} 默认值）。 */
    public static final int ANIM_PRESET_DEFAULT_INDEX = 0;

    /** 工具菜单的分组标题（plugin.xml 的 {@code text} 与这里必须一致）。 */
    public static final String MENU_ANIM = "Neovide 光标：动画预设";

    /** 工具菜单的分组标题（plugin.xml 的 {@code text} 与这里必须一致）。 */
    public static final String MENU_COLOR = "Neovide 光标：颜色预设";

    // 【0.9.9 已删除 MODIFIED_SUFFIX（「（已修改）」）】
    // 它服务的模型是「内置预设会被用户改动污染，所以名字要带后缀 + 提供『恢复默认值』」。
    // 0.9.9 改成内置预设永久只读、改动自动派生「X 副本」，
    // 内置预设永远干净，后缀失去了存在理由（GIMP / IntelliJ 配色方案同款模型）。

    /** 颜色下拉末项的兜底文案。颜色**不参与预设语义**，所以它不叫「已修改」。 */
    public static final String CUSTOM_COLOR_LABEL = "自定义颜色";

    /** 把持久化的基准下标钳进合法范围（0..ANIM_PRESETS.length-1）。 */
    public static int clampAnimPresetIndex(int idx) {
        return Math.max(0, Math.min(ANIM_PRESETS.length - 1, idx));
    }

    /** 颜色版钳制（2026-10-29，与 {@link #clampAnimPresetIndex} 同构）。 */
    public static int clampColorPresetIndex(int idx) {
        return Math.max(0, Math.min(COLOR_PRESETS.length - 1, idx));
    }

    // 【0.9.88 删除：预设覆盖值机制（177 行）】
    //
    // 这是 0.9.8 的「每个内置预设各自记住用户改动」机制：
    //   parseOverride / putOverride / removeOverride + 三个常量表。
    //
    // 【为什么删】0.9.9 改成「内置预设永久只读 + 改动自动派生副本」之后，
    // 这整套机制就没有调用方了。核实：三个方法在生产代码里零调用，
    // 仅剩两处 javadoc 的 {@link} 引用（那不会产生运行时依赖）。
    //
    // 保留注释而不是完全抹掉，是为了让后人知道「这里曾经有什么、为什么没了」。

    // ==================== 颜色预设 ====================

    /**
     * 一个颜色预设。
     *
     * <p><b>HEX 必须大写</b>：{@code sanitizeColor} 会统一成大写，
     * 预设表若写小写会造成 {@code equalsIgnoreCase} 匹配但 {@code equals} 不匹配的双口径。
     *
     * <p><b>0.9.75 新增 rgbWash</b>：颜色预设不再只是「两个 HEX」，
     * 它还能决定「用不用色相循环」。
     *
     * <p><b>为什么 RGB 归颜色预设而不是动画预设</b>（用户要求）：
     * 它改变的是「颜色怎么呈现」，与动画时长/拉伸完全无关；
     * 而且选中它时必须同时关掉混色 —— 这正是颜色预设的职责。
     *
     * <p><b>rgbWash = true 时</b>：
     * <ul>
     *   <li>{@code rgbGradient} 置 true（走色相循环绘制）</li>
     *   <li>混色（tailColor 纯色填充）自动被覆盖 —— 由绘制层的 if/else 保证</li>
     *   <li>两个 HEX 仍写进 State，作为「关掉 RGB 后回到的颜色」</li>
     * </ul>
     */
    public static final class ColorPreset {
        private final String name;
        private final String tailColor;
        private final String shadowColor;
        /** 是否启用色相循环（RGB 动态）。见类注释。 */
        private final boolean rgbWash;

        private ColorPreset(String name, String tailColor, String shadowColor) {
            this(name, tailColor, shadowColor, false);
        }

        private ColorPreset(String name, String tailColor, String shadowColor,
                            boolean rgbWash) {
            this.name = name;
            this.tailColor = tailColor;
            this.shadowColor = shadowColor;
            this.rgbWash = rgbWash;
        }

        public String name() {
            return name;
        }

        public String tailColor() {
            return tailColor;
        }

        public String shadowColor() {
            return shadowColor;
        }

        /** 是否启用色相循环（RGB 动态） */
        public boolean rgbWash() {
            return rgbWash;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 颜色预设。与动画预设<b>完全正交</b>：不碰任何时长/拉伸字段。
     *
     * <p><b>「彩虹」是唯一的例外性质</b>：它会把 {@code rgbGradient} 置 true，
     * 因为色相循环本身就是一种颜色方案。其余预设都会把它置 false
     * （否则用户选「冰蓝」却看到彩虹，那是撒谎）。
     */
    public static final ColorPreset[] COLOR_PRESETS = {
            new ColorPreset("原版粉色", "#FFC0CB", "#FFC0CB"),   // = NeovideConfig 默认值
            new ColorPreset("冰蓝",     "#7DD3FC", "#38BDF8"),
            new ColorPreset("薄荷",     "#6EE7B7", "#34D399"),
            new ColorPreset("藕荷",     "#C4B5FD", "#A78BFA"),
            new ColorPreset("暖沙",     "#FDE68A", "#FCD34D"),
            // 【0.9.88 修复：给彩虹独立的 HEX】
            //
            // 原来它和「原版粉色」都是 #FFC0CB/#FFC0CB，只能靠
            // `rgbWash() == rgbGradient` 区分。而 0.9.85 起 rgbGradient
            // 从「走色相循环」变成「光标渐变开关」，判据失效 ——
            // 实测「粉色+开渐变」会被误认成彩虹、「彩虹+关渐变」会被误认成粉色。
            //
            // 改法：给彩虹一对**独有**的 HEX，判据退回纯 HEX 比对即可。
            //
            // 【0.9.88 当时写的理由有错，0.9.105 已纠正 —— 见 BUG-011】
            //
            // 原文是「rgbWash=true 时两个 HEX 都不参与绘制」，这句话
            // **只对 tailColor 成立**：彩虹走色相循环时它只剩 alpha 被借去
            // 当不透明度（CaretTrailPanel.buildRgbWashPaint 的 userAlpha）。
            //
            // shadowColor 恰恰相反，它从头到尾都在喂辉光：
            //   CaretTrailPanel.refreshStyles()  →  glowColor = parse(shadowColor, 1.0f)
            //   CaretTrailPanel.drawQuadGlow()   →  直接拿 glowColor 的 RGB 逐层填充
            // 于是 0.9.88 顺手挑的「紫罗兰 #B388FF」，把彩虹光标旁边的雾
            // 从原来的粉雾换成了一片淡紫 —— 用户看到的「光标和辉光不搭」就是这么来的。
            //
            // 现在 shadowColor 取 #FFFFFF（中性白雾）。三条理由：
            //   1. 色相环上任何颜色转到它旁边都不打架；
            //   2. 再经 glowOpacity 压暗，就是用户记忆里的「半透明雾色」；
            //   3. 与其余五个预设的 HEX 对都不重复，匹配唯一性不受影响。
            //
            // 唯一的独有性由 tailColor（#4FC3F7）保证 —— 匹配判据要两个 HEX
            // 都相等（CursorPresetApplier.matchColorPreset），所以彩虹与
            // 「原版粉色」不会因为这里改成白色而撞车。守门探针：VGlowFog。
            new ColorPreset("彩虹（动态）", "#4FC3F7", "#FFFFFF", true),
    };

    /** 预设 1 的<b>下标</b>（= 原版粉色 = {@link NeovideConfig} 默认值）。 */
    public static final int COLOR_PRESET_DEFAULT_INDEX = 0;

    /**
     * 自定义颜色预设的快照串（2026-10-29）。
     *
     * <p>只有两个 HEX，但仍沿用动画预设的 {@code key=value;key=value} 风格 ——
     * 两张自定义预设表共用 {@link CustomPresets} 的序列化与父链，
     * 快照内部再发明一套格式只会多一种要维护的解析器。
     */
    public static String colorSnapshot(String tail, String shadow) {
        return "tailColor=" + tail + ";shadowColor=" + shadow;
    }

    /**
     * 解析颜色快照。
     *
     * @return {@code [tailColor, shadowColor]}；缺任一项或格式不对返回 {@code null}
     *         （调用方当作「无此预设」，绝不抛异常 —— 手改坏一个字符不该让面板打不开）
     */
    public static String[] parseColorSnapshot(String snap) {
        if (snap == null || snap.isEmpty()) {
            return null;
        }
        String tail = null;
        String shadow = null;
        for (String kv : snap.split(";")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            if ("tailColor".equals(k)) {
                tail = v;
            } else if ("shadowColor".equals(k)) {
                shadow = v;
            }
        }
        return (tail == null || shadow == null) ? null : new String[]{tail, shadow};
    }
}
