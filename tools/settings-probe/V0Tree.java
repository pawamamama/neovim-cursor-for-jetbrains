import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.DialogPanel;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * verifier 自建（t4）：不依赖实现者探针钩子，直接把设置面板的组件树 dump 出来。
 * <p>走 createPanel() 而不是 createComponent()：后者会调 DialogPanel.registerValidators → ComponentValidator
 * → SingleAlarm → 平台共享 coroutine scope 服务，离线探针没有该服务。createPanel 足以拿到全部控件。
 */
public final class V0Tree {

    public static void main(String[] args) throws Exception {
        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        JComponent panel = conf.createPanel();
        System.out.println("=== panel class = " + panel.getClass().getName());
        dump(panel, 0, "");
        System.out.println("=== 结束 ===");
    }

    static void dump(Component c, int depth, String path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        String name = c.getClass().getName();
        if (name.contains(".")) name = name.substring(name.lastIndexOf('.') + 1);
        sb.append(name).append("  ").append(path);
        if (c instanceof javax.swing.AbstractButton) {
            sb.append("  text=\"").append(((javax.swing.AbstractButton) c).getText()).append("\"");
        }
        if (c instanceof javax.swing.JLabel) {
            String t = ((javax.swing.JLabel) c).getText();
            if (t == null) t = "";
            if (t.length() > 36) t = t.substring(0, 36) + "...";
            sb.append("  label=\"").append(t).append("\"");
        }
        if (c instanceof javax.swing.JSlider) {
            javax.swing.JSlider s = (javax.swing.JSlider) c;
            sb.append("  [").append(s.getMinimum()).append(",").append(s.getMaximum())
              .append("] val=").append(s.getValue())
              .append(" labels=").append(s.getLabelTable() == null ? 0 : s.getLabelTable().size())
              .append(" ls=").append(s.getChangeListeners().length);
        }
        if (c instanceof javax.swing.text.JTextComponent) {
            String t = ((javax.swing.text.JTextComponent) c).getText();
            sb.append("  tf=\"").append(t).append("\"");
        }
        System.out.println(sb);
        if (c instanceof Container) {
            Component[] kids = ((Container) c).getComponents();
            for (int i = 0; i < kids.length; i++) {
                dump(kids[i], depth + 1, path + "/" + i);
            }
        }
    }
}
