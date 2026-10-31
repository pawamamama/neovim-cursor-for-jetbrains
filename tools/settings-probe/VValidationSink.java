import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.openapi.diagnostic.Logger;

import javax.swing.JSlider;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V-VALIDATION-SINK —— 「校验下沉」（U-1 / U-2 / U-3）的守门探针（2026-10-31）。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>同一个「预设快照校验」原来在<b>三个调用方</b>各写一遍：
 * 面板选中 / 面板恢复默认值 / Tools 菜单。第 1 批只修了面板那一个，
 * Tools 入口原样保留缺陷（`plans/batch3-fix2.md` §七 的 U-1 / U-2）。
 * 本轮把校验下沉到唯一一层（{@code SettingsField.restoreSkipping}）之后，
 * 两条入口必须<b>给出完全相同的结果</b>，并且量程只有一份。
 *
 * <h2>八条断言</h2>
 *
 * <ul>
 *   <li><b>A-0</b>：日志捕获机制自检 —— 它红，则后面所有「必须有日志」的断言都不算数。</li>
 *   <li><b>T-1（U-1）</b>：Tools 菜单入口喂越界快照 → 值被夹回、有 warn、
 *       State 里<b>没有任何数值字段越界</b>。oracle 是 {@code loadState} 对同一份脏值的处理
 *       （不是抄 State 默认值，也不是抄面板的量程）。</li>
 *   <li><b>T-2（AC-004）</b>：同一份越界快照 + 同一份起始 State，分别从 Tools 与面板喂，
 *       全部 {@code PRESET_FIELDS} 的编码<b>逐项相同</b>。</li>
 *   <li><b>T-3（U-3 / AC-005）</b>：9 位 {@code #RRGGBBAA} 经 {@code loadState} 往返仍在
 *       （不是只看 apply），7 位仍工作，坏串仍回落默认。</li>
 *   <li><b>T-4（U-2）</b>：坏 HEX 从 Tools 入口 → 返回 false、两个颜色都不动、有 warn；
 *       同一份坏快照从面板入口 → 同样不写半套。</li>
 *   <li><b>S-1（AC-002）</b>：区间只有一份 —— 每个带区间的字段，{@code loadState} 对
 *       极小/极大值的处理必须等于<b>探针自己的</b>区间表端点；并且面板滑块的刻度端点
 *       换算回原始值后必须与 {@code loadState} 的端点相同（面板不再自带第二份量程）。</li>
 *   <li><b>S-2（AC-003）</b>：颜色合法性只有一份 —— {@code loadState} 的
 *       {@code sanitizeColor} 接受集合必须与 {@code SettingsField.isValidHexColor} 的
 *       接受集合逐项相同（9 组输入）。</li>
 *   <li><b>S-3</b>：下沉层返回的账（{@code applied} / {@code clamped} / {@code rejected}）
 *       必须能区分「写入 / 夹回 / 拒绝」三种情况，三项之和 == 快照里可识别的键数；
 *       并且<b>直接调字段的 decode 也会夹回</b> —— 那是「未来新增入口自动受保护」的可测形式。</li>
 * </ul>
 *
 * <p><b>为什么 S-1 / S-2 是行为断言而不是 grep 字面量</b>：字面量相同不代表语义相同
 * （面板的刻度端点与原始值是两套单位）。只有「喂进去、读回来、两边相等」才能抓住
 * 「数字抄对了但换算是反的」这类分歧。
 */
public final class VValidationSink {

    private static int pass;
    private static int fail;

    /** 捕获到的 warn / error（按消息内容断言）。 */
    private static final List<String> WARNS = Collections.synchronizedList(new ArrayList<>());

    /**
     * 一份「什么都坏」的动画快照：越界数值 + 一个类型非法的值。
     *
     * <p>用它同时验证三件事：越界被夹回、类型非法保持当前值、两者都留痕。
     */
    private static final String DIRTY_ANIM_SNAPSHOT =
            "glowLayers=99;animationLength=0.9;tailOpacity=5;"
                    + "shadowBlurFactor=99;stretchRatioK=0.1;slopeTicks=999;"
                    + "trailSize=0.4;glowOpacity=abc";

    /**
     * 探针自己的区间表（<b>oracle，不是第二份生产量程</b>）。
     *
     * <p>刻意<b>不</b>从生产字段表派生：那样「区间写错了」两边一起错，断言永远绿
     * （陷阱 4 的「假通过」）。这与 {@code VUi.SPEC}、{@code VDefaultFreeze.GOLDEN}
     * 是本项目一贯做法 —— 探针侧的表是**独立事实**，生产侧的表是被测对象。
     */
    private static final Object[][] RANGES = {
            // {key, 原始下界, 原始上界}
            {"cursorDisappearDelay", 0.0, 500.0},
            {"cursorFadeOutDuration", 0.0, 1.0},
            {"tailOpacity", 0.0, 1.0},
            {"glowOpacity", 0.0, 1.0},
            {"glowLayers", 1.0, 12.0},
            {"shadowBlurFactor", 0.05, 0.60},
            {"trailSize", 0.0, 1.0},
            {"stretchRatioK", 1.0, 8.0},
            {"animationLength", 0.05, 0.35},
            {"shortAnimationLength", 0.02, 0.25},
            {"typingSmoothness", 0.0, 1.0},
            {"cursorWidthPx", 1.0, 5.0},
            {"cursorHeightRatio", 0.3, 1.0},
            {"slopeTicks", 0.0, 100.0},
    };

    public static void main(String[] args) {
        // 【必须第一句】生产类的 static final Logger 在类首次被触达时就创建。
        Logger.setFactory(new CapturingFactory());

        System.out.println("=== V-VALIDATION-SINK：校验下沉（U-1 / U-2 / U-3）===");

        a0();
        toolsEntryClamps();
        twoEntriesAgree();
        nineDigitColorSurvivesRestart();
        badHexRejectedFromBothEntries();
        oneRangeTableOnly();
        oneColorRuleOnly();
        reportAccounting();

        finish();
    }

    // ==================== A-0 ====================

    private static void a0() {
        int m = mark();
        Logger.getInstance("vvalidationsink-selftest").warn("selftest-marker");
        check("A-0 日志捕获机制自检（它红 = 其余「必须有日志」的断言都不算数）",
                anyWarnSince(m, "selftest-marker"), "");
    }

    // ==================== T-1：Tools 菜单入口必须夹回 ====================

    private static void toolsEntryClamps() {
        System.out.println();
        System.out.println("--- T-1（U-1）：Tools 菜单入口喂越界快照，值必须被夹回 ---");

        // ---- oracle：把同一份脏数据交给 loadState，它给出的是「另一条入口的答案」 ----
        NeovideCursorSettings.State dirty = new NeovideCursorSettings.State();
        dirty.glowLayers = 99;
        dirty.animationLength = 0.9f;
        dirty.tailOpacity = 5.0f;
        dirty.shadowBlurFactor = 99f;
        dirty.stretchRatioK = 0.1f;
        dirty.slopeTicks = 999;
        NeovideCursorSettings oracle = new NeovideCursorSettings();
        oracle.loadState(dirty);
        NeovideCursorSettings.State o = oracle.getState();

        V0Env.install();
        seedCustomAnimPreset();
        int m = mark();
        boolean appliedOk = CursorPresetApplier.applyCustomAnimPreset("坏预设");

        NeovideCursorSettings.State s = V0Env.svc.getState();
        check("T-1a 越界快照经 Tools 入口应用后返回 true（有合法字段，不算整条失败）",
                appliedOk, "applyCustomAnimPreset=" + appliedOk);
        check("T-1b glowLayers=99 被夹回（必须 == loadState 对同一脏值的处理）",
                s.glowLayers == o.glowLayers, "state=" + s.glowLayers + " loadState=" + o.glowLayers);
        check("T-1c animationLength=0.9 被夹回", Math.abs(s.animationLength - o.animationLength) < 1e-6f,
                "state=" + s.animationLength + " loadState=" + o.animationLength);
        check("T-1d tailOpacity=5 被夹回", Math.abs(s.tailOpacity - o.tailOpacity) < 1e-6f,
                "state=" + s.tailOpacity + " loadState=" + o.tailOpacity);
        check("T-1e shadowBlurFactor=99 被夹回", Math.abs(s.shadowBlurFactor - o.shadowBlurFactor) < 1e-6f,
                "state=" + s.shadowBlurFactor + " loadState=" + o.shadowBlurFactor);
        check("T-1f stretchRatioK=0.1 被夹回", Math.abs(s.stretchRatioK - o.stretchRatioK) < 1e-6f,
                "state=" + s.stretchRatioK + " loadState=" + o.stretchRatioK);
        check("T-1g slopeTicks=999 被夹回", s.slopeTicks == o.slopeTicks,
                "state=" + s.slopeTicks + " loadState=" + o.slopeTicks);
        // 【为什么查四个子串，而不只是字段名】（2026-10-31 变异测试 M-6 实测）
        // 第一版只查 "glowLayers"。把下沉层里那条「值越界 → 已夹回」的 warn 改成静默之后，
        // 断言**仍然绿** —— 因为 Tools 入口自己还有一条「有 N 个参数越界，已按合法区间夹回：
        // glowLayers」，同样含这个字段名。也就是说那条断言只证明了「某处提到过这个字段」，
        // 没有证明「用户能看出从多少夹到了多少」。
        // 现在要求同一行日志里同时有：字段名、原值、夹后的值、以及「越界」二字 ——
        // 这正是 `SettingsField.noteClamped` 的职责（用户报问题时唯一可查的证据）。
        check("T-1h 夹回时留一条 warn，且写清是哪个字段、从多少夹到多少",
                anyWarnSince(m, "glowLayers", "越界", "99", "12"),
                "warn=" + brief(m));
        check("T-1i 类型非法的值（glowOpacity=abc）保持当前值、不改写",
                Math.abs(s.glowOpacity - 0.47f) < 1e-6f, "glowOpacity=" + s.glowOpacity);

        // 「State 不变脏」：夹回之后，没有任何带区间的字段越界。
        List<String> out = new ArrayList<>();
        for (Object[] r : RANGES) {
            SettingsField f = SettingsField.byKey((String) r[0]);
            double v = ((Number) f.readState(s)).doubleValue();
            double lo = (Double) r[1];
            double hi = (Double) r[2];
            if (Double.isNaN(v) || v < lo - 1e-6 || v > hi + 1e-6) {
                out.add(f.key + "=" + v + " 不∈[" + lo + "," + hi + "]");
            }
        }
        check("T-1j 应用后 State 里没有任何数值字段越界", out.isEmpty(),
                out.isEmpty() ? "全部落在区间内" : String.valueOf(out));
    }

    // ==================== T-2：两条入口必须同源 ====================

    private static void twoEntriesAgree() {
        System.out.println();
        System.out.println("--- T-2（AC-004）：同一份坏快照，Tools 与面板必须给出完全相同的结果 ---");

        // ---- A：Tools 菜单入口 ----
        V0Env.install();
        seedCustomAnimPreset();
        CursorPresetApplier.applyCustomAnimPreset("坏预设");
        Map<String, String> viaTools = encodePresetFields(V0Env.svc.getState());

        // ---- B：面板入口（下拉选中自定义预设）----
        V0Env.install();
        seedCustomAnimPreset();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        conf.animPresetComboForProbe().setSelectedIndex(CursorPresets.ANIM_PRESETS.length);
        Map<String, String> viaPanel = encodePresetFields(conf.pendingForProbe());

        List<String> diffs = new ArrayList<>();
        for (String key : viaTools.keySet()) {
            String a = viaTools.get(key);
            String b = viaPanel.get(key);
            if (!a.equalsIgnoreCase(b)) {
                diffs.add(key + " Tools=" + a + " 面板=" + b);
            }
        }
        check("T-2 两条入口结果逐项相同（预设域 " + viaTools.size() + " 个字段）",
                diffs.isEmpty(), diffs.isEmpty() ? "0 项不同" : String.valueOf(diffs));

        // 对照：这份快照确实「做了一点事」——否则两条都「什么都没写」也会被判相同。
        check("T-2b 对照：两条入口都不是空操作（trailSize=0.4 真的写进去了）",
                "0.4".equals(viaTools.get("trailSize")) && "0.4".equals(viaPanel.get("trailSize")),
                "Tools=" + viaTools.get("trailSize") + " 面板=" + viaPanel.get("trailSize"));
    }

    // ==================== T-3：9 位颜色必须经得住重启 ====================

    private static void nineDigitColorSurvivesRestart() {
        System.out.println();
        System.out.println("--- T-3（U-3 / AC-005）：9 位 #RRGGBBAA 必须经得住 loadState 往返 ---");

        String defaultTail = new NeovideCursorSettings.State().tailColor;

        NeovideCursorSettings.State s1 = new NeovideCursorSettings.State();
        s1.tailColor = "#FFC0CB80";
        NeovideCursorSettings svc1 = new NeovideCursorSettings();
        svc1.loadState(s1);
        check("T-3a 9 位颜色经 loadState 往返后仍在（不被打回默认色）",
                "#FFC0CB80".equals(svc1.getState().tailColor), "读回=" + svc1.getState().tailColor);

        NeovideCursorSettings.State s2 = new NeovideCursorSettings.State();
        s2.tailColor = "#ffc0cb80";
        NeovideCursorSettings svc2 = new NeovideCursorSettings();
        svc2.loadState(s2);
        check("T-3b 小写 9 位也保留，并统一成大写（与 7 位同一套规范化）",
                "#FFC0CB80".equals(svc2.getState().tailColor), "读回=" + svc2.getState().tailColor);

        NeovideCursorSettings.State s3 = new NeovideCursorSettings.State();
        s3.shadowColor = "#123456";
        NeovideCursorSettings svc3 = new NeovideCursorSettings();
        svc3.loadState(s3);
        check("T-3c 7 位仍然工作（没有为了支持 9 位把 7 位弄坏）",
                "#123456".equals(svc3.getState().shadowColor), "读回=" + svc3.getState().shadowColor);

        NeovideCursorSettings.State s4 = new NeovideCursorSettings.State();
        s4.tailColor = "not-a-color";
        NeovideCursorSettings svc4 = new NeovideCursorSettings();
        svc4.loadState(s4);
        check("T-3d 坏串仍然回落默认色（不能为了放开 9 位而放过非颜色）",
                defaultTail.equals(svc4.getState().tailColor), "读回=" + svc4.getState().tailColor);
    }

    // ==================== T-4：坏 HEX 从两条入口都必须被拒 ====================

    private static void badHexRejectedFromBothEntries() {
        System.out.println();
        System.out.println("--- T-4（U-2）：坏颜色快照，两条入口都必须拒绝且不写半套 ---");

        // ---- A：Tools 菜单入口 ----
        V0Env.install();
        NeovideCursorSettings.State s = V0Env.svc.getState();
        s.tailColor = "#123456";
        s.shadowColor = "#654321";
        s.customColorPresets = CustomPresets.put("", "坏颜色", "b0",
                "tailColor=not-a-color;shadowColor=#FFFFFF");
        int m = mark();
        boolean ok = CursorPresetApplier.applyCustomColorPreset("坏颜色");
        check("T-4a Tools 入口拒绝坏颜色快照（返回 false）", !ok, "返回=" + ok);
        check("T-4b Tools 入口不写半套：两个颜色都保持原值",
                "#123456".equals(s.tailColor) && "#654321".equals(s.shadowColor),
                "tail=" + s.tailColor + " shadow=" + s.shadowColor);
        check("T-4c Tools 入口拒绝时留一条 warn", anyWarnSince(m, "颜色") || anyWarnSince(m, "tailColor"),
                "warn=" + brief(m));

        // ---- B：面板入口（同一份坏快照）----
        V0Env.install();
        NeovideCursorSettings.State s2 = V0Env.svc.getState();
        s2.colorPresetBase = 0;
        s2.colorCustomName = "";
        s2.tailColor = "#123456";
        s2.shadowColor = "#654321";
        s2.customColorPresets = CustomPresets.put("", "坏颜色", "b0",
                "tailColor=not-a-color;shadowColor=#FFFFFF");
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        conf.colorPresetComboForProbe().setSelectedIndex(CursorPresets.COLOR_PRESETS.length);
        NeovideCursorSettings.State p = conf.pendingForProbe();
        check("T-4d 面板入口同样不写半套（与 Tools 入口同一份判据）",
                "#123456".equals(p.tailColor) && "#654321".equals(p.shadowColor),
                "tail=" + p.tailColor + " shadow=" + p.shadowColor);

        // ---- T-4e：名字不存在 ≠ 快照损坏（审查 L-1 的守门）----
        // 两种情况都返回 false、都不写颜色，但**给用户的结论必须不同**：
        // 「你的预设内容坏了」会让人去重建一份其实完好、只是被删掉的数据。
        V0Env.install();
        NeovideCursorSettings.State s3 = V0Env.svc.getState();
        s3.tailColor = "#123456";
        s3.shadowColor = "#654321";
        s3.customColorPresets = CustomPresets.put("", "完好预设", "b0",
                "tailColor=#112233;shadowColor=#445566");
        int m2 = mark();
        boolean ok2 = CursorPresetApplier.applyCustomColorPreset("已被删掉的预设");
        check("T-4e 名字不存在时：返回 false、颜色不动、日志说的是「不存在」而不是「内容损坏」",
                !ok2 && "#123456".equals(s3.tailColor) && "#654321".equals(s3.shadowColor)
                        && anyWarnSince(m2, "不存在") && !anyWarnSince(m2, "已损坏"),
                "返回=" + ok2 + " warn=" + brief(m2));
    }

    // ==================== S-1：区间只有一份 ====================

    private static void oneRangeTableOnly() {
        System.out.println();
        System.out.println("--- S-1（AC-002）：区间只有一份 —— loadState 的端点必须等于独立 oracle ---");

        List<String> bad = new ArrayList<>();
        for (Object[] r : RANGES) {
            String key = (String) r[0];
            double lo = (Double) r[1];
            double hi = (Double) r[2];
            double gotLo = loadExtreme(key, true);
            double gotHi = loadExtreme(key, false);
            if (Math.abs(gotLo - lo) > 1e-6 || Math.abs(gotHi - hi) > 1e-6) {
                bad.add(key + " 期望[" + lo + "," + hi + "] 实得[" + gotLo + "," + gotHi + "]");
            }
        }
        check("S-1a 每个带区间字段的两个端点，loadState 都夹到与独立 oracle 相同的位置",
                bad.isEmpty(), bad.isEmpty() ? "14 个字段全部一致" : String.valueOf(bad));

        // 合法值不得被改写（防止「一律夹到端点」这种假绿）
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.glowLayers = 7;
        s.animationLength = 0.21f;
        s.shadowBlurFactor = 0.33f;
        NeovideCursorSettings svc = new NeovideCursorSettings();
        svc.loadState(s);
        check("S-1b 区间内的值一位不改（不是「一律夹到端点」）",
                svc.getState().glowLayers == 7
                        && Float.compare(svc.getState().animationLength, 0.21f) == 0
                        && Float.compare(svc.getState().shadowBlurFactor, 0.33f) == 0,
                "glowLayers=" + svc.getState().glowLayers
                        + " animationLength=" + svc.getState().animationLength
                        + " shadowBlurFactor=" + svc.getState().shadowBlurFactor);

        // ---- 面板侧：滑块的刻度端点换算回原始值后，必须与 loadState 的端点相同 ----
        //
        // 这是「不许出现第二份量程表」的可测形式：面板曾经自带 26 个端点字面量，
        // 下沉后必须由字段表的区间导出。两个字段足够暴露方向错乱：
        //   · animationLength —— 正向换算（刻度 = 秒 × 100）
        //   · shadowBlurFactor —— 反向换算（锐度 0 = 最柔 0.60，锐度 100 = 最锐 0.05）
        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();

        List<String> sliderDiffs = new ArrayList<>();
        checkSliderEnds(ui, conf, "animationLength", 0.05f, 0.35f, sliderDiffs);
        checkSliderEnds(ui, conf, "shadowBlurFactor", 0.60f, 0.05f, sliderDiffs);
        // ---- S-1d：独立 oracle 的覆盖度必须自己守着 ----
        // 给字段加了 .range(...) 却忘了登记 RANGES 时，S-1a/T-1j 会**静默漏测**那个字段，
        // 门禁全绿而「区间只有一份」对它从未被验证（2026-10-31 审查 M-6）。
        java.util.Set<String> rangedInTable = new java.util.LinkedHashSet<>();
        for (SettingsField f : SettingsField.FIELDS) {
            if (f.hasRange()) {
                rangedInTable.add(f.key);
            }
        }
        java.util.Set<String> inOracle = new java.util.LinkedHashSet<>();
        for (Object[] r : RANGES) {
            inOracle.add((String) r[0]);
        }
        java.util.Set<String> missing = new java.util.LinkedHashSet<>(rangedInTable);
        missing.removeAll(inOracle);
        java.util.Set<String> extra = new java.util.LinkedHashSet<>(inOracle);
        extra.removeAll(rangedInTable);
        check("S-1d 独立 oracle(RANGES) 与字段表里带区间的字段集合完全相同（漏登记即红）",
                missing.isEmpty() && extra.isEmpty(),
                "漏登记=" + missing + " 多余的=" + extra + " 字段表=" + rangedInTable.size() + " 项");

        check("S-1c 面板滑块端点换算回原始值后与 loadState 的端点相同（没有第二份量程）",
                sliderDiffs.isEmpty(), sliderDiffs.isEmpty() ? "两个方向的换算都一致"
                        : String.valueOf(sliderDiffs));
    }

    /** 把滑块推到两端，读 pending 的原始值，与期望端点比对（按 tick 方向给出期望值）。 */
    private static void checkSliderEnds(VUi ui, NeovideCursorConfigurable conf, String key,
                                        float rawAtMinTick, float rawAtMaxTick, List<String> diffs) {
        JSlider sl = ui.sliderByKey(key);
        if (sl == null) {
            diffs.add(key + " 找不到滑块");
            return;
        }
        SettingsField f = SettingsField.byKey(key);
        sl.setValue(sl.getMinimum());
        float atMin = ((Number) f.readState(conf.pendingForProbe())).floatValue();
        sl.setValue(sl.getMaximum());
        float atMax = ((Number) f.readState(conf.pendingForProbe())).floatValue();
        if (Math.abs(atMin - rawAtMinTick) > 1e-6f) {
            diffs.add(key + " 最小刻度→" + atMin + " 期望 " + rawAtMinTick);
        }
        if (Math.abs(atMax - rawAtMaxTick) > 1e-6f) {
            diffs.add(key + " 最大刻度→" + atMax + " 期望 " + rawAtMaxTick);
        }
    }

    // ==================== S-2：颜色合法性只有一份 ====================

    private static void oneColorRuleOnly() {
        System.out.println();
        System.out.println("--- S-2（AC-003）：颜色合法性只有一份 —— loadState 与 isValidHexColor 同口径 ---");

        // 默认色是 #FFC0CB：所有用例都选「规范化后 ≠ 默认色」的串，
        // 否则「保留了」与「回落到默认」在结果上不可区分（恒真断言）。
        String[] inputs = {
                "#FF0000",       // 合法 7 位
                "#ff0000",       // 合法 7 位小写
                "#80FF0000",     // 合法 9 位
                "#ff000080",     // 合法 9 位小写
                " #FF0000 ",     // 合法（两端空白，sanitizeColor 会 trim）
                "FF0000",        // 非法：没有 #
                "#FF000",        // 非法：长度
                "#GG0000",       // 非法：非十六进制
                "#FF0000000",    // 非法：10 位
        };
        List<String> diffs = new ArrayList<>();
        String defaultTail = new NeovideCursorSettings.State().tailColor;
        for (String in : inputs) {
            boolean expectedAccept = SettingsField.isValidHexColor(in);
            NeovideCursorSettings.State s = new NeovideCursorSettings.State();
            s.tailColor = in;
            NeovideCursorSettings svc = new NeovideCursorSettings();
            svc.loadState(s);
            String got = svc.getState().tailColor;
            boolean actuallyKept = got != null && got.equalsIgnoreCase(in.trim())
                    && !got.equalsIgnoreCase(defaultTail);
            if (expectedAccept != actuallyKept) {
                diffs.add("「" + in + "」isValidHexColor=" + expectedAccept + " loadState 保留=" + actuallyKept
                        + "（读回 " + got + "）");
            }
        }
        check("S-2 9 组输入的接受集合逐项相同（含 7 位 / 9 位 / 空白 / 非法）",
                diffs.isEmpty(), diffs.isEmpty() ? "9/9 一致" : String.valueOf(diffs));
    }

    // ==================== S-3：报告口径 ====================

    private static void reportAccounting() {
        System.out.println();
        System.out.println("--- S-3：下沉层返回的账必须能区分「写入 / 夹回 / 拒绝」 ---");

        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.glowLayers = 3;
        s.trailSize = 0.8f;
        s.tailOpacity = 0.4f;
        SettingsField.RestoreReport r = SettingsField.restoreSkippingReported(
                s, "glowLayers=99;trailSize=0.4;tailOpacity=abc");

        check("S-3a applied 只数真正写进去的（越界夹回算写入，类型非法不算）",
                r.applied == 2, "applied=" + r.applied);
        check("S-3b clamped 里只有越界的那个字段",
                r.clamped.size() == 1 && "glowLayers".equals(r.clamped.get(0)),
                "clamped=" + r.clamped);
        check("S-3c rejected 里只有类型非法的那个字段",
                r.rejected.size() == 1 && "tailOpacity".equals(r.rejected.get(0)),
                "rejected=" + r.rejected);
        check("S-3d applied + rejected == 快照里可识别的键数（clamped ⊂ applied，不能再加一次）",
                r.applied + r.rejected.size() == 3,
                "applied=" + r.applied + " rejected=" + r.rejected.size());
        check("S-3e 夹回后的值正确（99 → 12）", s.glowLayers == 12, "glowLayers=" + s.glowLayers);

        // 结构性：将来新增的入口只要走 decode，就自动获得同一套校验 ——
        // 这正是「下沉」与「补丁」的区别。直接在字段上验一次。
        NeovideCursorSettings.State s2 = new NeovideCursorSettings.State();
        boolean ok = SettingsField.byKey("glowLayers").decode(s2, "99");
        check("S-3f 直接调字段的 decode 也会夹回（未来新增入口自动受保护）",
                ok && s2.glowLayers == 12, "decode=" + ok + " glowLayers=" + s2.glowLayers);

        // ---- S-3g：快照里同一个键写两次，只记一次（审查 L-2 的守门）----
        // 面板的提示文案是「有 N 个参数超出正常范围」；重复计数会让 N 撒谎。
        NeovideCursorSettings.State s3 = new NeovideCursorSettings.State();
        SettingsField.RestoreReport r3 = SettingsField.restoreSkippingReported(
                s3, "glowLayers=99;glowLayers=99");
        check("S-3g 同一字段在快照里重复出现时，clamped 只记一次（提示里的个数不能撒谎）",
                r3.clamped.size() == 1 && s3.glowLayers == 12,
                "clamped=" + r3.clamped + " glowLayers=" + s3.glowLayers);
    }

    // ==================== helpers ====================

    /** 布一份「越界快照」的自定义动画预设，并把起始 State 设成一组合法值。 */
    private static void seedCustomAnimPreset() {
        NeovideCursorSettings.State s = V0Env.svc.getState();
        s.animPresetBase = 0;
        s.animCustomName = "";
        s.glowLayers = 3;
        s.animationLength = 0.2f;
        s.tailOpacity = 0.47f;
        s.shadowBlurFactor = 0.2f;
        s.stretchRatioK = 4.5f;
        s.slopeTicks = 0;
        s.trailSize = 0.8f;
        s.glowOpacity = 0.47f;     // 刻意避开 State 默认值 0.55：否则「保持」与「被重置」不可区分
        s.customAnimPresets = CustomPresets.put("", "坏预设", "b0", DIRTY_ANIM_SNAPSHOT);
    }

    /** 预设域全部字段的编码（按 key，顺序 = PRESET_FIELDS）。 */
    private static Map<String, String> encodePresetFields(NeovideCursorSettings.State s) {
        Map<String, String> m = new LinkedHashMap<>();
        for (SettingsField f : SettingsField.PRESET_FIELDS) {
            m.put(f.key, f.encode(s));
        }
        return m;
    }

    /**
     * 把一个极端值<b>直接</b>写进 State 字段，跑 loadState，读回它被夹到哪儿。
     *
     * <p><b>为什么用反射直接写字段，而不是 {@code decode}</b>：要模拟的是「XML 里存着
     * 手改出来的坏值」，那条路径根本不经过 {@code decode}。若用 {@code decode} 夹具，
     * 校验下沉之后它自己就会先夹一遍 —— loadState 的夹取就再也测不到了（假通过）。
     */
    private static double loadExtreme(String key, boolean low) {
        SettingsField f = SettingsField.byKey(key);
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        try {
            Field sf = NeovideCursorSettings.State.class.getField(key);
            if (f.kind == SettingsField.Kind.INT) {
                sf.setInt(s, low ? Integer.MIN_VALUE : Integer.MAX_VALUE);
            } else {
                sf.setFloat(s, low ? -1e9f : 1e9f);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("夹具写不进 State 字段 " + key, e);
        }
        NeovideCursorSettings svc = new NeovideCursorSettings();
        svc.loadState(s);
        return ((Number) f.readState(svc.getState())).doubleValue();
    }

    private static int mark() {
        return WARNS.size();
    }

    /** 同一行日志里必须同时出现全部片段（片段可以来自不同位置，但必须是同一条 warn）。 */
    private static boolean anyWarnSince(int m, String... needles) {
        for (int i = Math.min(m, WARNS.size()); i < WARNS.size(); i++) {
            String w = WARNS.get(i);
            boolean all = true;
            for (String needle : needles) {
                if (!w.contains(needle)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static String brief(int m) {
        if (WARNS.size() <= m) {
            return "无";
        }
        String w = WARNS.get(WARNS.size() - 1);
        return w.length() > 120 ? w.substring(0, 120) + "…" : w;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        }
    }

    private static void finish() {
        System.out.println();
        System.out.printf("=== V-VALIDATION-SINK 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-VALIDATION-SINK RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-VALIDATION-SINK RESULT: ALL PASS");
        System.exit(0);
    }

    // ==================== 日志捕获 ====================

    private static final class CapturingFactory implements Logger.Factory {
        @Override
        public Logger getLoggerInstance(String category) {
            return new Capturing(category);
        }
    }

    private static final class Capturing extends Logger {
        private final String category;

        Capturing(String category) {
            this.category = category;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public void debug(String message, Throwable t) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void info(String message, Throwable t) {
        }

        @Override
        public void warn(String message) {
            WARNS.add(category + ": " + message);
        }

        @Override
        public void warn(String message, Throwable t) {
            WARNS.add(category + ": " + message);
        }

        @Override
        public void error(String message) {
            WARNS.add(category + ": " + message);
        }

        @Override
        public void error(String message, Throwable t, String... details) {
            WARNS.add(category + ": " + message);
        }
    }
}
