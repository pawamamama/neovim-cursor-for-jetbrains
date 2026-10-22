import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.AnimPresetGroup;
import com.github.yyyolo7a79.neovidecursor.ui.ColorPresetGroup;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;

import java.util.ArrayList;
import java.util.List;

/**
 * verifier 自建（t4）：Tools 菜单的两个预设子菜单（契约 §8）。
 *
 * <p>判据：
 * <ul>
 *   <li>两个组都能实例化，子项数 = 7 / 5，全部是 {@link ToggleAction}；</li>
 *   <li>勾选判据与面板同源（matchAnimPreset / matchColorPreset），因此天然单选；</li>
 *   <li>{@code setSelected(false)}（用户点已勾选项）不改变任何值；</li>
 *   <li>{@code setSelected(true)} 应用该预设，且只改它该改的字段。</li>
 * </ul>
 */
public final class VTools {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-TOOLS：Tools 预设子菜单 ===");
        V0Env.install();
        NeovideCursorSettings svc = V0Env.svc;

        // ---- 动画组 ----
        AnimPresetGroup animGroup = new AnimPresetGroup();
        AnAction[] animItems = animGroup.getChildActionsOrStubs();
        check("M-1 动画预设组可实例化", animGroup != null, "");
        check("M-2 动画组是 DefaultActionGroup（AnActionGroup 在本机 jar 里不存在）",
                animGroup instanceof DefaultActionGroup, "");
        // 【0.9.10】动画预设精简到 3 个；断言与预设表同源，不再写死数字。
        check("M-3 动画组子项数 == ANIM_PRESETS.length",
                animItems.length == com.github.yyyolo7a79.neovidecursor.settings.CursorPresets
                        .ANIM_PRESETS.length,
                "n=" + animItems.length + " 表="
                        + com.github.yyyolo7a79.neovidecursor.settings.CursorPresets.ANIM_PRESETS.length);
        boolean allToggle = true;
        for (AnAction a : animItems) {
            allToggle &= a instanceof ToggleAction;
        }
        check("M-4 动画组全部子项是 ToggleAction（勾选态天然单选）", allToggle, "");

        ColorPresetGroup colorGroup = new ColorPresetGroup();
        AnAction[] colorItems = colorGroup.getChildActionsOrStubs();
        check("M-5 颜色组有 5 个子项", colorItems.length == 5, "n=" + colorItems.length);
        boolean allToggle2 = true;
        for (AnAction a : colorItems) {
            allToggle2 &= a instanceof ToggleAction;
        }
        check("M-6 颜色组 5 个子项全是 ToggleAction", allToggle2, "");

        // ---- 菜单文案与预设表一致（两处不一致 = 双口径）----
        boolean namesMatch = true;
        for (int i = 0; i < animItems.length; i++) {
            namesMatch &= animItems[i].getTemplatePresentation().getText()
                    .equals(CursorPresets.ANIM_PRESETS[i].name());
        }
        check("M-7 动画菜单项文案与预设表逐项一致", namesMatch, "");
        boolean namesMatch2 = true;
        for (int i = 0; i < colorItems.length; i++) {
            namesMatch2 &= colorItems[i].getTemplatePresentation().getText()
                    .equals(CursorPresets.COLOR_PRESETS[i].name());
        }
        check("M-8 颜色菜单项文案与预设表逐项一致", namesMatch2, "");

        // ---- 单选：应用「缎带」后恰 1 项勾选 ----
        NeovideCursorSettings.State st = svc.getState();
        st.animationLength = 0.1f;
        st.shortAnimationLength = 0.11f;
        st.trailSize = 1f;
        st.glowLayers = 8;
        st.shadowBlurFactor = 0.2f;
        st.glowOpacity = 0.55f;
        st.tailColor = "#FFC0CB";
        st.shadowColor = "#FFC0CB";

        CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[1]);   // 缎带
        List<Integer> checked = new ArrayList<>();
        for (int i = 0; i < animItems.length; i++) {
            ToggleAction t = (ToggleAction) animItems[i];
            if (t.isSelected(null)) {
                checked.add(i);
            }
        }
        check("M-9 应用「缎带」后恰好 1 项被勾选，且下标为 1",
                checked.size() == 1 && checked.get(0) == 1, "checked=" + checked);

        // ---- setSelected(false) 不改变任何值 ----
        java.util.Map<String, String> before = VFields.snapshot(svc.getState());
        ((ToggleAction) animItems[1]).setSelected(null, false);
        java.util.Map<String, String> after = VFields.snapshot(svc.getState());
        check("M-10 点已勾选项（setSelected=false）不改变任何字段",
                VFields.countDiff(before, after, java.util.Collections.emptySet()) == 0,
                "");

        // ---- setSelected(true) 应用且只改动画字段 ----
        before = VFields.snapshot(svc.getState());
        // 用最后一个预设（原写死 [6] 指「高对比」，已随预设精简删除）
        ((ToggleAction) animItems[animItems.length - 1]).setSelected(null, true);
        after = VFields.snapshot(svc.getState());
        // 【F7 的连带影响，必须写清楚】
        // F7 之后 applyAnimPreset 会同步前移 animPresetBase（与面板 onAnimPresetPicked() 一致），
        // 否则「Tools 切到有覆盖的预设」后面板下拉仍指着上一个预设，
        // 下一次面板 apply 会把覆盖写到【错误的 key】上（见 CursorPresetApplier 的 javadoc）。
        //
        // animPresetBase 是**预设元数据**（当前停在第几个预设），不是观感参数 ——
        // 它不参与 matchAnimPreset 的逐字段比对，也不进 NeovideConfig。
        // 所以它归入「应用预设时允许变化」这一侧，而不是「其余字段逐位不变」那一侧。
        // 这不是放宽断言：动画字段之外的 18 个字段仍然要求 0 变化。
        // 【0.9.10 预设字段域扩大（F-05/F-07）】
        // 预设现在描述「一整套观感」，除 6 个动画字段外还包含 useShadow / overlayFadeOut。
        // 这是【有意设计】：用户在某预设下关掉辉光，切到别的预设必须能把辉光打开
        // （用户实测：「切换预设不能让辉光回来」「缎带没有扩散了，就是持久化了」）。
        // 而「纯平滑输入」这个预设本身就必须表达「关辉光」—— 6 字段模型写不出来。
        //
        // 所以这三项归入「应用预设时允许变化」一侧。这不是放宽断言：
        // 颜色 2 项 + stretchRatioK + typingSmoothness + cursorDisappearDelay +
        // cursorFadeOutDuration + tailOpacity + enabled + trailMode + hideNativeCaret +
        // alwaysHighFps 共 12 个字段仍然要求逐位不变（颜色正交性硬判据不变）。
        java.util.Set<String> animFields = new java.util.HashSet<>(java.util.Arrays.asList(
                "animationLength", "shortAnimationLength", "trailSize", "glowLayers",
                "shadowBlurFactor", "glowOpacity",
                "useShadow", "overlayFadeOut",
                "animPresetBase"));
        java.util.Set<String> others = new java.util.HashSet<>(before.keySet());
        others.removeAll(animFields);
        // 「只改 6 个动画字段」的精确判据是：动画字段之外 0 变化；
        // 动画字段内部有几项变化取决于前后两个预设是否恰好同值（缎带与高对比的 trailSize 都是 1.00）。
        // 所以这里断言的是「其余字段 0 变化」，并额外核对动画字段确有实质变化。
        for (String k : before.keySet()) {
            if (!before.get(k).equals(after.get(k))) {
                System.out.println("    [字段变化] " + String.format("%-24s", k)
                        + before.get(k) + "  ->  " + after.get(k));
            }
        }
        // countDiff 的第三参是「忽略集合」：忽略动画字段 → 数出非动画字段的变化数。
        int nonAnimChanged = VFields.countDiff(before, after, animFields);
        int animChanged = VFields.countDiff(before, after, others);
        check("M-11 Tool 应用预设：只改预设字段域（动画 6 项 + 辉光/淡出 + base），其余逐位不变",
                nonAnimChanged == 0 && animChanged > 0,
                "预设域外变化 = " + nonAnimChanged + "，预设域内变化 = " + animChanged);

        // ---- 颜色组单选 ----
        CursorPresetApplier.applyColorPreset(CursorPresets.COLOR_PRESETS[2]); // 薄荷
        List<Integer> cchecked = new ArrayList<>();
        for (int i = 0; i < colorItems.length; i++) {
            if (((ToggleAction) colorItems[i]).isSelected(null)) {
                cchecked.add(i);
            }
        }
        check("M-12 应用「薄荷」后恰好 1 项被勾选，且下标为 2",
                cchecked.size() == 1 && cchecked.get(0) == 2, "checked=" + cchecked);

        System.out.println();
        System.out.printf("=== V-TOOLS 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-TOOLS RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-TOOLS RESULT: ALL PASS");
        System.exit(0);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "   [" + detail + "]"));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}
