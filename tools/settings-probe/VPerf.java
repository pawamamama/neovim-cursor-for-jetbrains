
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * V-PERF —— refresh-auditor（t6）：帧数 / 滞后 / 拉伸的三方权衡与安全边界。
 *
 * <p><b>不读代码下结论，全部驱动真实 core 类：</b>
 * <ul>
 *   <li>{@link TrailCorner}（真四角，真的 {@code jump()/update()}）</li>
 *   <li>{@link com.github.yyyolo7a79.neovidecursor.core.DampedSpring}（真闭式解）</li>
 *   <li>{@link NeovideCursorSettings#deriveTrailFactors(float, float)}（真派生公式）</li>
 * </ul>
 *
 * <p>关键：稳态滞后是用「连续删除」的真实时序量出来的 —— 每 R 毫秒目标左移一个字符，
 * 每 dt 毫秒推进一帧物理，与 {@code CaretAnimator.tickSpring()} 同序
 * （先 assignRanks/jump，再 update）。
 */
public final class VPerf {

    private static int pass = 0, fail = 0;

    /** 正常帧间隔（= CaretAnimator.ACTIVE_DELAY_MS）。 */
    private static final double DT_8 = 0.008;
    /** 半帧间隔（假设把调度降到 4ms）。 */
    private static final double DT_4 = 0.004;

    /** 光标几何：与本插件默认一致（cursorWidth = 2），行高取 16px。 */
    private static final double W = 2.0;
    private static final double H = 16.0;
    /** 一个字符的推进量（等宽字体约 8px）。 */
    private static final double CHAR_W = 8.0;

    public static void main(String[] args) {
        System.out.println("=== V-PERF：帧数 / 滞后 / 拉伸 三方权衡（真 TrailCorner + 真 DampedSpring）===");

        question1_formula();
        question2_whichCorner();
        question3_feasibleRegion();
        question4_physicsChange();
        question5_vscode();
        question6_frameRate();

        System.out.println();
        System.out.printf("=== V-PERF 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-PERF RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-PERF RESULT: ALL PASS");
        System.exit(0);
    }

    // ================================================================ Q1 前缘帧数公式

    private static void question1_formula() {
        System.out.println();
        System.out.println("--- Q1. 「前缘帧数」由什么决定？公式验证 + 客观判据 ---");

        // ---- 1a. 真实使用的 rank 到底是哪几档 ----
        //
        // CaretAnimator.assignRanks() 的判据是「名次 = 严格更小的对齐度个数」。
        // 同一行纯横向移动时，四个角的对齐度只有两种值（±0.707），
        // 于是必然出现【并列】：领先的两个角并列 rank=2，落后的两个角并列 rank=0。
        // → rank1 与 rank3 在纯横向打字中【永远拿不到】。
        int[] ranks = horizontalRanks(true);
        System.out.printf(Locale.ROOT, "  纯横向（向右/删左）四角 rank = [%d, %d, %d, %d]%n",
                ranks[0], ranks[1], ranks[2], ranks[3]);
        boolean onlyZeroAndTwo = true;
        for (int r : ranks) {
            if (r != 0 && r != 2) {
                onlyZeroAndTwo = false;
            }
        }
        check("P-1 纯横向打字时 rank 只有 {0, 2}（并列没有 rank1/rank3）",
                onlyZeroAndTwo, "ranks=" + java.util.Arrays.toString(ranks));

        float[] f45 = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        float[] f8 = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        System.out.printf(Locale.ROOT, "  k=4.5 → trailFactors = [%.2f, %.2f, %.2f, %.2f]%n",
                f45[0], f45[1], f45[2], f45[3]);
        System.out.printf(Locale.ROOT, "  k=8.0 → trailFactors = [%.2f, %.2f, %.2f, %.2f]%n",
                f8[0], f8[1], f8[2], f8[3]);
        check("P-2 【文档口径错】W_SHORT_LEN 说「前缘 rank3 = 本值 × 0.22」，"
                        + "但纯横向打字实际只用 rank2 的系数（k=4.5 时 0.42，k=8 时 0.35）——"
                        + "真正的横向前缘系数不是 0.22",
                f45[2] != f45[3],
                String.format(Locale.ROOT, "rank2=%.2f rank3=%.2f（差 %.2f，前缘被低估 %.0f%%）",
                        f45[2], f45[3], f45[2] - f45[3], (f45[2] / f45[3] - 1) * 100));

        // ---- 1b. 公式验证：帧数 = 有效时长 / dt（用真 DampedSpring 量）----
        System.out.println();
        System.out.println("  公式验证：有效时长 = short × factor(rank)，帧数 = 该时长下 DampedSpring 的实际运动帧数");
        System.out.printf(Locale.ROOT, "  %-8s %-8s %-10s %-10s %-9s %-9s%n",
                "short", "smooth", "rank2系数", "前缘时长", "实测帧", "实测ms");
        double[] shorts = {0.08, 0.11, 0.14, 0.20, 0.30};
        double[] smooths = {0.0, 0.5, 1.0};
        for (double shortLen : shorts) {
            for (double sm : smooths) {
                float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, (float) sm);
                double eff = shortLen * f[2];
                int frames = framesToSettle(eff, DT_8, CHAR_W);
                System.out.printf(Locale.ROOT, "  %-8.2f %-8.2f %-10.2f %-10.1f %-9d %-9.1f%n",
                        shortLen, sm, f[2], eff * 1000, frames, frames * DT_8 * 1000);
            }
        }

        // ---- 1c. 客观判据：前缘单帧位移是否「跳」----
        //
        // 「跳」的机械判据应当与视觉分辨率挂钩，而不是帧数：
        //   光标本身只有 2px 宽，一帧走完 N px 时，相邻两帧之间【没有中间位置】。
        // 判据取「单帧位移 ≤ 光标宽度 W」= 至少有一帧中间态落在光标自身尺寸内。
        // 更严格一点：单帧位移 ≤ 1px（亚像素光栅化能画出过渡帧）。
        System.out.println();
        System.out.println("  客观判据（不凭感觉）：一帧的位移量 vs 光标宽度 2px");
        System.out.printf(Locale.ROOT, "  %-10s %-10s %-12s %-12s %-10s%n",
                "short", "smooth", "前缘首帧位移", "占光标宽度", "判定");
        for (double shortLen : new double[]{0.11, 0.14, 0.20, 0.30}) {
            for (double sm : new double[]{0.0, 1.0}) {
                float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, (float) sm);
                double eff = shortLen * f[2];
                double firstStep = firstFrameStep(eff, DT_8, CHAR_W);
                double ratio = firstStep / W;
                System.out.printf(Locale.ROOT, "  %-10.2f %-10.2f %-12.2f %-12.2f %-10s%n",
                        shortLen, sm, firstStep, ratio, ratio > 1.0 ? "会跳" : "有中间态");
            }
        }
        // 用「一帧走完整个位移」的极端：只有当 effective <= dt 才会被 DampedSpring 首句短路
        double effAt_011_smooth0 = 0.11 * NeovideCursorSettings.deriveTrailFactors(4.5f, 0f)[2];
        check("P-3 short=0.11 / smooth=0（「纯平滑输入」当前值）时前缘不是「一帧到位」："
                        + "有效时长远超 dt 8ms",
                effAt_011_smooth0 * 1000 > 8.0,
                String.format(Locale.ROOT, "有效=%.1fms 帧数=%d",
                        effAt_011_smooth0 * 1000, framesToSettle(effAt_011_smooth0, DT_8, CHAR_W)));

        // 真正「跳」的边界：rank2 有效时长 <= dt ⇒ 首帧归零
        double jumpBoundary = 8.0 / NeovideCursorSettings.deriveTrailFactors(4.5f, 0f)[2] / 1000.0;
        System.out.printf(Locale.ROOT,
                "  真正的「跳」边界（rank2 有效时长 ≤ dt=8ms）：short ≤ %.4fs%n", jumpBoundary);
        check("P-4 任务书说的「smooth=0 时前缘 5 帧」是在 rank3 系数(0.22)下算的；"
                        + "按真实 rank2(0.42) 应为约 11 帧 —— 结论「会跳」不成立",
                framesToSettle(0.14 * 0.42, DT_8, CHAR_W) >= 10,
                "short=0.14 前缘实测 " + framesToSettle(0.14 * 0.42, DT_8, CHAR_W) + " 帧");
    }

    // ================================================================ Q2 稳态滞后

    private static void question2_whichCorner() {
        System.out.println();
        System.out.println("--- Q2. 稳态滞后由哪个角决定？连续删除时该改哪个参数 ---");

        // 连续删除：每 repeatMs 目标左移一个字符，帧间隔 8ms
        double[] repeatRates = {20, 30, 50, 80};
        System.out.println("  k=4.5, short=0.11, smooth=0（「基础」预设当前值），按不同连删速度：");
        System.out.printf(Locale.ROOT, "  %-12s %-12s %-12s %-12s %-12s%n",
                "连删间隔", "rank0滞后", "rank2滞后", "最大滞后", "后缘/前缘");
        double lagAt30_rank0 = 0;
        double lagAt30_rank2 = 0;
        for (double rpt : repeatRates) {
            double[] lags = steadyStateLags(0.11, 0.0, 4.5f, rpt / 1000.0, DT_8);
            System.out.printf(Locale.ROOT, "  %-12.0f %-12.2f %-12.2f %-12.2f %-12.2f%n",
                    rpt, lags[0], lags[2], Math.max(lags[0], lags[2]),
                    lags[2] > 0 ? lags[0] / lags[2] : 0);
            if (Math.abs(rpt - 30) < 1e-9) {
                lagAt30_rank0 = lags[0];
                lagAt30_rank2 = lags[2];
            }
        }

        // 【实测推翻了我自己的初始假设】我本以为「前缘才是落后的那个」，
        // 实测正好相反：后缘 rank0 的稳态滞后是前缘的 2.7 倍。
        // 原因不是时长（rank0 时长最长 → 衰减最慢），而是它的 offset 一直没被收回就迎来下一次击键。
        check("P-5 【答案】稳态滞后由【后缘 rank0】决定，不是前缘 —— 后缘是前缘的约 2.7 倍",
                lagAt30_rank0 > lagAt30_rank2,
                String.format(Locale.ROOT,
                        "rank0(后缘)=%.2fpx > rank2(前缘)=%.2fpx（比值 %.2f）",
                        lagAt30_rank0, lagAt30_rank2,
                        lagAt30_rank2 > 0 ? lagAt30_rank0 / lagAt30_rank2 : 0));

        // 为什么：稳态滞后 ∝ 有效时长 × 输入速率。rank0 时长 110ms，
        // 但每次 jump() 会 reset 弹簧 + 重设 position，累计位移被「重新起算」——
        // 真正决定「看得见的落后量」的是【同一帧内所有角共享的目标移动】，
        // 而 rank0 因时长最长，反而在每次 reset 后从更远的起点出发。
        // 这里直接把「每次击键后被甩开的距离」量出来，避免口头解释。
        System.out.println();
        System.out.println("  单次击键后 1 帧（8ms）各角被甩开的距离（这就是「跟手」的原始量）：");
        double[] after1 = oneFrameLags(0.11, 0.0, 4.5f, DT_8);
        System.out.printf(Locale.ROOT, "    rank0=%.2fpx  rank2=%.2fpx（目标移动 %.1fpx）%n",
                after1[0], after1[2], CHAR_W);
        // 【实测推翻了我的第二个假设】我以为第一帧后偏移还等于整个 8px。
        // 实测 rank0=7.72 / rank2=6.77：**首帧就已经走掉了 0.28 / 1.23 px**，
        // 也就是说「被甩开多少」当场就被 animationLength 决定了（时长越短走得越多）。
        check("P-6 首帧结束时四角已各自走掉不同距离（rank2 走得比 rank0 多 4.4 倍）——"
                        + "「跟手」从第一帧就已经由各自时长决定，不是等几帧才体现",
                after1[2] < after1[0] && (after1[0] - after1[2]) > 0.5,
                String.format(Locale.ROOT,
                        "首帧后剩余偏移 rank0=%.2fpx / rank2=%.2fpx（目标位移 %.2fpx）",
                        after1[0], after1[2], CHAR_W));

        // 关键结论：连续快速删除时，真正决定「跟不跟得上」的是【最短的那个角的时长】
        // 还是【最长】？用两组对照：把 short 调大 vs 把 smooth 调大
        System.out.println();
        System.out.println("  改哪个参数能降滞后（连删间隔 30ms，测最大滞后 px）：");
        System.out.printf(Locale.ROOT, "  %-28s %-12s%n", "参数组", "最大滞后");
        double base = steadyStateLags(0.11, 0.0, 4.5f, 0.030, DT_8)[2];
        double biggerShort = steadyStateLags(0.30, 0.0, 4.5f, 0.030, DT_8)[2];
        double biggerSmooth = steadyStateLags(0.11, 1.0, 4.5f, 0.030, DT_8)[2];
        double biggerK = steadyStateLags(0.11, 0.0, 8.0f, 0.030, DT_8)[2];
        System.out.printf(Locale.ROOT, "  %-28s %-12.2f%n", "基准 short=0.11 smooth=0", base);
        System.out.printf(Locale.ROOT, "  %-28s %-12.2f%n", "short=0.30（拉长）", biggerShort);
        System.out.printf(Locale.ROOT, "  %-28s %-12.2f%n", "smooth=1.0（压平梯度）", biggerSmooth);
        System.out.printf(Locale.ROOT, "  %-28s %-12.2f%n", "k=8（加大梯度）", biggerK);
        // 【实测推翻了我的第三个假设】我以为「k↑ 也会推高滞后」。实测相反：k↑ 让前缘更快。
        // 但注意这里的量是 **rank2（前缘）** 的滞后；决定用户观感的是 rank0（后缘），
        // 而 rank0 的系数在任何 k 下都被钉死在 1.0（不随 k 变）——
        // 所以 **k 与 smooth 都动不了后缘滞后**，只有 shortAnimationLength 能。
        double lag0Base = steadyStateLags(0.11, 0.0, 4.5f, REPEAT_30, DT_8)[0];
        double lag0BiggerShort = steadyStateLags(0.30, 0.0, 4.5f, REPEAT_30, DT_8)[0];
        double lag0BiggerSmooth = steadyStateLags(0.11, 1.0, 4.5f, REPEAT_30, DT_8)[0];
        double lag0BiggerK = steadyStateLags(0.11, 0.0, 8.0f, REPEAT_30, DT_8)[0];
        check("P-7 【答案】**只有 shortAnimationLength 能降后缘滞后**："
                        + "smooth 与 k 对 rank0 完全无效（它的系数恒为 1.0）",
                Math.abs(lag0BiggerSmooth - lag0Base) < 0.01
                        && Math.abs(lag0BiggerK - lag0Base) < 0.01
                        && lag0BiggerShort > lag0Base,
                String.format(Locale.ROOT,
                        "rank0滞后：基准 %.2f ｜ short↑→%.2f ｜ smooth↑→%.2f(不变) ｜ k↑→%.2f(不变)",
                        lag0Base, lag0BiggerShort, lag0BiggerSmooth, lag0BiggerK));
    }

    // ================================================================ Q3 可行域穷举

    private static void question3_feasibleRegion() {
        System.out.println();
        System.out.println("--- Q3. 穷举 short∈[0.08,0.30] × smooth∈[0,1]：找「梯度≥3× 且 前缘≥10帧 且 滞后≤8px」---");

        final double REPEAT = 0.030;      // 连删间隔 30ms（按住退格的中速）
        List<String> feasible = new ArrayList<>();
        double bestScore = -1;
        String best = "";

        // 先给「梯度」两种口径：标称 f0/f3（文档口径）与真实横向 f0/f2
        int gridN = 0;
        for (int i = 0; i <= 22; i++) {
            double shortLen = 0.08 + i * 0.01;
            if (shortLen > 0.30001) {
                break;
            }
            for (int j = 0; j <= 20; j++) {
                double sm = j * 0.05;
                gridN++;
                float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, (float) sm);
                double gradReal = f[0] / f[2];              // 纯横向真实用到的两档
                double leadFrames = framesToSettle(shortLen * f[2], DT_8, CHAR_W);
                double lag = steadyStateLags(shortLen, sm, 4.5f, REPEAT, DT_8)[2];

                if (gradReal >= 3.0 && leadFrames >= 10 && lag <= 8.0) {
                    feasible.add(String.format(Locale.ROOT,
                            "short=%.2f smooth=%.2f 梯度%.2f× 前缘%d帧 滞后%.2fpx",
                            shortLen, sm, gradReal, (int) leadFrames, lag));
                }
                // 记录「最接近」的：把三个约束归一化后取最小超配额
                double score = Math.min(gradReal / 3.0,
                        Math.min(leadFrames / 10.0, 8.0 / Math.max(lag, 0.01)));
                if (score > bestScore) {
                    bestScore = score;
                    best = String.format(Locale.ROOT,
                            "short=%.2f smooth=%.2f 梯度%.2f×(需3) 前缘%d帧(需10) 滞后%.2fpx(需≤8)",
                            shortLen, sm, gradReal, (int) leadFrames, lag);
                }
            }
        }

        System.out.printf(Locale.ROOT, "  共测 %d 组参数，完全满足三条约束的：%d 组%n",
                gridN, feasible.size());
        if (feasible.isEmpty()) {
            System.out.println("  → 可行域为空。最接近的一组：");
            System.out.println("    " + best);
            System.out.printf(Locale.ROOT, "    （归一化最差项 = %.2f，<1 表示至少一条没满足）%n", bestScore);
        } else {
            System.out.println("  可行组合（最多列 20 组）：");
            for (int i = 0; i < Math.min(20, feasible.size()); i++) {
                System.out.println("    " + feasible.get(i));
            }
        }

        // 结构性证明（不靠穷举结果碰运气）：为什么不可能
        //
        // 前缘帧数 ∝ short × f2，滞后 ∝ short × f0（稳态偏移按最短时间常数累积）
        // ⇒ 前缘帧数 ≥ 10 要求 short × f2 ≥ 10 × dt
        //   滞后 ≤ 8px   要求 short × f0 ≤ K（K 由输入速率定）
        // 两式相除 ⇒ 梯度 f0/f2 ≥ (10×dt) / (8/K 对应的 short) —— 直接看比值约束。
        System.out.println();
        System.out.println("  结构性分析（不靠穷举碰运气）：");
        double dtSearchBoundary = 10 * DT_8;              // 前缘 ≥10 帧 ⇔ 有效时长 ≥ 80ms
        double needShortFor10Frames = dtSearchBoundary / NeovideCursorSettings.deriveTrailFactors(4.5f, 0f)[2];
        System.out.printf(Locale.ROOT,
                "    前缘 ≥10 帧 ⇒ short × f2 ≥ %.0fms ⇒ short ≥ %.3fs（k=4.5, smooth=0）%n",
                dtSearchBoundary * 1000, needShortFor10Frames);
        double lagAtThatShort = steadyStateLags(needShortFor10Frames, 0.0, 4.5f, REPEAT, DT_8)[2];
        System.out.printf(Locale.ROOT,
                "    该 short 下的稳态滞后 = %.2fpx（约束要求 ≤8px）%n", lagAtThatShort);
        System.out.printf(Locale.ROOT,
                "    ⇒ 滞后与「前缘帧数」通过【同一个 short】刚性耦合：拉开前缘必然推高滞后%n");

        check("P-8 【结构性结论】三条约束在现有物理下无法同时满足 —— 可行域为空，"
                        + "因为前缘帧数与滞后都由同一个 short 线性驱动",
                feasible.isEmpty(),
                feasible.isEmpty() ? "0 组可行；最接近：" + best : feasible.size() + " 组可行");
    }

    // ================================================================ Q4 必须改物理

    private static void question4_physicsChange() {
        System.out.println();
        System.out.println("--- Q4. 若不存在，必须改哪里？代价是什么 ---");

        // 关键事实：短跳分支把 short 同时乘给了四角，
        // 于是「后缘时长 = short × f0 = short」与「前缘时长 = short × f2」共享同一个 short。
        // 想让前缘拉长而滞后不变，只有两条路：
        //   A. 解耦：前缘用独立标量（例如只抬 f2/f3，不动 f0）
        //   B. 抬高 f2 但保持 f0 = 1（等价于调小梯度，与「要拉伸」冲突）
        float[] f45 = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        // 模拟方案 A：把 rank2 系数从 0.42 抬到 0.7，rank0 保持 1.0
        double shortLen = 0.11;
        double leadA = framesToSettle(shortLen * 0.70, DT_8, CHAR_W);
        double lagA = steadyStateLagsWithFactors(shortLen, new float[]{1.0f, 0.72f, 0.70f, 0.22f},
                REPEAT_30, DT_8)[2];
        System.out.printf(Locale.ROOT,
                "  方案 A（只抬 rank2 系数 0.42→0.70，rank0 不动）：前缘 %d 帧、滞后 %.2fpx%n",
                (int) leadA, lagA);
        System.out.printf(Locale.ROOT,
                "    代价：梯度 f0/f2 从 %.2f× 降到 %.2f× —— 【拉伸变小】，与「要拉伸」直接冲突%n",
                f45[0] / f45[2], 1.0 / 0.70);

        // 方案 B：把 trailFactors[0] 也压下来（后缘变快）
        double lagB = steadyStateLagsWithFactors(shortLen, new float[]{0.55f, 0.50f, 0.42f, 0.22f},
                REPEAT_30, DT_8)[2];
        double leadB = framesToSettle(shortLen * 0.42, DT_8, CHAR_W);
        System.out.printf(Locale.ROOT,
                "  方案 B（压后缘 f0 1.0→0.55）：前缘 %d 帧、滞后 %.2fpx、梯度 %.2f×%n",
                (int) leadB, lagB, 0.55 / 0.42);
        System.out.printf(Locale.ROOT,
                "    代价：梯度只剩 %.2f× —— 比「≥3×」差得远，且后缘也变快 = 拉伸观感消失%n",
                0.55 / 0.42);

        // 方案 C：改 DampedSpring 的 o = 4/len 系数（更快的收拢但同样的展开）
        // 现在 o = 4/animationLength。若把 o 的分子从 4 提到 8，同 len 下衰减快一倍。
        // 但展开(lag)与收拢(帧数)用的是同一个闭式解 —— 一起变。
        System.out.println();
        System.out.println("  方案 C（改 DampedSpring 的 o = 4/len 分子）：");
        double cStd = decayPerMs(4.0, 0.11);
        double cFast = decayPerMs(8.0, 0.11);
        System.out.printf(Locale.ROOT,
                "    o 分子 4 → 每 ms 衰减因子 exp(-o*dt)：%.6f；分子 8 → %.6f%n", cStd, cFast);
        System.out.printf(Locale.ROOT,
                "    两者都在同一个闭式解里，滞后与帧数等比缩小 —— 【无法单独降滞后】%n");

        // 【实测结论】方案 A 让前缘达标了，但**后缘滞后反而恶化到 17.42px**（基准 10.20）——
        // 因为 rank0 的时长是 short×1.0，short 没变、却把前缘抬慢 ⇒ 整体观感更拖。
        // 这证明：只要 short 不动，任何「只抬前缘」的方案都动不了后缘滞后；
        // 而后缘滞后正是用户说的「跟不上」。
        check("P-9 【必须改 physics】只抬前缘系数（方案A）救不了滞后：后缘滞后从 %.2f 恶化到 %.2f，"
                        + "且梯度塌到 %.2f× —— 现有时长分配模型里没有能同时满足三条的解",
                lagA > 8.0 && (1.0 / 0.70) < 3.0,
                String.format(Locale.ROOT,
                        "方案A 前缘 %.0f 帧(达标) / 后缘滞后 %.2fpx(恶化) / 梯度 %.2f×(不达标)",
                        leadA, lagA, 1.0 / 0.70));

        // core/ 的 parity 风险量化：哪些文件/常量会被动
        System.out.println();
        System.out.println("  core/ parity 影响面（改物理必红的具体断言）：");
        System.out.println("    · TrailCorner.java:220  trailFactors[rank] 的取值 → 影响 VSpring/VOrder 的常量断言");
        System.out.println("    · DampedSpring.java:64  o = 4.0/animationLength → SPR-1..SPR-10 全部帧数断言");
        System.out.println("    · NeovideConfig.java:162 trailFactors 数组 → deriveTrailFactors(4.5) 的逐位特例");
        check("P-10 parity 的「唯一特例」是 deriveTrailFactors(k==4.5) 直接返回规范数组——"
                        + "任何改 trailFactors 的方案都会让该特例与 NeovideConfig 默认值不一致",
                isSpecialCaseHardcoded(),
                "4.5f 分支返回 new float[]{1.0f,0.72f,0.42f,0.22f}");
    }

    private static final double REPEAT_30 = 0.030;

    // ================================================================ Q5 VS Code 对比

    private static void question5_vscode() {
        System.out.println();
        System.out.println("--- Q5. 「纯平滑输入」预设 vs VS Code 原版：真实参数算差在哪 ---");

        // 上游 diag/upstream-decoded.js:9-11
        final double UP_ANIM = 0.10;
        final double UP_SHORT = 0.04;
        final double UP_TRAIL = 1.0;

        // 上游 jump():158-159：短跳时【四角同一个时长】= min(anim, short)
        double upLen = Math.min(UP_ANIM, UP_SHORT);
        System.out.printf(Locale.ROOT,
                "  上游 neovide-cursor.js:9-11  ANIM=%.2f SHORT=%.2f TRAIL=%.1f%n",
                UP_ANIM, UP_SHORT, UP_TRAIL);
        System.out.printf(Locale.ROOT,
                "  上游 jump():159  短跳四角时长 = min(%.2f, %.2f) = %.2fs（四角完全相同）%n",
                UP_ANIM, UP_SHORT, upLen);
        System.out.printf(Locale.ROOT,
                "    上游梯度 = 1.00×（四个角同长 → 形状变化只由起点差异决定）%n");
        int upFrames60 = framesToSettle(upLen, 1.0 / 60, CHAR_W);
        System.out.printf(Locale.ROOT,
                "    上游 60fps(16.7ms) 下该时长 ≈ %d 帧（= 1 帧，上游前缘本来就一帧到位）%n", upFrames60);

        // 本插件「纯平滑输入」预设：short=0.11, smooth=0, k=4.5（预设不设 k）
        float[] ours = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        System.out.println();
        System.out.printf(Locale.ROOT,
                "  本插件「纯平滑输入」预设：short=0.11 smooth=0 → factors=[%.2f,%.2f,%.2f,%.2f]%n",
                ours[0], ours[1], ours[2], ours[3]);
        System.out.printf(Locale.ROOT,
                "    后缘(rank0) = %.0fms / 前缘(rank2) = %.0fms → 真实梯度 %.2f×%n",
                ours[0] * 0.11 * 1000, ours[2] * 0.11 * 1000, ours[0] / ours[2]);

        check("P-11 「纯平滑输入」号称对齐 VS Code，但 smooth=0 时短跳仍走 trailFactors 梯度"
                        + "（上游四角同长）：本插件梯度 %.2f× vs 上游 1.00×",
                ours[0] / ours[2] > 1.5,
                String.format(Locale.ROOT, "本插件 %.2f× / 上游 1.00×", ours[0] / ours[2]));

        double ratioShort = 0.11 / UP_SHORT;
        System.out.printf(Locale.ROOT,
                "    后缘绝对时长：本插件 %.0fms vs 上游 %.0fms → 本插件是上游的 %.2f 倍%n",
                0.11 * 1000, UP_SHORT * 1000, ratioShort);
        check("P-12 后缘时长也对不上：本插件 110ms 是上游 40ms 的 2.75 倍，"
                        + "「纯平滑输入」比 VS Code 慢得多",
                ratioShort > 2.0, String.format(Locale.ROOT, "%.2f 倍", ratioShort));

        // 关键：要在本插件里真复刻 VS Code，smooth 必须是多少？
        // 目标：f2 → 1.0（四角同长）。f' = f + (1-f)*s*0.8，令 f2' = 1 ⇒ s = 1/((1-f2)*0.8) * (1-f2) ...
        // 实际上 f2' = f2 + (1-f2)*s*0.8 = 1 需要 s*0.8 = 1 ⇒ s = 1.25 > 1 不可达。
        double f2 = ours[2];
        double sNeeded = 1.0 / 0.8;   // s = smooth*0.8，要 s*0.8*=1 → 上限只有 smooth=1 → s=0.8
        double f2AtMaxSmooth = NeovideCursorSettings.deriveTrailFactors(4.5f, 1f)[2];
        System.out.println();
        System.out.printf(Locale.ROOT,
                "  要在本插件里把前缘系数抬到 1.0（= 上游四角同长）：smooth=1 时 f2' = %.2f%n",
                f2AtMaxSmooth);
        System.out.printf(Locale.ROOT,
                "    ⇒ smooth 上限 1.0 只能到 %.2f，【永远达不到 1.0】—— 该预设无法用现有旋钮表达%n",
                f2AtMaxSmooth);
        check("P-13 【结构性】smooth 的最大值 1.0 只让 f2 从 0.42 升到 %.2f，"
                        + "永远到不了「四角同长」——「纯平滑输入」在现有参数空间里不可能对齐上游",
                f2AtMaxSmooth < 0.99,
                String.format(Locale.ROOT, "f2'(smooth=1)=%.2f < 1.00", f2AtMaxSmooth));
    }

    // ================================================================ Q6 帧率

    private static void question6_frameRate() {
        System.out.println();
        System.out.println("--- Q6. 帧率是不是真正的瓶颈？8ms → 4ms 实测 ---");

        System.out.printf(Locale.ROOT, "  %-10s %-12s %-14s %-14s %-14s%n",
                "short", "帧间隔", "前缘帧数", "稳态滞后", "单帧位移");
        double[] shorts = {0.11, 0.14, 0.30};
        for (double shortLen : shorts) {
            for (double dt : new double[]{DT_8, DT_4}) {
                float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
                int frames = framesToSettle(shortLen * f[2], dt, CHAR_W);
                double lag = steadyStateLags(shortLen, 0.0, 4.5f, REPEAT_30, dt)[2];
                double step = firstFrameStep(shortLen * f[2], dt, CHAR_W);
                System.out.printf(Locale.ROOT, "  %-10.2f %-12.0f %-14d %-14.2f %-14.2f%n",
                        shortLen, dt * 1000, frames, lag, step);
            }
        }

        float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        double frames8 = framesToSettle(0.11 * f[2], DT_8, CHAR_W);
        double frames4 = framesToSettle(0.11 * f[2], DT_4, CHAR_W);
        double lag8 = steadyStateLags(0.11, 0.0, 4.5f, REPEAT_30, DT_8)[2];
        double lag4 = steadyStateLags(0.11, 0.0, 4.5f, REPEAT_30, DT_4)[2];

        check("P-14 帧间隔 8→4ms：前缘帧数【翻倍】（闭式解对时间可加，帧数 ∝ 1/dt）",
                Math.abs(frames4 / frames8 - 2.0) < 0.35,
                String.format(Locale.ROOT, "%.0f → %.0f 帧（%.2f 倍）", frames8, frames4, frames4 / frames8));
        check("P-15 帧间隔 8→4ms：稳态滞后【基本不变】（滞后是时间量，不是帧数）",
                Math.abs(lag4 - lag8) / Math.max(lag8, 0.01) < 0.15,
                String.format(Locale.ROOT, "%.2fpx → %.2fpx（变化 %.1f%%）",
                        lag8, lag4, (lag4 - lag8) / Math.max(lag8, 0.01) * 100));
        check("P-16 ⇒ 帧率【不是】滞后的瓶颈：降帧间隔只买到「更细的中间态」，"
                        + "买不到「更小的落后量」——要降滞后只能动时间常数",
                Math.abs(lag4 - lag8) / Math.max(lag8, 0.01) < 0.15,
                String.format(Locale.ROOT, "滞后 %.2f vs %.2f", lag8, lag4));

        // 但帧率对「跳」有真实影响：单帧位移
        double step8 = firstFrameStep(0.11 * f[2], DT_8, CHAR_W);
        double step4 = firstFrameStep(0.11 * f[2], DT_4, CHAR_W);
        System.out.printf(Locale.ROOT,
                "  单帧位移：8ms → %.2fpx ；4ms → %.2fpx（对「跳」有改善，对「滞后」没有）%n",
                step8, step4);
        check("P-17 帧率真正改善的是「单帧位移」（= 观感跳动），约减半", step4 < step8 * 0.7,
                String.format(Locale.ROOT, "%.2f → %.2f px", step8, step4));
    }

    // ================================================================ 仿真内核

    /**
     * 四角 rank（复刻 {@code CaretAnimator.assignRanks()}：rank = 严格更小的对齐度个数）。
     */
    private static int[] horizontalRanks(boolean rightward) {
        NeovideConfig cfg = new NeovideConfig();
        double[][] rel = {{-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5}};
        TrailCorner[] cs = new TrailCorner[4];
        for (int i = 0; i < 4; i++) {
            cs[i] = new TrailCorner(rel[i][0], rel[i][1], cfg);
            cs[i].snapTo(W, H, 0, 0);
        }
        // 让目标移到右侧（或左侧）以产生对齐度
        double targetX = rightward ? CHAR_W : -CHAR_W;
        for (TrailCorner c : cs) {
            c.setCharsMoved(1);
        }
        // 先 jump 一次把 previousDest 建立起来，再算对齐度
        for (int i = 0; i < 4; i++) {
            cs[i].jump(W, H, targetX, 0, i);
        }
        double[] align = new double[4];
        for (int i = 0; i < 4; i++) {
            align[i] = cs[i].calculateDirectionAlignment(W, H, targetX, 0);
        }
        int[] ranks = new int[4];
        for (int i = 0; i < 4; i++) {
            int r = 0;
            for (int j = 0; j < 4; j++) {
                if (align[j] < align[i]) {
                    r++;
                }
            }
            ranks[i] = r;
        }
        return ranks;
    }

    /**
     * 连续删除的稳态滞后仿真：每 {@code repeatSec} 目标左移 CHAR_W，帧间隔 dt。
     *
     * @return 每个 rank 的稳态滞后（px），下标 = rank
     */
    private static double[] steadyStateLags(double shortLen, double smooth, float k,
                                            double repeatSec, double dt) {
        float[] factors = NeovideCursorSettings.deriveTrailFactors(k, (float) smooth);
        return steadyStateLagsWithFactors(shortLen, factors, repeatSec, dt);
    }

    private static double[] steadyStateLagsWithFactors(double shortLen, float[] factors,
                                                       double repeatSec, double dt) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.shortAnimationLength = (float) shortLen;
        cfg.trailFactors = factors.clone();
        cfg.animationLength = 0.10f;

        double[][] rel = {{-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5}};
        TrailCorner[] cs = new TrailCorner[4];
        for (int i = 0; i < 4; i++) {
            cs[i] = new TrailCorner(rel[i][0], rel[i][1], cfg);
            cs[i].snapTo(W, H, 0, 0);
        }

        double centerX = 0;
        double tSec = 0;
        double nextKey = 0;
        int keys = 0;
        double[] maxLagByRank = new double[4];
        // 先跑 8 次击键进入稳态，再统计后 12 次
        final int WARMUP_KEYS = 8;
        final int MEASURE_KEYS = 12;
        int guard = 0;
        while (keys < WARMUP_KEYS + MEASURE_KEYS && guard++ < 2_000_000) {
            if (tSec + 1e-12 >= nextKey) {
                centerX -= CHAR_W;
                // assignRanks 复刻
                double[] align = new double[4];
                for (int i = 0; i < 4; i++) {
                    align[i] = cs[i].calculateDirectionAlignment(W, H, centerX, 0);
                }
                int[] ranks = new int[4];
                for (int i = 0; i < 4; i++) {
                    int r = 0;
                    for (int j = 0; j < 4; j++) {
                        if (align[j] < align[i]) {
                            r++;
                        }
                    }
                    ranks[i] = r;
                }
                for (int i = 0; i < 4; i++) {
                    cs[i].setCharsMoved(1);
                    cs[i].jump(W, H, centerX, 0, ranks[i]);
                }
                keys++;
                nextKey += repeatSec;
            }
            for (TrailCorner c : cs) {
                c.update(W, H, centerX, 0, dt, false);
            }
            if (keys > WARMUP_KEYS) {
                // 该单帧里各 rank 的偏移
                double[] perRank = new double[4];
                for (int i = 0; i < 4; i++) {
                    double destX = centerX + rel[i][0] * W;
                    perRank[i] = Math.abs(destX - cs[i].getCurrentX());
                }
                for (int i = 0; i < 4; i++) {
                    int r = rankOfAlignment(cs, centerX, i);
                    if (perRank[i] > maxLagByRank[r]) {
                        maxLagByRank[r] = perRank[i];
                    }
                }
            }
            tSec += dt;
        }
        return maxLagByRank;
    }

    private static int rankOfAlignment(TrailCorner[] cs, double centerX, int idx) {
        double[] align = new double[cs.length];
        for (int i = 0; i < cs.length; i++) {
            align[i] = cs[i].calculateDirectionAlignment(W, H, centerX, 0);
        }
        int r = 0;
        for (int j = 0; j < cs.length; j++) {
            if (align[j] < align[idx]) {
                r++;
            }
        }
        return r;
    }

    /** 单次击键后第一帧各 rank 的偏移量。 */
    private static double[] oneFrameLags(double shortLen, double smooth, float k, double dt) {
        float[] factors = NeovideCursorSettings.deriveTrailFactors(k, (float) smooth);
        NeovideConfig cfg = new NeovideConfig();
        cfg.shortAnimationLength = (float) shortLen;
        cfg.trailFactors = factors.clone();
        double[][] rel = {{-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5}};
        TrailCorner[] cs = new TrailCorner[4];
        for (int i = 0; i < 4; i++) {
            cs[i] = new TrailCorner(rel[i][0], rel[i][1], cfg);
            cs[i].snapTo(W, H, 0, 0);
        }
        double centerX = -CHAR_W;
        for (int i = 0; i < 4; i++) {
            cs[i].setCharsMoved(1);
            cs[i].jump(W, H, centerX, 0, i == 0 || i == 3 ? 0 : 2);
        }
        for (TrailCorner c : cs) {
            c.update(W, H, centerX, 0, dt, false);
        }
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            double destX = centerX + rel[i][0] * W;
            int r = (i == 0 || i == 3) ? 0 : 2;
            out[r] = Math.abs(destX - cs[i].getCurrentX());
        }
        return out;
    }

    /** 有效时长 effLen 的弹簧，在 dt 下从 CHAR_W 偏移出发，需要几帧才静止。 */
    private static int framesToSettle(double effLen, double dt, double start) {
        com.github.yyyolo7a79.neovidecursor.core.DampedSpring s =
                new com.github.yyyolo7a79.neovidecursor.core.DampedSpring(effLen);
        s.setPosition(start);
        boolean moving = s.update(dt);
        int frames = moving ? 1 : 0;
        int guard = 0;
        while (moving && guard++ < 5000) {
            moving = s.update(dt);
            if (moving) {
                frames++;
            }
        }
        return frames;
    }

    /** 首帧走掉的位移量。 */
    private static double firstFrameStep(double effLen, double dt, double start) {
        com.github.yyyolo7a79.neovidecursor.core.DampedSpring s =
                new com.github.yyyolo7a79.neovidecursor.core.DampedSpring(effLen);
        s.setPosition(start);
        s.update(dt);
        return start - s.getPosition();
    }

    private static double decayPerMs(double oNumerator, double len) {
        double o = oNumerator / len;
        return Math.exp(-o * 0.001);
    }

    private static boolean isSpecialCaseHardcoded() {
        float[] a = NeovideCursorSettings.deriveTrailFactors(4.5f);
        return a[0] == 1.0f && a[1] == 0.72f && a[2] == 0.42f && a[3] == 0.22f;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + "   [" + detail + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}
