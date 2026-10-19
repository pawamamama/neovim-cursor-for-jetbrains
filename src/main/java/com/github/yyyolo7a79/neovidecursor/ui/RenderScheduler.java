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

    /** 已注册的 animator。迭代是弱一致的，足以支撑"重算最早到期时刻"这一用途 */
    private static final Set<CaretAnimator> ANIMATORS = ConcurrentHashMap.newKeySet();

    /** 共享调度线程；集合为空时退出并置回 null，下次 {@link #register} 时重建 */
    private static Thread thread;

    private RenderScheduler() {
    }

    /** 注册一个 animator，必要时创建共享调度线程 */
    static synchronized void register(CaretAnimator animator) {
        ANIMATORS.add(animator);
        if (thread == null) {
            Thread t = new Thread(RenderScheduler::loop, THREAD_NAME);
            t.setDaemon(true);
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
        if (t != null) {
            t.interrupt();
        }
    }

    private static void loop() {
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

            long now = System.nanoTime();
            long due = now + MAX_SLEEP_MS * 1_000_000L;
            for (CaretAnimator a : ANIMATORS) {
                long d = a.nextDueNanos;
                if (d < due) {
                    due = d;
                }
            }

            long sleepMs = (due - now) / 1_000_000L;
            if (sleepMs >= 1) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    // 被 wakeUp() / register / unregister 打断：立刻重算最早到期时刻，
                    // 不重新入睡 —— 与基线"interrupt 醒来后直接落到投递"的语义一致。
                    continue;
                }
            }

            // 睡醒（或本来就已到期）：投递本轮所有到期的 animator
            if (!dispatchDue()) {
                // 到期时刻已过，但那个 animator 的任务还在 EDT 队列里：退避后再看。
                try {
                    Thread.sleep(BUSY_RETRY_MS);
                } catch (InterruptedException ignored) {
                    // 忽略：下一轮重新计算
                }
            }
        }
    }

    /**
     * 对本轮所有已到期的 animator <b>逐个</b>投递 —— 每个 animator 一次 {@code invokeLater}。
     *
     * @return 本轮是否至少投递了一个任务（用于区分"已到期"与"到期但任务都在途"）
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
            posted = true;
            // 【EDT 任务粒度红线】这个 Runnable 体内只服务这一个 animator：
            // 不含 for / forEach，也不会去 tick 第二个 animator。
            SwingUtilities.invokeLater(animator::runTick);
        }
        return posted;
    }
}
