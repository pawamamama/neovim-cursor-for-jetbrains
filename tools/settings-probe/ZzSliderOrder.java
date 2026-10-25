import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import javax.swing.*;
import java.awt.*;

/** 打印面板里滑块的【真实渲染顺序】，用于核对 VUi.KEYS。 */
public final class ZzSliderOrder {
    public static void main(String[] a) {
        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        System.out.println("滑块真实顺序（共 " + ui.sliders.size() + " 个）：");
        for (int i = 0; i < ui.sliders.size(); i++) {
            JSlider s = ui.sliders.get(i);
            String key = i < VUi.KEYS.length ? VUi.KEYS[i] : "?超出表长";
            int[] spec = VUi.spec(i);
            String specStr = spec == null ? "表缺失" : ("[" + spec[0] + "," + spec[1] + "] step=" + spec[2]);
            System.out.printf("  [%2d] 面板量程=[%d,%d]  KEYS[%d]=%-24s SPEC=%s%n",
                    i, s.getMinimum(), s.getMaximum(), i, key, specStr);
        }
        System.exit(0);
    }
}