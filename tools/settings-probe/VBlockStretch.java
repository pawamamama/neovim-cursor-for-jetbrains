import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;

import java.lang.reflect.Method;

/**
 * V-BLOCK-STRETCH：块选区（多光标）的横向拉伸必须和单行一致。
 *
 * <p><b>用户报的 bug（2026-10-24）</b>：「多行缎带光标的拉伸表现和缎带单行不一致，
 * 多光标很长」。
 *
 * <p><b>根因</b>：块选区的 charsMoved 用 offset 差值算，跨行时必然 &gt; typingMaxChars(3)
 * → 被判成「跨行」→ 用 animationLength(0.28) 而不是 shortAnimationLength(0.11)。
 *
 * <p><b>本探针的判据</b>：同样「行内小步移动」，
 * 块选区（charsMoved=-1 走归一化）与单行（charsMoved=1）算出的时长必须一致。
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

        // C) 旧行为：块选区误用大 charsMoved（模拟跨行 offset 差）
        TrailCorner oldBad = new TrailCorner(-0.5, -0.5, cfg);
        oldBad.snapTo(w, h, x0, y0);
        setChars.invoke(oldBad, 50);            // offset 差 50 字符
        jump.invoke(oldBad, w, h, x1, y0, 0);
        double oldLen = springLen(oldBad);

        System.out.printf("%n  单行(charsMoved=2)      时长 = %.4f s%n", singleLen);
        System.out.printf("  块选区(修后, -1)        时长 = %.4f s%n", blockLen);
        System.out.printf("  块选区(修前, 50)        时长 = %.4f s%n", oldLen);

        check("B-1 块选区（修后）与单行时长一致",
                Math.abs(blockLen - singleLen) < 0.01,
                String.format("%.4f vs %.4f", blockLen, singleLen));

        check("B-2 修前确实用了更长的 animationLength（复现 bug）",
                oldLen > singleLen * 2,
                String.format("修前 %.4f，是单行的 %.1f 倍", oldLen, oldLen / singleLen));

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
