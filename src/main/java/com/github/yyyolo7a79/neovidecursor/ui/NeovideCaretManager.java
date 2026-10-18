package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.ide.util.PropertiesComponent;
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

    private static volatile boolean enabled = true;

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

    // ==================== 持久化 ====================

    /**
     * 「常驻刷新」在 PropertiesComponent 里的键名。
     *
     * <p>此前整个插件没有任何持久化机制，所有开关都只活在内存里，
     * 重启 IDEA 后一律回到默认值 —— 对「常驻刷新」这种用户会长期开启的
     * 开关来说是不可接受的（用户会以为"又变回去了"）。
     *
     * <p>这里用 {@code PropertiesComponent} 而不是让 {@link NeovideConfig}
     * 实现 {@code PersistentStateComponent}：后者要连类一起改、还要注册服务，
     * 而这里只需要记住一个布尔值，用前者代价最小、也不影响既有配置字段。
     */
    private static final String PROP_ALWAYS_HIGH_FPS = "neovide.cursor.alwaysHighFps";

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
        return enabled;
    }

    // ==================== 编辑器生命周期 ====================

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        if (!enabled) {
            return;
        }

        // 【首次回调做两件事，顺序不能反】
        //
        // 1. 先恢复持久化设置。此时 IDEA 的 PropertiesComponent 已可用；
        //    放在构造器里则可能过早。
        //
        // 2. 再把 initialScanDone 置位，然后补建已打开的编辑器。
        //
        // 历史 bug：恢复调用原本写在 install() 里判断 `if (!initialScanDone)`，
        // 但置位发生在本方法更靠前的位置 —— 判据恒为 false，那段分支是死代码，
        // 持久化的开关重启后永远回不到默认值以外。
        if (!initialScanDone) {
            restorePersistedSettings();
            initialScanDone = true;
            for (Editor existing : EditorFactory.getInstance().getAllEditors()) {
                install(existing);
            }
        }

        install(event.getEditor());
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
     * 开启 / 关闭「常驻刷新」。
     *
     * <p>关闭时恢复原有的自动降频策略（空闲降到 120ms / 600ms）。
     * 切换后立即让所有调度线程按新间隔重新排程 —— 否则关闭后
     * 最长要等一个空闲周期（600ms）才按新策略跑。
     */
    public static void setAlwaysHighFps(boolean on) {
        CONFIG.alwaysHighFps = on;
        // 落盘：否则重启 IDEA 后又回到默认（关闭），用户会以为"又变回去了"
        try {
            PropertiesComponent.getInstance().setValue(PROP_ALWAYS_HIGH_FPS, on, false);
        } catch (Throwable t) {
            // 持久化失败不应影响本次运行
            LOG.warn("neovide-cursor: 保存 alwaysHighFps 失败", t);
        }
        for (CaretAnimator animator : ANIMATORS.values()) {
            animator.requestWakeUp();
        }
    }

    public static boolean isAlwaysHighFps() {
        return CONFIG.alwaysHighFps;
    }

    /**
     * 启动时从磁盘恢复「常驻刷新」的值。
     *
     * <p>在 {@link #install()} 里调用一次。读不到就用默认值 false。
     */
    public static void restorePersistedSettings() {
        try {
            CONFIG.alwaysHighFps = PropertiesComponent.getInstance()
                    .getBoolean(PROP_ALWAYS_HIGH_FPS, false);
        } catch (Throwable t) {
            LOG.warn("neovide-cursor: 读取 alwaysHighFps 失败，使用默认值", t);
        }
    }

    // ==================== 启用 / 禁用 ====================

    /** 切换拖尾效果开关 */
    public static void setEnabled(boolean on) {
        enabled = on;

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
