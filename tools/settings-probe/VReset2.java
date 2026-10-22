
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JCheckBox;
import java.lang.reflect.Field;
import java.util.List;

/**
 * V-RESET2 —— refresh-auditor（t3）第二支：super.reset() 进 syncAll 之后的两条新因果。
 *
 * <p>两支探针分工：
 * <ul>
 *   <li>{@code VReset}：重复轮数、嵌套深度、apply 幂等（量的部分）；</li>
 *   <li>{@code VReset2}：<b>「控件状态本来属于用户、却被 super.reset() 静默覆盖」</b>这一类新回归，
 *       以及 bindCheckTracked 的 afterPendingEdited 到底有没有真的跑过。</li>
 * </ul>
 */
public final class VReset2 {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-RESET2：super.reset() 覆盖用户控件态 / bindCheckTracked 是否真的生效 ===");
        V0Env.install();

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();

        int diagIdx = findPaintDiag(conf, ui.checkBoxes);
        check("A-0 定位「性能诊断」复选框", diagIdx >= 0, "下标=" + diagIdx);

        // ---------------------------------------------------------------- A-1
        System.out.println();
        System.out.println("--- A-1 勾选「性能诊断」后切预设：勾选态会不会被 super.reset() 静默吃掉 ---");
        if (diagIdx >= 0) {
            JCheckBox diag = ui.checkBoxes.get(diagIdx);
            conf.reset();
            boolean initial = diag.isSelected();
            diag.doClick();                                   // 用户勾上
            boolean afterClick = diag.isSelected();
            conf.pickAnimPresetForProbe(1);                   // 用户接着换了个预设（没点应用）
            boolean afterPick = diag.isSelected();
            check("A-1 用户的勾选在「换预设」后必须保留（性能诊断不属于任何预设）",
                    afterPick == afterClick,
                    "初始=" + initial + " 勾选后=" + afterClick + " 换预设后=" + afterPick
                            + " → " + (afterPick == afterClick ? "保留" : "被静默取消"));
        }

        // ---------------------------------------------------------------- A-2
        System.out.println();
        System.out.println("--- A-2 bindCheckTracked 的 afterPendingEdited 在 apply 路径上是否被屏蔽 ---");
        conf.reset();
        NeovideCursorSettings.State s = conf.pendingForProbe();
        s.animCustomName = "";
        s.customAnimPresets = "";
        s.animPresetBase = 0;
        s.useShadow = true;
        conf.apply();                                          // 定稿到一个「干净的内置预设上」

        int shadowIdx = findShadow(conf, ui.checkBoxes);
        check("A-2 定位「启用辉光」复选框", shadowIdx >= 0, "下标=" + shadowIdx);
        if (shadowIdx >= 0 && diagIdx >= 0) {
            conf.reset();
            NeovideCursorSettings.State p = conf.pendingForProbe();
            boolean nameBefore = !p.animCustomName.isEmpty();
            String tableBefore = p.customAnimPresets;
            ui.checkBoxes.get(shadowIdx).doClick();             // 用户在内置预设上关辉光
            conf.apply();                                       // 点「应用」
            String nameAfter = conf.pendingForProbe().animCustomName;
            String tableAfter = conf.pendingForProbe().customAnimPresets;
            check("A-3 在内置预设上改一个【预设域字段】后必须派生出落脚点（与滑块行为一致）",
                    !nameAfter.isEmpty() || !tableBefore.equals(tableAfter),
                    "应用后 animCustomName=「" + nameAfter + "」 派生表 "
                            + CustomPresets.parse(tableBefore).size() + " → "
                            + CustomPresets.parse(tableAfter).size() + " 条"
                            + "（nameBefore=" + nameBefore + "）");
        }

        // ---------------------------------------------------------------- A-4
        System.out.println();
        System.out.println("--- A-4 对照组：滑块改 6 字段中的一项，行为是否一致 ---");
        conf.reset();
        conf.pendingForProbe().animCustomName = "";
        conf.pendingForProbe().customAnimPresets = "";
        conf.pendingForProbe().animPresetBase = 0;
        conf.apply();
        conf.reset();
        String before4 = conf.pendingForProbe().animCustomName;
        ui.sliders.get(VUi.indexOfKey("glowLayers")).setValue(11);   // 模拟拖动
        String after4 = conf.pendingForProbe().animCustomName;
        check("A-4 滑块拖动后立刻派生落脚点（对照组，用于证明复选框路径确实缺失）",
                before4.isEmpty() && !after4.isEmpty(),
                "拖动前「" + before4 + "」→ 拖动后「" + after4 + "」");

        // ---------------------------------------------------------------- A-5
        System.out.println();
        System.out.println("--- A-5 控件回灌与用户输入的先后：键入未应用的文字会不会被冲掉 ---");
        conf.reset();
        int idxTyping = VUi.indexOfKey("glowLayers");
        javax.swing.JTextField tf = (javax.swing.JTextField) ui.textFields.get(idxTyping);
        tf.setText("7");                                        // 用户键入但没点应用
        String typed = tf.getText();
        conf.pickAnimPresetForProbe(0);                          // 顺手切个预设
        String afterPick5 = tf.getText();
        check("A-5 键入值在切预设后被 super.reset() 覆盖（应覆盖：预设切换是显式操作）",
                !typed.equals(afterPick5),
                "键入=" + typed + " 切预设后=" + afterPick5 + "（这是预期行为，仅记录）");

        // ---------------------------------------------------------------- A-6
        System.out.println();
        System.out.println("--- A-6 复选框不回写 pending 的前提下，apply 的判脏是否仍能点亮 ---");
        conf.reset();
        conf.pendingForProbe().useShadow = true;
        conf.apply();
        conf.reset();
        boolean modBefore = conf.isModified();
        int si = findShadow(conf, ui.checkBoxes);
        if (si >= 0) {
            ui.checkBoxes.get(si).doClick();
            boolean modAfter = conf.isModified();
            check("A-6 点复选框后「应用」按钮必须点亮",
                    !modBefore && modAfter, "isModified " + modBefore + " -> " + modAfter);
        } else {
            check("A-6 定位辉光复选框", false, "未找到");
        }

        System.out.println();
        System.out.printf("=== V-RESET2 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-RESET2 RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-RESET2 RESULT: ALL PASS");
        System.exit(0);
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

    private static int findPaintDiag(NeovideCursorConfigurable conf, List<JCheckBox> boxes) {
        for (int i = 0; i < boxes.size(); i++) {
            conf.reset();
            String before = digest(conf);
            boxes.get(i).doClick();
            conf.apply();
            String after = digest(conf);
            boxes.get(i).doClick();
            conf.apply();
            if (before.equals(after)) {
                return i;
            }
        }
        return -1;
    }

    private static String digest(NeovideCursorConfigurable conf) {
        NeovideCursorSettings.State s = conf.pendingForProbe();
        return s.enabled + "|" + s.trailMode + "|" + s.hideNativeCaret + "|" + s.alwaysHighFps
                + "|" + s.overlayFadeOut + "|" + s.useShadow + "|" + s.glowLayers + "|" + s.trailSize
                + "|" + s.customAnimPresets + "|" + s.animCustomName;
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
