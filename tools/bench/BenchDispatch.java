import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimatorProbe;

/** 验证调度器在亚毫秒精度下的实际投递间隔。 */
public final class BenchDispatch {
    public static void main(String[] a) throws Exception {
        System.out.println("ACTIVE_DELAY_MS = " + CaretAnimatorProbe.ACTIVE_DELAY_MS());
        System.out.println();
        System.out.printf("%-14s %-12s %-12s %-10s%n", "目标间隔", "实测均值", "p99", "等效fps");

        for (long targetNs : new long[]{
                8_000_000L,    // 125fps
                6_944_444L,    // 144fps
                6_060_606L,    // 165fps
                4_166_667L}) { // 240fps
            measure(targetNs);
        }
    }

    /** 复刻 RenderScheduler 的睡眠逻辑，测实际达成的间隔 */
    private static void measure(long targetNs) throws Exception {
        int n = 400;
        long[] ds = new long[n];
        long next = System.nanoTime();
        for (int i = 0; i < n; i++) {
            next += targetNs;
            long remain = next - System.nanoTime();
            if (remain >= 1_000_000L) {
                Thread.sleep(remain / 1_000_000L);
            } else if (remain > 0) {
                while (System.nanoTime() < next) Thread.onSpinWait();
            }
            ds[i] = System.nanoTime() - (next - targetNs);
        }
        long[] sorted = ds.clone();
        java.util.Arrays.sort(sorted);
        long sum = 0;
        for (long d : ds) sum += d;
        double avgMs = sum / 1e6 / n;
        System.out.printf("%-14s %-12s %-12s %-10.1f%n",
                String.format("%.2fms", targetNs / 1e6),
                String.format("%.2fms", avgMs),
                String.format("%.2fms", sorted[(int) (n * 0.99)] / 1e6),
                1000.0 / avgMs);
    }
}