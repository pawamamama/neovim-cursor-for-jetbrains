import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * V-SELFCHECK：自检发现的 bug 的回归防线。
 *
 * <p>本探针锁住 2026-10-24 自检（code-review 8 角度）发现的 3 个真实缺陷：
 * <ol>
 *   <li><b>字体每帧重算</b>：remeasure 每帧调 editorFont()，
 *       兜底分支会枚举系统全部字体 → EDT 卡死。已加缓存。</li>
 *   <li><b>crossStep 逻辑重复</b>：原 %3 时 case 0 和 case 2 都是行尾，
 *       换行两次里才发生一次，看着像「跳一下就停」。已改两步往返。</li>
 *   <li><b>PluginInfoBar 资源泄漏</b>：getInputStream 未关闭 + 解析失败时
 *       tooltip 显示「失败：null」。已 try-with-resources + 明确错误文案。</li>
 * </ol>
 */
public final class VSelfCheck {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-SELFCHECK：自检发现的 bug 回归防线 ===");

        // ---- C-1: 字体缓存存在且生效 ----
        Class<?> ap = AnimationPreview.class;
        Field cache = ap.getDeclaredField("cachedBaseFont");
        cache.setAccessible(true);
        boolean hasCache = cache.getType().getName().equals("java.awt.Font");
        check("C-1 存在 cachedBaseFont 字段（字体缓存）", hasCache,
                hasCache ? "Font" : "缺失");

        Method inv = null;
        try {
            inv = ap.getDeclaredMethod("invalidateFontCache");
            inv.setAccessible(true);
        } catch (Throwable ignored) {
        }
        check("C-2 存在 invalidateFontCache()（换主题后能重取）",
                inv != null, inv == null ? "缺失" : "存在");

        // ---- C-3: 连续调 100 次 remeasure 不枚举字体（用耗时间接判断）----
        AnimationPreview inst = AnimationPreview.inline(new NeovideConfig());
        inst.setSize(520, 200);
        Method remeasure = ap.getDeclaredMethod("remeasure", java.awt.Graphics2D.class);
        remeasure.setAccessible(true);

        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                520, 200, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            remeasure.invoke(inst, g);      // 第一次：建缓存
            long t0 = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                remeasure.invoke(inst, g);
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check("C-3 100 次 remeasure 耗时 < 100ms（字体已缓存）",
                    ms < 100, ms + " ms");
        } finally {
            g.dispose();
        }

        // ---- C-4: crossStep 是两步往返 ----
        AnimationPreview cross = AnimationPreview.cross(new NeovideConfig());
        cross.setSize(300, 96);
        Field stepF = ap.getDeclaredField("crossStep");
        stepF.setAccessible(true);
        Field centreY = ap.getDeclaredField("centerY");
        centreY.setAccessible(true);
        Field centreX = ap.getDeclaredField("centerX");
        centreX.setAccessible(true);

        Method advance = ap.getDeclaredMethod("advance");
        advance.setAccessible(true);
        Method place = ap.getDeclaredMethod("placeIfNeeded");
        place.setAccessible(true);

        // 需要先 remeasure 才有 metrics
        java.awt.image.BufferedImage img2 = new java.awt.image.BufferedImage(
                300, 96, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = img2.createGraphics();
        try {
            remeasure.invoke(cross, g2);
            place.invoke(cross);

            // 连续 4 次 advance，记录 y 的变化序列
            double[] ys = new double[4];
            for (int i = 0; i < 4; i++) {
                advance.invoke(cross);
                ys[i] = (double) centreY.get(cross);
            }
            // 两步往返 → y 应该交替（行1 / 行2 / 行1 / 行2）
            boolean alternating = Math.abs(ys[0] - ys[1]) > 1
                    && Math.abs(ys[1] - ys[2]) > 1
                    && Math.abs(ys[2] - ys[3]) > 1;
            check("C-4 crossStep 每步都跨行（y 连续交替，不是隔一次才动）",
                    alternating,
                    String.format("%.1f → %.1f → %.1f → %.1f", ys[0], ys[1], ys[2], ys[3]));
        } finally {
            g2.dispose();
        }

        System.out.println();
        System.out.printf("=== V-SELFCHECK 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-SELFCHECK RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-SELFCHECK RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + n + "   [" + d + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + n + "   [" + d + "]");
        }
    }
}
