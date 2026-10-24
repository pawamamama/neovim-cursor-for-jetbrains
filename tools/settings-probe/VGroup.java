import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * V-GROUP：相邻光标的「视觉分组」
 *
 * <p><b>用户需求（2026-10-24）</b>：
 * 「相邻行感觉绘制成一个大光标可以做吗？但是假如 123 行是在一起的，5 行独立一个，
 * 这样可以避免串扰」
 *
 * <p><b>要解决的问题</b>：多光标时每个光标独立发光 → 相邻行的辉光会重叠成一团，
 * 视觉上像「一个大光标」，且互相串扰。
 *
 * <p><b>设计</b>：把「纵向相邻（行号连续）」的光标归为一组。
 * 同组内只让<b>整组</b>共用一个辉光强度（取组内最大值），组与组之间互不影响。
 * 这样：
 * <ul>
 *   <li>1、2、3 行在一起 → 一个组，辉光一致，不会互相打架</li>
 *   <li>5 行独立 → 另一个组，自己算自己的</li>
 * </ul>
 *
 * <p><b>本探针验证的是纯函数分组逻辑</b>，不依赖 Editor。
 */
public final class VGroup {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        System.out.println("=== V-GROUP：相邻光标分组 ===");

        // ---- G-1：单光标 → 1 组 ----
        int[] g1 = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.groupAdjacentLines(new int[]{10});
        check("G-1 单光标 → 归入第 0 组", g1[0] == 0, "组号=" + g1[0]);

        // ---- G-2：连续三行 → 同一组 ----
        int[] g2 = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.groupAdjacentLines(new int[]{10, 11, 12});
        check("G-2 第 10/11/12 行 → 全部同一组",
                g2[0] == g2[1] && g2[1] == g2[2],
                "组号=" + Arrays.toString(g2));

        // ---- G-3：三行连续 + 一个孤立（用户举的例子）----
        //
        // 1、2、3 行在一起 → 第 0 组
        // 5 行独立      → 第 1 组
        int[] g3 = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.groupAdjacentLines(new int[]{1, 2, 3, 5});
        boolean contiguous = g3[0] == g3[1] && g3[1] == g3[2];
        boolean isolated = g3[3] != g3[0];
        check("G-3 行 1/2/3 同组，行 5 独立（用户举的例子）",
                contiguous && isolated,
                "组号=" + Arrays.toString(g3));

        // ---- G-4：两段连续各成一组 ----
        int[] g4 = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.groupAdjacentLines(new int[]{1, 2, 10, 11});
        check("G-4 行 1-2 一组、行 10-11 另一组",
                g4[0] == g4[1] && g4[2] == g4[3] && g4[0] != g4[2],
                "组号=" + Arrays.toString(g4));

        // ---- G-5：中间断行也算不同组（1,2,4 —— 4 不与 2 相邻）----
        int[] g5 = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.groupAdjacentLines(new int[]{1, 2, 4});
        check("G-5 行 1-2 一组、行 4 独立（4 与 2 不相邻）",
                g5[0] == g5[1] && g5[2] != g5[1],
                "组号=" + Arrays.toString(g5));

        // ---- G-6：乱序输入也要正确分组 ----
        int[] g6 = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.groupAdjacentLines(new int[]{12, 10, 11});
        // 排序后是 10,11,12 → 全同组；但输出要按【原始下标】给
        boolean allSame = g6[0] == g6[1] && g6[1] == g6[2];
        check("G-6 乱序输入（12,10,11）→ 仍全部同组", allSame,
                "组号=" + Arrays.toString(g6));

        // ---- G-7：空输入 ----
        int[] g7 = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.groupAdjacentLines(new int[0]);
        check("G-7 空输入 → 空输出", g7.length == 0, "长度=" + g7.length);

        // ---- G-8：单行大跨度（1 和 100）→ 两组 ----
        int[] g8 = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.groupAdjacentLines(new int[]{1, 100});
        check("G-8 行 1 与行 100 → 两个不同的组",
                g8[0] != g8[1], "组号=" + Arrays.toString(g8));

        System.out.println();
        System.out.printf("=== V-GROUP 结果：PASS %d, FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-GROUP RESULT: FAILED"); System.exit(1); }
        System.out.println("V-GROUP RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
