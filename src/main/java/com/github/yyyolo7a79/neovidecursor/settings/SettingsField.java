package com.github.yyyolo7a79.neovidecursor.settings;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * 设置项的<b>字段表</b>（0.9.9 / B1–B3）。
 *
 * <h2>为什么需要这张表</h2>
 *
 * <p>加一个参数原本要改 <b>8 处</b>：{@code NeovideConfig} 字段、{@code State} 字段、
 * {@code syncStateToConfig}、{@code syncConfigToState}、{@code SliderSpec}、
 * {@code apply()} 脏合并、{@code dirtyAnyField}、探针。
 * 漏掉第 6/7 处的症状是<b>静默</b>的：「存了但读不到」「改了但应用按钮不亮」。
 * 这不是假设 —— 0.9.6 加 {@code typingSmoothness} 时漏了 {@code dirtyAnyField}，
 * 用户实测「输入平滑拉不动应用」。
 *
 * <h2>本表提供什么</h2>
 *
 * <p>一行描述一个字段：名字 + 两对访问器（{@code State} / {@code NeovideConfig}）
 * + <b>它自己的比较语义</b> + 是否进 CONFIG。
 * {@code apply()} / {@code dirtyAnyField()} / 双向同步 / 自定义预设快照
 * <b>全部按表遍历</b>，于是「加一个参数」降到 2 处。
 *
 * <h2>比较语义必须逐字段携带</h2>
 *
 * <p>这是本表存在的核心理由：
 * <ul>
 *   <li>{@code float} 必须 {@link Float#compare} —— {@code ==} 会让 {@code NaN != NaN}
 *       与 {@code -0.0f == 0.0f} 两个边界都判错；</li>
 *   <li>HEX 必须 {@code equalsIgnoreCase} —— 手改 XML 可能留小写；</li>
 *   <li>文本必须把 {@code null} 当 {@code ""} —— 否则 NPE 会让整页设置打不开；</li>
 *   <li>其余用值比较。</li>
 * </ul>
 *
 * <h2>不携带默认值（D0 铁律）</h2>
 *
 * <p>默认值唯一源头仍是 {@link NeovideConfig} 与 {@link NeovideCursorSettings.State}
 * 的字段初始化式。表只描述「怎么读、怎么写、怎么比」，所以重构不触碰任何默认值。
 *
 * <h2>自定义预设的编解码</h2>
 *
 * <p>{@link #snapshot}/{@link #restore} 让「快照全部参数」自动跟随本表 ——
 * 新增参数时自定义预设自动包含它，不需要另写第二份清单。
 */
public final class SettingsField {

    /** 字段类型。只用于原始值编解码，不参与比较（比较由 {@code same} 负责）。 */
    public enum Kind {
        /** 浮点，原样 {@link Float#toString}。 */
        FLOAT,
        /** 整数，十进制。 */
        INT,
        /** 布尔，写 {@code 1}/{@code 0}（比 true/false 短，且不易与枚举名混淆）。 */
        BOOL,
        /** HEX 颜色串，原样。 */
        HEX,
        /** 枚举，存 {@link Enum#name()}。 */
        ENUM
    }

    /** 字段名 = {@code State} 的 Java 字段名。 */
    public final String key;

    /** 字段类型（编解码用）。 */
    public final Kind kind;

    private final Function<NeovideCursorSettings.State, Object> getState;
    private final BiConsumer<NeovideCursorSettings.State, Object> setState;
    private final Function<NeovideConfig, Object> getConfig;
    private final BiConsumer<NeovideConfig, Object> setConfig;
    private final BiPredicate<Object, Object> same;

    /** 是否参与 {@code State ⇄ NeovideConfig} 双向同步。 */
    public final boolean syncsToConfig;

    /**
     * 是否属于「自定义预设快照」。
     *
     * <p>{@code false} 的只有 {@code animPresetBase}：它是「当前选中了谁」的<b>指针</b>，
     * 存进快照会形成「预设指向自己」的循环。
     */
    public final boolean inPreset;

    private SettingsField(String key, Kind kind,
                          Function<NeovideCursorSettings.State, Object> getState,
                          BiConsumer<NeovideCursorSettings.State, Object> setState,
                          Function<NeovideConfig, Object> getConfig,
                          BiConsumer<NeovideConfig, Object> setConfig,
                          BiPredicate<Object, Object> same,
                          boolean syncsToConfig, boolean inPreset) {
        this.key = key;
        this.kind = kind;
        this.getState = getState;
        this.setState = setState;
        this.getConfig = getConfig;
        this.setConfig = setConfig;
        this.same = same;
        this.syncsToConfig = syncsToConfig;
        this.inPreset = inPreset;
    }

    /** 两个 {@code State} 在这个字段上是否不同。 */
    public boolean differs(NeovideCursorSettings.State a, NeovideCursorSettings.State b) {
        return !same.test(getState.apply(a), getState.apply(b));
    }

    /** 把 {@code from} 的这个字段拷进 {@code to}。 */
    public void copy(NeovideCursorSettings.State from, NeovideCursorSettings.State to) {
        setState.accept(to, getState.apply(from));
    }

    /** {@code State → CONFIG}；不参与者为空操作。 */
    public void stateToConfig(NeovideCursorSettings.State s, NeovideConfig c) {
        if (syncsToConfig) {
            setConfig.accept(c, getState.apply(s));
        }
    }

    /** {@code CONFIG → State}；不参与者为空操作。 */
    public void configToState(NeovideConfig c, NeovideCursorSettings.State s) {
        if (syncsToConfig) {
            setState.accept(s, getConfig.apply(c));
        }
    }

    /** 读 {@code State} 的当前值（探针/审计用）。 */
    public Object readState(NeovideCursorSettings.State s) {
        return getState.apply(s);
    }

    /**
     * 编码成可持久化的短字符串。
     *
     * <p>用 {@link Float#toString} 而不是 {@code %.2f}：前者是「能唯一还原该 float 的
     * 最短十进制」，往返逐位相等；两位小数会让「辉光锐度」那种 0.0055 步长的值漂移。
     */
    public String encode(NeovideCursorSettings.State s) {
        Object v = getState.apply(s);
        switch (kind) {
            case FLOAT:
                return Float.toString((Float) v);
            case INT:
                return Integer.toString((Integer) v);
            case BOOL:
                return Boolean.TRUE.equals(v) ? "1" : "0";
            case ENUM:
                return ((Enum<?>) v).name();
            case HEX:
            default:
                return v == null ? "" : v.toString();
        }
    }

    /**
     * 从短字符串还原并写入 {@code s}。
     *
     * <p><b>非法值绝不抛异常</b>（宁干净不崩）：解析失败就保持 {@code s} 的现值。
     * 这条是硬要求 —— 自定义预设串可能被手改坏，抛异常会让整页设置打不开。
     *
     * @return true = 成功写入；false = 内容非法、已忽略
     */
    public boolean decode(NeovideCursorSettings.State s, String raw) {
        if (raw == null) {
            return false;
        }
        try {
            Object v;
            switch (kind) {
                case FLOAT: {
                    float f = Float.parseFloat(raw.trim());
                    if (Float.isNaN(f) || Float.isInfinite(f)) {
                        return false;
                    }
                    v = f;
                    break;
                }
                case INT: {
                    v = Integer.parseInt(raw.trim());
                    break;
                }
                case BOOL: {
                    String t = raw.trim();
                    if ("1".equals(t) || "true".equalsIgnoreCase(t)) {
                        v = Boolean.TRUE;
                    } else if ("0".equals(t) || "false".equalsIgnoreCase(t)) {
                        v = Boolean.FALSE;
                    } else {
                        return false;
                    }
                    break;
                }
                case ENUM: {
                    v = resolveEnum(getState.apply(s), raw.trim());
                    if (v == null) {
                        return false;
                    }
                    break;
                }
                case HEX:
                default: {
                    v = raw.trim();
                    break;
                }
            }
            setState.accept(s, v);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 按名字解析枚举（沿用当前值的枚举类型，不硬编码常量名）。 */
    private static Object resolveEnum(Object current, String name) {
        if (current instanceof Enum<?>) {
            Class<?> cls = ((Enum<?>) current).getDeclaringClass();
            Object[] constants = cls.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) {
                    if (((Enum<?>) c).name().equals(name)) {
                        return c;
                    }
                }
            }
        }
        return null;
    }

    // ==================== 工厂 ====================

    private static SettingsField bools(String key,
                                       Function<NeovideCursorSettings.State, Boolean> gs,
                                       BiConsumer<NeovideCursorSettings.State, Boolean> ss,
                                       Function<NeovideConfig, Boolean> gc,
                                       BiConsumer<NeovideConfig, Boolean> sc) {
        return new SettingsField(key, Kind.BOOL,
                s -> gs.apply(s), (s, v) -> ss.accept(s, (Boolean) v),
                gc == null ? null : c -> gc.apply(c),
                sc == null ? null : (c, v) -> sc.accept(c, (Boolean) v),
                (a, b) -> ((Boolean) a).booleanValue() == ((Boolean) b).booleanValue(),
                gc != null, true);
    }

    private static SettingsField ints(String key,
                                      Function<NeovideCursorSettings.State, Integer> gs,
                                      BiConsumer<NeovideCursorSettings.State, Integer> ss,
                                      Function<NeovideConfig, Integer> gc,
                                      BiConsumer<NeovideConfig, Integer> sc) {
        return new SettingsField(key, Kind.INT,
                s -> gs.apply(s), (s, v) -> ss.accept(s, (Integer) v),
                gc == null ? null : c -> gc.apply(c),
                sc == null ? null : (c, v) -> sc.accept(c, (Integer) v),
                (a, b) -> ((Integer) a).intValue() == ((Integer) b).intValue(),
                gc != null, true);
    }

    private static SettingsField floats(String key,
                                        Function<NeovideCursorSettings.State, Float> gs,
                                        BiConsumer<NeovideCursorSettings.State, Float> ss,
                                        Function<NeovideConfig, Float> gc,
                                        BiConsumer<NeovideConfig, Float> sc) {
        return new SettingsField(key, Kind.FLOAT,
                s -> gs.apply(s), (s, v) -> ss.accept(s, (Float) v),
                gc == null ? null : c -> gc.apply(c),
                sc == null ? null : (c, v) -> sc.accept(c, (Float) v),
                (a, b) -> Float.compare((Float) a, (Float) b) == 0,
                gc != null, true);
    }

    private static SettingsField hexColors(String key,
                                           Function<NeovideCursorSettings.State, String> gs,
                                           BiConsumer<NeovideCursorSettings.State, String> ss,
                                           Function<NeovideConfig, String> gc,
                                           BiConsumer<NeovideConfig, String> sc) {
        return new SettingsField(key, Kind.HEX,
                s -> gs.apply(s), (s, v) -> ss.accept(s, (String) v),
                gc == null ? null : c -> gc.apply(c),
                sc == null ? null : (c, v) -> sc.accept(c, (String) v),
                (a, b) -> a == null ? b == null : ((String) a).equalsIgnoreCase((String) b),
                gc != null, true);
    }

    private static SettingsField enums(String key,
                                       Function<NeovideCursorSettings.State, NeovideConfig.TrailMode> gs,
                                       BiConsumer<NeovideCursorSettings.State, NeovideConfig.TrailMode> ss,
                                       Function<NeovideConfig, NeovideConfig.TrailMode> gc,
                                       BiConsumer<NeovideConfig, NeovideConfig.TrailMode> sc) {
        return new SettingsField(key, Kind.ENUM,
                s -> gs.apply(s), (s, v) -> ss.accept(s, (NeovideConfig.TrailMode) v),
                gc == null ? null : c -> gc.apply(c),
                sc == null ? null : (c, v) -> sc.accept(c, (NeovideConfig.TrailMode) v),
                (a, b) -> a == b,
                gc != null, true);
    }

    /** 预设基准下标：参与 apply / 判脏，但<b>不进快照</b>（它是「选中了谁」的指针）。 */
    private static SettingsField presetPointer() {
        return new SettingsField("animPresetBase", Kind.INT,
                s -> s.animPresetBase, (s, v) -> s.animPresetBase = (Integer) v,
                null, null,
                (a, b) -> ((Integer) a).intValue() == ((Integer) b).intValue(),
                false, false);
    }

    // ==================== 表本体 ====================

    /** 全部字段（顺序 = 契约 §6.2 书写顺序）。 */
    public static final List<SettingsField> FIELDS = build();

    /** 参与自定义预设快照的字段（{@link #FIELDS} 去掉 {@code animPresetBase}）。 */
    public static final List<SettingsField> PRESET_FIELDS = buildPresetFields();

    private static List<SettingsField> build() {
        List<SettingsField> out = new ArrayList<>();

        out.add(bools("enabled", s -> s.enabled, (s, v) -> s.enabled = v,
                c -> c.enabled, (c, v) -> c.enabled = v));
        out.add(enums("trailMode", s -> s.trailMode, (s, v) -> s.trailMode = v,
                c -> c.trailMode, (c, v) -> c.trailMode = v));
        out.add(bools("hideNativeCaret", s -> s.hideNativeCaret, (s, v) -> s.hideNativeCaret = v,
                c -> c.hideNativeCaret, (c, v) -> c.hideNativeCaret = v));
        out.add(bools("alwaysHighFps", s -> s.alwaysHighFps, (s, v) -> s.alwaysHighFps = v,
                c -> c.alwaysHighFps, (c, v) -> c.alwaysHighFps = v));
        out.add(bools("overlayFadeOut", s -> s.overlayFadeOut, (s, v) -> s.overlayFadeOut = v,
                c -> c.overlayFadeOut, (c, v) -> c.overlayFadeOut = v));

        // 【0.9.50 修复 bug：诊断日志勾选后应用即消失】
        //
        // 原因：diagLogging 虽然定义在 State 里，但不在本表 → apply() 遍历本表时
        // 根本不会把它从 pending 拷到 target → 一应用就回滚成旧值。
        //
        // CONFIG 两个映射传 null：它不需要进 NeovideConfig
        // （CursorDiag 直接读 State）。
        out.add(bools("diagLogging", s -> s.diagLogging, (s, v) -> s.diagLogging = v,
                null, null));

        // 【多光标不辉光】同样是「只进 State、不进 CONFIG」的运行期开关 ——
        // CONFIG 里没有对应字段（生效逻辑在 CaretAnimator 里读 State）。
        out.add(bools("multiCursorNoGlow", s -> s.multiCursorNoGlow,
                (s, v) -> s.multiCursorNoGlow = v, null, null));
        out.add(ints("cursorDisappearDelay", s -> s.cursorDisappearDelay, (s, v) -> s.cursorDisappearDelay = v,
                c -> c.cursorDisappearDelay, (c, v) -> c.cursorDisappearDelay = v));
        out.add(floats("cursorFadeOutDuration", s -> s.cursorFadeOutDuration, (s, v) -> s.cursorFadeOutDuration = v,
                c -> c.cursorFadeOutDuration, (c, v) -> c.cursorFadeOutDuration = v));
        out.add(hexColors("tailColor", s -> s.tailColor, (s, v) -> s.tailColor = v,
                c -> c.tailColor, (c, v) -> c.tailColor = v));
        out.add(floats("tailOpacity", s -> s.tailOpacity, (s, v) -> s.tailOpacity = v,
                c -> c.tailOpacity, (c, v) -> c.tailOpacity = v));
        out.add(bools("useShadow", s -> s.useShadow, (s, v) -> s.useShadow = v,
                c -> c.useShadow, (c, v) -> c.useShadow = v));
        out.add(hexColors("shadowColor", s -> s.shadowColor, (s, v) -> s.shadowColor = v,
                c -> c.shadowColor, (c, v) -> c.shadowColor = v));
        out.add(floats("glowOpacity", s -> s.glowOpacity, (s, v) -> s.glowOpacity = v,
                c -> c.glowOpacity, (c, v) -> c.glowOpacity = v));
        out.add(ints("glowLayers", s -> s.glowLayers, (s, v) -> s.glowLayers = v,
                c -> c.glowLayers, (c, v) -> c.glowLayers = v));
        out.add(floats("shadowBlurFactor", s -> s.shadowBlurFactor, (s, v) -> s.shadowBlurFactor = v,
                c -> c.shadowBlurFactor, (c, v) -> c.shadowBlurFactor = v));
        out.add(floats("trailSize", s -> s.trailSize, (s, v) -> s.trailSize = v,
                c -> c.trailSize, (c, v) -> c.trailSize = v));
        // stretchRatioK / typingSmoothness 派生 trailFactors，CONFIG 无对应标量
        out.add(floats("stretchRatioK", s -> s.stretchRatioK, (s, v) -> s.stretchRatioK = v, null, null));
        out.add(floats("typingSmoothness", s -> s.typingSmoothness, (s, v) -> s.typingSmoothness = v, null, null));
        out.add(floats("animationLength", s -> s.animationLength, (s, v) -> s.animationLength = v,
                c -> c.animationLength, (c, v) -> c.animationLength = v));
        out.add(floats("shortAnimationLength", s -> s.shortAnimationLength, (s, v) -> s.shortAnimationLength = v,
                c -> c.shortAnimationLength, (c, v) -> c.shortAnimationLength = v));
        out.add(presetPointer());

        return Collections.unmodifiableList(out);
    }

    private static List<SettingsField> buildPresetFields() {
        List<SettingsField> out = new ArrayList<>();
        for (SettingsField f : FIELDS) {
            if (f.inPreset) {
                out.add(f);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** 按名字取字段；找不到返回 {@code null}。 */
    public static SettingsField byKey(String key) {
        for (SettingsField f : FIELDS) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        return null;
    }

    // ==================== 判脏（全项目唯一实现）====================

    /**
     * {@code pending} 与 {@code state} 是否有任一字段不同。
     *
     * <p><b>这是全项目唯一的判脏实现</b>：{@code apply()} 的判据、{@code isModified()}
     * 的第三半、以及探针<b>都调它</b> —— 三者不可能再出现「补了一边忘了另一边」。
     *
     * <p>历史教训：0.9.6 加 {@code typingSmoothness} 时只补了 {@code apply}，
     * 于是 {@code isModified()} 恒为 false → 用户实测「输入平滑拉不动应用」。
     *
     * <p>预设元数据（自定义预设表 + 当前选中名）单独判：它们不是「参数」，
     * 但改动它们同样必须点亮应用按钮（否则「新建预设」后点不了应用）。
     */
    public static boolean dirtyAnyField(NeovideCursorSettings.State p,
                                        NeovideCursorSettings.State s) {
        if (p == null || s == null) {
            return p != s;
        }
        for (SettingsField f : FIELDS) {
            if (f.differs(p, s)) {
                return true;
            }
        }
        // 【为什么只比 animCustomName，不比 customAnimPresets】
        // 预设表是**派生数据**：它由「当前参数值」在编辑时自动生成/更新
        // （见 NeovideCursorConfigurable#afterPendingEdited）。
        // 把它纳入判脏会造成两个问题：
        //   ① 装载一份与内置常量不同的历史 state 时，pending 的派生快照可能比 state
        //      的多/少一个字符（数值位数差异），于是 isModified() 恒为真 —— 假脏；
        //   ② 真正的「值改了」已经由上面 20 个字段覆盖，比它没有额外信息量。
        // 「当前选中了哪个自定义预设」必须比：重命名/切换后要让应用按钮亮起来。
        return !textSame(p.animCustomName, s.animCustomName);
    }

    /** {@code null} 当 {@code ""} 的文本比较。 */
    public static boolean textSame(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    // ==================== 快照（自定义预设用）====================

    /**
     * 把 {@code s} 的全部预设域字段编码成一行。
     *
     * <p>格式：{@code key=value;key=value;...}，键序 = {@link #PRESET_FIELDS}。
     * 用「键值对」而不是「定长数组」：以后加参数时，<b>旧的自定义预设串仍然可读</b>
     * （缺的键保持当前值），不会因为多一个字段就整条报废。
     */
    public static String snapshot(NeovideCursorSettings.State s) {
        StringBuilder sb = new StringBuilder();
        for (SettingsField f : PRESET_FIELDS) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(f.key).append('=').append(f.encode(s));
        }
        return sb.toString();
    }

    /**
     * 把一行快照还原进 {@code s}。
     *
     * <p>宽容解析：未知键忽略、缺失键保持现值、非法值忽略。
     * 这样「旧版本存的预设串」在新版本里仍然能用（新字段取当前值）。
     *
     * @return 成功写入的字段数（探针用；0 表示整条非法）
     */
    public static int restore(NeovideCursorSettings.State s, String snapshot) {
        if (s == null || snapshot == null || snapshot.isEmpty()) {
            return 0;
        }
        int applied = 0;
        for (String entry : snapshot.split(";")) {
            if (entry == null) {
                continue;
            }
            String e = entry.trim();
            int eq = e.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            SettingsField f = byKey(e.substring(0, eq).trim());
            if (f == null || !f.inPreset) {
                continue;
            }
            if (f.decode(s, e.substring(eq + 1))) {
                applied++;
            }
        }
        return applied;
    }

    /**
     * 同 {@link #restore}，但<b>跳过指定的键</b>。
     *
     * <p><b>为什么需要它（第三轮审查 ②，high）</b>：自定义预设快照存的是全部 19 个参数，
     * 其中包含 {@code enabled} / {@code hideNativeCaret} / {@code trailMode} /
     * {@code alwaysHighFps} 这四个<b>运行环境开关</b>。
     *
     * <p>直接整份 restore 会造成：
     * <ul>
     *   <li>快照里 {@code enabled=false} → 点一下预设<b>把整个插件关掉</b>；</li>
     *   <li>反向：已经关掉的插件被点开；</li>
     *   <li>{@code hideNativeCaret} 被改动会写 IDEA 配色方案（不可逆副作用）。</li>
     * </ul>
     *
     * <p>而内置预设路径（{@code applyAnimPreset}）<b>从不碰</b>这四个字段 ——
     * 两条路径语义必须一致，否则「切内置预设安全、切自定义预设危险」。
     *
     * @param skip 要跳过的字段 key（可变参数，null 安全）
     * @return 成功写入的字段数
     */
    public static int restoreSkipping(NeovideCursorSettings.State s, String snapshot,
                                      String... skip) {
        if (s == null || snapshot == null || snapshot.isEmpty()) {
            return 0;
        }
        java.util.Set<String> skipSet = new java.util.HashSet<>();
        if (skip != null) {
            for (String k : skip) {
                if (k != null) {
                    skipSet.add(k);
                }
            }
        }
        int applied = 0;
        for (String entry : snapshot.split(";")) {
            if (entry == null) {
                continue;
            }
            String e = entry.trim();
            int eq = e.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = e.substring(0, eq).trim();
            if (skipSet.contains(key)) {
                continue;                   // 明确的运行环境开关：预设无权改动
            }
            SettingsField f = byKey(key);
            if (f == null || !f.inPreset) {
                continue;
            }
            if (f.decode(s, e.substring(eq + 1))) {
                applied++;
            }
        }
        return applied;
    }
}
