import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * 微基准：对比「每帧 new GradientPaint 填充」与「setColor 纯色填充」的开销。
 *
 * <p>目的：验证「开 RGB 渐变后掉 20 帧」是不是渐变光栅化导致的。
 * 场景按真实参数：2px 宽 × 32px 高的细长四边形，8 个光标，每次 20000 帧。
 */
public final class BenchGradient {
    public static void main(String[] a) {
        int W = 1200, H = 800;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Color c1 = new Color(0xFDE68A);
        Color c2 = new Color(0x7B68EE);

        // 预热
        for (int i = 0; i < 2000; i++) {
            both(img, c1, c2, i, 1);
        }

        int N = 20000;
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) both(img, c1, c2, i, 1);
        long t1 = System.nanoTime();
        System.out.printf("每帧 new GradientPaint : %.1f us/帧%n", (t1 - t0) / 1000.0 / N);

        long t2 = System.nanoTime();
        for (int i = 0; i < N; i++) both(img, c1, c2, i, 0);
        long t3 = System.nanoTime();
        System.out.printf("每帧 setColor 纯色      : %.1f us/帧%n", (t3 - t2) / 1000.0 / N);
        System.out.printf("差值                   : %.1f us/帧%n", ((t1 - t0) - (t3 - t2)) / 1000.0 / N);

        // 缓存版：GradientPaint 只建一次（锚定在固定几何上）
        GradientPaint cached = new GradientPaint(0, 100f, c1, 0, 132f, c2);
        long t4 = System.nanoTime();
        for (int i = 0; i < N; i++) cachedRun(img, cached, i);
        long t5 = System.nanoTime();
        System.out.printf("缓存后的 GradientPaint : %.1f us/帧%n", (t5 - t4) / 1000.0 / N);
    }

    /** mode=1 → 渐变；mode=0 → 纯色 */
    private static void both(BufferedImage img, Color c1, Color c2, int frame, int mode) {
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int k = 0; k < 8; k++) {
                double y = 50 + k * 90 + (frame % 20) * 0.3;
                double x = 100 + k * 120 + (frame % 30) * 0.5;
                double w = 2.0, h = 32.0;
                // 模拟拖尾：拉伸时四边形变高
                double stretch = 1.0 + ((frame + k) % 10) * 0.6;
                double top = y - h * stretch / 2;
                double bottom = y + h * stretch / 2;
                Path2D.Double p = new Path2D.Double();
                p.moveTo(x, top);
                p.lineTo(x + w, top);
                p.lineTo(x + w, bottom);
                p.lineTo(x, bottom);
                p.closePath();
                if (mode == 1) {
                    if (bottom - top > 0.5f) {
                        g2.setPaint(new GradientPaint(0, (float) top, c1, 0, (float) bottom, c2));
                    } else {
                        g2.setColor(c1);
                    }
                } else {
                    g2.setColor(c1);
                }
                g2.fill(p);
            }
        } finally {
            g2.dispose();
        }
    }

    /** 用预先建好的 GradientPaint（几何固定） */
    private static void cachedRun(BufferedImage img, GradientPaint gp, int frame) {
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int k = 0; k < 8; k++) {
                double y = 50 + k * 90 + (frame % 20) * 0.3;
                double x = 100 + k * 120 + (frame % 30) * 0.5;
                double w = 2.0, h = 32.0;
                double stretch = 1.0 + ((frame + k) % 10) * 0.6;
                double top = y - h * stretch / 2;
                double bottom = y + h * stretch / 2;
                Path2D.Double p = new Path2D.Double();
                p.moveTo(x, top);
                p.lineTo(x + w, top);
                p.lineTo(x + w, bottom);
                p.lineTo(x, bottom);
                p.closePath();
                g2.setPaint(gp);
                g2.fill(p);
            }
        } finally {
            g2.dispose();
        }
    }
}
