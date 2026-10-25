import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** 把三个方案的双拼渐变渲染成图，直接看效果。 */
public final class ZzDualToneImg {
    static final String[] NAMES = {"原版粉色", "冰蓝", "薄荷", "藕荷", "暖沙"};
    static final String[] TAILS = {"#FFC0CB", "#7DD3FC", "#6EE7B7", "#C4B5FD", "#FDE68A"};
    static final String[] GLOWS = {"#FFC0CB", "#38BDF8", "#34D399", "#A78BFA", "#FCD34D"};

    public static void main(String[] a) throws Exception {
        int W = 900, ROW = 90, COLW = 130;
        BufferedImage img = new BufferedImage(W, ROW * 4 + 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // 深色背景（模拟 IDEA 深色主题）
        g.setColor(new Color(0x2B2D30));
        g.fillRect(0, 0, W, img.getHeight());

        g.setColor(Color.WHITE);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
        g.drawString("双拼渐变候选（光标宽 8px，高度放大到 70px 便于观察）", 20, 30);

        String[] labels = {"方案A 色相偏移", "方案B 辉光色参与", "方案C 明度梯度"};
        for (int row = 0; row < 3; row++) {
            int y = 60 + row * ROW;
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
            g.setColor(new Color(0xB0B0B0));
            g.drawString(labels[row], 20, y + 20);
            for (int i = 0; i < NAMES.length; i++) {
                int x = 150 + i * COLW;
                int top = hex(TAILS[i]);
                int bot = switch (row) {
                    case 0 -> hueShift(top, 30, 0.75, 0.92);
                    case 1 -> glowBased(top, GLOWS[i]);
                    default -> lightShift(top, 0.62);
                };
                // 画竖向渐变柱
                g.setPaint(new GradientPaint(x, y, new Color(top), x, y + 60, new Color(bot)));
                g.fillRoundRect(x, y, 8, 60, 4, 4);
                // 标注
                g.setFont(new Font("Consolas", Font.PLAIN, 11));
                g.setColor(new Color(0x909090));
                g.drawString(String.format("#%06X", bot), x + 14, y + 55);
            }
        }
        g.dispose();
        File out = new File("build/dual-tone.png");
        ImageIO.write(img, "png", out);
        System.out.println("已输出 " + out.getAbsolutePath());
    }

    static int hueShift(int rgb, double deg, double satMul, double briMul) {
        float[] h = Color.RGBtoHSB((rgb>>16)&0xFF, (rgb>>8)&0xFF, rgb&0xFF, null);
        double nh = (h[0] + deg/360.0) % 1.0;
        return Color.HSBtoRGB((float) nh, (float)(h[1]*satMul), (float)(h[2]*briMul)) & 0xFFFFFF;
    }
    static int glowBased(int tail, String glowHex) {
        int glow = hex(glowHex);
        float[] h = Color.RGBtoHSB((glow>>16)&0xFF, (glow>>8)&0xFF, glow&0xFF, null);
        return Color.HSBtoRGB(h[0], Math.min(1f, h[1]*1.05f), h[2]*0.85f) & 0xFFFFFF;
    }
    static int lightShift(int rgb, double mul) {
        float[] h = Color.RGBtoHSB((rgb>>16)&0xFF, (rgb>>8)&0xFF, rgb&0xFF, null);
        return Color.HSBtoRGB(h[0], h[1], (float)Math.max(0.15, h[2]*mul)) & 0xFFFFFF;
    }
    static int hex(String s) { return Integer.parseInt(s.substring(1), 16); }
}