import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

/**
 * V-SLOPE-PRESET：斜度 = 「纯矩形 ←→ 完全斜」的插值。
 *
 * <p><b>用户语义（0.9.81 明确）</b>：
 * <ul>
 *   <li>斜度 0 = <b>纯矩形拉伸</b>（有拉伸、无倾斜）</li>
 *   <li>斜度 100 = <b>完全斜</b>（= k 原值 = 上游原版）</li>
 * </ul>
 */
public final class VSlopePreset {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        System.out.println("=== 斜度语义 + 进预设 ===");

        // 1) 斜度 0 = 同侧两角相等（纯矩形）
        float[] base = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f, 100);
        float[] rect = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f, 0);
        check("斜度 0 时同侧两角相等（rank0==rank1、rank2==rank3）→ 纯矩形",
                Math.abs(rect[0] - rect[1]) < 0.001 && Math.abs(rect[2] - rect[3]) < 0.001,
                String.format("%.2f/%.2f/%.2f/%.2f", rect[0], rect[1], rect[2], rect[3]));

        // 2) 斜度 0 仍然有梯度（rank0 != rank2）→ 拉伸保留
        check("斜度 0 仍有上下梯度（拉伸保留，不是四角同速）",
                Math.abs(rect[0] - rect[2]) > 0.1,
                String.format("rank0=%.2f rank2=%.2f", rect[0], rect[2]));

        // 3) 斜度 100 = 原值（完全斜 = 上游）
        float[] up = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        check("斜度 100 == 未经插值的原值（= 上游 k=4.5）",
                Math.abs(base[0] - up[0]) < 0.001 && Math.abs(base[3] - up[3]) < 0.001,
                String.format("%.2f/%.2f/%.2f/%.2f", base[0], base[1], base[2], base[3]));

        // 4) 单调：斜度越大，同侧差越大
        int prevDiff = -1;
        boolean mono = true;
        for (int s : new int[]{0, 25, 50, 75, 100}) {
            float[] f = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f, s);
            int diff = Math.round((f[0] - f[1]) * 100);
            if (diff < prevDiff) mono = false;
            prevDiff = diff;
        }
        check("斜度 0→100 时间侧差单调增大", mono, "末端差=" + prevDiff);

        // 5) 三个预设都是 100
        boolean all100 = true;
        StringBuilder t = new StringBuilder();
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            t.append(p.name()).append("=").append(p.slopeTicksValue()).append(" ");
            if (p.slopeTicksValue() != 100) all100 = false;
        }
        check("三个内置预设斜度都是 100", all100, t.toString().trim());

        // 6) 切预设会写 slopeTicks
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.slopeTicks = 30;
        NeovideCursorConfigurable.applyAnimPresetFields(s, CursorPresets.ANIM_PRESETS[0]);
        check("切预设把斜度拉回 100（在预设域内）", s.slopeTicks == 100,
                "切后 = " + s.slopeTicks);

        // 7) State 默认 100
        check("State 默认 slopeTicks = 100",
                new NeovideCursorSettings.State().slopeTicks == 100, "");

        System.out.println();
        System.out.printf("=== 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.exit(1); }
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}