import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.util.Locale;

/**
 * V-TYPING-SPAN：连续输入时，四角动画时长的跨度不得随「一帧内落进几次击键」而变。
 *
 * <p><b>守的是什么</b>：0.9.90 用户观察「按住 f 连打时拖尾长度不一致，辉光最明显」。
 * 根因是 {@code charsMoved} 是<b>帧间累计量</b>（{@code SpringTrail.updateCharsMoved}）：
 * 8ms 帧下通常每帧 1 次击键 → charsMoved=1 → 短跳分支；
 * 偶尔一帧落进 4 次 → charsMoved=4 &gt; typingMaxChars(3) → 跨行分支。
 * 而三个预设的 trailSize 都是 1.0，跨行分支的 leading 精确为 0
 * → {@code DampedSpring.update()} 首句 {@code animationLength <= dt} 归零 → 每帧瞬移。
 * 于是同一个按键，这次有拖尾、下次瞬移。
 *
 * <p><b>修法</b>（{@code TrailCorner.jump()} 跨行分支）：给 leading 加下限
 * {@code max(leading, shortAnimationLength × 0.5)}。
 *
 * <p><b>纪律四-G</b>：本探针只调用生产方法 {@code TrailCorner.jump()}，
 * 不自己重写判据或分配算法。时长的读取走反射读弹簧私有字段 —— 这是「断言生产行为」，
 * 与 VBlockStretch 的 springLen 同一手法。
 */
public final class VTypingSpan {

    private static int pass = 0, fail = 0;

    /** 短跳分支的参照：charsMoved=1 */
    private static final int SHORT_CHARS = 1;

    /** 每字符横向推进的像素（与光标宽 2px、字号无关，只用来区分两个分支） */
    private static final double CHAR_W = 12.0;

    public static void main(String[] args) {
        System.out.println("=== V-TYPING-SPAN：连打时四角时长跨度必须稳定 ===");

        NeovideConfig cfg = baseConfig();
        System.out.printf(Locale.ROOT,
                "  配置：animationLength=%.2fs shortAnimationLength=%.2fs trailSize=%.2f "
                        + "typingMaxChars=%.0f%n",
                cfg.animationLength, cfg.shortAnimationLength, cfg.trailSize, cfg.typingMaxChars);
        System.out.printf(Locale.ROOT, "  修复后的 leading 下限 = %.4f s（%.0f ms）%n",
                floorOf(cfg), floorOf(cfg) * 1000.0);

        // ---------- T-1：trailSize=1.0 时跨行分支的前缘必须 > 0 ----------
        double[] cross4 = cornerLens(cfg, 4);
        double leadingCross = Math.min(cross4[2], cross4[3]);
        check("T-1 trailSize=1.0 时跨行分支的前缘时长 > 0（原来的 0 就是每帧瞬移）",
                leadingCross > 0,
                String.format(Locale.ROOT, "四角 = %s，前缘 = %.4f s", j(cross4), leadingCross));

        // ---------- T-2：charsMoved = 1..6 的四角极差 <= 3 倍 ----------
        //
        // 修复前：charsMoved=1 走短跳（rank0 94.6ms），charsMoved=4 走跨行（rank0 0ms）
        //        → 极差 = ∞。这才是「同一个按键，每次动画不一样」的量化形态。
        double worstRatio = 0;
        String worstDetail = "";
        for (int ch = 1; ch <= 6; ch++) {
            double[] lens = cornerLens(cfg, ch);
            double min = Double.MAX_VALUE, max = 0;
            for (double v : lens) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            // 前缘为 0 时比值无意义，按无穷大处理（正是修复前的情形）
            double ratio = min > 0 ? max / min : Double.POSITIVE_INFINITY;
            if (ratio > worstRatio) {
                worstRatio = ratio;
                worstDetail = "charsMoved=" + ch + " → " + j(lens) + " 极差 " + fmt(ratio);
            }
            System.out.printf(Locale.ROOT, "  charsMoved=%d 四角 = %s%n", ch, j(lens));
        }
        check("T-2 charsMoved=1..6 四角时长的极差 <= 3 倍（修复前是 ∞：短跳 94.6ms vs 跨行 0ms）",
                worstRatio <= 3.0,
                "最差：" + worstDetail);

        // ---------- T-3：真跨行（charsMoved=10）仍要保留梯度：前缘必须比后缘快 ----------
        double[] cross10 = cornerLens(cfg, 10);
        check("T-3 真跨行时前缘仍比后缘快（不破坏原版 trailSize 三档梯度）",
                cross10[3] < cross10[0] && cross10[2] < cross10[0],
                String.format(Locale.ROOT, "后缘(rank0) = %.4f s，前缘(rank2/3) = %.4f / %.4f s",
                        cross10[0], cross10[2], cross10[3]));

        // ---------- T-4：短跳分支逐位不变 ----------
        //
        // 这是最关键的一条：修复只该动跨行分支，正常打字的手感必须一模一样。
        // 期望值不是写死的数字，而是按生产公式 shock = shortAnimationLength × trailFactors[rank] 复算。
        double[] short1 = cornerLens(cfg, SHORT_CHARS);
        double[] expect = new double[4];
        for (int rank = 0; rank < 4; rank++) {
            expect[rank] = cfg.shortAnimationLength * cfg.trailFactors[rank];
        }
        check("T-4 短跳分支四角时长与生产公式逐位一致（修复没有污染单行打字）",
                sameWithin(short1, expect, 1e-6),
                String.format(Locale.ROOT, "实测 %s，公式 %s", j(short1), j(expect)));

        // ---------- T-5：跨行下限不得反过来抬高短跳 ----------
        //
        // 下限取 shortAnimationLength × 0.5；若将来有人把它调到 shortAnimationLength × 10，
        // 跨行分支的 leading 会超过 trailing，梯度反转 —— 这条守住上界。
        check("T-5 跨行前缘不超过后缘（下限 < animationLength）",
                Math.min(cross4[2], cross4[3]) <= cross4[0],
                String.format(Locale.ROOT, "前缘 %.4f s vs 后缘 %.4f s",
                        Math.min(cross4[2], cross4[3]), cross4[0]));

        System.out.println();
        System.out.printf("=== V-TYPING-SPAN 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-TYPING-SPAN RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-TYPING-SPAN RESULT: ALL PASS");
        System.exit(0);
    }

    /**
     * 出厂观感（「基础」预设）：animationLength 0.10、trailSize 1.0。
     *
     * <p>trailSize=1.0 是问题的前提 —— 它让 (1 - trailSize) 精确为 0。
     */
    private static NeovideConfig baseConfig() {
        NeovideConfig cfg = new NeovideConfig();
        cfg.animationLength = 0.10f;
        cfg.shortAnimationLength = 0.11f;
        cfg.trailSize = 1.0f;
        // 与「基础」预设一致：k=4.5、smooth=0、slope=0（斜度默认值已是 0）
        cfg.trailFactors = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f, 0f);
        cfg.typingMaxChars = 3f;
        return cfg;
    }

    /**
     * 生产代码里 leading 的下限系数（与 TrailCorner.jump() 同源）。
     *
     * <p>探针里出现这个数字只是为了把「期望」打印出来，断言本身全部读生产跑出的弹簧时长。
     */
    private static double floorOf(NeovideConfig cfg) {
        return cfg.shortAnimationLength * 0.5;
    }

    /**
     * 架起四个角，同行横向推进 charsMoved 个字符宽度，返回按角点索引排列的四角时长（秒）。
     *
     * <p>rank 直接取角点索引（TL=0, TR=1, BR=2, BL=3）：纯横向推进时右侧两角对齐度更高，
     * 真实 {@code CaretAnimator.assignRanks()} 也把它们排在后两位（见 VRank 的实测）。
     * 断言只比较「跨行 vs 短跳」的相对关系和安全边界，不依赖具体派位。
     */
    private static double[] cornerLens(NeovideConfig cfg, int charsMoved) {
        double w = 2.0, h = 16.0, cx = 100, cy = 100;
        double dx = CHAR_W * charsMoved;
        double[][] rel = {{-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5}};
        TrailCorner[] cs = new TrailCorner[4];
        for (int i = 0; i < 4; i++) {
            cs[i] = new TrailCorner(rel[i][0], rel[i][1], cfg);
            cs[i].snapTo(w, h, cx, cy);
            cs[i].setCharsMoved(charsMoved);
            cs[i].jump(w, h, cx + dx, cy, i);
        }
        double[] lens = new double[4];
        for (int i = 0; i < 4; i++) {
            lens[i] = springLen(cs[i]);
        }
        return lens;
    }

    /** 反射读 springX 的 animationLength（与 VBlockStretch.springLen 同一手法） */
    private static double springLen(TrailCorner c) {
        try {
            java.lang.reflect.Field sf = TrailCorner.class.getDeclaredField("springX");
            sf.setAccessible(true);
            Object spring = sf.get(c);
            java.lang.reflect.Field af = spring.getClass().getDeclaredField("animationLength");
            af.setAccessible(true);
            return ((Number) af.get(spring)).doubleValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读不到弹簧时长，探针失效：" + e, e);
        }
    }

    private static boolean sameWithin(double[] a, double[] b, double eps) {
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > eps) {
                return false;
            }
        }
        return true;
    }

    private static String fmt(double v) {
        return Double.isInfinite(v) ? "∞" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String j(double[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format(Locale.ROOT, "%.4f", a[i]));
        }
        return sb.append("]").toString();
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + n + "   [" + d + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + n + "   [" + d + "]");
        }
    }
}
