import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;

/**
 * V-BLOCK：块选区（Alt+拖拽列选）多光标
 *
 * <p><b>用户实测反馈（2026-10-24）</b>：
 * 「只测试了列选没有多光标，只在我鼠标拖拽的地方有一个没有复制到其他行
 *  （其他行直接 idea 的原生的没有）」
 *
 * <p><b>根因</b>：Alt+鼠标拖拽走 {@code SelectionModel.setBlockSelection()}，
 * 它是「一个 caret + 一个矩形选区」，<b>不会创建多个 Caret</b>。
 * 我们的多光标实现基于 {@code getAllCarets()}，因此只看到 1 个。
 *
 * <p><b>本探针验证的是「目标点收集」这一层</b>：
 * 把「编辑器里该画几个光标、分别在哪」抽象成纯函数，
 * 输入 (caret 列表, 块选区数组)，输出目标点列表。
 * 这样无需真实 Editor 也能断言块选区与普通多光标两条路径。
 */
public final class VBlock {
    private static int pass = 0, fail = 0;

    private static final double LINE_H = 10.0;
    private static final double CHAR_W = 20.0;

    /** 像素换算：offset → [x, y]（行号 × 行高，与生产代码同语义） */
    private static final java.util.function.IntFunction<double[]> PT = offset -> {
        int line = offset / 20;
        return new double[]{0.0, line * LINE_H};
    };

    public static void main(String[] a) {
        System.out.println("=== V-BLOCK：块选区多光标 ===");

        // ---- K-1：普通单光标 → 1 个目标 ----
        var single = call(new int[]{100}, null, null);
        check("K-1 单光标 → 1 个目标点", single.size() == 1,
                "实际 " + single.size());

        // ---- K-2：多光标（Alt+点击）→ N 个目标 ----
        var multi = call(new int[]{100, 200, 300}, null, null);
        check("K-2 三个光标 → 3 个目标点", multi.size() == 3,
                "实际 " + multi.size());

        // ---- K-3：块选区（Alt+拖拽）→ 按行数生成 ----
        //
        // 用户报的核心缺陷：块选区时 getAllCarets() 只返回 1 个，
        // 但视觉上应该「拖了几行就有几个」。
        int[] starts = {0, 20, 40, 60};
        int[] ends   = {5, 25, 45, 65};
        var block = call(new int[]{0}, starts, ends);
        check("K-3 块选区 4 行 → 4 个目标点（不是 1 个）", block.size() == 4,
                "实际 " + block.size() + "（若为 1 说明块选区未展开）");

        // ---- K-4：块选区每个目标行间距 = 行高 ----
        boolean spacingOk = block.size() >= 2;
        if (spacingOk) {
            for (int i = 1; i < block.size(); i++) {
                double dy = block.get(i)[1] - block.get(i - 1)[1];
                if (Math.abs(dy - LINE_H) > 0.01) { spacingOk = false; }
            }
        }
        check("K-4 块选区各目标点纵向间距 = 行高", spacingOk,
                block.size() >= 2 ? "间距正确" : "目标数不足");

        // ---- K-5：块选区上限 64 ----
        int[] manyStarts = new int[200];
        int[] manyEnds = new int[200];
        for (int i = 0; i < 200; i++) { manyStarts[i] = i * 20; manyEnds[i] = i * 20 + 5; }
        var capped = call(new int[]{0}, manyStarts, manyEnds);
        check("K-5 块选区 200 行 → 截断到 64", capped.size() == 64,
                "实际 " + capped.size());

        // ---- K-6：块选区仅 1 行 → 退化为 1 个 ----
        var singleBlock = call(new int[]{0}, new int[]{0}, new int[]{5});
        check("K-6 块选区仅 1 行 → 1 个目标", singleBlock.size() == 1,
                "实际 " + singleBlock.size());

        // ---- K-7：块选区优先级高于 caret（两者互斥时的取舍）----
        var both = call(new int[]{0, 20, 40}, starts, ends);
        check("K-7 块选区与多光标同时存在时，以块选区为准（4 而非 3）",
                both.size() == 4,
                "实际 " + both.size());

        System.out.println();
        System.out.printf("=== V-BLOCK 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-BLOCK RESULT: FAILED"); System.exit(1); }
        System.out.println("V-BLOCK RESULT: ALL PASS");
    }

    private static java.util.List<double[]> call(int[] caretOffsets, int[] bs, int[] be) {
        return com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator
                .collectTrailTargets(caretOffsets, bs, be, PT);
    }


    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
