import java.util.concurrent.locks.LockSupport;

/**
 * 实测 Windows / JVM 的睡眠精度，判断 240fps 可行性。
 *
 * <p>背景（研究结论）：Windows 默认定时器一跳约 15.6ms，
 * 而 240fps 需要 4.17ms 周期。若 sleep 实际按 15.6ms 走，则 240fps 结构性不可达。
 * 本基准量化：不同睡眠时长的实际偏差，以及「短睡 + nanoTime 忙等」能否补足。
 */
public final class BenchSleepPrecision {
    public static void main(String[] a) throws Exception {
        System.out.println("=== 1. Thread.sleep 实际精度（每档 200 次）===");
        System.out.printf("%-10s %-12s %-12s %-12s%n", "请求ms", "实际均值", "实际p99", "超时量");
        for (int want : new int[]{1, 2, 4, 8, 16}) {
            long[] ds = new long[200];
            for (int i = 0; i < 200; i++) {
                long t0 = System.nanoTime();
                Thread.sleep(want);
                ds[i] = (System.nanoTime() - t0) / 1_000_000L;
            }
            java.util.Arrays.sort(ds);
            long sum = 0;
            for (long d : ds) sum += d;
            System.out.printf("%-10d %-12.2f %-12d %-12.2f%n",
                    want, sum / 200.0, ds[197], sum / 200.0 - want);
        }

        System.out.println();
        System.out.println("=== 2. LockSupport.parkNanos 精度 ===");
        System.out.printf("%-10s %-12s %-12s%n", "请求ms", "实际均值", "超时量");
        for (int want : new int[]{1, 2, 4, 8}) {
            long sum = 0;
            for (int i = 0; i < 200; i++) {
                long t0 = System.nanoTime();
                LockSupport.parkNanos(want * 1_000_000L);
                sum += (System.nanoTime() - t0) / 1_000_000L;
            }
            System.out.printf("%-10d %-12.2f %-12.2f%n", want, sum / 200.0, sum / 200.0 - want);
        }

        System.out.println();
        System.out.println("=== 3. 短睡 + 忙等（目标 4.17ms = 240fps）===");
        int n = 400;
        long[] ds = new long[n];
        long targetNs = 4_166_667L;
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            sleepUntil(t0 + targetNs);
            ds[i] = (System.nanoTime() - t0);
        }
        java.util.Arrays.sort(ds);
        long sum = 0;
        for (long d : ds) sum += d;
        System.out.printf("  均值 %.2fms  中位 %.2fms  p99 %.2fms  目标 4.17ms%n",
                sum / 1000.0 / n, ds[n / 2] / 1e6, ds[(int) (n * 0.99)] / 1e6);
        double avgMs = sum / 1000.0 / n;
        System.out.printf("  等效帧率 %.1f fps%n", 1000.0 / avgMs);
    }

    /** 先短睡到接近目标，剩余用 nanoTime 忙等（报告推荐的做法） */
    private static void sleepUntil(long deadlineNs) {
        long remain = deadlineNs - System.nanoTime();
        // 留 1.5ms 给忙等：睡眠的抖动通常不超过这个量级
        long parkNs = remain - 1_500_000L;
        if (parkNs > 0) {
            LockSupport.parkNanos(parkNs);
        }
        while (System.nanoTime() < deadlineNs) {
            Thread.onSpinWait();
        }
    }
}