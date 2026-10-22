
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.github.yyyolo7a79.neovidecursor.ui.settings.ProbeFacade;

/** Phase 0 等价性证明：同一个面板实例，经门面与经实例方法读到的必须是同一对象。 */
public final class VFacadeEquiv {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        System.out.println("=== V-FACADE-EQUIV 等价性：门面 vs 实例方法 ===");
        V0Env.install();
        VUi ui = new VUi();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        ProbeFacade f = conf;                 // 同一个对象，走接口

        check("E-1 pendingForProbe 同引用",
                f.pendingForProbe() == conf.pendingForProbe(), "");
        check("E-2 baselineForProbe 同引用",
                f.baselineForProbe() == conf.baselineForProbe(), "");
        check("E-3 syncingForProbe 同值",
                f.syncingForProbe() == conf.syncingForProbe(), "");
        check("E-4 slidersForProbe 同尺寸且逐项同引用",
                sameList(f.slidersForProbe(), conf.slidersForProbe()), "");
        check("E-5 textFieldsForProbe 同尺寸且逐项同引用",
                sameList(f.textFieldsForProbe(), conf.textFieldsForProbe()), "");
        check("E-6 labelsForProbe 同尺寸且逐项同引用",
                sameList(f.labelsForProbe(), conf.labelsForProbe()), "");
        check("E-7 sliderKeysForProbe 同内容",
                f.sliderKeysForProbe().equals(conf.sliderKeysForProbe()), "");
        check("E-8 sliderRangeForProbe(0) 同值",
                java.util.Arrays.equals(f.sliderRangeForProbe(0), conf.sliderRangeForProbe(0)), "");
        check("E-9 animPresetComboForProbe 同引用",
                f.animPresetComboForProbe() == conf.animPresetComboForProbe(), "");
        check("E-10 colorPresetComboForProbe 同引用",
                f.colorPresetComboForProbe() == conf.colorPresetComboForProbe(), "");
        check("E-11 animRestoreButtonForProbe 同引用",
                f.animRestoreButtonForProbe() == conf.animRestoreButtonForProbe(), "");
        check("E-12 colorRestoreButtonForProbe 同引用",
                f.colorRestoreButtonForProbe() == conf.colorRestoreButtonForProbe(), "");
        check("E-13 trailModeComboForProbe 同引用",
                f.trailModeComboForProbe() == conf.trailModeComboForProbe(), "");
        check("E-14 couplingHintsForProbe 逐项同引用",
                sameArr(f.couplingHintsForProbe(), conf.couplingHintsForProbe()), "");
        check("E-15 tailColorPanelForProbe 同引用",
                f.tailColorPanelForProbe() == conf.tailColorPanelForProbe(), "");
        check("E-16 shadowColorPanelForProbe 同引用",
                f.shadowColorPanelForProbe() == conf.shadowColorPanelForProbe(), "");

        // 写操作也必须等效：经门面选中预设 → 实例方法读到同样的结果
        f.pickAnimPresetForProbe(1);
        check("E-17 经门面 pickAnimPresetForProbe(1) 后实例可见",
                conf.animPresetComboForProbe().getSelectedIndex() == 1,
                "idx=" + conf.animPresetComboForProbe().getSelectedIndex());
        f.pickColorPresetForProbe(2);
        check("E-18 经门面 pickColorPresetForProbe(2) 后实例可见",
                conf.colorPresetComboForProbe().getSelectedIndex() == 2, "");
        f.refreshPresetDisplayForProbe();
        check("E-19 经门面 refreshPresetDisplayForProbe 不抛异常", true, "");

        System.out.println();
        System.out.printf("=== V-FACADE-EQUIV 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-FACADE-EQUIV RESULT: FAILED"); System.exit(1); }
        System.out.println("V-FACADE-EQUIV RESULT: ALL PASS");
        System.exit(0);
    }

    private static boolean sameList(java.util.List<?> a, java.util.List<?> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }
    private static boolean sameArr(Object[] a, Object[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }
    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
