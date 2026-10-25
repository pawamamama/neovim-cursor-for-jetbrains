/**
 * 验证 timeBeginPeriod(1) 能否把高帧率拉起来。
 *
 * <p>研究报告指出：微软文档写明只有调 timeBeginPeriod 才保证更高分辨率。
 * 本基准对比「调用前 / 调用后」的连续 sleep(4) 帧率。
 *
 * <p>注意：这需要 JNA 或 native 调用。这里用 PowerShell 的 Add-Type 走 Win32，
 * 或者退而求其次 —— 先测 JVM 自身有没有办法。
 */
public final class BenchTimerRes {
    public static void main(String[] a) throws Exception {
        System.out.println("=== 调用前 ===");
        measure();

        boolean ok = false;
        try {
            // 通过 winmm 的 timeBeginPeriod（JNA 不在依赖里，用反射试 JNA）
            Class<?> nc = Class.forName("com.sun.jna.Native");
            ok = nc != null;
            System.out.println("  JNA 可用");
        } catch (Throwable t) {
            System.out.println("  JNA 不可用：" + t.getClass().getSimpleName());
        }

        if (!ok) {
            System.out.println();
            System.out.println("=== 结论 ===");
            System.out.println("  需要 native 调 timeBeginPeriod。JVM 无内置 API。");
            System.out.println("  可选落地方式：");
            System.out.println("    1. 依赖 JNA/JNI 调 winmm.timeBeginPeriod(1)  + 程序退出时 timeEndPeriod(1)");
            System.out.println("    2. 接受 144fps 上限（实测可达），不做 240");
            System.out.println("    3. 短睡 + 忙等（会吃 CPU 一个核，笔记本上有功耗代价）");
        }
    }

    private static void measure() throws Exception {
        for (int round = 0; round < 3; round++) {
            long t0 = System.nanoTime();
            int done = 0;
            while (System.nanoTime() - t0 < 300_000_000L) {
                Thread.sleep(4);
                done++;
            }
            long el = System.nanoTime() - t0;
            System.out.printf("  300ms 内 sleep(4) %d 次 -> %.1f fps%n", done, done * 1e9 / el);
        }
    }
}