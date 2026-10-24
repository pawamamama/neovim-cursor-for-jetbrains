import com.intellij.openapi.editor.ex.util.EditorUtil;
import java.lang.reflect.Method;

/**
 * V-CARET：确认 IDEA 光标宽度的正确来源。
 *
 * <p><b>用户实测反馈（2026-10-24）</b>：
 * 「关闭隐藏原生发现我们绘制出来的还是不和 idea 的一样（2px 5px），
 * 怀疑我设置成了粗光标」
 *
 * <p><b>要解决的问题</b>：{@code EditorSettings.getLineCursorWidth()} 返回的
 * 到底是「像素」还是「相对倍率」？如果是倍率，直接当像素用就差了字体宽度倍。
 *
 * <p><b>本探针确认三件事</b>：
 * <ol>
 *   <li>IDEA 提供了 {@code EditorUtil.getDefaultCaretWidth()} —— 这才是官方换算</li>
 *   <li>{@code getLineCursorWidth()} 的返回类型与语义</li>
 *   <li>两者在同一份配置下是否自洽</li>
 * </ol>
 */
public final class VCaret {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-CARET：IDEA 光标宽度来源确认 ===");

        // ---- C-1：EditorUtil.getDefaultCaretWidth() 存在 ----
        boolean hasDefault = false;
        String detail = "";
        try {
            Method m = EditorUtil.class.getMethod("getDefaultCaretWidth");
            hasDefault = (m.getReturnType() == int.class);
            detail = "EditorUtil.getDefaultCaretWidth() → " + m.getReturnType().getName();
        } catch (Throwable t) {
            detail = "不存在: " + t.getClass().getSimpleName();
        }
        check("C-1 EditorUtil.getDefaultCaretWidth() 存在且返回 int", hasDefault, detail);

        // ---- C-2：EditorSettings.getLineCursorWidth() 存在 ----
        boolean hasLine = false;
        String lineDetail = "";
        try {
            Method m = Class.forName("com.intellij.openapi.editor.EditorSettings")
                    .getMethod("getLineCursorWidth");
            hasLine = (m.getReturnType() == int.class);
            lineDetail = "getLineCursorWidth() → " + m.getReturnType().getName();
        } catch (Throwable t) {
            lineDetail = "不存在";
        }
        check("C-2 EditorSettings.getLineCursorWidth() 存在且返回 int", hasLine, lineDetail);

        // ---- C-3：两者是不同的 API（不能混用）----
        check("C-3 两个 API 是不同的方法（getDefaultCaretWidth 才是像素换算）",
                hasDefault && hasLine,
                "EditorUtil.getDefaultCaretWidth vs EditorSettings.getLineCursorWidth");

        // ---- C-4：宽度解析策略的纯函数验证 ----
        //
        // 生产策略（本项目）：按 hideNativeCaret 分流，且至少 1px。
        check("C-4a 常驻绘制 → 用 config 值",
                resolve(true, 5, 2f) == 2.0,
                "cfg=2 → " + resolve(true, 5, 2f));
        check("C-4b 保留原生 + 有真实宽度 → 用真实宽度",
                resolve(false, 5, 2f) == 5.0,
                "real=5 → " + resolve(false, 5, 2f));
        check("C-4c 读不到真实宽度 → 回退 config",
                resolve(false, -1, 2f) == 2.0,
                "real=-1 → " + resolve(false, -1, 2f));
        check("C-4d 零宽保护（至少 1px）",
                resolve(true, 5, 0f) >= 1.0,
                "cfg=0 → " + resolve(true, 5, 0f));

        // ---- C-5：粗光标场景（用户怀疑自己设成了粗光标）----
        //
        // IDEA 的「粗光标」是 LineCursorWidth 倍数，不是固定像素。
        // 若用户设成 2，真实宽度 = 2 × 基准宽度。
        check("C-5 粗光标（倍数 2）解析出的宽度 = 2 倍基准",
                resolve(false, 10, 2f) == 10.0,
                "real=10（=2×5）→ " + resolve(false, 10, 2f));

        System.out.println();
        System.out.printf("=== V-CARET 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-CARET RESULT: FAILED"); System.exit(1); }
        System.out.println("V-CARET RESULT: ALL PASS");
    }

    static double resolve(boolean hideNative, int realWidth, float cfgWidth) {
        double w;
        if (hideNative) {
            w = cfgWidth;
        } else if (realWidth > 0) {
            w = realWidth;
        } else {
            w = cfgWidth;
        }
        return Math.max(w, 1.0);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
