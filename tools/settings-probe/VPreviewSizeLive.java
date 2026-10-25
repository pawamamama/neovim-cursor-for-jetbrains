import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.lang.reflect.Field;

/**
 * V-PREVIEW-SIZE-LIVE：光标尺寸必须实时反映到预览窗。
 *
 * <p><b>锁什么</b>：用户在「光标（尺寸 / 颜色效果）」组拖「光标宽度」或
 * 「光标高度」时，预览窗必须跟着变 —— 否则它不叫预览。
 *
 * <p><b>历史</b>：0.9.66 之前预览自己算尺寸（cursorW = fontSize/16、
 * cursorH = lineH×0.8），<b>完全不看用户设置</b>。拖滑块预览毫无反应。
 *
 * <p>注：与已有的 {@code VPreviewSize}（量窗口尺寸与 DPI）不重复 ——
 * 那个量「窗口多大」，这个验「尺寸设置是否实时传导」。
 */
public final class VPreviewSizeLive {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-PREVIEW-SIZE-LIVE：光标尺寸实时预览 ===");

        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();

        Object inline = get(conf, "inlinePreview");
        Object cross = get(conf, "crossPreview");

        check("L-1 面板建好后存在单行预览窗", inline != null, cls(inline));
        check("L-2 面板建好后存在跨行预览窗", cross != null, cls(cross));
        if (inline == null || cross == null) {
            finish();
            return;
        }

        int pw0 = conf.pendingForProbe().cursorWidthPx;
        float ph0 = conf.pendingForProbe().cursorHeightRatio;
        int iw0 = userW(inline);
        float ih0 = userH(inline);
        check("L-3 初始宽度同步（预览 == pending）", iw0 == pw0,
                "预览=" + iw0 + " pending=" + pw0);
        check("L-4 初始高度比例同步", Math.abs(ih0 - ph0) < 1e-4f,
                "预览=" + ih0 + " pending=" + ph0);

        // 改宽度 → 触发 afterPendingEdited（模拟用户拖滑块）
        int newW = (pw0 == 5) ? 1 : 5;
        conf.pendingForProbe().cursorWidthPx = newW;
        fire(conf);
        int iw1 = userW(inline);
        int cw1 = userW(cross);
        check("L-5 改宽度后单行预览立即跟上", iw1 == newW, "预览=" + iw1 + " 期望=" + newW);
        check("L-6 改宽度后跨行预览也跟着变", cw1 == newW, "预览=" + cw1 + " 期望=" + newW);

        float newH = (Math.abs(ph0 - 0.6f) < 1e-4f) ? 1.0f : 0.6f;
        conf.pendingForProbe().cursorHeightRatio = newH;
        fire(conf);
        float ih1 = userH(inline);
        check("L-7 改高度比例后预览立即跟上", Math.abs(ih1 - newH) < 1e-4f,
                "预览=" + ih1 + " 期望=" + newH);

        check("L-8 宽度确实变化过（不是巧合相等）", iw0 != iw1, iw0 + " → " + iw1);
        check("L-9 高度确实变化过", Math.abs(ih0 - ih1) > 1e-4f, ih0 + " → " + ih1);

        // 未设置时应是 -1（表示沿用自适应），这一条锁「默认不干预」
        Object fresh = newFreshPreview();
        check("L-10 新建预览默认 -1（不干预自动尺寸）",
                userW(fresh) == -1 && Math.abs(userH(fresh) + 1f) < 1e-4f,
                "width=" + userW(fresh) + " heightRatio=" + userH(fresh));

        finish();
    }

    private static Object newFreshPreview() throws Exception {
        Class<?> c = Class.forName(
                "com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview");
        java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor(
                com.github.yyyolo7a79.neovidecursor.core.NeovideConfig.class,
                Class.forName("com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview$Mode"));
        ctor.setAccessible(true);
        Class<?> modeCls = Class.forName(
                "com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview$Mode");
        Object inlineMode = java.lang.Enum.valueOf((Class) modeCls, "INLINE");
        return ctor.newInstance(new com.github.yyyolo7a79.neovidecursor.core.NeovideConfig(), inlineMode);
    }

    private static void finish() {
        System.out.println();
        System.out.printf("=== V-PREVIEW-SIZE-LIVE 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-PREVIEW-SIZE-LIVE RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-PREVIEW-SIZE-LIVE RESULT: ALL PASS");
    }

    private static void fire(NeovideCursorConfigurable conf) throws Exception {
        java.lang.reflect.Method m =
                NeovideCursorConfigurable.class.getDeclaredMethod("afterPendingEdited");
        m.setAccessible(true);
        m.invoke(conf);
    }

    private static Object get(NeovideCursorConfigurable conf, String name) throws Exception {
        Field f = NeovideCursorConfigurable.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(conf);
    }

    private static int userW(Object p) throws Exception {
        return (Integer) p.getClass().getMethod("userCursorWidthForProbe").invoke(p);
    }

    private static float userH(Object p) throws Exception {
        return (Float) p.getClass().getMethod("userCursorHeightRatioForProbe").invoke(p);
    }

    private static String cls(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
