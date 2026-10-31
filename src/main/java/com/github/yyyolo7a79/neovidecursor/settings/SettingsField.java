package com.github.yyyolo7a79.neovidecursor.settings;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.intellij.openapi.diagnostic.Logger;

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

    private static final Logger LOG = Logger.getInstance(SettingsField.class);

    /**
     * 拒绝日志里原值的最大展示长度。
     *
     * <p>预设串可能被手改成几 KB，整条打进 idea.log 会把「是哪个键坏了」这条唯一有用的
     * 信息淹掉。截断后仍保留前 24 个字符与<b>总长度</b> —— 足够判断「是个正常值写错了」
     * 还是「整条数据被截断/污染了」。
     */
    private static final int RAW_LOG_MAX = 24;

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

    /**
     * 是否是「运行环境开关」—— 恢复自定义预设时<b>必须跳过</b>。
     *
     * <p>从本次重构起，这个判断从 {@code RUNTIME_SWITCH_KEYS} 字符串数组搬到了
     * 字段自己身上：加字段时标志就写在它那一行旁边，忘不掉，也拼不错。
     *
     * <p><b>判定标准</b>：这个字段改变的是「光标长什么样」，还是「插件怎么跑 /
     * 在什么设备上跑」？后者一律为 {@code true}。
     * 按此标准，{@code cursorWidthPx（光标宽度）} 与 {@code cursorHeightRatio（高度比例）}
     * 虽然影响观感，但它们跟着<b>屏幕 / 字体</b>走而不是跟着<b>风格</b>走 ——
     * 换机器该保持，所以仍是 {@code true}。
     *
     * <p><b>为什么必须跳过</b>：快照整份还原会造成
     * <ul>
     *   <li>{@code enabled} —— 快照里 {@code false} 时，点一下预设<b>把整个插件关掉</b>；
     *       反向则把已经关掉的插件点开</li>
     *   <li>{@code hideNativeCaret} —— 改写 IDEA 全局配色方案（不可逆、会落盘）</li>
     *   <li>{@code targetFps} —— 带白名单 setter（只收 125 / 144），
     *       恢复时会把历史值静默改成 125</li>
     * </ul>
     * 内置预设路径（{@code applyAnimPresetFields}）从不碰这些字段，两条路径语义必须一致。
     *
     * <p><b>历史</b>：0.9.87 之前这份清单只有 4 项（{@code enabled} /
     * {@code hideNativeCaret} / {@code trailMode} / {@code alwaysHighFps}），
     * 是从 {@code CursorPresetApplier} 的硬写清单原样搬过来时没按同一标准重新审视，
     * 漏了 5 个设备级字段 —— 切自定义预设会把用户的帧率和光标尺寸一起改掉。
     * 补到 9 项修好了，但「两份清单各自维护」的病根还在，于是有了本次重构。
     *
     * <p><b>不在本清单里的三个颜色字段</b>（{@code tailColor} / {@code shadowColor} /
     * {@code rgbGradient}）是另一回事：它们属于<b>颜色预设</b>域。目前它们同时在
     * {@code inPreset} 里，导致自定义<b>动画</b>预设也会写它们 —— 这是已知的机制重叠
     * （见 docs/PARAMETER-MAP.md），暂不改，因为改了会破坏
     * 「自定义预设恢复全部观感」的预期。
     */
    public final boolean runtimeSwitch;

    /**
     * 合法区间（闭区间），只对 {@link Kind#FLOAT} / {@link Kind#INT} 有意义。
     *
     * <p><b>这是全项目唯一一份「量程」</b>（2026-10-31 校验下沉）。它同时被三处消费：
     * <ol>
     *   <li>{@link #decode} —— 预设快照还原时夹回（面板 / Tools 菜单 / 将来任何入口）；</li>
     *   <li>{@code NeovideCursorSettings.loadState()} —— 启动读盘时夹回；</li>
     *   <li>{@code ui/settings/NeovideCursorConfigurable} 的滑块刻度端点 —— 由它导出，
     *       <b>不再手写第二份端点字面量</b>。</li>
     * </ol>
     *
     * <p><b>为什么放在字段表而不是面板</b>：量程是「这个字段合法取值范围」，属于数据契约，
     * 与「怎么画滑块」无关。放在 {@code ui/} 里，{@code settings/} 层的
     * {@code loadState} 就只能抄一份数字 —— 那正是陷阱 8「补了 4 项忘了 5 项」的同型错误。
     *
     * <p>{@link Double#NaN} 表示不校验（布尔 / 枚举 / 颜色 / 指针，以及
     * {@code targetFps} 这种「白名单 setter 自带校验」的字段）。
     */
    public final double min;

    /** 合法区间上界；语义见 {@link #min}。 */
    public final double max;

    private SettingsField(String key, Kind kind,
                          Function<NeovideCursorSettings.State, Object> getState,
                          BiConsumer<NeovideCursorSettings.State, Object> setState,
                          Function<NeovideConfig, Object> getConfig,
                          BiConsumer<NeovideConfig, Object> setConfig,
                          BiPredicate<Object, Object> same,
                          boolean syncsToConfig, boolean inPreset,
                          boolean runtimeSwitch) {
        this(key, kind, getState, setState, getConfig, setConfig, same,
                syncsToConfig, inPreset, runtimeSwitch, Double.NaN, Double.NaN);
    }

    private SettingsField(String key, Kind kind,
                          Function<NeovideCursorSettings.State, Object> getState,
                          BiConsumer<NeovideCursorSettings.State, Object> setState,
                          Function<NeovideConfig, Object> getConfig,
                          BiConsumer<NeovideConfig, Object> setConfig,
                          BiPredicate<Object, Object> same,
                          boolean syncsToConfig, boolean inPreset,
                          boolean runtimeSwitch, double min, double max) {
        this.key = key;
        this.kind = kind;
        this.getState = getState;
        this.setState = setState;
        this.getConfig = getConfig;
        this.setConfig = setConfig;
        this.same = same;
        this.syncsToConfig = syncsToConfig;
        this.inPreset = inPreset;
        this.runtimeSwitch = runtimeSwitch;
        this.min = min;
        this.max = max;
    }

    /**
     * 返回<b>带合法区间</b>的副本（本类对象不可变，工厂链式调用不会改到已发布实例）。
     *
     * <p>用法：写在字段自己那一行，紧挨着它的 setter ——
     * {@code out.add(floats("glowOpacity", ...).range(0, 1));}。
     * 与 {@code runtimeSwitch} 同一个思路：<b>契约写在字段定义旁边，忘不掉也抄不错</b>。
     */
    public SettingsField range(double lo, double hi) {
        if (kind != Kind.FLOAT && kind != Kind.INT) {
            throw new IllegalStateException("只有 FLOAT/INT 字段能有区间：" + key + " 是 " + kind);
        }
        // 【零宽区间必须当场炸】（2026-10-31 审查 M-2）range(0.05, 0.05) 的后果是
        // 「该字段永远被夹成同一个值」，滑块变成零宽度，而全链路一声不响 ——
        // 那正是本项目最难查的一类：没有异常、没有红、只有观感诡异。
        if (Double.isNaN(lo) || Double.isNaN(hi) || Double.isInfinite(lo) || Double.isInfinite(hi)
                || !(lo < hi)) {
            throw new IllegalStateException("区间非法：" + key + " [" + lo + "," + hi + "]");
        }
        // 【INT 端点必须是整数】（2026-10-31 审查 M-1）夹取用 ceil(min)/floor(max)，
        // 而滑块求端点用 writeRaw（Math.round）—— 非整数端点会让两边差 1，
        // 症状正是「拖到顶格 → 落盘 11 → 重启被改回 10」。今天四个 INT 字段都是整数端点，
        // 这条断言把「将来有人写 .range(0, 10.5)」从静默偏差变成类加载期崩溃。
        if (kind == Kind.INT && (lo != Math.rint(lo) || hi != Math.rint(hi))) {
            throw new IllegalStateException("INT 字段的区间端点必须是整数：" + key + " [" + lo + "," + hi + "]");
        }
        return new SettingsField(key, kind, getState, setState, getConfig, setConfig, same,
                syncsToConfig, inPreset, runtimeSwitch, lo, hi);
    }

    /** 本字段是否有合法区间（{@link #min} / {@link #max} 是否有效）。 */
    public boolean hasRange() {
        return !Double.isNaN(min) && !Double.isNaN(max);
    }

    /**
     * 把原始数值夹进本字段的合法区间；没有区间时原样返回。
     *
     * <p>NaN 一律夹到 {@link #min} —— 与 {@code NeovideCursorSettings.clamp(float)} 的
     * NaN 语义一致（NaN 会同时绕过两个比较，不单独处理就会流进绘制路径，见 H-1 / M-5）。
     */
    public double clampRange(double v) {
        if (!hasRange()) {
            return v;
        }
        if (Double.isNaN(v)) {
            return min;
        }
        return v < min ? min : (v > max ? max : v);
    }

    /** 整数下界（{@link #range} 已保证 INT 端点是整数，这里只是统一取整口径）。 */
    private int intLo() {
        return (int) Math.ceil(min);
    }

    /** 整数上界；与 {@link #intLo()} 同源，避免两处各写一套取整规则。 */
    private int intHi() {
        return (int) Math.floor(max);
    }

    /**
     * 按本字段的区间夹 {@code s} 的这个字段（就地写回；无区间时什么都不做）。
     *
     * <p>供 {@code NeovideCursorSettings.loadState()} 遍历字段表用 ——
     * 那里<b>不再出现任何 (lo, hi) 常量</b>。
     */
    public void clampStateToRange(NeovideCursorSettings.State s) {
        if (!hasRange()) {
            return;
        }
        Object v = getState.apply(s);
        if (kind == Kind.INT) {
            int i = (Integer) v;
            int lo = intLo();
            int hi = intHi();
            setState.accept(s, i < lo ? lo : (i > hi ? hi : i));
        } else if (kind == Kind.FLOAT) {
            setState.accept(s, (float) clampRange((Float) v));
        }
    }

    /**
     * 把原始值直接写进 {@code s}（<b>不做任何校验</b>）。
     *
     * <p>仅供两类自用场景：
     * <ul>
     *   <li>面板由「字段表的区间」导出滑块刻度端点 —— 需要往临时 State 里放一个端点原始值；</li>
     *   <li>探针构造「越界/哨兵」夹具 —— 它们要的是<b>绕过</b>校验，否则测的就是校验自己。</li>
     * </ul>
     * 业务代码不要用它写用户数据：那等于绕开唯一一层校验。
     */
    public void writeRaw(NeovideCursorSettings.State s, Object v) {
        if (kind == Kind.INT) {
            setState.accept(s, (int) Math.round(((Number) v).doubleValue()));
        } else if (kind == Kind.FLOAT) {
            setState.accept(s, (float) ((Number) v).doubleValue());
        } else {
            setState.accept(s, v);
        }
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

    /** 本字段是否有 {@code CONFIG} 侧映射（诊断日志据此决定能不能打印旧值→新值）。 */
    public boolean hasConfigMapping() {
        return getConfig != null;
    }

    /**
     * 读 {@code CONFIG} 的当前值（诊断用）。
     *
     * <p><b>调用前必须先用 {@link #hasConfigMapping()} 过滤</b>：不参与 CONFIG 的字段
     * （{@code stretchRatioK} / {@code typingSmoothness} / 各类只进 State 的运行期开关）
     * 没有 getConfig，这里会 NPE —— 故意不静默返回 null，因为把它当成"值就是 null"
     * 会让 {@link #sameValue} 在 boolean/float 字段上抛 ClassCastException，
     * 把一个诊断功能变成同步路径的崩溃点。
     */
    public Object readStateFromConfig(NeovideConfig c) {
        return getConfig.apply(c);
    }

    /**
     * 用本字段自己的比较语义判等 —— 供诊断日志判断「这个字段到底变没变」。
     *
     * <p>不能直接用 {@code Objects.equals}：HEX 需要忽略大小写、float 需要
     * {@link Float#compare}（NaN 与 -0.0f 两个边界）。本表项携带的 {@code same}
     * 就是全项目唯一那份比较语义，复用它才不会出现"日志说没变、实际变了"。
     */
    public boolean sameValue(Object a, Object b) {
        return same.test(a, b);
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
     * <p><b>量程校验（2026-10-31 下沉）</b>：FLOAT / INT 在类型校验之后按<b>本字段的
     * 区间</b>（{@link #min} / {@link #max}）夹回。两类「非法」的处置不同：
     * <ul>
     *   <li><b>类型非法</b>（NaN / 解析失败 / 非法枚举名 / 非 HEX）→ 保持当前值、不计入 applied；</li>
     *   <li><b>量程越界</b>（{@code glowLayers=99}）→ 夹回端点、<b>计入</b> applied、留一行 warn。</li>
     * </ul>
     * 后者与 {@code NeovideCursorSettings.loadState()} 对同一字段的处理逐位一致 ——
     * 这正是 H-1 要求的「两条入口共用同一份区间」。
     *
     * @return true = 成功写入（含被夹回的）；false = 内容非法、已忽略
     */
    public boolean decode(NeovideCursorSettings.State s, String raw) {
        return decode(s, raw, null);
    }

    /**
     * 同 {@link #decode(NeovideCursorSettings.State, String)}，并把「夹回 / 拒绝」记进报告。
     *
     * @param report 可为 {@code null}（只要布尔结果时）
     */
    public boolean decode(NeovideCursorSettings.State s, String raw, RestoreReport report) {
        if (raw == null) {
            return reject(null, "值为 null", report);
        }
        try {
            Object v;
            switch (kind) {
                case FLOAT: {
                    float f = Float.parseFloat(raw.trim());
                    if (Float.isNaN(f) || Float.isInfinite(f)) {
                        return reject(raw, "不是有限数", report);
                    }
                    v = clampFloat(f, raw, report);
                    break;
                }
                case INT: {
                    v = clampInt(Integer.parseInt(raw.trim()), raw, report);
                    break;
                }
                case BOOL: {
                    String t = raw.trim();
                    if ("1".equals(t) || "true".equalsIgnoreCase(t)) {
                        v = Boolean.TRUE;
                    } else if ("0".equals(t) || "false".equalsIgnoreCase(t)) {
                        v = Boolean.FALSE;
                    } else {
                        return reject(raw, "不是 0/1/true/false", report);
                    }
                    break;
                }
                case ENUM: {
                    v = resolveEnum(getState.apply(s), raw.trim());
                    if (v == null) {
                        return reject(raw, "不是合法枚举名", report);
                    }
                    break;
                }
                case HEX: {
                    // 【0.9.74 修复：HEX 分支原来完全不校验】
                    //
                    // 后果：手改坏的自定义预设串（例如 tailColor=\"not-a-color\"）
                    // 会被原样写进 State → NeovideColors.parse 解析失败 →
                    // 返回不透明黑色 → **光标变黑**，而 decode 返回 true（声称成功）。
                    // 用户看到的是「切了个预设光标黑了」，且没有任何错误提示。
                    //
                    // 现在要求 #RRGGBB 或 #RRGGBBAA（大小写不限），否则返回 false
                    // 让调用方保持原值 —— 与 ENUM 分支「非法就不写」的语义一致。
                    String hex = raw.trim();
                    if (!isValidHexColor(hex)) {
                        return reject(raw, "不是 #RRGGBB / #RRGGBBAA", report);
                    }
                    v = hex;
                    break;
                }
                default: {
                    v = raw.trim();
                    break;
                }
            }
            setState.accept(s, v);
            return true;
        } catch (NumberFormatException ex) {
            // 【2026-10-31 审查 M-5】这不是代码 bug，是**脏数据**的主用例：
            // `Integer.parseInt("abc")` / `Float.parseFloat("abc")` 就在上面的 try 里抛。
            // 原来它落到下面那条「带堆栈」的分支，于是用户每应用一次坏预设，
            // idea.log 里就有一条读起来像崩溃的堆栈 —— 排查的人会去查代码而不是查数据。
            return reject(raw, "不是数字", report);
        } catch (RuntimeException ex) {
            // 【必须带堆栈】解析失败（NumberFormatException）在上面单独接住了，所以能走到
            // 这里的只剩 setState.accept 里的 NPE / ClassCastException —— 也就是<b>代码
            // bug</b>，它是唯一真正需要堆栈的一类。
            return rejectWithTrace(raw, "解析抛异常", ex, report);
        }
    }

    /**
     * 按本字段区间夹一个 float，并在真的夹了时留一行 warn + 记进报告。
     *
     * @return 夹取后的值（区间内原样返回，<b>不过手</b>）
     */
    private float clampFloat(float f, String raw, RestoreReport report) {
        if (!hasRange()) {
            return f;
        }
        float lo = (float) min;
        float hi = (float) max;
        float c = f < lo ? lo : (f > hi ? hi : f);
        if (c != f) {
            noteClamped(raw, Float.toString(f), Float.toString(c), report);
        }
        return c;
    }

    /** 按本字段区间夹一个 int；语义同 {@link #clampFloat}。 */
    private int clampInt(int i, String raw, RestoreReport report) {
        if (!hasRange()) {
            return i;
        }
        int lo = intLo();
        int hi = intHi();
        int c = i < lo ? lo : (i > hi ? hi : i);
        if (c != i) {
            noteClamped(raw, Integer.toString(i), Integer.toString(c), report);
        }
        return c;
    }

    /**
     * 记录一次「已知键、值越界 → 已夹回」。
     *
     * <p><b>为什么必须留痕</b>：夹回本质上是「用户存的值被改了」。静默改值正是这条缺陷的
     * 原始症状（重启后值自己变了）。留一行 warn，用户报问题时能一眼看出「是哪个字段、
     * 从多少夹到多少」。日志里写<b>字段 key</b>（排查时唯一能对上代码的东西）。
     */
    private void noteClamped(String raw, String from, String to, RestoreReport report) {
        if (report != null && !report.clamped.contains(key)) {
            // 【L-2，2026-10-31 审查】快照里同一个键写了两次时只记一次 ——
            // 否则面板会提示「有 2 个参数超出正常范围」，而实际上只有一个参数。
            report.clamped.add(key);
        }
        try {
            LOG.warn("neovide-cursor: 预设字段 " + key + " 的值越界（" + from + "），已夹回合法区间 ["
                    + formatBound(min) + "," + formatBound(max) + "] 内的 " + to
                    + "。原值=[" + shown(raw) + "]");
        } catch (Throwable ignored) {
            // 日志失败不得让 decode 抛异常（见 decode 的契约）
        }
    }

    /** 记录一次「已知键、值非法」的拒绝，并返回 {@code false}。 */
    private boolean reject(String raw, String why, RestoreReport report) {
        return rejectWithTrace(raw, why, null, report);
    }

    /**
     * 记录一次拒绝（可附带异常），并返回 {@code false}。
     *
     * <p><b>为什么能确定是「值非法」而不是「缺键」</b>：未知键在
     * {@link #restore}/{@link #restoreSkipping} 的 {@code byKey()} 处就被 continue 掉了，
     * 而缺键根本不会走到 {@code decode}。所以 {@code decode} 返回 false
     * 一定等于「这个键存在、但值不合法」—— 这两者必须能区分开：前者是数据坏了要留痕，
     * 后者是版本兼容的正常宽容，不该刷日志（见这两处方法的 javadoc）。
     *
     * <p><b>只有 {@code catch (RuntimeException)} 那条路径带堆栈</b>：那类失败是代码 bug，
     * 只留一个类名（{@code NullPointerException}）对定位毫无帮助；脏数据在上面的各分支
     * 就已经被自己那条 reject 拦下了。
     *
     * <p><b>日志本身也包了 try/catch</b>：{@code decode} 的第一契约是「绝不抛异常」
     * （见其 javadoc），日志子系统失败不能反过来破坏它。
     */
    private boolean rejectWithTrace(String raw, String why, Throwable t, RestoreReport report) {
        if (report != null && !report.rejected.contains(key)) {
            report.rejected.add(key);      // 同上：重复键只记一次
        }
        try {
            String msg = "neovide-cursor: 预设字段 " + key + " 的值非法（" + why
                    + "），保持当前值。值=[" + shown(raw) + "]";
            if (t == null) {
                LOG.warn(msg);
            } else {
                LOG.warn(msg, t);
            }
        } catch (Throwable ignored) {
            // 见上：日志失败不得让 decode 抛异常
        }
        return false;
    }

    /** 日志里原值的展示形态（超长截断，保留前缀与总长度）。 */
    private static String shown(String raw) {
        if (raw == null) {
            return "(null)";
        }
        return raw.length() <= RAW_LOG_MAX ? raw
                : raw.substring(0, RAW_LOG_MAX) + "…(共 " + raw.length() + " 字符)";
    }

    /** 区间的展示形态：整数字段不带 `.0`，浮点保留原样。 */
    private String formatBound(double v) {
        if (kind == Kind.INT && v == Math.rint(v)) {
            return Integer.toString((int) v);
        }
        return Float.toString((float) v);
    }

    /**
     * HEX 颜色串是否合法：{@code #RRGGBB} 或 {@code #RRGGBBAA}，大小写不限。
     *
     * <p><b>9 位的位序是 {@code RR GG BB AA}</b>（末两位 alpha）—— 与
     * {@code core/NeovideColors.parse} 的解析顺序一致，也是用户 2026-10-30 拍板的口径。
     * 本方法只判「长度与字符是否十六进制」，位序由绘制层决定；这里把位序写清楚，
     * 是因为用户看到的提示语就是抄这一句（写反会让人按 {@code #AARRGGBB} 手写，
     * 得到一个 alpha=0 的全透明光标）。
     *
     * <p>只做格式校验，不校验"颜色是否好看" —— 那是 UI 的事。
     * 这里的目标是挡住「明显不是颜色」的串，避免它流进绘制层变成黑色。
     */
    public static boolean isValidHexColor(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.length() != 7 && t.length() != 9) {
            return false;
        }
        if (t.charAt(0) != '#') {
            return false;
        }
        for (int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
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
    //
    // 每个工厂有两版：
    //   · 6 参数版 —— 非「运行环境开关」字段用，runtimeSwitch 由内部填 false
    //   · 7 参数版 —— 运行环境开关字段用，末尾多一个 runtimeSwitch=true
    // 这样 17 个普通字段的调用点一字不动，diff 与审计面都更小。

    private static SettingsField bools(String key,
                                       Function<NeovideCursorSettings.State, Boolean> gs,
                                       BiConsumer<NeovideCursorSettings.State, Boolean> ss,
                                       Function<NeovideConfig, Boolean> gc,
                                       BiConsumer<NeovideConfig, Boolean> sc) {
        return bools(key, gs, ss, gc, sc, false);
    }

    private static SettingsField bools(String key,
                                       Function<NeovideCursorSettings.State, Boolean> gs,
                                       BiConsumer<NeovideCursorSettings.State, Boolean> ss,
                                       Function<NeovideConfig, Boolean> gc,
                                       BiConsumer<NeovideConfig, Boolean> sc,
                                       boolean runtimeSwitch) {
        return new SettingsField(key, Kind.BOOL,
                s -> gs.apply(s), (s, v) -> ss.accept(s, (Boolean) v),
                gc == null ? null : c -> gc.apply(c),
                sc == null ? null : (c, v) -> sc.accept(c, (Boolean) v),
                (a, b) -> ((Boolean) a).booleanValue() == ((Boolean) b).booleanValue(),
                gc != null, !runtimeSwitch, runtimeSwitch);
    }

    /**
     * 全局项：<b>不进任何预设</b>，也不是「设备开关」（{@code runtimeSwitch=false}）。
     *
     * <p>用于 {@code rgbGradient} 这类「属于颜色域、由颜色预设路径管」的开关 ——
     * 它既不该出现在动画预设快照里，也不该被 {@code restoreSkipping} 当成设备开关解释。
     *
     * <p><b>为什么不能只标 {@code runtimeSwitch}</b>：那个标志的语义是「插件怎么跑 / 在什么设备上跑」，
     * 颜色开关不是这一类；混用会让下一个读代码的人以为「渐变是设备设置」。
     */
    private static SettingsField boolsGlobal(String key,
                                             Function<NeovideCursorSettings.State, Boolean> gs,
                                             BiConsumer<NeovideCursorSettings.State, Boolean> ss) {
        return new SettingsField(key, Kind.BOOL,
                s -> gs.apply(s), (s, v) -> ss.accept(s, (Boolean) v),
                null, null,
                (a, b) -> ((Boolean) a).booleanValue() == ((Boolean) b).booleanValue(),
                false, false, false);
    }

    /**
     * 全局项：<b>不进任何预设</b>，也不是设备开关。
     *
     * <p>用于 {@code typingSmoothness} —— 0.9.73 就定了「平滑不参与预设」，
     * 但它一直留在快照里，导致「切自定义预设会改平滑」（与文档记载相反）。
     * 现在从字段定义这一行就说清楚，不再依赖运行时跳过清单。
     */
    private static SettingsField floatsGlobal(String key,
                                              Function<NeovideCursorSettings.State, Float> gs,
                                              BiConsumer<NeovideCursorSettings.State, Float> ss) {
        return new SettingsField(key, Kind.FLOAT,
                s -> gs.apply(s), (s, v) -> ss.accept(s, (Float) v),
                null, null,
                (a, b) -> Float.compare((Float) a, (Float) b) == 0,
                false, false, false);
    }

    private static SettingsField ints(String key,
                                      Function<NeovideCursorSettings.State, Integer> gs,
                                      BiConsumer<NeovideCursorSettings.State, Integer> ss,
                                      Function<NeovideConfig, Integer> gc,
                                      BiConsumer<NeovideConfig, Integer> sc) {
        return ints(key, gs, ss, gc, sc, false);
    }

    private static SettingsField ints(String key,
                                      Function<NeovideCursorSettings.State, Integer> gs,
                                      BiConsumer<NeovideCursorSettings.State, Integer> ss,
                                      Function<NeovideConfig, Integer> gc,
                                      BiConsumer<NeovideConfig, Integer> sc,
                                      boolean runtimeSwitch) {
        return new SettingsField(key, Kind.INT,
                s -> gs.apply(s), (s, v) -> ss.accept(s, (Integer) v),
                gc == null ? null : c -> gc.apply(c),
                sc == null ? null : (c, v) -> sc.accept(c, (Integer) v),
                (a, b) -> ((Integer) a).intValue() == ((Integer) b).intValue(),
                gc != null, !runtimeSwitch, runtimeSwitch);
    }

    private static SettingsField floats(String key,
                                        Function<NeovideCursorSettings.State, Float> gs,
                                        BiConsumer<NeovideCursorSettings.State, Float> ss,
                                        Function<NeovideConfig, Float> gc,
                                        BiConsumer<NeovideConfig, Float> sc) {
        return floats(key, gs, ss, gc, sc, false);
    }

    private static SettingsField floats(String key,
                                        Function<NeovideCursorSettings.State, Float> gs,
                                        BiConsumer<NeovideCursorSettings.State, Float> ss,
                                        Function<NeovideConfig, Float> gc,
                                        BiConsumer<NeovideConfig, Float> sc,
                                        boolean runtimeSwitch) {
        return new SettingsField(key, Kind.FLOAT,
                s -> gs.apply(s), (s, v) -> ss.accept(s, (Float) v),
                gc == null ? null : c -> gc.apply(c),
                sc == null ? null : (c, v) -> sc.accept(c, (Float) v),
                (a, b) -> Float.compare((Float) a, (Float) b) == 0,
                gc != null, !runtimeSwitch, runtimeSwitch);
    }

    private static SettingsField hexColors(String key,
                                           Function<NeovideCursorSettings.State, String> gs,
                                           BiConsumer<NeovideCursorSettings.State, String> ss,
                                           Function<NeovideConfig, String> gc,
                                           BiConsumer<NeovideConfig, String> sc) {
        return hexColors(key, gs, ss, gc, sc, false);
    }

    private static SettingsField hexColors(String key,
                                           Function<NeovideCursorSettings.State, String> gs,
                                           BiConsumer<NeovideCursorSettings.State, String> ss,
                                           Function<NeovideConfig, String> gc,
                                           BiConsumer<NeovideConfig, String> sc,
                                           boolean runtimeSwitch) {
        return new SettingsField(key, Kind.HEX,
                s -> gs.apply(s), (s, v) -> ss.accept(s, (String) v),
                gc == null ? null : c -> gc.apply(c),
                sc == null ? null : (c, v) -> sc.accept(c, (String) v),
                (a, b) -> a == null ? b == null : ((String) a).equalsIgnoreCase((String) b),
                // 颜色域：颜色由「颜色预设 + 用户手选」管，动画预设不碰（用户 2026-10-28 决定）
                gc != null, false, runtimeSwitch);
    }

    private static SettingsField enums(String key,
                                       Function<NeovideCursorSettings.State, NeovideConfig.TrailMode> gs,
                                       BiConsumer<NeovideCursorSettings.State, NeovideConfig.TrailMode> ss,
                                       Function<NeovideConfig, NeovideConfig.TrailMode> gc,
                                       BiConsumer<NeovideConfig, NeovideConfig.TrailMode> sc) {
        return enums(key, gs, ss, gc, sc, false);
    }

    private static SettingsField enums(String key,
                                       Function<NeovideCursorSettings.State, NeovideConfig.TrailMode> gs,
                                       BiConsumer<NeovideCursorSettings.State, NeovideConfig.TrailMode> ss,
                                       Function<NeovideConfig, NeovideConfig.TrailMode> gc,
                                       BiConsumer<NeovideConfig, NeovideConfig.TrailMode> sc,
                                       boolean runtimeSwitch) {
        return new SettingsField(key, Kind.ENUM,
                s -> gs.apply(s), (s, v) -> ss.accept(s, (NeovideConfig.TrailMode) v),
                gc == null ? null : c -> gc.apply(c),
                sc == null ? null : (c, v) -> sc.accept(c, (NeovideConfig.TrailMode) v),
                (a, b) -> a == b,
                gc != null, !runtimeSwitch, runtimeSwitch);
    }

    /** 预设基准下标：参与 apply / 判脏，但<b>不进快照</b>（它是「选中了谁」的指针）。 */
    private static SettingsField presetPointer() {
        return new SettingsField("animPresetBase", Kind.INT,
                s -> s.animPresetBase, (s, v) -> s.animPresetBase = (Integer) v,
                null, null,
                (a, b) -> ((Integer) a).intValue() == ((Integer) b).intValue(),
                false, false,
                // 指针既不进快照，也不属于「运行环境开关」—— 两个标志都为 false。
                false);
    }

    // ==================== 表本体 ====================

    /** 全部字段（顺序 = 契约 §6.2 书写顺序）。 */
    public static final List<SettingsField> FIELDS = build();

    /** 参与自定义预设快照的字段（{@link #FIELDS} 去掉 {@code animPresetBase}）。 */
    public static final List<SettingsField> PRESET_FIELDS = buildPresetFields();

    private static List<SettingsField> build() {
        List<SettingsField> out = new ArrayList<>();

        out.add(bools("enabled", s -> s.enabled, (s, v) -> s.enabled = v,
                c -> c.enabled, (c, v) -> c.enabled = v, true));
        out.add(enums("trailMode", s -> s.trailMode, (s, v) -> s.trailMode = v,
                c -> c.trailMode, (c, v) -> c.trailMode = v, true));
        out.add(bools("hideNativeCaret", s -> s.hideNativeCaret, (s, v) -> s.hideNativeCaret = v,
                c -> c.hideNativeCaret, (c, v) -> c.hideNativeCaret = v, true));
        out.add(bools("alwaysHighFps", s -> s.alwaysHighFps, (s, v) -> s.alwaysHighFps = v,
                c -> c.alwaysHighFps, (c, v) -> c.alwaysHighFps = v, true));
        // 【0.9.76 新增 targetFps】只允许 125 / 144 两个值。
        // 写入时做白名单校验，避免手改 XML 塞进 240（那个档位实测达不到）。
        out.add(ints("targetFps", s -> s.targetFps,
                (s, v) -> s.targetFps = (v == 144) ? 144 : 125,
                null, null, true));
        // 斜度（slopeTicks）：0..100。
        // 【2026-10-31 校验下沉】区间改由 .range(0, 100) 表达 —— 越界值的处置从
        // 「setter 里回落 0」变成「decode/loadState 按区间夹到最近端点」，
        // 与其余数值字段同一套规则（原先是 999 → 0、-1 → 0 的单向回落，是唯一的例外）。
        out.add(ints("slopeTicks", s -> s.slopeTicks,
                (s, v) -> s.slopeTicks = v,
                null, null).range(0, 100));
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
                null, null, true));

        // 【多光标不辉光】同样是「只进 State、不进 CONFIG」的运行期开关 ——
        // CONFIG 里没有对应字段（生效逻辑在 CaretAnimator 里读 State）。
        out.add(bools("multiCursorNoGlow", s -> s.multiCursorNoGlow,
                (s, v) -> s.multiCursorNoGlow = v, null, null, true));

        // 【光标尺寸】只进 State（绘制时读 State，不进 CONFIG）
        out.add(ints("cursorWidthPx", s -> s.cursorWidthPx,
                (s, v) -> s.cursorWidthPx = v, null, null, true).range(1, 5));
        out.add(floats("cursorHeightRatio", s -> s.cursorHeightRatio,
                (s, v) -> s.cursorHeightRatio = v, null, null, true).range(0.3, 1.0));
        // 【颜色域，不进动画预设】用户 2026-10-28 决定：动画预设完全不管颜色。
        // 之前它漏标了（走的是普通 bools），导致切自定义预设会顺带改你的渐变开关。
        out.add(boolsGlobal("rgbGradient", s -> s.rgbGradient,
                (s, v) -> s.rgbGradient = v));
        out.add(ints("cursorDisappearDelay", s -> s.cursorDisappearDelay, (s, v) -> s.cursorDisappearDelay = v,
                c -> c.cursorDisappearDelay, (c, v) -> c.cursorDisappearDelay = v).range(0, 500));
        out.add(floats("cursorFadeOutDuration", s -> s.cursorFadeOutDuration, (s, v) -> s.cursorFadeOutDuration = v,
                c -> c.cursorFadeOutDuration, (c, v) -> c.cursorFadeOutDuration = v).range(0, 1.0));
        out.add(hexColors("tailColor", s -> s.tailColor, (s, v) -> s.tailColor = v,
                c -> c.tailColor, (c, v) -> c.tailColor = v));
        out.add(floats("tailOpacity", s -> s.tailOpacity, (s, v) -> s.tailOpacity = v,
                c -> c.tailOpacity, (c, v) -> c.tailOpacity = v).range(0, 1));
        out.add(bools("useShadow", s -> s.useShadow, (s, v) -> s.useShadow = v,
                c -> c.useShadow, (c, v) -> c.useShadow = v));
        out.add(hexColors("shadowColor", s -> s.shadowColor, (s, v) -> s.shadowColor = v,
                c -> c.shadowColor, (c, v) -> c.shadowColor = v));
        out.add(floats("glowOpacity", s -> s.glowOpacity, (s, v) -> s.glowOpacity = v,
                c -> c.glowOpacity, (c, v) -> c.glowOpacity = v).range(0, 1));
        out.add(ints("glowLayers", s -> s.glowLayers, (s, v) -> s.glowLayers = v,
                c -> c.glowLayers, (c, v) -> c.glowLayers = v).range(1, 12));
        out.add(floats("shadowBlurFactor", s -> s.shadowBlurFactor, (s, v) -> s.shadowBlurFactor = v,
                c -> c.shadowBlurFactor, (c, v) -> c.shadowBlurFactor = v).range(0.05, 0.60));
        out.add(floats("trailSize", s -> s.trailSize, (s, v) -> s.trailSize = v,
                c -> c.trailSize, (c, v) -> c.trailSize = v).range(0, 1));
        // stretchRatioK / typingSmoothness 派生 trailFactors，CONFIG 无对应标量
        out.add(floats("stretchRatioK", s -> s.stretchRatioK, (s, v) -> s.stretchRatioK = v, null, null)
                .range(1.0, 8.0));
        // 【全局项，不进任何预设】0.9.73 就定了「平滑不参与预设」，但它一直留在快照里 ——
        // 实测后果：切自定义预设会把平滑一起改掉（与文档记载相反）。
        out.add(floatsGlobal("typingSmoothness", s -> s.typingSmoothness,
                (s, v) -> s.typingSmoothness = v).range(0, 1));
        // 动画时长的区间<b>引用</b> NeovideCursorSettings 的公开常量（同一份数字，不抄第二遍）
        out.add(floats("animationLength", s -> s.animationLength, (s, v) -> s.animationLength = v,
                c -> c.animationLength, (c, v) -> c.animationLength = v)
                .range(NeovideCursorSettings.ANIM_LENGTH_MIN, NeovideCursorSettings.ANIM_LENGTH_MAX));
        out.add(floats("shortAnimationLength", s -> s.shortAnimationLength, (s, v) -> s.shortAnimationLength = v,
                c -> c.shortAnimationLength, (c, v) -> c.shortAnimationLength = v)
                .range(NeovideCursorSettings.SHORT_LENGTH_MIN, NeovideCursorSettings.SHORT_LENGTH_MAX));
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
        // 2026-10-29：颜色预设的「当前选中项 / 基准」也要点亮应用按钮。
        // customAnimPresets / customColorPresets 仍不比 —— 它们是派生数据（理由同上）。
        return !textSame(p.animCustomName, s.animCustomName)
                || !textSame(p.colorCustomName, s.colorCustomName)
                || p.colorPresetBase != s.colorPresetBase;
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
     * 一次快照还原的<b>完整账</b>：写成功几项、夹回哪些、拒绝哪些。
     *
     * <p><b>为什么要有它</b>（第 1 批 H-2 的延续）：只返回一个 {@code applied} 数字时，
     * 调用方无法区分「这个键不在快照里」（旧版本预设，属正常兼容）、「这个键在、值越界被夹回」、
     * 与「这个键在、值非法被拒」。三种情况用户可见的后果完全不同：
     * 第一种该静默，第二种<b>必须告诉用户「你的值被改了」</b>（面板据此自愈回写快照，
     * 否则每点一次下拉都弹一次提示），第三种该留痕。
     *
     * <p>下沉之后校验只有一份，这份账也只有一个来源 —— 调用方不再自己重算一遍，
     * 这正是「不许出现第二份量程表」在<b>报告层</b>的等价要求。
     */
    public static final class RestoreReport {
        /** 真正写进 State 的字段数（<b>含被夹回的</b>；被拒的不算）。 */
        public int applied;
        /** 因越界被夹回的字段 key（按快照出现顺序）。 */
        public final List<String> clamped = new ArrayList<>();
        /** 因类型非法被拒、保持当前值的字段 key（按快照出现顺序）。 */
        public final List<String> rejected = new ArrayList<>();
    }

    /**
     * 把一行快照还原进 {@code s}。
     *
     * <p>宽容解析：未知键忽略、缺失键保持现值、非法值忽略、越界值夹回。
     * 这样「旧版本存的预设串」在新版本里仍然能用（新字段取当前值）。
     *
     * @return 成功写入的字段数（探针用；0 表示整条非法）
     */
    public static int restore(NeovideCursorSettings.State s, String snapshot) {
        return restore(s, snapshot, null);
    }

    private static int restore(NeovideCursorSettings.State s, String snapshot, RestoreReport report) {
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
            if (f.decode(s, e.substring(eq + 1), report)) {
                applied++;
            }
        }
        return applied;
    }

    /**
     * 同 {@link #restore}，但<b>跳过所有「运行环境开关」字段</b>。
     *
     * <p><b>为什么需要它（第三轮审查 ②，high）</b>：自定义预设快照存的是全部预设域参数，
     * 其中包含 {@code enabled} / {@code hideNativeCaret} / {@code trailMode} 这类
     * <b>运行环境开关</b>。直接整份 restore 会造成：
     * <ul>
     *   <li>快照里 {@code enabled=false} → 点一下预设<b>把整个插件关掉</b>；</li>
     *   <li>反向：已经关掉的插件被点开；</li>
     *   <li>{@code hideNativeCaret} 被改动会写 IDEA 配色方案（不可逆副作用）。</li>
     * </ul>
     *
     * <p>而内置预设路径（{@code applyAnimPreset}）<b>从不碰</b>这些字段 ——
     * 两条路径语义必须一致，否则「切内置预设安全、切自定义预设危险」。
     *
     * <p><b>跳过哪些</b>由字段自身的 {@link #runtimeSwitch} 决定，判定标准与历史见该字段的 javadoc。
     * 本次重构前是一份独立的 {@code RUNTIME_SWITCH_KEYS} 字符串数组，与字段表分家维护，
     * 漏过一次 5 个字段 —— 现在判断跟字段定义同处一行，不可能再漂移。
     *
     * @return 成功写入的字段数
     */
    public static int restoreSkipping(NeovideCursorSettings.State s, String snapshot) {
        return restoreSkipping(s, snapshot, null);
    }

    /**
     * 同 {@link #restoreSkipping}，但返回完整账（{@link RestoreReport}）。
     *
     * <p><b>这是面板与 Tools 菜单两条入口共同消费的唯一出口</b>：
     * 「夹回了哪些字段」由这一层判定，调用方只负责「怎么告诉用户」。
     * 在此之前，面板自己遍历一遍 {@code SPECS} 再算一次夹回 —— 那就是第二份量程表。
     */
    public static RestoreReport restoreSkippingReported(NeovideCursorSettings.State s, String snapshot) {
        RestoreReport report = new RestoreReport();
        report.applied = restoreSkipping(s, snapshot, report);
        return report;
    }

    private static int restoreSkipping(NeovideCursorSettings.State s, String snapshot,
                                       RestoreReport report) {
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
            String key = e.substring(0, eq).trim();
            SettingsField f = byKey(key);
            if (f == null || !f.inPreset) {
                continue;
            }
            if (f.runtimeSwitch) {
                continue;                   // 运行环境开关：预设无权改动
            }
            if (f.decode(s, e.substring(eq + 1), report)) {
                applied++;
            }
        }
        return applied;
    }

    // ==================== 颜色快照（第二个下沉点）====================

    /**
     * 颜色快照的还原结果。
     *
     * <p>颜色快照<b>不是</b> {@code key=value} 全字段格式，而是
     * {@code tailColor=#..;shadowColor=#..} 两个键（见 {@code CursorPresets.parseColorSnapshot}），
     * 所以它有自己的解析器 —— 但<b>合法性判据必须与字段表同源</b>，
     * 这就是它放在本类而不是面板里的理由。
     */
    public enum ColorRestoreResult {
        /** 两个颜色都合法且已写入。 */
        OK,
        /** 快照里缺 tailColor / shadowColor（整条损坏或不是颜色快照）。 */
        MISSING_FIELDS,
        /** 两个键都在，但至少一个不是合法 HEX。 */
        BAD_FORMAT
    }

    /**
     * 把一条<b>颜色预设快照</b>还原进 {@code s}。
     *
     * <p><b>为什么必须在这里（U-2）</b>：这条路径原来有两个实现 ——
     * 面板的 {@code applyColorSnapshot} 调 {@link #isValidHexColor}，
     * Tools 菜单的 {@code applyCustomColorPreset} 只判 {@code hex == null}。
     * 于是「同一份坏快照」在面板被拒、在菜单被原样写进 State，
     * 绘制层 {@code NeovideColors.parse} 解析失败 → <b>光标变黑</b>。
     *
     * <p><b>不写半套</b>：两个颜色都合法才一起写。半个颜色写进去 = 用户看到
     * 「拖尾颜色变了、辉光颜色没变」，比整条拒绝更难排查。
     *
     * <p><b>写入前 trim</b>：{@link #isValidHexColor} 内部会 trim（判的是「trim 后是否合法」），
     * 而绘制层不 trim —— 把带空白的原串写进 State 就是「校验通过、绘制认不出、光标变黑」。
     */
    public static ColorRestoreResult restoreColorSnapshot(NeovideCursorSettings.State s, String snapshot) {
        if (s == null) {
            return ColorRestoreResult.MISSING_FIELDS;
        }
        String[] hex = CursorPresets.parseColorSnapshot(snapshot);
        if (hex == null) {
            return ColorRestoreResult.MISSING_FIELDS;
        }
        String tail = hex[0].trim();
        String shadow = hex[1].trim();
        if (!isValidHexColor(tail) || !isValidHexColor(shadow)) {
            try {
                LOG.warn("neovide-cursor: 颜色预设快照里有非法颜色（tailColor=[" + shown(hex[0])
                        + "] shadowColor=[" + shown(hex[1]) + "]），保持当前值");
            } catch (Throwable ignored) {
                // 日志失败不得改变结论
            }
            return ColorRestoreResult.BAD_FORMAT;
        }
        s.tailColor = tail;
        s.shadowColor = shadow;
        return ColorRestoreResult.OK;
    }
}
