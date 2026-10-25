import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * 对比两种辉光渲染路径的真实成本。
 *
 * A = 现状：N 层同心外扩，每层 fill(Path2D)
 * B = 新方案：N 道同心 stroke，每道 draw(Path2D)
 *
 * 参数取用户真实设置：8 层、shadowBlurFactor=0.6、行高 32、光标高 100%。
 */
public final class BenchGlow2 {
    static final int W = 1600, H = 1000;
    static final float BLUR = 0.6f;
    static final int LAYERS = 8;
    static final double CW = 2.0, LH = 32.0;

    public static void main(String[] a) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        int N = 4000;

        for (int cursors : new int[]{1, 8}) {
            for (int i = 0; i < 400; i++) both(img, cursors, i, 0);

            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) both(img, cursors, i, 0);
            long t1 = System.nanoTime();
            long t2 = System.nanoTime();
            for (int i = 0; i < N; i++) both(img, cursors, i, 1);
            long t3 = System.nanoTime();

            double fill = (t1 - t0) / 1000.0 / N;
            double stroke = (t3 - t2) / 1000.0 / N;
            System.out.printf("%d 光标: fill %.0f us -> stroke %.0f us  (%.2fx, 省 %.0f us)%n",
                    cursors, fill, stroke, fill / stroke, fill - stroke);
        }
    }

    private static void both(BufferedImage img, int cursors, int f, int mode) {
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double h = LH;
            double radius = BLUR * Math.max(CW, h);
            int argb = (int) Math.round(0.3 / LAYERS * 255);
            g2.setColor(new Color(252, 211, 77, argb));

            for (int k = 0; k < cursors; k++) {
                double cx = 200 + k * 150 + (f % 30) * 0.4;
                double cy = 100 + (k % 8) * 100;
                double stretch = 1.0 + ((f + k) % 12) * 0.4;
                double top = cy - h * stretch / 2, bottom = cy + h * stretch / 2;
                double left = cx - CW / 2, right = cx + CW / 2;
                Path2D.Double base = rect(left, top, right, bottom);

                if (mode == 0) {
                    for (int i = LAYERS; i >= 1; i--) {
                        double rr = radius * i / LAYERS;
                        g2.fill(expand(base, rr));
                    }
                } else {
                    double band = radius / LAYERS;
                    g2.setStroke(new BasicStroke((float) Math.max(band, 1.0),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    for (int i = LAYERS; i >= 1; i--) {
                        double rr = radius * i / LAYERS - band / 2;
                        if (rr < 0) rr = 0;
                        g2.draw(expand(base, rr));
                    }
                }
            }
        } finally {
            g2.dispose();
        }
    }

    private static Path2D.Double rect(double l, double t, double r, double b) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(l, t); p.lineTo(r, t); p.lineTo(r, b); p.lineTo(l, b); p.closePath();
        return p;
    }

    private static Path2D.Double expand(Path2D.Double src, double r) {
        java.awt.geom.PathIterator it = src.getPathIterator(null);
        double[] c = new double[6];
        Path2D.Double out = new Path2D.Double();
        boolean first = true;
        while (!it.isDone()) {
            int t = it.currentSegment(c);
            if (t == java.awt.geom.PathIterator.SEG_MOVETO) {
                out.moveTo(c[0] - r, c[1] - r); first = false;
            } else if (t == java.awt.geom.PathIterator.SEG_LINETO) {
                // 简化：按象限外扩（仅用于基准，生产实现用精确外法线）
                out.lineTo(c[0] + r, c[1] + r);
            } else if (t == java.awt.geom.PathIterator.SEG_CLOSE) {
                out.closePath();
            }
            it.next();
        }
        return out;
    }
}
