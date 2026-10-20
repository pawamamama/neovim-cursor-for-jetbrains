import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JSlider;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * verifier 自建（t4）：预设正交性 + 预设取值 + 刻度网格（V3/V4/V5/V14/V15）。
 *
 * <p>正交性的判据按契约 §5：换动画预设只允许改 6 个动画字段，
 * 换颜色预设只允许改 2 个 HEX。这里用 <b>floatToRawIntBits</b> 逐位比对，
 * 不用 EPS —— 「差不多」不是正交性的证明。
 */
public final class VPreset {

    private static int pass = 0;
    private static int fail = 0;

    /** 动画预设允许改的字段（契约 §5.3 的 6 个维度）。 */
    private static final Set<String> ANIM_FIELDS = new LinkedHashSet<>();
    /** 颜色预设允许改的字段（契约 §5.6）。 */
    private static final Set<String> COLOR_FIELDS = new LinkedHashSet<>();

    static {
        ANIM_FIELDS.add("animationLength");
        ANIM_FIELDS.add("shortAnimationLength");
        ANIM_FIELDS.add("trailSize");
        ANIM_FIELDS.add("glowLayers");
        ANIM_FIELDS.add("shadowBlurFactor");
        ANIM_FIELDS.add("glowOpacity");

        COLOR_FIELDS.add("tailColor");
        COLOR_FIELDS.add("shadowColor");
    }

    private static NeovideCursorSettings svc;
    private static NeovideCursorConfigurable conf;
    private static VUi ui;

    public static void main(String[] args) {
        System.out.println("=== V-PRESET：正交性 / 取值 / 网格 / 不持久化 ===");
        V0Env.install();
        svc = V0Env.svc;
        conf = new NeovideCursorConfigurable();
        ui = VUi.build(conf);

        presetsDefaultEqualToConfig();
        animOrthogonal();
        colorOrthogonal();
        gridCoverage();
        noFourthSource();

        System.out.println();
        System.out.printf("=== V-PRESET 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-PRESET RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-PRESET RESULT: ALL PASS");
        System.exit(0);
    }

    // ------------------------------------------------------- V4 预设 1 == 默认值

    private static void presetsDefaultEqualToConfig() {
        System.out.println();
        System.out.println("--- V4：预设 1（原版粉色）必须逐位等于 NeovideConfig 默认值 ---");
        NeovideConfig cfg = new NeovideConfig();
        CursorPresets.AnimPreset a0 = CursorPresets.ANIM_PRESETS[0];
        CursorPresets.ColorPreset c0 = CursorPresets.COLOR_PRESETS[0];

        check("V4-1 animationLength 预设1 == NeovideConfig 默认值",
                Float.floatToRawIntBits(a0.animationLength())
                        == Float.floatToRawIntBits(cfg.animationLength),
                bits(a0.animationLength()) + " vs " + bits(cfg.animationLength));
        check("V4-2 shortAnimationLength 预设1 == 默认值",
                Float.floatToRawIntBits(a0.shortAnimationLength())
                        == Float.floatToRawIntBits(cfg.shortAnimationLength),
                bits(a0.shortAnimationLength()));
        check("V4-3 trailSize 预设1 == 默认值",
                Float.floatToRawIntBits(a0.trailSize()) == Float.floatToRawIntBits(cfg.trailSize),
                bits(a0.trailSize()));
        check("V4-4 shadowBlurFactor 预设1 == 默认值",
                Float.floatToRawIntBits(a0.shadowBlurFactor())
                        == Float.floatToRawIntBits(cfg.shadowBlurFactor),
                bits(a0.shadowBlurFactor()));
        check("V4-5 glowOpacity 预设1 == 默认值",
                Float.floatToRawIntBits(a0.glowOpacity()) == Float.floatToRawIntBits(cfg.glowOpacity),
                bits(a0.glowOpacity()));
        check("V4-6 glowLayers 预设1 == 默认值", a0.glowLayers() == cfg.glowLayers,
                a0.glowLayers() + " vs " + cfg.glowLayers);
        check("V4-7 tailColor 预设1 == 默认值", c0.tailColor().equalsIgnoreCase(cfg.tailColor),
                c0.tailColor() + " vs " + cfg.tailColor);
        check("V4-8 shadowColor 预设1 == 默认值", c0.shadowColor().equalsIgnoreCase(cfg.shadowColor),
                c0.shadowColor() + " vs " + cfg.shadowColor);
        check("V4-9 预设表数量：动画 7 + 颜色 5",
                CursorPresets.ANIM_PRESETS.length == 7 && CursorPresets.COLOR_PRESETS.length == 5,
                "anim=" + CursorPresets.ANIM_PRESETS.length + " color=" + CursorPresets.COLOR_PRESETS.length);

        // State 的初值必须与 NeovideConfig 一致（干净安装的路径）
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        check("V4-10 State 初值 animationLength == 默认值",
                Float.floatToRawIntBits(s.animationLength) == Float.floatToRawIntBits(cfg.animationLength),
                bits(s.animationLength));
        check("V4-11 State 初值 shortAnimationLength == 默认值",
                Float.floatToRawIntBits(s.shortAnimationLength)
                        == Float.floatToRawIntBits(cfg.shortAnimationLength),
                bits(s.shortAnimationLength));

        // 全部预设的 HEX 必须大写（契约 §5.6）
        boolean upper = true;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            upper &= p.tailColor().equals(p.tailColor().toUpperCase(java.util.Locale.ROOT))
                    && p.shadowColor().equals(p.shadowColor().toUpperCase(java.util.Locale.ROOT));
        }
        check("V4-12 5 个颜色预设的 HEX 全部大写", upper, "");
    }

    // ------------------------------------------------------- V5 正交性（动画）

    private static void animOrthogonal() {
        System.out.println();
        System.out.println("--- V5a：换动画预设 → 颜色字段逐位不变 ---");
        int bad = 0;
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            svc.getState().tailColor = "#123456";
            svc.getState().shadowColor = "#ABCDEF";
            conf.reset();
            ui = VUi.build(conf);
            Map<String, String> before = VFields.snapshot(conf.pendingForProbe());

            ui.combos.get(0).setSelectedIndex(i);
            Map<String, String> after = VFields.snapshot(conf.pendingForProbe());

            int changedColor = VFields.countDiff(before, after, complement(COLOR_FIELDS));
            boolean colorSame = before.get("tailColor").equals(after.get("tailColor"))
                    && before.get("shadowColor").equals(after.get("shadowColor"));
            if (changedColor != 0 || !colorSame) {
                bad++;
            }
            System.out.println("    " + (colorSame ? "PASS " : "FAIL ")
                    + "预设[" + i + "] " + CursorPresets.ANIM_PRESETS[i].name()
                    + "  tail=" + after.get("tailColor") + "  shadow=" + after.get("shadowColor"));
            // 顺便把 6 个动画字段打印出来（逐字段 before → after，肉眼可核）
            for (String f : ANIM_FIELDS) {
                System.out.println("         " + String.format("%-22s", f)
                        + before.get(f) + "  ->  " + after.get(f));
            }
        }
        check("V5a-1 7 个动画预设都不改任何颜色字段（逐位）", bad == 0, bad + " 个预设污染颜色");

        // 动画预设也不许碰 stretchRatioK / trailMode（契约 §5.3）
        System.out.println();
        System.out.println("--- V5a2：换动画预设不得改 stretchRatioK / trailMode ---");
        int bad2 = 0;
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            NeovideCursorSettings.State st = svc.getState();
            st.stretchRatioK = 4.5f;
            st.trailMode = NeovideConfig.TrailMode.SPRING;
            conf.reset();
            ui = VUi.build(conf);
            ui.combos.get(0).setSelectedIndex(i);
            NeovideCursorSettings.State p = conf.pendingForProbe();
            boolean ok = Float.floatToRawIntBits(p.stretchRatioK)
                    == Float.floatToRawIntBits(4.5f) && p.trailMode == NeovideConfig.TrailMode.SPRING;
            if (!ok) {
                bad2++;
            }
            System.out.println("    " + (ok ? "PASS " : "FAIL ") + CursorPresets.ANIM_PRESETS[i].name()
                    + " stretchRatioK=" + bits(p.stretchRatioK) + " trailMode=" + p.trailMode);
        }
        check("V5a2 7 个动画预设都不改 stretchRatioK / trailMode", bad2 == 0, bad2 + " 个越界");
    }

    // ------------------------------------------------------- V5 正交性（颜色）

    private static void colorOrthogonal() {
        System.out.println();
        System.out.println("--- V5b：换颜色预设 → 动画字段逐位不变 ---");
        int bad = 0;
        for (int i = 0; i < CursorPresets.COLOR_PRESETS.length; i++) {
            NeovideCursorSettings.State st = svc.getState();
            st.animationLength = 0.28f;
            st.shortAnimationLength = 0.04f;
            st.trailSize = 0.30f;
            st.glowLayers = 6;
            st.shadowBlurFactor = 0.25f;
            st.glowOpacity = 0.45f;
            st.stretchRatioK = 6.0f;
            conf.reset();
            ui = VUi.build(conf);
            Map<String, String> before = VFields.snapshot(conf.pendingForProbe());

            ui.combos.get(1).setSelectedIndex(i);
            Map<String, String> after = VFields.snapshot(conf.pendingForProbe());

            int changed = VFields.countDiff(before, after, complement(ANIM_FIELDS, "stretchRatioK"));
            if (changed != 0) {
                bad++;
            }
            System.out.println("    " + (changed == 0 ? "PASS " : "FAIL ")
                    + "颜色预设[" + i + "] " + CursorPresets.COLOR_PRESETS[i].name()
                    + "  tail=" + after.get("tailColor") + "  shadow=" + after.get("shadowColor"));
            for (String f : ANIM_FIELDS) {
                System.out.println("         " + String.format("%-22s", f)
                        + before.get(f) + "  ->  " + after.get(f));
            }
            System.out.println("         " + String.format("%-22s", "stretchRatioK")
                    + before.get("stretchRatioK") + "  ->  " + after.get("stretchRatioK"));
        }
        check("V5b-1 5 个颜色预设都不改任何动画字段 / stretchRatioK（逐位）", bad == 0,
                bad + " 个预设越界");
    }

    // ------------------------------------------------------- V3 网格

    private static void gridCoverage() {
        System.out.println();
        System.out.println("--- V3：默认值与全部预设值都必须落在滑块刻度网格上 ---");
        // 第 11 项 typingSmoothness 是 0.9.6 / M4 新增的「输入平滑」（刻度 0–100，步长 5）
        int[] steps = {50, 25, 5, 5, 1, 5, 5, 5, 1, 1, 5};
        int[] mins = {0, 0, 0, 0, 1, 5, 0, 10, 5, 2, 0};
        int[] maxs = {500, 1000, 100, 100, 12, 60, 100, 80, 35, 25, 100};
        // 每个滑块参与的全部取值（默认 + 预设）
        java.util.List<int[]> values = new java.util.ArrayList<>();
        values.add(new int[]{50});                       // cursorDisappearDelay 默认
        values.add(new int[]{75});                       // cursorFadeOutDuration 默认
        values.add(new int[]{100});                      // tailOpacity 默认
        java.util.List<Integer> glowOpacity = new java.util.ArrayList<>();
        java.util.List<Integer> glowLayers = new java.util.ArrayList<>();
        java.util.List<Integer> blur = new java.util.ArrayList<>();
        java.util.List<Integer> trailSize = new java.util.ArrayList<>();
        java.util.List<Integer> animLen = new java.util.ArrayList<>();
        java.util.List<Integer> shortLen = new java.util.ArrayList<>();
        java.util.List<Integer> stretch = new java.util.ArrayList<>();
        glowOpacity.add(55);
        glowLayers.add(8);
        blur.add(20);
        trailSize.add(100);
        animLen.add(10);
        shortLen.add(11);
        stretch.add(45);
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            glowOpacity.add(p.glowOpacityTicks());
            glowLayers.add(p.glowLayers());
            blur.add(p.shadowBlurFactorTicks());
            trailSize.add(p.trailSizeTicks());
            animLen.add(p.animationLengthTicks());
            shortLen.add(p.shortAnimationLengthTicks());
        }

        int violations = 0;
        violations += gridCheck("cursorDisappearDelay", values.get(0), mins[0], maxs[0], steps[0]);
        violations += gridCheck("cursorFadeOutDuration", values.get(1), mins[1], maxs[1], steps[1]);
        violations += gridCheck("tailOpacity", values.get(2), mins[2], maxs[2], steps[2]);
        violations += gridCheck("glowOpacity", toArray(glowOpacity), mins[3], maxs[3], steps[3]);
        violations += gridCheck("glowLayers", toArray(glowLayers), mins[4], maxs[4], steps[4]);
        violations += gridCheck("shadowBlurFactor", toArray(blur), mins[5], maxs[5], steps[5]);
        violations += gridCheck("trailSize", toArray(trailSize), mins[6], maxs[6], steps[6]);
        violations += gridCheck("stretchRatioK", toArray(stretch), mins[7], maxs[7], steps[7]);
        violations += gridCheck("animationLength", toArray(animLen), mins[8], maxs[8], steps[8]);
        violations += gridCheck("shortAnimationLength", toArray(shortLen), mins[9], maxs[9], steps[9]);
        // 输入平滑没有预设值（预设不写它），只校验默认 0 落在网格上
        violations += gridCheck("typingSmoothness", new int[]{0}, mins[10], maxs[10], steps[10]);

        check("V3-1 默认值 + 全部预设值的越界数 = 0", violations == 0, "越界 " + violations + " 项");

        // 同时核对面板里 10 个 JSlider 的真实量程与上面这张表一致
        int mismatch = 0;
        for (int i = 0; i < ui.sliders.size(); i++) {
            JSlider s = ui.sliders.get(i);
            if (s.getMinimum() != mins[i] || s.getMaximum() != maxs[i]) {
                mismatch++;
                System.out.println("    FAIL 量程不符 " + VUi.nameOf(i)
                        + " 面板=[" + s.getMinimum() + "," + s.getMaximum()
                        + "] 契约=[" + mins[i] + "," + maxs[i] + "]");
            }
        }
        check("V3-2 面板 11 个滑块的量程与契约 §6.2 + M4 增补逐项一致", mismatch == 0, mismatch + " 项不符");
    }

    private static int[] toArray(java.util.List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }

    private static int gridCheck(String name, int[] vals, int min, int max, int step) {
        int bad = 0;
        for (int v : vals) {
            boolean onGrid = v >= min && v <= max && (v - min) % step == 0;
            if (!onGrid) {
                bad++;
                System.out.println("    FAIL " + name + " 值 " + v + " 不在网格 ["
                        + min + "," + max + "] step=" + step);
            }
        }
        if (bad == 0) {
            System.out.println("    PASS " + String.format("%-22s", name) + " 全部 " + vals.length
                    + " 个取值都在网格上（[" + min + "," + max + "] step=" + step + "）");
        }
        return bad;
    }

    // ------------------------------------------------------- V14 / V15 无第四真相源

    private static void noFourthSource() {
        System.out.println();
        System.out.println("--- V14/V15：无第四真相源 ---");
        int fields = 0;
        for (java.lang.reflect.Field f : NeovideCursorSettings.State.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isPublic(f.getModifiers())
                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                fields++;
            }
        }
        check("V14-1 State 的 public 实例字段数 == 19（stateVersion + 16 既有 + 2 新增）",
                fields == 19, "fields=" + fields);

        boolean hasPresetField = false;
        for (java.lang.reflect.Field f : NeovideCursorSettings.State.class.getDeclaredFields()) {
            if (f.getName().toLowerCase(java.util.Locale.ROOT).contains("preset")) {
                hasPresetField = true;
            }
        }
        check("V14-2 State 里没有任何 preset 字段（预设是纯动作，不持久化）", !hasPresetField, "");

        // Tools 与应用器两条写入路径的匹配判据必须同源
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        CursorPresets.AnimPreset match = CursorPresetApplier.matchAnimPreset(s);
        check("V15-1 默认值匹配到预设 1（原版粉色）",
                match == CursorPresets.ANIM_PRESETS[0],
                match == null ? "null" : match.name());
        CursorPresets.ColorPreset cmatch = CursorPresetApplier.matchColorPreset(s);
        check("V15-2 默认值匹配到颜色预设 1",
                cmatch == CursorPresets.COLOR_PRESETS[0], cmatch == null ? "null" : cmatch.name());

        // 任一动画预设应用后必须能反查回来（否则下拉永远显示「自定义」）
        int unmatched = 0;
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            CursorPresetApplier.applyAnimPreset(p);
            if (CursorPresetApplier.matchAnimPreset(svc.getState()) != p) {
                unmatched++;
                System.out.println("    FAIL 预设 " + p.name() + " 应用后匹配不回来");
            }
        }
        check("V15-3 7 个动画预设应用后都能被 matchAnimPreset 反查回来", unmatched == 0,
                unmatched + " 个匹配不上");

        int cunmatched = 0;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            CursorPresetApplier.applyColorPreset(p);
            if (CursorPresetApplier.matchColorPreset(svc.getState()) != p) {
                cunmatched++;
                System.out.println("    FAIL 颜色预设 " + p.name() + " 应用后匹配不回来");
            }
        }
        check("V15-4 5 个颜色预设应用后都能被 matchColorPreset 反查回来", cunmatched == 0,
                cunmatched + " 个匹配不上");

        // Tools 路径（全量替换）也必须正交
        svc.getState().tailColor = "#0F0F0F";
        svc.getState().shadowColor = "#F0F0F0";
        CursorPresetApplier.applyAnimPreset(CursorPresets.ANIM_PRESETS[6]);
        boolean toolsColorSame = "#0F0F0F".equalsIgnoreCase(svc.getState().tailColor)
                && "#F0F0F0".equalsIgnoreCase(svc.getState().shadowColor);
        check("V15-5 Tools 应用动画预设不改颜色（与面板同源的正交性）", toolsColorSame,
                svc.getState().tailColor + " / " + svc.getState().shadowColor);

        svc.getState().glowLayers = 5;
        svc.getState().animationLength = 0.22f;
        CursorPresetApplier.applyColorPreset(CursorPresets.COLOR_PRESETS[3]);
        boolean toolsAnimSame = svc.getState().glowLayers == 5
                && Float.floatToRawIntBits(svc.getState().animationLength)
                        == Float.floatToRawIntBits(0.22f);
        check("V15-6 Tools 应用颜色预设不改动画字段", toolsAnimSame,
                "glowLayers=" + svc.getState().glowLayers
                        + " animationLength=" + bits(svc.getState().animationLength));
    }

    // ------------------------------------------------------- 工具

    private static Set<String> complement(Set<String> keep) {
        return complement(keep, new String[0]);
    }

    /** 返回「除 keep 之外」的字段集合（这些字段必须 SAME）。 */
    private static Set<String> complement(Set<String> keep, String... extraKeep) {
        Set<String> all = VFields.snapshot(new NeovideCursorSettings.State()).keySet();
        Set<String> out = new LinkedHashSet<>(all);
        out.removeAll(keep);
        for (String e : extraKeep) {
            out.remove(e);
        }
        return out;
    }

    private static String bits(float f) {
        return f + "(0x" + Integer.toHexString(Float.floatToRawIntBits(f)) + ")";
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
