/** 定位 240fps 的真实瓶颈：三种写法对比。 */
public final class BenchGranularity {
    interface Act { void run() throws Exception; }

    public static void main(String[] a) throws Exception {
        for (int round = 0; round < 2; round++) {
            System.out.println("--- 轮次 " + (round + 1) + " ---");
            run("A 裸 sleep(4)", () -> Thread.sleep(4));
            run("B sleep4+nanoTime", () -> { Thread.sleep(4); System.nanoTime(); });
            run("C deadline 循环", () -> sleepUntil(System.nanoTime() + 4_166_667L));
            run("D sleep(4)+忙等1ms", () -> {
                Thread.sleep(4);
                long d = System.nanoTime() + 1_000_000L;
                while (System.nanoTime() < d) Thread.onSpinWait();
            });
            System.out.println();
        }
    }

    private static void run(String name, Act r) throws Exception {
        long t0 = System.nanoTime();
        int done = 0;
        while (System.nanoTime() - t0 < 300_000_000L) {
            r.run();
            done++;
        }
        long el = System.nanoTime() - t0;
        System.out.printf("  %-22s %5d 次  %.1f fps%n", name, done, done * 1e9 / el);
    }

    private static void sleepUntil(long deadlineNs) throws Exception {
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