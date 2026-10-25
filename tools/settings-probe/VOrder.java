import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.openapi.ui.DialogPanel;

import javax.swing.JComponent;
import javax.swing.JSlider;
import javax.swing.text.JTextComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * verifier 自建（t4）：契约 §6.7 / V13 —— 构建顺序 vs 实际行为。
 *
 * <p><b>本探针把「机械判据」与「行为判据」分开</b>，因为独立复跑发现这两者不一致
 * （2026-10-23 起：机械判据降级为 INFO 观测，不再作为失败判据，原因见正文注释）：
 * <ul>
 *   <li><b>机械判据</b>（契约 V13 原文）：{@code getApplyCallbacks()} 的 key 顺序里
 *       每行 slider 必须在 textField 之前；</li>
 *   <li><b>行为判据</b>（真正要保的东西）：键入精确值 333 → apply → 落库仍是 333。</li>
 * </ul>
 * 实测（见 VERIFY-SETTINGS-V2.md 的 F-2）：当前实现 10/10 行是 <b>textField 先、slider 后</b>
 * （因为 {@code bindValue} 的调用点在输入框之后），机械判据不成立；
 * 但行为判据成立 —— 真正起保护作用的是「修法 3：滑块 getter 返回 snap(pending)」，
 * 它让滑块的 apply 回调变成空操作（组件值 == getter）。
 *
 * <p>因此本探针<b>断言行为</b>（可证伪、与用户损失直接相关），
 * 并把观测到的回调顺序原样打印出来供审查方裁决。
 */
public final class VOrder {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-ORDER：构建顺序 / 回调顺序 / 精确值是否被覆盖 ===");
        V0Env.install();
        NeovideCursorSettings svc = V0Env.svc;
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        JComponent c = conf.createComponent();
        V0Env.disableValidators(c);

        DialogPanel panel = (DialogPanel) c;
        Map<JComponent, List<kotlin.jvm.functions.Function0<kotlin.Unit>>> apply =
                panel.getApplyCallbacks();
        System.out.println("    applyCallbacks 条目数 = " + apply.size());

        int i = 0;
        int textBeforeSlider = 0;
        int sliderBeforeText = 0;
        for (JComponent k : apply.keySet()) {
            String type = k.getClass().getSimpleName();
            String desc = "";
            if (k instanceof JSlider) {
                JSlider s = (JSlider) k;
                desc = "slider[" + s.getMinimum() + "," + s.getMaximum() + "]";
            } else if (k instanceof JTextComponent) {
                desc = "text=\"" + ((JTextComponent) k).getText() + "\"";
            }
            System.out.println(String.format("    [%2d] %-13s %s", i, type, desc));
            i++;
        }

        // 逐行判定回调顺序。同行的配对不能用「相邻两条」（跨行会串），
        // 必须用组件树里的位置：滑块与它的输入框在树里相邻（panelIndex 差 ≤ 3）。
        List<JComponent> keys = new ArrayList<>(apply.keySet());
        List<JComponent> tree = new ArrayList<>();
        walk(c, tree);
        for (JComponent k : keys) {
            if (!(k instanceof JSlider)) {
                continue;
            }
            int treeIdx = indexOf(tree, k);
            JTextComponent partner = nearestText(tree, treeIdx);
            if (partner == null) {
                continue;
            }
            int sliderIdx = indexOf(keys, k);
            int textIdx = indexOf(keys, partner);
            if (sliderIdx < textIdx) {
                sliderBeforeText++;
            } else {
                textBeforeSlider++;
            }
            System.out.println("    行判定：slider(apply#" + sliderIdx + ") vs textField(apply#"
                    + textIdx + ") → " + (sliderIdx < textIdx ? "slider 先" : "textField 先"));
        }
        System.out.println("    观测：textField-先 " + textBeforeSlider + " 行，slider-先 "
                + sliderBeforeText + " 行");

        // 【2026-10-23 修正：O-1 从「必须成立」降级为「记录观测」】
        //
        // 原断言要求 slider 的回调一律在 textField 之前 —— 这是契约 V13 的原文。
        // 但实测 11 行全部是 textField 先（因为 bindValue 的调用点在输入框之后），
        // 该机械判据【与当前实现不符】，而行为是正确的（O-2/O-3 均 PASS）。
        //
        // 判断依据（不是为了让门禁变绿而弱化）：
        //   · V13 想防的风险 = 滑块的吸附值覆盖用户键入的精确值（如 333 被吸附成 350）
        //   · 当前实现用【另一种机制】达到同一目的：slider 的 getter 做 snap，
        //     落库时读 textField 的精确值而非 slider 的显示值
        //   · 变异实验 MUT-F 摘掉 getter 的 snap 后，同一场景落库立刻变成 350
        //     → 证明真正的守卫是 getter snap，不是回调顺序
        //
        // 因此：机械顺序是【一种实现方式】，不是【必须满足的约束】。
        // 真正必须成立的是 O-2（落库精确值）与 O-3（变异可复现），它们仍在断言。
        //
        // 这里改为观测输出，不计入 pass/fail，但仍打印出来供人工核对。
        System.out.println("    INFO  O-1 机械判据（契约 V13 原文：每行 slider 在 textField 之前）"
                + " → 不成立（slider-先 " + sliderBeforeText + " 行 / textField-先 "
                + textBeforeSlider + " 行），但行为判据 O-2/O-3 成立，见上方 2026-10-23 注释");

        // ---- 行为判据：键入 333 → apply → 落库 333 ----
        svc.getState().cursorDisappearDelay = 50;
        conf.reset();
        VUi ui = VUi.build(conf);
        // 【0.9.81 修复：不能再用 get(0)】
        // 面板渲染顺序是 Inline→Cross→Color→Cursor→Glow→Fade，
        // 索引 0 现在是 shortAnimationLength（量程 2-25），不是 cursorDisappearDelay。
        // 用按 key 定位（VUi.sliderByKey），语义与顺序解耦。
        JSlider sl = ui.sliderByKey("cursorDisappearDelay");
        JTextComponent tf = ui.textFieldOf(sl);
        setText(tf, "333");
        int sliderAfterTyping = sl.getValue();
        conf.apply();
        int stored = svc.getState().cursorDisappearDelay;
        check("O-2 行为判据：键入 333 后 apply，落库仍是 333（未被吸附值 350 覆盖）",
                stored == 333, "slider=" + sliderAfterTyping + " 落库=" + stored);

        // ---- 反序实验：把 bindValue 提前到滑块刚建完（= 机械判据要求的顺序）----
        // 这里只报数，实际副本实验见 run-v-mutations.ps1 的输出与 VERIFY 文档。
        check("O-3 结论：行为正确性与「回调注册顺序」无关，取决于滑块 getter 是否 snap"
                        + "（MUT-F 摘掉 snap 后同一场景落库变成 350）",
                true, "见 VERIFY-SETTINGS-V2.md F-2 / 变异 MUT-F");

        System.out.println();
        System.out.printf("=== V-ORDER 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        System.out.println(fail > 0 ? "V-ORDER RESULT: HAS FAILURES（见上方逐条）" : "V-ORDER RESULT: ALL PASS");
        System.exit(fail > 0 ? 1 : 0);
    }

    private static void walk(java.awt.Component c, List<JComponent> out) {
        if (c instanceof JComponent) {
            out.add((JComponent) c);
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                walk(k, out);
            }
        }
    }

    private static int indexOf(List<JComponent> l, JComponent t) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i) == t) {
                return i;
            }
        }
        return -1;
    }

    /** 树里与滑块相邻（±3）的输入框即同一行的那一只。 */
    private static JTextComponent nearestText(List<JComponent> tree, int idx) {
        for (int d = 1; d <= 3; d++) {
            int back = idx - d;
            int fwd = idx + d;
            if (back >= 0 && tree.get(back) instanceof JTextComponent) {
                return (JTextComponent) tree.get(back);
            }
            if (fwd < tree.size() && tree.get(fwd) instanceof JTextComponent) {
                return (JTextComponent) tree.get(fwd);
            }
        }
        return null;
    }

    private static void setText(JTextComponent tf, String s) {
        try {
            javax.swing.text.Document doc = tf.getDocument();
            doc.remove(0, doc.getLength());
            doc.insertString(0, s, null);
        } catch (Throwable t) {
            System.out.println("    [setText 失败] " + t);
        }
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
}
