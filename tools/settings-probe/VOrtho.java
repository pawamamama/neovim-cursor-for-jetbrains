import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.util.Locale;

/**
 * V-ORTHO —— 第二轮审查 t2：<b>预设字段域扩大后的正交性破损</b>。
 *
 * <p>被审的三类改动（0.9.10）：
 * <ol>
 *   <li>{@code AnimPreset} 从 6 字段扩到 8 字段（+useShadow +overlayFadeOut）；</li>
 *   <li>新增 {@code NeovideCursorConfigurable.applyAnimPresetFields()} 供面板与 Tools 共用；</li>
 *   <li>预设表精简为 3 个（基础 / 缎带 / 纯平滑输入），而 {@code animPresetBase} 的
 *       持久化 clamp 仍写着 {@code 0..6}。</li>
 * </ol>
 *
 * <p><b>判据全部是行为断言</b>：不读生产代码的常量当期望值，量程/区间在探针里独立抄一份
 * （与 {@code VUi.SPEC} 同款理由：用被测方的值校验被测方等于自证）。
 *
 * <p>七条主线（对应任务清单 1–7）：
 * <pre>
 *   1) 颜色预设与动画预设是否仍正交          → O-10 / O-11
 *   2) apply 写 8 个、match 只比 6 个         → O-6 / O-4
 *   3) 手动改 useShadow 后下拉该不该变自定义  → O-7 / O-8
 *   4) AnimPresetGroup 的勾选态会不会撒谎     → O-7（同一判据）
 *   5) 「纯平滑输入」blur=0 的真实语义        → O-3 / O-5 / O-13
 *   6) 老 animPresetBase（0..6）如何迁移      → O-1 / O-2
 *   7) applyAnimPresetFields 绕过的补丁        → O-6 / O-12 / O-9
 * </pre>
 */
public final class VOrtho {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * State 侧「持久化后仍合法」的区间，<b>独立抄一份</b>。
     *
     * <p>依据是 {@code NeovideCursorSettings.loadState()} 的 clamp：任何写进 State 的值
     * 只要在区间外，<b>重启就会被静默改成区间端点</b> —— 那一刻预设匹配（EPS 1e-6）必然失配。
     * 顺序与 {@code ANIM_FIELDS} 一致。
     */
    private static final String[] ANIM_FIELDS = {
            "animationLength", "shortAnimationLength", "trailSize",
            "glowLayers", "shadowBlurFactor", "glowOpacity"};

    private static final float[] ANIM_MIN = {0.05f, 0.02f, 0f, 1f, 0.05f, 0f};
    private static final float[] ANIM_MAX = {0.35f, 0.25f, 1f, 12f, 0.60f, 1f};

    public static void main(String[] args) {
        System.out.println("=== V-ORTHO：预设字段域扩大后的正交性 / 迁移 / 落盘自洽 ===");
        V0Env.install();

        inMemoryApplyMatches();
        blurZeroRoundTrip();
        blurZeroSemantics();
        applySilentlyRewritesBlur();
        blurLabelMismatch();
        presetValuesInsidePersistRange();
        legacyIndexMigration();
        loadStateUpperBound();
        legacyIndexPanelLabel();
        legacyIndexDerivesWrongName();
        legacyIndexRestoreTurnsGlowOff();
        useShadowNotInMatch();
        panelComboConsistency();
        noDeriveOnShadowToggle();
        panelVsToolsDivergence();
        colorStillOrthogonal();
        applierWritesAllFields();

        System.out.println();
        System.out.printf("=== V-ORTHO 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-ORTHO RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-ORTHO RESULT: ALL PASS");
        System.exit(0);
    }

    // ---------------------------------------------------------------- 2) 纯内存往返

    /** 问题 2 的正面：8 字段全写之后，6 字段判据能不能反查回来。 */
    private static void inMemoryApplyMatches() {
        System.out.println();
        System.out.println("--- 2. apply 写 8 个 / match 只比 6 个：纯内存路径 ---");
        int bad = 0;
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            resetSvc();
            CursorPresetApplier.applyAnimPreset(p);
            CursorPresets.AnimPreset back = CursorPresetApplier.matchAnimPreset(V0Env.svc.getState());
            boolean ok = back == p;
            if (!ok) {
                bad++;
            }
            System.out.println("    " + (ok ? "PASS " : "FAIL ") + p.name()
                    + "  match=" + (back == null ? "null" : back.name()));
        }
        check("O-6 【问题2】每个预设 apply 后 matchAnimPreset 都能反查回自己"
                        + "（纯内存路径成立 → 失配只可能来自落盘 clamp）",
                bad == 0, bad + " 个失配");

        // 顺带证明：match 的判据确实看不见 useShadow/overlayFadeOut
        resetSvc();
        CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[0]);
        V0Env.svc.getState().useShadow = false;
        V0Env.svc.getState().overlayFadeOut = false;
        boolean stillBase = CursorPresetApplier.matchAnimPreset(V0Env.svc.getState())
                == CursorPresets.ANIM_PRESETS[0];
        // 【期望已反转】这条原本是「证明 6 字段判据失明」的断言（当时期望 stillBase=true）。
        // 修好之后仍然匹配 = bug 复活，所以现在期望改为 false：
        // 关掉辉光后【不应该】再被认成「基础」。
        check("O-6b 关掉辉光后不再被认成「基础」（match 判据已含 useShadow/overlayFadeOut）",
                !stillBase, "stillBase=" + stillBase);
    }

    // ---------------------------------------------------------------- 5) blur = 0

    /** 问题 5 的第一面：把预设声明的 0.0 写进 State 再走一次真实落盘，看它变成什么。 */
    private static void blurZeroRoundTrip() {
        System.out.println();
        System.out.println("--- 5a. 「纯平滑输入」的 shadowBlurFactor=0.0 能否活过落盘 ---");
        CursorPresets.AnimPreset pure = purePreset();
        if (pure == null) {
            check("O-3 找到「纯平滑输入」预设", false, "预设表里没有这个名字");
            return;
        }
        resetSvc();
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        NeovideCursorConfigurable.applyAnimPresetFields(s, pure);
        float wrote = s.shadowBlurFactor;

        NeovideCursorSettings.State back = roundTrip(s);
        check("O-3 【问题5】预设写出的 shadowBlurFactor 必须逐位活过落盘",
                Float.compare(back.shadowBlurFactor, wrote) == 0,
                "写=" + wrote + " 回读=" + back.shadowBlurFactor
                        + "（落盘 clamp 区间 0.05–0.60）");

        CursorPresets.AnimPreset matched = CursorPresetApplier.matchAnimPreset(back);
        check("O-4 【问题2/5】落盘往返后仍能反查到「纯平滑输入」",
                matched == pure,
                "回读后 match=" + (matched == null ? "null（界面显示基准项，实际已不是它）"
                        : matched.name()));
    }

    /** 问题 5 的第二面：那个被 clamp 之后的值，在滑块上是什么语义。 */
    private static void blurZeroSemantics() {
        System.out.println();
        System.out.println("--- 5b. blur=0 经落盘后，锐度滑块说的是「最柔」还是「最锐」---");
        CursorPresets.AnimPreset pure = purePreset();
        if (pure == null) {
            return;
        }
        resetSvc();
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        NeovideCursorConfigurable.applyAnimPresetFields(s, pure);
        NeovideCursorSettings.State back = roundTrip(s);

        int sharp = NeovideCursorConfigurable.sharpnessOf(back.shadowBlurFactor);
        float blur = NeovideCursorConfigurable.blurOfSharpness(sharp);
        check("O-5 【问题5·反向】用户开回辉光后必须得到「最柔」（外扩 0.60），不能变成最锐",
                Math.abs(blur - 0.60f) < 1e-6f,
                "回读 blur=" + back.shadowBlurFactor + " → 锐度=" + sharp
                        + " → 外扩=" + String.format(Locale.ROOT, "%.2f", blur)
                        + "（0.05 = 最锐，与预设注释「想要柔和光雾」正好相反）");
    }

    /** 根因判据：预设写出的每个值都必须在 loadState 的 clamp 区间内。 */
    private static void presetValuesInsidePersistRange() {
        System.out.println();
        System.out.println("--- 5c. 预设写出的每个值是否都落在 loadState 的合法区间 ---");
        int bad = 0;
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            float[] v = animValuesOf(p);
            for (int i = 0; i < v.length; i++) {
                if (v[i] < ANIM_MIN[i] - 1e-6f || v[i] > ANIM_MAX[i] + 1e-6f) {
                    bad++;
                    System.out.println("    FAIL " + p.name() + "." + ANIM_FIELDS[i]
                            + " = " + v[i] + " 不在 [" + ANIM_MIN[i] + "," + ANIM_MAX[i]
                            + "] → 每次落盘都会被静默改写");
                }
            }
        }
        check("O-13 【根因】全部预设取值都在持久化合法区间内（否则落盘即自改，匹配必失）",
                bad == 0, bad + " 个字段越界");
    }

    // ---------------------------------------------------------------- 6) 老下标迁移

    /**
     * animPresetBase 的越界回落（0.9.72 决策变更）。
     *
     * <p><b>原判据</b>：「老下标 0..6 迁移后都必须落到辉光开着的预设」。
     * 那是 0.9.10 七预设精简为三时的规则 —— 当时 2 是别的预设，
     * 把它映射到现在的 2（纯平滑输入，关辉光）会让用户莫名失去辉光。
     *
     * <p><b>为什么现在改</b>：0.9.72 起数组就是 3 项，<b>2 = 纯平滑输入 是合法值</b>，
     * 用户主动选中它当然允许关辉光。旧判据会把「我就是要关辉光」当成缺陷来报。
     *
     * <p>而且本插件实际只有单一用户（作者本人），没有需要保护的历史存量。
     *
     * <p><b>现在只测真正该管的事</b>：越界下标必须回落到「辉光开着」的默认预设，
     * 不能回落到关辉光那一档。
     */
    private static void legacyIndexMigration() {
        System.out.println();
        System.out.println("--- 6a. animPresetBase 越界回落（合法值不再强改）---");
        int bad = 0;
        StringBuilder trace = new StringBuilder();
        int last = CursorPresets.ANIM_PRESETS.length - 1;
        // 越界值：必须回落到「辉光开着」的默认预设
        for (int legacy : new int[]{last + 1, 6, 99, -1}) {
            resetSvc();
            NeovideCursorSettings.State old = new NeovideCursorSettings.State();
            old.animPresetBase = legacy;
            V0Env.svc.loadState(old);

            int stored = V0Env.svc.getState().animPresetBase;
            int idx = CursorPresets.clampAnimPresetIndex(stored);
            CursorPresets.AnimPreset p = CursorPresets.ANIM_PRESETS[idx];
            trace.append(legacy).append("→").append(p.name())
                    .append(p.useShadow() ? "" : "(关辉光!)").append("  ");
            if (!p.useShadow()) {
                bad++;
            }
            System.out.println("    越界 " + legacy + " 落库=" + stored
                    + " → 「" + p.name() + "」 useShadow=" + p.useShadow());
        }
        check("O-1 越界下标必须回落到「辉光开着」的预设（合法值原样保留，不再强改）",
                bad == 0, bad + " 个越界值落到关辉光的预设 | " + trace);
    }

    /** 问题 6 的根因：loadState 的 clamp 上界是硬编码 6，与表长脱钩。 */
    private static void loadStateUpperBound() {
        System.out.println();
        System.out.println("--- 6b. loadState 对 animPresetBase 的上界是否跟随 ANIM_PRESETS.length ---");
        resetSvc();
        NeovideCursorSettings.State crazy = new NeovideCursorSettings.State();
        crazy.stateVersion = 1;
        crazy.animPresetBase = 999;
        V0Env.svc.loadState(crazy);
        int v = V0Env.svc.getState().animPresetBase;
        check("O-2 【问题6·根因】loadState 之后 animPresetBase 必须 <= ANIM_PRESETS.length-1",
                v <= CursorPresets.ANIM_PRESETS.length - 1,
                "落库=" + v + " 表长=" + CursorPresets.ANIM_PRESETS.length
                        + "（NeovideCursorSettings.loadState:148 写死 clamp(.., 0, 6)）");
    }

    // ---------------------------------------------------------------- 3/4) 勾选态

    /** 问题 3/4：match 判据看不见 useShadow → Tools 勾选态与面板下拉都会撒谎。 */
    private static void useShadowNotInMatch() {
        System.out.println();
        System.out.println("--- 3/4. 手动关辉光后，勾选态 / 下拉该不该变「自定义」---");
        resetSvc();
        CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[0]);
        V0Env.svc.getState().useShadow = false;
        CursorPresets.AnimPreset m = CursorPresetApplier.matchAnimPreset(V0Env.svc.getState());
        check("O-7 【问题3/4】手动关掉辉光后 matchAnimPreset 不该再宣称是「"
                        + CursorPresets.ANIM_PRESETS[0].name() + "」（Tools 菜单的 ToggleAction 勾选态走它）",
                m != CursorPresets.ANIM_PRESETS[0],
                "match=" + (m == null ? "null（= 自定义，正确）" : m.name() + "（勾选态会显示为已选中）")
                        + " useShadow=" + V0Env.svc.getState().useShadow);
    }

    /** 面板侧的同一判据：下拉选中的预设必须与 State 的 8 个预设字段自洽。 */
    private static void panelComboConsistency() {
        System.out.println();
        System.out.println("--- 3b. 面板下拉显示的预设 vs 实际 8 个字段 ---");
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        NeovideCursorConfigurable.applyAnimPresetFields(st, CursorPresets.ANIM_PRESETS[0]);
        st.animPresetBase = 0;
        st.animCustomName = "";
        st.customAnimPresets = "";
        st.useShadow = false;                       // 用户手动关掉辉光

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();

        int sel = conf.animPresetComboForProbe().getSelectedIndex();
        String name = sel >= 0 && sel < CursorPresets.ANIM_PRESETS.length
                ? CursorPresets.ANIM_PRESETS[sel].name() : ("#" + sel);
        // 修好之后：关掉辉光时不该再匹配上「基础」，下拉应落到"无匹配"状态。
        boolean consistent = sel < 0
                || sel >= CursorPresets.ANIM_PRESETS.length
                || animatedEqual(conf.pendingForProbe(), CursorPresets.ANIM_PRESETS[sel]);
        check("O-8 【问题3·已修】关掉辉光后下拉不再自称「" + name + "」（显示层不撒谎）",
                consistent,
                "下拉=" + sel + "(" + name + ") useShadow=" + conf.pendingForProbe().useShadow);
    }

    /** 问题 3 的另一半：复选框改动没有落脚点（派生判据仍只看 6 字段）。 */
    private static void noDeriveOnShadowToggle() {
        System.out.println();
        System.out.println("--- 3c. 内置预设上手动关辉光 → 有没有派生落脚点 ---");
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        NeovideCursorConfigurable.applyAnimPresetFields(st, CursorPresets.ANIM_PRESETS[0]);
        st.animPresetBase = 0;
        st.animCustomName = "";
        st.customAnimPresets = "";

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();

        // 【为什么不用 doClick() 驱动复选框】headless 下 AbstractButton.doClick 会先判 isEnabled，
        // 而 DSL 的 cell.enabledIf(shadowOn) 在本桩里未必已落定，点击可能被整体吞掉 ——
        // 那测的是桩，不是被测代码。
        //
        // 这里直接走复选框点击的<b>下游同一个入口</b>：bindCheckTracked 的 setter 在写完
        // pending 之后调的就是 afterPendingEdited()，而面板上任何一次重算显示都会经
        // refreshPresetDisplay() → afterPendingEdited() 再跑一次同一个判据。
        // 因此「只把 useShadow 改掉，然后重算显示」等价于用户点一下那个复选框。
        NeovideCursorSettings.State p = conf.pendingForProbe();
        p.useShadow = false;
        conf.refreshPresetDisplayForProbe();

        NeovideCursorSettings.State after = conf.pendingForProbe();
        int derived = com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                .parse(after.customAnimPresets).size();
        check("O-9 【问题3】在内置预设上关辉光必须派生出落脚点"
                        + "（滑块改 6 字段会派生，复选框改预设域字段却不派生 = 两类改动行为不一致）",
                !after.animCustomName.isEmpty(),
                "useShadow=" + after.useShadow
                        + " animCustomName=\"" + after.animCustomName + "\""
                        + " 自定义预设数=" + derived
                        + " 复选框数=" + ui.checkBoxes.size());
    }

    /**
     * 问题 2 + 4 的合流：落盘之后「面板下拉」与「Tools 勾选」会不会各说一套。
     *
     * <p>下拉的当前项取自 {@code pending.animPresetBase}（持久化指针），
     * 而 Tools 的勾选态取自 {@code matchAnimPreset(state)}（6 字段实时比对）。
     * 两者一旦分叉，同一个「当前预设」在两处会显示不同的东西 —— 这就是「勾选态撒谎」。
     */
    private static void panelVsToolsDivergence() {
        System.out.println();
        System.out.println("--- 4. 落盘之后：面板下拉 vs Tools 勾选态是否自洽 ---");
        CursorPresets.AnimPreset pure = purePreset();
        if (pure == null) {
            return;
        }
        int pureIdx = 0;
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            if (CursorPresets.ANIM_PRESETS[i] == pure) {
                pureIdx = i;
            }
        }
        resetSvc();
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        NeovideCursorConfigurable.applyAnimPresetFields(s, pure);
        s.animPresetBase = pureIdx;
        s.animCustomName = "";
        s.customAnimPresets = "";
        NeovideCursorSettings.State back = roundTrip(s);

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        int panelSel = conf.animPresetComboForProbe().getSelectedIndex();
        CursorPresets.AnimPreset toolsMatch = CursorPresetApplier.matchAnimPreset(back);

        boolean fieldsHonest = panelSel >= 0 && panelSel < CursorPresets.ANIM_PRESETS.length
                && animatedEqual(back, CursorPresets.ANIM_PRESETS[panelSel]);
        check("O-14 【问题2+4】面板下拉选中的项必须同时满足：①与 Tools 判据一致 ②八个字段真等于它",
                fieldsHonest && toolsMatch == CursorPresets.ANIM_PRESETS[panelSel],
                "面板下拉=" + panelSel + "(" + (panelSel >= 0
                        && panelSel < CursorPresets.ANIM_PRESETS.length
                        ? CursorPresets.ANIM_PRESETS[panelSel].name() : "?")
                        + ")  Tools勾选=" + (toolsMatch == null ? "无（null）" : toolsMatch.name())
                        + "  实存blur=" + back.shadowBlurFactor);
    }

    /**
     * 问题 5 的显示面：预设声明 0.0，锐度滑块却按 0.05 显示。
     *
     * <p>{@code sharpnessOf()} 首句就把入参夹到 {@code [0.05, 0.60]}，
     * 于是「存着 0.0」与「显示 0.05」同时成立。用户看到的既不是存下的值，
     * 也不是（改后）将要存下的值 —— 只要碰一下那个滑块，0.0 就永久变成 0.05。
     */
    private static void blurLabelMismatch() {
        System.out.println();
        System.out.println("--- 5d. 选「纯平滑输入」后，锐度那一行显示的外扩 vs 实际存储 ---");
        CursorPresets.AnimPreset pure = purePreset();
        if (pure == null) {
            return;
        }
        resetSvc();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();
        conf.pickAnimPresetForProbe(CursorPresets.ANIM_PRESETS.length - 1);

        int idx = VUi.indexOfKey("shadowBlurFactor");
        float stored = conf.pendingForProbe().shadowBlurFactor;
        String shown = ui.valueLabels.get(idx).getText();
        String expected = String.format(Locale.ROOT, "%.2f", stored);
        check("O-19 【问题5·显示层】锐度那一行显示的外扩必须等于实际存储的 shadowBlurFactor",
                shown.contains(expected),
                "存储=" + stored + " 标签=\"" + shown + "\" 滑块刻度="
                        + ui.sliders.get(idx).getValue());
    }

    /**
     * 问题 6 的第二层伤害：老下标连带产生<b>名字错误的副本</b>。
     *
     * <p>老用户在第 5 个预设（旧「柔和」）上改了一个滑块 → {@code afterPendingEdited()}
     * 用 {@code clamp(pending.animPresetBase)} 取基准名，于是派生出的副本叫
     * 「纯平滑输入 副本」。用户从没见过「纯平滑输入」这个预设，列表里却凭空冒出它的副本。
     */
    private static void legacyIndexDerivesWrongName() {
        System.out.println();
        System.out.println("--- 6d. 老下标 5 + 用户改一个滑块 → 派生出的副本叫什么 ---");
        resetSvc();
        NeovideCursorSettings.State old = new NeovideCursorSettings.State();
        old.stateVersion = 1;
        old.animPresetBase = 5;                     // 旧「柔和」
        old.useShadow = true;
        old.animationLength = 0.18f;                // 旧「柔和」的取值
        old.trailSize = 0.8f;
        old.glowLayers = 6;
        old.shadowBlurFactor = 0.30f;
        old.glowOpacity = 0.40f;
        V0Env.svc.loadState(old);

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        conf.pendingForProbe().glowLayers = 7;      // 用户拖一下「辉光层数」
        conf.refreshPresetDisplayForProbe();

        String derived = conf.pendingForProbe().animCustomName;
        boolean plausible = derived.isEmpty()
                || derived.startsWith("纯平滑输入") == false;
        check("O-17 【问题6·派生名】老下标 5 的用户改动后，派生副本不能叫「纯平滑输入 副本」"
                        + "（他从没选过那个预设，也没关过辉光）",
                plausible,
                "派生名=\"" + derived + "\"（基准来自 clamp(5)="
                        + CursorPresets.clampAnimPresetIndex(5) + "）");
    }

    /**
     * 问题 6 的第三层伤害：面板「恢复为「X」默认值」把老用户带进关辉光状态。
     */
    private static void legacyIndexRestoreTurnsGlowOff() {
        System.out.println();
        System.out.println("--- 6e. 老下标 5 的用户点「恢复为「X」默认值」---");
        resetSvc();
        NeovideCursorSettings.State old = new NeovideCursorSettings.State();
        old.stateVersion = 1;
        old.animPresetBase = 5;
        old.useShadow = true;
        old.overlayFadeOut = true;
        V0Env.svc.loadState(old);

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        String btn = conf.animRestoreButtonForProbe() == null ? "<null>"
                : conf.animRestoreButtonForProbe().getText();
        conf.animRestoreButtonForProbe().doClick();
        NeovideCursorSettings.State p = conf.pendingForProbe();
        check("O-18 【问题6·恢复按钮】老下标 5 的用户点恢复后不该被关掉辉光"
                        + "（他从没选过「纯平滑输入」）",
                p.useShadow,
                "按钮文案=\"" + btn + "\" 恢复后 useShadow=" + p.useShadow
                        + " overlayFadeOut=" + p.overlayFadeOut);
    }

    /**
     * 问题 2 的<b>最严重形态</b>：不用等重启，面板里点「应用」就把 blur 0 改成 0.05。
     *
     * <p>机制：{@code DialogPanel.apply()} 会把每个绑定滑块的<b>组件值</b>写回 {@code pending}。
     * 锐度滑块的 getter 是 {@code snap(sharpnessOf(blur))}，而 {@code sharpnessOf(0f)} 先被
     * 夹到 0.05 再算出锐度 100；写回时 {@code blurOfSharpness(100)} = 0.05。
     * 于是「预设声明 0.0」在<b>第一次点应用</b>时就被 0.05 覆盖，重启只是让它更难发现。
     */
    private static void applySilentlyRewritesBlur() {
        System.out.println();
        System.out.println("--- 2b. 面板选「纯平滑输入」→ 点应用：blur 是否被滑块写回 ---");
        CursorPresets.AnimPreset pure = purePreset();
        if (pure == null) {
            return;
        }
        resetSvc();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        conf.pickAnimPresetForProbe(CursorPresets.ANIM_PRESETS.length - 1);   // 「纯平滑输入」
        float inPending = conf.pendingForProbe().shadowBlurFactor;
        conf.apply();

        float inState = V0Env.svc.getState().shadowBlurFactor;
        CursorPresets.AnimPreset back = CursorPresetApplier.matchAnimPreset(V0Env.svc.getState());
        check("O-16 【问题2·最严重】面板选「纯平滑输入」并点应用后，state.shadowBlurFactor 仍是预设声明的 "
                        + pure.shadowBlurFactor() + "，且 matchAnimPreset 仍能反查回它",
                Float.compare(inState, pure.shadowBlurFactor()) == 0 && back == pure,
                "选预设后 pending=" + inPending + " 应用后 state=" + inState
                        + " match=" + (back == null ? "null" : back.name())
                        + "（结论：apply 的脏字段合并<b>没有</b>把 0.0 改写成 0.05，"
                        + "丢失发生在<b>下一次 loadState</b>）");
    }

    /**
     * 问题 6 的用户可见面：老 {@code animPresetBase} 已越界时，面板下拉会自称是谁。
     *
     * <p>{@code createPanel()} 用 {@code clampAnimPresetIndex(pending.animPresetBase)} 决定当前项，
     * 而 {@code loadState()} 只把值钳到 {@code 0..6}。于是 3..6 全部映射到下标 2，
     * 下拉会**宣称**用户的设置是「纯平滑输入」—— 那份关辉光、关淡出的预设。
     */
    private static void legacyIndexPanelLabel() {
        System.out.println();
        System.out.println("--- 6c. 老 animPresetBase=5 时面板下拉自称是哪个预设 ---");
        resetSvc();
        NeovideCursorSettings.State old = new NeovideCursorSettings.State();
        old.stateVersion = 1;
        old.animPresetBase = 5;                     // 老「高对比」类预设的下标
        old.useShadow = true;                       // 老用户的真实状态：辉光开着
        old.overlayFadeOut = true;
        old.animationLength = 0.15f;
        old.shadowBlurFactor = 0.05f;               // 高对比：外扩最窄
        V0Env.svc.loadState(old);

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();

        int sel = conf.animPresetComboForProbe().getSelectedIndex();
        String shown = sel >= 0 && sel < CursorPresets.ANIM_PRESETS.length
                ? CursorPresets.ANIM_PRESETS[sel].name() : ("#" + sel);
        NeovideCursorSettings.State p = conf.pendingForProbe();
        boolean honest = sel < 0
                || sel >= CursorPresets.ANIM_PRESETS.length
                || animatedEqual(p, CursorPresets.ANIM_PRESETS[sel]);
        check("O-15 【问题6·用户可见】老下标 5 的用户，下拉显示「" + shown + "」时八个字段必须真等于它",
                honest,
                "下拉=" + sel + "(" + shown + ") useShadow=" + p.useShadow
                        + " fade=" + p.overlayFadeOut
                        + " animLength=" + p.animationLength
                        + " blur=" + p.shadowBlurFactor
                        + "（下拉宣称的预设 useShadow=" + CursorPresets.ANIM_PRESETS[sel == 2 ? 2 : 0].useShadow() + "）");
    }

    // ---------------------------------------------------------------- 1) 正交性

    /** 问题 1：颜色预设 ⇄ 动画预设是否仍正交（新增的两个开关也要一起测）。 */
    private static void colorStillOrthogonal() {
        System.out.println();
        System.out.println("--- 1. 颜色预设 ⇄ 动画预设 正交性（含新增的 useShadow / overlayFadeOut）---");
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        st.tailColor = "#123456";
        st.shadowColor = "#ABCDEF";
        int bad = 0;
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            CursorPresetApplier.applyAnimPreset(p);
            NeovideCursorSettings.State cur = V0Env.svc.getState();
            boolean same = "#123456".equalsIgnoreCase(cur.tailColor)
                    && "#ABCDEF".equalsIgnoreCase(cur.shadowColor);
            if (!same) {
                bad++;
            }
            System.out.println("    " + (same ? "PASS " : "FAIL ") + p.name()
                    + "  tail=" + cur.tailColor + " shadow=" + cur.shadowColor);
        }
        check("O-10 【问题1】动画预设仍然不改任何 HEX（颜色正交成立）", bad == 0,
                bad + " 个预设污染颜色");

        resetSvc();
        V0Env.svc.getState().useShadow = true;
        V0Env.svc.getState().overlayFadeOut = true;
        int bad2 = 0;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            CursorPresetApplier.applyColorPreset(p);
            NeovideCursorSettings.State cur = V0Env.svc.getState();
            boolean same = cur.useShadow && cur.overlayFadeOut;
            if (!same) {
                bad2++;
            }
        }
        check("O-11 【问题1】颜色预设仍然不改 useShadow / overlayFadeOut（反向正交也成立）",
                bad2 == 0, bad2 + " 个颜色预设越界");
    }

    /** 问题 7：共用入口的落盘完整性（调用方是否都补了 persist + applyConfig）。 */
    private static void applierWritesAllFields() {
        System.out.println();
        System.out.println("--- 7. applyAnimPresetFields 的调用方是否都补了落盘与生效 ---");
        int bad = 0;
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            resetSvc();
            CursorPresetApplier.applyAnimPreset(p);
            NeovideCursorSettings.State cur = V0Env.svc.getState();
            boolean ok = cur.useShadow == p.useShadow()
                    && cur.overlayFadeOut == p.overlayFadeOut()
                    && Math.abs(cur.shadowBlurFactor - p.shadowBlurFactor()) < CursorPresets.EPS;
            if (!ok) {
                bad++;
            }
            System.out.println("    " + (ok ? "PASS " : "FAIL ") + p.name()
                    + "  useShadow=" + cur.useShadow + "/" + p.useShadow()
                    + " fade=" + cur.overlayFadeOut + "/" + p.overlayFadeOut());
        }
        check("O-12 【问题7】Tools 路径（CursorPresetApplier）把 8 个预设字段全部落到真实 state",
                bad == 0, bad + " 个预设未落地");
    }

    // ---------------------------------------------------------------- 工具

    private static CursorPresets.AnimPreset purePreset() {
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            if ("纯平滑输入".equals(p.name())) {
                return p;
            }
        }
        return null;
    }

    private static float[] animValuesOf(CursorPresets.AnimPreset p) {
        return new float[]{p.animationLength(), p.shortAnimationLength(), p.trailSize(),
                p.glowLayers(), p.shadowBlurFactor(), p.glowOpacity()};
    }

    /** 8 个预设字段（6 个动画值 + 两个开关）是否与该预设自洽。 */
    private static boolean animatedEqual(NeovideCursorSettings.State s, CursorPresets.AnimPreset p) {
        return Math.abs(s.animationLength - p.animationLength()) < CursorPresets.EPS
                && Math.abs(s.shortAnimationLength - p.shortAnimationLength()) < CursorPresets.EPS
                && Math.abs(s.trailSize - p.trailSize()) < CursorPresets.EPS
                && s.glowLayers == p.glowLayers()
                && Math.abs(s.shadowBlurFactor - p.shadowBlurFactor()) < CursorPresets.EPS
                && Math.abs(s.glowOpacity - p.glowOpacity()) < CursorPresets.EPS
                && s.useShadow == p.useShadow()
                && s.overlayFadeOut == p.overlayFadeOut();
    }

    /** 清空服务状态（stateVersion=1，避开旧键迁移噪音）。 */
    private static void resetSvc() {
        NeovideCursorSettings.State fresh = new NeovideCursorSettings.State();
        fresh.stateVersion = 1;
        V0Env.svc.loadState(fresh);
    }

    /**
     * 走<b>真实平台的序列化链</b>再 loadState —— 这是「重启 IDE」的等价路径。
     *
     * <p>只做 serialize/deserialize 不调 loadState 测不出 clamp，
     * 因为 clamp 全部在 {@code loadState()} 里。
     */
    private static NeovideCursorSettings.State roundTrip(NeovideCursorSettings.State s) {
        s.stateVersion = 1;
        org.jdom.Element el = com.intellij.util.xmlb.XmlSerializer.serialize(s);
        NeovideCursorSettings.State back = com.intellij.util.xmlb.XmlSerializer
                .deserialize(el, NeovideCursorSettings.State.class);
        V0Env.svc.loadState(back);
        return V0Env.svc.getState();
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
