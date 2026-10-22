package com.github.yyyolo7a79.neovidecursor.ui.settings;

import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.intellij.openapi.observable.properties.GraphProperty;
import com.intellij.openapi.observable.properties.PropertyGraph;
import com.intellij.openapi.options.BoundConfigurable;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.ui.DialogPanel;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ColorPanel;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.dsl.builder.BottomGap;
import com.intellij.ui.dsl.builder.BuilderKt;
import com.intellij.ui.dsl.builder.ButtonKt;
import com.intellij.ui.dsl.builder.Cell;
import com.intellij.ui.dsl.builder.ComboBoxKt;
import com.intellij.ui.dsl.builder.HyperlinkEventAction;
import com.intellij.ui.dsl.builder.MutableProperty;
import com.intellij.ui.dsl.builder.Panel;
import com.intellij.ui.dsl.builder.Row;
import com.intellij.ui.dsl.builder.SliderKt;
import com.intellij.ui.dsl.builder.TextFieldKt;
import com.intellij.ui.dsl.builder.TopGap;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.ranges.IntRange;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Neovide 拖尾光标的设置面板（0.9.5：预设 + 吸附滑块 + 布局重排）。
 *
 * <p><b>为什么继承 {@link BoundConfigurable} 而不是裸实现 {@code SearchableConfigurable}：
 * </b>父类已经实现了 {@code createComponent / isModified / reset / apply / disposeUIResources}，
 * 子类只需要给出 {@link #createPanel()}。手写那几个方法等于把"什么时候把控件值刷回模型"
 * 的时序自己重新发明一遍，而 {@code DialogPanel} 已经做对了。
 *
 * <p><b>仍然是「OK 生效」，不做实时预览。</b>拖动滑块时右侧数值会跟着变——那只是<b>改一个
 * JLabel 的文字 + 写预览副本 {@link #pending}</b>，不碰 {@code CONFIG}、不调
 * {@code applyConfig()}、不落盘，与"实时预览"是两件事。
 *
 * <p><b>数据流</b>（{@code SETTINGS-UI-CONTRACT.md} §3.3 / §3.4）：
 * <pre>
 *   reset()   service.getState() --深拷贝--&gt; pending + baseline --DialogPanel--&gt; 控件
 *   apply()   控件 --DialogPanel--&gt; pending --逐字段(pending!=baseline)--&gt; service.getState()
 *                                              --&gt; persistQuietly()
 *                                              --&gt; NeovideCaretManager.applyConfig()
 *                                              --&gt; baseline 前移
 * </pre>
 *
 * <p><b>缺陷 1 是三层缺陷，不是一个 bug</b>（契约 §1.1 的 L1/L2/L3），三条修法缺一不可：
 * <ol>
 *   <li><b>修法 1</b>：显式 {@code slider.addChangeListener} —— 写 {@code pending} + 刷标签；</li>
 *   <li><b>修法 2</b>：{@link #isModified()} 必须自己比 {@code pending vs state} ——
 *       否则补了 ChangeListener 之后 {@code super.isModified()} 恒 false（B-1：应用按钮变灰、
 *       改动静默丢失）；</li>
 *   <li><b>修法 3</b>：滑块的 getter 必须返回 <b>吸附后的</b> pending（{@link #snapToTick}）——
 *       否则"精确值 vs 刻度值"不一致时 {@code isModified()} 永真（B-2：OK 按钮永远亮着）。</li>
 * </ol>
 * 修法 2 与修法 3 是<b>对向陷阱</b>：只修一个就会踩另一个。
 */
public final class NeovideCursorConfigurable extends BoundConfigurable
        implements SearchableConfigurable, ProbeFacade {

    /** 配置页 id —— 必须是插件 id 前缀（官方原话 "The FQN should be based on the plugin id"）。 */
    /** 0.9.7 起 fork 独立 id（与 plugin.xml 的 applicationConfigurable id 必须一致）。 */
    public static final String ID = "com.pawamamama.neovidecursor.settings";

    /** 页面名称。**同时**写在 plugin.xml 的 displayName 里（平台取名字时不必实例化本类）。 */
    public static final String DISPLAY_NAME = "Neovide Cursor";

    // ==================== 警告文案（契约 §2.2 / §7.3 / §9.4，逐字，不得改动）====================

    private static final String W_ALWAYS_HIGH_FPS =
            "常驻刷新 = 调度线程每秒向 EDT 投递约 125 次任务，且永不停歇。标签页很多或机器较慢时，"
            + "它可能让 IDE 反而更卡。关掉后空闲会降到 120ms（失焦 600ms），"
            + "代价是「停手后第一次操作」可能等一个空闲周期才动。";

    private static final String W_HIDE_NATIVE =
            "隐藏原生光标 = 把全局配色方案的 CARET_COLOR 改成全透明。IDE 会把它落盘写进 colors/*.icls，"
            + "卸载插件后光标可能依然是隐形的。取消勾选即可恢复原生光标（也建议从此处恢复）。";

    private static final String W_COUPLING =
            "「隐藏原生光标」勾选时覆盖层就是唯一的「光标」，跟随完全依赖调度，建议同时保持常驻刷新；"
            + "取消勾选后原生光标零延迟跟随，常驻刷新可以关掉以省电。";

    private static final String W_GLOW_LAYERS =
            "层数越多渐变越平滑，但每层都是一次 CPU 抗锯齿 + alpha 混合（fill(Path2D) 不享受 Java2D 的 GPU 加速）。"
            + "层数不影响亮度：内部按层数反解每层 alpha，8 层与 12 层的峰值亮度相同。";

    /**
     * 辉光锐度/外扩的说明（0.9.6 / M6：显示层从「外扩幅度」改成「锐度」，内部值不变）。
     *
     * <p>原文案「0.6 是原版 JS 的夸张光雾（约 40px）」保留其事实，但主语换成锐度 ——
     * 否则用户看到一个叫「锐度」的滑块配一段讲 0.6 的文案，会以为滑块坏了。
     */
    private static final String W_SHADOW_BLUR =
            "「锐度」是外扩幅度的反向说法：100 = 外扩 0.05（最锐）、0 = 外扩 0.60（最柔，即原版 JS 的夸张光雾，约 40px）。"
            + "它同时决定重绘 padding：每个编辑器每帧的重绘包围盒会随之变大，慢机器上可能掉帧。"
            + "默认外扩 0.20（约 14px，锐度 73）是本插件按「光晕总宽与残影模式一致」反推的值。";

    private static final String W_STRETCH =
            "只调整后缘与前缘的时长比（拉伸倍率）。默认 4.5×＝后缘 110ms / 前缘 24ms，是实测调出来的观感；"
            + "调低会让拖尾变平，调高会把后缘拉长。rank0 最拖、rank3 最跟手，方向固定，不可对调。";

    private static final String W_FADE_OFF =
            "辉光淡出关闭时不使用这两个值，辉光瞬时归零（行为与 0.9.1 一致）。";

    /** 契约 §9.4：跨行动画时长的 comment，逐字。 */
    private static final String W_ANIM_LEN =
            "跨行动画时长 = 光标从一行移到另一行时的拖尾时长。上限 0.35s：再长切屏回来会看到「上一个位置还在拖」。";

    /**
     * 契约 §9.4：短动画时长的 comment。<b>两边界版本</b>（t7 / R-06 定稿）。
     *
     * <p><b>为什么不再是「下限 0.05s 是硬边界」</b>：那句话把
     * {@code MAX_DT = 0.05s} 直接当成了滑块下限，但滑块量程是 <b>0.02–0.25</b>
     * （契约 §7.1，captain 已裁决保持），文案与量程互相矛盾。
     *
     * <p>真实机制是：<b>前缘（rank3）</b>的有效时长 = 本值 × {@code trailFactors[3]=0.22}，
     * 而 {@code DampedSpring.update()} 首句是
     * {@code if (animationLength <= dt) 归零} —— 所以短路发生在<b>前缘</b>而不是整体，
     * 且阈值是 {@code 0.05 / 0.22 ≈ 0.227s}（卡顿帧）与 {@code 0.008 / 0.22 ≈ 0.036s}（日常帧），
     * 不是 0.05s。
     *
     * <p>文案由 verifier §10.0 实测 + reviewer 独立复算确认（0.03 起步步归零、0.04 起能跑）。
     */
    /**
     * <b>分行渲染的写法（t9 / t8-R11）</b>：DSL 的 comment 走 {@code DslLabel}（继承
     * {@code JEditorPane}，按 HTML 渲染），串里的字面 {@code \n} <b>不会</b>变成换行
     * —— HTML 会把连续空白折叠成一个空格。必须显式写 {@code <br/>}。
     *
     * <p>为什么不拆成多条 {@code rowComment}：那会在语义上变成「多条独立提示」，
     * 而这 6 行是<b>同一条</b>提示的连续说明（前半句 + 三种取值区间 + 实测注记），
     * 拆开会让回退/检索时难以判断它们属于同一个字段；用 {@code <br/>} 保持「一个 comment」语义。
     */
    private static final String BR = "<br/>";

    private static final String W_SHORT_LEN =
            "短动画时长 = 同行打字/退格时的拖尾时长。" + BR + BR
            + "注意前缘（rank3）的有效时长 = 本值 × 0.22，两种情况下会被单帧短路成「前缘瞬移」：" + BR
            + "  · 低于 0.036s：日常帧（8ms）下前缘就整段归零，后缘仍有拖尾" + BR
            + "  · 0.036s ~ 0.227s：日常帧能正常展开，只在卡顿（单帧 ≥ 50ms）时前缘才归零" + BR
            + "  · 0.227s 以上：四角都能完整展开" + BR
            + "实测：0.03 起步步归零，0.04 起能跑。";

    /** 契约 §7.3 第三行：动画时序组底部，恒显示，逐字。 */
    private static final String W_ANIM_COUPLING =
            "这两个值只影响【动画时长】，不改变拉伸倍率；拉伸倍率见上方「拖尾拉伸强度」。";

    /** 动画时序：「输入平滑」的说明（M4）。必须说清「不改总时长」，否则会被当成第二个短动画时长。 */
    private static final String W_TYPING_SMOOTH =
            "只压平「同一行打字/退格」时四个角的时长梯度，<b>不改变拖尾总时长</b>："
            + "后缘（最长的一角）时长完全不变，抬起来的只是前缘。" + BR
            + "0% = 保持原样；100% = 前缘延时抬到约 80%，单行打字不再「一帧跳到新位置」。" + BR
            + "跨行移动完全不受它影响 —— 那一路由「跨行动画时长」和「拖尾密度」决定。";

    /** 高级（诊断）区：性能诊断开关的说明。该项不持久化。 */
    private static final String W_PAINT_DIAG =
            "只跑物理计算、跳过重绘，用于分离瓶颈：开启后 fps 大幅回升说明瓶颈在绘制；"
            + "几乎不变说明瓶颈在 EDT 调度。本项不写进配置文件，重启 IDEA 后自动关闭。";

    // ==================== 下拉项文案（契约 §2.4：不得只显示枚举名）====================

    private static final String MODE_SPRING_TEXT = "弹簧拉伸（Neovide 原版效果）";
    private static final String MODE_AFTERIMAGE_TEXT = "残影轨迹（低帧率更稳）";
    private static final List<String> MODE_ITEMS = Arrays.asList(MODE_SPRING_TEXT, MODE_AFTERIMAGE_TEXT);

    private static final int COMMENT = -1;

    private static final HyperlinkEventAction NO_LINK = HyperlinkEventAction.HTML_HYPERLINK_INSTANCE;

    /** 下拉末项的动态后缀（0.9.6 / M5）：{基准预设名} + 本后缀。 */
    /** 「恢复为默认值」按钮的文案模板。 */
    private static final String RESTORE_TPL = "恢复为「%s」默认值";

    // ==================== 10 个滑块的刻度表（契约 §6.2，逐值冻结）====================

    /**
     * 一个滑块的完整建模。<b>滑块刻度值</b>是唯一的中间表示：
     * <ul>
     *   <li>滑块只在刻度上（{@code setSnapToTicks(true)}）；</li>
     *   <li>输入框任意值；</li>
     *   <li>{@code pending} 存<b>精确值</b>（由 {@link #write} 换算）。</li>
     * </ul>
     * 步长不能随便定（契约 §0.2 的 R-2）：淡出时长必须是 25（默认 0.075 = 刻度 75），
     * 百分比类必须是 5（默认 0.55 / 预设 0.45、0.40、0.30 都落在 5 的网格上）。
     * 定错一档，<b>设置页一打开默认值就漂移</b>，直接违反硬纪律 1。
     */
    /** 滑块规格。包私有以便 {@link SettingsSearchIndex} 按字段 key 定位标签。 */
    static final class SliderSpec {
        final String key;
        final int min;
        final int max;
        final int step;
        final String unitSuffix;
        /** 从 pending 读<b>刻度整数</b>。 */
        final java.util.function.ToIntFunction<NeovideCursorSettings.State> readTick;
        /** 把刻度整数写回 pending。 */
        final java.util.function.BiConsumer<NeovideCursorSettings.State, Integer> writeTick;
        /** 右侧数值标签的格式（可含多段信息，如拉伸强度）。 */
        final java.util.function.BiFunction<NeovideCursorConfigurable, Integer, String> valueText;
        /** 刻度轴上的文字（必须短，否则又挤成一团）。 */
        final IntFunction<String> tickText;

        SliderSpec(String key, int min, int max, int step, String unitSuffix,
                   java.util.function.ToIntFunction<NeovideCursorSettings.State> readTick,
                   java.util.function.BiConsumer<NeovideCursorSettings.State, Integer> writeTick,
                   java.util.function.BiFunction<NeovideCursorConfigurable, Integer, String> valueText,
                   IntFunction<String> tickText) {
            this.key = key;
            this.min = min;
            this.max = max;
            this.step = step;
            this.unitSuffix = unitSuffix;
            this.readTick = readTick;
            this.writeTick = writeTick;
            this.valueText = valueText;
            this.tickText = tickText;
        }
    }

    private static final int DELAY_MIN = 0;
    private static final int DELAY_MAX = 500;
    private static final int DELAY_STEP = 50;

    private static final int FADE_MIN = 0;
    private static final int FADE_MAX = 1000;
    private static final int FADE_STEP = 25;

    private static final int PCT_MIN = 0;
    private static final int PCT_MAX = 100;
    private static final int PCT_STEP = 5;

    private static final int LAYERS_MIN = 1;
    private static final int LAYERS_MAX = 12;
    private static final int LAYERS_STEP = 1;

    private static final int BLUR_MIN = 5;
    private static final int BLUR_MAX = 60;
    private static final int BLUR_STEP = 5;

    private static final int STRETCH_MIN = 10;
    private static final int STRETCH_MAX = 80;
    private static final int STRETCH_STEP = 5;

    private static final int ANIM_MIN = 5;
    private static final int ANIM_MAX = 35;
    private static final int ANIM_STEP = 1;

    private static final int SHORT_MIN = 2;
    private static final int SHORT_MAX = 25;
    private static final int SHORT_STEP = 1;

    /** 10 个滑块的规格表。<b>顺序 = 面板从上到下的顺序</b>（探针按此下标取滑块）。 */
    private static final SliderSpec[] SPECS = {
            // 1. 淡出前保持时长：刻度 = 毫秒
            new SliderSpec("cursorDisappearDelay", DELAY_MIN, DELAY_MAX, DELAY_STEP, " ms",
                    s -> s.cursorDisappearDelay,
                    (s, v) -> s.cursorDisappearDelay = v,
                    (c, v) -> v + " ms",
                    v -> String.valueOf(v)),

            // 2. 淡出时长：刻度 / 1000f = 秒
            new SliderSpec("cursorFadeOutDuration", FADE_MIN, FADE_MAX, FADE_STEP, " ms",
                    s -> Math.round(s.cursorFadeOutDuration * 1000f),
                    (s, v) -> s.cursorFadeOutDuration = v / 1000f,
                    (c, v) -> v + " ms",
                    v -> String.valueOf(v)),

            // 3. 拖尾不透明度：刻度 / 100f
            new SliderSpec("tailOpacity", PCT_MIN, PCT_MAX, PCT_STEP, "",
                    s -> Math.round(s.tailOpacity * 100f),
                    (s, v) -> s.tailOpacity = v / 100f,
                    (c, v) -> fmt2(v / 100f),
                    v -> fmt2(v / 100f)),

            // 4. 辉光峰值不透明度：刻度 / 100f
            new SliderSpec("glowOpacity", PCT_MIN, PCT_MAX, PCT_STEP, "",
                    s -> Math.round(s.glowOpacity * 100f),
                    (s, v) -> s.glowOpacity = v / 100f,
                    (c, v) -> fmt2(v / 100f),
                    v -> fmt2(v / 100f)),

            // 5. 辉光层数：刻度 = 层数
            new SliderSpec("glowLayers", LAYERS_MIN, LAYERS_MAX, LAYERS_STEP, " 层",
                    s -> s.glowLayers,
                    (s, v) -> s.glowLayers = v,
                    (c, v) -> v + " 层",
                    v -> String.valueOf(v)),

            // 6. 辉光锐度（0.9.6 / M6）：刻度 **0..100 = 锐度**，存储仍是 shadowBlurFactor（外扩 0.05..0.60）。
            //    0 ⇒ 外扩 0.60（最柔）、100 ⇒ 外扩 0.05（最锐）—— 向右拖 = 更锐，方向符合直觉。
            //    为什么不做成第二个滑块：那会多一个「同一物理量的两个旋钮」，用户不知道听谁的；
            //    而「锐度」正是用户说「高对比」时的直觉语义（外扩窄 = 边缘锐）。
            new SliderSpec("shadowBlurFactor", 0, 100, 1, "",
                    s -> sharpnessOf(s.shadowBlurFactor),
                    (s, v) -> s.shadowBlurFactor = blurOfSharpness(v),
                    (c, v) -> "锐 " + v + "（外扩 " + fmt2(blurOfSharpness(v)) + "）",
                    v -> v == 0 ? "柔" : (v == 100 ? "锐" : String.valueOf(v))),

            // 7. 拖尾密度：刻度 / 100f
            new SliderSpec("trailSize", PCT_MIN, PCT_MAX, PCT_STEP, "",
                    s -> Math.round(s.trailSize * 100f),
                    (s, v) -> s.trailSize = v / 100f,
                    (c, v) -> fmt2(v / 100f),
                    v -> fmt2(v / 100f)),

            // 8. 拖尾拉伸强度：刻度 / 10f = 倍率（标签要显示派生时长，故用实例方法）
            new SliderSpec("stretchRatioK", STRETCH_MIN, STRETCH_MAX, STRETCH_STEP, "",
                    s -> Math.round(s.stretchRatioK * 10f),
                    (s, v) -> s.stretchRatioK = v / 10f,
                    (c, v) -> c.stretchText(v / 10f),
                    v -> String.format(Locale.ROOT, "%.1f×", v / 10f)),

            // 9. 跨行动画时长：刻度 / 100f = 秒（0.9.5 新增可调，契约 §7.1）
            new SliderSpec("animationLength", ANIM_MIN, ANIM_MAX, ANIM_STEP, " s",
                    s -> Math.round(s.animationLength * 100f),
                    (s, v) -> s.animationLength = v / 100f,
                    (c, v) -> fmt2(v / 100f) + " s",
                    v -> fmt2(v / 100f)),

            // 10. 短动画时长：刻度 / 100f = 秒（0.9.5 新增可调，契约 §7.1）
            new SliderSpec("shortAnimationLength", SHORT_MIN, SHORT_MAX, SHORT_STEP, " s",
                    s -> Math.round(s.shortAnimationLength * 100f),
                    (s, v) -> s.shortAnimationLength = v / 100f,
                    (c, v) -> fmt2(v / 100f) + " s",
                    v -> fmt2(v / 100f)),

            // 11. 输入平滑：刻度 = 百分比（0.9.6 / M4 新增）。它只压平短跳的四角梯度，
            // 不改任何总时长 —— 派生公式见 NeovideCursorSettings#deriveTrailFactors(float, float)。
            new SliderSpec("typingSmoothness", PCT_MIN, PCT_MAX, PCT_STEP, " %",
                    s -> Math.round(s.typingSmoothness * 100f),
                    (s, v) -> s.typingSmoothness = v / 100f,
                    (c, v) -> v + " %",
                    v -> v + "%"),
    };

    // ==================== 预览副本 + 基线 + 抑制标志 ====================

    /** 编辑会话：唯一能改变 {@code pending} / {@code baseline} 指向的地方（Phase 1）。 */
    private final EditSession session = new EditSession();

    /**
     * 面板正在编辑的副本（{@link EditSession} 的只读镜像）。
     *
     * <p><b>绝不在 {@code apply()} 之前写进 service</b>——那是「OK 生效」的语义边界。
     * <b>不允许在本类里给它赋值</b>：唯一赋值点是 {@link #syncSessionViews()}，
     * 而它只由 {@link EditSession} 的操作触发（RF-4 判据）。
     */
    private NeovideCursorSettings.State pending;

    /**
     * 面板打开那一刻的 state 快照（契约 §3.3 / §3.4）。<b>只作比较基准，不是真相源。</b>
     *
     * <p>缺陷 3 的修法：{@code apply()} 逐字段比较 {@code pending} 与 {@code baseline}，
     * 只有<b>用户真改过</b>的字段才写进 {@code state}。于是「面板打开期间 Tools 改了 X」时，
     * 因为 {@code pending.X == baseline.X}，X 不会被面板覆盖；而用户拖过的 Y
     * （{@code pending.Y != baseline.Y}）以用户为准 —— 两个方向都不丢。
     *
     * <p><b>时效</b>：{@code createPanel()} / {@code reset()} 时与 pending 同时拍快照，
     * {@code apply()} 成功后前移为 {@code deepCopy(target)}，{@code disposeUIResources()} 置 null。
     */
    private NeovideCursorSettings.State baseline;

    /**
     * 「正在由代码改控件」抑制标志（契约 §6.4）。
     *
     * <p>滑块监听器<b>写 pending + 刷标签</b>，程序化 {@code setValue()} 也会触发它；
     * 输入框的 DocumentListener 同理。若不抑制，{@code reset()} / 应用预设时
     * 用吸附值回灌控件就会把「精确值 333」改写成「刻度 350」，
     * 于是 {@code isModified()} 永远为真（契约附录 B 的 B-2 变体）。
     */
    private boolean syncingControls;

    /**
     * 「正在程序化装载/保存」标志 —— 期间<b>禁止自动派生自定义预设</b>。
     *
     * <p>{@link #afterPendingEdited()} 的语义是「用户刚改了参数，给他一个落脚点」。
     * 但 {@code reset()} / {@code apply()} 也会经 {@code syncAllControlsFromPending()}
     * 调到 {@code refreshPresetDisplay()} —— 那不是用户编辑。
     * 不屏蔽的话，装载一份「与内置常量不同」的历史 state 会凭空派生出一个副本，
     * 让 {@code isModified()} 恒为真（VDefects 的 V11-3c 当场抓到）。
     */
    private boolean suppressDerive;

    // 非 DSL 绑定控件：DialogPanel 不追踪它们，必须在 reset/apply/isModified 里手工同步
    private ColorPanel tailColorPanel;
    private ColorPanel shadowColorPanel;

    // 预设下拉（手工驱动：选中末项「X（已修改）」不写任何字段，契约 §5.7）
    private com.intellij.openapi.ui.ComboBox<String> animPresetCombo;

    /** 末项文案的「基准预设」下标（0.9.6 / M5）：最近一次匹配成功或手动选中的预设。 */
    private int animBaseIdx = CursorPresets.ANIM_PRESET_DEFAULT_INDEX;
    private int colorBaseIdx = CursorPresets.COLOR_PRESET_DEFAULT_INDEX;

    /** 「恢复为「X」默认值」按钮（0.9.6 / M5）。点它把基准预设的常量重新写进 pending。 */
    private javax.swing.JButton animRestoreBtn;
    /** 0.9.9：自定义预设的三个动作按钮（内置预设选中时全部置灰）。 */
    private javax.swing.JButton animNewBtn;
    private javax.swing.JButton animRenameBtn;
    private javax.swing.JButton animDeleteBtn;
    private javax.swing.JButton colorRestoreBtn;
    private com.intellij.openapi.ui.ComboBox<String> colorPresetCombo;

    private final List<String> animPresetItems = new ArrayList<>();
    private final List<String> colorPresetItems = new ArrayList<>();

    /** 10 个滑块的建模对象（滑块 + 输入框 + 数值标签三者一组）。 */
    private final List<SliderBinding> bindings = new ArrayList<>();

    // 动画时序组的两条动态耦合提示（契约 §7.4：禁止写死数字）
    private JLabel singleMoveHint;
    private JLabel crossMoveHint;

    /** 「拖尾渲染模式」下拉。仅为探针保留引用（M2 的 U24：Tools 改模式后下拉必须跟着变）。 */
    private com.intellij.openapi.ui.ComboBox<String> trailModeCombo;

    /**
     * 性能诊断（高级区）。**唯一不持久化**的开关：不进 State、不进 CONFIG、不进 isModified，
     * 只在 {@code apply()} 时下发给 {@link NeovideCaretManager}。
     */
    private boolean paintDiag;

    // 联动置灰用的三个实时属性（由控件自身的监听器驱动）
    private final PropertyGraph graph = new PropertyGraph();
    private final GraphProperty<Boolean> springMode = graph.property(Boolean.TRUE);
    private final GraphProperty<Boolean> shadowOn = graph.property(Boolean.TRUE);
    private final GraphProperty<Boolean> fadeOn = graph.property(Boolean.TRUE);

    /**
     * 构造器<b>不得创建任何 Swing 组件</b>：application 级 Configurable
     * "may be instantiated on a background thread"，且 {@code buildSearchableOptions}
     * 会为取关键字而实例化它。所有 UI 都在 {@link #createPanel()} 里建。
     *
     * <p>父类构造器是<b>两个 String</b>：{@code BoundConfigurable(String displayName, String helpTopic)}。
     * 官方 Kotlin 示例是单参数（带默认值），照抄到 Java 会编译失败。
     */
    public NeovideCursorConfigurable() {
        super(DISPLAY_NAME, null);
    }

    @Override
    public String getId() {
        return ID;
    }

    // ==================== 面板构建 ====================

    /**
     * 纯 DSL 构建，<b>不依赖 Project / Editor</b>——
     * {@code buildSearchableOptions}、{@code TraverseUIStarter} 会在无项目环境下调用它。
     *
     * <p><b>布局顺序（契约 §9.1 的线框）</b>：
     * <pre>
     *   [预设]   动画预设 [▾]    颜色预设 [▾]        ← 最显眼，置顶
     *   总开关    （4 行 + 耦合提示）
     *   淡出      （开关 + 2 个滑块）
     *   颜色与辉光（2 个 ColorPanel + 开关 + 3 个滑块）
     *   ▾ 拖尾手感（折叠，默认展开）
     *   ▾ 动画时序（折叠，默认折叠 = 高级）
     * </pre>
     * 每个分组的第一行都加 {@code topGap(MEDIUM)} —— 这是 JetBrains Layout 规范
     * 「用垂直间距把控件分组」的落地方式（{@code Panel} 接口没有 topGap，只有 {@code Row} 有）。
     */
    @Override
    public DialogPanel createPanel() {
        // 构建面板是【程序化装载】，不是用户编辑 —— 期间禁止自动派生自定义预设。
        // 否则一份「与当前内置常量不同」的历史 state（例如刚被 Tools 改过）
        // 会在打开设置页时凭空多出一个副本，并让 isModified() 恒为真。
        suppressDerive = true;
        try {
            // 契约 §3.4：平台可能只调 createPanel 不调 reset —— 所以这里也要开一次会话。
            // 【为什么不能写成两行 deepCopy】两者必须同源；写成一行由 EditSession 保证。
            session.openAt(currentState());
            syncSessionViews();
            graphReset(pending);
            bindings.clear();
            animPresetItems.clear();
            colorPresetItems.clear();
            singleMoveHint = null;
            crossMoveHint = null;
            paintDiag = NeovideCaretManager.isPaintDisabled();
            // 基准预设从持久化状态里读（不是从值猜）—— 否则重启后当前项会漂到别的内置预设
            animBaseIdx = CursorPresets.clampAnimPresetIndex(pending.animPresetBase);
            colorBaseIdx = CursorPresets.COLOR_PRESET_DEFAULT_INDEX;

            // 0.9.6 / M5-fix：下拉**只有真预设**，没有「已修改」伪条目。
            // 「已修改」是把基准那一项**就地改名**（不新增条目），由 refreshPresetDisplay() 负责。
            animPresetItems.addAll(animPresetNames());
            colorPresetItems.addAll(colorPresetNames());
            colorPresetItems.add(CursorPresets.CUSTOM_COLOR_LABEL);   // 颜色不参与预设语义，末项只是兜底

            return BuilderKt.panel(panel -> {

                panel.row("", row -> {
                    row.comment("本页所有改动点「应用 / OK」后才生效；面板内不做实时预览。", COMMENT, NO_LINK);
                    return kotlin.Unit.INSTANCE;
                });

                // ---------- 0. 预设置顶（契约 §9.1 的第一块）----------
                panel.row("", row -> {
                    row.topGap(TopGap.MEDIUM);
                    row.bottomGap(BottomGap.SMALL);
                    row.label("动画预设");
                    animPresetCombo = row.comboBox(animPresetItems, null).getComponent();
                    animPresetCombo.addActionListener(e -> onAnimPresetPicked());
                    row.label("颜色预设");
                    colorPresetCombo = row.comboBox(colorPresetItems, null).getComponent();
                    colorPresetCombo.addActionListener(e -> onColorPresetPicked());
                    return kotlin.Unit.INSTANCE;
                });

                // 0b. 一键恢复（0.9.6 / M5）：把「基准预设」的常量重新写进 pending。
                // 只改 pending（和选预设完全同一条路径），落库仍由「应用 / OK」完成。
                panel.row("", row -> {
                    row.bottomGap(BottomGap.SMALL);
                    animRestoreBtn = new javax.swing.JButton(String.format(RESTORE_TPL, "…"));
                    animRestoreBtn.addActionListener(e -> restoreAnimPreset());
                    row.cell(animRestoreBtn);
                    colorRestoreBtn = new javax.swing.JButton(String.format(RESTORE_TPL, "…"));
                    colorRestoreBtn.addActionListener(e -> restoreColorPreset());
                    row.cell(colorRestoreBtn);
                    return kotlin.Unit.INSTANCE;
                });

                // ---------- A. 总开关 ----------
                panel.group("总开关", true, g -> {
                    g.row("启用拖尾光标（总开关）", row -> {
                        row.topGap(TopGap.MEDIUM);
                        bindCheck(row, () -> pending.enabled, v -> pending.enabled = v);
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("拖尾渲染模式", row -> {
                        Cell<com.intellij.openapi.ui.ComboBox<String>> cell = ComboBoxKt.bindItem(
                                row.comboBox(MODE_ITEMS, null),
                                new MutableProperty<String>() {
                                    @Override public String get() { return modeText(pending.trailMode); }
                                    @Override public void set(String v) { pending.trailMode = textToMode(v); }
                                });
                        com.intellij.openapi.ui.ComboBox<String> box = cell.getComponent();
                        trailModeCombo = box;
                        // 下拉一改就刷新置灰；只改 enabled 状态，不碰 CONFIG
                        box.addActionListener(e -> springMode.set(isSpringText(box.getSelectedItem())));
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("隐藏原生光标（覆盖层充当光标）", row -> {
                        bindCheck(row, () -> pending.hideNativeCaret, v -> pending.hideNativeCaret = v)
                                .comment(W_HIDE_NATIVE, COMMENT, NO_LINK);
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("常驻刷新（永远按 8ms 调度）", row -> {
                        bindCheck(row, () -> pending.alwaysHighFps, v -> pending.alwaysHighFps = v)
                                .comment(W_ALWAYS_HIGH_FPS, COMMENT, NO_LINK);
                        return kotlin.Unit.INSTANCE;
                    });

                    // 契约 §2.2：W_COUPLING 挂在 #3/#4 下方的 rowComment
                    g.row("", row -> {
                        row.rowComment(W_COUPLING, COMMENT, NO_LINK);
                        return kotlin.Unit.INSTANCE;
                    });
                    return kotlin.Unit.INSTANCE;
                });

                // ---------- B. 淡出 ----------
                panel.group("淡出", true, g -> {
                    g.row("辉光淡出（停止后柔和熄灭）", row -> {
                        row.topGap(TopGap.MEDIUM);
                        bindCheck(row, () -> pending.overlayFadeOut, v -> {
                            pending.overlayFadeOut = v;
                            fadeOn.set(v);
                        });
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("淡出前保持时长", row -> {
                        addSlider(row, SPECS[0], fadeOn);
                        row.rowComment(W_FADE_OFF, COMMENT, NO_LINK).enabledIf(fadeOn);
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("淡出时长", row -> {
                        addSlider(row, SPECS[1], fadeOn);
                        return kotlin.Unit.INSTANCE;
                    });
                    return kotlin.Unit.INSTANCE;
                });

                // ---------- C. 颜色与辉光 ----------
                panel.group("颜色与辉光", true, g -> {
                    g.row("拖尾颜色", row -> {
                        row.topGap(TopGap.MEDIUM);
                        tailColorPanel = new ColorPanel();
                        tailColorPanel.setSupportTransparency(false);
                        // t7 / R-02：ColorPanel 不是 DialogPanel 的绑定控件，它改了 pending 不会
                        // 自动反映到「当前预设」下拉上 —— 必须自己挂 ActionListener 重算。
                        tailColorPanel.addActionListener(e -> onColorPanelChanged());
                        row.cell(tailColorPanel);
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("拖尾不透明度（不随预设变化）", row -> {
                        addSlider(row, SPECS[2], null);
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("启用辉光（不随预设变化）", row -> {
                        bindCheck(row, () -> pending.useShadow, v -> {
                            pending.useShadow = v;
                            shadowOn.set(v);
                        });
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("辉光颜色", row -> {
                        shadowColorPanel = new ColorPanel();
                        shadowColorPanel.setSupportTransparency(false);
                        shadowColorPanel.addActionListener(e -> onColorPanelChanged());
                        row.cell(shadowColorPanel).enabledIf(shadowOn);
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("辉光峰值不透明度", row -> {
                        addSlider(row, SPECS[3], shadowOn);
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("辉光层数", row -> {
                        addSlider(row, SPECS[4], shadowOn);
                        row.rowComment(W_GLOW_LAYERS, COMMENT, NO_LINK).enabledIf(shadowOn);
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("辉光锐度（越大越锐，外扩越窄）", row -> {
                        addSlider(row, SPECS[5], shadowOn);
                        row.rowComment(W_SHADOW_BLUR, COMMENT, NO_LINK).enabledIf(shadowOn);
                        return kotlin.Unit.INSTANCE;
                    });
                    return kotlin.Unit.INSTANCE;
                });

                // ---------- D. 拖尾手感（折叠，默认展开）----------
                panel.collapsibleGroup("拖尾手感", true, g -> {
                    g.row("拖尾密度", row -> {
                        row.topGap(TopGap.MEDIUM);
                        addSlider(row, SPECS[6], springMode);
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("拖尾拉伸强度（不随预设变化）", row -> {
                        addSlider(row, SPECS[7], springMode);
                        row.rowComment(W_STRETCH, COMMENT, NO_LINK).enabledIf(springMode);
                        return kotlin.Unit.INSTANCE;
                    });
                    return kotlin.Unit.INSTANCE;
                });

                // ---------- E. 动画时序（折叠，默认折叠 = 高级）----------
                panel.collapsibleGroup("动画时序", false, g -> {
                    g.row("跨行动画时长", row -> {
                        row.topGap(TopGap.MEDIUM);
                        addSlider(row, SPECS[8], null);
                        row.rowComment(W_ANIM_LEN, COMMENT, NO_LINK);
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("短动画时长", row -> {
                        addSlider(row, SPECS[9], null);
                        row.rowComment(W_SHORT_LEN, COMMENT, NO_LINK);
                        return kotlin.Unit.INSTANCE;
                    });

                    // M4：输入平滑。放在短动画时长正下方 —— 它是那个值的「形状」而不是「长度」。
                    g.row("输入平滑（只影响单行打字/退格，不随预设变化）", row -> {
                        addSlider(row, SPECS[10], null);
                        row.rowComment(W_TYPING_SMOOTH, COMMENT, NO_LINK);
                        return kotlin.Unit.INSTANCE;
                    });

                    // 契约 §7.3 第三行：恒显示的耦合提示（逐字）
                    g.row("", row -> {
                        row.rowComment(W_ANIM_COUPLING, COMMENT, NO_LINK);
                        return kotlin.Unit.INSTANCE;
                    });

                    // 契约 §7.4：两条【必须】动态计算的耦合提示（禁止写死数字）
                    g.row("", row -> {
                        singleMoveHint = new JLabel();
                        row.cell(singleMoveHint);
                        return kotlin.Unit.INSTANCE;
                    });

                    g.row("", row -> {
                        crossMoveHint = new JLabel();
                        row.cell(crossMoveHint);
                        return kotlin.Unit.INSTANCE;
                    });
                    return kotlin.Unit.INSTANCE;
                });

                // ---------- F. 高级（诊断）（折叠，默认折叠）----------
                // 从 Tools 菜单挪进来（0.9.6 / M1）：菜单只留 3 项，诊断属于「偶尔用一次」的高级项。
                // 注意它**不持久化** —— 所以也不参与 isModified()：勾选后点「应用」才生效，重启即失效。
                panel.collapsibleGroup("高级（诊断）", false, g -> {
                    g.row("性能诊断（跳过重绘，只跑计算）", row -> {
                        row.topGap(TopGap.MEDIUM);
                        bindCheck(row, () -> paintDiag, v -> paintDiag = v)
                                .comment(W_PAINT_DIAG, COMMENT, NO_LINK);
                        return kotlin.Unit.INSTANCE;
                    });
                    return kotlin.Unit.INSTANCE;
                });

                return kotlin.Unit.INSTANCE;
            });
        } finally {
            suppressDerive = false;
        }
    }

    // ==================== 滑块构建（缺陷 1 + 缺陷 2 + 吸附 + 输入框）====================

    /**
     * 建一条「滑块 → 输入框 → 数值标签」的 row，并接进双向绑定。
     *
     * <p><b>三列的先后【必须】是 滑块 → 输入框 → 标签</b>（契约 §6.7）：
     * {@code DialogPanel} 的 apply 回调表是 {@code LinkedHashMap}（javap 实证），
     * 因此回调按<b>构建顺序</b>执行。输入框必须<b>最后</b>写 {@code pending} ——
     * 否则用户键入的精确值会被滑块的吸附值覆盖（实测 A-4 的 A/B 段：333 被 350 覆盖）。
     * <b>调换这三行的顺序就是静默的数据丢失。</b>
     *
     * @param spec      刻度表里的一项
     * @param enabledIf 联动置灰属性，可为 null
     */
    private void addSlider(Row row, SliderSpec spec, GraphProperty<Boolean> enabledIf) {
        SliderBinding b = new SliderBinding(spec);

        // ---- 1) 滑块（必须最先建）----
        Cell<JSlider> cell = row.slider(spec.min, spec.max, spec.step, spec.step);  // minor == major
        b.slider = cell.getComponent();
        // 吸附开关：拖动只落在刻度上（契约 §1.4；paintTicks=false 不影响吸附）
        b.slider.setSnapToTicks(true);

        // ---- 2) 缺陷 2 的修法：只给 min / mid / max 三个关键刻度（契约 §2.2）----
        // labelTable 的条目数 = (max-min)/majorTickSpacing + 1，原来是 51 / 101 / 201 条 → 挤成一团。
        Map<Integer, JComponent> marks = new LinkedHashMap<>();
        marks.put(spec.min, new JLabel(spec.tickText.apply(spec.min)));
        int mid = spec.min + ((spec.max - spec.min) / 2 / spec.step) * spec.step;   // 必须对齐到 step
        if (mid > spec.min && mid < spec.max) {
            marks.put(mid, new JLabel(spec.tickText.apply(mid)));
        }
        marks.put(spec.max, new JLabel(spec.tickText.apply(spec.max)));
        SliderKt.labelTable(cell, marks);
        // labelTable 内部是 new Hashtable + setLabelTable，【不会】替你打开 paintLabels → 必须显式设置
        b.slider.setPaintLabels(true);
        b.slider.setPaintTicks(false);     // 不画刻度线，只保留 label 的位置语义

        // ---- 3) 输入框（必须建在滑块【之后】）----
        Cell<JBTextField> tf = row.intTextField(
                new IntRange(spec.min, spec.max), spec.readTick.applyAsInt(pending));
        b.textField = tf.getComponent();
        TextFieldKt.bindIntText(tf,
                // getter 返回【精确值】，不 snap：用户键入的 333 必须原样进 pending
                (Function0<Integer>) () -> spec.readTick.applyAsInt(pending),
                (Function1<Integer, kotlin.Unit>) v -> {
                    spec.writeTick.accept(pending, v);
                    refreshDerivedHints();
                    return kotlin.Unit.INSTANCE;
                });
        b.textField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onTextEdited(b); }
            @Override public void removeUpdate(DocumentEvent e) { onTextEdited(b); }
            @Override public void changedUpdate(DocumentEvent e) { onTextEdited(b); }
        });
        // 契约 §6.5：失焦才做 clamp + 回写（键入过程中不 clamp，否则 999 会被改成 500 打不下去）
        b.textField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { normalizeTextField(b); }
        });

        // ---- 4) 右侧数值标签 ----
        JLabel valueLabel = new JLabel(spec.valueText.apply(this, spec.readTick.applyAsInt(pending)));
        b.valueLabel = valueLabel;
        Cell<JLabel> labelCell = row.cell(valueLabel);

        // ---- 修法 3：getter 返回【吸附后的】 pending（防 B-2）----
        SliderKt.bindValue(cell,
                (Function0<Integer>) () -> snapToTick(spec.readTick.applyAsInt(pending),
                        spec.min, spec.max, spec.step),
                (Function1<Integer, kotlin.Unit>) v -> {
                    spec.writeTick.accept(pending, v);
                    refreshDerivedHints();
                    return kotlin.Unit.INSTANCE;
                });

        // ---- 修法 1：显式 ChangeListener（缺陷 1 的直接修复）----
        // 用 JSlider 的 ChangeListener（不是 ItemListener）。吸附开启后一次 setValue 可能
        // 触发 2 次 ChangeEvent（实测 [100, 100]），因此监听器必须幂等 —— 它只写「当前值」。
        b.slider.addChangeListener(e -> {
            if (syncingControls) {
                return;                                  // 程序化回灌，不回写
            }
            int v = b.slider.getValue();                 // 已被 JSlider 吸附过
            spec.writeTick.accept(pending, v);           // ① 写 pending（脏标记需要）
            valueLabel.setText(spec.valueText.apply(this, v));   // ② 右侧标签实时跟随 ← 缺陷 1
            syncTextFieldIfDifferent(b, v);              // ③ 输入框文字同步
            refreshDerivedHints();
            // ④ 预设下拉的显示必须跟着变（t7 / R-02）。
            //    pending 一变，原先匹配上的预设就可能不再匹配 → 必须重算，
            //    否则下拉继续显示「原版粉色」，而实际值早已不是它（显示层撒谎）。
            refreshPresetDisplay();
        });

        if (enabledIf != null) {
            cell.enabledIf(enabledIf);
            tf.enabledIf(enabledIf);
            labelCell.enabledIf(enabledIf);
        }

        bindings.add(b);
    }

    /**
     * 把任意原始值<b>吸附</b>到最近刻度，并 <b>clamp</b> 到 {@code [min, max]}。
     *
     * <p>它【必须】同时做两件事：
     * <ul>
     *   <li><b>clamp</b>：越界输入（如 XML 里被手改成 9999）不能进滑块；</li>
     *   <li><b>吸附</b>：抹平「精确值 vs 刻度值」的差异 —— 只 clamp 不吸附时，
     *       {@code pending=333} / 滑块显示 {@code 350} 会让 {@code isModified()} 恒真（B-2）。</li>
     * </ul>
     * {@code Math.round} 对 .5 向上，与 {@code JSlider} 的 tick 吸附规则一致
     * （契约 §1.3.1 的样本：333→350 / 37→50 / -9→0 / 9999→500 / 25→50）。
     */
    static int snapToTick(int raw, int min, int max, int step) {
        int clamped = Math.max(min, Math.min(max, raw));
        int k = Math.round((float) (clamped - min) / step);
        return Math.max(min, Math.min(max, min + k * step));
    }

    // ==================== 双向同步（契约 §6.4 / §6.5）====================

    /** 方向 1：滑块 → 输入框。只改文字，不碰 pending。 */
    private void syncTextFieldIfDifferent(SliderBinding b, int v) {
        syncingControls = true;
        try {
            String txt = String.valueOf(v);
            if (!txt.equals(b.textField.getText())) {
                b.textField.setText(txt);
            }
        } finally {
            syncingControls = false;
        }
    }

    /** 方向 2：输入框 → 滑块位置。只挪滑块，pending 交给 apply（契约 §6.1 的语义表）。 */
    private void onTextEdited(SliderBinding b) {
        if (syncingControls) {
            return;
        }
        String t = b.textField.getText();
        if (t == null || !t.trim().matches("-?\\d+")) {
            return;                                   // 非数字：什么都不做
        }
        int v;
        try {
            v = Integer.parseInt(t.trim());
        } catch (NumberFormatException ex) {
            return;
        }
        syncingControls = true;
        try {
            int snapped = snapToTick(v, b.spec.min, b.spec.max, b.spec.step);
            if (b.slider.getValue() != snapped) {
                b.slider.setValue(snapped);
            }
        } finally {
            syncingControls = false;
        }
        // 标签显示【用户输入】而不是滑块值（契约 §6.1 / B-2b）
        b.valueLabel.setText(b.spec.valueText.apply(this, v));
        refreshDerivedHints();
        // t7 / R-02：键入时 pending 按契约【不写】（§6.1 的语义表：只在 apply 时写精确值），
        // 所以不能拿 pending 去匹配预设 —— 否则「键入 3 但 pending 还是 8」时下拉毫无变化，
        // 而标签已经显示 3，两者对不上。这里用「用户输入值覆盖 pending 后」的视图做匹配，
        // 只影响<b>显示</b>，不写 pending。
        refreshPresetDisplayForInput(b, v);
    }

    /** 契约 §6.5：失焦规整 —— 非法回滚、越界 clamp 后写回 pending。 */
    private void normalizeTextField(SliderBinding b) {
        String t = b.textField.getText();
        int v = b.spec.readTick.applyAsInt(pending);
        if (t != null && t.trim().matches("-?\\d+")) {
            try {
                v = Integer.parseInt(t.trim());
            } catch (NumberFormatException ignored) {
                // 保持 pending 现值
            }
        }
        int clamped = Math.max(b.spec.min, Math.min(b.spec.max, v));
        syncingControls = true;
        try {
            b.textField.setText(String.valueOf(clamped));
            b.slider.setValue(snapToTick(clamped, b.spec.min, b.spec.max, b.spec.step));
        } finally {
            syncingControls = false;
        }
        b.spec.writeTick.accept(pending, clamped);
        b.valueLabel.setText(b.spec.valueText.apply(this, clamped));
        refreshDerivedHints();
        refreshPresetDisplay();      // t7 / R-02：失焦规整也会改 pending，下拉必须重算
    }

    /** 把 {@code pending} 灌进全部控件（reset / 预设应用共用）。 */
    /**
     * 把 {@code pending} 灌进全部控件（reset / 预设应用 / 预设恢复共用）。
     *
     * <p><b>两个抑制标志必须同生同死</b>：
     * <ul>
     *   <li>{@code syncingControls} —— 控件监听器不回写；</li>
     *   <li>{@code suppressDerive} —— 本方法末尾的 {@code refreshPresetDisplay()}
     *       会走到 {@code afterPendingEdited()}，那是「程序化回灌」而不是「用户编辑」，
     *       绝不能在那里自动派生副本（否则 reset() 装载历史配置就会凭空多出副本，
     *       isModified() 恒为真 —— V11-3c / U13 各抓到过一次）。</li>
     * </ul>
     *
     * <p><b>为什么放在这里而不是三个调用点各写一遍</b>：之前放在调用点，
     * 漏掉了 {@code reset()} 末尾这一次，同一个 bug 复发。放进方法内部后，
     * 任何调用路径都自动受保护。
     *
     * <p><b>为什么用一个 try/finally 而不是两个</b>：两个 finally 时，
     * 第一段抛异常会让第二段也被跳过，{@code suppressDerive} 就永久卡在 true ——
     * 此后所有编辑都不再派生副本，而且没有任何报错。一个 finally 里同时恢复才安全。
     */
    private void syncAllControlsFromPending() {
        boolean prevSuppress = suppressDerive;
        suppressDerive = true;
        syncingControls = true;
        try {
            for (SliderBinding b : bindings) {
                int exact = b.spec.readTick.applyAsInt(pending);
                int snapped = snapToTick(exact, b.spec.min, b.spec.max, b.spec.step);
                if (b.slider.getValue() != snapped) {
                    b.slider.setValue(snapped);
                }
                String txt = String.valueOf(exact);
                if (!txt.equals(b.textField.getText())) {
                    b.textField.setText(txt);
                }
                b.valueLabel.setText(b.spec.valueText.apply(this, exact));
            }
            if (tailColorPanel != null) {
                tailColorPanel.setSelectedColor(parseHex(pending.tailColor));
            }
            if (shadowColorPanel != null) {
                shadowColorPanel.setSelectedColor(parseHex(pending.shadowColor));
            }
            refreshDerivedHints();
            refreshPresetDisplay();
        } finally {
            syncingControls = false;
            suppressDerive = prevSuppress;      // ← 同一个 finally 里恢复，异常时也不会漏
        }
    }

    // ==================== 预设（契约 §5.7 / §8.6）====================

    /**
     * 动画预设被选中。<b>只写 {@code pending}，绝不直接写 state / CONFIG</b>。
     *
     * <p><b>0.9.9 起下拉里有两类项</b>：
     * <ul>
     *   <li>下标 {@code < ANIM_PRESETS.length} → <b>内置预设</b>（永久只读，只写那 6 个动画字段）；</li>
     *   <li>下标 {@code >= ANIM_PRESETS.length} → <b>自定义预设</b>（恢复它的<b>全参数快照</b>）。</li>
     * </ul>
     *
     * <p>选内置预设时会把 {@code animCustomName} 清空 —— 于是此后任何参数改动都会
     * 重新派生一份新副本（见 {@link #afterPendingEdited()}），内置那份永远不被污染。
     */
    private void onAnimPresetPicked() {
        if (syncingControls || animPresetCombo == null) {
            return;
        }
        int idx = animPresetCombo.getSelectedIndex();
        if (idx < 0) {
            refreshPresetDisplay();
            return;
        }
        if (idx < CursorPresets.ANIM_PRESETS.length) {
            // 内置预设：只写 6 个动画字段，其余 13 个参数一概不动（正交性硬判据）
            writeAnimValues(pending, animConstantsOf(idx));
            animBaseIdx = idx;
            pending.animPresetBase = idx;
            pending.animCustomName = "";
        } else {
            java.util.List<CustomPresets.Entry> entries =
                    CustomPresets
                            .parse(pending.customAnimPresets);
            int ci = idx - CursorPresets.ANIM_PRESETS.length;
            if (ci < 0 || ci >= entries.size()) {
                refreshPresetDisplay();
                return;
            }
            CustomPresets.Entry e = entries.get(ci);
            // 自定义预设是【全参数快照】：恢复它 = 把 19 个参数一次性写回
            SettingsField
                    .restore(pending, e.snapshot);
            pending.animCustomName = e.name;
        }
        syncAllControlsFromPending();
    }

    /** 颜色预设被选中。只写 2 个 HEX，不碰任何动画字段（正交性硬判据）。 */
    private void onColorPresetPicked() {
        if (syncingControls || colorPresetCombo == null) {
            return;
        }
        int idx = colorPresetCombo.getSelectedIndex();
        if (idx < 0 || idx >= CursorPresets.COLOR_PRESETS.length) {
            refreshPresetDisplay();
            return;
        }
        CursorPresets.ColorPreset p = CursorPresets.COLOR_PRESETS[idx];
        pending.tailColor = p.tailColor();
        pending.shadowColor = p.shadowColor();
        syncAllControlsFromPending();
    }

    /**
     * ColorPanel 变更回调（t7 / R-02 的第 4 处入口）。
     *
     * <p>ColorPanel <b>不是</b> {@code DialogPanel} 的绑定控件 —— 用户选色时它只改自己的
     * 内部状态，{@code pending} 要等 {@code apply()} 才回读。因此这里要先把颜色同步进
     * {@code pending}（只同步这两个 HEX，不碰别的字段），再重算「当前预设」，
     * 否则用户换完颜色、下拉还显示原来的颜色预设。
     *
     * <p>{@link #refreshPresetDisplay()} 自身在 {@code syncingControls} 保护下，
     * 且 {@code setSelectedIndex} 触发的 actionPerformed 会被 {@code onColorPresetPicked()}
     * 的 {@code syncingControls} 早退挡住 —— 无回环。
     */
    private void onColorPanelChanged() {
        if (syncingControls) {
            return;
        }
        if (tailColorPanel != null && tailColorPanel.getSelectedColor() != null) {
            pending.tailColor = toHex(tailColorPanel.getSelectedColor());
        }
        if (shadowColorPanel != null && shadowColorPanel.getSelectedColor() != null) {
            pending.shadowColor = toHex(shadowColorPanel.getSelectedColor());
        }
        refreshPresetDisplay();
    }

    /**
     * 键入路径专用的预设显示重算（t7 / R-02）。
     *
     * <p><b>为什么不直接调 refreshPresetDisplay()</b>：契约 §6.1 的语义表规定
     * 「键入合法值 → pending <b>只在 apply() 时写</b>」。所以键入 3 时 pending 仍是 8，
     * 拿 pending 去匹配会一直匹配「原版粉色」，而下拉与标签（显示 3）就对不上。
     *
     * <p>这里用「把用户输入值临时代入 pending 视图」的方式算匹配 ——
     * <b>只用于显示，绝不写 pending</b>（真正的落库仍由 apply() 完成）。
     */
    private void refreshPresetDisplayForInput(SliderBinding b, int inputTick) {
        if (animPresetCombo == null || colorPresetCombo == null) {
            return;
        }
        // 复制一份 pending，把这一格换成用户输入值，再拿副本去匹配
        NeovideCursorSettings.State view = deepCopy(pending);
        b.spec.writeTick.accept(view, inputTick);
        CursorPresets.AnimPreset a = CursorPresetApplier.matchAnimPreset(view);
        syncingControls = true;
        try {
            animPresetCombo.setSelectedIndex(
                    a == null ? animPresetItems.size() - 1 : indexOfAnim(a));
        } finally {
            syncingControls = false;
        }
    }

    /**
     * 重算两个下拉的当前项，并在需要时<b>自动派生/更新自定义预设</b>。
     *
     * <p><b>0.9.9 的两个关键变化</b>：
     * <ol>
     *   <li>先调 {@link #afterPendingEdited()} —— 用户改了参数就要有落脚点；</li>
     *   <li>下拉项 = 7 个内置 + N 个自定义，<b>没有任何「（已修改）」后缀</b>
     *       （内置预设不再被污染，这个后缀失去了存在理由）。</li>
     * </ol>
     */
    private void refreshPresetDisplay() {
        if (animPresetCombo == null || colorPresetCombo == null) {
            return;
        }
        // 1) 自动派生 / 更新自定义预设（必须先于重建下拉，否则新名字进不了列表）
        afterPendingEdited();

        // 2) 基准与颜色匹配
        animBaseIdx = CursorPresets.clampAnimPresetIndex(pending.animPresetBase);
        CursorPresets.ColorPreset c = CursorPresetApplier.matchColorPreset(pending);
        if (c != null) {
            colorBaseIdx = indexOfColor(c);
        }

        // 3) 下拉项 = 内置 + 自定义
        java.util.List<String> names = new ArrayList<>(animPresetNames());
        java.util.List<CustomPresets.Entry> entries =
                CustomPresets
                        .parse(pending.customAnimPresets);
        int selectedCustom = -1;
        for (int i = 0; i < entries.size(); i++) {
            names.add(entries.get(i).name);
            if (entries.get(i).name.equals(pending.animCustomName)) {
                selectedCustom = i;
            }
        }

        syncingControls = true;
        try {
            replaceModel(animPresetCombo, animPresetItems, names);
            rebuildColorItems(colorPresetCombo, colorPresetItems);
            // 当前项：自定义优先（animCustomName 非空就是它），否则内置基准
            int sel = selectedCustom >= 0
                    ? CursorPresets.ANIM_PRESETS.length + selectedCustom
                    : animBaseIdx;
            if (sel >= 0 && sel < animPresetItems.size()) {
                animPresetCombo.setSelectedIndex(sel);
            }
            colorPresetCombo.setSelectedIndex(
                    c == null ? colorPresetItems.size() - 1 : indexOfColor(c));
            refreshRestoreButtonText();
        } finally {
            syncingControls = false;
        }
    }

    /**
     * 用户改动参数后的<b>落脚点</b>（0.9.9 的核心交互）。
     *
     * <p>规则（IntelliJ 配色方案 / GIMP 工具预设的模型）：
     * <ul>
     *   <li>当前选中<b>自定义预设</b> → 把它的快照更新成当前值（这就是「随便改」）；</li>
     *   <li>当前选中<b>内置预设</b> → 一旦 6 个动画字段偏离该预设常量，
     *       <b>立刻派生</b>一个「{内置名} 副本」并切过去，内置那份一位数都不动；</li>
     *   <li>没偏离 → 什么都不做（避免一打开面板就凭空多出一堆副本）。</li>
     * </ul>
     *
     * <p><b>为什么判据只看那 6 个动画字段</b>：内置预设只定义这 6 项，
     * 其余 13 个参数从来不属于预设（改它们不该fork 出新预设）。
     * 但自定义预设的<b>快照存全部参数</b> —— 于是「存一份自定义预设」= 存下整套观感。
     *
     * <p>GIMP 官方文档的说法正好是这条规则的依据：编辑内置预设时自动创建副本，
     * 所以「Duplicate Tool Preset」命令永远是禁用的 —— 不需要。
     */
    private void afterPendingEdited() {
        if (syncingControls || suppressDerive) {
            return;
        }
        // A. 已在自定义预设上 → 持续同步快照
        if (!pending.animCustomName.isEmpty()) {
            if (CustomPresets
                    .has(pending.customAnimPresets, pending.animCustomName)) {
                pending.customAnimPresets = CustomPresets
                        .put(pending.customAnimPresets, pending.animCustomName,
                                SettingsField
                                        .snapshot(pending));
            } else {
                pending.animCustomName = "";     // 名字没了（被删/被手改坏）→ 退回内置
            }
            return;
        }
        // B. 在内置预设上 → 只在真的偏离时才派生
        int base = CursorPresets.clampAnimPresetIndex(pending.animPresetBase);
        if (sameAnimValues(animValuesOf(pending), animConstantsOf(base))) {
            return;                              // 没偏离，不派生
        }
        if (!CustomPresets
                .canAdd(pending.customAnimPresets)) {
            return;                              // 到上限了，不再派生（保护下拉可用性）
        }
        String name = CustomPresets
                .uniqueName(pending.customAnimPresets,
                        CursorPresets.ANIM_PRESETS[base].name() + " 副本");
        pending.animCustomName = name;
        pending.customAnimPresets = CustomPresets
                .put(pending.customAnimPresets, name,
                        SettingsField
                                .snapshot(pending));
    }


    /** 颜色下拉：5 个预设 + 末项「自定义颜色」（颜色不参与预设语义，没有「已修改」）。 */
    private static void rebuildColorItems(com.intellij.openapi.ui.ComboBox<String> combo,
                                          List<String> items) {
        List<String> next = new ArrayList<>(colorPresetNames());
        next.add(CursorPresets.CUSTOM_COLOR_LABEL);
        replaceModel(combo, items, next);
    }

    /** 内容没变就不动模型（避免每次 refresh 都重置选中项与发事件）。 */
    private static void replaceModel(com.intellij.openapi.ui.ComboBox<String> combo,
                                     List<String> items, List<String> next) {
        if (items.equals(next)) {
            return;
        }
        items.clear();
        items.addAll(next);
        combo.setModel(new javax.swing.DefaultComboBoxModel<>(next.toArray(new String[0])));
    }

    /** 刷新两个「恢复为「X」默认值」按钮的文案（基准预设可能刚变过）。 */
    private void refreshRestoreButtonText() {
        if (animRestoreBtn != null) {
            // 0.9.9：文案必须跟着【当前选中项】走 —— 在自定义预设上时显示它的名字，
            // 否则用户点「恢复为「原版粉色」默认值」会以为是回到内置常量，
            // 实际却是回到那个自定义预设已存的值（M5-4 抓到过这个不一致）。
            String current = pending.animCustomName.isEmpty()
                    ? CursorPresets.ANIM_PRESETS[
                            CursorPresets.clampAnimPresetIndex(animBaseIdx)].name()
                    : pending.animCustomName;
            animRestoreBtn.setText(String.format(RESTORE_TPL, current));
        }
        if (colorRestoreBtn != null) {
            colorRestoreBtn.setText(String.format(RESTORE_TPL,
                    CursorPresets.COLOR_PRESETS[colorBaseIdx].name()));
        }
    }

    /**
     * 把<b>当前选中项</b>的值重新写进 pending。
     *
     * <p>0.9.9 起语义变了：内置预设只读，所以这里不再是「清掉覆盖、回到默认值」，
     * 而是「丢弃面板里尚未保存的拖动，回到当前预设的已保存值」——
     * 对内置预设 = 回到那 6 个常量；对自定义预设 = 回到它存的快照。
     */
    private void restoreAnimPreset() {
        // 【必须在抑制档里做】syncAllControlsFromPending() 会走到
        // refreshPresetDisplay() → afterPendingEdited()，而后者会把快照
        // 【重写成当前值】。不抑制的话，刚恢复出来的值立刻被覆写回去 —— M5-5 抓到过。
        boolean prev = suppressDerive;
        suppressDerive = true;
        try {
            if (!pending.animCustomName.isEmpty()) {
                String snap = CustomPresets
                        .get(pending.customAnimPresets, pending.animCustomName);
                if (snap != null) {
                    SettingsField
                            .restore(pending, snap);
                    syncAllControlsFromPending();
                    return;
                }
                pending.animCustomName = "";
            }
            if (animBaseIdx < 0 || animBaseIdx >= CursorPresets.ANIM_PRESETS.length) {
                return;
            }
            writeAnimValues(pending, animConstantsOf(animBaseIdx));
            pending.animPresetBase = animBaseIdx;
            syncAllControlsFromPending();
        } finally {
            suppressDerive = prev;
        }
    }

    /** M5：把基准颜色预设的 2 个 HEX 重新写进 pending。 */
    private void restoreColorPreset() {
        if (colorBaseIdx < 0 || colorBaseIdx >= CursorPresets.COLOR_PRESETS.length) {
            return;
        }
        CursorPresets.ColorPreset p = CursorPresets.COLOR_PRESETS[colorBaseIdx];
        pending.tailColor = p.tailColor();
        pending.shadowColor = p.shadowColor();
        syncAllControlsFromPending();
    }

    // ==================== 自定义预设动作（0.9.9）====================

    /**
     * 把当前的全部参数存成一个新的自定义预设。
     *
     * <p>与「自动派生」互补：
     * <ul>
     *   <li>自动派生 — 用户<b>改动画字段</b>时自动发生，不需要点任何按钮；</li>
     *   <li>本方法 — 用户显式要「把现在这套存下来」（可能只改了颜色/拉伸强度等
     *       不在内置预设定义范围内的参数）。</li>
     * </ul>
     *
     * <p>名字默认取「{当前预设名} 副本」并自动去重。
     */
    private void createCustomPreset() {
        if (!CustomPresets
                .canAdd(pending.customAnimPresets)) {
            Messages.showWarningDialog(
                    "自定义预设最多 " + CustomPresets
                            .MAX_PRESETS + " 个。请先删除不用的。", "无法新建");
            return;
        }
        String base = pending.animCustomName.isEmpty()
                ? CursorPresets.ANIM_PRESETS[
                        CursorPresets.clampAnimPresetIndex(pending.animPresetBase)].name()
                : pending.animCustomName;
        String suggested = CustomPresets
                .uniqueName(pending.customAnimPresets, base + " 副本");
        java.awt.Component parent = suppressDerive ? null : createComponent();
        String name = Messages.showInputDialog(parent, "给这套设置起个名字：", "新建预设",
                null, suggested, null);
        if (name == null) {
            return;                                      // 用户取消
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            Messages.showWarningDialog("名字不能为空。", "无法新建");
            return;
        }
        if (CustomPresets
                .has(pending.customAnimPresets, trimmed)) {
            Messages.showWarningDialog("已有同名预设：「" + trimmed + "」。请换个名字。", "无法新建");
            return;
        }
        pending.customAnimPresets = CustomPresets
                .put(pending.customAnimPresets, trimmed,
                        SettingsField.snapshot(pending));
        pending.animCustomName = trimmed;
        syncAllControlsFromPending();
    }

    /** 重命名当前选中的自定义预设。内置预设只读，没有「改名」这个概念。 */
    private void renameCustomPreset() {
        if (pending.animCustomName.isEmpty()) {
            Messages.showInfoMessage("内置预设是只读的，不能改名。要改请先「新建预设」。", "重命名");
            return;
        }
        String from = pending.animCustomName;
        String name = Messages.showInputDialog(createComponent(), "新名字：", "重命名预设",
                null, from, null);
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.equals(from)) {
            return;
        }
        String next = CustomPresets
                .rename(pending.customAnimPresets, from, trimmed);
        if (next == null) {
            Messages.showWarningDialog("已有同名预设：「" + trimmed + "」。请换个名字。", "无法重命名");
            return;
        }
        pending.customAnimPresets = next;
        pending.animCustomName = trimmed;
        syncAllControlsFromPending();
    }

    /** 删除当前选中的自定义预设。内置预设不可删。 */
    private void deleteCustomPreset() {
        if (pending.animCustomName.isEmpty()) {
            Messages.showInfoMessage("内置预设是只读的，不能删除。", "删除");
            return;
        }
        String name = pending.animCustomName;
        int ok = Messages.showYesNoDialog(createComponent(),
                "删除自定义预设「" + name + "」？此操作不可撤销。",
                "删除预设", Messages.getQuestionIcon());
        if (ok != Messages.YES) {
            return;
        }
        pending.customAnimPresets = CustomPresets
                .remove(pending.customAnimPresets, name);
        pending.animCustomName = "";
        // 值保持在当前参数上（用户看到的样子不变），只是不再挂在任何自定义预设下；
        // 若这些值与内置常量不同，下一次编辑会自动派生新副本 —— 预期行为。
        syncAllControlsFromPending();
    }

    private static int indexOfAnim(CursorPresets.AnimPreset p) {
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            if (CursorPresets.ANIM_PRESETS[i] == p) {
                return i;
            }
        }
        return 0;
    }

    private static int indexOfColor(CursorPresets.ColorPreset p) {
        for (int i = 0; i < CursorPresets.COLOR_PRESETS.length; i++) {
            if (CursorPresets.COLOR_PRESETS[i] == p) {
                return i;
            }
        }
        return 0;
    }

    // ==================== 预设覆盖值（0.9.8）====================

    /**
     * 预设域的 6 个字段（顺序 = {@link CursorPresets#parseOverride} 的字段顺序）。
     *
     * <p><b>顺序是硬契约</b>：它必须与 {@code CursorPresets.AnimPreset} 的构造参数顺序逐位一致，
     * 错一位就会出现「辉光层数写进拖尾密度」这种只有肉眼能发现的错。
     */
    static float[] animValuesOf(NeovideCursorSettings.State s) {
        return new float[]{
                s.animationLength,
                s.shortAnimationLength,
                s.trailSize,
                s.glowLayers,
                s.shadowBlurFactor,
                s.glowOpacity
        };
    }

    /** 把 6 个值写回 State（glowLayers 是整数：四舍五入，不截断）。 */
    static void writeAnimValues(NeovideCursorSettings.State s, float[] v) {
        s.animationLength = v[0];
        s.shortAnimationLength = v[1];
        s.trailSize = v[2];
        s.glowLayers = Math.round(v[3]);
        s.shadowBlurFactor = v[4];
        s.glowOpacity = v[5];
    }

    /** 第 {@code idx} 个动画预设的 6 个常量（与 {@link #animValuesOf} 同序）。 */
    static float[] animConstantsOf(int idx) {
        CursorPresets.AnimPreset p = CursorPresets.ANIM_PRESETS[CursorPresets.clampAnimPresetIndex(idx)];
        return new float[]{
                p.animationLength(),
                p.shortAnimationLength(),
                p.trailSize(),
                p.glowLayers(),
                p.shadowBlurFactor(),
                p.glowOpacity()
        };
    }

    /** 6 个值是否逐项相等（必须用 {@link CursorPresets#EPS}，不用 {@code ==}）。 */
    static boolean sameAnimValues(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) >= CursorPresets.EPS) {
                return false;
            }
        }
        return true;
    }

    // ==================== 派生提示（契约 §7.4：禁止写死数字）====================

    /**
     * 刷新两条动态耦合提示 + 拉伸强度那一行的派生时长。
     *
     * <p>公式与 {@code TrailCorner.jump()} 同源（契约 §7.4）：
     * <ul>
     *   <li><b>短跳分支</b>（同一行、≤3 字符）：{@code 时长 = shortAnimationLength × trailFactors[rank]}；</li>
     *   <li><b>跨行分支</b>：{@code leading = animationLength × (1 - trailSize)}、{@code trailing = animationLength}。</li>
     * </ul>
     * 这里只<b>显示</b>，绝不做第二套换算。
     */
    private void refreshDerivedHints() {
        if (singleMoveHint != null) {
            float[] f = NeovideCursorSettings.deriveTrailFactors(pending.stretchRatioK);
            long trailing = Math.round(pending.shortAnimationLength * 1000f * f[0]);
            long leading = Math.round(pending.shortAnimationLength * 1000f * f[3]);
            singleMoveHint.setText(String.format(Locale.ROOT,
                    "单行动画时长 = 短动画时长 × 拉伸系数：后缘 %d ms / 前缘 %d ms（系数 %.3f×）。",
                    trailing, leading, f[3] > 0f ? f[0] / f[3] : 0f));
        }
        if (crossMoveHint != null) {
            long trailing = Math.round(pending.animationLength * 1000f);
            long leading = Math.round(pending.animationLength * 1000f * (1f - pending.trailSize));
            // t6 / t10-(R-19)：这里报的是 DampedSpring 的【时间常数】（= 参数值本身），
            // 不是「运动做完」的墙钟时长。
            //
            // 【为什么必须澄清口径】t10 用真实的 DampedSpring(dt=8ms) 量到：
            // 参数 0.35s → 实际运动 115 帧 / 920ms。面板若只写「350 ms」，
            // 用户按它判断会低报约 62%，看着困惑；而「单行动画时长」那条提示
            // 走的是 trailFactors 系数，口径与这条不同，两者并排会显得自相矛盾。
            //
            // 【为什么不直接乘一个系数】那是新的写死数字，需要在多处同步维护，
            // 下一轮审查必然再抓一次。改成「说明它是时间常数」既准确又零新常量。
            crossMoveHint.setText(String.format(Locale.ROOT,
                    "跨行动画时长：DampedSpring 时间常数 —— 后缘 = %d ms；前缘 = %d ms"
                            + "（拖尾密度 %.2f，为 1.00 时前缘为 0）。"
                            + "实际拖尾约为该值的 2.6 倍（0.35 s 实测约 920 ms / 115 帧）。",
                    trailing, leading, pending.trailSize));
        }
        // 拉伸强度那一行的数值标签里也带派生时长 → 一并刷新
        for (SliderBinding b : bindings) {
            if ("stretchRatioK".equals(b.spec.key)) {
                b.valueLabel.setText(b.spec.valueText.apply(this, b.spec.readTick.applyAsInt(pending)));
            }
        }
    }

    /**
     * 「拖尾拉伸强度」的派生显示：标称倍率 + 后缘/前缘绝对时长 + rank 方向。
     *
     * <p>时长 = <b>{@code pending.shortAnimationLength}</b> × {@code trailFactors[rank]}
     * （{@code TrailCorner.java:221}）。0.9.5 起短动画时长可调，因此这里
     * <b>必须读 pending</b>，不能用任何写死的镜像常量（契约 §10.2 的 N-2）。
     */
    String stretchText(float k) {
        float[] f = NeovideCursorSettings.deriveTrailFactors(k);
        long trailingMs = Math.round(pending.shortAnimationLength * 1000f * f[0]);
        long leadingMs = Math.round(pending.shortAnimationLength * 1000f * f[3]);
        float ratio = f[3] > 0f ? f[0] / f[3] : 0f;
        // 比值用三位小数：契约 §2.5 的自检表就是按 4.545 / 7.692 这种精度写的，
        // 面板显示必须与它逐位对得上，否则"显示"和"文档"又是两套口径。
        return String.format(Locale.ROOT,
                "%.1f×   后缘 %d ms / 前缘 %d ms   (rank0 最拖 → rank3 最跟手，实际比值 %.3f×)",
                k, trailingMs, leadingMs, ratio);
    }

    // ==================== reset / apply / isModified ====================

    /**
     * 从 service 取当前值灌进控件（契约 §3.4 的 R1–R4）。
     *
     * <p><b>顺序：先换 pending + baseline，再 super.reset()。</b>{@code DialogPanel.reset()} 是把
     * {@code get()} 的结果写进控件——pending 还是旧的，控件就会被灌上一轮的值。
     *
     * <p><b>baseline 必须与 pending 同时拍</b>：此刻 state 就是「尚未改动」的基准（缺陷 3）。
     */
    @Override
    public void reset() {
        // 打开/重开会话：pending 与 baseline 同源取当前 state（EditSession 保证同源）
        session.openAt(currentState());
        syncSessionViews();
        graphReset(pending);

        // 程序化回灌期间抑制监听器：否则吸附会把「精确值」改写成「刻度值」（B-2 变体）
        syncingControls = true;
        suppressDerive = true;      // 装载历史配置不是「用户编辑」，不派生副本
        try {
            super.reset();          // DialogPanel 按 pending 刷所有绑定控件
        } finally {
            syncingControls = false;
            suppressDerive = false;
        }
        syncAllControlsFromPending();
    }

    /**
     * 把控件值写回 service 并生效（契约 §3.3 的 A1–A6）。
     *
     * <p><b>唯一的生效入口是 {@link NeovideCaretManager#applyConfig()}</b>：直接改
     * {@code CONFIG} 字段会让颜色 / 辉光 / 淡出这些被<b>缓存</b>的值停在旧状态。
     *
     * <p><b>缺陷 3 的修法</b>：逐字段比较 {@code pending} 与 {@code baseline}，
     * <b>只有用户真改过的字段才写</b>。原来的写法是无条件全量写 18 个字段，
     * 面板打开期间 Tools 菜单改的值会被冲掉。
     *
     * <p><b>【禁止】把它改成「任一字段不同就整份替换」</b>（{@code if (dirtyAnyField) target = pending}）——
     * 那正是缺陷 3 的原缺陷：用户没碰的字段也会被面板值覆盖掉 Tools 的改动。
     *
     * <p><b>已知语义边界（契约 §3.5，刻意保留，不是缺陷）</b>：用户把某字段改了又改回原值、
     * 期间 Tools 也改了它 → {@code pending == baseline} → 判为「没改过」→ <b>Tools 的值胜出</b>。
     * 用户最终值就是「原值」，行为上等于没改；而 Tools 的改动是更晚发生的真实事件。
     */
    @Override
    public void apply() {
        // A1：非绑定控件回读（DialogPanel 追踪不到 ColorPanel）
        if (tailColorPanel != null && tailColorPanel.getSelectedColor() != null) {
            pending.tailColor = toHex(tailColorPanel.getSelectedColor());
        }
        if (shadowColorPanel != null && shadowColorPanel.getSelectedColor() != null) {
            pending.shadowColor = toHex(shadowColorPanel.getSelectedColor());
        }

        // A2：DialogPanel 把绑定控件的值写进 pending（滑块先、输入框后 —— 见 addSlider 的注释）
        syncingControls = true;
        suppressDerive = true;
        try {
            super.apply();
        } finally {
            syncingControls = false;
            suppressDerive = false;
        }

        NeovideCursorSettings svc = serviceOrNull();
        if (svc == null) {
            return;
        }
        NeovideCursorSettings.State target = svc.getState();

        // A3：逐字段脏标记合并（缺陷 3 的修法本体）—— 从 {@link SettingsField#FIELDS} 派生。
        //
        // 【0.9.9 / B2：为什么改成遍历表】
        // 这里原来是 20 段手写的 if，每段自己记得用哪种比较（float 用 Float.compare、
        // 颜色用 equalsIgnoreCase、其余用 !=）。它是「加一个参数要改 8 处」的第 6 处，
        // 而漏掉的后果是静默的：存了但读不到、改了但应用按钮不亮。
        // 现在比较语义由表项的 same 函数各自携带，遍历只负责「不同就拷」。
        //
        // 【语义一字未改】每个字段用的比较函数与旧代码逐条对应：
        //   float → Float.compare（NaN / -0.0f 两个边界都对）
        //   HEX   → equalsIgnoreCase（sanitizeColor 统一大写，手改 XML 可能留小写）
        //   其余  → 值比较
        for (SettingsField sf
                : SettingsField.FIELDS) {
            if (sf.differs(pending, baseline)) {
                sf.copy(pending, target);
            }
        }

        // 21（0.9.9 / 自定义预设）：内置预设【永久只读】，用户改动不污染它。
        //
        // 【模型对比】
        //   0.9.8：改动写进「该内置预设的覆盖值」→ 内置被污染 → 需要「（已修改）」后缀
        //          + 「恢复为默认值」按钮来收场。
        //   0.9.9：改动只落在<b>当前这个自定义预设</b>上；如果当前选的是内置预设，
        //          那么在拖动那一刻就已经派生出了一份副本（见 syncCustomPresetIfNeeded），
        //          内置那个一位数都不动。于是「已修改」概念消失。
        //
        // 这里只做「把 pending 里的自定义预设表/当前名同步到 target」这一件事。
        if (!SettingsField
                .textSame(pending.customAnimPresets, baseline.customAnimPresets)) {
            target.customAnimPresets = pending.customAnimPresets;
        }
        if (!SettingsField
                .textSame(pending.animCustomName, baseline.animCustomName)) {
            target.animCustomName = pending.animCustomName;
        }


        // A4：落盘
        svc.persistQuietly();

        // A5：唯一生效入口（写字段 → 调 setter → 刷新三类缓存）
        NeovideCaretManager.applyConfig();

        // A5.5：性能诊断（高级区，唯一不持久化的项）。
        // 只在**变化时**下发：setPaintDisabled 会写一行日志并清屏，每次 apply 都调会刷屏。
        if (NeovideCaretManager.isPaintDisabled() != paintDiag) {
            NeovideCaretManager.setPaintDisabled(paintDiag);
        }

        // A6+A7：pending 与 baseline 【同源前移】到 target。
        //
        // 【为什么合成一次调用】原来这里是两条独立语句：
        //     baseline = deepCopy(target);
        //     pending  = deepCopy(target);
        // R-01 那个 bug（第二次点「应用」把 Tools 的改动冲掉）就是漏了第二条。
        // 合成 EditSession.reloadFrom 之后，两者在方法体内一起做，结构上漏不掉。
        session.reloadFrom(target);
        syncSessionViews();
        // A7：pending 必须与 baseline 【同源】前移（0.9.5 t7 / R-01）。
        //
        // 【不这么做的实际后果 · 第二次点「应用」会冲掉 Tools 的改动】：
        //   1) 第 1 次 apply：用户没碰 X，pending.X == baseline.X → 不写 → Tools 的 X 保住；
        //   2) 但 pending.X 仍是【面板打开时的旧值】，而 target.X 已是 Tools 的新值
        //      → dirtyAnyField(pending, state) 仍为真 → 按钮保持点亮；
        //   3) 第 2 次 apply：baseline 已被前移成 Tools 的新值，
        //      于是 pending.X != baseline.X 成立 → 把【旧值】写回去 → Tools 被冲掉。
        // 两者同源前移后，第 2 次 apply 的判据全部为「没变」，不会再写任何字段。
        // A8：控件也必须跟着回灌到 target（t7 补强 —— 只前移 pending 是不够的）。
        //
        // 【为什么 A7 单独不够】：pending 前移之后，控件里<b>仍显示面板打开时的旧值</b>。
        // 第 2 次 apply 一开始就 {@code super.apply()}（把控件值写进 pending），
        // 于是 pending 又被<u>陈旧控件值</u>覆盖回去 → 判据重新变成「变脏」
        // → 把旧值写进 target → Tools 的改动第二次被冲掉。
        // 回灌控件后控件值与 target 一致，super.apply() 写回的也是同一组值，判据保持「没变」。
        //
        // 【为什么必须用 super.reset() 而不是只调 syncAllControlsFromPending()】：
        // 后者只回灌滑块 / 输入框 / ColorPanel / 数值标签 —— <b>复选框与下拉不在它的覆盖范围里</b>。
        // 而本例被 Tools 改掉的恰恰是复选框（overlayFadeOut）：控件仍显示 true，
        // 第 2 次 super.apply() 会把 true 写回 pending，再次冲掉 Tools 的 false。
        // {@code super.reset()} 走的是 DialogPanel 自己的 reset 回调表（全部绑定控件），
        // 正好补齐这一块；两者配合才完整。
        syncingControls = true;
        suppressDerive = true;
        try {
            super.reset();          // DialogPanel 把 pending 灌进【所有】绑定控件（含复选框/下拉）
        } finally {
            syncingControls = false;
            suppressDerive = false;
        }
        syncAllControlsFromPending();
    }

    /**
     * 是否有未保存的改动。
     *
     * <p><b>两半都必须有</b>（契约 §1.3.1 的 C，缺一即踩 B-1 或 B-2）：
     * <ol>
     *   <li>{@code super.isModified()} —— 抓「控件值 vs getter()」的差异
     *       （复选框/下拉被点，或输入框里"键入但未 apply"的文字）；</li>
     *   <li>{@code dirtyAnyField(pending, state)} —— 抓「pending vs state」的差异
     *       （滑块拖动已写 pending，但组件值与 getter 一致，super 看不见）。</li>
     * </ol>
     *
     * <p><b>为什么 dirtyAnyField 不可省</b>：修法 3 让 getter 返回 {@code snap(pending)}，
     * 于是滑块组件值恒等于 {@code getter()}，super 对滑块<b>永远</b>返回 false（B-1：
     * 应用按钮变灰 → 拖动被静默丢弃）。而拖动已经把 pending 写成新值，故这一半能抓到。
     *
     * <p><b>为什么 super 不可省</b>：只比 pending vs state 时，
     * 「精确值 333 / 刻度 350」会让 isModified 恒真（B-2：OK 按钮永远亮着）。
     */
    @Override
    public boolean isModified() {
        NeovideCursorSettings.State cur = currentState();
        return super.isModified()
                || colorPanelModified(cur)
                || dirtyAnyField(pending, cur);
    }

    /**
     * pending 与 state 是否有【任一】字段不同（{@code isModified()} 的第 3 半）。
     *
     * <p><b>0.9.9 / B3：委托给 {@link SettingsField#dirtyAnyField}（全项目唯一实现）。</b>
     *
     * <p>这里原来是 21 行手写的链式 {@code ||}，与 {@code apply()} 里的 20 段 if
     * <b>是同一套判据的两份手抄</b> —— 代码注释自己都写着「与 apply 的判据同源」。
     * 两份手抄的后果是实打实的两个已修复缺陷：
     * <ul>
     *   <li>M4 加 {@code typingSmoothness} 时只补了 apply 这边 → isModified() 恒 false
     *       → 用户实测「输入平滑拉不动应用」；</li>
     *   <li>0.9.8 加覆盖串时同样漏了 → 改了预设覆盖后点不了「应用」。</li>
     * </ul>
     *
     * <p>现在<b>只有一份实现</b>（在表类里），生产代码与探针调的是同一个函数 ——
     * 结构上不可能再出现「补了一边忘了另一边」，也不可能出现「探针测影子实现」。
     */
    static boolean dirtyAnyField(NeovideCursorSettings.State p, NeovideCursorSettings.State s) {
        return SettingsField.dirtyAnyField(p, s);
    }

    /** HEX 必须忽略大小写：{@code sanitizeColor} 统一成大写，用户手改 XML 可能留小写。 */
    static boolean eqHex(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    /**
     * 字符串逐字相等，{@code null} 一律当 {@code ""}。
     *
     * <p>覆盖串永远是「有则非空、无则空串」（见 CursorPresets#removeOverride），
     * 但 {@code State} 是可以被外部 new 出来并塞 null 的（探针 / 手写测试），
     * 直接 {@code equals} 会 NPE —— 在 {@code isModified()} 里 NPE 就是整页打不开。
     */
    static boolean eqText(String a, String b) {
        String x = (a == null) ? "" : a;
        String y = (b == null) ? "" : b;
        return x.equals(y);
    }

    /** ColorPanel 与 state 的颜色是否不同（不是 DialogPanel 的绑定控件，必须单独比）。 */
    private boolean colorPanelModified(NeovideCursorSettings.State cur) {
        if (tailColorPanel != null && tailColorPanel.getSelectedColor() != null
                && !toHex(tailColorPanel.getSelectedColor()).equalsIgnoreCase(cur.tailColor)) {
            return true;
        }
        return shadowColorPanel != null && shadowColorPanel.getSelectedColor() != null
                && !toHex(shadowColorPanel.getSelectedColor()).equalsIgnoreCase(cur.shadowColor);
    }

    @Override
    public void disposeUIResources() {
        super.disposeUIResources();
        tailColorPanel = null;
        shadowColorPanel = null;
        animPresetCombo = null;
        animNewBtn = null;
        animRenameBtn = null;
        animDeleteBtn = null;
        colorPresetCombo = null;
        singleMoveHint = null;
        crossMoveHint = null;
        bindings.clear();
        animPresetItems.clear();
        colorPresetItems.clear();
        session.close();
        syncSessionViews();
    }

    // ==================== 搜索定位 ====================

    /**
     * 设置页搜索：按关键字把焦点与滚动定位到对应字段。
     *
     * <p>0.9.9 / Phase 2 第 1 步：关键字映射已抽到 {@link SettingsSearchIndex}
     * （纯静态、零状态耦合）。这里只保留「拿到标签之后怎么滚动」这一段 ——
     * 它需要 Swing 组件树，属于面板自己的职责。
     */
    @Override
    public Runnable enableSearch(String option) {
        if (option == null || option.trim().isEmpty()) {
            return null;
        }
        final JLabel target = SettingsSearchIndex.targetFor(bindings,
                option.trim().toLowerCase(Locale.ROOT));
        if (target == null) {
            return null;
        }
        return () -> {
            target.requestFocusInWindow();
            JComponent parent = (JComponent) target.getParent();
            if (parent != null) {
                Rectangle r = target.getBounds();
                parent.scrollRectToVisible(r);
            }
        };
    }

    // ==================== 小工具 ====================

    /**
     * 复选框（不带行内文字：DSL 的行首文字与行内控件是两列，
     * 契约 §2.4 要求「左侧是字段名、右侧是控件」）。
     */
    private static Cell<JBCheckBox> bindCheck(Row row,
                                              Function0<Boolean> getter,
                                              final java.util.function.Consumer<Boolean> setter) {
        return ButtonKt.bindSelected(row.checkBox(""), new MutableProperty<Boolean>() {
            @Override public Boolean get() { return getter.invoke(); }
            @Override public void set(Boolean v) {
                setter.accept(v);
            }
        });
    }

    // 0.9.9 / Phase 2 第 2 步：纯函数已抽到 {@link SettingsUiUtil}
    private static String modeText(NeovideConfig.TrailMode mode) {
        return SettingsUiUtil.modeText(mode, MODE_SPRING_TEXT, MODE_AFTERIMAGE_TEXT);
    }

    private static NeovideConfig.TrailMode textToMode(String text) {
        return SettingsUiUtil.textToMode(text, MODE_AFTERIMAGE_TEXT);
    }

    /**
     * M6：锐度（0–100）⇄ 外扩幅度（0.60–0.05）的纯显示换算。
     *
     * <p>**不参与渲染**：真正写进 CONFIG 的仍然是 {@code shadowBlurFactor}（外扩幅度），
     * 而它是 {@code NeovideConfig} 的既有常量，本轮一个字节都没动。
     *
     * <p>映射：{@code blur = 0.05 + (100 - sharp) × 0.0055} ⇒
     * 0 = 外扩 0.60（最柔，原版 JS 的夸张光雾）、100 = 外扩 0.05（最锐）。
     * 往返误差由 {@link #sharpnessOf(float)} 的四舍五入吸收（同一 blur 读回同一锐度）。
     */
    public static float blurOfSharpness(int sharp) {
        return SettingsUiUtil.blurOfSharpness(sharp);
    }

    /** 外扩幅度 → 锐度（0–100）。M6 的显示层换算，见 {@link #blurOfSharpness(int)}。 */
    public static int sharpnessOf(float blur) {
        return SettingsUiUtil.sharpnessOf(blur);
    }

    private static boolean isSpringText(Object selected) {
        return SettingsUiUtil.isSpringText(selected, MODE_AFTERIMAGE_TEXT);
    }

    private static String fmt2(float v) {
        return SettingsUiUtil.fmt2(v);
    }

    private void graphReset(NeovideCursorSettings.State s) {
        springMode.set(s.trailMode == NeovideConfig.TrailMode.SPRING);
        shadowOn.set(s.useShadow);
        fadeOn.set(s.overlayFadeOut);
    }

    private static NeovideCursorSettings serviceOrNull() {
        try {
            return NeovideCursorSettings.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取当前持久化状态；服务不可用（无 Application 的离线探针）时退回默认值。 */
    private static NeovideCursorSettings.State currentState() {
        NeovideCursorSettings svc = serviceOrNull();
        if (svc != null) {
            return svc.getState();
        }
        return new NeovideCursorSettings.State();
    }


    /**
     * 把 {@link EditSession} 的两个 State 同步到本类的只读镜像字段。
     *
     * <p><b>这是本类里唯一给 {@code pending} / {@code baseline} 赋值的地方</b>
     * （RF-4 的判据就是 grep 这两个字段的赋值点 = 0）。
     *
     * <p>为什么不直接把 190 处 {@code pending} 改成 {@code session.pending()}：
     * 那样每处都变长，却不减少任何风险 —— 风险在于「谁能改变指向」，
     * 而这一点已经由「只有本方法能赋值 + 本方法只被 session 操作调用」保证。
     *
     * <p>为什么不做成「每次读都从 session 取」：{@code pending} 被当成值传给
     * {@code dirtyAnyField(pending, cur)} 之类的方法 101 次，每次都调方法没有收益。
     */
    private void syncSessionViews() {
        pending = session.pending();
        baseline = session.baseline();
    }

    private static NeovideCursorSettings.State deepCopy(NeovideCursorSettings.State from) {
        return SettingsUiUtil.deepCopy(from);
    }

    /** 供探针复用：{@code "#RRGGBB"} → Color。 */
    public static Color parseHex(String hex) {
        try {
            if (hex == null) {
                return Color.BLACK;
            }
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return new Color(Integer.parseInt(h, 16));
        } catch (Throwable t) {
            return Color.BLACK;
        }
    }

    /** 供探针复用：Color → {@code "#RRGGBB"}。 */
    public static String toHex(Color c) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static List<String> animPresetNames() {
        List<String> out = new ArrayList<>();
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            out.add(p.name());
        }
        return out;
    }

    private static List<String> colorPresetNames() {
        List<String> out = new ArrayList<>();
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            out.add(p.name());
        }
        return out;
    }

    // ==================== 滑块绑定（滑块 + 输入框 + 标签）====================

    /** 一个滑块的三个组件 + 它的规格。 */
    /** 滑块 + 输入框 + 数值标签三者一组。包私有以便 {@link SettingsSearchIndex} 读字段 key。 */
    static final class SliderBinding {
        final SliderSpec spec;
        JSlider slider;
        JBTextField textField;
        JLabel valueLabel;

        SliderBinding(SliderSpec spec) {
            this.spec = spec;
        }
    }

    // ==================== 供探针读取的观测量 ====================

    /** 探针/调试用：当前预览副本（只读语义，不要改它）。 */
    public NeovideCursorSettings.State pendingForProbe() {
        return pending;
    }

    /** 探针/调试用：面板打开那一刻的 state 快照（缺陷 3 的比较基准）。 */
    public NeovideCursorSettings.State baselineForProbe() {
        return baseline;
    }

    /** 探针/调试用：{@code syncingControls} 抑制标志的当前值。 */
    public boolean syncingForProbe() {
        return syncingControls;
    }

    /** 探针/调试用：10 个滑块，按创建顺序（= 面板从上到下）。 */
    public List<JSlider> slidersForProbe() {
        List<JSlider> out = new ArrayList<>();
        for (SliderBinding b : bindings) {
            out.add(b.slider);
        }
        return out;
    }

    /** 探针/调试用：10 个输入框，与 {@link #slidersForProbe()} 同序。 */
    public List<JBTextField> textFieldsForProbe() {
        List<JBTextField> out = new ArrayList<>();
        for (SliderBinding b : bindings) {
            out.add(b.textField);
        }
        return out;
    }

    /** 探针/调试用：10 个数值标签，与 {@link #slidersForProbe()} 同序。 */
    public List<JLabel> labelsForProbe() {
        List<JLabel> out = new ArrayList<>();
        for (SliderBinding b : bindings) {
            out.add(b.valueLabel);
        }
        return out;
    }

    /** 探针/调试用：10 个滑块的字段名，与 {@link #slidersForProbe()} 同序。 */
    public List<String> sliderKeysForProbe() {
        List<String> out = new ArrayList<>();
        for (SliderBinding b : bindings) {
            out.add(b.spec.key);
        }
        return out;
    }

    /** 探针/调试用：某个滑块的刻度表规格（min/max/step）。 */
    public int[] sliderRangeForProbe(int index) {
        SliderSpec s = bindings.get(index).spec;
        return new int[]{s.min, s.max, s.step};
    }

    /** 探针/调试用：动画预设下拉。 */
    public com.intellij.openapi.ui.ComboBox<String> animPresetComboForProbe() {
        return animPresetCombo;
    }

    /** 探针/调试用：颜色预设下拉。 */
    /** 探针/调试用：动画预设「恢复为「X」默认值」按钮（0.9.6 / M5）。 */
    public javax.swing.JButton animRestoreButtonForProbe() {
        return animRestoreBtn;
    }

    /** 探针/调试用：颜色预设「恢复为「X」默认值」按钮（0.9.6 / M5）。 */
    public javax.swing.JButton colorRestoreButtonForProbe() {
        return colorRestoreBtn;
    }

    /** 探针/调试用：拖尾渲染模式下拉（M2 / U24）。 */
    public com.intellij.openapi.ui.ComboBox<String> trailModeComboForProbe() {
        return trailModeCombo;
    }

    public com.intellij.openapi.ui.ComboBox<String> colorPresetComboForProbe() {
        return colorPresetCombo;
    }

    /** 探针/调试用：两条动态耦合提示。 */
    public JLabel[] couplingHintsForProbe() {
        return new JLabel[]{singleMoveHint, crossMoveHint};
    }

    public ColorPanel tailColorPanelForProbe() {
        return tailColorPanel;
    }

    public ColorPanel shadowColorPanelForProbe() {
        return shadowColorPanel;
    }

    /** 探针/调试用：强制重算预设显示（不经过下拉事件）。 */
    public void refreshPresetDisplayForProbe() {
        refreshPresetDisplay();
    }

    /**
     * 探针/调试用：直接驱动 ColorPanel 的变更回调体（t7 / R-02 的第 4 处入口）。
     *
     * <p>为什么需要它：{@code ColorPanel.setSelectedColor(...)} <b>不派发</b> ActionEvent，
     * 只有用户在弹出选择器里确认时才派发。探针要覆盖这条路径，
     * 就等价调用同一个回调体 —— 它驱动的正是生产代码里 {@code addActionListener} 指向的方法。
     */
    public void onColorPanelChangedForProbe() {
        onColorPanelChanged();
    }

    /** 探针/调试用：模拟用户在下拉里选了某个动画预设（下标 = 预设下标）。 */
    public void pickAnimPresetForProbe(int index) {
        animPresetCombo.setSelectedIndex(index);
    }

    /**
     * 探针/调试用：按<b>名字</b>选中预设。
     *
     * <p>比按下标可靠：下拉 = 7 个内置 + N 个自定义，自定义项的<b>顺序会随派生/删除变化</b>。
     * 探针按下标算位置时，一旦中途多派生了一个副本就会选错项
     * （M5-13 曾因此读到 10 而不是 3）。
     *
     * @return true = 找到并已选中；false = 没有这个名字
     */
    public boolean pickAnimPresetByNameForProbe(String name) {
        for (int i = 0; i < animPresetCombo.getItemCount(); i++) {
            if (name.equals(animPresetCombo.getItemAt(i))) {
                animPresetCombo.setSelectedIndex(i);
                return true;
            }
        }
        return false;
    }

    /** 探针/调试用：模拟用户在下拉里选了某个颜色预设。 */
    public void pickColorPresetForProbe(int index) {
        colorPresetCombo.setSelectedIndex(index);
    }
}
