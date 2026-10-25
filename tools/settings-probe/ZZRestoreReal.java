import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 临时诊断：模拟用户当前的真实状态，点「恢复为默认值」，看 tailOpacity 是否回来。
 *
 * 真实状态（从 NeovideCursorSettings.xml 读到）：
 *   tailOpacity = 0.05, cursorWidthPx = 5, rgbGradient = true
 *   当前选中「基础 副本」，animPresetBase 未知（需从 XML 确认）
 */
public final class ZZRestoreReal {
    public static void main(String[] a) throws Exception {
        V0Env.install();

        NeovideCursorSettings.State st = new NeovideCursorSettings.State();
        st.tailOpacity = 0.05f;
        st.cursorWidthPx = 5;
        st.cursorHeightRatio = 1.0f;
        st.rgbGradient = true;
        st.animCustomName = "基础 副本";
        st.customAnimPresets = "基础 副本\u241F"
                + "tailOpacity=0.05;animationLength=0.1;shortAnimationLength=0.11;"
                + "glowLayers=8;shadowBlurFactor=0.2;glowOpacity=0.55;trailSize=1.0;"
                + "stretchRatioK=4.5;typingSmoothness=0.0;cursorDisappearDelay=50;"
                + "cursorFadeOutDuration=0.075;useShadow=1;overlayFadeOut=1";

        System.out.println("点击前： tailOpacity=" + st.tailOpacity
                + "  animCustomName=\"" + st.animCustomName + "\""
                + "  animPresetBase=" + st.animPresetBase);

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        Field pf = NeovideCursorConfigurable.class.getDeclaredField("pending");
        pf.setAccessible(true);
        pf.set(conf, st);
        Field bf = NeovideCursorConfigurable.class.getDeclaredField("animBaseIdx");
        bf.setAccessible(true);
        bf.setInt(conf, CursorPresets.clampAnimPresetIndex(st.animPresetBase));

        Method m = NeovideCursorConfigurable.class.getDeclaredMethod("restoreAnimPreset");
        m.setAccessible(true);
        m.invoke(conf);

        System.out.println("点击后： tailOpacity=" + st.tailOpacity
                + "  animCustomName=\"" + st.animCustomName + "\""
                + "  animPresetBase=" + st.animPresetBase
                + "  cursorWidthPx=" + st.cursorWidthPx
                + "  rgbGradient=" + st.rgbGradient);
        System.out.println(st.tailOpacity > 0.99f
                ? "结果：tailOpacity 恢复成功"
                : "结果：tailOpacity 仍是 " + st.tailOpacity + " —— 恢复失败");
        System.exit(st.tailOpacity > 0.99f ? 0 : 1);
    }
}
