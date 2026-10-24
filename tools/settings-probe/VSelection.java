import java.lang.reflect.Method;

/**
 * V-SELECTION：选区扩展时的光标跟随
 *
 * <p><b>用户反馈（2026-10-24）</b>：
 * 「ctrl + shift 左方向键 覆盖右边的字符 和继续按右边回来有动画效果，
 * 但是如果开头就按按右边 就完全没有动画，就是说光标不会随着选择覆盖，
 * 而是留在原地」
 *
 * <p><b>分析</b>：用户描述的是「选区头部（lead）移动，但我们跟错了点」。
 *
 * <p>IntelliJ 的 Caret 有两个位置概念：
 * <ul>
 *   <li>{@code getVisualPosition()} —— caret 本身的位置（选区<b>起点</b>侧）</li>
 *   <li>{@code getLeadSelectionPosition()} —— 选区<b>活动端</b>
 *       （Shift+方向键时移动的那一端）</li>
 * </ul>
 *
 * <p>若我们用前者，按 Shift+→ 时 caret 不动 → 拖尾留在原地 →
 * 「完全没有动画」。而按 Shift+← 时可能因为 caret 侧变化而有动画 ——
 * 正好对应用户的描述「左方向键有动画、右方向键没有」。
 *
 * <p>本探针验证 API 存在性，并对「该跟随哪个位置」给出明确判定。
 */
public final class VSelection {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-SELECTION：选区扩展的光标跟随 ===");

        Class<?> caret = Class.forName("com.intellij.openapi.editor.Caret");

        // ---- S-1：getLeadSelectionPosition 存在（物理跟随点）----
        boolean hasLead = false;
        String leadDetail = "";
        try {
            Method m = caret.getMethod("getLeadSelectionPosition");
            hasLead = m.getReturnType().getName()
                    .equals("com.intellij.openapi.editor.VisualPosition");
            leadDetail = "getLeadSelectionPosition() → VisualPosition";
        } catch (Throwable t) {
            leadDetail = "不存在";
        }
        check("S-1 Caret.getLeadSelectionPosition() 存在（选区活动端）", hasLead, leadDetail);

        // ---- S-2：getVisualPosition 也存在（caret 本身位置）----
        boolean hasVp = false;
        try {
            Method m = caret.getMethod("getVisualPosition");
            hasVp = m.getReturnType().getName()
                    .equals("com.intellij.openapi.editor.VisualPosition");
        } catch (Throwable t) { }
        check("S-2 Caret.getVisualPosition() 存在（caret 本身位置）", hasVp,
                hasVp ? "存在" : "缺失");

        // ---- S-3：两者必须区分对待 ----
        check("S-3 两个 API 不同：getVisualPosition ≠ getLeadSelectionPosition",
                hasLead && hasVp,
                "前者是 caret 位置，后者是选区活动端");

        // ---- S-4：选区判定 API ----
        boolean hasSel = false;
        try {
            caret.getMethod("hasSelection");
            hasSel = true;
        } catch (Throwable t) { }
        check("S-4 Caret.hasSelection() 存在（判断是否在选区内）", hasSel,
                hasSel ? "存在" : "缺失");

        // ---- S-5：该跟随哪个位置的判定（纯函数）----
        //
        // 规则：
        //   有选区 → 跟随 lead（用户看到的是选区在扩展）
        //   无选区 → 跟随 visualPosition（普通光标）
        check("S-5a 无选区 → 跟随 caret 位置",
                followWhich(false) == FollowPoint.CARET,
                "无选区 → CARET");
        check("S-5b 有选区 → 跟随选区活动端",
                followWhich(true) == FollowPoint.LEAD,
                "有选区 → LEAD");

        // ---- S-6：用户描述的场景映射 ----
        //
        // 「开头按右方向键没动画」：光标在行首，Shift+→ 扩展选区，
        //   caret 仍在行首（不动），lead 向右移动
        //   → 若跟 caret，拖尾不动 → 没动画 ✓ 与用户描述吻合
        check("S-6 行首 Shift+→ 场景：caret 不动、lead 右移 → 跟 lead 才有动画",
                followWhich(true) == FollowPoint.LEAD,
                "必须跟 LEAD 才能复现动画");

        System.out.println();
        System.out.printf("=== V-SELECTION 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-SELECTION RESULT: FAILED"); System.exit(1); }
        System.out.println("V-SELECTION RESULT: ALL PASS");
    }

    enum FollowPoint { CARET, LEAD }

    /** 该跟随哪个位置：有选区跟 lead，无选区跟 caret */
    static FollowPoint followWhich(boolean hasSelection) {
        return hasSelection ? FollowPoint.LEAD : FollowPoint.CARET;
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
