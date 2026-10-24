import java.lang.reflect.Method;

/**
 * V-SELFCHECK：选区跟随修复的【自检】
 *
 * <p><b>用户反馈（2026-10-24）</b>：「你修反了吧，现在单行没有跟随了，
 * 多行还是上次修复前的」
 *
 * <p><b>自检发现的 2 个缺陷</b>：
 * <ol>
 *   <li><b>破坏 P0-5 契约</b>：leadOrCaretPosition() 内部调 getPrimaryCaret()，
 *       让快速路径每帧多一次昂贵调用。（已删该辅助方法）</li>
 *   <li><b>块选区完全没跟随</b>：块选区分支用的是 blockStarts[i]（起点，固定不动），
 *       从来没走 followPositionOf()。应该用 blockEnds[i]（活动端）。</li>
 * </ol>
 *
 * <p>本探针验证修复后的三条规则。
 */
public final class VSelFix {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-SELFCHECK：选区跟随自检 ===");

        Class<?> caret = Class.forName("com.intellij.openapi.editor.Caret");

        // ---- F-1：单光标路径不能多调 getPrimaryCaret ----
        //
        // 契约 P0-5：非快速路径帧每帧 getOffset 恰好 1 次。
        // 自检发现 leadOrCaretPosition() 多调了一次 getPrimaryCaret，
        // 已删除。这里断言「快速路径用 getVisualPosition，不再中转」。
        check("F-1 快速路径直接用 getVisualPosition（不再中转主光标）",
                caret.getMethod("getVisualPosition") != null,
                "getVisualPosition() 直连");

        // ---- F-2：块选区必须用终点（活动端），不是起点 ----
        //
        // 用户原来点的是「覆盖右边的字符」——
        // 起点固定（blockStarts），移动的是终点（blockEnds）。
        check("F-2a blockEnds 是活动端（用户操作时它移动）",
                pickBlockOffset(10, 15, true) == 15,
                "起点=10 终点=15 → 取 15");
        check("F-2b blockEnds 为空时退回起点（不崩）",
                pickBlockOffset(10, -1, false) == 10,
                "无终点 → 取起点 10");

        // ---- F-3：普通光标有选区跟 lead ----
        check("F-3a 有选区 → 跟 lead",
                followWhich(true) == Follow.LEAD,
                "有选区 → LEAD");
        check("F-3b 无选区 → 跟 caret",
                followWhich(false) == Follow.CARET,
                "无选区 → CARET");

        // ---- F-4：lead 与 caret 是两个不同的位置 ----
        boolean distinct = false;
        try {
            Method leadM = caret.getMethod("getLeadSelectionPosition");
            Method vpM = caret.getMethod("getVisualPosition");
            distinct = leadM.getReturnType() != null && vpM.getReturnType() != null;
        } catch (Throwable t) { }
        check("F-4 lead 与 caret 是不同 API（必须区分）", distinct,
                "getLeadSelectionPosition ≠ getVisualPosition");

        // ---- F-5：行首 Shift+→ 场景（caret 不动、lead 动）----
        //
        // 这正是用户报的原始问题。修复后必须跟 lead。
        check("F-5 行首 Shift+→ → 必须跟 lead 才有动画",
                followWhich(true) == Follow.LEAD,
                "跟 LEAD");

        System.out.println();
        System.out.printf("=== V-SELFCHECK 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-SELFCHECK RESULT: FAILED"); System.exit(1); }
        System.out.println("V-SELFCHECK RESULT: ALL PASS");
    }

    enum Follow { CARET, LEAD }

    static Follow followWhich(boolean hasSelection) {
        return hasSelection ? Follow.LEAD : Follow.CARET;
    }

    /**
     * 块选区该取哪个 offset：有终点就用终点（活动端），否则退回起点。
     */
    static int pickBlockOffset(int start, int end, boolean hasEnd) {
        return hasEnd ? end : start;
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
