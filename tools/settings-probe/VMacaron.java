import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import java.awt.image.BufferedImage;

/**
 * V-MACARON：马卡龙动态渐变 + 双拼渐变。
 *
 * <p>锁三件事：
 * 1. 「马卡龙（动态）」预设存在且标记为 MACARON 风格
 * 2. 三色动态风格互斥（纯色 / 彩虹 / 马卡龙）
 * 3. 马卡龙渐变真的随时间变色（不只是一根静态棒）
 * 4. 双拼渐变（非动态）末端色确实比主色暗
 */
public final class VMacaron {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-MACARON：马卡龙动态 + 双拼 ===");

        // M-1 预设存在
        CursorPresets.ColorPreset macaron = null;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p.washStyle() == CursorPresets.WashStyle.MACARON) { macaron = p; }
        }
        check("M-1 存在风格为 MACARON 的颜色预设", macaron != null,
                macaron == null ? "没有" : macaron.name());

        // M-2 三种风格都出现了
        java.util.Set<CursorPresets.WashStyle> styles = new java.util.HashSet<>();
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) styles.add(p.washStyle());
        check("M-2 颜色预设覆盖 NONE / RAINBOW / MACARON 三种风格",
                styles.size() == 3, styles.toString());

        // M-3 只有彩虹和马卡龙是动态
        int dynamic = 0;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) if (p.rgbWash()) dynamic++;
        check("M-3 只有 2 个预设是动态（彩虹 + 马卡龙）", dynamic == 2, "动态数=" + dynamic);

        // M-4 色板 6 色且都是合法 HEX
        String[] pal = CursorPresets.MACARON_COLORS;
        boolean palOk = pal != null && pal.length == 6;
        if (palOk) for (String h : pal) if (!h.matches("#[0-9A-F]{6}")) palOk = false;
        check("M-4 马卡龙色板 6 色，全为合法大写 HEX", palOk,
                pal == null ? "null" : String.join(" ", pal));

        // M-5 马卡龙渐变随时间变色
        NeovideConfig cfg = new NeovideConfig();
        cfg.useShadow = false;
        CaretTrailPanel p = new CaretTrailPanel(cfg);
        p.setRgbEnabled(true);
        p.setRgbStyle(CursorPresets.WashStyle.MACARON);
        p.updateGlowRadius(2, 40);
        int c1 = sampleTop(p);
        Thread.sleep(700);           // 跨越 3000ms 周期的 23%
        int c2 = sampleTop(p);
        check("M-5 马卡龙渐变随时间变色（静止时也在流动）",
                c1 != c2, String.format("%06X -> %06X", c1, c2));

        // M-6 与彩虹风格产出不同颜色（说明风格真的分派了）
        CaretTrailPanel pRain = new CaretTrailPanel(cfg);
        pRain.setRgbEnabled(true);
        pRain.setRgbStyle(CursorPresets.WashStyle.RAINBOW);
        pRain.updateGlowRadius(2, 40);
        int cRain = sampleTop(pRain);
        CaretTrailPanel pMac = new CaretTrailPanel(cfg);
        pMac.setRgbEnabled(true);
        pMac.setRgbStyle(CursorPresets.WashStyle.MACARON);
        pMac.updateGlowRadius(2, 40);
        int cMac = sampleTop(pMac);
        check("M-6 MACARON 与 RAINBOW 产出不同颜色（风格真的分派）",
                cRain != cMac, String.format("rain=%06X macaron=%06X", cRain, cMac));

        // M-7 双拼渐变：末端比顶端暗
        CaretTrailPanel pDual = new CaretTrailPanel(cfg);
        pDual.setRgbEnabled(false);          // 关动态 = 走双拼
        pDual.updateGlowRadius(2, 40);
        int topC = sampleAt(pDual, 0, 5);
        int botC = sampleAt(pDual, 0, 35);
        check("M-7 双拼渐变末端比顶端暗（真的有渐变）",
                luminance(botC) < luminance(topC) - 0.02,
                String.format("top=%06X(L=%.3f) bottom=%06X(L=%.3f)",
                        topC, luminance(topC), botC, luminance(botC)));

        System.out.println();
        System.out.printf("=== 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-MACARON RESULT: FAILED"); System.exit(1); }
        System.out.println("V-MACARON RESULT: ALL PASS");
    }

    static int sampleTop(CaretTrailPanel p) { return sampleAt(p, 0, 5); }

    static int sampleAt(CaretTrailPanel p, int x, int y) {
        p.beginQuads();
        p.addQuad(new double[]{0, 2, 2, 0}, new double[]{0, 0, 40, 40}, 0f);
        BufferedImage img = new BufferedImage(4, 44, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try { p.paintForPreview(g, 4, 44); } finally { g.dispose(); }
        return img.getRGB(1, y) & 0xFFFFFF;
    }

    static double luminance(int rgb) {
        double r = lin(((rgb>>16)&0xFF)/255.0), g = lin(((rgb>>8)&0xFF)/255.0), b = lin((rgb&0xFF)/255.0);
        return 0.2126*r + 0.7152*g + 0.0722*b;
    }
    static double lin(double v) { return v <= 0.03928 ? v/12.92 : Math.pow((v+0.055)/1.055, 2.4); }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}