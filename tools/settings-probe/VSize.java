import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview;

/**
 * V-SIZE：预览窗尺寸断言（验证 getPreferredSize 真的按内容算，不是硬编码）
 *
 * <p><b>为什么需要</b>：用户反馈「怎么感觉你硬编码了改了几个没效果」。
 * 查证确实有死代码（BASE_FONT_SIZE / CURSOR_W_BASE 声明了但被覆盖）。
 * 这支持续守住「尺寸必须由内容推导」这个契约。
 */
public final class VSize {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-SIZE：预览窗尺寸 ===");

        AnimationPreview inl = AnimationPreview.inline(new NeovideConfig());
        AnimationPreview crs = AnimationPreview.cross(new NeovideConfig());

        java.awt.Dimension di = inl.getPreferredSize();
        java.awt.Dimension dc = crs.getPreferredSize();

        System.out.println("  单行窗 preferredSize = " + di.width + "x" + di.height);
        System.out.println("  跨行窗 preferredSize = " + dc.width + "x" + dc.height);

        // ---- S-1：高度不能是硬编码 200 ----
        check("S-1 单行窗高度 ≠ 硬编码 200（按内容算）",
                di.height != 200, "实际 " + di.height);

        check("S-2 跨行窗高度 ≠ 硬编码 200",
                dc.height != 200, "实际 " + dc.height);

        // ---- S-3：高度要够两行内容但不夸张 ----
        check("S-3 高度在 96~220 之间（够放内容，不浪费）",
                di.height >= 96 && di.height <= 220, "实际 " + di.height);

        // ---- S-4：单行窗要比跨行窗宽（跨行只需放 int x = 0;）----
        check("S-4 单行窗比跨行窗宽",
                di.width > dc.width, di.width + " vs " + dc.width);

        // ---- S-5：宽度不是 0（布局 bug 回归防线）----
        check("S-5 宽度 > 300（不是被压成 1x1）",
                di.width > 300 && dc.width > 200,
                di.width + " / " + dc.width);

        System.out.println();
        System.out.printf("=== V-SIZE 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-SIZE RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-SIZE RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + n + "   [" + d + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + n + "   [" + d + "]");
        }
    }
}
