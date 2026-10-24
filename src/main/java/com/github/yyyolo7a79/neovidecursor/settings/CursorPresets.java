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
        private final int smoothTicks;

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

        private AnimPreset(String name, int animLengthTicks, int shortLengthTicks,
                           int trailSizeTicks, int glowLayers,
                           int blurFactorTicks, int glowOpacityTicks,
                           boolean useShadow, boolean overlayFadeOut, int smoothTicks,
                           int stretchTicks) {
            this.name = name;
            this.animLengthTicks = animLengthTicks;
            this.shortLengthTicks = shortLengthTicks;
            this.trailSizeTicks = trailSizeTicks;
            this.glowLayers = glowLayers;
            this.blurFactorTicks = blurFactorTicks;
            this.glowOpacityTicks = glowOpacityTicks;
            this.useShadow = useShadow;
            this.overlayFadeOut = overlayFadeOut;
            this.smoothTicks = smoothTicks;
            this.stretchTicks = stretchTicks;
        }

        /** 输入平滑（0–100）。见 {@link #smoothTicks} 的注释。 */
        public int typingSmoothnessTicks() {
            return smoothTicks;
        }

        /** 输入平滑（0–1）。 */
        public float typingSmoothness() {
            return smoothTicks / 100f;
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
            //                        名称          anim short trail layers blur opacity 辉光   淡出   平滑
            // ① 基础：= NeovideConfig 默认值。日常用这个。
            new AnimPreset("基础",              10,  11,  100,  8,   20,  55,  true,  true,    0,  45),
            // ② 缎带：跨行动画拉长，斜缎带最明显。
            new AnimPreset("缎带",              28,  11,  100,  8,   20,  55,  true,  true,    0,  80),
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
            new AnimPreset("纯平滑输入",        10,  11,  100,  8,   60,  30,  false, false,   0,  20),
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

    // ==================== 预设覆盖值（0.9.8 / 每个预设各自记住改动）====================

    /**
     * 一个覆盖值里参与的字段个数 = 6，顺序与 {@link AnimPreset} 完全一致：
     * animationLength, shortAnimationLength, trailSize, glowLayers, shadowBlurFactor, glowOpacity。
     */
    public static final int OVERRIDE_FIELDS = 6;

    /** 覆盖值的合法下界（与 {@code NeovideCursorSettings.State} 的字段范围同源）。 */
    private static final float[] OVERRIDE_MIN = {0.05f, 0.02f, 0f, 1f, 0.05f, 0f};

    /** 覆盖值的合法上界。 */
    private static final float[] OVERRIDE_MAX = {0.35f, 0.25f, 1f, 12f, 0.60f, 1f};

    /**
     * 从覆盖串里取出第 {@code idx} 个预设的覆盖值；没有覆盖 / 内容非法 → {@code null}。
     *
     * <p><b>非法内容绝不抛异常</b>（宁干净不崩）：条目缺失、字段数不对、数字解析失败、
     * NaN / 无穷、越界 —— 一律当「无覆盖」，界面上就是干净的预设名，行为与 0.9.7 一致。
     *
     * <p><b>必须按字段逐项返回 float[6]</b>：调用方拿它直接写 State 的 6 个字段，
     * 顺序错一位就会把「辉光层数」写进「拖尾密度」（历史教训：这种错只有肉眼能发现）。
     */
    public static float[] parseOverride(String all, int idx) {
        if (all == null || all.isEmpty()) {
            return null;
        }
        for (String raw : all.split(";")) {
            if (raw == null) {
                continue;
            }
            String entry = raw.trim();
            if (entry.isEmpty() || keyOf(entry) != idx) {
                continue;
            }
            return parseValues(entry.substring(entry.indexOf('=') + 1));
        }
        return null;
    }

    /**
     * 写入 / 替换第 {@code idx} 个预设的覆盖值。
     *
     * <p>幂等且自愈：无法解析的旧条目会被丢掉（不会把坏内容一直带着走）；
     * 同一个 key 重复出现只保留最后一次写入的位置。
     *
     * <p><b>序列化必须逐位可逆</b>：这里用 {@link Float#toString(float)}，
     * 它是「能唯一还原该 float 的最短十进制」—— 往返 {@code parseFloat} 后<b>逐位相等</b>。
     * <b>不能</b>用 {@code %.2f} / {@code %.4f}：预设匹配用 {@link #EPS}（1e-6）比对，
     * 而「辉光锐度」那条滑块的刻度是 0.0055 的倍数（如外扩 0.1985），
     * 两位小数会把它变成 0.20 —— 覆盖值一进一出就漂了。
     */
    public static String putOverride(String all, int idx, float[] v) {
        if (v == null || v.length != OVERRIDE_FIELDS) {
            return all == null ? "" : all;
        }
        List<String> out = new ArrayList<>();
        boolean replaced = false;
        for (String raw : splitEntries(all)) {
            int key = keyOf(raw);
            if (key < 0) {
                continue;                       // 丢掉坏条目（自愈）
            }
            if (key == idx) {
                if (!replaced) {
                    out.add(formatEntry(idx, v));
                    replaced = true;
                }
                continue;                       // 重复 key 只留一份
            }
            out.add(raw);
        }
        if (!replaced) {
            out.add(formatEntry(idx, v));
        }
        return join(out);
    }

    /** 删除第 {@code idx} 个预设的覆盖值（幂等；删空后返回空串 = 无覆盖）。 */
    public static String removeOverride(String all, int idx) {
        List<String> out = new ArrayList<>();
        for (String raw : splitEntries(all)) {
            int key = keyOf(raw);
            if (key < 0 || key == idx) {
                continue;
            }
            out.add(raw);
        }
        return join(out);
    }

    /** {@code idx=v1,...,v6}；层数按整数写，其余用可逆的 {@code Float.toString}。 */
    private static String formatEntry(int idx, float[] v) {
        StringBuilder sb = new StringBuilder();
        sb.append(idx).append('=');
        for (int i = 0; i < OVERRIDE_FIELDS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i == 3 ? String.valueOf(Math.round(v[i])) : Float.toString(v[i]));
        }
        return sb.toString();
    }

    /** 解析 {@code v1,v2,v3,v4,v5,v6}；任何一项不合法 → {@code null}（整条作废）。 */
    private static float[] parseValues(String body) {
        String[] parts = body.split(",");
        if (parts.length != OVERRIDE_FIELDS) {
            return null;
        }
        float[] out = new float[OVERRIDE_FIELDS];
        for (int i = 0; i < OVERRIDE_FIELDS; i++) {
            float raw;
            try {
                raw = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException ex) {
                return null;
            }
            if (Float.isNaN(raw) || Float.isInfinite(raw)) {
                return null;
            }
            // 边界容差：滑块派生值（如 0.05f + 100×0.0055f）可能差 1 ulp，先容差再 clamp
            if (raw < OVERRIDE_MIN[i] - EPS || raw > OVERRIDE_MAX[i] + EPS) {
                return null;
            }
            if (i == 3) {
                int layers = Math.round(raw);
                if (layers < (int) OVERRIDE_MIN[i] || layers > (int) OVERRIDE_MAX[i]) {
                    return null;
                }
                out[i] = layers;
            } else {
                out[i] = Math.max(OVERRIDE_MIN[i], Math.min(OVERRIDE_MAX[i], raw));
            }
        }
        return out;
    }

    /** 条目的 key；非法 / 越界 → -1。 */
    private static int keyOf(String entry) {
        int eq = entry.indexOf('=');
        if (eq <= 0) {
            return -1;
        }
        int key;
        try {
            key = Integer.parseInt(entry.substring(0, eq).trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
        return (key >= 0 && key < ANIM_PRESETS.length) ? key : -1;
    }

    private static List<String> splitEntries(String all) {
        List<String> out = new ArrayList<>();
        if (all == null || all.isEmpty()) {
            return out;
        }
        for (String raw : all.split(";")) {
            if (raw == null) {
                continue;
            }
            String entry = raw.trim();
            if (!entry.isEmpty()) {
                out.add(entry);
            }
        }
        return out;
    }

    private static String join(List<String> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        return String.join(";", entries);
    }

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
