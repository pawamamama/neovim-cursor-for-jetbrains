import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * V-RGB-WASH：RGB 色相循环 + 「彩虹」颜色预设的联动。
 *
 * <p><b>锁什么</b>：
 * <ol>
 *   <li>选「彩虹」预设 → {@code rgbGradient = true}（色相循环开）</li>
 *   <li>选其余任何颜色预设 → {@code rgbGradient = false}（回到纯色）</li>
 *   <li>RGB 开启时绘制出的像素颜色**随位置变化**（不是单一颜色）</li>
 *   <li>RGB 开启时颜色**随时间变化**（色相循环在流动）</li>
 *   <li>RGB 关闭时同一四边形**颜色恒定**（纯色路径未受影响）</li>
 * </ol>
 */
public final class VRgbWash {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-RGB-WASH：色相循环 + 彩虹预设 ===");
        V0Env.install();

        // ---------- W-1：彩虹预设存在且风格为 RAINBOW ----------
        //
        // 【0.9.84 更新】原来只判 rgbWash=true，但马卡龙也是动态的，
        // 「最后一个 rgbWash=true」变成了马卡龙。现在按【风格】精确定位。
        CursorPresets.ColorPreset rainbow = null;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p.washStyle() == CursorPresets.WashStyle.RAINBOW) {
                rainbow = p;
            }
        }
        check("W-1 存在风格为 RAINBOW 的颜色预设", rainbow != null,
                rainbow == null ? "没有" : rainbow.name());

        // ---------- W-2：动态与纯色各占一部分 ----------
        //
        // 【0.9.84 更新】动态预设从 1 个（彩虹）变成 2 个（彩虹 + 马卡龙），
        // 所以「其余全是纯色」这条不再成立。改为精确校验：
        //   纯色数 + 动态数 = 总数，且动态数 == 2
        int plain = 0, dyn = 0;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p.rgbWash()) dyn++; else plain++;
        }
        check("W-2 动态预设 2 个（彩虹 + 马卡龙），其余为纯色",
                dyn == 2 && plain + dyn == CursorPresets.COLOR_PRESETS.length,
                "动态 " + dyn + " / 纯色 " + plain + " / 共 " + CursorPresets.COLOR_PRESETS.length);

        // ---------- W-3：选彩虹 → rgbGradient=true；选冰蓝 → false ----------
        if (rainbow != null) {
            NeovideCursorSettings.State s = new NeovideCursorSettings.State();
            s.rgbGradient = false;
            int ri = indexOf(rainbow);
            applyColor(s, ri);
            boolean onAfterRainbow = s.rgbGradient;
            int bi = indexOfName("冰蓝");
            if (bi >= 0) {
                applyColor(s, bi);
            }
            boolean offAfterBlue = !s.rgbGradient;
            check("W-3 选彩虹开 RGB、选冰蓝关 RGB（双向同步，否则显示层撒谎）",
                    onAfterRainbow && offAfterBlue,
                    "彩虹后=" + onAfterRainbow + " 冰蓝后=" + s.rgbGradient);
        }

        // ---------- W-4：RGB 开启时颜色随位置变化 ----------
        int spreadOn = colorSpread(true);
        check("W-4 RGB 开启时同一四边形颜色随位置变化（色相循环生效）",
                spreadOn > 30, "颜色跨度=" + spreadOn);

        // ---------- W-5：RGB 关闭时走双拼渐变 ----------
        //
        // 【0.9.84 行为变更】原来 RGB 关闭 = 纯色填充（颜色跨度 0）。
        // 现在加了双拼渐变（用户要求「混色是主色系自带的双拼渐变」），
        // 所以 RGB 关闭时**沿长度方向有渐变**，但**不随时间流动**。
        //
        // 判据拆成两条：
        //   W-5a  关闭时同一条内颜色有变化（双拼生效）
        //   W-5b  关闭时颜色不随时间变（静态，与动态互斥）
        int spreadOff = colorSpread(false);
        check("W-5a 关闭动态时同一条内颜色有渐变（双拼生效）",
                spreadOff > 5, "颜色跨度=" + spreadOff + "（应 > 5，但不该像动态那么大）");

        NeovideConfig cfgStatic = new NeovideConfig();
        cfgStatic.useShadow = false;
        CaretTrailPanel pStatic = new CaretTrailPanel(cfgStatic);
        pStatic.setRgbEnabled(false);
        int s1 = sampleTopColor(pStatic);
        Thread.sleep(700);
        int s2 = sampleTopColor(pStatic);
        check("W-5b 关闭动态时颜色不随时间变（静态双拼，与动态互斥）",
                s1 == s2, String.format("%06X -> %06X", s1, s2));

        // ---------- W-6：颜色随时间变化 ----------
        NeovideConfig cfg = new NeovideConfig();
        cfg.useShadow = false;
        CaretTrailPanel p1 = new CaretTrailPanel(cfg);
        p1.setRgbEnabled(true);
        int c1 = sampleTopColor(p1);
        Thread.sleep(400);   // 跨越 3000ms 周期的 13%，颜色应明显不同
        int c2 = sampleTopColor(p1);
        check("W-6 RGB 颜色随时间前移（静止时也在流动）",
                c1 != c2, "400ms 前=" + c1 + " 后=" + c2);

        System.out.println();
        System.out.printf("=== V-RGB-WASH 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-RGB-WASH RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-RGB-WASH RESULT: ALL PASS");
    }

    private static int indexOf(CursorPresets.ColorPreset target) {
        for (int i = 0; i < CursorPresets.COLOR_PRESETS.length; i++) {
            if (CursorPresets.COLOR_PRESETS[i] == target) return i;
        }
        return -1;
    }

    private static int indexOfName(String name) {
        for (int i = 0; i < CursorPresets.COLOR_PRESETS.length; i++) {
            if (CursorPresets.COLOR_PRESETS[i].name().equals(name)) return i;
        }
        return -1;
    }

    /** 复刻 onColorPresetPicked 的写入语义（不改面板 UI） */
    private static void applyColor(NeovideCursorSettings.State s, int idx) {
        CursorPresets.ColorPreset p = CursorPresets.COLOR_PRESETS[idx];
        s.tailColor = p.tailColor();
        s.shadowColor = p.shadowColor();
        s.rgbGradient = p.rgbWash();
    }

    /** 渲染一个四边形，量「沿长度方向的颜色跨度」 */
    private static int colorSpread(boolean rgb) {
        BufferedImage img = renderOneQuad(rgb);
        int[] rows = new int[img.getHeight()];
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            int alpha = (img.getRGB(img.getWidth() / 2, y) >>> 24) & 0xFF;
            if (alpha > 40) {
                rows[n++] = img.getRGB(img.getWidth() / 2, y) & 0xFFFFFF;
            }
        }
        if (n < 2) return 0;
        int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
        for (int i = 0; i < n; i++) {
            int r = (rows[i] >> 16) & 0xFF, g = (rows[i] >> 8) & 0xFF, b = rows[i] & 0xFF;
            minR = Math.min(minR, r); maxR = Math.max(maxR, r);
            minG = Math.min(minG, g); maxG = Math.max(maxG, g);
            minB = Math.min(minB, b); maxB = Math.max(maxB, b);
        }
        return (maxR - minR) + (maxG - minG) + (maxB - minB);
    }

    private static BufferedImage renderOneQuad(boolean rgb) {
        NeovideConfig cfg = new NeovideConfig();
        cfg.useShadow = false;
        cfg.tailColor = "#FFC0CB";
        CaretTrailPanel panel = new CaretTrailPanel(cfg);
        panel.setRgbEnabled(rgb);
        panel.beginQuads();
        panel.addQuad(new double[]{100, 106, 106, 100},
                new double[]{50, 50, 130, 130}, 0f);
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2 = img.createGraphics();
        try {
            panel.paintForPreview(g2, 200, 200);
        } finally {
            g2.dispose();
        }
        return img;
    }

    private static int sampleTopColor(CaretTrailPanel panel) {
        panel.beginQuads();
        panel.addQuad(new double[]{100, 106, 106, 100},
                new double[]{50, 50, 130, 130}, 0f);
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2 = img.createGraphics();
        try {
            panel.paintForPreview(g2, 200, 200);
        } finally {
            g2.dispose();
        }
        return img.getRGB(103, 55) & 0xFFFFFF;
    }

    private static void check(String n, boolean ok, String d) {
        String tail = d == null || d.isEmpty() ? "" : "   [" + d + "]";
        if (ok) { pass++; System.out.println("  PASS  " + n + tail); }
        else { fail++; System.out.println("  FAIL  " + n + tail); }
    }
}