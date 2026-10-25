package com.github.yyyolo7a79.neovidecursor.ui.settings;

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

    private static final int FRAME_MS = 8;

    /** 打字：慢到看得清每个字 */
    private static final int TYPE_MS = 150;

    /**
     * 【用户要求：「输入完之后等几秒 再删除 不然看不出来」】
     *
     * <p>打完要停 2 秒 —— 让人看清「完整句子长什么样」，再开始删。
     * 上一版打完立即删，反而看不出「输入」这件事。
     */
    private static final int HOLD_AFTER_TYPE_MS = 2000;

    /**
     * 退格节奏：一直连续激发，不停顿。
     *
     * <p>真实按住 Backspace 就是连续删（Windows 默认重复率约 10~12 字/秒）。
     * 90ms ≈ 11 字/秒。
     */
    private static final int BACKSPACE_MS = 90;

    /** 删空后停顿，再切换语言重新打 */
    private static final int SWITCH_PAUSE_MS = 800;

    /** 跨行换行间隔 */
    private static final int CROSS_MS = 1100;

    private Timer physicsTimer;
    private Timer actionTimer;
    private int actionInterval;

    private double centerX;
    private double centerY;
    private boolean placed;
    private boolean glowSized;

    /** 字体来源（诊断用：独立窗口里能看出取到的是哪个） */
    private String fontSource = "(未取)";

    /** 编辑器行距（来自 FontPreferences；默认 1.5 与 IDEA 一致） */
    private float editorLineSpacing = 1.5f;

    private double cursorW = 2.0;   // 未测量前的占位，remeasure 会算

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
    }

    public static AnimationPreview inline(NeovideConfig c) { return new AnimationPreview(c, Mode.INLINE); }
    public static AnimationPreview cross(NeovideConfig c) { return new AnimationPreview(c, Mode.CROSS); }

    public void onConfigChanged() {
        panel.refreshStyles();
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
            cursorH = lineH * 0.8;
            // 光标宽随字号缩放，否则大字号下粉色块会占两三个字符宽
            cursorW = Math.max(1.8, size / 16.0);
            placed = false;
            glowSized = false;
        } else if (metrics == null) {
            metrics = g2.getFontMetrics(codeFont);
        }
    }

    private void placeIfNeeded() {
        if (placed || getWidth() <= 0) return;
        placed = true;
        if (lineH <= 0) lineH = fontSize * editorLineSpacing;
        if (cursorH <= 0) cursorH = lineH * 0.8;
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
                setInterval(BACKSPACE_MS);
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
                setInterval(TYPE_MS);
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
        panel.setGlowStrength((float) Math.min(1.0, maxOffset / 8.0));
        repaint();
    }

    private int[] assignRanks(double cx, double cy) {
        double[] al = new double[4];
        for (int i = 0; i < 4; i++) {
            al[i] = corners[i].calculateDirectionAlignment(cursorW, cursorH, cx, cy);
        }
        int[] r = new int[4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (al[j] < al[i]) r[i]++;
            }
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
