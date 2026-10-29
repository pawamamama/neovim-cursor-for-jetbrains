import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.lang.reflect.Method;

/**
 * V-PRESET-RESET：自定义预设点「恢复默认值」必须做到四件事，缺一不可。
 *
 * <ol>
 *   <li><b>R-2</b> 被手调的字段回到它派生自的父预设常量；</li>
 *   <li><b>R-3</b> 仍<b>留在副本上</b>（{@code animCustomName} 保留，不跳回内置预设）；</li>
 *   <li><b>R-4</b> 副本条目（{@code customAnimPresets} 里那条快照）<b>一个字节都不许被改写</b>；</li>
 *   <li><b>R-5</b> 父预设常量全程不变。</li>
 * </ol>
 *
 * <h2>为什么必须新增这个探针（同一个 bug 的第三次回归）</h2>
 *
 * <p>这个按钮已经错过两次，两次都是「改了值，顺手把别的东西也改了」：
 * <pre>
 *   0.9.9~0.9.67  副本上 restore 副本自己的快照     → 空操作，用户点了没反应
 *   b47443b       值回父预设 + 清空副本名           → 用户说的「跳回父预设」
 *   bd932b9       再追加「把副本快照改写成父预设值」 → 用户手调的数据被永久抹掉
 * </pre>
 *
 * <p>{@link VRestoreDefault} 只守了「值回到哪」（D-1~D-7），
 * 它<b>测不到「副本条目有没有被改」</b> —— 而那正是 bd932b9 引入数据丢失的地方。
 * 本探针补的就是这个缺口：R-4 直接比对 {@code customAnimPresets} 里那条快照的原文。
 *
 * <h2>写法约束（CLAUDE.md 陷阱 3）</h2>
 *
 * <p>不复刻任何生产逻辑：派生用 {@link NeovideCursorConfigurable#applyAnimPresetFields}、
 * 快照用 {@link SettingsField#snapshot}、存取用 {@link CustomPresets#put}/{@link CustomPresets#get}，
 * 恢复则通过反射调真实的 {@code restoreAnimPreset()}。
 */
public final class VPresetReset {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-PRESET-RESET：恢复默认值不得毁掉副本条目 ===");
        // restoreAnimPreset 末尾会走 syncAllControlsFromPending() → reset() → createPanel()，
        // 需要 IntelliJ 平台桩（ActionManager / JBPopupFactory 等）。
        V0Env.install();

        // 父预设：缎带（下标 1）。选它是因为它的 animationLength 与「基础」不同（0.28 vs 0.10），
        // 这样「值回到了父预设」与「值根本没动」能被区分开。
        final int parentIdx = 1;
        CursorPresets.AnimPreset parent = CursorPresets.ANIM_PRESETS[parentIdx];

        // ---- 先记下父预设的 5 个值，最后一步要证明它们全程没变 ----
        final float pAnim = parent.animationLength();
        final float pShort = parent.shortAnimationLength();
        final float pTailSize = parent.trailSize();
        final int pLayers = parent.glowLayers();
        final float pAlpha = parent.tailOpacity();

        // ==================== R-1：派生一个副本，初始值 == 父预设值 ====================
        //
        // 这一步是「派生的基线」。用户 2026-10-29 拍板：副本的默认值就是它派生自的
        // 那个内置预设的常量 —— 所以基线直接由生产方法 applyAnimPresetFields 写出来。
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.animPresetBase = parentIdx;
        s.animCustomName = "缎带 副本";
        NeovideCursorConfigurable.applyAnimPresetFields(s, parent);

        final float baseAnim = s.animationLength;
        final float baseAlpha = s.tailOpacity;
        final float baseBlur = s.shadowBlurFactor;
        final int baseLayers = s.glowLayers;

        check("R-1 副本派生初始值 == 父预设值（4 个字段）",
                near(baseAnim, pAnim) && near(baseAlpha, pAlpha)
                        && near(baseBlur, parent.shadowBlurFactor()) && baseLayers == pLayers,
                "anim=" + baseAnim + "（父 " + pAnim + "）alpha=" + baseAlpha
                        + " blur=" + baseBlur + " layers=" + baseLayers);

        // 把副本写进自定义预设表 —— 这是「派生完成」的持久化形态。
        // 【2026-10-29 父链】必须带父引用：不带的话「恢复默认值」没有目标，
        // 按钮会置灰（新模型的正确行为），R-2 就测不到东西了。
        s.customAnimPresets = CustomPresets.put("", s.animCustomName,
                CustomPresets.builtinParent(parentIdx), SettingsField.snapshot(s));
        // R-4 的对照基线：派生完成、用户还没手调时的快照。
        final String cleanSnapshot = CustomPresets.get(s.customAnimPresets, s.animCustomName);

        // ==================== 模拟用户手调 3 个字段 ====================
        //
        // 3 个字段都必须属于 PRESET_FIELDS（否则它们本来就不进快照，测了也没意义）：
        //   animationLength / tailOpacity / shadowBlurFactor 全是预设域字段。
        //
        // 同时按 afterPendingEdited 分支 A 的行为更新副本快照 ——
        // 这一步是真实交互的复现（用户在副本上改一个数，快照立刻跟着变）。
        s.animationLength = 0.05f;
        s.tailOpacity = 0.20f;
        s.shadowBlurFactor = 0.90f;
        s.customAnimPresets = CustomPresets.put(
                s.customAnimPresets, s.animCustomName, SettingsField.snapshot(s));
        final String dirtySnapshot = CustomPresets.get(s.customAnimPresets, s.animCustomName);

        // 前置条件：这次手调确实改了快照，否则 R-4 是「没变化所以通过」的假绿。
        check("R-0 手调后副本快照确实变了（R-4 的前提，防止假绿）",
                dirtySnapshot != null && cleanSnapshot != null
                        && !dirtySnapshot.equals(cleanSnapshot),
                "clean=" + shortSnap(cleanSnapshot) + " dirty=" + shortSnap(dirtySnapshot));

        // ==================== 点「恢复默认值」====================
        restore(s);

        // ---- R-2：3 个手调字段都回到派生时的值 ----
        check("R-2 恢复后 3 个手调字段回到派生时的值（anim / alpha / blur）",
                near(s.animationLength, baseAnim) && near(s.tailOpacity, baseAlpha)
                        && near(s.shadowBlurFactor, baseBlur),
                "anim=" + s.animationLength + "（期望 " + baseAnim + "）alpha=" + s.tailOpacity
                        + "（期望 " + baseAlpha + "）blur=" + s.shadowBlurFactor
                        + "（期望 " + baseBlur + "）");

        // ---- R-3：仍留在副本上 ----
        check("R-3 恢复后仍留在副本上（animCustomName 保留，不跳回父预设）",
                "缎带 副本".equals(s.animCustomName), "name=\"" + s.animCustomName + "\"");

        // ---- R-4：副本条目一个字节都没被改写 ----
        //
        // 这是 bd932b9 引入数据丢失的地方：它把副本快照改写成父预设值，
        // 用户手调过的整套值就此永久消失。恢复按钮只该改「当前值」，不该改「存下来的预设」。
        final String after = CustomPresets.get(s.customAnimPresets, s.animCustomName);
        check("R-4 副本条目未被改写（手调值仍在，恢复只动当前值）",
                dirtySnapshot != null && dirtySnapshot.equals(after),
                "before=" + shortSnap(dirtySnapshot) + " after=" + shortSnap(after));

        // ---- R-5：父预设常量全程未被改动 ----
        check("R-5 父预设常量全程未被改动（5 个字段）",
                near(parent.animationLength(), pAnim)
                        && near(parent.shortAnimationLength(), pShort)
                        && near(parent.trailSize(), pTailSize)
                        && parent.glowLayers() == pLayers
                        && near(parent.tailOpacity(), pAlpha),
                "anim=" + parent.animationLength() + " short=" + parent.shortAnimationLength()
                        + " trail=" + parent.trailSize() + " layers=" + parent.glowLayers()
                        + " alpha=" + parent.tailOpacity());

        finish();
    }

    /**
     * 调用【真实的】restoreAnimPreset() —— 不复刻逻辑。
     *
     * <p>做法与 {@code VRestoreDefault} 同源：造一个 Configurable，把 state 塞进 pending，
     * 再反射调私有方法。若探针自己复刻「回到 animPresetBase」，测的就是探针自己。
     */
    private static void restore(NeovideCursorSettings.State s) throws Exception {
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        java.lang.reflect.Field pf =
                NeovideCursorConfigurable.class.getDeclaredField("pending");
        pf.setAccessible(true);
        pf.set(conf, s);
        java.lang.reflect.Field bf =
                NeovideCursorConfigurable.class.getDeclaredField("animBaseIdx");
        bf.setAccessible(true);
        bf.setInt(conf, CursorPresets.clampAnimPresetIndex(s.animPresetBase));

        Method m = NeovideCursorConfigurable.class.getDeclaredMethod("restoreAnimPreset");
        m.setAccessible(true);
        m.invoke(conf);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < CursorPresets.EPS;
    }

    /** 快照串可能很长 —— 只打印前 60 字符，避免刷屏。 */
    private static String shortSnap(String s) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= 60 ? s : s.substring(0, 60) + "…(" + s.length() + ")";
    }

    private static void finish() {
        System.out.println();
        System.out.printf("=== V-PRESET-RESET 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-PRESET-RESET RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-PRESET-RESET RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + n + "   [" + d + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + n + "   [" + d + "]");
        }
    }
}
