import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;

import java.awt.Polygon;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 几何「逐位一致」parity harness（契约附件 A-2，由 verifier 落盘到 tools/parity/）。
 *
 * <p>与 PhysicsParity 的区别：本 harness 反射调用**真实类**的私有方法
 * {@code CaretTrailPanel.computeOffsetDirections}，因此能抓住「把 nx/ny 提为字段复用后
 * 忘记清零 → 退化四边形读到上一帧残留法线」这类陷阱（本地副本式验算做不到这一点）。
 *
 * <p>每个多边形连调两次：第二次模拟"同一次绘制链路的后续调用"，残留状态会在这里暴露。
 *
 * <p>编译需要 idea-libs（CaretTrailPanel 依赖 org.jetbrains.annotations）；
 * 运行 headless 即可（不创建窗口）。
 */
public class GeometryParity {

    static int seq = 0;

    static void run(CaretTrailPanel panel, Method m, String name, Polygon p) throws Exception {
        boolean isStatic = Modifier.isStatic(m.getModifiers());
        System.out.println("### " + name);
        for (int pass = 0; pass < 2; pass++) {
            double[] dx = new double[4];
            double[] dy = new double[4];
            Object target = isStatic ? null : panel;
            m.invoke(target, p, dx, dy);
            StringBuilder sb = new StringBuilder();
            sb.append("seq=").append(seq++).append(" pass=").append(pass);
            for (int i = 0; i < 4; i++) {
                sb.append(" dx").append(i).append('=')
                        .append(Long.toHexString(Double.doubleToRawLongBits(dx[i])));
                sb.append(" dy").append(i).append('=')
                        .append(Long.toHexString(Double.doubleToRawLongBits(dy[i])));
            }
            System.out.println(sb);
        }
    }

    public static void main(String[] args) throws Exception {
        NeovideConfig cfg = new NeovideConfig();
        CaretTrailPanel panel = new CaretTrailPanel(cfg);
        Method m = CaretTrailPanel.class.getDeclaredMethod(
                "computeOffsetDirections", Polygon.class, double[].class, double[].class);
        m.setAccessible(true);

        // ① 静止矩形 2×32（用户最关注的场景）
        run(panel, m, "R_rest_rect", new Polygon(
                new int[]{0, 2, 2, 0}, new int[]{0, 0, 32, 32}, 4));

        // ② 拖尾中间态（斜四边形）
        run(panel, m, "S_trail_slant", new Polygon(
                new int[]{10, 12, 40, 38}, new int[]{0, 0, 28, 28}, 4));

        // ③ 极端跳转（细长斜条，锐角被 SQRT_2 压住）
        run(panel, m, "T_extreme_thin", new Polygon(
                new int[]{100, 102, 104, 102}, new int[]{0, 0, 300, 300}, 4));

        // ④ 退化：四角重合  ← nx/ny 残留陷阱的探测器（基线期望全 0）
        run(panel, m, "D_degenerate_point", new Polygon(
                new int[]{50, 50, 50, 50}, new int[]{50, 50, 50, 50}, 4));

        // ⑤ 退化：单条零长边（j=0→j=1 重合），其余正常
        run(panel, m, "E_zero_length_edge", new Polygon(
                new int[]{7, 7, 9, 7}, new int[]{3, 3, 33, 33}, 4));

        // ⑥ 大矩形（跨行拖到尾）
        run(panel, m, "F_wide", new Polygon(
                new int[]{0, 400, 400, 0}, new int[]{0, 0, 32, 32}, 4));

        // ⑦ 负坐标（面板夹紧前可能出现的越界值）
        run(panel, m, "G_negative", new Polygon(
                new int[]{-40, -20, -20, -40}, new int[]{-10, -10, 60, 60}, 4));

        // ⑧ 反向绕行（顶点顺序相反 → 法线应指向外）
        run(panel, m, "H_reversed_order", new Polygon(
                new int[]{0, 0, 2, 2}, new int[]{0, 32, 32, 0}, 4));

        System.out.println("### end");
    }
}
