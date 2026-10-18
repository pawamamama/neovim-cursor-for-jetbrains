package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.AfterimageTrail;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
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
     * 运动时的调度间隔（毫秒）—— 叠加 Thread.sleep 的 ~1.5ms 误差后约 100fps。
     *
     * <p><b>保持原版的 8ms</b>。曾试过 12ms(83fps) 以减少 EDT 投递频率，
     * 但用户实测反馈观感变差（动画拉长后不够跟手），因此回退。
     * 这一项保持与上游一致，不再调整。
     *
     * <p>输入 / 换行 / 删除统一用这一个间隔，不按操作类型区分。
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
    private final TrailCorner[] corners;

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

    /** 调度线程：以高精度 sleep 驱动渲染节奏 */
    private Thread scheduler;

    /** 是否有尚未执行的渲染任务（用于跳过积压帧） */
    private final AtomicBoolean framePending = new AtomicBoolean(false);

    /** 当前调度间隔：EDT 侧更新，调度线程读取 */
    private volatile int currentDelayMs = IDLE_DELAY_MS;

    /** 是否已销毁 */
    private volatile boolean disposed = false;

    // ===== 性能统计（每秒输出一次，用于客观定位卡顿来源）=====

    private static final Logger LOG = Logger.getInstance(CaretAnimator.class);

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
     * 平滑后的帧间隔（秒），用于低帧率下的动画补偿。
     *
     * <p>低帧率时瞬时 dt 波动很大（15~40fps 意味着 0.025~0.067s），
     * 若直接用它计算动画时长，弹簧的时间常数会每帧跳变，运动就不连续了。
     */
    private double smoothedDt = 1.0 / 60.0;

    /**
     * 上次记录的视觉位置，用于低成本地判断光标是否移动。
     * {@code getVisualPosition()} 远比 {@code visualPositionToXY()} 便宜 ——
     * 后者要换算折叠、制表符宽度等，是高帧率下的主要开销来源。
     */
    private int lastVisualLine = Integer.MIN_VALUE;
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
    private int lastCaretOffset = -1;

    // ===== 动画目标状态 =====

    /** 光标矩形中心点（contentComponent 坐标系） */
    private double centerX;
    private double centerY;

    private double cursorWidth;
    private double cursorHeight;

    /** 标记目标点刚发生变化，下一帧需要重新分配角点 rank */
    private boolean jumped = false;

    /** 是否已完成初始化（首帧直接吸附到位，不做入场动画） */
    private boolean initialized = false;

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

        this.corners = new TrailCorner[CORNER_RELATIVE.length];
        for (int i = 0; i < CORNER_RELATIVE.length; i++) {
            corners[i] = new TrailCorner(CORNER_RELATIVE[i][0], CORNER_RELATIVE[i][1], config);
        }

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
     * 启动渲染调度线程。
     *
     * <p>用后台线程 {@code Thread.sleep} 控制节奏，再通过 {@code invokeLater}
     * 把绘制切回 EDT —— 绕开了 {@code javax.swing.Timer} 的 15.6ms 精度天花板。
     */
    private void startScheduler() {
        scheduler = new Thread(() -> {
            while (!disposed) {
                try {
                    Thread.sleep(Math.max(1, currentDelayMs));
                } catch (InterruptedException e) {
                    // 被 wakeUp() 主动唤醒：立即进入下一轮，不退出线程
                    if (disposed) {
                        return;
                    }
                }
                if (disposed) {
                    return;
                }

                // 上一帧还没渲染完就跳过本次调度，避免任务在 EDT 队列里堆积
                if (!framePending.compareAndSet(false, true)) {
                    continue;
                }

                SwingUtilities.invokeLater(() -> {
                    framePending.set(false);
                    if (!disposed) {
                        tick();
                    }
                });
            }
        }, "neovide-cursor-render");
        scheduler.setDaemon(true);
        scheduler.start();
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

            // 【同样必须重置 smoothedDt —— 这是"卡一下"残留的真正来源】
            //
            // 空闲期间调度降频到 IDLE_DELAY_MS(120) / IDLE_UNFOCUSED(600)，
            // 但 tick() 仍在跑：每次算出的 dt 都被 MAX_DT 截断成 1/30 ≈ 33.3ms，
            // 而 tick() 里是无条件喂给平滑器的：
            //
            //     smoothedDt += (dt - smoothedDt) * 0.25;
            //     double effectiveDt = Math.max(dt, smoothedDt);
            //
            // 于是停止操作的那几秒里，smoothedDt 被反复喂到 33.3ms 并停在那里。
            // 只重置 lastNanos 只能让「瞬时 dt」恢复正常，effectiveDt 取的是
            // max(dt, smoothedDt)，仍会取到那个被污染的 33.3ms，且要十几帧才降下来：
            //
            //     第 1 帧 0.0333 → 0.0270（仍是正常的 3.4 倍）
            //     第 3 帧 0.0187 → 第 10 帧 0.0094 → 约 12 帧后才接近 8ms
            //
            // 12 帧 × 8ms ≈ 100ms 的异常 dt，正是「停顿后第一次删除，卡一下」的时长。
            // 归位到一个典型帧长（1/125s，对应活跃档 8ms）即可，之后由正常的
            // 平滑流程接管。
            smoothedDt = 1.0 / 125.0;
        }

        Thread s = scheduler;
        if (s != null) {
            s.interrupt();
        }
    }

    /**
     * 外部要求立即重排下一帧（切换「常驻刷新」开关时用）。
     *
     * <p>只打断调度线程的 sleep，让它按最新的 {@code currentDelayMs} 重新排程，
     * 不改动任何动画状态 —— 因此不会引起视觉跳变。
     */
    public void requestWakeUp() {
        Thread s = scheduler;
        if (s != null) {
            s.interrupt();
        }
    }

    // ==================== 光标监听 ====================

    /**
     * 光标事件只用于"唤醒"高帧率。
     * 真正的目标点更新在 {@link #tick()} 中每帧进行，避免事件时序问题。
     */
    @Override
    public void caretPositionChanged(@NotNull CaretEvent event) {
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
            smoothedDt = ACTIVE_DELAY_MS / 1000.0;
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

        if (!initialized) {
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

        // 目标点刚变化：按运动方向重新分配四个角点的滞后名次
        // 平滑帧间隔：低帧率下 dt 波动大，直接用于计算动画时长会让弹簧的
        // 时间常数每帧跳变、运动不连续。
        //
        // 但必须取「平滑值」与「瞬时值」的较大者：平滑需要好几帧才收敛，
        // 而动画本身可能就只有 4~6 帧 —— 若直接用平滑值，
        // 开头几帧的补偿会严重不足，动画照样被 dt 吃掉（实测帧数反而更少）。
        smoothedDt += (dt - smoothedDt) * 0.25;
        double effectiveDt = Math.max(dt, smoothedDt);

        boolean anyAnimating;
        if (staticCaret) {
            // 控制台编辑器：只补画静止光标，不参与拖尾
            anyAnimating = tickStaticCaret();
        } else if (config.trailMode == NeovideConfig.TrailMode.AFTERIMAGE) {
            anyAnimating = tickAfterimage(now, moved);
        } else {
            anyAnimating = tickSpring(dt, effectiveDt);
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
        if (elapsed >= 1_000_000_000L) {
            LOG.info(String.format(
                    "neovide-cursor: fps=%.1f  frames=%d  window=%.0fms  maxGap=%.0fms  "
                            + "peakRepaintArea=%dpx  delay=%dms",
                    frameCount * 1_000_000_000.0 / elapsed,
                    frameCount,
                    elapsed / 1_000_000.0,
                    maxGapNanos / 1_000_000.0,
                    peakRepaintArea,
                    currentDelayMs));
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

    /** 计算四个角点的包围盒（含辉光余量），结果写入 out 以避免分配 */
    private void computeTrailBounds(Rectangle out) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (TrailCorner corner : corners) {
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
     * 重新读取光标位置并更新动画目标点。
     *
     * @return true 表示位置确实发生了变化
     */
    private boolean refreshTarget() {
        try {
            VisualPosition vp = editor.getCaretModel().getVisualPosition();

            // 快速路径：视觉位置没变就直接返回，跳过昂贵的坐标换算。
            // 这是高帧率下最关键的性能优化 —— 绝大多数帧其实光标根本没动。
            // forceResync 用于布局刚变化的情形：视觉位置可能没变，
            // 但像素坐标已经失效，必须强制重算。
            if (!forceResync && vp.line == lastVisualLine && vp.column == lastVisualColumn) {
                return false;
            }
            forceResync = false;

            Point p = editor.visualPositionToXY(vp);
            lastVisualLine = vp.line;
            lastVisualColumn = vp.column;

            double height = editor.getLineHeight();
            double width = config.cursorWidth;

            // 【字符移动量】供 {@link TrailCorner#jump} 判定「短跳」用。
            //
            // 直接用编辑器的原生字符偏移，而不是从像素反推字符宽度。
            // 这是 IntelliJ 平台的标准做法：offset 就是「第几个字符」，
            // 差值天然是「移动了几个字符」，与字体、字号、全角半角完全无关 ——
            // 中文、日文、韩文、阿拉伯文、emoji（代理对按 2 计）一视同仁。
            //
            // 早期版本曾用「像素 ÷ 字符宽度」反推，先后失败三次：
            // 写死 16px（字号 20px 的中文约 20px 直接超出）、
            // FontMetrics('m')（西文推进量，中文是 2 倍）、
            // 第0列→第1列实测（取的是行首字符，光标在中文区时仍会翻倍）。
            // 结果都是「英文丝滑、中文卡」。
            int charsMoved = -1;
            try {
                int offset = editor.getCaretModel().getOffset();
                if (lastCaretOffset >= 0) {
                    charsMoved = Math.abs(offset - lastCaretOffset);
                }
                lastCaretOffset = offset;
            } catch (Throwable ignored) {
                // 取不到就保持 -1，jump() 会退回兼容分支
            }
            for (TrailCorner corner : corners) {
                corner.setCharsMoved(charsMoved);
            }

            double newCenterX = p.x + width / 2.0;
            double newCenterY = p.y + height / 2.0;

            if (newCenterX == centerX && newCenterY == centerY && height == cursorHeight) {
                return false;
            }

            cursorWidth = width;
            cursorHeight = height;
            centerX = newCenterX;
            centerY = newCenterY;

            // 辉光半径与重绘余量随光标尺寸同步更新
            panel.updateGlowRadius(width, height);
            glowPadding = (int) Math.ceil(panel.getGlowWidth()) + 4;

            if (!initialized) {
                // 首次定位：四个角直接吸附到位，避免从 (0,0) 飞入
                for (TrailCorner corner : corners) {
                    corner.snapTo(cursorWidth, cursorHeight, centerX, centerY);
                }
                initialized = true;
                jumped = false;
            } else {
                jumped = true;
            }
            return true;

        } catch (Throwable t) {
            // 编辑器正在销毁或重绘中：本次跳过，下一帧会自动重试
            return false;
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
        for (TrailCorner corner : corners) {
            corner.snapTo(cursorWidth, cursorHeight, centerX, centerY);
        }

        // 不绘制辉光：控制台不需要，也省掉这部分重绘开销
        panel.setGlowStrength(0f);

        panel.updateCorners(
                corners[0].getCurrentX(), corners[0].getCurrentY(),
                corners[1].getCurrentX(), corners[1].getCurrentY(),
                corners[2].getCurrentX(), corners[2].getCurrentY(),
                corners[3].getCurrentX(), corners[3].getCurrentY());

        computeTrailBounds(currentBounds);
        submitRepaint();
        return false;
    }

    /** 弹簧模式的一帧：推动物理模拟并绘制四边形 */
    private boolean tickSpring(double dt, double effectiveDt) {
        if (jumped) {
            assignRanks(effectiveDt);
            jumped = false;
        }

        boolean anyAnimating = false;
        double maxOffset = 0;
        for (TrailCorner corner : corners) {
            if (corner.update(cursorWidth, cursorHeight, centerX, centerY, dt, false)) {
                anyAnimating = true;
            }
            double offset = corner.getOffsetMagnitude();
            if (offset > maxOffset) {
                maxOffset = offset;
            }
        }

        // 辉光只在拖尾展开时点亮 —— 静止的光标顶着一圈光晕并不好看，
        // 那是"运动中"才该有的效果。强度由最大偏移量映射，边界见 GLOW_FADE_*。
        panel.setGlowStrength((float) ((maxOffset - GLOW_FADE_START)
                / (GLOW_FADE_END - GLOW_FADE_START)));

        panel.updateCorners(
                corners[0].getCurrentX(), corners[0].getCurrentY(),
                corners[1].getCurrentX(), corners[1].getCurrentY(),
                corners[2].getCurrentX(), corners[2].getCurrentY(),
                corners[3].getCurrentX(), corners[3].getCurrentY());

        computeTrailBounds(currentBounds);
        submitRepaint();
        return anyAnimating;
    }

    /**
     * 残影模式的一帧：记录当前位置并绘制历史残影。
     *
     * <p>不涉及任何物理模拟或帧间插值 —— 位置直接来自每帧的真实采样，
     * 因此掉帧只会让残影稀疏，轨迹本身绝不失真。
     * 这正是它比弹簧模型更适合低帧率环境的原因。
     */
    private boolean tickAfterimage(long now, boolean moved) {
        afterimageTrail.record(centerX, centerY, cursorWidth, cursorHeight, now);

        List<AfterimageTrail.Ghost> visible = afterimageTrail.collectVisible(now);

        ghostBuffer.clear();

        // ---- 第一部分：残影尾巴 ----
        // 跳过与当前光标位置重合的采样点，否则会和下面那个常驻光标叠在一起、
        // 半透明颜色叠加导致该处明显偏深。
        for (AfterimageTrail.Ghost ghost : visible) {
            if (ghost.centerX == centerX && ghost.centerY == centerY) {
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
                (float) (centerX - cursorWidth / 2.0),
                (float) (centerY - cursorHeight / 2.0),
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

        for (TrailCorner corner : corners) {
            corner.snapTo(cursorWidth, cursorHeight, centerX, centerY);
        }

        panel.clearCorners();
        panel.refreshStyles();

        // 让下一帧强制重算位置，避免快速路径把旧状态固化
        forceResync = true;
        lastDirtyRegion.setBounds(0, 0, 0, 0);
        wakeUp();
    }

    /**
     * 按「运动方向对齐度」给四个角点名次。
     *
     * <p>对齐度越高说明该角越位于运动前方，名次越靠前（rank 越小），
     * 它在 {@link TrailCorner#jump} 中拿到的滞后系数越大 —— 于是前缘紧跟、
     * 后缘拖沓，矩形被拉长成拖尾形状。
     */
    private void assignRanks(double dt) {
        double[] alignment = new double[corners.length];
        for (int i = 0; i < corners.length; i++) {
            alignment[i] = corners[i].calculateDirectionAlignment(
                    cursorWidth, cursorHeight, centerX, centerY);
        }

        // 求名次：对齐度更高的角 rank 更小（更跟手）
        int[] ranks = new int[corners.length];
        for (int i = 0; i < corners.length; i++) {
            int rank = 0;
            for (int j = 0; j < corners.length; j++) {
                if (alignment[j] < alignment[i]) {
                    rank++;
                }
            }
            ranks[i] = rank;
        }

        for (int i = 0; i < corners.length; i++) {
            corners[i].jump(cursorWidth, cursorHeight, centerX, centerY, ranks[i]);
        }
    }

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

        Thread s = scheduler;
        if (s != null) {
            s.interrupt();
        }

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
}
