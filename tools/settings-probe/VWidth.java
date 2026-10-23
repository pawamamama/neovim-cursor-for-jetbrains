import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;

/**
 * V-WIDTH：光标宽度对齐（P0）
 *
 * <p><b>要解决的问题</b>：`NeovideConfig.cursorWidth` 默认 2px 是写死的，
 * 而 IDEA 原生光标宽度由 `EditorSettings.getLineCursorWidth()` 决定（实测约 5px）。
 *
 * <p>宽度不一致的后果（用户实测反馈"两个光标"）：
 * <ul>
 *   <li>`hideNativeCaret=false`（保留原生）时，原生 5px + 覆盖层 2px 叠加
 *       → 两条边都露出来，看起来像两个光标</li>
 *   <li>`hideNativeCaret=true`（常驻绘制）时，覆盖层比用户惯用光标细
 *       → 显得"光标变窄了"</li>
 * </ul>
 *
 * <p><b>本探针验证的是生产代码里的决策函数</b>（headless 环境拿不到真实 Editor）：
 * `com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.resolveCursorWidth(...)` 是生产代码的一部分，探针直接调用它，
 * <b>不持有任何逻辑副本</b> —— 否则就是"测试自己"。
 *
 * <p>真实的 `getLineCursorWidth()` 读取留在 `CaretAnimator`，由 W-5 的反射检查保证 API 存在。
 *
 * <p>真实的 `getLineCursorWidth()` 读取留给实现层，由 `W-5` 的反射检查保证存在。
 */
public final class VWidth {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-WIDTH：光标宽度对齐 ===");

        // ---- W-1：保留原生光标时，用真实宽度 ----
        check("W-1 保留原生光标（hideNativeCaret=false）→ 用 IDEA 真实宽度",
                com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.resolveCursorWidth(false, 5, 2f) == 5.0,
                "real=5, cfg=2 → " + com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.resolveCursorWidth(false, 5, 2f));

        // ---- W-2：常驻绘制时，用配置值 ----
        check("W-2 常驻绘制（hideNativeCaret=true）→ 用 config.cursorWidth",
                com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.resolveCursorWidth(true, 5, 2f) == 2.0,
                "real=5, cfg=2 → " + com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.resolveCursorWidth(true, 5, 2f));

        // ---- W-3：真实宽度读不到时（0 或负）回退配置值 ----
        check("W-3 real=0（读取失败）→ 回退 config 值，不崩",
                com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.resolveCursorWidth(false, 0, 2f) == 2.0,
                "real=0, cfg=2 → " + com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.resolveCursorWidth(false, 0, 2f));
        check("W-3b real=-1（异常值）→ 回退 config 值",
                com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.resolveCursorWidth(false, -1, 2f) == 2.0,
                "real=-1, cfg=2 → " + com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.resolveCursorWidth(false, -1, 2f));

        // ---- W-4：配置值本身异常时的兜底 ----
        check("W-4 cfg=0（用户误设）→ 至少返回 1，不产生零宽四边形",
                com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.resolveCursorWidth(true, 5, 0f) >= 1.0,
                "cfg=0 → " + com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.resolveCursorWidth(true, 5, 0f));

        // ---- W-5：确认 IDEA API 真实存在（反射）----
        // headless 下不能 new Editor，但可以确认方法签名在 classpath 里
        boolean apiExists = false;
        String apiDetail;
        try {
            Class<?> settings = Class.forName("com.intellij.openapi.editor.EditorSettings");
            java.lang.reflect.Method m = settings.getMethod("getLineCursorWidth");
            apiExists = (m.getReturnType() == int.class);
            apiDetail = "EditorSettings.getLineCursorWidth() → int";
        } catch (Throwable t) {
            apiDetail = "反射失败: " + t.getClass().getSimpleName();
        }
        check("W-5 EditorSettings.getLineCursorWidth() 存在且返回 int",
                apiExists, apiDetail);

        // ---- W-6：三种策略组合的矩阵 ----
        // hideNativeCaret=false 时，无论 cfg 是多少，都必须跟随真实宽度
        boolean matrixOk = true;
        StringBuilder sb = new StringBuilder();
        int[] reals = {3, 4, 5, 6};
        float[] cfgs = {1f, 2f, 3f};
        for (int real : reals) {
            for (float cfg : cfgs) {
                double w = com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator.resolveCursorWidth(false, real, cfg);
                if (w != real) { matrixOk = false; sb.append("real=").append(real)
                        .append(",cfg=").append(cfg).append("→").append(w).append(" "); }
            }
        }
        check("W-6 保留原生时宽度恒等于真实值（12 种组合）",
                matrixOk, matrixOk ? "12/12 一致" : "不一致: " + sb);

        System.out.println();
        System.out.printf("=== V-WIDTH 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-WIDTH RESULT: FAILED"); System.exit(1); }
        System.out.println("V-WIDTH RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
