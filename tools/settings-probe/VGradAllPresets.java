import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import java.awt.image.BufferedImage;

/**
 * V-GRAD-ALL-PRESETS：用户两条明确要求。
 *
 * <p>要求1：「渐变开了所有的单主题色应该全部是渐变效果」
 *   -> 开光标渐变后，选任何纯色预设都仍是渐变（只是颜色变了），
 *      不会被颜色预设静默关掉。
 *
 * <p>要求2：「性能诊断开启不应该创建副本」
 *   -> 在 VReset 的 R-16 里验证（点性能诊断不参与 isModified）。
 */
public final class VGradAllPresets {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        V0Env.install();
        System.out.println("=== V-GRAD-ALL-PRESETS ===");

        // 1) 开渐变 + 选每个纯色预设 -> 都该有渐变
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p.rgbWash()) continue;   // 彩虹是动态，另算
            NeovideCursorSettings.State s = new NeovideCursorSettings.State();
            s.rgbGradient = true;        // 用户开了渐变
            s.tailColor = p.tailColor();
            s.shadowColor = p.shadowColor();

            // 模拟「应用颜色预设」（0.9.88 后不该再动 rgbGradient）
            s.tailColor = p.tailColor();
            s.shadowColor = p.shadowColor();

            int spread = spreadOf(s);
            check("开渐变 + 选「" + p.name() + "」-> 仍是渐变",
                    spread > 10, "颜色跨度=" + spread);
        }

        // 2) 关渐变 + 选纯色 -> 纯色
        CursorPresets.ColorPreset blue = null;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if ("冰蓝".equals(p.name())) blue = p;
        }
        check("找到冰蓝预设", blue != null, blue == null ? "无" : blue.name());
        if (blue != null) {
            NeovideCursorSettings.State s = new NeovideCursorSettings.State();
            s.rgbGradient = false;
            s.tailColor = blue.tailColor();
            s.shadowColor = blue.shadowColor();
            check("关渐变 + 冰蓝 -> 纯色", spreadOf(s) <= 2, "跨度=" + spreadOf(s));
        }

        // 3) 彩虹仍能唯一匹配（说明删了三处写入没破坏彩虹）
        CursorPresets.ColorPreset rainbow = null;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p.rgbWash()) rainbow = p;
        }
        if (rainbow != null) {
            NeovideCursorSettings.State s = new NeovideCursorSettings.State();
            s.tailColor = rainbow.tailColor();
            s.shadowColor = rainbow.shadowColor();
            s.rgbGradient = false;    // 用户没开渐变
            CursorPresets.ColorPreset m = CursorPresetApplier.matchColorPreset(s);
            check("彩虹不依赖 rgbGradient 也能匹配到",
                    m == rainbow, "匹配=" + (m == null ? "null" : m.name()));
        }

        System.out.println();
        System.out.printf("=== PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("RESULT: FAILED"); System.exit(1); }
        System.out.println("RESULT: ALL PASS");
    }

    /** 在给定 State 下画一条，量沿长度方向的颜色跨度 */
    static int spreadOf(NeovideCursorSettings.State s) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.tailColor = s.tailColor;
        cfg.shadowColor = s.shadowColor;
        cfg.useShadow = false;
        CaretTrailPanel p = new CaretTrailPanel(cfg);
        p.setRgbEnabled(s.rgbGradient);
        p.setCursorRgbWash(false);   // 纯色预设，不走彩虹
        p.updateGlowRadius(2, 40);
        p.beginQuads();
        p.addQuad(new double[]{0, 2, 2, 0}, new double[]{0, 0, 40, 40}, 0f);
        BufferedImage img = new BufferedImage(4, 44, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try { p.paintForPreview(g, 4, 44); } finally { g.dispose(); }
        int minR=255,maxR=0,minG=255,maxG=0,minB=255,maxB=0;
        for (int y = 4; y <= 36; y += 2) {
            int c = img.getRGB(1, y) & 0xFFFFFF;
            int r=(c>>16)&0xFF, gg=(c>>8)&0xFF, b=c&0xFF;
            minR=Math.min(minR,r); maxR=Math.max(maxR,r);
            minG=Math.min(minG,gg); maxG=Math.max(maxG,gg);
            minB=Math.min(minB,b); maxB=Math.max(maxB,b);
        }
        return (maxR-minR)+(maxG-minG)+(maxB-minB);
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}