import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * V-MULTI：多光标核心行为（步骤 3）
 *
 * <p>headless 下无法创建真实 {@code Editor}，因此本探针直接构造 {@code SpringTrail}
 * 并验证「每个光标独立」的几件事：
 * <ol>
 *   <li>四角状态互不干扰</li>
 *   <li>上一帧视觉行逐光标独立（A1 分支判据，本项目最重要的差异点）</li>
 *   <li>起飞复制的是源的形状，不是目标点</li>
 *   <li>帧号标记-清除能在「合并导致数量不变」时正确回收</li>
 * </ol>
 */
public final class VMulti {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-MULTI：多光标核心行为 ===");

        Class<?> stClass = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.SpringTrail");
        Constructor<?> ctor = stClass.getDeclaredConstructor(NeovideConfig.class);
        ctor.setAccessible(true);

        NeovideConfig cfg = new NeovideConfig();

        // ---- M-1：两个 SpringTrail 的四角完全独立 ----
        Object t1 = ctor.newInstance(cfg);
        Object t2 = ctor.newInstance(cfg);
        invoke(stClass, t1, "setTarget", new Class[]{double.class, double.class, double.class, double.class},
                100.0, 100.0, 2.0, 32.0);
        invoke(stClass, t2, "setTarget", new Class[]{double.class, double.class, double.class, double.class},
                500.0, 500.0, 2.0, 32.0);

        double[] c1 = (double[]) invoke(stClass, t1, "cornersForProbe", new Class[]{});
        double[] c2 = (double[]) invoke(stClass, t2, "cornersForProbe", new Class[]{});
        check("M-1 两个光标的四角完全独立（坐标不串）",
                c1[0] != c2[0] && c1[1] != c2[1],
                String.format("t1=(%.1f,%.1f) t2=(%.1f,%.1f)", c1[0], c1[1], c2[0], c2[1]));

        // ---- M-2：上一帧视觉行逐光标独立（A1 核心差异点）----
        //
        // 场景：光标 A 上一帧视觉行 10，当前仍在 10 → 同一视觉行 → SHORT（0）
        //       光标 B 上一帧视觉行 100，当前到 10 → 视觉行变化 → CROSS（MAX）
        // 若共用一份 lastVisualLine，两者会被算成同一结果，
        // 多光标里「另一个光标的换行」会污染本光标的判定。
        Object t3 = ctor.newInstance(cfg);
        Object t4 = ctor.newInstance(cfg);
        Method markAt = stClass.getDeclaredMethod("markAt", int.class, int.class, long.class);
        Method lastLine = stClass.getDeclaredMethod("lastVisualLine");
        Method semantic = stClass.getDeclaredMethod(
                "semanticCharsMoved", boolean.class, int.class, int.class);
        markAt.setAccessible(true);
        lastLine.setAccessible(true);
        semantic.setAccessible(true);

        markAt.invoke(t3, 10, 0, 1L);
        markAt.invoke(t4, 100, 0, 1L);
        int lineA = (int) lastLine.invoke(t3);
        int lineB = (int) lastLine.invoke(t4);
        int charsA = (int) semantic.invoke(null, true, lineA, 10);
        int charsB = (int) semantic.invoke(null, true, lineB, 10);

        check("M-2 上一帧视觉行逐光标独立（A=10, B=100；语义 A=0, B=MAX，不串扰）",
                lineA == 10 && lineB == 100
                        && charsA == 0 && charsB == Integer.MAX_VALUE,
                "lineA=" + lineA + " lineB=" + lineB
                        + " charsA=" + charsA + " charsB=" + charsB);

        // ---- M-3：起飞复制的是源的【当前形状】 ----
        Object src = ctor.newInstance(cfg);
        invoke(stClass, src, "setTarget", new Class[]{double.class, double.class, double.class, double.class},
                200.0, 200.0, 2.0, 32.0);
        // 让源的角点偏离目标（模拟"正在拖尾"）
        pushCorners(stClass, src, 5.0);

        Object dst = ctor.newInstance(cfg);
        Method startFrom = stClass.getDeclaredMethod("startFrom", stClass);
        startFrom.setAccessible(true);
        startFrom.invoke(dst, src);

        double[] srcCorners = (double[]) invoke(stClass, src, "cornersForProbe", new Class[]{});
        double[] dstCorners = (double[]) invoke(stClass, dst, "cornersForProbe", new Class[]{});
        boolean same = true;
        for (int i = 0; i < 8; i++) {
            if (Math.abs(srcCorners[i] - dstCorners[i]) > 1e-9) { same = false; }
        }
        check("M-3 起飞复制源的当前形状（含偏移，不是目标点）", same,
                String.format("src首角=(%.2f,%.2f) dst首角=(%.2f,%.2f)",
                        srcCorners[0], srcCorners[1], dstCorners[0], dstCorners[1]));

        // ---- M-4：源的角点确实偏离目标（否则 M-3 是空验证）----
        //
        // 【探针修正】原期望值写成 199.0（目标中心 200 - 半宽 1），
        // 但 pushCorners 把四角整体位移了 +5，所以正确值是 204.0。
        // 判据改为「与目标位置的偏移量 ≈ 5」，不写死绝对坐标。
        double targetCornerX = 200.0 - 1.0;      // 目标中心 200，光标宽 2
        double deviation = srcCorners[0] - targetCornerX;
        boolean deviated = Math.abs(deviation - 5.0) < 0.01;
        check("M-4 源角点确实偏离目标 5px（证明 M-3 不是空验证）", deviated,
                String.format("目标角点=%.1f 实际=%.2f 偏移=%.2f", targetCornerX, srcCorners[0], deviation));

        // ---- M-5：未初始化的源不复制（避免凭空出现）----
        Object fresh = ctor.newInstance(cfg);
        Object dst2 = ctor.newInstance(cfg);
        invoke(stClass, dst2, "setTarget", new Class[]{double.class, double.class, double.class, double.class},
                900.0, 900.0, 2.0, 32.0);
        double[] before = (double[]) invoke(stClass, dst2, "cornersForProbe", new Class[]{});
        startFrom.invoke(dst2, fresh);   // fresh 未初始化 → 应无效果
        double[] after = (double[]) invoke(stClass, dst2, "cornersForProbe", new Class[]{});
        boolean unchanged = Math.abs(before[0] - after[0]) < 1e-9;
        check("M-5 未初始化的源不复制（避免新光标从 (0,0) 飞入）", unchanged,
                String.format("调用前后 x0=%.2f", after[0]));

        // ---- M-6：lastSeenFrame 字段存在（帧号清理的前提）----
        Field seen = stClass.getDeclaredField("lastSeenFrame");
        seen.setAccessible(true);
        Object t5 = ctor.newInstance(cfg);
        long initVal = (long) seen.get(t5);
        seen.set(t5, 42L);
        long setVal = (long) seen.get(t5);
        check("M-6 lastSeenFrame 可读写（帧号标记-清除的前提）",
                initVal == -1L && setVal == 42L,
                "初值=" + initVal + " 设置后=" + setVal);

        // ---- M-7：maxOffset 逐光标独立（辉光强度各自算）----
        Object still = ctor.newInstance(cfg);
        invoke(stClass, still, "setTarget", new Class[]{double.class, double.class, double.class, double.class},
                300.0, 300.0, 2.0, 32.0);
        Method upd = stClass.getDeclaredMethod("update", double.class);
        upd.setAccessible(true);
        upd.invoke(still, 0.008);           // 静止光标：偏移应接近 0
        double stillOffset = (double) invoke(stClass, still, "maxOffset", new Class[]{});

        Object moving = ctor.newInstance(cfg);
        invoke(stClass, moving, "setTarget", new Class[]{double.class, double.class, double.class, double.class},
                300.0, 300.0, 2.0, 32.0);
        invoke(stClass, moving, "setTarget", new Class[]{double.class, double.class, double.class, double.class},
                400.0, 300.0, 2.0, 32.0);   // 目标突变 → 角点偏离
        upd.invoke(moving, 0.008);
        double movingOffset = (double) invoke(stClass, moving, "maxOffset", new Class[]{});

        check("M-7 maxOffset 逐光标独立（静止≈0，运动>0）",
                stillOffset < 0.5 && movingOffset > 1.0,
                String.format("静止=%.2f 运动=%.2f", stillOffset, movingOffset));

        System.out.println();
        System.out.printf("=== V-MULTI 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-MULTI RESULT: FAILED"); System.exit(1); }
        System.out.println("V-MULTI RESULT: ALL PASS");
    }

    /** 把四角推到偏离目标的位置（模拟"正在拖尾"） */
    private static void pushCorners(Class<?> stClass, Object trail, double dx) throws Exception {
        Field f = stClass.getDeclaredField("corners");
        f.setAccessible(true);
        TrailCorner[] corners = (TrailCorner[]) f.get(trail);
        // 直接改 currentX（TrailCorner 没有 setter，用 placeAt）
        for (TrailCorner c : corners) {
            c.placeAt(c.getCurrentX() + dx, c.getCurrentY());
        }
    }

    private static Object invoke(Class<?> cls, Object target, String name,
                                 Class<?>[] sig, Object... args) throws Exception {
        Method m = cls.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
