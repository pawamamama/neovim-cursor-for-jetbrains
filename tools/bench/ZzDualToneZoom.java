import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** 2px 渲染后放大 10 倍，看渐变是否可见。 */
public final class ZzDualToneZoom {
    static final String[] NAMES = {"原版粉色", "冰蓝", "薄荷", "藕荷", "暖沙"};
    static final String[] TAIL = {"#FFC0CB", "#7DD3FC", "#6EE7B7", "#C4B5FD", "#FDE68A"};
    static final String[] GLOW = {"#FFC0CB", "#38BDF8", "#34D399", "#A78BFA", "#FCD34D"};

    public static void main(String[] a) throws Exception {
        int ZOOM = 10, CW = 2, CH = 40;
        int cellW = CW * ZOOM * 2 + 120;
        int W = 60 + NAMES.length * cellW, H = 40 + CH * ZOOM + 80;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x2B2D30));
        g.fillRect(0, 0, W, H);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
        g.drawString("2px 渲染，放大 " + ZOOM + " 倍（左双拼 / 右纯色）", 20, 26);

        for (int i = 0; i < NAMES.length; i++) {
            int bx = 40 + i * cellW;
            int by = 50;
            // 双拼
            BufferedImage duo = render(TAIL[i], GLOW[i], true);
            g.drawImage(duo, bx, by, CW * ZOOM, CH * ZOOM, null);
            // 纯色
            BufferedImage flat = render(TAIL[i], GLOW[i], false);
            g.drawImage(flat, bx + CW * ZOOM + 20, by, CW * ZOOM, CH * ZOOM, null);
            g.setColor(new Color(0xC0C0C0));
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
            g.drawString(NAMES[i], bx, by + CH * ZOOM + 24);
        }
        g.dispose();
        ImageIO.write(img, "png", new File("build/dual-tone-zoom.png"));
        System.out.println("已输出 build/dual-tone-zoom.png  " + W + "x" + H);
    }

    static BufferedImage render(String tail, String glow, boolean dual) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.tailColor = tail;
        cfg.shadowColor = glow;
        cfg.useShadow = false;
        BufferedImage img = new BufferedImage(2, 40, BufferedImage.TYPE_INT_RGB);
        if (dual) {
            CaretTrailPanel p = new CaretTrailPanel(cfg);
            p.updateGlowRadius(2, 40);
            p.beginQuads();
            p.addQuad(new double[]{0, 2, 2, 0}, new double[]{0, 0, 40, 40}, 0f);
            Graphics2D g2 = img.createGraphics();
            try { p.paintForPreview(g2, 2, 40); } finally { g2.dispose(); }
        } else {
            Graphics2D g2 = img.createGraphics();
            try {
                g2.setColor(Color.decode(tail));
                g2.fillRect(0, 0, 2, 40);
            } finally { g2.dispose(); }
        }
        return img;
    }
}