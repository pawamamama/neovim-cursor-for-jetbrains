
import com.github.yyyolo7a79.neovidecursor.core.AfterimageTrail;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;

/**
 * 自查冻结语义的关键风险（headless，纯 core 类）。
 *
 * 风险 1：残影模式冻结很久后切回，会不会一次吐出大量 ghost？
 * 风险 2：collectVisible 的 break 语义是否正确（依赖"新->旧"排序）？
 */
public final class VFreeze {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        System.out.println("=== V-FREEZE：冻结语义自查 ===");

        NeovideConfig cfg = new NeovideConfig();
        cfg.afterimageLifetime = 0.22f;

        // ---- 风险 1：冻结很久后 collectVisible ----
        AfterimageTrail trail = new AfterimageTrail(cfg);
        trail.setLifetime(0.22f);

        long t0 = 1_000_000_000L;
        // 记录 50 个点（模拟活跃期）
        for (int i = 0; i < 50; i++) {
            trail.record(100 + i * 2, 100, 2, 32, t0 + i * 8_000_000L);
        }
        int active = trail.collectVisible(t0 + 49 * 8_000_000L).size();
        System.out.println("  活跃期可见残影 = " + active);

        // 冻结 5 秒后
        long frozen = t0 + 49 * 8_000_000L + 5_000_000_000L;
        int afterFreeze = trail.collectVisible(frozen).size();
        check("F1 冻结 5 秒后 collectVisible 返回空（不会一次吐出大量 ghost）",
                afterFreeze == 0, "返回 " + afterFreeze + " 个");

        // 冻结 0.5 秒（lifetime=0.22s，也已全部超时）
        int afterHalf = trail.collectVisible(t0 + 49 * 8_000_000L + 500_000_000L).size();
        check("F2 冻结 0.5 秒（> lifetime 0.22s）后也是空", afterHalf == 0,
                "返回 " + afterHalf + " 个");

        // 冻结 0.2 秒（< lifetime）：部分仍可见，验证 break 语义
        int afterShort = trail.collectVisible(t0 + 49 * 8_000_000L + 200_000_000L).size();
        System.out.println("  冻结 0.2 秒后可见 = " + afterShort + " 个（lifetime=0.22s）");

        // ---- 风险 2：验证 break 语义（新->旧排序）----
        AfterimageTrail t2 = new AfterimageTrail(cfg);
        t2.setLifetime(0.22f);
        t2.record(100, 100, 2, 32, t0);                    // 最旧
        t2.record(200, 100, 2, 32, t0 + 100_000_000L);     // 中间
        t2.record(300, 100, 2, 32, t0 + 200_000_000L);     // 最新

        // 在 t0+200ms 时刻：最旧的已超时(200ms)，中间/最新可见
        //
        // 【探针修正】不能断言"恰好 2 个" —— record() 会在两点之间【自动补插值点】
        // （MAX_GAP_PX 逻辑，让残影沿轨迹均匀分布）。所以 3 个手写点会变成几十个。
        // 真正要验证的是：超时点【确实被排除】，即返回集合里最旧的那个的年龄 <= lifetime。
        var vis = t2.collectVisible(t0 + 200_000_000L);
        long nowT = t0 + 200_000_000L;
        boolean noExpired = true;
        long oldestAge = 0;
        for (var gh : vis) {
            long age = nowT - gh.timeNanos;
            if (age > 220_000_000L) { noExpired = false; }
            if (age > oldestAge) { oldestAge = age; }
        }
        check("F3 返回集合里没有超时点（break 语义正确，允许插值点）",
                noExpired && !vis.isEmpty(),
                String.format("共 %d 个（含插值点），最旧年龄 %.0fms <= lifetime 220ms",
                        vis.size(), oldestAge / 1_000_000.0));

        // 验证返回顺序：队首应该是最新的
        if (vis.size() >= 2) {
            boolean newestFirst = vis.get(0).centerX > vis.get(vis.size() - 1).centerX;
            check("F4 返回顺序为「新->旧」（break 语义依赖此排序）",
                    newestFirst, String.format("首=%.0f 尾=%.0f",
                            vis.get(0).centerX, vis.get(vis.size() - 1).centerX));
        }

        System.out.println();
        System.out.printf("=== V-FREEZE 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-FREEZE RESULT: FAILED"); System.exit(1); }
        System.out.println("V-FREEZE RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
