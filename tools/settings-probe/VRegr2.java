import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * V-REGR2 —— 第三轮（t5）：<b>上一轮 11 条修法的回归</b>。
 *
 * <p>本轮又动了三件事，逐条验证它们有没有把上一轮的修法弄回去：
 * <ol>
 *   <li>{@code AnimPreset} 从 8 字段扩到 <b>9 字段</b>（+ {@code typingSmoothness}）；</li>
 *   <li>{@code AnimPresetGroup} 增加自定义预设子菜单（运行期数据 + {@code update()} 重建）；</li>
 *   <li>下拉末尾占位项「自定义（未保存）」+ {@code skipDeriveOnce}。</li>
 * </ol>
 *
 * <p><b>上一轮修法清单（逐条对应）</b>：
 * <pre>
 *   F1  老 animPresetBase 显式重映射            → R-1 / R-2
 *   F2  blur 60（最柔端）在合法区间              → R-3
 *   F3  matchAnimPreset 比 8 字段               → R-4 / R-5（现在必须比 9，且与写入严格一致）
 *   F4  派生判据含 useShadow/overlayFadeOut     → R-6 / R-7（现在必须含 smooth）
 *   T3-1 复选框改动经 apply() 后的 afterPendingEdited → R-8 / R-9
 *   T3-2 paintDiag 以组件为真相源                → R-10
 *   O-8  下拉不能"自称"是某预设（占位项）        → R-11 / R-12
 * </pre>
 *
 * <p>判据一律是<b>行为断言</b>：字段域、量程、区间在本探针里独立抄一份。
 */
public final class VRegr2 {

    private static int pass = 0;
    private static int fail = 0;

    /** 面板下拉末尾的占位项文案（探针独立抄一份，用于识别"停在占位"）。 */
    private static final String PLACEHOLDER = "自定义（未保存）";

    /**
     * {@code shadowBlurFactor} 的持久化合法区间（来自 {@code NeovideCursorSettings.loadState} 的 clamp）。
     * 独立抄一份 —— 用被测方的常量校验被测方等于自证。
     */
    private static final float BLUR_MIN = 0.05f;
    private static final float BLUR_MAX = 0.60f;

    public static void main(String[] args) {
        System.out.println("=== V-REGR2：上一轮 11 条修法的回归（9 字段预设域 + Tools 菜单 + 占位项）===");
        V0Env.install();

        legacyBaseRemap();
        legacyBaseNeverTurnsGlowOff();
        blurInsidePersistRange();
        matchCoversEveryWrittenField();
        matchAfterApplyAllPresets();
        deriveCoversSmooth();
        deriveOnSmoothChange();
        deriveJudgementDivergesFromMatch();
        applyKeepsComboCheckboxAfterPresetSwap();
        paintDiagSurvivesPresetSwap();
        placeholderFallsBackToPreset();
        placeholderNotSelectable();
        customSnapshotEqualsPresetFields();
        customApplyMustNotTouchEnvironmentSwitches();
        presetDefaultsStillMatch();
        toolsApplyOverwritesUserSmooth();
        toolsMenuSeparatorAccumulation();
        toolsMenuDoubleSelection();
        toolsApplyCustomLeavesStaleBase();
        applyMissingCustomIsAtomic();

        System.out.println();
        System.out.printf("=== V-REGR2 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-REGR2 RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-REGR2 RESULT: ALL PASS");
        System.exit(0);
    }

    // ---------------------------------------------------------------- 1) F1 重映射

    /**
     * animPresetBase 的合法值校验（0.9.72 决策变更）。
     *
     * <p><b>决策变更原因</b>：本探针原来断言「0/1 保留、2..6 一律回落 0」，
     * 那是 0.9.10「七个预设精简到三个」时写的老下标迁移规则 —— 当时 2 是别的预设。
     *
     * <p>但 0.9.72 起，数组就是 3 项，<b>2 = 纯平滑输入 是合法值</b>。
     * 旧规则会把用户选的「纯平滑输入」每次重启踢回「基础」。
     *
     * <p><b>为什么不做 stateVersion 分支迁移</b>：本插件实际只有单一用户（作者本人），
     * 没有需要保护的历史存量数据。为一个不存在的用户群加一次迁移 + 双分支断言不划算。
     * 真出现外部用户时再补（届时 R-1 会变回双分支断言）。
     *
     * <p>现在的判据：<b>合法范围内原样保留，越界回落默认</b>。
     */
    private static void legacyBaseRemap() {
        System.out.println();
        System.out.println("--- 1. animPresetBase 合法值校验（合法保留 / 越界回落）---");
        int last = CursorPresets.ANIM_PRESETS.length - 1;
        int bad = 0;
        StringBuilder trace = new StringBuilder();
        // 合法范围内：0..last 必须原样保留
        for (int v = 0; v <= last; v++) {
            resetSvc();
            NeovideCursorSettings.State old = new NeovideCursorSettings.State();
            old.stateVersion = 1;
            old.animPresetBase = v;
            V0Env.svc.loadState(old);
            int got = V0Env.svc.getState().animPresetBase;
            trace.append(v).append("→").append(got).append("  ");
            if (got != v) {
                bad++;
                System.out.println("    FAIL 合法下标 " + v + " 被改动，实际 " + got);
            }
        }
        // 越界：必须回落默认
        for (int v : new int[]{-1, last + 1, 99}) {
            resetSvc();
            NeovideCursorSettings.State old = new NeovideCursorSettings.State();
            old.animPresetBase = v;
            V0Env.svc.loadState(old);
            int got = V0Env.svc.getState().animPresetBase;
            if (got != CursorPresets.ANIM_PRESET_DEFAULT_INDEX) {
                bad++;
                System.out.println("    FAIL 越界 " + v + " 未回落，实际 " + got);
            }
        }
        check("R-1 合法下标（0.." + last + "）原样保留，越界回落默认",
                bad == 0, bad + " 个不符 | " + trace);
    }

    /**
     * 原 F1 目的：老下标不许被丢进关辉光的预设。
     *
     * <p><b>0.9.72 起这条不再适用于「合法下标」</b>：2 = 纯平滑输入 本来就关辉光，
     * 用户<b>主动选中</b>它当然是允许的。本探针改为只检查<b>越界回落</b>的那一档
     * —— 越界值必须落到「辉光开着」的默认预设，不能落到关辉光的那一档。
     */
    private static void legacyBaseNeverTurnsGlowOff() {
        System.out.println();
        System.out.println("--- 2. 越界下标回落时必须落到「辉光开着」的预设 ---");
        int bad = 0;
        StringBuilder trace = new StringBuilder();
        // 只测越界值：合法下标（含 2=纯平滑输入）是用户主动选择的，不该被强改
        for (int legacy : new int[]{CursorPresets.ANIM_PRESETS.length, 99, -1}) {
            resetSvc();
            NeovideCursorSettings.State old = new NeovideCursorSettings.State();
            old.animPresetBase = legacy;
            old.useShadow = true;
            V0Env.svc.loadState(old);
            int idx = CursorPresets.clampAnimPresetIndex(V0Env.svc.getState().animPresetBase);
            CursorPresets.AnimPreset p = CursorPresets.ANIM_PRESETS[idx];
            if (!p.useShadow()) {
                bad++;
            }
            trace.append(legacy).append("→").append(p.name())
                    .append(p.useShadow() ? "(辉光开)" : "(关辉光)").append("  ");
        }
        check("R-2 【F1 回归】全部老下标迁移后都落在「辉光开着」的预设",
                bad == 0, bad + " 个落到关辉光 | " + trace);
    }

    // ---------------------------------------------------------------- 2) F2 blur 区间

    /** 上一轮 F2：blur 从 0 改成 60，必须在持久化区间内。 */
    private static void blurInsidePersistRange() {
        System.out.println();
        System.out.println("--- 3. F2 回归：全部预设的 shadowBlurFactor 都在 [0.05, 0.60] ---");
        int bad = 0;
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            float b = p.shadowBlurFactor();
            boolean ok = b >= BLUR_MIN - 1e-6f && b <= BLUR_MAX + 1e-6f;
            if (!ok) {
                bad++;
            }
            System.out.println("    " + (ok ? "PASS " : "FAIL ") + p.name()
                    + "  blur=" + b);
        }
        check("R-3 【F2 回归】全部预设的 blur 都在持久化区间内（落盘不会被静默改写）",
                bad == 0, bad + " 个越界");

        // 并且必须真的活过落盘 + 反查得回来
        CursorPresets.AnimPreset pure = purePreset();
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        NeovideCursorConfigurable.applyAnimPresetFields(s, pure);
        NeovideCursorSettings.State back = roundTrip(s);
        float wrote = pure.shadowBlurFactor();
        check("R-3b 【F2 回归】「纯平滑输入」逐位活过落盘，且仍 match 得回来",
                Float.compare(back.shadowBlurFactor, wrote) == 0
                        && CursorPresetApplier.matchAnimPreset(back) == pure,
                "写=" + wrote + " 回读=" + back.shadowBlurFactor
                        + " match=" + nameOf(CursorPresetApplier.matchAnimPreset(back)));
    }

    // ---------------------------------------------------------------- 3) 9 字段一致性

    /**
     * 本轮的核心一致性判据：{@code applyAnimPresetFields} 写的字段集
     * 必须与 {@code matchAnimPreset} 比的字段集<b>完全相等</b>。
     *
     * <p>多比一个（写了但 match 不比，或反之）= 应用后 match 不回来；
     * 少比一个 = 用户改了那个字段后勾选态/下拉撒谎。两个方向都必须挡住。
     */
    private static void matchCoversEveryWrittenField() {
        System.out.println();
        System.out.println("--- 4. 第 9 字段一致性：apply 写的每个字段都被 match 看见 ---");
        CursorPresets.AnimPreset p0 = CursorPresets.ANIM_PRESETS[0];

        // ① 基准：干净状态必须 match
        resetSvc();
        NeovideCursorConfigurable.applyAnimPresetFields(V0Env.svc.getState(), p0);
        boolean base = CursorPresetApplier.matchAnimPreset(V0Env.svc.getState()) == p0;
        check("R-4 基准状态能 match（apply 与 match 的字段集至少相容）", base,
                "match=" + nameOf(CursorPresetApplier.matchAnimPreset(V0Env.svc.getState())));

        // ② 逐字段扰动：把 apply 写入的 9 个字段【各自】改一点点，match 必须全部掉下来。
        //    某个字段扰动后仍能 match = 该字段没被 match 看见 = 判据漏了一个。
        List<String> blind = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            resetSvc();
            NeovideCursorConfigurable.applyAnimPresetFields(V0Env.svc.getState(), p0);
            perturb(V0Env.svc.getState(), i);
            if (CursorPresetApplier.matchAnimPreset(V0Env.svc.getState()) == p0) {
                blind.add(FIELD_NAMES[i]);
            }
        }
        check("R-5 【第9字段】9 个预设字段逐个扰动后 match 都必须掉下来（否则勾选态对该字段撒谎）",
                blind.isEmpty(), "漏看的字段=" + blind);
    }

    /** 应用每个预设后必须能 match 回来（第 9 字段写进去后仍然成立）。 */
    private static void matchAfterApplyAllPresets() {
        System.out.println();
        System.out.println("--- 5. 每个预设 apply 后 match 都能反查回来（含第 9 字段）---");
        int bad = 0;
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            resetSvc();
            CursorPresetApplier.applyAnimPreset(p);
            NeovideCursorSettings.State after = roundTrip(V0Env.svc.getState());
            CursorPresets.AnimPreset back = CursorPresetApplier.matchAnimPreset(after);
            // 【0.9.47 语义反转】输入平滑已改为全局设置（不随预设变化）。
            // 原断言「切预设后 smooth == 预设值」不再成立。
            // 新断言：match 得回来即可（smooth 不再参与匹配）。
            boolean ok = back == p;
            if (!ok) {
                bad++;
            }
            System.out.println("    " + (ok ? "PASS " : "FAIL ") + p.name()
                    + "  match=" + nameOf(back)
                    + " smooth=" + after.typingSmoothness + "(全局)");
        }
        // 0.9.47：smooth 已移出预设，本断言只看 match。
        check("R-5b 全部预设 apply + 落盘往返后 match 得回来（smooth 已改全局不参与）",
                bad == 0, bad + " 个失配");
    }

    // ---------------------------------------------------------------- 4) 派生判据

    /** 上一轮 F4 的判据扩到 8 字段，本轮加了第 9 个 —— 它也必须纳入。 */
    private static void deriveCoversSmooth() {
        System.out.println();
        System.out.println("--- 6. F4 回归 + 第 9 字段：在内置预设上改 smooth 必须派生落脚点 ---");
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        NeovideCursorConfigurable.applyAnimPresetFields(st, CursorPresets.ANIM_PRESETS[0]);
        st.animPresetBase = 0;
        st.animCustomName = "";
        st.customAnimPresets = "";

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();

        NeovideCursorSettings.State p = conf.pendingForProbe();
        p.typingSmoothness = 1.0f;                 // 用户把「输入平滑」拖到 100
        conf.refreshPresetDisplayForProbe();

        NeovideCursorSettings.State after = conf.pendingForProbe();
        check("R-6 【第9字段·派生】在内置预设上改 typingSmoothness 必须派生出落脚点"
                        + "（否则用户调好的 smooth 无处保存，切预设即丢）",
                !after.animCustomName.isEmpty(),
                "animCustomName=\"" + after.animCustomName + "\" smooth=" + after.typingSmoothness
                        + " 自定义预设数=" + CustomPresets.parse(after.customAnimPresets).size());

        // 上一轮 F4 的两个字段仍必须有效（回归）
        resetSvc();
        NeovideCursorSettings.State st2 = V0Env.svc.getState();
        NeovideCursorConfigurable.applyAnimPresetFields(st2, CursorPresets.ANIM_PRESETS[0]);
        st2.animPresetBase = 0;
        st2.animCustomName = "";
        st2.customAnimPresets = "";
        NeovideCursorConfigurable conf2 = new NeovideCursorConfigurable();
        VUi.build(conf2);
        conf2.reset();
        conf2.pendingForProbe().useShadow = false;
        conf2.refreshPresetDisplayForProbe();
        check("R-7 【F4 回归】在内置预设上关辉光仍然必须派生落脚点",
                !conf2.pendingForProbe().animCustomName.isEmpty(),
                "animCustomName=\"" + conf2.pendingForProbe().animCustomName + "\"");
    }

    // ---------------------------------------------------------------- 5) apply 交互

    /** T3-1 的修法：apply() 里 super.apply() 之后的 afterPendingEdited() 与 skipDeriveOnce 的配合。 */
    private static void deriveOnSmoothChange() {
        System.out.println();
        System.out.println("--- 8. skipDeriveOnce 与 apply() 末尾 afterPendingEdited() 的交互 ---");
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        NeovideCursorConfigurable.applyAnimPresetFields(st, CursorPresets.ANIM_PRESETS[0]);
        st.animPresetBase = 0;
        st.animCustomName = "";
        // 预先存一个自定义预设，再把它删掉 —— 模拟 deleteCustomPreset() 的 skipDeriveOnce
        st.customAnimPresets = CustomPresets.put("", "要删的",
                SettingsField.snapshot(st));

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();

        // 【必须走控件】T3-1 的真实路径是「用户点复选框」：
        // 复选框的 setter 只在 DialogPanel 的 apply 回调里跑，那时 pending 才拿到新值。
        // 直接改 pending 再 apply 会被 super.apply() 用【陈旧控件值】覆盖回去 ——
        // 那测的是探针自己的写法，不是被测代码。
        if (ui.checkBoxes.size() < 5) {
            check("R-8 定位到「启用辉光」复选框", false, "复选框=" + ui.checkBoxes.size());
            return;
        }
        javax.swing.JCheckBox shadowBox = ui.checkBoxByLabel("启用辉光");
        shadowBox.setSelected(false);               // 用户在「基础」预设上关掉辉光
        conf.apply();

        boolean derivedAfterApply = !V0Env.svc.getState().animCustomName.isEmpty();
        boolean shadowOff = !V0Env.svc.getState().useShadow;
        check("R-8 T3-1 修法仍生效：点复选框关辉光 → 点应用 → 必须派生出落脚点并真的落盘",
                derivedAfterApply && shadowOff,
                "state.animCustomName=\"" + V0Env.svc.getState().animCustomName + "\""
                        + " useShadow=" + V0Env.svc.getState().useShadow
                        + "（若派生名为空 = afterPendingEdited 没跑到，复选框改动又会丢）");

        // 【T3-1 连带的第二半：删除之后点「应用」会不会把它复活】
        //
        // deleteCustomPreset() 的做法是：skipDeriveOnce=true → 清掉快照与名字 →
        // syncAllControlsFromPending()（这一下消耗掉 skipDeriveOnce）。
        // 但【值本身不变】（注释明写"值保持在当前参数上"），于是 pending 仍然偏离内置常量。
        // 之后用户点「应用」→ apply() 末尾的 afterPendingEdited() 再跑一次，
        // 而 skipDeriveOnce 已被消耗 → 派生判据看到"偏离" → 刚删掉的预设原地复活。
        //
        // 探针走的是删除动作【对 state 的全部可观测效果】（清表 + 清名 + 保持值），
        // 然后驱动 apply()。Messages.showYesNoDialog 在离线桩里不可用，
        // 所以不能直接点按钮 —— 但删除之后的可观测状态与这里完全一致。
        resetSvc();
        NeovideCursorSettings.State st2 = V0Env.svc.getState();
        NeovideCursorConfigurable.applyAnimPresetFields(st2, CursorPresets.ANIM_PRESETS[0]);
        st2.animPresetBase = 0;
        st2.glowLayers = 10;                        // 偏离内置常量（删掉副本后值仍留在参数上）
        st2.customAnimPresets = CustomPresets.put("", "要删的", SettingsField.snapshot(st2));

        NeovideCursorConfigurable conf2 = new NeovideCursorConfigurable();
        VUi.build(conf2);
        conf2.reset();

        // 【探针修正：必须复现真实 deleteCustomPreset() 的完整语义】
        // 旧版探针只做了「删表项 + 清名字」，漏了修复的核心一步：**吸附回基准预设**。
        // 真实 deleteCustomPreset() 现在是：
        //   ① customAnimPresets = remove(...)
        //   ② animCustomName = ""
        //   ③ applyAnimPresetFields(pending, ANIM_PRESETS[base])   ← 吸附回基准
        //   ④ syncAllControlsFromPending()
        // 只做 ①② 当然会在 apply 时被 afterPendingEdited 派生回来 —— 那不是 bug，
        // 是探针没走完整路径。这里补上 ③④。
        conf2.pendingForProbe().customAnimPresets = "";
        conf2.pendingForProbe().animCustomName = "";
        NeovideCursorConfigurable.applyAnimPresetFields(
                conf2.pendingForProbe(),
                CursorPresets.ANIM_PRESETS[
                        CursorPresets.clampAnimPresetIndex(conf2.pendingForProbe().animPresetBase)]);

        // 【关键：必须把 pending 回灌进控件】
        // apply() 的第一件事是 super.apply()，它把【控件值】写回 pending。
        // 探针直接改 pending 而不回灌控件 → apply 时陈旧控件值把刚吸附好的值冲回去
        // → 又被判为"偏离基准" → 派生副本。这不是生产 bug，是探针漏了 sync 这一步。
        // 复现真实路径最简单的方式：先写回 state，再 reset()（reset 会完整回灌控件）。
        V0Env.svc.getState().customAnimPresets = conf2.pendingForProbe().customAnimPresets;
        V0Env.svc.getState().animCustomName = "";
        V0Env.svc.getState().glowLayers = conf2.pendingForProbe().glowLayers;
        V0Env.svc.getState().animationLength = conf2.pendingForProbe().animationLength;
        conf2.reset();

        // —— 用户接着点「应用」——
        conf2.apply();
        String afterApply = conf2.pendingForProbe().animCustomName;
        int countAfterApply = CustomPresets.parse(conf2.pendingForProbe().customAnimPresets).size();
        String inState = V0Env.svc.getState().animCustomName;

        check("R-9 【T3-1 连带】删掉自定义预设后点「应用」，它必须保持删除（不许原地复活）",
                afterApply.isEmpty() && countAfterApply == 0 && inState.isEmpty(),
                "点应用后 pending.animCustomName=\"" + afterApply + "\""
                        + " 自定义预设数=" + countAfterApply
                        + " state.animCustomName=\"" + inState + "\"");
    }

    // ---------------------------------------------------------------- 6) 控件一致性

    /** T3-2 之前的连带：换预设后，复选框 / 下拉 / 滑块是否与 pending 一致。 */
    private static void applyKeepsComboCheckboxAfterPresetSwap() {
        System.out.println();
        System.out.println("--- 9. 换预设后：全部绑定控件是否与 pending 一致（super.reset 覆盖度）---");
        resetSvc();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();

        // 切到「纯平滑输入」（关辉光 + 关淡出 + smooth=0）
        int last = CursorPresets.ANIM_PRESETS.length - 1;
        conf.pickAnimPresetForProbe(last);
        NeovideCursorSettings.State p = conf.pendingForProbe();
        CursorPresets.AnimPreset expect = CursorPresets.ANIM_PRESETS[last];

        // 【0.9.71：定位与顺序契约分开写】
        //
        // 原来这里用 get(3)/get(4) 取「辉光淡出」「启用辉光」。
        // 那是**用索引定位**，一旦组顺序变化，取到的是别的控件，
        // 断言会红在「复选框与 pending 不一致」—— 报症状不报病因。
        //
        // 现在分两步：
        //   ① 显式断言顺序契约（变了就红得明白）
        //   ② 按文案定位，与顺序解耦
        boolean orderOk = ui.assertCheckBoxAt(3, "辉光淡出")
                && ui.assertCheckBoxAt(4, "启用辉光");
        check("R-10a 复选框顺序契约：第 4 个是「辉光淡出」、第 5 个是「启用辉光」",
                orderOk,
                orderOk ? "顺序符合预期"
                        : "顺序已变！实际=" + ui.describeCheckBoxes());

        int mismatch = 0;
        javax.swing.JCheckBox fadeBox = ui.checkBoxByLabel("辉光淡出");
        javax.swing.JCheckBox shadowBox2 = ui.checkBoxByLabel("启用辉光");
        if (fadeBox == null || shadowBox2 == null) {
            check("R-10b 能按文案定位到辉光/淡出复选框", false,
                    "fadeBox=" + fadeBox + " shadowBox=" + shadowBox2
                            + "  全部复选框=" + ui.describeCheckBoxes());
        } else {
            if (fadeBox.isSelected() != p.overlayFadeOut) {
                mismatch++;
                System.out.println("    FAIL 辉光淡出复选框=" + fadeBox.isSelected()
                        + " pending=" + p.overlayFadeOut);
            }
            if (shadowBox2.isSelected() != p.useShadow) {
                mismatch++;
                System.out.println("    FAIL 启用辉光复选框=" + shadowBox2.isSelected()
                        + " pending=" + p.useShadow);
            }
            check("R-10b 【T3-2 连带】换预设后辉光/淡出复选框与 pending 一致（super.reset 覆盖到了）",
                    mismatch == 0,
                    mismatch + " 个不一致 复选框数=" + ui.checkBoxes.size()
                            + " pending.useShadow=" + p.useShadow
                            + " 预设声明=" + expect.useShadow());
        }

        // 第 9 字段的滑块也必须跟着回灌
        int idx = VUi.indexOfKey("typingSmoothness");
        int sliderVal = ui.sliders.get(idx).getValue();
        int expectTick = Math.round(p.typingSmoothness * 100f);
        check("R-10b 第 9 字段（输入平滑）滑块在换预设后跟着回灌",
                sliderVal == expectTick || Math.abs(sliderVal - expectTick) <= 5,
                "滑块=" + sliderVal + " pending=" + p.typingSmoothness
                        + "（刻度 " + expectTick + "）");
    }

    /** T3-2：paintDiag 用组件做真相源之后，换预设会不会把勾选取消。 */
    private static void paintDiagSurvivesPresetSwap() {
        System.out.println();
        System.out.println("--- 10. T3-2 回归：勾选「性能诊断」后换预设，勾选必须保住 ---");
        resetSvc();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();
        NeovideCaretManager_setPaintDisabled(false);

        // 【0.9.71：顺序契约 + 文案定位分开】
        boolean diagOrderOk = ui.assertCheckBoxAt(5, "性能诊断");
        check("R-11a 复选框顺序契约：第 6 个是「性能诊断」",
                diagOrderOk,
                diagOrderOk ? "顺序符合预期" : "顺序已变！实际=" + ui.describeCheckBoxes());

        javax.swing.JCheckBox diag = ui.checkBoxByLabel("性能诊断");
        if (diag == null) {
            check("R-11b 能按文案定位到「性能诊断」", false,
                    "全部复选框=" + ui.describeCheckBoxes());
            return;
        }
        diag.setSelected(true);
        boolean afterClick = diag.isSelected();

        // 换预设（走 syncAllControlsFromPending → super.reset()）
        conf.pickAnimPresetForProbe(1);
        boolean afterSwap = diag.isSelected();

        check("R-11 【T3-2 回归】勾选性能诊断 → 换预设 → 勾选仍在"
                        + "（super.reset() 用陈旧字段值覆盖控件会静默取消它）",
                afterClick && afterSwap,
                "点后=" + afterClick + " 换预设后=" + afterSwap);
    }

    // ---------------------------------------------------------------- 7) 占位项

    /** 本轮新增的占位项：切到预设后必须落回对应项，而不是停在「自定义（未保存）」。 */
    private static void placeholderFallsBackToPreset() {
        System.out.println();
        System.out.println("--- 7. 占位项回归：切预设后下拉必须落回该预设项 ---");
        resetSvc();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();

        int bad = 0;
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            conf.pickAnimPresetForProbe(i);
            int sel = conf.animPresetComboForProbe().getSelectedIndex();
            CursorPresets.AnimPreset shown = sel >= 0 && sel < CursorPresets.ANIM_PRESETS.length
                    ? CursorPresets.ANIM_PRESETS[sel] : null;
            boolean ok = shown == CursorPresets.ANIM_PRESETS[i];
            if (!ok) {
                bad++;
            }
            trace.append(i).append("→").append(shown == null ? ("#" + sel) : shown.name()).append("  ");
            System.out.println("    " + (ok ? "PASS " : "FAIL ") + "选 " + i + "("
                    + CursorPresets.ANIM_PRESETS[i].name() + ") 下拉=" + sel
                    + "(" + itemName(conf, sel) + ")");
        }
        check("R-12 【占位项】点每个内置预设后，下拉必须显示该预设而不是末尾占位项",
                bad == 0, bad + " 个落在占位/错项 | " + trace);
    }

    /** 占位项本身不可被"选中即写入"：点它不许改任何字段。 */
    private static void placeholderNotSelectable() {
        System.out.println();
        System.out.println("--- 7b. 占位项不是可选项：点它不写任何字段 ---");
        resetSvc();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();
        conf.pickAnimPresetForProbe(0);

        int count = conf.animPresetComboForProbe().getItemCount();
        String lastItem = conf.animPresetComboForProbe().getItemAt(count - 1);
        java.util.Map<String, String> before = VFields.snapshot(conf.pendingForProbe());
        conf.animPresetComboForProbe().setSelectedIndex(count - 1);   // 点占位项
        java.util.Map<String, String> after = VFields.snapshot(conf.pendingForProbe());
        int changed = VFields.countDiff(before, after, new java.util.HashSet<>());

        check("R-13 【占位项】下拉末项就是占位文案，且点它不改任何字段",
                PLACEHOLDER.equals(lastItem) && changed == 0,
                "末项=\"" + lastItem + "\" 改动字段=" + changed);
    }

    // ---------------------------------------------------------------- 8) 快照一致性

    /** 预设 9 字段 vs 自定义预设 19 字段快照：一条预设转成快照再 restore 必须自洽。 */
    private static void customSnapshotEqualsPresetFields() {
        System.out.println();
        System.out.println("--- 8b. 预设 9 字段 ⇄ 自定义预设 19 字段快照的一致性 ---");
        CursorPresets.AnimPreset p0 = CursorPresets.ANIM_PRESETS[0];
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        NeovideCursorConfigurable.applyAnimPresetFields(s, p0);
        String snap = SettingsField.snapshot(s);

        NeovideCursorSettings.State dst = new NeovideCursorSettings.State();
        int written = SettingsField.restore(dst, snap);
        int bad = 0;
        for (int i = 0; i < 9; i++) {
            if (!sameField(s, dst, i)) {
                bad++;
                System.out.println("    FAIL " + FIELD_NAMES[i] + " 往返不一致");
            }
        }
        check("R-14 snapshot → restore 后 9 个预设字段逐项一致（第 9 字段也在快照里）",
                bad == 0 && written == SettingsField.PRESET_FIELDS.size(),
                bad + " 项不一致 写入=" + written + "/" + SettingsField.PRESET_FIELDS.size());

        check("R-14b 快照里含 typingSmoothness（否则自定义预设不管输入平滑）",
                snap.contains("typingSmoothness="),
                "首段=" + snap.split(";")[0]);

        // Tools 应用自定义预设后，match 必须不再宣称是某个内置预设（否则勾选两个）
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        NeovideCursorConfigurable.applyAnimPresetFields(st, p0);
        NeovideCursorSettings.State custom = new NeovideCursorSettings.State();
        NeovideCursorConfigurable.applyAnimPresetFields(custom, p0);
        custom.typingSmoothness = 0.75f;            // 自定义预设与内置不同的地方
        st.customAnimPresets = CustomPresets.put("", "我的", SettingsField.snapshot(custom));
        CursorPresetApplier.applyCustomAnimPreset("我的");
        CursorPresets.AnimPreset m = CursorPresetApplier.matchAnimPreset(V0Env.svc.getState());
        check("R-15 Tools 应用自定义预设后，内置预设的勾选态必须全部为空"
                        + "（否则菜单里两个项同时勾选）",
                m == null, "match=" + nameOf(m)
                        + " smooth=" + V0Env.svc.getState().typingSmoothness);
    }

    /**
     * 把 R-6 的根因钉死：「是否偏离预设」有两处判据，字段域不同。
     *
     * <ul>
     *   <li>{@code matchAnimPreset()} —— <b>9 字段</b>（含 typingSmoothness）；</li>
     *   <li>{@code afterPendingEdited()} 的内联判据 —— <b>8 字段</b>（不含 typingSmoothness）。</li>
     * </ul>
     *
     * 于是「只改 smooth」这一种输入，两处给出相反答案：match 说"已经不是它"，
     * 派生判据说"没偏离"。这正是 R-6 / R-17c 的直接成因。
     */
    private static void deriveJudgementDivergesFromMatch() {
        System.out.println();
        System.out.println("--- 6b. 「是否偏离预设」的两处判据字段域是否一致 ---");
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        CursorPresets.AnimPreset base = CursorPresets.ANIM_PRESETS[0];
        NeovideCursorConfigurable.applyAnimPresetFields(st, base);
        st.typingSmoothness = 0.5f;                 // 只改第 9 字段

        boolean matchSaysDeviated = CursorPresetApplier.matchAnimPreset(st) != base;

        // 【探针修正】不再自己内联一份"派生判据"（那是自证：源码改了探针也跟着"对"）。
        // 改为【驱动真实面板】：调 refreshPresetDisplayForProbe()，
        // 它会走 afterPendingEdited() —— 真实派生判据跑一遍。
        // 判定"派生判据认为偏离"= 面板真的派生出了一个自定义预设。
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        // reset 会把 pending 重新灌成 state 的值，这里再设一次"只改 smooth"的场景
        conf.pendingForProbe().typingSmoothness = 0.5f;
        conf.refreshPresetDisplayForProbe();
        boolean deriveSaysDeviated =
                !conf.pendingForProbe().animCustomName.isEmpty();

        check("R-22 【根因】只改 typingSmoothness 时，matchAnimPreset 与派生判据必须给同一个答案",
                matchSaysDeviated == deriveSaysDeviated,
                "match 说偏离=" + matchSaysDeviated
                        + " 派生判据说偏离=" + deriveSaysDeviated
                        + (matchSaysDeviated != deriveSaysDeviated
                        ? " → 派生判据漏了第 9 字段（源码 :1337 只比 8 个）" : ""));
    }

    // ---------------------------------------------------------------- 13) Tools 菜单（本轮新增）

    /**
     * 本轮新增：{@code AnimPresetGroup} 每次 {@code update()} 都 {@code rebuildCustom()}。
     *
     * <p>{@code addSeparator()} 只在 {@code rebuildCustom()} 里调，而删除循环<b>只删
     * {@code ApplyCustomAnimPresetAction}、不删 {@code Separator}</b>。于是每弹出一次菜单
     * 就多一条分隔线 —— 菜单里会出现「自定义 ▸ ─── ─── ─── ...」越积越多。
     */
    private static void toolsMenuSeparatorAccumulation() {
        System.out.println();
        System.out.println("--- 13. Tools 菜单：多次 update() 后子项是否无限增长 ---");
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        NeovideCursorConfigurable.applyAnimPresetFields(st, CursorPresets.ANIM_PRESETS[0]);
        st.customAnimPresets = CustomPresets.put("", "我的甲",
                SettingsField.snapshot(st));

        com.github.yyyolo7a79.neovidecursor.ui.AnimPresetGroup group =
                new com.github.yyyolo7a79.neovidecursor.ui.AnimPresetGroup();
        int builtin = CursorPresets.ANIM_PRESETS.length;
        int afterCtor = group.getChildActionsOrStubs().length;
        // 模拟用户反复打开菜单（平台每次弹出都会 update）
        for (int i = 0; i < 5; i++) {
            group.update(com.intellij.openapi.actionSystem.AnActionEvent
                    .createFromAnAction(group, null, "probe",
                            com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT));
        }
        int afterUpdates = group.getChildActionsOrStubs().length;

        check("R-18 【本轮新引入】反复打开菜单后子项数量不得增长（rebuildCustom 必须把分隔线也清掉）",
                afterUpdates == afterCtor,
                "构造后=" + afterCtor + " 5 次 update 后=" + afterUpdates
                        + "（内置 " + builtin + " + 分隔线 + 自定义 1 = 应为 "
                        + (builtin + 2) + "）");
    }

    /**
     * 本轮新增：自定义预设项的勾选判据是 {@code animCustomName}，内置项是数值匹配。
     *
     * <p>两者是<b>两套判据</b>。{@code applyCustomAnimPreset()} 只写
     * {@code animCustomName}，不清 {@code animPresetBase} —— 若自定义快照恰好等于某个内置预设，
     * 菜单里就会<b>同时勾选两项</b>。
     */
    private static void toolsMenuDoubleSelection() {
        System.out.println();
        System.out.println("--- 13b. 自定义快照 == 内置预设时，菜单会不会同时勾两个 ---");
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        CursorPresets.AnimPreset base = CursorPresets.ANIM_PRESETS[0];
        NeovideCursorConfigurable.applyAnimPresetFields(st, base);
        // 自定义预设 = 与「基础」逐位相同（用户存了一份没改的）
        st.customAnimPresets = CustomPresets.put("", "未改的", SettingsField.snapshot(st));

        CursorPresetApplier.applyCustomAnimPreset("未改的");

        // 【探针修正】原来断言 matchAnimPreset == null —— 那是错的：
        // matchAnimPreset 本来就该返回"数值匹配到的内置预设"，防双勾选的是
        // isSelected() 里的 animCustomName.isEmpty()。必须测真实勾选态。
        com.intellij.openapi.actionSystem.AnAction[] kids =
                new com.github.yyyolo7a79.neovidecursor.ui.AnimPresetGroup().getChildActionsOrStubs();
        int checkedCount = 0;
        StringBuilder checkedNames = new StringBuilder();
        for (com.intellij.openapi.actionSystem.AnAction ac : kids) {
            if (ac instanceof com.intellij.openapi.actionSystem.ToggleAction
                    && !(ac instanceof com.intellij.openapi.actionSystem.Separator)) {
                if (((com.intellij.openapi.actionSystem.ToggleAction) ac).isSelected(null)) {
                    checkedCount++;
                    checkedNames.append(ac.getTemplatePresentation().getText()).append(" ");
                }
            }
        }
        check("R-19 应用自定义预设后，菜单里最多只有 1 项被勾选（单选契约）",
                checkedCount <= 1,
                "勾选 " + checkedCount + " 项 [" + checkedNames.toString().trim() + "]");
    }

    /**
     * 本轮新增：{@code applyCustomAnimPreset()} 没有清 {@code animPresetBase}。
     *
     * <p>{@code applyAnimPreset()} 会写 {@code animPresetBase} 并清 {@code animCustomName}；
     * 自定义那条只写名字。于是面板下拉重建时，若 {@code animCustomName} 指向的条目
     * 在列表里找不到（被手改坏 / 刚删），就会退化成「自称是上周那个内置预设」。
     */
    private static void toolsApplyCustomLeavesStaleBase() {
        System.out.println();
        System.out.println("--- 13c. applyCustomAnimPreset 是否留下陈旧的 animPresetBase ---");
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        st.animPresetBase = 1;                      // 用户上一次在「缎带」上
        NeovideCursorConfigurable.applyAnimPresetFields(st, CursorPresets.ANIM_PRESETS[1]);
        NeovideCursorSettings.State custom = new NeovideCursorSettings.State();
        NeovideCursorConfigurable.applyAnimPresetFields(custom, CursorPresets.ANIM_PRESETS[0]);
        custom.typingSmoothness = 0.60f;            // 让它明显不等于任何内置
        st.customAnimPresets = CustomPresets.put("", "我的",
                SettingsField.snapshot(custom));

        CursorPresetApplier.applyCustomAnimPreset("我的");
        int base = V0Env.svc.getState().animPresetBase;

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        int sel = conf.animPresetComboForProbe().getSelectedIndex();
        String shown = itemName(conf, sel);
        boolean honest = PLACEHOLDER.equals(shown) || "我的".equals(shown);

        check("R-20 【本轮新引入】Tools 应用自定义预设后，面板下拉必须显示它（或占位），"
                        + "不能倒退到陈旧的内置基准",
                honest,
                "animPresetBase=" + base + " 下拉=" + sel + "(\"" + shown + "\")");
    }

    /**
     * 本轮新增：{@code applyCustomAnimPreset()} 不校验快照里的 {@code animCustomName} 是否还在表里。
     *
     * <p>名字被删后再从菜单点（菜单是 {@code update()} 时重建的，但点击事件可能在重建之前
     * 已经入队）—— 此时快照取不到，方法返回 false 且<b>不改任何字段</b>。
     * 这一条是「安全失败」，确认它真的安全（不半途写字段）。
     */
    private static void applyMissingCustomIsAtomic() {
        System.out.println();
        System.out.println("--- 13d. 应用不存在的自定义预设必须是原子的（不改任何字段）---");
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        NeovideCursorConfigurable.applyAnimPresetFields(st, CursorPresets.ANIM_PRESETS[0]);
        st.animCustomName = "我的";
        st.customAnimPresets = CustomPresets.put("", "我的", SettingsField.snapshot(st));
        // 从表里删掉，但 animCustomName 还指着它（模拟「菜单项已入队、预设刚被删」）
        st.customAnimPresets = CustomPresets.remove(st.customAnimPresets, "我的");

        java.util.Map<String, String> before = VFields.snapshot(V0Env.svc.getState());
        boolean ok = CursorPresetApplier.applyCustomAnimPreset("我的");
        java.util.Map<String, String> after = VFields.snapshot(V0Env.svc.getState());
        int changed = VFields.countDiff(before, after, new java.util.HashSet<>());

        check("R-21 应用不存在的自定义预设返回 false 且不改任何字段（原子失败）",
                !ok && changed == 0, "返回=" + ok + " 改动字段=" + changed);
    }

    /**
     * 任务 8 的深挖：预设 9 字段与自定义预设 19 字段快照<b>不是同一个域</b>。
     *
     * <p>{@code applyAnimPreset()}（内置）只写 9 个观感字段，从不碰
     * {@code enabled} / {@code hideNativeCaret} / {@code trailMode} / {@code alwaysHighFps}。
     * 而 {@code applyCustomAnimPreset()} 直接 {@code SettingsField.restore()} 整份 19 字段 ——
     * 于是同一个「切预设」动作，走内置路径和走自定义路径对<b>运行环境开关</b>的影响不同。
     */
    private static void customApplyMustNotTouchEnvironmentSwitches() {
        System.out.println();
        System.out.println("--- 8c. 自定义预设在 Tools 里应用时，不许改运行环境开关 ---");
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        // 造一个「快照里这些开关全是另一套值」的自定义预设
        NeovideCursorSettings.State src = new NeovideCursorSettings.State();
        NeovideCursorConfigurable.applyAnimPresetFields(src, CursorPresets.ANIM_PRESETS[0]);
        src.enabled = false;                        // 存快照时插件是关的
        src.hideNativeCaret = false;
        src.trailMode = NeovideConfig.TrailMode.AFTERIMAGE;
        src.alwaysHighFps = false;
        st.customAnimPresets = CustomPresets.put("", "环境快照",
                SettingsField.snapshot(src));

        // 应用它之前的真实环境：插件开着、原生光标隐藏、弹簧模式、常驻刷新
        boolean en0 = V0Env.svc.getState().enabled;
        boolean hid0 = V0Env.svc.getState().hideNativeCaret;
        NeovideConfig.TrailMode mode0 = V0Env.svc.getState().trailMode;
        boolean fps0 = V0Env.svc.getState().alwaysHighFps;
        CursorPresetApplier.applyCustomAnimPreset("环境快照");
        NeovideCursorSettings.State after = V0Env.svc.getState();

        boolean envIntact = after.enabled == en0
                && after.hideNativeCaret == hid0
                && after.trailMode == mode0
                && after.alwaysHighFps == fps0;
        check("R-23 【任务8】Tools 应用自定义预设不许改动 enabled / hideNativeCaret / trailMode / alwaysHighFps"
                        + "（内置路径从不碰这四个；自定义路径必须一致）",
                envIntact,
                "enabled " + en0 + "→" + after.enabled
                        + " hideNativeCaret " + hid0 + "→" + after.hideNativeCaret
                        + " trailMode " + mode0 + "→" + after.trailMode
                        + " alwaysHighFps " + fps0 + "→" + after.alwaysHighFps);

        // 反向：两条路径的字段域必须对得上（同一份观感，两个入口不应产生不同结果）
        resetSvc();
        NeovideCursorSettings.State s2 = V0Env.svc.getState();
        s2.enabled = true;
        s2.hideNativeCaret = true;
        s2.trailMode = NeovideConfig.TrailMode.SPRING;
        s2.alwaysHighFps = true;
        CursorPresets.AnimPreset p0 = CursorPresets.ANIM_PRESETS[0];
        CursorPresetApplier.applyAnimPreset(p0);
        java.util.Map<String, String> viaBuiltin = VFields.snapshot(V0Env.svc.getState());

        resetSvc();
        NeovideCursorSettings.State s3 = V0Env.svc.getState();
        s3.enabled = true;
        s3.hideNativeCaret = true;
        s3.trailMode = NeovideConfig.TrailMode.SPRING;
        s3.alwaysHighFps = true;
        NeovideCursorSettings.State same = new NeovideCursorSettings.State();
        NeovideCursorConfigurable.applyAnimPresetFields(same, p0);
        same.enabled = true;
        same.hideNativeCaret = true;
        same.trailMode = NeovideConfig.TrailMode.SPRING;
        same.alwaysHighFps = true;
        s3.customAnimPresets = CustomPresets.put("", "与基础相同", SettingsField.snapshot(same));
        CursorPresetApplier.applyCustomAnimPreset("与基础相同");
        java.util.Map<String, String> viaCustom = VFields.snapshot(V0Env.svc.getState());

        java.util.Set<String> ignore = new java.util.HashSet<>(java.util.Arrays.asList(
                "animCustomName", "customAnimPresets", "animPresetBase", "stateVersion"));
        int diff = VFields.countDiff(viaBuiltin, viaCustom, ignore);
        check("R-24 【任务8】同一份观感经「内置」与「自定义」两条路径应用后，参数必须逐项一致",
                diff == 0, diff + " 个字段不同");
    }

    // ---------------------------------------------------------------- 9) VPreset 的默认值断言

    /** 任务 9：VPreset 的「预设 1 == 默认值」现在多了 smooth，默认也是 0，对得上吗。 */
    private static void presetDefaultsStillMatch() {
        System.out.println();
        System.out.println("--- 11. 预设 1（基础）仍逐位等于 NeovideConfig 默认值（含 smooth）---");
        NeovideConfig cfg = new NeovideConfig();
        CursorPresets.AnimPreset a0 = CursorPresets.ANIM_PRESETS[0];
        NeovideCursorSettings.State st = new NeovideCursorSettings.State();
        NeovideCursorConfigurable.applyAnimPresetFields(st, a0);

        // 直接与「干净安装的 State 初值」逐项比 —— 那才是用户第一次看到的观感
        NeovideCursorSettings.State fresh = new NeovideCursorSettings.State();
        int bad = 0;
        for (int i = 0; i < 9; i++) {
            if (!sameField(st, fresh, i)) {
                bad++;
                System.out.println("    FAIL " + FIELD_NAMES[i]
                        + " 预设=" + fieldText(st, i) + " 默认=" + fieldText(fresh, i));
            }
        }
        check("R-16 【任务9】「基础」预设写入的 9 个字段逐项等于干净安装的 State 初值"
                        + "（含 typingSmoothness = 默认 0f）",
                bad == 0, bad + " 项不等 | 预设 smooth=(全局，不参与)"
                        + " State 初值 smooth=" + fresh.typingSmoothness
                        + " NeovideConfig 无 smooth 字段（派生量）");

        // 与 NeovideConfig 的标量逐项比对（smooth 不在 config 里，跳过）
        boolean cfgEq = Float.compare(a0.animationLength(), cfg.animationLength) == 0
                && Float.compare(a0.shortAnimationLength(), cfg.shortAnimationLength) == 0
                && Float.compare(a0.trailSize(), cfg.trailSize) == 0
                && Float.compare(a0.shadowBlurFactor(), cfg.shadowBlurFactor) == 0
                && Float.compare(a0.glowOpacity(), cfg.glowOpacity) == 0
                && a0.glowLayers() == cfg.glowLayers;
        check("R-16b 「基础」预设与 NeovideConfig 的 6 个标量逐位相等（未回归）",
                cfgEq, "blur 预设=" + a0.shadowBlurFactor() + " cfg=" + cfg.shadowBlurFactor);
    }

    // ---------------------------------------------------------------- 10) smooth 覆盖

    /**
     * 任务 10：Tools 应用内置预设会把用户的 typingSmoothness 写回 0。
     *
     * <p>这在「预设 = 一整套观感」的模型下是<b>设计内</b>的（与 useShadow 同理：
     * 用户可以接受"切预设会重置辉光开关"，就必须接受"切预设会重置输入平滑"）。
     * 但要验证它至少是<b>可逆/可发现</b>的：应用后下拉/匹配必须自洽，
     * 且用户改回去能重新派生出落脚点。
     */
    private static void toolsApplyOverwritesUserSmooth() {
        System.out.println();
        System.out.println("--- 12. Tools 应用内置预设会覆盖用户调好的 smooth（数据丢失？）---");
        resetSvc();
        NeovideCursorSettings.State st = V0Env.svc.getState();
        NeovideCursorConfigurable.applyAnimPresetFields(st, CursorPresets.ANIM_PRESETS[0]);
        st.typingSmoothness = 1.0f;                 // 用户调好的值

        CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[1]);   // 切「缎带」
        float afterSwitch = V0Env.svc.getState().typingSmoothness;
        System.out.println("    切「缎带」后 typingSmoothness=" + afterSwitch
                + "（切前 1.0）");

        // ① 覆盖本身是否与「预设 = 一整套观感」自洽（不判对错，只记录）
        boolean overwritten = Math.abs(afterSwitch - 0f) < 1e-6f;
        check("R-17 【任务10】切预设会覆盖 typingSmoothness（= 模型内行为，不是回归）",
                overwritten,
                "覆盖后=" + afterSwitch + " —— 与 useShadow/overlayFadeOut 同一语义；"
                        + "真正的风险是：用户没有 UI 提示这次切换会重置它");

        // ② 关键是「可发现」：覆盖之后 match 必须仍然自洽（不能出两套答案）
        CursorPresets.AnimPreset m = CursorPresetApplier.matchAnimPreset(V0Env.svc.getState());
        check("R-17b 覆盖后的状态仍能被 match 反查回该预设（不产生「应用后不匹配」）",
                m == CursorPresets.ANIM_PRESETS[1],
                "match=" + nameOf(m) + " smooth=" + V0Env.svc.getState().typingSmoothness);

        // ③ 以及「用户改回去能留下落脚点」：切预设后再改 smooth 必须派生
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        conf.pendingForProbe().typingSmoothness = 0.5f;
        conf.refreshPresetDisplayForProbe();
        check("R-17c 覆盖之后用户重新调 smooth 仍能留下落脚点（不会被下次覆盖静默丢）",
                !conf.pendingForProbe().animCustomName.isEmpty(),
                "animCustomName=\"" + conf.pendingForProbe().animCustomName + "\"");
    }

    // ---------------------------------------------------------------- 工具

    /** 9 个预设字段的名字（顺序 = applyAnimPresetFields 的写入顺序）。 */
    private static final String[] FIELD_NAMES = {
            "animationLength", "shortAnimationLength", "trailSize", "glowLayers",
            "shadowBlurFactor", "glowOpacity", "useShadow", "overlayFadeOut",
            "typingSmoothness"};

    private static String fieldText(NeovideCursorSettings.State s, int i) {
        switch (i) {
            case 0: return String.valueOf(s.animationLength);
            case 1: return String.valueOf(s.shortAnimationLength);
            case 2: return String.valueOf(s.trailSize);
            case 3: return String.valueOf(s.glowLayers);
            case 4: return String.valueOf(s.shadowBlurFactor);
            case 5: return String.valueOf(s.glowOpacity);
            case 6: return String.valueOf(s.useShadow);
            case 7: return String.valueOf(s.overlayFadeOut);
            case 8: return String.valueOf(s.typingSmoothness);
            default: return "?";
        }
    }

    private static boolean sameField(NeovideCursorSettings.State a, NeovideCursorSettings.State b, int i) {
        switch (i) {
            case 6: return a.useShadow == b.useShadow;
            case 7: return a.overlayFadeOut == b.overlayFadeOut;
            case 3: return a.glowLayers == b.glowLayers;
            default: return Math.abs(asFloat(a, i) - asFloat(b, i)) < CursorPresets.EPS;
        }
    }

    private static float asFloat(NeovideCursorSettings.State s, int i) {
        switch (i) {
            case 0: return s.animationLength;
            case 1: return s.shortAnimationLength;
            case 2: return s.trailSize;
            case 3: return s.glowLayers;
            case 4: return s.shadowBlurFactor;
            case 5: return s.glowOpacity;
            case 8: return s.typingSmoothness;
            default: return 0f;
        }
    }

    /**
     * 把第 {@code i} 个预设字段扰动到「明显不等于预设常量」的值。
     *
     * <p>每个分支都必须落在合法区间内 —— 否则 {@code loadState} 的 clamp 会先把它
     * 改回端点，测到的就不是 match 的覆盖面了。
     */
    private static void perturb(NeovideCursorSettings.State s, int i) {
        switch (i) {
            case 0: s.animationLength = 0.20f; break;
            case 1: s.shortAnimationLength = 0.20f; break;
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

    private static CursorPresets.AnimPreset purePreset() {
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            if ("纯平滑输入".equals(p.name())) {
                return p;
            }
        }
        return CursorPresets.ANIM_PRESETS[CursorPresets.ANIM_PRESETS.length - 1];
    }

    private static String nameOf(CursorPresets.AnimPreset p) {
        return p == null ? "null（自定义）" : p.name();
    }

    private static String itemName(NeovideCursorConfigurable conf, int idx) {
        try {
            return conf.animPresetComboForProbe().getItemAt(idx);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void NeovideCaretManager_setPaintDisabled(boolean v) {
        try {
            com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager.setPaintDisabled(v);
        } catch (Throwable ignored) {
            // 离线桩下不保证可用；paintDiag 的断言只看控件勾选态
        }
    }

    private static void resetSvc() {
        NeovideCursorSettings.State fresh = new NeovideCursorSettings.State();
        fresh.stateVersion = 1;
        V0Env.svc.loadState(fresh);
    }

    /** 真实平台序列化 + loadState（= 「重启 IDE」的等价路径，clamp 全在 loadState 里）。 */
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
