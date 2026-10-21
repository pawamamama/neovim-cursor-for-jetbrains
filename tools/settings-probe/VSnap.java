import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JSlider;
import javax.swing.text.JTextComponent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

/**
 * verifier 自建（t4）：滑块吸附 + 输入框双向绑定 + 动画参数边界（契约 §6.1 / §6.5 / §7.1）。
 *
 * <p>三档语义（§6.1 的表格）：
 * <ul>
 *   <li>拖滑块 → 落刻度、写 pending、标签跟随；</li>
 *   <li>键入合法值 → 滑块吸附（位置变），pending <b>apply 时</b>写精确值；</li>
 *   <li>键入非法 → pending 不变；失焦才 clamp。</li>
 * </ul>
 */
public final class VSnap {

    private static int pass = 0;
    private static int fail = 0;

    private static NeovideCursorSettings svc;
    private static NeovideCursorConfigurable conf;
    private static VUi ui;

    public static void main(String[] args) {
        System.out.println("=== V-SNAP：吸附 / 双向绑定 / 非法值 / 动画参数边界 ===");
        V0Env.install();
        svc = V0Env.svc;
        conf = new NeovideCursorConfigurable();
        ui = VUi.build(conf);

        snapOnDrag();
        typingPrecision();
        illegalValues();
        animationBounds();

        System.out.println();
        System.out.printf("=== V-SNAP 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-SNAP RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-SNAP RESULT: ALL PASS");
        System.exit(0);
    }

    // ---------------------------------------------------------------- 吸附

    /**
     * 把滑块设到一个明显不在刻度上的值，断言最终落位在刻度网格上。
     *
     * <p><b>【reviewer F1】这条曾经把整个探针打断。</b>旧实现自带一份
     * {@code int[] steps = {50,25,5,5,1,5,5,5,1,1}}（10 项），而循环上界是
     * {@code ui.sliders.size()}（M4 之后 = 11）。于是第 11 次迭代 {@code steps[10]}
     * 抛 {@link ArrayIndexOutOfBoundsException}，main 直接逃出去 ——
     * 后面的 TYP / ILL / BND 一共十几条断言【一条都没执行】，
     * 而报告上只显示 "VSnap FAIL=1"，看不出是崩溃。这是最坏的形态：
     * 红的地方被掩盖，绿的地方是假的。
     *
     * <p>现在步长与量程一律从 {@link VUi#spec(int)} 取（探针侧唯一刻度来源），
     * 越界立刻判 FAIL 而不是崩。
     */
    private static void snapOnDrag() {
        System.out.println();
        System.out.println("--- 吸附：非刻度值必须落到刻度 ---");
        int measured = 0;
        int ok = 0;
        for (int i = 0; i < ui.sliders.size(); i++) {
            JSlider s = ui.sliders.get(i);
            int[] spec = VUi.spec(i);
            if (spec == null) {
                check("SNAP-0 滑块 " + i + " 在 VUi.SPEC 里有刻度定义", false,
                        "spec=null key=" + VUi.nameOf(i));
                continue;
            }
            int min = spec[0];
            int max = spec[1];
            int step = spec[2];
            // 探针表必须与面板实际量程一致，否则下面的期望值算错
            boolean rangeOk = s.getMinimum() == min && s.getMaximum() == max;
            if (step == 1) {
                // 步长 1 没有「非刻度整数」可测；但仍要核对量程
                if (!rangeOk) {
                    check("SNAP-0b 滑块 " + VUi.nameOf(i) + " 量程与 VUi.SPEC 一致", false,
                            "面板=[" + s.getMinimum() + "," + s.getMaximum()
                                    + "] 表=[" + min + "," + max + "]");
                }
                continue;
            }
            measured++;
            // 取一个「一定不是刻度」的值：min + 3 又 half 档
            int off = min + step * 3 + step / 2;
            if (off > max) {
                off = max - step / 2;            // 兜底：贴着上限的反向偏离
            }
            s.setValue(off);
            int got = s.getValue();
            boolean onGrid = got >= min && got <= max && (got - min) % step == 0;
            boolean nearest = Math.abs(got - off) <= step / 2;
            boolean recentered = got == min + Math.round((off - min) / (float) step) * step;
            if (rangeOk && onGrid && nearest && recentered) {
                ok++;
            }
            System.out.println("    " + (rangeOk && onGrid && nearest && recentered ? "PASS " : "FAIL ")
                    + String.format("%-22s", VUi.nameOf(i))
                    + " 量程=[" + min + "," + max + "] step=" + step
                    + " 面板=[" + s.getMinimum() + "," + s.getMaximum() + "]"
                    + " 请求 " + off + " → 落位 " + got + "（网格=" + onGrid
                    + " 最近=" + nearest + " 就近取整=" + recentered + "）");
        }
        // 契约里步长 >1 的滑块恒为 7 个：50/25/5/5/5/5/5 那七个
        // （cursorDisappearDelay / cursorFadeOutDuration / tailOpacity / glowOpacity /
        //  trailSize / stretchRatioK / typingSmoothness）。
        check("SNAP-1 全部可测滑块（step>1）的 setValue(非刻度) 都落到最近刻度",
                measured == 7 && ok == 7, ok + "/" + measured + "（契约里 step>1 恒为 7 个）");
    }

    // ---------------------------------------------------------------- 精确值

    /** 键入 333（步长 50）：pending 在 apply 时必须写精确值，滑块显示 350。 */
    private static void typingPrecision() {
        System.out.println();
        System.out.println("--- 精确值：键入 333 不被吸附值覆盖 ---");
        NeovideCursorSettings.State st = svc.getState();
        st.cursorDisappearDelay = 50;
        conf.reset();
        ui = VUi.build(conf);

        JTextComponent tf = ui.textFields.get(0);
        JSlider sl = ui.sliders.get(0);
        setText(tf, "333");
        check("TYP-1 键入 333 后滑块吸附到 350", sl.getValue() == 350, "slider=" + sl.getValue());
        check("TYP-2 键入期间 pending 仍为旧值 50（apply 前不写）",
                conf.pendingForProbe().cursorDisappearDelay == 50,
                "pending=" + conf.pendingForProbe().cursorDisappearDelay);
        check("TYP-3 标签显示用户输入的精确值 333（不篡改）",
                ui.valueLabels.get(0).getText().startsWith("333"),
                "label=\"" + ui.valueLabels.get(0).getText() + "\"");
        conf.apply();
        check("TYP-4 apply 后落库 333（未被 350 覆盖）",
                svc.getState().cursorDisappearDelay == 333,
                "state=" + svc.getState().cursorDisappearDelay);
        check("TYP-5 apply 后 isModified()=false（精确值 vs 刻度值的差异不制造假脏）",
                !conf.isModified(), String.valueOf(conf.isModified()));
    }

    // ---------------------------------------------------------------- 非法值

    private static void illegalValues() {
        System.out.println();
        System.out.println("--- 非法值三档：越界 / 非数字 / 空串 ---");
        NeovideCursorSettings.State st = svc.getState();
        st.cursorDisappearDelay = 50;
        conf.reset();
        ui = VUi.build(conf);
        JTextComponent tf = ui.textFields.get(0);
        JSlider sl = ui.sliders.get(0);

        // 越界：键入过程中不 clamp（9999 可能是 999 的前缀）
        setText(tf, "9999");
        check("ILL-1 键入越界 9999 时 pending 不变（不在键入中 clamp）",
                conf.pendingForProbe().cursorDisappearDelay == 50,
                "pending=" + conf.pendingForProbe().cursorDisappearDelay);
        // 失焦 → clamp 到上限并写入
        fireFocusLost(tf);
        check("ILL-2 失焦后 clamp 到上限 500 并写入 pending",
                conf.pendingForProbe().cursorDisappearDelay == 500,
                "pending=" + conf.pendingForProbe().cursorDisappearDelay);
        check("ILL-3 失焦后输入框文本规整为 500", "500".equals(tf.getText()), "tf=\"" + tf.getText() + "\"");

        // 非数字
        st.cursorDisappearDelay = 50;
        conf.reset();
        ui = VUi.build(conf);
        tf = ui.textFields.get(0);
        setText(tf, "abc");
        check("ILL-4 键入非数字不写 pending、不抛异常",
                conf.pendingForProbe().cursorDisappearDelay == 50,
                "pending=" + conf.pendingForProbe().cursorDisappearDelay);
        fireFocusLost(tf);
        check("ILL-5 非数字失焦后回滚为 pending 值 50",
                "50".equals(tf.getText())
                        && conf.pendingForProbe().cursorDisappearDelay == 50,
                "tf=\"" + tf.getText() + "\" pending=" + conf.pendingForProbe().cursorDisappearDelay);

        // 空串
        st.cursorDisappearDelay = 50;
        conf.reset();
        ui = VUi.build(conf);
        tf = ui.textFields.get(0);
        setText(tf, "");
        check("ILL-6 空串不写 pending、不抛异常",
                conf.pendingForProbe().cursorDisappearDelay == 50,
                "pending=" + conf.pendingForProbe().cursorDisappearDelay);
        fireFocusLost(tf);
        check("ILL-7 空串失焦后回滚为 50", "50".equals(tf.getText()), "tf=\"" + tf.getText() + "\"");
        check("ILL-8 非法值处理后 isModified()=false（没制造假脏）",
                !conf.isModified(), String.valueOf(conf.isModified()));
    }

    // ---------------------------------------------------------------- 动画参数边界

    private static void animationBounds() {
        System.out.println();
        System.out.println("--- animationLength / shortAnimationLength 四端点 + 越界 ---");
        NeovideCursorSettings.State st = svc.getState();
        st.animationLength = 0.1f;
        st.shortAnimationLength = 0.11f;
        conf.reset();
        ui = VUi.build(conf);

        JSlider a = ui.sliders.get(8);
        JSlider b = ui.sliders.get(9);
        check("BND-0 滑块量程：cross=[5,35] short=[2,25]",
                a.getMinimum() == 5 && a.getMaximum() == 35
                        && b.getMinimum() == 2 && b.getMaximum() == 25,
                "cross=[" + a.getMinimum() + "," + a.getMaximum()
                        + "] short=[" + b.getMinimum() + "," + b.getMaximum() + "]");

        a.setValue(5);
        conf.apply();
        check("BND-1 animationLength 下端点 0.05 落库",
                Float.compare(svc.getState().animationLength, 0.05f) == 0,
                bits(svc.getState().animationLength));

        conf.reset();
        ui = VUi.build(conf);
        ui.sliders.get(8).setValue(35);
        conf.apply();
        check("BND-2 animationLength 上端点 0.35 落库",
                Float.compare(svc.getState().animationLength, 0.35f) == 0,
                bits(svc.getState().animationLength));

        conf.reset();
        ui = VUi.build(conf);
        ui.sliders.get(9).setValue(2);
        conf.apply();
        check("BND-3 shortAnimationLength 下端点 0.02 落库",
                Float.compare(svc.getState().shortAnimationLength, 0.02f) == 0,
                bits(svc.getState().shortAnimationLength));

        conf.reset();
        ui = VUi.build(conf);
        ui.sliders.get(9).setValue(25);
        conf.apply();
        check("BND-4 shortAnimationLength 上端点 0.25 落库",
                Float.compare(svc.getState().shortAnimationLength, 0.25f) == 0,
                bits(svc.getState().shortAnimationLength));

        // 越界：直接往 state 里塞越界值，reset 后控件必须被 clamp（XML 手改场景）
        conf.reset();
        svc.getState().animationLength = 9.99f;
        svc.getState().shortAnimationLength = -5f;
        conf.reset();
        ui = VUi.build(conf);
        check("BND-5 state 里 animationLength=9.99 时滑块被 clamp 到 35",
                ui.sliders.get(8).getValue() == 35, "slider=" + ui.sliders.get(8).getValue());
        check("BND-6 state 里 shortAnimationLength=-5 时滑块被 clamp 到 2",
                ui.sliders.get(9).getValue() == 2, "slider=" + ui.sliders.get(9).getValue());

        // 越界值在键入 + 失焦后必须被 clamp 到量程内并落库
        conf.reset();
        ui = VUi.build(conf);
        JTextComponent tf = ui.textFields.get(8);
        setText(tf, "999");
        fireFocusLost(tf);
        conf.apply();
        check("BND-7 键入 999 失焦后 clamp 到 0.35 并落库",
                Float.compare(svc.getState().animationLength, 0.35f) == 0,
                bits(svc.getState().animationLength));

        // 契约 §7.5：animationResetThreshold 禁止进 UI
        boolean hasReset = false;
        for (int i = 0; i < ui.sliders.size(); i++) {
            if ("animationResetThreshold".equals(VUi.nameOf(i))) {
                hasReset = true;
            }
        }
        check("BND-8 面板里没有 animationResetThreshold 滑块（契约 §7.5 禁止项）", !hasReset,
                "sliders=" + ui.sliders.size());
    }

    // ---------------------------------------------------------------- 工具

    private static String bits(float f) {
        return f + " (raw=0x" + Integer.toHexString(Float.floatToRawIntBits(f)) + ")";
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

    private static void fireFocusLost(JTextComponent tf) {
        FocusEvent e = new FocusEvent(tf, FocusEvent.FOCUS_LOST);
        for (FocusListener l : tf.getFocusListeners()) {
            l.focusLost(e);
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
