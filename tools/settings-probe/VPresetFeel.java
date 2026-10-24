import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

/**
 * V-PRESET-FEEL：预设必须覆盖「手感」全部旋钮。
 *
 * <p><b>用户反馈（2026-10-24）</b>：「切换预设手感动效没回来」。
 *
 * <p><b>根因</b>：{@code AnimPreset} 的字段表里没有 {@code stretchRatioK}，
 * 而它和 {@code typingSmoothness} 一起决定 {@code trailFactors}。
 * 于是切任何预设，{@code stretchRatioK} 都保持用户上次拖出来的值 ——
 * 三个预设的手感实际完全相同。
 *
 * <p><b>本探针的判据</b>：三个内置预设算出的 {@code trailFactors}
 * <b>必须两两不同</b>。相同就说明预设没管到那个旋钮。
 */
public final class VPresetFeel {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-PRESET-FEEL：预设是否覆盖全部手感旋钮 ===");
        System.out.println();

        int n = CursorPresets.ANIM_PRESETS.length;

        // ---- F-1：每个预设都要能声明 stretchRatioK ----
        boolean allDeclareStretch = true;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < n; i++) {
            CursorPresets.AnimPreset p = CursorPresets.ANIM_PRESETS[i];
            float k = readStretch(p);
            if (k < 1.0f || k > 8.0f) {
                allDeclareStretch = false;
                detail.append(p.name()).append("=null ");
            } else {
                detail.append(p.name()).append("=").append(k).append(" ");
            }
        }
        check("F-1 每个预设都声明了 stretchRatioK（1.0–8.0）",
                allDeclareStretch, detail.toString());

        // ---- F-2：三个预设的 trailFactors 必须两两不同 ----
        float[][] factors = new float[n][];
        StringBuilder fdetail = new StringBuilder();
        for (int i = 0; i < n; i++) {
            CursorPresets.AnimPreset p = CursorPresets.ANIM_PRESETS[i];
            factors[i] = NeovideCursorSettings.deriveTrailFactors(
                    readStretch(p), p.typingSmoothness());
            fdetail.append("\n        ").append(p.name()).append(" → ")
                    .append(java.util.Arrays.toString(factors[i]));
        }
        int distinct = 0;
        for (int i = 0; i < n; i++) {
            boolean dup = false;
            for (int j = 0; j < i; j++) {
                if (java.util.Arrays.equals(factors[i], factors[j])) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                distinct++;
            }
        }
        check("F-2 " + n + " 个预设的 trailFactors 两两不同", distinct == n,
                distinct + "/" + n + " 个不同" + fdetail);

        // ---- F-3：applyAnimPresetFields 必须写 stretchRatioK ----
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.stretchRatioK = 8.0f;                   // 用户手动拖到上限
        NeovideCursorConfigurable.applyAnimPresetFields(
                s, CursorPresets.ANIM_PRESETS[0]);
        check("F-3 applyAnimPresetFields 会覆盖 stretchRatioK",
                Math.abs(s.stretchRatioK - 8.0f) > 0.01f,
                "切到「基础」后 k=" + s.stretchRatioK + "（应变成预设值，不是 8.0）");

        System.out.println();
        System.out.printf("=== V-PRESET-FEEL 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-PRESET-FEEL RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-PRESET-FEEL RESULT: ALL PASS");
    }

    /**
     * 读预设声明的 stretchRatioK。
     *
     * <p>改之前 {@code AnimPreset} 没有这个方法 → 反射拿不到 → 返回 -1。
     * 这正是 F-1 要抓的。
     */
    private static float readStretch(CursorPresets.AnimPreset p) {
        try {
            java.lang.reflect.Method m = p.getClass().getMethod("stretchRatioK");
            return (float) m.invoke(p);
        } catch (Throwable t) {
            return -1f;
        }
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
