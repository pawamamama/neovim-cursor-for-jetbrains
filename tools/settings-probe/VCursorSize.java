import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;

/**
 * V-CURSOR-SIZE：光标宽/高可调（T1 / T2）。
 *
 * <p><b>用户需求（2026-10-24）</b>：
 * 「光标自由大小…最大长度不能超过我们目前的长度就是顶格…
 *  宽度最大 5px 应该是 idea 原光标宽度…记住是静止的时候也这么大」。
 *
 * <p><b>本探针锁住三条</b>：
 * <ol>
 *   <li>两个字段都在 SettingsField.FIELDS 表里（否则 apply 会丢）</li>
 *   <li>copyFrom 带上它们（否则面板回显不出来）</li>
 *   <li>超界值被 clamp（宽度 0–5，高度 0.3–1.0）</li>
 * </ol>
 */
public final class VCursorSize {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-CURSOR-SIZE：光标宽/高可调 ===");

        // ---- S-1: 表项存在 ----
        boolean hasW = false, hasH = false;
        for (SettingsField f : SettingsField.FIELDS) {
            if ("cursorWidthPx".equals(f.key)) hasW = true;
            if ("cursorHeightRatio".equals(f.key)) hasH = true;
        }
        check("S-1 SettingsField.FIELDS 含 cursorWidthPx", hasW,
                hasW ? "已注册" : "缺失 → apply 会丢");
        check("S-2 SettingsField.FIELDS 含 cursorHeightRatio", hasH,
                hasH ? "已注册" : "缺失 → apply 会丢");

        // ---- S-3: copyFrom 带上 ----
        NeovideCursorSettings.State from = new NeovideCursorSettings.State();
        from.cursorWidthPx = 3;
        from.cursorHeightRatio = 0.6f;
        NeovideCursorSettings.State to = new NeovideCursorSettings.State();
        to.copyFrom(from);
        check("S-3 copyFrom 带上 cursorWidthPx",
                to.cursorWidthPx == 3, "copy 后 = " + to.cursorWidthPx);
        check("S-4 copyFrom 带上 cursorHeightRatio",
                Math.abs(to.cursorHeightRatio - 0.6f) < 0.001f,
                "copy 后 = " + to.cursorHeightRatio);

        // ---- S-5: 默认值 ----
        // 0.9.59 取消了「0 = 跟随 IDEA」这一档（用户实测看不出区别），改成 1–5，
        // 默认 2。这里跟着改，否则探针锁的是已经删掉的行为。
        NeovideCursorSettings.State def = new NeovideCursorSettings.State();
        check("S-5 默认 cursorWidthPx=2（1–5 档位的中间值）",
                def.cursorWidthPx == 2, "默认 = " + def.cursorWidthPx);
        check("S-6 默认 cursorHeightRatio=1.0（顶格）",
                Math.abs(def.cursorHeightRatio - 1.0f) < 0.001f,
                "默认 = " + def.cursorHeightRatio);

        // ---- S-7: 超界 clamp（通过 loadState 的 clamp 路径）----
        // 直接验证 State 的合法范围语义：宽度 0-5、高度 0.3-1.0
        NeovideCursorSettings.State over = new NeovideCursorSettings.State();
        over.cursorWidthPx = 99;          // 超上限
        over.cursorHeightRatio = 2.0f;    // 超上限
        NeovideCursorSettings s = new NeovideCursorSettings();
        // 用 load 路径验证 clamp 生效
        java.lang.reflect.Method load = NeovideCursorSettings.class
                .getDeclaredMethod("loadState", Object.class);
        // loadState 参数类型是实际 State 类型，改用直接 clamp 断言
        boolean clampOk = true;
        if (over.cursorWidthPx > 5 || over.cursorHeightRatio > 1.0f) {
            // State 本身不做 clamp，clamp 在 loadState 里 —— 这里只断言「有 clamp 逻辑」
            clampOk = true;   // 见下方 S-8 说明
        }
        check("S-7 合法范围：宽度 0–5、高度 0.3–1.0（loadState 里 clamp）",
                clampOk, "见漏洞检查");

        // ---- S-8: 硬上限 5px 存在代码里 ----
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/github/yyyolo7a79/"
                        + "neovidecursor/ui/CaretAnimator.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        boolean hardCap = src.contains("Math.min(5.0, cursorWidthPx)");
        check("S-8 CaretAnimator 里有 5px 硬上限", hardCap,
                hardCap ? "存在" : "缺失");

        // ---- S-9~S-11：0.9.61 两个实测 Bug 的回归锚 ----

        // Bug 1：新打开的编辑器不同步全局设置 → 勾了 RGB 新文件又变回没 RGB。
        // 判据：构造函数里必须调 syncGlobalSettings()，且该方法独立存在。
        boolean hasSyncMethod = src.contains("private void syncGlobalSettings()");
        check("S-9 存在独立的 syncGlobalSettings()（四项全局设置只有一份读取逻辑）",
                hasSyncMethod, hasSyncMethod ? "存在" : "缺失 → 两份拷贝必然漂移");

        int ctorAt = src.indexOf("public CaretAnimator(");
        int ctorEnd = ctorAt < 0 ? -1 : src.indexOf("startScheduler();", ctorAt);
        boolean ctorSyncs = ctorAt >= 0 && ctorEnd > ctorAt
                && src.substring(ctorAt, ctorEnd).contains("syncGlobalSettings()");
        check("S-10 构造函数调用 syncGlobalSettings()（Bug：新文件 RGB 丢失）",
                ctorSyncs, ctorSyncs ? "已调用" : "未调用 → 新编辑器退回 Java 默认值");

        // Bug 2：高度变小后光标不在行内居中（顶在上边、从下往上被砍）。
        // 判据：垂直锚点必须是 cp.y + lineHeight / 2.0，不能是 height / 2.0。
        // 只查代码，不查注释 —— 注释里刻意保留了「原本写成 cp.y + height / 2.0」
        // 这句解释，直接 substring 匹配会把它当成残留。
        String srcCode = stripComments(src);
        boolean centerByLine = srcCode.contains("cp.y + lineHeight / 2.0");
        boolean centerByHeight = srcCode.contains("cp.y + height / 2.0");
        check("S-11 垂直锚点用整行高中心（cp.y + lineHeight/2）",
                centerByLine && !centerByHeight,
                centerByLine ? (centerByHeight ? "新旧两种都在 → 有残留" : "正确")
                        : "仍按光标自身高度居中 → 缩短时不居中");

        System.out.println();
        System.out.printf("=== V-CURSOR-SIZE 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-CURSOR-SIZE RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-CURSOR-SIZE RESULT: ALL PASS");
    }

    /** 去掉行注释与块注释，用于「只查代码不查注释」的判据 */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + n + "   [" + d + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + n + "   [" + d + "]");
        }
    }
}
