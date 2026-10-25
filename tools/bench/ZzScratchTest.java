import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;

/** 模拟生产代码：复用同一个 idxScratch，看第二次调用后 order 是否还从 {0,1,2,3} 开始。 */
public final class ZzScratchTest {
    private static Integer[] idxScratch;

    public static void main(String[] a) {
        System.out.println("=== 复现 idxScratch 复用问题 ===");
        // 第一次：斜跳（对齐度互异）
        int[] r1 = assignRanks(12.0, 32.0);
        System.out.println("  第1次（斜跳）  order 后 = " + java.util.Arrays.toString(idxScratch));
        System.out.println("  rank = " + java.util.Arrays.toString(r1));
        // 第二次：同行打字（有并列）
        int[] r2 = assignRanks(12.0, 0.0);
        System.out.println("  第2次（打字）  order 前 = " + java.util.Arrays.toString(idxScratch));
        System.out.println("  rank = " + java.util.Arrays.toString(r2));
        // 第三次：同样输入，看是否与第二次一致
        int[] r3 = assignRanks(12.0, 0.0);
        System.out.println("  第3次（同样打字）rank = " + java.util.Arrays.toString(r3));
        boolean same = java.util.Arrays.equals(r2, r3);
        System.out.println();
        if (same) {
            System.out.println("  确定性 OK（第2、3次一致）");
        } else {
            System.out.println("  ★ BUG 确认：同样输入得到不同 rank！");
            System.out.println("    " + java.util.Arrays.toString(r2) + " vs " + java.util.Arrays.toString(r3));
        }
    }

    /** 完全复刻 SpringTrail.assignRanks 的写法（含 idxScratch 复用） */
    private static int[] assignRanks(double dx, double dy) {
        NeovideConfig cfg = new NeovideConfig();
        double w = 2.0, h = 32.0, cx = 100, cy = 100;
        double[][] rel = {{-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5}};
        TrailCorner[] cs = new TrailCorner[4];
        double[] al = new double[4];
        for (int i = 0; i < 4; i++) {
            cs[i] = new TrailCorner(rel[i][0], rel[i][1], cfg);
            cs[i].snapTo(w, h, cx, cy);
            al[i] = cs[i].calculateDirectionAlignment(w, h, cx + dx, cy + dy);
        }
        final int n = 4;
        Integer[] order = idxScratch;
        if (order == null || order.length != n) {
            order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            idxScratch = order;
        }
        for (int i = 1; i < n; i++) {
            int key = order[i];
            int j = i - 1;
            while (j >= 0 && al[order[j]] > al[key]) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = key;
        }
        int[] rank = new int[n];
        for (int k = 0; k < n; k++) rank[order[k]] = k;
        return rank;
    }
}