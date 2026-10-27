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

        // 5) 只有「纯平滑输入」有斜度，其余两个为 0（纯拉伸）
        //
        // 【0.9.87 语义变更】原断言「三个预设斜度都是 100」。
        // 用户 2026-10-25 明确要求：「只有纯平滑输入会单行倾斜，其他两个都是零只拉伸」。
        // 所以断言方向反转：不是全 100，而是只有一个非 0。
        StringBuilder t = new StringBuilder();
        int slanted = 0;
        String slantedName = "";
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            int v = p.slopeTicksValue();
            t.append(p.name()).append("=").append(v).append(" ");
            if (v != 0) {
                slanted++;
                slantedName = p.name();
            }
        }
        check("只有一个内置预设带斜度（纯平滑输入）",
                slanted == 1 && "纯平滑输入".equals(slantedName),
                t.toString().trim() + " | 带斜度的=" + slantedName);

        check("「基础」与「缎带」斜度为 0（纯拉伸、无倾斜）",
                CursorPresets.ANIM_PRESETS[0].slopeTicksValue() == 0
                        && CursorPresets.ANIM_PRESETS[1].slopeTicksValue() == 0,
                "基础=" + CursorPresets.ANIM_PRESETS[0].slopeTicksValue()
                        + " 缎带=" + CursorPresets.ANIM_PRESETS[1].slopeTicksValue());

        // 6) 切预设会写 slopeTicks（切到基础 -> 0，切到纯平滑输入 -> 100）
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.slopeTicks = 30;
        NeovideCursorConfigurable.applyAnimPresetFields(s, CursorPresets.ANIM_PRESETS[0]);
        int afterBase = s.slopeTicks;
        NeovideCursorConfigurable.applyAnimPresetFields(s, CursorPresets.ANIM_PRESETS[2]);
        int afterSmooth = s.slopeTicks;
        check("切预设真的写 slopeTicks（基础->0、纯平滑->100）",
                afterBase == 0 && afterSmooth == 100,
                "基础后=" + afterBase + " 纯平滑后=" + afterSmooth);

        // 7) State 默认 0（= 纯矩形拉伸，不斜）
        //
        // 【0.9.91 改方向：100 → 0，断言没删只改期望值（纪律二.6）】
        // 用户 2026-10-26/27 两次要求「只有纯平滑输入倾斜，其余不斜」。
        // 原断言写 100，而「基础」「缎带」预设早已声明 0 ——
        // 两者不一致正是 0.9.90「全新安装会斜」的根因（见 BRANCH-AUDIT-20261027.md）。
        int defSlope = new NeovideCursorSettings.State().slopeTicks;
        check("State 默认 slopeTicks = 0（纯矩形，不斜）",
                defSlope == 0, "实际 = " + defSlope);

        System.out.println();
        System.out.printf("=== 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.exit(1); }
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}