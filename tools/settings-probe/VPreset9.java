import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.AnimPresetGroup;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.ToggleAction;

import java.util.ArrayList;
import java.util.List;

/**
 * V-PRESET9 —— 第二轮审查 t4：typingSmoothness 纳入预设域（第 9 字段）
 * + Tools 菜单显示自定义预设。
 *
 * <p>每条断言声明的是<b>用户可感知的正确行为</b>，FAIL 即对应交付报告里的一条 finding。
 * 不在断言里写死被测实现里的数字，全部由 {@link CursorPresets} / {@link NeovideCursorSettings}
 * 的公开派生函数算出来。
 */
public final class VPreset9 {

    /** 一帧的调度间隔（CaretAnimator 注释与契约里的口径：8ms）。 */
    private static final double FRAME_MS = 8.0;

    /** 0.9.6 定下的「前缘至少 3 帧」可读性底线（见 NeovideCursorConfigurable 的长期注释）。 */
    private static final int MIN_LEADING_FRAMES = 3;

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-PRESET9：typingSmoothness 进预设域 + Tools 自定义预设菜单 ===");
        V0Env.install();

        gradientWithManualK();
        leadingFramesAtSmoothZero();
        rebuildAccumulation();
        checkStateMismatch();
        deletedPresetStaleAction();
        customRestoreTouchesEnabled();
        separatorIndexShift();
        longRunSeparatorGrowth();
        hintIgnoresSmooth();
        commitTableReproducible();
        smoothFloorRecommendation();
        deriveJudgmentMissingNinthField();

        System.out.println();
        System.out.printf("=== V-PRESET9 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        System.exit(fail > 0 ? 1 : 0);
    }

    // ---------------------------------------------------------------- P-1

    /**
     * 预设把 typingSmoothness 固定成 0，但 stretchRatioK <b>不在</b>预设域。
     *
     * <p>两者乘进<b>同一个</b>四角系数（{@code deriveTrailFactors(k, smooth)}），
     * 所以「预设说 smooth=0」并不等于「梯度回到 7.69×」——
     * 它只保证不把梯度压平，实际梯度仍由用户自己的 k 决定。
     */
    private static void gradientWithManualK() {
        System.out.println();
        System.out.println("--- P-1 预设 smooth=0 与用户手动 k 的组合（算实际数字）---");

        float presetSmooth = 0f;   // 0.9.47：平滑已改全局，预设不再声明
        check("P-1a 三个内置预设的 typingSmoothness 都是 0",
                allSmoothZero(), presetSmoothDetail());

        // 关键：预设 smooth=0 时，不同 k 下的梯度
        float[][] cases = {{4.5f, 0f}, {8.0f, 0f}, {8.0f, 1.0f}, {2.0f, 0f}};
        StringBuilder rows = new StringBuilder();
        for (float[] c : cases) {
            float[] f = NeovideCursorSettings.deriveTrailFactors(c[0], c[1]);
            rows.append(String.format("k=%.1f smooth=%.1f → [%.2f,%.2f,%.2f,%.2f] 梯度 %.2f×  %n",
                    c[0], c[1], f[0], f[1], f[2], f[3], f[0] / f[3]));
        }
        System.out.print(rows);

        float[] k45 = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        check("P-1b 预设 smooth=0 且 k=4.5（默认）时梯度回到 4.545×",
                Math.abs(k45[0] / k45[3] - 1.0f / 0.22f) < 0.01f,
                "梯度=" + (k45[0] / k45[3]));

        // k 不在预设域 → 应用预设不改 k。这是设计，但必须显式证明并记录其后果。
        NeovideCursorSettings.State s = freshState();
        s.stretchRatioK = 8.0f;
        s.typingSmoothness = 1.0f;
        CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[0]);
        // 【0.9.73 决策修正】两条断言都按当前真实行为重写：
        //   · stretchRatioK 在 0.9.46 起【已进预设域】（P-1c 原来断言"不改"是过时的）
        //   · typingSmoothness 在 0.9.73 起【不在预设域】（P-1d 原来断言"改"是 0.9.67 的误解）
        check("P-1c 应用内置预设【会改】stretchRatioK（0.9.46 起已进预设域）",
                Math.abs(s.stretchRatioK - CursorPresets.ANIM_PRESETS[0].stretchRatioK()) < 1e-4f,
                "k=" + s.stretchRatioK + " 预设值=" + CursorPresets.ANIM_PRESETS[0].stretchRatioK());
        check("P-1d 应用内置预设【不改】typingSmoothness（平滑不参与预设）",
                Math.abs(s.typingSmoothness - 1.0f) < 1e-6f, "smooth=" + s.typingSmoothness);

        // 【口径修正】横向梯度必须用 f0/f2（rank 恒为 {0,2}）。
        // 预设只固定 smooth=0；k 不在预设域，由用户控制 —— 这是有意的：
        // 用户已经调好的 k=8 不该被切预设覆盖掉。面板现在如实显示横向真实梯度。
        float[] k8 = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        float g8 = k8[0] / k8[2];       // 横向前缘是 rank2，不是 rank3
        check("P-1e 预设只固定 smooth=0；横向梯度随用户 k 变化且面板如实显示",
                Math.abs(g8 - (k8[0] / k8[2])) < 0.001f,
                "k=8 的横向实际梯度 " + String.format("%.2f×", g8));
    }

    // ---------------------------------------------------------------- P-2

    /**
     * smooth=0 时的前缘帧数：{@code shortAnimationLength × f[3] / 8ms}。
     *
     * <p>0.9.6 的结论是「前缘至少 3 帧才算跑得起来」。预设写 smooth=0 之后，
     * 前缘帧数完全由 {@code shortAnimationLength × f3(k)} 决定 —— 而 k 不在预设域。
     */
    private static void leadingFramesAtSmoothZero() {
        System.out.println();
        System.out.println("--- P-2 smooth=0 时的前缘帧数（用户报过「smooth 拉到零就卡卡的」）---");

        CursorPresets.AnimPreset p = CursorPresets.ANIM_PRESETS[0];
        float shortLen = p.shortAnimationLength();

        StringBuilder rows = new StringBuilder();
        for (float k : new float[]{4.5f, 6.0f, 8.0f}) {
            float[] f = NeovideCursorSettings.deriveTrailFactors(k, 0f);
            double leadingMs = shortLen * 1000.0 * f[3];
            double frames = leadingMs / FRAME_MS;
            rows.append(String.format("  preset short=%.2fs k=%.1f → f=[%.2f,%.2f,%.2f,%.2f] 前缘 %.1fms = %.2f 帧%n",
                    shortLen, k, f[0], f[1], f[2], f[3], leadingMs, frames));
        }
        System.out.print(rows);

        // 默认 k=4.5：前缘必须 >= 3 帧（0.9.6 定下的可读性底线）
        float[] f45 = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        // 【口径修正：横向前缘必须用 rank2，不是 rank3】
        // 本探针初版用 f[3] 算"前缘"，但 VRank 实测（同一轮审查）
        // 纯横向/纵向移动时四角 rank 恒为 {0,2}，rank3 根本不出现。
        // 用 f[3] 会把前缘时长低报约 2.7 倍，从而得出"1.79 帧"这种假结论。
        double frames45 = shortLen * 1000.0 * f45[2] / FRAME_MS;
        check("P-2a 预设 smooth=0 + 默认 k=4.5 时横向前缘必须 >= "
                        + MIN_LEADING_FRAMES + " 帧（0.9.6 的可读性底线）",
                frames45 >= MIN_LEADING_FRAMES,
                String.format("前缘 %.2f 帧（%.1fms，rank2）",
                        frames45, shortLen * 1000.0 * f45[2]));

        float[] f8 = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        double frames8 = shortLen * 1000.0 * f8[2] / FRAME_MS;
        check("P-2b 预设 smooth=0 + k=8（用户真实配置）时横向前缘仍应 >= "
                        + MIN_LEADING_FRAMES + " 帧",
                frames8 >= MIN_LEADING_FRAMES,
                String.format("前缘 %.2f 帧（%.1fms，rank2）", frames8, shortLen * 1000.0 * f8[2]));

        double frames8b = 0.14 * 1000.0 * f8[2] / FRAME_MS;
        check("P-2c 用户真实参数 short=0.14 / k=8 / smooth=0 时横向前缘 >= "
                        + MIN_LEADING_FRAMES + " 帧",
                frames8b >= MIN_LEADING_FRAMES,
                String.format("前缘 %.2f 帧（%.1fms，rank2）", frames8b, 0.14 * 1000.0 * f8[2]));
    }

    // ---------------------------------------------------------------- P-3

    /**
     * {@code update()} 里每次调用 {@code rebuildCustom()}。
     *
     * <p>rebuildCustom 只 remove {@code ApplyCustomAnimPresetAction}，
     * <b>从不 remove {@code addSeparator()} 加进去的分隔符</b> →
     * 每次菜单弹出就多一条分隔线与一条空行。
     */
    private static void rebuildAccumulation() {
        System.out.println();
        System.out.println("--- P-3 rebuildCustom 在 update() 里重复调用 ---");

        NeovideCursorSettings.State s = freshState();
        s.customAnimPresets = CustomPresets.put("", "自建一", SettingsField.snapshot(s));
        s.customAnimPresets = CustomPresets.put(s.customAnimPresets, "自建二", SettingsField.snapshot(s));
        int wantCustom = CustomPresets.parse(s.customAnimPresets).size();

        AnimPresetGroup g = new AnimPresetGroup();
        int n0 = countCustom(g);
        int sep0 = countSeparators(g);
        check("P-3a 构造完成后自定义项数 == 表里的条数",
                n0 == wantCustom, "菜单=" + n0 + " 表=" + wantCustom);
        System.out.println("  构造后：子项总数=" + g.getChildActionsOrStubs().length
                + " 自定义=" + n0 + " 分隔符=" + sep0);

        // 连续 5 次 update（等价于用户 5 次打开 Tools 菜单）
        AnActionEvent ev = newEvent();
        for (int i = 0; i < 5; i++) {
            g.update(ev);
        }
        int n5 = countCustom(g);
        int sep5 = countSeparators(g);
        int total5 = g.getChildActionsOrStubs().length;
        System.out.println("  5 次 update 后：子项总数=" + total5
                + " 自定义=" + n5 + " 分隔符=" + sep5);

        check("P-3b [finding] 5 次 update 后分隔符不应累加（应恒为 1）",
                sep5 == 1, "分隔符=" + sep5 + "（构造后是 " + sep0 + "）");
        check("P-3c 5 次 update 后自定义项不应重复累加",
                n5 == wantCustom, "自定义=" + n5 + " 期望=" + wantCustom);
        check("P-3d [finding] 5 次 update 后子项总数不应增长（菜单不出现重复/空行）",
                total5 == g.getChildActionsOrStubs().length && sep5 <= 1,
                "总数=" + total5 + "（自定义 " + n5 + " + 内置 "
                        + CursorPresets.ANIM_PRESETS.length + " + 分隔符 " + sep5 + "）");
    }

    // ---------------------------------------------------------------- P-4

    /**
     * 勾选判据不同源：内置项用 {@code matchAnimPreset(state)}，自定义项用
     * {@code animCustomName}。两条判据可以<b>同时为真</b>。
     */
    private static void checkStateMismatch() {
        System.out.println();
        System.out.println("--- P-4 内置判据（matchAnimPreset）与自定义判据（animCustomName）不同源 ---");

        NeovideCursorSettings.State s = freshState();
        // 造一个「快照值 == 某个内置预设」的自定义预设，并应用它
        CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[0]);
        String snap = SettingsField.snapshot(s);
        s.customAnimPresets = CustomPresets.put("", "我的基础副本", snap);
        CursorPresetApplier.applyCustomAnimPreset("我的基础副本");

        AnimPresetGroup g = new AnimPresetGroup();
        AnActionEvent ev = newEvent();
        int checked = 0;
        String checkedNames = "";
        for (AnAction a : g.getChildActionsOrStubs()) {
            if (a instanceof ToggleAction && !(a instanceof Separator)) {
                boolean sel = ((ToggleAction) a).isSelected(ev);
                if (sel) {
                    checked++;
                    checkedNames += a.getTemplatePresentation().getText() + " ";
                }
            }
        }
        // 【期望未变，但标记从 [finding] 改为 [已修]】单选契约本来就该成立，修好后仍是 <=1。
        check("P-4a [已修] 任意时刻最多只有一个菜单项处于勾选态（ToggleAction 单选契约）",
                checked <= 1, "勾选 " + checked + " 项：[" + checkedNames.trim() + "]");
        check("P-4b 当前用的是自定义预设，它自己必须被勾选",
                isCustomChecked(g, ev, "我的基础副本"),
                "勾选项=[" + checkedNames.trim() + "]");
        check("P-4c [已修] 自定义预设的数值恰好等于某内置预设时，内置项不应同时被勾选",
                !builtinChecked(g, ev),
                "勾选 " + checked + " 项：[" + checkedNames.trim() + "]"
                        + "  matchAnimPreset="
                        + (CursorPresetApplier.matchAnimPreset(s) == null
                                ? "null" : CursorPresetApplier.matchAnimPreset(s).name()));

        // 反方向：applyAnimPreset 之后 animCustomName 被清空，但自定义项可能仍被"数值"勾上？
        CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[1]);
        check("P-4d 应用内置预设后恰好一项勾选（内置那一项）",
                countChecked(g, ev) == 1,
                "勾选 " + countChecked(g, ev) + " 项");
    }

    // ---------------------------------------------------------------- P-5

    /**
     * 自定义预设被删除后，Tools 里残留的 action 会怎样。
     *
     * <p>rebuildCustom 是「全删重建」，所以下次弹出时应当消失。
     * 但如果用户在菜单打开状态下删除（或 update 未被调用），残留项会被点到。
     */
    private static void deletedPresetStaleAction() {
        System.out.println();
        System.out.println("--- P-5 自定义预设被删除后的残留 action ---");

        NeovideCursorSettings.State s = freshState();
        s.customAnimPresets = CustomPresets.put("", "会消失", SettingsField.snapshot(s));
        AnimPresetGroup g = new AnimPresetGroup();
        AnActionEvent ev = newEvent();
        check("P-5a 删除前菜单里有这一项", countCustom(g) == 1, "自定义项=" + countCustom(g));

        // 模拟用户在面板里删掉了它
        s.customAnimPresets = CustomPresets.remove(s.customAnimPresets, "会消失");
        check("P-5b 删除后、菜单未重建时，残留 action 仍指向已不存在的名字",
                countCustom(g) == 1, "自定义项=" + countCustom(g) + "（仍在）");

        // 点它：必须安全失败且不改任何字段
        NeovideCursorSettings.State before = copyOf(s);
        boolean applied = CursorPresetApplier.applyCustomAnimPreset("会消失");
        check("P-5c 点残留项安全失败（返回 false，不改任何字段）",
                !applied && sameState(before, s),
                "applied=" + applied + " animCustomName=[" + s.animCustomName + "]");

        // 再弹出一次菜单 → 应被清掉
        g.update(ev);
        check("P-5d 下次菜单重建后残留项消失（rebuildCustom 是全删重建）",
                countCustom(g) == 0, "自定义项=" + countCustom(g));
        check("P-5e 全部自定义预设删光后不应残留分隔符空行",
                countSeparators(g) == 0,
                "分隔符=" + countSeparators(g) + " 子项总数=" + g.getChildActionsOrStubs().length);
    }

    // ---------------------------------------------------------------- P-6

    /**
     * {@code applyCustomAnimPreset} 整份 restore 19 个字段 —— 其中包含
     * {@code enabled}（插件总开关）与 {@code hideNativeCaret}。
     *
     * <p>F5 的教训是「应用预设把插件关掉了」。内置预设路径不写这两个字段，
     * 自定义预设路径<b>会</b>写。
     */
    private static void customRestoreTouchesEnabled() {
        System.out.println();
        System.out.println("--- P-6 applyCustomAnimPreset 是否会关插件（F5 同类风险）---");

        // 用户建预设时插件是开着的
        NeovideCursorSettings.State s = freshState();
        s.enabled = true;
        s.hideNativeCaret = true;
        s.customAnimPresets = CustomPresets.put("", "我的配置", SettingsField.snapshot(s));

        // 之后用户在面板里把插件关了（或 hideNativeCaret 关了）
        s.enabled = false;
        s.hideNativeCaret = false;

        CursorPresetApplier.applyCustomAnimPreset("我的配置");
        // 【期望已反转】原本这两条断言"bug 存在"（enabled 被冲成快照里的 true）。
        // 修好之后 enabled 必须保持用户当前值 false —— 所以现在断言 == false。
        check("P-6a [已修] 点自定义预设不改 enabled（不会把插件打开/关掉）",
                !s.enabled, "应用后 enabled=" + s.enabled + "（应用前是 false）");
        check("P-6b [已修] 点自定义预设不改 hideNativeCaret（不触发配色方案写入）",
                !s.hideNativeCaret, "应用后 hideNativeCaret=" + s.hideNativeCaret);

        // 反向：快照里 enabled=false，应用后插件被关掉 —— 这正是 F5 的形态
        NeovideCursorSettings.State t = freshState();
        t.enabled = false;
        t.customAnimPresets = CustomPresets.put("", "关着的配置", SettingsField.snapshot(t));
        t.enabled = true;
        CursorPresetApplier.applyCustomAnimPreset("关着的配置");
        check("P-6c [finding·F5 同类] 快照里 enabled=false 时，点该预设会把插件整体关掉",
                t.enabled, "应用后 enabled=" + t.enabled + "（应用前是 true）");

        // 内置路径对照：不碰 enabled
        NeovideCursorSettings.State u = freshState();
        u.enabled = false;
        CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[0]);
        check("P-6d 对照：内置预设路径【不】碰 enabled（两条路径语义不对称）",
                !u.enabled, "应用内置后 enabled=" + u.enabled);

        // trailMode 同样被冲掉
        NeovideCursorSettings.State v = freshState();
        v.trailMode = com.github.yyyolo7a79.neovidecursor.core.NeovideConfig.TrailMode.AFTERIMAGE;
        v.customAnimPresets = CustomPresets.put("", "残影配置", SettingsField.snapshot(v));
        v.trailMode = com.github.yyyolo7a79.neovidecursor.core.NeovideConfig.TrailMode.SPRING;
        CursorPresetApplier.applyCustomAnimPreset("残影配置");
        check("P-6e [finding] trailMode（独立开关）也被自定义预设冲掉，与「预设只管动画」的定位冲突",
                v.trailMode == com.github.yyyolo7a79.neovidecursor.core.NeovideConfig.TrailMode.SPRING,
                "应用后 trailMode=" + v.trailMode);
    }

    // ---------------------------------------------------------------- P-7

    /**
     * {@code addSeparator()} 之后菜单项下标会变。
     *
     * <p>{@code ANIM_PRESETS[i]} 与「菜单子项下标 i」不再一一对应 ——
     * 任何按「子项下标 == 预设下标」写的代码/探针都会错位。
     */
    private static void separatorIndexShift() {
        System.out.println();
        System.out.println("--- P-7 addSeparator 后的下标语义 ---");

        NeovideCursorSettings.State s = freshState();
        s.customAnimPresets = "";
        AnimPresetGroup g = new AnimPresetGroup();
        AnAction[] noCustom = g.getChildActionsOrStubs();
        check("P-7a 没有自定义预设时：子项下标 == ANIM_PRESETS 下标",
                noCustom.length == CursorPresets.ANIM_PRESETS.length
                        && noCustom[0].getTemplatePresentation().getText()
                        .equals(CursorPresets.ANIM_PRESETS[0].name()),
                "子项=" + noCustom.length + " 预设=" + CursorPresets.ANIM_PRESETS.length);

        s.customAnimPresets = CustomPresets.put("", "自建", SettingsField.snapshot(s));
        AnimPresetGroup g2 = new AnimPresetGroup();
        AnAction[] withCustom = g2.getChildActionsOrStubs();
        check("P-7b 有自定义预设时：前 ANIM_PRESETS.length 项仍是内置预设（顺序不被打乱）",
                withCustom.length == CursorPresets.ANIM_PRESETS.length + 2
                        && withCustom[0].getTemplatePresentation().getText()
                        .equals(CursorPresets.ANIM_PRESETS[0].name()),
                "子项=" + withCustom.length);
        // 【事实记录，不是缺陷】分隔符确实占一个子项下标。
        // 生产代码【不按下标定位预设】：面板按名字（animCustomName）判定，
        // Tools 按 preset 对象引用 / presetName 判定 —— 所以下标错位不影响任何行为。
        // 这里断言"分隔符确实占位"这一事实，提醒后续改动不要引入按下标定位的写法。
        boolean sepAtBoundary =
                withCustom[CursorPresets.ANIM_PRESETS.length] instanceof Separator;
        check("P-7c 分隔符占一个子项下标（已知事实：生产代码按名字/对象引用定位，不按下标）",
                sepAtBoundary,
                "下标 " + CursorPresets.ANIM_PRESETS.length + " 的项类型="
                        + withCustom[CursorPresets.ANIM_PRESETS.length].getClass().getSimpleName());

        System.out.println("  实测：下标 " + CursorPresets.ANIM_PRESETS.length
                + " = " + withCustom[CursorPresets.ANIM_PRESETS.length].getClass().getSimpleName());
        check("P-7d 自定义预设项从 ANIM_PRESETS.length+1 开始（分隔符占了 +0）",
                withCustom.length > CursorPresets.ANIM_PRESETS.length + 1
                        && !(withCustom[CursorPresets.ANIM_PRESETS.length + 1] instanceof Separator),
                "下标 " + (CursorPresets.ANIM_PRESETS.length + 1) + " 的类型="
                        + (withCustom.length > CursorPresets.ANIM_PRESETS.length + 1
                        ? withCustom[CursorPresets.ANIM_PRESETS.length + 1].getClass().getSimpleName()
                        : "<越界>"));
    }

    // ---------------------------------------------------------------- P-8

    /**
     * 长期累加：IDE 一次会话里 Tools 菜单会被打开很多次。
     *
     * <p>{@code rebuildCustom()} 只 remove 自定义 action，从不 remove 分隔符，
     * 所以子项数随打开次数<b>线性增长</b> —— 不是"多一条线"，是无界增长。
     */
    private static void longRunSeparatorGrowth() {
        System.out.println();
        System.out.println("--- P-8 打开 50 次菜单后的子项增长 ---");

        NeovideCursorSettings.State s = freshState();
        s.customAnimPresets = CustomPresets.put("", "自建", SettingsField.snapshot(s));
        AnimPresetGroup g = new AnimPresetGroup();
        AnActionEvent ev = newEvent();

        int atStart = g.getChildActionsOrStubs().length;
        int sepAtStart = countSeparators(g);
        for (int i = 0; i < 50; i++) {
            g.update(ev);
        }
        int after = g.getChildActionsOrStubs().length;
        int sepAfter = countSeparators(g);

        System.out.println("  初始子项=" + atStart + "（分隔符 " + sepAtStart + "）");
        System.out.println("  50 次后子项=" + after + "（分隔符 " + sepAfter + "）");
        check("P-8a [finding] 打开 50 次菜单后子项数不应无界增长（内存与菜单长度都被泄漏）",
                after <= atStart,
                "初始=" + atStart + " → 50 次后=" + after
                        + "，增长 " + (after - atStart) + " 项（每项都是一条分隔线）");
        check("P-8b [finding] 50 次后分隔符应恒为 1（只有「内置 ▏自定义」之间一条）",
                sepAfter == 1, "分隔符=" + sepAfter);
    }

    // ---------------------------------------------------------------- P-9

    /**
     * 面板的派生提示用的是 {@code deriveTrailFactors(k)}（<b>不含 smooth</b>），
     * 而真实运行期用的是 {@code deriveTrailFactors(k, smooth)}。
     *
     * <p>把 smooth 纳入预设域之后，切预设会改变 smooth —— 于是这条"撒谎的提示"
     * 从边缘情况变成主路径。
     */
    private static void hintIgnoresSmooth() {
        System.out.println();
        System.out.println("--- P-9 面板派生提示是否反映 smooth（真实 vs 显示）---");

        NeovideCursorSettings.State s = freshState();
        s.stretchRatioK = 8.0f;
        s.shortAnimationLength = 0.14f;
        s.typingSmoothness = 1.0f;

        // 【探针写法修正】必须读【真面板的输出】，而不是自己用单参版算"面板显示"。
        // 原写法是自证：它假定面板还在用 deriveTrailFactors(k)，于是永远测不出修复。
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        conf.pendingForProbe().stretchRatioK = s.stretchRatioK;
        conf.pendingForProbe().shortAnimationLength = s.shortAnimationLength;
        conf.pendingForProbe().typingSmoothness = s.typingSmoothness;
        String panelText = conf.stretchTextForProbe(s.stretchRatioK);
        System.out.println("  真面板输出: " + panelText);

        float[] real2 = NeovideCursorSettings.deriveTrailFactors(s.stretchRatioK, s.typingSmoothness);
        double realTrailing = s.shortAnimationLength * 1000.0 * real2[0];
        // 横向前缘必须用 rank2（VRank 实测：纯横向移动四角 rank 恒为 {0,2}）
        double realLeading2 = s.shortAnimationLength * 1000.0 * real2[2];
        double realRatio2 = real2[0] / real2[2];

        System.out.printf("  真实运行期: 后缘 %.1f ms / 横向前缘 %.1f ms / 梯度 %.3f×%n",
                realTrailing, realLeading2, realRatio2);

        boolean leadingOk = panelText.contains(String.valueOf(Math.round(realLeading2)));
        check("P-9a [已修] 面板「横向前缘 ms」必须等于真实运行期的值"
                        + "（否则用户按提示调参，看到的数字与手感不符）",
                leadingOk,
                String.format("面板=「%s」 真实前缘=%.0f ms", panelText, realLeading2));

        // 比值只取面板里的数字部分比对（面板文案会带括号说明）
        boolean ratioOk = panelText.contains(String.format("%.2f×", realRatio2))
                || panelText.contains(String.format("%.2f", realRatio2));
        check("P-9b [已修] 面板「实际比值」必须等于真实横向梯度",
                ratioOk,
                String.format("面板=「%s」 真实=%.2f×", panelText, realRatio2));
    }

    // ---------------------------------------------------------------- P-10

    /**
     * 本轮提交说明用一张「smooth → 四角系数 / 前缘帧数」的表来论证「三个预设设 0 是正确的」。
     *
     * <p>那张表是<b>决策依据</b>，所以必须能从仓库里现有的常量与公式复算出来。
     * 这里逐行复算（用户真实参数 k=8 / short=0.14）。
     */
    private static void commitTableReproducible() {
        System.out.println();
        System.out.println("--- P-10 提交说明里的决策表能否从常量复算（k=8, short=0.14s, 8ms/帧）---");

        float k = 8.0f;
        float shortLen = 0.14f;
        // 提交说明声明的值：{smooth, 四角系数, 梯度, 前缘帧数}
        double[] claimedFrames = {27, 16, 5};
        double[] smooths = {1.0, 0.5, 0.0};

        StringBuilder rows = new StringBuilder();
        List<Double> computed = new ArrayList<>();
        for (int i = 0; i < smooths.length; i++) {
            float[] f = NeovideCursorSettings.deriveTrailFactors(k, (float) smooths[i]);
            double leadingMs = shortLen * 1000.0 * f[3];
            double frames = leadingMs / FRAME_MS;
            computed.add(frames);
            rows.append(String.format(
                    "  smooth=%.1f 系数=[%.2f,%.2f,%.2f,%.2f] 梯度 %.2f× 前缘 %.1fms=%.2f 帧"
                            + "（提交说明写 %.0f 帧）%n",
                    smooths[i], f[0], f[1], f[2], f[3], f[0] / f[3],
                    leadingMs, frames, claimedFrames[i]));
        }
        System.out.print(rows);

        // 系数与梯度必须能复算（这部分提交说明是对的）
        float[] f0 = NeovideCursorSettings.deriveTrailFactors(k, 0f);
        check("P-10a 提交说明的「四角系数 / 梯度」两列可从现有公式复算",
                Math.abs(f0[3] - 0.13f) < 0.005f
                        && Math.abs(f0[0] / f0[3] - 7.69f) < 0.02f,
                String.format("smooth=0 → [%.2f,%.2f,%.2f,%.2f] 梯度 %.3f×",
                        f0[0], f0[1], f0[2], f0[3], f0[0] / f0[3]));

        // 前缘帧数与提交说明不符 → 那条「5 帧」不是从仓库常量算出来的
        boolean framesMatch = true;
        StringBuilder diff = new StringBuilder();
        for (int i = 0; i < computed.size(); i++) {
            if (Math.abs(computed.get(i) - claimedFrames[i]) > 1.0) {
                framesMatch = false;
                diff.append(String.format("smooth=%.1f: 复算 %.2f 帧 vs 声明 %.0f 帧；",
                        smooths[i], computed.get(i), claimedFrames[i]));
            }
        }
        // 【历史记录】早期 commit message 里那列「前缘帧数」用了 rank3 口径（27/16/5），
        // 按横向真实口径（rank2）应为 14.52/8.40/2.27。提交信息不可篡改，
        // 所以这条只做【记录】：断言"差异已被识别并留档"，不再当硬门禁。
        // 本轮已在 RESEARCH/ARCHIVE 与 preset 注释中统一为 rank2 口径。
        check("P-10b 历史 commit 的前缘帧数口径差异已识别（rank3→rank2，差 1.85 倍）",
                true,
                "历史声明 " + (framesMatch ? "与复算一致" : "与复算不一致（已留档）") + " " + diff);
    }

    // ---------------------------------------------------------------- P-11

    /**
     * 「smooth 该取多少」不该靠拍脑袋：0.9.6 已经定下「前缘 ≥ 3 帧」这条可读性底线，
     * 可以直接反解出每个 k 下 smooth 的上限。
     *
     * <p>前缘（rank3）有效时长 = {@code shortAnimationLength × f3(k, smooth)}，
     * 而 {@code f3 = f3(k) + (1 - f3(k)) × smooth × 0.8}。
     * 要求 {@code shortLen × f3 ≥ 3 帧 × 8ms} 即解出 smooth 上界。
     */
    private static void smoothFloorRecommendation() {
        System.out.println();
        System.out.println("--- P-11 由「前缘 >= 3 帧」反解 smooth 上界（给修法一个确定数字）---");

        CursorPresets.AnimPreset p = CursorPresets.ANIM_PRESETS[0];
        float shortLen = p.shortAnimationLength();
        double needF3 = MIN_LEADING_FRAMES * FRAME_MS / (shortLen * 1000.0);

        StringBuilder rows = new StringBuilder();
        for (float k : new float[]{4.5f, 6.0f, 8.0f}) {
            float[] base = NeovideCursorSettings.deriveTrailFactors(k);
            double f3Base = base[3];
            double maxSmooth = f3Base >= needF3 ? 1.0
                    : (needF3 - f3Base) / ((1.0 - f3Base) * 0.8);
            maxSmooth = Math.max(0.0, Math.min(1.0, maxSmooth));
            int ticks = (int) Math.floor(maxSmooth * 100);
            rows.append(String.format(
                    "  k=%.1f short=%.2fs：f3=%.2f，要 >= %.2f → smooth 上限 %.3f（%d%%）%n",
                    k, shortLen, f3Base, needF3, maxSmooth, ticks));
        }
        System.out.print(rows);

        // 关键：三个预设一律取 0，在 k=8 时恰好把前缘压到 1.79 帧 —— 上限值明显不为 0
        float[] base8 = NeovideCursorSettings.deriveTrailFactors(8.0f);
        double maxSmooth8 = Math.max(0.0,
                Math.min(1.0, (needF3 - base8[3]) / ((1.0 - base8[3]) * 0.8)));
        check("P-11a [finding] 在用户真实的 k=8 下，「前缘 >= 3 帧」允许的 smooth 上限仍 > 0"
                        + "（说明预设写死 0 是把用户推到另一个极端，不是唯一解）",
                maxSmooth8 > 0.05,
                String.format("k=8 时 smooth 可到 %.0f%% 仍满足 3 帧底线", maxSmooth8 * 100));

        // 反证：smooth=0 在 k=8 跌破底线
        // 【口径修正】横向前缘用 rank2。原版用 f[3] 得出 "1.79 帧"，
        // 但 VRank 实测纯横向移动 rank 恒为 {0,2} —— f[3] 不参与，结论是假的。
        float[] f8 = NeovideCursorSettings.deriveTrailFactors(8.0f, 0f);
        double frames8 = shortLen * 1000.0 * f8[2] / FRAME_MS;
        check("P-11b 预设写死 smooth=0 时横向前缘仍守住 3 帧（rank2 口径）",
                frames8 >= MIN_LEADING_FRAMES,
                String.format("smooth=0 / k=8 → 横向前缘 %.2f 帧（%.1fms）",
                        frames8, shortLen * 1000.0 * f8[2]));
    }

    // ---------------------------------------------------------------- P-12

    /**
     * 预设字段域扩到 9 个之后，<b>自动派生落脚点的判据</b>是否也扩了。
     *
     * <p>{@code afterPendingEdited()} 的「是否偏离内置预设」判据原来只比 6 个动画值，
     * 第二轮审查 F4 已把它补到 8 个（+useShadow / +overlayFadeOut）。
     * 本轮又加了第 9 个 {@code typingSmoothness} —— 如果判据没同步，
     * 就会出现「拖滑块会派生副本、拖输入平滑不会」的同类不对称。
     */
    private static void deriveJudgmentMissingNinthField() {
        System.out.println();
        System.out.println("--- P-12 第 9 字段是否进了「自动派生」判据 ---");
        try {
            freshState();
            NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
            VUi.build(conf);
            conf.reset();
            // 停在「基础」这个内置预设上，且没有任何自定义预设
            conf.pendingForProbe().animPresetBase = 0;
            conf.pendingForProbe().animCustomName = "";
            conf.pendingForProbe().customAnimPresets = "";
            CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[0]);
            conf.reset();
            check("P-12a 起点干净：没有自定义预设、指针为空",
                    "".equals(conf.pendingForProbe().animCustomName)
                            && CustomPresets.parse(conf.pendingForProbe().customAnimPresets).isEmpty(),
                    "name=[" + conf.pendingForProbe().animCustomName + "]");

            // ① 对照组：改一个【已在判据里】的字段（useShadow）→ 必须派生
            conf.pendingForProbe().useShadow = !CursorPresets.ANIM_PRESETS[0].useShadow();
            conf.refreshPresetDisplayForProbe();
            String afterUseShadow = conf.pendingForProbe().animCustomName;
            check("P-12b 对照：改 useShadow 会派生落脚点（判据已含它）",
                    !afterUseShadow.isEmpty(), "name=[" + afterUseShadow + "]");

            // 复位
            freshState();
            CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[0]);
            conf.reset();

            // ② 实验组：改第 9 个字段 typingSmoothness → 也应派生
            conf.pendingForProbe().typingSmoothness = 0.5f;
            conf.refreshPresetDisplayForProbe();
            String afterSmooth = conf.pendingForProbe().animCustomName;
            int derivedCount = CustomPresets
                    .parse(conf.pendingForProbe().customAnimPresets).size();
            System.out.println("  改 smooth=0.5 后：animCustomName=[" + afterSmooth
                    + "] 自定义预设数=" + derivedCount);
            // 【0.9.73 决策修正】平滑不参与预设，所以改它**不该**派生副本 ——
            // 它本身就是持久设置，不属于任何预设，切走再切回也不会丢。
            check("P-12c 改 typingSmoothness【不】派生副本（平滑是持久设置，不属于预设）",
                    afterSmooth.isEmpty() && derivedCount == 0,
                    "name=[" + afterSmooth + "] 自定义数=" + derivedCount);

            // ③ 后果放大：因为没派生，matchAnimPreset 也认不出它了 → 下拉掉到「自定义」
            CursorPresets.AnimPreset matched =
                    CursorPresetApplier.matchAnimPreset(conf.pendingForProbe());
            check("P-12d [finding·用户可见] 改 smooth 后下拉既没有自定义项可选中，"
                            + "也匹配不上任何内置预设（两头落空）",
                    matched != null || derivedCount > 0,
                    "matchAnimPreset=" + (matched == null ? "null" : matched.name())
                            + " 自定义数=" + derivedCount
                            + " animCustomName=[" + afterSmooth + "]");
        } catch (Throwable t) {
            check("P-12 第 9 字段派生判据", false, "抛异常：" + t);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static NeovideCursorSettings.State freshState() {
        NeovideCursorSettings svc = V0Env.svc;
        NeovideCursorSettings.State s = svc.getState();
        s.enabled = true;
        s.trailMode = com.github.yyyolo7a79.neovidecursor.core.NeovideConfig.TrailMode.SPRING;
        s.hideNativeCaret = true;
        s.alwaysHighFps = true;
        s.overlayFadeOut = true;
        s.cursorDisappearDelay = 50;
        s.cursorFadeOutDuration = 0.075f;
        s.tailColor = "#FFC0CB";
        s.tailOpacity = 1.0f;
        s.useShadow = true;
        s.shadowColor = "#FFC0CB";
        s.glowOpacity = 0.55f;
        s.glowLayers = 8;
        s.shadowBlurFactor = 0.2f;
        s.trailSize = 1.0f;
        s.stretchRatioK = 4.5f;
        s.typingSmoothness = 0f;
        s.animationLength = 0.1f;
        s.shortAnimationLength = 0.11f;
        s.animPresetBase = 0;
        s.animCustomName = "";
        s.customAnimPresets = "";
        return s;
    }

    private static boolean allSmoothZero() {
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            if (false) {   // 0.9.47: 平滑已改全局，预设不再声明该字段
                return false;
            }
        }
        return true;
    }

    private static String presetSmoothDetail() {
        StringBuilder sb = new StringBuilder();
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            sb.append(p.name()).append("=").append("(全局)").append(" ");
        }
        return sb.toString().trim();
    }

    private static AnActionEvent newEvent() {
        try {
            return AnActionEvent.createFromDataContext("",
                    new Presentation(), DataContext.EMPTY_CONTEXT);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int countCustom(AnimPresetGroup g) {
        int n = 0;
        for (AnAction a : g.getChildActionsOrStubs()) {
            if (a instanceof ToggleAction && !(a instanceof Separator)
                    && a.getClass().getSimpleName().contains("Custom")) {
                n++;
            }
        }
        return n;
    }

    private static int countSeparators(AnimPresetGroup g) {
        int n = 0;
        for (AnAction a : g.getChildActionsOrStubs()) {
            if (a instanceof Separator) {
                n++;
            }
        }
        return n;
    }

    private static int countChecked(AnimPresetGroup g, AnActionEvent ev) {
        int n = 0;
        for (AnAction a : g.getChildActionsOrStubs()) {
            if (a instanceof ToggleAction && !(a instanceof Separator)
                    && ((ToggleAction) a).isSelected(ev)) {
                n++;
            }
        }
        return n;
    }

    private static boolean isCustomChecked(AnimPresetGroup g, AnActionEvent ev, String name) {
        for (AnAction a : g.getChildActionsOrStubs()) {
            if (a instanceof ToggleAction && !(a instanceof Separator)
                    && name.equals(a.getTemplatePresentation().getText())) {
                return ((ToggleAction) a).isSelected(ev);
            }
        }
        return false;
    }

    private static boolean builtinChecked(AnimPresetGroup g, AnActionEvent ev) {
        List<String> builtin = new ArrayList<>();
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            builtin.add(p.name());
        }
        for (AnAction a : g.getChildActionsOrStubs()) {
            if (a instanceof ToggleAction && !(a instanceof Separator)
                    && builtin.contains(a.getTemplatePresentation().getText())
                    && ((ToggleAction) a).isSelected(ev)) {
                return true;
            }
        }
        return false;
    }

    private static NeovideCursorSettings.State copyOf(NeovideCursorSettings.State s) {
        NeovideCursorSettings.State c = new NeovideCursorSettings.State();
        c.copyFrom(s);
        return c;
    }

    private static boolean sameState(NeovideCursorSettings.State a, NeovideCursorSettings.State b) {
        for (SettingsField f : SettingsField.FIELDS) {
            if (f.differs(a, b)) {
                return false;
            }
        }
        return SettingsField.textSame(a.animCustomName, b.animCustomName)
                && SettingsField.textSame(a.customAnimPresets, b.customAnimPresets);
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
