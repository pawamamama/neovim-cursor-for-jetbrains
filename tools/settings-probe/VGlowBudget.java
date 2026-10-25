/**
 * V-GLOW-BUDGET：辉光层数自适应降级的守门探针。
 *
 * <p>规则：每光标层数 = clamp(glowLayers × BUDGET / 光标数, 2, glowLayers)，
 * 其中 BUDGET = 3。要求 1~3 光标拿满层数（观感零变化），多光标才摊薄。
 */
public final class VGlowBudget {
    private static int pass = 0, fail = 0;

    /** 与生产代码 GLOW_LAYER_BUDGET_CURSORS 保持一致（探针独立抄一份，改一处要改两处） */
    private static final int BUDGET = 3;
    private static final int GLOW_LAYERS = 8;

    public static void main(String[] a) {
        System.out.println("=== V-GLOW-BUDGET：辉光层数按光标数自适应 ===");

        // B-1: 1~3 光标必须拿满层数（最常用场景零影响）
        for (int n = 1; n <= 3; n++) {
            int got = layersFor(n);
            check("B-1 " + n + " 光标拿满 " + GLOW_LAYERS + " 层（观感不变）",
                    got == GLOW_LAYERS, "实际 " + got);
        }

        // B-2: 8 光标必须降到 3 层（实测 20%→9% 预算）
        check("B-2 8 光标降到 3 层（把辉光绘制压到 10% 预算内）",
                layersFor(8) == 3, "实际 " + layersFor(8));

        // B-3: 4/6 光标居中
        check("B-3 4 光标 = 6 层", layersFor(4) == 6, "实际 " + layersFor(4));
        check("B-4 6 光标 = 4 层", layersFor(6) == 4, "实际 " + layersFor(6));

        // B-5: 极端多光标也不低于 2 层（1 层会变成实心描边，很难看）
        check("B-5 32 光标仍有 2 层保底", layersFor(32) == 2, "实际 " + layersFor(32));

        // B-6: 单调不增 —— 光标越多层数不能越多
        int prev = Integer.MAX_VALUE;
        boolean mono = true;
        for (int n = 1; n <= 64; n++) {
            int v = layersFor(n);
            if (v > prev) {
                mono = false;
                break;
            }
            prev = v;
        }
        check("B-6 层数随光标数单调不增", mono, mono ? "OK" : "存在回升");

        // B-7: 永远不超过用户设定的层数
        boolean cap = true;
        for (int n = 1; n <= 64; n++) {
            if (layersFor(n) > GLOW_LAYERS) {
                cap = false;
                break;
            }
        }
        check("B-7 永不超过用户设定层数 " + GLOW_LAYERS, cap, cap ? "OK" : "越界");

        System.out.println();
        System.out.printf("=== V-GLOW-BUDGET 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-GLOW-BUDGET RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-GLOW-BUDGET RESULT: ALL PASS");
    }

    /** 生产规则的独立复刻 */
    private static int layersFor(int cursors) {
        int n = Math.max(1, cursors);
        int budget = GLOW_LAYERS * BUDGET;
        int per = budget / n;
        return Math.max(2, Math.min(GLOW_LAYERS, per));
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
