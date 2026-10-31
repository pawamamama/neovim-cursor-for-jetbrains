package com.github.yyyolo7a79.neovidecursor.ui;

import javax.swing.SwingUtilities;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局渲染调度器 —— 所有编辑器共用<b>一条</b>调度线程（Tier 1 / T1-1）。
 *
 * <p><b>为什么要改</b>：此前每个 {@link CaretAnimator} 各起一条 {@code Thread.sleep}
 * 调度线程，空闲时各自每 120~600ms 醒一次投递一帧；标签页 / 控制台一多，就是 N 条
 * 线程在空转（JFR 显示 {@code startScheduler} 占插件采样 90%，栈几乎都在 sleep 上）。
 * 现在改为全局一条线程，按各 animator 登记的 {@link CaretAnimator#nextDueNanos} 统一排程。
 *
 * <p><b>本类唯一要守住的语义（EDT 任务粒度）</b>：
 * <ul>
 *   <li>每个到期 animator <b>各自</b>投递一次 {@link SwingUtilities#invokeLater} ——
 *       N 个到期 animator = N 个 <b>独立</b>的 EDT 任务。任何"一个 EDT 任务里批量 tick
 *       多个 animator"的写法都是被<b>禁止</b>的（契约 P0-1）：那会改变 EDT 任务粒度与
 *       可插空性，正是用户在上游 issue #2 里指出的风险。</li>
 *   <li>防堆积标志是<b>每个 animator 自己</b>的（{@link CaretAnimator} 的
 *       {@code framePending}），不许用全局标志 —— 全局标志会让"一个 animator 还没画完"
 *       连带拖住其它 animator 的投递。</li>
 *   <li>到期判定只看该 animator 自己的三件事：是否到期、是否已有在途任务、是否已销毁。
 *       任何"某个 animator 未完成就让所有 animator 一起等"的逻辑都不允许。</li>
 * </ul>
 *
 * <p>仍用 {@code Thread.sleep} 而非 {@code javax.swing.Timer}：后者在 Windows 上精度只有
 * 15.6ms（约 64fps），见 README 踩坑记录。
 */
final class RenderScheduler {

    /** 调度线程名 —— 沿用基线命名，便于线程枚举取证：多编辑器下也只应看到 1 条 */
    private static final String THREAD_NAME = "neovide-cursor-render";

    /**
     * 【W3-1】所有 animator 都还远未到期时的最长睡眠（毫秒），兜底防止调度线程永久沉睡。
     * 量级取自基线失焦档 {@code IDLE_UNFOCUSED_DELAY_MS}。
     */
    private static final long MAX_SLEEP_MS = 600;

    /**
     * 【W3-3】到期判定容差（纳秒）：调度线程按毫秒取整睡眠，略早醒来也应视为到期，
     * 避免把到期时刻整体往后推。
     */
    private static final long DUE_TOLERANCE_NANOS = 1_000_000L;

    /**
     * 【W3-2】"睡眠时长不足 1ms 即立刻派发"的退避（毫秒）。
     *
     * <p>到期时刻已过、但该 animator 的任务还在 EDT 队列里（它自己的 {@code framePending}
     * 仍为 true）时，退避这么久再来看一眼，避免热自旋。退避期间其它 animator 不受影响 ——
     * 它们的到期判定在同一轮里已经各自做过。
     */
    private static final long BUSY_RETRY_MS = 1;

    // ==================== U-M-6：「到期但投不出去」的退避升级 ====================
    //
    // 原来 dispatchDue() 返回 false 时只有固定的 Thread.sleep(1ms)：
    // EDT 被长时间任务卡住（所有到期 animator 的 framePending 都为 true）时，
    // 调度线程 **1000 次/秒空转且零日志**，与异常路径的 consecutiveFailures 待遇不对等。

    /**
     * 连续「到期但投不出去」超过这个轮数才开始退避。
     *
     * <p>前 10 轮保持 1ms：EDT 上跑完一个稍长的 tick 会短暂让 dispatchDue() 返回 false，
     * 那是设计内的（不是故障）；立刻退避会平白拖慢帧率。连续 10 轮（≈10ms）才当故障。
     */
    private static final int BUSY_BACKOFF_START_ROUNDS = 10;

    /** 退避指数的最大位移：1 << 5 = 32ms。 */
    private static final int BUSY_BACKOFF_MAX_SHIFT = 5;

    /** 退避上限：32ms ≈ 60fps 两帧，EDT 恢复后最迟两帧内补投。 */
    private static final long BUSY_BACKOFF_CAP_MS = 32L;

    /** 「到期但投不出去」日志节流：首次必打，之后每 100 轮一条。 */
    private static final int BUSY_LOG_EVERY = 100;

    /**
     * 连续「到期但投不出去」轮数 → 本轮退避毫秒（U-M-6）。
     *
     * <p>曲线：1..10 轮 → 1ms；11 → 2；12 → 4；13 → 8；14 → 16；15 及以后 → 32。
     */
    static long busyBackoffMs(int consecutiveNotPosted) {
        if (consecutiveNotPosted <= BUSY_BACKOFF_START_ROUNDS) {
            return BUSY_RETRY_MS;
        }
        int shift = Math.min(consecutiveNotPosted - BUSY_BACKOFF_START_ROUNDS,
                BUSY_BACKOFF_MAX_SHIFT);
        return Math.min(BUSY_RETRY_MS << shift, BUSY_BACKOFF_CAP_MS);
    }

    /**
     * 「到期但投不出去」的日志节流（U-M-6）。
     *
     * <p><b>首条门槛是「开始退避」那一轮，不是第 1 轮</b>（java-reviewer M-1）：
     * {@code dispatchDue()} 返回 false 最常见的原因不是 EDT 卡死，而是「上一帧 tick 还没跑完」
     * —— 只要某帧 EDT 耗时超过帧间隔（GC / 建索引 / inlay 更新）就会命中。
     * 若第 1 轮就打 warn，IDE 繁忙时会按帧率刷屏（125~144 条/秒），
     * 新加的诊断手段自己变成噪音，与 {@code shouldLogFailure} 的严格限流也不对等。
     */
    static boolean shouldLogBusy(int consecutiveNotPosted) {
        return consecutiveNotPosted == BUSY_BACKOFF_START_ROUNDS
                || consecutiveNotPosted % BUSY_LOG_EVERY == 0;
    }

    /** 已注册的 animator。迭代是弱一致的，足以支撑"重算最早到期时刻"这一用途 */
    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(RenderScheduler.class);

    private static final Set<CaretAnimator> ANIMATORS = ConcurrentHashMap.newKeySet();

    /**
     * 共享调度线程；集合为空时退出并置回 null，下次 {@link #register} 时重建。
     *
     * <p><b>0.9.73 加 volatile</b>：{@link #unregister} / {@link #wakeUp} 在
     * <b>无锁</b>状态下读它，而写只发生在 {@link #register} 与 {@link #loop}
     * 的 synchronized 块里。没有 volatile 时，无锁读者可能读到陈旧值：
     * <ul>
     *   <li>{@code unregister} 读到旧引用 → 打断一个已经退出/替换的线程；</li>
     *   <li>{@code wakeUp} 读到 null → <b>跳过 interrupt</b>，于是「停手后第一次操作」
     *       要多等一个空闲周期（最长 600ms）才投递 —— 正是 T1-1 注释里要消除的那一拍。</li>
     * </ul>
     * 加 volatile 后写入立即对无锁读者可见，代价是每次读一次内存屏障（可忽略）。
     *
     * <p>注意：{@code CaretAnimator} 内部的 volatile 用法本来就是对的
     * （{@code nextDueNanos} / {@code framePending}），问题只在这一个字段。
     */
    private static volatile Thread thread;

    private RenderScheduler() {
    }

    /** 注册一个 animator，必要时创建共享调度线程 */
    static synchronized void register(CaretAnimator animator) {
        ANIMATORS.add(animator);
        if (thread == null) {
            Thread t = new Thread(RenderScheduler::loop, THREAD_NAME);
            t.setDaemon(true);
            installUncaughtHandler(t);
            thread = t;
            t.start();
        } else {
            // 让线程重算"最早到期时刻"：新 animator 的首次到期可能比当前这一觉更早。
            // 唤醒只触发重算，不会提前 tick 一个尚未到期的 animator ——
            // 是否投递只由该 animator 自己的 nextDueNanos 决定。
            thread.interrupt();
        }
    }

    /** 注销（{@link CaretAnimator#dispose()} 时调用）；集合空了调度线程自行退出 */
    static void unregister(CaretAnimator animator) {
        ANIMATORS.remove(animator);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * 唤醒：先把该 animator 置为「立即到期」，再打断共享线程的睡眠。
     *
     * <p><b>顺序不能反</b>（契约 T1-1 的红线）：若先打断、后置到期，线程可能在两者之间
     * 重新睡下，于是「停手后第一次操作」要多等一个空闲周期（最长 600ms）才投递 ——
     * 正是基线用 {@code interrupt()} 从 sleep 醒来后<b>直接</b>落到 {@code invokeLater}
     * 所避免的那一拍。本方法把这两步绑在一起，调用点无法写反。
     */
    static void wakeUp(CaretAnimator animator) {
        animator.nextDueNanos = System.nanoTime();
        Thread t = thread;
        boolean spawned = false;
        if (t == null) {
            // 【U-H-3】调度线程因「连续失败」或「未捕获异常」已经置 null 并退出。
            //
            // 原来这里只 interrupt 一个 null —— 等于「全体动画停摆，且没有任何自愈入口」：
            // 重建入口只有 register()，而它只在「打开一个新编辑器」时被调用。
            // 用户打字、点鼠标、切标签页全都救不回来。
            //
            // 现在在 register()/loop() 用的同一把类锁内就地重建，防止两条线程被同时创建；
            // 并且限定「集合非空」—— 集合空时不该留一条空转线程（退出条件仍是 ANIMATORS.isEmpty()）。
            // 死锁分析：锁内只做判空、new Thread、start()，不获取其他监视器，无嵌套 → 无环。
            //
            // 【覆盖边界，如实记录】这是「无锁读到 null」之后的补救。若旧线程恰好在本行读之后、
            // 进入锁之前置 null 并返回，本次唤醒既没重建也没兜住 —— 动画会停到**下一次**光标
            // 事件为止。窗口只有几条指令，且进入该路径的前提是「调度线程已连坏 3 轮」或
            // 未捕获异常（现实里几乎只有 EDT 关闭 / OOM 才可能）。闭环的最终保证由下一次事件提供。
            synchronized (RenderScheduler.class) {
                if (thread == null && !ANIMATORS.isEmpty()) {
                    Thread nt = new Thread(RenderScheduler::loop, THREAD_NAME);
                    nt.setDaemon(true);
                    installUncaughtHandler(nt);
                    thread = nt;
                    nt.start();
                    spawned = true;
                }
                t = thread;
            }
        }
        // 【L-1】刚新建的线程不需要 interrupt：它的 nextDueNanos 已被置为「现在」，
        // 第一轮循环会自己投递。反过来 interrupt 会给它留一个黏性中断标志，
        // 让第一轮的 1ms 退避立刻抛 InterruptedException（无害，但没有理由）。
        if (t != null && !spawned) {
            t.interrupt();
        }
    }

    /**
     * 给调度线程装未捕获异常处理器（U-H-4）。
     *
     * <p>{@link #loop()} 的循环体与 catch 体都已各自自保护，这里兜的是「两者都漏了」的第三扇门：
     * 线程在 run 边界抛出时 JVM 会先调本处理器。不处理的话线程死亡而 {@code thread} 仍非 null，
     * 此后每次 {@code register()} 都去 interrupt 一个死线程 —— 全体永久停摆，
     * 连「打开新编辑器」这个唯一恢复入口都失效。
     *
     * <p>处理器自身必须绝对不抛：若它在内部再抛，JVM 只会打印到 stderr，
     * 而 {@code thread} 已经置 null（置空动作在日志之前），恢复入口仍然有效。
     */
    private static void installUncaughtHandler(Thread t) {
        t.setUncaughtExceptionHandler((th, ex) -> {
            synchronized (RenderScheduler.class) {
                if (thread == th) {
                    thread = null;
                }
            }
            try {
                LOG.warn("neovide-cursor: 调度线程未捕获异常，已置空；下一次 wakeUp/register 将重建", ex);
            } catch (Throwable ignored) {
                // 日志子系统本身不可用：thread 已置空，恢复入口有效，放弃记录。
            }
        });
    }

    private static void loop() {
        // 【H-1 / U-H-3 配套】「连续失败」是**这一条线程**的状态，不能是 static。
        //
        // 原来是 static 字段，于是重建出来的新线程会继承上一代的计数：
        // T1 连坏 3 轮退出 → T2 由 wakeUp/register 建出来 → T2 只要遇到一次偶发异常
        // 就立刻 `>= 3` 再退出 —— 「自愈」后的线程只剩一条命。而类锁的 monitor
        // release/acquire 保证 T2 一定能读到 3，不是靠可见性侥幸能躲的。
        // 顺带：日志会报「连续 4 次」（其实是 T1 的 3 次 + T2 的 1 次），把排查方向带偏。
        int consecutiveFailures = 0;
        // 【U-M-6】连续「到期但投不出去」的轮数 —— 同样是线程级状态，不能 static（同 H-1 的理由）。
        int consecutiveNotPosted = 0;
        while (true) {
            // 【退出条件】已经没有 animator 了：退出并把 thread 置回 null，下次 register 重建。
            // 判空与 thread=null 必须与 register 共用同一把锁：否则会出现
            // "register 看到 thread != null 于是不重建" 与 "旧线程随即置 null 并退出"
            // 的时序打架，新 animator 就永远等不到 tick 了。
            synchronized (RenderScheduler.class) {
                if (ANIMATORS.isEmpty()) {
                    thread = null;
                    return;
                }
            }

            // ==================== 0.9.73：整个循环体包 try/catch ====================
            //
            // 【为什么必须有】原来 loop() 没有任何异常保护。而循环里会调
            // dispatchDue() → SwingUtilities.invokeLater()，后者在 EDT shutdown 期间
            // 可能抛异常。一旦抛出：
            //   · 线程死掉
            //   · 但 thread 字段仍非 null（只有正常退出路径才置 null）
            //   · 后续 register 全走 else 分支去 interrupt() 一个死线程
            //   · 结果：**所有动画永久停摆，且没有任何信号**
            //
            // 现在捕获后继续下一轮。连续失败时打印并主动置 thread=null，
            // 让下一次 register 重建线程（自愈）。
            try {
                long now = System.nanoTime();
                long due = now + MAX_SLEEP_MS * 1_000_000L;
                for (CaretAnimator a : ANIMATORS) {
                    long d = a.nextDueNanos;
                    if (d < due) {
                        due = d;
                    }
                }

                // 【0.9.76 亚毫秒调度】
                //
                // 原来只有 `Thread.sleep(sleepMs)`，其中 sleepMs 是整毫秒 ——
                // 于是帧率被钉死在整毫秒档位（8ms=125 / 6ms=166 / 5ms=200 / 4ms=250），
                // 165Hz 屏想要的 6.06ms 根本表达不出来。
                //
                // 现在：睡到「毫秒下界」，剩下的不足 1ms 部分用 nanoTime 忙等补齐。
                // 实测（BenchGranularity）：连续 sleep(4) 能到 221~223fps，
                // 说明系统粒度足够；忙等反而更差（180fps，因为占用调度时间片）。
                // 所以不足 1ms 时用 onSpinWait 短暂让出，而不是死循环空转。
                long remainNs = due - System.nanoTime();
                if (remainNs >= 1_000_000L) {
                    try {
                        Thread.sleep(remainNs / 1_000_000L);
                    } catch (InterruptedException e) {
                        // 被 wakeUp() / register / unregister 打断：立刻重算最早到期时刻，
                        // 不重新入睡 —— 与基线「interrupt 醒来后直接落到投递」的语义一致。
                        continue;
                    }
                } else if (remainNs > 0L) {
                    // 不足 1ms：不再「直接跳过睡眠立刻派发」（那会让帧率变成
                    // EDT 往返速度，反而抖），而是等到真正到期。
                    // 用 onSpinWait 而不是空转 —— 它会提示 CPU 进入低功耗等待状态。
                    //
                    // 【0.9.88 实测说明：本分支在 Windows 上极少命中】
                    //
                    // 800 帧采样（tools/bench 的调度探针）：sleep 命中 799 次、
                    // 本分支命中 **0 次**。原因是 Thread.sleep(N ms) 实际会睡
                    // N+1 个时钟粒度，醒来时通常已跨过 due，remainNs <= 0。
                    //
                    // 那为什么保留：① 它是正确性兜底 —— 时钟源抖动时仍需等到
                    // 真正到期，不能提前派发；② 在其他平台（时钟粒度更细）
                    // 会正常生效。
                    //
                    // 【重要】NEXT-STEPS 里「忙等 180fps 更差」那条实测，
                    // 测的是**独立的忙等循环**，不是这段代码 —— 本分支从未在生产中
                    // 执行过，所以那条数据与本分支无关。已在文档中更正。
                    while (System.nanoTime() < due) {
                        Thread.onSpinWait();
                    }
                }

                // 睡醒（或本来就已到期）：投递本轮所有到期的 animator
                if (!dispatchDue()) {
                    // 到期时刻已过，但那个 animator 的任务还在 EDT 队列里：退避后再看。
                    //
                    // 【U-M-6】原来这里恒 sleep(1ms)：EDT 被长任务卡住时，调度线程
                    // 1000 次/秒空转且零日志。现在连续 10 轮起指数退避到 32ms，
                    // 首次与每 100 轮各记一条 warn。判定与计数与 consecutiveFailures 同型
                    // （线程级局部变量），不碰异常自愈链路。
                    consecutiveNotPosted++;
                    long backoff = busyBackoffMs(consecutiveNotPosted);
                    if (shouldLogBusy(consecutiveNotPosted)) {
                        try {
                            LOG.warn("neovide-cursor: 调度到期但投递不出去（连续 "
                                    + consecutiveNotPosted + " 轮），EDT 可能被阻塞；本轮退避 "
                                    + backoff + " ms");
                        } catch (Throwable ignored) {
                            // 日志失败不能连累调度循环（同 U-H-4 原则）
                        }
                    }
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ignored) {
                        // 忽略：下一轮重新计算
                    }
                } else {
                    consecutiveNotPosted = 0;
                }
                consecutiveFailures = 0;
            } catch (Throwable t) {
                // 【U-M-6 / java-reviewer L-2】异常路径由 consecutiveFailures 负责，
                // 「到期但投不出去」的计数必须归零 —— 否则下一轮 false 会从大退避开始。
                consecutiveNotPosted = 0;
                consecutiveFailures++;
                if (handleLoopFailure(t, consecutiveFailures)) {
                    return;
                }
                // 短暂退避，避免异常时疯狂自旋占满 CPU。
                try {
                    Thread.sleep(BUSY_RETRY_MS);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    /**
     * 处理调度线程一轮失败：记日志 + 判定是否该退出（U-H-3 / U-H-4）。
     *
     * <p>从 {@link #loop()} 里抽出来，一是让循环体回到可读长度（本批把它从约 90 行推到 135 行），
     * 二是把「日志失败也不能打断状态判定」这条约束收在一个地方，避免以后有人再往里加代码时漏掉。
     *
     * @param t                   本轮异常
     * @param consecutiveFailures 本线程连续失败次数（调用方负责递增）
     * @return true 表示线程已判定不可靠并置空，调用方应退出循环
     */
    private static boolean handleLoopFailure(Throwable t, int consecutiveFailures) {
        // 【U-H-4】「处理异常的代码自己抛出」是同一事故的第二扇门：日志子系统
        // shutdown / LoggingFactory 被摘时 LOG.warn 可能抛，异常穿出 run 边界
        // → 线程死亡，而 thread 仍非 null（下面的 ≥3 分支根本到不了）→
        // 此后每次 register() 都去 interrupt 一个死线程，全体永久停摆。
        // 所以日志与会话状态判定分离：日志失败也必须走得到下面的分支。
        try {
            LOG.warn("neovide-cursor: 调度线程本轮异常（连续 " + consecutiveFailures + " 次）", t);
        } catch (Throwable ignored) {
            // 日志子系统本身不可用：放弃记录，但继续走状态判定。
        }
        if (consecutiveFailures < 3) {
            return false;
        }
        // 【U-H-3】退出前把每个现存 animator 的到期时间置为「现在」：
        // 重建后的第一轮遍历会立刻投递它们，而不是白等一个空闲周期（最长 600ms）。
        // （只置到期时间不会自己产生 tick —— 真正的恢复入口是 wakeUp()/register()
        //   里的线程重建，见 wakeUp 的注释。）
        for (CaretAnimator a : ANIMATORS) {
            a.nextDueNanos = System.nanoTime();
        }
        // 连续失败说明这个线程状态已不可靠：置 null，交给 wakeUp()/register() 重建。
        synchronized (RenderScheduler.class) {
            if (thread == Thread.currentThread()) {
                thread = null;
            }
        }
        try {
            LOG.warn("neovide-cursor: 调度线程连续失败 " + consecutiveFailures
                    + " 次，已置空；下一次 wakeUp/register 将重建");
        } catch (Throwable ignored) {
            // 同上：日志失败不能改变「已经置空」这个事实。
        }
        return true;
    }

    // 连续异常计数已改为 loop() 的局部变量（H-1）：它是线程级状态，
    // static 会让重建出来的新线程继承上一代的失败次数。

    /**
     * 对本轮所有已到期的 animator <b>逐个</b>投递 —— 每个 animator 一次 {@code invokeLater}。
     *
     * @return 本轮是否至少有一次投递<b>尝试</b>（用于区分"已到期"与"到期但任务都在途"）。
     *         U-M-5 起：`invokeLater` 抛出的那一次也计入「尝试过」—— 它的失败由 catch 内部
     *         回滚该 animator 自己的两个标志，不影响本轮其它 animator 的投递结论。
     */
    private static boolean dispatchDue() {
        long threshold = System.nanoTime() + DUE_TOLERANCE_NANOS;
        boolean posted = false;
        for (CaretAnimator animator : ANIMATORS) {
            if (animator.isDisposed()) {
                continue;
            }
            long due = animator.nextDueNanos;
            if (due > threshold) {
                continue;
            }
            // 这个到期值已经投递过、EDT 还没在 tick 结束时改写它 ⇒ 上一帧仍在执行中
            // （详见 CaretAnimator#lastDispatchedDueNanos）：跳过，保证每个到期周期
            // 至多一次 invokeLater，不让 EDT 繁忙时的长 tick 被反复投递。
            if (animator.lastDispatchedDueNanos == due) {
                continue;
            }
            // 防堆积：用的是该 animator 自己的标志（禁止全局标志）
            if (!animator.framePending.compareAndSet(false, true)) {
                continue;
            }
            animator.lastDispatchedDueNanos = due;
            // 【EDT 任务粒度红线】这个 Runnable 体内只服务这一个 animator：
            // 不含 for / forEach，也不会去 tick 第二个 animator。
            //
            // ============ 0.9.88 修复：invokeLater 抛出会导致永久活锁 ============
            //
            // 【死态】上面两行已经把 framePending=true 和 lastDispatchedDueNanos=due
            // 落地了。如果 invokeLater 抛异常（EDT 已关闭、内存不足、EDT 队列拒绝…），
            // 那个 Runnable 永远不会执行 → runTick 里的复位代码也永远不会跑。
            //
            // 于是这个 animator 同时满足两个「跳过」条件：
            //   :243  lastDispatchedDueNanos == due      -> 跳过
            //   :247  framePending 已经是 true           -> 跳过
            // **该光标彻底不动，而且没有任何日志** —— 循环级 catch(Throwable)
            // 也盖不住，因为循环本身没抛。
            //
            // 【修法】失败时把两个标志回滚到「未投递」状态，并记一行 warn。
            // 只在异常路径生效，正常路径零变化。
            try {
                SwingUtilities.invokeLater(animator::runTick);
                // 【M-2】成功投递一次即归零，与 refreshTarget / tick 的「连续失败」语义一致。
                animator.invokeLaterFailureCount = 0;
                // 【U-M-5 · MEDIUM-1】posted 只在**投递成功**时置位。
                //
                // 它同时是 loop() 的退避开关：`if (!dispatchDue()) Thread.sleep(BUSY_RETRY_MS)`。
                // 若把它当成「尝试过」（无论成败都 true），当 invokeLater **持续**抛时：
                //   · posted 恒 true → 跳过唯一的 1ms 退避；
                //   · 该 animator 的 nextDueNanos 早已过期（scheduleNextDue 写的是过去时刻）；
                //   · 于是下一轮立刻重投 → 再抛 → 100% CPU 紧循环；
                //   · 而异常在本 catch 里被吃掉，consecutiveFailures 不增 → 连 U-H-3 的自愈都到不了。
                // 语义取「本轮至少有一个**成功**投递」：某个 animator 失败不影响其它
                // animator 已投递的结论（U-M-5 的原意），但一个都没成功时 loop 照常退避。
                posted = true;
            } catch (Throwable t) {
                animator.framePending.set(false);
                animator.lastDispatchedDueNanos = Long.MIN_VALUE;
                // 【U-M-5】不回滚 posted：它此刻只会被**其它** animator 的成功投递置位，
                // 本 animator 的失败不该改写本轮的整体结论。
                // warn 洪水由本处按 animator 计数限流（原来每次都打，单编辑器约 125~144 行/秒，
                // 多编辑器线性放大；刷新失败时它会重试，见上一条注释的退避说明）。
                animator.invokeLaterFailureCount++;
                if (CaretAnimator.shouldLogFailure(animator.invokeLaterFailureCount)) {
                    try {
                        LOG.warn("neovide-cursor: invokeLater failed, rolled back dispatch flags "
                                + "(animator=" + System.identityHashCode(animator) + ")", t);
                    } catch (Throwable ignored) {
                        // 日志失败不能连累调度循环（同 U-H-4 原则）
                    }
                }
            }
        }
        return posted;
    }
}
