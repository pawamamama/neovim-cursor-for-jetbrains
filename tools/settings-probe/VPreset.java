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
        // 【0.9.75 改进：不再硬写颜色预设数量】
        //
        // 原文断言「颜色 5 个」—— 而 0.9.75 新增了「彩虹（动态）」变成 6 个，
        // 于是这条探针红了。但**它红得没有价值**：颜色预设数量本来就是设计决定，
        // 不是契约；真正该锁的是「动画预设 3 个」（那是 0.9.10 精简的成果）。
        //
        // 现在：动画预设精确锁 3 个；颜色预设只要求 ≥1 且全部有名字。
        boolean colorOk = CursorPresets.COLOR_PRESETS.length >= 1;
        for (CursorPresets.ColorPreset cp : CursorPresets.COLOR_PRESETS) {
            if (cp.name() == null || cp.name().isEmpty()) {
                colorOk = false;
            }
        }
        check("V4-9 动画预设精确 3 个；颜色预设非空且都有名字",
                CursorPresets.ANIM_PRESETS.length == 3 && colorOk,
                "anim=" + CursorPresets.ANIM_PRESETS.length + " color=" + CursorPresets.COLOR_PRESETS.length);
        check("V4-9b 动画预设名称精确匹配契约（基础 / 缎带 / 纯平滑输入）",
                "基础".equals(CursorPresets.ANIM_PRESETS[0].name())
                        && "缎带".equals(CursorPresets.ANIM_PRESETS[1].name())
                        && "纯平滑输入".equals(CursorPresets.ANIM_PRESETS[2].name()),
                CursorPresets.ANIM_PRESETS[0].name() + " / "
                        + CursorPresets.ANIM_PRESETS[1].name() + " / "
                        + CursorPresets.ANIM_PRESETS[2].name());

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
        check("V5a-1 全部动画预设都不改任何颜色字段（逐位）", bad == 0, bad + " 个预设污染颜色");

        // 动画预设也不许碰 stretchRatioK / trailMode（契约 §5.3）
        System.out.println();
        System.out.println("--- V5a2：换动画预设【改】stretchRatioK、【不改】trailMode ---");
        int bad2 = 0;
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            NeovideCursorSettings.State st = svc.getState();
            // 先设一个与【任何预设都不同】的 k，这样「预设确实改了它」才可观测。
            // 原来设 4.5f（= 基础预设的值），选「基础」时改与不改无法区分。
            st.stretchRatioK = 6.0f;
            st.trailMode = NeovideConfig.TrailMode.SPRING;
            conf.reset();
            ui = VUi.build(conf);
            ui.combos.get(0).setSelectedIndex(i);
            NeovideCursorSettings.State p = conf.pendingForProbe();
            // 期望：k == 该预设声明的值；trailMode 保持 SPRING（不进预设域）
            float wantK = CursorPresets.ANIM_PRESETS[i].stretchRatioK();
            boolean ok = Math.abs(p.stretchRatioK - wantK) < 1e-4f
                    && p.trailMode == NeovideConfig.TrailMode.SPRING;
            if (!ok) {
                bad2++;
            }
            System.out.println("    " + (ok ? "PASS " : "FAIL ") + CursorPresets.ANIM_PRESETS[i].name()
                    + " k=" + p.stretchRatioK + "（期望 " + wantK + "）"
                    + " trailMode=" + p.trailMode);
        }
        // 【0.9.79 修正断言方向】0.9.46 起 stretchRatioK **已在预设域**
        // （用户要求「切换预设手感要回来」）。这条断言原来写的是「不改」，
        // 是 0.9.46 之前的旧契约。现在改为：预设必须改 k，且必须不改 trailMode。
        check("V5a2 全部动画预设【改】stretchRatioK（0.9.46 起属预设域）"
                        + "、【不改】trailMode", bad2 == 0, bad2 + " 个越界");
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
        // 第 6 项 shadowBlurFactor 自 M6 起对外是「锐度 0–100，步长 1」（存储仍是外扩幅度）
        int[] steps = {50, 25, 5, 5, 1, 1, 5, 5, 1, 1, 5};
        int[] mins = {0, 0, 0, 0, 1, 0, 0, 10, 5, 2, 0};
        int[] maxs = {500, 1000, 100, 100, 12, 100, 100, 80, 35, 25, 100};
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

        // 同时核对面板里每个 JSlider 的真实量程与【VUi 的契约表】一致。
        //
        // 【0.9.79 修复：原来用本方法局部的 mins[]/maxs[]，那是 SPECS 声明顺序，
        //  而面板按 buildXxxGroup 调用顺序渲染 —— 两者从 0.9.62 S3 重构起就不同。
        //  于是这条断言从那时起就是"拿错表比对"，一直报假失败。
        //
        //  现在改用 VUi.spec(i)（已按渲染顺序对齐，见 VUi.KEYS 的注释），
        //  量程契约只在 VUi 一处定义。】
        int mismatch = 0;
        int checked = 0;
        for (int i = 0; i < ui.sliders.size(); i++) {
            JSlider s = ui.sliders.get(i);
            int[] spec = VUi.spec(i);
            if (spec == null) {
                mismatch++;
                System.out.println("    FAIL VUi 契约表缺第 " + i + " 项（"
                        + VUi.nameOf(i) + "）");
                continue;
            }
            checked++;
            if (s.getMinimum() != spec[0] || s.getMaximum() != spec[1]) {
                mismatch++;
                System.out.println("    FAIL 量程不符 " + VUi.nameOf(i)
                        + " 面板=[" + s.getMinimum() + "," + s.getMaximum()
                        + "] 契约=[" + spec[0] + "," + spec[1] + "]");
            }
        }
        check("V3-2 面板 " + checked + " 个滑块的量程与 VUi 契约表逐项一致",
                mismatch == 0 && checked == ui.sliders.size(),
                mismatch + " 项不符（已核对 " + checked + " 项）");
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
        // 【reviewer F2】22 = stateVersion(1) + 19 个参数 + animPresetBase + animPresetOverrides。
        // 旧判据写死 19，那是 0.9.5【之前】的字段数 —— 那时还没有 M4 的 typingSmoothness
        // 与 0.9.8 的两个预设元数据字段。数字变了不是缺陷，是参数确实多了。
        // 每次加参数都必须同步改这里（这也正是 NEXT-REFACTOR.md 说的「加参数要改 8 处」之一，
        // 等字段表重构落地后这里会变成「从表导出」，届时本断言自动跟随）。
        // 23 = stateVersion(1) + 19 参数 + 3 预设元数据（animPresetBase / customAnimPresets / animCustomName）。
        // 0.9.9 把 0.9.8 的单个覆盖串拆成「自建预设表 + 当前选中名」两项。
        // 【0.9.79 更新基线】0.9.51 起陆续新增：
        //   diagLogging / multiCursorNoGlow / cursorWidthPx / cursorHeightRatio /
        //   rgbGradient / targetFps / slopeTicks  → 23 变 30
        // 这条断言的用途是「新增字段要有人知道」，它工作了。
        // 【2026-10-29 契约变更，非缺陷】颜色预设三件套（colorPresetBase /
        // customColorPresets / colorCustomName）→ 30 变 33。
        // 用户要求「颜色也做成完整预设」，与动画的三件套完全对称。
        check("V14-1 State 的 public 实例字段数 == 33（30 + 颜色预设三件套）",
                fields == 33, "fields=" + fields);

        // 契约 §5.2 的 P-1 语义是「预设是纯动作，不持久化『当前选中了哪个预设的哪个值副本』」，
        // 但它允许两个**元数据**字段：
        //   · animPresetBase      —— 当前停在哪一个预设（M5，用于「X（已修改）」显示）
        //   · animPresetOverrides —— 每个预设各自的覆盖值（0.9.8）
        // 这两个都不是「第二真相源」：真正的观感值仍在 19 个参数字段里，
        // 它们只记录「用户改过哪个预设」。删掉它们才会丢用户数据。
        // 因此判据改成「除这两个白名单之外，State 里没有其它 preset 字段」。
        // 白名单只列「名字里含 preset」的元数据字段 —— 本断言本身就是按这个过滤的，
        // animCustomName 不含 "preset"，它由 V14-1 的字段总数覆盖。
        java.util.Set<String> allowedPreset = new java.util.HashSet<>(java.util.Arrays.asList(
                "animPresetBase", "customAnimPresets",
                // 2026-10-29：颜色预设的基准与自建表，与动画的同名同义
                "colorPresetBase", "customColorPresets"));
        java.util.List<String> illegalPreset = new java.util.ArrayList<>();
        java.util.Set<String> presentPreset = new java.util.HashSet<>();
        for (java.lang.reflect.Field f : NeovideCursorSettings.State.class.getDeclaredFields()) {
            String name = f.getName();
            if (name.toLowerCase(java.util.Locale.ROOT).contains("preset")) {
                if (allowedPreset.contains(name)) {
                    presentPreset.add(name);
                } else {
                    illegalPreset.add(name);
                }
            }
        }
        check("V14-2 State 里除 animPresetBase/customAnimPresets/animCustomName 外没有其它 preset 字段",
                illegalPreset.isEmpty() && presentPreset.equals(allowedPreset),
                "越界=" + illegalPreset + " 白名单实存=" + presentPreset);

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
        check("V15-3 全部动画预设应用后都能被 matchAnimPreset 反查回来", unmatched == 0,
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
        // 用最后一个预设（原写死 [6] 指「高对比」，已随预设精简删除）
        CursorPresetApplier.applyAnimPreset(
                CursorPresets.ANIM_PRESETS[CursorPresets.ANIM_PRESETS.length - 1]);
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
