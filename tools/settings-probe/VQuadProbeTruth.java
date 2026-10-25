import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;

/**
 * V-P4-FIXES：代码审查 P4 三处 medium 的固定探针。
 *
 * <p><b>M-1 HEX 颜色必须校验</b>：{@code SettingsField.decode} 的 HEX 分支原来完全不校验，
 * 手改坏的自定义预设会被原样写进 State → {@code NeovideColors.parse} 解析失败返回黑色
 * → 光标变黑，而 decode 返回 true（声称成功）。
 *
 * <p><b>M-2 探针不能读死数据</b>：{@code CaretTrailPanel.cornersForProbe()} 原来读
 * {@code polyX/polyY}，而多光标重构后那对数组恒为 0（生产代码全走 addQuad）。
 * 探针读到 8 个 0 却验证通过 —— 假的安全感。现在改读真正的 quads 存储。
 *
 * <p><b>M-3 零分配</b>：{@code updateCorners} 一度用 new double[] 中转，
 * 与本类「零分配热路径」的承诺矛盾。
 */
public final class VQuadProbeTruth {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-P4-FIXES：审查 medium 项锁定 ===");

        // ---------- M-1：HEX 校验 ----------
        check("M-1a 合法 #RRGGBB 通过", SettingsField.isValidHexColor("#FFC0CB"), "");
        check("M-1b 合法 #AARRGGBB 通过", SettingsField.isValidHexColor("#80FFC0CB"), "");
        check("M-1c 小写也通过", SettingsField.isValidHexColor("#ffc0cb"), "");
        check("M-1d 无 # 前缀被拒", !SettingsField.isValidHexColor("FFC0CB"), "");
        check("M-1e 长度不对被拒", !SettingsField.isValidHexColor("#FFC0C"), "");
        check("M-1f 非十六进制被拒（防光标变黑）", !SettingsField.isValidHexColor("not-a-color"), "");
        check("M-1g null 被拒", !SettingsField.isValidHexColor(null), "");

        // ---------- M-2：cornersForProbe 必须反映真实 quad ----------
        com.github.yyyolo7a79.neovidecursor.core.NeovideConfig cfg =
                new com.github.yyyolo7a79.neovidecursor.core.NeovideConfig();
        com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel panel =
                new com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel(cfg);
        panel.beginQuads();
        panel.addQuad(new double[]{10, 12, 12, 10},
                new double[]{20, 20, 52, 52}, 0.5f);
        double[] c0 = panel.cornersForProbe(0);
        boolean quadOk = c0 != null && c0.length == 8
                && c0[0] == 10 && c0[1] == 20 && c0[6] == 10 && c0[7] == 52;
        check("M-2a cornersForProbe(0) 返回真实角点（不是恒 0 的死数据）",
                quadOk, quadOk ? "[10,20]...[10,52]" : (c0 == null ? "null" : java.util.Arrays.toString(c0)));
        check("M-2b 越界下标返回 null（不伪造数据）", panel.cornersForProbe(99) == null, "");
        check("M-2c 无 quad 时 offsetDirectionsForProbe 返回 null",
                new com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel(cfg)
                        .offsetDirectionsForProbe() == null, "");

        // ---------- M-3：源码里不该再有 new double[] 中转 ----------
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/github/yyyolo7a79/"
                        + "neovidecursor/ui/CaretTrailPanel.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        int ucStart = src.indexOf("public void updateCorners");
        int ucEnd = src.indexOf("public void beginQuads", ucStart);
        String ucBody = (ucStart >= 0 && ucEnd > ucStart) ? src.substring(ucStart, ucEnd) : "";
        // 【必须剥离注释再判断】注释里刻意写了「为什么不用 new double[]{...}」，
        // 直接 substring 匹配会把这句解释当成"仍有分配"（探针第一版就这样误报）。
        String ucCode = stripComments(ucBody);
        check("M-3 updateCorners 里没有 new double[] 中转（零分配）",
                !ucCode.contains("new double[]"),
                ucCode.contains("new double[]") ? "仍有分配" : "已改用复用数组");
        check("M-3b polyX/polyY 中转数组已删除",
                !src.contains("private final double[] polyX"),
                src.contains("private final double[] polyX") ? "仍存在" : "已删除");

        System.out.println();
        System.out.printf("=== V-P4-FIXES 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-P4-FIXES RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-P4-FIXES RESULT: ALL PASS");
    }

    /** 去掉行注释与块注释 —— 只保留真正的代码 */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void check(String n, boolean ok, String d) {
        String tail = d.isEmpty() ? "" : "   [" + d + "]";
        if (ok) { pass++; System.out.println("  PASS  " + n + tail); }
        else { fail++; System.out.println("  FAIL  " + n + tail); }
    }
}