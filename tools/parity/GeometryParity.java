import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 几何「逐位一致」parity harness（契约附件 A-2）。
 *
 * <p>反射调用<b>真实类</b>的私有方法 {@code CaretTrailPanel.computeOffsetDirections}，
 * 因此能抓住「把 nx/ny 提为字段复用后忘记清零 → 退化四边形读到上一帧残留法线」
 * 这类陷阱（本地副本式验算做不到）。
 *
 * <p>每个多边形连调两次：第二次模拟「同一次绘制链路的后续调用」，残留状态会在这里暴露。
 *
 * <h2>为什么同时支持两种签名（双签名兼容）</h2>
 *
 * <p>0.9.9 的亚像素修复把顶点容器从 {@code Polygon}（int[]）改成 {@code double[]}：
 * <pre>
 *   旧: computeOffsetDirections(Polygon, double[], double[])
 *   新: computeOffsetDirections(double[] px, double[] py, double[] dx, double[] dy)
 * </pre>
 *
 * <p>本 harness 用同一份代码分别跑「基线版本」与「当前版本」，
 * 所以必须能识别两种签名。判据：{@link Method#getParameterCount()} 是 3 还是 4 ——
 * 3 参 = 旧版（Polygon 一个参数），4 参 = 新版（px/py 两个参数）。
 *
 * <p><b>几何数据必须逐位相同</b>：两种签名下同一组顶点算出的 dx/dy raw bits 必须一致，
 * 差异只允许出现在 {@code ### signature=...} 这一行。
 */
public class GeometryParity {

    static int seq = 0;

    /** 一组顶点（用 double 存，两种签名都能喂）。 */
    static final class Quad {
        final String name;
        final double[] x;
        final double[] y;

        Quad(String name, double[] x, double[] y) {
            this.name = name;
            this.x = x;
            this.y = y;
        }
    }

    /** 反射找到的调用方式：旧版 3 参 / 新版 4 参。 */
    static final class Invoker {
        final Method m;
        final boolean oldStyle;      // true = (Polygon, dx, dy)

        Invoker(Method m, boolean oldStyle) {
            this.m = m;
            this.oldStyle = oldStyle;
        }

        void call(CaretTrailPanel panel, Quad q, double[] dx, double[] dy) throws Exception {
            Object target = Modifier.isStatic(m.getModifiers()) ? null : panel;
            if (oldStyle) {
                int[] ix = new int[4];
                int[] iy = new int[4];
                for (int i = 0; i < 4; i++) {
                    ix[i] = (int) Math.round(q.x[i]);
                    iy[i] = (int) Math.round(q.y[i]);
                }
                m.invoke(target, new java.awt.Polygon(ix, iy, 4), dx, dy);
            } else {
                m.invoke(target, q.x, q.y, dx, dy);
            }
        }
    }

    static void run(CaretTrailPanel panel, Invoker inv, Quad q) throws Exception {
        System.out.println("### " + q.name);
        for (int pass = 0; pass < 2; pass++) {
            double[] dx = new double[4];
            double[] dy = new double[4];
            inv.call(panel, q, dx, dy);
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

        // ---- 双签名探测 ----
        Invoker inv;
        Method oldM = null;
        Method newM = null;
        try {
            oldM = CaretTrailPanel.class.getDeclaredMethod(
                    "computeOffsetDirections", java.awt.Polygon.class,
                    double[].class, double[].class);
        } catch (NoSuchMethodException ignored) {
            // 新版没有这个签名
        }
        try {
            newM = CaretTrailPanel.class.getDeclaredMethod(
                    "computeOffsetDirections", double[].class,
                    double[].class, double[].class, double[].class);
        } catch (NoSuchMethodException ignored) {
            // 旧版没有这个签名
        }
        if (newM != null) {
            newM.setAccessible(true);
            inv = new Invoker(newM, false);
            System.out.println("### signature=double[]");
        } else if (oldM != null) {
            oldM.setAccessible(true);
            inv = new Invoker(oldM, true);
            System.out.println("### signature=Polygon");
        } else {
            throw new IllegalStateException(
                    "找不到 computeOffsetDirections 的任何已知签名（源码已变，请同步本 harness）");
        }

        // ① 静止矩形 2×32（用户最关注的场景）
        run(panel, inv, new Quad("R_rest_rect",
                new double[]{0, 2, 2, 0}, new double[]{0, 0, 32, 32}));

        // ② 拖尾中间态（斜四边形）
        run(panel, inv, new Quad("S_trail_slant",
                new double[]{10, 12, 40, 38}, new double[]{0, 0, 28, 28}));

        // ③ 极端跳转（细长斜条，锐角被 SQRT_2 压住）
        run(panel, inv, new Quad("T_extreme_thin",
                new double[]{100, 102, 104, 102}, new double[]{0, 0, 300, 300}));

        // ④ 退化：四角重合  ← nx/ny 残留陷阱的探测器（基线期望全 0）
        run(panel, inv, new Quad("D_degenerate_point",
                new double[]{50, 50, 50, 50}, new double[]{50, 50, 50, 50}));

        // ⑤ 退化：单条零长边（j=0→j=1 重合），其余正常
        run(panel, inv, new Quad("E_zero_length_edge",
                new double[]{7, 7, 9, 7}, new double[]{3, 3, 33, 33}));

        // ⑥ 大矩形（跨行拖到尾）
        run(panel, inv, new Quad("F_wide",
                new double[]{0, 400, 400, 0}, new double[]{0, 0, 32, 32}));

        // ⑦ 负坐标（面板夹紧前可能出现的越界值）
        run(panel, inv, new Quad("G_negative",
                new double[]{-40, -20, -20, -40}, new double[]{-10, -10, 60, 60}));

        // ⑧ 反向绕行（顶点顺序相反 → 法线应指向外）
        run(panel, inv, new Quad("H_reversed_order",
                new double[]{0, 0, 2, 2}, new double[]{0, 32, 32, 0}));

        System.out.println("### end");
    }
}
