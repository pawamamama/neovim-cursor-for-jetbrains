import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

/**
 * V-SMOOTH-GLOBAL：输入平滑是【全局持久设置，不参与预设】。
 *
 * <p><b>决策演进（三个版本三次翻转，记清楚）</b>：
 * <ol>
 *   <li>0.9.46~0.9.66：全局持久，不随预设 <b>（正确）</b></li>
 *   <li>0.9.67：我误读了用户那句「其他持久化 比如平滑关闭」——
 *       那是「平滑持久化、默认关闭」，不是「平滑进预设」。
 *       于是错误地把 smoothTicks 加进预设并让 applyAnimPresetFields 写它。</li>
 *   <li>0.9.73：用户澄清「平滑一直不参与预设」，恢复正确行为。</li>
 * </ol>
 *
 * <p><b>本探针锁的判据</b>：切遍所有预设，{@code typingSmoothness} 一次都不能变。
 * 这是「不参与预设」最直接的机械表达。
 */
public final class VSmoothGlobal {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-SMOOTH-GLOBAL：输入平滑不参与预设 ===");

        // ---- S-1: 生产代码不再从预设写 typingSmoothness ----
        String cfgSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/github/yyyolo7a79/"
                        + "neovidecursor/ui/settings/NeovideCursorConfigurable.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        boolean writes = cfgSrc.contains("s.typingSmoothness = p.typingSmoothness()");
        check("S-1 applyAnimPresetFields 不写 typingSmoothness", !writes,
                writes ? "仍在写 → 平滑会被预设覆盖" : "已移除写入");

        // ---- S-2: 切「基础」不改用户值 ----
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.typingSmoothness = 0.9f;
        NeovideCursorConfigurable.applyAnimPresetFields(
                s, CursorPresets.ANIM_PRESETS[0]);
        check("S-2 切「基础」后 smooth 保持用户值 0.9",
                Math.abs(s.typingSmoothness - 0.9f) < 1e-4f,
                "smooth=" + s.typingSmoothness);

        // ---- S-3: 切成「纯平滑输入」也不改它 ----
        NeovideCursorConfigurable.applyAnimPresetFields(
                s, CursorPresets.ANIM_PRESETS[2]);
        check("S-3 切「纯平滑输入」后 smooth 仍保持 0.9",
                Math.abs(s.typingSmoothness - 0.9f) < 1e-4f,
                "smooth=" + s.typingSmoothness);

        // ---- S-4: State 默认值必须是 0 ----
        NeovideCursorSettings.State def = new NeovideCursorSettings.State();
        check("S-4 State 默认 typingSmoothness = 0（平滑默认关闭）",
                Math.abs(def.typingSmoothness) < 1e-4f,
                String.valueOf(def.typingSmoothness));

        // ---- S-5: 切遍所有预设，一次都不能变 ----
        NeovideCursorSettings.State s2 = new NeovideCursorSettings.State();
        s2.typingSmoothness = 0.37f;
        boolean stable = true;
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            NeovideCursorConfigurable.applyAnimPresetFields(
                    s2, CursorPresets.ANIM_PRESETS[i]);
            trace.append(s2.typingSmoothness).append(" ");
            if (Math.abs(s2.typingSmoothness - 0.37f) > 1e-4f) {
                stable = false;
            }
        }
        check("S-5 切遍全部预设后 smooth 恒为 0.37",
                stable, "轨迹 = " + trace);

        // ---- S-6: stretchRatioK 仍随预设（不能因为这次改动破坏它）----
        NeovideCursorSettings.State s3 = new NeovideCursorSettings.State();
        s3.stretchRatioK = 8.0f;
        NeovideCursorConfigurable.applyAnimPresetFields(
                s3, CursorPresets.ANIM_PRESETS[0]);
        check("S-6 stretchRatioK 仍随预设变（0.9.46 修复未破坏）",
                Math.abs(s3.stretchRatioK - 4.5f) < 0.01f,
                "切到「基础」后 k=" + s3.stretchRatioK);

        System.out.println();
        System.out.printf("=== V-SMOOTH-GLOBAL 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-SMOOTH-GLOBAL RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-SMOOTH-GLOBAL RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}