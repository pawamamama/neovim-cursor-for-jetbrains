import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import java.awt.image.BufferedImage;

/** 验证三条路径互斥：彩虹 / 双拼 / 纯色。 */
public final class VRainbowSep {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== 三条路径互斥验证 ===");

        // 1) 彩虹：沿长度多个色相 + 随时间旋转
        CaretTrailPanel rain = mk(true, false);
        int spread = spread(rain);
        check("1 彩虹：沿长度有多个颜色（跨度高）", spread > 150, "跨度=" + spread);
        int r1 = topColor(rain);
        Thread.sleep(500);
        int r2 = topColor(rain);
        check("2 彩虹：颜色随时间旋转", r1 != r2,
                String.format("%06X -> %06X", r1, r2));

        // 2) 双拼：有渐变但不随时间变
        CaretTrailPanel dual = mk(false, true);
        int ds = spread(dual);
        check("3 双拼：同一条内有渐变", ds > 10, "跨度=" + ds);
        int d1 = topColor(dual);
        Thread.sleep(500);
        int d2 = topColor(dual);
        check("4 双拼：颜色不随时间变（静态）", d1 == d2,
                String.format("%06X -> %06X", d1, d2));

        // 3) 纯色
        CaretTrailPanel flat = mk(false, false);
        check("5 纯色：跨度为 0", spread(flat) <= 2, "跨度=" + spread(flat));

        // 4) 互斥：彩虹优先（两者都开时走彩虹）
        CaretTrailPanel both = mk(true, true);
        check("6 互斥：彩虹与双拼都开时，彩虹优先", spread(both) > 150,
                "跨度=" + spread(both));

        System.out.println();
        System.out.printf("=== PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("RESULT: FAILED"); System.exit(1); }
        System.out.println("RESULT: ALL PASS");
    }

    static CaretTrailPanel mk(boolean rainbow, boolean dual) {
        NeovideConfig c = new NeovideConfig();
        c.tailColor = "#FFC0CB";
        c.shadowColor = "#FFC0CB";
        c.useShadow = false;
        CaretTrailPanel p = new CaretTrailPanel(c);
        p.updateGlowRadius(2, 40);
        p.setCursorRgbWash(rainbow);
        p.setRgbEnabled(dual);
        return p;
    }

    static int spread(CaretTrailPanel p) {
        int minR=255,maxR=0,minG=255,maxG=0,minB=255,maxB=0;
        for (int y = 4; y <= 36; y += 2) {
            int c = at(p, y);
            int r=(c>>16)&0xFF, g=(c>>8)&0xFF, b=c&0xFF;
            minR=Math.min(minR,r); maxR=Math.max(maxR,r);
            minG=Math.min(minG,g); maxG=Math.max(maxG,g);
            minB=Math.min(minB,b); maxB=Math.max(maxB,b);
        }
        return (maxR-minR)+(maxG-minG)+(maxB-minB);
    }
    static int topColor(CaretTrailPanel p) { return at(p, 4); }
    static int at(CaretTrailPanel p, int y) {
        p.beginQuads();
        p.addQuad(new double[]{0,2,2,0}, new double[]{0,0,40,40}, 0f);
        BufferedImage img = new BufferedImage(4, 44, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try { p.paintForPreview(g, 4, 44); } finally { g.dispose(); }
        return img.getRGB(1, y) & 0xFFFFFF;
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}