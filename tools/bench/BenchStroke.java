import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** 用生产代码对比 fill 版与 stroke 版辉光的每帧成本。 */
public final class BenchStroke {
    static final int W = 1600, H = 1000;
    static final double LH = 32.0, CW = 2.0;

    public static void main(String[] a) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        System.out.printf("%-8s %-8s %-10s %-10s %-8s%n",
                "光标数", "层数", "fill(µs)", "stroke(µs)", "加速比");
        System.out.println("-------------------------------------------------------");
        for (int cursors : new int[]{1, 8}) {
            for (int layers : new int[]{8, 6, 4}) {
                double f = measure(img, cursors, layers, false);
                double s = measure(img, cursors, layers, true);
                System.out.printf("%-8d %-8d %-10.0f %-10.0f %.2fx%n",
                        cursors, layers, f, s, f / s);
            }
        }
    }

    private static double measure(BufferedImage img, int cursors, int layers, boolean stroke) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.useShadow = true;
        cfg.glowLayers = layers;
        cfg.glowOpacity = 0.3f;
        cfg.shadowBlurFactor = 0.6f;
        cfg.glowStrokeRendering = stroke;
        cfg.tailColor = "#FDE68A";
        cfg.shadowColor = "#FCD34D";

        CaretTrailPanel panel = new CaretTrailPanel(cfg);
        panel.setGlowStrength(1f);
        panel.setGlowFade(1f);
        panel.updateGlowRadius(CW, LH);

        for (int i = 0; i < 300; i++) paint(img, panel, cursors, i);
        int N = 3000;
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) paint(img, panel, cursors, i);
        long t1 = System.nanoTime();
        return (t1 - t0) / 1000.0 / N;
    }

    private static void paint(BufferedImage img, CaretTrailPanel panel, int cursors, int f) {
        panel.beginQuads();
        for (int k = 0; k < cursors; k++) {
            double cx = 200 + k * 150 + (f % 30) * 0.4;
            double cy = 100 + (k % 8) * 100;
            double stretch = 1.0 + ((f + k) % 12) * 0.4;
            double top = cy - LH * stretch / 2, bottom = cy + LH * stretch / 2;
            double left = cx - CW / 2, right = cx + CW / 2;
            panel.addQuad(new double[]{left, right, right, left},
                    new double[]{top, top, bottom, bottom}, 1f);
        }
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            panel.paintForPreview(g2, W, H);
        } finally {
            g2.dispose();
        }
    }
}
