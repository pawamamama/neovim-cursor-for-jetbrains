import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.lang.reflect.Method;

/**
 * V-RESTORE-DEFAULT：点「恢复为默认值」必须真的回到默认值。
 *
 * <p><b>用户报</b>：「恢复为默认值 没有这个逻辑这么难吗 —— 直接从哪个预设拷贝的覆盖一下呗」。
 *
 * <p><b>旧行为错在哪</b>：在自定义副本上，{@code restoreAnimPreset()} restore 的是
 * 副本<b>自己存的快照</b> —— 等于把自己又拷了一遍，<b>点完看不出任何变化</b>。
 * 而按钮文案写着「恢复为「基础 副本」默认值」，用户以为会回到「基础」。
 *
 * <p><b>新行为</b>：副本 → 回到它派生自的那个内置预设（{@code animPresetBase}），
 * 并清掉副本名。内置预设 → 回到自己的常量（原行为不变）。
 */
public final class VRestoreDefault {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-RESTORE-DEFAULT：恢复为默认值 ===");
        // restoreAnimPreset 末尾会调 syncAllControlsFromPending() → reset() → createPanel()，
        // 需要 IntelliJ 平台桩（ActionManager / JBPopupFactory 等）。
        V0Env.install();

        // ---- D-1: 内置预设上，恢复 = 回到该预设常量 ----
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.animPresetBase = 1;                       // 缎带
        s.animCustomName = "";
        NeovideCursorConfigurable.applyAnimPresetFields(
                s, CursorPresets.ANIM_PRESETS[1]);
        // 弄脏多个字段（模拟用户拖动）
        s.animationLength = 0.05f;
        s.tailOpacity = 0.2f;
        s.glowLayers = 3;
        s.typingSmoothness = 0.9f;
        restore(s, 1);
        boolean back = Math.abs(s.animationLength - 0.28f) < 1e-3f
                && Math.abs(s.tailOpacity - 1.0f) < 1e-3f
                && s.glowLayers == 8
                && Math.abs(s.typingSmoothness) < 1e-3f;
        check("D-1 内置预设上恢复 = 回到该预设常量（4 个字段全回）", back,
                "anim=" + s.animationLength + " alpha=" + s.tailOpacity
                        + " layers=" + s.glowLayers + " smooth=" + s.typingSmoothness);

        // ---- D-2（核心）：自定义副本上，恢复 = 回到它派生自的内置预设 ----
        NeovideCursorSettings.State s2 = new NeovideCursorSettings.State();
        s2.animPresetBase = 0;                      // 派生自「基础」
        s2.animCustomName = "基础 副本";
        NeovideCursorConfigurable.applyAnimPresetFields(
                s2, CursorPresets.ANIM_PRESETS[0]);
        // 用户在副本上乱改（这正是用户想反悔的场景）
        s2.animationLength = 0.31f;
        s2.tailOpacity = 0.33f;
        s2.typingSmoothness = 0.77f;
        s2.glowLayers = 2;
        s2.stretchRatioK = 1.5f;
        restore(s2, 0);
        boolean restored = Math.abs(s2.animationLength - 0.10f) < 1e-3f
                && Math.abs(s2.tailOpacity - 1.0f) < 1e-3f
                && Math.abs(s2.typingSmoothness) < 1e-3f
                && s2.glowLayers == 8
                && Math.abs(s2.stretchRatioK - 4.5f) < 1e-3f;
        check("D-2 自定义副本上恢复 = 回到派生自的内置预设（不是拷自己）",
                restored,
                "anim=" + s2.animationLength + " alpha=" + s2.tailOpacity
                        + " smooth=" + s2.typingSmoothness + " layers=" + s2.glowLayers);

        // ---- D-3: 恢复后副本名必须清掉（否则下拉撒谎）----
        check("D-3 恢复后 animCustomName 被清空（下拉不能显示副本名而值是内置的）",
                s2.animCustomName.isEmpty(), "name=\"" + s2.animCustomName + "\"");

        // ---- D-4: 副本派生自「缎带」时，恢复要回到缎带而不是基础 ----
        NeovideCursorSettings.State s3 = new NeovideCursorSettings.State();
        s3.animPresetBase = 1;                      // 派生自「缎带」
        s3.animCustomName = "缎带 副本";
        NeovideCursorConfigurable.applyAnimPresetFields(
                s3, CursorPresets.ANIM_PRESETS[1]);
        s3.animationLength = 0.05f;
        restore(s3, 1);
        check("D-4 派生自「缎带」的副本，恢复回到缎带（anim=0.28 而不是 0.10）",
                Math.abs(s3.animationLength - 0.28f) < 1e-3f,
                "anim=" + s3.animationLength);

        // ---- D-5: 恢复必须可逆 —— 再弄脏再恢复仍能回去 ----
        s3.animationLength = 0.06f;
        s3.tailOpacity = 0.5f;
        restore(s3, 1);
        check("D-5 可逆：再次弄脏后恢复仍回到缎带",
                Math.abs(s3.animationLength - 0.28f) < 1e-3f
                        && Math.abs(s3.tailOpacity - 1.0f) < 1e-3f,
                "anim=" + s3.animationLength + " alpha=" + s3.tailOpacity);

        // ---- D-6: 颜色不能被动画预设的恢复冲掉（用户要求"不换颜色"）----
        s3.tailColor = "#123456";
        s3.shadowColor = "#654321";
        restore(s3, 1);
        check("D-6 恢复动画默认值不碰颜色（用户要求：不换颜色）",
                "#123456".equals(s3.tailColor) && "#654321".equals(s3.shadowColor),
                "tail=" + s3.tailColor + " shadow=" + s3.shadowColor);

        // ---- D-7: 持久化字段不能被预设恢复冲掉 ----
        s3.rgbGradient = true;
        s3.cursorWidthPx = 5;
        s3.cursorHeightRatio = 0.4f;
        restore(s3, 1);
        check("D-7 恢复动画默认值不碰持久化字段（RGB / 宽 / 高）",
                s3.rgbGradient && s3.cursorWidthPx == 5
                        && Math.abs(s3.cursorHeightRatio - 0.4f) < 1e-3f,
                "rgb=" + s3.rgbGradient + " w=" + s3.cursorWidthPx + " h=" + s3.cursorHeightRatio);

        finish();
    }

    /**
     * 调用【真实的】restoreAnimPreset() —— 不复刻逻辑。
     *
     * <p><b>为什么这一点必须坚持</b>：如果探针自己复刻一遍「回到 animPresetBase」，
     * 那它测的是探针自己的实现，生产代码写错了也照样绿。
     * 做法：造一个 Configurable，把 state 塞进它的 pending，再反射调私有方法。
     */
    private static void restore(NeovideCursorSettings.State s, int expectBase) throws Exception {
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        // pending 是私有字段，直接写进去（不建面板，避免 UI 依赖）
        java.lang.reflect.Field pf =
                NeovideCursorConfigurable.class.getDeclaredField("pending");
        pf.setAccessible(true);
        pf.set(conf, s);
        // animBaseIdx 也同步（生产代码里由 createPanel/reset 从 State 读）
        java.lang.reflect.Field bf =
                NeovideCursorConfigurable.class.getDeclaredField("animBaseIdx");
        bf.setAccessible(true);
        bf.setInt(conf, CursorPresets.clampAnimPresetIndex(s.animPresetBase));

        Method m = NeovideCursorConfigurable.class.getDeclaredMethod("restoreAnimPreset");
        m.setAccessible(true);
        m.invoke(conf);
    }

    private static void finish() {
        System.out.println();
        System.out.printf("=== V-RESTORE-DEFAULT 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-RESTORE-DEFAULT RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-RESTORE-DEFAULT RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
