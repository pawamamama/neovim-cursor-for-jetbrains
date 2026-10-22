
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

/**
 * 自测：syncAllControlsFromPending 补 super.reset() 之后的递归/重复风险。
 * 审查员还没回来，我自己先验最危险的一条：死循环。
 */
public final class VRecurse {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        System.out.println("=== super.reset() 递归与重复调用自测 ===");
        V0Env.install();

        // 1) 连续 30 次切预设 —— 若有回环会栈溢出或极慢
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        long t0 = System.nanoTime();
        boolean ok = true;
        try {
            for (int i = 0; i < 30; i++) {
                conf.pickAnimPresetForProbe(i % 3);
            }
        } catch (Throwable t) {
            ok = false;
            System.out.println("  [异常] " + t);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("R1 连续 30 次切预设不抛异常（含 StackOverflow）", ok, "");
        check("R2 30 次切换耗时 < 3000ms（无指数级重复）", ms < 3000, ms + " ms");

        // 2) apply 内部调 syncAll 是否与 A8 段冲突：连续两次 apply 值必须一致
        conf.reset();
        conf.pickAnimPresetForProbe(1);
        conf.apply();
        float firstAnim = V0Env.svc.getState().animationLength;
        conf.apply();
        float secondAnim = V0Env.svc.getState().animationLength;
        check("R3 连续两次 apply 后值稳定（A8 段与 super.reset 不冲突）",
                Math.abs(firstAnim - secondAnim) < 1e-6f,
                firstAnim + " -> " + secondAnim);

        // 3) 在一个「值偏离预设」的 state 上 reset，不派生垃圾副本
        NeovideCursorSettings.State st = V0Env.svc.getState();
        st.animationLength = 0.22f;
        st.glowLayers = 9;
        st.customAnimPresets = "";
        st.animCustomName = "";
        conf.reset();
        check("R4 reset 装载偏离值时不凭空派生副本（suppressDerive 有效）",
                conf.pendingForProbe().animCustomName.isEmpty()
                        && CustomPresets.parse(conf.pendingForProbe().customAnimPresets).isEmpty(),
                "name=「" + conf.pendingForProbe().animCustomName + "」 表="
                        + conf.pendingForProbe().customAnimPresets);

        // 4) 控件状态与 pending 必须同步（这是本轮修复的核心）
        conf.pickAnimPresetForProbe(2);   // 纯平滑输入：useShadow=false
        check("R5 切「纯平滑输入」后 pending.useShadow=false",
                !conf.pendingForProbe().useShadow, "useShadow=" + conf.pendingForProbe().useShadow);
        conf.pickAnimPresetForProbe(0);   // 基础：useShadow=true
        check("R6 切回「基础」后 pending.useShadow=true",
                conf.pendingForProbe().useShadow, "useShadow=" + conf.pendingForProbe().useShadow);

        System.out.println();
        System.out.printf("=== RECURSE 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("RECURSE RESULT: FAILED"); System.exit(1); }
        System.out.println("RECURSE RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
