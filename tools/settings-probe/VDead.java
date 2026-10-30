
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.openapi.ui.DialogPanel;

import javax.swing.JCheckBox;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V-DEAD —— refresh-auditor（t3）：证明 bindCheckTracked 里的 afterPendingEdited 是<b>不可达死代码</b>。
 *
 * <p>推理：复选框的 <b>{@code MutableProperty.set()} 只由 DialogPanel 的 applyCallbacks 触发</b>
 * —— 注意 2026-10-29 起「点击瞬间就会写 pending」，但走的是 {@code bindCheck} 里新挂的
 * ActionListener（它直接调 setter 闭包），<b>不是</b> property 的 set()。
 * 所以本条推理的前提不变；变化的只是「pending 什么时候变」，见 {@code VReset} 的 R-9。
 * 而 super.apply() 的唯一调用点被 {@code syncingControls = true} 包住，
 * bindCheckTracked 的守卫是 {@code if (!syncingControls)}。
 *
 * <p>本探针<b>不靠读代码下结论</b>：把 applyCallbacks 套一层包装，在每条回调被调用的瞬间
 * 采样 {@code syncingForProbe()}。如果每次采样都是 true，那守卫永远为假，包装必为死代码。
 */
public final class VDead {

    private static int pass = 0, fail = 0;

    private static final List<Boolean> syncingSamples = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=== V-DEAD：bindCheckTracked 的 afterPendingEdited 是否可达 ===");
        V0Env.install();

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();

        DialogPanel panel = (DialogPanel) conf.createComponent();
        int n = instrumentApply(panel, conf);
        check("D-0 观测器挂上：能在 apply 回调执行瞬间采样 syncingControls",
                n > 0, "apply 回调 " + n + " 条");

        // 找一个「在自定义预设上、且点击会改 pending.useShadow」的复选框
        conf.reset();
        NeovideCursorSettings.State s = conf.pendingForProbe();
        s.animCustomName = "";
        s.customAnimPresets = "";
        s.useShadow = true;
        s.glowLayers = 10;
        conf.refreshPresetDisplayForProbe();
        String custom = conf.pendingForProbe().animCustomName;
        conf.apply();
        check("D-1 预备：已选中一个自定义预设", !custom.isEmpty(), "name=" + custom);

        int shadowIdx = findShadow(conf, ui.checkBoxes);
        check("D-2 定位「启用辉光」复选框", shadowIdx >= 0, "下标=" + shadowIdx);

        if (shadowIdx >= 0) {
            conf.reset();
            syncingSamples.clear();
            ui.checkBoxes.get(shadowIdx).doClick();           // 用户关辉光
            conf.apply();                                     // 点应用 → setter 在这一刻跑

            boolean anyFalse = false;
            for (Boolean b : syncingSamples) {
                if (Boolean.FALSE.equals(b)) {
                    anyFalse = true;
                    break;
                }
            }
            check("D-3 复选框 setter 执行期间 syncingControls 恒为 true → bindCheckTracked 的守卫永远为假",
                    !anyFalse,
                    "采样 " + syncingSamples.size() + " 次，其中 false 出现 "
                            + syncingSamples.stream().filter(b -> Boolean.FALSE.equals(b)).count() + " 次");

            // 直接验后果：这一次点击有没有让快照变。
            // 【断言方向】这里断言的是【修好之后】的期望：快照必须记下 useShadow=0。
            // 在未修复的版本上它必然读成 useShadow=1 —— 那正是 F-06 的症状。
            String snap = CustomPresets.get(conf.pendingForProbe().customAnimPresets, custom);
            check("D-4 快照必须记下这次复选框改动（修复前读成 useShadow=1 = F-06 症状）",
                    snap != null && snap.contains("useShadow=0"),
                    "预设=「" + custom + "」 快照=" + pick(snap, "useShadow")
                            + " 而 pending.useShadow=" + conf.pendingForProbe().useShadow);
        }

        // 对照：滑块路径在 apply 期间同样被封
        conf.reset();
        conf.pendingForProbe().animCustomName = "";
        conf.pendingForProbe().customAnimPresets = "";
        conf.pendingForProbe().glowLayers = 10;
        conf.refreshPresetDisplayForProbe();
        String cb = conf.pendingForProbe().animCustomName;
        conf.apply();
        String before = CustomPresets.get(conf.pendingForProbe().customAnimPresets, cb);
        String snapA = CustomPresets.get(conf.pendingForProbe().customAnimPresets, cb);

        conf.reset();
        ui.sliders.get(VUi.indexOfKey("glowLayers")).setValue(11);
        String afterDrag = conf.pendingForProbe().animCustomName;
        conf.apply();
        String snapB = CustomPresets.get(conf.pendingForProbe().customAnimPresets, afterDrag);
        check("D-5 对照组：滑块改的是快照里的 glowLayers，apply 后必须记住新值",
                snapB != null && snapB.contains("glowLayers=11"),
                "预设=「" + afterDrag + "」 快照=" + pick(snapB, "glowLayers")
                        + " pending.glowLayers=" + conf.pendingForProbe().glowLayers);

        System.out.println();
        System.out.printf("=== V-DEAD 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-DEAD RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-DEAD RESULT: ALL PASS");
        System.exit(0);
    }

    private static String pick(String snap, String key) {
        if (snap == null) {
            return "无快照";
        }
        for (String part : snap.split(";")) {
            if (part.startsWith(key + "=")) {
                return part;
            }
        }
        return "无 " + key;
    }

    @SuppressWarnings("unchecked")
    private static int instrumentApply(DialogPanel panel, NeovideCursorConfigurable conf) {
        // 【2026-10-29 分标签适配：回调表按面板分开存放】
        //
        // 分成 6 个标签页后，外层 DialogPanel 自己**一条** apply 回调都没有 ——
        // 所有控件的回调都在各自标签页那个 DialogPanel 的 applyCallbacks 里。
        // 平台的 apply() 会先逐个调用 integratedPanels 的 apply() 再跑自己的表
        // （javap 实证），所以「外层 + 集成面板」才是完整的一组。
        // 只读外层 → 读到 0 条 → D-0 直接红（本次实测就是这样红的）。
        int n = 0;
        List<DialogPanel> panels = new ArrayList<>();
        panels.add(panel);
        panels.addAll(panel.getIntegratedPanels());
        for (DialogPanel p : panels) {
            int k = instrumentOneApply(p, conf);
            if (k < 0) {
                return -1;
            }
            n += k;
        }
        return n;
    }

    /** 只给「一个」面板的 apply 回调套包装。原 instrumentApply 的方法体原样搬进来。 */
    @SuppressWarnings("unchecked")
    private static int instrumentOneApply(DialogPanel panel, NeovideCursorConfigurable conf) {
        try {
            Field f = DialogPanel.class.getDeclaredField("applyCallbacks");
            f.setAccessible(true);
            Map<Object, List<Object>> old = (Map<Object, List<Object>>) f.get(panel);
            Map<Object, List<Object>> next = new LinkedHashMap<>();
            int n = 0;
            for (Map.Entry<Object, List<Object>> e : old.entrySet()) {
                List<Object> wrapped = new ArrayList<>();
                for (Object cb : e.getValue()) {
                    final kotlin.jvm.functions.Function0<?> orig =
                            (kotlin.jvm.functions.Function0<?>) cb;
                    wrapped.add((kotlin.jvm.functions.Function0<kotlin.Unit>) () -> {
                        syncingSamples.add(conf.syncingForProbe());
                        return (kotlin.Unit) orig.invoke();
                    });
                    n++;
                }
                next.put(e.getKey(), wrapped);
            }
            f.set(panel, next);
            return n;
        } catch (Throwable t) {
            System.out.println("  [instrument 失败] " + t);
            return -1;
        }
    }

    private static int findShadow(NeovideCursorConfigurable conf, List<JCheckBox> boxes) {
        for (int i = 0; i < boxes.size(); i++) {
            conf.reset();
            conf.pendingForProbe().useShadow = true;
            boxes.get(i).doClick();
            conf.apply();
            boolean flipped = !conf.pendingForProbe().useShadow;
            boxes.get(i).doClick();
            conf.apply();
            if (flipped) {
                return i;
            }
        }
        return -1;
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
