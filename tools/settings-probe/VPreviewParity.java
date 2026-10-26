import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

/**
 * V-PREVIEW-PARITY：预览与生产必须收到同一组参数。
 *
 * <p>锁两个用户报过的 bug：
 *   1. 斜度拉到 0，预览还是斜的（deriveTrailFactors 漏传 slope）
 *   2. 选「彩虹（动态）」，预览完全不变（没推 cursorRgbWash）
 */
public final class VPreviewParity {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        V0Env.install();
        System.out.println("=== V-PREVIEW-PARITY ===");

        // ---------- 1. 斜度进预览 ----------
        NeovideCursorSettings.State s = V0Env.svc.getState();
        s.stretchRatioK = 4.5f;
        s.typingSmoothness = 0f;
        s.slopeTicks = 0;

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        // 必须 reset 一次：构建面板时 previewConfig 还是构造默认值，
        // refreshPreviews 要等 reset() 才跑第一遍（那正是真实打开设置页的时序）。
        conf.reset();

        float[] atZero = previewFactors(conf);
        check("1 斜度 0 时预览的 trailFactors 是纯矩形（同侧两角相等）",
                atZero != null && Math.abs(atZero[0] - atZero[1]) < 0.02f,
                atZero == null ? "null" : fmt(atZero));

        s.slopeTicks = 100;
        conf.reset();
        float[] at100 = previewFactors(conf);
        check("2 斜度 100 时预览的 trailFactors 完全斜（同侧两角分离）",
                at100 != null && Math.abs(at100[0] - at100[1]) > 0.15f,
                at100 == null ? "null" : fmt(at100));

        check("3 斜度 0 与 100 的系数确实不同（证明 preview 真的读了 slope）",
                atZero != null && at100 != null
                        && Math.abs(atZero[0] - at100[0]) > 0.05f,
                (atZero == null || at100 == null) ? "null"
                        : "0档=" + fmt(atZero) + " 100档=" + fmt(at100));

        // ---------- 2. 彩虹进预览 ----------
        CursorPresets.ColorPreset rainbow = null;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p.rgbWash()) rainbow = p;
        }
        check("4 找到彩虹预设", rainbow != null, rainbow == null ? "无" : rainbow.name());

        if (rainbow != null) {
            s.tailColor = rainbow.tailColor();
            s.shadowColor = rainbow.shadowColor();
            s.rgbGradient = true;
            conf.reset();
            boolean isRainbow = previewRainbow(conf);
            // 面板的 pending 已经和 state 同步，isRainbowSelected 基于 pending
            check("5 选彩虹后预览收到 cursorRgbWash（彩虹能到预览）",
                    isRainbow, "预览 rgbWash=" + isRainbow);
        }

        System.out.println();
        System.out.printf("=== PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("RESULT: FAILED"); System.exit(1); }
        System.out.println("RESULT: ALL PASS");
    }

    /** 反射取面板的 previewConfig.trailFactors */
    static float[] previewFactors(NeovideCursorConfigurable conf) {
        try {
            java.lang.reflect.Field f =
                    NeovideCursorConfigurable.class.getDeclaredField("previewConfig");
            f.setAccessible(true);
            Object cfg = f.get(conf);
            java.lang.reflect.Field tf = cfg.getClass().getField("trailFactors");
            return (float[]) tf.get(cfg);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射取面板的 isRainbowSelected 结果 */
    static boolean previewRainbow(NeovideCursorConfigurable conf) {
        try {
            java.lang.reflect.Method m =
                    NeovideCursorConfigurable.class.getDeclaredMethod("isRainbowSelected");
            m.setAccessible(true);
            return (Boolean) m.invoke(conf);
        } catch (Throwable t) {
            return false;
        }
    }

    static String fmt(float[] f) {
        if (f == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < f.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.2f", f[i]));
        }
        return sb.append("]").toString();
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}