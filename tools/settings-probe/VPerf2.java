
import com.github.yyyolo7a79.neovidecursor.core.DampedSpring;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.util.Locale;

/**
 * V-PERF2 —— refresh-auditor（t6）第二支：把「前缘 / 后缘」分开量。
 *
 * <p>为什么必须分开：{@code VPerf} 实测纯横向打字的 rank 集合是 {0, 2}，
 * 而「拉伸」= 后缘滞后 − 前缘滞后，「跟手」= 前缘滞后。两者由不同的系数驱动，
 * 混在一起谈「滞后」会得出互相矛盾的结论。
 *
 * <p>本支还负责核对任务书给出的那组数字（梯度 1.20/2.08/2.94/7.69×、
 * 前缘 27/16/11/5 帧、滞后 22.81/11.37/7.82/3.25px）到底用的是哪一档系数。
 */
public final class VPerf2 {

    private static int pass = 0, fail = 0;

    private static final double DT = 0.008;
    private static final double W = 2.0, H = 16.0, CHAR_W = 8.0;
    private static final double REPEAT = 0.030;

    public static void main(String[] args) {
        System.out.println("=== V-PERF2：前缘 / 后缘分离量化 —— 「跟手」与「拉伸」是两个不同的量 ===");

        crossCheckTaskNumbers();
        edgeResolved();
        feasibleWithEdges();
        upstreamFirstFrame();

        System.out.println();
        System.out.printf("=== V-PERF2 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-PERF2 RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-PERF2 RESULT: ALL PASS");
        System.exit(0);
    }

    // ================================================================ 核对任务书数字

    private static void crossCheckTaskNumbers() {
        System.out.println();
        System.out.println("--- 核对：任务书那组数字用的是哪一档系数？（k=8, short=0.14）---");

        double shortLen = 0.14;
        // 任务书四行
        double[] sm = {1.0, 0.5, 0.3, 0.0};
        double[] taskGrad = {1.20, 2.08, 2.94, 7.69};
        double[] taskFrames = {27, 16, 11, 5};
        double[] taskLag = {22.81, 11.37, 7.82, 3.25};

        System.out.printf(Locale.ROOT, "  %-8s %-10s %-10s %-12s %-12s%n",
                "smooth", "f2(k=8)", "f3(k=8)", "任务书梯度", "f0/f2 / f0/f3");
        double errF2 = 0, errF3 = 0;
        for (int i = 0; i < sm.length; i++) {
            float[] f = NeovideCursorSettings.deriveTrailFactors(8.0f, (float) sm[i]);
            double g2 = f[0] / f[2];
            double g3 = f[0] / f[3];
            errF2 += Math.abs(g2 - taskGrad[i]);
            errF3 += Math.abs(g3 - taskGrad[i]);
            System.out.printf(Locale.ROOT, "  %-8.2f %-10.2f %-10.2f %-12.2f %.2f / %.2f%n",
                    sm[i], f[2], f[3], taskGrad[i], g2, g3);
        }
        System.out.printf(Locale.ROOT,
                "  与任务书梯度的累计误差：用 f2 => %.2f ；用 f3 => %.2f%n", errF2, errF3);
        check("C-1 任务书的「梯度」列用的是【rank3 系数(0.22 系)】，不是纯横向真正用到的 rank2",
                errF3 < errF2,
                String.format(Locale.ROOT, "f3 误差 %.2f < f2 误差 %.2f", errF3, errF2));

        // 再验证滞后列：按「滞后 ∝ 有效时长」比较两档的比值
        System.out.println();
        System.out.printf(Locale.ROOT, "  %-8s %-14s %-14s %-14s%n",
                "smooth", "任务书滞后比", "f2 比值", "f3 比值");
        double e2 = 0, e3 = 0;
        for (int i = 1; i < sm.length; i++) {
            float[] fNow = NeovideCursorSettings.deriveTrailFactors(8.0f, (float) sm[i]);
            double tRatio = taskLag[i] / taskLag[3];
            double r2 = fNow[2] / NeovideCursorSettings.deriveTrailFactors(8.0f, 0f)[2];
            double r3 = fNow[3] / NeovideCursorSettings.deriveTrailFactors(8.0f, 0f)[3];
            e2 += Math.abs(r2 - tRatio);
            e3 += Math.abs(r3 - tRatio);
            System.out.printf(Locale.ROOT, "  %-8.2f %-14.2f %-14.2f %-14.2f%n",
                    sm[i], tRatio, r2, r3);
        }
        System.out.printf(Locale.ROOT,
                "  与任务书滞后比的累计误差：f2 => %.2f ；f3 => %.2f%n", e2, e3);
        check("C-2 任务书的「滞后」列同样按 rank3（f3）缩放 —— 两列口径一致，都是 rank3",
                e3 < e2, String.format(Locale.ROOT, "f3 误差 %.2f < f2 误差 %.2f", e3, e2));

        // 前缘帧数列：用真 DampedSpring 数一下两档各多少帧
        System.out.println();
        System.out.printf(Locale.ROOT, "  %-8s %-14s %-14s %-16s%n",
                "smooth", "任务书帧数", "f2 实测帧", "f3 实测帧");
        for (int i = 0; i < sm.length; i++) {
            float[] f = NeovideCursorSettings.deriveTrailFactors(8.0f, (float) sm[i]);
            int n2 = frames(shortLen * f[2]);
            int n3 = frames(shortLen * f[3]);
            System.out.printf(Locale.ROOT, "  %-8.2f %-14.0f %-14d %-16d%n",
                    sm[i], taskFrames[i], n2, n3);
        }
        // 【实测修正了我自己的怀疑】我先猜「任务书帧数 = 时长/dt 的算术商」，错了。
        // 0.14 × 0.13 / 0.008 = 2.27，而 DampedSpring 实测正好 5 帧 —— 任务书写的就是【实测帧】，
        // 而且那一列的口径是 rank3（f3），不是纯横向真正用到的 rank2。
        float[] f0 = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        int real3 = frames(shortLen * f0[3]);
        int real2 = frames(shortLen * f0[2]);
        System.out.printf(Locale.ROOT,
                "    smooth=0 时：任务书 5 帧 = f3 实测 %d 帧（吻合）；若用 f2 则为 %d 帧%n", real3, real2);
        check("C-3 任务书的「前缘帧数」列 = rank3 下的 DampedSpring 实测帧数"
                        + "（smooth=0 时 5 帧逐值吻合），不是算术商 2.27；"
                        + "若按纯横向真实用到的 rank2 则为 " + real2 + " 帧（约 2.6 倍）",
                real3 == 5 && real2 > real3 * 2,
                String.format(Locale.ROOT, "f3 实测=%d f2 实测=%d 算术商=%.2f",
                        real3, real2, shortLen * f0[3] / DT));
    }

    // ================================================================ 前后缘分离

    private static void edgeResolved() {
        System.out.println();
        System.out.println("--- 前缘(rank2) vs 后缘(rank0)：谁是「跟手」，谁是「拉伸」---");

        System.out.println("  k=8, short=0.14, 连删间隔 30ms：");
        System.out.printf(Locale.ROOT, "  %-8s %-14s %-14s %-14s %-12s%n",
                "smooth", "前缘滞后px", "后缘滞后px", "拉伸px", "前缘帧");
        for (double s : new double[]{0.0, 0.3, 0.5, 1.0}) {
            double[] lags = lagByRank(0.14, s, 8.0f);
            float[] f = NeovideCursorSettings.deriveTrailFactors(8.0f, (float) s);
            System.out.printf(Locale.ROOT, "  %-8.2f %-14.2f %-14.2f %-14.2f %-12d%n",
                    s, lags[2], lags[0], lags[0] - lags[2], frames(0.14 * f[2]));
        }

        // smooth↑ 会拉长前缘 = 前缘更慢 = 「跟手」变差；但后缘不动
        double[] at0 = lagByRank(0.14, 0.0, 8.0f);
        double[] at1 = lagByRank(0.14, 1.0, 8.0f);
        check("C-4 【与直觉相反】smooth↑ 让**前缘滞后变大**（%.2f→%.2f），后缘纹丝不动（%.2f）——"
                        + "因为 f0 被钉死在 1.0，smooth 只抬前三档",
                at1[2] > at0[2] && Math.abs(at1[0] - at0[0]) < 0.05,
                String.format(Locale.ROOT, "前缘 %.2f→%.2f / 后缘 %.2f→%.2f",
                        at0[2], at1[2], at0[0], at1[0]));

        // k↑ 让前缘更快（系数变小）
        double[] atK8 = lagByRank(0.14, 0.0, 8.0f);
        double[] atK45 = lagByRank(0.14, 0.0, 4.5f);
        check("C-5 k↑ 让前缘更快（滞后 %.2f→%.2f），后缘同样不动",
                atK8[2] < atK45[2] && Math.abs(atK8[0] - atK45[0]) < 0.05,
                String.format(Locale.ROOT, "k=4.5 前缘 %.2f → k=8 前缘 %.2f / 后缘恒 %.2f",
                        atK45[2], atK8[2], atK8[0]));

        // short 同时动两边
        double[] short08 = lagByRank(0.08, 0.0, 8.0f);
        check("C-6 只有 shortAnimationLength 同时动前缘与后缘（唯一的总闸）",
                short08[0] < atK8[0] && short08[2] < atK8[2],
                String.format(Locale.ROOT, "short 0.14→0.08：后缘 %.2f→%.2f，前缘 %.2f→%.2f",
                        atK8[0], short08[0], atK8[2], short08[2]));

        // 「跟不上」到底看哪个？—— 给出两个口径的答案
        System.out.println();
        System.out.println("  【Q2 答案】用户说「删除快 光标跟不上」，看的是**前缘**"
                + "（覆盖层就是唯一可见光标，前缘=眼睛认定的光标位置）");
        System.out.println("  但「拖尾有多长」看的是**后缘**。两者必须分开谈：");
        System.out.printf(Locale.ROOT,
                "    前缘滞后 %.2fpx（可被 smooth↑ / k↓ / short↓ 三者影响，但前者都以牺牲拉伸换）%n",
                atK8[2]);
        System.out.printf(Locale.ROOT,
                "    后缘滞后 %.2fpx（只被 short↓ 影响；smooth 与 k 完全无效）%n", atK8[0]);
    }

    // ================================================================ 可行域（分前/后缘）

    private static void feasibleWithEdges() {
        System.out.println();
        System.out.println("--- 可行域：改用「前缘滞后 ≤8px」与「后缘滞后 ≤8px」两个口径分别跑 ---");

        for (double k : new double[]{4.5, 8.0}) {
            int nLead = 0, nTrail = 0, total = 0;
            String bestLead = "", bestTrail = "";
            double bsLead = -1, bsTrail = -1;
            for (int i = 0; i <= 22; i++) {
                double shortLen = 0.08 + i * 0.01;
                if (shortLen > 0.30001) {
                    break;
                }
                for (int j = 0; j <= 20; j++) {
                    double s = j * 0.05;
                    total++;
                    float[] f = NeovideCursorSettings.deriveTrailFactors((float) k, (float) s);
                    double grad = f[0] / f[2];
                    double fFrames = frames(shortLen * f[2]);
                    double[] lags = lagByRank(shortLen, s, (float) k);
                    String desc = String.format(Locale.ROOT,
                            "short=%.2f smooth=%.2f 梯度%.2f× 前缘%.0f帧 前缘滞后%.2f 后缘滞后%.2f",
                            shortLen, s, grad, fFrames, lags[2], lags[0]);
                    if (grad >= 3.0 && fFrames >= 10 && lags[2] <= 8.0) {
                        nLead++;
                    }
                    if (grad >= 3.0 && fFrames >= 10 && lags[0] <= 8.0) {
                        nTrail++;
                    }
                    double scLead = Math.min(grad / 3.0, Math.min(fFrames / 10.0, 8.0 / Math.max(lags[2], 0.01)));
                    double scTrail = Math.min(grad / 3.0, Math.min(fFrames / 10.0, 8.0 / Math.max(lags[0], 0.01)));
                    if (scLead > bsLead) {
                        bsLead = scLead;
                        bestLead = desc;
                    }
                    if (scTrail > bsTrail) {
                        bsTrail = scTrail;
                        bestTrail = desc;
                    }
                }
            }
            System.out.printf(Locale.ROOT,
                    "  k=%.1f（%d 组）：前缘口径可行 %d 组 ；后缘口径可行 %d 组%n",
                    k, total, nLead, nTrail);
            System.out.println("      前缘口径最接近：" + bestLead);
            System.out.println("      后缘口径最接近：" + bestTrail);
        }

        // 结构性：前缘帧 ≥10 需要的 short，与后缘 ≤8px 允许的 short，是两个不相交的区间
        double needFor10;
        double allowFor8;
        // 用二分找「后缘滞后 ≤ 8px」允许的最大 short
        double lo = 0.02, hi = 0.30;
        for (int it = 0; it < 40; it++) {
            double mid = (lo + hi) / 2;
            if (lagByRank(mid, 0.0, 8.0f)[0] <= 8.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        allowFor8 = lo;
        needFor10 = 10 * DT / NeovideCursorSettings.deriveTrailFactors(8.0f, 0f)[2];
        System.out.println();
        System.out.printf(Locale.ROOT,
                "  结构性证明（k=8, smooth=0）：%n");
        System.out.printf(Locale.ROOT,
                "    前缘 ≥10 帧 需要 short ≥ %.3fs%n", needFor10);
        System.out.printf(Locale.ROOT,
                "    后缘 ≤8px 允许 short ≤ %.3fs%n", allowFor8);
        System.out.printf(Locale.ROOT,
                "    两区间%s —— 这就是可行域为空的数学原因%n",
                needFor10 > allowFor8 ? "【不相交】" : "相交");
        check("C-7 【可行域为空的数学原因】「前缘帧数≥10」的下界与「后缘滞后≤8px」的上界不相交",
                needFor10 > allowFor8,
                String.format(Locale.ROOT, "need short≥%.3f 但 allow short≤%.3f（差 %.3f）",
                        needFor10, allowFor8, needFor10 - allowFor8));
    }

    // ================================================================ 上游首帧

    private static void upstreamFirstFrame() {
        System.out.println();
        System.out.println("--- 上游 VS Code 原版在它自己的 60fps 下有多「跳」---");
        double upLen = 0.04;   // min(anim=0.10, short=0.04)
        double dt60 = 1.0 / 60;
        DampedSpring s = new DampedSpring(upLen);
        s.setPosition(CHAR_W);
        s.update(dt60);
        double firstStep = CHAR_W - s.getPosition();
        double ratio = firstStep / CHAR_W;
        System.out.printf(Locale.ROOT,
                "  上游短跳时长 %.2fs @60fps：首帧走掉 %.2fpx = 总位移的 %.0f%%%n",
                upLen, firstStep, ratio * 100);
        // 本插件「纯平滑输入」short=0.11, rank2
        float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        DampedSpring me = new DampedSpring(0.11 * f[2]);
        me.setPosition(CHAR_W);
        me.update(DT);
        double myStep = CHAR_W - me.getPosition();
        System.out.printf(Locale.ROOT,
                "  本插件「纯平滑输入」short=0.11 rank2 @8ms：首帧走掉 %.2fpx = %.0f%%%n",
                myStep, myStep / CHAR_W * 100);
        check("C-8 上游前缘其实【比本插件更跳】（首帧走掉 "
                        + String.format(Locale.ROOT, "%.0f%%", ratio * 100)
                        + " vs 本插件 " + String.format(Locale.ROOT, "%.0f%%", myStep / CHAR_W * 100)
                        + "）——「对齐 VS Code 原版」若按字面执行会让观感更跳，不是更平滑",
                ratio > myStep / CHAR_W,
                String.format(Locale.ROOT, "上游 %.0f%% / 本插件 %.0f%%",
                        ratio * 100, myStep / CHAR_W * 100));
    }

    // ================================================================ 仿真内核

    /** 干燥后的每 rank 稳态滞后。rank 0 = 后缘（系数 1.0），rank 2 = 前缘（横向）。 */
    private static double[] lagByRank(double shortLen, double smooth, float k) {
        float[] factors = NeovideCursorSettings.deriveTrailFactors(k, (float) smooth);
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

        double centerX = 0, t = 0, nextKey = 0;
        int keys = 0;
        double[] maxLag = new double[4];
        final int WARM = 8, MEAS = 12;
        int guard = 0;
        while (keys < WARM + MEAS && guard++ < 3_000_000) {
            if (t + 1e-12 >= nextKey) {
                centerX -= CHAR_W;
                int[] ranks = ranksFor(cs, centerX);
                for (int i = 0; i < 4; i++) {
                    cs[i].setCharsMoved(1);
                    cs[i].jump(W, H, centerX, 0, ranks[i]);
                }
                keys++;
                nextKey += REPEAT;
            }
            for (TrailCorner c : cs) {
                c.update(W, H, centerX, 0, DT, false);
            }
            if (keys > WARM) {
                for (int i = 0; i < 4; i++) {
                    double destX = centerX + rel[i][0] * W;
                    double lag = Math.abs(destX - cs[i].getCurrentX());
                    int r = rankForIndex(cs, centerX, i);
                    if (lag > maxLag[r]) {
                        maxLag[r] = lag;
                    }
                }
            }
            t += DT;
        }
        return maxLag;
    }

    private static int[] ranksFor(TrailCorner[] cs, double centerX) {
        double[] a = new double[cs.length];
        for (int i = 0; i < cs.length; i++) {
            a[i] = cs[i].calculateDirectionAlignment(W, H, centerX, 0);
        }
        int[] r = new int[cs.length];
        for (int i = 0; i < cs.length; i++) {
            int n = 0;
            for (int j = 0; j < cs.length; j++) {
                if (a[j] < a[i]) {
                    n++;
                }
            }
            r[i] = n;
        }
        return r;
    }

    private static int rankForIndex(TrailCorner[] cs, double centerX, int idx) {
        return ranksFor(cs, centerX)[idx];
    }

    private static int frames(double effLen) {
        DampedSpring s = new DampedSpring(effLen);
        s.setPosition(CHAR_W);
        boolean moving = s.update(DT);
        int n = moving ? 1 : 0;
        int guard = 0;
        while (moving && guard++ < 5000) {
            moving = s.update(DT);
            if (moving) {
                n++;
            }
        }
        return n;
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
