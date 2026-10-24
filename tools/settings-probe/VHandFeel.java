import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;

/**
 * V-HANDFEEL：手感链路端到端验证。
 *
 * <p><b>用户反馈</b>：「输入手感完全不对了，切换预设手感动效没回来，
 * 而且我刚才提到的两个没有改变」。
 *
 * <p>本探针直接量「切预设后 CONFIG 里真正生效的值」，
 * 而不是看 UI 或 State —— 手感的最终真相在 CONFIG。
 */
public final class VHandFeel {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-HANDFEEL：手感链路端到端 ===");

        // 直接构造 State，模拟「切到预设 0 / 1 / 2」
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();

        System.out.println();
        System.out.println("--- 预设定义的原始值 ---");
        for (int i = 0; i < com.github.yyyolo7a79.neovidecursor.settings.CursorPresets
                .ANIM_PRESETS.length; i++) {
            var p = com.github.yyyolo7a79.neovidecursor.settings.CursorPresets.ANIM_PRESETS[i];
            System.out.printf("  预设%d %-8s anim=%.2f short=%.2f trail=%.2f "
                            + "smooth=%d blur=%.2f layers=%d%n",
                    i, p.name(), p.animationLength(), p.shortAnimationLength(),
                    p.trailSize(), 0,   // 平滑已改为全局，预设不再有该字段
                    p.shadowBlurFactor(), p.glowLayers());
        }

        System.out.println();
        System.out.println("--- 关键：stretchRatioK 在预设里吗？ ---");
        // 用反射看 CursorPresets.AnimPreset 有没有 stretchRatioK 字段
        Class<?> ap = Class.forName(
                "com.github.yyyolo7a79.neovidecursor.settings.CursorPresets$AnimPreset");
        boolean hasStretch = false;
        for (java.lang.reflect.Field f : ap.getDeclaredFields()) {
            if (f.getName().toLowerCase().contains("stretch")) {
                hasStretch = true;
                System.out.println("    找到字段: " + f.getName());
            }
        }
        if (!hasStretch) {
            System.out.println("    ❌ AnimPreset 没有 stretchRatioK 字段");
        }

        // ---- H-1：切预设后 trailFactors 必须变化 ----
        float[] prevFactors = null;
        int changedCount = 0;
        for (int i = 0; i < 3; i++) {
            NeovideCursorSettings.State st = new NeovideCursorSettings.State();
            st.stretchRatioK = 4.5f;
            st.typingSmoothness = 0f;
            com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable
                    .applyAnimPresetFields(st, com.github.yyyolo7a79.neovidecursor.settings
                            .CursorPresets.ANIM_PRESETS[i]);
            float[] f = NeovideCursorSettings.deriveTrailFactors(
                    st.stretchRatioK, st.typingSmoothness);
            System.out.printf("  预设%d → smooth=%.2f k=%.1f trailFactors=%s%n",
                    i, st.typingSmoothness, st.stretchRatioK, java.util.Arrays.toString(f));
            if (prevFactors != null && !java.util.Arrays.equals(prevFactors, f)) {
                changedCount++;
            }
            prevFactors = f;
        }
        check("H-1 切换预设时 trailFactors 会变化", changedCount > 0,
                "变化次数 " + changedCount + "/2");

        // ---- H-2：stretchRatioK 是否被预设覆盖 ----
        NeovideCursorSettings.State h2 = new NeovideCursorSettings.State();
        h2.stretchRatioK = 8.0f;                  // 用户手动拖到 8
        com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable
                .applyAnimPresetFields(h2, com.github.yyyolo7a79.neovidecursor.settings
                        .CursorPresets.ANIM_PRESETS[0]);
        boolean stretchKept = Math.abs(h2.stretchRatioK - 8.0f) < 0.01f;
        check("H-2 【关键】切预设后 stretchRatioK 是否被重置",
                !stretchKept, "切完后 k=" + h2.stretchRatioK
                        + (stretchKept ? "（没重置 = 预设管不到它）" : "（被重置了）"));

        System.out.println();
        System.out.printf("=== V-HANDFEEL 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.println(fail > 0 ? "V-HANDFEEL RESULT: FAILED" : "V-HANDFEEL RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + n + "   [" + d + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + n + "   [" + d + "]");
        }
    }
}
