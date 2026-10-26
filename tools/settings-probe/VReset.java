
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
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
 * V-RESET —— refresh-auditor（t3）主探针。
 *
 * <p>审的是「syncAllControlsFromPending() 里补的 super.reset()」这处改动。
 * 全部数字都从<b>真面板</b>量：反射包装 {@link DialogPanel} 的 resetCallbacks / applyCallbacks，
 * 统计「完整遍历轮数」与「回调嵌套深度」，再配合行为断言（快照、pending、控件选中态）。
 *
 * <p>断言分组 = 任务书的 4 个风险点：
 * <ol>
 *   <li>重复劳动：一次公开操作跑几轮全量回灌；</li>
 *   <li>递归/重入：回调里会不会再触发一轮（死循环前兆）；</li>
 *   <li>bindCheckTracked 的 setter → afterPendingEdited 回环；</li>
 *   <li>字段域：还有谁「改了 pending 却不走 afterPendingEdited」。</li>
 * </ol>
 */
public final class VReset {

    private static int pass = 0;
    private static int fail = 0;

    // ---- DialogPanel 观测器 ----
    private static int resetCb;
    private static int applyCb;
    private static int resetTotal;
    private static int applyTotal;
    private static int depth;
    private static int maxDepth;

    public static void main(String[] args) {
        System.out.println("=== V-RESET：syncAllControlsFromPending 补 super.reset() 的副作用（真面板实测）===");
        V0Env.install();

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        DialogPanel panel = (DialogPanel) conf.createComponent();
        resetCb = instrument(panel, "resetCallbacks", true);
        applyCb = instrument(panel, "applyCallbacks", false);
        check("R-0 观测器挂上：能读到 DialogPanel 的两张回调表",
                resetCb > 0 && applyCb > 0, "reset 回调 " + resetCb + " 条 / apply 回调 " + applyCb + " 条");

        // ================================================================ 1. 重复劳动
        System.out.println();
        System.out.println("--- 1. 一次公开操作跑几轮【完整】全量回灌 ---");

        zero();
        conf.reset();
        double rounds1 = (double) resetTotal / resetCb;
        check("R-1 reset() 跑 2 轮 super.reset()（reset 自身 1 轮 + syncAllControlsFromPending 1 轮）",
                rounds1 == 2.0,
                "resetTotal=" + resetTotal + " / " + resetCb + " 条 = " + rounds1 + " 轮");

        zero();
        conf.apply();
        double rounds2 = (double) resetTotal / resetCb;
        check("R-2 apply() 里跑 2 轮 super.reset()（A8 段 1 轮 + syncAll 1 轮 = 纯重复）",
                rounds2 == 2.0,
                "resetTotal=" + resetTotal + " / " + resetCb + " 条 = " + rounds2 + " 轮；apply 回调触发 "
                        + applyTotal + " 次");

        zero();
        conf.pickAnimPresetForProbe(1);
        double rounds3 = (double) resetTotal / resetCb;
        check("R-3 一次预设切换只跑 1 轮 super.reset()（其它 14 处调用点不放大）",
                rounds3 == 1.0, "轮数=" + rounds3);

        // ================================================================ 2. 递归 / 重入
        System.out.println();
        System.out.println("--- 2. 递归 / 重入：回调里会不会再触发一轮遍历 ---");
        zero();
        conf.reset();
        conf.apply();
        for (int i = 0; i < 10; i++) {
            conf.pickAnimPresetForProbe(i % 3);
        }
        check("R-4 上述路径回调嵌套深度恒为 1（无重入 → 无死循环，双抑制标志足够）",
                maxDepth == 1, "maxDepth=" + maxDepth);
        double rounds4 = (double) resetTotal / resetCb;
        check("R-5 10 次预设切换 = 恰好 10+4 轮（线性，不是指数）",
                rounds4 == 14.0, "轮数=" + rounds4 + "（reset 2 + apply 2 + 10 次切换 ×1）");

        long t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            conf.pickAnimPresetForProbe(i % 3);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        check("R-6 200 次预设切换耗时 < 3000ms（重复一轮不构成性能问题）", ms < 3000, ms + " ms");

        // ================================================================ 3. bindCheckTracked 回环
        System.out.println();
        System.out.println("--- 3. bindCheckTracked → afterPendingEdited：回环与生效性 ---");

        int shadowIdx = findShadowCheckbox(conf, ui.checkBoxes);
        check("R-7 定位到「启用辉光」复选框（按 apply 后 pending 翻转判定）",
                shadowIdx >= 0, "面板下标=" + shadowIdx);

        if (shadowIdx >= 0) {
            JCheckBox box = ui.checkBoxes.get(shadowIdx);

            // 3a) 点击瞬间会不会触发一轮回灌（= 回环）
            conf.reset();
            conf.pendingForProbe().animCustomName = "";
            conf.pendingForProbe().customAnimPresets = "";
            zero();
            box.doClick();
            boolean wroteOnClick = !conf.pendingForProbe().useShadow;
            check("R-8 点击复选框【不】触发任何一轮 super.reset()（无回环）",
                    resetTotal == 0 && maxDepth <= 1,
                    "resetTotal=" + resetTotal + " maxDepth=" + maxDepth);
            check("R-9 点击复选框【不】在点击瞬间写 pending（写回发生在 super.apply()）",
                    !wroteOnClick, "点击后 pending.useShadow=" + conf.pendingForProbe().useShadow);

            // 3b) F-06 的核心：自定义预设上关辉光，快照跟不跟
            conf.reset();
            NeovideCursorSettings.State s = conf.pendingForProbe();
            s.animCustomName = "";
            s.customAnimPresets = "";
            s.useShadow = true;
            s.glowLayers = 10;                                  // 偏离内置 → 派生落脚点
            conf.refreshPresetDisplayForProbe();
            String custom = conf.pendingForProbe().animCustomName;
            conf.apply();                                       // 定稿：快照进 state

            String snapBefore = CustomPresets.get(conf.pendingForProbe().customAnimPresets, custom);
            box.doClick();                                      // 用户关掉辉光
            conf.apply();                                       // 用户点「应用」
            String snapAfter = CustomPresets.get(conf.pendingForProbe().customAnimPresets, custom);

            check("R-10 【F-06】在自定义预设上关辉光并应用后，该预设快照必须记下 useShadow=0",
                    snapAfter != null && snapAfter.contains("useShadow=0"),
                    "预设=「" + custom + "」 应用前快照=" + pick(snapBefore, "useShadow")
                            + " 应用后快照=" + pick(snapAfter, "useShadow")
                            + " pending.useShadow=" + conf.pendingForProbe().useShadow);

            // 3c) 用户可见后果：切走再切回，辉光会不会又亮起来
            if (!custom.isEmpty()) {
                conf.pickAnimPresetForProbe(0);
                conf.pickAnimPresetByNameForProbe(custom);
                check("R-11 【F-06 用户症状】切走再切回该自定义预设，辉光必须仍是关的",
                        !conf.pendingForProbe().useShadow,
                        "切回后 useShadow=" + conf.pendingForProbe().useShadow);
            }
        }

        // ================================================================ 4. 字段域
        System.out.println();
        System.out.println("--- 4. 字段域：谁「改了 pending 却不走 afterPendingEdited」---");
        conf.reset();
        conf.pendingForProbe().animCustomName = "";
        conf.pendingForProbe().customAnimPresets = "";
        NeovideConfig.TrailMode mode0 = conf.pendingForProbe().trailMode;
        conf.trailModeComboForProbe().setSelectedIndex(1);
        NeovideConfig.TrailMode mode1 = conf.pendingForProbe().trailMode;
        check("R-12 【同类漏洞】trailMode 下拉在选中瞬间不写 pending（setter 只在 super.apply 里跑）",
                mode1 == mode0, "点击后 pending.trailMode=" + mode1 + "（下拉已显示另一项）");

        conf.reset();
        conf.pendingForProbe().animCustomName = "";
        conf.pendingForProbe().customAnimPresets = "";
        conf.pendingForProbe().glowLayers = 10;
        conf.refreshPresetDisplayForProbe();
        String comboPreset = conf.pendingForProbe().animCustomName;
        boolean usedTrailMode = conf.pendingForProbe().trailMode == NeovideConfig.TrailMode.AFTERIMAGE;
        conf.trailModeComboForProbe().setSelectedIndex(usedTrailMode ? 0 : 1);
        NeovideConfig.TrailMode picked = usedTrailMode
                ? NeovideConfig.TrailMode.SPRING : NeovideConfig.TrailMode.AFTERIMAGE;
        conf.apply();
        String comboSnap = CustomPresets.get(conf.pendingForProbe().customAnimPresets, comboPreset);
        check("R-13 【同类漏洞】切了 trailMode 并应用后，自定义预设快照必须记下新的 trailMode",
                comboSnap != null && comboSnap.contains("trailMode=" + picked.name()),
                "预设=「" + comboPreset + "」 应用后快照=" + pick(comboSnap, "trailMode")
                        + " pending.trailMode=" + conf.pendingForProbe().trailMode);

        // ================================================================ 5. apply 幂等
        System.out.println();
        System.out.println("--- 5. A8 段与 syncAll 都在跑 super.reset()：会不会互相打架 ---");
        conf.reset();
        conf.pickAnimPresetForProbe(1);
        conf.apply();
        float first = V0Env.svc.getState().animationLength;
        boolean mod = conf.isModified();
        conf.apply();
        float second = V0Env.svc.getState().animationLength;
        check("R-14 第一次 apply 后 isModified()==false（控件确实被回灌对齐了）", !mod, "isModified=" + mod);
        check("R-15 连续两次 apply 值稳定（重复的两轮 super.reset() 不打架）",
                Math.abs(first - second) < 1e-6f, first + " -> " + second);

        // ================================================================ 6. paintDiag
        System.out.println();
        System.out.println("--- 6. paintDiag 保持 bindCheck：经多轮 super.reset() 后是否被卷进判脏 ---");
        conf.reset();
        int diagIdx = findPaintDiagCheckbox(conf, ui.checkBoxes);
        if (diagIdx >= 0) {
            boolean before = conf.isModified();
            ui.checkBoxes.get(diagIdx).doClick();
            boolean after = conf.isModified();
            ui.checkBoxes.get(diagIdx).doClick();
            check("R-16 点「性能诊断」不参与 isModified（源码注释如此承诺）",
                    before == after, "isModified " + before + " -> " + after);
        } else {
            check("R-16 定位「性能诊断」复选框", false, "未找到");
        }

        // ================================================================ 7. 异常安全
        System.out.println();
        System.out.println("--- 7. 抑制标志的异常安全 ---");
        conf.reset();
        check("R-17 reset() 结束后 syncingControls 已复位", !conf.syncingForProbe(),
                "syncing=" + conf.syncingForProbe());

        System.out.println();
        System.out.printf("=== V-RESET 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-RESET RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-RESET RESULT: ALL PASS");
        System.exit(0);
    }

    // ---------------------------------------------------------------- 辅助

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

    /** 用「点击 + apply 后 pending.useShadow 翻转」定位「启用辉光」。 */
    private static int findShadowCheckbox(NeovideCursorConfigurable conf, List<JCheckBox> boxes) {
        for (int i = 0; i < boxes.size(); i++) {
            conf.reset();
            conf.pendingForProbe().useShadow = true;
            boolean before = conf.pendingForProbe().useShadow;
            boxes.get(i).doClick();
            conf.apply();
            boolean after = conf.pendingForProbe().useShadow;
            boxes.get(i).doClick();
            conf.apply();
            if (before != after) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 定位「性能诊断」复选框。
     *
     * <p><b>0.9.88 改法</b>：原来靠「点它后 apply，pending 任何字段都不变的那个」
     * 来暴力识别 —— 那是因为它当年用的是 {@code bindCheck}，会写进 pending。
     *
     * <p>0.9.88 把它改成**非绑定复选框**（这正是 R-16 要的行为：不参与 isModified、
     * 不创建副本），于是它不再写 pending，暴力识别就找不到它了。
     *
     * <p>现在直接按**组件身份**定位：从 configurable 的 {@code paintDiagCheck}
     * 私有字段取出那个 JCheckBox，再在列表里找同一个实例。
     * 这比启发式更可靠 —— 它测的就是「面板上那个真实的性能诊断开关」。
     */
    private static int findPaintDiagCheckbox(NeovideCursorConfigurable conf, List<JCheckBox> boxes) {
        try {
            java.lang.reflect.Field f =
                    NeovideCursorConfigurable.class.getDeclaredField("paintDiagCheck");
            f.setAccessible(true);
            Object target = f.get(conf);
            if (target instanceof JCheckBox) {
                return boxes.indexOf(target);
            }
            return -1;
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private static String digest(NeovideCursorConfigurable conf) {
        NeovideCursorSettings.State s = conf.pendingForProbe();
        return s.enabled + "|" + s.trailMode + "|" + s.hideNativeCaret + "|" + s.alwaysHighFps
                + "|" + s.overlayFadeOut + "|" + s.useShadow + "|" + s.glowLayers + "|" + s.trailSize
                + "|" + s.stretchRatioK + "|" + s.typingSmoothness + "|" + s.customAnimPresets
                + "|" + s.animCustomName;
    }

    // ---------------------------------------------------------------- 观测器

    @SuppressWarnings("unchecked")
    private static int instrument(DialogPanel panel, String fieldName, boolean isReset) {
        try {
            Field f = DialogPanel.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            Map<Object, List<Object>> old = (Map<Object, List<Object>>) f.get(panel);
            Map<Object, List<Object>> next = new LinkedHashMap<>();
            int n = 0;
            for (Map.Entry<Object, List<Object>> e : old.entrySet()) {
                List<Object> wrapped = new ArrayList<>();
                for (Object cb : e.getValue()) {
                    wrapped.add(wrap((kotlin.jvm.functions.Function0<?>) cb, isReset));
                    n++;
                }
                next.put(e.getKey(), wrapped);
            }
            f.set(panel, next);
            return n;
        } catch (Throwable t) {
            System.out.println("  [instrument 失败] " + fieldName + " : " + t);
            return -1;
        }
    }

    private static kotlin.jvm.functions.Function0<kotlin.Unit> wrap(
            kotlin.jvm.functions.Function0<?> orig, boolean isReset) {
        return () -> {
            if (isReset) {
                resetTotal++;
            } else {
                applyTotal++;
            }
            depth++;
            if (depth > maxDepth) {
                maxDepth = depth;
            }
            try {
                if (depth > 40) {
                    throw new IllegalStateException("检测到递归：嵌套深度 > 40");
                }
                return (kotlin.Unit) orig.invoke();
            } finally {
                depth--;
            }
        };
    }

    private static void zero() {
        resetTotal = 0;
        applyTotal = 0;
        maxDepth = 0;
        depth = 0;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + "   [" + detail + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}
