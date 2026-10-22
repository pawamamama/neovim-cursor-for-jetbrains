import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

public final class VSepUi {

    public static void main(String[] args) throws Exception {
        System.out.println("=== V-SEP-UI：面板层非法名路径可否驱动（EDT）===");
        V0Env.install();
        final Throwable[] err = new Throwable[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                NeovideCursorSettings.State st = V0Env.svc.getState();
                st.customAnimPresets = "";
                st.animCustomName = "";
                NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
                VUi.build(conf);
                conf.reset();
                javax.swing.JButton add = conf.animNewButtonForProbe();
                System.out.println("新建按钮=" + (add == null ? "null" : add.getText()) + " enabled=" + (add != null && add.isEnabled()));
                if (add != null) {
                    add.doClick();
                    System.out.println("doClick 返回，未抛异常");
                    System.out.println("点后 animCustomName=[" + conf.pendingForProbe().animCustomName + "]");
                    System.out.println("点后表条数=" + CustomPresets.parse(conf.pendingForProbe().customAnimPresets).size());
                }
            } catch (Throwable t) {
                err[0] = t;
            }
        });
        if (err[0] != null) {
            System.out.println("抛异常：" + err[0].getClass().getName() + " / " + err[0].getMessage());
        }
        System.out.flush();
        System.exit(0);
    }
}
