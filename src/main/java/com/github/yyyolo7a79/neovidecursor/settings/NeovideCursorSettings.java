package com.github.yyyolo7a79.neovidecursor.settings;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * 拖尾光标的持久化服务（application 级）。
 *
 * <p><b>为什么需要它</b>：设置界面出现之前，本插件有两套互不相干的持久化 ——
 * {@link NeovideConfig} 的字段默认值（代码里的编译期常量），以及两个开关各自的
 * {@code PropertiesComponent} 键（见下方 {@code OLD_KEY_*} 常量）。一旦设置面板可以改全部 16 项，
 * 这三处就会变成三个真相源：面板里是 A、Tools 菜单里是 B、重启后是 C。
 *
 * <p>本类把「持久化真相」收敛到一处：{@link State}。数据流是单向的
 *
 * <pre>
 *   平台 XML ──loadState/noStateLoaded──▶ State ──syncStateToConfig()──▶ NeovideConfig CONFIG
 *                                          ▲                                     │
 *                                          └──── 面板 apply() / 单点 setter ◀─────┘
 * </pre>
 *
 * <p><b>为什么 {@link NeovideConfig} 不被取代</b>（三条独立理由，任一成立即足）：
 * <ol>
 *   <li><b>热路径零改动</b>：{@code CaretAnimator} / {@code TrailCorner} /
 *       {@code CaretTrailPanel} / {@code AfterimageTrail} 都持有同一份 config 引用并
 *       <b>每帧</b>读字段。改成"每次读都问 service"会把每帧几十次字段读变成服务调用。</li>
 *   <li><b>parity harness 完全依赖它</b>：{@code tools/parity/PhysicsParity.java:73}、
 *       {@code GeometryParity.java:45}、{@code TrailParityTest.java:75}、
 *       {@code tools/ime-probe/ImeGeomCriterionTest.java:203} 全部 {@code new NeovideConfig()}
 *       并依赖其默认值。取代它就是让三件套失效。</li>
 *   <li><b>审计脚本只认这个文件</b>：{@code tools/parity/run-audit.ps1} 的 19 项字面量检查
 *       全部指向 {@code core/NeovideConfig.java}。把默认值搬走等于让审计变成瞎子。</li>
 * </ol>
 * 因此 {@code NeovideConfig} 保持「运行期快照 + 默认值来源」的纯 POJO 身份，本类只做
 * <b>拷贝</b>，绝不复制一份默认值出来。
 *
 * <p><b>roaming 关闭（{@link RoamingType#DISABLED}）</b>：这批字段里
 * {@code alwaysHighFps} / {@code glowLayers} / {@code shadowBlurFactor} 是<b>性能相关</b>的
 * （弱机器上该关），平台文档也建议「特定于机器的设置不要同步」。
 */
@State(name = "NeovideCursorSettings",
       storages = @Storage(value = "NeovideCursorSettings.xml",
                           roamingType = RoamingType.DISABLED))
public final class NeovideCursorSettings implements PersistentStateComponent<NeovideCursorSettings.State> {

    private static final Logger LOG = Logger.getInstance(NeovideCursorSettings.class);

    // ==================== 旧键（迁移用，迁移完成后这两个常量会被删除）====================

    /**
     * 旧键 1：「常驻刷新」。
     *
     * <p>0.9.0 ~ 0.9.3 期间由 {@code NeovideCaretManager} 直接读写
     * {@link PropertiesComponent}。历史上这里有个 bug：读不到时的兜底值是 {@code false}，
     * 而 {@link NeovideConfig#alwaysHighFps} 的字段默认值是 {@code true} ——
     * 干净安装的用户实际拿到 {@code false}，与文档宣称相反。
     * 本类的迁移按 {@link PropertiesComponent#isValueSet} 分两支，把「从没存过」与
     * 「明确存过 false」区分开，见 {@link #migrateIfNeeded()}。
     */
    private static final String OLD_KEY_ALWAYS_HIGH_FPS = "neovide.cursor.alwaysHighFps";

    /** 旧键 2：「辉光淡出」。迁移策略同 {@link #OLD_KEY_ALWAYS_HIGH_FPS}。 */
    private static final String OLD_KEY_OVERLAY_FADE_OUT = "neovide.cursor.overlayFadeOut";

    /** 迁移版本号。{@code 1} = 旧键已迁移完毕；{@code 0} = 尚未迁移（或本轮迁移未成功落盘）。 */
    public static final int STATE_VERSION_MIGRATED = 1;

    // ==================== 动画时序的取值范围（契约 §7.1 / §7.2）====================

    /** 跨行动画时长下限（秒）。= {@code MAX_DT}：等于它会被一帧短路成瞬移，故是硬边界。 */
    public static final float ANIM_LENGTH_MIN = 0.05f;

    /** 跨行动画时长上限（秒）。0.35 的量化依据见 {@code State#animationLength} 的 javadoc。 */
    public static final float ANIM_LENGTH_MAX = 0.35f;

    /** 短动画时长下限（秒）。 */
    public static final float SHORT_LENGTH_MIN = 0.02f;

    /** 短动画时长上限（秒）。 */
    public static final float SHORT_LENGTH_MAX = 0.25f;

    private State state = new State();

    public static NeovideCursorSettings getInstance() {
        return ApplicationManager.getApplication().getService(NeovideCursorSettings.class);
    }

    // ==================== PersistentStateComponent ====================

    /**
     * 返回内部 {@code state} 对象本身 —— <b>不新建、不复制</b>。
     *
     * <p>它是唯一持久化真相；拷贝会立刻制造第二个真相源。平台只在保存时才读它。
     */
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State from) {
        State target = new State();

        // ---- 逐字段落地：每个字段都必须容忍缺省与越界 ----
        // XML 可能是旧版本、也可能是用户手改过的。任何一项解析失败都不该
        // 让整个 State 变成 null（那会让下一帧读到 null 字符串而崩溃）。

        target.stateVersion = Math.max(0, from.stateVersion);

        target.enabled = from.enabled;
        target.trailMode = (from.trailMode == null) ? NeovideConfig.TrailMode.SPRING : from.trailMode;

        target.hideNativeCaret = from.hideNativeCaret;
        target.alwaysHighFps = from.alwaysHighFps;
        target.overlayFadeOut = from.overlayFadeOut;

        target.cursorDisappearDelay = clamp(from.cursorDisappearDelay, 0, 500);
        target.cursorFadeOutDuration = clamp(from.cursorFadeOutDuration, 0f, 1.0f);

        target.tailColor = sanitizeColor(from.tailColor, new NeovideConfig().tailColor);
        target.tailOpacity = clamp(from.tailOpacity, 0f, 1f);

        target.useShadow = from.useShadow;
        target.shadowColor = sanitizeColor(from.shadowColor, new NeovideConfig().shadowColor);

        target.glowOpacity = clamp(from.glowOpacity, 0f, 1f);
        target.glowLayers = clamp(from.glowLayers, 1, 12);
        target.shadowBlurFactor = clamp(from.shadowBlurFactor, 0.05f, 0.60f);

        target.trailSize = clamp(from.trailSize, 0f, 1f);
        target.stretchRatioK = clamp(from.stretchRatioK, 1.0f, 8.0f);

        // 17/18：0.9.5 新增的两个「动画时序」字段（契约 §7.1）—— 也必须容忍缺省与越界。
        // 老 XML 里没有这两项 → 平台按字段初值（= NeovideConfig 现值）填充，行为不变。
        target.animationLength = clamp(from.animationLength, ANIM_LENGTH_MIN, ANIM_LENGTH_MAX);
        target.shortAnimationLength =
                clamp(from.shortAnimationLength, SHORT_LENGTH_MIN, SHORT_LENGTH_MAX);

        // 19（0.9.6 / M4）：输入平滑。老 XML 没有这一项 → 平台填字段初值 0f = 行为不变。
        target.typingSmoothness = clamp(from.typingSmoothness, 0f, 1f);
        // 【第二轮审查 F1，blocker】老用户的 animPresetBase 可能是 0..6（旧版有 7 个预设）。
        // 0.9.10 精简到 3 个后，如果只把上界改成 2，老下标 3..6 会被 clamp 成 2 = 「纯平滑输入」
        // —— 那是**关辉光**的预设。用户会莫名其妙失去辉光，下拉还自称他选的就是这个。
        //
        // 正确做法是**显式重映射**：只有 0/1（基础/缎带）能一对一保留，
        // 其余（3..6 的「社区·快/平滑/柔和/高对比」）一律回落到默认预设 0（基础，辉光开）。
        // 宁可让用户回到默认，也不能把他静默丢进一个关了辉光的预设。
        int oldBase = from.animPresetBase;
        target.animPresetBase = (oldBase >= 0 && oldBase <= 1)
                ? oldBase
                : CursorPresets.ANIM_PRESET_DEFAULT_INDEX;

        // 21/22（0.9.9）：自定义预设表 + 当前选中名。老 XML 没有这两项 → 平台给 null
        // → 必须落成 ""。【为什么不能留 null】面板/探针里的 String.equals 会 NPE；
        // 空串才是「没有自建预设」/「选中内置」的唯一表示。
        target.customAnimPresets =
                (from.customAnimPresets == null) ? "" : from.customAnimPresets;
        target.animCustomName =
                (from.animCustomName == null) ? "" : from.animCustomName;

        state = target;

        // 先逐字段落地，再迁移 —— 迁移只在 stateVersion < 1 时才会覆盖两个布尔。
        migrateIfNeeded();
    }

    /**
     * 首次安装 / XML 不存在时由平台调用。
     *
     * <p>此时 {@code state} 仍是构造初值（= {@link NeovideConfig} 的默认值），
     * 但老用户的偏好可能正躺在旧键里 —— 所以这里也要跑一次迁移。
     */
    @Override
    public void noStateLoaded() {
        migrateIfNeeded();
    }

    // ==================== 迁移 ====================

    /**
     * 一次性把 {@link PropertiesComponent} 的两个旧键搬进 {@link State}，然后删掉旧键。
     *
     * <p>三个分支（缺一即会出现「升级后设置被重置」或「旧键复活」）：
     * <table border="1">
     *   <tr><th>分支</th><th>条件</th><th>行为</th></tr>
     *   <tr><td>A-1</td><td>旧键存在</td><td>旧键的值优先，覆盖 state 里对应的两个布尔</td></tr>
     *   <tr><td>A-2</td><td>旧键不存在</td><td>state 保留 loadState / 构造给出的值（干净安装 = 默认 true）</td></tr>
     *   <tr><td>B</td><td>{@code stateVersion >= 1}</td><td>旧键一律<b>不读</b>，只删（防止降级后再升级让旧键复活）</td></tr>
     * </table>
     *
     * <p><b>为什么必须用 isValueSet</b>：老用户若点过菜单把「常驻刷新」关掉，
     * 旧键里存的是 {@code false}；而「从未存过」也读得出 {@code false}。
     * 仅凭 {@code getBoolean(KEY, false)} 无法区分这两种情况，
     * 前者必须搬 {@code false}，后者必须落到默认 {@code true}。
     *
     * <p><b>落盘成功才删旧键</b>：迁移失败时既不删旧键、也不置版本号，
     * 下次启动重试。宁可多迁移一次，不可丢值。
     */
    void migrateIfNeeded() {
        // ---- 分支 B：新 state 已存在且已迁移过 ----
        if (state.stateVersion >= STATE_VERSION_MIGRATED) {
            deleteOldKeysIfPresent();
            return;
        }

        boolean changed = false;
        PropertiesComponent pc = tryGetPropertiesComponent();
        if (pc != null) {
            try {
                // ---- 分支 A-1：旧键存在 → 旧键优先 ----
                // 注意默认值传 true：与 NeovideConfig.alwaysHighFps 的字段默认值一致，
                // 绝不能再传 false（那正是被修掉的那个 bug）。
                if (pc.isValueSet(OLD_KEY_ALWAYS_HIGH_FPS)) {
                    state.alwaysHighFps = pc.getBoolean(OLD_KEY_ALWAYS_HIGH_FPS, true);
                    changed = true;
                    LOG.info("neovide-cursor: 迁移 alwaysHighFps = " + state.alwaysHighFps
                            + "（来源：旧键 " + OLD_KEY_ALWAYS_HIGH_FPS + "）");
                }
                // ---- 分支 A-2：旧键不存在 → 保留 state 现值（干净安装即默认 true）----
                else {
                    LOG.info("neovide-cursor: 旧键 " + OLD_KEY_ALWAYS_HIGH_FPS
                            + " 不存在，alwaysHighFps 取默认 " + state.alwaysHighFps);
                }

                if (pc.isValueSet(OLD_KEY_OVERLAY_FADE_OUT)) {
                    state.overlayFadeOut = pc.getBoolean(OLD_KEY_OVERLAY_FADE_OUT, true);
                    changed = true;
                    LOG.info("neovide-cursor: 迁移 overlayFadeOut = " + state.overlayFadeOut
                            + "（来源：旧键 " + OLD_KEY_OVERLAY_FADE_OUT + "）");
                } else {
                    LOG.info("neovide-cursor: 旧键 " + OLD_KEY_OVERLAY_FADE_OUT
                            + " 不存在，overlayFadeOut 取默认 " + state.overlayFadeOut);
                }
            } catch (Throwable t) {
                // 读旧键失败：不阻塞启动，仍把版本号往前推（旧键按「不存在」处理）
                LOG.warn("neovide-cursor: 读取旧键失败，按「旧键不存在」处理", t);
            }
        } else {
            LOG.info("neovide-cursor: PropertiesComponent 不可用，跳过旧键迁移（按旧键不存在处理）");
        }

        state.stateVersion = STATE_VERSION_MIGRATED;
        PersistResult persisted = persistQuietly();
        switch (persisted) {
            case CONFIRMED:
                // 已确认落盘 → 唯一允许删旧键的分支
                deleteOldKeysIfPresent();
                break;
            case DEFERRED:
                // 排队中：版本号保持 1（内存值是对的，回退会导致永不收敛），
                // 但【不删旧键】—— 删键是不可逆动作，必须等真正确认写入。
                // 真正删键的动作在 persistQuietly 的延迟回调里、saveSettings 成功之后。
                LOG.info("neovide-cursor: 迁移落盘已排队（DEFERRED），stateVersion=1，旧键待确认后删除");
                break;
            case FAILED:
            default:
                // 真失败 → 回退版本号，保留旧键，下次启动重试。绝不半途而废。
                state.stateVersion = 0;
                LOG.warn("neovide-cursor: 迁移落盘失败（FAILED），stateVersion 回退为 0，下次启动重试；旧键保留");
                break;
        }
        LOG.info("neovide-cursor: 旧键迁移结束，changed=" + changed + "，persist=" + persisted);
    }

    /**
     * 删掉两个旧键（幂等）。
     *
     * <p>与迁移分开是有意的：分支 B（已经迁移过的老 state）也要调它 ——
     * 否则用户「降级到 0.9.3 → 在不含本服务的旧版里点菜单写回旧键 → 再升级」
     * 时旧键会复活，与 state 打架。
     */
    private void deleteOldKeysIfPresent() {
        PropertiesComponent pc = tryGetPropertiesComponent();
        if (pc == null) {
            return;
        }
        try {
            if (pc.isValueSet(OLD_KEY_ALWAYS_HIGH_FPS)) {
                pc.unsetValue(OLD_KEY_ALWAYS_HIGH_FPS);
                LOG.info("neovide-cursor: 已删除旧键 " + OLD_KEY_ALWAYS_HIGH_FPS);
            }
            if (pc.isValueSet(OLD_KEY_OVERLAY_FADE_OUT)) {
                pc.unsetValue(OLD_KEY_OVERLAY_FADE_OUT);
                LOG.info("neovide-cursor: 已删除旧键 " + OLD_KEY_OVERLAY_FADE_OUT);
            }
        } catch (Throwable t) {
            LOG.warn("neovide-cursor: 删除旧键失败（不影响本次运行）", t);
        }
    }

    private static PropertiesComponent tryGetPropertiesComponent() {
        try {
            if (ApplicationManager.getApplication() == null) {
                return null;
            }
            return ApplicationManager.getApplication().getService(PropertiesComponent.class);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 持久化 → 运行期 ====================

    /**
     * 把 {@link State} 的字段写进 {@link NeovideConfig}（运行期唯一快照）。
     *
     * <p><b>它是纯字段拷贝，没有任何副作用</b> —— 不调 setter、不碰配色方案、
     * 不触发重绘。原因：它会在「首次 editorCreated」这个非常早的时间点被调用，
     * 此时 {@code savedCaretColor} 等副作用状态还不该动（那要由
     * {@code applyConfig()} 的 S2~S6 按严格顺序完成）。
     *
     * <p>{@code trailFactors} 由标量 {@code stretchRatioK} 派生，且<b>必须新建数组</b> ——
     * 把 State 里的引用直接交出去等于让两个真相源共享同一份可变数组。
     */
    public void syncStateToConfig() {
        NeovideConfig c = com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager.getConfig();
        State s = state;

        // 【0.9.9 / B3】从 SettingsField 表派生（原来是 17 行手写赋值）。
        // 谁参与由表项的 syncsToConfig 标志决定；不参与的两类见 SettingsField 注释
        // （stretchRatioK / typingSmoothness 派生成 trailFactors）。
        for (SettingsField f : SettingsField.FIELDS) {
            f.stateToConfig(s, c);
        }

        // 派生量：新建数组，绝不复用 State 的对象引用
        // 输入平滑（M4）也在这里落地：它只改这四档系数，而 trailFactors 只在短跳分支被读
        // （TrailCorner.jump():220），因此对跨行分支零影响。
        c.trailFactors = deriveTrailFactors(s.stretchRatioK, s.typingSmoothness);
    }

    /**
     * 运行期 → 持久化：把 CONFIG 里能持久化的字段回写进 State（不含 stateVersion）。
     *
     * <p><b>0.9.9 / B3：从 {@link SettingsField} 表派生</b>（原来是 17 行手写赋值）。
     */
    public void syncConfigToState() {
        NeovideConfig c = com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager.getConfig();
        State s = state;

        for (SettingsField f : SettingsField.FIELDS) {
            f.configToState(c, s);
        }

        // stretchRatioK 不在这里回写：它是 trailFactors 的「派生源」而不是派生值。
        // 若从 trailFactors 反推 k，round2 会把 4.5 变回 4.545，面板滑块就会自己漂移。
        // 反推逻辑第一期【不做】（见 SETTINGS-CONTRACT.md v2 §2.5 的裁决），
        // 只有面板 apply() 会显式写这个字段。
        if (state.stretchRatioK < 1.0f || state.stretchRatioK > 8.0f
                || Float.isNaN(state.stretchRatioK)) {
            state.stretchRatioK = 4.5f;
        }
    }

    // ==================== trailFactors 派生（契约 §2.5 的精确公式）====================

    /**
     * 由「拖尾拉伸强度」标量 {@code k} 派生四角滞后系数。
     *
     * <p><b>为什么不是「四个系数同乘一个常数」</b>：那样会同时改变 rank0 的<b>绝对时长</b>
     * （{@code shortAnimationLength × 1.0 = 110ms} 是实测调出来的观感），
     * 而用户拖「拉伸强度」时想要的只是<b>比值</b>变化。下面的公式保持 {@code f0 = 1}
     * （后缘绝对时长恒 110ms），只压缩前三档滞后量 —— 这才是「等比缩放」在 lag 域的正确实现。
     *
     * <p>基准形状取 {@code L = {0.0, 0.28, 0.58, 0.78}}（即 {@code 1 - {1.0, 0.72, 0.42, 0.22}}，
     * 与 {@code TrailCorner.jump()} 现有的梯度一致），则
     * <pre>
     *   m  = (1 - 1/k) / 0.78
     *   f0 = 1.0
     *   f1 = round2(1 - 0.28*m)
     *   f2 = round2(1 - 0.58*m)
     *   f3 = round2(1 - 0.78*m)      // 恒等于 round2(1/k)
     * </pre>
     *
     * <p><b>默认刻度必须精确复现现有数组</b>：{@code k = 4.5} ⇒
     * {@code m = 0.997150997...} ⇒ {@code {1.00, 0.72, 0.42, 0.22}}。
     * 这里对 {@code k == 4.5f} 做了一次显式特例直接返回规范常量数组 ——
     * 这是契约允许的<b>唯一</b>特例（避免 1 ulp 级差异让 parity/审计判成 DIFF），
     * 不是"为了凑值改 round2 的语义"。
     *
     * <p>自检表（端点与中段）：
     * <pre>
     *   t=10 (k=1.0) → {1.00, 1.00, 1.00, 1.00}  实际比值 1.000
     *   t=20 (k=2.0) → {1.00, 0.82, 0.63, 0.50}  实际比值 2.000
     *   t=45 (k=4.5) → {1.00, 0.72, 0.42, 0.22}  实际比值 4.545  ← 默认
     *   t=80 (k=8.0) → {1.00, 0.69, 0.35, 0.13}  实际比值 7.692
     * </pre>
     * 刻度标注用<b>标称值</b>（4.5×）：{@code round2} 把 {@code f3} 从 0.2222 取整到 0.22，
     * 实际比值必然落在标称值的 ±1.5% 内，这是公式本身的性质，不是缺陷。
     */
    public static float[] deriveTrailFactors(float k) {
        float kk = clamp(k, 1.0f, 8.0f);
        if (kk == 4.5f) {
            // 唯一允许的特例：默认刻度必须与 NeovideConfig.trailFactors 逐位相等
            return new float[]{1.0f, 0.72f, 0.42f, 0.22f};
        }
        double m = (1.0 - 1.0 / kk) / 0.78;
        return new float[]{
                1.0f,
                round2(1.0 - 0.28 * m),
                round2(1.0 - 0.58 * m),
                round2(1.0 - 0.78 * m)
        };
    }


    /**
     * 输入平滑（0.9.6 / M4）：把四角梯度朝「四角同长」压平，**总时长不变**。
     *
     * <p>{@code f'[i] = round2(f[i] + (1 - f[i]) × smooth × 0.8)}，rank0 恒为 1 ⇒
     * 后缘（最长的那一角）时长完全不动，改的只是前三档滞后量 —— 这正是「只调短跳梯度」。
     *
     * <p><b>smooth ≤ 0 时必须原样返回</b>：parity 的逐字节比对、audit 的常量审计、
     * 以及预设匹配（{@code CursorPresetApplier} 逐项比较）都依赖「默认值下数组与旧版完全一致」。
     *
     * @param smooth 0–1（面板 0–100）；0 = 关闭
     */
    public static float[] deriveTrailFactors(float k, float smooth) {
        float[] base = deriveTrailFactors(k);
        float s = clamp(smooth, 0f, 1f) * 0.8f;
        if (s <= 0f) {
            return base;
        }
        float[] out = new float[base.length];
        for (int i = 0; i < base.length; i++) {
            out[i] = round2(base[i] + (1.0 - base[i]) * s);
        }
        return out;
    }

    private static float round2(double x) {
        return (float) (Math.round(x * 100) / 100.0);
    }

    // ==================== 落盘 ====================

    /**
     * 立刻把当前 {@code State} 写进 XML（平台负责真正落盘）。
     *
     * <p>不手写 XML —— 序列化格式由平台决定，手写必然与升级后的平台不一致。
     *
     * <p><b>为什么需要显式落盘</b>：平台通常在关闭工程 / IDE 退出时才刷盘。
     * 迁移需要「确认写成功后才能删旧键」，否则会出现「旧键删了、新 state 没写」的丢值窗口。
     * 这里用 {@code XmlSerializer} 做一次同一份序列化链路的往返（能证明格式正确），
     * 再请平台刷盘。
     *
     * @return 三态结果，见 {@link PersistResult}。
     *         <b>只有 {@link PersistResult#CONFIRMED} 才能删旧键</b> —— 这是
     *         「旧键只在新 state 确认落盘后才删」的机械保证。
     */
    public PersistResult persistQuietly() {
        try {
            // 自检：同一份 State 必须能被平台的 XmlSerializer 完整往返。
            //
            // 【为什么用 com.intellij.util.xmlb.XmlSerializer 而不是 configurationStore 那个】
            // 后者的 serialize() 带「跳过默认值」优化：当 State 恰好等于默认实例时它返回 null
            // （实测：干净安装 + 无旧键的迁移正是这种情况）。若把 null 当成失败，
            // 迁移就会永远"落盘失败"→ 版本号反复回退 → 旧键永远删不掉。
            // util.xmlb 版不带该优化，永远返回完整文档，才是合适的格式校验器。
            org.jdom.Element element = com.intellij.util.xmlb.XmlSerializer.serialize(state);
            if (element == null) {
                LOG.warn("neovide-cursor: XmlSerializer 返回 null，放弃落盘");
                return PersistResult.FAILED;
            }
            State probe = com.intellij.util.xmlb.XmlSerializer.deserialize(element, State.class);
            if (probe == null) {
                LOG.warn("neovide-cursor: XmlSerializer 往返失败，放弃落盘");
                return PersistResult.FAILED;
            }
        } catch (Throwable t) {
            LOG.warn("neovide-cursor: State 序列化自检失败，放弃落盘", t);
            return PersistResult.FAILED;
        }

        try {
            com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
            if (app == null) {
                // 没有 Application（单元探针 / 早期启动）：无法确认写入，如实报 FAILED
                return PersistResult.FAILED;
            }
            if (app.isDispatchThread()) {
                // 在 EDT 上：同步刷盘，能确认成功/失败
                app.saveSettings();
                return PersistResult.CONFIRMED;
            }
            // ---------- 不在 EDT 上：DEFERRED（排队中，未确认）----------
            // 【这里曾经有个自相矛盾的 bug】原来它排队一次 saveSettings，然后在回调里
            // **无条件** deleteOldKeysIfPresent()，同时对外返回 false。于是：
            //   · migrateIfNeeded 收到 false → 把 stateVersion 回退成 0 并记日志
            //     「旧键保留」（NeovideCursorSettings:214）；
            //   · 但几毫秒后回调跑起来，把旧键**删了**。
            // 日志说保留、行为是删除；而且版本号永远停在 0，永不收敛 —— 下次启动又迁移一遍。
            //
            // 现在删键只挂在「确认写入」之后，并且 DEFERRED 不会让调用方回退版本号：
            // 内存里的 state 已经是新值，版本号理应前进；只是"删旧键"这个不可逆动作
            // 要等真正落盘确认。
            app.invokeLater(() -> {
                try {
                    app.saveSettings();
                    LOG.info("neovide-cursor: 延迟刷盘完成（CONFIRMED），现在可以删旧键");
                    deleteOldKeysIfPresent();
                } catch (Throwable t) {
                    LOG.warn("neovide-cursor: 延迟刷盘失败，旧键保留，下次启动重试迁移", t);
                }
            });
            return PersistResult.DEFERRED;
        } catch (Throwable t) {
            LOG.warn("neovide-cursor: 落盘失败", t);
            return PersistResult.FAILED;
        }
    }

    /**
     * {@link #persistQuietly()} 的三态结果。
     *
     * <p><b>为什么不能再用 boolean</b>：「排队中」与「真失败」对是否删旧键的处理
     * <b>正好相反</b> ——
     * <ul>
     *   <li>排队中：写盘大概率会成功，<b>不该</b>删旧键（还没确认），
     *       但<b>也不该</b>回退 stateVersion（内存值是对的，回退会导致永不收敛）；</li>
     *   <li>真失败：<b>必须</b>回退 stateVersion 并保留旧键，下次启动重试。</li>
     * </ul>
     * 一个 boolean 装不下这两种语义，这正是 R-03 那个 bug 的来源。
     */
    public enum PersistResult {
        /** 已在本线程确认写入磁盘 —— 唯一允许删旧键的状态。 */
        CONFIRMED,
        /** 已排队到 EDT 待刷盘，尚未确认 —— 不删旧键，但版本号可前进。 */
        DEFERRED,
        /** 确认失败 —— 保留旧键，调用方应回退 stateVersion 以便下次重试。 */
        FAILED
    }

    // ==================== 小工具 ====================

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp(float v, float lo, float hi) {
        // NaN 会同时绕过两个比较，必须单独处理：否则 NaN 会进绘制路径
        if (Float.isNaN(v)) {
            return lo;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 颜色串校验：非 {@code #RRGGBB} 的一律退回 {@link NeovideConfig} 里的默认值。
     *
     * <p>为什么不能放过坏值：{@code NeovideColors.parse} 对非法串<b>静默返回黑色</b> ——
     * 用户的拖尾会毫无提示地变黑，比"用回默认色"难排查得多。
     */
    private static String sanitizeColor(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String v = value.trim();
        if (v.length() != 7 || v.charAt(0) != '#') {
            return fallback;
        }
        for (int i = 1; i < 7; i++) {
            if (Character.digit(v.charAt(i), 16) < 0) {
                return fallback;
            }
        }
        return "#" + v.substring(1).toUpperCase(java.util.Locale.ROOT);
    }

    /** 供面板 {@code reset()} 做深拷贝（面板持有预览副本，绝不能直接改 state）。 */
    public static State deepCopy(@NotNull State from) {
        State s = new State();
        s.copyFrom(from);
        return s;
    }

    // ==================== 状态类 ====================

    /**
     * 持久化形态：第一期 16 项 + 1 个迁移版本号。
     *
     * <p><b>0.9.5 起有 18 项可调</b>：第一期没有任何 UI 通路能改 {@code animationLength} /
     * {@code shortAnimationLength}，故未持久化；0.9.5 把这两个做成「动画时序」折叠组后，
     * 它们成为可调字段（契约 §7.1），必须持久化，否则重启就丢。
     * <b>除此之外不新增任何字段</b> —— 预设名<b>刻意不持久化</b>（契约 §5.2 的 P-1：
     * 预设是纯动作不是状态，记「上次选了哪个」就是第四个真相源）。
     *
     * <p><b>平台约束</b>：必须有公开无参构造器，字段必须 {@code public}
     * （{@code XmlSerializer} 默认按字段名序列化）。
     * <b>不得</b>出现 {@code float[] trailFactors} —— {@code float[]} 不在平台的
     * 序列化白名单里，而标量 {@code stretchRatioK} 彻底规避了它（{@link #deriveTrailFactors}）。
     *
     * <p><b>初值必须 = {@link NeovideConfig} 的现值（逐字一致）</b>：
     * 干净安装的用户走 {@link #noStateLoaded()}，此时 state 保持这些初值，
     * 于是"默认值"依然只有一处来源 —— 只是这里写的是副本。
     * 任何改动都必须与 {@code NeovideConfig} 同步，否则就是新的 bug 源。
     */
    public static final class State {

        /** 迁移闸门：0 = 待迁移，1 = 已迁移（见 {@link #migrateIfNeeded()}） */
        public int stateVersion = 0;

        /** 1. 总开关（与运行期 {@code CONFIG.enabled} 统一后唯一的那一个布尔） */
        public boolean enabled = true;

    /**
     * 光标诊断日志开关（默认关闭）。
     *
     * <p>开启后每 15 帧往 idea.log 打一条 {@code [caret] / [draw] / [block]} 记录，
     * 用于定位「多光标不跟手」「块选区行为异常」这类问题。
     *
     * <p><b>为什么做成设置项而不是 JVM 参数</b>：
     * {@code -Dneovide.cursor.diag=true} 需要用户改 idea64.exe.vmoptions 再重启，
     * 对普通用户门槛太高。放在设置面板里勾一下就行。
     *
     * <p>性能影响：每 15 帧一次字符串拼接 + 一行日志，可忽略。
     * 长期开着会撑大 idea.log，排查完建议关掉。
     */
    public boolean diagLogging = false;

    /**
     * 多光标时关闭辉光（全局开关，<b>不随预设变化</b>）。
     *
     * <p><b>为什么做成全局</b>：用户明确要求「全局生效的改预设不该动这个」。
     * 它属于性能/设备级设置，不是观感风格。
     *
     * <p><b>语义</b>：光标数 ≥ 2 时把辉光层数强制归零，只保留拖尾拉伸形状。
     * 单光标不受影响。
     */
    public boolean multiCursorNoGlow = false;

    /**
     * 光标宽度（像素，0–5）。<b>0 = 跟随 IDEA 原生宽度</b>。
     *
     * <p><b>用户需求（2026-10-24）</b>：「光标自由大小…宽度最大 5px
     * 应该是 idea 原光标宽度…记住是静止的时候也这么大」。
     *
     * <p><b>为什么不进预设</b>：它是设备级设置（和屏幕/字体相关），
     * 不是观感风格 —— 与输入平滑同理。
     */
    public int cursorWidthPx = 2;   // 0.9.59: 0 档取消（用户说看不出区别），改成 1-5

    /**
     * 光标高度占行高的比例（0.3–1.0）。<b>1.0 = 顶格（当前行为）</b>。
     *
     * <p><b>用户需求</b>：「最大长度不能超过我们目前的长度就是顶格」。
     */
    public float cursorHeightRatio = 1.0f;

    /**
     * RGB 纵向渐变（实验性，默认关）。
     *
     * <p>用户要求：「rgb没见开关」——原来只有启动参数，现在做成界面开关。
     */
    public boolean rgbGradient = false;

        /** 2. 拖尾渲染模式 */
        public NeovideConfig.TrailMode trailMode = NeovideConfig.TrailMode.SPRING;

        /** 3. 隐藏原生光标（覆盖层充当光标） */
        public boolean hideNativeCaret = true;

        /** 4. 常驻刷新（永远按 8ms 调度） */
        public boolean alwaysHighFps = true;

        /** 5. 辉光淡出（停止后柔和熄灭） */
        public boolean overlayFadeOut = true;

        /** 6. 淡出前保持时长（毫秒） */
        public int cursorDisappearDelay = 50;

        /** 7. 淡出时长（秒） */
        public float cursorFadeOutDuration = 0.075f;

        /** 8. 拖尾颜色（HEX）。仍存 String 而不转 int：见契约 D-2 裁决 */
        public String tailColor = "#FFC0CB";

        /** 9. 拖尾不透明度（0~1） */
        public float tailOpacity = 1.0f;

        /** 10. 是否启用辉光 */
        public boolean useShadow = true;

        /** 11. 辉光颜色（HEX） */
        public String shadowColor = "#FFC0CB";

        /** 12. 辉光峰值不透明度（0~1） */
        public float glowOpacity = 0.55f;

        /** 13. 辉光层数（1~12） */
        public int glowLayers = 8;

        /** 14. 辉光外扩幅度（0.05~0.60） */
        public float shadowBlurFactor = 0.2f;

        /** 15. 拖尾密度（0~1） */
        public float trailSize = 1f;

        /**
         * 16. 拖尾拉伸强度（标称 1.0× ~ 8.0×）。
         *
         * <p>它是<b>派生源</b>：{@code trailFactors} 由 {@link #deriveTrailFactors(float)}
         * 算出来，不单独持久化。
         */
        public float stretchRatioK = 4.5f;

        /**
         * 17. 跨行动画时长（秒）。0.9.5 新增可调（契约 §7.1）。
         *
         * <p>范围 {@code [0.05, 0.35]}，滑块步长 0.01。
         * <b>初值必须 = {@code NeovideConfig.animationLength}（0.1f）的字面量</b> ——
         * 干净安装的用户走 {@code noStateLoaded()}，此时 state 保持这些初值。
         * 契约 §10.2 的 N-3 允许 {@code 0.1f} / {@code 0.11f} 只出现在这两处声明。
         *
         * <p><b>上限 0.35s 的量化依据</b>（契约 §7.2，驱动真实 DampedSpring、dt=8ms）：
         * 0.35 → 跨行拖尾持续 115 帧 / 920ms，已接近 1 秒心理阈值；
         * 0.50 → 164 帧 / 1312ms，切屏回来会看到「上一个位置还在拖」。
         * <b>下限 0.05s 是硬边界</b>：{@code DampedSpring.update()} 首句
         * {@code animationLength <= dt} 时直接归零，而 {@code MAX_DT = 0.05s}
         * （{@code CaretAnimator:101}），因此 animationLength = 0.05 在一帧 dt 下即瞬移。
         */
        public float animationLength = 0.1f;

        /**
         * 18. 短动画时长（秒）。0.9.5 新增可调（契约 §7.1）。
         *
         * <p>范围 {@code [0.02, 0.25]}，滑块步长 0.01。初值 = {@code NeovideConfig.shortAnimationLength}。
         *
         * <p>它只在<b>短跳分支</b>（同一行、≤3 字符）生效：
         * 时长 = {@code shortAnimationLength × trailFactors[rank]}（{@code TrailCorner.jump() :221}）。
         */
        public float shortAnimationLength = 0.11f;

        /**
         * 19. 输入平滑（0.9.6 / M4 新增）。内部存 0–1，面板刻度 0–100。
         *
         * <p><b>它只压平「四角梯度」，不改任何总时长</b>：短跳每个角的有效时长 =
         * {@code shortAnimationLength × trailFactors[rank]}（{@code TrailCorner.jump():221}），
         * 而这里把 {@code trailFactors} 朝 1 靠：
         * <pre>
         *   f'[i] = f[i] + (1 - f[i]) × smooth × 0.8        // rank0 恒为 1 → 总时长不变
         * </pre>
         * 于是前缘（rank3，默认 0.22）被抬起来，单行打字不会「前缘一帧到位」。
         *
         * <p><b>默认 0 = 逐位不变</b>：parity / audit / 预设匹配都依赖「smooth=0 时数组与旧版完全一致」。
         */
        public float typingSmoothness = 0f;

        /**
         * 20. 当前动画预设的「基准」下标（0.9.6 / M5-fix 起持久化）。
         *
         * <p><b>只存「基准是谁」，不存「有没有改过」</b>：已修改是算出来的
         * （6 个预设字段与该下标的预设常量逐项比对）。存布尔会撒谎 —— 值改回去它还是 true。
         */
        public int animPresetBase = 0;

        /**
         * 21（0.9.9 / 自定义预设）：<b>用户自建的预设表</b>（持久化）。
         *
         * <p>格式：{@code 名称[U+0001]快照;名称[U+0001]快照;...}，其中「快照」是
         * {@link SettingsField#snapshot} 产出的 {@code key=value;key=value;...}。
         * 空串 = 用户还没有自建预设。
         *
         * <p><b>为什么是「全参数快照」而不是 0.9.8 的「6 字段覆盖」</b>：
         * 0.9.8 的模型是「覆盖某个内置预设的值」，于是内置预设会被用户改动<b>污染</b>，
         * 需要「（已修改）」后缀 + 「恢复为默认值」按钮来收场。
         * 0.9.9 改用 IntelliJ 配色方案 / GIMP 工具预设的模型：
         * <b>内置预设永久只读</b>，用户一旦改动任何参数就<b>自动派生</b>
         * 一个自定义预设，内置那份一位数都不动。
         * 于是「已修改」这个概念连同它的后缀一起消失。
         *
         * <p><b>名称允许出现任何字符</b>（含 {@code ;} {@code =} 与中文）：
         * 分隔符用 {@code U+0001}（ASCII 1，用户不可能从面板键入），
         * 名称本身不转义也不编码，所以「原版粉色 副本」这类名字原样保存。
         * 解析时缺分隔符的条目整条忽略（宁可丢一个坏条目，不能让整页打不开）。
         *
         * <p>解析与序列化是纯函数（{@link CustomPresets}），非法内容一律当「无此预设」，
         * <b>绝不抛异常</b>。
         */
        public String customAnimPresets = "";

        /**
         * 22（0.9.9）：当前选中的<b>自定义预设名</b>；空串 = 当前选中内置预设。
         */
        public String animCustomName = "";

        public State() {
        }

        /** 逐字段拷贝（可空容忍：{@code from == null} 时保持不变）。 */
        public void copyFrom(State from) {
            if (from == null) {
                return;
            }
            stateVersion = from.stateVersion;
            enabled = from.enabled;
            trailMode = from.trailMode;
            hideNativeCaret = from.hideNativeCaret;
            alwaysHighFps = from.alwaysHighFps;
            overlayFadeOut = from.overlayFadeOut;
            cursorDisappearDelay = from.cursorDisappearDelay;
            cursorFadeOutDuration = from.cursorFadeOutDuration;
            tailColor = from.tailColor;
            tailOpacity = from.tailOpacity;
            useShadow = from.useShadow;
            shadowColor = from.shadowColor;
            glowOpacity = from.glowOpacity;
            glowLayers = from.glowLayers;
            shadowBlurFactor = from.shadowBlurFactor;
            trailSize = from.trailSize;
            stretchRatioK = from.stretchRatioK;
            animationLength = from.animationLength;
            shortAnimationLength = from.shortAnimationLength;
            typingSmoothness = from.typingSmoothness;
            animPresetBase = from.animPresetBase;
            customAnimPresets = from.customAnimPresets;
            animCustomName = from.animCustomName;
        // 【0.9.51 修复：copyFrom 漏了 diagLogging】
        //
        // 「勾选诊断日志 → 应用 → 消失」的第二层原因。
        // copyFrom 是 State 深拷贝，pending = deepCopy(state) 走这里。
        // 漏了它 → 面板打开时 pending.diagLogging 恒为 false → 复选框永远不勾。
        // （第一层原因是它不在 SettingsField.FIELDS 里，apply 时不拷贝。）
        diagLogging = from.diagLogging;
        multiCursorNoGlow = from.multiCursorNoGlow;
        cursorWidthPx = from.cursorWidthPx;
        cursorHeightRatio = from.cursorHeightRatio;
        rgbGradient = from.rgbGradient;
        }

        /** 调试/探针用：一行显示全部 16 项 + 版本号。 */
        @Override
        public String toString() {
            return "State{stateVersion=" + stateVersion
                    + ", enabled=" + enabled
                    + ", trailMode=" + trailMode
                    + ", hideNativeCaret=" + hideNativeCaret
                    + ", alwaysHighFps=" + alwaysHighFps
                    + ", overlayFadeOut=" + overlayFadeOut
                    + ", cursorDisappearDelay=" + cursorDisappearDelay
                    + ", cursorFadeOutDuration=" + cursorFadeOutDuration
                    + ", tailColor=" + tailColor
                    + ", tailOpacity=" + tailOpacity
                    + ", useShadow=" + useShadow
                    + ", shadowColor=" + shadowColor
                    + ", glowOpacity=" + glowOpacity
                    + ", glowLayers=" + glowLayers
                    + ", shadowBlurFactor=" + shadowBlurFactor
                    + ", trailSize=" + trailSize
                    + ", stretchRatioK=" + stretchRatioK
                    + ", animationLength=" + animationLength
                    + ", shortAnimationLength=" + shortAnimationLength
                    + ", typingSmoothness=" + typingSmoothness
                    + ", animPresetBase=" + animPresetBase
                    + ", customAnimPresets=" + customAnimPresets
                    + ", animCustomName=" + animCustomName
                    + "}";
        }
    }
}
