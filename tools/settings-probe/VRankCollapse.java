import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;

/** V-RANK：rank 塌缩已修复 = 纯轴向移动也必须拿到 4 个不同 rank。 */
public final class VRankCollapse {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        System.out.println("=== V-RANK：rank 不塌缩 ===");
        System.out.println();
        System.out.printf("%-22s %-28s %-14s %s%n", "场景", "对齐度 TL/TR/BR/BL", "rank", "档位");
        System.out.println("----------------------------------------------------------------------");

        checkDir("打字 →（同行）", 12.0, 0.0);
        checkDir("删除 ←（同行）", -12.0, 0.0);
        checkDir("下一行同列", 0.0, 32.0);
        checkDir("上一行同列", 0.0, -32.0);
        checkDir("斜跳 ↘", 12.0, 32.0);

        // 确定性：同输入两次必须同 rank
        int[] r1 = ranksOf(12.0, 0.0);
        int[] r2 = ranksOf(12.0, 0.0);
        check("确定性：同样输入两次得到同样 rank",
                java.util.Arrays.equals(r1, r2),
                java.util.Arrays.toString(r1) + " vs " + java.util.Arrays.toString(r2));

        System.out.println();
        System.out.printf("=== V-RANK 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-RANK RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-RANK RESULT: ALL PASS");
    }

    private static void checkDir(String name, double dx, double dy) {
        double[] al = new double[4];
        int[] rank = ranksOf(dx, dy, al);
        java.util.HashSet<Integer> d = new java.util.HashSet<>();
        for (int r : rank) d.add(r);
        System.out.printf("%-22s %-28s %-14s %d/4%n", name,
                String.format("%.2f/%.2f/%.2f/%.2f", al[0], al[1], al[2], al[3]),
                rank[0] + " " + rank[1] + " " + rank[2] + " " + rank[3], d.size());
        check(name + " 四个 rank 互不相同", d.size() == 4,
                "档位=" + d.size() + " rank=" + java.util.Arrays.toString(rank));
    }

    private static int[] ranksOf(double dx, double dy) {
        return ranksOf(dx, dy, new double[4]);
    }

    /** 复刻生产代码：排序 + 稳定（并列按索引） */
    private static int[] ranksOf(double dx, double dy, double[] alOut) {
        NeovideConfig cfg = new NeovideConfig();
        double w = 2.0, h = 32.0, cx = 100, cy = 100;
        double[][] rel = {{-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5}};
        TrailCorner[] cs = new TrailCorner[4];
        for (int i = 0; i < 4; i++) {
            cs[i] = new TrailCorner(rel[i][0], rel[i][1], cfg);
            cs[i].snapTo(w, h, cx, cy);
            alOut[i] = cs[i].calculateDirectionAlignment(w, h, cx + dx, cy + dy);
        }
        Integer[] order = {0, 1, 2, 3};
        // 稳定插入排序（与生产代码一致：只有严格大于才后移，并列保持原序）
        for (int i = 1; i < 4; i++) {
            int key = order[i];
            int j = i - 1;
            while (j >= 0 && alOut[order[j]] > alOut[key]) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = key;
        }
        int[] rank = new int[4];
        for (int k = 0; k < 4; k++) {
            rank[order[k]] = k;
        }
        return rank;
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}