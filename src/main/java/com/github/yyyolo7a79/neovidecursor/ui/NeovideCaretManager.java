package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拖尾光标全局管理器。
 *
 * <p>两大职责：
 * <ol>
 *   <li>为每个新建编辑器挂上 {@link CaretAnimator}，编辑器关闭时回收</li>
 *   <li>隐藏原生光标（把 {@code CARET_COLOR} 改成全透明）——
 *       否则原生光标会和拖尾重叠在一起，效果很脏</li>
 * </ol>
 *
 * <p><b>关于隐藏原生光标的影响范围</b>：修改的是全局配色方案的 {@code CARET_COLOR}，
 * 因而影响本 IDE 进程内的所有编辑器。这是与 VS Code 版一致的做法（那边也是全局注入 CSS）。
 *
 * <p><b>关于持久化，有一个反直觉的事实</b>：{@code setColor} 本身确实只改内存、
 * 不碰文件，但<b>内存状态会被 IntelliJ 间接落盘</b> —— 用户只要在 Settings 里改过
 * 任意一项编辑器设置并点了 OK，IDE 就会把当前配色方案<b>整体</b>保存下来，
 * 透明光标随之写进 {@code colors/*.icls}。此后即使卸载插件，光标依然是隐形的：
 * 坏值在磁盘上，已经与插件无关了。
 *
 * <p>本类因此加了一层防护：{@link #sanitizeCaretColor} 拒绝把"全透明"当成原始值
 * 保存，避免恢复时把隐形原样还回去。详见 README 踩坑 #18。
 */
public class NeovideCaretManager implements EditorFactoryListener {

    /** 每个编辑器对应一个动画控制器 */
    private static final Map<Editor, CaretAnimator> ANIMATORS = new ConcurrentHashMap<>();

    /** 全局配置（后续可接入 Settings 持久化） */
    private static final NeovideConfig CONFIG = new NeovideConfig();

    /** 保存改动前的光标颜色，用于恢复；为 null 表示当前未隐藏 */
    private static volatile Color savedCaretColor = null;

    /**
     * 兜底光标色：Darcula / Dark 系方案的标准光标色。
     *
     * <p>仅在配色方案已被污染、且 {@code getDefaultForeground()} 也拿不到时启用，
     * 见 {@link #sanitizeCaretColor}。
     */
    private static final Color FALLBACK_CARET_COLOR = new Color(0xA9, 0xB7, 0xC6);

    /** 是否已为「插件加载时已存在的编辑器」补建过动画 */
    private static volatile boolean initialScanDone = false;

    /**
     * 性能诊断开关：置位后跳过全部覆盖层重绘，只保留物理计算与帧率统计。
     *
     * <p>用途是<b>分离瓶颈</b>：
     * <ul>
     *   <li>开启后 fps 大幅上升 → 瓶颈在重绘（值得改造成独立窗口方案）</li>
     *   <li>开启后 fps 依然很低 → 瓶颈在 EDT 调度/负载，改窗口方案也没用</li>
     * </ul>
     */
    private static volatile boolean paintDisabled = false;

    private static final Logger LOG = Logger.getInstance(NeovideCaretManager.class);

    /** 是否已完成「持久化 → 运行期」的首次同步（只做一次） */
    private static volatile boolean settingsSynced = false;

    // ==================== 持久化 ====================

    /**
     * 持久化真相已在本批集中到 {@link NeovideCursorSettings}（application service，
     * {@code @State} + {@code PersistentStateComponent}，文件
     * {@code <config>/options/NeovideCursorSettings.xml}）。
     *
     * <p><b>本类不再自己存任何东西</b>：历史上「常驻刷新」与「辉光淡出」各自在平台的
     * 属性存储里存了一份，与 {@link NeovideConfig} 的字段默认值并立，
     * 于是有三个真相源。那两个旧键已由 {@code NeovideCursorSettings.migrateIfNeeded()}
     * 一次性搬进新 state 并删除，本类的相关常量与读写代码同步删除。
     */

    public static boolean isPaintDisabled() {
        return paintDisabled;
    }

    public static void setPaintDisabled(boolean disabled) {
        paintDisabled = disabled;
        // 打入日志便于事后按时间点对比两种模式下的 fps 数据
        LOG.info("neovide-cursor: 性能诊断模式 " + (disabled ? "已开启（跳过重绘）" : "已关闭"));
        if (disabled) {
            // 关闭重绘前先清一次屏，否则残影会一直留在编辑器上
            for (CaretAnimator animator : ANIMATORS.values()) {
                animator.clearTrail();
            }
        }
    }

    public static NeovideConfig getConfig() {
        return CONFIG;
    }

    public static boolean isEnabled() {
        return CONFIG.enabled;
    }

    // ==================== 编辑器生命周期 ====================

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        // 【首次回调：先同步持久化设置，再判断 enabled —— 顺序不能反】
        //
        // 同步必须排在 enabled 早退【之前】。原来写的是
        //     if (!CONFIG.enabled) return;            ← 早退
        //     if (!initialScanDone) { syncStateToConfig(); ... }
        // 于是「用户关掉插件 → 重启 IDEA」这条路径永远不会同步：
        // CONFIG.enabled 停在编译期默认 true，而持久化的 state.enabled 是 false。
        // 后果有二：
        //   ① 关掉插件的用户重启后覆盖层又回来了（设置被无视）；
        //   ② initialScanDone 永远不置位，每次 editorCreated 都重新扫一遍。
        // 现在把同步提到最前面，用 initialScanDone 兜住「只做一次」。
        if (!initialScanDone) {
            syncStateToConfig();
            initialScanDone = true;

            // 同步可能把 enabled 由默认 true 改成持久化的 false —— 必须重新判一次，
            // 否则"用户关掉了插件"这件事要等到下一次 editorCreated 才生效。
            if (!CONFIG.enabled) {
                LOG.info("neovide-cursor: 持久化设置为「已关闭」，跳过全部安装");
                return;
            }

            for (Editor existing : EditorFactory.getInstance().getAllEditors()) {
                install(existing);
            }
        }

        if (!CONFIG.enabled) {
            return;
        }

        install(event.getEditor());
    }

    // ==================== 持久化 → 运行期 ====================

    /**
     * 把 {@link NeovideCursorSettings} 的 State 同步进 {@link #CONFIG}（run-time 唯一快照）。
     *
     * <p><b>幂等且无副作用</b>：纯字段拷贝，不调 setter、不改配色方案、不重绘。
     * 它可以在任何时刻安全调用；需要"改完立刻生效"的场景请用 {@link #applyConfig()}。
     */
    public static void syncStateToConfig() {
        try {
            NeovideCursorSettings svc = NeovideCursorSettings.getInstance();
            if (svc == null) {
                LOG.warn("neovide-cursor: NeovideCursorSettings 服务不可用，沿用 NeovideConfig 默认值");
                return;
            }
            svc.syncStateToConfig();
            settingsSynced = true;
            LOG.info("neovide-cursor: 已从设置服务同步 CONFIG；alwaysHighFps=" + CONFIG.alwaysHighFps
                    + "，overlayFadeOut=" + CONFIG.overlayFadeOut
                    + "，enabled=" + CONFIG.enabled
                    + "，hideNativeCaret=" + CONFIG.hideNativeCaret);
        } catch (Throwable t) {
            LOG.warn("neovide-cursor: 同步设置服务失败，沿用 NeovideConfig 默认值", t);
        }
    }

    /** 运行期 → 持久化：把 {@link #CONFIG} 里能持久化的 15 项回写进 State 并落盘。 */
    private static void syncConfigToStateAndPersist() {
        try {
            NeovideCursorSettings svc = NeovideCursorSettings.getInstance();
            if (svc == null) {
                return;
            }
            svc.syncConfigToState();
            svc.persistQuietly();
        } catch (Throwable t) {
            // 持久化失败不应影响本次运行
            LOG.warn("neovide-cursor: 保存设置失败", t);
        }
    }

    /** 是否已经做过一次「持久化 → 运行期」同步（探针与排障用） */
    public static boolean isSettingsSynced() {
        return settingsSynced;
    }

    /** 为单个编辑器挂载拖尾动画（含各项过滤条件） */
    private static void install(Editor editor) {
        try {
            if (editor == null) {
                return;
            }

            // 跳过没有归属项目的编辑器（如部分全局日志窗口）：
            // 这类编辑器不是用户会去交互的地方，挂覆盖层反而可能出问题
            if (editor.getProject() == null) {
                return;
            }

            // 跳过终端：它的光标由终端模拟器自行绘制、不受 CARET_COLOR 控制，
            // 全局隐藏对它无效 —— 补画反而会变成双光标。详见 isTerminalEditor。
            if (isTerminalEditor(editor)) {
                return;
            }

            // 【不要跳过控制台编辑器】
            //
            // 曾经的写法是 `if (getEditorKind() == CONSOLE) return;`，
            // 理由是"挂上去只会多出一个不跟随的假光标"。这个判断只看到了一半：
            // 隐藏原生光标改的是【全局】配色方案，跳过控制台不等于放过控制台 ——
            // 它们照样被隐藏了，却没有替代品，于是 Build / Run / Services / Problems
            // 等输出区光标全部消失。
            //
            // 正确做法是照常挂载，由 CaretAnimator 以「静态光标」模式处理：
            // 只补画一个静止的光标，不做拖尾。
            //
            // 注：终端（Terminal 工具窗口）的光标由终端模拟器自己绘制，不受
            // CARET_COLOR 影响，因此不在此列，也无需特殊处理。
            //
            // 【上游踩坑 #19】必须尊重 hideNativeCaret 配置。
            // 此前这里是无条件 applyNativeCaretHidden()，导致该配置是死字段：
            // 设成 false 也照样隐藏原生光标（行为与配置相反）。
            // 而隐藏原生光标会改写全局配色方案的 CARET_COLOR，是有落盘副作用的操作。
            if (CONFIG.hideNativeCaret) {
                applyNativeCaretHidden();
            } else {
                restoreNativeCaret();
            }

            ANIMATORS.computeIfAbsent(editor, e -> new CaretAnimator(e, CONFIG));
        } catch (Throwable t) {
            // 单个编辑器挂载失败不应影响其他编辑器
        }
    }

    /**
     * 判断编辑器是否属于终端（Terminal 工具窗口）。
     *
     * <p><b>为什么必须区分</b>：终端背后的 editor 与输出控制台<b>在类型上完全一样</b> ——
     * {@code TerminalEditorFactory.createOutputEditor()} 返回的就是标准
     * {@code EditorImpl}，两者的 {@code EditorKind} 也都是 {@code CONSOLE}，
     * 因此只能靠<b>组件树</b>识别（沿内容组件向上找终端特有的类）。
     *
     * <p><b>为什么不能接管终端</b>：终端的光标由终端模拟器自行绘制，
     * <b>不受 {@code CARET_COLOR} 控制</b> —— 全局隐藏对它无效。若照常补画光标，
     * 结果就是双光标，且我们读到的 caret 位置与终端实际光标并不同步（实测差一个字符）。
     *
     * <p>本方法刻意依赖<b>包名前缀</b>而非具体类名：终端在近几个版本换过实现
     * （{@code org.jetbrains.plugins.terminal} → {@code com.intellij.terminal.frontend}），
     * 但包名始终稳定。
     */
    private static boolean isTerminalEditor(Editor editor) {
        try {
            Component c = editor.getContentComponent();
            for (int depth = 0; c != null && depth < 16; depth++) {
                String name = c.getClass().getName();
                if (name.startsWith("com.intellij.terminal")
                        || name.startsWith("org.jetbrains.plugins.terminal")
                        || name.contains("JediTerm")) {
                    LOG.info("neovide-cursor: 判定为终端编辑器，跳过 — " + name);
                    return true;
                }
                c = c.getParent();
            }
        } catch (Throwable ignored) {
            // 组件树不可用时就按「非终端」处理：宁可多画一个光标，也不要漏掉输出区
        }
        return false;
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        CaretAnimator animator = ANIMATORS.remove(event.getEditor());
        if (animator != null) {
            animator.dispose();
        }
    }

    // ==================== 渲染模式切换 ====================

    /** 切换拖尾渲染模式（弹簧 / 残影），并重置所有编辑器的动画状态 */
    public static void setTrailMode(NeovideConfig.TrailMode mode) {
        CONFIG.trailMode = mode;
        syncConfigToStateAndPersist();
        for (CaretAnimator animator : ANIMATORS.values()) {
            animator.onModeChanged();
        }
    }

    public static NeovideConfig.TrailMode getTrailMode() {
        return CONFIG.trailMode;
    }

    // ==================== 常驻刷新 ====================

    /**
     * 开启 / 关闭「隐藏原生光标（常驻绘制）」。
     *
     * <p><b>上游踩坑 #19</b>：
     * <ul>
     *   <li>不隐藏（默认）—— 原生光标零延迟跟随，覆盖层只叠加拖尾。
     *       跟随不依赖我们的调度，所以空闲降频时也不会「卡」。</li>
     *   <li>隐藏 —— 覆盖层就是光标本体，跟随完全取决于 EDT 调度；
     *       空闲降频时会有明显延迟，通常需要配合「常驻刷新」。</li>
     * </ul>
     *
     * <p>切换后立即对所有已打开的编辑器生效。
     */
    public static void setHideNativeCaret(boolean on) {
        CONFIG.hideNativeCaret = on;
        syncConfigToStateAndPersist();
        if (on) {
            applyNativeCaretHidden();
        } else {
            restoreNativeCaret();
        }
        // 让每个编辑器立即重绘一帧，避免观感切换要等下一次输入
        for (CaretAnimator animator : ANIMATORS.values()) {
            animator.clearTrail();
        }
    }

    public static boolean isHideNativeCaret() {
        return CONFIG.hideNativeCaret;
    }

    /**
     * 开启 / 关闭「辉光淡出」（F2 / 批次2）。
     *
     * <p>开启：拖尾停止后辉光先保持 {@code cursorDisappearDelay}(50ms)，
     * 再用 {@code cursorFadeOutDuration}(75ms) 线性熄灭 —— 只淡辉光，光标本体不淡。
     * <p>关闭：行为与 0.9.1 一致（辉光瞬时归零）。
     */
    public static void setOverlayFadeOut(boolean on) {
        CONFIG.overlayFadeOut = on;
        // 落盘：否则重启 IDEA 后又回到默认值。
        // 走 NeovideCursorSettings（唯一持久化真相），不再写第二处。
        syncConfigToStateAndPersist();
        for (CaretAnimator animator : ANIMATORS.values()) {
            animator.requestWakeUp();
        }
    }

    public static boolean isOverlayFadeOut() {
        return CONFIG.overlayFadeOut;
    }

    /**
     * 开启 / 关闭「常驻刷新」。
     *
     * <p>关闭时恢复原有的自动降频策略（空闲降到 120ms / 600ms）。
     * 切换后立即让所有调度线程按新间隔重新排程 —— 否则关闭后
     * 最长要等一个空闲周期（600ms）才按新策略跑。
     */
    public static void setAlwaysHighFps(boolean on) {
        CONFIG.alwaysHighFps = on;
        // 落盘：否则重启 IDEA 后又回到默认，用户会以为"又变回去了"。
        // 走 NeovideCursorSettings（唯一持久化真相），不再写第二处。
        syncConfigToStateAndPersist();
        for (CaretAnimator animator : ANIMATORS.values()) {
            animator.requestWakeUp();
        }
    }

    public static boolean isAlwaysHighFps() {
        return CONFIG.alwaysHighFps;
    }

    // ==================== 全局生效编排 ====================

    /**
     * 把 {@link NeovideCursorSettings} 的 State（<b>已是最新值</b>）编排成
     * 「写字段 → 调 setter → 刷新缓存」的完整生效链。
     *
     * <p><b>它不是一个"再写一遍字段"的便利方法，而是唯一的全局生效入口</b>：
     * 设置面板 {@code apply()}、以及以后任何"批量改配置"都必须走它。
     * 已有的单点 setter（{@link #setTrailMode} 等）继续为 Tools 菜单服务，语义不变。
     *
     * <p><b>顺序即契约</b>（每一步都有反例，见下）：
     * <pre>
     *   S0  取快照：记下"改之前"的 5 个关键值（用于支路判断与幂等；必须在写 CONFIG 之前）
     *   S1  syncStateToConfig()        15 个字段一次性写到位 + 重建 trailFactors
     *   S2  setTrailMode / S3 setHideNativeCaret / S4 setAlwaysHighFps
     *   S5  setOverlayFadeOut / S6 setEnabled      —— 仅当该值真的变了
     *   S7  遍历 ANIMATORS 调 onModeChanged()      —— 刷新三类被缓存的值
     *   S8  遍历 ANIMATORS 调 requestWakeUp()      —— 无害加固
     * </pre>
     *
     * <p><b>为什么 S1 必须最先</b>：后面所有 setter 与刷新都读 {@link #CONFIG}。
     * {@code setEnabled(true)} 内部要读 {@code CONFIG.hideNativeCaret}，
     * {@code onModeChanged()} 要读 {@code shadowBlurFactor} / {@code afterimageLifetime} / 颜色。
     * 先调 setter 再写字段 → 新建的 animator 会用旧字段构造，{@code glowPadding}
     * 还要等下一次慢路径才正确。
     *
     * <p><b>为什么 S6（enabled）必须最后</b>：开 → 对所有编辑器 {@code install()}，
     * 新 {@code CaretAnimator} 在构造器里一次性算 {@code glowPadding} 并
     * {@code setLifetime}，所以必须在字段就位之后；关 → dispose 全部并清空集合，
     * 之后 S7/S8 空转无害。
     *
     * <p><b>为什么 S3 在 S6 之前</b>：{@code setHideNativeCaret} 有全局配色副作用并
     * {@code clearTrail()}。若在 {@code setEnabled(false)} 之后调，
     * 后者已经把光标恢复成原生可见，S3 刚隐藏的又会被覆盖。
     *
     * <p><b>为什么 S7 不可省</b>：S2~S6 都没碰三类"构造时算一次 / 缓存"的值 ——
     * {@code glowPadding}、{@code CaretTrailPanel.fillColor/glowColor}、
     * {@code AfterimageTrail.lifetime}。{@code onModeChanged()} 是现成的全量刷新入口：
     * 它调 {@code panel.refreshStyles()}（重新 parse 颜色）、
     * 调 {@code afterimageTrail.setLifetime(...)}、并置 {@code forceResync = true}。
     * 但 <b>{@code forceResync} 单独不足以刷新 {@code glowPadding}</b>：
     * 慢路径在算 padding 之前还有一条「光标中心与高度都没变就 return」的早退
     * （{@code CaretAnimator:1007}），静态光标编辑器更是整体跳过 {@code refreshTarget}。
     * 所以 S7 的循环里还要显式调 {@code refreshGlowPadding()}。
     * 不做 S7 → 改颜色 / 改 {@code shadowBlurFactor} 后画面不变。
     *
     * <p><b>幂等性</b>：连调两次，第二次的 S2–S6 全部跳过（值没变），S7/S8 仍执行。
     *
     * <p><b>EDT 约束</b>：各 setter 与 {@code onModeChanged()} 都会碰 Swing 组件
     * （配色方案、面板字段、重绘），必须在 EDT 上跑。本方法若不在 EDT，会
     * <b>先取 S0 快照 → 同步 CONFIG（让读者立刻看到新值）→ 带着快照 {@code invokeLater}</b>。
     * 带快照是必须的：EDT 侧若自己现拍 S0，拍到的是已经同步过的新值，
     * S2..S6 的 changed() 全为 false，setter 链一个都不会执行。
     */
    public static void applyConfig() {
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        if (app == null) {
            // 没有平台（离线探针）：直接同步执行，无处排队
            applyConfigOnEdt(snapshot());
            return;
        }
        if (!app.isDispatchThread()) {
            // ---------- S0 必须在任何写 CONFIG 之前取 ----------
            // 这里先拍快照、再同步字段、再把快照传给 EDT 侧。
            //
            // 【曾经的写法错在哪】原来这里是 invokeLater(applyConfigOnEdt) 之后立刻
            // syncStateToConfig()：CONFIG 当场变成新值，等 EDT 侧跑起来时它自己的 S0
            // 拍到的已经是【新】值 → S2..S6 的 changed() 全为 false → setter 链一个都不调。
            // 结果是非 EDT 调用 applyConfig() 只会写字段，颜色/辉光/淡出这些被缓存的副作用
            // 永不执行（而且原 javadoc 还写着"副作用链尚未执行完"，实际是"永不完"）。
            //
            // 【为什么不干脆不在非 EDT 分支同步】那会让"调用完立即读 CONFIG"的调用方
            // 读到旧值，与既有语义不符。传快照则两个诉求同时满足。
            Snapshot old = snapshot();
            syncStateToConfig();          // 让非 EDT 的读者立刻看到新配置
            app.invokeLater(() -> applyConfigOnEdt(old));
            return;
        }
        applyConfigOnEdt(snapshot());
    }

    /** S0 改前快照。**必须**在任何 {@code syncStateToConfig()} 之前采集。 */
    private static Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.trailMode = CONFIG.trailMode;
        s.hideNativeCaret = CONFIG.hideNativeCaret;
        s.alwaysHighFps = CONFIG.alwaysHighFps;
        s.overlayFadeOut = CONFIG.overlayFadeOut;
        s.enabled = CONFIG.enabled;
        return s;
    }

    /** S0 的载体：只装 S2..S6 需要比较的 5 个字段。 */
    private static final class Snapshot {
        NeovideConfig.TrailMode trailMode;
        boolean hideNativeCaret;
        boolean alwaysHighFps;
        boolean overlayFadeOut;
        boolean enabled;
    }

    /** {@link #applyConfig()} 的 EDT 侧实现（S1..S8），{@code old} 是 S0 快照。 */
    private static void applyConfigOnEdt(@NotNull Snapshot old) {
        final NeovideConfig.TrailMode oldTrailMode = old.trailMode;
        final boolean oldHideNativeCaret = old.hideNativeCaret;
        final boolean oldAlwaysHighFps = old.alwaysHighFps;
        final boolean oldOverlayFadeOut = old.overlayFadeOut;
        final boolean oldEnabled = old.enabled;

        // ---------- S1：字段一次性写到位 ----------
        NeovideCursorSettings svc = null;
        try {
            svc = NeovideCursorSettings.getInstance();
        } catch (Throwable t) {
            LOG.warn("neovide-cursor: applyConfig 取不到设置服务", t);
        }
        if (svc != null) {
            svc.syncStateToConfig();
        }

        // ---------- S2..S6：只在值真的变了时才调 setter ----------
        // 每个 setter 都带持久化与结构性副作用，无条件调用既慢又会清掉正在画的拖尾。
        try {
            if (CONFIG.trailMode != oldTrailMode) {
                setTrailMode(CONFIG.trailMode);
            }
            if (CONFIG.hideNativeCaret != oldHideNativeCaret) {
                setHideNativeCaret(CONFIG.hideNativeCaret);
            }
            if (CONFIG.alwaysHighFps != oldAlwaysHighFps) {
                setAlwaysHighFps(CONFIG.alwaysHighFps);
            }
            if (CONFIG.overlayFadeOut != oldOverlayFadeOut) {
                setOverlayFadeOut(CONFIG.overlayFadeOut);
            }
            if (CONFIG.enabled != oldEnabled) {
                setEnabled(CONFIG.enabled);
            }
        } catch (Throwable t) {
            LOG.warn("neovide-cursor: applyConfig 的 setter 链失败", t);
        }

        // ---------- S7：刷新三类被缓存的值 ----------
        // ANIMATORS 为空是合法状态（没有编辑器 / 刚被 setEnabled(false) 清空），
        // 遍历空集合不会出错；编辑器已 dispose 时各自的 try 兜底。
        //
        // onModeChanged() 覆盖：fillColor/glowColor（refreshStyles）、
        // AfterimageTrail.lifetime、forceResync（→ 下一帧慢路径重算 glowPadding）。
        // 但 forceResync 那条路在「光标静止」时走不到（refreshTarget 在 :1007 早退），
        // 静态光标编辑器更是整体跳过 refreshTarget —— 所以 glowPadding 必须再主动算一次。
        for (CaretAnimator animator : ANIMATORS.values()) {
            try {
                animator.onModeChanged();
            } catch (Throwable t) {
                // 单个 animator 刷新失败不应影响其他编辑器
                LOG.warn("neovide-cursor: 刷新 animator 缓存失败（编辑器可能已销毁）", t);
            }
            try {
                animator.refreshGlowPadding();
            } catch (Throwable t) {
                LOG.warn("neovide-cursor: 重算 glowPadding 失败（编辑器可能已销毁）", t);
            }
        }

        // ---------- S8：重排调度（无害加固）----------
        for (CaretAnimator animator : ANIMATORS.values()) {
            try {
                animator.requestWakeUp();
            } catch (Throwable ignored) {
                // 调度唤醒失败不影响正确性：下一次正常 tick 会自己接上
            }
        }

        LOG.info("neovide-cursor: applyConfig 完成（animators=" + ANIMATORS.size()
                + "，trailMode=" + CONFIG.trailMode
                + "，hideNativeCaret=" + CONFIG.hideNativeCaret
                + "，alwaysHighFps=" + CONFIG.alwaysHighFps
                + "，overlayFadeOut=" + CONFIG.overlayFadeOut
                + "，enabled=" + CONFIG.enabled + "）");
    }

    // ==================== 启用 / 禁用 ====================

    /** 切换拖尾效果开关 */
    public static void setEnabled(boolean on) {
        // 【§3.1.4】enabled 只有一个运行期真相：CONFIG.enabled。
        // 历史上这里还有一个独立的静态布尔字段（volatile）与 CONFIG.enabled 并立 ——
        // 那正是"三真相源"的来源，已删除。
        CONFIG.enabled = on;
        syncConfigToStateAndPersist();

        if (on) {
            // 【上游踩坑 #19】尊重 hideNativeCaret：默认保留原生光标
            if (CONFIG.hideNativeCaret) {
                applyNativeCaretHidden();
            } else {
                restoreNativeCaret();
            }
            // 为当前已打开的编辑器补建动画
            for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                install(editor);
            }
        } else {
            restoreNativeCaret();
            for (CaretAnimator animator : ANIMATORS.values()) {
                animator.dispose();
            }
            ANIMATORS.clear();
        }
    }

    // ==================== 原生光标显隐 ====================

    /**
     * 隐藏原生光标：把 {@code CARET_COLOR} 设为全透明。
     *
     * <p>IntelliJ 没有「隐藏光标」的公开 API（POC 阶段已实测确认
     * {@code CaretModel} 上不存在 {@code setCaretsVisible} 之类的方法），
     * 因此改配色方案是唯一可行路径。
     */
    public static void applyNativeCaretHidden() {
        try {
            if (savedCaretColor != null) {
                return; // 已经隐藏过，无需重复操作
            }

            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            savedCaretColor = sanitizeCaretColor(scheme, scheme.getColor(EditorColors.CARET_COLOR));

            scheme.setColor(EditorColors.CARET_COLOR, new Color(0, 0, 0, 0));
            repaintAllEditors();
        } catch (Throwable ignored) {
            // 配色方案不可用时静默跳过：此时原生光标会保留，但拖尾仍能正常工作
        }
    }

    /**
     * 校验待保存的光标颜色，不可用时返回兜底值。
     *
     * <p><b>为什么需要这一步</b>：本类保存的"原始值"会被 {@link #restoreNativeCaret()}
     * 原样恢复。如果这个原始值本身就是<b>全透明</b>，那么"恢复"等于什么都没做 ——
     * 用户关掉插件依然看不到光标。而全透明恰恰是<b>本插件上一个会话留下的</b>：
     * 隐藏期间用户若在 Settings 里改过设置并点 OK，透明色就被 IntelliJ 带进了
     * 配置文件（见类注释与 README 踩坑 #18）。
     *
     * <p>同样地，配色方案里查不到该项时 {@code getColor} 会返回 {@code null}，
     * 直接存 null 会让 {@link #restoreNativeCaret()} 提前返回、什么都不恢复，
     * 光标同样回不来。两种情况都走兜底。
     *
     * <p>兜底优先取编辑器默认前景色 —— 它本来就是"这个配色下文字该有的颜色"，
     * 与光标色通常一致；取不到再退回 {@link #FALLBACK_CARET_COLOR}。
     *
     * <p>取舍：万一用户是<b>故意</b>把原生光标设成隐形的，这里会把它恢复成可见。
     * 考虑到"故意设隐形"极罕见、而"配置被污染后无法自愈"伤害更大，选择前者。
     */
    private static Color sanitizeCaretColor(EditorColorsScheme scheme, Color original) {
        if (original != null && original.getAlpha() > 0) {
            return original; // 正常的可见颜色，直接采用
        }

        Color fallback = null;
        try {
            fallback = scheme.getDefaultForeground();
        } catch (Throwable ignored) {
            // 个别 scheme 实现可能不支持，交给下面的常量兜底
        }
        if (fallback == null || fallback.getAlpha() == 0) {
            fallback = FALLBACK_CARET_COLOR;
        }

        LOG.warn("neovide-cursor: 配色方案里的光标颜色为全透明（疑为历史遗留污染），"
                + "关闭插件时将恢复为 #" + String.format("%06X", fallback.getRGB() & 0xFFFFFF)
                + "。如仍不可见，请在 Settings | Editor | Color Scheme | General | Caret 中设置");
        return fallback;
    }

    /** 恢复原生光标原始颜色 */
    public static void restoreNativeCaret() {
        try {
            Color original = savedCaretColor;
            if (original == null) {
                return;
            }

            EditorColorsManager.getInstance().getGlobalScheme()
                    .setColor(EditorColors.CARET_COLOR, original);
            savedCaretColor = null;
            repaintAllEditors();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 改完配色后主动刷新所有编辑器，让新颜色立即生效 */
    private static void repaintAllEditors() {
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            try {
                editor.getContentComponent().repaint();
            } catch (Throwable ignored) {
                // 编辑器可能已销毁
            }
        }
    }
}
