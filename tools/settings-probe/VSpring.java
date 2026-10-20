import com.github.yyyolo7a79.neovidecursor.core.DampedSpring;

import java.util.Locale;

/**
 * verifier 自建（t4）：独立复算「上限 0.35 / 下限 0.05」的量化依据（契约 §7.2）。
 *
 * <p>驱动**真实的** {@link DampedSpring}，dt = 8 ms（= ACTIVE_DELAY_MS），
 * 初始位移 32 px（= 一行），数到静止为止的帧数。
 *
 * <p>这条不是「相信契约的 A-11」，而是我自己跑一遍 —— 契约的两个数字
 * （0.35 → 115 帧 / 920ms、0.50 → 164 帧 / 1312ms）必须能复现。
 */
public final class VSpring {

    private static int pass = 0;
    private static int fail = 0;

    private static final double DT = 8.0 / 1000.0;      // ACTIVE_DELAY_MS = 8
    private static final double START = 32.0;           // 一行 ≈ 32 px

    public static void main(String[] args) {
        System.out.println("=== V-SPRING：animationLength 上下限的量化依据（真实 DampedSpring，dt=8ms）===");
        System.out.printf(Locale.ROOT,
                "  %-10s %-10s %-10s %-10s %-14s%n", "animLen", "首帧走完", "运动帧数", "运动时长", "首帧后静止");

        int frames035 = 0;
        int frames050 = 0;
        boolean shortCircuit005 = false;

        // 0.02 / 0.03 / 0.04 = shortAnimationLength 滑块量程的低端（契约 §7.1 给 0.02，
        // 而 §9.4 的 W_SHORT_LEN 文案说「下限 0.05s 是硬边界」—— 这两个数字互相矛盾，
        // 这里把低端实际行为量出来，供 VERIFY-SETTINGS-V2.md 的 F-1 引用）。
        double[] lens = {0.02, 0.03, 0.04, 0.05, 0.06, 0.10, 0.15, 0.28, 0.35, 0.40, 0.50};
        for (double len : lens) {
            DampedSpring s = new DampedSpring(len);
            s.setPosition(START);

            // 第一帧：拿「首帧走完百分比」
            boolean moving = s.update(DT);
            double afterFirst = s.getPosition();
            double firstPct = (START - afterFirst) / START * 100.0;
            boolean firstFrameStop = !moving;

            int frames = moving ? 1 : 0;
            while (moving) {
                moving = s.update(DT);
                if (moving) {
                    frames++;
                }
                if (frames > 5000) {
                    break;
                }
            }
            long ms = Math.round(frames * DT * 1000.0);
            System.out.printf(Locale.ROOT, "  %-10.2f %-10.2f %-10d %-10d %-14s%n",
                    len, firstPct, frames, ms, String.valueOf(firstFrameStop));
            if (Math.abs(len - 0.35) < 1e-9) {
                frames035 = frames;
            }
            if (Math.abs(len - 0.50) < 1e-9) {
                frames050 = frames;
            }
            if (Math.abs(len - 0.05) < 1e-9) {
                shortCircuit005 = firstFrameStop;
            }
        }

        System.out.println();
        check("SPR-1 上端 0.35 的运动帧数在 100–130 之间（契约 A-11 报 115）",
                frames035 >= 100 && frames035 <= 130, "实测 " + frames035 + " 帧");
        check("SPR-2 0.50 的运动帧数在 150–180 之间（契约 A-11 报 164）",
                frames050 >= 150 && frames050 <= 180, "实测 " + frames050 + " 帧");
        check("SPR-3 0.35 与 0.50 的帧数差 > 30（上限确有量化意义，不是拍的）",
                frames050 - frames035 > 30, "差 " + (frames050 - frames035) + " 帧");
        // 注意一个容易读错的细节：0.05 **不是**「任何 dt 下都瞬移」。
        // 正常帧 dt=8ms 时 0.05 能跑 16 帧 / 128ms（与契约 A-11 的表格逐值吻合）；
        // 只有某一帧的 dt 冲到 MAX_DT=0.05s（即一次 ≥50ms 的停顿/恢复）才会被首句短路归零。
        // 契约 §7.2 的原话是「animationLength ≤ dt 时归零，而 MAX_DT = 0.05」，
        // 所以这里是「最坏帧下的硬边界」，不是「永远瞬移」。
        check("SPR-4 下端 0.05 在正常帧 dt=8ms 下【不会】首帧归零（16 帧 / 128ms）",
                !shortCircuit005 && framesAt(lens, 0.05) == 16,
                "首帧静止=" + shortCircuit005 + " 帧数=" + framesAt(lens, 0.05));

        // 下限的另一种写法：0.05 配 dt=0.05（MAX_DT）也立即静止
        DampedSpring maxDt = new DampedSpring(0.05);
        maxDt.setPosition(START);
        boolean moved = maxDt.update(0.05);
        check("SPR-5 animationLength=0.05 且 dt=MAX_DT(0.05) 时第一次 update 即归零",
                !moved && maxDt.getPosition() == 0.0, "moved=" + moved);

        // 0.06 应该至少能跑一帧（契约说 0.06 才是实际最小推荐值）
        DampedSpring s006 = new DampedSpring(0.06);
        s006.setPosition(START);
        check("SPR-6 animationLength=0.06 配 dt=0.05 时不会一帧归零",
                s006.update(0.05), "");

        // ---- 短跳分支：短动画时长要 × trailFactors[rank]（TrailCorner.jump():221）----
        // 契约 §7.1 给 shortAnimationLength 的下限 0.02，但 §9.4 的 W_SHORT_LEN 文案说
        // 「下限 0.05s 是硬边界」。两者矛盾。这里把「乘完系数之后」的实际有效时长量出来：
        // 真正决定观感的是 rank3（前缘，系数 0.22），不是滑块本身的值。
        float[] factors = com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings
                .deriveTrailFactors(4.5f);
        System.out.println();
        System.out.println("  短跳分支有效时长 = shortAnimationLength × trailFactors[rank]（默认 4.5×）");
        System.out.printf(Locale.ROOT, "    trailFactors = [%.2f, %.2f, %.2f, %.2f]%n",
                factors[0], factors[1], factors[2], factors[3]);
        int shortCircuitAt02 = 0;
        for (double shortLen : new double[]{0.02, 0.05, 0.11, 0.25}) {
            System.out.printf(Locale.ROOT, "    short=%.2f：", shortLen);
            for (int rank = 0; rank < 4; rank++) {
                double eff = shortLen * factors[rank];
                DampedSpring s = new DampedSpring(eff);
                s.setPosition(START);
                boolean moving = s.update(DT);
                System.out.printf(Locale.ROOT, " rank%d=%.1fms%s", rank, eff * 1000.0,
                        moving ? "" : "(一帧归零)");
                if (!moving && rank == 3 && Math.abs(shortLen - 0.02) < 1e-9) {
                    shortCircuitAt02++;
                }
            }
            System.out.println();
        }
        check("SPR-7 shortAnimationLength=0.02（滑块下限）时，rank3 前缘的有效时长 4.4ms "
                        + "低于一个 8ms 帧 → 该分支被 DampedSpring 首句短路（这是契约 §7.1 与 §9.4 的"
                        + "「下限 0.05s」互相矛盾的可量化证据）",
                shortCircuitAt02 == 1, "rank3 归零 = " + (shortCircuitAt02 == 1));

        // ---- 前缘分支在「正常帧」下的失效边界（供 F-1 的文案精确化）----
        // DampedSpring.update 的首句是 animationLength <= dt 则归零。
        // 正常帧 dt = 8ms，故 rank3 有效时长 = short x 0.22 必须 > 8ms，
        // 即 short > 8 / 0.22 / 1000 = 0.03636...。这里把边界**实测**出来，不靠算术推。
        System.out.println();
        System.out.println("  前缘（rank3 = short x 0.22）在正常帧 dt=8ms 下的失效边界（实测）：");
        double boundary = -1.0;
        for (int ticks = 1; ticks <= 10; ticks++) {
            double shortLen = ticks / 100.0;
            double eff = shortLen * factors[3];
            DampedSpring sp = new DampedSpring(eff);
            sp.setPosition(START);
            boolean moving = sp.update(DT);
            System.out.printf(Locale.ROOT, "    short=%.2f  rank3=%5.1fms  %s%n",
                    shortLen, eff * 1000.0, moving ? "能跑" : "一帧归零");
            if (moving && boundary < 0) {
                boundary = shortLen;
            }
        }
        check("SPR-8 实测边界：short=0.03 时前缘一帧归零、short=0.04 时能跑"
                        + "（算术边界 8/0.22/1000 = 0.0364，落在两者之间）",
                boundary > 0.03 && boundary < 0.05,
                "第一个能跑的刻度 = " + boundary);

        // ---- 第二个分界：长帧（dt = MAX_DT = 50ms）下的失效边界（供 F-1 的两边界文案）----
        // 同理 animationLength <= dt 则归零，长帧 dt = 50ms，
        // 故 rank3 = short x 0.22 必须 > 50ms，即 short > 50 / 0.22 / 1000 = 0.2273。
        // captain 原文案把「0.05」当成 50ms 口径的阈值 —— 这里实测核对真实阈值。
        System.out.println();
        System.out.println("  前缘（rank3 = short x 0.22）在长帧 dt=MAX_DT=50ms 下的失效边界（实测）：");
        double longBoundary = -1.0;
        for (int ticks = 18; ticks <= 25; ticks++) {
            double shortLen = ticks / 100.0;
            double eff = shortLen * factors[3];
            DampedSpring sp = new DampedSpring(eff);
            sp.setPosition(START);
            boolean moving = sp.update(0.05);
            System.out.printf(Locale.ROOT, "    short=%.2f  rank3=%5.1fms  %s%n",
                    shortLen, eff * 1000.0, moving ? "能跑" : "一帧归零");
            if (moving && longBoundary < 0) {
                longBoundary = shortLen;
            }
        }
        check("SPR-9 长帧边界实测：short=0.22 前缘一帧归零、short=0.23 能跑"
                        + "（算术边界 50/0.22/1000 = 0.2273，落在两者之间）",
                longBoundary > 0.22 && longBoundary < 0.25,
                "长帧下第一个能跑的刻度 = " + longBoundary);
        check("SPR-10 【推翻了 captain 原文案】short=0.20 时 rank3=44ms 虽 < 50ms，"
                        + "但日常 8ms 帧下完全正常（不会归零）—— 所以「低于 0.05s 归零」是错的",
                runsAt(0.20, factors[3], DT) && !runsAt(0.20, factors[3], 0.05),
                "short=0.20: dt=8ms 跑=" + runsAt(0.20, factors[3], DT)
                        + " / dt=50ms 跑=" + runsAt(0.20, factors[3], 0.05));

        System.out.println();
        System.out.printf("=== V-SPRING 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-SPRING RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-SPRING RESULT: ALL PASS");
        System.exit(0);
    }

    /** 某个短动画时长在给定 dt 下，rank3 分支能否跑起来（不靠算术，驱动真实 DampedSpring）。 */
    private static boolean runsAt(double shortLen, float factor, double dt) {
        DampedSpring sp = new DampedSpring(shortLen * factor);
        sp.setPosition(START);
        return sp.update(dt);
    }

    /** 复算一次某个 animationLength 的帧数（供断言引用，避免把数字手抄进断言）。 */
    private static int framesAt(double[] lens, double target) {
        for (double len : lens) {
            if (Math.abs(len - target) < 1e-9) {
                DampedSpring s = new DampedSpring(len);
                s.setPosition(START);
                boolean moving = s.update(DT);
                int n = moving ? 1 : 0;
                while (moving) {
                    moving = s.update(DT);
                    if (moving) {
                        n++;
                    }
                    if (n > 5000) {
                        break;
                    }
                }
                return n;
            }
        }
        return -1;
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
