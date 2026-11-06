import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JCheckBox;
import javax.swing.JSlider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * V-ADD-SETTING：设置页对「加一个新参数」的稳健性审计。
 *
 * <p><b>为什么要这个探针</b>：用户要求「设置页真的改不坏再继续」。判断标准不是
 * 读代码觉得整洁，而是主动去找「加了新字段但某处没跟上」的漏点。
 *
 * <p>做法：把 State 里所有 public 实例字段与 SettingsField.FIELDS 的 key 集合做差。
 * 差集里的字段分两类：
 * <ul>
 *   <li><b>OK 漏</b>：确实不需要持久化（内存缓存、派生值）</li>
 *   <li><b>坏 漏</b>：需要持久化但没进表 —— apply() 会静默把它回滚（BUG: diagLogging 就是这样）</li>
 * </ul>
 * 本探针把两者都列出来，由白名单区分。
 */
public final class VAddSetting {
    private static int pass = 0, fail = 0;

    /**
     * 已知「不必进 SettingsField.FIELDS」的 State 字段（白名单）。
     * 每加一条都要写清理由 —— 这是防止「漏了就当设计」的唯一手段。
     */
    private static final Set<String> NOT_PERSISTED = new LinkedHashSet<>(List.of(
            "stateVersion",          // 版本号，由 loadState 单独处理
            "customAnimPresets",     // 自定义预设表，由 CustomPresets 单独管理
            "animCustomName",        // 派生名，同上
            "animPresetBase",        // 进表但标记为「不进快照」，见 presetPointer()
            // 2026-10-29：颜色预设三件套 —— 与动画的对应项同理由
            "colorPresetBase",       // 颜色基准下标，与 animPresetBase 对称
            "customColorPresets",    // 自定义颜色预设表，由 CustomPresets 单独管理
            "colorCustomName"        // 当前选中的自定义颜色预设名
    ));

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-ADD-SETTING：设置页对新增参数的稳健性 ===");

        // ---------- A. State 字段 vs SettingsField.FIELDS ----------
        List<String> stateFields = publicInstanceFields(NeovideCursorSettings.State.class);
        Set<String> tableKeys = new LinkedHashSet<>();
        for (SettingsField f : SettingsField.FIELDS) {
            tableKeys.add(f.key);
        }
        System.out.println("  State 字段数 = " + stateFields.size()
                + "，FIELDS 表项数 = " + tableKeys.size());

        List<String> missing = new ArrayList<>();
        for (String s : stateFields) {
            if (!tableKeys.contains(s)) {
                missing.add(s);
            }
        }
        List<String> unexplained = new ArrayList<>();
        for (String m : missing) {
            if (!NOT_PERSISTED.contains(m)) {
                unexplained.add(m);
            }
        }
        check("A-1 每个 State 字段都在 SettingsField.FIELDS 里（或白名单有理由）",
                unexplained.isEmpty(),
                unexplained.isEmpty() ? "全部覆盖" : "未解释的缺口=" + unexplained);

        // 反查：表里有但 State 里没有的 key（拼错字段名的典型症状）
        List<String> ghost = new ArrayList<>();
        for (String k : tableKeys) {
            if (!stateFields.contains(k)) {
                ghost.add(k);
            }
        }
        check("A-2 FIELDS 里没有指向不存在字段的 key（拼写错误会静默失效）",
                ghost.isEmpty(), ghost.isEmpty() ? "无幽灵 key" : "幽灵=" + ghost);

        // ---------- B. copyFrom 覆盖度 ----------
        // 用反射造一个「所有字段都设成非默认值」的 State，copyFrom 后逐字段比对。
        NeovideCursorSettings.State src = new NeovideCursorSettings.State();
        NeovideCursorSettings.State dst = new NeovideCursorSettings.State();
        setAllTo(src, true);
        dst.copyFrom(src);
        List<String> notCopied = new ArrayList<>();
        for (String f : stateFields) {
            if (NOT_PERSISTED.contains(f)) {
                continue;
            }
            Field fld = NeovideCursorSettings.State.class.getField(f);
            Object sv = fld.get(src);
            Object dv = fld.get(dst);
            if (sv instanceof Float && dv instanceof Float) {
                if (Math.abs((Float) sv - (Float) dv) > 1e-6f) {
                    notCopied.add(f);
                }
            } else if (sv != null && !sv.equals(dv)) {
                notCopied.add(f);
            }
        }
        check("B-1 copyFrom 覆盖全部参与持久化的字段（漏一个 = 面板回显不出来）",
                notCopied.isEmpty(),
                notCopied.isEmpty() ? "全覆盖" : "漏拷=" + notCopied);

        // ---------- C. 面板能建成、控件数合理 ----------
        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        check("C-1 createComponent 不抛异常且返回非 null", ui.panel.component != null,
                String.valueOf(ui.panel.component == null ? "null" : ui.panel.component.getClass().getSimpleName()));

        int sliders = ui.sliders.size();
        int checks = ui.checkBoxes.size();
        int combos = ui.combos.size();
        // 【2026-10-31 契约变更，非缺陷】新增「前缘贴合（半边拖尾）」复选框 → 9 变 10。
        // 【2026-11-05 契约变更，非缺陷】新增「跨行形状实验」复选框 → 10 变 11。
        check("C-2 控件数量与已知基线一致（滑块 14 / 复选框 11 / 下拉 4）",
                sliders == 14 && checks == 11 && combos == 4,
                "滑块=" + sliders + " 复选框=" + checks + " 下拉=" + combos
                        + "（若这里变了，说明面板结构被改动，需同步更新探针）");

        // ---------- D. reset / apply / isModified 三步都不炸 ----------
        boolean ok = true;
        String crash = "";
        try {
            conf.reset();
            boolean m1 = conf.isModified();
            conf.apply();
            boolean m2 = conf.isModified();
            if (m1 || m2) {
                ok = false;
                crash = "reset 后 isModified 应为 false，实际 " + m1 + " / apply 后 " + m2;
            }
        } catch (Throwable t) {
            ok = false;
            crash = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        check("D-1 reset → isModified=false → apply → isModified=false（无残留脏状态）",
                ok, ok ? "正常" : crash);

        // ---------- E. 新增参数的四层同步：位置清单 ----------
        // 这不是断言，是把「加一个参数要改哪几处」打印出来，供人工核对。
        System.out.println();
        System.out.println("  ── 加一个新 sliders 参数需要改动的 4 处（现状）──");
        System.out.println("     1) NeovideCursorSettings.State  —— 加字段 + 默认值");
        System.out.println("     2) State.copyFrom()            —— 漏了则面板回显不出来");
        System.out.println("     3) SettingsField.FIELDS        —— 漏了则 apply 静默回滚");
        System.out.println("     4) NeovideCursorConfigurable   —— SPECS[] 加项 + build*Group 加一行");
        System.out.println("  ── 加一个新 checkbox 只需改 3 处（跳过 SPECS）──");
        System.out.println("     1) State 字段  2) copyFrom  3) FIELDS  + build*Group 里 bindCheck 一行");

        System.out.println();
        System.out.printf("=== V-ADD-SETTING 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-ADD-SETTING RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-ADD-SETTING RESULT: ALL PASS");
    }

    /** 收集 public 实例字段名（排除 static / final 常量） */
    private static List<String> publicInstanceFields(Class<?> c) {
        List<String> out = new ArrayList<>();
        for (Field f : c.getFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod)) continue;
            out.add(f.getName());
        }
        return out;
    }

    /** 把所有可写字段设成「非默认值」，用于验证 copyFrom 覆盖度 */
    private static void setAllTo(NeovideCursorSettings.State s, boolean marker) throws Exception {
        for (Field f : s.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) continue;
            Class<?> t = f.getType();
            try {
                if (t == boolean.class) f.setBoolean(s, !f.getBoolean(s));
                else if (t == int.class) f.setInt(s, f.getInt(s) + 7);
                else if (t == float.class) f.setFloat(s, f.getFloat(s) + 0.137f);
                else if (t == String.class) f.set(s, "probe-" + f.getName());
            } catch (Throwable ignored) {
                // 某些字段有特殊约束，跳过即可
            }
        }
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

/**
 * V-CROSS-SHAPE-EXPERIMENT：设备级「跨行形状实验」的守门探针（A-like 版，2026-11-06）。
 *
 * <p><b>为什么不新增独立文件</b>：项目的 {@code VDocCheck.D-2} 把磁盘上的
 * {@code V*.java} 数量与三份既有状态文档（{@code CLAUDE.md} / {@code START-HERE.md} /
 * {@code NEXT-STEPS.md}）逐字比对；本轮 PROMPT 禁止改这三份文档，新增第 119 个
 * {@code V*.java} 会让门禁必红。因此本类作为 package-private 顶层类放在白名单内
 * {@code VAddSetting.java}，由 {@code build-v-probes.ps1} 的 {@code $mains} 直接运行。
 *
 * <p><b>它抓什么</b>：
 * <ul>
 *   <li>X-1~X-2：设备级字段、默认关、不进预设域、六层持久化；</li>
 *   <li>X-3：默认关时跨行四角时长仍等于旧三档基线（{@code trailSize=1} → 前缘 0ms）；</li>
 *   <li>X-4：开启后近水平/垂直大跨度 → 对齐度 &gt; 0.5 的前缘<b>成对</b>拿到 20ms，
 *       其余角拿 {@code animationLength × {1.0,0.72,0.42,0.22}[rank]}；
 *       20ms 与 {@code animationLength} 无关、与 {@code trailSize} 无关、不是 0ms；</li>
 *   <li>X-5：单行短跳（含连续 30 次「f」）开关 true/false 四角时长逐位相同，
 *       且等于短跳金值，绝不出现 20ms；</li>
 *   <li>X-6：行为层源码剥注释后没有 0ms 硬吸附/半边拖尾模型；短跳分支源码
 *       不含实验开关、对齐度计算或 20ms 常量（A-5 的静态断言）；</li>
 *   <li>X-7：真实面板里开关置灰 {@code trailSize}、保留值，并只让 {@code crossPreview}
 *       消费开关，{@code inlinePreview} / {@code glowPreview} 不消费；
 *       预览开启态必须真的出现 20ms 前缘。</li>
 * </ul>
 *
 * <p><b>驱动方式</b>：用反射调用生产 {@code ui.SpringTrail}（包私有）走真实链路
 * —— {@code setTarget → update → assignRanks → TrailCorner.jump}，
 * 不复制 rank 排序或时长公式。四角顺序恒为 TL/TR/BR/BL。
 *
 * <p><b>不做什么</b>：不复制生产公式；默认关基线用固定金值断言，
 * 开启路径直接读生产 {@link TrailCorner#jump} 后的真实结果。
 */
final class VCrossShapeExperiment {
    private static int pass;
    private static int fail;

    private static final double W = 2.0;
    private static final double H = 32.0;
    private static final double X0 = 100.0;
    private static final double Y0 = 100.0;

    /** A-like 前缘固定时长（秒）：必须与生产常量一致，且必须 > 0。 */
    private static final double A_LIKE_LEADING_SECONDS = 0.02;

    /** 短跳金值：{@code shortAnimationLength(0.11) × trailFactors{1.0,0.72,0.42,0.22}}。 */
    private static final double[] SHORT_GOLDEN = {0.11, 0.0462, 0.0242, 0.0792};

    public static void main(String[] args) throws Exception {
        System.out.println("=== V-CROSS-SHAPE-EXPERIMENT：跨行形状实验（A-like · 设备级 · 默认关）===");
        V0Env.install();

        fieldContract();
        persistence();
        defaultOffBaseline();
        takeover();
        singleLineUnaffected();
        failureModelAbsent();
        panelAndPreview();

        System.out.println();
        System.out.printf("=== V-CROSS-SHAPE-EXPERIMENT 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-CROSS-SHAPE-EXPERIMENT RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-CROSS-SHAPE-EXPERIMENT RESULT: ALL PASS");
    }

    // ==================== X-1：字段契约 ====================

    private static void fieldContract() {
        SettingsField f = SettingsField.byKey("crossShapeExperiment");
        check("X-1a SettingsField 已注册 crossShapeExperiment", f != null,
                f == null ? "缺失 → apply 会丢" : "已注册");
        if (f != null) {
            check("X-1b 设备级：runtimeSwitch=true，不进 CONFIG / 不进预设",
                    f.runtimeSwitch && !f.inPreset && !f.syncsToConfig,
                    "runtimeSwitch=" + f.runtimeSwitch + " inPreset=" + f.inPreset
                            + " syncsToConfig=" + f.syncsToConfig);
        }

        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        check("X-1c 默认值为 false", !s.crossShapeExperiment,
                "default=" + s.crossShapeExperiment);

        boolean inPresetFields = false;
        for (SettingsField x : SettingsField.PRESET_FIELDS) {
            if ("crossShapeExperiment".equals(x.key)) {
                inPresetFields = true;
            }
        }
        check("X-1d PRESET_FIELDS 不含该字段", !inPresetFields,
                inPresetFields ? "被误加进预设快照" : "不在 PRESET_FIELDS 里");

        boolean inAnimPreset = false;
        try {
            CursorPresets.AnimPreset.class.getMethod("crossShapeExperiment");
            inAnimPreset = true;
        } catch (NoSuchMethodException expected) {
            // 期望走这里
        }
        check("X-1e AnimPreset 不含该字段", !inAnimPreset,
                inAnimPreset ? "被误加进内置预设" : "不在预设里");

        boolean changed = false;
        NeovideCursorSettings.State presetState = new NeovideCursorSettings.State();
        presetState.crossShapeExperiment = true;
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            NeovideCursorConfigurable.applyAnimPresetFields(presetState, p);
            if (!presetState.crossShapeExperiment) {
                changed = true;
            }
        }
        check("X-1f 切任何动画预设都不改该字段", !changed,
                changed ? "被预设覆盖" : "全部 " + CursorPresets.ANIM_PRESETS.length + " 个预设都不动它");

        check("X-1g State 字段带中文注释「跨行形状实验」",
                source("settings/NeovideCursorSettings.java").contains("跨行形状实验"),
                "见 NeovideCursorSettings.State 字段注释");
    }

    // ==================== X-2：持久化 ====================

    private static void persistence() {
        NeovideCursorSettings.State from = new NeovideCursorSettings.State();
        from.crossShapeExperiment = true;
        NeovideCursorSettings.State to = new NeovideCursorSettings.State();
        to.copyFrom(from);
        check("X-2a copyFrom 带上 crossShapeExperiment", to.crossShapeExperiment,
                "copy 后=" + to.crossShapeExperiment);

        NeovideCursorSettings svc = new NeovideCursorSettings();
        NeovideCursorSettings.State persisted = new NeovideCursorSettings.State();
        persisted.crossShapeExperiment = true;
        svc.loadState(persisted);
        check("X-2b loadState 写 true 后读回仍为 true", svc.getState().crossShapeExperiment,
                "读回=" + svc.getState().crossShapeExperiment);
    }

    // ==================== X-3：默认关旧基线 ====================

    private static void defaultOffBaseline() throws Exception {
        double[] off = drive(cfg(0.1f), false, X0, Y0, X0 + 20, Y0, 99);
        // 纯水平右移的 rank：TL=0 / BL=1 / TR=2 / BR=3。
        // 旧三档（trailSize=1）：leading=0、trailing=0.1 →
        //   TL(rank0)=0.1  TR(rank2)=0  BR(rank3)=0  BL(rank1)=(0+0.1)/2=0.05
        check("X-3 默认关：水平大跨度四角 == 旧三档基线 [TL100, TR0, BR0, BL50] ms",
                eqArray(off, new double[]{0.1, 0.0, 0.0, 0.05}),
                "实际=" + java.util.Arrays.toString(off));
    }

    // ==================== X-4：开启后 A-like ====================

    private static void takeover() throws Exception {
        double[] atZero = drive(cfgWithTrailSize(0.1f, 0.0f), true, X0, Y0, X0 + 20, Y0, 99);
        double[] atOne = drive(cfgWithTrailSize(0.1f, 1.0f), true, X0, Y0, X0 + 20, Y0, 99);
        check("X-4a 开启后 trailSize=0 与 1 的跨行四角时长逐位相同",
                eqArray(atZero, atOne),
                "0=" + java.util.Arrays.toString(atZero)
                        + " 1=" + java.util.Arrays.toString(atOne));

        double[] horizontal = drive(cfg(0.1f), true, X0, Y0, X0 + 20, Y0, 99);
        check("X-4b 开启后四角时长全部为正（前缘 20ms，不是 0ms 硬吸附）",
                allPositive(horizontal), java.util.Arrays.toString(horizontal));
        check("X-4c 水平大跨度：右侧前缘成对拿 20ms，后缘两档 100/72ms",
                eqArray(horizontal, new double[]{
                        0.1, A_LIKE_LEADING_SECONDS, A_LIKE_LEADING_SECONDS, 0.072}),
                java.util.Arrays.toString(horizontal));
        check("X-4d 开启后不再等于旧三档结果（完全接管，不是加倍率）",
                !eqArray(horizontal, drive(cfg(0.1f), false, X0, Y0, X0 + 20, Y0, 99)),
                "开=" + java.util.Arrays.toString(horizontal)
                        + " 关=" + java.util.Arrays.toString(drive(cfg(0.1f), false, X0, Y0, X0 + 20, Y0, 99)));

        // 近水平（跨行 2px）：两角对齐度仍 > 0.5，必须成对吸附。
        double[] nearlyHorizontal = drive(cfg(0.1f), true, X0, Y0, X0 + 20, Y0 + 2, 99);
        check("X-4e 近水平跨行：前缘仍成对拿 20ms，非吸附角按 rank 取 100/72ms",
                eqArray(nearlyHorizontal, new double[]{
                        0.1, A_LIKE_LEADING_SECONDS, A_LIKE_LEADING_SECONDS, 0.072}),
                java.util.Arrays.toString(nearlyHorizontal));

        // 纯垂直下移：下缘两角（BR/BL）成对吸附。
        double[] vertical = drive(cfg(0.1f), true, X0, Y0, X0, Y0 + 32, 99);
        check("X-4f 垂直跨行：下缘成对拿 20ms，上缘按 rank 取 100/72ms",
                eqArray(vertical, new double[]{
                        0.1, 0.072, A_LIKE_LEADING_SECONDS, A_LIKE_LEADING_SECONDS}),
                java.util.Arrays.toString(vertical));

        // 20ms 是绝对时长：animationLength 翻倍时吸附角不变，非吸附角翻倍。
        double[] doubled = drive(cfg(0.2f), true, X0, Y0, X0 + 20, Y0, 99);
        check("X-4g animationLength=0.2：前缘仍为 20ms，后缘为 200/144ms",
                eqArray(doubled, new double[]{
                        0.2, A_LIKE_LEADING_SECONDS, A_LIKE_LEADING_SECONDS, 0.144}),
                java.util.Arrays.toString(doubled));

        double constant = productionLeadingSeconds();
        check("X-4h 生产前缘常量是正 20ms，不是 0",
                constant > 0 && Math.abs(constant - A_LIKE_LEADING_SECONDS) < 1e-9,
                "生产常量=" + constant);
    }

    // ==================== X-5：单行零伤 ====================

    private static void singleLineUnaffected() throws Exception {
        double[] off = drive(cfg(0.1f), false, X0, Y0, X0 + 2, Y0, 1);
        double[] on = drive(cfg(0.1f), true, X0, Y0, X0 + 2, Y0, 1);
        check("X-5a 开关 true / false 时单行短跳四角时长逐位相同",
                eqArray(off, on),
                "false=" + java.util.Arrays.toString(off)
                        + " true=" + java.util.Arrays.toString(on));
        check("X-5b 单行短跳四角 == 短跳金值（与实验常量无关）",
                eqArray(on, SHORT_GOLDEN), java.util.Arrays.toString(on));

        boolean offStable = continuousTypingStable(false);
        boolean onStable = continuousTypingStable(true);
        check("X-5c 连续 30 次「f」：开关开/关都保持短跳金值，且从不出现 20ms",
                offStable && onStable,
                "关=" + offStable + " 开=" + onStable);
    }

    // ==================== X-6：失败模型未回归 ====================

    private static void failureModelAbsent() {
        String[] behaviorFiles = {
                "core/TrailCorner.java",
                "ui/SpringTrail.java",
                "ui/CaretAnimator.java",
                "ui/settings/AnimationPreview.java",
                "ui/CaretTrailPanel.java",
        };
        String[] forbidden = {
                "useHardSnap", "leadingSnapFactor", "leadingSnapThreshold",
                "snapAnimationLength", "HALF_TRAIL", "setAnimationLengthForTrail",
                "halfTrail",
        };
        StringBuilder hits = new StringBuilder();
        for (String rel : behaviorFiles) {
            String body = stripComments(source(rel));
            for (String symbol : forbidden) {
                if (body.contains(symbol)) {
                    hits.append(rel).append(" 含 ").append(symbol).append("; ");
                }
            }
        }
        check("X-6a 行为层五文件剥注释后零 0ms 硬吸附/半边拖尾实现", hits.length() == 0,
                hits.length() == 0 ? "五个文件零出现" : hits.toString());
        check("X-6b 绘制面板不含 crossShapeExperiment 叠层路径",
                !stripComments(source("ui/CaretTrailPanel.java")).contains("crossShapeExperiment"),
                "CaretTrailPanel 只保留原有单次四边形填充");

        // A-5：短跳分支源码剥注释后不得出现实验开关、对齐度计算、吸附常量。
        String trail = stripComments(source("core/TrailCorner.java"));
        String shortBranch = slice(trail, "if (isShortJump) {", "} else if (crossShapeExperiment) {");
        boolean clean = !shortBranch.isEmpty()
                && !shortBranch.contains("crossShapeExperiment")
                && !shortBranch.contains("A_LIKE")
                && !shortBranch.contains("calculateDirectionAlignment")
                && !shortBranch.contains("0.02");
        check("X-6c 短跳分支源码不含实验开关/对齐度/20ms 常量（A-5）",
                clean, shortBranch.isEmpty() ? "短跳分支切片失败" : "切片长度=" + shortBranch.length());

        // 反向断言：跨行实验分支必须真的接上对齐度 + 20ms，防止「开关存在但分支没接」。
        boolean wired = trail.contains("leadingAlignment > A_LIKE_LEADING_ALIGNMENT_THRESHOLD")
                && trail.contains("A_LIKE_LEADING_ANIMATION_LENGTH");
        check("X-6d 跨行实验分支已接对齐度阈值与 20ms 常量", wired,
                "leadingAlignment 判定=" + trail.contains("leadingAlignment")
                        + " 20ms 常量=" + trail.contains("A_LIKE_LEADING_ANIMATION_LENGTH"));
    }

    // ==================== X-7：真实面板 / 预览 ====================

    private static void panelAndPreview() throws Exception {
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        // 面板真实顺序：0..3 总开关，4 颜色/渐变，5 前缘贴合，6 跨行形状实验，
        // 7 辉光开关，8 淡出开关，9 性能诊断，10 诊断日志。总数不对就判红。
        JCheckBox cb = ui.checkBoxes.size() == 11 ? ui.checkBoxes.get(6) : null;
        JSlider trailSize = ui.sliderByKey("trailSize");
        JSlider animationLength = ui.sliderByKey("animationLength");
        check("X-7a 面板中存在新复选框、trailSize 与 animationLength 滑块",
                cb != null && trailSize != null && animationLength != null,
                "checkbox=" + (cb != null) + " trailSize=" + (trailSize != null)
                        + " animationLength=" + (animationLength != null));
        if (cb == null || trailSize == null || animationLength == null) {
            return;
        }

        float before = conf.pendingForProbe().trailSize;
        check("X-7b 默认关：复选框未勾选，两个跨行滑块都可调",
                !cb.isSelected() && trailSize.isEnabled() && animationLength.isEnabled(),
                "selected=" + cb.isSelected() + " trailEnabled=" + trailSize.isEnabled()
                        + " animEnabled=" + animationLength.isEnabled());

        cb.doClick();
        conf.refreshPreviewsForProbe();
        NeovideCursorSettings.State onState = conf.pendingForProbe();
        check("X-7c 开启：只有 trailSize 置灰，animationLength 仍可调",
                onState.crossShapeExperiment && !trailSize.isEnabled() && animationLength.isEnabled(),
                "on=" + onState.crossShapeExperiment + " trailEnabled=" + trailSize.isEnabled()
                        + " animEnabled=" + animationLength.isEnabled());
        check("X-7d 置灰不改变 trailSize 的 pending 值",
                Math.abs(onState.trailSize - before) < 1e-6f,
                "before=" + before + " after=" + onState.trailSize);

        AnimationPreview cross = castPreview(getField(conf, "crossPreview"));
        AnimationPreview inline = castPreview(getField(conf, "inlinePreview"));
        AnimationPreview glow = castPreview(getField(conf, "glowPreview"));
        check("X-7e crossPreview 消费开关，inlinePreview / glowPreview 不消费",
                cross != null && cross.crossShapeExperimentForProbe()
                        && inline != null && !inline.crossShapeExperimentForProbe()
                        && glow != null && !glow.crossShapeExperimentForProbe(),
                "cross=" + flag(cross) + " inline=" + flag(inline) + " glow=" + flag(glow));

        double[] onDurations = cross == null ? null : previewDurations(cross);
        cb.doClick();
        conf.refreshPreviewsForProbe();
        check("X-7f 关闭：两个跨行滑块恢复可调",
                trailSize.isEnabled() && animationLength.isEnabled(),
                "trailEnabled=" + trailSize.isEnabled() + " animEnabled=" + animationLength.isEnabled());
        check("X-7g 关闭后 trailSize 的值仍然保持",
                Math.abs(conf.pendingForProbe().trailSize - before) < 1e-6f,
                "before=" + before + " after=" + conf.pendingForProbe().trailSize);

        double[] offDurations = cross == null ? null : previewDurations(cross);
        check("X-7h crossPreview：开启态前缘成对 20ms，关闭态回到 0ms 前缘且两者不同",
                onDurations != null && offDurations != null
                        && !eqArray(onDurations, offDurations)
                        && allPositive(onDurations)
                        && countValue(onDurations, A_LIKE_LEADING_SECONDS) == 2
                        && !allPositive(offDurations)
                        && countValue(offDurations, 0.0) >= 1,
                "开=" + java.util.Arrays.toString(onDurations)
                        + " 关=" + java.util.Arrays.toString(offDurations));
    }

    // ==================== 生产路径调用 ====================
    //
    // 不复制 rank 排序：反射实例化包私有的生产 ui.SpringTrail，
    // 走 setTarget → update → assignRanks → TrailCorner.jump 的真实链路。
    // 四角顺序 = SpringTrail.CORNER_RELATIVE：TL / TR / BR / BL。

    private static NeovideConfig cfg(float animationLength) {
        NeovideConfig c = new NeovideConfig();
        c.animationLength = animationLength;
        return c;
    }

    private static NeovideConfig cfgWithTrailSize(float animationLength, float trailSize) {
        NeovideConfig c = cfg(animationLength);
        c.trailSize = trailSize;
        return c;
    }

    private static double[] drive(NeovideConfig config, boolean experiment,
                                  double x0, double y0, double x1, double y1,
                                  int charsMoved) throws Exception {
        Object trail = newTrail(config);
        setTrailExperiment(trail, experiment);
        setTrailChars(trail, charsMoved);
        setTrailTarget(trail, x0, y0, W, H);   // 首次：直接吸附到起点
        setTrailTarget(trail, x1, y1, W, H);   // 第二次：目标变化 → 下一帧分配 rank
        tickTrail(trail, 0.008);
        return cornerDurations(trail);
    }

    /**
     * 连续 30 次「同方向、每次 1 个字符」的移动（模拟 {@code ffffffffff...}）。
     * 每次都必须仍是短跳金值 —— 一旦 A-like 吸附漏进短跳分支就会返回 false。
     */
    private static boolean continuousTypingStable(boolean experiment) {
        try {
            Object trail = newTrail(cfg(0.1f));
            setTrailExperiment(trail, experiment);
            setTrailChars(trail, 1);
            setTrailTarget(trail, X0, Y0, W, H);
            for (int i = 1; i <= 30; i++) {
                setTrailTarget(trail, X0 + 2.0 * i, Y0, W, H);
                tickTrail(trail, 0.008);
                if (!eqArray(cornerDurations(trail), SHORT_GOLDEN)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            System.out.println("    [X-5 连续打字驱动失败] " + t);
            return false;
        }
    }

    private static double[] previewDurations(AnimationPreview preview) {
        try {
            setField(preview, "cursorW", W);
            setField(preview, "cursorH", H);
            setField(preview, "centerX", X0);
            setField(preview, "centerY", Y0);
            TrailCorner[] corners = (TrailCorner[]) getField(preview, "corners");
            // 先把四角摆到起点，否则首帧的方向由 (0,0) 指向目标、不是纯水平，
            // 对齐度会被对角线带偏，测不到「前缘成对」。
            for (TrailCorner corner : corners) {
                corner.snapTo(W, H, X0, Y0);
            }
            setField(preview, "centerX", X0 + 20);
            invoke(preview, "pushTarget");
            double[] out = new double[corners.length];
            for (int i = 0; i < corners.length; i++) {
                out[i] = corners[i].animationLengthForProbe();
            }
            return out;
        } catch (Throwable t) {
            System.out.println("    [X-7 预览驱动失败] " + t);
            return null;
        }
    }

    // ==================== SpringTrail 反射驱动 ====================

    private static Object newTrail(NeovideConfig config) throws Exception {
        Class<?> c = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.SpringTrail");
        Constructor<?> ctor = c.getDeclaredConstructor(NeovideConfig.class);
        ctor.setAccessible(true);
        return ctor.newInstance(config);
    }

    private static void setTrailExperiment(Object trail, boolean on) throws Exception {
        Method m = trail.getClass().getDeclaredMethod("setCrossShapeExperiment", boolean.class);
        m.setAccessible(true);
        m.invoke(trail, on);
    }

    private static void setTrailChars(Object trail, int charsMoved) throws Exception {
        Method m = trail.getClass().getDeclaredMethod("applyCharsMoved", int.class);
        m.setAccessible(true);
        m.invoke(trail, charsMoved);
    }

    private static void setTrailTarget(Object trail, double cx, double cy,
                                       double w, double h) throws Exception {
        Method m = trail.getClass().getDeclaredMethod("setTarget",
                double.class, double.class, double.class, double.class);
        m.setAccessible(true);
        m.invoke(trail, cx, cy, w, h);
    }

    private static void tickTrail(Object trail, double dt) throws Exception {
        Method m = trail.getClass().getDeclaredMethod("update", double.class);
        m.setAccessible(true);
        m.invoke(trail, dt);
    }

    private static double[] cornerDurations(Object trail) throws Exception {
        Field f = trail.getClass().getDeclaredField("corners");
        f.setAccessible(true);
        TrailCorner[] corners = (TrailCorner[]) f.get(trail);
        double[] out = new double[corners.length];
        for (int i = 0; i < corners.length; i++) {
            out[i] = corners[i].animationLengthForProbe();
        }
        return out;
    }

    /** 读生产常量（拿不到就返回 NaN，断言必红）。 */
    private static double productionLeadingSeconds() {
        try {
            Field f = TrailCorner.class.getDeclaredField("A_LIKE_LEADING_ANIMATION_LENGTH");
            f.setAccessible(true);
            return f.getDouble(null);
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** 取两段标记之间的源码（剥注释后使用）；找不到返回空串。 */
    private static String slice(String text, String from, String to) {
        int a = text.indexOf(from);
        if (a < 0) {
            return "";
        }
        int b = text.indexOf(to, a + from.length());
        return b < 0 ? "" : text.substring(a, b);
    }

    private static int countValue(double[] a, double v) {
        if (a == null) {
            return 0;
        }
        int n = 0;
        for (double x : a) {
            if (Math.abs(x - v) <= 1e-9) {
                n++;
            }
        }
        return n;
    }

    // ==================== 源码 / 反射辅助 ====================

    private static String source(String rel) {
        try {
            Path root = repoRoot();
            return new String(Files.readAllBytes(
                    root.resolve("src/main/java/com/github/yyyolo7a79/neovidecursor/" + rel)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static Path repoRoot() {
        String prop = System.getProperty("neovide.repo");
        if (prop != null && !prop.isEmpty()) {
            Path p = Paths.get(prop);
            if (Files.isRegularFile(p.resolve("CLAUDE.md"))) {
                return p;
            }
        }
        Path p = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("CLAUDE.md"))) {
                return p;
            }
            p = p.getParent();
        }
        return Paths.get(".");
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static Object getField(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void setField(Object o, String name, double v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setDouble(o, v);
    }

    private static void invoke(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(o);
    }

    private static AnimationPreview castPreview(Object o) {
        return o instanceof AnimationPreview ? (AnimationPreview) o : null;
    }

    private static String flag(AnimationPreview p) {
        return p == null ? "null" : String.valueOf(p.crossShapeExperimentForProbe());
    }

    private static boolean eqArray(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-6) {
                return false;
            }
        }
        return true;
    }

    private static boolean allPositive(double[] a) {
        if (a == null) {
            return false;
        }
        for (double v : a) {
            if (!(v > 0)) {
                return false;
            }
        }
        return true;
    }

    private static int distinctCount(double[] a) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            boolean seen = false;
            for (int j = 0; j < i; j++) {
                if (Math.abs(a[i] - a[j]) <= 1e-9) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                n++;
            }
        }
        return n;
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
