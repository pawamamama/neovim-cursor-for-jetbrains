import java.awt.LinearGradientPaint;
import java.awt.Color;

/** 验证 buildRgbWashPaint 的色标与坐标是否满足 LinearGradientPaint 硬约束。 */
public final class ZzGradTest {
    public static void main(String[] a) {
        for (double phase : new double[]{0.0, 0.25, 0.5, 0.75, 0.999}) {
            final int steps = 16;
            float[] fr = new float[steps + 1];
            Color[] col = new Color[steps + 1];
            for (int i = 0; i <= steps; i++) {
                float t = i / (float) steps;
                fr[i] = t;
                double hue = (phase + t * 2.0) % 1.0;
                if (hue < 0) hue += 1.0;
                Color c = Color.getHSBColor((float) hue, 0.75f, 0.95f);
                col[i] = new Color(c.getRed(), c.getGreen(), c.getBlue(), 200);
            }
            try {
                new LinearGradientPaint(0f, 0f, 0f, 40f, fr, col);
                System.out.printf("  phase=%.3f  OK%n", phase);
            } catch (Throwable t) {
                System.out.printf("  phase=%.3f  FAIL %s%n", phase, t);
            }
        }
        try {
            new LinearGradientPaint(0f, 10f, 0f, 10f,
                    new float[]{0f, 1f},
                    new Color[]{Color.RED, Color.BLUE});
            System.out.println("  退化(起终点相同)  OK");
        } catch (Throwable t) {
            System.out.println("  退化(起终点相同)  FAIL " + t);
        }
        try {
            new LinearGradientPaint(0f, Float.NaN, 0f, 40f,
                    new float[]{0f, 1f}, new Color[]{Color.RED, Color.BLUE});
            System.out.println("  NaN 起点          OK");
        } catch (Throwable t) {
            System.out.println("  NaN 起点          FAIL " + t);
        }
        try {
            new LinearGradientPaint(0f, 1e9f, 0f, 1e9f + 40f,
                    new float[]{0f, 1f}, new Color[]{Color.RED, Color.BLUE});
            System.out.println("  极大坐标          OK");
        } catch (Throwable t) {
            System.out.println("  极大坐标          FAIL " + t);
        }
    }
}