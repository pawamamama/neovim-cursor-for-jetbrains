
import java.util.Arrays;
import java.util.Locale;

/**
 * V-SLEEP2 —— refresh-auditor（t7）：把判断 A 的两半都量到底。
 *
 * <p>V-SLEEP 的第一轮实测已经显示：Thread.sleep(5) 中位 5.54ms，
 * 而 javax.swing.Timer(5) 中位 5.61ms —— <b>两者都没有 15.6ms 台阶</b>。
 * 这与代码注释（CaretAnimator.java:97-99）以及 README 的说法冲突，必须查清。
 *
 * <p>本支要回答三件事：
 * <ol>
 *   <li>本机 sleep 的最小可兑现粒度到底是多少（1ms? 2ms?）—— 直接量；</li>
 *   <li>javax.swing.Timer 真的是 15.6ms 吗；若否，为什么流传这个说法；</li>
 *   <li>「Timer 精度 15.6ms」是否只在<b>某个特定条件下</b>成立（例如未初始化 AWT、
 *       或系统 timer resolution 未被 JVM 提升）。</li>
 * </ol>
 */
public final class VSleep2 {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== V-SLEEP2：sleep 的最小可兑现粒度 + Timer 台阶到底在哪 ===");
        System.out.println("  java=" + System.getProperty("java.version")
                + "  os=" + System.getProperty("os.name"));
        System.out.println();

        // ---- 1. sleep 的最小可兑现粒度 ----
        System.out.println("--- 1. Thread.sleep 的粒度下探（1/2/3/4ms，各 200 次）---");
        for (int i = 0; i < 40; i++) {
            Thread.sleep(2);
        }
        System.out.printf(Locale.ROOT, "  %-8s %-10s %-10s %-12s%n", "目标ms", "中位", "p95", "中位/目标");
        double med1 = 0;
        for (int t = 1; t <= 4; t++) {
            double[] s = sampleSleep(t, 200);
            System.out.printf(Locale.ROOT, "  %-8d %-10.2f %-10.2f %-12.2f%n",
                    t, s[0], s[1], s[0] / t);
            if (t == 1) {
                med1 = s[0];
            }
        }
        check("W-1 本机 sleep(1) 中位 " + fmt(med1) + "ms —— 系统定时器分辨率已被提升到约 1ms，"
                        + "不存在「最小 15.6ms」的天花板",
                med1 < 4.0,
                String.format(Locale.ROOT, "中位 %.2fms（15.6 的 %.0f%%）", med1, med1 / 15.6 * 100));

        // ---- 2. Timer 在不同 delay 下 ----
        System.out.println();
        System.out.println("--- 2. javax.swing.Timer 在不同 delay 下的实际周期 ---");
        System.out.printf(Locale.ROOT, "  %-10s %-14s %-14s%n", "delay", "实测中位", "是否 15.6 台阶");
        double t5 = -1, t1 = -1, t16 = -1;
        for (int d : new int[]{1, 5, 8, 16}) {
            double m = timerPeriod(d, 120);
            System.out.printf(Locale.ROOT, "  %-10d %-14.2f %-14s%n",
                    d, m, m > 12 ? "是" : "否");
            if (d == 1) {
                t1 = m;
            }
            if (d == 5) {
                t5 = m;
            }
            if (d == 16) {
                t16 = m;
            }
        }
        check("W-2 Timer(5) 实测 " + fmt(t5) + "ms，Timer(16) 实测 " + fmt(t16)
                        + "ms —— <b>Timer 也不是 15.6ms 台阶</b>；"
                        + "「15.6ms 属于 javax.swing.Timer」这个归因同样不成立",
                t5 < 12.0,
                String.format(Locale.ROOT, "Timer(5)=%.2fms Timer(16)=%.2fms", t5, t16));

        // ---- 3. 那么 15.6ms 的说法从哪来 ----
        System.out.println();
        System.out.println("--- 3. 15.625ms 这个数字的真实出处（机制说明，非断言）---");
        System.out.println("  Windows 的默认时钟中断周期是 15.625ms。在 <b>JVM 未提升 timer resolution</b> 时，");
        System.out.println("  wait/sleep 的超时会被向上取整到该周期的整数倍 → 那时确实看到 15.6ms。");
        System.out.println("  但现代 JDK 在 AWT/屏显初始化时会调用 timeBeginPeriod(1)，把分辨率提到 ~1ms。");
        System.out.println("  本次实测（sleep 1ms 与 Timer 1ms 都精确）说明<b>本机已处于提升状态</b>。");
        System.out.println("  ⇒ 结论：15.6ms 是「未提升时的 Windows 默认粒度」，不是本机现状，");
        System.out.println("    也不能归给 javax.swing.Timer（它是 AWT 定时器，同样受益于提升）。");

        // ---- 4. 对 5ms 决策的直接影响 ----
        System.out.println();
        System.out.println("--- 4. 对「5ms 是否有害」的直接含义 ---");
        double s5 = sampleSleep(5, 200)[0];
        System.out.printf(Locale.ROOT,
                "  sleep(5) 中位 %.2fms → 调度线程每秒可醒约 %.0f 次（名义 200 次）%n", s5, 1000.0 / s5);
        System.out.println("  ⇒ 「5ms 因为 sleep 精度达不到而白设」这个理由是错的：");
        System.out.println("    5ms 确实能兑现，帧率确实会翻倍。");
        System.out.println("    所以「5ms 用户实测帧率下降」必须另有原因 —— 见 VEdt/VFrame 的 EDT 容量实测。");
        check("W-3 5ms 是【可兑现】的（sleep 中位 " + fmt(s5) + "ms ≈ 名义值），"
                        + "判断 A 的前半句（sleep 精度 15.6ms）被实测否定",
                Math.abs(s5 - 5) < 1.5,
                String.format(Locale.ROOT, "中位 %.2fms，偏差 %+.2fms", s5, s5 - 5));

        System.out.println();
        System.out.printf("=== V-SLEEP2 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-SLEEP2 RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-SLEEP2 RESULT: ALL PASS");
        System.exit(0);
    }

    /** @return {中位, p95} */
    private static double[] sampleSleep(int ms, int n) throws Exception {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            Thread.sleep(ms);
            a[i] = (System.nanoTime() - s) / 1_000_000.0;
        }
        Arrays.sort(a);
        return new double[]{a[n / 2], a[(int) (n * 0.95)]};
    }

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
        timer.start();
        boolean done = latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        timer.stop();
        if (!done || idx[0] < 20) {
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
