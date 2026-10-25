import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/**
 * 诊断：XML 只有部分字段时，loadState 会不会把缺失字段写成"零值"。
 *
 * 模拟用户那次事故：XML 里只剩 stateVersion + customAnimPresets 两个字段，
 * 其他 24 个字段全缺。看 loadState 后 state 变成什么。
 */
public final class ZZPartialXml {
    public static void main(String[] a) throws Exception {
        V0Env.install();

        NeovideCursorSettings svc = new NeovideCursorSettings();
        System.out.println("=== 初始（构造默认值）===");
        dump(svc.getState());

        // 模拟平台反序列化：XML 里只有 2 个字段 → from 对象其余字段是 Java 零值
        NeovideCursorSettings.State from = new NeovideCursorSettings.State();
        // 平台会 new 一个 State 然后只填 XML 里有的字段。
        // 但 State 的字段有声明初值（= 1.0f 等），所以 new 出来的就是默认值。
        // 真正的风险场景：平台用「无参构造 + 反射 set」，缺失字段保留声明初值。
        // 那 loadState 里 from.xxx 读到的就是默认值，不是零值 —— 这样反而安全。
        from.stateVersion = 1;
        from.customAnimPresets = "基础 副本\u241FtailOpacity=0.05";

        svc.loadState(from);
        System.out.println();
        System.out.println("=== loadState(from) 之后 ===");
        dump(svc.getState());

        System.exit(0);
    }

    private static void dump(NeovideCursorSettings.State s) {
        System.out.println("  enabled            = " + s.enabled);
        System.out.println("  hideNativeCaret    = " + s.hideNativeCaret);
        System.out.println("  alwaysHighFps      = " + s.alwaysHighFps);
        System.out.println("  overlayFadeOut     = " + s.overlayFadeOut);
        System.out.println("  useShadow          = " + s.useShadow);
        System.out.println("  tailOpacity        = " + s.tailOpacity);
        System.out.println("  glowOpacity        = " + s.glowOpacity);
        System.out.println("  glowLayers         = " + s.glowLayers);
        System.out.println("  tailColor          = " + s.tailColor);
        System.out.println("  animationLength    = " + s.animationLength);
        System.out.println("  shortAnimationLength=" + s.shortAnimationLength);
        System.out.println("  stretchRatioK      = " + s.stretchRatioK);
        System.out.println("  typingSmoothness   = " + s.typingSmoothness);
        System.out.println("  cursorWidthPx      = " + s.cursorWidthPx);
        System.out.println("  cursorHeightRatio  = " + s.cursorHeightRatio);
        System.out.println("  rgbGradient        = " + s.rgbGradient);
        System.out.println("  animPresetBase     = " + s.animPresetBase);
    }
}
