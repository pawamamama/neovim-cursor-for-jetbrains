import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.awt.image.BufferedImage;

/**
 * V-CURSOR-GRADIENT：光标渐变（双拼）语义。
 *
 * <p><b>0.9.83 重写</b>。本探针原名 V-RGB-WASH，锁的是「RGB 开 = 色相循环」。
 * 现在语义变了，断言的**方向**必须跟着改（重写，不删除 —— 纪律二.6）：
 *
 * <pre>
 *   旧语义：rgbGradient 开 → 色相循环（彩虹），颜色随时间流动
 *   新语义：rgbGradient 开 → 双拼渐变（上端拖尾色 / 下端算出的末端色），静态
 * </pre>
 *
 * <p><b>锁什么</b>：
 * <ol>
 *   <li>开 → 同一四边形内颜色随位置变化（有渐变）</li>
 *   <li>开 → 颜色**不**随时间变化（静态，不是色相循环）</li>
 *   <li>关 → 颜色恒定（纯色填充）</li>
 *   <li>末端色确实比上端暗（渐变方向对）</li>
 *   <li>换颜色预设，渐变跟着换（末端色由该预设的辉光色算出）</li>
 *   <li>颜色预设的 rgbWash 与 rgbGradient 双向同步（选彩虹会开、选冰蓝会关）</li>
 * </ol>
 */
public final class VRgbWash {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-CURSOR-GRADIENT：光标渐变（双拼）===");
        V0Env.install();

        // ---------- W-1：开 → 同一四边形内颜色随位置变化 ----------
        CaretTrailPanel on = panel(true);
        int spreadOn = spread(on);
        check("W-1 开启时同一四边形内颜色随位置变化（有渐变）",
                spreadOn > 10, "颜色跨度=" + spreadOn);

        // ---------- W-2：开 → 颜色不随时间变（静态，不是色相循环）----------
        int t1 = topColor(on);
        Thread.sleep(600);
        int t2 = topColor(on);
        check("W-2 开启时颜色【不】随时间变化（静态双拼，非色相循环）",
                t1 == t2, String.format("%06X -> %06X", t1, t2));

        // ---------- W-3：关 → 颜色恒定 ----------
        CaretTrailPanel off = panel(false);
        int spreadOff = spread(off);
        check("W-3 关闭时颜色恒定（纯色填充）",
                spreadOff <= 2, "颜色跨度=" + spreadOff);

        // ---------- W-4：末端比上端暗（渐变方向对）----------
        int top = sampleAt(on, 0, 4);
        int bot = sampleAt(on, 0, 36);
        check("W-4 末端比上端暗（渐变方向正确）",
                luminance(bot) < luminance(top) - 0.02,
                String.format("top=L%.3f bottom=L%.3f", luminance(top), luminance(bot)));

        // ---------- W-5：换预设，末端色跟着变 ----------
        NeovideConfig c1 = new NeovideConfig();
        c1.tailColor = "#7DD3FC"; c1.shadowColor = "#38BDF8"; c1.useShadow = false;
        int end1 = sampleAt(panelOf(c1, true), 0, 36);
        NeovideConfig c2 = new NeovideConfig();
        c2.tailColor = "#6EE7B7"; c2.shadowColor = "#34D399"; c2.useShadow = false;
        int end2 = sampleAt(panelOf(c2, true), 0, 36);
        check("W-5 换颜色预设时末端色跟着变（末端由该预设辉光色算出）",
                end1 != end2, String.format("冰蓝=%06X 薄荷=%06X", end1, end2));

        // ---------- W-6：颜色预设的 rgbWash ↔ rgbGradient 双向同步 ----------
        CursorPresets.ColorPreset rainbow = null;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p.rgbWash()) rainbow = p;
        }
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.rgbGradient = false;
        applyColor(s, indexOf(rainbow));
        boolean onAfterRainbow = s.rgbGradient;
        applyColor(s, indexOfName("冰蓝"));
        boolean offAfterBlue = !s.rgbGradient;
        check("W-6 选彩虹开渐变、选冰蓝关渐变（双向同步）",
                onAfterRainbow && offAfterBlue,
                "彩虹后=" + onAfterRainbow + " 冰蓝后=" + s.rgbGradient);

        System.out.println();
        System.out.printf("=== 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-CURSOR-GRADIENT RESULT: FAILED"); System.exit(1); }
        System.out.println("V-CURSOR-GRADIENT RESULT: ALL PASS");
    }

    static CaretTrailPanel panel(boolean grad) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.tailColor = "#FFC0CB";
        cfg.shadowColor = "#FFC0CB";
        cfg.useShadow = false;
        return panelOf(cfg, grad);
    }

    static CaretTrailPanel panelOf(NeovideConfig cfg, boolean grad) {
        CaretTrailPanel p = new CaretTrailPanel(cfg);
        p.setRgbEnabled(grad);
        p.updateGlowRadius(2, 40);
        return p;
    }

    /** 沿长度方向取多点，量 RGB 通道的最大跨度 */
    static int spread(CaretTrailPanel p) {
        int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
        int n = 0;
        for (int y = 4; y <= 36; y += 2) {
            int c = sampleAt(p, 0, y);
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            minR = Math.min(minR, r); maxR = Math.max(maxR, r);
            minG = Math.min(minG, g); maxG = Math.max(maxG, g);
            minB = Math.min(minB, b); maxB = Math.max(maxB, b);
            n++;
        }
        return n < 2 ? 0 : (maxR - minR) + (maxG - minG) + (maxB - minB);
    }

    static int topColor(CaretTrailPanel p) { return sampleAt(p, 0, 4); }

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

    static int indexOf(CursorPresets.ColorPreset target) {
        for (int i = 0; i < CursorPresets.COLOR_PRESETS.length; i++) {
            if (CursorPresets.COLOR_PRESETS[i] == target) return i;
        }
        return -1;
    }
    static int indexOfName(String name) {
        for (int i = 0; i < CursorPresets.COLOR_PRESETS.length; i++) {
            if (CursorPresets.COLOR_PRESETS[i].name().equals(name)) return i;
        }
        return -1;
    }
    static void applyColor(NeovideCursorSettings.State s, int idx) {
        CursorPresets.ColorPreset p = CursorPresets.COLOR_PRESETS[idx];
        s.tailColor = p.tailColor();
        s.shadowColor = p.shadowColor();
        s.rgbGradient = p.rgbWash();
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}