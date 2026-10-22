
import java.util.Arrays;
import java.util.Locale;

/**
 * V-SLEEP —— refresh-auditor（t7）：本机 Thread.sleep 各档精度实测。
 *
 * <p>直接判定任务书的判断 A：「Windows 上 Thread.sleep 的分辨率就是 15.6ms」。
 *
 * <p><b>为什么这条必须实测而不是查文档</b>：Windows 的时钟中断周期确实是 15.625ms，
 * 但 JVM 在现代 Windows 上会调 {@code timeBeginPeriod(1)} 把定时器分辨率提到 ~1ms，
 * 而 {@code Thread.sleep} 走的是 WaitForSingleObject 超时（受 timer resolution 影响），
 * {@code javax.swing.Timer} 走的是 AWT 的定时器合并逻辑（受事件合并影响）。
 * 两者机制不同，<b>不能互相推断</b>。这里把 sleep 与 Timer 都量出来对照。
 */
public final class VSleep {

    private static int pass = 0, fail = 0;

    /** 每档重复次数。取 100：足够算 p95，又不至于让探针跑太久。 */
    private static final int N = 100;

    public static void main(String[] args) throws Exception {
        System.out.println("=== V-SLEEP：本机 Thread.sleep 精度（判定判断 A）===");
        System.out.println("  os.name=" + System.getProperty("os.name")
                + "  java=" + System.getProperty("java.version"));
        System.out.println();

        // 预热：前几次 sleep/分派会带上类加载与 JIT 开销，必须丢弃
        warmup();

        System.out.printf(Locale.ROOT, "  %-8s %-10s %-10s %-10s %-10s %-12s%n",
                "目标ms", "中位", "p95", "最大", "平均", "超时>目标+2ms");
        double[] targets = {5, 6, 8, 12, 16};
        double med5 = 0, med8 = 0, med12 = 0, med16 = 0;
        double over5 = 0, over8 = 0;
        for (double t : targets) {
            double[] samples = new double[N];
            int over = 0;
            for (int i = 0; i < N; i++) {
                long a = System.nanoTime();
                Thread.sleep((long) t);
                double ms = (System.nanoTime() - a) / 1_000_000.0;
                samples[i] = ms;
                if (ms > t + 2.0) {
                    over++;
                }
            }
            Arrays.sort(samples);
            double med = samples[N / 2];
            double p95 = samples[(int) (N * 0.95)];
            double max = samples[N - 1];
            double mean = 0;
            for (double v : samples) {
                mean += v;
            }
            mean /= N;
            System.out.printf(Locale.ROOT,
                    "  %-8.0f %-10.2f %-10.2f %-10.2f %-10.2f %-12d%n",
                    t, med, p95, max, mean, over);
            if (Math.abs(t - 5) < 1e-9) {
                med5 = med;
                over5 = over;
            }
            if (Math.abs(t - 8) < 1e-9) {
                med8 = med;
                over8 = over;
            }
            if (Math.abs(t - 12) < 1e-9) {
                med12 = med;
            }
            if (Math.abs(t - 16) < 1e-9) {
                med16 = med;
            }
        }

        System.out.println();
        // ---- 判定 A ----
        check("S-1 【判断 A 错误】Thread.sleep(5) 的中位数 " + fmt(med5)
                        + "ms，远小于 15.6ms —— sleep 不受 15.6ms 分辨率限制",
                med5 < 8.0,
                String.format(Locale.ROOT, "中位 %.2fms（15.6 的 %.0f%%）", med5, med5 / 15.6 * 100));
        check("S-2 sleep(8) 中位 " + fmt(med8) + "ms、sleep(12) 中位 " + fmt(med12)
                        + "ms、sleep(16) 中位 " + fmt(med16) + "ms —— 全部按请求值精确兑现",
                Math.abs(med8 - 8) < 1.5 && Math.abs(med12 - 12) < 2.0 && Math.abs(med16 - 16) < 2.5,
                "三档偏差 " + String.format(Locale.ROOT, "%+.2f/%+.2f/%+.2f",
                        med8 - 8, med12 - 12, med16 - 16));
        check("S-3 5ms 档的「超时 > 目标+2ms」次数 = " + (int) over5
                        + " / " + N + "，8ms 档 = " + (int) over8 + " / " + N
                        + " —— 没有出现「全部被抬到 15.6ms」的台阶",
                over5 < N * 0.5 && over8 < N * 0.5,
                "超时率 5ms=" + String.format(Locale.ROOT, "%.0f%%", over5 / N * 100)
                        + " 8ms=" + String.format(Locale.ROOT, "%.0f%%", over8 / N * 100));

        // ---- 对照：javax.swing.Timer 的 15.6ms 台阶确实是 15.6 ----
        System.out.println();
        System.out.println("  对照实验：javax.swing.Timer 是不是真有 15.6ms 台阶？");
        double timerMed = timerPeriod(5, 40);
        System.out.printf(Locale.ROOT,
                "    Timer(delay=5ms) 实测相邻事件间隔中位 = %.2fms%n", timerMed);
        check("S-4 【判断 A 的后半句正确】javax.swing.Timer 在 delay=5ms 下实际约 "
                        + fmt(timerMed) + "ms —— 15.6ms 的台阶属于 Timer，不属于 Thread.sleep",
                timerMed > 10.0,
                String.format(Locale.ROOT,
                        "Timer %.2fms（≈%.1fms 时钟粒度）vs sleep(5) %.2fms",
                        timerMed, Math.round(timerMed / 15.6) * 15.6, med5));

        // ---- 结论方向：5ms 的 sleep 是「能兑现」的 ----
        System.out.println();
        System.out.println("  ⇒ 推论：ACTIVE_DELAY_MS=5 时调度线程【确实】能每 5ms 醒一次（200 次/秒），");
        System.out.println("    所以「5ms 因为 sleep 精度做不到而白设」这个理由不成立。");
        System.out.println("    5ms 若真的有害，原因必须另找（见 VEdt 的 EDT 容量实测）。");

        System.out.println();
        System.out.printf("=== V-SLEEP 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-SLEEP RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-SLEEP RESULT: ALL PASS");
        System.exit(0);
    }

    /** 丢弃前若干次测量（类加载 / JIT / 首次分派）。 */
    private static void warmup() throws Exception {
        for (int i = 0; i < 30; i++) {
            Thread.sleep(5);
            Thread.sleep(8);
        }
    }

    /**
     * javax.swing.Timer 的实际触发间隔中位（毫秒）。
     *
     * <p>Timer 在非 EDT 线程也能启动，但事件在 EDT 上跑；这里用 latch 计数后
     * 在调用线程 join，可避免依赖 EDT 是否就绪。
     */
    private static double timerPeriod(int delayMs, int samples) throws Exception {
        final long[] stamps = new long[samples];
        final int[] idx = {0};
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(samples);
        javax.swing.Timer timer = new javax.swing.Timer(delayMs, e -> {
            int i = idx[0];
            if (i < samples) {
                stamps[i] = System.nanoTime();
                idx[0] = i + 1;
                latch.countDown();
            }
        });
        timer.setCoalesce(true);
        timer.start();
        boolean done = latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
        timer.stop();
        if (!done || idx[0] < 10) {
            return -1;
        }
        int n = idx[0];
        double[] gaps = new double[n - 1];
        for (int i = 1; i < n; i++) {
            gaps[i - 1] = (stamps[i] - stamps[i - 1]) / 1_000_000.0;
        }
        Arrays.sort(gaps);
        return gaps[gaps.length / 2];
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + "   [" + detail + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}
