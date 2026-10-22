import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.util.ArrayList;
import java.util.List;

/**
 * V-CUSTOM —— 自定义预设（0.9.9 的核心功能）。
 *
 * <p><b>为什么这条探针取代了 VOverride</b>：0.9.8 的 F7 描述的是
 * 「Tools 应用预设时冲掉用户的覆盖值」。0.9.9 把模型换成
 * 「内置预设永久只读 + 用户改动落在自定义预设上」，覆盖值这个概念不存在了，
 * 那条 bug 路径随之消失。所以这里改为验证新模型本身。
 *
 * <p>覆盖四件事：
 * <ol>
 *   <li><b>快照往返</b>：snapshot → restore 后 19 个参数逐项相等（含 HEX 大小写与枚举）；</li>
 *   <li><b>增删改查</b>：名称可含中文/空格/分号/等号；重名拒绝；删除幂等；</li>
 *   <li><b>内置只读</b>：Tools 应用内置预设不动颜色、清空“当前自定义”指针、不删用户预设；</li>
 *   <li><b>自动派生</b>：在内置预设上改一个动画字段 → 自动出现「X 副本」并切过去，
 *       且内置常量表一位数都不变。</li>
 * </ol>
 */
public final class VCustom {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-CUSTOM：自定义预设（快照 / 增删改查 / 内置只读 / 自动派生）===");
        V0Env.install();

        snapshotRoundTrip();
        crud();
        builtinReadOnly();
        buttonsExist();
        presetWritesAllFields();
        survivesXmlRoundTrip();
        autoDerive();

        System.out.println();
        System.out.printf("=== V-CUSTOM 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-CUSTOM RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-CUSTOM RESULT: ALL PASS");
        System.exit(0);
    }

    // ---------------------------------------------------------------- 1. 快照往返

    private static void snapshotRoundTrip() {
        System.out.println();
        System.out.println("--- 1. 全参数快照往返 ---");
        NeovideCursorSettings.State src = new NeovideCursorSettings.State();
        src.enabled = false;
        src.trailMode = NeovideConfig.TrailMode.AFTERIMAGE;
        src.hideNativeCaret = false;
        src.alwaysHighFps = false;
        src.overlayFadeOut = false;
        src.cursorDisappearDelay = 320;
        src.cursorFadeOutDuration = 0.615f;
        src.tailColor = "#0a1b2c";
        src.tailOpacity = 0.625f;
        src.useShadow = false;
        src.shadowColor = "#3D4E5F";
        src.glowOpacity = 0.375f;
        src.glowLayers = 11;
        src.shadowBlurFactor = 0.47f;
        src.trailSize = 0.812f;
        src.stretchRatioK = 6.2f;
        src.animationLength = 0.22f;
        src.shortAnimationLength = 0.07f;
        src.typingSmoothness = 0.35f;

        String snap = SettingsField.snapshot(src);
        check("C-1 快照包含全部 19 个预设域字段",
                snap.split(";").length == SettingsField.PRESET_FIELDS.size(),
                "条目=" + snap.split(";").length + " 字段=" + SettingsField.PRESET_FIELDS.size());

        NeovideCursorSettings.State dst = new NeovideCursorSettings.State();
        int applied = SettingsField.restore(dst, snap);
        check("C-2 restore 写入了全部字段",
                applied == SettingsField.PRESET_FIELDS.size(), "写入=" + applied);

        List<String> diffs = new ArrayList<>();
        for (SettingsField f : SettingsField.PRESET_FIELDS) {
            Object a = f.readState(src);
            Object b = f.readState(dst);
            if (a instanceof Float && b instanceof Float) {
                if (Float.compare((Float) a, (Float) b) != 0) {
                    diffs.add(f.key + " " + a + "!=" + b);
                }
            } else if (!String.valueOf(a).equalsIgnoreCase(String.valueOf(b))) {
                diffs.add(f.key + " " + a + "!=" + b);
            }
        }
        check("C-3 往返后 19 个参数逐项相等（HEX 忽略大小写）", diffs.isEmpty(), String.valueOf(diffs));

        check("C-4 animPresetBase 不进快照（它是选中指针，不是参数）",
                !snap.contains("animPresetBase"), "首段=" + snap.split(";")[0]);

        NeovideCursorSettings.State tolerant = new NeovideCursorSettings.State();
        int n = SettingsField.restore(tolerant, "glowLayers=9;不存在的键=1;tailColor=#ABCDEF;坏的");
        check("C-5 未知键 / 坏条目被忽略，合法项照常写入", n == 2,
                "写入=" + n + " glowLayers=" + tolerant.glowLayers
                        + " tailColor=" + tolerant.tailColor);
    }

    // ---------------------------------------------------------------- 2. 增删改查

    private static void crud() {
        System.out.println();
        System.out.println("--- 2. 增删改查（名称可含中文 / 空格 / 分号 / 等号）---");
        String weird = "我的 预设;=1";
        String snapA = SettingsField.snapshot(new NeovideCursorSettings.State());

        String all = CustomPresets.put("", weird, snapA);
        check("C-6 名称含中文/空格/分号/等号也能存取",
                CustomPresets.has(all, weird) && snapA.equals(CustomPresets.get(all, weird)),
                "name=" + weird);

        String all2 = CustomPresets.put(all, "第二个", snapA);
        check("C-7 追加第二个预设", CustomPresets.parse(all2).size() == 2,
                "数量=" + CustomPresets.parse(all2).size());

        NeovideCursorSettings.State changed = new NeovideCursorSettings.State();
        changed.glowLayers = 3;
        String snapB = SettingsField.snapshot(changed);
        String all3 = CustomPresets.put(all2, weird, snapB);
        check("C-8 同名 put 原位替换且顺序不变",
                snapB.equals(CustomPresets.get(all3, weird))
                        && CustomPresets.parse(all3).get(0).name.equals(weird),
                "首项=" + CustomPresets.parse(all3).get(0).name);

        check("C-9 改名到已存在的名字被拒绝（不静默覆盖别人的预设）",
                CustomPresets.rename(all2, weird, "第二个") == null, "期望 null");
        String renamed = CustomPresets.rename(all2, weird, "改名后");
        check("C-10 改名成功且快照跟着走",
                renamed != null && CustomPresets.has(renamed, "改名后")
                        && CustomPresets.get(renamed, "改名后").equals(snapA),
                String.valueOf(renamed != null));

        String removed = CustomPresets.remove(renamed, "改名后");
        check("C-11 删除后不再存在", !CustomPresets.has(removed, "改名后"),
                "剩 " + CustomPresets.parse(removed).size() + " 个");
        check("C-12 删除不存在的名字是幂等空操作",
                removed.equals(CustomPresets.remove(removed, "根本没有")), "");

        check("C-13 uniqueName 重名时追加 2（Windows/IntelliJ 复制命名习惯）",
                "缎带 副本".equals(CustomPresets.uniqueName("", "缎带 副本"))
                        && "缎带 副本 2".equals(CustomPresets.uniqueName(
                                CustomPresets.put("", "缎带 副本", snapA), "缎带 副本")),
                "");

        check("C-14 空串与坏串解析不抛异常且返回空表",
                CustomPresets.parse("").isEmpty()
                        && CustomPresets.parse(";;;").isEmpty()
                        && CustomPresets.parse("没有分隔符").isEmpty(),
                "");
    }

    // ---------------------------------------------------------------- 3. 内置只读

    private static void builtinReadOnly() {
        System.out.println();
        System.out.println("--- 3. 内置预设永久只读 ---");
        NeovideCursorSettings.State s = V0Env.svc.getState();
        s.tailColor = "#123456";
        s.shadowColor = "#654321";
        s.animCustomName = "某个自定义";
        s.customAnimPresets = CustomPresets.put("", "某个自定义", SettingsField.snapshot(s));

        int idx = 1;                                   // 「缎带」
        CursorPresets.AnimPreset ribbon = CursorPresets.ANIM_PRESETS[idx];
        CursorPresetApplier.applyAnimPreset(ribbon);

        NeovideCursorSettings.State after = V0Env.svc.getState();
        check("C-15 应用内置预设只写 6 个动画字段（值 = 预设常量）",
                Math.abs(after.animationLength - ribbon.animationLength()) < 1e-6f
                        && after.glowLayers == ribbon.glowLayers(),
                "anim=" + after.animationLength + " layers=" + after.glowLayers);
        check("C-16 应用内置预设不动颜色（正交性硬判据）",
                "#123456".equalsIgnoreCase(after.tailColor)
                        && "#654321".equalsIgnoreCase(after.shadowColor),
                after.tailColor + " / " + after.shadowColor);
        check("C-17 应用内置预设清空「当前自定义」指针（回到内置）",
                "".equals(after.animCustomName), "animCustomName=\"" + after.animCustomName + "\"");
        check("C-18 应用内置预设不删除任何自定义预设（用户数据不丢）",
                CustomPresets.has(after.customAnimPresets, "某个自定义"),
                "数量=" + CustomPresets.parse(after.customAnimPresets).size());
    }

    // ---------------------------------------------------------------- 3.5 按钮真的在界面上

    /**
     * 三个动作按钮必须真的挂在面板上。
     *
     * <p><b>为什么补这一条</b>：0.9.9 第一次交付时，新建/重命名/删除三个按钮的
     * <b>方法都写了、字段也声明了，但 createPanel 里从没创建它们</b> ——
     * 用户打开设置页什么都看不到。而当时门禁全绿，因为<b>没有任何断言检查按钮存在</b>。
     *
     * <p>教训：凡是要用户点的东西，必须有一条断言证明它真的在组件树上，
     * 不能只证明「背后的方法能跑」。
     */
    private static void buttonsExist() {
        System.out.println();
        System.out.println("--- 3.5 三个动作按钮必须真的在面板上 ---");
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);

        javax.swing.JButton add = conf.animNewButtonForProbe();
        javax.swing.JButton ren = conf.animRenameButtonForProbe();
        javax.swing.JButton del = conf.animDeleteButtonForProbe();

        check("C-26 「新建预设…」按钮已创建", add != null,
                add == null ? "null（createPanel 里忘了 new）" : add.getText());
        check("C-27 「重命名…」按钮已创建", ren != null,
                ren == null ? "null" : ren.getText());
        check("C-28 「删除」按钮已创建", del != null,
                del == null ? "null" : del.getText());

        // 不仅要有对象，还必须真的在组件树上（漏了 row.cell(...) 时对象存在但看不见）
        java.awt.Component root = (java.awt.Component) ui.panel.component;
        check("C-29 三个按钮都在面板组件树里（不是只 new 了对象）",
                inTree(root, add) && inTree(root, ren) && inTree(root, del),
                "add=" + inTree(root, add) + " ren=" + inTree(root, ren)
                        + " del=" + inTree(root, del));

        // 内置预设选中时：新建可用，改名/删除置灰
        check("C-30 内置预设选中时：新建可用、重命名与删除置灰",
                add.isEnabled() && !ren.isEnabled() && !del.isEnabled(),
                "new=" + add.isEnabled() + " ren=" + ren.isEnabled() + " del=" + del.isEnabled());
    }

    /** 组件是否真的在某棵容器树里（递归）。 */
    private static boolean inTree(java.awt.Component root, java.awt.Component target) {
        if (root == null || target == null) {
            return false;
        }
        if (root == target) {
            return true;
        }
        if (root instanceof java.awt.Container) {
            for (java.awt.Component c : ((java.awt.Container) root).getComponents()) {
                if (inTree(c, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 3.6 预设必须写全字段

    /**
     * 切换/恢复预设时，{@code useShadow} / {@code overlayFadeOut} 必须跟着变。
     *
     * <p><b>这是用户报的 B1/B3 的守门断言</b>：
     * 「辉光淡入淡出关闭了，恢复预设没用」「切换预设不能让辉光回来」
     * 「实测缎带没有扩散了，就是持久化了」。
     *
     * <p>根因：预设原来只描述 6 个动画字段，{@code useShadow} 不在字段域内
     * → 用户在别处关掉辉光后，切任何预设都不会把它打开。
     */
    private static void presetWritesAllFields() {
        System.out.println();
        System.out.println("--- 3.6 切换/恢复预设必须写全字段（含辉光/淡出开关）---");
        NeovideCursorSettings svc2 = V0Env.svc;

        // 造一个「辉光关、淡出关」的起点（模拟用户在别的预设下关掉了）
        NeovideCursorSettings.State st = svc2.getState();
        st.useShadow = false;
        st.overlayFadeOut = false;
        st.animCustomName = "";
        st.customAnimPresets = "";

        // 「基础」预设声明 useShadow=true / overlayFadeOut=true（见 CursorPresets）
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();
        conf.pickAnimPresetForProbe(0);

        NeovideCursorSettings.State after = conf.pendingForProbe();
        check("C-31 【B3】切到「基础」后 useShadow 恢复为 true",
                after.useShadow, "useShadow=" + after.useShadow);
        check("C-32 【B3】切到「基础」后 overlayFadeOut 恢复为 true",
                after.overlayFadeOut, "overlayFadeOut=" + after.overlayFadeOut);

        // 「纯平滑输入」必须能表达「关辉光 + 关淡出」
        int last = com.github.yyyolo7a79.neovidecursor.settings.CursorPresets
                .ANIM_PRESETS.length - 1;
        conf.pickAnimPresetForProbe(last);
        NeovideCursorSettings.State pure = conf.pendingForProbe();
        check("C-33 「纯平滑输入」预设把 useShadow 关掉",
                !pure.useShadow, "useShadow=" + pure.useShadow);
        check("C-34 「纯平滑输入」预设把 overlayFadeOut 关掉",
                !pure.overlayFadeOut, "overlayFadeOut=" + pure.overlayFadeOut);

        // 关键回归：再切回「基础」，辉光必须回来（这正是用户报的症状）
        conf.pickAnimPresetForProbe(0);
        check("C-35 【B3 核心】从「纯平滑输入」切回「基础」→ 辉光重新打开",
                conf.pendingForProbe().useShadow,
                "useShadow=" + conf.pendingForProbe().useShadow);

        // 恢复按钮同样必须写全字段（B1）
        conf.pendingForProbe().useShadow = false;
        conf.pendingForProbe().overlayFadeOut = false;
        conf.animRestoreButtonForProbe().doClick();
        check("C-36 【B1】点「恢复」后辉光/淡出回到该预设声明值",
                conf.pendingForProbe().useShadow && conf.pendingForProbe().overlayFadeOut,
                "useShadow=" + conf.pendingForProbe().useShadow
                        + " overlayFadeOut=" + conf.pendingForProbe().overlayFadeOut);
    }

    // ---------------------------------------------------------------- 3.7 必须能活过 XML 落盘

    /**
     * 【F2 守门】自定义预设必须能活过平台的 XmlSerializer 落盘/回读。
     *
     * <p><b>这是最严重的一条</b>：最初分隔符用了 U+0001 / U+0002，
     * 而它们是 <b>XML 1.0 非法码点</b>，IDEA 的 {@code XmlSerializer} 会静默丢弃。
     * 实测后果：内存里 2 条预设 → 落盘再回读变成 <b>0 条</b>，
     * 用户建的自定义预设重启后全部消失。
     *
     * <p>现在用 U+241F / U+241E（XML 合法）。本断言直接调真实平台序列化器验证，
     * 不靠「分隔符看起来合法」这种推断。
     */
    private static void survivesXmlRoundTrip() {
        System.out.println();
        System.out.println("--- 3.7 自定义预设必须活过 XML 落盘（F2 守门）---");
        try {
            NeovideCursorSettings.State s = new NeovideCursorSettings.State();
            s.glowLayers = 10;
            String snap = com.github.yyyolo7a79.neovidecursor.settings.SettingsField.snapshot(s);
            s.customAnimPresets = com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                    .put("", "落盘测试", snap);
            s.animCustomName = "落盘测试";

            org.jdom.Element el = com.intellij.util.xmlb.XmlSerializer.serialize(s);
            NeovideCursorSettings.State back = com.intellij.util.xmlb.XmlSerializer
                    .deserialize(el, NeovideCursorSettings.State.class);

            int n = com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                    .parse(back.customAnimPresets).size();
            check("C-37 【F2】1 条自定义预设落盘再回读仍是 1 条（分隔符必须是 XML 合法字符）",
                    n == 1, "回读=" + n + " 条");
            check("C-38 【F2】回读后名字与快照都没被黏连",
                    back.customAnimPresets != null
                            && com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                                    .get(back.customAnimPresets, "落盘测试") != null,
                    back.customAnimPresets == null ? "null"
                            : (back.customAnimPresets.length() + " 字符"));
        } catch (Throwable t) {
            check("C-37 【F2】XML 往返", false, "抛异常：" + t);
        }
    }

    // ---------------------------------------------------------------- 4. 自动派生

    private static void autoDerive() {
        System.out.println();
        System.out.println("--- 4. 改内置的字段 → 自动派生副本，内置不变 ---");
        int base = 0;                                  // 「原版粉色」
        float[] constants = constantsOf(base);

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        // 必须走 VUi.build：它内部调 V0Env.disableValidators，
        // 否则离线环境没有 JBPopupFactory，setText 时平台验证器会抛异常。
        VUi.build(conf);
        conf.reset();

        NeovideCursorSettings.State p1 = conf.pendingForProbe();
        p1.animPresetBase = base;
        p1.animCustomName = "";
        p1.customAnimPresets = "";
        writeAnim(p1, constants);
        conf.refreshPresetDisplayForProbe();
        check("C-19 未改动时不派生任何自定义预设",
                "".equals(conf.pendingForProbe().animCustomName)
                        && CustomPresets.parse(conf.pendingForProbe().customAnimPresets).isEmpty(),
                "name=\"" + conf.pendingForProbe().animCustomName + "\"");

        // 模拟拖动滑块：改一个动画字段 → 自动派生
        conf.pendingForProbe().glowLayers = 10;
        conf.refreshPresetDisplayForProbe();
        NeovideCursorSettings.State p2 = conf.pendingForProbe();
        String derived = p2.animCustomName;
        check("C-20 改一个动画字段后自动派生出「X 副本」并切过去",
                !derived.isEmpty() && derived.startsWith(CursorPresets.ANIM_PRESETS[base].name()),
                "name=\"" + derived + "\"");
        check("C-21 派生的副本里存的就是当前值（glowLayers=10）",
                CustomPresets.get(p2.customAnimPresets, derived) != null
                        && CustomPresets.get(p2.customAnimPresets, derived).contains("glowLayers=10"),
                "");
        check("C-22 内置预设常量表未被污染（一位数都没变）",
                CursorPresets.ANIM_PRESETS[base].glowLayers() == Math.round(constants[3]),
                "常量=" + CursorPresets.ANIM_PRESETS[base].glowLayers());

        // 继续改 → 更新同一个副本，不新增
        conf.pendingForProbe().glowLayers = 11;
        conf.refreshPresetDisplayForProbe();
        NeovideCursorSettings.State p3 = conf.pendingForProbe();
        check("C-23 继续改动只更新同一个副本，不重复新建",
                p3.animCustomName.equals(derived)
                        && CustomPresets.parse(p3.customAnimPresets).size() == 1,
                "数量=" + CustomPresets.parse(p3.customAnimPresets).size());

        // 切回内置 → 指针清空、值回常量
        conf.pickAnimPresetForProbe(base);
        NeovideCursorSettings.State p4 = conf.pendingForProbe();
        check("C-24 切回内置预设后指针清空、值回到常量",
                "".equals(p4.animCustomName) && p4.glowLayers == Math.round(constants[3]),
                "name=\"" + p4.animCustomName + "\" glowLayers=" + p4.glowLayers);
        check("C-25 切回内置后副本仍在列表里（不自动删用户数据）",
                CustomPresets.parse(p4.customAnimPresets).size() == 1,
                "数量=" + CustomPresets.parse(p4.customAnimPresets).size());
    }

    private static float[] constantsOf(int idx) {
        CursorPresets.AnimPreset p = CursorPresets.ANIM_PRESETS[idx];
        return new float[]{
                p.animationLength(), p.shortAnimationLength(), p.trailSize(),
                p.glowLayers(), p.shadowBlurFactor(), p.glowOpacity()};
    }

    private static void writeAnim(NeovideCursorSettings.State s, float[] v) {
        s.animationLength = v[0];
        s.shortAnimationLength = v[1];
        s.trailSize = v[2];
        s.glowLayers = Math.round(v[3]);
        s.shadowBlurFactor = v[4];
        s.glowOpacity = v[5];
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
