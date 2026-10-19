import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;

/**
 * 物理「逐位一致」parity harness（契约附件 A-1，由 verifier 落盘到 tools/parity/，逐行照抄、不得改格式）。
 *
 * <p>判定：基线源码与本批源码各跑一次，输出必须【字节完全相同】。
 * 输出只用 doubleToRawLongBits 的 16 进制，避免任何格式化误差。
 *
 * <p>只依赖 core 包（不依赖 IntelliJ 平台），因此可直接 javac 编译。
 */
public class PhysicsParity {

    static final double[][] REL = {
            {-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5},
    };

    /**
     * 固定的 dt 序列（秒），每步按 f 下标取：
     * 8ms（正常帧）×2 → 16.7ms → 33.3ms → 50ms（MAX_DT 边界）→ 120ms（&gt;RECOVERY_GAP，触发短路归位）。
     * 首帧恒为 8ms，保证每一步都能看到弹簧展开过程，同时覆盖极端 dt 边界。
     */
    static final double[] DTS = {
            8.0 / 1000, 8.0 / 1000, 16.7 / 1000, 33.3 / 1000, 50.0 / 1000, 0.12,
    };

    static void scenario(String name, NeovideConfig cfg,
                         double[][] path, int[] charsMoved) {
        System.out.println("### " + name);
        double w = cfg.cursorWidth;
        double h = 32;

        TrailCorner[] c = new TrailCorner[4];
        for (int i = 0; i < 4; i++) {
            c[i] = new TrailCorner(REL[i][0], REL[i][1], cfg);
        }

        // 初始吸附到第一个目标点
        for (TrailCorner t : c) {
            t.snapTo(w, h, path[0][0] + w / 2.0, path[0][1] + h / 2.0);
        }

        for (int step = 1; step < path.length; step++) {
            double cx = path[step][0] + w / 2.0;
            double cy = path[step][1] + h / 2.0;
            for (TrailCorner t : c) {
                t.setCharsMoved(charsMoved[step]);
            }
            // rank 0..3 各调一次（与 CaretAnimator.assignRanks 的调用语义一致）
            for (int r = 0; r < 4; r++) {
                c[r].jump(w, h, cx, cy, r);
            }
            for (int f = 0; f < DTS.length; f++) {
                boolean any = false;
                for (int r = 0; r < 4; r++) {
                    any |= c[r].update(w, h, cx, cy, DTS[f], false);
                }
                System.out.print("s" + step + "f" + f + " any=" + any);
                for (int r = 0; r < 4; r++) {
                    System.out.print(" x" + r + "=" + Long.toHexString(
                            Double.doubleToRawLongBits(c[r].getCurrentX())));
                    System.out.print(" y" + r + "=" + Long.toHexString(
                            Double.doubleToRawLongBits(c[r].getCurrentY())));
                    System.out.print(" o" + r + "=" + Long.toHexString(
                            Double.doubleToRawLongBits(c[r].getOffsetMagnitude())));
                }
                System.out.println();
            }
        }
    }

    public static void main(String[] args) {
        NeovideConfig cfg = new NeovideConfig();
        System.out.println("config: cursorWidth=" + cfg.cursorWidth
                + " animationLength=" + cfg.animationLength
                + " shortAnimationLength=" + cfg.shortAnimationLength
                + " trailSize=" + cfg.trailSize
                + " typingMaxChars=" + cfg.typingMaxChars
                + " trailFactors=" + java.util.Arrays.toString(cfg.trailFactors));

        // ① 同行逐字符（短跳分支）
        scenario("A_shortjump_typing", cfg,
                new double[][]{{0, 0}, {8, 0}, {16, 0}, {24, 0}, {32, 0}},
                new int[]{-1, 1, 1, 1, 1});

        // ② 删除中文（宽字符）：offset 差 1、像素差 20
        scenario("B_cjk_delete", cfg,
                new double[][]{{100, 0}, {80, 0}, {60, 0}, {40, 0}},
                new int[]{-1, 1, 1, 1});

        // ③ 跨行大跳（trailSize 三档分支）
        scenario("C_crossline_jump", cfg,
                new double[][]{{10, 10}, {10, 10 + 32 * 6}, {10, 10 + 32 * 12}},
                new int[]{-1, 40, 40});

        // ④ 退化：目标不变（长时间静止后再次 jump）
        scenario("D_static_repeat", cfg,
                new double[][]{{5, 5}, {5, 5}, {5, 5}},
                new int[]{-1, -1, -1});

        System.out.println("### end");
    }
}
