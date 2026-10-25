import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

/**
 * V-SMOOTH-PRESET：输入平滑【随预设】。
 *
 * <p><b>决策变更（2026-10-25，用户）</b>：
 * 原决策「平滑做成全局不随预设」（2026-10-24）；
 * 现改为「除了我指定的持久化字段，其他都要有默认值填入预设」——
 * 用户指定的持久化字段是：rgbGradient / cursorWidthPx / cursorHeightRatio /
 * 总开关那几项。**typingSmoothness 不在其中**，所以它回到预设域。
 *
 * <p><b>为什么必须回预设域（实测依据）</b>：smooth 拖到 100 时，
 * 四角系数被压成 [1, 0.94, 0.87, 0.83]，梯度只剩 1.20×，
 * 切任何预设都是规整矩形 —— 预设等于失效一半。预设管不了它，
 * 就等于用户可以靠一个滑块把所有预设"抹平"。
 *
 * <p>但默认值仍是 0（关闭），符合用户「其他持久化 比如平滑关闭」的要求。
 *
 * <p><b>本探针替代了原来的 VSmoothGlobal</b>。旧探针锁的是旧决策
 * （断言 AnimPreset 没有该字段），行为变更后它必然红 —— 那是探针过时，
 * 不是代码坏了。按纪律：行为变更时改探针，不是删探针。
 */
public final class VSmoothGlobal {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-SMOOTH-PRESET：输入平滑随预设（0.9.67 新决策）===");

        // ---- S-1: AnimPreset 必须有 typingSmoothness() ----
        boolean hasMethod = false;
        try {
            CursorPresets.AnimPreset.class.getMethod("typingSmoothness");
            hasMethod = true;
        } catch (NoSuchMethodException ignored) {
        }
        check("S-1 AnimPreset 有 typingSmoothness()（0.9.67 补入预设域）",
                hasMethod, hasMethod ? "已存在" : "缺失");

        // ---- S-2: 切预设会覆盖用户的平滑值 ----
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.typingSmoothness = 0.9f;                  // 用户调到 90%
        NeovideCursorConfigurable.applyAnimPresetFields(
                s, CursorPresets.ANIM_PRESETS[0]);  // 基础
        check("S-2 切「基础」后 smooth 被预设覆盖成 0（默认关闭）",
                Math.abs(s.typingSmoothness - 0f) < 1e-4f,
                "smooth=" + s.typingSmoothness);

        // ---- S-3: 「纯平滑输入」必须带自己的特征值（非 0）----
        NeovideCursorConfigurable.applyAnimPresetFields(
                s, CursorPresets.ANIM_PRESETS[2]);
        check("S-3 切「纯平滑输入」后 smooth 变成该预设的特征值（> 0）",
                s.typingSmoothness > 0f, "smooth=" + s.typingSmoothness);

        // ---- S-4: 默认值必须是 0（用户要求"平滑关闭"）----
        check("S-4 「基础」预设的 smooth 默认值为 0（用户要求：平滑关闭）",
                Math.abs(CursorPresets.ANIM_PRESETS[0].typingSmoothness()) < 1e-4f,
                String.valueOf(CursorPresets.ANIM_PRESETS[0].typingSmoothness()));

        // ---- S-5: 切回「基础」能恢复 0（可逆，不是单向覆盖）----
        NeovideCursorConfigurable.applyAnimPresetFields(
                s, CursorPresets.ANIM_PRESETS[0]);
        check("S-5 从「纯平滑输入」切回「基础」时 smooth 回到 0（可逆）",
                Math.abs(s.typingSmoothness) < 1e-4f, "smooth=" + s.typingSmoothness);

        // ---- S-6: stretchRatioK 仍随预设变（不能破坏 0.9.46 的修复）----
        NeovideCursorSettings.State s2 = new NeovideCursorSettings.State();
        s2.stretchRatioK = 8.0f;
        NeovideCursorConfigurable.applyAnimPresetFields(
                s2, CursorPresets.ANIM_PRESETS[0]);
        check("S-6 stretchRatioK 仍随预设变（0.9.46 修复未破坏）",
                Math.abs(s2.stretchRatioK - 4.5f) < 0.01f,
                "切到「基础」后 k=" + s2.stretchRatioK);

        System.out.println();
        System.out.printf("=== V-SMOOTH-PRESET 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-SMOOTH-PRESET RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-SMOOTH-PRESET RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
