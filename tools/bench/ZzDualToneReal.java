import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** 用生产 CaretTrailPanel 渲染，看 2px 宽下双拼是否可见。 */
public final class ZzDualToneReal {
    static final String[] NAMES = {"原版粉色", "冰蓝", "薄荷", "藕荷", "暖沙"};
    static final String[] TAIL = {"#FFC0CB", "#7DD3FC", "#6EE7B7", "#C4B5FD", "#FDE68A"};
    static final String[] GLOW = {"#FFC0CB", "#38BDF8", "#34D399", "#A78BFA", "#FCD34D"};

    public static void main(String[] a) throws Exception {
        int W = 1000, H = 260;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x2B2D30));
        g.fillRect(0, 0, W, H);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
        g.drawString("真实渲染：2px 宽 × 40px 高（生产 CaretTrailPanel）", 20, 28);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        g.setColor(new Color(0x909090));
        g.drawString("左：双拼渐变    右：原来的纯色（对照）", 20, 50);

        for (int i = 0; i < NAMES.length; i++) {
            int y = 70 + i * 36;
            // 双拼
            draw(g, img, TAIL[i], GLOW[i], 60, y, true);
            // 纯色对照
            draw(g, img, TAIL[i], GLOW[i], 90, y, false);
            g.setColor(new Color(0xB0B0B0));
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString(NAMES[i], 120, y + 22);
        }
        g.dispose();
        ImageIO.write(img, "png", new File("build/dual-tone-real.png"));
        System.out.println("已输出 build/dual-tone-real.png");
    }

    static void draw(Graphics2D g, BufferedImage img, String tail, String glow,
                     int x, int y, boolean dualTone) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.tailColor = tail;
        cfg.shadowColor = glow;
        cfg.useShadow = false;
        cfg.cursorWidth = 2f;
        CaretTrailPanel p = new CaretTrailPanel(cfg);
        p.updateGlowRadius(2, 40);
        p.beginQuads();
        // 一个 2x40 的矩形
        p.addQuad(new double[]{x, x + 2, x + 2, x},
                new double[]{y, y, y + 40, y + 40}, 0f);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (dualTone) {
                p.paintForPreview(g2, img.getWidth(), img.getHeight());
            } else {
                g2.setColor(java.awt.Color.decode(tail));
                g2.fillRect(x, y, 2, 40);
            }
        } finally {
            g2.dispose();
        }
    }
}