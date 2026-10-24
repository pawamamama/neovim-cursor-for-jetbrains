import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

/**
 * V-MULTI-NOGLOW：多光标不辉光必须是【全局设置】，不随预设变化。
 *
 * <p><b>用户要求原文</b>：「做在总开关吧这个算是性能优化了，
 * 记住是全局生效的改预设不该动这个」。
 */
public final class VMultiNoGlow {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-MULTI-NOGLOW：全局开关，不随预设变 ===");

        // ---- N-1: SettingsField 表里有它（决定 apply 会不会丢）----
        boolean inTable = false;
        boolean syncsToConfig = true;
        for (SettingsField f : SettingsField.FIELDS) {
            if ("multiCursorNoGlow".equals(f.key)) {
                inTable = true;
                syncsToConfig = f.syncsToConfig;
                break;
            }
        }
        check("N-1 SettingsField.FIELDS 含 multiCursorNoGlow", inTable,
                inTable ? "已注册" : "缺失 → apply 会丢");
        check("N-2 不进 CONFIG（生效逻辑在 CaretAnimator 读 State）",
                !syncsToConfig, "syncsToConfig=" + syncsToConfig);

        // ---- N-3: copyFrom 有它（决定面板回显）----
        NeovideCursorSettings.State from = new NeovideCursorSettings.State();
        from.multiCursorNoGlow = true;
        NeovideCursorSettings.State to = new NeovideCursorSettings.State();
        to.copyFrom(from);
        check("N-3 copyFrom 会带上 multiCursorNoGlow", to.multiCursorNoGlow,
                "copy 后 = " + to.multiCursorNoGlow);

        // ---- N-4: 切预设【不影响】它 ----
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.multiCursorNoGlow = true;
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            NeovideCursorConfigurable.applyAnimPresetFields(
                    s, CursorPresets.ANIM_PRESETS[i]);
            if (!s.multiCursorNoGlow) {
                fail++;
                System.out.println("  FAIL  N-4 切到预设" + i
                        + " 后 multiCursorNoGlow 被改成了 false");
                break;
            }
        }
        if (s.multiCursorNoGlow) {
            pass++;
            System.out.println("  PASS  N-4 全部 " + CursorPresets.ANIM_PRESETS.length
                    + " 个预设都不动 multiCursorNoGlow");
        }

        // ---- N-5: AnimPreset 里没有这个字段 ----
        boolean inPreset = false;
        try {
            CursorPresets.AnimPreset.class.getMethod("multiCursorNoGlow");
            inPreset = true;
        } catch (NoSuchMethodException expected) {
            // 期望走这里
        }
        check("N-5 AnimPreset 不含该字段（确认是全局）", !inPreset,
                inPreset ? "被误加进预设了" : "不在预设里");

        System.out.println();
        System.out.printf("=== V-MULTI-NOGLOW 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-MULTI-NOGLOW RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-MULTI-NOGLOW RESULT: ALL PASS");
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
