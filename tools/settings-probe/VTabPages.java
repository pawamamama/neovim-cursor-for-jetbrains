import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.openapi.ui.DialogPanel;

import javax.swing.JCheckBox;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * V-TAB-PAGES：6 个标签页是否都<b>登记为外层面板的集成面板</b>。
 *
 * <p><b>这个探针是为一个已实测的静默失效缺口写的（2026-10-29）。</b>
 * 设置面板分标签后，每个标签页是一个独立 {@code DialogPanel}，靠
 * {@code outer.registerIntegratedPanel(page)} 挂到外层。平台的
 * {@code apply() / reset() / isModified()} 第一段就是遍历 {@code integratedPanels}
 * 逐个委托（javap 实证，字段 #81），所以：
 *
 * <pre>
 *   漏一句 registerIntegratedPanel(page)
 *     → 该页控件改了【不会写进 pending】、点应用也没反应、
 *       重置不回灌、isModified() 也看不见它
 *     → 而且【不报错、不抛异常、界面一切正常】
 * </pre>
 *
 * <p><b>为什么必须单独写一条探针</b>：变异测试实证「别的探针抓不到」。
 * 2026-10-29 实测三种变异：
 * <ul>
 *   <li>删掉 {@code registerIntegratedPanel(tabDiag)}（标签6「调试」）→ 全量门禁
 *       <b>0 条红</b>。因为没有任何探针通过面板去改「诊断日志」/「性能诊断」，
 *       那页登记漏掉也不会被任何行为断言撞上；</li>
 *   <li>删掉 {@code registerIntegratedPanel(tabMaster)} → VDefects 1 条 + VerifyPanel 3 条红
 *       （标签1 的控件有行为覆盖）；</li>
 *   <li>交换两个标签页的组件插入顺序 → VSnap 12 + VPreset 8 + VHints 2 + VLabel 1 条红
 *       （控件下标契约有覆盖）。</li>
 * </ul>
 * 也就是说：**「登记」这件事本身没有任何覆盖** —— 漏了也只会在有人恰好去碰
 * 那一页的控件时才暴露。本探针把判据换成结构事实：标签页集合 ⊆ 集成面板集合，
 * 与页里放了什么控件、用户会不会去碰都无关。
 *
 * <p><b>本探针只看结构，不测行为</b>：行为（改了能不能生效）由 VReset / VDefects /
 * VerifyPanel / VerifyApplyConfig 那一批负责。这里只回答一个问题：
 * 「有没有页面被漏登记」。
 */
public final class VTabPages {

    private static int pass = 0;
    private static int fail = 0;

    /** 期望的标签名与顺序（必须与 createPanel() 里 tabs.addTab 的先后一致）。 */
    private static final String[] EXPECTED_TITLES = {
            "总开关", "颜色", "光标行手感", "列手感", "特殊", "调试"
    };

    /**
     * 每页的控件构成：{滑块, 复选框, 下拉, 色板}。
     *
     * <p>【为什么还要这一条】T-3 只比对**标题**，守不住「标题对、内容错」。
     * 2026-10-29 变异实测：把标签2 与标签3 的**组件**对调、标题跟着组件走
     * （{@code addTab("颜色", tabCursorLine)} / {@code addTab("光标行手感", tabColor)}），
     * T-1..T-7 全绿 —— 因为页都在、都登记了、都不空，只有「哪一页装了什么」错了。
     * 这类错位对用户就是「颜色页里全是滑块」，而控件总数（VAddSetting 的 14/9/4）也查不出来。
     *
     * <p>数字来源：任务书 §2 的标签分配表（用户逐项指定，不得自行调整）。
     * 以后真要挪控件，**这条会红，先改这里的数字并写清原因**，别绕开它。
     */
    private static final int[][] EXPECTED_COMPOSITION = {
            // 滑块, 复选框, 下拉, 色板
            {0, 4, 3, 0},   // 总开关：3 下拉（动画/颜色预设、目标帧率）+ 4 复选框
            {1, 1, 0, 2},   // 颜色：拖尾不透明度 + 光标渐变 + 两把色板
            {6, 0, 0, 0},   // 光标行手感
            {2, 0, 0, 0},   // 列手感
            {5, 2, 1, 0},   // 特殊：辉光 + 淡出 + 拖尾渲染模式
            {0, 2, 0, 0},   // 调试
    };

    public static void main(String[] args) {
        System.out.println("=== V-TAB-PAGES：6 个标签页是否都已登记为集成面板 ===");
        V0Env.install();

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        // 走 createPanel() 而不是 createComponent()：后者会 registerValidators →
        // ComponentValidator → JBPopupFactory，离线桩里没有（见 V0Tree 的同款注释）。
        DialogPanel outer = conf.createPanel();

        List<JTabbedPane> tabPanes = new ArrayList<>();
        collectTabbedPanes(outer, tabPanes);
        check("T-1 面板里有且只有一个 JBTabbedPane",
                tabPanes.size() == 1, "找到 " + tabPanes.size() + " 个");
        if (tabPanes.size() != 1) {
            summary();
            return;
        }
        JTabbedPane tabs = tabPanes.get(0);

        // ---- T-2 标签页数量 ----
        check("T-2 标签页数量 = " + EXPECTED_TITLES.length,
                tabs.getTabCount() == EXPECTED_TITLES.length,
                "实际 " + tabs.getTabCount() + " 个");

        // ---- T-3 标签名与顺序 ----
        StringBuilder actual = new StringBuilder();
        StringBuilder wrong = new StringBuilder();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            String title = tabs.getTitleAt(i);
            actual.append(title);
            if (i < tabs.getTabCount() - 1) {
                actual.append('/');
            }
            if (i < EXPECTED_TITLES.length && !EXPECTED_TITLES[i].equals(title)) {
                wrong.append("[").append(i).append("] 期望「").append(EXPECTED_TITLES[i])
                        .append("」实际「").append(title).append("」 ");
            }
        }
        check("T-3 标签名与顺序 = 总开关/颜色/光标行手感/列手感/特殊/调试",
                tabs.getTabCount() == EXPECTED_TITLES.length && wrong.length() == 0,
                wrong.length() == 0 ? actual.toString() : wrong.toString());

        // ---- T-4 每页内容都是 DialogPanel ----
        StringBuilder notPanel = new StringBuilder();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Component page = tabs.getComponentAt(i);
            if (!(page instanceof DialogPanel)) {
                notPanel.append("[").append(i).append("]").append(tabs.getTitleAt(i)).append('=')
                        .append(page == null ? "null" : page.getClass().getSimpleName()).append(' ');
            }
        }
        check("T-4 每个标签页的内容都是 DialogPanel",
                notPanel.length() == 0,
                notPanel.length() == 0 ? "全部是 DialogPanel" : notPanel.toString());

        // ---- T-5【核心守门断言】每页都已登记为集成面板 ----
        //
        // 判据：标签页集合 ⊆ outer.getIntegratedPanels()。
        // 这是结构性事实，与页里放什么控件、有没有人去碰都无关 ——
        // 漏登记时它必红，正好补上「漏 tabDiag → 全量门禁 0 条红」那个缺口。
        List<DialogPanel> integrated = outer.getIntegratedPanels();
        StringBuilder missing = new StringBuilder();
        int registered = 0;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Component page = tabs.getComponentAt(i);
            if (page instanceof DialogPanel && integrated.contains(page)) {
                registered++;
            } else {
                missing.append('[').append(i).append(']').append(tabs.getTitleAt(i)).append(' ');
            }
        }
        check("T-5 每个标签页都已 registerIntegratedPanel 登记（漏一个 = 该页改了不生效）",
                tabs.getTabCount() > 0 && registered == tabs.getTabCount(),
                registered + "/" + tabs.getTabCount() + " 已登记"
                        + (missing.length() == 0 ? "" : "；漏登记：" + missing));

        // ---- T-6 集成面板数量与标签页数量一致（没有游离 / 重复登记）----
        check("T-6 集成面板数量 = 标签页数量（无游离或多登记的页）",
                integrated.size() == tabs.getTabCount(),
                "集成面板 " + integrated.size() + " 个 / 标签页 " + tabs.getTabCount() + " 个");

        // ---- T-7 没有空白标签页 ----
        //
        // 空白页的典型成因：新加了一个 tab，但对应的 build*Group 调用漏了。
        // 它与 T-5 是两件事（登记了但内容是空的），所以单列一条。
        StringBuilder empty = new StringBuilder();
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            int n = countControls(tabs.getComponentAt(i));
            detail.append(tabs.getTitleAt(i)).append('=').append(n);
            if (i < tabs.getTabCount() - 1) {
                detail.append(" / ");
            }
            if (n == 0) {
                empty.append('[').append(i).append(']').append(tabs.getTitleAt(i)).append(' ');
            }
        }
        check("T-7 每个标签页里至少有一个控件（无空白页）",
                empty.length() == 0,
                empty.length() == 0 ? detail.toString() : "空白页：" + empty);

        // ---- T-8 每页的控件构成符合任务书 §2 的分配表 ----
        //
        // 见 EXPECTED_COMPOSITION 上方的注释：T-3 只守标题，守不住「标题对、内容错」。
        StringBuilder wrongComp = new StringBuilder();
        StringBuilder compDetail = new StringBuilder();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            int[] got = composition(tabs.getComponentAt(i));
            int[] want = i < EXPECTED_COMPOSITION.length
                    ? EXPECTED_COMPOSITION[i] : new int[]{0, 0, 0, 0};
            compDetail.append(tabs.getTitleAt(i)).append('=')
                    .append(got[0]).append('/').append(got[1]).append('/')
                    .append(got[2]).append('/').append(got[3]);
            if (i < tabs.getTabCount() - 1) {
                compDetail.append("  ");
            }
            if (got[0] != want[0] || got[1] != want[1] || got[2] != want[2]
                    || got[3] != want[3]) {
                wrongComp.append('[').append(i).append(']').append(tabs.getTitleAt(i))
                        .append(" 期望 ").append(want[0]).append('/').append(want[1])
                        .append('/').append(want[2]).append('/').append(want[3])
                        .append(" 实际 ").append(got[0]).append('/').append(got[1])
                        .append('/').append(got[2]).append('/').append(got[3]).append("； ");
            }
        }
        check("T-8 每页控件构成 = 任务书分配表（滑块/复选框/下拉/色板）",
                wrongComp.length() == 0,
                wrongComp.length() == 0 ? compDetail.toString() : wrongComp.toString());

        summary();
    }

    /** {滑块, 复选框, 下拉, 色板} 四个数量。 */
    private static int[] composition(Component root) {
        int[] n = new int[4];
        countComposition(root, n);
        return n;
    }

    private static void countComposition(Component root, int[] n) {
        if (root == null) {
            return;
        }
        if (root instanceof JSlider) {
            n[0]++;
        } else if (root instanceof JCheckBox) {
            n[1]++;
        } else if (root instanceof com.intellij.openapi.ui.ComboBox) {
            n[2]++;
        } else if (root instanceof com.intellij.ui.ColorPanel) {
            n[3]++;
        }
        if (root instanceof Container) {
            for (Component k : ((Container) root).getComponents()) {
                countComposition(k, n);
            }
        }
    }

    /** 递归收集 JTabbedPane（不假设只有一层）。 */
    private static void collectTabbedPanes(Component root, List<JTabbedPane> out) {
        if (root instanceof JTabbedPane) {
            out.add((JTabbedPane) root);
        }
        if (root instanceof Container) {
            for (Component k : ((Container) root).getComponents()) {
                collectTabbedPanes(k, out);
            }
        }
    }

    /** 子树里「可操作控件」的数量：滑块 / 复选框 / 下拉。JLabel 不算。 */
    private static int countControls(Component root) {
        if (root == null) {
            return 0;
        }
        int n = (root instanceof JSlider || root instanceof JCheckBox
                || root instanceof com.intellij.openapi.ui.ComboBox) ? 1 : 0;
        if (root instanceof Container) {
            for (Component k : ((Container) root).getComponents()) {
                n += countControls(k);
            }
        }
        return n;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + "   [" + detail + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }

    private static void summary() {
        System.out.println();
        System.out.printf("=== V-TAB-PAGES 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-TAB-PAGES RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-TAB-PAGES RESULT: ALL PASS");
        System.exit(0);
    }
}
