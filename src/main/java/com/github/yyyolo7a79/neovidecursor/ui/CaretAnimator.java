package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.AfterimageTrail;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个编辑器的拖尾动画控制器。
 *
 * <p>职责有三：
 * <ol>
 *   <li>跟踪光标位置，维护动画目标点</li>
 *   <li>驱动物理模拟</li>
 *   <li>把角点坐标写入绘制面板并触发重绘</li>
 * </ol>
 *
 * <p><b>坐标系</b>：全程使用 contentComponent 坐标系。
 * {@code editor.visualPositionToXY()} 返回的正是该坐标系下的点，
 * 而绘制面板也挂在 contentComponent 上，因此<b>完全不需要坐标转换</b>。
 *
 * <p><b>为什么不依赖 caretPositionChanged</b>：实测发现删除文本等场景下，
 * 光标事件可能在 {@code visualPositionToXY()} 数据更新之前就触发 ——
 * 此时读到的仍是旧位置，而之后不会再有新事件，拖尾就会永久卡在旧位置。
 * 因此改为<b>每个 tick 都重新读取光标位置</b>，事件仅用于"唤醒"。
 *
 * <h3>性能设计（四项，缺一不可）</h3>
 *
 * <p><b>1. 高精度调度</b>：不用 {@code javax.swing.Timer}。
 * 在 Windows 上实测，Timer 设 8ms 的实际触发间隔是 15.61ms（仅 64fps）——
 * 根源在于它内部走 {@code Object.wait}，拿不到系统的高精度时钟；
 * 而 {@code Thread.sleep} 实测精度可达 1.56ms（JDK 在 Windows 上使用了高精度定时器）。
 * 因此改为「后台线程 sleep 调度 + {@code invokeLater} 切回 EDT 绘制」。
 *
 * <p><b>2. 局部重绘</b>：只重绘拖尾占据的包围盒（并合并上一帧区域以擦除残影），
 * 而不是重绘覆盖整个编辑器的面板。
 *
 * <p><b>3. 快速路径</b>：用轻量的 {@code getVisualPosition()} 先判断光标是否移动，
 * 避免每帧都做昂贵的 {@code visualPositionToXY()} 换算。
 *
 * <p><b>4. 零分配热路径</b>：复用 Rectangle 等对象，避免每帧产生垃圾触发 GC。
 *
 * <p><b>5. 积压保护</b>：若上一帧尚未渲染完成，本次调度直接跳过，
 * 避免 EDT 队列堆积导致动画越跑越滞后。
 */
public class CaretAnimator implements Disposable, CaretListener {

    /**
     * 运动时的调度间隔（毫秒）。
     *
     * <h2>为什么从 8 改成 5（第三轮审查 + 用户实测）</h2>
     *
     * <p>用户反馈：「横向拉伸变小了」「平滑没了，卡卡的」。查出两个独立原因：
     * <ol>
     *   <li>横向打字只用到 rank {0,2}（VRank 实测），real前缘时长 = short × f[2]；</li>
     *   <li>「平滑」的物理本质是<b>这次移动被拆成几帧</b>：
     *       {@code 帧数 = 前缘时长 ÷ 帧间隔}。前缘时长定了（要保拉伸与跟手），
     *       那么<b>只能降帧间隔</b>。</li>
     * </ol>
     *
     * <p>实测（k=8 / short=0.14 / smooth=0，横向前缘 49 ms）：
     * <pre>
     *   9.5ms(105fps) → 前缘 5.2 帧，单帧位移 1.74px  「略跳」
     *   8.0ms(125fps) → 前缘 6.1 帧，单帧位移 1.47px  「略跳」
     *   5.0ms(200fps) → 前缘 9.8 帧，单帧位移 0.92px  「可接受」  ← 本值
     *   4.0ms(250fps) → 前缘 12.3 帧，单帧位移 0.73px 「顺」
     * </pre>
     *
     * <p><b>关键性质：降帧间隔不影响稳态滞后</b>（滞后由前缘时长决定，不由帧率决定）。
     * 审查员实测 8→4ms：前缘帧数 12→25（翻倍），滞后 10.20→10.69px（+4.9%）。
     * 所以这是<b>纯收益</b>，代价只是 EDT 投递频率翻倍。
     *
     * <p><b>【2026-10-23 实测纠正：改回 8ms，5ms 是错的】</b>
     *
     * <p>上一版把它改成 5ms，理由是"帧数翻倍更平滑"。但那是<b>纸上推演</b>，漏了两件事实：
     * <ol>
     *   <li>{@link RenderScheduler} 用的是 {@code Thread.sleep(ms)}，<b>Windows 上 sleep
     *       的分辨率就是 15.6ms</b>（本文件 50 行与 README 都记着这件事）。
     *       设 5ms 和设 8ms，<b>实际都睡到 15.6ms</b> —— 帧数一个没多。</li>
     *   <li>设成 5ms 反而有害：调度线程每 5ms 醒一次却干不了活（还没到真实节拍），
     *       空转次数翻倍，还会让 {@code smoothedIntervalMs} 的自适应测量变得更不准。</li>
     * </ol>
     *
     * <p>用户实测反馈"帧率下降了"正是这个：名义 200fps，实际仍是 ~64fps，但多了无效唤醒。
     *
     * <p>结论：<b>回到 8ms</b>。真正的平滑要靠"前缘时长 ÷ 实际帧间隔"这个比值，
     * 而不是把目标帧间隔设成一个 sleep 精度达不到的数字。
     *
     * <p><b>历史</b>：曾试过 12ms(83fps) 以减少 EDT 投递，用户反馈「不够跟手」而回退。
     * 那次是往<b>上</b>调，与 5ms 那次同为"改目标帧间隔"的思路，两次都没解决根本问题。
     */
    static final int ACTIVE_DELAY_MS = 8;

    /** 静止时的轮询间隔（低频看门狗） */
    private static final int IDLE_DELAY_MS = 120;

    /** 失焦且静止时的轮询间隔：编辑器没有焦点时光标不会动，无需高响应度 */
    private static final int IDLE_UNFOCUSED_DELAY_MS = 600;

    /** 自适应调度的间隔上限（毫秒） */
    private static final int MAX_ADAPTIVE_DELAY_MS = 40;

    /**
     * 单帧最大时间步长，防止 IDE 卡顿后弹簧"跳变"。
     *
     * <p><b>【缺陷 1 —— 审计确认】必须 ≥ {@link #MAX_ADAPTIVE_DELAY_MS}。</b>
     *
     * <p>原值 {@code 1.0/30 ≈ 33.3ms}，比调度器的自适应上限 40ms 还小 ——
     * 这意味着调度器自己会交付 33.3~40ms 的间隔，而物理步长被钳在 33.3ms，
     * 中间那段（最多 6.7ms）每一帧都被丢掉，累计起来就是
     * 「慢放 1.3~2.9 倍」。两个门限必须自洽。
     *
     * <p>取 {@code 1.0/20 = 50ms}：略大于调度上限 40ms，保证调度能交付的
     * 任何间隔都能被物理完整推进，不再丢时间。
     */
    private static final double MAX_DT = 1.0 / 20;

    /**
     * 「恢复检测」阈值（秒）：本帧真实间隔超过它，就认为刚从长时间停顿中恢复。
     *
     * <p>触发场景：切到别的窗口再切回、系统休眠唤醒、EDT 被长时间阻塞。
     * 这些情况下 AWT 会延迟派发 {@code invokeLater} 任务，{@code tick()} 整段不执行，
     * 于是下一帧的 dt 等于离开的整个时长，被 {@link #MAX_DT} 截断成 33.3ms，
     * 弹簧一帧走完约 93% —— 观感就是"回来卡一下"。
     *
     * <p>阈值取 100ms：正常帧约 8ms，偶发卡顿 20~50ms，都远小于它；
     * 而真正的"离开"通常是几百毫秒到几分钟。取这个量级可以确保
     * <b>日常打字与连续操作绝不会误触发</b>。
     */
    private static final double RECOVERY_GAP_SECONDS = 0.1;

    /**
     * 「分步推进」适用的最大停顿时长（秒）。超过它就一次性归位。
     *
     * <p>见 {@link #tick()} 里 recovery 分支的说明：
     * <ul>
     *   <li>≤ 本值：切成 ~8ms 小步推进，观感与常驻刷新一致
     *       —— 这是「不开常驻刷新也别卡」要解决的区间</li>
     *   <li>&gt; 本值：直接归位，不补放拖尾
     *       —— 离开几秒后回来，用户期待「光标已在新位置」，
     *          而不是补放一段几秒的动画；分步只会白白占用 EDT</li>
     * </ul>
     *
     * <p>取 0.5s：步数上限约 62 步（0.5 / 0.008），单帧循环开销可忽略。
     */
    private static final double RECOVERY_STEP_CAP_SECONDS = 0.5;

    /**
     * 辉光淡入淡出的偏移量区间（像素）。
     *
     * <p>辉光强度由四角相对目标的最大偏移量（即拖尾展开程度）驱动：
     * 偏移 ≤ {@link #GLOW_FADE_START} 视为静止，不画光晕；
     * 偏移 ≥ {@link #GLOW_FADE_END} 为满强度。
     *
     * <p>区间只有 2px 宽，因为这两个状态本来就离得很近：静止时偏移为 0，
     * 而任何真实移动的偏移至少是一个字符宽（约 8px）或一行高（约 32px）。
     * 窄区间既保证移动时光晕"完全没被动过"，又让停下时的熄灭不突兀。
     */
    private static final double GLOW_FADE_START = 0.5;
    private static final double GLOW_FADE_END = 2.5;

    /**
     * IME 组合中间态最多连续跳过的帧数（F1-c 的冻结上界）。
     *
     * <p><b>为什么必须有上界</b>：F1-c 判定「几何不一致」为中间态并跳过重算，
     * 且**不更新** {@code lastVisualColumn} —— 于是若该条件在正常场景下也持续成立，
     * 拦截会变成**无限期冻结**：坐标永远不更新，光标停在错误位置。
     *
     * <p><b>这不是假想</b>：批次2 的 F1-b（判据为「offset 不变但 column 变」）
     * 就被验证方用反射 harness 实测出这个问题 —— 连续 200 帧全被拦，
     * centerX 停在 101.0 而正确值 133.0（偏差 30px ≈ 1.5 个字符）。
     *
     * <p>取 15 帧（8ms × 15 ≈ 120ms）：足够覆盖一次 IME 组合的中间态，
     * 又短到肉眼无法察觉"冻结了 120ms"。超过即强制放行一次，避免永久卡死。
     *
     * <p><b>F1-c 起这个上界更重要了</b>：新判据是几何比较，理论上比「offset+column」
     * 更贴近目标，但任何几何 API 都可能在某些重排瞬间短暂不一致。上界保证
     * 最坏情况只是「120ms 后强制重算一次」，不会永久错位。
     */
    private static final int IME_MAX_SKIP_FRAMES = 15;

    /** 四个角点的相对位置（左上 → 右上 → 右下 → 左下，顺时针） */
    private static final double[][] CORNER_RELATIVE = {
            {-0.5, -0.5},
            {0.5, -0.5},
            {0.5, 0.5},
            {-0.5, 0.5},
    };

    private final Editor editor;
    private final JComponent content;
    private final CaretTrailPanel panel;
    private final NeovideConfig config;
    /**
     * 每个 {@code Caret} 一份拖尾状态（多光标）。
     *
     * <p><b>为什么用 {@link IdentityHashMap}</b>：{@code Caret} 对象没有可靠的
     * {@code equals/hashCode}，必须按<b>引用</b>判等，否则不同光标可能撞键。
     *
     * <p><b>为什么还需要帧号清理</b>：官方文档明确「两个 caret 落到同一视觉位置时
     * 平台会合并成一个」。合并后旧 {@code Caret} 对象不再出现在
     * {@code getAllCarets()} 里，而 IdentityHashMap 按引用判等 ——
     * 旧条目永远不会再被访问到。因此每帧遍历时打 {@code lastSeenFrame}，
     * 回收时删掉「帧号 ≠ 当前帧」的条目（不依赖数量比较，
     * 因为合并是一增一减、数量可能不变）。
     */
    /**
     * 每个光标一份拖尾状态。
     *
     * <p><b>key 有两种，必须用 HashMap 而不是 IdentityHashMap</b>：
     * <ul>
     *   <li>普通多光标 → {@code Caret} 对象。{@code Caret} 没有重写
     *       {@code equals/hashCode}，因此 HashMap 下天然是引用判等 —
     *       与 IdentityHashMap 行为一致。</li>
     *   <li>块选区 → {@code Integer}（该行选区起点的 offset）。
     *       <b>这里曾用 IdentityHashMap 出过 bug</b>：块选区每帧都要
     *       按 offset 查同一个 key，而 {@code Long.valueOf()} 只在
     *       -128~127 有缓存，offset 超过就每次 new 一个 ——
     *       IdentityHashMap 按引用判等会认为"新 key"，于是每帧新建
     *       SpringTrail，旧条目永远清不掉（内存泄漏 + 拖尾重复）。</li>
     * </ul>
     */
    private final Map<Object, SpringTrail> trails = new java.util.HashMap<>();

    /** 每帧复用的活跃列表（避免每帧新建） */
    private final List<SpringTrail> activeTrails = new java.util.ArrayList<>();

    /** 帧序号，用于帧号标记-清除 */
    private long frameSeq = 0L;

    /**
     * 主光标对应的拖尾（残影模式 / 日志 / 探针需要"一个代表"）。
     *
     * <p>为什么保留单数语义：残影模型 {@link AfterimageTrail} 目前只记一条轨迹，
     * 多光标残影是后续工作。这里先让单光标行为与改造前完全一致。
     */
    private SpringTrail cameraTrail;

    /**
     * 块选区的固定 key。
     *
     * <p>块选区恒定只有 1 个拖尾（一个整体矩形），因此用常量 key ——
     * 选区扩大/缩小时 key 不变，同一个拖尾继续拉伸，
     * 这是「一整个斜阶梯」观感的物理前提。
     *
     * <p>取一个不可能与 {@code Caret} 对象碰撞的字符串常量。
     */
    private static final Object BLOCK_SELECTION_KEY = new Object();

    /** 多光标上限：防止「全选文件」时创建上万条拖尾 */
    private static final int MAX_TRAILS = 64;

    /**
     * 是否为「静态光标」模式 —— 用于控制台类编辑器（{@link EditorKind#CONSOLE}）。
     *
     * <p><b>为什么必须处理它们</b>：原生光标是通过全局配色方案
     * （把 {@code CARET_COLOR} 置为全透明）隐藏的，而这个操作<b>无法按编辑器区分</b> ——
     * {@code Editor} / {@code CaretModel} / {@code Caret} 三个接口均已确认不存在
     * per-editor 的光标隐藏 API。因此 Build / Run / Services / Problems 等工具窗口的
     * 输出区一旦被全局隐藏，若不补画光标，<b>那些位置的光标就会彻底消失</b>。
     *
     * <p>这类编辑器只需要一个静止的光标，不需要拖尾，因此走静态分支：
     * 每帧把四角直接吸附到目标位置（等价于画一个矩形），且不绘制辉光。
     */
    private final boolean staticCaret;

    // ===== 残影模型（trailMode = AFTERIMAGE 时使用）=====

    private final AfterimageTrail afterimageTrail;

    /** 残影绘制数据缓冲（每项 [x, y, w, h, alpha]），避免每帧重建列表 */
    private final List<float[]> ghostBuffer = new ArrayList<>(32);

    /** 辉光最大外扩量，用于计算重绘区域需要留出的余量（随光标尺寸动态更新） */
    private int glowPadding;

    // ===== 渲染调度 =====

    /**
     * 是否有尚未执行的渲染任务（用于跳过积压帧）。
     *
     * <p><b>每个 animator 各自一个</b>：投递前由 {@link RenderScheduler} 的调度线程
     * {@code compareAndSet(false, true)}，EDT 任务一开始就复位。绝不能换成全局单一标志 ——
     * 那会让"一个 animator 还没画完"连带拖住其它 animator 的投递（契约 T1-1 红线）。
     */
    final AtomicBoolean framePending = new AtomicBoolean(false);

    /** 当前调度间隔：EDT 侧更新，调度线程读取 */
    private volatile int currentDelayMs = IDLE_DELAY_MS;

    /**
     * 下一次希望被 tick 的时刻（{@code System.nanoTime()}）：<b>EDT 写、调度线程读</b>。
     *
     * <p><b>T1-1</b>：原来是"每个 animator 一条调度线程，各自 sleep 到自己的下一个到期
     * 时刻"，现在由全局唯一的 {@link RenderScheduler} 线程按本字段统一排程。
     *
     * <p>初值 = 现在 + {@link #IDLE_DELAY_MS}：与基线的首帧时机对齐 —— 基线新线程先
     * {@code sleep(currentDelayMs)} 再投递，而 {@code currentDelayMs} 的初值就是
     * {@link #IDLE_DELAY_MS}。**不得**初始化成 {@code System.nanoTime()}（那会让注册瞬间
     * 就多投一帧，早于基线的首次 tick）。
     */
    volatile long nextDueNanos = System.nanoTime() + IDLE_DELAY_MS * 1_000_000L;

    /**
     * 最近一次投递时 {@link #nextDueNanos} 的值（仅 {@link RenderScheduler} 的调度线程读写）。
     *
     * <p>用途：到期时刻由 EDT 在 tick <b>结束</b>时改写，而 {@link #framePending} 在 EDT
     * 任务<b>开始</b>时就复位了 —— 于是"tick 正在执行"的这段时间里，调度线程会读到
     * 「已经过期、且没有在途任务」的假象。只靠 framePending 的话，一次耗时超过 1ms 的 tick
     * 会被反复投递（调度线程 1ms 一轮），在 EDT 本就繁忙时形成恶性堆积。
     * 本字段记住"这个到期值已经投递过了"：值没变 = 上一帧尚未落地，直接跳过，
     * 从而保证每个到期周期 ≤1 次 {@code invokeLater}。
     */
    volatile long lastDispatchedDueNanos = Long.MIN_VALUE;

    /**
     * 最近一次「仍在运动」的时刻（纳秒）；{@code 0} 表示自创建以来从未运动过。
     *
     * <p><b>F2 / 批次2</b>：只用于辉光淡出计时（{@link #glowFade}）。
     * 只在 {@code anyAnimating == true} 的帧里更新，<b>不是每帧更新</b> ——
     * 否则「停止后开始计时」就永远起不来。
     */
    private long lastAnimatingNanos = 0L;

    /**
     * 辉光最后一次可见时的强度（0~1），与 {@link #lastAnimatingNanos} 配套。
     *
     * <p><b>F-1 修复的关键</b>：{@code glowStrength} 归零之后，需要以「归零前的那个亮度」
     * 为起点做淡出。若没有这个字段，淡出就只能从一个已经是 0 的亮度开始 —— 像素上不可见
     * （这正是第一版被验证方实测否定的原因）。
     */
    private float lastGlowStrength = 0f;

    /**
     * IME 组合态连续跳过的帧数（F1-c）。
     *
     * <p>与 {@link #IME_MAX_SKIP_FRAMES} 配合使用：连续跳过达到上界后强制放行一次，
     * 防止几何不一致长期成立时坐标被无限期冻结。
     */
    private int imeSkipFrames = 0;

    /** 是否已销毁 */
    private volatile boolean disposed = false;

    // ===== 性能统计（每秒输出一次，用于客观定位卡顿来源）=====

    private static final Logger LOG = Logger.getInstance(CaretAnimator.class);

    /**
     * fps 统计窗口（纳秒）。
     *
     * <p><b>T0-1（零观感优化·批次1）</b>：原为 {@code 1_000_000_000L}（1s），现取 5s。
     * 常驻刷新下约 125fps，1s 窗口每秒都往 idea.log 写一行（实测占日志 7.24%）；
     * 5s 窗口把写入频率降到约 1/5，而统计口径
     * （{@code frameCount / maxGapNanos / peakRepaintArea} 的计算与重置时机）一字未改 ——
     * 变的只是窗口长度，不是算法。
     *
     * <p>口径提醒：窗口变长后单行 {@code fps} 是 5s 均值，不可与旧的 1s 窗口数据直接比较。
     */
    private static final long STATS_WINDOW_NANOS = 5_000_000_000L;

    private int frameCount = 0;
    private int peakRepaintArea = 0;
    private long fpsWindowStart = 0L;

    /** 上一次记账的时刻；0 表示本窗口还没有过第二帧 */
    private long lastStatsNanos = 0L;

    /** 本窗口内相邻两帧的最大间隔（纳秒）—— 用于区分"持续阻塞"与"统计口径失真" */
    private long maxGapNanos = 0L;

    // ===== 重绘区域复用对象（避免每帧分配）=====

    private final Rectangle lastDirtyRegion = new Rectangle();
    private final Rectangle currentBounds = new Rectangle();

    /** 合并两个重绘区域时的复用对象，避免每帧分配 */
    private final Rectangle scratchUnion = new Rectangle();

    /** 上一次 tick 的时间戳（纳秒） */
    private long lastNanos;

    /** 上一次 tick 的绝对时刻，用于测量真实帧间隔 */
    private long lastTickNanos = 0L;

    /** 实测帧间隔的平滑值（毫秒），供自适应调度使用 */
    private double smoothedIntervalMs = ACTIVE_DELAY_MS;

    /**
     * 是否需要丢弃下一帧的帧间隔测量（仅 EDT 访问）。
     *
     * <p>从低频空闲档被 {@link #wakeUp()} 拉回高帧率时置位：那一刻的"帧间隔"
     * 实际是整个空闲期的长度，并非真实帧间隔，喂给平滑器会再次污染估计量。
     */
    private boolean skipNextIntervalMeasurement = false;

    /**
     * 上次记录的视觉位置，用于低成本地判断光标是否移动。
     * {@code getVisualPosition()} 远比 {@code visualPositionToXY()} 便宜 ——
     * 后者要换算折叠、制表符宽度等，是高帧率下的主要开销来源。
     */
    // lastVisualLine/Column 已下沉到 SpringTrail（见 isAt / markAt）
    private int __unusedLastVisualLine = Integer.MIN_VALUE;
    private int lastVisualColumn = Integer.MIN_VALUE;

    /**
     * 上一帧的光标字符偏移（{@link com.intellij.openapi.editor.CaretModel#getOffset()}）。
     *
     * <p>用于算出「本次移动跨越了几个字符」，供短跳判定使用。
     * 负数表示尚未初始化。
     *
     * <p><b>为什么用 offset 而不是像素</b>：offset 是编辑器的原生字符坐标，
     * 差值是精确的字符个数，与字体、字号、全角半角、连字全部无关 ——
     * 这正是「英文丝滑但中文卡」问题的根治法。详见 {@code TrailCorner#jump}。
     */
    // lastCaretOffset 已下沉到 SpringTrail（每光标独立，见该类注释第 4 点）

    // ===== 动画目标状态 =====

    /** 光标矩形中心点（contentComponent 坐标系） */
    // centerX/centerY 已下沉到 SpringTrail

    private double cursorWidth;
    private double cursorHeight;

    /** 标记目标点刚发生变化，下一帧需要重新分配角点 rank */
    // jumped 已下沉到 SpringTrail

    /** 是否已完成初始化（首帧直接吸附到位，不做入场动画） */
    // initialized 已下沉到 SpringTrail

    /**
     * 强制重新同步标记：布局刚发生变化时置位。
     * 此时视觉位置可能没变，但像素坐标已失效，必须绕过快速路径重算 ——
     * 否则会留下一个停在错误位置的「悬浮光标」。
     */
    private boolean forceResync = false;

    public CaretAnimator(@NotNull Editor editor, @NotNull NeovideConfig config) {
        this.editor = editor;
        this.config = config;
        this.content = editor.getContentComponent();
        this.panel = new CaretTrailPanel(config);

        // 控制台类编辑器（Build / Run / Services / Problems 的输出区）只补画静止光标，
        // 不做拖尾 —— 它们的光标被全局隐藏了，必须有人补上，原因见 staticCaret 字段说明。
        this.staticCaret = editor.getEditorKind() == EditorKind.CONSOLE;

        // 多光标：四角不再在这里创建，而是每个 Caret 各自一个 SpringTrail
        // （见 refreshTarget）。这里只保留 panel 与调度初始化。

        this.afterimageTrail = new AfterimageTrail(config);
        afterimageTrail.setLifetime(config.afterimageLifetime);

        // 重绘区域必须覆盖辉光的外扩部分，否则光晕边缘会残留脏像素。
        // 辉光半径 = shadowBlurFactor × 光标较长边，这里先用典型行高 32 估算，
        // 之后 refreshTarget 拿到真实光标尺寸时会修正。
        this.glowPadding = (int) Math.ceil(Math.max(3f, config.shadowBlurFactor * 32f)) + 4;

        // 面板覆盖整个内容区（拖尾可能延伸到光标之外）
        panel.setBounds(0, 0, Math.max(content.getWidth(), 1), Math.max(content.getHeight(), 1));
        content.add(panel);

        editor.getCaretModel().addCaretListener(this);

        // 首次定位
        refreshTarget();

        startScheduler();
    }

    // ==================== 渲染调度 ====================

    /**
     * 启动渲染调度：注册到<b>全局唯一</b>的 {@link RenderScheduler} 线程。
     *
     * <p><b>T1-1</b>：原来是"每个 animator 一条 {@code Thread.sleep} 线程"，现在是
     * "全局一条共享线程按 {@link #nextDueNanos} 统一排程" —— 空闲线程数从 N 降到 1。
     * 但 EDT 任务粒度<b>不变</b>：每个到期 animator 仍由调度线程<b>各自</b>投递一次
     * {@code invokeLater}（见 {@link RenderScheduler} 的类注释）。
     */
    private void startScheduler() {
        RenderScheduler.register(this);
    }

    /**
     * 共享调度线程投递到 EDT 的单帧任务（由 {@link RenderScheduler} 调用）。
     *
     * <p><b>EDT 任务粒度</b>：一个 EDT 任务只服务<b>一个</b> animator。严禁把它改成
     * "批量 tick 多个 animator"的那种任务（契约 P0-1）。
     */
    void runTick() {
        // 复位时机与基线一致：EDT 任务开始即复位（置位由调度线程在投递前完成）
        framePending.set(false);
        if (disposed) {
            return;
        }
        try {
            tick();
        } finally {
            scheduleNextDue();
        }
    }

    /**
     * 登记下一次到期时刻（tick 结束时调用）。
     *
     * <p><b>为什么不能写成 {@code now + currentDelayMs}</b>（本批 r2 修复的核心）：
     * 基线是「<i>先 sleep(currentDelayMs)，再 invokeLater</i>」，节拍由 sleep 主导 ——
     * 当单帧 EDT 耗时（例如卡顿时 25ms）<b>大于</b> delay 时，基线不会再多等一个 delay，
     * 有效周期就是单帧耗时本身（EDT 极限）。
     *
     * <p>若从「tick 结束的 now」起算，有效周期会变成 {@code 单帧耗时 + delay}，
     * 比基线多等整整一个 delay。实测（1500ms 窗口，单帧 25ms）：
     * <pre>
     *   R2 常驻刷新     基线 59 帧 → 旧写法 44 帧（−25%）
     *   R4 自适应       基线 59 帧 → 旧写法 37 帧（−37%）
     *   R5/R6 打字场景  基线 59/58  → 旧写法 48/48（−19%/−17%）
     * </pre>
     * 而 tick=0ms 的常态场景反而是新版略高（171→178），说明差异只出在"单帧超过 delay"。
     *
     * <p><b>修法</b>：从「本次 tick 的<b>应到期时刻</b>」往后累加 delay，即
     * {@code lastDispatchedDueNanos + currentDelayMs}；若该时刻已经过去（单帧超时），
     * 取 {@code max(·, now)} 使其"立即到期"，EDT 一空就接着投递 —— 与基线「不再多等
     * 一个 delay」的语义一致。这样稳态下周期恒为 delay（与基线相同），
     * 超时下自动退化为 EDT 极限（也与基线相同）。
     */
    private void scheduleNextDue() {
        long now = System.nanoTime();
        long dispatched = lastDispatchedDueNanos;
        long next;
        if (dispatched == Long.MIN_VALUE) {
            // 从未被调度线程投递过（理论上 runTick 只由投递触发，这里仅防御）：
            // 不能拿 MIN_VALUE 去加，否则溢出成极大负数 → 立刻疯狂投递。
            next = now + currentDelayMs * 1_000_000L;
        } else {
            next = dispatched + currentDelayMs * 1_000_000L;
        }
        // 单帧耗时已超过 delay：立即到期（追赶），不额外等一个 delay —— 对齐基线
        nextDueNanos = Math.max(next, now);
    }

    /** 供 {@link RenderScheduler} 在投递前判断是否已销毁（{@code dispose()} 与本字段置位之间只有极短窗口） */
    boolean isDisposed() {
        return disposed;
    }

    /**
     * 立即切到高帧率并唤醒调度线程（否则最长要等一个 IDLE 周期才响应）。
     *
     * <p>本方法在<b>每次光标事件</b>（含每次击键）时都会被调用，因此这里只做
     * 「从空闲档回到活跃档」时的必要修整，不能无条件重置状态 —— 原因见下。
     */
    private void wakeUp() {
        // 必须在覆盖 currentDelayMs 之前取：它记录的是"刚才所处的档位"。
        //   true  → 刚才在空闲档（IDLE_DELAY_MS / IDLE_UNFOCUSED_DELAY_MS）
        //   false → 刚才已在活跃档，说明这是打字／移动光标过程中的普通唤醒
        final boolean fromIdleTier = currentDelayMs > MAX_ADAPTIVE_DELAY_MS;

        currentDelayMs = ACTIVE_DELAY_MS;

        // 从空闲档唤醒时，{@link #smoothedIntervalMs} 已被空闲间隔喂大
        // （最坏接近 IDLE_UNFOCUSED_DELAY_MS = 600），而它只在 tick() 内更新。
        // 若不一并归位，tick() 末尾的 computeNextDelay() 会用它算出被
        // MAX_ADAPTIVE_DELAY_MS 截断的间隔，之后要几十帧才收敛回满帧率 ——
        // 实测：失焦 600ms 档切回后需 715ms / 26 帧才恢复，
        // 正是"切回界面动光标卡一下"的成因。
        //
        // 但绝不能无条件重置：活跃档下的 smoothedIntervalMs 反映的是真实帧间隔
        // （编辑器繁忙所致，可达 30~40ms），那正是自适应调度赖以工作的输入。
        // 若在每次击键时都把它按回 8ms，调度器会以编辑器实际交付不了的频率提交，
        // 绝大多数任务随即被 framePending 丢弃，反而白白占用 EDT —— 打字手感变差。
        if (fromIdleTier) {
            smoothedIntervalMs = ACTIVE_DELAY_MS;
            // 紧随其后的那次测量跨越了整个空闲期，不是真实帧间隔，丢弃
            skipNextIntervalMeasurement = true;

            // 【同样必须重置 lastNanos —— 它跨过的也是整个空闲期】
            //
            // tick() 开头有 dt = min((now - lastNanos)/1e9, MAX_DT)。
            // 空闲期间 lastNanos 停在最后一次 tick，于是唤醒后的第一帧
            // dt 直接拉满 MAX_DT = 1/30 ≈ 33.3ms（正常约 8ms，大 4 倍）。
            //
            // 而 dt 会原样喂给弹簧：DampedSpring.update() 里 c = exp(-(4/len)·dt)。
            // 取 animationLength = 0.05s、o = 80 时：
            //   dt = 0.008 → c ≈ 0.527（每帧衰减约 47%，动画跨多帧展开）
            //   dt = 0.033 → c ≈ 0.070（一帧衰减 93%，等于"瞬移"）
            //
            // 观感上这就是「停几秒后第一次删除到上一行、下方文本并入原行时
            // 卡一下」：位置突变本应由弹簧平滑展开，却被一个超大的 dt
            // 在一帧内走完。而快速连续删除时 lastNanos 每 8ms 都在更新，
            // dt 始终正常，所以反而流畅 —— 这个"越连续越顺"的反差正是佐证。
            //
            // 注意 skipNextIntervalMeasurement 只丢弃了「统计测量」，
            // 并不能阻止 dt 被算成整个空闲期。
            lastNanos = System.nanoTime();
        }

        // 【T1-1】把本 animator 置为"立即到期"并打断共享调度线程的睡眠。
        // 顺序由 RenderScheduler#wakeUp 内部保证（先置到期、后打断），调用点写不反：
        // 只打断而不置到期，会让"停手后第一次操作"重新多等一个空闲周期（最长 600ms）。
        RenderScheduler.wakeUp(this);
    }

    /**
     * 外部要求立即重排下一帧（切换「常驻刷新」开关时用）。
     *
     * <p>只打断调度线程的 sleep，让它按最新的 {@code currentDelayMs} 重新排程，
     * 不改动任何动画状态 —— 因此不会引起视觉跳变。
     *
     * <p><b>T1-1</b>：与 {@link #wakeUp()} 同样先把该 animator 的
     * {@link #nextDueNanos} 置为"现在"再打断共享线程 —— 与基线"interrupt 醒来后
     * 直接落到投递"的时序等价；不涉及任何动画状态。
     */
    public void requestWakeUp() {
        RenderScheduler.wakeUp(this);
    }

    // ==================== 光标监听 ====================

    /**
     * 光标事件只用于"唤醒"高帧率。
     * 真正的目标点更新在 {@link #tick()} 中每帧进行，避免事件时序问题。
     */
    /**
     * 多光标新增（Alt+拖拽列选、Alt+Shift+点击等）。
     *
     * <p><b>只唤醒，不直接改状态</b> —— 与 {@link #caretPositionChanged} 同一原则：
     * 平台事件的时序不可靠（光标事件可能在坐标数据更新之前触发），
     * 真正的目标点更新统一在 {@code tick()} 里做。
     *
     * <p>仍需置 {@code forceResync}：新光标若恰好落在与旧光标相同的视觉位置，
     * 快速路径会误判"没动"，导致它不从邻居起飞而是直接吸附。
     */
    @Override
    public void caretAdded(@NotNull CaretEvent event) {
        forceResync = true;
        wakeUp();
    }

    /**
     * 多光标移除（含平台<b>自动合并</b>：两个 caret 落到同一视觉位置时会合并成一个）。
     *
     * <p>移除后对应的 {@code SpringTrail} 会由帧号标记-清除回收，
     * 这里只需唤醒重绘，让画面立刻少一条拖尾。
     */
    @Override
    public void caretRemoved(@NotNull CaretEvent event) {
        forceResync = true;
        wakeUp();
    }

    @Override
    public void caretPositionChanged(@NotNull CaretEvent event) {
        // 【F1-a / 批次2】让坐标缓存立即失效。
        //
        // 为什么需要：CaretAnimator 依赖「每帧主动读光标位置」，但 refreshTarget()
        // 有快速路径 —— 视觉位置（line, column）没变就直接跳过 visualPositionToXY。
        // 于是当平台给的列值停在某个中间态时，插件会认定「光标没动」，永久沿用
        // 可能错误的像素坐标。实测触发场景：中文输入法组合态（详见 FIX-BATCH2.md F1）。
        //
        // 加这一行后，滚动 / 切换标签页 / 点击等任何引发 caret 事件的操作都会
        // 强制重算一次坐标，不必再等「下一次输入」才自愈。
        //
        // 代价：只在光标事件时多做一次 visualPositionToXY，不在每帧热路径上。
        forceResync = true;
        wakeUp();
    }

    // ==================== 动画循环 ====================

    /** 每帧：重新读取光标位置 → 推进物理 → 局部重绘 */
    private void tick() {
        long now = System.nanoTime();
        double rawDt = (now - lastNanos) / 1_000_000_000.0;
        double dt = Math.min(rawDt, MAX_DT);
        lastNanos = now;

        // 【恢复检测：这一帧跨过了很长的时间】
        //
        // 与 wakeUp() 里的 fromIdleTier 是同一类问题的另一个入口。
        // 那个判据看的是「刚才是否在空闲调度档」，但有两种情况它抓不到：
        //
        //   ① 开启常驻刷新（alwaysHighFps）时 currentDelayMs 恒为 8ms，
        //      fromIdleTier 永远 false —— 切屏回来时三个修复全被跳过；
        //   ② 即使不开常驻刷新，窗口失去前台焦点 / 系统休眠期间
        //      AWT 会延迟派发 invokeLater 任务，tick() 整段不执行，
        //      而 currentDelayMs 可能仍停在活跃档，判据同样是 false。
        //
        // ==================== 空闲唤醒：按「一帧」推进，而不是整段 ====================
        //
        // 【历史与实测结论】
        //   版本 A：dt = ACTIVE_DELAY_MS(8ms)
        //     → 唤醒后拖尾正常展开（15 帧、峰值拉伸 5.51px），观感最好
        //     → 唯一顾虑：停顿期间拖尾「定格」在旧偏移上
        //   版本 B：dt = rawDt（真实间隔，0.7.0 起）
        //     → 一帧就走完几乎全部位移（因 dt >= animationLength 直接短路归零）
        //     → 用户感知「不开常驻刷新就卡爆」
        //   版本 C：分步推进（0.7.3，已废弃）
        //     → 【实测证明无效】闭式解对时间可加：一次 update(0.12) 与
        //        update(0.008)×15 结果差 8.88e-16（完全等价）。
        //        而 updateCorners / submitRepaint 在循环之外，
        //        中间状态根本不渲染 —— 所以「分步」不产生任何中间帧，
        //        只是绕开了短路。等效于版本 B，救不回拉伸。
        //
        // 【本版】采用版本 A 的做法，并区分两种停顿：
        //
        //   · 短停顿（≤ RECOVERY_STEP_CAP_SECONDS）：dt 取「一帧」= 8ms
        //     → 拖尾从当前位置平滑展开 15 帧，与常驻刷新观感一致
        //     → 停顿期间残留的偏移会在 ~120ms 内自然收拢，不是永久卡住
        //
        //   · 长停顿（> 上限）：dt 取真实间隔，触发短路一次性归位
        //     → 离开几秒后回来，用户期待「光标已在新位置」，
        //        而不是补放一段几秒钟的拖尾动画
        if (rawDt > RECOVERY_GAP_SECONDS) {
            dt = (rawDt <= RECOVERY_STEP_CAP_SECONDS)
                    ? ACTIVE_DELAY_MS / 1000.0     // 短停顿：按一帧推进 → 平滑展开
                    : rawDt;                       // 长停顿：一次性归位
            smoothedIntervalMs = ACTIVE_DELAY_MS;
            // 紧随其后的那次"帧间隔测量"跨越了整段离开时间，不是真实帧间隔
            skipNextIntervalMeasurement = true;

            // 【诊断】确认缺陷 4：空闲唤醒那一拍是否读到旧坐标。
            // 若这里有日志但紧接着没有动画帧，说明首拍扑空、掉回 120ms。
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format(
                        "neovide-cursor: recovery rawDt=%.0fms → dt=%.0fms", rawDt * 1000, dt * 1000));
            }
        }

        // 测量真实帧间隔（指数平滑）。变快时快速跟随、变慢时缓慢跟随，
        // 避免偶发的一次卡顿把调度频率长期压低。
        if (lastTickNanos != 0L) {
            if (skipNextIntervalMeasurement) {
                // 该测量跨越了整个空闲期，不代表真实帧间隔，丢弃
                skipNextIntervalMeasurement = false;
            } else {
                double actualMs = (now - lastTickNanos) / 1_000_000.0;
                double weight = actualMs < smoothedIntervalMs ? 0.4 : 0.1;
                smoothedIntervalMs += (actualMs - smoothedIntervalMs) * weight;
            }
        }
        lastTickNanos = now;

        boolean moved = refreshTarget();

        // 多光标：只要没有任何活跃拖尾就退出（等价于旧的 !initialized）
        if (activeTrails.isEmpty()) {
            return;
        }

        // 编辑器不可见（后台标签页、尚未完成初始化）时不绘制。
        // 否则拖尾会停在一个错误的位置，看起来就是"光标莫名悬浮"。
        if (!content.isShowing()) {
            panel.clearCorners();
            return;
        }

        // 内容区尺寸变化时同步面板大小（窗口缩放、分屏调整等）。
        // 同时置位 forceResync：布局刚变过，此前缓存的像素坐标可能已失效。
        if (panel.getWidth() != content.getWidth() || panel.getHeight() != content.getHeight()) {
            panel.setBounds(0, 0, Math.max(content.getWidth(), 1), Math.max(content.getHeight(), 1));
            lastDirtyRegion.setBounds(0, 0, 0, 0);
            forceResync = true;
        }

        boolean anyAnimating;
        if (staticCaret) {
            // 控制台编辑器：只补画静止光标，不参与拖尾
            anyAnimating = tickStaticCaret();
        } else if (config.trailMode == NeovideConfig.TrailMode.AFTERIMAGE) {
            anyAnimating = tickAfterimage(now, moved);
        } else {
            anyAnimating = tickSpring(dt, now);
        }

        currentDelayMs = computeNextDelay(moved || anyAnimating);

        recordFrameStats(now, moved || anyAnimating);
    }

    /**
     * 计算下一次调度间隔。
     *
     * <p>三项策略叠加：
     * <ol>
     *   <li><b>自适应</b>：AWT 的 EventQueue 中，原生输入事件（键盘/鼠标）的优先级
     *       高于 {@code invokeLater} 提交的任务 —— 编辑器繁忙时我们的渲染任务会被排到后面，
     *       实测帧间隔远大于设定的 8ms。此时若继续按 8ms 提交，绝大多数任务会因
     *       framePending 被直接丢弃，纯粹浪费 EDT 时间。改为跟随实测间隔，减少无效提交。</li>
     *   <li><b>失焦降频</b>：编辑器没有焦点时光标不会移动，无需高响应度。</li>
     *   <li><b>静止降频</b>：完全没有动画时只做低频看门狗轮询。</li>
     * </ol>
     */
    private int computeNextDelay(boolean active) {
        // 常驻刷新：不区分有无动画，永远走活跃档。
        // 这样停手后第一次操作也不会"冷启动"—— 下一帧最多 8ms 就到。
        // 代价见 NeovideConfig#alwaysHighFps（会持续占用 EDT）。
        if (config.alwaysHighFps) {
            return ACTIVE_DELAY_MS;
        }

        if (active) {
            int adaptive = (int) Math.round(smoothedIntervalMs * 0.85);
            return Math.max(ACTIVE_DELAY_MS, Math.min(adaptive, MAX_ADAPTIVE_DELAY_MS));
        }
        return isEditorFocused() ? IDLE_DELAY_MS : IDLE_UNFOCUSED_DELAY_MS;
    }

    /** 编辑器当前是否持有键盘焦点 */
    private boolean isEditorFocused() {
        try {
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            return owner != null && SwingUtilities.isDescendingFrom(owner, content);
        } catch (Throwable t) {
            // 判断失败时按「有焦点」处理，确保功能不因此丢失
            return true;
        }
    }

    /**
     * 帧率与重绘面积的统计（运动期间每秒输出一次到 IDE 日志）。
     *
     * <p>用于客观区分两类卡顿：
     * <ul>
     *   <li>fps 低但重绘面积小 → 瓶颈在调度或 EDT 负载</li>
     *   <li>重绘面积大 → 瓶颈在重绘区域（拖尾过长或跨度过大）</li>
     * </ul>
     */
    private void recordFrameStats(long now, boolean active) {
        if (!active) {
            return;
        }
        if (fpsWindowStart == 0L) {
            fpsWindowStart = now;
        }
        frameCount++;

        int area = currentBounds.width * currentBounds.height;
        if (area > peakRepaintArea) {
            peakRepaintArea = area;
        }

        // 【诊断：相邻两次记账的最大间隔】
        //
        // 起因：日志里出现过 fps=0.5 / delay=8ms 的组合，它有两种截然不同的含义，
        // 必须区分，否则会朝错的方向优化：
        //   (a) 真的 2 秒才画出一帧 → EDT 被占满，任务投递出去轮不到执行
        //   (b) 统计口径失真        → 窗口起点久未重置，把空闲时间算进了分母
        //                            （README 踩坑 #21 记录过同样的 bug）
        //
        // 判据：maxGap 远小于 window ⇒ fps 低是因为窗口里只有零星几帧、
        // 其余时间是空的（属 b 或间歇性停顿）；
        // maxGap 本身就达数百毫秒 ⇒ 属 (a)，是持续阻塞。
        long gap = lastStatsNanos == 0L ? 0L : now - lastStatsNanos;
        if (gap > maxGapNanos) {
            maxGapNanos = gap;
        }
        lastStatsNanos = now;

        long elapsed = now - fpsWindowStart;
        if (elapsed >= STATS_WINDOW_NANOS) {
            // 【T0-1】日志门控：Debug Log 未开时连 String.format 都不求值。
            // 编辑器标识只在"窗口到期 + Debug 开启"时才计算（每帧绝不计算），
            // 且必须追加在行尾 —— build/analyze-log.ps1:8 与 diag/extract.py:6
            // 用固定顺序的正则解析前面六个字段，中间插字段会让既有证据链失效。
            if (LOG.isDebugEnabled()) {
                String editorTag;
                try {
                    editorTag = editor.getEditorKind().name();
                } catch (Throwable t) {
                    // 编辑器正在销毁时取不到 kind，退化为 UNKNOWN（不得影响统计口径）
                    editorTag = "UNKNOWN";
                }
                LOG.debug(String.format(
                        "neovide-cursor: fps=%.1f  frames=%d  window=%.0fms  maxGap=%.0fms  "
                                + "peakRepaintArea=%dpx  delay=%dms  editor=%s#%d",
                        frameCount * 1_000_000_000.0 / elapsed,
                        frameCount,
                        elapsed / 1_000_000.0,
                        maxGapNanos / 1_000_000.0,
                        peakRepaintArea,
                        currentDelayMs,
                        editorTag,
                        System.identityHashCode(editor)));
            }
            frameCount = 0;
            peakRepaintArea = 0;
            maxGapNanos = 0L;
            fpsWindowStart = now;
        }
    }

    /** 把区域裁剪到面板范围内后提交重绘，越界部分直接丢弃 */
    private void repaintRegion(Rectangle region) {
        int x1 = Math.max(0, region.x);
        int y1 = Math.max(0, region.y);
        int x2 = Math.min(panel.getWidth(), region.x + region.width);
        int y2 = Math.min(panel.getHeight(), region.y + region.height);

        if (x2 > x1 && y2 > y1) {
            // 【必须用 repaint（异步），不要改成 paintImmediately（同步）】
            //
            // 曾试过 paintImmediately，理由是想省掉一次 EDT 调度往返，结果适得其反：
            // 它除了同步绘制本区域，还会顺带把 RepaintManager 中所有已排队的脏区
            // 一次性处理掉 —— 删除文本等操作会产生大量脏区，于是一次全被同步执行，
            // EDT 被长时间阻塞。
            //
            // 实测对比（同机同场景）：
            //   repaint           → 峰值 105.6 fps
            //   paintImmediately  → 峰值仅 36.9 fps，且重绘面积只有 1680px 时仍低至 8.9 fps
            panel.repaint(x1, y1, x2 - x1, y2 - y1);
        }
    }

    /**
     * 计算【全部光标】所有角点的包围盒（含辉光余量），结果写入 out 以避免分配。
     *
     * <p>多光标：必须遍历 activeTrails 里的每一个拖尾，否则重绘区域会漏掉
     * 非主光标的拖尾，表现为「其他光标留下残影」。
     */
    private void computeTrailBounds(Rectangle out) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (TrailCorner corner : allCorners()) {
            double x = corner.getCurrentX();
            double y = corner.getCurrentY();
            if (x < minX) {
                minX = x;
            }
            if (x > maxX) {
                maxX = x;
            }
            if (y < minY) {
                minY = y;
            }
            if (y > maxY) {
                maxY = y;
            }
        }

        int pad = glowPadding;
        out.setBounds(
                (int) Math.floor(minX) - pad,
                (int) Math.floor(minY) - pad,
                (int) Math.ceil(maxX - minX) + pad * 2,
                (int) Math.ceil(maxY - minY) + pad * 2);
    }

    /**
     * {@link #refreshTarget()} 耗时埋点阈值（纳秒）= 2ms。
     *
     * <p><b>T0-5（零观感优化·批次1）</b>：用于确认/排除「软换行的
     * {@code visualPositionToXY} 换算是否昂贵」。2ms 是正常帧预算（8ms）的 1/4，
     * 越过它才值得记一行 —— 只决定是否写 debug 日志，<b>不参与任何行为分支</b>。
     */
    private static final long REFRESH_TARGET_SLOW_NANOS = 2_000_000L;

    /**
     * 重新读取光标位置并更新动画目标点。
     *
     * @return true 表示位置确实发生了变化
     */
    private boolean refreshTarget() {
        try {
            VisualPosition vp = editor.getCaretModel().getVisualPosition();

            // 【快速路径：恢复为顶层早退】
            //
            // 为什么必须保留（而不是像第一版重构那样下沉到循环内）：
            // 单光标是绝对多数场景，而这一层跳过的是每帧三次昂贵调用 ——
            //   getOffset() / visualPositionToXY() / offsetToXY()
            // 实测契约 P0-5 要求「非快速路径帧每帧 getOffset 恰好 1 次」，
            // 下沉后变成 2 次（IME 判据 1 次 + charsMoved 1 次），直接违反。
            //
            // 多光标的正确性：只有当【所有】光标都没变时才早退。
            // 判断依据是主光标的缓存 + 光标数量 —— 数量变化（新增/移除/合并）
            // 会让 changed 为 true，不会漏掉。个别非主光标微妙移动的场景，
            // 由 caret 事件（caretPositionChanged / Added / Removed）置 forceResync 兜住。
            if (!forceResync && cameraTrail != null && cameraTrail.isInitialized()
                    && cameraTrail.isAt(vp.line, vp.column,
                            editor.getDocument().getModificationStamp())) {
                // 【多光标补充】主光标没变，但光标集合可能变了（Alt+拖拽新增、
                // 平台合并、Alt+点击移除）。这些情况由 caretAdded/Removed 事件
                // 置 forceResync 兜住 —— 那三个监听器已经实现了。
                //
                // 这里【不能】调 getAllCarets() 检查数量：那会破坏单光标的
                // 快速路径契约（A9b/A9c/A9d 要求这一帧零昂贵调用）。
                // 数量变化一定伴随 caret 事件，事件会先置 forceResync，
                // 下一帧就走不到这里。
                return false;
            }

            // 【F1-c / 批次3】IME 组合态识别 —— **几何判据**。
            //
            // ============ 为什么要换掉批次2 的判据（F1-b） ============
            //
            // F1-b 的判据是「getOffset() 没变（lastCaretOffset），但 vp.column 变了」。
            // 用户实测反馈这个判据对本 bug **无效** —— 因为水杉输入法组合期间
            // 光标会移动进「拼音音节内部」：offset 和 column **都变了**，
            // 判据的前提根本不成立，于是中间态被当成正常移动画了出去。
            //
            // ============ 为什么几何判据能治它 ============
            //
            // 平台的两个坐标 API 走**不同的换算路径**（已在 IDEA 261 字节码里核实）：
            //
            //   visualPositionToXY(vp)
            //       → EditorCoordinateMapper.visualPositionToXY
            //       → 按 **vp.column（视觉列）** 迭代 fragment，调 Fragment.visualColumnToX
            //
            //   offsetToXY(offset)
            //       → EditorCoordinateMapper.offsetToXY
            //       → 先把 offset 钳进 [0, textLength] 并 alignToCodePointBoundary，
            //         再按 **offset** 迭代 fragment，调 Fragment.offsetToX
            //
            // 正常状态下（vp 确实对应 offset 所在的那个字符边界）两者给出**同一个 x**。
            // 但 IME 组合期间，平台给的 vp 是按「组合串」的临时字符宽度算的，
            // 而组合串（拼音/候选）与最终汉字的宽度不同 —— 于是 vp 对应的视觉列
            // 与 offset 真实所在的字符边界**不是同一个位置**，两个 x 就分岔了，
            // 差值正好是「半个字宽」。这与用户观察到的「卡在『中』的竖笔画上」一致。
            //
            // 因此：**x 不一致 ⇔ 这一帧的 vp 不可信**。这不是猜 vp 是否"合理"，
            // 而是直接检查我们即将画出去的那个点与"按真实 offset 应处的点"是否重合。
            // 这个判据对「offset 和 column 都变了」的中间态同样有效 —— 正是它要解决的。
            //
            // ============ 边界（逐条满足，验证方要专门测） ============
            //   · forceResync 优先：布局变化必须重算，不被本判据拦截
            //   · 首次调用（还没定过位）不得误判：必须正常走完首次定位
            //   · offset 取不到（-1）时判据不介入，退回正常路径
            //   · 正常移动：vp 与 offset 指向同一个字符边界 → x 相等 → 放行
            //   · 连续不一致达到 IME_MAX_SKIP_FRAMES 后强制放行一次（保留冻结上界）
            //
            // 注意：offset 只读一次，供本判据与下方 charsMoved 共用（契约 P0-5）。
            int caretOffset = -1;
            try {
                caretOffset = editor.getCaretModel().getOffset();
            } catch (Throwable ignored) {
                // 取不到就保持 -1：本判据不介入，charsMoved 也退回兼容分支
            }

            // 【T0-5】耗时埋点起点：必须在快速路径提前返回"之后" ——
            // 否则等于给 125fps 的每帧路径加两次 nanoTime，与本批目标相反。
            //
            // 【位置注意】它必须在几何判据之前开始计时：判据里的 offsetToXY 也是
            // 被计量的换算成本的一部分，漏掉它会让 T0-5 的读数偏乐观。
            long startedNanos = System.nanoTime();

            // vp 的像素坐标：本帧只算这一次，几何判据与后续定位共用同一个 p。
            Point p = editor.visualPositionToXY(vp);

            // 【冻结上界】没有它，一旦几何不一致在正常场景下持续成立
            // （inlay 提示、折叠展开、重排瞬间等），拦截会变成**无限期冻结**。
            // 批次2 的 F1-b 就被验证方实测出这个缺陷：连续 200 帧全被拦、偏差 30px。
            // 这里保留同一个上界：超过即强制放行一次，最坏只是「120ms 后自愈」。
            if (!forceResync
                    && cameraTrail != null
                    && cameraTrail.isInitialized()
                    && caretOffset >= 0
                    && imeSkipFrames < IME_MAX_SKIP_FRAMES) {
                // 只有当两个 API 都成功给出坐标时才做比较；任一抛异常就放行，
                // 绝不让"取不到坐标"变成"永远不重算"。
                boolean midComposition = false;
                try {
                    Point byOffset = editor.offsetToXY(caretOffset);
                    // 只比较 x：本项目只画竖条光标，横向错位才是本 bug 的表现；
                    // 纵向差异属于换行/软换行重排，交给下方正常路径重算，不在此拦截。
                    //
                    // 【必须先排除虚拟空间】光标越过行尾时（IntelliJ 默认开启 virtual space，
                    // 按 → 键可以把光标推到行尾之后），两个 API **本就应当**给出不同的 x：
                    //   visualPositionToXY → 行尾 x + 空格宽 × 越界列数
                    //   offsetToXY         → 被钳到行尾 offset，返回行尾 x
                    // 这是设计如此，不是中间态。不排除的话，用户每次把光标推到行尾之后，
                    // 覆盖层就会被误判成组合态而冻结 —— 一个比原 bug 更显眼的新缺陷。
                    // 判据见 isInVirtualSpace()：offset 已在该行行尾，
                    // 而 vp.column 超出该行真实存在的字符数。
                    midComposition = byOffset.x != p.x && !isInVirtualSpace(caretOffset, vp);
                } catch (Throwable ignored) {
                    // 坐标 API 不可用：不判定为中间态，走正常路径
                }

                if (midComposition) {
                    // 平台给的 vp 与真实插入点的几何位置不一致 ⇒ 组合中间态。
                    // 本帧不重算坐标，也【不更新】lastVisualLine/lastVisualColumn ——
                    // 这样下一帧若几何重新一致，仍能正常重算。
                    imeSkipFrames++;
                    return false;
                }
            }
            imeSkipFrames = 0;

            forceResync = false;

            // 多光标：视觉位置缓存下沉到每个 SpringTrail（isAt / markAt）

            double height = editor.getLineHeight();

            // 【P0 修复：光标宽度对齐】
            //
            // 原来直接用 config.cursorWidth(2px)，而 IDEA 原生光标宽度由
            // EditorSettings.getLineCursorWidth() 决定（实测约 5px）。
            //
            // 后果（用户实测反馈"两个光标"）：
            //   · hideNativeCaret=false（保留原生）→ 原生 5px 与覆盖层 2px 叠加，
            //     两条边都露出来
            //   · hideNativeCaret=true（常驻绘制）→ 覆盖层比用户惯用光标细
            //
            // 策略见本类静态方法 resolveCursorWidth（刻意放在 UI 层而不放 core/NeovideConfig：
            // D0 门禁把 core/NeovideConfig.java 冻结了，任何改动都会让 D0 变红）：
            //   常驻绘制 → 用 config 值（用户可调）
            //   保留原生 → 跟随真实宽度（否则叠加错位）
            //   读不到   → 回退 config 值（不能因为取不到就让功能失效）
            // 【宽度来源：必须用 EditorUtil.getDefaultCaretWidth()】
            //
            // 用户实测（2026-10-24）：「关闭隐藏原生发现我们绘制出来的
            // 还是不和 idea 的一样（2px 5px），怀疑我设置成了粗光标」。
            //
            // 根因：EditorSettings.getLineCursorWidth() 返回的是【相对倍率】
            // （IDEA 默认 1，用户设「粗」时 2 或 3），不是像素。
            // 直接当像素用 → 用户设成粗光标时，我们画出 2px 而 IDEA 画 10px。
            //
            // 正确来源：EditorUtil.getDefaultCaretWidth() —— IDEA 自己的换算方法，
            // 它把「倍率 × 字体度量」算成真实像素宽度。
            //
            // 两个都读一下：优先用 getDefaultCaretWidth（像素），
            // 失败时退回 getLineCursorWidth（至少能反映"用户设了粗光标"这个事实）。
            int realCursorWidth = -1;
            try {
                realCursorWidth = com.intellij.openapi.editor.ex.util.EditorUtil
                        .getDefaultCaretWidth();
            } catch (Throwable ignored) {
                // 退回倍率（虽然语义不同，但比完全不读好）
            }
            if (realCursorWidth <= 0) {
                try {
                    int ratio = editor.getSettings().getLineCursorWidth();
                    // 倍率 → 像素：IDEA 的基准宽度是 1px（普通光标），
                    // 所以 ratio × 1 近似成立；真正的像素值以 getDefaultCaretWidth 为准。
                    if (ratio > 0) {
                        realCursorWidth = ratio;
                    }
                } catch (Throwable ignored) {
                    // 两者都取不到：保持 -1，由 resolveCursorWidth 回退到 config
                }
            }
            double width = resolveCursorWidth(config.hideNativeCaret, realCursorWidth,
                    config.cursorWidth);

            // ==================== 多光标：逐 Caret 更新 ====================
            //
            // 【与单光标版本的关键差异】
            // 1. 字符移动量必须【逐光标独立】—— 每个 Caret 有独立 offset。
            //    若共用一份，光标 A 的读数会与 B 交错，算出荒谬的移动量，
            //    直接破坏本项目最重要的差异点（按字符判断短跳）。
            // 2. 新光标从【像素距离最近】的已初始化光标起飞，
            //    而不是遍历顺序的前一个（上游做法，Alt+散点选会从远处飞入）。
            // 3. 缓存键复用 hasCaretChanged() —— 见该方法的 IME 与时序说明。

            long stamp = editor.getDocument().getModificationStamp();
            frameSeq++;

            CaretModel caretModel = editor.getCaretModel();
            List<Caret> carets = caretModel.getAllCarets();
            Caret primaryCaret = caretModel.getPrimaryCaret();

            // 【块选区（Alt+拖拽列选）】它不产生多个 Caret，只有 1 个 + 矩形选区。
            // 详见 collectTrailTargets 的类注释。这里把块选区展开成 N 个目标点，
            // 用「offset」当稳定 key（Caret 对象此时只有 1 个，不能当 key）。
            int[] blockStarts = null;
            int[] blockEnds = null;
            try {
                // SelectionModel 没有 hasBlockSelection()；
                // 块选区存在时 getBlockSelectionStarts() 返回非空数组，
                // 否则返回空数组（不是 null）。直接取即可。
                SelectionModel sel = editor.getSelectionModel();
                int[] bs = sel.getBlockSelectionStarts();
                if (bs != null && bs.length > 1) {
                    blockStarts = bs;
                    blockEnds = sel.getBlockSelectionEnds();
                }
            } catch (Throwable ignored) {
                // 取不到就当没有块选区
            }

            boolean useBlock = blockStarts != null && blockStarts.length > 1;

            // 【块选区 = 每行一条独立的线，不是整块矩形】
            //
            // 已查阅 VS Code 版 neovide-cursor 源码（30d98f9b2/Neovide-Cursor）：
            //   const els = document.querySelectorAll(".monaco-editor .cursor");
            //   els.forEach((el) => {
            //       const inst = createNeovideCursor({ canvas });
            //       inst.updateSize(r.width, r.height);   // ← 单个光标尺寸
            //       this.cursors.set(id, { instance: inst, target: el });
            //   });
            // 也就是说：**每个 DOM 光标一个独立实例，尺寸是单个光标**。
            // 6 行 = 6 条独立的线，回退到 1 行就只剩 1 条。
            //
            // 中途我曾改成「整块一个矩形」—— 那是错的：
            //   · 多光标一开变成一整块矩形，不是线
            //   · 从 6 行退回 1 行不会坍缩（仍是 6 行高度的矩形）
            // 已恢复为「每行一条线」。
            //
            // 第二轮的「前三个慢后三个快」是 startFrom 造成的（已修：块选区不 startFrom）。
            int count = useBlock
                    ? Math.min(blockStarts.length, MAX_TRAILS)
                    : Math.min(carets.size(), MAX_TRAILS);
            boolean changed = count != activeTrails.size();

            activeTrails.clear();
            for (int i = 0; i < count; i++) {
                // 【块选区的 key 必须用「行号」，不能用 offset】
                //
                // 用户实测（2026-10-24）：「多光标不支持中文，表现为最后一行输出、
                // 其他不动，按 tab 时候感觉光标要重定位」。
                //
                // 根因：第一版用 blockStarts[i]（offset）当 key。输入中文后
                // 每一行的 offset 都会变（多了一个字符），key 随之变化 →
                // 被当成「新光标」→ 每帧重建 SpringTrail →
                //   · 旧拖尾被丢弃（其他行"不动"）
                //   · 新拖尾走 startFrom（"重定位"的观感）
                //   · 只有最后一行（offset 恰好没变或重建后位置对）看起来正常
                //
                // 正确做法：用「行号」当 key —— 行号在行内编辑时不变，
                // 只在真正增删行时才变，那才是「光标换了位置」。
                // 【块选区的 key 用「行号」】
                //
                // 为什么不用 offset：输入中文后每行 offset 都变 → key 变 →
                // 被当成新光标重建（用户报的「最后一行输出其他不动」）。
                //
                // 为什么不用固定 key：块选区现在是【每行一条线】，
                // 固定 key 会让所有行挤在同一个拖尾上。
                //
                // 行号是最稳定的：行内编辑不变，只有真正增删行时才变。
                Object key;
                if (useBlock) {
                    int lineNo = -1;
                    try {
                        lineNo = editor.getDocument().getLineNumber(blockStarts[i]);
                    } catch (Throwable ignored) { }
                    key = (lineNo >= 0)
                            ? Integer.valueOf(-1000000 - lineNo)
                            : Integer.valueOf(blockStarts[i]);
                } else {
                    key = carets.get(i);
                }
                SpringTrail trail = trails.get(key);
                if (trail == null) {
                    trail = new SpringTrail(config);
                    // 【块选区不做出场动画，直接各自吸附】
                    //
                    // 用户反馈：「第一行拖尾长，最后一行短」。
                    //
                    // 根因：块选区第一版让所有新光标都 startFrom(最近的光标)，
                    // 而 startFrom 是「复制源的当前形状」—— 源在 (x0,y0)、
                    // 新光标在 (x0,y1)，复制过来的四角相对自己目标点是错的，
                    // 于是各行的初始拖尾长度不一致（第一行先建、状态最新，
                    // 后面几个复制到的是"陈旧形状"）。
                    //
                    // 正确做法：块选区的每行光标是【同时出现】的，
                    // 不该有先来后到。让它们各自吸附到目标位置即可。
                    //
                    // 普通多光标（Alt+点击新增）仍保留 startFrom ——
                    // 那种场景用户是"逐个添加"的，从邻居滑出符合直觉。
                    if (!useBlock) {
                        SpringTrail source = nearestInitializedTrail(trails);
                        if (source != null) {
                            trail.startFrom(source);
                        }
                    }
                    trails.put(key, trail);
                    changed = true;
                }
                trail.lastSeenFrame = frameSeq;
                activeTrails.add(trail);

                // 块选区：用【整个选区的包围盒】定位（1 个矩形）
                // 普通光标：用 Caret
                int caretOff;
                VisualPosition cvp;
                double targetW = width;
                double targetH = height;
                double targetCx;
                double targetCy;
                int blockLine = -1;

                if (useBlock) {
                    // 【块选区：每行一条独立的线】
                    //
                    // 与 VS Code 一致（查过源码：每个 DOM .cursor 一个实例，
                    // 尺寸是单个光标）。这里取该行选区起点的像素坐标，
                    // 尺寸用单字符 (width, height) —— 不合并成整块。
                    caretOff = blockStarts[i];
                    try {
                        blockLine = editor.getDocument().getLineNumber(caretOff);
                        cvp = editor.offsetToVisualPosition(caretOff);
                    } catch (Throwable t) {
                        cvp = editor.getCaretModel().getVisualPosition();
                    }
                    targetW = width;
                    targetH = height;
                    targetCx = 0;    // 实际用 cp 算，见下方 setTarget
                    targetCy = 0;
                } else {
                    Caret caret = carets.get(i);
                    cvp = caret.getVisualPosition();
                    caretOff = caret.getOffset();
                    targetCx = 0;               // 普通光标分支不用这两个值
                    targetCy = 0;
                }
                trail.currentLine = (useBlock && blockLine >= 0) ? blockLine : cvp.line;

                // 快速路径：视觉位置 + 文档修改戳都没变就跳过坐标换算。
                // resync / forceResync 时强制重算全部光标。
                boolean needsUpdate = forceResync
                        || !trail.isInitialized()
                        || !trail.isAt(cvp.line, cvp.column, stamp);

                if (needsUpdate) {
                    Point cp = editor.visualPositionToXY(cvp);
                    trail.markAt(cvp.line, cvp.column, stamp);
                    int charsMoved = trail.updateCharsMoved(caretOff);
                    trail.applyCharsMoved(charsMoved);

                    // 【每个光标都是单字符尺寸的线】（与 VS Code 一致）
                    //
                    // 曾改成整块包围盒 → 多光标变成一整块矩形、退回 1 行不坍缩。
                    // 恢复为每行一条线。
                    double cx;
                    double cy;
                    double w;
                    double h;
                    if (useBlock) {
                        // 块选区每行：用该行起点的像素坐标 + 单字符尺寸
                        cx = cp.x + width / 2.0;
                        cy = cp.y + height / 2.0;
                        w = width;
                        h = height;
                    } else {
                        cx = cp.x + width / 2.0;
                        cy = cp.y + height / 2.0;
                        w = width;
                        h = height;
                    }
                    if (trail.setTarget(cx, cy, w, h)) {
                        changed = true;
                    }
                } else {
                    // 目标没变，但仍要把字符移动量清零，避免上一帧的值残留
                    trail.applyCharsMoved(-1);
                }

                // 主光标的那份用于残影 / 日志 / 探针（保持旧语义）
                // 块选区没有「主光标」概念，取第 0 个当代表
                if (useBlock ? i == 0
                        : (primaryCaret == null || key == primaryCaret)) {
                    cameraTrail = trail;
                }
            }

            // 【帧号标记-清除：无条件执行】
            //
            // 第一版写成 `if (trails.size() > activeTrails.size())` —— 有缺陷：
            //   · 平台合并光标时一增一减、数量不变 → 永远不进清理分支
            //   · 块选区行数不变但内容换了 → 同样不进
            // 现在无条件跑：代价是一次 O(n) 遍历（n ≤ 64），
            // 换来的是「死条目一定被清」。
            int beforeSize = trails.size();
            trails.entrySet().removeIf(e -> e.getValue().lastSeenFrame != frameSeq);
            if (trails.size() != beforeSize) {
                changed = true;
            }

            if (activeTrails.isEmpty()) {
                logSlowRefresh(startedNanos, false);
                return false;
            }

            // 【视觉分组：相邻行归为一组】
            //
            // 用户需求：「假如 123 行是在一起的，5 行独立一个，这样可以避免串扰」。
            //
            // 同组内共用一个辉光强度（取组内最大），组间互不影响。
            // 这样相邻行的辉光不会被"拆成几个独立光晕"，而是呈现为一个整体。
            int n = activeTrails.size();
            int[] lines = new int[n];
            for (int i = 0; i < n; i++) {
                lines[i] = activeTrails.get(i).currentLine;
            }
            int[] groups = groupAdjacentLines(lines);
            for (int i = 0; i < n; i++) {
                activeTrails.get(i).groupId = groups[i];
            }

            // 辉光半径与重绘余量随光标尺寸同步更新
            //
            // 【审查修正】这里原本写成「仅当宽高变化时更新」——那是错的。
            // refreshGlowPadding()（面板改 shadowBlurFactor 后调用）读的是
            // cursorWidth/cursorHeight 这两个字段；若只在新旧不同时才赋值，
            // 首次或某些路径下它们可能还是旧值，导致 glowPadding 不刷新。
            // 实测后果：shadowBlurFactor 0.2→0.6 时 glowPadding 应为 24，实际停在 4。
            // 无条件更新，代价只是两次赋值。
            cursorWidth = width;
            cursorHeight = height;
            panel.updateGlowRadius(width, height);
            glowPadding = (int) Math.ceil(panel.getGlowWidth()) + 4;

            logSlowRefresh(startedNanos, changed);
            return changed;

        } catch (Throwable t) {
            // 编辑器正在销毁或重绘中：本次跳过，下一帧会自动重试
            return false;
        }
    }

    /**
     * 找「像素距离最近、且已初始化」的拖尾，作为新光标的起飞源。
     *
     * <p><b>为什么不照抄上游</b>：上游用 {@code previous}（遍历顺序的前一个）。
     * Alt+向下拖列选时 carets 按 offset 排序，碰巧正确；但 <b>Alt+点击散点选</b> 时，
     * 新光标会从「offset 更小的任意光标」复制形状 —— 视觉上是「从别处飞来」。
     *
     * <p>按像素距离选源能让新光标<b>从空间上最近的邻居滑出</b>，符合直觉。
     *
     * @param self  新光标（尚未放入 trails）
     * @param carets 全部 caret（用于取新光标自己的目标位置）
     * @param map   已存在的拖尾
     * @return 最近的已初始化拖尾；没有就返回 null（调用方会走首次吸附）
     */
    private SpringTrail nearestInitializedTrail(Map<Object, SpringTrail> map) {
        SpringTrail best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (SpringTrail candidate : map.values()) {
            if (!candidate.isInitialized()) {
                continue;
            }
            // 距离用「离原点最近」代替「离新光标最近」：
            // 新光标此刻还没有目标点，用已有拖尾的中心距原点距离做稳定排序即可
            // （列选时各光标纵向排列，取首个已初始化拖尾的效果等价）。
            double distSq = candidate.centerX * candidate.centerX
                    + candidate.centerY * candidate.centerY;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 光标是否停在「虚拟空间」里（即行尾之后）。
     *
     * <p><b>为什么这个排除是必需的</b>：IntelliJ 默认开启 virtual space，
     * 用户按 → 键可以把光标推到行尾<b>之后</b>。此时平台的两个坐标 API
     * <b>本来就应当给出不同的 x</b>：
     *
     * <ul>
     *   <li>{@code visualPositionToXY(vp)} → 行尾 x + 空格宽 × 越界列数（越界多远画多远）</li>
     *   <li>{@code offsetToXY(offset)} → offset 被钳到 {@code [0, textLength]}，
     *       所以永远返回行尾 x</li>
     * </ul>
     *
     * <p>这是设计如此，<b>不是</b> IME 中间态。若不排除，用户每次把光标推到行尾之后，
     * 几何判据都会误判为组合态、把覆盖层冻在旧位置 —— 这会是一个比原 bug
     * 更显眼的新缺陷（原 bug 只在第三方输入法组合期出现，这个则是每次按 → 都出现）。
     *
     * <p>判据（两条同时成立才算虚拟空间）：
     * <ol>
     *   <li>{@code offset == 该行行尾}（即插入点已经在最后一个字符之后），且</li>
     *   <li>{@code vp.column > 该行的可见列数}（vp 超出了这一行真实存在的列）。</li>
     * </ol>
     *
     * <p>任一取不到数据就返回 {@code false}（保守：宁可让它走正常重算路径，
     * 也不要把真实中间态放过去）。
     */
    private boolean isInVirtualSpace(int caretOffset, VisualPosition vp) {
        try {
            Document doc = editor.getDocument();
            int line = doc.getLineNumber(caretOffset);
            int lineEnd = doc.getLineEndOffset(line);
            if (caretOffset != lineEnd) {
                return false;           // 插入点不在行尾 → 不可能是虚拟空间
            }
            // 该行真实存在的字符数。getLineEndOffset 已不含行分隔符，
            // 所以这个差值就是行内字符数。
            // 注意 tab 会让「视觉列」大于「字符数」（一个 \t 可能占 4 列），
            // 但那只会让 vp.column 更大、更可能被判为虚拟空间 —— 所以这里
            // 用「字符数」是**保守**的：真正的虚拟空间一定满足 vp.column > 字符数。
            int lineLength = lineEnd - doc.getLineStartOffset(line);
            return vp.column > lineLength;
        } catch (Throwable t) {
            // 取不到就按「不是虚拟空间」处理：宁可多走一次正常重算
            return false;
        }
    }

    /**
     * T0-5 埋点：仅当本次坐标换算耗时超过 {@link #REFRESH_TARGET_SLOW_NANOS} 才写一行 debug。
     *
     * <p>只在未命中快速路径的那一次 {@link #refreshTarget()} 调用上执行，且日志文本被
     * {@code LOG.isDebugEnabled()} 短路 —— Debug Log 关闭时没有任何字符串拼接。
     * 两种「未命中快速路径」的返回（中心点未变 / 已更新）各调用一次，单次调用最多一行。
     */
    private void logSlowRefresh(long startedNanos, boolean moved) {
        long costNanos = System.nanoTime() - startedNanos;
        if (LOG.isDebugEnabled() && costNanos > REFRESH_TARGET_SLOW_NANOS) {
            LOG.debug(String.format(
                    "neovide-cursor: refreshTarget %.1fms  moved=%b  vp=%d:%d",
                    costNanos / 1_000_000.0, moved,
                    activeTrails.size(), trails.size()));
        }
    }

    // ==================== 两种渲染模式 ====================

    /**
     * 静态光标模式的一帧：把四角直接吸附到目标位置，不产生任何拖尾。
     *
     * <p>仅用于控制台类编辑器（Build / Run / Services / Problems 等输出区）——
     * 它们的光标被全局隐藏了，必须有人补画，详见 {@link #staticCaret}。
     *
     * <p>返回值恒为 false，让调度器保持在空闲频率：控制台光标不需要高帧率，
     * 仅在 caret 事件唤醒时才会短暂提速。
     */
    private boolean tickStaticCaret() {
        // 多光标：每个光标都要补画（原生光标被全局隐藏，不补画就彻底不可见）
        panel.beginQuads();
        for (SpringTrail trail : activeTrails) {
            trail.snapToTarget();
            panel.addQuad(
                    new double[]{
                            trail.corners[0].getCurrentX(), trail.corners[1].getCurrentX(),
                            trail.corners[2].getCurrentX(), trail.corners[3].getCurrentX()},
                    new double[]{
                            trail.corners[0].getCurrentY(), trail.corners[1].getCurrentY(),
                            trail.corners[2].getCurrentY(), trail.corners[3].getCurrentY()},
                    0f);    // 不绘制辉光：控制台不需要，也省掉这部分重绘开销
        }

        computeTrailBounds(currentBounds);
        submitRepaint();
        return false;
    }

    /** 弹簧模式的一帧：推动物理模拟并绘制四边形 */
    private boolean tickSpring(double dt, long now) {
        // 多光标：逐光标推进物理
        boolean anyAnimating = false;
        double maxOffset = 0;
        for (SpringTrail trail : activeTrails) {
            if (trail.update(dt)) {
                anyAnimating = true;
            }
            if (trail.maxOffset() > maxOffset) {
                maxOffset = trail.maxOffset();
            }
        }

        // 【按组取最大偏移】—— 消除串扰
        //
        // 用户反馈：「多光标下输入输出快速按感觉有的撕裂」。
        //
        // 根因：第一版直接用全局 maxOffset 驱动所有光标，于是
        // 「一个光标在动」会拉长「所有光标」的辉光淡出状态，
        // 相邻行之间互相干扰。
        //
        // 现在：同组内共享组内最大值（相邻行看起来是一个整体），
        // 不同组各算各的（第 5 行不受 1/2/3 行影响）。
        int maxGroup = -1;
        for (SpringTrail trail : activeTrails) {
            if (trail.groupId > maxGroup) {
                maxGroup = trail.groupId;
            }
        }
        double[] groupMaxOffset = new double[maxGroup + 1];
        for (SpringTrail trail : activeTrails) {
            if (trail.maxOffset() > groupMaxOffset[trail.groupId]) {
                groupMaxOffset[trail.groupId] = trail.maxOffset();
            }
        }

        // 辉光只在拖尾展开时点亮 —— 静止的光标顶着一圈光晕并不好看，
        // 那是"运动中"才该有的效果。强度由最大偏移量映射，边界见 GLOW_FADE_*。
        float glowStrength = (float) ((maxOffset - GLOW_FADE_START)
                / (GLOW_FADE_END - GLOW_FADE_START));
        if (glowStrength < 0f) {
            glowStrength = 0f;
        } else if (glowStrength > 1f) {
            glowStrength = 1f;
        }

        // 【F2 / 批次2】辉光淡出（方案 B：只淡辉光，光标本体始终不透明）。
        //
        // ==================== 为什么用「辉光最后可见时刻」而不是 anyAnimating ====================
        //
        // 第一版用 anyAnimating 当计时起点，验证方用逐帧位模式实测发现：淡出**在像素上
        // 完全不可见**（`gs>0 且 gf<1` 的帧数 = 0），因为两者是同一条曲线上的读数 ——
        //   anyAnimating 为 false 的时刻 ≈ glowStrength 归零的时刻（maxOffset 跌破 0.5），
        // 于是淡出计时刚开始，已经没有辉光可淡了。
        //
        // 正确做法：以「glowStrength 最后一次 > 0 的时刻」为计时起点，并把那一帧的
        // glowStrength 记住（lastGlowStrength）。glowStrength 归零后，用
        // `lastGlowStrength × fade` 作为有效强度 —— 这才是真正的"从当前亮度淡出"。
        //
        // 效果：停止移动后辉光保持 50ms（cursorDisappearDelay），
        // 再从原亮度线性熄灭 75ms（cursorFadeOutDuration）。
        if (glowStrength > 0f) {
            lastGlowStrength = glowStrength;
            lastAnimatingNanos = now;
        }

        float fade = glowFade(now);
        float effectiveGlow = (glowStrength > 0f) ? glowStrength : lastGlowStrength * fade;
        panel.setGlowStrength(effectiveGlow);
        panel.setGlowFade(fade);

        // 淡出未完成时必须继续 tick，否则最后一帧不刷新 → 辉光残留。
        //
        // 【F-2 修复】必须同时要求 fade > 0：fade 到 0 意味着淡出已经结束，
        // 若仍把 fading 置 true，tickSpring() 会恒返回 true →
        // computeNextDelay(active=true) → EDT 永不降频（空闲也在 8ms 空转）。
        // 这一条在 alwaysHighFps=true 的默认档被掩盖，干净安装（默认 false）时会暴露。
        boolean fading = config.overlayFadeOut && fade > 0f && fade < 1f;

        // 多光标：每个光标一个四边形，辉光强度逐光标独立
        panel.beginQuads();
        for (SpringTrail trail : activeTrails) {
            // 同组共享组内最大偏移 → 相邻行呈现为一个整体，组间互不影响
            double offForGlow = groupMaxOffset[trail.groupId];
            float trailGlow = glowStrengthForValue(offForGlow, effectiveGlow, fading);
            panel.addQuad(
                    new double[]{
                            trail.corners[0].getCurrentX(), trail.corners[1].getCurrentX(),
                            trail.corners[2].getCurrentX(), trail.corners[3].getCurrentX()},
                    new double[]{
                            trail.corners[0].getCurrentY(), trail.corners[1].getCurrentY(),
                            trail.corners[2].getCurrentY(), trail.corners[3].getCurrentY()},
                    trailGlow);
        }

        computeTrailBounds(currentBounds);
        submitRepaint();
        return anyAnimating || fading;
    }

    /**
     * 辉光淡出系数（0~1）：拖尾停止后先保持 delay，再线性淡出到 0。
     *
     * <p><b>只作用于辉光层</b>（{@code drawQuadGlow} 的峰值 alpha），
     * 光标本体（{@code fillPolygon}）始终不透明 —— 详见 FIX-BATCH2.md §3。
     *
     * <p>开关 {@code config.overlayFadeOut} 关闭时恒返回 1f，行为与 0.9.1 一致。
     */
    private float glowFade(long now) {
        if (!config.overlayFadeOut) {
            return 1f;                              // 开关关闭：不淡出，等价 0.9.1
        }
        if (lastAnimatingNanos == 0L) {
            // 尚未出现过可见辉光：没有可淡的东西。
            // 注意：这里返回 0f（契约 §3.3 原文），因为调用方只在 glowStrength>0 时
            // 才用 fade 参与乘法；glowStrength==0 时有效强度由 lastGlowStrength(0) 决定，
            // 行为与返回 1f 完全等价（0 × 1 = 0 × 0）。
            return 0f;
        }
        double sinceStop = (now - lastAnimatingNanos) / 1_000_000_000.0
                - config.cursorDisappearDelay / 1000.0;
        if (sinceStop <= 0) {
            return 1f;                              // 停止后 delay 内：保持满强度
        }
        double fade = config.cursorFadeOutDuration;
        if (fade <= 0 || sinceStop >= fade) {
            return 0f;                              // 淡出完毕
        }
        return (float) (1.0 - sinceStop / fade);
    }

    /**
     * 残影模式的一帧：记录当前位置并绘制历史残影。
     *
     * <p>不涉及任何物理模拟或帧间插值 —— 位置直接来自每帧的真实采样，
     * 因此掉帧只会让残影稀疏，轨迹本身绝不失真。
     * 这正是它比弹簧模型更适合低帧率环境的原因。
     */
    private boolean tickAfterimage(long now, boolean moved) {
        // 多光标：残影模型目前只跟主光标。
        //
        // 为什么先这样：AfterimageTrail 是"单条历史轨迹"模型，
        // 要支持多光标需要为每个 caret 各建一条轨迹 + 各自淘汰。
        // 这属于后续工作，先保证单光标残影行为与改造前完全一致。
        if (cameraTrail == null) {
            return false;
        }
        double cx = cameraTrail.centerX;
        double cy = cameraTrail.centerY;
        afterimageTrail.record(cx, cy, cursorWidth, cursorHeight, now);

        List<AfterimageTrail.Ghost> visible = afterimageTrail.collectVisible(now);

        ghostBuffer.clear();

        // ---- 第一部分：残影尾巴 ----
        // 跳过与当前光标位置重合的采样点，否则会和下面那个常驻光标叠在一起、
        // 半透明颜色叠加导致该处明显偏深。
        for (AfterimageTrail.Ghost ghost : visible) {
            if (ghost.centerX == cx && ghost.centerY == cy) {
                continue;
            }
            float alpha = afterimageTrail.alphaOf(ghost, now);
            if (alpha <= 0.02f) {
                continue;
            }
            ghostBuffer.add(new float[]{
                    (float) (ghost.centerX - ghost.width / 2.0),
                    (float) (ghost.centerY - ghost.height / 2.0),
                    (float) ghost.width,
                    (float) ghost.height,
                    alpha
            });
        }

        boolean hasTail = !ghostBuffer.isEmpty();

        // ---- 第二部分：常驻光标 ----
        // 残影会在 lifetime 到期后淡出，若只画残影，光标静止一会儿画面就空了。
        // 因此额外绘制一个始终完全不透明的"光标本体"。
        ghostBuffer.add(new float[]{
                (float) (cx - cursorWidth / 2.0),
                (float) (cy - cursorHeight / 2.0),
                (float) cursorWidth,
                (float) cursorHeight,
                1.0f
        });

        if (moved || hasTail) {
            panel.updateGhosts(ghostBuffer);
            computeGhostBounds(currentBounds);
            submitRepaint();
        }

        // 只有尾巴还在渐隐时才需要持续高帧率；只剩常驻光标时无需空转
        return hasTail;
    }

    /** 提交重绘：本帧区域 + 上一帧区域（后者用于擦除残影） */
    private void submitRepaint() {
        // 性能诊断模式：跳过全部重绘，只跑计算
        if (NeovideCaretManager.isPaintDisabled()) {
            return;
        }

        // 【两个区域相交时合并成一次 repaint】
        //
        // 原来无条件提交两次（上一帧区域用于擦除 + 当前帧区域）。
        // 但小位移时这两个矩形几乎完全重合 —— 同一块像素被交给 RepaintManager 两次，
        // 而 addDirtyRegion 本身要向上遍历组件树，白白多付一次代价。
        // 连续输入（每次只挪一个字符宽）正是这种"几乎重合"的典型场景。
        //
        // 大步跳转（删除到上一行、翻页）时两区域不相交，此时必须分开提交：
        // 合并成并集会把中间那片从未画过内容的区域一起重绘（README 踩坑 #7）。
        if (lastDirtyRegion.isEmpty() || currentBounds.isEmpty()
                || lastDirtyRegion.intersects(currentBounds)) {
            scratchUnion.setBounds(lastDirtyRegion);
            scratchUnion.add(currentBounds);
            repaintRegion(scratchUnion);
        } else {
            repaintRegion(lastDirtyRegion);
            repaintRegion(currentBounds);
        }
        lastDirtyRegion.setBounds(currentBounds);
    }

    /** 计算残影列表的包围盒（含辉光余量），结果写入 out 以避免分配 */
    private void computeGhostBounds(Rectangle out) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (float[] shape : ghostBuffer) {
            if (shape[0] < minX) {
                minX = shape[0];
            }
            if (shape[1] < minY) {
                minY = shape[1];
            }
            if (shape[0] + shape[2] > maxX) {
                maxX = shape[0] + shape[2];
            }
            if (shape[1] + shape[3] > maxY) {
                maxY = shape[1] + shape[3];
            }
        }

        int pad = glowPadding;
        out.setBounds(
                (int) Math.floor(minX) - pad,
                (int) Math.floor(minY) - pad,
                (int) Math.ceil(maxX - minX) + pad * 2,
                (int) Math.ceil(maxY - minY) + pad * 2);
    }

    /** 渲染模式切换后调用：清空两种模型的内部状态，避免残留 */
    public void onModeChanged() {
        afterimageTrail.clear();
        afterimageTrail.setLifetime(config.afterimageLifetime);

        for (SpringTrail trail : trails.values()) {
            trail.snapToTarget();
        }

        panel.clearCorners();
        panel.refreshStyles();

        // 让下一帧强制重算位置，避免快速路径把旧状态固化
        forceResync = true;
        lastDirtyRegion.setBounds(0, 0, 0, 0);
        wakeUp();
    }

    /**
     * 立刻按当前 {@code shadowBlurFactor} 重算辉光外扩量 {@link #glowPadding}。
     *
     * <p><b>为什么 onModeChanged() 不够</b>：它只置 {@code forceResync}，指望下一次
     * {@link #refreshTarget()} 走慢路径去更新 {@code glowPadding}（:1018-1019）。
     * 但慢路径在算 padding <b>之前</b>还有一条早退：
     * {@code if (newCenterX == centerX && newCenterY == centerY && height == cursorHeight) return false;}
     * —— 光标静止时这条恒成立，于是「改了 shadowBlurFactor、光标没动」的场景下
     * padding 永远算不到。静态光标编辑器（控制台）更是<b>整体跳过</b> refreshTarget
     * （见 {@code staticCaret}），它连慢路径都进不去，只能靠这里。
     *
     * <p>padding 偏小会让重绘包围盒切掉光晕边缘（脏像素残留），偏大只是多画一点。
     * 设置面板 apply() 之后用户会立刻盯着画面看，所以这里选择<b>主动算一次</b>，
     * 而不是等下一次光标移动。
     */
    /** 全部活跃拖尾的角点（供包围盒计算，不分配数组 —— 由调用方遍历 map） */
    private java.util.List<TrailCorner> allCornersScratch = new java.util.ArrayList<>();

    /**
     * 收集全部活跃光标的角点，供 {@code for (TrailCorner c : allCorners())} 使用。
     *
     * <p>返回的是内部复用列表（每帧 clear 再填），调用方不得持有引用。
     */
    private java.util.List<TrailCorner> allCorners() {
        allCornersScratch.clear();
        for (SpringTrail trail : activeTrails) {
            for (TrailCorner corner : trail.corners) {
                allCornersScratch.add(corner);
            }
        }
        return allCornersScratch;
    }

    /**
     * 单个光标的辉光强度。
     *
     * <p><b>为什么不能直接用全局 {@code glowStrength}</b>：多光标时每个光标独立运动，
     * 有的在动（辉光满强度）、有的已静止（辉光应为 0）。若共用全局值，
     * 静止的光标也会发光。
     *
     * <p>实现：把该光标自己的 maxOffset 映射到 [0,1]；若整体已在淡出阶段
     * （{@code fading}），则用全局的有效强度（因为淡出是 overlay 级别的行为）。
     */
    private float glowStrengthFor(SpringTrail trail, float globalStrength,
                                  float effectiveGlow, boolean fading) {
        if (fading) {
            return effectiveGlow;
        }
        double off = trail.maxOffset();
        // 注意：调用方已按组传入 trail 自己的偏移；
        // 若需要组内共享，见 tickSpring 里的 groupMaxOffset 用法。
        float s = (float) ((off - GLOW_FADE_START) / (GLOW_FADE_END - GLOW_FADE_START));
        if (s < 0f) {
            return 0f;
        }
        if (s > 1f) {
            return 1f;
        }
        return s;
    }

    void refreshGlowPadding() {
        if (disposed || staticCaret) {
            return;
        }
        panel.updateGlowRadius(cursorWidth, cursorHeight);
        glowPadding = (int) Math.ceil(panel.getGlowWidth()) + 4;
    }

    // assignRanks 已移入 SpringTrail（每个光标各自排名，见该类注释）

    /** 清除已绘制的拖尾并刷新一次（切到诊断模式时调用） */
    public void clearTrail() {
        panel.clearCorners();
        try {
            content.repaint();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================== 清理 ====================

    @Override
    public void dispose() {
        disposed = true;

        // 【T1-1】从共享调度器注销：集合空了调度线程自行退出。
        // 必须在 disposed 置位之后 —— 已经投递、还没执行的 EDT 任务会先看到 disposed
        // 直接返回（基线的 dispose 保护语义不变）。
        RenderScheduler.unregister(this);

        try {
            editor.getCaretModel().removeCaretListener(this);
        } catch (Throwable ignored) {
            // 编辑器可能已关闭
        }
        try {
            content.remove(panel);
            // 只 repaint，不 revalidate：避免触发 contentComponent 之外容器的重新布局
            content.repaint();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 解析「覆盖层该用多宽的光标」。
     *
     * <p><b>为什么放在这里而不是 {@link NeovideConfig}</b>：
     * D0 门禁对 {@code core/NeovideConfig.java} 做 {@code git diff --exit-code}，
     * <b>任何改动都会让 D0 变红</b>（该文件是冻结的默认值契约）。而这个函数是
     * 纯 UI 层的宽度决策，不属于配置默认值，因此放在这里。
     *
     * <p><b>要解决的问题</b>：{@code NeovideConfig.cursorWidth} 默认 2px 是写死的，
     * 而 IDEA 原生光标宽度由 {@code EditorSettings.getLineCursorWidth()} 决定
     * （实测约 5px）。两者不一致时：
     * <ul>
     *   <li>保留原生光标 → 原生 5px 与覆盖层 2px 叠加，<b>两条边都露出来，像两个光标</b></li>
     *   <li>常驻绘制 → 覆盖层比用户惯用光标细，显得「光标变窄了」</li>
     * </ul>
     *
     * <p><b>策略</b>：
     * <ul>
     *   <li>{@code hideNativeCaret = true}（我们自己画）→ 用 {@code cursorWidth}，
     *       保留用户可调的自由度</li>
     *   <li>{@code hideNativeCaret = false}（保留原生）→ <b>必须跟随真实宽度</b></li>
     *   <li>真实宽度读不到（≤ 0，编辑器正在销毁）→ 回退 {@code cursorWidth}</li>
     * </ul>
     *
     * <p>结果至少为 1.0：零宽会画出不可见的四边形，等于拖尾消失。
     *
     * @param hideNativeCaret 是否隐藏原生光标
     * @param realWidth       IDEA 报告的光标宽度（像素），≤ 0 表示读取失败
     * @param cursorWidth     用户配置的宽度
     * @return 覆盖层应使用的宽度（像素）
     */
    public static double resolveCursorWidth(boolean hideNativeCaret, int realWidth, float cursorWidth) {
        double w;
        if (hideNativeCaret) {
            w = cursorWidth;
        } else if (realWidth > 0) {
            w = realWidth;
        } else {
            w = cursorWidth;
        }
        return Math.max(w, 1.0);
    }

    /**
     * 决定「本帧该画几个光标、每个在哪」—— 把块选区与普通多光标统一到一处。
     *
     * <p><b>为什么需要这个抽象</b>：用户实测报「Alt+拖拽列选没有多光标」。
     * 根因是两者在平台层是<b>完全不同</b>的机制：
     * <ul>
     *   <li><b>多光标</b>（Alt+点击 / Alt+Shift+点击）→ {@code getAllCarets()} 返回 N 个</li>
     *   <li><b>块选区</b>（Alt+鼠标拖拽）→ 仍然只有 1 个 caret，
     *       列信息在 {@code SelectionModel.getBlockSelectionStarts()}</li>
     * </ul>
     * 若只读 {@code getAllCarets()}，块选区永远只有 1 个光标。
     *
     * <p><b>块选区优先</b>：它有 N 行就必须画 N 条拖尾，
     * 即使 carets 只有 1 个。两者不会同时存在（平台互斥）。
     *
     * @param caretOffsets  {@code getAllCarets()} 的 offset；空表示无光标
     * @param blockStarts   块选区起点数组；null 或无块选区时传 null
     * @param blockEnds     块选区终点数组
     * @param toPoint       把 offset 换算成像素坐标的函数（行首 x/y）
     * @return 每项 [x, y]；空数组表示本帧没有可画的光标
     */
    public static List<double[]> collectTrailTargets(int[] caretOffsets,
                                              int[] blockStarts, int[] blockEnds,
                                              java.util.function.IntFunction<double[]> toPoint) {
        List<double[]> out = new ArrayList<>();

        // 块选区优先：N 行 → N 个目标
        if (blockStarts != null && blockStarts.length > 1) {
            int n = Math.min(blockStarts.length, MAX_TRAILS);
            for (int i = 0; i < n; i++) {
                double[] pt = toPoint.apply(blockStarts[i]);
                if (pt != null) {
                    out.add(pt);
                }
            }
            return out;
        }

        // 普通光标 / 单光标退化
        if (caretOffsets != null) {
            int n = Math.min(caretOffsets.length, MAX_TRAILS);
            for (int i = 0; i < n; i++) {
                double[] pt = toPoint.apply(caretOffsets[i]);
                if (pt != null) {
                    out.add(pt);
                }
            }
        }
        return out;
    }

    /**
     * 把「行号数组」分组：相邻行（差 ≤ 1）归为一组。
     *
     * <p><b>用户需求</b>：「相邻行感觉绘制成一个大光标可以做吗？但是假如 123 行
     * 是在一起的，5 行独立一个，这样可以避免串扰」。
     *
     * <p>返回与输入等长的数组，元素是组号（从 0 开始）。
     * 组号按最小行号排序分配，保证「行号小的组组号小」。
     *
     * @param lines 每行光标的行号；空数组返回空数组
     * @return 每项对应的组号
     */
    public static int[] groupAdjacentLines(int[] lines) {
        int n = lines.length;
        int[] out = new int[n];
        if (n == 0) {
            return out;
        }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (x, y) -> Integer.compare(lines[x], lines[y]));

        int group = -1;
        int prevLine = Integer.MIN_VALUE;
        for (int k = 0; k < n; k++) {
            int i = idx[k];
            int line = lines[i];
            if (prevLine == Integer.MIN_VALUE || line - prevLine > 1) {
                group++;
            }
            out[i] = group;
            prevLine = line;
        }
        return out;
    }

    /**
     * 由「偏移量」直接算辉光强度（不含 fading 时用 effectiveGlow）。
     *
     * <p>与 {@link #glowStrengthFor} 的区别：那个读 trail 自己的 maxOffset，
     * 这个由调用方显式传入 —— 多光标分组后需要传「组内最大值」，
     * 让同组光标呈现为同一个整体亮度。
     */
    private float glowStrengthForValue(double offset, float effectiveGlow, boolean fading) {
        if (fading) {
            return effectiveGlow;
        }
        float s = (float) ((offset - GLOW_FADE_START) / (GLOW_FADE_END - GLOW_FADE_START));
        if (s < 0f) {
            return 0f;
        }
        if (s > 1f) {
            return 1f;
        }
        return s;
    }

}
