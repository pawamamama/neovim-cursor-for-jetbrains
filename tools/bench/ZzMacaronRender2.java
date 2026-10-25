import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** 马卡龙动态：加速采样（每 800ms 一帧），覆盖更长时间跨度。 */
public final class ZzMacaronRender2 {
    public static void main(String[] a) throws Exception {
        int COLS = 12, ZOOM = 7, CW = 2, CH = 40;
        int gap = 18, cellW = CW * ZOOM + gap;
        int W = 60 + COLS * cellW, H = 145;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x2B2D30));
        g.fillRect(0, 0, W, H);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        g.drawString("马卡龙动态：每 0.8 秒一帧（周期 3 秒/色，放大 " + ZOOM + " 倍）", 16, 22);

        NeovideConfig cfg = new NeovideConfig();
        cfg.useShadow = false;
        CaretTrailPanel p = new CaretTrailPanel(cfg);
        p.setRgbEnabled(true);
        p.setRgbStyle(CursorPresets.WashStyle.MACARON);
        p.updateGlowRadius(CW, CH);

        for (int i = 0; i < COLS; i++) {
            p.beginQuads();
            p.addQuad(new double[]{0, CW, CW, 0}, new double[]{0, 0, CH, CH}, 0f);
            BufferedImage one = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = one.createGraphics();
            try { p.paintForPreview(g2, CW, CH); } finally { g2.dispose(); }
            g.drawImage(one, 30 + i * cellW, 40, CW * ZOOM, CH * ZOOM, null);
            g.setColor(new Color(0x909090));
            g.setFont(new Font("Consolas", Font.PLAIN, 10));
            g.drawString(String.format("%.1fs", i * 0.8), 30 + i * cellW, 40 + CH * ZOOM + 16);
            Thread.sleep(800);
        }
        g.dispose();
        ImageIO.write(img, "png", new File("build/macaron-frames2.png"));
        System.out.println("已输出 build/macaron-frames2.png");
    }
}