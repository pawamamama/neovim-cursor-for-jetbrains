package com.github.yyyolo7a79.neovidecursor.ui.settings;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 设置页里的【动画预览窗】—— 用小画布演示真实物理。
 *
 * <h2>两种模式</h2>
 * <ul>
 *   <li>{@link Mode#INLINE} —— <b>单行输入输出</b>：逐字符打字，像真的在输入
 *       （对应 {@code shortAnimationLength} / {@code typingSmoothness} / {@code trailSize}）</li>
 *   <li>{@link Mode#CROSS} —— <b>多行缎带</b>：跨行移动，拖尾拉成长缎带
 *       （对应 {@code animationLength} / {@code stretchRatioK}）</li>
 * </ul>
 *
 * <h2>三个关键设计决定</h2>
 *
 * <p><b>1. 复用真实物理</b>：用真的 {@link TrailCorner} + 真的 {@link CaretTrailPanel}。
 * 另写简化模拟的话，预览观感和实际不一致，就失去意义了。
 *
 * <p><b>2. 延迟初始化</b>：构造时 {@code getWidth()} 还是 0（组件未布局），
 * 在那里算中心点会得到 (0,0) —— 表现为「光标贴在左上角」。
 * 所以位置在首次 {@code paintComponent} 时按真实宽度初始化。
 *
 * <p><b>3. 按「字符格」移动，不是按像素</b>：单行模式模拟真实打字，
 * 光标每次前进一个字符宽，间隔约 120ms（接近人手打字速度）。
 * 跨行模式每次移动一行高度 + 适量横向位移。
 */
final class AnimationPreview extends JComponent {

    /** 演示模式 */
    enum Mode {
        /** 单行输入输出：逐字符打字 */
        INLINE,
        /** 多行缎带：跨行移动 */
        CROSS
    }

    private final NeovideConfig config;
    private final Mode mode;
    private final CaretTrailPanel panel;
    private final TrailCorner[] corners;

    /** 光标中心（预览坐标系） */
    private double centerX;
    private double centerY;
    /** 是否已完成首次布局初始化 */
    private boolean placed;

    /** 光标尺寸（与真实 2px 光标同比放大，便于观察） */
    private static final double CURSOR_W = 3.0;
    private static final double CURSOR_H = 18.0;

    /** 行高 */
    private static final double LINE_H = 24.0;

    /** 单行模式的字符宽（用于逐字前进） */
    private static final double CHAR_W = 9.0;

    /** 物理推进间隔（毫秒）—— 与生产 8ms 一致 */
    private static final int FRAME_MS = 8;

    /** 单行模式：打字间隔（毫秒）。接近人手速度，太快看不清动画。 */
    private static final int TYPE_INTERVAL_MS = 130;

    /** 跨行模式：移动间隔（毫秒）。太短会让拖尾一直满屏拉伸像发疯。 */
    private static final int CROSS_INTERVAL_MS = 900;

    private Timer physicsTimer;
    private Timer actionTimer;

    /** 已打出的字符数（负数表示在退格） */
    private int typed;
    /** 跨行模式当前行（0/1 交替） */
    private int row;
    /** 跨行模式水平方向 */
    private int dir = 1;

    /** 单行演示文本（英文） */
    private static final String TEXT_EN = "pawamamama";
    /** 单行演示文本（中文——验证 CJK 字符宽度处理） */
    private static final String TEXT_CN = "\u8fd9\u662f\u4e00\u4e2a\u7f16\u8f91\u5668\u5149\u6807\u63d2\u4ef6";

    private static final Font PREVIEW_FONT =
            new Font(Font.MONOSPACED, Font.PLAIN, 13);

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
    }

    /** 参数变了：重建样式 + 重绘 */
    void onConfigChanged() {
        panel.refreshStyles();
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (physicsTimer == null) {
            physicsTimer = new Timer(FRAME_MS, e -> step());
        }
        if (actionTimer == null) {
            int interval = (mode == Mode.INLINE) ? TYPE_INTERVAL_MS : CROSS_INTERVAL_MS;
            actionTimer = new Timer(interval, e -> advance());
        }
        physicsTimer.start();
        actionTimer.start();
    }

    @Override
    public void removeNotify() {
        if (physicsTimer != null) {
            physicsTimer.stop();
        }
        if (actionTimer != null) {
            actionTimer.stop();
        }
        super.removeNotify();
    }

    /**
     * 首次布局后才算位置。
     *
     * <p>构造时 getWidth() 为 0，若在那里算 centerX 会得到 0 ——
     * 表现为光标贴在左上角。
     */
    private void placeIfNeeded() {
        if (placed || getWidth() <= 0) {
            return;
        }
        placed = true;
        if (mode == Mode.INLINE) {
            centerX = 14;
            centerY = LINE_H;
        } else {
            centerX = 40;
            centerY = LINE_H;
        }
        for (TrailCorner c : corners) {
            c.snapTo(CURSOR_W, CURSOR_H, centerX, centerY);
        }
    }

    /** 动作定时器：改变目标位置（模拟打字 / 跨行移动） */
    private void advance() {
        if (!placed || getWidth() <= 0) {
            return;
        }
        double targetX;
        double targetY;

        if (mode == Mode.INLINE) {
            String text = visibleText();
            int max = text.length();
            // 逐字前进；到末尾停一拍再逐字退格（模拟输入→删除）
            if (typed < max) {
                typed++;
            } else if (typed == max) {
                typed = -1;          // 触发退格阶段
            } else if (typed > -max) {
                typed--;
            } else {
                typed = 0;           // 循环
                cycleChinese = !cycleChinese;   // 每轮切换中英文演示
            }
            int shown = (typed < 0) ? (max + typed) : typed;
            targetX = 14 + shown * CHAR_W + CURSOR_W / 2;
            targetY = LINE_H;
        } else {
            // 跨行：左右移动 + 上下换行，模拟「跳来跳去」
            row = 1 - row;
            double span = Math.max(60, getWidth() - 80);
            targetX = 40 + span * (row == 0 ? 0.15 : 0.75);
            targetY = LINE_H + row * LINE_H * 1.6;
        }

        double prevX = centerX;
        double prevY = centerY;
        centerX = targetX;
        centerY = targetY;

        // 用真实 jump：内部按位移选「短跳 / 跨行」分支，时长梯度与生产一致
        int[] ranks = assignRanks(prevX, prevY, targetX, targetY);
        for (int i = 0; i < 4; i++) {
            corners[i].jump(CURSOR_W, CURSOR_H, targetX, targetY, ranks[i]);
        }
    }

    /** 物理步进（每 8ms） */
    private void step() {
        if (!placed) {
            return;
        }
        double dt = FRAME_MS / 1000.0;
        double maxOffset = 0;
        for (TrailCorner c : corners) {
            c.update(CURSOR_W, CURSOR_H, centerX, centerY, dt, false);
            maxOffset = Math.max(maxOffset, c.getOffsetMagnitude());
        }
        // 辉光强度与生产一致：静止为 0，运动中随偏移增长
        float glow = (float) Math.min(1.0, maxOffset / 10.0);
        panel.setGlowStrength(glow);
        repaint();
    }

    /** 当前要演示的文本：按 prefixed 语言交替（每轮切换英文/中文） */
    private String visibleText() {
        // 用 typed 的符号位不好区分轮次，这里按「跨行模式 vs 单行」简单取；
        // 中文轮次由 typed 循环到负数时切——够用且不引入额外状态机。
        return (cycleChinese) ? TEXT_CN : TEXT_EN;
    }

    /** 中英文轮换标志 */
    private boolean cycleChinese;

    /** 按「运动方向对齐度」给四角排名（与 SpringTrail.assignRanks 同逻辑） */
    private int[] assignRanks(double px, double py, double cx, double cy) {
        double[] alignment = new double[4];
        for (int i = 0; i < 4; i++) {
            alignment[i] = corners[i].calculateDirectionAlignment(CURSOR_W, CURSOR_H, cx, cy);
        }
        int[] ranks = new int[4];
        for (int i = 0; i < 4; i++) {
            int r = 0;
            for (int j = 0; j < 4; j++) {
                if (alignment[j] < alignment[i]) {
                    r++;
                }
            }
            ranks[i] = r;
        }
        return ranks;
    }

    @Override
    protected void paintComponent(Graphics g) {
        placeIfNeeded();

        g.setColor(new Color(0x2B2B2B));
        g.fillRect(0, 0, getWidth(), getHeight());

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 单行模式：画出已打出的文字（让预览像真的编辑器）
            if (mode == Mode.INLINE) {
                String text = visibleText();
                int max = text.length();
                int shown = (typed < 0) ? Math.max(0, max + typed) : Math.min(typed, max);
                g2.setFont(PREVIEW_FONT);
                g2.setColor(new Color(0xC8C8C8));
                g2.drawString(text.substring(0, Math.max(0, shown)), 14, (int) LINE_H + 5);
            } else {
                // 跨行模式：画两条基线，让「换行」看得见
                g2.setColor(new Color(0x3A3A3A));
                g2.drawLine(10, (int) LINE_H, getWidth() - 10, (int) LINE_H);
                g2.drawLine(10, (int) (LINE_H + LINE_H * 1.6),
                        getWidth() - 10, (int) (LINE_H + LINE_H * 1.6));
            }

            // 用真实面板画四边形（复用填充/辉光逻辑）
            panel.beginQuads();
            panel.addQuad(
                    new double[]{
                            corners[0].getCurrentX(), corners[1].getCurrentX(),
                            corners[2].getCurrentX(), corners[3].getCurrentX()},
                    new double[]{
                            corners[0].getCurrentY(), corners[1].getCurrentY(),
                            corners[2].getCurrentY(), corners[3].getCurrentY()},
                    panel.getGlowStrengthForPreview());
            panel.paintForPreview(g2, getWidth(), getHeight());
        } finally {
            g2.dispose();
        }
    }

    @Override
    public java.awt.Dimension getPreferredSize() {
        return new java.awt.Dimension(300, 72);
    }
}
