import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;

/**
 * 诊断：targetFps 选 144 后，能否经 FIELDS + loadState 往返保持。
 */
public final class VFpsPersist {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        V0Env.install();
        System.out.println("=== targetFps 持久化诊断 ===");

        // 1) State 默认
        NeovideCursorSettings.State def = new NeovideCursorSettings.State();
        check("1 State 默认 targetFps == 125", def.targetFps == 125, "实际=" + def.targetFps);

        // 2) FIELDS 里 targetFps 的写入器能否写 144
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        SettingsField tf = null;
        for (SettingsField f : SettingsField.FIELDS) {
            if ("targetFps".equals(f.key)) tf = f;
        }
        check("2 FIELDS 里有 targetFps", tf != null, tf == null ? "缺失" : "找到");
        if (tf != null) {
            NeovideCursorSettings.State target = new NeovideCursorSettings.State();
            s.targetFps = 144;          // 模拟 pending
            tf.copy(s, target);         // 模拟 apply 时写进 target
            check("3 FIELDS.copy 能把 144 写进 target", target.targetFps == 144,
                    "实际=" + target.targetFps);
        }

        // 3) loadState 往返
        NeovideCursorSettings svc = new NeovideCursorSettings();
        NeovideCursorSettings.State from = new NeovideCursorSettings.State();
        from.targetFps = 144;
        svc.loadState(from);
        check("4 loadState 往返保持 144", svc.getState().targetFps == 144,
                "读回=" + svc.getState().targetFps);

        // 4) differs 能否发现变化
        if (tf != null) {
            NeovideCursorSettings.State base = new NeovideCursorSettings.State();
            base.targetFps = 125;
            NeovideCursorSettings.State pend = new NeovideCursorSettings.State();
            pend.targetFps = 144;
            check("5 differs 能发现 125->144 的变化", tf.differs(pend, base),
                    "differs=" + tf.differs(pend, base));
        }

        System.out.println();
        System.out.printf("=== PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}