import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** 验证：光标渐变（双拼）用光标色系；彩虹用光标色的 S/B。 */
public final class ZzColorCheck {
    static final String[] NAMES = {"原版粉色", "冰蓝", "薄荷", "藕荷", "暖沙"};
    static final String[] TAIL  = {"#FFC0CB", "#7DD3FC", "#6EE7B7", "#C4B5FD", "#FDE68A"};
    static final String[] GLOW  = {"#FFC0CB", "#38BDF8", "#34D399", "#A78BFA", "#FCD34D"};

    public static void main(String[] a) throws Exception {
        int ZOOM = 8, CW = 2, CH = 40, gap = 26, cellW = CW * ZOOM + gap;
        int W = 130 + NAMES.length * cellW * 2, H = 150;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x2B2D30));
        g.fillRect(0, 0, W, H);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        g.drawString("光标渐变（左）    vs    彩虹（右）—— 都基于光标色", 16, 22);

        for (int i = 0; i < NAMES.length; i++) {
            int bx = 40 + i * cellW * 2;
            // 左：双拼（rgbGradient 开，但非彩虹）
            draw(g, TAIL[i], GLOW[i], true, bx, 45);
            // 右：彩虹（需要走 buildRgbWashPaint —— 这里模拟：开 + 用旧的 wash 路径）
            draw(g, TAIL[i], GLOW[i], false, bx + cellW, 45);
            g.setColor(new Color(0xB0B0B0));
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            g.drawString(NAMES[i], bx, 45 + CH * ZOOM + 18);
        }
        g.dispose();
        ImageIO.write(img, "png", new File("build/color-check.png"));
        System.out.println("已输出 build/color-check.png  " + W + "x" + H);
    }

    static void draw(Graphics2D g, String tail, String glow, boolean dualTone, int x, int y) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.tailColor = tail;
        cfg.shadowColor = glow;
        cfg.useShadow = false;
        CaretTrailPanel p = new CaretTrailPanel(cfg);
        p.updateGlowRadius(2, 40);
        p.setRgbEnabled(dualTone);
        p.beginQuads();
        p.addQuad(new double[]{0, 2, 2, 0}, new double[]{0, 0, 40, 40}, 0f);
        BufferedImage one = new BufferedImage(2, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = one.createGraphics();
        try { p.paintForPreview(g2, 2, 40); } finally { g2.dispose(); }
        g.drawImage(one, x, y, 2 * 8, 40 * 8, null);
    }
}