import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;

/**
 * V-QUAD：面板多四边形支持（多光标步骤 2）
 *
 * <p><b>核心约束：单光标路径必须逐位不变。</b>
 * 改造把 `polyX/polyY` + `glowStrength` 换成了步长式扁平数组
 * `[x0,y0,...,x3,y3,glow] × N`，而单光标是 N=1 的特例。
 * 若这里出现任何偏差，就等于把已验证过的手感改坏了。
 *
 * <p>本探针在 headless 下直接构造面板（不 setVisible，不弹窗），
 * 通过 `updateCorners` / `addQuad` 写入数据，再用探针接口读回比对。
 */
public final class VQuad {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        System.out.println("=== V-QUAD：面板多四边形 ===");

        // ---- Q-1：单四边形路径（updateCorners）写入后能原样读回 ----
        CaretTrailPanel p = new CaretTrailPanel(new NeovideConfig());
        double x0 = 10.25, y0 = 20.75, x1 = 12.25, y1 = 20.75;
        double x2 = 12.25, y2 = 52.75, x3 = 10.25, y3 = 52.75;
        p.updateCorners(x0, y0, x1, y1, x2, y2, x3, y3);

        check("Q-1 updateCorners 后 quadCount == 1", p.quadCount() == 1,
                "quadCount=" + p.quadCount());

        double[] q0 = p.quadForProbe(0);
        boolean exact = q0 != null
                && q0[0] == x0 && q0[1] == y0
                && q0[2] == x1 && q0[3] == y1
                && q0[4] == x2 && q0[5] == y2
                && q0[6] == x3 && q0[7] == y3;
        check("Q-2 单四边形坐标逐位一致（含小数，无取整）", exact,
                q0 == null ? "null" : String.format("首角=(%.2f,%.2f)", q0[0], q0[1]));

        // ---- Q-3：旧 cornersForProbe 仍然可用且一致 ----
        double[] legacy = p.cornersForProbe();
        boolean legacyOk = legacy[0] == x0 && legacy[1] == y0
                && legacy[6] == x3 && legacy[7] == y3;
        check("Q-3 cornersForProbe 与 updateCorners 一致（向后兼容）", legacyOk,
                String.format("legacy[0]=%.2f legacy[7]=%.2f", legacy[0], legacy[7]));

        // ---- Q-4：多四边形 —— 3 个独立写入 ----
        p.beginQuads();
        check("Q-4 beginQuads 后 quadCount == 0", p.quadCount() == 0,
                "quadCount=" + p.quadCount());

        double[][] xs = {
                {0.5, 2.5, 2.5, 0.5},
                {100.5, 102.5, 102.5, 100.5},
                {200.125, 202.125, 202.125, 200.125},
        };
        double[][] ys = {
                {0.5, 0.5, 32.5, 32.5},
                {100.5, 100.5, 132.5, 132.5},
                {200.25, 200.25, 232.25, 232.25},
        };
        float[] glows = {1.0f, 0.5f, 0.0f};
        for (int i = 0; i < 3; i++) {
            p.addQuad(xs[i], ys[i], glows[i]);
        }
        check("Q-5 追加 3 个四边形后 quadCount == 3", p.quadCount() == 3,
                "quadCount=" + p.quadCount());

        // ---- Q-6：每个四边形独立、互不覆盖 ----
        boolean independent = true;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            double[] q = p.quadForProbe(i);
            if (q == null || q[0] != xs[i][0] || q[1] != ys[i][0]) {
                independent = false;
                sb.append("q").append(i).append(" 错位 ");
            }
        }
        check("Q-6 三个四边形坐标互不干扰", independent,
                independent ? "3/3 正确" : sb.toString());

        // ---- Q-7：辉光强度逐四边形独立 ----
        boolean glowOk = true;
        for (int i = 0; i < 3; i++) {
            double[] q = p.quadForProbe(i);
            if (q == null || Math.abs(q[8] - glows[i]) > 1e-6) {
                glowOk = false;
            }
        }
        check("Q-7 辉光强度逐四边形独立（1.0 / 0.5 / 0.0）", glowOk,
                glowOk ? "3/3 正确" : "不一致");

        // ---- Q-8：辉光强度越界钳制 ----
        p.beginQuads();
        p.addQuad(xs[0], ys[0], 2.5f);
        p.addQuad(xs[1], ys[1], -1.0f);
        boolean clampOk = Math.abs(p.quadForProbe(0)[8] - 1.0) < 1e-6
                && Math.abs(p.quadForProbe(1)[8] - 0.0) < 1e-6;
        check("Q-8 辉光强度钳制到 [0,1]（2.5→1.0，-1.0→0.0）", clampOk,
                String.format("2.5→%.1f  -1.0→%.1f", p.quadForProbe(0)[8], p.quadForProbe(1)[8]));

        // ---- Q-9：越界索引返回 null（不抛异常）----
        boolean safe = p.quadForProbe(-1) == null && p.quadForProbe(99) == null;
        check("Q-9 quadForProbe 越界返回 null 而非抛异常", safe,
                "index=-1 与 99 均返回 null");

        // ---- Q-10：32 个四边形（多光标上限场景）不崩 ----
        p.beginQuads();
        boolean bulk = true;
        for (int i = 0; i < 32; i++) {
            double base = i * 10.0;
            double[] bx = {base, base + 2, base + 2, base};
            double[] by = {0, 0, 32, 32};
            p.addQuad(bx, by, 1.0f);
        }
        bulk = (p.quadCount() == 32);
        // 抽查最后一个
        double[] last = p.quadForProbe(31);
        bulk = bulk && last != null && Math.abs(last[0] - 310.0) < 1e-9;
        check("Q-10 32 个四边形（扩容路径）无异常", bulk,
                "quadCount=" + p.quadCount() + " 末个 x0=" + (last == null ? "null" : last[0]));

        // ---- Q-11：beginQuads 重置后旧数据不再可读 ----
        p.beginQuads();
        check("Q-11 beginQuads 重置后 quadCount == 0 且旧索引不可读",
                p.quadCount() == 0 && p.quadForProbe(0) == null,
                "已清空");

        System.out.println();
        System.out.printf("=== V-QUAD 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-QUAD RESULT: FAILED"); System.exit(1); }
        System.out.println("V-QUAD RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
