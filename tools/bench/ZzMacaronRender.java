import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Method;

/** 渲染马卡龙动态：不同时间点的样子。 */
public final class ZzMacaronRender {
    public static void main(String[] a) throws Exception {
        int COLS = 8, ZOOM = 8, CW = 2, CH = 40;
        int cellW = CW * ZOOM + 22;
        int W = 80 + COLS * cellW, H = 130;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x2B2D30));
        g.fillRect(0, 0, W, H);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        g.drawString("马卡龙动态：每 1 秒一帧（周期 8 秒/色，放大 " + ZOOM + " 倍）", 20, 26);

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
            g.drawImage(one, 40 + i * cellW, 50, CW * ZOOM, CH * ZOOM, null);
            g.setColor(new Color(0x909090));
            g.setFont(new Font("Consolas", Font.PLAIN, 11));
            g.drawString((i) + "s", 40 + i * cellW + 4, 50 + CH * ZOOM + 18);
            Thread.sleep(1000);   // 每帧间隔 1 秒，8 帧覆盖一个周期
        }
        g.dispose();
        ImageIO.write(img, "png", new File("build/macaron-frames.png"));
        System.out.println("已输出 build/macaron-frames.png");
    }
}