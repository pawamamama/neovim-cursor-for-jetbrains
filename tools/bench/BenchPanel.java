import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 用【生产代码】CaretTrailPanel 实测每帧绘制成本。
 *
 * <p>这是定论性证据：不猜公式，直接调真正的 paintForPreview。
 *
 * <p>变量：光标数（1 / 8）、辉光层数（8 / 0）、光标高度（30% / 100%）。
 * 用户真实设置：lineHeight=32、glowLayers=8、shadowBlurFactor 取 0.2（默认）与 0.6（副本预设）。
 */
public final class BenchPanel {
    static final int W = 1600, H = 1000;
    static final double LINE_H = 32.0;
    static final double CUR_W = 2.0;

    public static void main(String[] a) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        System.out.println("每帧绘制成本（µs），预算 8000µs = 8ms @125fps");
        System.out.println("--------------------------------------------------------------------------");
        System.out.printf("%-6s %-8s %-8s %-10s %-10s %-8s%n",
                "光标数", "层数", "高度", "blur=0.2", "blur=0.6", "占比@0.6");
        System.out.println("--------------------------------------------------------------------------");

        for (int cursors : new int[]{1, 8}) {
            for (int layers : new int[]{8, 0}) {
                for (double ratio : new double[]{0.3, 1.0}) {
                    double t02 = measure(img, cursors, layers, ratio, 0.2f);
                    double t06 = measure(img, cursors, layers, ratio, 0.6f);
                    System.out.printf("%-6d %-8d %-8.0f%% %-10.0f %-10.0f %.1f%%%n",
                            cursors, layers, ratio * 100, t02, t06, t06 / 8000 * 100);
                }
            }
        }
    }

    private static double measure(BufferedImage img, int cursors, int layers,
                                  double ratio, float blur) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.useShadow = true;
        cfg.glowLayers = 8;
        cfg.glowOpacity = 0.3f;
        cfg.shadowBlurFactor = blur;
        cfg.tailColor = "#FDE68A";
        cfg.shadowColor = "#FCD34D";

        CaretTrailPanel panel = new CaretTrailPanel(cfg);
        panel.setEffectiveGlowLayers(layers);
        panel.setGlowStrength(1f);
        panel.setGlowFade(1f);

        double h = LINE_H * ratio;
        panel.updateGlowRadius(CUR_W, h);

        // 预热
        for (int i = 0; i < 300; i++) paint(img, panel, cfg, cursors, h, i);

        int N = 3000;
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) paint(img, panel, cfg, cursors, h, i);
        long t1 = System.nanoTime();
        return (t1 - t0) / 1000.0 / N;
    }

    private static void paint(BufferedImage img, CaretTrailPanel panel,
                              NeovideConfig cfg, int cursors, double h, int f) {
        panel.beginQuads();
        for (int k = 0; k < cursors; k++) {
            double cx = 200 + k * 150 + (f % 30) * 0.4;
            double cy = 100 + (k % 8) * 100;
            double stretch = 1.0 + ((f + k) % 12) * 0.4;
            double top = cy - h * stretch / 2;
            double bottom = cy + h * stretch / 2;
            double left = cx - CUR_W / 2;
            double right = cx + CUR_W / 2;
            panel.addQuad(
                    new double[]{left, right, right, left},
                    new double[]{top, top, bottom, bottom},
                    1f);
        }
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            panel.paintForPreview(g2, W, H);
        } finally {
            g2.dispose();
        }
    }
}
