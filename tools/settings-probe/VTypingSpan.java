import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.util.Locale;

/**
 * V-TYPING-SPAN：真跨行前缘 = 0；同行 1..3 短跳、4+ 仍走跨行（Step B 已撤回）。
 *
 * <p><b>【A1 2026-11-06】</b>生产 {@code CaretAnimator} 现在只传 0（同一屏幕视觉行）
 * 与 {@code Integer.MAX_VALUE}（视觉行变化），不再传 1..10 的字符数。
 * 本探针<b>直调</b> {@code TrailCorner.jump()}，锁的是它自身的判定契约：
 * {@code TrailCorner} 本轮未改，0/1..3 同行 → SHORT，4+ 同行 → CROSS；
 * 旧像素兜底分支仍保留（{@code charsMoved < 0}）。
 *
 * <p><b>契约（2026-11-04 撤回 Step B 后的现状）</b>：
 * <ul>
 *   <li>真跨行（{@code sameLine == false}）→ {@code leading = animationLength × clamp(1 - trailSize)}，
 *       trailSize=1.0 时为 0；rank0 = animationLength；</li>
 *   <li>同一行 1..3 字符 → {@code shortAnimationLength × trailFactors}；</li>
 *   <li>同一行 4..10 字符 → <b>仍走跨行分支</b>（一帧累计多次击键导致的已知误判）；</li>
 *   <li>{@code charsMoved < 0} 的两个兜底分支保持既有行为（零尺寸沿用上一帧分支）。</li>
 * </ul>
 *
 * <p><b>为什么撤回 Step B</b>：2026-11-04 实机后用户反馈「连打偶发无拖尾」从未感受到，
 * 而选词 / End / 鼠标拖选等同行大跳的观感变化可见 —— 收益不可感知、副作用可见。
 * 详见 docs/archive/reports/STEP-B-REVERT-REPORT-2026-11-04.md。
 *
 * <p><b>历史</b>：0.9.90 的旧观察（提交 c13f517，2026-10-27）与当时的 55ms 下限
 * 已在 2026-11-03 由 Step C 撤回；该观察距今 42 个版本，未再复现。
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
        System.out.println("=== V-TYPING-SPAN：真跨行前缘 = 0、同行 4..10 仍走跨行（Step B 已撤回）===");

        NeovideConfig cfg = baseConfig();
        System.out.printf(Locale.ROOT,
                "  配置：animationLength=%.2fs shortAnimationLength=%.2fs trailSize=%.2f "
                        + "typingMaxChars=%.0f%n",
                cfg.animationLength, cfg.shortAnimationLength, cfg.trailSize, cfg.typingMaxChars);
        System.out.printf(Locale.ROOT,
                "  Step C 恢复：真跨行 leading = animationLength × (1 - trailSize) = %.4f s%n",
                cfg.animationLength * (1.0 - cfg.trailSize));

        // ---------- T-1：真跨行时前缘恢复为 0 ----------
        //
        // 【Step C 契约恢复，2026-11-03】真跨行（deltaY > height/2）的前缘必须是 0：
        // animationLength × (1 - trailSize) 在 trailSize=1.0 时精确为 0，
        // DampedSpring.update() 首句 animationLength <= dt 使它第一帧到位。
        // 这正是「缎带」形状的来源 —— 前缘咬住新位置、只有后缘拖出去。
        //
        // 必须用【垂直位移】制造真跨行。同一行 charsMoved=4 在 Step B 之后
        // 走的是短跳分支，不能再用它冒充真跨行。
        double[] crossReal = cornerLens(cfg, 4, false);
        double[] expectCross = {cfg.animationLength, cfg.animationLength / 2.0, 0.0, 0.0};
        check("T-1 真跨行时前缘为 0（trailSize=1.0，缎带形状的来源）",
                sameWithin(crossReal, expectCross, 1e-9),
                String.format(Locale.ROOT, "四角 = %s，期望 %s", j(crossReal), j(expectCross)));

        // ---------- T-2：短跳 1..3 逐位不变；同行 4..6 仍走跨行 ----------
        //
        // 【Step B 已撤回，2026-11-04】当前契约：
        //   a) 同一行 1..3 字符走短跳，四角逐位等于 shortAnimationLength × trailFactors；
        //   b) 同一行 4..6 字符仍走跨行（一帧累计多次击键的已知误判），前缘 = 0。
        // Step B 曾把 b 改成短跳；因用户无可感知收益、副作用可见而当天撤回。
        double[] expectShort = new double[4];
        for (int rank = 0; rank < 4; rank++) {
            expectShort[rank] = (double) cfg.shortAnimationLength * cfg.trailFactors[rank];
        }
        boolean shortOk = true;
        String shortDetail = "";
        for (int ch = 1; ch <= 3; ch++) {
            double[] lens = cornerLens(cfg, ch, true);
            System.out.printf(Locale.ROOT, "  charsMoved=%d（短跳）四角 = %s%n", ch, j(lens));
            if (!sameWithin(lens, expectShort, 1e-6)) {
                shortOk = false;
                shortDetail = "charsMoved=" + ch + " → " + j(lens) + "，公式 " + j(expectShort);
            }
        }
        check("T-2a 同一行 1..3 字符四角逐位等于短跳公式（恢复没有污染单行打字）",
                shortOk, shortDetail.isEmpty() ? "1..3 全部一致" : shortDetail);

        boolean burstOk = true;
        String burstDetail = "";
        for (int ch = 4; ch <= 6; ch++) {
            double[] lens = cornerLens(cfg, ch, true);
            System.out.printf(Locale.ROOT, "  charsMoved=%d（仍走跨行）四角 = %s%n", ch, j(lens));
            if (!sameWithin(lens, expectCross, 1e-9)) {
                burstOk = false;
                burstDetail = "charsMoved=" + ch + " → " + j(lens) + "，公式 " + j(expectCross);
            }
        }
        check("T-2b 同一行 4..6 字符仍走跨行分支（前缘 = 0，Step B 已撤回）",
                burstOk, burstDetail.isEmpty() ? "4..6 与跨行公式逐位一致" : burstDetail);

        // ---------- T-3：真跨行仍要保留梯度：前缘必须比后缘快 ----------
        //
        // 【2026-11-04】必须用真跨行（sameLine=false）。旧版用「同一行 charsMoved=10」
        // 冒充大跨度；那一档在 Step B 期间是短跳、撤回后是跨行，身份会随判据变化。
        // 用真跨行断言，才能稳定锁定「跨行梯度」本身。
        double[] cross10 = cornerLens(cfg, 10, false);
        check("T-3 真跨行时前缘仍比后缘快（不破坏原版 trailSize 三档梯度）",
                cross10[3] < cross10[0] && cross10[2] < cross10[0],
                String.format(Locale.ROOT, "后缘(rank0) = %.4f s，前缘(rank2/3) = %.4f / %.4f s",
                        cross10[0], cross10[2], cross10[3]));

        // ---------- T-4：短跳分支逐位不变 ----------
        //
        // 这是最关键的一条：撤回 Step B 只改判据，短跳公式本身必须逐位不变。
        // 期望值不是写死的数字，而是按生产公式 shock = shortAnimationLength × trailFactors[rank] 复算。
        double[] short1 = cornerLens(cfg, SHORT_CHARS, true);
        double[] expect = new double[4];
        for (int rank = 0; rank < 4; rank++) {
            expect[rank] = (double) cfg.shortAnimationLength * cfg.trailFactors[rank];
        }
        check("T-4 短跳分支四角时长与生产公式逐位一致（修复没有污染单行打字）",
                sameWithin(short1, expect, 1e-6),
                String.format(Locale.ROOT, "实测 %s，公式 %s", j(short1), j(expect)));

        // ---------- T-5：跨行前缘不得超过后缘 ----------
        //
        // 原公式 leading = animationLength × (1 - trailSize) 在 trailSize ∈ [0,1] 时
        // 恒 ≤ trailing。这条守住上界：将来若有人再给 leading 加一个大于后缘的「下限」，
        // 梯度会反转（前缘比后缘还慢），这里当场变红。
        check("T-5 跨行前缘不超过后缘（leading ≤ animationLength）",
                Math.min(crossReal[2], crossReal[3]) <= crossReal[0],
                String.format(Locale.ROOT, "前缘 %.4f s vs 后缘 %.4f s",
                        Math.min(crossReal[2], crossReal[3]), crossReal[0]));

        // ---------- T-6：真跨行密度守卫（2026-11-04 新增，撤回 Step B 后保留）----------
        //
        // 真跨行在 trailSize=0.0 / 0.5 / 1.0 三档都必须保持 Step C 的公式：
        //   leading  = animationLength × (1 - trailSize)
        //   trailing = animationLength
        // rank0 = trailing，rank1 = (leading + trailing) / 2，rank2/3 = leading。
        // 这条防的是「下一次又有人给真跨行加固定下限」。
        //
        // trailSize 追加 -0.5 / 1.5 两档越界值：生产公式里有 clamp(1 - trailSize, 0, 1)，
        // 期望值必须同样夹取，否则「删掉 clamp」不会有任何探针反应。
        boolean densityOk = true;
        String densityDetail = "";
        for (float ts : new float[]{-0.5f, 0.0f, 0.5f, 1.0f, 1.5f}) {
            NeovideConfig c = baseConfig();
            c.trailSize = ts;
            double[] lens = cornerLens(c, 4, false);
            double leading = (double) c.animationLength * clamp01(1.0 - (double) ts);
            double trailing = c.animationLength;
            double[] expectDensity = {trailing, (leading + trailing) / 2.0, leading, leading};
            System.out.printf(Locale.ROOT, "  真跨行 trailSize=%.1f 四角 = %s%n", ts, j(lens));
            if (!sameWithin(lens, expectDensity, 1e-9)) {
                densityOk = false;
                densityDetail = "trailSize=" + ts + " → " + j(lens) + "，公式 " + j(expectDensity);
            }
        }
        check("T-6 真跨行密度守卫：trailSize=-0.5/0/0.5/1/1.5 时 "
                        + "leading = animationLength×clamp(1-trailSize)、rank0 = animationLength",
                densityOk, densityDetail.isEmpty() ? "五档全部一致" : densityDetail);

        // ---------- T-7：同行 4..10 仍走跨行（Step B 已撤回）----------
        //
        // Step B 曾把这一档改成短跳；2026-11-04 已撤回。当前它仍是跨行分支：
        // 四角 = [animationLength, animationLength/2, 0, 0]（trailSize=1.0），
        // 且与 trailFactors / slope 无关。这条锁住撤回后的真实行为，
        // 防止下一次又有人把它当短跳改。
        boolean horizontalOk = true;
        String horizontalDetail = "";
        for (int slopeTicks : new int[]{0, 100}) {
            NeovideConfig c = baseConfig();
            c.trailFactors = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f, slopeTicks);
            double[] expectCrossBurst = {c.animationLength, c.animationLength / 2.0, 0.0, 0.0};
            for (int ch = 4; ch <= 10; ch++) {
                double[] lens = cornerLens(c, ch, true);
                if (!sameWithin(lens, expectCrossBurst, 1e-9)) {
                    horizontalOk = false;
                    horizontalDetail = "slope=" + slopeTicks + " charsMoved=" + ch
                            + " → " + j(lens) + "，公式 " + j(expectCrossBurst);
                }
            }
            System.out.printf(Locale.ROOT, "  同行 4..10 slope=%d 仍走跨行，公式 = %s%n",
                    slopeTicks, j(expectCrossBurst));
        }
        check("T-7 同行 4..10 仍走跨行（Step B 已撤回；已知误判，非目标）",
                horizontalOk, horizontalDetail.isEmpty() ? "两组斜度全部一致" : horizontalDetail);

        // ---------- T-8：真跨行 + charsMoved <= typingMaxChars（2026-11-04 新增，撤回后保留）----------
        //
        // 旧的「真跨行」用例 charsMoved 全是 4/10/99，全部大于 typingMaxChars，
        // 所以 sameLine 这一半可以被静默删掉而无人发现：把判据简化成
        // `charsMoved <= typingMaxChars` 后，按 Enter / 上下键（charsMoved=1）会误走短跳。
        // 这条用 charsMoved=1 + 纵向位移，锁住 sameLine。
        double[] crossSmall = cornerLens(cfg, 1, false);
        check("T-8 真跨行 + charsMoved=1 仍走跨行（sameLine 判据不可丢）",
                sameWithin(crossSmall, expectCross, 1e-9),
                String.format(Locale.ROOT, "四角 = %s，期望 %s", j(crossSmall), j(expectCross)));

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
     * 架起四个角，按指定方向推进，返回按角点索引排列的四角时长（秒）。
     *
     * <p>{@code sameLine=true}：横向推进 {@code charsMoved} 个字符宽度；
     * {@code sameLine=false}：纵向推进一整行高度（deltaY = height &gt; height/2），
     * 这是真正的跨行，判定不受 {@code charsMoved} 影响。
     *
     * <p>rank 直接取角点索引（TL=0, TR=1, BR=2, BL=3）：纯横向推进时右侧两角对齐度更高，
     * 真实 {@code CaretAnimator.assignRanks()} 也把它们排在后两位（见 VRank 的实测）。
     * 断言按传入的 rank（本探针取角点索引）逐项比较，因此 T-1 也锁定了 rank1 的中档值；
     * 只要传 rank 的方式不变，结果就可复现。
     */
    private static double[] cornerLens(NeovideConfig cfg, int charsMoved, boolean sameLine) {
        double w = 2.0, h = 16.0, cx = 100, cy = 100;
        double dx = sameLine ? CHAR_W * charsMoved : 0.0;
        double dy = sameLine ? 0.0 : h;
        double[][] rel = {{-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5}};
        TrailCorner[] cs = new TrailCorner[4];
        for (int i = 0; i < 4; i++) {
            cs[i] = new TrailCorner(rel[i][0], rel[i][1], cfg);
            cs[i].snapTo(w, h, cx, cy);
            cs[i].setCharsMoved(charsMoved);
            cs[i].jump(w, h, cx + dx, cy + dy, i);
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

    /** 与生产 {@code TrailCorner.clamp(...)} 同语义：探针不直接调用生产私有方法。 */
    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    private static boolean sameWithin(double[] a, double[] b, double eps) {
        for (int i = 0; i < a.length; i++) {
            // NaN 参与比较时 `Math.abs(NaN - x) > eps` 恒为 false，会静默通过。
            // 生产短跳时长一旦变成 NaN，没有这一行就可以「全绿」——见审查 M-3。
            if (!Double.isFinite(a[i]) || !Double.isFinite(b[i])) {
                return false;
            }
            if (Math.abs(a[i] - b[i]) > eps) {
                return false;
            }
        }
        return true;
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
