import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.util.Locale;

/**
 * V-TYPING-SPAN：真跨行前缘 = 0；同一行的短跳与 burst 同源（Step B）。
 *
 * <p><b>守的是什么</b>：0.9.90 用户观察「按住 f 连打时拖尾长度不一致，辉光最明显」。
 * 根因是 {@code charsMoved} 是<b>帧间累计量</b>（{@code SpringTrail.updateCharsMoved}）：
 * 8ms 帧下通常每帧 1 次击键 → charsMoved=1 → 短跳分支；
 * 偶尔一帧落进 4 次 → charsMoved=4 &gt; typingMaxChars(3) → 跨行分支。
 * 而三个预设的 trailSize 都是 1.0，跨行分支的 leading 精确为 0
 * → {@code DampedSpring.update()} 首句 {@code animationLength <= dt} 归零 → 每帧瞬移。
 * 于是同一个按键，这次有拖尾、下次瞬移。
 *
 * <p><b>历史</b>（0.9.90）：曾给跨行 leading 加下限
 * {@code max(leading, shortAnimationLength × 0.5)}，想让「被误判成跨行」的后果
 * 从「没有拖尾」降级为「尾巴略短」。
 *
 * <p><b>2026-11-03 Step C 撤回该下限</b>：下限加在真跨行与同行误判<b>共用</b>的分支上，
 * 真跨行前缘从 0 被抬到 55ms，三个预设的缎带密度一起变窄（用户实机确认）。
 *
 * <p><b>2026-11-04 Step B 拆开判据</b>：同一行内 {@code charsMoved > typingMaxChars}
 * 不再借用跨行分支，而是与短跳共用同一支画笔。当前契约：
 * <ul>
 *   <li>真跨行（{@code sameLine == false}）→ {@code leading = animationLength × (1 - trailSize)}，
 *       trailSize=1.0 时为 0；rank0 = animationLength；</li>
 *   <li>同一行 1..3 字符 → 短跳公式 {@code shortAnimationLength × trailFactors}；</li>
 *   <li>同一行 4..10 字符（连打 / 删词一帧合并多次击键）→ <b>同行 burst，同短跳公式</b>；</li>
 *   <li>{@code charsMoved < 0} 的两个兜底分支保持既有行为（零尺寸沿用上一帧分支）。</li>
 * </ul>
 * 前两条是 Step C 的契约恢复，后两条是 Step B 的契约变更，均<b>非缺陷</b>。
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
        System.out.println("=== V-TYPING-SPAN：真跨行前缘 = 0、同行 burst 与短跳同源（Step B）===");

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

        // ---------- T-2：短跳 1..3 逐位不变；同行 burst 4..6 同源 ----------
        //
        // 【Step B，2026-11-04】同一行 4..6 字符必须与短跳同源：
        //   a) 同一行 1..3 字符走短跳，四角逐位等于 shortAnimationLength × trailFactors；
        //   b) 同一行 4..6 字符走「同行 burst」，同样等于短跳公式。
        // 旧契约（4..6 走跨行、前缘 = 0）是已知误判，Step B 已改方向。
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
            System.out.printf(Locale.ROOT, "  charsMoved=%d（同行 burst）四角 = %s%n", ch, j(lens));
            if (!sameWithin(lens, expectShort, 1e-6)) {
                burstOk = false;
                burstDetail = "charsMoved=" + ch + " → " + j(lens) + "，公式 " + j(expectShort);
            }
        }
        check("T-2b 同一行 4..6 字符走短跳分支（四角 = short × trailFactors，Step B 契约变更）",
                burstOk, burstDetail.isEmpty() ? "4..6 与短跳逐位一致" : burstDetail);

        // ---------- T-3：真跨行仍要保留梯度：前缘必须比后缘快 ----------
        //
        // 【Step B，2026-11-04】必须用真跨行（sameLine=false）。修复前它用
        // 「同一行 charsMoved=10」冒充大跨度；Step B 之后那走短跳，断言仍然成立，
        // 但测的已经不是跨行 —— 会变成一条永久假绿的断言。
        double[] cross10 = cornerLens(cfg, 10, false);
        check("T-3 真跨行时前缘仍比后缘快（不破坏原版 trailSize 三档梯度，Step B 改用真跨行）",
                cross10[3] < cross10[0] && cross10[2] < cross10[0],
                String.format(Locale.ROOT, "后缘(rank0) = %.4f s，前缘(rank2/3) = %.4f / %.4f s",
                        cross10[0], cross10[2], cross10[3]));

        // ---------- T-4：短跳分支逐位不变 ----------
        //
        // 这是最关键的一条：Step B 只拆判据，短跳公式本身必须逐位不变。
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

        // ---------- T-6：真跨行密度守卫（Step B 新增）----------
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

        // ---------- T-7：同行 burst 守卫（Step B 新增）----------
        //
        // 同一行 charsMoved=4..10 必须走短跳公式；至少覆盖 slope=0 与 slope=100。
        // 不要断言 rank0 <= animationLength：纯平滑输入 slope=100 时
        // short rank0 = 0.11 × 1.00 = 110ms > animationLength 100ms，会写出假红。
        boolean horizontalOk = true;
        String horizontalDetail = "";
        for (int slopeTicks : new int[]{0, 100}) {
            NeovideConfig c = baseConfig();
            c.trailFactors = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f, slopeTicks);
            double[] expectBurst = new double[4];
            for (int rank = 0; rank < 4; rank++) {
                expectBurst[rank] = (double) c.shortAnimationLength * c.trailFactors[rank];
            }
            for (int ch = 4; ch <= 10; ch++) {
                double[] lens = cornerLens(c, ch, true);
                if (!sameWithin(lens, expectBurst, 1e-6)) {
                    horizontalOk = false;
                    horizontalDetail = "slope=" + slopeTicks + " charsMoved=" + ch
                            + " → " + j(lens) + "，公式 " + j(expectBurst);
                }
            }
            System.out.printf(Locale.ROOT, "  同行 burst slope=%d 公式 = %s%n",
                    slopeTicks, j(expectBurst));
        }
        check("T-7 同行 burst 守卫：charsMoved=4..10、slope=0/100 四角逐位 = short × trailFactors",
                horizontalOk, horizontalDetail.isEmpty() ? "两组斜度全部一致" : horizontalDetail);

        // ---------- T-8：真跨行 + charsMoved <= typingMaxChars（Step B 新增）----------
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
