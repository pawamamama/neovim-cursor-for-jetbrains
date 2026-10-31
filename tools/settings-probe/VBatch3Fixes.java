import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JCheckBox;
import javax.swing.JSlider;

/**
 * V-BATCH3-FIXES —— 第 3 批审查修复的守门探针（2026-10-31）。
 *
 * <p>覆盖本轮六条修复中的五组（P-H-1 的提示数字由 {@code VHints} 守门）：
 * <ul>
 *   <li><b>T-1（P-C-1，CRITICAL）</b>：落盘失败时 apply 之后 baseline <b>不得</b>推进，
 *       而且必须留下一条用户可见的提示；<b>isModified() 必须保持真</b>
 *       （平台用它的闸门决定要不要再调 apply —— 这是「重试」真能发生的前提）；
 *       同时给一条「不注入失败 → baseline 正常推进」的对照，证明 T-1a 不是恒真断言。</li>
 *   <li><b>T-2（P-H-2）</b>：锐度换算必须与出厂默认值互逆（点一下输入框再移开不得变脏）。</li>
 *   <li><b>T-3（P-H-3）</b>：坏快照（applied==0）不得写 {@code animCustomName}、必须提示；
 *       部分缺失（0&lt;applied&lt;total）是文档化的向后兼容，<b>不得</b>报错。</li>
 *   <li><b>T-4（P-M-10）</b>：用户点复选框要像拖滑块一样派生副本；程序化回灌不得派生。</li>
 *   <li><b>T-5（P-M-2）</b>：{@code enableSearch} 覆写已摘除，平台自己的搜索接管。</li>
 * </ul>
 *
 * <p><b>每条都有对应的变异测试</b>（改坏生产代码 → 这里必须红），记录在
 * `plans/batch3-fix.md` 与本轮交付报告里。断言只写「用户可感知的正确行为」，
 * 期望值全部由生产代码的公开派生函数算出，不抄实现里的魔数。
 */
public final class VBatch3Fixes {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-BATCH3-FIXES：第 3 批审查修复守门（P-C-1 / P-H-1 / P-H-3 / P-H-2 / P-M-2 / P-M-10）===");

        persistFailureKeepsBaseline();
        sharpnessRoundTrip();
        brokenSnapshotDoesNotLie();
        checkboxDerivesLikeSlider();
        searchRegistrationRemoved();

        done();
    }

    // ---------------------------------------------------------------- T-1（P-C-1）

    /**
     * 落盘失败（{@code PersistResult.FAILED}）时的行为。
     *
     * <p>判据（缺一不可）：
     * <ol>
     *   <li>baseline 不得前移 —— 否则下一次 apply 会把「其实没写进磁盘的字段」当成已保存，
     *       再也不会重试；</li>
     *   <li>必须留下用户可见的提示，且文案写清「未能写入磁盘 / 重启会恢复旧值」；</li>
     *   <li>对照：不注入失败时 baseline 必须正常前移（证明第 1 条不是「永远不推进」的恒真）。</li>
     * </ol>
     */
    private static void persistFailureKeepsBaseline() {
        System.out.println();
        System.out.println("--- T-1（P-C-1）：落盘失败时 baseline 不推进 + 有可见提示 ---");

        V0Env.install();
        NeovideCursorSettings svc = V0Env.svc;
        NeovideCursorSettings.State s = svc.getState();
        s.shortAnimationLength = 0.11f;
        s.animPresetBase = 0;
        s.animCustomName = "";
        s.customAnimPresets = "";

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();

        JSlider slider = ui.sliderByKey("shortAnimationLength");
        check("T-1-0 定位到 shortAnimationLength 滑块", slider != null, "slider=" + slider);
        if (slider == null) {
            return;
        }
        float baselineBefore = conf.baselineForProbe().shortAnimationLength;
        int noticesBefore = conf.userNoticeCountForProbe();
        slider.setValue(20);                                 // 0.11 → 0.20

        V0Env.throwOnSaveSettings = true;
        try {
            conf.apply();                                    // persistQuietly 应返回 FAILED
        } finally {
            V0Env.throwOnSaveSettings = false;
        }

        float baselineAfter = conf.baselineForProbe().shortAnimationLength;
        check("T-1a 落盘失败后 baseline 未推进（仍 = 改动前的 " + baselineBefore + "）",
                Math.abs(baselineAfter - baselineBefore) < 1e-6f,
                "baseline=" + baselineAfter + "（推进了，后续 apply 就不会重写这些字段）");

        String notice = conf.lastUserNoticeForProbe();
        boolean informed = conf.userNoticeCountForProbe() > noticesBefore
                && notice != null
                && notice.contains("未能写入磁盘")
                && notice.contains("重启");
        check("T-1b 用户看得到提示，且文案写清「未能写入磁盘 / 重启恢复旧值」",
                informed, "提示=" + notice);

        // 【审查 H-1】光不前移 baseline 不够：平台的 ConfigurableEditor.apply() 先看
        // isModified()，为假就根本不调 apply()。A3 已把 pending 写进活 state，
        // 若没有 persistFailed 标志，这里会是 false —— 「重试」只是注释里的一句话。
        check("T-1c 落盘失败后 isModified() 保持 true（平台才会再次调用 apply）",
                conf.isModified(), "isModified=" + conf.isModified());

        // 对照：拿掉注入后，同一次 apply 必须能推进 baseline 并清掉失败态。
        conf.apply();
        float baselineRetried = conf.baselineForProbe().shortAnimationLength;
        check("T-1d 【对照】不注入失败时 baseline 正常推进到 0.20 且 isModified 归 false",
                Math.abs(baselineRetried - 0.20f) < 1e-6f && !conf.isModified(),
                "baseline=" + baselineRetried + " isModified=" + conf.isModified());
    }

    // ---------------------------------------------------------------- T-2（P-H-2）

    /**
     * 锐度（显示刻度）⇄ 外扩幅度（存储值）必须与出厂默认值互逆。
     *
     * <p>根因（第 3 批审查 P-H-2）：默认 {@code shadowBlurFactor = 0.2f} 不在旧网格
     * {@code 0.05 + (100-sharp)×0.0055} 上 —— 0.2 → 锐度 73 → 0.1985，差 1.5e-3。
     * 用户点一下输入框再移开（什么都没改）就会把 pending 写脏、派生「X 副本」。
     */
    private static void sharpnessRoundTrip() {
        System.out.println();
        System.out.println("--- T-2（P-H-2）：锐度换算与默认值互逆 ---");

        float defaultBlur = new NeovideCursorSettings.State().shadowBlurFactor;
        int sharp = NeovideCursorConfigurable.sharpnessOf(defaultBlur);
        check("T-2a 默认外扩 " + defaultBlur + " 读回锐度 73",
                sharp == 73, "实际 " + sharp);

        float back = NeovideCursorConfigurable.blurOfSharpness(73);
        check("T-2b 锐度 73 写回 == 默认外扩（差 < 1e-6）",
                Math.abs(back - defaultBlur) < 1e-6f,
                "写回=" + back + " 默认=" + defaultBlur + " 差=" + Math.abs(back - defaultBlur));

        boolean all = true;
        StringBuilder bad = new StringBuilder();
        for (int v = 0; v <= 100; v++) {
            int rt = NeovideCursorConfigurable.sharpnessOf(
                    NeovideCursorConfigurable.blurOfSharpness(v));
            if (rt != v) {
                all = false;
                if (bad.length() < 200) {
                    bad.append(v).append("→").append(rt).append(' ');
                }
            }
        }
        check("T-2c 0..100 全部档位往返 == 自身", all,
                all ? "101/101" : "错位=" + bad);

        // 端点与既有断言（ProbeSettingsV2 M6）必须一字不变：这里是回归护栏，不是新契约。
        boolean ends = NeovideCursorConfigurable.sharpnessOf(0.05f) == 100
                && NeovideCursorConfigurable.sharpnessOf(0.60f) == 0
                && NeovideCursorConfigurable.sharpnessOf(0.15f) == 82
                && Math.abs(NeovideCursorConfigurable.blurOfSharpness(100) - 0.05f) < 1e-6f
                && Math.abs(NeovideCursorConfigurable.blurOfSharpness(0) - 0.60f) < 1e-6f;
        check("T-2d 端点与既有取值不变（0.05↔100 / 0.60↔0 / 0.15↔82）", ends,
                "100→" + NeovideCursorConfigurable.blurOfSharpness(100)
                        + " 0→" + NeovideCursorConfigurable.blurOfSharpness(0)
                        + " 0.15→" + NeovideCursorConfigurable.sharpnessOf(0.15f));
    }

    // ---------------------------------------------------------------- T-3（P-H-3）

    private static void brokenSnapshotDoesNotLie() {
        System.out.println();
        System.out.println("--- T-3（P-H-3）：坏快照不得「声称已切换」 ---");

        // ---- 3a：整条快照解析不出一项（applied == 0）----
        V0Env.install();
        NeovideCursorSettings.State s = V0Env.svc.getState();
        s.animPresetBase = 0;
        s.animCustomName = "";
        s.customAnimPresets = CustomPresets.put("", "坏预设", "b0", "garbage");

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();

        int customIdx = CursorPresets.ANIM_PRESETS.length;    // 第一个自定义预设
        check("T-3-0 下拉里出现自定义预设「坏预设」",
                conf.animPresetComboForProbe().getItemCount() > customIdx,
                "项数=" + conf.animPresetComboForProbe().getItemCount());
        if (conf.animPresetComboForProbe().getItemCount() <= customIdx) {
            return;
        }
        String nameBefore = conf.pendingForProbe().animCustomName;
        int selBefore = conf.animPresetComboForProbe().getSelectedIndex();
        int noticesBefore = conf.userNoticeCountForProbe();

        conf.animPresetComboForProbe().setSelectedIndex(customIdx);

        check("T-3a 坏快照不写 animCustomName（下拉不得声称已切换）",
                nameBefore.equals(conf.pendingForProbe().animCustomName),
                "之前=「" + nameBefore + "」之后=「" + conf.pendingForProbe().animCustomName + "」");

        String notice = conf.lastUserNoticeForProbe();
        check("T-3b 坏快照要给用户一行提示",
                conf.userNoticeCountForProbe() > noticesBefore
                        && notice != null && notice.contains("损坏"),
                "提示=" + notice);

        check("T-3c 下拉回到真实选中项（不留在坏预设上）",
                conf.animPresetComboForProbe().getSelectedIndex() == selBefore,
                "之前=" + selBefore + " 之后=" + conf.animPresetComboForProbe().getSelectedIndex());

        // ---- 3b：旧预设少一个键（0 < applied < total）→ 兼容，不报错 ----
        V0Env.install();
        NeovideCursorSettings.State s2 = V0Env.svc.getState();
        s2.animPresetBase = 0;
        s2.animCustomName = "";
        String full = SettingsField.snapshot(s2);
        String partial = full.replaceAll("glowLayers=\\d+;?", "");
        check("T-3-0b 构造出「少一个键」的旧预设快照", !partial.equals(full)
                        && partial.length() < full.length(),
                "原长=" + full.length() + " 现长=" + partial.length());
        s2.customAnimPresets = CustomPresets.put("", "旧预设", "b0", partial);

        NeovideCursorConfigurable conf2 = new NeovideCursorConfigurable();
        VUi.build(conf2);
        conf2.reset();
        int notices2 = conf2.userNoticeCountForProbe();
        conf2.animPresetComboForProbe().setSelectedIndex(CursorPresets.ANIM_PRESETS.length);

        check("T-3d 部分缺失是兼容行为：仍切到该预设、且【不】报错",
                "旧预设".equals(conf2.pendingForProbe().animCustomName)
                        && conf2.userNoticeCountForProbe() == notices2,
                "名字=「" + conf2.pendingForProbe().animCustomName + "」新增提示="
                        + (conf2.userNoticeCountForProbe() - notices2));

        // ---- 3c（审查 M-1）：坏快照的兜底刷新不得「顺手派生副本」 ----
        //
        // 场景：pending 的值已经偏离基准预设（用户先改了参数），此时点一个损坏的
        // 自定义预设。兜底 refreshPresetDisplay() → afterPendingEdited() 若不被抑制，
        // 会派生「X 副本」—— 与「本次切换已取消，当前设置保持不变」自相矛盾。
        V0Env.install();
        NeovideCursorSettings.State s3 = V0Env.svc.getState();
        s3.animPresetBase = 0;
        s3.animCustomName = "";
        s3.glowLayers = 5;                       // 偏离「基础」预设的 8 层
        s3.customAnimPresets = CustomPresets.put("", "坏预设2", "b0", "garbage");
        NeovideCursorConfigurable conf3 = new NeovideCursorConfigurable();
        VUi.build(conf3);
        conf3.reset();
        int entriesBefore = CustomPresets.parse(conf3.pendingForProbe().customAnimPresets).size();
        conf3.animPresetComboForProbe().setSelectedIndex(CursorPresets.ANIM_PRESETS.length);
        int entriesAfter = CustomPresets.parse(conf3.pendingForProbe().customAnimPresets).size();
        check("T-3e 坏快照兜底刷新不得派生「X 副本」（用户值已偏离基准时也不许）",
                entriesAfter == entriesBefore
                        && conf3.pendingForProbe().animCustomName.isEmpty(),
                "预设数 " + entriesBefore + "→" + entriesAfter
                        + "，名字=「" + conf3.pendingForProbe().animCustomName + "」");
    }

    // ---------------------------------------------------------------- T-4（P-M-10）

    private static void checkboxDerivesLikeSlider() {
        System.out.println();
        System.out.println("--- T-4（P-M-10）：复选框改值也派生副本；程序化回灌不派生 ---");

        // 先找出「启用辉光」那个复选框：bindCheck 的 checkbox 文案是空串，
        // 只能像 VDead 一样「点一下看 useShadow 翻不翻」来定位（不依赖面板顺序）。
        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();

        int shadowIdx = -1;
        for (int i = 0; i < ui.checkBoxes.size(); i++) {
            conf.reset();
            boolean before = conf.pendingForProbe().useShadow;
            ui.checkBoxes.get(i).doClick();
            if (conf.pendingForProbe().useShadow != before) {
                shadowIdx = i;
                break;
            }
        }
        check("T-4-0 定位到「启用辉光」复选框", shadowIdx >= 0,
                "共 " + ui.checkBoxes.size() + " 个复选框");
        if (shadowIdx < 0) {
            return;
        }

        conf.reset();
        String nameBefore = conf.pendingForProbe().animCustomName;
        boolean shadowBefore = conf.pendingForProbe().useShadow;
        ui.checkBoxes.get(shadowIdx).doClick();

        String nameAfter = conf.pendingForProbe().animCustomName;
        check("T-4a 用户点复选框 → 像拖滑块一样派生副本（与内置预设偏离）",
                nameBefore.isEmpty()
                        && nameAfter != null && !nameAfter.isEmpty()
                        && conf.pendingForProbe().useShadow != shadowBefore,
                "点前名字=「" + nameBefore + "」点后=「" + nameAfter
                        + "」useShadow=" + conf.pendingForProbe().useShadow);

        // 程序化回灌：装载一份「与内置预设不一致」的历史配置，不得凭空派生。
        V0Env.install();
        NeovideCursorSettings.State s = V0Env.svc.getState();
        s.animPresetBase = 0;
        s.animCustomName = "";
        s.customAnimPresets = "";
        s.useShadow = !CursorPresets.ANIM_PRESETS[0].useShadow();    // 故意偏离「基础」
        NeovideCursorConfigurable conf2 = new NeovideCursorConfigurable();
        VUi.build(conf2);
        conf2.reset();

        check("T-4b 程序化回灌（reset）不派生副本",
                conf2.pendingForProbe().animCustomName.isEmpty()
                        && conf2.pendingForProbe().customAnimPresets.isEmpty(),
                "名字=「" + conf2.pendingForProbe().animCustomName + "」表="
                        + conf2.pendingForProbe().customAnimPresets);
    }

    // ---------------------------------------------------------------- T-5（P-M-2）

    private static void searchRegistrationRemoved() {
        System.out.println();
        System.out.println("--- T-5（P-M-2）：插件不再注册设置搜索 ---");

        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        check("T-5a 去掉 enableSearch 覆写后返回 null（平台自己的搜索接管）",
                conf.enableSearch("辉光") == null && conf.enableSearch("拖尾") == null,
                "enableSearch(辉光)=" + conf.enableSearch("辉光"));

        // 【审查 LOW-6】只断言「返回 null」有个漏洞：覆写一个对这两个词都返回 null 的
        // 假实现也能过关。真正要守的是「覆写本身不存在」—— 看方法声明在哪个类。
        boolean declaredOnInterface = false;
        String declaredAt = "?";
        try {
            java.lang.reflect.Method m = NeovideCursorConfigurable.class
                    .getMethod("enableSearch", String.class);
            declaredAt = m.getDeclaringClass().getName();
            declaredOnInterface =
                    m.getDeclaringClass() == com.intellij.openapi.options.SearchableConfigurable.class;
        } catch (Throwable t) {
            declaredAt = t.getClass().getSimpleName();
        }
        check("T-5b enableSearch 未在本类覆写（声明落在 SearchableConfigurable 的 default 上）",
                declaredOnInterface, "声明于 " + declaredAt);
    }

    // ---------------------------------------------------------------- helpers

    private static void done() {
        System.out.println();
        System.out.printf("=== V-BATCH3-FIXES 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-BATCH3-FIXES RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-BATCH3-FIXES RESULT: ALL PASS");
        System.exit(0);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}
