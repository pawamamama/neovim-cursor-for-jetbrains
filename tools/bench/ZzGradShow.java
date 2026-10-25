import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** 光标渐变效果图：5 个预设 × 开/关对比。 */
public final class ZzGradShow {
    static final String[] N = {"原版粉色", "冰蓝", "薄荷", "藕荷", "暖沙"};
    static final String[] T = {"#FFC0CB", "#7DD3FC", "#6EE7B7", "#C4B5FD", "#FDE68A"};
    static final String[] G = {"#FFC0CB", "#38BDF8", "#34D399", "#A78BFA", "#FCD34D"};

    public static void main(String[] a) throws Exception {
        int Z = 9, W0 = 2, H0 = 40, gap = 26, cw = W0 * Z + gap;
        int W = 80 + N.length * cw * 2, H = 130;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x2B2D30)); g.fillRect(0, 0, W, H);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        g.drawString("光标渐变开（左）  vs  关（右）—— 放大 " + Z + " 倍", 14, 20);
        for (int i = 0; i < N.length; i++) {
            int x = 30 + i * cw * 2;
            draw(g, T[i], G[i], true, x, 38, Z);
            draw(g, T[i], G[i], false, x + cw, 38, Z);
            g.setColor(new Color(0xB0B0B0));
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            g.drawString(N[i], x, 38 + H0 * Z + 16);
        }
        g.dispose();
        ImageIO.write(img, "png", new File("build/grad-show.png"));
        System.out.println("已输出 build/grad-show.png");
    }

    static void draw(Graphics2D g, String t, String gl, boolean on, int x, int y, int z) {
        NeovideConfig c = new NeovideConfig();
        c.tailColor = t; c.shadowColor = gl; c.useShadow = false;
        CaretTrailPanel p = new CaretTrailPanel(c);
        p.setRgbEnabled(on);
        p.updateGlowRadius(2, 40);
        p.beginQuads();
        p.addQuad(new double[]{0, 2, 2, 0}, new double[]{0, 0, 40, 40}, 0f);
        BufferedImage one = new BufferedImage(2, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = one.createGraphics();
        try { p.paintForPreview(g2, 2, 40); } finally { g2.dispose(); }
        g.drawImage(one, x, y, 2 * z, 40 * z, null);
    }
}