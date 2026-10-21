import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CaretSchemeWatcher;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;

import java.awt.Color;

/**
 * verifier 自建（t4）：缺陷 4（切主题后原生光标复活）。
 *
 * <p>判定不依赖实现者探针：用自建的 {@link V0Env} 假 EditorColorsManager，
 * 通过「换一个 scheme 对象」模拟用户切主题，读 scheme 上真实的 CARET_COLOR。
 *
 * <p>四条：
 * <ul>
 *   <li>S-1 无参 applyNativeCaretHidden() 在已隐藏后【早退】，新 scheme 的可见色不被改
 *       —— 这条同时是「缺陷 4 的修复前行为」，必须仍然成立（否则老调用点会重复写配置）；</li>
 *   <li>S-2 force=true 会重新隐藏，且 savedCaretColor 更新为【新】scheme 的原色；</li>
 *   <li>S-3 真实装配路径 handleSchemeChange 在 enabled + hideNativeCaret 时调 force 版；</li>
 *   <li>S-4 端到端：install() 订阅 EditorColorsManager.TOPIC，总线广播后新 scheme 变透明。</li>
 * </ul>
 */
public final class VScheme {

    private static int pass = 0;
    private static int fail = 0;

    private static final Color OLD_CARET = new Color(0xA9, 0xB7, 0xC6);
    private static final Color NEW_CARET = new Color(0xFF, 0x00, 0x00);

    public static void main(String[] args) {
        System.out.println("=== V-SCHEME：缺陷 4（切主题后原生光标复活）===");
        V0Env.install();
        NeovideCursorSettings svc = V0Env.svc;

        EditorColorsScheme oldScheme = V0Env.newScheme(OLD_CARET);
        EditorColorsScheme newScheme = V0Env.newScheme(NEW_CARET);
        V0Env.colors.scheme = oldScheme;

        svc.getState().enabled = true;
        svc.getState().hideNativeCaret = true;
        NeovideCaretManager.syncStateToConfig();
        NeovideCaretManager.clearSavedCaretColorForProbe();

        // ---- 起始：首次隐藏 ----
        NeovideCaretManager.applyNativeCaretHidden();
        check("S-0 首次隐藏后旧 scheme 的 CARET_COLOR 变成全透明",
                isTransparent(oldScheme.getColor(EditorColors.CARET_COLOR)),
                str(oldScheme.getColor(EditorColors.CARET_COLOR)));
        check("S-0b savedCaretColor 记下旧 scheme 的原色",
                OLD_CARET.equals(NeovideCaretManager.savedCaretColorForProbe()),
                str(NeovideCaretManager.savedCaretColorForProbe()));

        // ---- S-1：切主题后，无参版本也必须隐藏【新的这个】scheme ----
        //
        // 【本条曾被写成相反语义，为什么改了】
        // 原断言是「无参版在已隐藏时早退 → 新 scheme 的光标仍可见」，理由是
        // 「怕老调用点重复写配置」。但那描述的是 R-18 修复【之前】的代码：
        // 那时早退判据是全局的 savedCaretColor != null，切主题后它对
        // 【新的】scheme 对象仍然非 null，于是早退 —— 新 scheme 的 CARET_COLOR
        // 从未被写过，原生光标复活，与覆盖层叠成两套绘制。这正是缺陷 4 本体。
        //
        // 0.9.8 的 applyNativeCaretHidden(boolean) 已经把判据换成
        // 「【当前】scheme 是否在 HIDDEN_SCHEMES 里」（per-object），
        // 所以新 scheme 对象不在表里 → 不早退 → 正常隐藏它。这是修好的行为。
        // 继续断言「仍可见」等于要求把 R-18 的修复回滚。
        //
        // 因此正确断言是：**新 scheme 必须被隐藏**。
        V0Env.colors.scheme = newScheme;
        NeovideCaretManager.applyNativeCaretHidden();
        check("S-1 【R-18】切主题后无参版本也隐藏【当前】scheme（不因全局标记早退）",
                isTransparent(newScheme.getColor(EditorColors.CARET_COLOR)),
                str(newScheme.getColor(EditorColors.CARET_COLOR)));

        // ---- S-1b：【按对象幂等】同一 scheme 已记录过时，无参版本必须早退 ----
        // 这才是「怕老调用点重复写配置」的正确表达：按【对象】幂等，不是按【全局标记】早退。
        // 判据：手改成一个可见色，再调无参版；若早退逻辑正确
        // （该对象已在 HIDDEN_SCHEMES 里），它不会覆盖我们的手改 → 仍是可见色。
        newScheme.setColor(EditorColors.CARET_COLOR, NEW_CARET);
        NeovideCaretManager.applyNativeCaretHidden();
        check("S-1b 【按对象幂等】同一 scheme 已记录过时无参版本早退，不覆盖手改的可见色",
                NEW_CARET.equals(newScheme.getColor(EditorColors.CARET_COLOR)),
                str(newScheme.getColor(EditorColors.CARET_COLOR)));

        // ---- S-2：force 版 ----
        NeovideCaretManager.applyNativeCaretHidden(true);
        check("S-2 force=true 后新 scheme 的 CARET_COLOR 变成全透明",
                isTransparent(newScheme.getColor(EditorColors.CARET_COLOR)),
                str(newScheme.getColor(EditorColors.CARET_COLOR)));
        check("S-2b force=true 时 savedCaretColor 更新为新 scheme 的原色（不是旧主题色）",
                NEW_CARET.equals(NeovideCaretManager.savedCaretColorForProbe()),
                str(NeovideCaretManager.savedCaretColorForProbe()) + "（旧色 " + str(OLD_CARET) + "）");

        // ---- S-3：真实装配路径 ----
        EditorColorsScheme third = V0Env.newScheme(new Color(0x00, 0xFF, 0x00));
        V0Env.colors.scheme = third;
        CaretSchemeWatcher.getInstance().handleSchemeChange(third);
        check("S-3 handleSchemeChange（enabled + hideNativeCaret）后新 scheme 被重新隐藏",
                isTransparent(third.getColor(EditorColors.CARET_COLOR)),
                str(third.getColor(EditorColors.CARET_COLOR)));

        EditorColorsScheme fourth = V0Env.newScheme(new Color(0x00, 0x00, 0xFF));
        V0Env.colors.scheme = fourth;
        NeovideCaretManager.clearSavedCaretColorForProbe();
        svc.getState().hideNativeCaret = false;
        NeovideCaretManager.syncStateToConfig();
        CaretSchemeWatcher.getInstance().handleSchemeChange(fourth);
        check("S-3b hideNativeCaret=false 时切主题不动配色",
                new Color(0x00, 0x00, 0xFF).equals(fourth.getColor(EditorColors.CARET_COLOR)),
                str(fourth.getColor(EditorColors.CARET_COLOR)));

        svc.getState().hideNativeCaret = true;
        svc.getState().enabled = false;
        NeovideCaretManager.syncStateToConfig();
        NeovideCaretManager.clearSavedCaretColorForProbe();
        CaretSchemeWatcher.getInstance().handleSchemeChange(fourth);
        check("S-3c enabled=false 时切主题不动配色",
                new Color(0x00, 0x00, 0xFF).equals(fourth.getColor(EditorColors.CARET_COLOR)),
                str(fourth.getColor(EditorColors.CARET_COLOR)));

        // ---- S-4：端到端（订阅 + 广播）----
        svc.getState().enabled = true;
        svc.getState().hideNativeCaret = true;
        NeovideCaretManager.syncStateToConfig();

        CaretSchemeWatcher.getInstance().dispose();
        boolean installed = CaretSchemeWatcher.getInstance().install();
        check("S-4 install() 建立订阅（首次返回 true）", installed, String.valueOf(installed));
        check("S-4b install() 幂等（第二次返回 false）",
                !CaretSchemeWatcher.getInstance().install(), "第二次 install");
        check("S-4c 订阅的是 EditorColorsManager.TOPIC 上的 EditorColorsListener",
                V0Env.bus.subscribedTopic == EditorColorsManager.TOPIC
                        && V0Env.bus.subscribedListener instanceof EditorColorsListener,
                "topic=" + (V0Env.bus.subscribedTopic == null ? "null"
                        : V0Env.bus.subscribedTopic.getDisplayName()));

        // 端到端前必须让 savedCaretColor 处于「非 null」状态 —— 否则
        // 变异（去掉 force 穿透）也不会早退，S-4d 就测不出「监听器真的走了 force」。
        // 这也是真实用户路径：先隐藏过一次（saved 有值），再切主题。
        EditorColorsScheme warmUp = V0Env.newScheme(new Color(0x11, 0x22, 0x33));
        V0Env.colors.scheme = warmUp;
        NeovideCaretManager.applyNativeCaretHidden(true);
        check("S-4pre 端到端前置：savedCaretColor 已为非 null（模拟「已经隐藏过一次」）",
                NeovideCaretManager.savedCaretColorForProbe() != null,
                str(NeovideCaretManager.savedCaretColorForProbe()));

        EditorColorsScheme fifth = V0Env.newScheme(new Color(0xFF, 0xAA, 0x00));
        V0Env.colors.scheme = fifth;
        V0Env.bus.broadcastScheme(fifth);
        check("S-4d 经 TOPIC 广播后新 scheme 被重新隐藏（端到端）",
                isTransparent(fifth.getColor(EditorColors.CARET_COLOR)),
                str(fifth.getColor(EditorColors.CARET_COLOR)));

        CaretSchemeWatcher.getInstance().dispose();
        check("S-5 dispose() 后 listener 被清空（不泄漏）",
                CaretSchemeWatcher.getInstance().listenerForProbe() == null,
                String.valueOf(CaretSchemeWatcher.getInstance().listenerForProbe()));

        System.out.println();
        System.out.printf("=== V-SCHEME 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-SCHEME RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-SCHEME RESULT: ALL PASS");
        System.exit(0);
    }

    private static boolean isTransparent(Color c) {
        return c != null && c.getAlpha() == 0;
    }

    private static String str(Color c) {
        return c == null ? "null" : String.format("rgba(%d,%d,%d,%d)",
                c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
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
