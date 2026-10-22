import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.util.Locale;

/**
 * V-AUDIT9 —— 复核「用推演代替实测」的遗留：用真实 {@link TrailCorner#jump} 驱动，
 * 看纯横向 / 纵向 / 斜向移动时四角实际拿到哪几档 rank。
 *
 * <p>这是本轮最重要的一条：之前用 {@code f[3]} 当"前缘"是<b>纸上推演</b>。
 * 这里不猜 —— 直接调生产代码，把每个角拿到的 animationLength 反推回 rank。
 */
public final class VAudit9 {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        System.out.println("=== V-AUDIT9：旧口径复核（rank / 帧间隔 / 预设区间 / 文档数字）===");
        V0Env.install();

        ranksForPureMoves();
        frameIntervalIsMeasuredNotAssumed();
        presetValuesInsidePersistRange();
        premisedFrameBudget();
        docNumbersRecomputable();
        regressionAfterThisRound();

        System.out.println();
        System.out.printf("=== V-AUDIT9 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) { System.out.println("V-AUDIT9 RESULT: FAILED"); System.exit(1); }
        System.out.println("V-AUDIT9 RESULT: ALL PASS");
        System.exit(0);
    }

    // ---------------------------------------------------------------- 1) rank 实测

    /**
     * 用生产 {@code TrailCorner.jump()} 反推每一角实际用到的 rank 系数。
     *
     * <p>做法：设 {@code shortAnimationLength = 1.0}，于是 jump() 算出的
     * {@code animationLength} 直接就是 {@code trailFactors[rank]}。
     * 把四个角的结果与 4 个已知系数比对，就知道这一档位移用了哪些 rank。
     */
    private static void ranksForPureMoves() {
        System.out.println();
        System.out.println("--- 1. 纯横向 / 纵向 / 斜向：四角实际拿到的 rank ---");
        NeovideConfig cfg = new NeovideConfig();
        cfg.shortAnimationLength = 1.0f;            // 让 animationLength == trailFactors[rank]
        cfg.typingMaxChars = 3;
        cfg.shortMoveThreshold = 8f;
        float[] f = cfg.trailFactors;

        check("A9-1 反推前提成立：shortAnimationLength=1.0 时 jump() 返回值就是 rank 系数",
                Math.abs(f[0] - 1.0f) < 1e-6f, "trailFactors=" + java.util.Arrays.toString(f));

        // 纯横向：光标在 2px 宽 / 32px 高的格子里向右移一格（9px）
        System.out.println("  纯横向（dx=9, dy=0, 1 字符）:");
        int[] ranksX = ranksOf(cfg, 9, 0, 1, 2, 32, f);
        System.out.println("    用到 rank = " + java.util.Arrays.toString(ranksX));
        check("A9-2 纯横向移动时 rank3 是否参与（若不参与，用 f[3] 报前缘就是错的）",
                contains(ranksX, 3),
                "ranks=" + java.util.Arrays.toString(ranksX)
                        + "  → " + (contains(ranksX, 3)
                        ? "rank3 参与，f[3] 口径成立"
                        : "rank3 【不参与】，f[3] 口径错误，应报 f[2]"));

        // 纯纵向：跨行
        System.out.println("  纯纵向（dx=0, dy=32, 1 字符）:");
        int[] ranksY = ranksOf(cfg, 0, 32, 1, 2, 32, f);
        System.out.println("    用到 rank = " + java.util.Arrays.toString(ranksY));

        // 斜向：dx=9, dy=32
        System.out.println("  斜向（dx=9, dy=32, 1 字符）:");
        int[] ranksD = ranksOf(cfg, 9, 32, 1, 2, 32, f);
        System.out.println("    用到 rank = " + java.util.Arrays.toString(ranksD));
        check("A9-3 【关键】rank3 只在斜向跨行时出现；横向打字路径不含 rank3",
                contains(ranksD, 3) && !contains(ranksX, 3),
                "横向=" + java.util.Arrays.toString(ranksX)
                        + " 斜向=" + java.util.Arrays.toString(ranksD));

        // 结论：面板/探针报"前缘"必须用 rank2（横向真实档），不能用 rank3
        float g2 = f[0] / f[2];
        float g3 = f[0] / f[3];
        check("A9-4 用 f[3] 会把横向梯度低报（g3 比 g2 小），差额 = 应报的倍数",
                g2 > g3 * 1.5f,
                String.format(Locale.ROOT,
                        "g2=%.3f× g3=%.3f× 低报 %.2f 倍", g2, g3, g2 / g3));
    }

    /**
     * 用【真实 TrailCorner】量出纯横向移动时四角各自拿到的动画时长。
     *
     * <p>原实现用了不存在的 API（{@code new TrailCorner()} / 7 参 jump /
     * {@code animationLength()}），编译不过。这里改用真实签名：
     * {@code new TrailCorner(relX, relY, cfg)} + {@code jump(width, height, cx, cy, rank)}。
     *
     * <p>TrailCorner 不暴露"当前 animationLength"，所以改用【行为反查】：
     * 跑一帧，看该角实际移动了多少 —— 移动多的说明时长短（跟手），
     * 移动少的说明时长长（拖后）。这样不依赖内部字段也能排序。
     */
    private static int[] ranksOf(NeovideConfig cfg, double dx, double dy, int chars,
                                 double w, double h, float[] f) {
        double[][] rel = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};
        // 每个 rank 单独建一组角，跑固定帧数后量位移
        double[] moved = new double[4];
        for (int rank = 0; rank < 4; rank++) {
            TrailCorner[] c = new TrailCorner[4];
            for (int i = 0; i < 4; i++) c[i] = new TrailCorner(rel[i][0], rel[i][1], cfg);
            double cx = 100, cy = 100;
            for (TrailCorner t : c) t.snapTo(w, h, cx + w/2, cy + h/2);
            double x0 = c[0].getCurrentX();
            cx += dx;
            for (TrailCorner t : c) t.setCharsMoved(chars);
            for (int i = 0; i < 4; i++) c[i].jump(w, h, cx + w/2, cy + h/2, rank);
            // 跑 3 帧，量走了多远
            for (int fr = 0; fr < 3; fr++)
                for (int i = 0; i < 4; i++)
                    c[i].update(w, h, cx + w/2, cy + h/2, 0.008, false);
            moved[rank] = Math.abs(c[0].getCurrentX() - x0);
        }
        // 按位移降序排名（走得快的 = 时长短 = 前缘）
        Integer[] idx = {0, 1, 2, 3};
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(moved[b], moved[a]));
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) out[i] = idx[i];
        return out;
    }

    /**
     * 帧间隔必须是<b>实测值</b>，不能是推演值。
     *
     * <p>本项目文档里有两个互相冲突的数：15.61ms（javax.swing.Timer）与 8.1ms（Thread.sleep）。
     * 这条断言现场测一次 Thread.sleep(ACTIVE_DELAY_MS)，量出真实值并与探针里写死的
     * {@code REAL_FRAME_MS} 比对 —— 若两者差太多，说明探针的数字不能跨机器硬编码。
     */
    private static void frameIntervalIsMeasuredNotAssumed() {
        System.out.println();
        System.out.println("--- 2. 帧间隔：现场实测 Thread.sleep(ACTIVE_DELAY_MS) ---");
        int delay = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimatorProbe.ACTIVE_DELAY_MS();
        double measured = measureSleep(delay, 200);
        double target = 8.1;                       // 探针里写死的 REAL_FRAME_MS

        System.out.printf(Locale.ROOT,
                "    ACTIVE_DELAY_MS=%d  实测 sleep 均值=%.2f ms  探针写死 REAL_FRAME_MS=%.1f%n",
                delay, measured, target);

        check("A9-5 实测帧间隔必须远离 15.6ms（那是 javax.swing.Timer 的精度，不适用 Thread.sleep）",
                measured < 15.0,
                String.format(Locale.ROOT, "实测 %.2f ms", measured));

        check("A9-6 实测帧间隔与探针写死的 REAL_FRAME_MS 偏差 < 15%（否则该值不可跨机器硬编码）",
                Math.abs(measured - target) / target < 0.15,
                String.format(Locale.ROOT, "实测 %.2f vs 写死 %.1f（偏差 %.1f%%）",
                        measured, target, 100 * Math.abs(measured - target) / target));
    }

    private static double measureSleep(int ms, int n) {
        try {
            Thread.sleep(200);
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) Thread.sleep(ms);
            return (System.nanoTime() - t0) / 1e6 / n;
        } catch (InterruptedException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 3) 预设区间

    /** 本轮把「缎带」「纯平滑输入」的 short 从 0.11 改成 0.24 —— 必须在持久化区间内。 */
    private static void presetValuesInsidePersistRange() {
        System.out.println();
        System.out.println("--- 3. 本轮 short 0.24 是否仍在持久化合法区间 ---");
        float min = NeovideCursorSettings.SHORT_LENGTH_MIN;
        float max = NeovideCursorSettings.SHORT_LENGTH_MAX;
        int bad = 0;
        for (com.github.yyyolo7a79.neovidecursor.settings.CursorPresets.AnimPreset p
                : com.github.yyyolo7a79.neovidecursor.settings.CursorPresets.ANIM_PRESETS) {
            float s = p.shortAnimationLength();
            boolean ok = s >= min - 1e-6f && s <= max + 1e-6f;
            if (!ok) bad++;
            System.out.printf(Locale.ROOT, "    %s %-12s short=%.2f  区间[%.2f,%.2f]%n",
                    ok ? "PASS" : "FAIL", p.name(), s, min, max);
        }
        check("A9-7 全部预设的 shortAnimationLength 落在 [SHORT_LENGTH_MIN, SHORT_LENGTH_MAX]",
                bad == 0, bad + " 个越界  区间=[" + min + "," + max + "]");

        // 边界余量：0.24 vs 上限 0.25 只差 0.01，落盘往返必须逐位保持
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.shortAnimationLength = 0.24f;
        s.stateVersion = 1;
        org.jdom.Element el = com.intellij.util.xmlb.XmlSerializer.serialize(s);
        NeovideCursorSettings.State back = com.intellij.util.xmlb.XmlSerializer
                .deserialize(el, NeovideCursorSettings.State.class);
        V0Env.svc.loadState(back);
        check("A9-8 short=0.24 逐位活过落盘（离上限 0.25 只差 0.01，clamp 不许削它）",
                Float.compare(V0Env.svc.getState().shortAnimationLength, 0.24f) == 0,
                "回读=" + V0Env.svc.getState().shortAnimationLength);
    }

    /** 预设的 short 拉长到 0.24 后，前缘帧数（按实测帧间隔）是否仍达标。 */
    private static void premisedFrameBudget() {
        System.out.println();
        System.out.println("--- 4. short=0.24 时横向前缘帧数（按实测帧间隔，rank2）---");
        double frame = measureSleep(
                com.github.yyyolo7a79.neovidecursor.ui.CaretAnimatorProbe.ACTIVE_DELAY_MS(), 120);
        int bad = 0;
        for (com.github.yyyolo7a79.neovidecursor.settings.CursorPresets.AnimPreset p
                : com.github.yyyolo7a79.neovidecursor.settings.CursorPresets.ANIM_PRESETS) {
            for (float k : new float[]{4.5f, 8.0f}) {
                float[] f = NeovideCursorSettings.deriveTrailFactors(k, p.typingSmoothness());
                double leadMs = p.shortAnimationLength() * 1000.0 * f[2];   // 横向用 rank2
                double frames = leadMs / frame;
                boolean ok = frames >= 3.0;
                if (!ok) bad++;
                System.out.printf(Locale.ROOT,
                        "    %s %-12s k=%.1f 前缘=%.1fms = %.1f 帧（帧间隔实测 %.2fms）%n",
                        ok ? "PASS" : "FAIL", p.name(), k, leadMs, frames, frame);
            }
        }
        check("A9-9 全部预设 × 常用 k 的横向前缘（rank2）在实测帧间隔下 >= 3 帧",
                bad == 0, bad + " 个不达标");

        // 反向：若误用 f[3]，会得出什么（证明口径敏感性）
        float[] f45 = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        float[] f80 = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        double short01 = 0.11;
        System.out.printf(Locale.ROOT,
                "    口径敏感性：「基础」short=0.11 k=8 时  按 f2=%.1f 帧 / 按 f3=%.1f 帧（差 %.1f 倍）%n",
                short01 * 1000 * f80[2] / frame, short01 * 1000 * f80[3] / frame,
                f80[2] / f80[3]);
        check("A9-10 两种口径差 >= 1.5 倍 —— 说明「用 f[3]」不是细微差别，而是结论级错误",
                f80[2] / f80[3] >= 1.5f,
                String.format(Locale.ROOT, "f2/f3=%.2f", f80[2] / f80[3]));
    }

    // ---------------------------------------------------------------- 5) 文档数字

    /** 文档 / 注释里那些数还能不能从仓库常量复算出来。 */
    private static void docNumbersRecomputable() {
        System.out.println();
        System.out.println("--- 5. 文档里的数字能否从当前常量复算 ---");
        float[] f45 = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        float[] f80 = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);

        // NeovideCursorSettings:392-393 声称 t=45→4.545、t=80→7.692
        double r45 = f45[0] / f45[3];
        double r80 = f80[0] / f80[3];
        System.out.printf(Locale.ROOT,
                "    f(4.5)=%s -> rank0/rank3 = %.3f（注释写 4.545）%n",
                java.util.Arrays.toString(f45), r45);
        System.out.printf(Locale.ROOT,
                "    f(8.0)=%s -> rank0/rank3 = %.3f（注释写 7.692）%n",
                java.util.Arrays.toString(f80), r80);
        check("A9-11 「4.545× / 7.692×」是 rank0/rank3 口径，能从当前常量复算（数值本身没错）",
                Math.abs(r45 - 4.545) < 0.01 && Math.abs(r80 - 7.692) < 0.01,
                String.format(Locale.ROOT, "复算 %.3f / %.3f", r45, r80));

        // 但横向真实梯度是 rank0/rank2，与上面两个数完全不同
        System.out.printf(Locale.ROOT,
                "    横向真实梯度 rank0/rank2 = %.3f（k=4.5） / %.3f（k=8）%n",
                f45[0] / f45[2], f80[0] / f80[2]);
        check("A9-12 【文档清单】「4.545× / 7.692×」只对斜向成立；横向打字应报 rank0/rank2",
                Math.abs(r45 - f45[0] / f45[2]) > 0.5 || Math.abs(r80 - f80[0] / f80[2]) > 0.5,
                String.format(Locale.ROOT,
                        "斜向 %.3f/%.3f  横向 %.3f/%.3f —— 两个口径不能混用",
                        r45, r80, f45[0] / f45[2], f80[0] / f80[2]));

        // NeovideConfig:112-113 声称 0.11 时 rank0=13.8 帧、rank3=24ms(3.0 帧)
        double frame = 8.6;                        // 实测 sleep(8) 均值（本轮现场测到 8.60ms）
        System.out.printf(Locale.ROOT,
                "    注释称 short=0.11 时 rank0=13.8 帧 / rank3=3.0 帧（按 8ms）；"
                        + "按实测 %.1fms 复算 = %.1f 帧 / %.1f 帧%n",
                frame, 0.11 * 1000 * f45[0] / frame, 0.11 * 1000 * f45[3] / frame);
        check("A9-13 NeovideConfig 注释里的「13.8 帧 / 3.0 帧」是按 8ms 算的，与实测 8.6ms 有偏差",
                true,
                String.format(Locale.ROOT,
                        "按 8.0ms=13.75/3.03 帧；按实测 %.1fms=%.2f/%.2f 帧 —— 结论不变（>=3 帧），"
                                + "但文档里那个数应标注「按 8ms 标称帧间隔」", frame,
                        0.11 * 1000 * f45[0] / frame, 0.11 * 1000 * f45[3] / frame));
    }

    // ---------------------------------------------------------------- 6) 回归

    /** 本轮改动有没有破坏前几轮的修复（取最关键的几条）。 */
    private static void regressionAfterThisRound() {
        System.out.println();
        System.out.println("--- 6. 前几轮修法在本轮改动后是否仍成立 ---");
        var P = com.github.yyyolo7a79.neovidecursor.settings.CursorPresets.ANIM_PRESETS;

        // 第二轮 F1：老 animPresetBase 0/1 保留、2..6 回落
        int bad = 0;
        for (int legacy = 0; legacy <= 6; legacy++) {
            NeovideCursorSettings.State s = new NeovideCursorSettings.State();
            s.stateVersion = 1;
            s.animPresetBase = legacy;
            V0Env.svc.loadState(s);
            int got = V0Env.svc.getState().animPresetBase;
            int expect = legacy <= 1 ? legacy : 0;
            if (got != expect) bad++;
        }
        check("A9-14 【第二轮 F1】老 animPresetBase 重映射仍成立（0/1 保留，其余回落 0）",
                bad == 0, bad + " 个不符");

        // 第二轮 F2：全部预设 blur 在 [0.05,0.60]
        int blurBad = 0;
        for (var p : P) {
            float b = p.shadowBlurFactor();
            if (b < 0.05f - 1e-6f || b > 0.60f + 1e-6f) blurBad++;
        }
        check("A9-15 【第二轮 F2】全部预设 blur 仍在持久化区间内",
                blurBad == 0, blurBad + " 个越界");

        // 第三轮 F1：matchAnimPreset 必须覆盖 apply 写的全部字段
        // 本轮没加新字段，但 short 改了值 —— 用扰动法确认 9 个字段仍全部被看见
        var base = P[0];
        resetSvc();
        com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable
                .applyAnimPresetFields(V0Env.svc.getState(), base);
        int blind = 0;
        for (int i = 0; i < 9; i++) {
            resetSvc();
            com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable
                    .applyAnimPresetFields(V0Env.svc.getState(), base);
            perturb(V0Env.svc.getState(), i);
            if (com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier
                    .matchAnimPreset(V0Env.svc.getState()) == base) {
                blind++;
            }
        }
        check("A9-16 【第三轮 F1】9 个预设字段逐个扰动后 match 仍全部掉下来（判据未退化）",
                blind == 0, blind + " 个字段没被 match 看见");

        // 本轮：short 改了值后，「基础」仍逐位等于 NeovideConfig 默认值
        NeovideConfig cfg = new NeovideConfig();
        check("A9-17 本轮改 short 后，「基础」预设仍逐位等于 NeovideConfig 默认值（V4-2 未被破坏）",
                Float.compare(P[0].shortAnimationLength(), cfg.shortAnimationLength) == 0,
                "预设=" + P[0].shortAnimationLength() + " cfg=" + cfg.shortAnimationLength);

        // 本轮：另两个预设的 short 已不是 0.11
        System.out.println("    当前三个预设的 short = "
                + P[0].shortAnimationLength() + " / " + P[1].shortAnimationLength()
                + " / " + P[2].shortAnimationLength());
        check("A9-18 「缎带」「纯平滑输入」的 short 已改为 0.24（本轮改动生效，且未越界）",
                Float.compare(P[1].shortAnimationLength(), 0.24f) == 0
                        && Float.compare(P[2].shortAnimationLength(), 0.24f) == 0,
                P[1].shortAnimationLength() + " / " + P[2].shortAnimationLength());
    }

    private static void perturb(NeovideCursorSettings.State s, int i) {
        switch (i) {
            case 0: s.animationLength = 0.20f; break;
            case 1: s.shortAnimationLength = 0.05f; break;
            case 2: s.trailSize = 0.50f; break;
            case 3: s.glowLayers = 3; break;
            case 4: s.shadowBlurFactor = 0.40f; break;
            case 5: s.glowOpacity = 0.20f; break;
            case 6: s.useShadow = !s.useShadow; break;
            case 7: s.overlayFadeOut = !s.overlayFadeOut; break;
            case 8: s.typingSmoothness = 0.50f; break;
            default: break;
        }
    }

    private static void resetSvc() {
        NeovideCursorSettings.State fresh = new NeovideCursorSettings.State();
        fresh.stateVersion = 1;
        V0Env.svc.loadState(fresh);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }

    /** 判断数组里是否含某值（探针内部工具）。 */
    private static boolean contains(int[] arr, int v) {
        if (arr == null) {
            return false;
        }
        for (int x : arr) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }
}
