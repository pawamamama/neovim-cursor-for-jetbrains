import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/**
 * V-SLOPE-MIGRATION：0.9.81 斜度默认值变更（82 → 100）的迁移安全性。
 *
 * <p><b>为什么必须迁移</b>：0.9.80 出厂默认是 82，0.9.81 改成 100（公式重设计）。
 * 若不迁移，老用户 State 里停在 82 而预设声明 100，
 * refreshPresetDisplay 会判定「已修改」→ 自动派生「基础 副本」——
 * 用户什么都没干，下拉里凭空多出个副本。
 *
 * <p>82 是那个版本的出厂默认，不是用户选择，所以归到 100。
 * 用户真正调过的值（0-81、83-100）必须原样保留。
 */
public final class VSlopeMigration {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        V0Env.install();
        System.out.println("=== 斜度迁移验证 ===");

        // 82（0.9.80 默认）→ 100
        NeovideCursorSettings s1 = new NeovideCursorSettings();
        NeovideCursorSettings.State o1 = new NeovideCursorSettings.State();
        o1.slopeTicks = 82;
        s1.loadState(o1);
        check("82（0.9.80 出厂默认）→ 100", s1.getState().slopeTicks == 100,
                "读回 = " + s1.getState().slopeTicks);

        // 用户真调过的值原样保留
        for (int v : new int[]{0, 30, 50, 81, 83, 100}) {
            NeovideCursorSettings sv = new NeovideCursorSettings();
            NeovideCursorSettings.State ov = new NeovideCursorSettings.State();
            ov.slopeTicks = v;
            sv.loadState(ov);
            check("用户值 " + v + " 原样保留", sv.getState().slopeTicks == v,
                    "读回 = " + sv.getState().slopeTicks);
        }

        // 旧值 -1 → 100
        NeovideCursorSettings s2 = new NeovideCursorSettings();
        NeovideCursorSettings.State o2 = new NeovideCursorSettings.State();
        o2.slopeTicks = -1;
        s2.loadState(o2);
        check("旧值 -1 → 100", s2.getState().slopeTicks == 100,
                "读回 = " + s2.getState().slopeTicks);

        System.out.println();
        System.out.printf("=== PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}