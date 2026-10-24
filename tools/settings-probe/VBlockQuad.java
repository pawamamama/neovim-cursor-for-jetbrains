/**
 * V-BLOCKQUAD：块选区必须是【一个】大矩形，不是每行一个小竖条
 *
 * <p><b>用户反馈（2026-10-24 第三轮）</b>：
 * 「相邻行合并我说是多行统一一个矩形你明白吗，而且 6 行的话前三个走的慢后三个
 * 走得快，就是拉伸的时候列不一致也不像原版 vscode 那样是一整个斜阶梯，
 * 我们是不一致的」
 *
 * <p><b>根因</b>：块选区被展开成 N 个独立 SpringTrail（每行一个）。
 * 每行的四角弹簧状态各不相同（初始吸附时刻、maxOffset 都不同），
 * 于是：
 * <ul>
 *   <li>拉伸时各行速度不一致（前三个慢、后三个快）</li>
 *   <li>列的偏移不一致（有的行斜得多、有的少）→ 不是一条整齐的斜阶梯</li>
 *   <li>视觉上是 N 个竖条，不是一个矩形</li>
 * </ul>
 *
 * <p><b>正确做法</b>：块选区是【一个矩形区域】，
 * 应该用<b>一个</b> SpringTrail —— 四角 = 该选区的包围盒四角。
 * 拖动时整个矩形被一起拉伸成平行四边形，与 VS Code 原版一致。
 *
 * <p>本探针验证「块选区 → 1 个四边形」这个契约。
 */
public final class VBlockQuad {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-BLOCKQUAD：块选区应为一个矩形 ===");

        Class<?> cls = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator");

        // ---- B-1：API 存在性 ----
        boolean hasApi = false;
        try {
            cls.getDeclaredMethod("blockSelectionBounds", int[].class, int[].class, double.class, double.class);
            hasApi = true;
        } catch (NoSuchMethodException e) { }
        check("B-1 blockSelectionBounds(int[], int[], double, double) 存在", hasApi,
                hasApi ? "已实现" : "缺失");

        if (!hasApi) {
            finish();
            return;
        }

        // ---- B-2：4 行选区 → 1 个矩形（不是 4 个）----
        //
        // 模拟：每行起点 x=100、宽 2、行高 10，共 4 行
        int[] starts = {0, 20, 40, 60};
        int[] ends = {2, 22, 42, 62};
        double[] box = (double[]) invokeStatic(cls, "blockSelectionBounds",
                new Class[]{int[].class, int[].class, double.class, double.class}, starts, ends,
                /* charWidth */ 10.0, /* lineHeight */ 10.0);
        check("B-2 4 行选区 → 返回 1 个矩形（4 个数值，不是 N 组）",
                box != null && box.length == 4,
                box == null ? "null" : "长度=" + box.length);

        // ---- B-3：矩形覆盖所有行（高度 = 行数 × 行高）----
        if (box != null) {
            double h = box[3] - box[1];
            check("B-3 矩形高度 = 4 行 × 行高 10 = 40", Math.abs(h - 40) < 0.01,
                    String.format("实际 %.1f", h));
        }

        // ---- B-4：矩形宽度由选区列跨度决定（不是固定光标宽）----
        if (box != null) {
            double w = box[2] - box[0];
            check("B-4 矩形宽度覆盖选区（> 单个字符宽）", w > 10,
                    String.format("宽 %.1f", w));
        }

        // ---- B-5：单行块选区退化为普通光标尺寸 ----
        double[] single = (double[]) invokeStatic(cls, "blockSelectionBounds",
                new Class[]{int[].class, int[].class, double.class, double.class},
                new int[]{0}, new int[]{2}, 10.0, 10.0);
        check("B-5 单行选区 → 高度仅 1 行高", single != null
                        && Math.abs((single[3] - single[1]) - 10) < 0.01,
                single == null ? "null" : String.format("高 %.1f", single[3] - single[1]));

        // ---- B-6：空输入 → null（调用方回退到普通光标）----
        double[] empty = (double[]) invokeStatic(cls, "blockSelectionBounds",
                new Class[]{int[].class, int[].class, double.class, double.class},
                new int[0], new int[0], 10.0, 10.0);
        check("B-6 空输入 → null（回退普通光标，不崩）", empty == null,
                empty == null ? "null" : "非 null");

        finish();
    }

    private static Object invokeStatic(Class<?> cls, String name,
                                       Class<?>[] sig, Object... args) throws Exception {
        var m = cls.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static void finish() {
        System.out.println();
        System.out.printf("=== V-BLOCKQUAD 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("V-BLOCKQUAD RESULT: FAILED"); System.exit(1); }
        System.out.println("V-BLOCKQUAD RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
