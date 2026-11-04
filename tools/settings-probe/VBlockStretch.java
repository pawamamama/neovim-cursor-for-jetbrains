import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;

import java.lang.reflect.Method;

/**
 * V-BLOCK-STRETCH：块选区（多光标）的横向拉伸必须和单行一致。
 *
 * <p><b>用户报的 bug（2026-10-24）</b>：「多行缎带光标的拉伸表现和缎带单行不一致，
 * 多光标很长」。
 *
 * <p><b>根因（Step B 前）</b>：块选区的 charsMoved 用 offset 差值算，跨行时必然 &gt; typingMaxChars(3)
 * → 被判成「跨行」→ 用 animationLength(0.28) 而不是 shortAnimationLength(0.11)。
 *
 * <p><b>本探针的判据</b>：同样「行内小步移动」，
 * 块选区（charsMoved=-1 走归一化）与单行（charsMoved=1）算出的时长必须一致。
 *
 * <p><b>2026-11-04 Step B 契约变更</b>：同一行内 {@code charsMoved > typingMaxChars}
 * 不再走跨行分支，而是与短跳同源。因此 B-2 从「旧行为必须更长」改为
 * 「同行 burst 必须与单行短跳一致」—— 这是契约变更，不是缺陷。
 */
public final class VBlockStretch {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-BLOCK-STRETCH：块选区拉伸 vs 单行 ===");

        NeovideConfig cfg = new NeovideConfig();
        cfg.animationLength = 0.28f;           // 缎带预设的值
        cfg.shortAnimationLength = 0.11f;
        cfg.trailSize = 1.0f;
        cfg.typingMaxChars = 3f;

        Method jump = TrailCorner.class.getMethod("jump",
                double.class, double.class, double.class, double.class, int.class);
        Method setChars = TrailCorner.class.getMethod("setCharsMoved", int.class);

        // ---- 场景：同一行内横向移动 20px（约 2 个字符），纵向不动 ----
        double w = 8, h = 32, x0 = 100, y0 = 100, x1 = 120;

        // A) 单行：charsMoved=2（在 typingMaxChars 内）
        TrailCorner single = new TrailCorner(-0.5, -0.5, cfg);
        single.snapTo(w, h, x0, y0);
        setChars.invoke(single, 2);
        jump.invoke(single, w, h, x1, y0, 0);
        double singleLen = springLen(single);

        // B) 块选区：charsMoved=-1（走归一化兜底）
        TrailCorner block = new TrailCorner(-0.5, -0.5, cfg);
        block.snapTo(w, h, x0, y0);
        setChars.invoke(block, -1);
        jump.invoke(block, w, h, x1, y0, 0);
        double blockLen = springLen(block);

        // C) 同一行 burst：charsMoved=50（一帧合并大量击键 / Ctrl+End 等）。
        // Step B 之前它被误判成跨行，用 animationLength=0.28；修复后必须与短跳一致。
        TrailCorner burst = new TrailCorner(-0.5, -0.5, cfg);
        burst.snapTo(w, h, x0, y0);
        setChars.invoke(burst, 50);
        jump.invoke(burst, w, h, x1, y0, 0);
        double burstLen = springLen(burst);

        System.out.printf("%n  单行(charsMoved=2)      时长 = %.4f s%n", singleLen);
        System.out.printf("  块选区(修后, -1)        时长 = %.4f s%n", blockLen);
        System.out.printf("  同一行 burst(50)        时长 = %.4f s%n", burstLen);

        check("B-1 块选区（修后）与单行时长一致",
                Math.abs(blockLen - singleLen) < 0.01,
                String.format("%.4f vs %.4f", blockLen, singleLen));

        check("B-2 同一行 charsMoved=50 现在与单行短跳一致（Step B 契约变更，非缺陷）",
                Math.abs(burstLen - singleLen) < 0.01,
                String.format("同行 burst %.4f vs 单行 %.4f（Step B 前会是 animationLength=%.4f）",
                        burstLen, singleLen, cfg.animationLength));

        System.out.println();
        System.out.printf("=== V-BLOCK-STRETCH 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-BLOCK-STRETCH RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-BLOCK-STRETCH RESULT: ALL PASS");
    }

    /** 反射读 springX 的 animationLength */
    /** 反射读 DampedSpring 的私有 animationLength */
    private static double springLen(TrailCorner c) throws Exception {
        java.lang.reflect.Field sf = TrailCorner.class.getDeclaredField("springX");
        sf.setAccessible(true);
        Object spring = sf.get(c);
        java.lang.reflect.Field af = spring.getClass().getDeclaredField("animationLength");
        af.setAccessible(true);
        return ((Number) af.get(spring)).doubleValue();
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
