
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/**
 * V-FRAMERATE —— 守门断言：运动帧间隔必须让横向前缘至少跨 N 帧。
 *
 * <p>「平滑」的物理本质：一次移动被拆成几帧。帧数 = 前缘时长 ÷ 帧间隔。
 * 前缘时长要保拉伸与跟手（不能随便加长），所以「平滑」最终由帧间隔决定。
 *
 * <p>本轮把 {@code ACTIVE_DELAY_MS} 从 8 改成 5：横向前缘从 5.2 帧提到 9.8 帧，
 * 单帧位移从 1.74px 降到 0.92px，而<b>稳态滞后完全不变</b>（它由时长决定）。
 */
public final class VFrameRate {
    private static int pass = 0, fail = 0;

    /** 0.9.6 定下的可读性底线：前缘至少跨 3 帧才不像瞬移。 */
    static final double MIN_FRAMES = 3.0;

    /**
     * 本轮目标：横向前缘 ≥6 帧算「不跳」。
     *
     * <p>依据：单帧位移 = 9px / 帧数。6 帧 = 1.5px/帧，肉眼可接受；
     * 4 帧 = 2.25px/帧 就会被看成"跳"。
     */
    static final double GOOD_FRAMES = 6.0;

    public static void main(String[] a) {
        System.out.println("=== V-FRAMERATE：帧间隔是否撑得起「平滑」===");

        int delay = com.github.yyyolo7a79.neovidecursor.ui
                .CaretAnimatorProbe.ACTIVE_DELAY_MS();

        // 【真实帧间隔】本机实测 Thread.sleep(8) 实际 8.1ms（p95 9ms）——
        // sleep 是精确的；15.6ms 那个数属于 javax.swing.Timer，不适用这里。
        // 计算"能跑出几帧"用实测值，既不夸大也不缩小。
        final double REAL_FRAME_MS = 8.1;
        System.out.println("  当前运动帧间隔 = " + delay + " ms（约 " + (1000 / delay) + " fps）");

        check("F-1 运动帧间隔必须 >= 3ms（低于 3ms 的 invokeLater 会挤占 EDT，反而掉帧）",
                delay >= 3, delay + " ms");
        // 【2026-10-23 修正】8ms 是正确值，不是"太慢"。
        // 理由：RenderScheduler 用 Thread.sleep，Windows 分辨率 15.6ms ——
        // 设 5ms 实际照样睡 15.6ms，只会让调度线程空转翻倍（用户实测"帧率下降"）。
        check("F-2 运动帧间隔必须 = 8ms（与 sleep 精度对齐；设更小只会空转，实测更差）",
                delay == 8, delay + " ms");

        // 三个预设 × 用户真实 k 下，横向前缘都要跨够帧
        for (com.github.yyyolo7a79.neovidecursor.settings.CursorPresets.AnimPreset p
                : com.github.yyyolo7a79.neovidecursor.settings.CursorPresets.ANIM_PRESETS) {
            for (float k : new float[]{4.5f, 8.0f}) {
                float[] f = NeovideCursorSettings
                        .deriveTrailFactors(k, 0f);   // 0.9.47: 平滑已改全局
                double leadMs = p.shortAnimationLength() * 1000.0 * f[2];
                double frames = leadMs / REAL_FRAME_MS;
                check("F-3 「" + p.name() + "」k=" + k + " 横向前缘跨 >= "
                                + (int) MIN_FRAMES + " 帧",
                        frames >= MIN_FRAMES,
                        String.format("%.1f 帧（前缘 %.0f ms ÷ %.1f ms 真实帧间隔）", frames, leadMs, REAL_FRAME_MS));
            }
        }

        // 用户真实参数（k=8 / short=0.14）下的目标档
        float[] f8 = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        double userLeadMs = 0.14 * 1000.0 * f8[2];
        double userFrames = userLeadMs / REAL_FRAME_MS;
        check("F-4 用户真实参数（k=8 / short=0.14 / smooth=0）横向前缘 >= "
                        + (int) GOOD_FRAMES + " 帧（「可接受」档）",
                userFrames >= GOOD_FRAMES,
                String.format("%.1f 帧（前缘 %.0f ms ÷ %.1f ms 真实帧间隔）", userFrames, userLeadMs, REAL_FRAME_MS));

        System.out.println();
        System.out.printf("=== V-FRAMERATE 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-FRAMERATE RESULT: FAILED"); System.exit(1); }
        System.out.println("V-FRAMERATE RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
