package com.github.yyyolo7a79.neovidecursor.ui.settings;

import com.github.yyyolo7a79.neovidecursor.core.GlowIntensity;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 设置页里的动画预览窗 —— 真实编辑器输入效果的模拟。
 *
 * <h2>设计目标（2026-10-24 用户反馈后重做）</h2>
 *
 * <p>用户原话：「我要的是真实输入」「没有描字（灰色）」「字体选正在用的字体」
 * 「动画演示窗口能不能大一点」「删除太快了，我要 ffffffffff 这样逐字删」
 * 「多行缎带镜像了，没有人这样换行」。
 *
 * <ol>
 *   <li><b>大</b>：至少 4 行高的可视区，不再是一条约 36px 的细缝</li>
 *   <li><b>无灰色描字</b>：只画真正输入过的字符，不画未输入占位</li>
 *   <li><b>用编辑器当前字体</b>：从 {@code EditorFontManager} 取，取不到才退回等宽</li>
 *   <li><b>打字 → 停顿 → 逐字退格</b>：模拟真人（先打 pawamamama，退格后切英文）</li>
 *   <li><b>退格要慢且可见</b>：每 130ms 删一个字，能数出来</li>
 *   <li><b>跨行方向正确</b>：第一行 {@code int x = 0;} → 第二行 {@code return x;}，
 *       光标从行尾跳到下一行开头（真实换行，不是左右镜像）</li>
 * </ol>
 */
public final class AnimationPreview extends JComponent {

    public enum Mode { INLINE, CROSS }

    private final NeovideConfig config;
    private final Mode mode;
    private final CaretTrailPanel panel;
    private final TrailCorner[] corners;

    // ==================== 度量 ====================

    // 【度量：每个值只有一个来源】
    //   字号     → remeasure() 按组件尺寸算，受 MIN/MAX 夹逼
    //   光标宽   → cursorW（remeasure 里算，= 字号/16）
    //   行高     → 字号 × 编辑器 lineSpacing
    //   光标高   → 行高 × 0.8
    //
    // 【纪律教训】上一轮我声称「已删除死代码 BASE_FONT_SIZE / CURSOR_W_BASE」，
    // 实际这两个常量仍在文件里 —— 说改没改，是这轮最该反省的事。

    /** 字号下限（再小就看不清拖尾） */
    private static final int MIN_FONT_SIZE = 14;

    /**
     * 字号上限。
     *
     * <p><b>为什么需要</b>：取消 22 上限后，窗口一大字号就涨到 50pt，
     * 一屏放不下几个字。预览窗的目的是「看清拖尾细节」，不是「把字放到最大」。
     *
     * <p>28pt 在 16 寸 150% DPI 屏上约等于实际编辑器 18-19pt 的观感。
     */
    private static final int MAX_FONT_SIZE = 28;

    /** 演示文本长度上限（算字号用） */
    private static final int DEMO_CHARS = 14;

    /** 文字左边距 */
    private static final int TEXT_X = 18;

    /** 上下留白（用户要求「别完全贴着输入」） */
    private static final int PAD = 16;

    // ==================== 节奏（毫秒）====================
    //
    // 【0.9.66：按权威数据重定，解决「打字顿挫 / 快速删除看不清」】
    //
    // 三组依据（都不是拍脑袋）：
    //
    // ① Nielsen 的感知阈值（1993，至今仍是 HCI 基准，
    //    https://www.nngroup.com/articles/response-times-3-important-limits/）：
    //      **0.1 秒 = 用户感觉"系统瞬时响应"的上限**。
    //    换算到本场景：两次界面更新间隔 < 100ms 时，人眼会把它们
    //    看成**连续运动**而不是"一步一步"。> 100ms 才看得出是独立的一步。
    //
    // ② 本机 Windows 键盘参数（SystemParametersInfo 实测，比任何文档都准）：
    //      SPI_GETKEYBOARDSPEED = 31（最快档）⇒ 约 30 字/秒 ⇒ 每字 33ms
    //      SPI_GETKEYBOARDDELAY = 1              ⇒ 首次重复延迟 500ms
    //    33ms/字 是"按住 Backspace 不放"的真实速度 —— **但那是给真人打字用的**，
    //    直接用会让预览糊成一片，看不出在演示什么。
    //
    // ③ 真人打字速度：60 WPM ≈ 5 字/秒 ≈ 200ms/字（常见速记水平）。
    //
    // 由此定值：
    //   · 删除必须 > 100ms（否则落进"连续"区，看不清是逐字删）
    //     ⇒ 取 130ms（阈值 + 30ms 余量，比原 90ms 慢 44%）
    //   · 打字在 150ms（偏快）与 200ms（真人）之间 ⇒ 取 170ms
    //   · 删空后的停顿应与"首次重复延迟 500ms"同量级 ⇒ 保留 800ms（略长，便于看清结束）

    private static final int FRAME_MS = 8;

    /**
     * 打字间隔：170ms/字。
     *
     * <p><b>为什么不是 150ms（原值）</b>：150 偏快，字符像"跳出来"而不是"打出来"。
     * 真人 60 WPM 是 200ms/字，170 在两者之间 —— 既真实又比 200 略显利落。
     *
     * <p>为什么不用本机最快的 33ms：那是按住键不放的重复速度，不是打字速度。
     * 预览要"看得出在打"，33ms 会糊成一条。
     */
    private static final int TYPE_MS = 170;

    /**
     * 【用户要求：「输入完之后等几秒 再删除 不然看不出来」】
     *
     * <p>打完要停 2 秒 —— 让人看清「完整句子长什么样」，再开始删。
     * 上一版打完立即删，反而看不出「输入」这件事。
     *
     * <p>2 秒也在 Nielsen 的"1 秒内不打断思路、10 秒内保持注意力"区间中段，
     * 属于舒适停顿，不是等待。
     */
    private static final int HOLD_AFTER_TYPE_MS = 2000;

    /**
     * 退格间隔：130ms/字。
     *
     * <p><b>核心修正</b>：原值 90ms < Nielsen 的 100ms 感知阈值 ——
     * 人眼会把连续 90ms 的更新看成<b>一团在动的模糊块</b>，而不是
     * "一个字一个字地删掉"。这就是用户说的「快速删除的顿挫」。
     *
     * <p>顿挫不是"太快"，而是**落在感知边界的含糊区**：
     * 快到看不清每步、慢到又不像连续运动 —— 两边都不靠。
     *
     * <p>取 130ms = 阈值 100ms + 30ms 余量，确保每删一个字都是可分辨的一步。
     * 作为对照：本机按住 Backspace 是 33ms/字（糊），人工逐字删约 300ms（拖沓），
     * 130ms 在中间偏快一侧。
     */
    private static final int BACKSPACE_MS = 130;

    /**
     * 删空后停顿，再切换语言重新打。
     *
     * <p>800ms 略长于本机"首次重复延迟 500ms"，让"删完了"这件事有个清晰的收尾，
     * 而不是紧接着又开始打字。
     */
    private static final int SWITCH_PAUSE_MS = 800;

    /** 跨行换行间隔：一次缎带展开约 430ms（实测），1100ms 留足看清时间 */
    private static final int CROSS_MS = 1100;

    private Timer physicsTimer;
    private Timer actionTimer;
    private int actionInterval;

    private double centerX;
    private double centerY;
    private boolean placed;
    private boolean glowSized;

    // ==================== 辉光淡出状态（2026-10-29）====================
    //
    // 语义与 CaretAnimator.lastAnimatingNanos / lastGlowStrength 逐字相同。
    //
    // 【为什么补这两个字段】在此之前预览只做一件事：
    //     panel.setGlowStrength(min(1, maxOffset / 8));
    // 既没有淡出（从不调 setGlowFade），强度曲线也和生产的 (offset-0.5)/2 不同。
    // 结果：预览里辉光永不熄灭、且亮度随偏移变化的节奏与真实编辑器不一致 ——
    // 用户拖滑块看到的观感，回车后并不成立。这正是陷阱 6（预览与生产必须同源）。

    /** 辉光最后一次可见的时刻（纳秒）；0 = 从未亮过 */
    private long lastGlowVisibleNanos = 0L;

    /** 辉光最后一次可见时的强度（0~1），与 {@link #lastGlowVisibleNanos} 配套 */
    private float lastGlowStrength = 0f;

    /** 字体来源（诊断用：独立窗口里能看出取到的是哪个） */
    private String fontSource = "(未取)";

    /** 编辑器行距（来自 FontPreferences；默认 1.5 与 IDEA 一致） */
    private float editorLineSpacing = 1.5f;

    private double cursorW = 2.0;   // 未测量前的占位，remeasure 会算

    // ==================== 用户光标尺寸覆盖（0.9.66）====================
    //
    // 【为什么要覆盖】预览原来自己算尺寸：cursorW = max(1.8, fontSize/16)、
    // cursorH = lineH × 0.8。也就是说**它和用户在「光标（尺寸）」组设的
    // 宽度 / 高度比例毫无关系** —— 用户在面板里拖「光标宽度」，预览窗毫无反应，
    // 那就不能叫"预览"。
    //
    // 【为什么不能直接照搬像素】真实编辑器里 cursorWidthPx 是 1~5 个物理像素，
    // 而预览窗字号会在 14~28pt 之间浮动（按窗口大小算）。若直接画 2px 宽，
    // 在 28pt 大字号下会细得看不见；用户设 5px 时又会显得比例失调。
    // 所以这里做**按同比例的放大**：以编辑器行高为基准换算 ——
    //   宽 = cursorWidthPx × (预览行高 / 编辑器行高)
    // 这样预览里的光标"相对于字的大小"与真实编辑器一致。

    /** 用户设的光标宽度（px，1~5）；-1 = 未设置，沿用 remeasure 的自动值 */
    private int userCursorWidthPx = -1;

    /** 用户设的高度占行高比例（0.3~1.0）；-1 = 未设置，沿用 remeasure 的自动值 */
    private float userCursorHeightRatio = -1f;

    /**
     * RGB 渐变开关（由面板推送）。
     *
     * <p><b>为什么不从 {@code config} 读</b>：{@code rgbGradient} 是
     * <b>持久化配置</b>，只存在于 {@code State} 里，{@code NeovideConfig}
     * 里根本没有这个字段（SettingsField 的 CONFIG 映射传的是 null）。
     * 所以预览必须由面板显式推送，与 {@code CaretAnimator} 的做法一致
     * （它在每帧 {@code panel.setRgbEnabled(rgbGradient)}）。
     *
     * <p><b>原来的 bug</b>：只推给了真实编辑器的 panel，预览的 panel 从没收到过，
     * 所以勾 RGB 预览毫无反应。
     */
    private boolean rgbGradient;

    /** 设置 RGB 渐变（面板在 refreshPreviews 里推送） */
    public void setRgbGradient(boolean on) {
        if (rgbGradient == on) {
            return;
        }
        rgbGradient = on;
        panel.setRgbEnabled(on);
        repaint();
    }

    /** 供探针断言 */
    public boolean rgbGradientForProbe() {
        return rgbGradient;
    }

    /**
     * 当前是否走彩虹（色相循环）。与 {@link #rgbGradient}（双拼）互斥 ——
     * 语义与 {@code CaretTrailPanel.setCursorRgbWash} 完全一致。
     */
    private boolean cursorRgbWash;

    /**
     * 设置是否走彩虹（面板在 refreshPreviews 里推送）。
     *
     * <p><b>0.9.89 新增</b>：原来预览只看 {@code rgbGradient}，
     * 而彩虹是另一个字段（{@code State} 里没有，由预设反推），
     * 所以选「彩虹（动态）」时预览完全不变 —— 用户报「彩虹没有到预览」。
     *
     * <p>推送方式与 {@link #setRgbGradient} 一致：由面板显式推，不从 config 读
     * （{@code NeovideConfig} 里没有这个字段）。
     */
    public void setCursorRgbWash(boolean on) {
        if (cursorRgbWash == on) {
            return;
        }
        cursorRgbWash = on;
        panel.setCursorRgbWash(on);
        repaint();
    }

    /** 供探针断言 */
    public boolean cursorRgbWashForProbe() {
        return cursorRgbWash;
    }

    /**
     * 设置用户的光标尺寸，并立即重排。
     *
     * @param widthPx     1~5；传 -1 表示沿用自动值
     * @param heightRatio 0.3~1.0；传 -1 表示沿用自动值
     */
    public void setUserCursorSize(int widthPx, float heightRatio) {
        if (userCursorWidthPx == widthPx
                && Math.abs(userCursorHeightRatio - heightRatio) < 1e-4f) {
            return;                     // 没变就不重排，避免每次滑块事件都抖动
        }
        userCursorWidthPx = widthPx;
        userCursorHeightRatio = heightRatio;
        placed = false;                 // 强制下次绘制重新算尺寸并摆位
        glowSized = false;
        repaint();
    }

    /** 用户设置的宽度（供探针断言） */
    public int userCursorWidthForProbe() {
        return userCursorWidthPx;
    }

    /** 用户设置的高度比例（供探针断言） */
    public float userCursorHeightRatioForProbe() {
        return userCursorHeightRatio;
    }

    private int fontSize = 0;   // 0 = 未测量，remeasure 会填
    private Font codeFont;
    private FontMetrics metrics;
    private float lineH;
    private double cursorH;

    /** 已打出的字符数 */
    private int typed;
    /** 阶段：0=打字 1=停顿 2=退格 3=切换语言 */
    private int phase;
    /** 中英轮换 */
    private boolean chinese;
    /** 跨行演示步骤：0=第1行行尾，1=第2行行首（两步往返） */
    private int crossStep = 0;

    /**
     * 演示串。
     *
     * <p>用户反馈「输入删除的太少了看不出来」——加到 16 字符，
     * 退格过程能持续 2 秒以上，看得清光标一个个往左退。
     */
    private static final String TEXT_ID1 = "pawamamama_trail";
    private static final String TEXT_ID2 = "neovide_cursor";
    /** 跨行演示：第一行、第二行（真实换行：第二行在 i 的正下方） */
    private static final String CROSS_LINE0 = "int x = 0;";
    private static final String CROSS_LINE1 = "return x;";

    AnimationPreview(@NotNull NeovideConfig config, @NotNull Mode mode) {
        this.config = config;
        this.mode = mode;
        this.panel = new CaretTrailPanel(config);
        this.corners = new TrailCorner[4];
        double[][] rel = {{-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5}};
        for (int i = 0; i < 4; i++) {
            corners[i] = new TrailCorner(rel[i][0], rel[i][1], config);
        }
        setOpaque(true);
        actionInterval = (mode == Mode.INLINE) ? TYPE_MS : CROSS_MS;
        // 初值：单行模式按 1（短跳），跨行模式按跨行值 —— 与各自的第一帧一致。
        pendingCharsMoved = (mode == Mode.INLINE) ? 1 : CROSS_CHARS_MOVED;
        // RGB 初值从 panel 读（config 里没有这个字段，panel 持有启动参数兜底值）
        rgbGradient = panel.isRgbEnabled();
    }

    public static AnimationPreview inline(NeovideConfig c) { return new AnimationPreview(c, Mode.INLINE); }
    public static AnimationPreview cross(NeovideConfig c) { return new AnimationPreview(c, Mode.CROSS); }

    public void onConfigChanged() {
        panel.refreshStyles();
        // 【0.9.67 修复：尺寸必须在这里重算】
        //
        // Bug 现象（用户实测）：「实时预览没弄好，必须关闭设置切换回来才可以看见变化」。
        //
        // 根因：applyCursorSize() 原来只在 remeasure() 的
        //     if (size != fontSize || codeFont == null) { ... }
        // 分支里被调用。字号没变就进不去 —— 于是 setUserCursorSize() 改了字段、
        // 也 repaint() 了，但**尺寸从未按新值重算**，画面当然不变。
        // 只有关掉设置页再打开（重建组件、字号重新测量）才生效。
        //
        // 修法：在这里直接调。lineH 若还没测过（<=0）就跳过，首帧 remeasure 会补。
        if (lineH > 0) {
            applyCursorSize();
            placed = false;         // 尺寸变了要重新摆位，否则角点还在旧位置
        }
        glowSized = false;
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (physicsTimer == null) physicsTimer = new Timer(FRAME_MS, e -> step());
        if (actionTimer == null) actionTimer = new Timer(actionInterval, e -> advance());
        physicsTimer.start();
        actionTimer.start();
    }

    @Override
    public void removeNotify() {
        if (physicsTimer != null) physicsTimer.stop();
        if (actionTimer != null) actionTimer.stop();
        super.removeNotify();
    }

    // ==================== 字体与度量 ====================

    /**
     * 取编辑器正在用的字体。
     *
     * <p><b>为什么</b>：用户要求「字体选正在用的字体」——
     * 硬编码 monospace 在他机器上可能不是实际编辑器字体，预览就不真实。
     */
    /**
     * 取编辑器正在用的字体。
     *
     * <p><b>用户反馈</b>：「展示字体不对不知道是窗口问题还是你没设置」。
     *
     * <p>原因：独立窗口（PreviewShowcase）里没有 IDEA 的 Application，
     * {@code EditorColorsManager.getInstance()} 会抛异常 → 走兜底 monospace。
     * 在 IDEA 里是能取到的。
     *
     * <p>现在加两级兜底：IDEA 取不到时，去注册表里找系统等宽字体，
     * 避免盲退到 Java 默认 monospaced（那个在大字号下很难看）。
     */
    /**
     * 字体缓存。
     *
     * <p><b>【自检发现的性能 bug】</b>：remeasure 每帧都调 editorFont()，
     * 而兜底分支会执行 {@code GraphicsEnvironment.getAvailableFontFamilyNames()}
     * —— 那要枚举系统全部字体，实测每帧几十毫秒，直接把 EDT 拖死。
     *
     * <p>字体只可能在「IDEA 换了主题字体」时变，不需要每帧重算。
     * 缓存起来，只在 fontSize 变化时 deriveFont 一次。
     */
    private Font cachedBaseFont;
    private String cachedFontSource;

    private Font editorFont() {
        if (cachedBaseFont != null) {
            return cachedBaseFont;
        }
        cachedBaseFont = loadEditorFont();
        fontSource = cachedFontSource;
        return cachedBaseFont;
    }

    /** 参数变化时清缓存（换主题/换字体后要重新取） */
    public void invalidateFontCache() {
        cachedBaseFont = null;
        cachedFontSource = null;
    }

    private Font loadEditorFont() {
        // 1) IDEA 编辑器字体（最准）
        try {
            com.intellij.openapi.editor.colors.FontPreferences pref =
                    com.intellij.openapi.editor.colors.EditorColorsManager
                            .getInstance().getGlobalScheme().getFontPreferences();
            if (pref != null) {
                String family = pref.getFontFamily();
                if (family != null && !family.isEmpty()) {
                    float sp = pref.getLineSpacing();
                    if (sp > 0.8f && sp < 3.0f) {
                        editorLineSpacing = sp;
                    }
                    cachedFontSource = "IDEA:" + family;
                    return new Font(family, Font.PLAIN, Math.max(MIN_FONT_SIZE, fontSize));
                }
            }
        } catch (Throwable t) {
            cachedFontSource = "IDEA 取不到(" + t.getClass().getSimpleName() + ")";
        }

        // 2) 系统等宽字体按优先级挑（JetBrains Mono > Cascadia > Consolas > ...）
        String[] candidates = {
                "JetBrains Mono", "Cascadia Code", "Cascadia Mono",
                "Consolas", "Fira Code", "Source Code Pro", "Menlo", "Monaco"
        };
        java.util.Set<String> available = new java.util.HashSet<>(
                java.util.Arrays.asList(java.awt.GraphicsEnvironment
                        .getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String c : candidates) {
            if (available.contains(c)) {
                cachedFontSource = "系统:" + c;
                return new Font(c, Font.PLAIN, Math.max(MIN_FONT_SIZE, fontSize));
            }
        }

        // 3) 最后兜底
        cachedFontSource = "兜底:Monospaced";
        return new Font(Font.MONOSPACED, Font.PLAIN, Math.max(MIN_FONT_SIZE, fontSize));
    }

    /**
     * 按组件尺寸算字号：同时受高度与宽度约束。
     *
     * <p>用户要「窗口大一点」，所以这里主动吃满可用空间 ——
     * 目标是 4 行高、14 字符宽。
     */
    private void remeasure(Graphics2D g2) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        Font base = editorFont();
        // 【0.9.48】按模式给不同行数 —— 用户要「只需要一行」
        //   单行窗：1 行内容（+ 上下留白）
        //   跨行窗：2 行内容（要演示换行）
        // 之前两者都留 3 行，导致 96px 高只能算出 14pt，字太小。
        int lines = (mode == Mode.INLINE) ? 1 : 2;
        int byWidth = (int) ((w - TEXT_X * 2) / (DEMO_CHARS * 0.62f));
        int byHeight = (int) ((h - PAD * 2) / (lines * 1.5f));
        int size = Math.max(MIN_FONT_SIZE,
                Math.min(Math.min(byWidth, byHeight), MAX_FONT_SIZE));

        if (size != fontSize || codeFont == null) {
            fontSize = size;
            codeFont = base.deriveFont((float) size);
            metrics = g2.getFontMetrics(codeFont);
            lineH = size * editorLineSpacing;
            // 光标宽高：用户设了就用用户值（按行高比例放大），否则用自适应
            applyCursorSize();
            placed = false;
            glowSized = false;
        } else if (metrics == null) {
            metrics = g2.getFontMetrics(codeFont);
        }
    }

    /**
     * 按「用户设置优先、未设置则自适应」决定光标宽高。
     *
     * <p><b>宽度换算</b>：用户设的是 1~5 <b>物理像素</b>，而预览字号在 14~28pt
     * 之间浮动。直接照搬会在大字号下细得看不见，所以按行高做等比放大：
     * <pre>
     *   预览宽 = 用户宽 × (预览行高 / 编辑器典型行高 32px)
     * </pre>
     * 编辑器行高取 32px 是 IntelliJ 默认主题下的常见值 —— 这里只需要
     * 「相对比例正确」，不需要绝对像素精确（预览终究不是编辑器）。
     *
     * <p><b>高度</b>：用户设的是占行高比例（0.3~1.0），直接乘即可，
     * 这正是用户能直接对比的语义（100% = 顶格）。
     */
    private void applyCursorSize() {
        if (userCursorHeightRatio > 0) {
            cursorH = lineH * Math.max(0.3, Math.min(1.0, userCursorHeightRatio));
        } else {
            cursorH = lineH * 0.8;
        }
        if (userCursorWidthPx > 0) {
            // 与编辑器 32px 行高对齐后等比放大
            double scale = lineH / 32.0;
            cursorW = Math.max(1.5, userCursorWidthPx * scale);
        } else {
            // 自适应：随字号缩放，否则大字号下粉色块会占两三个字符宽
            cursorW = Math.max(1.8, fontSize / 16.0);
        }
    }

    private void placeIfNeeded() {
        if (placed || getWidth() <= 0) return;
        placed = true;
        if (lineH <= 0) lineH = fontSize * editorLineSpacing;
        if (cursorH <= 0) {
            applyCursorSize();
        }
        centerX = TEXT_X + cursorW / 2;
        centerY = contentTop() + lineH / 2;
        for (TrailCorner c : corners) snap(c);
    }

    private void snap(TrailCorner c) {
        c.snapTo(cursorW, cursorH, centerX, centerY);
    }

    // ==================== 动作 ====================

    private String currentText() {
        return chinese ? TEXT_ID1 : TEXT_ID2;
    }

    private void advance() {
        if (!placed || getWidth() <= 0 || metrics == null) return;

        if (mode == Mode.INLINE) {
            advanceInline();
        } else {
            advanceCross();
        }
    }

    /** 单行：打字 → 停顿 → 逐字退格 → 切换语言 */
    private void advanceInline() {
        String text = currentText();
        int max = text.length();

        switch (phase) {
            case 0:                                  // 打字
                if (typed < max) {
                    typed++;
                    if (typed >= max) {
                        // 【打完停 2 秒】让用户看清完整句子，再开始删
                        phase = 1;
                        setInterval(HOLD_AFTER_TYPE_MS);
                    }
                }
                break;
            case 1:                                  // 停顿结束 → 开始连续删
                phase = 2;
                setInterval(jitter(BACKSPACE_MS));
                break;
            case 2:                                  // 逐字退格
                if (typed > 0) typed--;
                if (typed == 0) {
                    phase = 3;
                    setInterval(SWITCH_PAUSE_MS);
                }
                break;
            default:                                 // 切换语言后重新打字
                chinese = !chinese;
                phase = 0;
                typed = 0;
                setInterval(jitter(TYPE_MS));
                break;
        }

        // 光标位置 = 文本起点 + 已打字数 × 单字符宽
        String shown = text.substring(0, Math.max(0, Math.min(typed, max)));
        int advanceW = metrics.stringWidth(shown);
        centerX = TEXT_X + advanceW + cursorW / 2;
        centerY = contentTop() + lineH / 2;
        // 单行打字/退格：每次只挪一个字符 → 短跳分支 → 用 trailFactors 梯度。
        // 这就是真实编辑器里的观感（拖尾只比原版长一点），也是用户说
        // 「预览拉得很诡异」时对比的那一侧。
        pendingCharsMoved = 1;
        pushTarget();
    }

    /**
     * 跨行：<b>真实换行</b> —— 换行后回到上一行。
     *
     * <p><b>用户要求原文</b>：「换行展示了个寂寞 int x = 0; 换行后应该在 i 下面
     * 才能看出来缎带效果 而且是换行再推格到上一行 才算完美展示」。
     *
     * <p>所以演示序列是：
     * <pre>
     *   第1步  int x = 0;            ← 光标在第 1 行行尾
     *   第2步  int x = 0;            ← 回车换行，光标跳到【第 2 行第 1 列】
     *          |                    ←   （正对着 i 的正下方）
     *   第3步  int x = 0;            ← 再按退格，光标【退回第 1 行行尾】
     *          |
     * </pre>
     *
     * <p>这样缎带在「行尾 ↔ 下行行首」之间来回拉，斜向的缎带形状最明显。
     * 第 2 行不放文字（用户明说「第二行有没有字无所谓的」）。
     */
    private void advanceCross() {
        // 【自检发现的 bug】原实现用 %3，但 case 0 和 case 2 都是「行尾」，
        // 所以实际序列是 行尾 → 下行 → 行尾 → 行尾 → 下行 → 行尾…
        // 换行只在两次里发生一次，看上去像「跳一下又停住」。
        //
        // 现在改成【两步往返】：行尾 ⇄ 下行行首。
        // 这样每次 advance 都产生一次真实的跨行移动，缎带一直有东西可看。
        crossStep = 1 - crossStep;

        if (crossStep == 1) {
            // 换行：第 2 行第 1 列 —— 正对 i 的正下方
            centerX = TEXT_X + cursorW / 2;
            centerY = contentTop() + lineH * 1.5;
        } else {
            // 退格回第 1 行行尾
            centerX = TEXT_X + metrics.stringWidth(CROSS_LINE0) + cursorW / 2;
            centerY = contentTop() + lineH * 0.5;
        }
        // 跨行/换行：字符数必然超过 typingMaxChars → 走跨行三档
        // （前缘 = animationLength×(1-trailSize)，trailSize=1 时为 0 = 瞬时吸附）
        pendingCharsMoved = CROSS_CHARS_MOVED;
        pushTarget();
    }

    private void pushTarget() {
        // 【0.9.65 修复：预览必须告诉 TrailCorner「这次挪了几个字符」】
        //
        // 用户报：「动画的单行删除预览窗口是拉长的很诡异」，而实际编辑器里
        // 单行只是比原版长一点，跨行才明显。查证结论：**预览画的是跨行分支**。
        //
        // ============ 根因 ============
        //
        // TrailCorner.jump() 用 charsMoved 判定走哪条分支（:199）：
        //
        //   if (charsMoved >= 0)  isShortJump = sameLine && charsMoved <= typingMaxChars(3);
        //   else                  isShortJump = |jumpX| <= shortMoveThreshold && |jumpY| <= 0.001;
        //
        // charsMoved 默认 -1，而本类从不调 setCharsMoved() ——
        // 于是每一帧都走**兜底分支**，而兜底用「像素归一化」判，预览里
        // 一个字符宽 ≈ cursorW，jumpX 恒大于阈值 → **isShortJump 永远 false**
        // → 全部走跨行三档。
        //
        // 两条分支的实际后果（用户参数 k=8 / smooth=0.2 / trailSize=1.0）：
        //   单行短跳 → 四角 [110, 81, 50, 29] ms   梯度 3.85×，拖尾只比原版长一点
        //   跨行三档 → 四角 [280, 140, 0, 0] ms    前缘瞬时吸附、后缘拖满 → 拉得很夸张
        //
        // 所以预览一直把「跨行」的形状套在「单行」的标题下。
        //
        // ============ 修法 ============
        //
        // 由调用方明确告知本次移动的字符数：
        //   单行打字/退格 → 1（短跳，走 trailFactors 梯度）
        //   跨行/换行     → 给一个必然超过 typingMaxChars 的值（走跨行三档）
        //
        // 这与 CaretAnimator 的做法完全一致（那边用 |offset 差| 得出真实字符数），
        // 只是预览没有真实编辑器，只能按演示意图直接给值。
        for (int i = 0; i < 4; i++) {
            corners[i].setCharsMoved(pendingCharsMoved);
        }
        int[] ranks = assignRanks(centerX, centerY);
        for (int i = 0; i < 4; i++) {
            corners[i].jump(cursorW, cursorH, centerX, centerY, ranks[i]);
        }
    }

    /**
     * 本帧移动了几个字符 —— 决定 {@code TrailCorner.jump} 走哪条分支。
     *
     * <p>见 {@link #pushTarget()} 的注释：这个值缺失时预览会全程走跨行分支。
     *
     * <p><b>为什么是字段而不是 pushTarget 的参数</b>：{@code pushTarget()} 由
     * 4 处调用（单行打字/退格、跨行两步），每处都要传同一个值；用字段可以
     * 在每个 advance* 里只设一次，避免某个调用点漏传 —— 这正是本 bug 的成因。
     */
    private int pendingCharsMoved = 1;

    /** 跨行判定必然越过的字符数（> typingMaxChars=3 即可，取 99 表明"这是换行"） */
    private static final int CROSS_CHARS_MOVED = 99;

    /**
     * 打字 / 退格节奏的抖动源。
     *
     * <p><b>为什么需要（用户 2026-10-28）</b>：预览原本用固定间隔
     * （打字 {@code TYPE_MS}、退格 {@code BACKSPACE_MS}）驱动，观感像机器在敲 ——
     * 用户原话「实时预览不是真实打字删除的感觉」「节奏机械」。
     * 真人打字的间隔是不均匀的，这里给它 ±30% 的抖动。
     *
     * <p><b>为什么用固定种子</b>：观感上一样随机（相邻间隔都不同），但序列可复现 ——
     * 出问题时能重复看到同一个节奏，也方便以后写守门探针。
     */
    private final java.util.Random rhythmRandom = new java.util.Random(20261028L);

    /**
     * 给「打字 / 退格」的间隔加 ±30% 抖动。
     *
     * <p><b>只抖动这两处</b>：{@code HOLD_AFTER_TYPE_MS}（打完停顿）、
     * {@code SWITCH_PAUSE_MS}（换语种）、{@code CROSS_MS}（跨行演示）保持恒定 ——
     * 它们是演示的节拍点，随机化只会让人等得更烦。
     */
    private int jitter(int baseMs) {
        return (int) Math.round(baseMs * (0.7 + rhythmRandom.nextDouble() * 0.6));
    }

    private void setInterval(int ms) {
        if (ms == actionInterval || actionTimer == null) return;
        actionInterval = ms;
        actionTimer.setDelay(ms);
    }

    private void step() {
        if (!placed) return;
        if (!glowSized) {
            panel.updateGlowRadius(cursorW, cursorH);
            glowSized = true;
        }
        double dt = FRAME_MS / 1000.0;
        double maxOffset = 0;
        for (TrailCorner c : corners) {
            c.update(cursorW, cursorH, centerX, centerY, dt, false);
            maxOffset = Math.max(maxOffset, c.getOffsetMagnitude());
        }

        // ==================== 辉光：强度 + 淡出都走唯一实现 ====================
        //
        // 【2026-10-29 修两个分家点】
        //   ① 强度曲线：原来是 min(1, maxOffset / 8)，而生产是 (offset - 0.5) / 2。
        //      两条不同的曲线 —— 预览要偏移 8px 才满强度，生产 2.5px 就满了。
        //      用户拖滑块看到的是预览的曲线，回车后看到的却是另一条。
        //   ② 淡出：原来从不调 panel.setGlowFade()，于是 CaretTrailPanel.glowFade
        //      在预览里恒为初值 1 —— 预览的辉光永不熄灭，真实编辑器会熄灭。
        //
        // 现在两件事都调 GlowIntensity（与 CaretAnimator 同一份算法）。
        long now = System.nanoTime();
        float strength = GlowIntensity.strengthFor(maxOffset);
        if (strength > 0f) {
            // 和 CaretAnimator 一样：只在「辉光可见」时推进计时起点。
            // 否则停手瞬间强度归零，淡出计时的起点就变成「已经没光可淡」了。
            lastGlowVisibleNanos = now;
            lastGlowStrength = strength;
        }
        float fade = GlowIntensity.fade(config.overlayFadeOut, lastGlowVisibleNanos, now,
                config.cursorDisappearDelay, config.cursorFadeOutDuration);
        panel.setGlowStrength(strength > 0f ? strength : lastGlowStrength * fade);
        panel.setGlowFade(fade);
        repaint();
    }

    /**
     * 四角 rank（与生产代码 SpringTrail.assignRanks 同一套算法）。
     *
     * <p><b>【0.9.79 修复：预览曾用旧版塌缩算法】</b>
     *
     * <p>本方法原来是「比它小的有几个」计数：
     * <pre>
     *   for (j) if (al[j] &lt; al[i]) r[i]++;
     * </pre>
     * 和对齐度相等时双方都不计数 → 纯水平移动塌成 2 档（rank 0 2 2 0），
     * 同侧上下角同速 → 预览里永远是矩形。
     *
     * <p>0.9.77 修了生产代码的 {@code SpringTrail.assignRanks()}，
     * 但**预览窗是另一份独立实现**，于是：生产里斜缎带出来了，
     * 预览里还是矩形 —— 用户看到「动画预览没有变斜」。
     *
     * <p>这与四-G「假通过 B：探针复刻逻辑而非调用生产代码」是同一类错误：
     * 同一套算法存在两份实现，改一份另一份不会跟着变。
     *
     * <p>现在改为与生产完全一致的「稳定排序 + 索引 tiebreak」。
     */
    private int[] assignRanks(double cx, double cy) {
        double[] al = new double[4];
        for (int i = 0; i < 4; i++) {
            al[i] = corners[i].calculateDirectionAlignment(cursorW, cursorH, cx, cy);
        }
        // 稳定插入排序：只有严格大于才后移，并列保持原始顺序（索引 tiebreak）
        int[] order = {0, 1, 2, 3};
        for (int i = 1; i < 4; i++) {
            int key = order[i];
            int j = i - 1;
            while (j >= 0 && al[order[j]] > al[key]) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = key;
        }
        int[] r = new int[4];
        for (int k = 0; k < 4; k++) {
            r[order[k]] = k;
        }
        return r;
    }

    // ==================== 绘制 ====================

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            remeasure(g2);
            placeIfNeeded();

            // 背景：IDEA 深色编辑器底色
            g2.setColor(new Color(0x1E1F22));
            g2.fillRect(0, 0, getWidth(), getHeight());

            if (codeFont == null) return;
            g2.setFont(codeFont);

            if (mode == Mode.INLINE) {
                paintInline(g2);
            } else {
                paintCross(g2);
            }

            panel.beginQuads();
            panel.addQuad(
                    new double[]{corners[0].getCurrentX(), corners[1].getCurrentX(),
                            corners[2].getCurrentX(), corners[3].getCurrentX()},
                    new double[]{corners[0].getCurrentY(), corners[1].getCurrentY(),
                            corners[2].getCurrentY(), corners[3].getCurrentY()},
                    panel.getGlowStrengthForPreview());
            panel.paintForPreview(g2, getWidth(), getHeight());
        } finally {
            g2.dispose();
        }
    }

    /** 单行：只画真正输入过的字符（<b>不画灰色占位</b>） */
    private void paintInline(Graphics2D g2) {
        String text = currentText();
        String shown = text.substring(0, Math.max(0, Math.min(typed, text.length())));
        g2.setColor(new Color(0xE6E6E6));
        g2.drawString(shown, TEXT_X, baselineFor(0));
    }

    /** 跨行：只画第一行（用户说第二行有没有字无所谓） */
    private void paintCross(Graphics2D g2) {
        g2.setColor(new Color(0xBCBEC4));
        g2.drawString(CROSS_LINE0, TEXT_X, baselineFor(0));
    }

    private int baselineFor(int lineIndex) {
        // 【0.9.40】垂直居中：先算内容总高，再算起始偏移
        int contentLines = (mode == Mode.INLINE) ? 1 : 2;
        double contentH = contentLines * lineH;
        double top = (getHeight() - contentH) / 2.0;
        return (int) (top + lineH * (lineIndex + 0.5) + fontSize * 0.35);
    }

    /** 内容起始 y（与 baselineFor 同基准） */
    private double contentTop() {
        int contentLines = (mode == Mode.INLINE) ? 1 : 2;
        double contentH = contentLines * lineH;
        return (getHeight() - contentH) / 2.0;
    }

    @Override
    public Dimension getPreferredSize() {
        // 【0.9.48 定稿】按「内容行数」算，不再拍脑袋写死。
        //
        // 用户要求原文：
        //   「窗口高度不用这么大只需要一行 高100的话 只需要140大小吧
        //     应该按照用户显示器的吧」
        //
        // 实测（16 寸 150% DPI，屏幕 1707x1067）：
        //   单行窗 520x110 → 字号 28pt（上限）→ 行高 42 → 1 行 + 留白 28 = 70，余量充足
        //   跨行窗 300x150 → 字号 20pt → 行高 30 → 2 行 + 留白 28 = 88，余量充足
        //
        // 宽度保持（要放下 DEMO_CHARS 个字符）。
        return (mode == Mode.INLINE)
                ? new Dimension(520, 110)
                : new Dimension(300, 150);
    }

    /** 探针：当前生效字号（验证自适应是否真的按尺寸变） */
    public int probeFontSizeNow() {
        return fontSize;
    }

    // ==================== 探针 / 独立展示器钩子 ====================

    /**
     * 手动驱动一帧（供 {@code PreviewShowcase} 截图用）。
     *
     * <p><b>为什么需要</b>：预览窗靠 {@link Timer} 自动驱动，
     * 但截图工具要「跑 N 帧然后拍一张」，不能等真实时间。
     * 这个方法把物理步进暴露出来，不影响正常计时器行为。
     *
     * @param doAdvance true 表示这一步同时推进动作（打字/退格/换行）
     */
    public void probeTick(boolean doAdvance) {
        // 【必须】先在离屏 Graphics 上跑一次 remeasure ——
        // 否则 lineH / metrics / codeFont 全是初始值（0 / null），
        // advance() 里 metrics.stringWidth 会 NPE 被吞、光标停在原点。
        if (lineH <= 0 || metrics == null) {
            java.awt.image.BufferedImage tmp = new java.awt.image.BufferedImage(
                    Math.max(1, getWidth()), Math.max(1, getHeight()),
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = tmp.createGraphics();
            try {
                remeasure(g);
            } finally {
                g.dispose();
            }
        }
        placeIfNeeded();
        if (doAdvance) {
            advance();
        }
        step();
    }

    /** 探针：当前字号（验证自适应） */
    public int probeFontSize() {
        return fontSize;
    }

    /** 探针：当前已打出的字符数（负数表示退格阶段） */
    public int probeTyped() {
        return typed;
    }

    /** 探针：当前阶段（0打字 1停顿 2退格 3切换） */
    public int probePhase() {
        return phase;
    }

    /** 探针：字号 / 行高 / 已打字数 / 当前文本（排查用） */
    public String probeState() {
        return "fs=" + fontSize + " lineH=" + lineH + " typed=" + typed
                + " phase=" + phase + " text=" + currentText()
                + " metrics=" + (metrics != null) + " font=" + (codeFont != null)
                + " 字体源=" + fontSource
                + " w=" + getWidth() + " h=" + getHeight();
    }
}
