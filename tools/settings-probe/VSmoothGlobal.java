import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

/**
 * V-SMOOTH-GLOBAL：输入平滑必须是全局设置，不随预设变化。
 *
 * <p><b>用户决定（2026-10-24）</b>：「平滑做成全局不随预设变化的
 * 但是标注一下 开的越大动画在单行连续场景下会跟不上」。
 *
 * <p>本探针锁住三条：
 * <ol>
 *   <li>AnimPreset 不再有 typingSmoothness 字段</li>
 *   <li>applyAnimPresetFields 不写 typingSmoothness</li>
 *   <li>切预设后用户自己调的平滑值保持不变</li>
 * </ol>
 */
public final class VSmoothGlobal {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-SMOOTH-GLOBAL：输入平滑是全局设置 ===");

        // ---- G-1: AnimPreset 没有 typingSmoothness 方法 ----
        boolean hasMethod = false;
        try {
            CursorPresets.AnimPreset.class.getMethod("typingSmoothness");
            hasMethod = true;
        } catch (NoSuchMethodException expected) {
            // 期望走这里
        }
        check("G-1 AnimPreset 不再有 typingSmoothness()",
                !hasMethod, hasMethod ? "仍存在（应移除）" : "已移除");

        // ---- G-2: 切预设不覆盖用户的平滑值 ----
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.typingSmoothness = 0.4f;               // 用户调到 40%
        NeovideCursorConfigurable.applyAnimPresetFields(
                s, CursorPresets.ANIM_PRESETS[0]);
        check("G-2 切预设后 typingSmoothness 保持用户值 0.4",
                Math.abs(s.typingSmoothness - 0.4f) < 0.001f,
                "切到「基础」后 smooth=" + s.typingSmoothness);

        // 再切一个预设，仍应保持
        NeovideCursorConfigurable.applyAnimPresetFields(
                s, CursorPresets.ANIM_PRESETS[2]);
        check("G-3 再切到「纯平滑输入」仍保持 0.4",
                Math.abs(s.typingSmoothness - 0.4f) < 0.001f,
                "smooth=" + s.typingSmoothness);

        // ---- G-4: stretchRatioK 仍随预设变（不能让上一个修复失效）----
        NeovideCursorSettings.State s2 = new NeovideCursorSettings.State();
        s2.stretchRatioK = 8.0f;
        NeovideCursorConfigurable.applyAnimPresetFields(
                s2, CursorPresets.ANIM_PRESETS[0]);
        check("G-4 stretchRatioK 仍随预设变（0.9.46 的修复没被破坏）",
                Math.abs(s2.stretchRatioK - 4.5f) < 0.01f,
                "切到「基础」后 k=" + s2.stretchRatioK);

        System.out.println();
        System.out.printf("=== V-SMOOTH-GLOBAL 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-SMOOTH-GLOBAL RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-SMOOTH-GLOBAL RESULT: ALL PASS");
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
