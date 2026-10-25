import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 拉伸强度（stretchRatioK）对每帧绘制成本的影响。
 *
 * <p>动机：用户持久设置里 stretchRatioK = 8.0（缎带档的上限）。k 越大拖尾被拉得越长，
 * 四边形面积越大 → 辉光每层要填的面积越大。这不依赖任何版本改动，是配置本身的成本。
 */
public final class BenchStretch {
    static final int W = 2000, H = 1200;
    static final double LH = 41.0;   // 用户日志里的实际光标高 wh=(2.0x41…)
    static final double CW = 2.0;

    public static void main(String[] a) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        System.out.printf("%-8s %-10s %-12s %-12s %-10s%n",
                "光标数", "拉伸倍数", "每帧µs", "占8ms", "vs k=1");
        System.out.println("----------------------------------------------------------");
        for (int cursors : new int[]{1, 8}) {
            double base = 0;
            for (double stretch : new double[]{1.0, 2.0, 4.0, 8.0}) {
                double t = measure(img, cursors, 8, stretch);
                if (stretch == 1.0) base = t;
                System.out.printf("%-8d %-10.1f %-12.0f %-12.1f%% %.2fx%n",
                        cursors, stretch, t, t / 8000 * 100, t / base);
            }
            System.out.println();
        }
    }

    private static double measure(BufferedImage img, int cursors, int layers, double stretch) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.useShadow = true;
        cfg.glowLayers = layers;
        cfg.glowOpacity = 0.3f;
        cfg.shadowBlurFactor = 0.6f;
        cfg.tailColor = "#FDE68A";
        cfg.shadowColor = "#FCD34D";

        CaretTrailPanel panel = new CaretTrailPanel(cfg);
        panel.setGlowStrength(1f);
        panel.setGlowFade(1f);
        panel.updateGlowRadius(CW, LH);

        for (int i = 0; i < 300; i++) paint(img, panel, cursors, i, stretch);
        int N = 2500;
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) paint(img, panel, cursors, i, stretch);
        long t1 = System.nanoTime();
        return (t1 - t0) / 1000.0 / N;
    }

    private static void paint(BufferedImage img, CaretTrailPanel panel,
                              int cursors, int f, double stretch) {
        panel.beginQuads();
        for (int k = 0; k < cursors; k++) {
            double cx = 200 + k * 200 + (f % 30) * 0.4;
            double cy = 150 + (k % 6) * 150;
            // 拖尾被拉伸后的真实形状：长度 = 光标高 × stretch，且沿对角倾斜
            double h = LH * stretch;
            double skew = (f % 20) * 0.5;
            double top = cy - h / 2, bottom = cy + h / 2;
            double left = cx - CW / 2, right = cx + CW / 2;
            panel.addQuad(
                    new double[]{left + skew, right + skew, right, left},
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
