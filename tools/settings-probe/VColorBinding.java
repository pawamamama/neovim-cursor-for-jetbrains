import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.ui.ColorPanel;

import javax.swing.JCheckBox;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * V-COLOR-BINDING：ColorPanel 三向同步必须「登记一次，三个方向全有」。
 *
 * <p><b>锁什么</b>：{@code ColorPanel} 不是 DialogPanel 的绑定控件，平台不追踪。
 * 每个颜色面板都要手工覆盖 reset / apply / isModified 三个方向，漏一个就静默失效：
 * <ul>
 *   <li>漏 reset → 打开面板看到默认色</li>
 *   <li>漏 apply → 选了色点应用没保存</li>
 *   <li>漏 isModified → 改了色应用按钮不亮</li>
 * </ul>
 *
 * <p>0.9.71 引入 {@code ColorPanelBinding} 把三向收进一处，本探针锁住：
 * <ol>
 *   <li>登记数 == 面板上实际 ColorPanel 数（漏登记就红）</li>
 *   <li>三个方向都真的工作（pull 写控件 / push 写 State / differs 判脏）</li>
 * </ol>
 */
public final class VColorBinding {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-COLOR-BINDING：ColorPanel 三向同步 ===");
        V0Env.install();

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        conf.reset();

        // C-1：登记数必须等于面板上实际的 ColorPanel 数
        int registered = conf.colorBindingCountForProbe();
        int actual = countColorPanels(ui.panel.component);
        check("C-1 登记的绑定数 == 面板上 ColorPanel 数（漏登记会静默失效）",
                registered == actual,
                "登记=" + registered + " 实际=" + actual);

        // C-2：ColorPanel 数必须是 2（拖尾色 + 辉光色）
        check("C-2 面板上有 2 个 ColorPanel",
                actual == 2, "实际=" + actual);

        // C-3：pull（State → 控件）—— reset 后控件应显示 State 的颜色
        NeovideCursorSettings.State st = conf.pendingForProbe();
        st.tailColor = "#12AB34";
        st.shadowColor = "#ABCDEF";
        invoke(conf, "syncAllControlsFromPending");
        ColorPanel tail = (ColorPanel) get(conf, "tailColorPanel");
        ColorPanel shadow = (ColorPanel) get(conf, "shadowColorPanel");
        check("C-3 pull 方向：State → 控件（tailColor 被灌进面板）",
                tail != null && hexOf(tail).equalsIgnoreCase("#12AB34"),
                "面板=" + (tail == null ? "null" : hexOf(tail)));

        // C-4：push（控件 → State）
        if (tail != null) {
            tail.setSelectedColor(NeovideCursorConfigurable.parseHex("#654321"));
        }
        invoke(conf, "onColorPanelChanged");
        check("C-4 push 方向：控件 → State（选色后 pending.tailColor 变了）",
                "#654321".equalsIgnoreCase(conf.pendingForProbe().tailColor),
                "pending=" + conf.pendingForProbe().tailColor);

        // C-5：differs（判脏）—— 面板色与 State 不同时必须返回 true
        NeovideCursorSettings.State cur = new NeovideCursorSettings.State();
        cur.tailColor = "#000000";
        cur.shadowColor = "#FFFFFF";
        Method differs = NeovideCursorConfigurable.class
                .getDeclaredMethod("colorPanelModified", NeovideCursorSettings.State.class);
        differs.setAccessible(true);
        boolean dirty = (Boolean) differs.invoke(conf, cur);
        check("C-5 differs 方向：控件色 ≠ State 色时判脏", dirty,
                "面板 " + (tail == null ? "null" : hexOf(tail)) + " vs State #000000 → " + dirty);

        // C-6：反例 —— 两者一致时必须不脏（否则「应用」按钮永远亮着）
        NeovideCursorSettings.State same = new NeovideCursorSettings.State();
        same.tailColor = tail != null ? hexOf(tail) : "#000000";
        same.shadowColor = shadow != null ? hexOf(shadow) : "#FFFFFF";
        boolean notDirty = !((Boolean) differs.invoke(conf, same));
        check("C-6 反例：控件色 == State 色时不判脏（否则按钮永远亮）", notDirty,
                "→ " + !notDirty);

        // C-7：isModified 端到端 —— 只改颜色，按钮必须亮
        conf.reset();
        boolean before = conf.isModified();
        ColorPanel t2 = (ColorPanel) get(conf, "tailColorPanel");
        if (t2 != null) {
            t2.setSelectedColor(NeovideCursorConfigurable.parseHex("#FEDCBA"));
        }
        boolean after = conf.isModified();
        check("C-7 只改颜色时 isModified 从 false 变 true（应用按钮会亮）",
                !before && after, "before=" + before + " after=" + after);

        System.out.println();
        System.out.printf("=== V-COLOR-BINDING 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-COLOR-BINDING RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-COLOR-BINDING RESULT: ALL PASS");
    }

    /** 数面板里所有 ColorPanel（递归组件树） */
    private static int countColorPanels(Component c) {
        int n = 0;
        if (c instanceof ColorPanel) {
            n++;
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                n += countColorPanels(k);
            }
        }
        return n;
    }

    private static String hexOf(ColorPanel p) {
        return p.getSelectedColor() == null ? "null"
                : NeovideCursorConfigurable.toHex(p.getSelectedColor());
    }

    private static void invoke(NeovideCursorConfigurable conf, String name) throws Exception {
        Method m = NeovideCursorConfigurable.class.getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(conf);
    }

    private static Object get(NeovideCursorConfigurable conf, String name) throws Exception {
        java.lang.reflect.Field f = NeovideCursorConfigurable.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(conf);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}