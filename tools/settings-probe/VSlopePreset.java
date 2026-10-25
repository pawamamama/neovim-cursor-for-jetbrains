import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

/** V-SLOPE-PRESET：斜度进预设域的验证。 */
public final class VSlopePreset {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        System.out.println("=== 斜度进预设 ===");

        // 1) 82 产出的四角系数 == 上游 k=4.5
        float[] f82 = NeovideCursorSettings.deriveTrailFactorsFromSlope(82);
        String got = String.format("%.3f/%.3f/%.3f/%.3f", f82[0], f82[1], f82[2], f82[3]);
        check("82 产出 [1.000/0.720/0.420/0.220]（对齐上游）",
                Math.abs(f82[0] - 1.0f) < 0.001 && Math.abs(f82[1] - 0.72f) < 0.001
                        && Math.abs(f82[2] - 0.42f) < 0.001 && Math.abs(f82[3] - 0.22f) < 0.001,
                got);

        // 2) 三个预设都是 82
        boolean all82 = true;
        StringBuilder t = new StringBuilder();
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            t.append(p.name()).append("=").append(p.slopeTicksValue()).append(" ");
            if (p.slopeTicksValue() != 82) all82 = false;
        }
        check("三个内置预设的斜度都是 82", all82, t.toString().trim());

        // 3) 切预设会写 slopeTicks（进预设域的核心判据）
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.slopeTicks = 30;                       // 用户改成 30
        NeovideCursorConfigurable.applyAnimPresetFields(s, CursorPresets.ANIM_PRESETS[0]);
        check("切预设把斜度拉回 82（说明它在预设域内）",
                s.slopeTicks == 82, "切后 = " + s.slopeTicks);

        // 4) State 默认 82
        NeovideCursorSettings.State def = new NeovideCursorSettings.State();
        check("State 默认 slopeTicks = 82", def.slopeTicks == 82,
                String.valueOf(def.slopeTicks));

        // 5) 旧值 -1 迁移成 82
        NeovideCursorSettings svc = new NeovideCursorSettings();
        NeovideCursorSettings.State old = new NeovideCursorSettings.State();
        old.slopeTicks = -1;
        svc.loadState(old);
        check("旧值 -1 迁移成 82", svc.getState().slopeTicks == 82,
                "读回 = " + svc.getState().slopeTicks);

        System.out.println();
        System.out.printf("=== 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.exit(1); }
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}