import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * V-UNIFORM：多光标行间【加速度一致性】
 *
 * <p><b>用户反馈（2026-10-24 第五轮）</b>：「加速度还是不均匀咋办」
 *
 * <p><b>已知的两个不均匀来源</b>：
 * <ol>
 *   <li><b>charsMoved 行间不同</b> → isShortJump 判定不同 → 时长差 2 倍以上
 *       （已修：块选区所有行共享第 0 行的移动量）</li>
 *   <li><b>rank 分配不同</b> → trailFactors 不同 → 时长不同
 *       （这是<b>设计如此</b>：前缘快后缘慢才形成拉伸）</li>
 * </ol>
 *
 * <p>本探针验证：<b>相同输入下，两个 SpringTrail 的物理推进必须逐位一致</b>。
 * 若不一致，说明还有隐藏的串扰。
 */
public final class VUniform {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-UNIFORM：行间加速度一致性 ===");

        Class<?> stClass = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.SpringTrail");
        var ctor = stClass.getDeclaredConstructor(NeovideConfig.class);
        ctor.setAccessible(true);
        var cfg = new NeovideConfig();

        // ---- U-1：相同起点 + 相同目标 → 两帧后位置必须一致 ----
        //
        // 模拟两行做完全相同的移动。若结果不同，说明有隐藏状态串扰。
        Object t1 = ctor.newInstance(cfg);
        Object t2 = ctor.newInstance(cfg);

        // 起点不同（不同行），但【相对位移相同】
        setTarget(t1, stClass, 100, 100, 2, 32);
        setTarget(t2, stClass, 100, 200, 2, 32);   // 只差 y

        // 同样的相对位移：都向右 8px
        setTarget(t1, stClass, 108, 100, 2, 32);
        setTarget(t2, stClass, 108, 200, 2, 32);

        // 推进 3 帧
        Method upd = stClass.getDeclaredMethod("update", double.class);
        upd.setAccessible(true);
        for (int f = 0; f < 3; f++) {
            upd.invoke(t1, 0.008);
            upd.invoke(t2, 0.008);
        }

        double[] c1 = corners(t1, stClass);
        double[] c2 = corners(t2, stClass);

        // 相对各自的 y 偏移后应当一致
        boolean sameX = Math.abs(c1[0] - c2[0]) < 0.01 && Math.abs(c1[2] - c2[2]) < 0.01;
        boolean sameRelY = Math.abs((c1[1] - 100) - (c2[1] - 200)) < 0.01;
        check("U-1 相同相对位移 → 两行 x 坐标一致（无串扰）", sameX,
                String.format("x0: %.3f vs %.3f", c1[0], c2[0]));
        check("U-2 相同相对位移 → 两行相对 y 一致", sameRelY,
                String.format("relY0: %.3f vs %.3f", c1[1] - 100, c2[1] - 200));

        // ---- U-3：不同 charsMoved 会导致不同时长（这是已知的两个分支）----
        //
        // 验证 TrailCorner 确实按 charsMoved 分支。这是「不均匀」的根源，
        // 修复方式是调用方共享同一个值，而不是改 TrailCorner。
        TrailCorner cShort = new TrailCorner(-0.5, -0.5, cfg);
        TrailCorner cLong = new TrailCorner(-0.5, -0.5, cfg);
        cShort.snapTo(2, 32, 100, 100);
        cLong.snapTo(2, 32, 100, 100);

        cShort.setCharsMoved(1);      // 短跳
        cLong.setCharsMoved(10);      // 跨行
        cShort.jump(2, 32, 108, 100, 0);
        cLong.jump(2, 32, 108, 100, 0);

        // 推进相同帧数，位移应当不同（证明两个分支确实不同）
        for (int f = 0; f < 2; f++) {
            cShort.update(2, 32, 108, 100, 0.008, false);
            cLong.update(2, 32, 108, 100, 0.008, false);
        }
        double dShort = Math.abs(cShort.getCurrentX() - 99.0);
        double dLong = Math.abs(cLong.getCurrentX() - 99.0);
        check("U-3 不同 charsMoved 确实产生不同位移（证明必须共享）",
                Math.abs(dShort - dLong) > 0.1,
                String.format("短跳位移=%.2f 跨行位移=%.2f", dShort, dLong));

        System.out.println();
        System.out.printf("=== V-UNIFORM 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-UNIFORM RESULT: FAILED"); System.exit(1); }
        System.out.println("V-UNIFORM RESULT: ALL PASS");
    }

    private static void setTarget(Object t, Class<?> cls,
                                  double cx, double cy, double w, double h) throws Exception {
        Method m = cls.getDeclaredMethod("setTarget",
                double.class, double.class, double.class, double.class);
        m.setAccessible(true);
        m.invoke(t, cx, cy, w, h);
    }

    private static double[] corners(Object t, Class<?> cls) throws Exception {
        Method m = cls.getDeclaredMethod("cornersForProbe");
        m.setAccessible(true);
        return (double[]) m.invoke(t);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
