import com.github.yyyolo7a79.neovidecursor.core.DampedSpring;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「零观感优化·批次1」独立 parity harness（t4 verifier 自建，与契约附录 A 的两份 harness 互补）。
 *
 * <p><b>目的</b>：用同一输入序列驱动四个角点，逐帧打印
 * 「四个角点坐标 + 弹簧状态（position / velocity）+ 是否仍在动画」，
 * 供基线源码（git 321b0a0）与本批工作区源码分别编译运行后做**逐字节**比对。
 *
 * <p><b>只依赖 core 包</b>（{@link NeovideConfig} / {@link TrailCorner} / {@link DampedSpring}），
 * 不依赖任何 IntelliJ 平台类，因此可脱离 IDEA 直接 javac + java（headless 亦可）。
 *
 * <p><b>覆盖的输入序列</b>（acceptance 要求的六项）：
 * <ol>
 *   <li>S1_single_char —— 同行输入 1 个字符（短跳分支）</li>
 *   <li>S2_backspace —— 同行退格（反向位移，验证 jump() 里的速度归零）</li>
 *   <li>S3_three_chars —— 同一行连续 3 个字符（连续短跳，帧间不归位）</li>
 *   <li>S4_cross_line —— 跨行换行（纵向位移 &gt; height*0.5，走跨行三档分支）</li>
 *   <li>S5_ten_lines —— 跨十行跳转（10*32px，大跨度 + 最大偏移 clamp 路径）</li>
 *   <li>S6_pause_then_move —— 长时间停顿后首次移动，分两段模拟
 *       {@code CaretAnimator.tick()} 的两条 recovery 分支：
 *       短停顿（≤RECOVERY_STEP_CAP_SECONDS=0.5s）→ 首帧 dt=ACTIVE_DELAY_MS=8ms；
 *       长停顿（&gt;0.5s）→ 首帧 dt=rawDt（本 harness 取 3.0s，触发 DampedSpring 的短路归位）</li>
 * </ol>
 *
 * <p><b>关于 dt</b>：dt 是「输入序列」的一部分，由本 harness 固定给出（8ms 常态帧；
 * S6 的两个恢复首帧分别为 8ms 与 3.0s）。两个源码树收到完全相同的 dt 序列。
 *
 * <p><b>关于 rank</b>：调用 {@code calculateDirectionAlignment} 后按「对齐度升序」排名，
 * 与 {@code CaretAnimator.assignRanks()} 的算法逐行一致（0=最靠后）。
 *
 * <p><b>输出格式（固定，不得改）</b>：每帧一行；坐标与弹簧量保留 6 位小数
 * （Locale.ROOT，避免区域设置影响小数点）；行尾 {@code bin=} 给出同一批 double 的
 * 原始 {@code doubleToRawLongBits} 十六进制，使比对达到**位级**而非六位小数级。
 */
public class TrailParityTest {

    /**
     * 四角相对位置（左上 → 右上 → 右下 → 左下，与 SpringTrail.CORNER_RELATIVE 一致）。
     *
     * <p>【2026-10-31】原来这里写的是 {@code CaretAnimator.CORNER_RELATIVE} —— 那张表
     * 零读者、已删除（U-L-2），角点顺序的唯一权威在 {@code SpringTrail}。
     */
    static final double[][] REL = {
            {-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5},
    };

    /** 行高（像素）：与契约附录 A-1 harness 取同一个值，便于横向对照 */
    static final double H = 32.0;

    /** 常态帧 dt = ACTIVE_DELAY_MS(8ms) */
    static final double F = 8.0 / 1000.0;

    /** S6 长停顿首帧的 rawDt（模拟离开 3 秒后回来） */
    static final double LONG_PAUSE_DT = 3.0;

    static NeovideConfig cfg;
    static double W;
    static TrailCorner[] corners;

    static Field fx;
    static Field fy;
    static Field fvel;

    public static void main(String[] args) throws Exception {
        fx = TrailCorner.class.getDeclaredField("springX");
        fy = TrailCorner.class.getDeclaredField("springY");
        fx.setAccessible(true);
        fy.setAccessible(true);
        fvel = DampedSpring.class.getDeclaredField("velocity");
        fvel.setAccessible(true);

        cfg = new NeovideConfig();
        W = cfg.cursorWidth;

        // 配置快照：任何观感常量被改动都会让本行不同（独立于附录 B-3 的清单脚本）
        System.out.println("config: cursorWidth=" + cfg.cursorWidth
                + " animationLength=" + cfg.animationLength
                + " shortAnimationLength=" + cfg.shortAnimationLength
                + " trailSize=" + cfg.trailSize
                + " typingMaxChars=" + cfg.typingMaxChars
                + " shortMoveThreshold=" + cfg.shortMoveThreshold
                + " animationResetThreshold=" + cfg.animationResetThreshold
                + " maxTrailDistanceFactor=" + cfg.maxTrailDistanceFactor
                + " glowLayers=" + cfg.glowLayers
                + " glowOpacity=" + cfg.glowOpacity
                + " trailFactors=" + java.util.Arrays.toString(cfg.trailFactors));

        // ---- S1 同行输入 1 个字符 ----
        List<Frame> s1 = new ArrayList<Frame>();
        step(s1, 8, 0, 1, F, 20);
        scenario("S1_single_char", 0, 0, s1);

        // ---- S2 同行退格（目标反向） ----
        List<Frame> s2 = new ArrayList<Frame>();
        step(s2, 32, 0, 1, F, 20);
        scenario("S2_backspace", 40, 0, s2);

        // ---- S3 同一行连续 3 个字符 ----
        List<Frame> s3 = new ArrayList<Frame>();
        step(s3, 8, 0, 1, F, 6);
        step(s3, 16, 0, 1, F, 6);
        step(s3, 24, 0, 1, F, 6);
        scenario("S3_three_chars", 0, 0, s3);

        // ---- S4 跨行换行 ----
        List<Frame> s4 = new ArrayList<Frame>();
        step(s4, 0, 32, 1, F, 20);
        scenario("S4_cross_line", 0, 0, s4);

        // ---- S5 跨十行跳转 ----
        List<Frame> s5 = new ArrayList<Frame>();
        step(s5, 0, 320, 40, F, 20);
        scenario("S5_ten_lines", 0, 0, s5);

        // ---- S6 长时间停顿后首次移动 ----
        List<Frame> s6 = new ArrayList<Frame>();
        step(s6, 8, 0, 1, F, 20);                        // 先制造一段拖尾
        step(s6, 16, 0, 1, F, 20);                       // 短停顿(400ms)后首次移动：recovery 首帧 dt=8ms
        step(s6, 400, 0, 40, LONG_PAUSE_DT, 12);         // 长停顿(3000ms)后首次移动：recovery 首帧 dt=rawDt=3.0s
        scenario("S6_pause_then_move", 0, 0, s6);

        System.out.println("### end");
    }

    /** 一帧：目标点（光标矩形左上角，像素）+ dt（秒）+ 是否在本帧改变目标（改则先 jump） */
    static final class Frame {
        final double x;
        final double y;
        final double dt;
        final int chars;
        final boolean jump;

        Frame(double x, double y, double dt, int chars, boolean jump) {
            this.x = x;
            this.y = y;
            this.dt = dt;
            this.chars = chars;
            this.jump = jump;
        }
    }

    /** 追加一个「改变目标 + 连续 frames 帧」的步骤：首帧 jump 并用 firstDt，其余帧用常态 dt */
    static void step(List<Frame> out, double x, double y, int chars, double firstDt, int frames) {
        for (int i = 0; i < frames; i++) {
            out.add(new Frame(x, y, i == 0 ? firstDt : F, chars, i == 0));
        }
    }

    static void scenario(String name, double startX, double startY, List<Frame> frames)
            throws Exception {
        corners = new TrailCorner[4];
        for (int i = 0; i < 4; i++) {
            corners[i] = new TrailCorner(REL[i][0], REL[i][1], cfg);
        }
        double cx0 = startX + W / 2.0;
        double cy0 = startY + H / 2.0;
        for (TrailCorner t : corners) {
            t.snapTo(W, H, cx0, cy0);
        }
        System.out.println("### " + name
                + " start=(" + fmt(startX) + "," + fmt(startY) + ")"
                + " frames=" + frames.size());

        for (int k = 0; k < frames.size(); k++) {
            Frame f = frames.get(k);
            double cx = f.x + W / 2.0;
            double cy = f.y + H / 2.0;
            if (f.jump) {
                for (TrailCorner t : corners) {
                    t.setCharsMoved(f.chars);
                }
                int[] ranks = assignRanks(cx, cy);
                for (int r = 0; r < 4; r++) {
                    corners[r].jump(W, H, cx, cy, ranks[r]);
                }
            }
            boolean any = false;
            for (int r = 0; r < 4; r++) {
                any |= corners[r].update(W, H, cx, cy, f.dt, false);
            }
            printFrame(k, f.dt, any);
        }
    }

    /** 与 CaretAnimator.assignRanks() 逐行一致的排名算法：对齐度更高 → rank 更小 */
    static int[] assignRanks(double cx, double cy) {
        double[] alignment = new double[corners.length];
        for (int i = 0; i < corners.length; i++) {
            alignment[i] = corners[i].calculateDirectionAlignment(W, H, cx, cy);
        }
        int[] ranks = new int[corners.length];
        for (int i = 0; i < corners.length; i++) {
            int rank = 0;
            for (int j = 0; j < corners.length; j++) {
                if (alignment[j] < alignment[i]) {
                    rank++;
                }
            }
            ranks[i] = rank;
        }
        return ranks;
    }

    static void printFrame(int k, double dt, boolean any) throws Exception {
        List<Double> bits = new ArrayList<Double>(28);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "f=%03d dtms=%.3f any=%d",
                k, dt * 1000.0, any ? 1 : 0));

        for (int i = 0; i < 4; i++) {
            double x = corners[i].getCurrentX();
            double y = corners[i].getCurrentY();
            double o = corners[i].getOffsetMagnitude();
            sb.append(String.format(Locale.ROOT, " x%d=%.6f y%d=%.6f o%d=%.6f", i, x, i, y, i, o));
            bits.add(x);
            bits.add(y);
            bits.add(o);
        }

        for (int i = 0; i < 4; i++) {
            DampedSpring sx = (DampedSpring) fx.get(corners[i]);
            DampedSpring sy = (DampedSpring) fy.get(corners[i]);
            double px = sx.getPosition();
            double py = sy.getPosition();
            double vx = (Double) fvel.get(sx);
            double vy = (Double) fvel.get(sy);
            sb.append(String.format(Locale.ROOT,
                    " sx%d=%.6f sy%d=%.6f vx%d=%.6f vy%d=%.6f", i, px, i, py, i, vx, i, vy));
            bits.add(px);
            bits.add(py);
            bits.add(vx);
            bits.add(vy);
        }

        sb.append(" bin=");
        for (int i = 0; i < bits.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Long.toHexString(Double.doubleToRawLongBits(bits.get(i))));
        }
        System.out.println(sb);
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
