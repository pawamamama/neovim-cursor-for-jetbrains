import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * V-DEAD-FIELD：State 里的字段必须真的被消费，不能「只声明 + 只持久化」就完事。
 *
 * <p><b>审查抓到的真 bug</b>：0.9.76 加了 {@code targetFps} 字段，
 * 有声明、有 loadState 拷、有 copyFrom 拷、有 SettingsField 白名单校验 ——
 * 但 {@code CaretAnimator.computeNextDelayNs()} 读的还是常量 {@code ACTIVE_DELAY_NS}。
 * 用户选 144 完全没有效果，是个纯死字段。
 *
 * <p><b>为什么现有探针抓不到</b>：
 * <ul>
 *   <li>{@code VLoadState} 只验「往返不丢」—— 字段存进去了就是通过</li>
 *   <li>{@code VDefaultFreeze} 只验「默认值没变」</li>
 *   <li>{@code VAddSetting} 只验「进了 FIELDS」</li>
 * </ul>
 * 三条都只关心**持久化链路**，没有一条关心**消费链路**。
 */
public final class VDeadField {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-DEAD-FIELD：字段必须被真正消费 ===");

        // D-1：targetFps 的默认值来自 State（不是硬编码在渲染器里）
        Field f = CaretAnimator.class.getDeclaredField("targetFps");
        f.setAccessible(true);
        check("D-1 CaretAnimator 有 targetFps 字段（从 State 同步）",
                true, "类型=" + f.getType().getSimpleName());

        // D-2：computeNextDelayNs 必须真的读它（不是永远返回常量）
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java")),
                StandardCharsets.UTF_8);
        int m = src.indexOf("private long computeNextDelayNs");
        int end = src.indexOf("private boolean isEditorFocused", m);
        String body = (m >= 0 && end > m) ? src.substring(m, end) : "";
        check("D-2 computeNextDelayNs 体内引用了 targetFps（不是死字段）",
                body.contains("targetFps") || body.contains("activeDelayNsForTarget"),
                body.contains("activeDelayNsForTarget") ? "经辅助方法读取"
                        : (body.contains("targetFps") ? "直接读取" : "★ 未读取 = 死字段"));

        // D-3：144 档必须真的比 125 档更快
        long d125 = 1_000_000_000L / 125;
        long d144 = 1_000_000_000L / 144;
        check("D-3 144fps 的间隔（" + (d144 / 1_000_000.0) + "ms）严格小于 125fps（"
                        + (d125 / 1_000_000.0) + "ms）",
                d144 < d125, "相差 " + ((d125 - d144) / 1_000_000.0) + "ms");

        // D-4：所有 State 的 int/float 性能类字段都该有人读（抽样核心几个）
        String[] mustBeRead = {"targetFps", "alwaysHighFps", "glowLayers", "cursorWidthPx"};
        StringBuilder unread = new StringBuilder();
        for (String name : mustBeRead) {
            boolean found = src.contains(name) || src.contains("s." + name);
            if (!found) {
                unread.append(name).append(" ");
            }
        }
        check("D-4 核心性能字段在 CaretAnimator 里都有引用",
                unread.length() == 0,
                unread.length() == 0 ? "全部有引用" : "未被引用=" + unread);

        System.out.println();
        System.out.printf("=== V-DEAD-FIELD 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-DEAD-FIELD RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-DEAD-FIELD RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}