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

        // ---- S-1：切主题，无参版本必须早退（否则老调用点会重复写配置）----
        V0Env.colors.scheme = newScheme;
        NeovideCaretManager.applyNativeCaretHidden();
        check("S-1 【修复前行为必须保留】无参版本在已隐藏时早退：新 scheme 光标仍可见",
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
