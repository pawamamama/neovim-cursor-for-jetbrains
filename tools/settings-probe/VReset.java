
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
            // 【2026-10-29 契约变更：断言方向反转，断言没有删】
            //
            // 原来断言「点击【不】写 pending」—— 那是当时的实测行为：
            // DSL 的 ButtonKt.bindSelected 只在 super.apply() 时回写，
            // 于是复选框要等「应用 / 确定」才生效，而同一张面板上的滑块是实时的。
            // 这个「两种控件两种语义」正是用户报的那个 bug：
            // 勾选「启用辉光」后动画预览毫无反应，只有关掉设置页再打开才好。
            //
            // 修法：bindCheck 里显式挂 ActionListener（与当年修滑块同一招），
            // 点一下当场写 pending 并刷新预览。所以断言方向反转。
            check("R-9 点击复选框【就】在点击瞬间写 pending（2026-10-29 起与滑块同语义）",
                    wroteOnClick, "点击后 pending.useShadow=" + conf.pendingForProbe().useShadow);

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
        // 【2026-10-28 改方向（不删断言）】
        // 旧断言是「切 trailMode 后快照必须记下它」——那建立在「设备开关也进快照」的旧契约上。
        // 用户明确要求「凡是设备/功能开关都不该进预设」，所以方向反过来：
        // 快照里**不该**出现 trailMode，而用户刚选的模式必须仍然是当前生效值。
        check("R-13 trailMode 是设备开关，不再写进自定义预设快照",
                comboSnap != null && !comboSnap.contains("trailMode="),
                "预设=「" + comboPreset + "」 快照里 trailMode=" + pick(comboSnap, "trailMode")
                        + "（期望 无）");
        check("R-13b 用户选的 trailMode 仍然是当前生效值（不被预设写回）",
                conf.pendingForProbe().trailMode == picked,
                "pending.trailMode=" + conf.pendingForProbe().trailMode + "  期望=" + picked);

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

    /**
     * 用「点击 + apply 后 pending.useShadow 翻转」定位「启用辉光」。
     *
     * <p><b>2026-10-29 去掉了一行 {@code pendingForProbe().useShadow = true}</b>：
     * 原来它靠「先把 pending 顶成 true，再点击，看 apply 后有没有被控件顶回 false」来判定。
     * 而复选框现在点击当场就写 pending（见 R-9 的说明），那一行会让「控制态」与
     * 「pending 值」分离 —— 初始 value 恰好是 true 时，点击前后读到的都是 true，
     * 等于**永远找不到**这个复选框。改成直接读点击前后的真实值，两种语义下都成立。
     */
    private static int findShadowCheckbox(NeovideCursorConfigurable conf, List<JCheckBox> boxes) {
        for (int i = 0; i < boxes.size(); i++) {
            conf.reset();
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

    private static int instrument(DialogPanel panel, String fieldName, boolean isReset) {
        // 【2026-10-29 分标签适配：回调表按面板分开存放】
        //
        // 分成 6 个标签页后，外层 DialogPanel 自己**一条**回调都没有 ——
        // 所有控件的 reset / apply 回调都在各自标签页那个 DialogPanel 的表里。
        // 平台的 reset() / apply() 都是「先逐个跑 integratedPanels，再跑自己的表」
        // （javap 实证），所以「外层 + 集成面板」的**合计**才等于原来的那张表。
        //
        // 断言的语义没变：R-1..R-5 判的是「一次操作把每条回调跑了几轮」，
        // 分母 resetCb / applyCb 现在是全部面板的回调总数，分子也按同一范围累计，
        // 比值仍然是「轮数」。
        int n = 0;
        List<DialogPanel> panels = new ArrayList<>();
        panels.add(panel);
        panels.addAll(panel.getIntegratedPanels());
        for (DialogPanel p : panels) {
            int k = instrumentOne(p, fieldName, isReset);
            if (k < 0) {
                return -1;
            }
            n += k;
        }
        return n;
    }

    /** 只给「一个」面板的回调表套包装。原 instrument 的方法体原样搬进来。 */
    @SuppressWarnings("unchecked")
    private static int instrumentOne(DialogPanel panel, String fieldName, boolean isReset) {
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
