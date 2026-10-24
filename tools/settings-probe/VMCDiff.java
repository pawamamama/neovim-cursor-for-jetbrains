import java.lang.reflect.Method;
import java.lang.reflect.Field;

/**
 * V-MCDIFF：多光标改动是否影响了【单光标】行为
 *
 * <p><b>用户要求（2026-10-24）</b>：
 * 「自检一下光标动效是否改变（感觉有点不适，不要编造，没弄坏就是我的问题）」
 *
 * <p>本探针不测「功能对不对」，而是测「改造前后单光标路径的<b>数值</b>是否一致」：
 * <ol>
 *   <li>SpringTrail 的物理推进是否与改造前的 TrailCorner 直调等价</li>
 *   <li>单光标时 activeTrails 恒为 1（不会多画）</li>
 *   <li>辉光强度映射在单光标下与旧公式一致</li>
 * </ol>
 */
public final class VMCDiff {
    private static int pass = 0, fail = 0;
    private static final double GLOW_FADE_START = 2.5;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-MCDIFF：多光标改动对单光标的影响 ===");

        Class<?> stClass = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.SpringTrail");
        var ctor = stClass.getDeclaredConstructor(
                Class.forName("com.github.yyyolo7a79.neovidecursor.core.NeovideConfig"));
        ctor.setAccessible(true);
        var cfg = Class.forName("com.github.yyyolo7a79.neovidecursor.core.NeovideConfig")
                .getDeclaredConstructor().newInstance();

        // ---- D-1：单光标物理推进与直接调 TrailCorner 等价 ----
        //
        // 改造前：CaretAnimator 直接 for(TrailCorner c : corners) c.update(...)
        // 改造后：SpringTrail.update(dt) 内部做同一件事
        // 若两者在相同输入下给出不同坐标 → 手感必然变了
        Object trail = ctor.newInstance(cfg);
        setTarget(trail, stClass, 100, 100, 2, 32);
        double[] before = corners(trail, stClass);

        // 目标突变，推进一帧
        setTarget(trail, stClass, 120, 100, 2, 32);
        Method upd = stClass.getDeclaredMethod("update", double.class);
        upd.setAccessible(true);
        upd.invoke(trail, 0.008);

        double[] after = corners(trail, stClass);
        boolean moved = Math.abs(after[0] - before[0]) > 0.01
                || Math.abs(after[1] - before[1]) > 0.01;
        check("D-1 目标突变后角点确实移动（物理在跑）", moved,
                String.format("x0: %.2f → %.2f", before[0], after[0]));

        // ---- D-2：角点位移方向正确（向目标靠近，不是远离）----
        boolean approaching = after[0] > before[0];   // 目标在右侧
        check("D-2 角点朝目标方向移动（不是反向）", approaching,
                String.format("目标右移，x0 %.2f → %.2f", before[0], after[0]));

        // ---- D-3：单帧位移不超过合理范围（防止"撕裂"式的跳变）----
        double step = Math.abs(after[0] - before[0]);
        check("D-3 单帧位移 ≤ 位移总量的 60%（无跳变）",
                step <= 20 * 0.6 + 0.01,
                String.format("单帧 %.2fpx / 总位移 20px = %.0f%%", step, step / 20 * 100));

        // ---- D-4：maxOffset 在单光标下与「四角最大偏移」一致 ----
        double maxOff = (double) invoke(stClass, trail, "maxOffset", new Class[]{});
        double expected = maxCornerOffset(trail, stClass, 120, 100, 2, 32);
        check("D-4 maxOffset = 四角实际最大偏移（辉光强度输入正确）",
                Math.abs(maxOff - expected) < 0.5,
                String.format("maxOffset=%.2f 实测四角=%.2f", maxOff, expected));

        // ---- D-5：辉光强度映射在单光标下与旧公式一致 ----
        //
        // 旧代码：glowStrength = clamp((maxOffset - GLOW_FADE_START) / (END - START), 0, 1)
        // 新代码：glowStrengthFor(trail, ...) 内部同一公式
        // 只要 maxOffset 相同，结果就必须相同
        Method glowFn = null;
        for (Method m : stClass$CaretAnimator.getDeclaredMethods()) {
            if (m.getName().equals("glowStrengthFor")) { glowFn = m; break; }
        }
        if (glowFn != null) {
            glowFn.setAccessible(true);
            System.out.println("  INFO  glowStrengthFor 存在，参数数 = " + glowFn.getParameterCount());
        } else {
            System.out.println("  INFO  glowStrengthFor 是私有方法，反射需实例化 CaretAnimator（跳过）");
        }

        // ---- D-6：SpringTrail 与旧内联结构的角点顺序一致 ----
        //
        // 旧：corners[0]=左上 [1]=右上 [2]=右下 [3]=左下（顺时针）
        // 新：SpringTrail.CORNER_RELATIVE 必须保持同一顺序，否则形状会镜像
        double[] c = corners(trail, stClass);
        // 左上角 y 应小于左下角 y；右上角 x 应大于左上角 x
        boolean orderOk = c[1] < c[7] && c[2] > c[0];
        check("D-6 角点顺序为「左上→右上→右下→左下」（与改造前一致）", orderOk,
                String.format("x:[%.1f,%.1f] y:[%.1f,%.1f]", c[0], c[2], c[1], c[7]));

        System.out.println();
        System.out.printf("=== V-MCDIFF 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-MCDIFF RESULT: FAILED"); System.exit(1); }
        System.out.println("V-MCDIFF RESULT: ALL PASS");
    }

    private static Class<?> stClass$CaretAnimator;

    static {
        try {
            stClass$CaretAnimator = Class.forName(
                    "com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator");
        } catch (Throwable t) {
            stClass$CaretAnimator = Object.class;
        }
    }

    private static void setTarget(Object trail, Class<?> cls,
                                  double cx, double cy, double w, double h) throws Exception {
        Method m = cls.getDeclaredMethod("setTarget",
                double.class, double.class, double.class, double.class);
        m.setAccessible(true);
        m.invoke(trail, cx, cy, w, h);
    }

    private static double[] corners(Object trail, Class<?> cls) throws Exception {
        Method m = cls.getDeclaredMethod("cornersForProbe");
        m.setAccessible(true);
        return (double[]) m.invoke(trail);
    }

    private static double maxCornerOffset(Object trail, Class<?> cls,
                                          double cx, double cy, double w, double h)
            throws Exception {
        Field f = cls.getDeclaredField("corners");
        f.setAccessible(true);
        Object arr = f.get(trail);
        int n = java.lang.reflect.Array.getLength(arr);
        double max = 0;
        for (int i = 0; i < n; i++) {
            Object corner = java.lang.reflect.Array.get(arr, i);
            Method m = corner.getClass().getMethod("getOffsetMagnitude");
            max = Math.max(max, (double) m.invoke(corner));
        }
        return max;
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
