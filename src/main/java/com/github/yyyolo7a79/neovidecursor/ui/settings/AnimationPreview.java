package com.github.yyyolo7a79.neovidecursor.ui.settings;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 设置页里的【动画预览窗】—— 用小画布演示真实物理，参数改了立刻反映。
 *
 * <p><b>为什么需要</b>：用户反馈「现有设置不直观」。滑块写着「短动画时长 0.11s」，
 * 但没人知道 0.11 和 0.14 看起来差多少。预览窗把参数变成可见的动画。
 *
 * <h2>两种演示模式</h2>
 * <ul>
 *   <li>{@link Mode#INLINE} —— <b>行内输入删除</b>：光标本行内左右小步移动
 *       （对应 {@code shortAnimationLength} / {@code typingSmoothness}）</li>
 *   <li>{@link Mode#CROSS} —— <b>跨行移动</b>：光标上下换行 + 大跨度横向移动
 *       （对应 {@code animationLength} / {@code stretchRatioK}）</li>
 * </ul>
 *
 * <h2>实现要点</h2>
 * <p><b>复用真实物理</b>：用真的 {@link TrailCorner} 和真的 {@link CaretTrailPanel}，
 * 不是另写一套简化模拟 —— 否则预览显示的和实际观感会不一致，那就失去意义了。
 *
 * <p><b>独立 CONFIG</b>：预览持有自己的 {@link NeovideConfig} 副本，
 * 由设置面板在参数变化时写入。不碰全局 CONFIG，避免影响真实编辑器。
 *
 * <p><b>定时器</b>：{@code javax.swing.Timer} 在 EDT 上跑，8ms 间隔。
 * 预览窗很小（约 260x64），绘制量可忽略。
 */
final class AnimationPreview extends JComponent {

    /** 演示模式 */
    enum Mode {
        /** 行内输入删除：小步左右移动 */
        INLINE,
        /** 跨行移动：上下换行 + 大跨度 */
        CROSS
    }

    /** 预览用的独立配置副本（不碰全局 CONFIG） */
    private final NeovideConfig config;

    private final Mode mode;

    /** 复用真实绘制面板 */
    private final CaretTrailPanel panel;

    /** 四个角点（真实物理） */
    private final TrailCorner[] corners;

    /** 光标中心（预览坐标系） */
    private double centerX;
    private double centerY;

    /** 光标尺寸：宽 2px、高 16px（预览按 2 倍缩放，视觉更清楚） */
    private static final double CURSOR_W = 3.0;
    private static final double CURSOR_H = 22.0;

    /** 演示用的行高与移动步长 */
    private static final double LINE_H = 22.0;
    private static final double INLINE_STEP = 9.0;
    private static final double CROSS_STEP = 70.0;

    /** 帧间隔（毫秒）—— 与真实调度一致 */
    private static final int FRAME_MS = 8;

    private Timer timer;

    /** 演示动画的相位：正数向右，负数向左 */
    private int phase = 1;

    /** 跨行模式下当前在第几行（0 / 1 交替） */
    private int row = 0;

    AnimationPreview(@NotNull NeovideConfig config, @NotNull Mode mode) {
        this.config = config;
        this.mode = mode;
        this.panel = new CaretTrailPanel(config);
        this.corners = new TrailCorner[4];
        // 与 CaretAnimator 一致的角点相对位置（左上→右上→右下→左下）
        double[][] rel = {{-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5}};
        for (int i = 0; i < 4; i++) {
            corners[i] = new TrailCorner(rel[i][0], rel[i][1], config);
        }
        setOpaque(true);
        resetPosition();
        for (TrailCorner c : corners) {
            c.snapTo(CURSOR_W, CURSOR_H, centerX, centerY);
        }
    }

    /** 参数变了：重建面板样式 + 提醒重绘 */
    void onConfigChanged() {
        panel.refreshStyles();
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (timer == null) {
            timer = new Timer(FRAME_MS, e -> tick());
        }
        timer.start();
    }

    @Override
    public void removeNotify() {
        if (timer != null) {
            timer.stop();
        }
        super.removeNotify();
    }

    private void resetPosition() {
        centerX = getWidth() / 2.0;
        centerY = mode == Mode.CROSS ? LINE_H : getHeight() / 2.0;
    }

    /** 一帧：推进物理 + 重绘 */
    private void tick() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        // 目标点按模式前进；到边界就反向
        double step = (mode == Mode.INLINE) ? INLINE_STEP : CROSS_STEP;
        double nextX = centerX + step * phase;
        double margin = CURSOR_W + 8;
        if (nextX > getWidth() - margin || nextX < margin) {
            phase = -phase;
            nextX = centerX + step * phase;
            if (mode == Mode.CROSS) {
                // 换行：上下交替
                row = 1 - row;
            }
        }

        double nextY = (mode == Mode.CROSS)
                ? (row == 0 ? LINE_H : LINE_H * 2.4)
                : centerY;

        // 【关键】用真实的 jump 逻辑选时长（含短跳/跨行分支、rank 排序）
        int[] ranks = assignRanks(nextX, nextY);
        for (int i = 0; i < 4; i++) {
            // 预览不给 charsMoved，让它走「归一化长度」的兜底分支 ——
            // 与真实编辑器相比少了中文字符判定，但时长梯度一致。
            corners[i].jump(CURSOR_W, CURSOR_H, nextX, nextY, ranks[i]);
        }

        centerX = nextX;
        centerY = nextY;

        // 推进物理
        boolean anyMoving = false;
        double maxOffset = 0;
        for (TrailCorner c : corners) {
            if (c.update(CURSOR_W, CURSOR_H, centerX, centerY, FRAME_MS / 1000.0, false)) {
                anyMoving = true;
            }
            maxOffset = Math.max(maxOffset, c.getOffsetMagnitude());
        }

        // 辉光强度：与真实一致（静止为 0，运动为 1）
        float glow = (float) Math.min(1.0, maxOffset / 8.0);
        panel.setGlowStrength(glow);

        repaint();
    }

    /** 按「运动方向对齐度」给四角排名（与 SpringTrail.assignRanks 同逻辑） */
    private int[] assignRanks(double cx, double cy) {
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
        // 背景：与 IDE 编辑器接近的深色，保证粉色拖尾看得清
        g.setColor(new java.awt.Color(0x2B2B2B));
        g.fillRect(0, 0, getWidth(), getHeight());

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            // 用真实面板画四边形（复用同一套辉光/填充逻辑）
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
        return new java.awt.Dimension(260, 64);
    }
}
