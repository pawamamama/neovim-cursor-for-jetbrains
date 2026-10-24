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
final class AnimationPreview extends JComponent {

    enum Mode { INLINE, CROSS }

    private final NeovideConfig config;
    private final Mode mode;
    private final CaretTrailPanel panel;
    private final TrailCorner[] corners;

    // ==================== 度量 ====================

    /** 基准字号；实际按组件高度与宽度自适应 */
    private static final int BASE_FONT_SIZE = 22;
    private static final int MIN_FONT_SIZE = 14;

    /** 演示文本（决定宽度需求） */
    private static final int DEMO_CHARS = 14;

    private static final double CURSOR_W = 2.5;
    private static final int TEXT_X = 18;
    private static final int PAD = 16;

    // ==================== 节奏（毫秒）====================

    private static final int FRAME_MS = 8;

    /** 打字：慢到看得清每个字 */
    private static final int TYPE_MS = 200;

    /** 打完停顿（让人看清完整单词） */
    private static final int HOLD_MS = 900;

    /** 退格：比打字稍快，但每个字仍看得清（用户要 ffffffffff 这种） */
    private static final int BACKSPACE_MS = 130;

    /** 跨行换行间隔 */
    private static final int CROSS_MS = 1100;

    private Timer physicsTimer;
    private Timer actionTimer;
    private int actionInterval;

    private double centerX;
    private double centerY;
    private boolean placed;
    private boolean glowSized;

    /** 编辑器行距（来自 FontPreferences；默认 1.5 与 IDEA 一致） */
    private float editorLineSpacing = 1.5f;

    private int fontSize = BASE_FONT_SIZE;
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
    /** 跨行当前是第几行 */
    private int row;

    /** 演示串：先 pawamamama，退格后切英文短句 */
    private static final String TEXT_ID1 = "pawamamama";
    private static final String TEXT_ID2 = "typing test";

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
    }

    static AnimationPreview inline(NeovideConfig c) { return new AnimationPreview(c, Mode.INLINE); }
    static AnimationPreview cross(NeovideConfig c) { return new AnimationPreview(c, Mode.CROSS); }

    void onConfigChanged() {
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
    private Font editorFont() {
        try {
            com.intellij.openapi.editor.colors.FontPreferences pref =
                    com.intellij.openapi.editor.colors.EditorColorsManager
                            .getInstance().getGlobalScheme().getFontPreferences();
            if (pref != null) {
                String family = pref.getFontFamily();
                if (family != null && !family.isEmpty()) {
                    // 同时取编辑器行距 —— 预览要和真实编辑器一致
                    float sp = pref.getLineSpacing();
                    if (sp > 0.8f && sp < 3.0f) {
                        editorLineSpacing = sp;
                    }
                    return new Font(family, Font.PLAIN, BASE_FONT_SIZE);
                }
            }
        } catch (Throwable ignored) {
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, BASE_FONT_SIZE);
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
        int byWidth = (int) ((w - TEXT_X * 2) / (DEMO_CHARS * 0.62f));
        int byHeight = (int) ((h - PAD * 2) / (4 * 1.5f));
        int size = Math.max(MIN_FONT_SIZE, Math.min(Math.min(BASE_FONT_SIZE, byWidth), byHeight));

        if (size != fontSize || codeFont == null) {
            fontSize = size;
            codeFont = base.deriveFont((float) size);
            metrics = g2.getFontMetrics(codeFont);
            lineH = size * editorLineSpacing;
            cursorH = lineH * 0.8;
            placed = false;
            glowSized = false;
        } else if (metrics == null) {
            metrics = g2.getFontMetrics(codeFont);
        }
    }

    private void placeIfNeeded() {
        if (placed || getWidth() <= 0) return;
        placed = true;
        centerX = PAD + CURSOR_W / 2;
        centerY = lineH;
        for (TrailCorner c : corners) snap(c);
    }

    private void snap(TrailCorner c) {
        c.snapTo(CURSOR_W, cursorH, centerX, centerY);
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
                        phase = 1;
                        setInterval(HOLD_MS);
                    }
                }
                break;
            case 1:                                  // 停顿后进入退格
                phase = 2;
                setInterval(BACKSPACE_MS);
                break;
            case 2:                                  // 逐字退格
                if (typed > 0) typed--;
                if (typed == 0) {
                    phase = 3;
                    setInterval(TYPE_MS);
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
        centerX = TEXT_X + advanceW + CURSOR_W / 2;
        centerY = lineH;
        pushTarget();
    }

    /**
     * 跨行：真实换行的方向。
     *
     * <p><b>原来是镜像的</b>：用户反馈「多行缎带镜像了，没有人这样换行」。
     * 真实换行是「第一行行尾 → 第二行行首」，横向是【从右往左】回落。
     * 现在第一行放 {@code int x = 0;}，第二行放 {@code return x;}，
     * 光标从第一行末尾跳到第二行开头 —— 与真实编辑器一致。
     */
    private void advanceCross() {
        row = 1 - row;
        String[] lines = {"int x = 0;", "return x;"};
        int targetLine = row;
        String before = lines[targetLine];
        // 光标落在该行【行尾】（换行后通常继续输入）
        int tx = (int) (TEXT_X + metrics.stringWidth(before) + CURSOR_W / 2);
        double ty = lineH + targetLine * lineH;
        centerX = tx;
        centerY = ty;
        pushTarget();
    }

    private void pushTarget() {
        int[] ranks = assignRanks(centerX, centerY);
        for (int i = 0; i < 4; i++) {
            corners[i].jump(CURSOR_W, cursorH, centerX, centerY, ranks[i]);
        }
    }

    private void setInterval(int ms) {
        if (ms == actionInterval || actionTimer == null) return;
        actionInterval = ms;
        actionTimer.setDelay(ms);
    }

    private void step() {
        if (!placed) return;
        if (!glowSized) {
            panel.updateGlowRadius(CURSOR_W, cursorH);
            glowSized = true;
        }
        double dt = FRAME_MS / 1000.0;
        double maxOffset = 0;
        for (TrailCorner c : corners) {
            c.update(CURSOR_W, cursorH, centerX, centerY, dt, false);
            maxOffset = Math.max(maxOffset, c.getOffsetMagnitude());
        }
        panel.setGlowStrength((float) Math.min(1.0, maxOffset / 8.0));
        repaint();
    }

    private int[] assignRanks(double cx, double cy) {
        double[] al = new double[4];
        for (int i = 0; i < 4; i++) {
            al[i] = corners[i].calculateDirectionAlignment(CURSOR_W, cursorH, cx, cy);
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

    /** 跨行：画两行真实代码 */
    private void paintCross(Graphics2D g2) {
        String[] lines = {"int x = 0;", "return x;"};
        g2.setColor(new Color(0xBCBEC4));
        for (int i = 0; i < lines.length; i++) {
            g2.drawString(lines[i], TEXT_X, baselineFor(i));
        }
    }

    private int baselineFor(int lineIndex) {
        // 行基线：行高中心 + 字号 0.35 的经验偏移
        return (int) (lineH * (lineIndex + 1) + fontSize * 0.35);
    }

    @Override
    public Dimension getPreferredSize() {
        // 用户要「大一点」：4 行高、14 字符宽
        float cw = BASE_FONT_SIZE * 0.62f;
        return new Dimension((int) (TEXT_X * 2 + cw * DEMO_CHARS),
                (int) (BASE_FONT_SIZE * 1.5f * 4 + PAD * 2));
    }
}
