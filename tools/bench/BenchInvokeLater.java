import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 微基准：量化 SwingUtilities.invokeLater 的固定开销。
 *
 * <p>JFR 显示渲染线程的样本里 SunToolkit.getSystemEventQueueImplPP 占 29/203、
 * notifyEventDispatchThreadBusy 占 11/203 —— 这些都是每次 invokeLater 的固定成本。
 * 本基准测出单次成本，用于判断「125fps 下每秒投递 125 次」是否已经吃掉明显 CPU。
 *
 * <p>注意：8 个编辑器 = 8 个 animator = 每秒 8×125 = 1000 次投递。
 */
public final class BenchInvokeLater {
    public static void main(String[] a) throws Exception {
        // 先把 EDT 跑起来
        SwingUtilities.invokeAndWait(() -> { });

        int N = 2000;

        // ---- A：纯 invokeLater 空转（无 work）----
        long t0 = System.nanoTime();
        AtomicInteger done = new AtomicInteger();
        for (int i = 0; i < N; i++) {
            SwingUtilities.invokeLater(done::incrementAndGet);
        }
        while (done.get() < N) {
            Thread.sleep(1);
        }
        long t1 = System.nanoTime();
        System.out.printf("A 仅 invokeLater 空任务 : %.2f us/次（含 EDT 往返）%n",
                (t1 - t0) / 1000.0 / N);

        // ---- B：8 个 animator 的投递速率（模拟真实：每次 tick 结束再投一次）----
        int ANIM = 8;
        int FRAMES = 250;   // 每 animator 250 帧 = 2 秒 @125fps
        AtomicInteger cnt = new AtomicInteger();
        int total = ANIM * FRAMES;
        long t2 = System.nanoTime();
        for (int f = 0; f < FRAMES; f++) {
            for (int k = 0; k < ANIM; k++) {
                SwingUtilities.invokeLater(cnt::incrementAndGet);
            }
            Thread.sleep(8);   // 模拟 8ms 一帧的节拍
        }
        while (cnt.get() < total) {
            Thread.sleep(1);
        }
        long t3 = System.nanoTime();
        System.out.printf("B %d animator × %d 帧 = %d 次投递，总耗时 %.0f ms%n",
                ANIM, FRAMES, total, (t3 - t2) / 1_000_000.0);
        System.out.printf("  折算每次投递 %.2f us%n", (t3 - t2) / 1000.0 / total);
    }
}
