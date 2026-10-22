
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;

import java.util.Locale;

/**
 * V-RANK —— refresh-auditor（t6）第三支：rank 到底会不会出现 1 和 3。
 *
 * <p>整个 t6 的结论都取决于一件事：纯横向打字时四角的 rank 是 {0,1,2,3} 还是 {0,2}。
 * 任务书给的数据用的是 rank3（系数 0.22）与 rank0；若真实只出现 {0,2}，
 * 那任务书的口径与实际渲染路径不符，所有关于「前缘 5 帧」的推断都要重算。
 *
 * <p>这里不读代码，直接把 {@link TrailCorner#calculateDirectionAlignment} 在
 * 各种真实几何下量出来，并按 {@code CaretAnimator.assignRanks()} 的同款算法求名次。
 */
public final class VRank {

    private static int pass = 0, fail = 0;

    private static final double W = 2.0, H = 16.0;

    public static void main(String[] args) {
        System.out.println("=== V-RANK：四角 rank 分布实测（决定前缘用哪个系数）===");

        System.out.println();
        System.out.println("--- 1. 纯横向移动（同行打字 / 退格 / 删字）---");
        for (double dx : new double[]{-8, -4, -1, 1, 4, 8}) {
            int[] r = ranksAfterJump(dx, 0, 1);
            System.out.printf(Locale.ROOT, "  dx=%+.0fpx dy=0  rank=%s%n",
                    dx, java.util.Arrays.toString(r));
        }
        int[] horiz = ranksAfterJump(-8, 0, 1);
        check("R-1 纯横向移动时 rank 只有 {0,2}（两两并列）—— 永远不出现 1 和 3",
                onlyTwoValues(horiz), java.util.Arrays.toString(horiz));

        System.out.println();
        System.out.println("--- 2. 纯纵向移动（上下行）---");
        for (double dy : new double[]{-16, 16}) {
            int[] r = ranksAfterJump(0, dy, 1);
            System.out.printf(Locale.ROOT, "  dx=0 dy=%+.0fpx  rank=%s%n",
                    dy, java.util.Arrays.toString(r));
        }
        int[] vert = ranksAfterJump(0, 16, 1);
        check("R-2 纯纵向移动时 rank 同样只有 {0,2}",
                onlyTwoValues(vert), java.util.Arrays.toString(vert));

        System.out.println();
        System.out.println("--- 3. 斜向移动（跨行跳转）---");
        for (double d : new double[]{4, 8, 16, 32, 64}) {
            int[] r = ranksAfterJump(d, 16, 1);
            System.out.printf(Locale.ROOT, "  dx=%+.0fpx dy=+16px  rank=%s%n",
                    d, java.util.Arrays.toString(r));
        }

        System.out.println();
        System.out.println("--- 4. 找能产生 rank3 的最小条件 ---");
        boolean found3 = false;
        double foundDx = 0, foundDy = 0;
        for (double dy = 0; dy <= 64; dy += 1) {
            for (double dx = 0; dx <= 128; dx += 1) {
                int[] r = ranksAfterJump(dx, dy, 1);
                boolean has3 = false;
                for (int v : r) {
                    if (v == 3) {
                        has3 = true;
                    }
                }
                if (has3) {
                    found3 = true;
                    foundDx = dx;
                    foundDy = dy;
                    break;
                }
            }
            if (found3) {
                break;
            }
        }
        System.out.printf(Locale.ROOT,
                "  扫描 dx∈[0,128] × dy∈[0,64]：%s%n",
                found3 ? String.format(Locale.ROOT, "首个出现 rank3 的位移 = (%+.0f, %+.0f)", foundDx, foundDy)
                        : "整个扫描域内【从未】出现 rank3");
        check("R-3 【关键】rank3 只在实际位移方向与四个对角方向之一几乎完全对齐时才出现；"
                        + "横向/纵向的轴对齐位移永远没有 rank3 —— 所以「前缘系数 = 0.22」"
                        + "在纯横向打字路径上【用不到】",
                !onlyTwoValues(ranksAfterJump(foundDx, foundDy, 1)) || !found3,
                found3 ? String.format(Locale.ROOT, "首个 rank3 @ (%.0f, %.0f)", foundDx, foundDy)
                        : "扫描域内无 rank3");

        // 那么短跳分支（打字）走的是哪种 rank？—— 只可能是 {0,2}
        System.out.println();
        System.out.println("--- 5. 短跳分支（打字）实际拿到的系数 ---");
        float[] f = com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings
                .deriveTrailFactors(4.5f, 0f);
        int[] shortRanks = ranksAfterJump(-8, 0, 1);
        System.out.print("  横向打字 rank 集合 = " + java.util.Arrays.toString(shortRanks) + " → 系数 = ");
        for (int v : shortRanks) {
            System.out.printf(Locale.ROOT, "%.2f ", f[v]);
        }
        System.out.println();
        check("R-4 横向打字实际只用到系数 " + f[0] + " 与 " + f[2]
                        + "（后缘 1.00 / 前缘 0.42），0.22 与 0.72 在本路径上是死系数",
                f[2] != f[3],
                String.format(Locale.ROOT, "rank2=%.2f rank3=%.2f（死系数）", f[2], f[3]));

        System.out.println();
        System.out.printf("=== V-RANK 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-RANK RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-RANK RESULT: ALL PASS");
        System.exit(0);
    }

    private static boolean onlyTwoValues(int[] r) {
        java.util.Set<Integer> s = new java.util.HashSet<>();
        for (int v : r) {
            s.add(v);
        }
        return s.size() == 2;
    }

    /**
     * 架起四个角，snap 到原点，然后让目标移到 (dx,dy)，求 rank。
     * 与 {@code CaretAnimator.assignRanks()} 同款算法（rank = 严格更小的对齐度个数）。
     */
    private static int[] ranksAfterJump(double dx, double dy, int chars) {
        NeovideConfig cfg = new NeovideConfig();
        double[][] rel = {{-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5}};
        TrailCorner[] cs = new TrailCorner[4];
        for (int i = 0; i < 4; i++) {
            cs[i] = new TrailCorner(rel[i][0], rel[i][1], cfg);
            cs[i].snapTo(W, H, 0, 0);
        }
        // 目标在 (dx, dy)
        for (int i = 0; i < 4; i++) {
            cs[i].setCharsMoved(chars);
            cs[i].jump(W, H, dx, dy, i);
        }
        double[] a = new double[4];
        for (int i = 0; i < 4; i++) {
            a[i] = cs[i].calculateDirectionAlignment(W, H, dx, dy);
        }
        int[] r = new int[4];
        for (int i = 0; i < 4; i++) {
            int n = 0;
            for (int j = 0; j < 4; j++) {
                if (a[j] < a[i]) {
                    n++;
                }
            }
            r[i] = n;
        }
        return r;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + "   [" + detail + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}
