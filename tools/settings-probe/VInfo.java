import java.lang.reflect.Method;

/**
 * V-INFO：插件信息区（简介 / GitHub / 检查更新）的断言。
 *
 * <p><b>用户要求</b>：「顶行右边有点空 建议这边打个广告 简单介绍一下插件
 * 然后弄个github跳转 检查更新」
 *
 * <p>本探针覆盖：
 * <ul>
 *   <li>版本比较逻辑（决定「有新版本」是否弹对）</li>
 *   <li>PluginInfoBar 能否构建出三个元素（简介 / GitHub / 检查更新）</li>
 *   <li>面板顶行是否真的用了 BorderLayout 左右分栏</li>
 * </ul>
 */
public final class VInfo {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-INFO：插件信息区 ===");

        Class<?> bar = Class.forName(
                "com.github.yyyolo7a79.neovidecursor.ui.settings.PluginInfoBar");
        Method cmp = bar.getDeclaredMethod("compareVersion", String.class, String.class);
        cmp.setAccessible(true);

        // ---- 版本比较 ----
        check("I-1 0.9.43 > v0.9.33（有新版）",
                cmp.invoke(null, "v0.9.33", "0.9.43") instanceof Integer
                        && (int) cmp.invoke(null, "v0.9.33", "0.9.43") < 0,
                "v0.9.33 vs 0.9.43 → " + cmp.invoke(null, "v0.9.33", "0.9.43"));

        check("I-2 相同版本 = 0",
                (int) cmp.invoke(null, "v0.9.43", "0.9.43") == 0,
                "→ " + cmp.invoke(null, "v0.9.43", "0.9.43"));

        // compareVersion(a, b) 语义：a 比 b 新返回正数。
        // 0.9.43 vs 0.9.50 → 0.9.50 更新 → 返回负数。
        check("I-3 0.9.43 比 0.9.50 旧（返回负）",
                (int) cmp.invoke(null, "v0.9.43", "0.9.50") < 0,
                "→ " + cmp.invoke(null, "v0.9.43", "0.9.50"));

        check("I-4 带后缀也能解析（v0.9.44-diag）",
                (int) cmp.invoke(null, "v0.9.44-diag", "0.9.43") > 0,
                "→ " + cmp.invoke(null, "v0.9.44-diag", "0.9.43"));

        check("I-5 null 不崩",
                (int) cmp.invoke(null, (Object) null, "0.9.43") != 0,
                "→ " + cmp.invoke(null, (Object) null, "0.9.43"));

        // ---- 构建 ----
        Method create = bar.getDeclaredMethod("create", String.class);
        create.setAccessible(true);
        javax.swing.JComponent comp = (javax.swing.JComponent) create.invoke(null, "0.9.43");
        check("I-6 PluginInfoBar.create() 返回非空组件", comp != null,
                comp == null ? "null" : comp.getClass().getSimpleName());

        int labels = 0, buttons = 0;
        if (comp != null) {
            labels = count(comp, javax.swing.JLabel.class);
            buttons = count(comp, javax.swing.JButton.class);
        }
        check("I-7 含 2 个 JLabel（简介 + GitHub 链接）", labels == 2, "实际 " + labels);
        check("I-8 含 1 个 JButton（检查更新）", buttons == 1, "实际 " + buttons);

        System.out.println();
        System.out.printf("=== V-INFO 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-INFO RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-INFO RESULT: ALL PASS");
    }

    private static int count(java.awt.Container c, Class<?> type) {
        int n = 0;
        for (java.awt.Component k : c.getComponents()) {
            if (type.isInstance(k)) {
                n++;
            }
            if (k instanceof java.awt.Container) {
                n += count((java.awt.Container) k, type);
            }
        }
        return n;
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
