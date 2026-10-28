package com.github.yyyolo7a79.neovidecursor.ui;

import javax.swing.SwingUtilities;
import java.util.Map;

/**
 * 【BUG-007】EDT 看门狗 —— 抓住「谁把 EDT 占住了」。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>首用诊断日志已经把问题定位到「EDT 被占住约 390ms」（`dispatchLag=385.600ms`
 * 而我们的 `cost` 只有几百微秒），但<b>无法回答是谁占的</b>：
 *
 * <ul>
 *   <li>{@code idea.log} 里同期的记录只有 {@code checkCancelled is invoked while
 *       write-action is pending} 和 IDEA 自己的降速告警，都不点名；</li>
 *   <li>关掉「本地 Full Line 补全」后停顿仍是 385ms —— 已排除该嫌疑。</li>
 * </ul>
 *
 * <p>本类用最直接的办法：后台线程每 50ms 往 EDT 投一个空任务并计时。
 * 一旦发现投递到执行隔了 >200ms，就抓一次 {@code AWT-EventQueue-0} 的调用栈
 * —— <b>阻塞者的类名和方法名会直接出现在栈里</b>。
 *
 * <h2>顺带区分「EDT 被某个方法占住」与「JVM 全局暂停（GC）」</h2>
 *
 * <p>抓栈本身要走到全局安全点：如果 {@code getAllStackTraces()} 自己也被卡了
 * 差不多久，说明当时整个 JVM 停了（GC / 安全点），而不是某个方法在跑。
 * 所以日志里同时记 {@code waited}（EDT 等了多久）与 {@code stackCost}（抓栈花了多久）：
 *
 * <pre>
 *   waited≈390ms, stackCost≈0ms   -> EDT 被某个方法占住，栈里就是真凶
 *   waited≈390ms, stackCost≈390ms -> JVM 全局暂停（GC），栈不可信
 * </pre>
 *
 * <h2>成本与边界</h2>
 *
 * <p>只在 {@code diagLogging} 打开时启动；每 50ms 一次 {@code invokeLater}（20 次/秒，
 * 空 Runnable，代价可忽略）；一次运行最多输出 {@link #MAX_DUMPS} 次、每次最多
 * {@link #MAX_FRAMES} 层栈，然后线程自行退出 —— 不会无限刷日志。
 */
final class EdtWatchdog {

    /** 触发抓栈的阈值：正常帧约 7ms，200ms 已是肉眼可见的卡顿。 */
    private static final long BLOCK_THRESHOLD_NANOS = 200_000_000L;

    /**
     * 一次运行最多抓几次（抓够就停，避免日志被淹没）。
     *
     * <p>取 20 而不是 6：启动期本身就有若干 100~200ms 的帧，配额太小会被启动噪声
     * 吃光，用户按键那一刻反而没配额了。20 行日志的代价可以忽略。
     */
    private static final int MAX_DUMPS = 20;

    /** 每次最多记多少层栈。 */
    private static final int MAX_FRAMES = 14;

    /** EDT 的线程名（Swing 固定命名；取不到时退回"第一个 AWT-EventQueue"）。 */
    private static final String EDT_NAME = "AWT-EventQueue-0";

    private static volatile boolean started;

    private EdtWatchdog() {
    }

    /** 幂等启动（每个 JVM 只起一条线程）。 */
    static void start() {
        if (started) {
            return;
        }
        synchronized (EdtWatchdog.class) {
            if (started) {
                return;
            }
            started = true;
        }
        Thread t = new Thread(EdtWatchdog::loop, "neovide-cursor-edt-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        int dumps = 0;
        while (dumps < MAX_DUMPS) {
            final java.util.concurrent.atomic.AtomicBoolean ran =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            long postedAt = System.nanoTime();
            try {
                SwingUtilities.invokeLater(() -> ran.set(true));
            } catch (Throwable t) {
                return; // EDT 已关闭：没有继续的意义
            }

            // 【必须在阻塞【期间】抓，不能等它跑完再抓】
            //
            // 第一版是先等 ping 执行、再判断耗时 —— 自检实测抓到的栈是
            // `EventQueue.getNextEvent`（EDT 已经空闲），完全没用：
            // waited=851ms 但真凶早就干完走了。正确的做法是发现超时立刻抓，
            // 那一刻 EDT 还停在阻塞者的方法里。
            boolean dumped = false;
            long deadline = postedAt + 2_000_000_000L;
            while (!ran.get() && System.nanoTime() < deadline) {
                long waited = System.nanoTime() - postedAt;
                if (!dumped && waited > BLOCK_THRESHOLD_NANOS) {
                    dumped = true;
                    dumps++;
                    dump(waited);
                }
                sleepQuietly(5);
            }
            sleepQuietly(50);
        }
    }

    /** 抓一次 EDT 的栈并写一行日志。 */
    private static void dump(long waitedNs) {
        long t0 = System.nanoTime();
        String frames;
        try {
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            long stackCost = System.nanoTime() - t0;
            frames = edtFrames(all);
            CursorDiag.edtBlocked(waitedNs, stackCost, frames);
        } catch (Throwable t) {
            CursorDiag.edtBlocked(waitedNs, -1L, "抓栈失败: " + t.getClass().getSimpleName());
        }
    }

    /** 从全量栈里挑出 EDT 的那份，拼成一行（用 {@code <-} 连接调用链）。 */
    private static String edtFrames(Map<Thread, StackTraceElement[]> all) {
        Thread edt = null;
        for (Thread th : all.keySet()) {
            if (EDT_NAME.equals(th.getName())) {
                edt = th;
                break;
            }
        }
        if (edt == null) {
            for (Thread th : all.keySet()) {
                if (th.getName() != null && th.getName().startsWith("AWT-EventQueue")) {
                    edt = th;
                    break;
                }
            }
        }
        if (edt == null) {
            return "未找到 AWT-EventQueue 线程";
        }
        StackTraceElement[] st = all.get(edt);
        if (st == null || st.length == 0) {
            return "EDT 栈为空（可能正处于安全点/JVM 暂停）";
        }
        StringBuilder sb = new StringBuilder(edt.getName());
        int n = Math.min(st.length, MAX_FRAMES);
        for (int i = 0; i < n; i++) {
            sb.append(" <- ").append(st[i].getClassName()).append('.').append(st[i].getMethodName());
        }
        if (st.length > n) {
            sb.append(" <- …(共 ").append(st.length).append(" 层)");
        }
        return sb.toString();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
