import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import javax.swing.JSlider;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.util.List;

/**
 * 守门探针：滑块旁「数字输入框」的提交链路（BUG-A，2026-11-01）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>用户报告：「滑块旁边的数字输入没效果，辉光用输入改 1 没有效果应用也没有效果」。
 * 排查发现正常路径（键入 → apply）其实是通的 —— {@code apply()} 里平台的
 * {@code CellImpl.bind} 会读组件文本写回 pending。真正的问题是<b>时机</b>：
 * 键入只挪滑块和标签，<b>不写 pending、不刷预览窗</b>，用户拖滑块能看到预览实时变、
 * 在输入框里键入却看不到，apply 之前一切「像没生效」。
 *
 * <p>所以本探针盯的不是「apply 后能不能落库」（那本来就有 ProbeSettingsV2 S6 守着），
 * 而是<b>键入这一瞬间 pending 必须已经更新</b>（AC-001），并且 14 个滑块行为一致（AC-003）。
 *
 * <h2>为什么用「键入」而不是 setText</h2>
 *
 * <p>{@code setText} 与真实键入在 Swing 里都会触发 {@code DocumentEvent}，但真实键入
 * 会先删后插、经过中间态（空串、单位数）。本探针用 {@code document.remove + insertString}
 * 复刻这条序列，避免「setText 刚好绕过中间态」造成的假绿。
 */
public final class VInputCommit {

    private static int pass = 0;
    private static int fail = 0;

    private static NeovideCursorSettings svc;
    private static NeovideCursorConfigurable conf;
    private static VUi ui;

    public static void main(String[] args) {
        System.out.println("=== V-INPUT-COMMIT：数字输入框 → pending → apply → State ===");
        V0Env.install();
        svc = V0Env.svc;

        conf = new NeovideCursorConfigurable();
        ui = VUi.build(conf);
        conf.reset();

        List<String> keys = conf.sliderKeysForProbe();
        check("I-1 面板建成：滑块 / 输入框 / 键表数量一致",
                ui.sliders.size() == ui.textFields.size()
                        && ui.sliders.size() == keys.size()
                        && ui.sliders.size() > 0,
                "sliders=" + ui.sliders.size() + " tf=" + ui.textFields.size()
                        + " keys=" + keys.size());

        // ---------- AC-001 / AC-003：14 个滑块逐个「键入 → pending 立即更新」 ----------
        int okPending = 0;
        int okApply = 0;
        StringBuilder badPending = new StringBuilder();
        StringBuilder badApply = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            // 【必须按 key 定位，不能按列表下标】
            //
            // `sliderKeysForProbe()` 返回的是 bindings 顺序（= SPECS 声明顺序），
            // 而 `VUi.sliders` 是面板渲染顺序 —— 两者不同（见 VUi.KEYS 的注释）。
            // 按下标取会「测 A 实际改的是 B」，第一步就踩了这个坑：
            // 14 条全部报「期望 90 实际 50」这种错位值。
            JSlider sl = ui.sliderByKey(key);
            JTextComponent tf = ui.textFieldOf(sl);
            int min = sl.getMinimum(), max = sl.getMaximum(), step = sl.getMinorTickSpacing();
            int cur = sl.getValue();
            int target = cur + step * 2;
            if (target > max) {
                target = cur - step * 2;
            }
            if (target < min) {
                target = min;
            }
            if (target == cur) {
                badPending.append('[').append(key).append(" 无法取值] ");
                continue;
            }

            conf.reset();
            typeInto(tf, String.valueOf(target));

            // ① 键入后 pending 必须立刻是用户输入的值（AC-001）
            int pendingTick = conf.sliderExactTickForProbe(i);
            if (pendingTick == target) {
                okPending++;
            } else {
                badPending.append('[').append(key).append(" 期望 ").append(target)
                        .append(" 实际 ").append(pendingTick).append(']');
            }

            // ② 点「应用」后必须落进 State（AC-002）。用 reset() 从 State 回灌再读，
            //    这样读到的是 State 本身，而不是还没被清掉的 pending。
            conf.apply();
            conf.reset();
            int stateTick = conf.sliderExactTickForProbe(i);
            if (stateTick == target) {
                okApply++;
            } else {
                badApply.append('[').append(key).append(" 期望 ").append(target)
                        .append(" 实际 ").append(stateTick).append(']');
            }
        }
        check("I-2 键入后 pending 立即更新（" + okPending + "/" + keys.size() + "）",
                okPending == keys.size(), badPending.toString());
        check("I-3 apply 后 State 更新（" + okApply + "/" + keys.size() + "）",
                okApply == keys.size(), badApply.toString());

        // ---------- I-4：越界值本身绝不进 pending（与 apply 的「拒绝越界」语义一致） ----------
        //
        // 【2026-11-01 契约细化】逐字符键入 9999 会经过 "9"、"99" 两个<b>合法前缀</b>，
        // 它们会被写进 pending —— 这是「键入即写」的必然结果，不是越界。
        // 这里要守的是：<b>越界的那个值（9999 / 999）永远不写</b>，
        // 且 apply 之后 State 也不会是越界值。
        conf.reset();
        int idx = keys.indexOf("glowOpacity");            // bindings 下标（给 *ForProbe 用）
        if (idx < 0) {
            check("I-4 前置：键表里存在 glowOpacity", false, "keys=" + keys);
            System.exit(1);
        }
        JTextComponent tf = ui.textFieldOf(ui.sliderByKey("glowOpacity"));
        int maxTick = ui.sliderByKey("glowOpacity").getMaximum();
        typeInto(tf, "9999");
        int afterType = conf.sliderExactTickForProbe(idx);
        check("I-4 越界键入 9999：pending 不会被写成越界值（≤ " + maxTick + "）",
                afterType != 9999 && afterType <= maxTick,
                "after=" + afterType);
        conf.apply();
        conf.reset();                       // 从 State 回灌，读到的才是 State
        int stateTick = conf.sliderExactTickForProbe(idx);
        check("I-4b apply 后 State 也不是越界值（滑块值或被拒绝）",
                stateTick != 9999, "stateTick=" + stateTick);

        // ---------- I-5：程序化回灌后控件文本与 pending 一致 ----------
        //
        // 【2026-11-01 审查 M-5/L-2：原来这条断言是同义反复】
        // 旧写法「连续两次 reset，pending 不变」在回环存在时也成立 —— reset 写回的
        // 正是 pending 自己。现在改成断言<b>回灌结果</b>：控件文本 == pending 的刻度值。
        // 这能抓到「syncingControls 期间 setText 被当成用户键入、把滑块吸附值写回去」
        // 这一类真实错位（它会表现为文本与 pending 差一个吸附档）。
        conf.reset();
        int pendTick = conf.sliderExactTickForProbe(idx);
        String textNow = tf.getText();
        check("I-5 reset 回灌后输入框文本 == pending 刻度值（" + pendTick + "）",
                String.valueOf(pendTick).equals(textNow),
                "text=「" + textNow + "」 pendingTick=" + pendTick);

        // ---------- I-6：键入后预览窗必须已经被推过新值（A-1 的直接判据） ----------
        //
        // refreshPreviews() 把 pending 推给 previewConfig；AnimationPreview.onConfigChanged()
        // 之后 config 里的值应与 pending 同步。这里用一个可观察者：conf 的预览配置读取钩子。
        conf.reset();
        int previewIdx = keys.indexOf("glowLayers");       // bindings 下标
        if (previewIdx < 0) {
            check("I-6 前置：键表里存在 glowLayers", false, "keys=" + keys);
            System.exit(1);
        }
        int previewTarget = conf.sliderExactTickForProbe(previewIdx) == 8 ? 6 : 8;
        typeInto(ui.textFieldOf(ui.sliderByKey("glowLayers")), String.valueOf(previewTarget));
        check("I-6 键入后预览配置同步更新（glowLayers=" + previewTarget + "）",
                conf.previewGlowLayersForProbe() == previewTarget,
                "previewConfig.glowLayers=" + conf.previewGlowLayersForProbe());

        System.out.println();
        System.out.printf("=== V-INPUT-COMMIT 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-INPUT-COMMIT RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-INPUT-COMMIT RESULT: ALL PASS");
        System.exit(0);
    }

    /**
     * 逐字符键入（不是一次性 setText）。
     *
     * <p>真实键入会经过中间态：清空后先出现 "1"、再 "12"、再 "125"。一次性插入整串
     * 只触发两个 DocumentEvent，测不到「中间态被越界校验挡住 / 中间态写坏 pending」
     * 这类问题。2026-11-01 代码审查 L-3 指出后改为逐字符。
     */
    private static void typeInto(JTextComponent tf, String text) {
        try {
            Document doc = tf.getDocument();
            doc.remove(0, doc.getLength());
            for (int i = 0; i < text.length(); i++) {
                doc.insertString(doc.getLength(), String.valueOf(text.charAt(i)), null);
            }
        } catch (Exception e) {
            throw new IllegalStateException("键入失败：" + text, e);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   " + detail);
        }
    }
}
