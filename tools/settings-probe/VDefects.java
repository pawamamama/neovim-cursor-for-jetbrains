import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import java.util.List;

/**
 * verifier 自建（t4）：4 个缺陷里属于「设置面板」的 3 个（缺陷 1/2/3）+ V11。
 *
 * <p><b>不引用实现者的探针与数字</b>：控件全部由 {@link VUi} 遍历组件树定位，
 * 断言的理由写在每一条里。
 *
 * <p>退出码 0 = 全 PASS。
 */
public final class VDefects {

    private static int pass = 0;
    private static int fail = 0;

    private static NeovideCursorSettings svc;
    private static NeovideCursorConfigurable conf;
    private static VUi ui;

    public static void main(String[] args) {
        System.out.println("=== V-DEFECTS：缺陷 1（滑块跟手）/ 缺陷 2（刻度挤压）/ 缺陷 3（Tools 覆盖）/ V11 ===");
        V0Env.install();
        svc = V0Env.svc;

        conf = new NeovideCursorConfigurable();
        ui = VUi.build(conf);

        check("D0  面板建成且找到 11 个滑块（M4 起含输入平滑）", ui.sliders.size() == 11,
                "sliders=" + ui.sliders.size());
        check("D0b 每个滑块都有紧跟的输入框与数值标签",
                ui.textFields.size() == 10 && ui.valueLabels.size() == 10
                        && ui.textFields.stream().allMatch(t -> t != null)
                        && ui.valueLabels.stream().allMatch(l -> l != null),
                "tf=" + ui.textFields.size() + " labels=" + ui.valueLabels.size());

        defect1();
        defect2();
        defect3();
        v11();

        System.out.println();
        System.out.printf("=== V-DEFECTS 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-DEFECTS RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-DEFECTS RESULT: ALL PASS");
        System.exit(0);
    }

    // ---------------------------------------------------------------- 缺陷 1

    /**
     * 缺陷 1：滑块拖动时右侧数值标签必须实时跟随。
     * <p>驱动方式与用户一致：直接 setValue，然后读标签文本。修复前（0.9.4）JSlider 上没有
     * 自己的 ChangeListener（bindValue 只写 pending），标签不会变。
     */
    private static void defect1() {
        System.out.println();
        System.out.println("--- 缺陷 1：滑块 → 标签实时跟随 ---");
        int followed = 0;
        int singleListener = 0;
        for (int i = 0; i < ui.sliders.size(); i++) {
            JSlider s = ui.sliders.get(i);
            JLabel l = ui.valueLabels.get(i);
            int n = s.getChangeListeners().length;
            if (n == 1) {
                singleListener++;
            }
            // 目标值必须与当前值不同（否则「标签没变」是正确行为，会假报 FAIL）
            int cur = s.getValue();
            int target = cur != s.getMaximum() ? s.getMaximum() : s.getMinimum();
            String before = l.getText();
            s.setValue(target);
            String after = l.getText();
            boolean moved = after != null && !after.equals(before);
            // 再拖到另一端的对称位置，确认是双向跟随而不是只跳一次
            int step = Math.max(1, stepOf(i));
            int mid = s.getMinimum() + ((s.getMaximum() - s.getMinimum()) / 2 / step) * step;
            if (mid == target) {
                mid = s.getMinimum();
            }
            String midBefore = l.getText();
            s.setValue(mid);
            String midAfter = l.getText();
            boolean moved2 = midAfter != null && !midAfter.equals(midBefore);
            if (moved && moved2) {
                followed++;
            }
            System.out.println("    " + (moved && moved2 ? "PASS " : "FAIL ")
                    + String.format("%-22s", VUi.nameOf(i))
                    + " listeners=" + n + "  max:「" + before + "」→「" + after + "」"
                    + "  mid:「" + midBefore + "」→「" + midAfter + "」");
        }
        check("D1-1 10/10 滑块拖动时数值标签跟随（两次不同目标都跟随）", followed == 10,
                followed + "/10");
        check("D1-2 每个滑块恰好 1 个 ChangeListener（未受 showValueHint 污染）",
                singleListener == 10, singleListener + "/10");
    }

    private static int stepOf(int i) {
        JSlider s = ui.sliders.get(i);
        // 用 labelTable 无法直接拿步长，这里用已知的 10 档规格（与 max/min 交叉核对过）
        int[] steps = {50, 25, 5, 5, 1, 5, 5, 5, 1, 1};
        if (i < steps.length) {
            return steps[i];
        }
        return Math.max(1, (s.getMaximum() - s.getMinimum()) / 10);
    }

    // ---------------------------------------------------------------- 缺陷 2

    /**
     * 缺陷 2：刻度数字挤压。
     * <p>修复前 labelTable 条目数 = (max-min)/majorSpacing + 1（51/101/201 条），全挤成一团。
     * 判据：条目数 ∈ [2,3]，且 paintLabels=true（labelTable 不会自动打开它）、paintTicks=false。
     */
    private static void defect2() {
        System.out.println();
        System.out.println("--- 缺陷 2：刻度标签条目数 ≤ 3 ---");
        int okCount = 0;
        for (int i = 0; i < ui.sliders.size(); i++) {
            JSlider s = ui.sliders.get(i);
            int n = s.getLabelTable() == null ? 0 : s.getLabelTable().size();
            boolean ok = n >= 2 && n <= 3 && s.getPaintLabels() && !s.getPaintTicks() && s.getSnapToTicks();
            if (ok) {
                okCount++;
            }
            System.out.println("    " + (ok ? "PASS " : "FAIL ")
                    + String.format("%-22s", VUi.nameOf(i))
                    + " labelTable=" + n + " paintLabels=" + s.getPaintLabels()
                    + " paintTicks=" + s.getPaintTicks() + " snap=" + s.getSnapToTicks());
        }
        check("D2-1 10/10 滑块：labelTable ∈ [2,3] 且 paintLabels=true / paintTicks=false / snap=true",
                okCount == 10, okCount + "/10");
    }

    // ---------------------------------------------------------------- 缺陷 3

    /**
     * 缺陷 3：面板打开期间 Tools 菜单的改动不得被面板的 apply() 覆盖。
     * <p>复现用户实测路径：state 已知 → reset()（= 打开面板）→ Tools 改字段 → apply()（= 点 OK）。
     */
    private static void defect3() {
        System.out.println();
        System.out.println("--- 缺陷 3：Tools 改动不被面板覆盖 ---");
        NeovideCursorSettings.State st = svc.getState();

        st.enabled = true;
        st.trailMode = com.github.yyyolo7a79.neovidecursor.core.NeovideConfig.TrailMode.SPRING;
        st.hideNativeCaret = true;
        st.alwaysHighFps = true;
        st.overlayFadeOut = true;
        st.cursorDisappearDelay = 50;
        st.cursorFadeOutDuration = 0.075f;
        st.tailColor = "#FFC0CB";
        st.shadowColor = "#FFC0CB";
        st.tailOpacity = 1.0f;
        st.useShadow = true;
        st.glowOpacity = 0.55f;
        st.glowLayers = 8;
        st.shadowBlurFactor = 0.2f;
        st.trailSize = 1.0f;
        st.stretchRatioK = 4.5f;
        st.animationLength = 0.1f;
        st.shortAnimationLength = 0.11f;

        conf.reset();                                   // 打开面板
        st.overlayFadeOut = false;                      // Tools：关掉辉光淡出
        st.alwaysHighFps = false;                       // Tools：关掉常驻刷新
        conf.apply();                                   // 点 OK（用户没碰任何控件）
        check("D3-1 Tools 关掉的 overlayFadeOut 在面板 apply 后仍为 false",
                !svc.getState().overlayFadeOut, "overlayFadeOut=" + svc.getState().overlayFadeOut);
        check("D3-2 Tools 关掉的 alwaysHighFps 在面板 apply 后仍为 false",
                !svc.getState().alwaysHighFps, "alwaysHighFps=" + svc.getState().alwaysHighFps);

        // 用户拖过的字段必须以用户为准（不能反向变成「谁都不写」）
        conf.reset();
        ui.sliders.get(4).setValue(4);                  // 辉光层数 8 → 4
        st.overlayFadeOut = false;                      // Tools 同时改另一个字段
        conf.apply();
        check("D3-3 用户拖过的 glowLayers 生效（=4）", svc.getState().glowLayers == 4,
                "glowLayers=" + svc.getState().glowLayers);
        check("D3-4 用户没碰的 overlayFadeOut 保住 Tools 的 false",
                !svc.getState().overlayFadeOut, "overlayFadeOut=" + svc.getState().overlayFadeOut);

        // 连续两次 apply：第二次不得把第一次的值判成「非脏」而丢弃（baseline 必须前移）
        conf.reset();
        ui.sliders.get(4).setValue(6);
        conf.apply();
        conf.apply();
        check("D3-5 连续两次 apply 后 glowLayers 仍为 6", svc.getState().glowLayers == 6,
                "glowLayers=" + svc.getState().glowLayers);
    }

    // ---------------------------------------------------------------- V11

    /**
     * V11（契约 §11.1）：isModified() 的双向正确性 —— 4 条断言必须同时成立。
     */
    private static void v11() {
        System.out.println();
        System.out.println("--- V11：isModified() 双向正确性 ---");
        conf.reset();
        ui = VUi.build(conf);          // 重新定位（reset 不重建组件，但保证引用一致）
        check("V11-0 reset() 后 isModified()=false", !conf.isModified(), String.valueOf(conf.isModified()));

        conf.reset();
        ui.sliders.get(0).setValue(250);
        boolean m1 = conf.isModified();
        check("V11-1 【防 B-1】拖滑块后 isModified()=true", m1, String.valueOf(m1));

        conf.reset();
        VUi ui2 = VUi.build(conf);
        JCheckBox box = ui2.checkBoxes.isEmpty() ? null : ui2.checkBoxes.get(0);
        boolean m2 = false;
        if (box != null) {
            box.doClick();
            m2 = conf.isModified();
        }
        check("V11-2 【防 B-1 回归】只点复选框后 isModified()=true", m2,
                "box=" + (box == null ? "null" : "ok") + " isModified=" + m2);

        // 断言 3（防 B-2）：state 里是精确值 333（不在 50 的网格上），滑块只能显示 350，
        // 但 isModified() 必须回落 false —— 否则 OK 按钮永亮。
        NeovideCursorSettings.State st = svc.getState();
        st.cursorDisappearDelay = 333;
        conf.reset();
        VUi ui3 = VUi.build(conf);
        int shown = ui3.sliders.get(0).getValue();
        String text = ui3.textFields.get(0) == null ? "" : ui3.textFields.get(0).getText();
        String label = ui3.valueLabels.get(0) == null ? "" : ui3.valueLabels.get(0).getText();
        boolean m3 = conf.isModified();
        check("V11-3a 精确值 333 → 滑块吸附显示 350", shown == 350, "slider=" + shown);
        check("V11-3b 精确值 333 → 输入框/标签显示精确值（不篡改用户数据）",
                "333".equals(text) && label != null && label.startsWith("333"),
                "tf=\"" + text + "\" label=\"" + label + "\"");
        check("V11-3c 【防 B-2】精确值 333 / 刻度 350 时 isModified()=false", !m3,
                String.valueOf(m3));

        conf.apply();
        check("V11-4a apply() 后 isModified()=false", !conf.isModified(), String.valueOf(conf.isModified()));
        check("V11-4b apply() 把精确值 333 原样落库（没被 350 覆盖）",
                svc.getState().cursorDisappearDelay == 333,
                "cursorDisappearDelay=" + svc.getState().cursorDisappearDelay);

        conf.reset();
        check("V11-5 reset() 后 isModified()=false", !conf.isModified(), String.valueOf(conf.isModified()));
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + (detail == null || detail.isEmpty() ? "" : "   [" + detail + "]"));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}
