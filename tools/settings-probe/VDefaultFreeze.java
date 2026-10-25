import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V-DEFAULT-FREEZE：默认值冻结 —— 发布安全的第二道防线。
 *
 * <p><b>为什么必须有它</b>：升级时「不掉用户配置」只有一条铁律 ——
 * <b>已经有默认值语义的字段，其默认值永远不能改</b>。
 *
 * <p>原因：用户的 XML 是旧版本写的。如果 V2 把 {@code tailOpacity} 的默认值
 * 从 1.0 改成 0.9，那么所有「没动过这个字段」的用户，升级后都会静默变成 0.9 ——
 * 他们从没调过这个滑块，却看到了不同的光标。
 *
 * <p>这不是理论风险。0.9.67 就发生过类似的事：把 {@code tailOpacity} 从
 * 「不在预设域」改成「在预设域」，结果切预设会覆盖它，用户报「光标半透明」。
 * 区别只在于那次是预设覆盖，这次是默认值本身。
 *
 * <p><b>本探针怎么工作</b>：把每个字段的默认值写成黄金常量。
 * 任何人改了 {@code State} 的字段声明初值，这里立刻红。
 *
 * <p><b>确实需要改默认值时怎么办</b>：
 * <ol>
 *   <li>先把 {@code stateVersion} 加一；</li>
 *   <li>在 {@code loadState} 里写迁移：<b>仅当用户值等于旧默认值</b>才改成新默认值；</li>
 *   <li>再更新本探针的黄金值（这是一次显式、有记录的决策，不是无声漂移）。</li>
 * </ol>
 *
 * <p>详细流程见 {@code docs/RELEASE-SAFETY.md}。
 */
public final class VDefaultFreeze {
    private static int pass = 0, fail = 0;

    /** 字段名 → 冻结的默认值（字符串形式，便于统一比较） */
    private static final Map<String, String> GOLDEN = new LinkedHashMap<>();

    static {
        GOLDEN.put("stateVersion", "0");
        GOLDEN.put("enabled", "true");
        GOLDEN.put("diagLogging", "false");
        GOLDEN.put("multiCursorNoGlow", "false");
        GOLDEN.put("cursorWidthPx", "2");
        GOLDEN.put("cursorHeightRatio", "1.0");
        GOLDEN.put("rgbGradient", "false");
        GOLDEN.put("trailMode", "SPRING");
        GOLDEN.put("hideNativeCaret", "true");
        GOLDEN.put("alwaysHighFps", "true");
        // 目标帧率（0.9.76 新增）。只允许 125/144 —— 实测 165/240 达不到，
        // 给做不到的数字让用户选是骗人（见 docs/PERFORMANCE-FPS.md）。
        GOLDEN.put("targetFps", "125");
        // 斜度（0.9.80 起进预设域）。默认 100 = 完全斜 = 对齐上游。
        // 【默认值变更记录】
        //   0.9.79 → -1（跟随 k）
        //   0.9.80 →  82（当时公式下 82 == 上游）
        //   0.9.81 → 100（语义改为「0=纯矩形 / 100=完全斜」，100 才等于上游）
        GOLDEN.put("slopeTicks", "100");
        GOLDEN.put("overlayFadeOut", "true");
        GOLDEN.put("cursorDisappearDelay", "50");
        GOLDEN.put("cursorFadeOutDuration", "0.075");
        GOLDEN.put("tailColor", "#FFC0CB");
        GOLDEN.put("tailOpacity", "1.0");
        GOLDEN.put("useShadow", "true");
        GOLDEN.put("shadowColor", "#FFC0CB");
        GOLDEN.put("glowOpacity", "0.55");
        GOLDEN.put("glowLayers", "8");
        GOLDEN.put("shadowBlurFactor", "0.2");
        GOLDEN.put("trailSize", "1.0");
        GOLDEN.put("stretchRatioK", "4.5");
        GOLDEN.put("animationLength", "0.1");
        GOLDEN.put("shortAnimationLength", "0.11");
        GOLDEN.put("typingSmoothness", "0.0");
        GOLDEN.put("animPresetBase", "0");
        GOLDEN.put("customAnimPresets", "");
        GOLDEN.put("animCustomName", "");
    }

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-DEFAULT-FREEZE：State 默认值冻结 ===");

        NeovideCursorSettings.State def = new NeovideCursorSettings.State();

        // F-1：每个冻结字段的实际默认值必须与黄金值一致
        List<String> drifted = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : GOLDEN.entrySet()) {
            String name = e.getKey();
            String actual;
            try {
                actual = read(def, name);
            } catch (NoSuchFieldException nsf) {
                missing.add(name);
                continue;
            }
            if (!e.getValue().equals(actual)) {
                drifted.add(name + ": 冻结=" + e.getValue() + " 实际=" + actual);
            }
        }
        check("F-1 冻结的 " + GOLDEN.size() + " 个字段默认值全部未漂移",
                drifted.isEmpty() && missing.isEmpty(),
                drifted.isEmpty() && missing.isEmpty()
                        ? "全部一致"
                        : (drifted.isEmpty() ? "" : "漂移=" + drifted)
                        + (missing.isEmpty() ? "" : " 缺失字段=" + missing));

        // F-2：不能有新增字段绕过冻结（新增字段必须显式加进 GOLDEN）
        List<String> unfrozen = new ArrayList<>();
        for (Field f : NeovideCursorSettings.State.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!GOLDEN.containsKey(f.getName())) {
                unfrozen.add(f.getName());
            }
        }
        check("F-2 新增字段必须显式登记默认值（防止绕过冻结）",
                unfrozen.isEmpty(),
                unfrozen.isEmpty() ? "全部已登记"
                        : "未登记=" + unfrozen + "  ← 请加进 GOLDEN 并确认默认值是有意的");

        // F-3：出厂观感必须符合用户要求
        // 用户原话：「第一次安装一定是基础 + 粉色 光标2px 顶行100%
        //          总开关除了多光标关闭辉光 都开 其他持久化 比如平滑关闭」
        boolean factoryOk = def.enabled && def.hideNativeCaret && def.alwaysHighFps
                && !def.multiCursorNoGlow && !def.rgbGradient
                && def.cursorWidthPx == 2
                && Math.abs(def.cursorHeightRatio - 1.0f) < 1e-6f
                && Math.abs(def.typingSmoothness) < 1e-6f
                && "#FFC0CB".equals(def.tailColor)
                && def.animPresetBase == 0;
        check("F-3 出厂观感符合用户要求（基础/粉色/2px/顶格/平滑关/总开关开）",
                factoryOk,
                "enabled=" + def.enabled + " hideNative=" + def.hideNativeCaret
                        + " highFps=" + def.alwaysHighFps
                        + " noGlow=" + def.multiCursorNoGlow
                        + " rgb=" + def.rgbGradient
                        + " w=" + def.cursorWidthPx
                        + " h=" + def.cursorHeightRatio
                        + " smooth=" + def.typingSmoothness
                        + " tail=" + def.tailColor
                        + " base=" + def.animPresetBase);

        // F-4：State 默认值必须与 CONFIG 默认值一致（两套配置不能打架）
        NeovideConfig cfg = new NeovideConfig();
        List<String> mismatch = new ArrayList<>();
        for (String name : new String[]{"enabled", "hideNativeCaret", "alwaysHighFps",
                "overlayFadeOut", "useShadow", "tailColor", "shadowColor",
                "tailOpacity", "glowOpacity", "glowLayers", "shadowBlurFactor",
                "trailSize", "animationLength", "shortAnimationLength",
                "cursorDisappearDelay", "cursorFadeOutDuration"}) {
            String s = read(def, name);
            String c = readCfg(cfg, name);
            if (c != null && !s.equals(c)) {
                mismatch.add(name + ": State=" + s + " CONFIG=" + c);
            }
        }
        check("F-4 State 与 CONFIG 的共有字段默认值一致",
                mismatch.isEmpty(),
                mismatch.isEmpty() ? "全部一致" : "不一致=" + mismatch);

        // F-5：出厂默认必须是「基础」预设的值（否则首次安装看到的不叫基础）
        com.github.yyyolo7a79.neovidecursor.settings.CursorPresets.AnimPreset base =
                com.github.yyyolo7a79.neovidecursor.settings.CursorPresets
                        .ANIM_PRESETS[com.github.yyyolo7a79.neovidecursor.settings
                                .CursorPresets.ANIM_PRESET_DEFAULT_INDEX];
        boolean baseMatch = Math.abs(def.animationLength - base.animationLength()) < 1e-4f
                && Math.abs(def.shortAnimationLength - base.shortAnimationLength()) < 1e-4f
                && Math.abs(def.trailSize - base.trailSize()) < 1e-4f
                && def.glowLayers == base.glowLayers()
                && Math.abs(def.shadowBlurFactor - base.shadowBlurFactor()) < 1e-4f
                && Math.abs(def.glowOpacity - base.glowOpacity()) < 1e-4f
                && def.useShadow == base.useShadow()
                && def.overlayFadeOut == base.overlayFadeOut()
                && Math.abs(def.stretchRatioK - base.stretchRatioK()) < 1e-4f
                && Math.abs(def.tailOpacity - base.tailOpacity()) < 1e-4f
                && def.cursorDisappearDelay == base.cursorDisappearDelay()
                && Math.abs(def.cursorFadeOutDuration - base.cursorFadeOutDuration()) < 1e-4f
                && Math.abs(def.typingSmoothness - base.typingSmoothness()) < 1e-4f;
        check("F-5 出厂默认 == 「基础」预设（首次安装看到的就是基础）",
                baseMatch,
                "anim=" + def.animationLength + "/" + base.animationLength()
                        + " alpha=" + def.tailOpacity + "/" + base.tailOpacity()
                        + " layers=" + def.glowLayers + "/" + base.glowLayers()
                        + " k=" + def.stretchRatioK + "/" + base.stretchRatioK());

        System.out.println();
        System.out.printf("=== V-DEFAULT-FREEZE 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-DEFAULT-FREEZE RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-DEFAULT-FREEZE RESULT: ALL PASS");
    }

    /** 读 State 字段并归一成字符串 */
    private static String read(Object o, String name) throws Exception {
        Field f = o.getClass().getField(name);
        return norm(f.get(o));
    }

    /** 读 CONFIG 字段；不存在返回 null */
    private static String readCfg(Object o, String name) {
        try {
            Field f = o.getClass().getField(name);
            return norm(f.get(o));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 归一成可比较的字符串。
     *
     * <p><b>浮点必须统一格式</b>：{@code 1.0f} 用 {@code BigDecimal.stripTrailingZeros}
     * 会变成 {@code "1"}，而黄金值写的是 {@code "1.0"} —— 数值相等但字符串不等，
     * 探针会误报漂移（第一版就这样，4 个字段假红）。
     *
     * <p>现在统一：浮点一律保留至少一位小数、去掉多余尾零，
     * 即 {@code 1.0f → "1.0"}、{@code 0.20f → "0.2"}、{@code 0.075f → "0.075"}。
     */
    private static String norm(Object v) {
        if (v instanceof Float) {
            // 【必须用 Float.toString，不能转 double】
            //
            // 0.1f 的二进制表示转成 double 后是 0.10000000149011612 ——
            // 数值上"相等"但字符串完全不同，探针会误报漂移（第二版就这样，5 个假红）。
            // Float.toString 会给出最短的可往返表示 "0.1"，这才是我们要比的东西。
            // Float.toString 给出最短可往返表示（"0.1" 而不是 0.10000000149...），
            // 再用 BigDecimal 去掉多余尾零（"0.50" → "0.5"）。
            String raw = Float.toString((Float) v);
            String s = new java.math.BigDecimal(raw).stripTrailingZeros().toPlainString();
            if (s.indexOf('.') < 0) {
                s = s + ".0";
            }
            return s;
        }
        if (v instanceof Double) {
            String s = Double.toString((Double) v);
            if (s.endsWith(".0")) {
                return s;
            }
            if (s.indexOf('.') < 0) {
                return s + ".0";
            }
            return s;
        }
        if (v instanceof Enum) {
            return ((Enum<?>) v).name();
        }
        return String.valueOf(v);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}