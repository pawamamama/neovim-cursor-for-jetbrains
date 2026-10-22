
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

/** 直接验 P-4a / P-9a 是否真修好。 */
public final class VVerify {
    public static void main(String[] a) {
        V0Env.install();
        System.out.println("=== 核实 P-4a（双勾选）与 P-9a（面板口径）===");

        // P-9a: 面板事件文本 vs 真实运行期
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        NeovideCursorSettings.State p = conf.pendingForProbe();
        p.stretchRatioK = 8.0f;
        p.typingSmoothness = 0.0f;
        p.shortAnimationLength = 0.14f;
        String txt = conf.stretchTextForProbe(8.0f);
        System.out.println("  面板 stretchText = " + txt);

        float[] f2 = NeovideCursorSettings.deriveTrailFactors(8.0f, 0.0f);
        long realFront = Math.round(0.14f * 1000f * f2[2]);
        System.out.printf("  真实横向前缘(rank2) = %d ms, 后缘 = %d ms, 梯度 = %.2fx%n",
                realFront, Math.round(0.14f*1000f*f2[0]), f2[0]/f2[2]);
        System.out.println("  -> P-9a " + (txt.contains(String.valueOf(realFront)) ? "已修好" : "仍不一致"));

        // P-4a: 自定义预设数值 == 内置预设时，是否双勾选
        NeovideCursorSettings svc = V0Env.svc;
        NeovideCursorSettings.State s = svc.getState();
        NeovideCursorConfigurable.applyAnimPresetFields(s, CursorPresets.ANIM_PRESETS[0]);
        s.customAnimPresets = "";
        s.customAnimPresets = com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                .put("", "我的基础副本", com.github.yyyolo7a79.neovidecursor.settings.SettingsField.snapshot(s));
        s.animCustomName = "我的基础副本";

        com.intellij.openapi.actionSystem.AnAction[] kids =
                new com.github.yyyolo7a79.neovidecursor.ui.AnimPresetGroup().getChildActionsOrStubs();
        int checked = 0; StringBuilder names = new StringBuilder();
        for (com.intellij.openapi.actionSystem.AnAction ac : kids) {
            if (ac instanceof com.intellij.openapi.actionSystem.ToggleAction) {
                if (((com.intellij.openapi.actionSystem.ToggleAction) ac).isSelected(null)) {
                    checked++;
                    names.append(ac.getTemplatePresentation().getText()).append(" ");
                }
            }
        }
        System.out.println("  勾选数 = " + checked + " [" + names.toString().trim() + "]");
        System.out.println("  -> P-4a " + (checked <= 1 ? "已修好" : "仍双勾选"));
    }
}
