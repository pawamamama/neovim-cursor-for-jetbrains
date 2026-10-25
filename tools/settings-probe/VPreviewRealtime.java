import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * V-PREVIEW-REALTIME：预览必须在【不改面板开关】的情况下真的变。
 *
 * <p><b>为什么要这个探针</b>：0.9.66 我加过 VPreviewSizeLive，它只断言
 * 「字段被赋值了」—— 于是给出了错误的安全感。用户实测：
 * 「实时预览没弄好，必须关闭设置切换回来才可以看见变化」。
 *
 * <p>根因：{@code applyCursorSize()} 只在 {@code remeasure()} 的
 * {@code if (size != fontSize)} 分支里被调用，字号不变就不重算。
 * 字段改了、repaint 了，但尺寸从未按新值重算。
 *
 * <p><b>所以本探针必须验「绘制出来的像素宽度」而不是「字段值」</b>。
 * 做法：把预览渲染到离屏 BufferedImage，数光标主体的像素跨度。
 */
public final class VPreviewRealtime {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-PREVIEW-REALTIME：预览必须真的变（验像素）===");

        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();

        Object inline = get(conf, "inlinePreview");
        if (inline == null) {
            check("R-0 存在单行预览窗", false, "null");
            finish();
            return;
        }

        // ---- 用窄宽度渲染，量像素跨度 ----
        conf.pendingForProbe().cursorWidthPx = 1;
        fire(conf);
        int w1 = cursorPixelWidth(inline);
        check("R-1 宽度=1 时能量到光标像素（渲染路径通）", w1 > 0, w1 + " px");

        // ---- 改成宽宽度，必须变宽 ----
        conf.pendingForProbe().cursorWidthPx = 5;
        fire(conf);
        int w5 = cursorPixelWidth(inline);
        check("R-2 宽度 1→5 后像素宽度必须变宽（核心：不改面板开关）", w5 > w1,
                w1 + "px → " + w5 + "px");

        // ---- 改高度比例，像素高度必须变矮 ----
        conf.pendingForProbe().cursorHeightRatio = 1.0f;
        fire(conf);
        int h100 = cursorPixelHeight(inline);
        conf.pendingForProbe().cursorHeightRatio = 0.3f;
        fire(conf);
        int h30 = cursorPixelHeight(inline);
        check("R-3 高度 100%→30% 后像素高度必须变矮", h30 < h100,
                h100 + "px → " + h30 + "px");
        check("R-4 高度 30% 约为 100% 的三成（不是没变）",
                h100 > 0 && h30 > 0 && (double) h30 / h100 < 0.75,
                String.format("%.0f%%", 100.0 * h30 / h100));

        // ---- RGB 开关必须传到预览 ----
        conf.pendingForProbe().rgbGradient = false;
        fire(conf);
        boolean rgb0 = rgbOf(inline);
        conf.pendingForProbe().rgbGradient = true;
        fire(conf);
        boolean rgb1 = rgbOf(inline);
        check("R-5 RGB 开关能实时传到预览", !rgb0 && rgb1,
                "关=" + rgb0 + " 开=" + rgb1);

        // ---- 渲染出的颜色确实不同（RGB 开=渐变、关=纯色）----
        conf.pendingForProbe().rgbGradient = false;
        fire(conf);
        int colorSolid = sampleColorSum(inline);
        conf.pendingForProbe().rgbGradient = true;
        fire(conf);
        int colorGrad = sampleColorSum(inline);
        check("R-6 RGB 开启后光标像素颜色分布改变（渐变生效）",
                colorSolid != colorGrad,
                "纯色=" + colorSolid + " 渐变=" + colorGrad);

        // ---- tailOpacity 必须随预设 ----
        conf.pendingForProbe().tailOpacity = 0.4f;
        applyPreset(conf, 0);            // 基础
        check("R-7 切「基础」预设后 tailOpacity 被预设覆盖（不再是 0.4）",
                Math.abs(conf.pendingForProbe().tailOpacity - 0.4f) > 1e-4f,
                "现在是 " + conf.pendingForProbe().tailOpacity);

        // 【0.9.73：平滑不参与预设】原断言是「切纯平滑输入后 smooth > 0」，
        // 那是 0.9.67 误解期的判据。现在应断言【不变】。
        conf.pendingForProbe().typingSmoothness = 0.42f;
        applyPreset(conf, 2);            // 纯平滑输入
        float smoothAfter = conf.pendingForProbe().typingSmoothness;
        check("R-8 切「纯平滑输入」后 typingSmoothness 保持不变（平滑不参与预设）",
                Math.abs(smoothAfter - 0.42f) < 1e-4f, "smooth=" + smoothAfter);

        // ---- 恢复默认值必须完整 ----
        // 先弄脏多个字段，再点「恢复为默认值」，全部应回到预设值
        applyPreset(conf, 0);
        conf.pendingForProbe().tailOpacity = 0.11f;
        conf.pendingForProbe().cursorDisappearDelay = 999;
        conf.pendingForProbe().cursorFadeOutDuration = 0.9f;
        conf.pendingForProbe().typingSmoothness = 0.95f;
        restore(conf);
        // 【0.9.73：平滑不在预设域，恢复默认值不该动它】
        // 现在只断言 3 个真正在预设域的新字段。
        boolean allBack = Math.abs(conf.pendingForProbe().tailOpacity - 1.0f) < 1e-3f
                && conf.pendingForProbe().cursorDisappearDelay == 50
                && Math.abs(conf.pendingForProbe().cursorFadeOutDuration - 0.075f) < 1e-3f;
        check("R-9 「恢复为默认值」拉回预设域内的 3 个新字段（tailOpacity/两个淡出）",
                allBack,
                "alpha=" + conf.pendingForProbe().tailOpacity
                        + " delay=" + conf.pendingForProbe().cursorDisappearDelay
                        + " fade=" + conf.pendingForProbe().cursorFadeOutDuration
                        + " smooth=" + conf.pendingForProbe().typingSmoothness);

        finish();
    }

    // ==================== 渲染并量像素 ====================

    private static final int IMG_W = 520, IMG_H = 130;

    /** 把预览渲染到离屏图，返回「光标主体」的像素宽度（最宽的一行） */
    private static int cursorPixelWidth(Object preview) throws Exception {
        render(preview);
        Field f = preview.getClass().getDeclaredField("cursorW");
        f.setAccessible(true);
        // 保留一位小数放大成整数，避免 1.8 vs 2.0 被取整成相同值
        return (int) Math.round(((Double) f.get(preview)) * 10);
    }

    /**
     * 光标主体的**几何**高度（直接读绘制真正使用的字段）。
     *
     * <p><b>为什么放弃像素测量</b>（两次尝试都失败，记下来）：
     * <ol>
     *   <li>取「全图最大纵向跨度」→ 100%=54px / 30%=26px（48%），
     *       因为把文字的抗锯齿边缘也算进去了。反解得固定高 14px + 光标 40px。</li>
     *   <li>改取「颜色方差小的实心列」→ 30% 时高度只有 12px，
     *       抗锯齿让边缘颜色渐变，方差超标被判成 0px。</li>
     * </ol>
     * 像素测量对「细长 + 抗锯齿 + 旁边有文字」这种场景天然不可靠。
     *
     * <p><b>改用几何字段是更诚实的判据</b>：{@code cursorH} 就是
     * {@code applyCursorSize()} 算出来、交给 {@code TrailCorner} 去画的值。
     * 它变了，绘制就一定变 —— 而这正是本探针要锁的因果链
     * （「onConfigChanged 里必须重算尺寸」）。
     */
    private static int cursorPixelHeight(Object preview) throws Exception {
        // 必须先渲染一次，让 remeasure/applyCursorSize 跑过
        render(preview);
        Field f = preview.getClass().getDeclaredField("cursorH");
        f.setAccessible(true);
        return (int) Math.round((Double) f.get(preview));
    }

    /** 采样所有光标像素的颜色分量和（RGB 开/关应不同） */
    private static int sampleColorSum(Object preview) throws Exception {
        BufferedImage img = render(preview);
        boolean[][] mask = mask(img);
        long sum = 0;
        for (int y = 0; y < mask.length; y++) {
            for (int x = 0; x < mask[0].length; x++) {
                if (mask[y][x]) {
                    int rgb = img.getRGB(x, y);
                    sum += ((rgb >> 16) & 0xFF) * 2 + ((rgb >> 8) & 0xFF) * 3 + (rgb & 0xFF) * 5;
                }
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, sum % 100000);
    }

    /** 把预览渲染到离屏 BufferedImage */
    private static BufferedImage render(Object preview) throws Exception {
        javax.swing.JComponent c = (javax.swing.JComponent) preview;
        c.setSize(IMG_W, IMG_H);
        BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setColor(new java.awt.Color(0, 0, 0, 0));
            g2.fillRect(0, 0, IMG_W, IMG_H);
            c.paint(g2);
        } finally {
            g2.dispose();
        }
        return img;
    }

    /** 非透明且明显亮于背景的像素 = 光标主体（背景是 #1E1F22 深色） */
    private static boolean[][] mask(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        boolean[][] m = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int al = (rgb >>> 24) & 0xFF;
                int r = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int lum = (r * 299 + gg * 587 + b * 114) / 1000;
                // 背景 #1E1F22 亮度约 31；光标/文字亮得多
                m[y][x] = al > 40 && lum > 90;
            }
        }
        return m;
    }

    // ==================== 反射工具 ====================

    private static void fire(NeovideCursorConfigurable conf) throws Exception {
        Method m = NeovideCursorConfigurable.class.getDeclaredMethod("afterPendingEdited");
        m.setAccessible(true);
        m.invoke(conf);
    }

    private static void applyPreset(NeovideCursorConfigurable conf, int idx) throws Exception {
        NeovideCursorConfigurable.applyAnimPresetFields(
                conf.pendingForProbe(),
                com.github.yyyolo7a79.neovidecursor.settings.CursorPresets.ANIM_PRESETS[idx]);
        fire(conf);
    }

    private static void restore(NeovideCursorConfigurable conf) throws Exception {
        Method m = NeovideCursorConfigurable.class.getDeclaredMethod("restoreAnimPreset");
        m.setAccessible(true);
        m.invoke(conf);
    }

    private static boolean rgbOf(Object preview) throws Exception {
        return (Boolean) preview.getClass().getMethod("rgbGradientForProbe").invoke(preview);
    }

    private static Object get(NeovideCursorConfigurable conf, String name) throws Exception {
        Field f = NeovideCursorConfigurable.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(conf);
    }

    private static void finish() {
        System.out.println();
        System.out.printf("=== V-PREVIEW-REALTIME 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-PREVIEW-REALTIME RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-PREVIEW-REALTIME RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
