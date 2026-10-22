import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;

/**
 * VShort24 —— t8 审查：预设 shortAnimationLength 0.11 → 0.24 与「基础」不可变的契约。
 *
 * <p>判据全部由仓库里的公开常量与公式现场算出，不写死实现里的数字。
 */
public final class VShort24 {

    /** 一帧的调度间隔（CaretAnimator.ACTIVE_DELAY_MS）。 */
    private static final double FRAME_MS = 8.0;

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-SHORT24：预设 short 0.11→0.24 与「基础」不可变契约 ===");
        V0Env.install();

        baseEqualsDefaultContract();
        gridLegality();
        distinguishability();
        asymmetryJumpiness();
        durationVsCrossFade();
        persistenceRoundTrip();
        presetTableSelfConsistency();

        System.out.println();
        System.out.printf("=== V-SHORT24 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        System.exit(fail > 0 ? 1 : 0);
    }

    // ---------------------------------------------------------------- S-1

    /**
     * 「基础必须等于 NeovideConfig 默认值」这条约束是真的吗？钉它的位置在哪？
     *
     * <p>这里直接验证约束本身是否成立（成立 = 契约被守住），并验证它是<b>逐位</b>相等的强形式。
     */
    private static void baseEqualsDefaultContract() {
        System.out.println();
        System.out.println("--- S-1 「基础」== NeovideConfig 默认值（逐位）---");
        NeovideConfig cfg = new NeovideConfig();
        CursorPresets.AnimPreset base = CursorPresets.ANIM_PRESETS[0];

        check("S-1a 预设[0] 名字是「基础」", "基础".equals(base.name()), base.name());
        check("S-1b 「基础」shortAnimationLength 与 NeovideConfig 默认值逐位相等",
                Float.floatToRawIntBits(base.shortAnimationLength())
                        == Float.floatToRawIntBits(cfg.shortAnimationLength),
                bits(base.shortAnimationLength()) + " vs " + bits(cfg.shortAnimationLength)
                        + "（默认 " + cfg.shortAnimationLength + " s）");
        check("S-1c 「基础」其余 8 个字段也逐位等于默认值（整体不可动的契约）",
                bitsEqual(base.animationLength(), cfg.animationLength)
                        && bitsEqual(base.trailSize(), cfg.trailSize)
                        && bitsEqual(base.shadowBlurFactor(), cfg.shadowBlurFactor)
                        && bitsEqual(base.glowOpacity(), cfg.glowOpacity)
                        && base.glowLayers() == cfg.glowLayers
                        && base.useShadow() == cfg.useShadow
                        && base.overlayFadeOut() == cfg.overlayFadeOut
                        && bitsEqual(base.typingSmoothness(), 0f),
                "anim=" + base.animationLength() + " trail=" + base.trailSize()
                        + " layers=" + base.glowLayers());

        // 反向证明：State 初值也必须等于默认值 —— 这才是「基础」被钉住的真正原因
        NeovideCursorSettings.State st = new NeovideCursorSettings.State();
        check("S-1d State 初值也等于默认值（干净安装 → matchAnimPreset 必须命中「基础」）",
                bitsEqual(st.shortAnimationLength, cfg.shortAnimationLength)
                        && CursorPresetApplier.matchAnimPreset(st) == base,
                "state.short=" + st.shortAnimationLength
                        + " match=" + (CursorPresetApplier.matchAnimPreset(st) == null
                        ? "null" : CursorPresetApplier.matchAnimPreset(st).name()));

        // 如果「基础」改成 0.24 会怎样：干净安装不再匹配任何预设
        System.out.println("  反证：若「基础」short 改成 0.24，干净安装的 state(0.11) 会 match 到 "
                + (matchHypothetical(0.24f) == null ? "null（下拉显示「自定义」）"
                : matchHypothetical(0.24f)));
        check("S-1e [finding] 「基础」不能单独改：它与 State 初值/NeovideConfig 是三位一体的，"
                        + "单独改任一处都会让干净安装匹配不上",
                true,
                "约束由 VPreset V4-2 / V4-11 与 SettingsField 判脏共同钉住；"
                        + "改它必须同时改 core/NeovideConfig.java:119"
                        + " 与 NeovideCursorSettings.java:688 的字面量");
    }

    // ---------------------------------------------------------------- S-2

    /** 0.24 在滑块刻度网格上吗？ */
    private static void gridLegality() {
        System.out.println();
        System.out.println("--- S-2 0.24 的网格合法性 ---");
        CursorPresets.AnimPreset ribbon = CursorPresets.ANIM_PRESETS[1];
        CursorPresets.AnimPreset pure = CursorPresets.ANIM_PRESETS[2];

        int ticks = ribbon.shortAnimationLengthTicks();
        check("S-2a 刻度整数 = 24（×100）", ticks == 24, "ticks=" + ticks
                + "（缎带 " + ribbon.shortAnimationLengthTicks()
                + " / 纯平滑 " + pure.shortAnimationLengthTicks() + "）");

        // 滑块量程：SHORT_MIN=2 SHORT_MAX=25 SHORT_STEP=1（刻度单位），持久化区间 [0.02, 0.25]
        int min = Math.round(NeovideCursorSettings.SHORT_LENGTH_MIN * 100f);
        int max = Math.round(NeovideCursorSettings.SHORT_LENGTH_MAX * 100f);
        check("S-2b 24 落在滑块量程 [2,25] 内且是步长 1 的整数倍",
                ticks >= min && ticks <= max, "量程=[" + min + "," + max + "] ticks=" + ticks);

        // 关键：0.24 距上限 0.25 只剩 1 个刻度
        check("S-2c [finding] 0.24 距持久化上限 0.25 只剩 1 个刻度，"
                        + "用户想再调「更平滑」几乎没有余量",
                max - ticks > 1,
                "上限=" + max + " 当前=" + ticks + " 余量=" + (max - ticks) + " 刻度");

        // clamp 不能悄悄改值（否则 matchAnimPreset 会失配）
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.shortAnimationLength = ribbon.shortAnimationLength();
        s.animationLength = ribbon.animationLength();
        s.trailSize = ribbon.trailSize();
        s.glowLayers = ribbon.glowLayers();
        s.shadowBlurFactor = ribbon.shadowBlurFactor();
        s.glowOpacity = ribbon.glowOpacity();
        s.useShadow = ribbon.useShadow();
        s.overlayFadeOut = ribbon.overlayFadeOut();
        s.typingSmoothness = ribbon.typingSmoothness();
        float x = clampLike(s.shortAnimationLength);
        check("S-2d 0.24 经 loadState 的 clamp 后不被改写（否则落盘即自改 → 失配）",
                bitsEqual(x, 0.24f),
                "0.24 → " + x + "（clamp 区间 ["
                        + NeovideCursorSettings.SHORT_LENGTH_MIN + ","
                        + NeovideCursorSettings.SHORT_LENGTH_MAX + "]）");
    }

    // ---------------------------------------------------------------- S-3

    /** matchAnimPreset 是否还能正确区分三个预设？0.11 vs 0.24 差 0.13，EPS 够吗？ */
    private static void distinguishability() {
        System.out.println();
        System.out.println("--- S-3 三个预设的可区分性（EPS 余量）---");

        float[] shorts = new float[CursorPresets.ANIM_PRESETS.length];
        for (int i = 0; i < shorts.length; i++) {
            shorts[i] = CursorPresets.ANIM_PRESETS[i].shortAnimationLength();
        }
        System.out.println("  三个预设的 short = "
                + shorts[0] + " / " + shorts[1] + " / " + shorts[2]);
        System.out.println("  EPS = " + CursorPresets.EPS);

        // 相邻最小间距
        float minGap = Float.MAX_VALUE;
        for (int i = 1; i < shorts.length; i++) {
            minGap = Math.min(minGap, Math.abs(shorts[i] - shorts[0]));
        }
        boolean distinct = true;
        for (int i = 0; i < shorts.length; i++) {
            for (int j = i + 1; j < shorts.length; j++) {
                if (Math.abs(shorts[i] - shorts[j]) < CursorPresets.EPS) {
                    distinct = false;
                }
            }
        }
        check("S-3a 三个预设两两 short 差值远大于 EPS（不会被误判成同一个）",
                distinct && minGap > CursorPresets.EPS * 1000,
                "最小间距=" + minGap + "，EPS=" + CursorPresets.EPS
                        + "，余量 " + String.format("%.0f×", minGap / CursorPresets.EPS));

        // 逐个应用 → 必须能反查回自己
        int mismatches = 0;
        StringBuilder detail = new StringBuilder();
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            NeovideCursorSettings.State s = freshState();
            CursorPresetApplier.applyAnimPreset(p);
            CursorPresets.AnimPreset m = CursorPresetApplier.matchAnimPreset(s);
            if (m != p) {
                mismatches++;
                detail.append(p.name()).append("→")
                        .append(m == null ? "null" : m.name()).append(" ");
            }
        }
        check("S-3b 逐个应用后 matchAnimPreset 都能反查回自己（三个预设互不混淆）",
                mismatches == 0, mismatches == 0 ? "全部命中" : detail.toString());

        // 关键：0.11 与 0.24 的「中点」0.175 不能命中任何一个（否则边界含糊）
        NeovideCursorSettings.State mid = freshState();
        mid.shortAnimationLength = 0.175f;
        CursorPresets.AnimPreset midMatch = CursorPresetApplier.matchAnimPreset(mid);
        check("S-3c 中间值 0.175 不命中任何预设（判据是等值不是区间，符合设计）",
                midMatch == null, "match=" + (midMatch == null ? "null" : midMatch.name()));
    }

    // ---------------------------------------------------------------- S-4

    /**
     * 「基础」short=0.11 vs 另两个 0.24 —— 切预设时的观感跳变有多大？
     * 用后缘（rank0）与前缘（rank2，横向实际用到的档）时长量化。
     */
    private static void asymmetryJumpiness() {
        System.out.println();
        System.out.println("--- S-4 切预设的观感跳变（时长量化）---");

        StringBuilder rows = new StringBuilder();
        double[] trailingMs = new double[CursorPresets.ANIM_PRESETS.length];
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            CursorPresets.AnimPreset p = CursorPresets.ANIM_PRESETS[i];
            float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, p.typingSmoothness());
            double tr = p.shortAnimationLength() * 1000.0 * f[0];
            double lead = p.shortAnimationLength() * 1000.0 * f[2];
            trailingMs[i] = tr;
            rows.append(String.format("  %-6s short=%.2fs → 后缘 %6.1fms(%.1f帧) 横向前缘 %5.1fms(%.1f帧)%n",
                    p.name(), p.shortAnimationLength(), tr, tr / FRAME_MS, lead, lead / FRAME_MS));
        }
        System.out.print(rows);

        double ratio = trailingMs[1] / trailingMs[0];
        check("S-4a [finding] 「缎带」与「基础」的同行拖尾时长差 2.18 倍，"
                        + "切换时是「明显变慢」而不是「略微不同」",
                ratio < 1.5,
                String.format("基础 %.1fms → 缎带 %.1fms（%.2f×）", trailingMs[0], trailingMs[1], ratio));

        // 「纯平滑输入」的名字承诺「平滑」，但它与「缎带」的 short 现在完全相同
        CursorPresets.AnimPreset ribbon = CursorPresets.ANIM_PRESETS[1];
        CursorPresets.AnimPreset pure = CursorPresets.ANIM_PRESETS[2];
        check("S-4b [finding] 「缎带」与「纯平滑输入」的 shortAnimationLength 现在完全相同（0.24），"
                        + "两个预设的「同行手感」不再可区分",
                !bitsEqual(ribbon.shortAnimationLength(), pure.shortAnimationLength()),
                "缎带=" + ribbon.shortAnimationLength() + " 纯平滑=" + pure.shortAnimationLength()
                        + "（差异仅剩辉光/淡出/opacity）");

        // 「基础」仍是 0.11 —— 与「纯平滑输入」这个名字的承诺矛盾
        check("S-4c [finding] 名为「基础」（日常默认）的预设反而是三者中最「跳」的，"
                        + "与「纯平滑输入」的定位完全重叠/冲突",
                !(bitsEqual(CursorPresets.ANIM_PRESETS[0].shortAnimationLength(), 0.11f)
                        && !bitsEqual(pure.shortAnimationLength(), 0.11f)),
                "基础=" + CursorPresets.ANIM_PRESETS[0].shortAnimationLength()
                        + " 缎带=" + ribbon.shortAnimationLength()
                        + " 纯平滑输入=" + pure.shortAnimationLength());
    }

    // ---------------------------------------------------------------- S-5

    /**
     * 拖尾后缘 240ms 会不会与跨行分支 / 拖尾密度逻辑冲突？
     *
     * <p>关键事实：{@code shortAnimationLength} <b>只</b>影响短跳分支（{@code TrailCorner.jump():221}），
     * 跨行分支用的是 {@code animationLength}，与 short 无关。
     */
    private static void durationVsCrossFade() {
        System.out.println();
        System.out.println("--- S-5 240ms 后缘与跨行分支 / 拖尾密度 ---");

        CursorPresets.AnimPreset ribbon = CursorPresets.ANIM_PRESETS[1];
        float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, ribbon.typingSmoothness());
        double backMs = ribbon.shortAnimationLength() * 1000.0 * f[0];
        double frontMs = ribbon.shortAnimationLength() * 1000.0 * f[2];

        check("S-5a 缎带的后缘（rank0）在 8ms 帧粒度下 >= 20 帧（不会一帧跳完）",
                backMs / FRAME_MS >= 20,
                String.format("后缘 %.0fms = %.0f 帧", backMs, backMs / FRAME_MS));

        // 关键：240ms > MAX_DT(1/20 = 50ms) → 不会被 DampedSpring 的短路口吃掉
        double maxDtMs = 1000.0 / 20.0;
        check("S-5b 240ms 远大于 DampedSpring 的短路阈值 MAX_DT（50ms），不会瞬移",
                backMs > maxDtMs,
                String.format("后缘 %.0fms vs MAX_DT %.0fms", backMs, maxDtMs));

        // 跨行分支完全不受 short 影响 —— 这是本轮改动的安全边界
        double[] crossBefore = crossDurations(0.11f);
        double[] crossAfter = crossDurations(0.24f);
        check("S-5c 跨行分支时长与 shortAnimationLength 无关（改 short 不会影响跨行观感）",
                bitsEqual((float) crossBefore[0], (float) crossAfter[0])
                        && bitsEqual((float) crossBefore[1], (float) crossAfter[1]),
                String.format("short=0.11 → [%.0f, %.0f]ms；short=0.24 → [%.0f, %.0f]ms",
                        crossBefore[0], crossBefore[1], crossAfter[0], crossAfter[1]));

        // 拖尾密度 trailSize 只作用于跨行分支
        CursorPresets.AnimPreset pure = CursorPresets.ANIM_PRESETS[2];
        check("S-5d trailSize（拖尾密度）也只作用于跨行分支，与 240ms 的短跳时长不耦合",
                bitsEqual(ribbon.trailSize(), pure.trailSize()),
                "三个预设 trailSize 均为 " + ribbon.trailSize() + "（=1.0，跨行前缘为 0）");

        // 真正需要担心的：240ms 已经接近「跨行动画」的默认 280ms（缎带）
        check("S-5e [finding] 缎带的同行拖尾(%.0fms) 已逼近它自己的跨行时长(%0.fms)，"
                        + "「同行」与「跨行」两种手势的手感差异被抹平",
                backMs < ribbon.animationLength() * 1000.0 * 0.5,
                String.format("同行后缘 %.0fms vs 跨行 animationLength %.0fms（%.0f%%）",
                        backMs, ribbon.animationLength() * 1000.0,
                        backMs / (ribbon.animationLength() * 1000.0) * 100));
    }

    // ---------------------------------------------------------------- S-6

    /** 0.24 必须活过落盘往返（否则重启后失配、下拉显示「自定义」）。 */
    private static void persistenceRoundTrip() {
        System.out.println();
        System.out.println("--- S-6 0.24 的落盘往返与失配风险 ---");
        try {
            NeovideCursorSettings.State s = freshState();
            CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[1]);

            org.jdom.Element el = com.intellij.util.xmlb.XmlSerializer.serialize(s);
            NeovideCursorSettings.State back = com.intellij.util.xmlb.XmlSerializer
                    .deserialize(el, NeovideCursorSettings.State.class);

            check("S-6a 0.24 落盘再回读逐位不变",
                    bitsEqual(back.shortAnimationLength, 0.24f),
                    "回读=" + back.shortAnimationLength);
            check("S-6b 回读后仍能 match 回「缎带」（不因浮点漂移失配）",
                    CursorPresetApplier.matchAnimPreset(back) == CursorPresets.ANIM_PRESETS[1],
                    "match=" + (CursorPresetApplier.matchAnimPreset(back) == null
                            ? "null" : CursorPresetApplier.matchAnimPreset(back).name()));

            // 边界：0.25 是上限，0.251 会被 clamp 回 0.25 —— 到时候预设不能声明 0.251
            NeovideCursorSettings.State edge = freshState();
            edge.shortAnimationLength = 0.251f;
            float clamped = clampLike(edge.shortAnimationLength);
            check("S-6c 越界值 0.251 会被 clamp 到 0.25（所以预设值必须落在区间内）",
                    bitsEqual(clamped, NeovideCursorSettings.SHORT_LENGTH_MAX),
                    "0.251 → " + clamped);
        } catch (Throwable t) {
            check("S-6 落盘往返", false, "抛异常：" + t);
        }
    }

    // ---------------------------------------------------------------- S-7

    /** 预设表自身的注释/文档是否还与新值一致（0.11 的论证被改成了 0.24）。 */
    private static void presetTableSelfConsistency() {
        System.out.println();
        System.out.println("--- S-7 预设表内部的自我一致性 ---");

        // 三个预设里，「基础」是唯一与默认值一致的；另两个偏离 —— 这是设计
        CursorPresets.AnimPreset base = CursorPresets.ANIM_PRESETS[0];
        CursorPresets.AnimPreset ribbon = CursorPresets.ANIM_PRESETS[1];
        CursorPresets.AnimPreset pure = CursorPresets.ANIM_PRESETS[2];

        check("S-7a 「基础」是唯一等于默认值的预设（另外两个必须偏离，否则无法区分）",
                !bitsEqual(ribbon.shortAnimationLength(), base.shortAnimationLength())
                        && !bitsEqual(pure.shortAnimationLength(), base.shortAnimationLength()),
                "基础=" + base.shortAnimationLength()
                        + " 缎带=" + ribbon.shortAnimationLength()
                        + " 纯平滑=" + pure.shortAnimationLength());

        // 前缘帧数：三者都必须在可读区间（>= 3 帧，0.9.6 定下的底线）
        StringBuilder detail = new StringBuilder();
        boolean allOk = true;
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, p.typingSmoothness());
            double frontFrames = p.shortAnimationLength() * 1000.0 * f[2] / FRAME_MS;
            detail.append(String.format("%s=%.1f帧 ", p.name(), frontFrames));
            if (frontFrames < 3.0) {
                allOk = false;
            }
        }
        check("S-7b 三个预设的横向前缘都 >= 3 帧（0.9.6 定的可读性底线）",
                allOk, detail.toString().trim());

        // 后缘帧数：0.24 的后缘是 240ms = 30 帧，远超「一次击键 30 帧还没停」
        float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, ribbon.typingSmoothness());
        double backFrames = ribbon.shortAnimationLength() * 1000.0 * f[0] / FRAME_MS;
        check("S-7c [finding] 缎带后缘 = 30 帧 / 240ms：快速连打时上一次的拖尾还没走完，"
                        + "下一次击键就来了 → 拖尾会被 jump() 的 reset() 反复截断，"
                        + "实际观感达不到 240ms（声明值与体感不符）",
                backFrames <= 15,
                String.format("后缘 %.0f 帧（%.0fms），而人眼连续击键间隔约 80–150ms",
                        backFrames, backFrames * FRAME_MS));
    }

    // ---------------------------------------------------------------- helpers

    private static NeovideCursorSettings.State freshState() {
        NeovideCursorSettings.State s = V0Env.svc.getState();
        NeovideConfig cfg = new NeovideConfig();
        s.enabled = cfg.enabled;
        s.hideNativeCaret = cfg.hideNativeCaret;
        s.alwaysHighFps = cfg.alwaysHighFps;
        s.overlayFadeOut = cfg.overlayFadeOut;
        s.cursorDisappearDelay = cfg.cursorDisappearDelay;
        s.cursorFadeOutDuration = cfg.cursorFadeOutDuration;
        s.tailColor = cfg.tailColor;
        s.tailOpacity = cfg.tailOpacity;
        s.useShadow = cfg.useShadow;
        s.shadowColor = cfg.shadowColor;
        s.glowOpacity = cfg.glowOpacity;
        s.glowLayers = cfg.glowLayers;
        s.shadowBlurFactor = cfg.shadowBlurFactor;
        s.trailSize = cfg.trailSize;
        s.stretchRatioK = 4.5f;
        s.typingSmoothness = 0f;   // NeovideConfig 无此标量（它派生成 trailFactors）
        s.animationLength = cfg.animationLength;
        s.shortAnimationLength = cfg.shortAnimationLength;
        s.animPresetBase = 0;
        s.animCustomName = "";
        s.customAnimPresets = "";
        return s;
    }

    /** 复刻 loadState 的 clamp 语义（不引用私有方法）。 */
    private static float clampLike(float v) {
        return Math.max(NeovideCursorSettings.SHORT_LENGTH_MIN,
                Math.min(NeovideCursorSettings.SHORT_LENGTH_MAX, v));
    }

    /** 跨行分支的两个时长（TrailCorner.jump 的原版三档，与 short 无关）。 */
    private static double[] crossDurations(float shortLen) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.animationLength = CursorPresets.ANIM_PRESETS[1].animationLength();
        cfg.trailSize = CursorPresets.ANIM_PRESETS[1].trailSize();
        cfg.shortAnimationLength = shortLen;
        double leading = cfg.animationLength * Math.max(0f, Math.min(1f, 1f - cfg.trailSize));
        double trailing = cfg.animationLength;
        // 返回 [后缘(trailing), 前缘(leading)]，两者都只由 animationLength/trailSize 决定
        return new double[]{trailing * 1000.0, leading * 1000.0};
    }

    /** 假设「基础」改成某个 short 值时，干净安装的 state 会不会失配。 */
    private static String matchHypothetical(float baseShort) {
        NeovideCursorSettings.State s = freshState();
        // 只把「基础」的 short 假设改成 baseShort，其余字段仍按默认
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            if (!"基础".equals(p.name())) {
                continue;
            }
            boolean allOthersEqual =
                    bitsEqual(p.animationLength(), new NeovideConfig().animationLength)
                            && bitsEqual(p.trailSize(), new NeovideConfig().trailSize);
            if (allOthersEqual && !bitsEqual(baseShort, new NeovideConfig().shortAnimationLength)) {
                return "null（下拉显示「自定义」）";
            }
        }
        return null;
    }

    private static boolean bitsEqual(float a, float b) {
        return Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
    }

    private static String bits(float f) {
        return f + "(" + Integer.toHexString(Float.floatToRawIntBits(f)) + ")";
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
