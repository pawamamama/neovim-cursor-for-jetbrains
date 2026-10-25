import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** 实测新 RGB 色相循环的每帧成本（生产代码路径）。 */
public final class BenchRgbFinal {
    static final int W = 1600, H = 1000;
    static final double LH = 40.0, CW = 2.0;
    static final double BUDGET = 8000;

    public static void main(String[] a) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        System.out.printf("%-8s %-16s %-10s %-10s%n", "光标", "RGB", "每帧us", "占预算");
        System.out.println("-------------------------------------------------");
        for (int n : new int[]{1, 4, 8}) {
            double off = m(img, n, false);
            double on = m(img, n, true);
            System.out.printf("%-8d %-16s %-10.0f %.1f%%%n", n, "关（纯色）", off, off / BUDGET * 100);
            System.out.printf("%-8d %-16s %-10.0f %.1f%%  (+%.0f)%n", n, "开（色相循环）", on, on / BUDGET * 100, on - off);
        }
    }

    private static double m(BufferedImage img, int cursors, boolean rgb) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.useShadow = false;
        cfg.tailColor = "#FFC0CB";
        CaretTrailPanel p = new CaretTrailPanel(cfg);
        p.setRgbEnabled(rgb);
        for (int i = 0; i < 300; i++) paint(img, p, cursors, i);
        int N = 2500;
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) paint(img, p, cursors, i);
        long t1 = System.nanoTime();
        return (t1 - t0) / 1000.0 / N;
    }

    private static void paint(BufferedImage img, CaretTrailPanel p, int cursors, int f) {
        p.beginQuads();
        for (int k = 0; k < cursors; k++) {
            double cx = 200 + k * 150 + (f % 30) * 0.4;
            double cy = 100 + (k % 8) * 100;
            double st = 1.0 + ((f + k) % 12) * 0.4;
            double top = cy - LH * st / 2, bottom = cy + LH * st / 2;
            p.addQuad(new double[]{cx - CW / 2, cx + CW / 2, cx + CW / 2, cx - CW / 2},
                    new double[]{top, top, bottom, bottom}, 0f);
        }
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            p.paintForPreview(g2, W, H);
        } finally {
            g2.dispose();
        }
    }
}