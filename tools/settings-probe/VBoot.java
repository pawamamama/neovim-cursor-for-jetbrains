/**
 * V-BOOT：启动 / 首用相关的守门探针（BUG-007）。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>2026-10-28 的首用诊断日志（`[clear]/[tick]`）实测出一个真缺陷：
 * {@code alwaysHighFps} 默认开启时，{@code tick()} 的「编辑器不可见」早退分支
 * <b>直接 return，没有改写 currentDelayNs</b> —— 于是该 animator 继续按
 * 6.94ms（144fps）被投递。5 个从未显示的编辑器各自刷了约 4880 次 not-showing，
 * 等于 <b>720 次 EDT 任务/秒的纯空转</b>。
 *
 * <p>修法：不可见时把间隔降到空闲档（{@code hiddenTierDelayNs}），并在切回可见
 * 的第一帧主动唤醒补帧。
 *
 * <h2>为什么不复刻算法</h2>
 *
 * <p>断言调用的是生产函数本身（经 {@code CaretAnimatorProbe} 转发），
 * 探针重构时不会与生产分家 —— 见 CLAUDE.md 陷阱 3。
 *
 * <p>运行：由 {@code build-v-probes.ps1} 的 {@code $mains} 驱动。
 */
public final class VBoot {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        System.out.println("=== V-BOOT：不可见编辑器必须降频（BUG-007）===");

        int activeMs = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimatorProbe.ACTIVE_DELAY_MS();
        long activeNs = activeMs * 1_000_000L;
        long hiddenNs = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimatorProbe.hiddenTierDelayNs(false);
        long visibleNs = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimatorProbe.hiddenTierDelayNs(true);

        System.out.println("  活跃档 = " + activeMs + "ms；不可见档 = " + (hiddenNs / 1_000_000L)
                + "ms；可见时返回值 = " + visibleNs);

        check("B-1 不可见编辑器必须返回正数间隔（不能退化成「交给原逻辑」）",
                hiddenNs > 0,
                hiddenNs + " ns");

        check("B-2 可见编辑器必须返回 -1（交给原有的自适应 / 常驻刷新逻辑，不得被本分支拦下）",
                visibleNs == -1L,
                String.valueOf(visibleNs));

        check("B-3 不可见档必须至少是活跃档的 10 倍（否则等于没降频）",
                hiddenNs >= activeNs * 10,
                hiddenNs + " ns vs 活跃档 " + activeNs + " ns");

        check("B-4 不可见档不得等于活跃档（常驻刷新下最容易回归成这里）",
                hiddenNs != activeNs,
                hiddenNs + " ns");

        check("B-5 不可见档必须是空闲档量级（>=120ms = IDLE_DELAY_MS）",
                hiddenNs >= 120L * 1_000_000L,
                hiddenNs + " ns");

        System.out.printf("=== V-BOOT 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-BOOT RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-BOOT RESULT: ALL PASS");
        System.exit(0);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + "  [" + detail + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  [" + detail + "]");
        }
    }

    private VBoot() {
    }
}
