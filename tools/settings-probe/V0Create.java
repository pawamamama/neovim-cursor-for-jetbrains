import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JComponent;

/** verifier 自建：只验证 createComponent() 能不能在离线桩下建起来（它多走一步 DialogPanel.registerValidators）。 */
public final class V0Create {
    public static void main(String[] args) throws Exception {
        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        JComponent c = conf.createComponent();
        System.out.println("createComponent OK = " + (c != null) + "  class=" + c.getClass().getName());
    }
}
