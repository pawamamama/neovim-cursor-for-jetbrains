import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JSlider;
import javax.swing.text.JTextComponent;

/**
 * verifier 自建（t4）：<b>混合操作</b>的落库值 —— 拖动与键入在同一行先后发生时，谁赢？
 *
 * <p>契约 §6.1 的语义表只覆盖了「单独拖」「单独键入」两种，没覆盖「先拖后输 / 先输后拖」。
 * 本探针把两个方向都测出来，用来判断 §6.7 的回调顺序到底是「无关紧要」还是「真的会错」。
 */
public final class VMixed {

    private static int pass = 0;
    private static int fail = 0;

    private static NeovideCursorSettings svc;
    private static NeovideCursorConfigurable conf;

    public static void main(String[] args) {
        System.out.println("=== V-MIXED：拖动与键入的先后（同一行）===");
        V0Env.install();
        svc = V0Env.svc;

        conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);

        // ---- 场景 1：先输 333，再拖到 400（最后一次操作 = 拖）→ 期望 400 ----
        svc.getState().cursorDisappearDelay = 50;
        conf.reset();
        ui = VUi.build(conf);
        JTextComponent tf = ui.textFields.get(0);
        JSlider sl = ui.sliders.get(0);
        setText(tf, "333");
        sl.setValue(400);
        conf.apply();
        int s1 = svc.getState().cursorDisappearDelay;
        System.out.println("    场景1（先输 333 再拖 400）：落库 = " + s1 + "，滑块 = " + sl.getValue()
                + "，输入框 = \"" + tf.getText() + "\"");
        check("MX-1 先输后拖：落库 == 400（最后一次操作是拖动）", s1 == 400, "落库=" + s1);

        // ---- 场景 2：先拖到 400，再输 333（最后一次操作 = 键入）→ 期望 333 ----
        svc.getState().cursorDisappearDelay = 50;
        conf.reset();
        ui = VUi.build(conf);
        tf = ui.textFields.get(0);
        sl = ui.sliders.get(0);
        sl.setValue(400);
        setText(tf, "333");
        conf.apply();
        int s2 = svc.getState().cursorDisappearDelay;
        System.out.println("    场景2（先拖 400 再输 333）：落库 = " + s2 + "，滑块 = " + sl.getValue()
                + "，输入框 = \"" + tf.getText() + "\"");
        check("MX-2 先拖后输：落库 == 333（最后一次操作是键入）", s2 == 333, "落库=" + s2);

        System.out.println();
        System.out.printf("=== V-MIXED 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        System.out.println(fail > 0 ? "V-MIXED RESULT: HAS FAILURES" : "V-MIXED RESULT: ALL PASS");
        System.exit(fail > 0 ? 1 : 0);
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
