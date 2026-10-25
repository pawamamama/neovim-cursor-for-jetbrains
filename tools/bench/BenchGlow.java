import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * 量化「光标高度 30% vs 100%」对每帧绘制成本的影响。
 *
 * <p>背景：0.9.61 修了「新编辑器不同步全局设置」的 bug 之后，cursorHeightRatio 从
 * 被 clamp 的 0.3 恢复成真实值 1.0 —— 光标变高 3.3 倍，辉光半径也跟着变
 * （radius = shadowBlurFactor x max(width, height)），绘制面积显著增加。
 *
 * <p>参数取用户真实设置：shadowBlurFactor=0.6、glowLayers=8、行高 32px。
 */
public final class BenchGlow {
    static final int W = 1600, H = 1000;
    static final float BLUR = 0.6f;
    static final int LAYERS = 8;
    static final double CURSOR_W = 2.0;
    static final double LINE_H = 32.0;

    public static void main(String[] a) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);

        for (double ratio : new double[]{0.3, 1.0}) {
            // 预热
            for (int i = 0; i < 500; i++) frame(img, ratio, i);
            int N = 3000;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) frame(img, ratio, i);
            long t1 = System.nanoTime();
            double h = LINE_H * ratio;
            double r = BLUR * Math.max(CURSOR_W, h);
            System.out.printf("高度比例 %.1f（光标高 %.1fpx，辉光半径 %.1fpx，8 光标 8 层）: %.1f us/帧%n",
                    ratio, h, r, (t1 - t0) / 1000.0 / N);
        }
    }

    private static void frame(BufferedImage img, double ratio, int f) {
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double h = LINE_H * ratio;
            for (int k = 0; k < 8; k++) {
                double cx = 200 + k * 150 + (f % 30) * 0.4;
                double cy = 100 + k * 100;
                // 拖尾拉伸：竖向拉长
                double stretch = 1.0 + ((f + k) % 12) * 0.4;
                double top = cy - h * stretch / 2;
                double bottom = cy + h * stretch / 2;
                double left = cx - CURSOR_W / 2;
                double right = cx + CURSOR_W / 2;

                double radius = BLUR * Math.max(CURSOR_W, h);
                g2.setColor(new Color(252, 211, 77, (int) (0.3 / LAYERS * 255)));

                // 8 层同心外扩
                for (int i = LAYERS; i >= 1; i--) {
                    double rr = radius * i / LAYERS;
                    Path2D.Double p = new Path2D.Double();
                    p.moveTo(left - rr, top - rr);
                    p.lineTo(right + rr, top - rr);
                    p.lineTo(right + rr, bottom + rr);
                    p.lineTo(left - rr, bottom + rr);
                    p.closePath();
                    g2.fill(p);
                }
                // 主体
                Path2D.Double body = new Path2D.Double();
                body.moveTo(left, top);
                body.lineTo(right, top);
                body.lineTo(right, bottom);
                body.lineTo(left, bottom);
                body.closePath();
                g2.setColor(new Color(0xFDE68A));
                g2.fill(body);
            }
        } finally {
            g2.dispose();
        }
    }
}
