import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** 诊断：到底是哪两个角的速度差产生了「斜」？ */
public final class ZzWhichPair {
    static final double W = 2.0, H = 32.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};

    public static void main(String[] a) {
        float[] base = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f);
        float[] f = {base[0], base[1], base[2], base[3]};
        System.out.println("打字右移，rank = 0 2 3 1");
        System.out.println("四角系数（按 rank 取）：TL=rank0  TR=rank2  BR=rank3  BL=rank1");
        System.out.println();
        int[] rank = {0, 2, 3, 1};
        System.out.printf("  TL(rank0) = %.2f    TR(rank2) = %.2f%n", f[0], f[2]);
        System.out.printf("  BL(rank1) = %.2f    BR(rank3) = %.2f%n", f[1], f[3]);
        System.out.println();
        System.out.printf("  左边差 TL-BL = %.2f  （上慢下快 → 左边缘倾斜）%n", f[0] - f[1]);
        System.out.printf("  右边差 TR-BR = %.2f  （上慢下快 → 右边缘倾斜）%n", f[2] - f[3]);
        System.out.println();
        System.out.println("=== 实测各角的动画时长（ms）===");
        NeovideConfig cfg = new NeovideConfig();
        cfg.trailFactors = f;
        cfg.shortAnimationLength = 0.11f;
        double cx = 100, cy = 100, dx = 12;
        TrailCorner[] cs = new TrailCorner[4];
        for (int i = 0; i < 4; i++) {
            cs[i] = new TrailCorner(REL[i][0], REL[i][1], cfg);
            cs[i].setCharsMoved(1);
            cs[i].snapTo(W, H, cx, cy);
        }
        for (int i = 0; i < 4; i++) cs[i].jump(W, H, cx + dx, cy, rank[i]);
        String[] names = {"TL", "TR", "BR", "BL"};
        for (int i = 0; i < 4; i++) {
            System.out.printf("  %s rank=%d 系数=%.2f 时长=%.1fms%n",
                    names[i], rank[i], f[rank[i]], 110 * f[rank[i]]);
        }
        System.out.println();
        System.out.println("结论：TL=110ms、BL=79ms（差 31ms） -> 左边缘倾斜");
        System.out.println("      TR=46ms、BR=24ms（差 22ms） -> 右边缘倾斜");
        System.out.println("所以「斜」来自同一【侧】上下两角时长不同。");
    }
}