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
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 设置页里的动画预览窗 —— 还原真实代码场景的观感。
 *
 * <h2>与真实编辑器 1:1 的比例</h2>
 *
 * <p><b>为什么必须 1:1</b>：第一版把光标放大到 3x18、辉光半径却用默认 12px，
 * 结果辉光相对光标过大，看起来「一圈诡异的雾」。
 * 用户反馈「字体小不对就是不舒服」「辉光很奇怪」，根因就是比例失真。
 *
 * <p>现在全部按 IDEA 默认编辑器度量：
 * <ul>
 *   <li>字体 13pt 等宽</li>
 *   <li>行高 = 字号 1.5 倍</li>
 *   <li>光标宽 2px（IDEA 默认）</li>
 *   <li>光标高 = 行高 80%</li>
 *   <li><b>辉光半径必须调 panel.updateGlowRadius()</b>
 *       —— 第一版漏了这步，glowWidth 停在默认 12px 而光标仅 18px 高</li>
 * </ul>
 */
final class AnimationPreview extends JComponent {

    enum Mode { INLINE, CROSS }

    private final NeovideConfig config;
    private final Mode mode;
    private final CaretTrailPanel panel;
    private final TrailCorner[] corners;

    /**
     * 基础字号。
     *
     * <p><b>为什么从 13 提到 18</b>：用户实拍反馈「动画的字号太小了，应该大一点才直观」。
     * 13pt 在高 DPI 的 16 寸屏上只有真实编辑器的 2/3 大小，看不清拖尾细节。
     *
     * <p>18pt 在 16 寸 2.5K 屏上接近真实编辑器观感；同时用
     * {@link #effectiveFontSize(int)} 按可用宽度自适应 —— 窄窗口自动缩小，
     * 不会出现文字被截断。
     */
    private static final int BASE_FONT_SIZE = 18;

    /** 最窄可用字号（再小就失去观察价值） */
    private static final int MIN_FONT_SIZE = 12;

    /** 单行要演示的字符数（pawamamama = 10，中文那串 = 13，取 14 留余量） */
    private static final int DEMO_CHARS = 14;

    private static final double CURSOR_W = 2.5;
    private static final int TEXT_X = 14;
    private static final int PAD = 12;

    /** 当前生效字号（每帧按宽度算，避免缓存失效） */
    private int fontSize = BASE_FONT_SIZE;
    private Font codeFont = new Font(Font.MONOSPACED, Font.PLAIN, BASE_FONT_SIZE);
    private float lineH;
    private float charW;
    private double cursorH;

    /**
     * 按可用宽度算字号：要保证 {@link #DEMO_CHARS} 个字符放得下。
     *
     * <p>等宽字体一个字符宽 ≈ 字号 × 0.6。
     */
    private int effectiveFontSize(int availableWidth) {
        int usable = Math.max(40, availableWidth - TEXT_X * 2);
        // 目标：DEMO_CHARS 个字符 + 光标余量 放得下
        int byWidth = (int) (usable / (DEMO_CHARS * 0.6f));
        return Math.max(MIN_FONT_SIZE, Math.min(BASE_FONT_SIZE, byWidth));
    }

    /** 重算度量（字号变化时调用） */
    private void remeasure(int width) {
        int size = effectiveFontSize(width);
        if (size == fontSize && lineH > 0) {
            return;
        }
        fontSize = size;
        codeFont = new Font(Font.MONOSPACED, Font.PLAIN, fontSize);
        lineH = fontSize * 1.5f;
        // 等宽字体实测宽度：取 FontMetrics 更准，但 headless 下 FontMetrics 可用
        charW = Math.max(6f, fontSize * 0.6f);
        cursorH = lineH * 0.8;
        placed = false;          // 度量变了要重定位
        glowSized = false;
    }

    private static final int FRAME_MS = 8;
    private static final int TYPE_MS = 140;
    private static final int BACKSPACE_MS = 90;
    private static final int CROSS_MS = 1000;

    private Timer physicsTimer;
    private Timer actionTimer;
    private int actionInterval;

    private double centerX;
    private double centerY;
    private boolean placed;
    private boolean glowSized;
    private int typed;
    private int row;
    private boolean chinese;

    private static final String TEXT_EN = "pawamamama";
    private static final String TEXT_CN = "\u8fd9\u662f\u4e00\u4e2a\u7f16\u8f91\u5668\u5149\u6807\u63d2\u4ef6";

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

    void onConfigChanged() {
        panel.refreshStyles();
        glowSized = false;
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (physicsTimer == null) {
            physicsTimer = new Timer(FRAME_MS, e -> step());
        }
        if (actionTimer == null) {
            actionTimer = new Timer(actionInterval, e -> advance());
        }
        physicsTimer.start();
        actionTimer.start();
    }

    @Override
    public void removeNotify() {
        if (physicsTimer != null) physicsTimer.stop();
        if (actionTimer != null) actionTimer.stop();
        super.removeNotify();
    }

    private void placeIfNeeded() {
        if (placed || getWidth() <= 0) return;
        placed = true;
        centerX = PAD + CURSOR_W / 2;
        centerY = lineH;
        for (TrailCorner c : corners) {
            c.snapTo(CURSOR_W, cursorH, centerX, centerY);
        }
    }

    private void advance() {
        if (!placed || getWidth() <= 0) return;

        double targetX;
        double targetY;

        if (mode == Mode.INLINE) {
            String text = chinese ? TEXT_CN : TEXT_EN;
            int max = text.length();
            if (typed < max) {
                typed++;
                setInterval(TYPE_MS);
            } else if (typed == max) {
                typed = -1;
                setInterval(BACKSPACE_MS);
            } else if (typed > -max) {
                typed--;
                setInterval(BACKSPACE_MS);
            } else {
                typed = 0;
                chinese = !chinese;
                setInterval(TYPE_MS);
            }
            int shown = (typed < 0) ? Math.max(0, max + typed) : typed;
            targetX = TEXT_X + shown * charW + CURSOR_W / 2;
            targetY = lineH;
        } else {
            row = 1 - row;
            double span = Math.max(60, getWidth() - PAD * 2 - 40);
            targetX = PAD + 10 + span * (row == 0 ? 0.1 : 0.7);
            targetY = lineH + row * lineH;
        }

        centerX = targetX;
        centerY = targetY;

        int[] ranks = assignRanks(targetX, targetY);
        for (int i = 0; i < 4; i++) {
            corners[i].jump(CURSOR_W, cursorH, targetX, targetY, ranks[i]);
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

    @Override
    protected void paintComponent(Graphics g) {
        remeasure(getWidth());
        placeIfNeeded();

        g.setColor(new Color(0x1E1F22));
        g.fillRect(0, 0, getWidth(), getHeight());

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            g2.setFont(codeFont);

            if (mode == Mode.INLINE) {
                String text = chinese ? TEXT_CN : TEXT_EN;
                int max = text.length();
                int shown = (typed < 0) ? Math.max(0, max + typed) : Math.min(typed, max);
                String draw = text.substring(0, Math.max(0, shown));
                int baseline = (int) (lineH + fontSize * 0.35);
                g2.setColor(new Color(0xBCBEC4));
                g2.drawString(draw, TEXT_X, baseline);
                String rest = text.substring(Math.max(0, shown));
                g2.setColor(new Color(0x4E5157));
                g2.drawString(rest, TEXT_X + shown * charW, baseline);
            } else {
                g2.setColor(new Color(0x2B2D30));
                g2.drawLine(PAD, (int) lineH, getWidth() - PAD, (int) lineH);
                g2.drawLine(PAD, (int) (lineH * 2), getWidth() - PAD, (int) (lineH * 2));
                g2.setColor(new Color(0x4E5157));
                g2.drawString("int x = 0;", TEXT_X, (int) (lineH + fontSize * 0.35));
                g2.drawString("return x;", TEXT_X, (int) (lineH * 2 + fontSize * 0.35));
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

    @Override
    public Dimension getPreferredSize() {
        // 按基准字号算：字符宽 ≈ 18 × 0.6 = 10.8，14 字符 ≈ 151 + 左右留白
        float demoCharW = BASE_FONT_SIZE * 0.6f;
        return new Dimension((int) (TEXT_X * 2 + demoCharW * DEMO_CHARS),
                (int) (BASE_FONT_SIZE * 1.5f * 2 + PAD * 2));
    }
}
