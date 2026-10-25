import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;

/** 量各方向的对齐度分布，选 hardSnap 阈值。 */
public final class ZzAlign {
    static final double W = 2.0, H = 32.0;
    static final double[][] REL = {{-0.5,-0.5},{0.5,-0.5},{0.5,0.5},{-0.5,0.5}};
    static final String[] NAMES = {"TL", "TR", "BR", "BL"};

    public static void main(String[] a) {
        System.out.println("各移动方向下的四角对齐度（静止起步）：");
        show("打字 →（同字符）", 12, 0);
        show("删除 ←（同字符）", -12, 0);
        show("下一行同列", 0, 32);
        show("上一行同列", 0, -32);
        show("斜跳 ↘", 12, 32);
        show("斜跳 ↖", -12, -32);
        System.out.println();
        System.out.println("阈值选择：");
        System.out.println("  0.5  -> 每个方向都至少有 1 个角超过（会吸附）");
        System.out.println("  0.9  -> 只有真正「正前方」的角才吸附（更保守）");
    }

    private static void show(String label, double dx, double dy) {
        NeovideConfig cfg = new NeovideConfig();
        double cx = 100, cy = 100;
        StringBuilder sb = new StringBuilder();
        int over05 = 0, over09 = 0;
        for (int i = 0; i < 4; i++) {
            TrailCorner c = new TrailCorner(REL[i][0], REL[i][1], cfg);
            c.snapTo(W, H, cx, cy);
            double al = c.calculateDirectionAlignment(W, H, cx + dx, cy + dy);
            sb.append(String.format("%s=%.3f ", NAMES[i], al));
            if (al > 0.5) over05++;
            if (al > 0.9) over09++;
        }
        System.out.printf("  %-20s  %s  >0.5:%d  >0.9:%d%n", label, sb.toString().trim(), over05, over09);
    }
}