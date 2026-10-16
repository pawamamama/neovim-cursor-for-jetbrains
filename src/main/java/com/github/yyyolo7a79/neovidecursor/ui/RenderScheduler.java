package com.github.yyyolo7a79.neovidecursor.ui;

import javax.swing.SwingUtilities;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局渲染调度器 —— 所有编辑器共用<b>一条</b>调度线程。
 *
 * <p>此前每个 {@link CaretAnimator} 各开一条线程，空闲时各自每 120~600ms
 * 往 EDT 投递一次任务：标签页、控制台一多，就是一堆线程在空转，
 * EDT 队列里也挤满了互相独立的小任务。现在改为：
 * <ul>
 *   <li>每个 animator 只登记「下一次希望被 tick 的时刻」{@link CaretAnimator#nextDueNanos}</li>
 *   <li>调度线程睡到所有 animator 中最早的到期时刻，然后<b>只投递一次</b> invokeLater，
 *       在同一个 EDT 任务里依次 tick 所有已到期的 animator</li>
 * </ul>
 *
 * <p>仍用 {@code Thread.sleep} 而非 {@code javax.swing.Timer}：
 * 后者在 Windows 上精度只有 15.6ms（约 64fps），见 README 踩坑记录。
 */
final class RenderScheduler {

    /** 无任何到期任务时的最长睡眠（毫秒），兜底防止线程永久沉睡 */
    private static final long MAX_SLEEP_MS = 600;

    private static final Set<CaretAnimator> ANIMATORS = ConcurrentHashMap.newKeySet();

    /** 上一批 tick 尚未在 EDT 上执行完时，跳过本次投递，避免任务堆积 */
    private static final AtomicBoolean FRAME_PENDING = new AtomicBoolean(false);

    private static Thread thread;

    private RenderScheduler() {
    }

    static synchronized void register(CaretAnimator animator) {
        ANIMATORS.add(animator);
        if (thread == null) {
            thread = new Thread(RenderScheduler::loop, "neovide-cursor-render");
            thread.setDaemon(true);
            thread.start();
        }
        wakeUp();
    }

    static void unregister(CaretAnimator animator) {
        ANIMATORS.remove(animator);
    }

    /** 打断调度线程的睡眠，让新到期的任务立即得到执行 */
    static void wakeUp() {
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private static void loop() {
        while (true) {
            synchronized (RenderScheduler.class) {
                // 所有编辑器都关闭了：线程退出，下次 register 时再重建
                if (ANIMATORS.isEmpty()) {
                    thread = null;
                    return;
                }
            }

            long now = System.nanoTime();
            long due = now + MAX_SLEEP_MS * 1_000_000L;
            for (CaretAnimator a : ANIMATORS) {
                due = Math.min(due, a.nextDueNanos);
            }

            long sleepMs = (due - now) / 1_000_000L;
            if (sleepMs >= 1) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    // 被 wakeUp() 唤醒：重新计算最早到期时刻
                    continue;
                }
            }

            if (!FRAME_PENDING.compareAndSet(false, true)) {
                // EDT 还没处理完上一批：稍等再试，不重复投递
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ignored) {
                    // 忽略
                }
                continue;
            }

            SwingUtilities.invokeLater(RenderScheduler::runDue);
        }
    }

    /** EDT 上执行：tick 所有已到期的 animator */
    private static void runDue() {
        FRAME_PENDING.set(false);
        // 留 1ms 容差：调度线程按毫秒取整睡眠，略早醒来时也应视为到期
        long threshold = System.nanoTime() + 1_000_000L;
        for (CaretAnimator a : ANIMATORS) {
            if (a.nextDueNanos <= threshold) {
                a.runTick();
            }
        }
    }
}
