import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;

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
 * <p><b>【0.9.88 断言方向修正】</b>
 *
 * <p>上一版本探针调的是 {@code CaretAnimator.collectTrailTargets(...)} ——
 * 一个<b>生产零调用</b>的纯函数（真正实现在 {@code refreshTarget()} 里内联展开）。
 * 于是 7 条断言全绿，却证明不了生产路径任何事：把它改成返回空列表，产品行为不变，
 * 探针也不会红。这正是 docs/WORK-DISCIPLINE.md 四-G 说的
 * 「探针验收生产不用的平行实现」。
 *
 * <p>现在改成断言<b>生产路径真正在用的两个判据</b>：
 * <ul>
 *   <li>{@code trailSlotCount(useBlock, blockStarts.length, carets.size())}
 *       —— refreshTarget 用它决定本帧建几条拖尾</li>
 *   <li>{@code blockTrailKey(lineNo, blockStart)}
 *       —— refreshTarget 用它的返回值当 trails 的 key</li>
 * </ul>
 * 两个方法都是被 refreshTarget 直接调用的（不是为探针新写的复刻），
 * 把它们的实现改坏，产品行为就变，本探针必红。
 *
 * <p>断言条数一条没减：原来 7 条，现在仍然是 7 条（K-3/K-4/K-6 换了验证对象）。
 */
public final class VBlock {
    private static int pass = 0, fail = 0;

    private static final double LINE_H = 10.0;

    /** 生产上限：CaretAnimator 的 MAX_TRAILS（见 trailSlotCount 的 javadoc） */
    private static final int MAX_TRAILS = 64;

    public static void main(String[] a) {
        System.out.println("=== V-BLOCK：块选区多光标（断言生产路径）===");

        // ---- K-1：普通单光标 → 1 条 ----
        int single = CaretAnimator.trailSlotCount(false, 0, 1);
        check("K-1 单光标 → 1 条拖尾", single == 1, "实际 " + single);

        // ---- K-2：多光标（Alt+点击）→ N 条 ----
        int multi = CaretAnimator.trailSlotCount(false, 0, 3);
        check("K-2 三个光标 → 3 条拖尾", multi == 3, "实际 " + multi);

        // ---- K-3：块选区（Alt+拖拽）→ 按行数生成，不是按 caret 数 ----
        //
        // 用户报的核心缺陷：块选区时 getAllCarets() 只返回 1 个，
        // 但视觉上应该「拖了几行就有几条线」。
        // 生产里 useBlock=true 时 count 走 blockStarts.length，caretCount 被忽略。
        int block = CaretAnimator.trailSlotCount(true, 4, 1);
        check("K-3 块选区 4 行（caret 只有 1 个）→ 4 条拖尾",
                block == 4, "实际 " + block + "（若为 1 说明块选区未展开）");

        // ---- K-4：块选区每行的 key 互不相同（否则所有行挤在一条拖尾上）----
        //
        // 生产用 Integer.valueOf(-1000000 - lineNo)。若哪天退回「固定 key」，
        // 这一条立刻红 —— 那正是用户报的「多光标只在一行动」的形态。
        int[] lineNos = {0, 1, 2, 3};
        Object[] keys = new Object[lineNos.length];
        boolean distinct = true;
        for (int i = 0; i < lineNos.length; i++) {
            keys[i] = CaretAnimator.blockTrailKey(lineNos[i], lineNos[i] * 20);
            for (int j = 0; j < i; j++) {
                if (keys[i].equals(keys[j])) {
                    distinct = false;
                }
            }
        }
        check("K-4 块选区 4 行的 trail key 两两不同", distinct,
                java.util.Arrays.toString(keys));

        // ---- K-5：上限 64（防止全选文件时建上万条）----
        int cappedBlock = CaretAnimator.trailSlotCount(true, 200, 1);
        int cappedMulti = CaretAnimator.trailSlotCount(false, 0, 200);
        check("K-5 200 行块选区与 200 光标都截断到 " + MAX_TRAILS,
                cappedBlock == MAX_TRAILS && cappedMulti == MAX_TRAILS,
                "块选区=" + cappedBlock + " 多光标=" + cappedMulti);

        // ---- K-6：行号取不到时 key 退回 offset（不退化成空指针/负数）----
        //
        // 生产里 getLineNumber 抛异常时 lineNo 保持 -1，必须仍有可用 key，
        // 否则该行当帧没有拖尾（表现为「某一行光标不动」）。
        Object fallback = CaretAnimator.blockTrailKey(-1, 4321);
        boolean fallbackOk = fallback instanceof Integer && ((Integer) fallback) == 4321;
        check("K-6 行号取不到（-1）时 key 退回该行 offset", fallbackOk,
                "key=" + fallback);

        // ---- K-7：块选区优先于 caret（两者同时存在时的取舍）----
        //
        // 平台互斥，但判据必须明确：块选区有 N 行就画 N 条，
        // 不能因为 carets 只有 1 个就退化成 1 条。
        int both = CaretAnimator.trailSlotCount(true, 4, 3);
        check("K-7 块选区与多光标同时存在时，以块选区为准（4 而非 3）",
                both == 4, "实际 " + both);

        System.out.println();
        System.out.printf("=== V-BLOCK 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-BLOCK RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-BLOCK RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
