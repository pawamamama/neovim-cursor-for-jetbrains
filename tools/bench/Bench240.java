/**
 * 重测 240fps 可达性：用 Thread.sleep 而非 parkNanos。
 *
 * <p>第一版用 LockSupport.parkNanos 得到 15.6ms —— 那是 parkNanos 自身的问题
 * （实测 parkNanos(4ms) 实际 8.65ms），不是 Windows 定时器的问题。
 * Thread.sleep(4) 实测 4.09ms 很准。
 */
public final class Bench240 {
    public static void main(String[] a) throws Exception {
        for (int fps : new int[]{125, 144, 165, 240}) {
            long targetNs = 1_000_000_000L / fps;
            double targetMs = targetNs / 1e6;
            int n = 500;
            long[] ds = new long[n];
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                sleepUntil(t0 + targetNs);
                ds[i] = System.nanoTime() - t0;
            }
            java.util.Arrays.sort(ds);
            long sum = 0;
            for (long d : ds) sum += d;
            double avgMs = sum / 1e6 / n;
            System.out.printf("%3d fps (目标 %.2fms):  均值 %.2fms  中位 %.2fms  p99 %.2fms  实测 %.1f fps%n",
                    fps, targetMs, avgMs, ds[n / 2] / 1e6, ds[(int) (n * 0.99)] / 1e6,
                    1000.0 / avgMs);
        }
        System.out.println();
        System.out.println("=== 纯 Thread.sleep 是否能做到 4ms ===");
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            int done = 0;
            while (System.nanoTime() - t0 < 200_000_000L) {
                Thread.sleep(4);
                done++;
            }
            long el = System.nanoTime() - t0;
            System.out.printf("  200ms 内 sleep(4) 跑了 %d 次 -> %.1f fps%n",
                    done, done * 1e9 / el);
        }
    }

    /** Thread.sleep 到接近目标，最后不足 1ms 的部分忙等 */
    private static void sleepUntil(long deadlineNs) throws InterruptedException {
        while (true) {
            long remain = deadlineNs - System.nanoTime();
            if (remain <= 0) return;
            long ms = remain / 1_000_000L;
            if (ms >= 1) {
                Thread.sleep(ms);
            } else {
                Thread.onSpinWait();
            }
        }
    }
}