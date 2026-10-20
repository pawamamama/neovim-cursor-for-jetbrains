package com.github.yyyolo7a79.neovidecursor.settings;

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

        private AnimPreset(String name, int animLengthTicks, int shortLengthTicks,
                           int trailSizeTicks, int glowLayers,
                           int blurFactorTicks, int glowOpacityTicks) {
            this.name = name;
            this.animLengthTicks = animLengthTicks;
            this.shortLengthTicks = shortLengthTicks;
            this.trailSizeTicks = trailSizeTicks;
            this.glowLayers = glowLayers;
            this.blurFactorTicks = blurFactorTicks;
            this.glowOpacityTicks = glowOpacityTicks;
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

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 7 个动画预设（契约 §5.5，逐值冻结）。
     *
     * <p>第 8 个「残影模式」<b>不在表里</b>（契约 §0.2 的 R-3）：它只改 {@code trailMode}，
     * 不改任何动画字段；混进预设会让「预设」同时承载两种语义。它归 Tools 菜单既有第 3 项。
     *
     * <p>全部落在各自滑块的刻度网格上（契约 §6.2 的 10 滑块刻度表）：
     * {@code animationLength} 步长 1、{@code shortAnimationLength} 步长 1、
     * {@code trailSize}/{@code shadowBlurFactor}/{@code glowOpacity} 步长 5、
     * {@code glowLayers} 步长 1。
     */
    public static final AnimPreset[] ANIM_PRESETS = {
            //                        名称            anim  short trail layers blur opacity
            new AnimPreset("原版粉色",            10,  11,  100,  8,   20,  55),   // = NeovideConfig 默认值
            new AnimPreset("缎带",                28,  11,  100,  8,   20,  55),   // 跨行拉长
            new AnimPreset("Neovide 官方",        15,   4,  100,  8,   20,  55),   // 最接近原版
            new AnimPreset("社区·快",             6,   4,  100,  8,   20,  55),   // 跟手
            new AnimPreset("社区·平滑",           10,   4,   30,  8,   25,  45),   // 四角匀、柔和
            new AnimPreset("柔和",                18,   8,   80,  6,   30,  40),   // 轻量
            new AnimPreset("高对比",              12,  10,  100, 10,   15,  75),   // 强调
    };

    /** 预设 1 的<b>下标</b>（= 原版粉色 = {@link NeovideConfig} 默认值）。 */
    public static final int ANIM_PRESET_DEFAULT_INDEX = 0;

    /** 工具菜单的分组标题（plugin.xml 的 {@code text} 与这里必须一致）。 */
    public static final String MENU_ANIM = "Neovide 光标：动画预设";

    /** 工具菜单的分组标题（plugin.xml 的 {@code text} 与这里必须一致）。 */
    public static final String MENU_COLOR = "Neovide 光标：颜色预设";

    /** 下拉末尾那一项的文案。它<b>不可被选中执行</b>，只是显示态的兜底（契约 §5.7）。 */
    public static final String CUSTOM_LABEL = "自定义（当前值）";

    // ==================== 颜色预设 ====================

    /**
     * 一个颜色预设：2 个 HEX（契约 §5.6）。
     *
     * <p><b>HEX 必须大写</b>：{@code sanitizeColor} 会统一成大写，
     * 预设表若写小写会造成 {@code equalsIgnoreCase} 匹配但 {@code equals} 不匹配的双口径。
     */
    public static final class ColorPreset {
        private final String name;
        private final String tailColor;
        private final String shadowColor;

        private ColorPreset(String name, String tailColor, String shadowColor) {
            this.name = name;
            this.tailColor = tailColor;
            this.shadowColor = shadowColor;
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

        @Override
        public String toString() {
            return name;
        }
    }

    /** 5 个颜色预设（契约 §5.6，逐值冻结）。与动画预设<b>完全正交</b>：只碰 2 个 HEX。 */
    public static final ColorPreset[] COLOR_PRESETS = {
            new ColorPreset("原版粉色", "#FFC0CB", "#FFC0CB"),   // = NeovideConfig 默认值
            new ColorPreset("冰蓝",     "#7DD3FC", "#38BDF8"),
            new ColorPreset("薄荷",     "#6EE7B7", "#34D399"),
            new ColorPreset("藕荷",     "#C4B5FD", "#A78BFA"),
            new ColorPreset("暖沙",     "#FDE68A", "#FCD34D"),
    };

    /** 预设 1 的<b>下标</b>（= 原版粉色 = {@link NeovideConfig} 默认值）。 */
    public static final int COLOR_PRESET_DEFAULT_INDEX = 0;
}
