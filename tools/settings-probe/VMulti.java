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
 *   <li>字符移动量逐光标独立（本项目最重要的差异点）</li>
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

        // ---- M-2：字符移动量逐光标独立（核心差异点）----
        //
        // 场景：光标 A 从 offset 10 移到 11（移动 1 个字符）
        //       光标 B 从 offset 100 移到 103（移动 3 个字符）
        // 若共用一份 lastCaretOffset，第二次调用会算出 |103 - 11| = 92，
        // 荒谬的移动量会破坏「按字符判断短跳」。
        Object t3 = ctor.newInstance(cfg);
        Object t4 = ctor.newInstance(cfg);
        Method upChars = stClass.getDeclaredMethod("updateCharsMoved", int.class);
        upChars.setAccessible(true);

        upChars.invoke(t3, 10);      // 首次：返回 -1（无基线）
        upChars.invoke(t4, 100);
        int movedA = (int) upChars.invoke(t3, 11);
        int movedB = (int) upChars.invoke(t4, 103);

        check("M-2 字符移动量逐光标独立（A=1, B=3，不串扰）",
                movedA == 1 && movedB == 3,
                "A=" + movedA + " B=" + movedB);

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
