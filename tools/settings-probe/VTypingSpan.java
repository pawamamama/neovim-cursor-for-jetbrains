import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.util.Locale;

/**
 * V-TYPING-SPAN：守 Step C 恢复后的跨行/短跳契约 —— 真跨行前缘 = 0、短跳逐位不变。
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
 * 本探针的契约随之恢复：
 * <ul>
 *   <li>真跨行（垂直位移）→ {@code leading = animationLength × (1 - trailSize)}，trailSize=1.0 时为 0；</li>
 *   <li>同一行 1..3 字符 → 短跳公式 {@code shortAnimationLength × trailFactors}；</li>
 *   <li>同一行 4..6 字符 → 仍走跨行分支（<b>已知误判，Step B 另修</b>），前缘为 0。</li>
 * </ul>
 * 这是<b>契约恢复，非缺陷</b>。
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
        System.out.println("=== V-TYPING-SPAN：真跨行前缘 = 0、短跳逐位不变（Step C 恢复）===");

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
        // 必须用【垂直位移】制造真跨行。同一行 charsMoved=4 也会走跨行分支，
        // 但那是误判（Step B 修）；用它冒充真跨行，会在误判被修掉后失去意义。
        double[] crossReal = cornerLens(cfg, 4, false);
        double[] expectCross = {cfg.animationLength, cfg.animationLength / 2.0, 0.0, 0.0};
        check("T-1 真跨行时前缘为 0（trailSize=1.0，缎带形状的来源）",
                sameWithin(crossReal, expectCross, 1e-9),
                String.format(Locale.ROOT, "四角 = %s，期望 %s", j(crossReal), j(expectCross)));

        // ---------- T-2：短跳逐位不变；同行走跨行分支（4..6）前缘为 0 ----------
        //
        // 【Step C 契约恢复，2026-11-03】撤下限后必须同时守住两件事：
        //   a) 同一行 1..3 字符仍走短跳，四角逐位等于 shortAnimationLength × trailFactors；
        //   b) 同一行 4..6 字符仍走跨行分支（误判），前缘回到 0 ——
        //      这是【已知缺陷，Step B 另修】，Step C 只负责把行为恢复到 0.9.127。
        double[] expectShort = new double[4];
        for (int rank = 0; rank < 4; rank++) {
            expectShort[rank] = cfg.shortAnimationLength * cfg.trailFactors[rank];
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
            System.out.printf(Locale.ROOT, "  charsMoved=%d（跨行分支）四角 = %s%n", ch, j(lens));
            if (lens[2] != 0.0 || lens[3] != 0.0 || lens[0] != (double) cfg.animationLength) {
                burstOk = false;
                burstDetail = "charsMoved=" + ch + " → " + j(lens);
            }
        }
        check("T-2b 同一行 4..6 字符走跨行分支时前缘为 0（已知误判，Step B 修）",
                burstOk, burstDetail.isEmpty() ? "4..6 前缘均为 0" : burstDetail);

        // ---------- T-3：大跨度仍要保留梯度：前缘必须比后缘快 ----------
        double[] cross10 = cornerLens(cfg, 10, true);
        check("T-3 大跨度时前缘仍比后缘快（不破坏原版 trailSize 三档梯度）",
                cross10[3] < cross10[0] && cross10[2] < cross10[0],
                String.format(Locale.ROOT, "后缘(rank0) = %.4f s，前缘(rank2/3) = %.4f / %.4f s",
                        cross10[0], cross10[2], cross10[3]));

        // ---------- T-4：短跳分支逐位不变 ----------
        //
        // 这是最关键的一条：本次恢复只该动跨行分支，短跳的手感必须与恢复前逐位一致。
        // 期望值不是写死的数字，而是按生产公式 shock = shortAnimationLength × trailFactors[rank] 复算。
        double[] short1 = cornerLens(cfg, SHORT_CHARS, true);
        double[] expect = new double[4];
        for (int rank = 0; rank < 4; rank++) {
            expect[rank] = cfg.shortAnimationLength * cfg.trailFactors[rank];
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

    private static boolean sameWithin(double[] a, double[] b, double eps) {
        for (int i = 0; i < a.length; i++) {
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
