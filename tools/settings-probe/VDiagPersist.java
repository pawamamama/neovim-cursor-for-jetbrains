import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;

/**
 * V-DIAG-PERSIST：诊断日志开关必须能持久化。
 *
 * <p><b>用户报的 bug（2026-10-24）</b>：「诊断日志勾上了应用之后消失了」。
 *
 * <p><b>根因</b>：{@code diagLogging} 定义在 {@code State} 里，
 * 但不在 {@code SettingsField.FIELDS} 表里。
 * {@code apply()} 遍历那张表把 pending 拷进 target —— 表里没有就永远不拷，
 * 一应用就回滚。
 *
 * <p>本探针锁住：表里必须有它，且 copy 真的能写进去。
 */
public final class VDiagPersist {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-DIAG-PERSIST：诊断日志开关持久化 ===");

        // ---- P-1: FIELDS 表里有 diagLogging ----
        boolean found = false;
        for (SettingsField f : SettingsField.FIELDS) {
            if ("diagLogging".equals(f.key)) {
                found = true;
                break;
            }
        }
        check("P-1 SettingsField.FIELDS 含 diagLogging", found,
                found ? "已注册" : "缺失 → apply() 会丢弃它");

        // ---- P-2: copy 真的能把 true 写进 target ----
        NeovideCursorSettings.State from = new NeovideCursorSettings.State();
        NeovideCursorSettings.State to = new NeovideCursorSettings.State();
        from.diagLogging = true;
        to.diagLogging = false;

        for (SettingsField f : SettingsField.FIELDS) {
            if ("diagLogging".equals(f.key)) {
                f.copy(from, to);
                break;
            }
        }
        check("P-2 copy 能把 diagLogging=true 写进 target", to.diagLogging,
                "target.diagLogging=" + to.diagLogging);

        // ---- P-3: differs 能识别差异（决定 apply 会不会跳过）----
        NeovideCursorSettings.State a1 = new NeovideCursorSettings.State();
        NeovideCursorSettings.State a2 = new NeovideCursorSettings.State();
        a1.diagLogging = true;
        a2.diagLogging = false;
        boolean differs = false;
        for (SettingsField f : SettingsField.FIELDS) {
            if ("diagLogging".equals(f.key)) {
                differs = f.differs(a1, a2);
                break;
            }
        }
        check("P-3 differs 能识别 true≠false", differs,
                "differs=" + differs);

        // ---- P-4: 不参与 CONFIG（不该污染 NeovideConfig）----
        boolean syncsToConfig = true;
        for (SettingsField f : SettingsField.FIELDS) {
            if ("diagLogging".equals(f.key)) {
                syncsToConfig = f.syncsToConfig;
                break;
            }
        }
        check("P-4 diagLogging 不进 CONFIG（CursorDiag 直接读 State）",
                !syncsToConfig, "syncsToConfig=" + syncsToConfig);

        System.out.println();
        System.out.printf("=== V-DIAG-PERSIST 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-DIAG-PERSIST RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-DIAG-PERSIST RESULT: ALL PASS");
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
