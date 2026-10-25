import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * V-REVIEW-FIXES：代码审查抓到的 4 条 bug + 平滑移出预设，逐条锁住。
 *
 * <p><b>S-1 输入平滑不参与预设</b>：用户明确「平滑一直不参与预设」。
 * 0.9.67 我误读了「其他持久化 比如平滑关闭」，错误地把它放进预设域。
 *
 * <p><b>R-1 RenderScheduler.thread 必须是 volatile</b>：unregister/wakeUp 在无锁
 * 状态下读它，非 volatile 时可能读到陈旧值 → 跳过 interrupt → 多等 600ms。
 *
 * <p><b>R-2 RenderScheduler.loop 必须有 try/catch</b>：否则线程死后 thread 仍非 null，
 * 后续 register 全去 interrupt 死线程 → 所有动画永久停摆且无信号。
 *
 * <p><b>A-1 refreshTarget 的 catch 不能静默</b>：原来只有一行注释，
 * 确定性异常会让拖尾永久冻结且零日志。
 *
 * <p><b>M-1 syncConfigToStateAndPersist 必须有初始化守卫</b>：
 * CONFIG 未从 State 灌满时回写 = 用编译期默认值覆盖用户设置并落盘。
 */
public final class VReviewFixes {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-REVIEW-FIXES：审查发现的 bug 逐条锁定 ===");
        V0Env.install();

        // ---------- S-1：输入平滑不参与预设 ----------
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.typingSmoothness = 0.73f;                       // 用户调好的值
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            NeovideCursorConfigurable.applyAnimPresetFields(
                    s, CursorPresets.ANIM_PRESETS[i]);
        }
        check("S-1 切遍所有预设后 typingSmoothness 保持不变（平滑不参与预设）",
                Math.abs(s.typingSmoothness - 0.73f) < 1e-4f,
                "切完后 = " + s.typingSmoothness + "（期望 0.73）");

        // ---------- R-1：thread 必须 volatile ----------
        Field tf = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.RenderScheduler")
                .getDeclaredField("thread");
        check("R-1 RenderScheduler.thread 声明为 volatile",
                Modifier.isVolatile(tf.getModifiers()),
                Modifier.toString(tf.getModifiers()));

        // ---------- R-2 / A-1 / M-1：源码里必须有相应结构 ----------
        String rs = readSrc("ui/RenderScheduler.java");
        check("R-2 loop() 内有 try/catch 包裹（线程异常不会静默死亡）",
                rs.contains("catch (Throwable t)") && rs.contains("consecutiveFailures"),
                rs.contains("consecutiveFailures") ? "已加" : "缺失");
        check("R-2b 连续失败后会置 thread=null 自愈",
                rs.contains("thread = null") && rs.contains("重建"),
                rs.contains("重建") ? "已加" : "缺失");

        String ca = readSrc("ui/CaretAnimator.java");
        check("A-1 refreshTarget 的 catch 里有日志（不再静默吞）",
                ca.contains("refreshFailureCount") && ca.contains("refreshTarget 抛异常"),
                ca.contains("refreshFailureCount") ? "已加" : "缺失");

        String mgr = readSrc("ui/NeovideCaretManager.java");
        check("M-1 syncConfigToStateAndPersist 有初始化守卫",
                mgr.contains("CONFIG 尚未从 State 同步"),
                mgr.contains("CONFIG 尚未从 State 同步") ? "已加" : "缺失");

        // ---------- M-2：守卫真的会先拉再写（行为验证）----------
        // 直接调 syncConfigToStateAndPersist 需要完整平台环境，这里只验证
        // 「settingsSynced 为 false 时 syncStateToConfig 会被调用」这条结构。
        check("M-2 守卫里调用了 svc.syncStateToConfig() 再回写",
                mgr.contains("svc.syncStateToConfig();") || mgr.contains("syncStateToConfig();"),
                "已加");

        System.out.println();
        System.out.printf("=== V-REVIEW-FIXES 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-REVIEW-FIXES RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-REVIEW-FIXES RESULT: ALL PASS");
    }

    private static String readSrc(String rel) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/github/yyyolo7a79/neovidecursor/" + rel)),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}