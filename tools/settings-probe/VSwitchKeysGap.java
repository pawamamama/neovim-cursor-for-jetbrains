import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * V-SWITCH-KEYS-GAP：恢复自定义预设时，「运行环境开关」必须被跳过。
 *
 * <p><b>历史</b>：这份探针最初只断言 9 个设备级字段的字符串在
 * {@code RUNTIME_SWITCH_KEYS} 数组里。那是<b>测声明不测行为</b> ——
 * 数组写对了，但 {@code restoreSkipping} 里没真的跳过，探针照样全绿。
 * 重构前全仓<b>没有任何探针</b>调用 {@code restoreSkipping}，这条关键路径零覆盖。
 *
 * <p><b>本次重构（runtimeSwitch）同时补上了这个洞</b>，改成三条断言：
 * <ul>
 *   <li><b>A（正向）</b>：{@code runtimeSwitch == true} 的字段集合 == 预期 9 个</li>
 *   <li><b>B（反向）</b>：除 {@code animPresetBase} 指针外，没有字段被错标</li>
 *   <li><b>C（行为）</b>：造一份「毒快照」，调真实的
 *       {@link SettingsField#restoreSkipping}，断言运行环境开关一个都没被写、
 *       观感字段确实被写 —— 这才是守门的东西</li>
 * </ul>
 *
 * <p><b>输出格式</b>：{@code "  PASS  ..."} / {@code "  FAIL  ..."}（前导两空格）。
 * 门禁 {@code build-v-probes.ps1} 用 {@code ^\s+PASS } / {@code ^\s+FAIL } 统计断言数，
 * 旧格式 {@code === PASS 1，FAIL 0 ===} 两边都匹配不上，导致它在汇总表里恒为 0 0 0。
 */
public final class VSwitchKeysGap {
    private static int pass = 0, fail = 0;

    /** 按「设备 / 环境级」标准必须跳过恢复的字段（标准见 SettingsField.runtimeSwitch）。 */
    private static final String[] EXPECTED_RUNTIME = {
            // 全局开关 / 环境
            "enabled", "hideNativeCaret", "trailMode", "alwaysHighFps",
            // 0.9.87 补齐：设备级
            "targetFps", "cursorWidthPx", "cursorHeightRatio",
            "diagLogging", "multiCursorNoGlow",
    };

    private static final Set<String> EXPECTED_RUNTIME_SET =
            new LinkedHashSet<>(Arrays.asList(EXPECTED_RUNTIME));

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-SWITCH-KEYS-GAP：运行环境开关不被预设带走 ===");
        System.out.println();

        checkA();
        checkB();
        checkC();

        System.out.println();
        System.out.printf("=== V-SWITCH-GAP 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-SWITCH-GAP RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-SWITCH-GAP RESULT: ALL PASS");
    }

    /** A：带标志的字段集合必须精确等于预期 9 个。 */
    private static void checkA() {
        Set<String> actual = new LinkedHashSet<>();
        for (SettingsField f : SettingsField.FIELDS) {
            if (f.runtimeSwitch) {
                actual.add(f.key);
            }
        }

        Set<String> missing = new LinkedHashSet<>(EXPECTED_RUNTIME_SET);
        missing.removeAll(actual);
        Set<String> extra = new LinkedHashSet<>(actual);
        extra.removeAll(EXPECTED_RUNTIME_SET);

        boolean ok = missing.isEmpty() && extra.isEmpty();
        check(ok,
                "A  runtimeSwitch=true 的字段集合 == 预期 " + EXPECTED_RUNTIME_SET.size() + " 个",
                ok
                        ? "实际 " + actual.size() + " 个，完全一致"
                        : "漏标=" + missing + "  多标=" + extra);
    }

    /** B：反向断言 —— 其余进预设的字段不得被标成运行环境开关。 */
    private static void checkB() {
        Set<String> wrongly = new LinkedHashSet<>();
        int checkedCount = 0;
        for (SettingsField f : SettingsField.FIELDS) {
            if (f.runtimeSwitch) {
                continue;
            }
            if (!f.inPreset) {
                continue;               // animPresetBase 是指针，不属于任何一类
            }
            checkedCount++;
            if (EXPECTED_RUNTIME_SET.contains(f.key)) {
                // 预期该跳过却没标 → 这是反向断言要抓的缺陷
                wrongly.add(f.key);
            }
        }
        check(wrongly.isEmpty(),
                "B  预期跳过的字段无一漏标",
                wrongly.isEmpty()
                        ? "检查了 " + checkedCount + " 个 runtimeSwitch=false 的预设字段"
                        : "漏标=" + wrongly);
    }

    /** C：行为断言 —— 真的调 restoreSkipping，看它跳没跳。 */
    private static void checkC() {
        // 源状态：把 9 个运行环境开关全部改成与默认相反的「毒值」
        NeovideCursorSettings.State src = new NeovideCursorSettings.State();
        src.enabled = false;                                        // 默认 true
        src.hideNativeCaret = false;                                // 默认 true
        src.alwaysHighFps = false;                                  // 默认 true
        src.trailMode = (src.trailMode == NeovideConfig.TrailMode.SPRING)
                ? NeovideConfig.TrailMode.AFTERIMAGE
                : NeovideConfig.TrailMode.SPRING;
        src.targetFps = (src.targetFps == 144) ? 125 : 144;         // 默认 125
        src.cursorWidthPx = (src.cursorWidthPx == 3) ? 2 : 3;       // 默认 2
        src.cursorHeightRatio = (src.cursorHeightRatio == 1.0f) ? 0.9f : 1.0f;
        src.diagLogging = !src.diagLogging;                         // 默认 false
        src.multiCursorNoGlow = !src.multiCursorNoGlow;             // 默认 false
        // 观感字段也改一改，用来验证「非开关确实被写入」
        src.slopeTicks = 77;                                        // 默认 0
        src.trailSize = 0.9f;                                       // 默认 1f
        src.glowLayers = 3;                                         // 默认 8

        String snap = SettingsField.snapshot(src);

        // 目标状态：全新默认值；先记下快照前的取值
        NeovideCursorSettings.State dst = new NeovideCursorSettings.State();
        String before = SettingsField.snapshot(dst);

        int applied = SettingsField.restoreSkipping(dst, snap);
        String after = SettingsField.snapshot(dst);

        // C-1：9 个运行环境开关必须保持 dst 原值（没被写入）
        StringBuilder leaked = new StringBuilder();
        for (String key : EXPECTED_RUNTIME) {
            String was = valueOf(before, key);
            String now = valueOf(after, key);
            if (!equalsSafe(was, now)) {
                leaked.append(key).append("(").append(was)
                        .append("->").append(now).append(") ");
            }
        }
        check(leaked.length() == 0,
                "C-1 恢复预设时 9 个运行环境开关一个都没被写入",
                leaked.length() == 0 ? "全部保持原值" : "被写入=" + leaked.toString().trim());

        // C-2：观感字段必须确实被写入（防止「整份 restore 都没生效」也过）
        int changed = 0;
        StringBuilder changeDetail = new StringBuilder();
        for (String key : new String[] {"slopeTicks", "trailSize", "glowLayers"}) {
            String was = valueOf(before, key);
            String now = valueOf(after, key);
            if (!equalsSafe(was, now)) {
                changed++;
            }
            changeDetail.append(key).append('=').append(was).append("->").append(now).append(' ');
        }
        check(changed == 3,
                "C-2 观感字段确实被写入（证明 restore 整体生效）",
                changeDetail.toString().trim());

        // C-3：写入数应当等于「快照条目数 - 运行环境开关数」
        int totalEntries = snap.split(";").length;
        int expectedApplied = totalEntries - EXPECTED_RUNTIME.length;
        check(applied == expectedApplied,
                "C-3 写入数与「快照条目 - 运行开关数」一致",
                "applied=" + applied + "  期望=" + expectedApplied
                        + "  (条目=" + totalEntries + ")");
    }

    /** 从快照串里取某个 key 的值；没有返回 null。 */
    private static String valueOf(String snapshot, String key) {
        if (snapshot == null) {
            return null;
        }
        for (String entry : snapshot.split(";")) {
            String e = entry.trim();
            int eq = e.indexOf('=');
            if (eq > 0 && e.substring(0, eq).trim().equals(key)) {
                return e.substring(eq + 1);
            }
        }
        return null;
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static void check(boolean ok, String n, String d) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + n + "   [" + d + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + n + "   [" + d + "]");
        }
    }
}
