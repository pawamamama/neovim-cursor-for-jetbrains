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
            "常驻按 8ms 刷新，光标跟随最跟手，但会一直占 CPU。"
            + "机器卡或笔记本省电时可以关掉 —— 代价是停手后第一次操作会慢一拍。";

    private static final String W_HIDE_NATIVE =
            "勾选后只显示拖尾光标。<b>注意</b>：它改的是 IDE 配色方案且会落盘，"
            + "卸载插件后原生光标可能仍是隐形的 —— 届时从本页取消勾选即可恢复。";

    private static final String W_COUPLING =
            "提示：勾选「隐藏原生光标」时建议同时开「常驻刷新」，否则跟随会有延迟。";

    /**
     * 目标帧率的说明。
     *
     * <p><b>为什么只有 125 / 144 两档</b>：实测（docs/PERFORMANCE-FPS.md）：
     * <pre>
     *   125fps (8.00ms) → 实测 124.5fps   精准
     *   144fps (6.94ms) → 实测 143.5fps   精准
     *   165fps (6.06ms) → 实测 129.9fps   达不到
     *   240fps (4.17ms) → 实测  91.7fps   达不到
     * </pre>
     * 165 以上目标间隔小于 sleep 抖动，反而更不准。
     * 给做不到的数字让用户选，是骗人。
     */
    private static final String W_TARGET_FPS =
            "只在开启「常驻刷新」时生效。<b>125</b> 是稳定档；"
            + "<b>144</b> 适合 144Hz 以上显示器，实测能精准达成。"
            + "<br/>再往上（165/240）受系统睡眠精度限制，实测反而更不准，所以不提供。";

    /**
     * 「斜度」的说明。
     *
     * <p>实测各档位（2×32 光标，单行右移一字符，tools/bench/BenchSlopeRange）：
     * <pre>
     *   0%   → 斜度 0.00px  0.0°  纯矩形（四角等速）
     *   25%  → 斜度 0.58px  1.0°  轻微
     *   50%  → 斜度 1.35px  2.4°  明显
     *   75%  → 斜度 3.03px  5.4°  很斜
     *   100% → 斜度 8.87px 15.5°  极端
     *   跟随 → 由「拉伸强度」决定（默认 k=4.5 时 4.06px / 7.2°）
     * </pre>
     */
    private static final String W_SLOPE =
            "单行打字时四边形<b>倾斜多少</b>。0% 是四角等速（纯矩形），越大越斜。"
            + "<br/><b>82% 是上游原版的值</b>（四角系数 1.00/0.72/0.42/0.22），三个内置预设都用它。"
            + "<br/><b>本项随预设变化</b>：改了它会自动生成副本，切预设会回到该预设的值。"
            + "<br/>注意「输入平滑」会把这个倾斜压平：平滑开大时把斜度也调高，净效果可能不明显。";

    /** 目标帧率下拉项（List 而不是数组 —— row.comboBox 要求 Collection） */
    private static final java.util.List<String> FPS_ITEMS =
            Arrays.asList("125 fps（推荐）", "144 fps");

    private static final String W_GLOW_LAYERS =
            "越多越平滑，但每层都要 CPU 绘制，机器慢时建议调低。层数不影响亮度。";

    /**
     * 辉光锐度/外扩的说明（0.9.6 / M6：显示层从「外扩幅度」改成「锐度」，内部值不变）。
     *
     * <p>原文案「0.6 是原版 JS 的夸张光雾（约 40px）」保留其事实，但主语换成锐度 ——
     * 否则用户看到一个叫「锐度」的滑块配一段讲 0.6 的文案，会以为滑块坏了。
     */
    private static final String W_SHADOW_BLUR =
            "数值越小光晕越散（像雾），越大越收紧（贴近光标边缘）。"
            + "<b>调到最散会明显增加重绘开销</b>，慢机器可能掉帧。默认 73 比较适中。";

    /**
     * 拉伸强度的说明。
     *
     * <p><b>0.9.72 补两点用户实际踩到的事实</b>：
     * <ol>
     *   <li>它<b>只作用于单行</b>：跨行分支（{@code TrailCorner.jump} 的 else 分支）
     *       完全不读 {@code trailFactors}，所以调它换行时毫无反应。原文只说了
     *       「跨行缎带由拖尾密度控制」，没点明「调本项对跨行无效」。</li>
     *   <li>它会被「输入平滑」抵消：实测 k 从 4.5 拉到 8.0，最短角只从 0.38 变到 0.26，
     *       而 {@code round2} 取整 + 平滑把梯度朝 1.0 压，净效果很小。
     *       用户「拉满了怎么没变化」的疑问就来自这里。</li>
     * </ol>
     */
    private static final String W_STRETCH =
            "<b>只影响单行打字/退格</b>：越大后缘越慢、拉得越长。"
            + "<br/><b>调它对换行没有影响</b> —— 跨行缎带由「拖尾密度」和「跨行动画时长」决定。"
            + "<br/>另外它会被「输入平滑」抵消：平滑把四角速度差压平后，拉伸强度的效果会变弱。"
            + "两者都调高时，观感可能反而更「平」。";

    private static final String W_FADE_OFF =
            "淡出关闭时这两个值不生效，辉光会瞬间消失。";

    /** 契约 §9.4：跨行动画时长的 comment，逐字。 */
    private static final String W_ANIM_LEN =
            "换行时拖尾持续多久。太长会看到「上一个位置还在拖」，建议 0.1~0.2s。";

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
            "打字/退格时拖尾持续多久。<b>低于 0.04s 会看不出拖尾</b>（太快了），建议 0.08~0.15s。";

    /** 契约 §7.3 第三行：动画时序组底部，恒显示，逐字。 */
    private static final String W_ANIM_COUPLING =
            "这几项只影响时长，拉伸倍率见上方「缎带拉伸强度」。";

    /**
     * 「拖尾密度」（trailSize）的说明 —— 0.9.72 新增。
     *
     * <p><b>为什么必须解释</b>：这个参数的效果极强但完全没有文案。
     * 它在跨行分支里决定 {@code leading = animationLength × (1 - trailSize)}：
     * <pre>
     *   trailSize = 1.0 → leading = 0 → 前缘两角【瞬时吸附】，只有后缘拖出缎带
     *   trailSize = 0.5 → leading = 半个 animationLength → 前缘也开始拖
     *   trailSize = 0.0 → leading = 满 animationLength → 前后缘等速，缎带消失
     * </pre>
     * 用户把滑块推到 1.0 会看到「前缘瞬间到位、后缘拉很长」这种不规则四边形 ——
     * 那正是缎带效果的核心，但没有文案的话会被当成 bug（「怎么一边是硬的」）。
     */
    private static final String W_TRAIL_SIZE =
            "<b>控制跨行缎带的两端差异</b>。"
            + "1.0 = 前缘在<b>第一帧就到新位置</b>、只有后缘慢慢拖出去（最像缎带）；"
            + "调小则前缘也开始拖，0 = 前后缘等速、缎带消失。"
            + "<br/>所以本项只决定跨行；行内打字由「拉伸强度」决定。"
            + "<br/>注：前缘最快也要 1 帧（约 8ms），不可能真的 0 —— 那是绘制帧率的下限。";

    /** 「跨行动画时长」补充说明：它同时是 trailSize 的基准。 */
    private static final String W_ANIM_LEN_EXTRA =
            "<b>它和「拖尾密度」一起决定缎带长度</b>：后缘 = 本值，"
            + "前缘 = 本值 ×（1 − 拖尾密度）。所以拖尾密度为 1.0 时前缘是 0（瞬间到位）。";

    /**
     * 动画时序：「输入平滑」的说明。
     *
     * <p><b>0.9.72 修正了两处错误陈述</b>：
     * <ol>
     *   <li>原文写「全局设置，不随预设变化」—— 0.9.67 已把它改回<b>预设域</b>
     *       （三个预设各带 smooth 值）。文案不改就是在骗用户。</li>
     *   <li>漏掉了它与「拉伸强度」<b>互相抵消</b>这个关键事实 —— 见下。</li>
     * </ol>
     */
    private static final String W_TYPING_SMOOTH =
            "把四角速度差压平一点，让打字更连贯。<b>0% 原样，100% 最平</b>。"
            + "<br/><b>它与「拉伸强度」互相抵消</b>：两者都在调四角的快慢差，"
            + "所以把拉伸强度调高、同时又把本项开大，净效果可能几乎看不出来。"
            + "<br/>只影响单行；跨行缎带不受本项影响（由「拖尾密度」和「跨行动画时长」决定）。"
            + "<br/><b>注意：开得越大，单行连续快速输入时拖尾会跟不上。</b>";

    /** 高级（诊断）区：性能诊断开关的说明。该项不持久化。 */
    private static final String W_PAINT_DIAG =
            "跳过绘制只跑计算，用于判断卡顿是「画得慢」还是「调度慢」。重启后自动关闭。";

    // ==================== 下拉项文案（契约 §2.4：不得只显示枚举名）====================

    private static final String MODE_SPRING_TEXT = "弹簧拉伸（Neovide 原版效果）";
    private static final String MODE_AFTERIMAGE_TEXT = "残影轨迹（低帧率更稳）";
    private static final List<String> MODE_ITEMS = Arrays.asList(MODE_SPRING_TEXT, MODE_AFTERIMAGE_TEXT);

    private static final int COMMENT = -1;

    private static final HyperlinkEventAction NO_LINK = HyperlinkEventAction.HTML_HYPERLINK_INSTANCE;

    /** 下拉末项的动态后缀（0.9.6 / M5）：{基准预设名} + 本后缀。 */
    /** 「恢复为默认值」按钮的文案模板。 */
    private static final String RESTORE_TPL = "恢复为「%s」默认值";

    /**
     * 动画预设下拉的末尾占位项：当前值不属于任何预设时显示。
     *
     * <p>它不是可选项 —— {@code onAnimPresetPicked()} 的越界分支会直接 return，
     * 不写任何字段。
     *
     * <p><b>为什么必须有</b>：ComboBox 不接受 {@code -1}（会保留上一次选中项），
     * 于是「用户在基础预设上关掉辉光」时下拉仍写着「基础」，而基础声明的辉光=开 ——
     * 显示层在撒谎（第二轮审查 O-8 实测）。
     */
    private static final String CUSTOM_ANIM_PLACEHOLDER = "自定义（未保存）";

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

    /**
     * 刻度表。<b>数组顺序不再承担任何契约</b> —— 取值一律走 {@link #specOf(String)}，
     * 按 key 查。往中间插入新项不会影响任何已有调用点。
     *
     * <p><b>历史</b>：原来注释写着「顺序 = 面板从上到下的顺序（探针按此下标取滑块）」，
     * 而生产代码用 {@code SPECS[11]} 这种硬编码下标取值。两者叠加的结果是：
     * 插入一项 → 后面全部偏移 → 编译期无提示 → 症状是「拖 A 滑块 B 参数变了」。
     * 0.9.65 改成按 key 查。
     *
     * <p><b>面板视觉顺序</b>由 {@code build*Group} 里各 {@code addSlider(...)} 的
     * 调用顺序决定，与本数组顺序无关。
     */
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

            // 8b. 斜度（0.9.79 拆自 stretchRatioK 的第二个职责）
            //
            // 刻度 -1 = 「沿用拉伸强度」（默认，升级不掉观感）；
            // 0 = 四角等速（纯矩形）；100 = 极端梯度。
            //
            // 拖动时把 -1「点亮」成实际值：用户一旦碰它就是要覆盖 k 的推导。
            new SliderSpec("slopeTicks", 0, 100, 5, "",
                    s -> s.slopeTicks,
                    (s, v) -> s.slopeTicks = v,
                    (c, v) -> v + "%",
                    v -> v + "%"),

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

            // ---- 12. 光标宽度（1–5 px）----
            new SliderSpec("cursorWidthPx", 1, 5, 1, " px",
                    s -> s.cursorWidthPx,
                    (s, v) -> s.cursorWidthPx = v,
                    (c, v) -> v + " px",
                    v -> String.valueOf(v)),

            // ---- 13. 光标高度（30–100 % 行高）----
            new SliderSpec("cursorHeightRatio", 30, 100, 10, " %",
                    s -> Math.round(s.cursorHeightRatio * 100f),
                    (s, v) -> s.cursorHeightRatio = v / 100f,
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
    /** 预览窗（行内输入删除）—— 参数变化时同步配置 */
    private AnimationPreview inlinePreview;

    /** 预览窗（跨行移动） */
    private AnimationPreview crossPreview;

    /** 预览专用配置副本（与 pending 同步，不碰全局 CONFIG） */
    private NeovideConfig previewConfig;

    /**
     * 颜色面板绑定组 —— 三个方向的同步都收在这里。
     *
     * <p><b>为什么需要它</b>：{@code ColorPanel} 不是 DialogPanel 的绑定控件，
     * 平台不追踪。原来每个面板要在 reset/apply/isModified 各写一遍手工同步，
     * 加第三个颜色面板时要再写 3 处，漏一个就静默失效（详见 ColorPanelBinding 的注释）。
     *
     * <p>现在只需在 {@link #initColorBindings()} 里登记一次。
     * 探针 {@code VColorBinding} 断言「登记数 == 面板上的 ColorPanel 数」。
     */
    private final ColorPanelBinding.Group colorBindings = new ColorPanelBinding.Group();

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

    /** 「性能诊断」复选框组件本身。T3-2：它才是勾选状态的真相源（字段会陈旧）。 */
    private JBCheckBox paintDiagCheck;

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
    /**
     * 登记颜色面板绑定 —— 加新颜色面板时只需在这里加一行。
     *
     * <p>用 Supplier 而不是直接传控件：控件在 {@code createPanel()} 里才创建，
     * 而绑定必须在面板创建前就登记好（reset 可能先于 createPanel 被调）。
     */
    private void initColorBindings() {
        if (colorBindings.size() > 0) {
            return;                     // 幂等：只登记一次
        }
        colorBindings.add(new ColorPanelBinding("tailColor",
                () -> tailColorPanel,
                s -> s.tailColor,
                (s, v) -> s.tailColor = v));
        colorBindings.add(new ColorPanelBinding("shadowColor",
                () -> shadowColorPanel,
                s -> s.shadowColor,
                (s, v) -> s.shadowColor = v));
    }

    /** 供探针：已登记的颜色绑定数 */
    public int colorBindingCountForProbe() {
        initColorBindings();
        return colorBindings.size();
    }

    public NeovideCursorConfigurable() {
        super(DISPLAY_NAME, null);
        initColorBindings();
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
    /** 行内预览：懒创建 */
    private javax.swing.JComponent inlinePreview() {
        if (previewConfig == null) {
            previewConfig = new NeovideConfig();
        }
        if (inlinePreview == null) {
            inlinePreview = AnimationPreview.inline(previewConfig);
        }
        return inlinePreview;
    }

    /** 跨行预览：懒创建 */
    private javax.swing.JComponent crossPreview() {
        if (previewConfig == null) {
            previewConfig = new NeovideConfig();
        }
        if (crossPreview == null) {
            crossPreview = AnimationPreview.cross(previewConfig);
        }
        return crossPreview;
    }

    /**
     * 把 pending 的动画参数同步进预览配置 —— 预览窗下一帧就会用新参数。
     *
     * <p><b>只同步影响动画的那几个</b>，不是全部：颜色/辉光走 CaretTrailPanel.refreshStyles()，
     * 由 {@link #refreshPreviews()} 单独处理。
     */
    private void syncPreviewAnimation() {
        if (previewConfig == null) {
            return;
        }
        previewConfig.animationLength = pending.animationLength;
        previewConfig.shortAnimationLength = pending.shortAnimationLength;
        previewConfig.trailSize = pending.trailSize;
        // typingSmoothness / stretchRatioK 在 NeovideCursorSettings 里（不在 NeovideConfig），
        // 它们的作用就是派生 trailFactors —— 这里直接算好写进预览配置。
        previewConfig.trailFactors = NeovideCursorSettings.deriveTrailFactors(
                pending.stretchRatioK, pending.typingSmoothness);
    }

    /**
     * 把光标宽高推给两个预览窗 —— 拖动滑块时立即生效，不需要点「应用」。
     *
     * <p><b>为什么预览可以实时、编辑器不行</b>：预览是面板内部的 Swing 组件，
     * 改它不涉及任何持久化状态；而真实编辑器的光标尺寸是持久级配置，
     * 必须在点「应用 / OK」后才写进 State —— 这是面板的语义边界，不能被预览带偏。
     * 所以「实时」只到这里为止。
     */
    private void syncPreviewCursorSize() {
        if (inlinePreview != null) {
            inlinePreview.setUserCursorSize(pending.cursorWidthPx, pending.cursorHeightRatio);
            // RGB 是持久化配置（只在 State 里，NeovideConfig 没这个字段），
            // 所以必须由面板显式推送 —— 原来只推给了真实编辑器，预览收不到。
            inlinePreview.setRgbGradient(pending.rgbGradient);
        }
        if (crossPreview != null) {
            crossPreview.setUserCursorSize(pending.cursorWidthPx, pending.cursorHeightRatio);
            crossPreview.setRgbGradient(pending.rgbGradient);
        }
    }

    /**
     * 刷新两个预览窗（动画参数 + 光标尺寸 + 颜色样式）。
     *
     * <p><b>0.9.66：加入光标尺寸实时同步</b>。原来预览自己算 cursorW/cursorH
     * （{@code size/16} 与 {@code lineH*0.8}），**完全不看用户在「光标（尺寸）」
     * 组设的值** —— 拖宽度滑块预览毫无反应，那就不叫预览。
     *
     * <p>这里把 pending 里的两个值推给预览；预览内部再按行高比例换算
     * （见 {@code AnimationPreview#applyCursorSize}），避免直接搬物理像素
     * 导致大字号下细得看不见。
     */
    private void refreshPreviews() {
        if (previewConfig == null) {
            return;
        }
        syncPreviewAnimation();
        syncPreviewCursorSize();
        previewConfig.tailColor = pending.tailColor;
        previewConfig.tailOpacity = pending.tailOpacity;
        previewConfig.useShadow = pending.useShadow;
        previewConfig.shadowColor = pending.shadowColor;
        previewConfig.glowLayers = pending.glowLayers;
        previewConfig.glowOpacity = pending.glowOpacity;
        previewConfig.shadowBlurFactor = pending.shadowBlurFactor;
        if (inlinePreview != null) {
            inlinePreview.onConfigChanged();
        }
        if (crossPreview != null) {
            crossPreview.onConfigChanged();
        }
    }

    /**
     * 读取本插件的真实版本号（jar 内 plugin.xml 的 {@code <version>}）。
     *
     * <p><b>为什么用运行时查询而不是写死常量</b>：写死的常量永远会和 jar 脱节 ——
     * 本项目 0.9.10~0.9.23 期间因为没有显示版本，用户和我都多次误判「装的是哪一版」。
     *
     * <p>取不到时返回 {@code "(未知)"}，不影响面板加载。
     */
    private static String pluginVersion() {
        try {
            com.intellij.openapi.extensions.PluginId pid =
                    com.intellij.openapi.extensions.PluginId.getId("com.pawamamama.neovidecursor");
            com.intellij.ide.plugins.IdeaPluginDescriptor desc =
                    com.intellij.ide.plugins.PluginManagerCore.getPlugin(pid);
            if (desc != null && desc.getVersion() != null) {
                return desc.getVersion();
            }
        } catch (Throwable ignored) {
            // 插件还没注册完 / API 不可用：返回占位
        }
        return "(未知)";
    }

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

                // ---------- 0. 顶行：左边版本+提示，右边插件信息 ----------
                buildVersionRow(panel);

                // ---------- 0. 预设区（下拉 / 一键恢复 / 自定义预设动作）----------
                buildPresetRows(panel);

                // ---------- A. 总开关 ----------
                buildMasterGroup(panel);

                // ---------- D. 单行输入输出（只影响行内打字/退格）----------
                buildInlineGroup(panel);

                // ---------- E. 多行缎带（只影响跨行/跳转）----------
                buildCrossGroup(panel);

                // ---------- C. 颜色 ----------
                buildColorGroup(panel);

                // ---------- C3. 光标（尺寸 / 颜色效果）----------
                //
                // 【调用顺序不是随意的：必须在辉光之前】
                //
                // 这两组的顺序在原版里是由「谁先完成 group() 注册」决定的：
                // 原版把光标组的 panel.group(...) 写在了辉光组的 lambda 体内部，
                // 而 DialogPanel 的 group() 是【等回调跑完才把组挂到父容器】的，
                // 于是内层那组先挂、外层辉光后挂 —— 实际渲染顺序是
                //     颜色 → 光标 → 辉光
                // 而不是源码里看起来的「辉光在前」。
                //
                // S3 抽成两个独立方法后，顺序改成按调用顺序走，一度变成
                //     颜色 → 辉光 → 光标
                // 组位置换了，所有控件的遍历索引跟着挪 —— 按索引定位的地方立刻串台
                // （VRegr2 的 R-8 / R-11 一好一坏）。所以这里显式把光标组放在
                // 辉光组【之前】，与 0.9.61 的渲染顺序逐位一致。
                buildCursorGroup(panel);

                // ---------- C2. 辉光 ----------
                buildGlowGroup(panel);

                // ---------- B. 淡出 ----------
                buildFadeGroup(panel);

                // ---------- G. 实验性（默认折叠）----------
                buildExperimentalGroup(panel);

                // ---------- F. 高级（诊断）（默认折叠）----------
                buildDiagGroup(panel);

                return kotlin.Unit.INSTANCE;
            });
        } finally {
            suppressDerive = false;
        }
    }

    // ==================== 分组构建（S3：从 createPanel 逐组抽出）====================
    //
    // 【为什么分组抽成方法】createPanel() 原本是一个 465 行的 lambda，9 个组
    // 平铺在里面。加一个新参数要在 400 多行里找位置、改缩进、补逗号，
    // 每次都要重新读一遍上下文 —— 这是「加功能容易坏」的真正原因，
    // 不是代码量本身。
    //
    // 现在每组一个方法，签名统一为 (Panel panel)。组内顺序、注释、字段赋值
    // 原样保留，只是搬了个位置。新增一组 = 写一个方法 + 在 createPanel 里加一行。
    //
    // 【纪律】抽组必须手工逐个进行，每抽一个编译一次 —— 机械切行会破坏
    // panel.group(..., g -> { 的闭合括号（已经因此回滚过两次）。

    /**
     * 0a. 顶行 —— 左边版本号 + 使用提示，右边插件信息条（GitHub / 检查更新）。
     *
     * <p>【用户要求】「顶行右边有点空 建议这边打个广告 简单介绍一下插件
     * 然后弄个 github 跳转 检查更新」。
     *
     * <p>用 BorderLayout：WEST 放版本说明，EAST 放 PluginInfoBar ——
     * 这样右侧空白被利用起来，且不挤压左侧的说明文字。
     */
    private void buildVersionRow(Panel panel) {
        panel.row("", row -> {
            javax.swing.JPanel bar = new javax.swing.JPanel(new java.awt.BorderLayout(12, 0));
            bar.setOpaque(false);

            javax.swing.JLabel ver = new javax.swing.JLabel(
                    "<html>Neovide Cursor v" + pluginVersion()
                            + " ｜ 下方预览窗实时渲染，"
                            + "拖动滑块/改颜色会立刻变化；"
                            + "作用于编辑器仍需点「应用 / OK」</html>");
            ver.setForeground(new Color(0x8A8A8A));
            ver.setFont(ver.getFont().deriveFont(java.awt.Font.PLAIN, 11f));
            bar.add(ver, java.awt.BorderLayout.WEST);

            bar.add(PluginInfoBar.create(pluginVersion()), java.awt.BorderLayout.EAST);

            row.cell(bar);
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * 0b/0c. 预设区 —— 两个下拉、两个「一键恢复」、三个自定义预设动作。
     *
     * <p>三块合成一个方法：它们操作的是同一组字段（animPresetCombo /
     * animRestoreBtn / animNewBtn …），拆开反而要来回跳。
     */
    private void buildPresetRows(Panel panel) {
        // 0b0. 预设下拉（契约 §9.1 的第一块）
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

        // 0b. 一键恢复：把当前预设已保存的值重新写进 pending。
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

        // 0c. 自定义预设的三个动作（0.9.9）。
        //
        // 【为什么「新建」不是多余的】自动派生只在「改动画字段」时发生；
        // 用户想「把当前整套观感（含颜色/拉伸强度等）存下来」时需要一个显式出口。
        // 「重命名」「删除」只对自定义预设可用 —— 内置预设永久只读，按钮置灰。
        panel.row("", row -> {
            row.bottomGap(BottomGap.SMALL);
            animNewBtn = new javax.swing.JButton("新建预设…");
            animNewBtn.addActionListener(e -> createCustomPreset());
            row.cell(animNewBtn);
            animRenameBtn = new javax.swing.JButton("重命名…");
            animRenameBtn.addActionListener(e -> renameCustomPreset());
            row.cell(animRenameBtn);
            animDeleteBtn = new javax.swing.JButton("删除");
            animDeleteBtn.addActionListener(e -> deleteCustomPreset());
            row.cell(animDeleteBtn);
            // 初值：内置预设选中时改名/删除置灰。之后每次 refreshPresetDisplay 会再刷。
            animRenameBtn.setEnabled(false);
            animDeleteBtn.setEnabled(false);
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * A. 总开关组 —— 四项全局开关 + 耦合提示。
     *
     * <p>全是「影响整个插件行为」的东西，不随动画预设变化。
     */
    private void buildMasterGroup(Panel panel) {
        panel.group("总开关", true, g -> {
            g.row("启用拖尾光标（总开关）", row -> {
                row.topGap(TopGap.MEDIUM);
                bindCheck(row, () -> pending.enabled, v -> pending.enabled = v);
                return kotlin.Unit.INSTANCE;
            });

            g.row("隐藏原生光标（覆盖层充当光标）", row -> {
                bindCheck(row, () -> pending.hideNativeCaret, v -> pending.hideNativeCaret = v)
                        .comment(W_HIDE_NATIVE, COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });

            g.row("常驻刷新（不停手降频）", row -> {
                bindCheck(row, () -> pending.alwaysHighFps, v -> pending.alwaysHighFps = v)
                        .comment(W_ALWAYS_HIGH_FPS, COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });

            // 【0.9.78 新增：目标帧率】
            //
            // 审查发现 targetFps 字段已加进 State / loadState / copyFrom / FIELDS，
            // 也已接进 CaretAnimator.computeNextDelayNs，但**面板里没有控件** ——
            // 用户根本没有入口选 144。字段通到底但 UI 断了，等于白做。
            g.row("目标帧率", row -> {
                ComboBoxKt.bindItem(
                        row.comboBox(FPS_ITEMS, null),
                        new MutableProperty<String>() {
                            @Override public String get() {
                                return pending.targetFps == 144 ? FPS_ITEMS.get(1) : FPS_ITEMS.get(0);
                            }
                            @Override public void set(String v) {
                                pending.targetFps = FPS_ITEMS.get(1).equals(v) ? 144 : 125;
                            }
                        });
                row.rowComment(W_TARGET_FPS, COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });

            // 【多光标不辉光】全局设置，不随预设变化
            g.row("多光标时关闭辉光（只保留拉伸）", row -> {
                bindCheck(row, () -> pending.multiCursorNoGlow,
                        v -> pending.multiCursorNoGlow = v)
                        .comment("<b>全局设置，不随预设变化</b>。"
                                + "光标 ≥ 2 个时把辉光层数强制归零，"
                                + "只保留拖尾拉伸形状 —— 减少多光标时的绘制开销。"
                                + "单光标不受影响。", COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });

            // 契约 §2.2：W_COUPLING 挂在 #3/#4 下方的 rowComment
            g.row("", row -> {
                row.rowComment(W_COUPLING, COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * D. 单行输入输出 —— 预览窗 + 只影响行内打字/退格的三个参数。
     *
     * <p>【分组依据】看预览窗。用户不需要记参数名 ——
     * 想调「打字时的感觉」就来这一组，想调「换行时的缎带」就去下一组。
     * 预览窗和它影响的参数放在同一组，看着窗调，调的就是窗里发生的事。
     */
    private void buildInlineGroup(Panel panel) {
        panel.group("单行输入输出（打字 / 退格）", true, g -> {
            g.row("", row -> {
                row.topGap(TopGap.MEDIUM);
                row.cell(inlinePreview());
                return kotlin.Unit.INSTANCE;
            });
            g.row("短动画时长（打字/退格）", row -> {
                addSlider(row, specOf("shortAnimationLength"), null);
                row.rowComment(W_SHORT_LEN, COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });
            g.row("输入平滑（拖尾形状 · 全局）", row -> {
                addSlider(row, specOf("typingSmoothness"), null);
                row.rowComment(W_TYPING_SMOOTH, COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });
            // 【0.9.38：从「多行缎带」移到此处】
            //
            // 它改的是 trailFactors，而 trailFactors 只在【短跳】分支被读
            // （见 TrailCorner.jump 的 isShortJump 分支），跨行分支用的是
            // trailSize + animationLength。
            // 放在「多行缎带」下会让用户以为它管跨行 —— 调了没反应。
            g.row("拉伸强度（行内后缘）", row -> {
                addSlider(row, specOf("stretchRatioK"), springMode);
                row.rowComment(W_STRETCH, COMMENT, NO_LINK).enabledIf(springMode);
                return kotlin.Unit.INSTANCE;
            });
            // 【0.9.79 新增：斜度】从「拉伸强度」拆出来的第二个职责。
            //
            // 用户想更斜时，原来只能把「拉伸强度」拉到 8 —— 而那个滑块的标签
            // 是「拉伸强度」，用户以为改的是拖尾长度。两个职责挤在一个字段里
            // 正是纪律四-D 说的定时炸弹，这里拆开。
            //
            // 默认「跟随拉伸强度」：不碰它 = 旧行为完全不变。
            g.row("斜度（单行倾斜）", row -> {
                addSlider(row, specOf("slopeTicks"), springMode);
                row.rowComment(W_SLOPE, COMMENT, NO_LINK).enabledIf(springMode);
                return kotlin.Unit.INSTANCE;
            });
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * E. 多行缎带 —— 跨行预览窗 + 只影响换行/跳转的参数。
     *
     * <p>【顺序必须保持 6 → 7 → 8】探针 Probe4Bugs 的 SLIDER_NAMES /
     * SLIDER_TARGET / tickOf() 都按【索引】硬编码。曾为了分组语义把顺序改成
     * 8→7→6，结果 B1-1 报「7/11 跟随」—— 拖动 sliders[6] 时校验的是
     * tickOf(6)=trailSize，而那个滑块实际是 animationLength。
     * 语义上没坏，但索引契约被破坏。恢复原序。
     */
    private void buildCrossGroup(Panel panel) {
        panel.group("多行缎带（换行 / 跳转）", true, g -> {
            g.row("", row -> {
                row.topGap(TopGap.MEDIUM);
                row.cell(crossPreview());
                return kotlin.Unit.INSTANCE;
            });
            // 【0.9.72 文案修正】
            //
            // 原文写「也影响行内，但行内主要由『缎带拉伸强度』决定」—— 这是错的。
            // 核实 TrailCorner.jump()：短跳分支（:209-221）只读 shortAnimationLength
            // 与 trailFactors，**完全不读 trailSize**。trailSize 只出现在跨行分支（:227）。
            // 所以「也影响行内」是没有依据的陈述，会误导用户去调它试行内手感。
            g.row("拖尾密度（跨行缎带）", row -> {
                addSlider(row, specOf("trailSize"), springMode);
                row.rowComment(W_TRAIL_SIZE, COMMENT, NO_LINK).enabledIf(springMode);
                return kotlin.Unit.INSTANCE;
            });
            g.row("跨行动画时长", row -> {
                addSlider(row, specOf("animationLength"), null);
                row.rowComment(W_ANIM_LEN + BR + W_ANIM_LEN_EXTRA, COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });
            // 契约 §7.3 / §7.4：耦合提示保留在这里（它们讲的就是跨行与短跳的关系）
            g.row("", row -> {
                row.rowComment(W_ANIM_COUPLING, COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });
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
    }

    /** C. 颜色 —— 拖尾纯色 + 不透明度。RGB 渐变在「光标」组，两者互斥。 */
    private void buildColorGroup(Panel panel) {
        panel.group("颜色", true, g -> {
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

            g.row("拖尾不透明度", row -> {
                addSlider(row, specOf("tailOpacity"), null);
                return kotlin.Unit.INSTANCE;
            });

            return kotlin.Unit.INSTANCE;
        });
    }

    /** C2. 辉光 —— 围绕光标矩形的光晕。四个参数全部受「启用辉光」联动置灰。 */
    private void buildGlowGroup(Panel panel) {
        panel.group("辉光", true, g -> {
            g.row("启用辉光", row -> {
                row.topGap(TopGap.MEDIUM);
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
                addSlider(row, specOf("glowOpacity"), shadowOn);
                return kotlin.Unit.INSTANCE;
            });

            g.row("辉光层数", row -> {
                addSlider(row, specOf("glowLayers"), shadowOn);
                row.rowComment(W_GLOW_LAYERS, COMMENT, NO_LINK).enabledIf(shadowOn);
                return kotlin.Unit.INSTANCE;
            });

            g.row("辉光锐度（越大越锐，外扩越窄）", row -> {
                addSlider(row, specOf("shadowBlurFactor"), shadowOn);
                row.rowComment(W_SHADOW_BLUR, COMMENT, NO_LINK).enabledIf(shadowOn);
                return kotlin.Unit.INSTANCE;
            });
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * C3. 光标（尺寸 / 颜色效果）—— 只管「光标这个矩形本身」。
     *
     * <p>【职责边界】多大、什么颜色效果。与「辉光」（围绕矩形的光晕）
     * 和「淡出」（时间上的衰减）分开。光标尺寸和辉光没有因果关系，
     * 放在一起会让人以为调宽度会影响辉光 —— 用户为此专门提过。
     *
     * <p>这三项都是<b>持久配置</b>，不随动画预设变化。
     */
    private void buildCursorGroup(Panel panel) {
        panel.group("光标（尺寸 / 颜色效果）", true, cur -> {
            cur.row("光标宽度", row -> {
                // 【0.9.59】0 的语义取消 —— 用户反馈「0 光标不变，实际 2px」，
                // 那个档位看不出区别。改成纯 1-5px。
                addSlider(row, specOf("cursorWidthPx"), null);
                row.rowComment("<b>持久配置，全局生效</b>。1~5 像素；"
                        + "5 = IDEA 粗光标宽度（上限）。"
                        + "<b>静止时也保持这个宽度。</b>", COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });

            cur.row("RGB 渐变（实验性）", row -> {
                bindCheck(row, () -> pending.rgbGradient,
                        v -> pending.rgbGradient = v)
                        .comment("<b>实验性</b>。拖尾纵向颜色渐变"
                                + "（上端=拖尾颜色，下端=蓝紫）。"
                                + "2px 宽也能看出上下色差。<br/>"
                                + "<b>与「颜色」组的纯色填充互斥</b>："
                                + "开启本项时纯色被覆盖，关闭后自动恢复。"
                                + "默认关。", COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });

            cur.row("光标高度（% 行高）", row -> {
                addSlider(row, specOf("cursorHeightRatio"), null);
                row.rowComment("<b>持久配置，全局生效</b>。"
                        + "100% = 顶格（占满行高）；30% = 只占三成（居中）。"
                        + "<b>上限就是顶格，不能超过。</b>", COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });
            return kotlin.Unit.INSTANCE;
        });
    }

    /** B. 淡出 —— 辉光停止后柔和熄灭。两项时长受「辉光淡出」联动置灰。 */
    private void buildFadeGroup(Panel panel) {
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
                addSlider(row, specOf("cursorDisappearDelay"), fadeOn);
                row.rowComment(W_FADE_OFF, COMMENT, NO_LINK).enabledIf(fadeOn);
                return kotlin.Unit.INSTANCE;
            });

            g.row("淡出时长", row -> {
                addSlider(row, specOf("cursorFadeOutDuration"), fadeOn);
                return kotlin.Unit.INSTANCE;
            });
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * G. 实验性（默认折叠）—— 目前只有「拖尾渲染模式」一项。
     *
     * <p>残影模式从「总开关组」拆到这里，并明确标注「官方不建议使用」。
     * 理由：AfterimageTrail 是单条历史轨迹模型，多光标只跟主光标；
     * 且掉帧时残影稀疏、观感与弹簧模式差异大，容易让人误以为是 bug。
     */
    private void buildExperimentalGroup(Panel panel) {
        panel.collapsibleGroup("实验性（官方不建议使用）", false, g -> {
            g.row("拖尾渲染模式", row -> {
                Cell<com.intellij.openapi.ui.ComboBox<String>> cell = ComboBoxKt.bindItem(
                        row.comboBox(MODE_ITEMS, null),
                        new MutableProperty<String>() {
                            @Override public String get() { return modeText(pending.trailMode); }
                            @Override public void set(String v) { pending.trailMode = textToMode(v); }
                        });
                com.intellij.openapi.ui.ComboBox<String> box = cell.getComponent();
                trailModeCombo = box;
                box.addActionListener(e -> springMode.set(isSpringText(box.getSelectedItem())));
                return kotlin.Unit.INSTANCE;
            });
            g.row("", row -> {
                row.rowComment("⚠ 残影模式（AFTERIMAGE）为实验性功能，<b>官方不建议日常使用</b>："
                        + "它记录真实历史位置，掉帧时残影会变稀疏；"
                        + "且当前<b>只跟随主光标</b>，多光标下只有主光标有残影。"
                        + "除非你在低帧率环境明确需要它，否则请保持「弹簧（拉伸）」模式。",
                        COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * F. 高级（诊断）（默认折叠）。
     *
     * <p>从 Tools 菜单挪进来（0.9.6 / M1）：菜单只留 3 项，诊断属于「偶尔用一次」
     * 的高级项。注意「性能诊断」<b>不持久化</b> —— 所以也不参与 isModified()：
     * 勾选后点「应用」才生效，重启即失效。「诊断日志」则写进 State，会持久化。
     */
    private void buildDiagGroup(Panel panel) {
        panel.collapsibleGroup("高级（诊断）", false, g -> {
            g.row("性能诊断（跳过重绘，只跑计算）", row -> {
                row.topGap(TopGap.MEDIUM);
                // 【T3-2：本轮新引入的回归，medium】
                // 原来 getter 读字段 paintDiag，而该字段只在 apply() 里由
                // super.apply() 更新。本轮给 syncAllControlsFromPending() 补了
                // super.reset() → 它用【陈旧的字段值】覆盖控件，于是
                // 「勾选性能诊断 → 换预设」会静默把勾选取消（父提交 c03ea7f^ 不会）。
                //
                // 修法：让控件自己成为真相 —— getter 之后直接读组件状态，
                // 字段只在 apply() 那一瞬同步给 NeovideCaretManager。
                Cell<JBCheckBox> diagCell = bindCheck(row, () -> paintDiag, v -> paintDiag = v);
                paintDiagCheck = diagCell.getComponent();
                paintDiagCheck.addItemListener(e ->
                        paintDiag = paintDiagCheck.isSelected());
                diagCell.comment(W_PAINT_DIAG, COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });

            // 诊断日志开关 —— 必须【独立一行】。
            //
            // 曾经把它塞进上面「性能诊断」那一行，结果两行 label 不匹配：
            // 行标题写着「性能诊断（跳过重绘，只跑计算）」，下面却有两个复选框，
            // 用户看到「诊断那里怎么两个框框」。
            g.row("诊断日志（记录光标状态，供开发者排查）", row -> {
                row.bottomGap(BottomGap.SMALL);
                bindCheck(row, () -> pending.diagLogging, v -> pending.diagLogging = v)
                        .comment("勾选后每 15 帧往 idea.log 写一条光标状态（caret / block / draw）。"
                                        + "复现问题后把 idea.log 发给开发者；长期开着会撑大日志。",
                                COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });
            return kotlin.Unit.INSTANCE;
        });
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
    private Cell<JSlider> addSlider(Row row, SliderSpec spec, GraphProperty<Boolean> enabledIf) {
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

        // 【按 SPECS 索引插入，而不是简单 append】
        //
        // 视觉顺序（按职责分组）与索引顺序（探针契约）是两件事：
        //   · 视觉：单行组在前（9,10），多行组在后（6,7,8）
        //   · 索引：0..5, 6, 7, 8, 9, 10
        //
        // 探针 Probe4Bugs / VerifyPanel 的 SLIDER_NAMES / SLIDER_TARGET /
        // tickOf(index) 全按索引硬编码，slidersForProbe() 也按 bindings 顺序返回。
        // 若直接 append，索引就错位了 —— 表现为「拖动滑块 6，校验的是 tickOf(6)」。
        int specIndex = indexOfSpec(spec);
        int insertAt = bindings.size();
        for (int i = 0; i < bindings.size(); i++) {
            if (indexOfSpec(bindings.get(i).spec) > specIndex) {
                insertAt = i;
                break;
            }
        }
        bindings.add(insertAt, b);
        return cell;
    }

    /**
     * 按 <b>key</b> 取刻度表里的一项 —— 取代 `SPECS[数字]` 这种硬编码下标。
     *
     * <p><b>为什么必须去掉数字下标</b>：原来写的是 {@code SPECS[11]} / {@code SPECS[12]}
     * 这类硬编码下标，一旦有人在数组中间插入一项，它后面所有下标全部偏移，
     * 而<b>编译期毫无提示</b>。
     * 症状会是「拖 A 滑块，B 参数变了」这种极难定位的错。
     *
     * <p><b>为什么用 key 而不是常量（如 SPEC_CURSOR_WIDTH = 11）</b>：
     * 常量仍然要人工数下标，插项时照样得改；key 是字符串，与数组位置完全解耦。
     *
     * <p><b>写错 key 会怎样</b>：这里直接抛 {@link IllegalStateException}，
     * 面板构建时立刻炸 —— 而不是静默返回一个错误刻度。<b>宁可启动失败，也不要静默错位。</b>
     * 另外 {@code VAddSetting} 的 A-1/A-2 会在门禁里先抓到这类拼写问题。
     */
    private static SliderSpec specOf(String key) {
        for (SliderSpec s : SPECS) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        throw new IllegalStateException(
                "SPECS 里没有 key=\"" + key + "\" 的刻度项 —— 检查拼写，或确认它已加入 SPECS 数组");
    }

    /** SPECS 数组里某 spec 的下标；找不到返回 Integer.MAX_VALUE（排最后） */
    private static int indexOfSpec(SliderSpec spec) {
        for (int i = 0; i < SPECS.length; i++) {
            if (SPECS[i] == spec) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
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
            // ---- ① 复选框 / 下拉 / 联动属性 ----
            //
            // 【2026-10-22 团队审查的 F1/F4 —— 这是三个已报 bug 的共同根因】
            // 本方法原来只回灌「滑块 + ColorPanel + 提示」，漏掉了：
            //   · 6 个复选框（enabled / hideNativeCaret / alwaysHighFps /
            //     overlayFadeOut / useShadow / 性能诊断）
            //   · trailModeCombo 下拉
            //   · 3 个 GraphProperty（springMode / shadowOn / fadeOn）→ 控件置灰状态
            //
            // 后果：切到自定义预设后，那 6 个字段的【控件】仍是旧值，
            // apply() 时被陈旧控件值反冲回去 → 19 字段快照实际只存住 13 个。
            // 而且辉光/淡出的复选框不跟着变 → 用户看到「恢复预设没用」「切换预设辉光不回来」。
            //
            // super.reset() 走 DialogPanel 自己的回调表（全部绑定控件，含复选框与下拉），
            // 这是平台提供的完整回灌路径 —— 自己再手写一份必然漏。
            super.reset();
            // GraphProperty 不在 DialogPanel 的绑定表里，必须单独刷
            graphReset(pending);

            // ---- ② 非绑定控件：滑块三件套 + ColorPanel ----
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
            // 颜色面板：State → 控件（三个方向的同步统一走 ColorPanelBinding）
            colorBindings.pullAll(pending);

            // ---- ③ 派生显示 ----
            refreshDerivedHints();
            refreshPresetDisplay();
        } finally {
            syncingControls = false;
            suppressDerive = prevSuppress;      // ← 同一个 finally 里恢复，异常时也不会漏
            // reset() / 切预设 / apply() 之后也要刷预览 ——
            // syncingControls 已复位，refreshPreviews 里改控件不会造成回环。
            refreshPreviews();
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
            // 内置预设：写【全部】预设字段（含辉光/淡出开关）
            // 【为什么不再是「只写 6 个动画字段」】那套正交性判据成立于「预设只描述动画」，
            // 但用户要的是「预设 = 一整套观感」，且「纯平滑输入」必须能关辉光。
            // 颜色两个 HEX 仍然不碰（颜色预设独立，这是真正需要正交的部分）。
            applyAnimPresetFields(pending, CursorPresets.ANIM_PRESETS[idx]);
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
            // 【BLOCKER 修复：必须用 restoreSkipping，不能用 restore】
            //
            // 自定义预设快照存的是全部字段，其中包含 4 个「运行环境开关」：
            //   enabled / hideNativeCaret / trailMode / alwaysHighFps
            //
            // 直接 restore 的后果（审查实测确认）：
            //   · 快照里 enabled=false → 选中该预设并应用 → **整个插件被关掉**
            //   · hideNativeCaret 被改写 → 触发 IDEA 配色方案 CARET_COLOR 的
            //     不可逆落盘副作用
            //
            // 而 Tools 菜单那条路径（CursorPresetApplier）早已用 restoreSkipping 修过。
            // 两条路径语义必须一致，否则「菜单切预设安全、面板切预设危险」。
            // 跳过清单统一放在 SettingsField.RUNTIME_SWITCH_KEYS，避免再次漂移。
            SettingsField.restoreSkipping(pending, e.snapshot,
                    SettingsField.RUNTIME_SWITCH_KEYS);
            pending.animCustomName = e.name;
        }
        syncAllControlsFromPending();
    }

    /**
     * 颜色预设被选中。
     *
     * <p><b>与动画预设正交</b>：不碰任何时长 / 拉伸 / 辉光字段（硬判据）。
     *
     * <p><b>0.9.75 新增 rgbGradient</b>：颜色预设现在还能表达「用不用色相循环」。
     * 选「彩虹（动态）」→ {@code rgbGradient = true}（走色相循环，纯色填充被覆盖）；
     * 选其余任何一项 → {@code rgbGradient = false}（回到纯色）。
     *
     * <p><b>为什么必须双向写、不能只在彩虹时置 true</b>：
     * 用户从「彩虹」切回「冰蓝」时，如果只写两个 HEX 而不关 rgbGradient，
     * 绘制层仍走色相循环 —— 下拉显示「冰蓝」但光标是彩虹，**显示层撒谎**。
     */
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
        // 色相循环开关随预设双向同步（见方法注释）
        pending.rgbGradient = p.rgbWash();
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
        // 颜色面板：控件 → State（统一走 ColorPanelBinding）
        colorBindings.pushAll(pending);
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
        // 末尾占位项：当前值不属于任何预设时显示它。
        // 【为什么需要】ComboBox 不接受 -1；不加占位项就会出现「关掉辉光后下拉仍写着基础」
        // 这种显示层撒谎（第二轮审查 O-8 实测）。点它不写任何字段（见 onAnimPresetPicked 的越界分支）。
        names.add(CUSTOM_ANIM_PLACEHOLDER);

        syncingControls = true;
        try {
            replaceModel(animPresetCombo, animPresetItems, names);
            rebuildColorItems(colorPresetCombo, colorPresetItems);
            // 当前项：自定义优先（animCustomName 非空就是它），否则内置基准。
            //
            // 【第二轮审查 O-8/O-14/O-15：下拉不能"自称"它是某个预设】
            // 原来无条件用 animBaseIdx。于是用户在「基础」上手动关掉辉光后，
            // 下拉仍显示「基础」—— 而这个预设声明辉光=开。
            // 用户看到的是「已选中基础」，实际观感已经不是它了（显示层撒谎）。
            //
            // 正确判据：**当前值必须真的等于该预设的全部 8 个字段**，否则视为"自定义"。
            // 与 matchAnimPreset 同源（它也刚补上 useShadow/overlayFadeOut）。
            int sel;
            if (selectedCustom >= 0) {
                sel = CursorPresets.ANIM_PRESETS.length + selectedCustom;
            } else {
                CursorPresets.AnimPreset matched = CursorPresetApplier.matchAnimPreset(pending);
                // 无匹配 = 「自定义」状态。ComboBox 不接受 -1（会保留上一次选中项，
                // 于是下拉继续"自称"某个预设 —— O-8 实测过）。
                // 这里显式加一个末尾占位项「自定义（未保存）」，与自定义预设区分：
                // 它不是可选项（用户点到它不会写任何字段），只是一个显示态。
                // 0.9.38：无匹配时清空选中（-1），不再用「自定义（未保存）」假项。
                sel = matched != null ? indexOfAnim(matched) : -1;
            }
            // -1 = 无选中，ComboBox 渲染为空白 —— 比一个点不动的假项诚实
            animPresetCombo.setSelectedIndex(sel);
            colorPresetCombo.setSelectedIndex(c == null ? -1 : indexOfColor(c));
            refreshRestoreButtonText();
            refreshCustomButtons();
        } finally {
            syncingControls = false;
        }
    }

    /**
     * 三个自定义预设动作按钮的可用性。
     *
     * <p><b>为什么需要它</b>：内置预设<b>永久只读</b>，所以「重命名」「删除」必须置灰 ——
     * 否则用户点了会弹「内置预设不能改名」，那是在用报错代替禁用。
     * 「新建」在任何状态下都可用（把当前这套存下来是合法操作）。
     *
     * <p><b>这一条曾被漏掉</b>：VCustom 的 C-30 抓到「三个按钮全亮」，
     * 根因是 refreshCustomButtons() 只写了调用点、没写方法体。
     */
    private void refreshCustomButtons() {
        boolean onCustom = !pending.animCustomName.isEmpty();
        if (animNewBtn != null) {
            animNewBtn.setEnabled(CustomPresets.canAdd(pending.customAnimPresets));
        }
        if (animRenameBtn != null) {
            animRenameBtn.setEnabled(onCustom);
        }
        if (animDeleteBtn != null) {
            animDeleteBtn.setEnabled(onCustom);
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
        // 【实时预览：必须在这两个早退之前】
        // 后面 A/B 两条分支各自都有 return（预设同步 / 偏离判定），
        // 若把刷新放在末尾会被跳过 —— 表现是「拖滑块预览不动」。
        refreshPreviews();
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
        // 【第二轮审查 F4，medium：偏离判据必须与预设字段域一致】
        // 原来只比 6 个动画值 → 用户在内置预设上【关掉辉光】时判为「没偏离」，
        // 于是不派生落脚点：滑块改动会派生副本，复选框改动不会 —— 同一类操作两种行为。
        // 现在把 useShadow / overlayFadeOut 也纳入判据（与 matchAnimPreset 同源）。
        // 【第三轮审查 ③，high：判据必须覆盖预设的全部字段】
        // 原来只比 8 项，漏了第 9 个 typingSmoothness —— 实测后果：
        //   改 useShadow 会派生副本、改 typingSmoothness【不会】
        //   → 下拉无自定义项、matchAnimPreset 返回 null，两头落空，改动无处可存。
        // 更根本的做法是直接复用 matchAnimPreset（它就是"当前值属于哪个预设"的唯一判据），
        // 这样以后加字段再也不会漏。
        if (CursorPresetApplier.matchAnimPreset(pending) == CursorPresets.ANIM_PRESETS[base]) {
            return;                              // 与基准预设完全一致，不派生
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
     * <p><b>0.9.68：语义按用户要求统一成「回到默认值」</b>。
     *
     * <p>用户原话：「恢复为默认值 没有这个逻辑这么难吗 —— 直接从哪个预设拷贝的覆盖一下呗」。
     *
     * <p>旧行为（0.9.9~0.9.67）在自定义副本上是错的：它 restore 副本<b>自己存的快照</b>，
     * 而按钮文案写的是「恢复为「基础 副本」默认值」—— 用户以为会回到「基础」的默认，
     * 实际拿到的是副本当前存的值。**用户改了副本想反悔，点这个按钮毫无作用**，
     * 因为它只是把自己又拷了一遍。
     *
     * <p>新行为：
     * <ul>
     *   <li><b>内置预设</b> → 写该预设的常量（原行为，正确）</li>
     *   <li><b>自定义副本</b> → 回到它<b>派生自的那个内置预设</b>
     *       （{@code pending.animPresetBase} 记着），并清掉自定义名 ——
     *       也就是「放弃这份副本的改动，回到你最初拷贝的那一版」</li>
     * </ul>
     *
     * <p>这正是用户说的「直接从哪个预设拷贝的覆盖一下」。
     */
    private void restoreAnimPreset() {
        // 【必须在抑制档里做】syncAllControlsFromPending() 会走到
        // refreshPresetDisplay() → afterPendingEdited()，而后者会把快照
        // 【重写成当前值】。不抑制的话，刚恢复出来的值立刻被覆写回去 —— M5-5 抓到过。
        boolean prev = suppressDerive;
        suppressDerive = true;
        try {
            // ==================== 0.9.68 核心修改 ====================
            //
            // 原来这里有一段「在自定义副本上就 restore 它自己的快照」的分支，
            // 那是**空操作**（把自己拷给自己），用户点了看不出任何变化。
            // 现在统一走下面的「回到 animPresetBase 指向的内置预设」：
            //   · 内置预设上：animPresetBase == 当前选中项，行为与原来一致
            //   · 自定义副本上：animPresetBase 是它派生自的那一版，正是用户要的
            //
            // 副本名字在这里一并清掉 —— 因为值已经回到内置预设了，
            // 再挂着副本名会让下拉显示与实际值不符（显示层撒谎）。
            // 【用 pending.animPresetBase 而不是 animBaseIdx】
            //
            // 两者在内置预设上是同一个值，但**数据来源不同**：
            //   animBaseIdx      面板打开时从 State 读出的快照，之后只在
            //                    「用户在内置预设下拉里选了一项」时被改写；
            //   animPresetBase   持久化字段，派生自定义副本时会被写成
            //                    「副本派生自哪个内置预设」（见 afterPendingEdited / 派生逻辑）。
            // 所以在自定义副本上，animPresetBase 才是权威的「我拷贝自谁」。
            int base = CursorPresets.clampAnimPresetIndex(pending.animPresetBase);
            // 写【全部】预设字段（含 0.9.67 补入的 tailOpacity / 淡出两项 / smooth）
            applyAnimPresetFields(pending, CursorPresets.ANIM_PRESETS[base]);
            pending.animPresetBase = base;
            animBaseIdx = base;
            // 值已回到内置预设 —— 副本名必须清掉，否则下拉显示「基础 副本」
            // 而实际值是「基础」的，显示层就撒谎了。
            pending.animCustomName = "";
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
        // 【0.9.75】与 onColorPresetPicked 一致：色相循环开关随预设双向同步，
        // 否则从「彩虹」点恢复回「冰蓝」时光标仍是彩虹。
        pending.rgbGradient = p.rgbWash();
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
        // 【第二轮审查 F3：非法名必须给用户可显示的提示】
        // 之前 put 拒绝非法名时静默返回原串，面板无条件写 animCustomName，
        // 随后被 afterPendingEdited 清空 —— 用户点了「新建」什么都不发生、
        // 没有提示、应用按钮也不亮。nameError() 当时是死 API（零调用点）。
        String err = CustomPresets.nameError(trimmed);
        if (err != null) {
            Messages.showWarningDialog(err + "。", "无法新建");
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
        if (trimmed.equals(from)) {
            return;
        }
        // 【F4：非法名与重名是两种不同的失败，提示不能混用】
        // rename 对两者都返回 null，之前一律弹「已有同名预设」——
        // 但非法名场景其实并不重名，那个提示是在骗用户。
        String err = CustomPresets.nameError(trimmed);
        if (err != null) {
            Messages.showWarningDialog(err + "。", "无法重命名");
            return;
        }
        if (CustomPresets.has(pending.customAnimPresets, trimmed)) {
            Messages.showWarningDialog("已有同名预设：「" + trimmed + "」。请换个名字。", "无法重命名");
            return;
        }
        String next = CustomPresets
                .rename(pending.customAnimPresets, from, trimmed);
        if (next == null) {
            Messages.showWarningDialog("重命名失败，请重试。", "无法重命名");
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

        // 【团队审查 T3-1 的连带：删除后不能立刻复活】
        // apply() 现在会在 super.apply() 之后调 afterPendingEdited()（那是修 T3-1 必需的）。
        // 而 afterPendingEdited() 看到「当前值 != 内置常量 且 animCustomName 为空」就会
        // 派生一个新副本 —— 于是用户刚删掉的预设在 apply 时又冒了出来。
        //
        // 【第三轮审查 F3，medium：一次性标志不够】
        // 最初用 skipDeriveOnce 挡一次，但它在 syncAllControlsFromPending() 里立刻被消耗，
        // 而 apply() 末尾还会再调一次 afterPendingEdited() → 删掉的预设照样复活并被持久化。
        //
        // 更彻底的做法：删除后【吸附回当前基准内置预设】。
        // 语义上也更对 —— 「删掉这个自定义预设」意味着「回到某个确定的状态」，
        // 而不是「留在一个谁都不属于的中间态，等着下次编辑随便派生一个副本」。
        int base = CursorPresets.clampAnimPresetIndex(pending.animPresetBase);
        applyAnimPresetFields(pending, CursorPresets.ANIM_PRESETS[base]);
        pending.animPresetBase = base;
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

    /**
     * 把内置预设的<b>全部字段</b>写进 State。
     *
     * <p><b>为什么不能只写 6 个动画字段（团队审查 F-07 / 用户报的 B1）</b>：
     * 用户在某预设下关掉辉光 → {@code useShadow=false} 被持久化；
     * 之后切到任何别的预设，辉光<b>都不会回来</b>
     * （用户原话：「切换预设不能让辉光回来」「缎带没有扩散了，就是持久化了」）。
     *
     * <p>原实现只调 {@link #writeAnimValues}（6 项），所以恢复/切换预设对
     * {@code useShadow} / {@code overlayFadeOut} 完全无效 —— 也就是「恢复预设没用」。
     *
     * <p>「纯平滑输入」这个预设还<b>必须</b>能表达「关辉光」，所以这不是补丁，
     * 是预设字段域本身就该包含这两个开关。
     */
    public static void applyAnimPresetFields(NeovideCursorSettings.State s, CursorPresets.AnimPreset p) {
        writeAnimValues(s, new float[]{
                p.animationLength(), p.shortAnimationLength(), p.trailSize(),
                p.glowLayers(), p.shadowBlurFactor(), p.glowOpacity()});
        s.useShadow = p.useShadow();
        s.overlayFadeOut = p.overlayFadeOut();
        // 【拉伸强度必须进预设】2026-10-24 实测：只让 smooth 进、k 不进，
        // 则切任何预设 k 都保持用户上次拖的值 → 三个预设手感完全相同。
        s.stretchRatioK = p.stretchRatioK();
        // ==================== 0.9.67 补入的 4 项 ====================
        //
        // 【为什么必须补】用户实测：「拖尾透明度不随预设改变了，这触碰红线了」。
        // 根因不是改坏了，而是这 4 项【从来就不在预设里】—— 从 0.9.5 起
        // AnimPreset 只有 9 个字段，tailOpacity / 淡出两项 / 输入平滑
        // 一直是持久级配置。
        //
        // 这造成了「副本恢复默认值失效」：
        //   副本快照存 24 个字段（SettingsField.PRESET_FIELDS）→ 切到副本全被覆盖
        //   内置预设只写 9 个                                    → 恢复只拉回 9 个
        //   剩下 15 个停在副本值 → 看起来「恢复没生效」。
        //
        // 【输入平滑为何改回预设域】用户 2026-10-23 实测：smooth 拖到 100 后
        // 四角系数被压成 [1, 0.94, 0.87, 0.83]，梯度只剩 1.20×，
        // 切任何预设都是规整矩形 —— 预设等于失效一半。它必须能被预设覆盖。
        // 但默认值仍是 0（关闭），符合用户「其他持久化 比如平滑关闭」的要求。
        s.tailOpacity = p.tailOpacity();
        s.cursorDisappearDelay = p.cursorDisappearDelay();
        s.cursorFadeOutDuration = p.cursorFadeOutDuration();
        // 【0.9.80 斜度进预设域】用户要求「这个参数进预设，改它会生成副本」。
        // 三个内置预设都是 82（= 对齐上游 trailFactors），所以切预设时
        // 斜度会回落到 82 —— 这正是「预设决定斜度」的语义。
        s.slopeTicks = p.slopeTicksValue();

        // 【0.9.73 修正：输入平滑不参与预设】
        //
        // 0.9.67 我误读了用户要求「其他持久化 比如平滑关闭」——
        // 那句话的意思是「平滑是持久化项、默认关闭」，**不是「平滑进预设」**。
        // 用户明确说过多次：「平滑一直不参与预设」。
        //
        // 现在恢复正确行为：本方法<b>不写</b> typingSmoothness。
        // 它是设备/习惯级的持久设置，切任何预设都保持不变。
        //
        // 连带影响：AnimPreset 里的 smoothTicks 与 typingSmoothness() 访问器
        // 保留但不使用（见 CursorPresets 里的说明），因为 VRegr2 / VPreset9
        // 等探针在做「预设字段域」比对时会读它 —— 删掉要改一串探针。
        // **它不再被任何生产代码读写**，这是明确记录的技术债。
        com.github.yyyolo7a79.neovidecursor.ui.CursorDiag.presetApplied(
                p.name(), s.stretchRatioK, s.animationLength, s.shortAnimationLength);
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
            // 双参版（含 typingSmoothness）+ rank2（横向实际用到的档），理由见 stretchText
            float[] f = NeovideCursorSettings
                    .deriveTrailFactors(pending.stretchRatioK, pending.typingSmoothness);
            long trailing = Math.round(pending.shortAnimationLength * 1000f * f[0]);
            long leading = Math.round(pending.shortAnimationLength * 1000f * f[2]);
            // 【0.9.72：把 smooth 对系数的影响写出来】
            //
            // 原来只报「拉伸系数」，而那个系数是 deriveTrailFactors(k, smooth) 的结果 ——
            // 用户把 smooth 调高后再看这个数字会觉得「我没改拉伸强度啊，怎么系数变了」。
            // 现在直接标注当前 smooth 值，让两个参数的关系可见。
            singleMoveHint.setText(String.format(Locale.ROOT,
                    "单行动画时长 = 短动画时长 × 拉伸系数：后缘 %d ms / 横向前缘 %d ms"
                            + "（系数 %.2f×，已含输入平滑 %.0f%% 的压平效果）。",
                    trailing, leading, f[2] > 0f ? f[0] / f[2] : 0f,
                    pending.typingSmoothness * 100f));
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
            // 【0.9.72 修正：原文写「为 1.00 时前缘为 0」，不准确】
            //
            // 实测（VTextFacts 的 T-2）：trailSize=1.0 时前缘是 **1 帧 = 8ms**，不是 0。
            // 原因是 DampedSpring.update() 首句 if (animationLength <= dt) 归零 ——
            // leading=0 时第一帧（dt=8ms）就命中短路，但那已经消耗了一帧。
            // 任何角点至少要跑一帧才会被判为「静止」，所以 0ms 在物理上不存在。
            String leadText = (leading <= 0)
                    ? "第 1 帧即到位（约 8 ms）"
                    : leading + " ms";
            crossMoveHint.setText(String.format(Locale.ROOT,
                    "跨行动画时长：DampedSpring 时间常数 —— 后缘 = %d ms；前缘 = %s"
                            + "（拖尾密度 %.2f，为 1.00 时前缘只剩 1 帧）。"
                            + "<br/>实际拖尾约为该值的 2.6 倍（0.35 s 实测约 920 ms / 115 帧）。",
                    trailing, leadText, pending.trailSize));
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
    /** 探针入口：{@link #stretchText} 的实际输出（验证面板口径与运行期一致）。 */
    public String stretchTextForProbe(float k) {
        return stretchText(k);
    }

    String stretchText(float k) {
        // 【第三轮审查 ⑤，medium：必须用双参版（含 typingSmoothness）】
        // 原来调单参 deriveTrailFactors(k)，而运行期用的是
        // deriveTrailFactors(k, typingSmoothness)。实测（smooth=1.0 时）：
        //   面板显示 前缘 18.2 ms / 7.692×      真实 前缘 116.2 ms / 1.205×
        // 差 6.4 倍 —— 这正是用户"横向拉伸变小了却在面板上看不出来"的直接原因。
        //
        // 而且前缘必须报 rank2（横向打字实际用到的档）：
        // 第三轮 VRank 实测，纯横向/纵向移动时四角 rank 恒为 {0,2}，rank3 不出现。
        // 报 f[3] 会把前缘时长低报约 1.9 倍。
        float[] f = NeovideCursorSettings
                .deriveTrailFactors(k, pending.typingSmoothness);
        long trailingMs = Math.round(pending.shortAnimationLength * 1000f * f[0]);
        long leadingMs = Math.round(pending.shortAnimationLength * 1000f * f[2]);
        float ratio = f[2] > 0f ? f[0] / f[2] : 0f;
        return String.format(Locale.ROOT,
                "%.1f×   后缘 %d ms / 横向前缘 %d ms   (rank0 最拖 → rank2 跟手，横向实际比值 %.2f×)",
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
        // A1：非绑定控件回读（DialogPanel 追踪不到 ColorPanel）—— 统一走 ColorPanelBinding
        colorBindings.pushAll(pending);

        // A2：DialogPanel 把绑定控件的值写进 pending（滑块先、输入框后 —— 见 addSlider 的注释）
        syncingControls = true;
        suppressDerive = true;
        try {
            super.apply();
        } finally {
            syncingControls = false;
            suppressDerive = false;
        }

        // A2.5：复选框 / 下拉的改动也要有落脚点（团队审查 T3-1 / T3-3）。
        //
        // 【为什么必须放在这里，而不是写进 bindCheck 的 setter】
        // 复选框的 setter 只由 DialogPanel 的 applyCallbacks 调用，
        // 而那条路径一定在 syncingControls=true 之内（就是上面这个 try 块）。
        // 所以写在 setter 里的 "if (!syncingControls) afterPendingEdited()" 是**死代码** ——
        // T3-1 用探针在每条 apply 回调瞬间采样，syncingControls 100% 为 true、0 次 false。
        //
        // 放在 super.apply() 之后：pending 已经是"控件的最新值"，
        // 此时派生/更新快照才是对的。滑块路径不受影响（它的 ChangeListener 自己已经调过）。
        afterPendingEdited();

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
        return colorBindings.anyDiffers(cur);
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

    /**
     * 探针：某个滑块在 pending 里的【精确刻度值】（未吸附）。
     *
     * <p>供探针核对「控件显示值 vs 数据真值」时使用 ——
     * 必须走面板自己的 {@code spec.readTick}，否则口径会不一致
     * （例如 shadowBlurFactor 的 readTick 是 sharpnessOf 转换）。
     */
    public int sliderExactTickForProbe(int index) {
        SliderSpec s = bindings.get(index).spec;
        return s.readTick.applyAsInt(pending);
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

    /** 探针/调试用：自定义预设的三个动作按钮（0.9.9）。缺任何一个都说明没挂到界面上。 */
    public javax.swing.JButton animNewButtonForProbe() {
        return animNewBtn;
    }

    public javax.swing.JButton animRenameButtonForProbe() {
        return animRenameBtn;
    }

    public javax.swing.JButton animDeleteButtonForProbe() {
        return animDeleteBtn;
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
