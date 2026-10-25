import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

/**
 * 测 RGB 方案的性能上限：现有绘制之上还能加多少层径向渐变。
 *
 * 用户要求「不能完全照抄上游，太吃性能」—— 先量清预算再设计。
 */
public final class BenchRgbWash {
    static final int W = 1600, H = 1000;
    static final double LH = 40.0, CW = 2.0;
    static final double BUDGET_US = 8000;

    public static void main(String[] a) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        System.out.println("每帧预算 8000us（125fps）");
        System.out.println("-----------------------------------------------------------");
        System.out.printf("%-8s %-18s %-10s %-10s%n", "光标数", "方案", "每帧us", "占预算");
        System.out.println("-----------------------------------------------------------");

        for (int cursors : new int[]{1, 8}) {
            double base = measure(img, cursors, 0, 0);
            System.out.printf("%-8d %-18s %-10.0f %.1f%%%n",
                    cursors, "纯色基线", base, base / BUDGET_US * 100);

            double one = measure(img, cursors, 1, 0);
            System.out.printf("%-8d %-18s %-10.0f %.1f%%  (+%.0f)%n",
                    cursors, "1层纵向渐变", one, one / BUDGET_US * 100, one - base);

            for (int layers : new int[]{3, 5, 7}) {
                double t = measure(img, cursors, 1, layers);
                System.out.printf("%-8d %-18s %-10.0f %.1f%%  (+%.0f)%n",
                        cursors, "1纵向+" + layers + "径向", t, t / BUDGET_US * 100, t - base);
            }
            System.out.println();
        }
    }

    private static double measure(BufferedImage img, int cursors, int gradient, int washLayers) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.useShadow = false;
        cfg.glowLayers = 0;
        cfg.tailColor = "#FFC0CB";
        cfg.shadowColor = "#FFC0CB";

        CaretTrailPanel panel = new CaretTrailPanel(cfg);
        panel.updateGlowRadius(CW, LH);

        for (int i = 0; i < 300; i++) paint(img, panel, cursors, i, gradient, washLayers);
        int N = 2500;
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) paint(img, panel, cursors, i, gradient, washLayers);
        long t1 = System.nanoTime();
        return (t1 - t0) / 1000.0 / N;
    }

    private static void paint(BufferedImage img, CaretTrailPanel panel,
                              int cursors, int f, int gradient, int washLayers) {
        panel.beginQuads();
        for (int k = 0; k < cursors; k++) {
            double cx = 200 + k * 150 + (f % 30) * 0.4;
            double cy = 100 + (k % 8) * 100;
            double stretch = 1.0 + ((f + k) % 12) * 0.4;
            double top = cy - LH * stretch / 2, bottom = cy + LH * stretch / 2;
            double left = cx - CW / 2, right = cx + CW / 2;
            panel.addQuad(new double[]{left, right, right, left},
                    new double[]{top, top, bottom, bottom}, 0f);
        }
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            panel.paintForPreview(g2, W, H);
            if (gradient > 0) {
                // 模拟「叠加 washLayers 层径向渐变」的成本
                for (int k = 0; k < cursors; k++) {
                    double cx = 200 + k * 150 + (f % 30) * 0.4;
                    double cy = 100 + (k % 8) * 100;
                    double stretch = 1.0 + ((f + k) % 12) * 0.4;
                    double top = cy - LH * stretch / 2, bottom = cy + LH * stretch / 2;
                    double left = cx - CW / 2, right = cx + CW / 2;
                    for (int w = 0; w < washLayers; w++) {
                        float r = 10 + w * 6;
                        RadialGradientPaint rg = new RadialGradientPaint(
                                new Point2D.Float((float) cx, (float) top),
                                r,
                                new float[]{0f, 1f},
                                new Color[]{new Color(0, 110, 245, 120), new Color(0, 110, 245, 0)});
                        g2.setPaint(rg);
                        Path2D.Double p = new Path2D.Double();
                        p.moveTo(left, top);
                        p.lineTo(right, top);
                        p.lineTo(right, bottom);
                        p.lineTo(left, bottom);
                        p.closePath();
                        g2.fill(p);
                    }
                }
            }
        } finally {
            g2.dispose();
        }
    }
}