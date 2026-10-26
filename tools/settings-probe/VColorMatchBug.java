import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;

/** 验证 matchColorPreset 的判据是否失效。 */
public final class VColorMatchBug {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        V0Env.install();
        System.out.println("=== matchColorPreset 判据验证 ===");

        // 找彩虹和原版粉色
        CursorPresets.ColorPreset rainbow = null, pink = null;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p.rgbWash()) rainbow = p;
            if ("原版粉色".equals(p.name())) pink = p;
        }
        check("1 找到彩虹预设", rainbow != null, rainbow == null ? "无" : rainbow.name());
        check("2 找到原版粉色预设", pink != null, pink == null ? "无" : pink.name());

        // 场景 A：选原版粉色 + 开光标渐变
        NeovideCursorSettings.State sA = new NeovideCursorSettings.State();
        sA.tailColor = pink.tailColor();
        sA.shadowColor = pink.shadowColor();
        sA.rgbGradient = true;          // 用户开了「光标渐变」
        CursorPresets.ColorPreset mA = CursorPresetApplier.matchColorPreset(sA);
        check("3 粉色+光标渐变 -> 应匹配【原版粉色】，不该误判成彩虹",
                mA == pink, "实际=" + (mA == null ? "null" : mA.name()));

        // 场景 B：选彩虹 + 用户关掉光标渐变
        NeovideCursorSettings.State sB = new NeovideCursorSettings.State();
        sB.tailColor = rainbow.tailColor();
        sB.shadowColor = rainbow.shadowColor();
        sB.rgbGradient = false;         // 用户关了「光标渐变」
        CursorPresets.ColorPreset mB = CursorPresetApplier.matchColorPreset(sB);
        check("4 彩虹+关闭渐变 -> 应仍匹配【彩虹】",
                mB == rainbow, "实际=" + (mB == null ? "null" : mB.name()));

        // 场景 C：正常情况（选彩虹 + 自动开的 rgbGradient）
        NeovideCursorSettings.State sC = new NeovideCursorSettings.State();
        sC.tailColor = rainbow.tailColor();
        sC.shadowColor = rainbow.shadowColor();
        sC.rgbGradient = true;
        CursorPresets.ColorPreset mC = CursorPresetApplier.matchColorPreset(sC);
        check("5 彩虹+开启渐变 -> 匹配彩虹（正常路径）",
                mC == rainbow, "实际=" + (mC == null ? "null" : mC.name()));

        System.out.println();
        System.out.printf("=== PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("RESULT: 判据确实失效"); System.exit(1); }
        System.out.println("RESULT: 判据正常");
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}