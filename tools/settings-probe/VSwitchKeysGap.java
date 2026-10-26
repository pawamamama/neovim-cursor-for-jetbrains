import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/**
 * 验证代理报告的核心指控：切自定义预设时，5 个「设备级」字段会被错误写入。
 *
 * <p>判据：RUNTIME_SWITCH_KEYS 只有 4 项（enabled/hideNativeCaret/trailMode/alwaysHighFps），
 * 但按「设备/环境开关不该被预设带走」的同一标准，
 * targetFps / cursorWidthPx / cursorHeightRatio / diagLogging / multiCursorNoGlow 也该跳过。
 */
public final class VSwitchKeysGap {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== RUNTIME_SWITCH_KEYS 覆盖度检查 ===");

        java.util.Set<String> skip = new java.util.HashSet<>();
        for (String k : SettingsField.RUNTIME_SWITCH_KEYS) skip.add(k);

        System.out.println("  当前跳过清单（" + skip.size() + " 项）：" + skip);
        System.out.println();

        // 这 5 个按「设备/环境开关」标准都该跳过
        String[] shouldSkip = {
            "targetFps",          // 屏幕刷新率相关，且带白名单 setter
            "cursorWidthPx",      // State 注释明写「设备级设置」
            "cursorHeightRatio",  // 字形/字号相关
            "diagLogging",        // 排查开关，不是观感
            "multiCursorNoGlow",  // State 注释明写「不随预设变化」
        };

        System.out.println("  按「设备/环境开关」标准应跳过但没跳过的：");
        int missing = 0;
        for (String k : shouldSkip) {
            if (!skip.contains(k)) {
                System.out.println("    ★ " + k);
                missing++;
            }
        }

        if (missing == 0) {
            pass++;
            System.out.println("    无 —— 清单已完整");
        } else {
            fail++;
            System.out.println();
            System.out.println("  缺陷确认：这 " + missing + " 个字段在切自定义预设时会被写回，");
            System.out.println("  而它们的语义是「设备级 / 不该随预设变化」。");
        }

        System.out.println();
        System.out.printf("=== PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}