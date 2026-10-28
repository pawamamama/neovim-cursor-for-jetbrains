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

    /**
     * C：行为断言 —— 真的调 restoreSkipping，看它跳没跳。
     *
     * <p><b>2026-10-28 改为手工拼「旧格式毒快照」，不再用 {@code snapshot()} 现造。</b>
     * 原因：新写的快照里压根不再包含运行开关（用户要求「只存会生效的」），
     * 用 {@code snapshot()} 造出来的是新格式 —— 那样只能测到「新快照没问题」，
     * <b>测不到真正要紧的兼容路径</b>：用户磁盘上已经存在的旧预设串里仍然带着这 9 个 key。
     */
    private static void checkC() {
        // 旧格式毒快照：9 个运行开关（值都取「与默认相反」）+ 3 个观感字段
        String snap = "enabled=0;hideNativeCaret=0;alwaysHighFps=0;trailMode=AFTERIMAGE;"
                + "multiCursorNoGlow=1;targetFps=144;cursorWidthPx=5;cursorHeightRatio=0.5;"
                + "diagLogging=1;"
                + "slopeTicks=77;trailSize=0.9;glowLayers=3";

        NeovideCursorSettings.State dst = new NeovideCursorSettings.State();
        NeovideCursorSettings.State def = new NeovideCursorSettings.State();
        int applied = SettingsField.restoreSkipping(dst, snap);

        // C-1：9 个运行环境开关必须保持默认（毒值一个都没进来）
        StringBuilder leaked = new StringBuilder();
        for (String key : EXPECTED_RUNTIME) {
            SettingsField f = SettingsField.byKey(key);
            if (f != null && !f.encode(def).equals(f.encode(dst))) {
                leaked.append(key).append('(').append(f.encode(def))
                        .append("->").append(f.encode(dst)).append(") ");
            }
        }
        check(leaked.length() == 0,
                "C-1 旧快照里的 9 个运行环境开关一个都没被写入",
                leaked.length() == 0 ? "全部保持默认" : "被写入=" + leaked.toString().trim());

        // C-2：观感字段必须确实被写入（防止「整份 restore 都没生效」也过）
        check(dst.slopeTicks == 77
                        && Math.abs(dst.trailSize - 0.9f) < 1e-4
                        && dst.glowLayers == 3,
                "C-2 观感字段确实被写入（证明 restore 整体生效）",
                "slopeTicks=" + dst.slopeTicks + " trailSize=" + dst.trailSize
                        + " glowLayers=" + dst.glowLayers);

        // C-3：只有观感字段被计数（快照 12 条里 9 条是运行开关）
        check(applied == 3,
                "C-3 写入数只计观感字段，运行开关条目不计数",
                "applied=" + applied + "  期望=3（快照共 12 条，其中 9 条是运行开关）");
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
